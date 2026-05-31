package com.acltabontabon.launchpad.task;

import com.acltabontabon.launchpad.ai.PromptSelector;
import com.acltabontabon.launchpad.config.LaunchpadTaskProperties;
import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.scanner.StackProfile;
import com.acltabontabon.launchpad.standards.Checklist;
import com.acltabontabon.launchpad.standards.ChecklistItem;
import com.acltabontabon.launchpad.standards.Rule;
import com.acltabontabon.launchpad.standards.Scope;
import com.acltabontabon.launchpad.standards.Skill;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Drives the /new-task interview. Two LLM-backed operations:
 *   - askNextQuestion: returns the next clarifying question, or the literal token
 *     "__DONE__" when the model judges there's nothing high-value left to ask.
 *     Standards-driven: the LLM picks the next applicable rule / skill / checklist
 *     item and probes it. Project scan is intentionally NOT passed in - it just
 *     gives the model material to hallucinate around. Only the stack one-liner
 *     goes in so questions are framework-appropriate. Standards are expected to
 *     be pre-filtered by scope before reaching this method (see
 *     {@link #selectRelevantStandards}) so per-turn dispatch can ship a focused
 *     menu instead of the full pack.
 *   - synthesise: produces the refined prompt (markdown) from the transcript +
 *     codebase context + applicable engineering standards. Real per-chunk
 *     streaming so a stalled model is caught by the read-timeout, with
 *     validator + single retry guarding the output that lands on disk.
 * Calls block on the streaming response - callers should run them on a
 * background thread (CompletableFuture.runAsync) so the TUI stays responsive.
 */
@Service
public class TaskAdvisorService {

    public static final String DONE_TOKEN = "__DONE__";
    /** Critic verdict that means "the brief is substantial enough; proceed to
     *  synthesise". Returned by {@link #critiqueReadiness} when no follow-up is
     *  warranted. Any other return value is the critic's follow-up question. */
    public static final String READY_TOKEN = "__OK__";
    /** Cap on rules embedded in the Constraints section. Higher counts overwhelm
     *  the implementing agent and the prompt context window. Overflow rules are
     *  cited by id in a footer line so they remain discoverable. */
    private static final int MAX_CONSTRAINTS = 10;

    private static final String SYSTEM_MARKER = "===SYSTEM===";
    private static final String USER_MARKER = "===USER===";

    private static final String FINALIZE_RETRY_REMINDER = """

        FORMAT VIOLATION: your previous response did not produce the three
        marker-delimited sections. Output ONLY:
        ===GOAL===
        <one prose paragraph>
        ===ACCEPTANCE===
        - <bullet>
        ===OUT_OF_SCOPE===
        - <bullet> or (none)
        Use ONLY the user's words. Nothing else.
        """;

    private final ChatClient chatClient;
    private final PromptSelector promptSelector;
    private final TaskOutputValidator validator = new TaskOutputValidator();
    private final Duration interviewTimeout;
    private final Duration finalizeTimeout;

    public TaskAdvisorService(
        ChatClient.Builder builder,
        PromptSelector promptSelector,
        LaunchpadTaskProperties taskProperties
    ) {
        this.chatClient = builder.build();
        this.promptSelector = promptSelector;
        this.interviewTimeout = taskProperties.interviewTimeout();
        this.finalizeTimeout = taskProperties.finalizeTimeout();
    }

    /**
     * Splits a prompt template on the {@code ===SYSTEM===} / {@code ===USER===}
     * markers so the same .txt file can carry both messages. Falls back to
     * treating the whole template as the user message if either marker is
     * missing - keeps tests and ad-hoc templates working without a system block.
     */
    record PromptParts(String system, String user) {
        static PromptParts split(String template) {
            if (template == null) return new PromptParts("", "");
            int sysIdx = template.indexOf(SYSTEM_MARKER);
            int userIdx = template.indexOf(USER_MARKER);
            if (sysIdx < 0 || userIdx < 0 || userIdx < sysIdx) {
                return new PromptParts("", template);
            }
            var system = template.substring(sysIdx + SYSTEM_MARKER.length(), userIdx).strip();
            var user = template.substring(userIdx + USER_MARKER.length()).strip();
            return new PromptParts(system, user);
        }
    }

    /** Holder for the three filtered standards lists passed across the
     *  pipeline. Computed once at TASK_INTERVIEW entry and reused for every
     *  interview turn plus the final synthesise call. */
    public record RelevantStandards(List<Rule> rules, List<Skill> skills, List<Checklist> checklists) {}

    /**
     * Apply scope + opt-out filtering once. Called by LaunchpadRunner when the
     * user lands on TASK_INTERVIEW (and again from synthesise to re-derive after
     * the interview may have surfaced new opt-outs). Pure function; safe to call
     * on a background thread.
     */
    public static RelevantStandards selectRelevantStandards(
        StackProfile stack,
        String taskDescription,
        List<TaskTurn> history,
        List<Rule> allRules,
        List<Skill> allSkills,
        List<Checklist> allChecklists
    ) {
        var taskTags = classifyTaskTags(taskDescription, history);
        var optedOutRuleIds = detectOptedOutRules(allRules, history);
        var optedOutTags = detectOptedOutTags(history);

        var rules = (allRules == null ? List.<Rule>of() : allRules).stream()
            .filter(r -> scopeApplies(r.scope(), stack, taskTags))
            .filter(r -> !optedOutRuleIds.contains(r.id()))
            .filter(r -> !overlapsTags(r.scope(), optedOutTags))
            .toList();
        var skills = (allSkills == null ? List.<Skill>of() : allSkills).stream()
            .filter(s -> scopeApplies(s.scope(), stack, taskTags))
            .filter(s -> !overlapsTags(s.scope(), optedOutTags))
            .toList();
        var checklists = (allChecklists == null ? List.<Checklist>of() : allChecklists).stream()
            .filter(c -> scopeApplies(c.scope(), stack, taskTags))
            .filter(c -> !overlapsTags(c.scope(), optedOutTags))
            .toList();
        return new RelevantStandards(rules, skills, checklists);
    }

    public String askNextQuestion(
        StackProfile stack,
        String taskDescription,
        List<TaskTurn> history,
        List<Rule> rules,
        List<Skill> skills,
        List<Checklist> checklists
    ) {
        var template = promptSelector.load(PromptSelector.Kind.TASK_INTERVIEW, stack);
        var parts = PromptParts.split(template);
        // Interview gets COMPACT one-line-per-item menus, not full descriptions.
        // Small local models lose the thread when handed 60+ rules with full
        // rationales - they start asking *about* the standards instead of *from*
        // them. Full text is reserved for the synthesise step.
        var response = chatClient.prompt()
            .system(parts.system())
            .user(u -> u.text(parts.user())
                .param("stack", formatStack(stack))
                .param("task", taskDescription)
                .param("history", formatHistory(history))
                .param("discovery_hint", discoveryHintFor(taskDescription, history))
                .param("rules", formatRulesCompact(rules))
                .param("skills", formatSkillsCompact(skills))
                .param("checklists", formatChecklistsCompact(checklists)))
            .call()
            .content();
        var extracted = extractQuestion(response);
        if (extracted.equals(DONE_TOKEN)) {
            // Override early DONE: small local models routinely return __DONE__
            // after a single round even when applicable [must]-severity rules
            // remain unaddressed. If we have uncovered must-rules and the
            // interview hasn't reached at least 3 rounds, synthesise a
            // standards-driven question deterministically from the next
            // uncovered rule instead of accepting DONE.
            if (history.size() < 3) {
                var taskTags = classifyTaskTags(taskDescription, history);
                var optedOut = detectOptedOutRules(rules, history);
                var forced = pickNextUncoveredMustRule(history, rules, stack, taskTags, optedOut);
                if (forced != null) return synthesizeStandardsQuestion(forced);
            }
            return DONE_TOKEN;
        }
        // Defensive: small local models routinely re-ask the same question in
        // slightly different wording even after the user answered it. If the new
        // question overlaps heavily with anything in history, treat it as the
        // model having nothing new to ask and finalize.
        if (isNearDuplicateOfPrior(extracted, history)) return DONE_TOKEN;
        return extracted;
    }

    /**
     * Second-opinion critic over the Q&amp;A transcript. Runs after the interview
     * model emits {@link #DONE_TOKEN} to judge whether the brief is actually
     * substantial enough to synthesise, or whether one more follow-up would
     * meaningfully improve it.
     * <p>
     * Returns {@link #READY_TOKEN} when the critic agrees the brief is ready;
     * otherwise returns one focused follow-up question (one sentence ending in
     * `?`). Near-duplicate questions of anything already in history collapse to
     * {@code READY_TOKEN} so the loop cannot oscillate on the same gap.
     * <p>
     * Same compact standards menus as {@link #askNextQuestion} - the critic
     * needs to see what is already covered. Uses the interview timeout (the
     * call is the same scale).
     */
    public String critiqueReadiness(
        StackProfile stack,
        String taskDescription,
        List<TaskTurn> history,
        List<Rule> rules,
        List<Skill> skills,
        List<Checklist> checklists
    ) {
        var template = promptSelector.load(PromptSelector.Kind.TASK_CRITIQUE, stack);
        var parts = PromptParts.split(template);
        var response = chatClient.prompt()
            .system(parts.system())
            .user(u -> u.text(parts.user())
                .param("stack", formatStack(stack))
                .param("task", taskDescription)
                .param("history", formatHistory(history))
                .param("rules", formatRulesCompact(rules))
                .param("skills", formatSkillsCompact(skills))
                .param("checklists", formatChecklistsCompact(checklists)))
            .call()
            .content();
        return interpretCritiqueResponse(response, history);
    }

    /**
     * Pure decision function for the critic's raw output. Visible for testing
     * without mocking the chat client. Treats empty / {@code __OK__} / a
     * {@link #DONE_TOKEN} mention / a near-duplicate of any prior question all
     * as "ready". Anything else is interpreted as a follow-up question via the
     * same {@link #extractQuestion} parse used by the interview path.
     */
    static String interpretCritiqueResponse(String raw, List<TaskTurn> history) {
        if (raw == null) return READY_TOKEN;
        var stripped = raw.strip();
        if (stripped.isEmpty()) return READY_TOKEN;
        if (stripped.contains(READY_TOKEN)) return READY_TOKEN;
        if (stripped.contains(DONE_TOKEN)) return READY_TOKEN;
        var question = extractQuestion(raw);
        if (question.equals(DONE_TOKEN)) return READY_TOKEN;
        if (isNearDuplicateOfPrior(question, history)) return READY_TOKEN;
        return question;
    }

    /**
     * Stack-aware DISCOVERY hint: instead of a fixed REST-shaped noun list, the
     * Java side composes the under-specified aspects most likely relevant given
     * the task wording. A bug-fix task hears about symptoms and reproduction; a
     * REST task hears about resource and callers; an unclassified task gets a
     * neutral fallback. The prompt picks one of these to probe first.
     */
    static String discoveryHintFor(String taskDescription, List<TaskTurn> history) {
        var tags = classifyTaskTags(taskDescription, history);
        if (tags.contains("debugging")) {
            return "symptom, reproduction steps, root-cause hypothesis, expected behaviour, affected scope";
        }
        if (tags.contains("refactoring")) {
            return "target module, motivation, behaviour-preservation criteria, scope boundary";
        }
        if (tags.contains("rest")) {
            return "resource, HTTP method, inputs, outputs, callers, success criteria, error shape";
        }
        if (tags.contains("ui")) {
            return "screen / view, user action, inputs, success / failure state, navigation context";
        }
        if (tags.contains("data")) {
            return "schema target, migration safety, query path, expected row counts, rollback plan";
        }
        if (tags.contains("ai")) {
            return "model role, prompt inputs, expected output shape, failure handling, observability";
        }
        if (tags.contains("configuration")) {
            return "setting name, default, source of truth, who can change it at runtime";
        }
        return "purpose, primary inputs, primary outputs, callers / triggers, success criteria";
    }

    /**
     * Finds the next applicable [must]-severity rule whose title words don't
     * overlap with anything asked in the interview so far. "Covered" = 2+
     * significant title words appear in some prior question. Returns null if all
     * applicable must-rules are already covered.
     */
    static Rule pickNextUncoveredMustRule(
        List<TaskTurn> history,
        List<Rule> rules,
        StackProfile stack,
        Set<String> taskTags,
        Set<String> optedOutIds
    ) {
        if (rules == null || rules.isEmpty()) return null;
        var coveredWords = new HashSet<String>();
        if (history != null) {
            for (var turn : history) {
                if (turn != null && turn.question() != null) {
                    coveredWords.addAll(significantWords(turn.question()));
                }
            }
        }
        return rules.stream()
            .filter(r -> "must".equalsIgnoreCase(r.severity()))
            .filter(r -> optedOutIds == null || !optedOutIds.contains(r.id()))
            .filter(r -> scopeApplies(r.scope(), stack, taskTags))
            .filter(r -> {
                var titleWords = significantWords(r.title() == null ? "" : r.title());
                if (titleWords.isEmpty()) return false;
                long covered = titleWords.stream().filter(coveredWords::contains).count();
                return covered < Math.min(2, titleWords.size());
            })
            .findFirst()
            .orElse(null);
    }

    /** Deterministic question wrapper around a rule title. */
    static String synthesizeStandardsQuestion(Rule rule) {
        var title = rule.title() == null ? rule.id() : rule.title();
        return "Regarding the team's \"" + title
            + "\" standard - how should this apply to your task (or do you want to skip it)?";
    }

    private static String formatStack(StackProfile stack) {
        if (stack == null) return "(unknown stack)";
        var name = stack.displayName();
        return name == null || name.isBlank() ? "(unknown stack)" : name;
    }

    /**
     * Defensive parse. Local models often ignore the prompt's "output ONLY the
     * question" rule and emit preamble like "Sure! Let me ask: ...". We pick the
     * actual question out of whatever they returned:
     *   1. If response is empty or contains __DONE__, finalize.
     *   2. Otherwise prefer the LAST line ending in '?' - that's almost always
     *      the real question, regardless of how much preamble preceded it.
     *   3. Fallback to the last non-empty line.
     * Visible for testing.
     */
    static boolean isNearDuplicateOfPrior(String newQuestion, List<TaskTurn> history) {
        if (history == null || history.isEmpty()) return false;
        var newWords = significantWords(newQuestion);
        if (newWords.size() < 3) return false;  // too short to compare meaningfully
        for (var turn : history) {
            var priorWords = significantWords(turn.question());
            if (priorWords.isEmpty()) continue;
            int matches = stemTolerantOverlap(newWords, priorWords);
            // Jaccard: catches substantial mutual overlap.
            double jaccard = (double) matches / (newWords.size() + priorWords.size() - matches);
            if (jaccard >= 0.5) return true;
            // Containment: catches the case where the new question contains the
            // prior question verbatim plus extra words (model re-asking the same
            // thing with a clarifying tail). Jaccard misses this when the prior
            // is much shorter than the new question.
            double containment = (double) matches / Math.min(newWords.size(), priorWords.size());
            if (containment >= 0.7) return true;
        }
        return false;
    }

    private static int stemTolerantOverlap(Set<String> a, Set<String> b) {
        int matches = 0;
        for (var x : a) {
            for (var y : b) {
                if (wordMatches(x, y)) { matches++; break; }
            }
        }
        return matches;
    }

    private static boolean wordMatches(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() >= 4 && b.length() >= 4) {
            // Tolerate trailing s/es/ed/ing variants by prefix-overlap of >=4 chars.
            if (b.startsWith(a)) return true;
            if (a.startsWith(b)) return true;
        }
        return false;
    }

    private static final Set<String> STOPWORDS = Set.of(
        "a", "an", "the", "is", "are", "be", "should", "would", "could", "this",
        "that", "for", "of", "to", "or", "and", "in", "on", "at", "with", "use",
        "using", "have", "has", "do", "does", "did", "will", "your", "you", "any",
        "new", "what", "which", "how", "why", "when", "where", "it", "its", "by",
        "from", "as", "if", "then", "than", "so", "but", "not", "no", "yes"
    );

    private static Set<String> significantWords(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
            .filter(w -> !w.isBlank() && w.length() > 2 && !STOPWORDS.contains(w))
            .collect(Collectors.toSet());
    }

    static String extractQuestion(String raw) {
        if (raw == null) return DONE_TOKEN;
        var stripped = raw.strip();
        if (stripped.isEmpty()) return DONE_TOKEN;
        if (stripped.contains(DONE_TOKEN)) return DONE_TOKEN;

        var lines = stripped.split("\\R");
        String lastQuestion = null;
        String lastNonEmpty = null;
        for (var line : lines) {
            var l = line.strip();
            if (l.isEmpty()) continue;
            lastNonEmpty = l;
            if (l.endsWith("?")) lastQuestion = l;
        }
        var picked = lastQuestion != null ? lastQuestion : (lastNonEmpty != null ? lastNonEmpty : DONE_TOKEN);
        // Strip Q-numbering prefixes the model picks up from the history format
        // ("Q1:", "Q3.", "1.", "**Q2:**", etc.). The history we feed in is
        // labeled Q1/A1/Q2/A2 and models leak that label into their own output.
        return picked.replaceFirst("^\\**\\s*(Q\\d+|\\d+)\\s*[:.\\)]\\s*\\**\\s*", "");
    }

    /** Result of a synthesise call - the assembled markdown plus any validator warnings. */
    public record SynthesiseResult(String markdown, List<String> warnings) {}

    /**
     * Hybrid synthesise: the LLM only produces the three sections that genuinely
     * need natural-language synthesis (Goal prose, Acceptance criteria, Out of
     * scope). Java deterministically assembles Constraints from the filtered
     * rules and Standards consulted from filtered skills / checklists, so the
     * model has no opportunity to hallucinate constraints or drop placeholders
     * into the output. Streams the model response with a per-chunk timeout so a
     * stalled daemon surfaces fast; emits the assembled doc through {@code onChunk}
     * once parsing + grounding + assembly complete. On validator failure, retries
     * once with a stricter reminder.
     */
    public SynthesiseResult synthesise(
        ProjectContext ctx,
        String taskDescription,
        List<TaskTurn> history,
        List<Rule> rules,
        List<Skill> skills,
        List<Checklist> checklists,
        Consumer<String> onChunk
    ) {
        // Standards are expected pre-filtered by the caller. Re-filtering here is
        // cheap and protects against callers that pass an unfiltered pack
        // (legacy / test setups). selectRelevantStandards is idempotent: a list
        // that already matches the scope passes through unchanged.
        var filtered = selectRelevantStandards(
            ctx == null ? null : ctx.stack(), taskDescription, history, rules, skills, checklists);

        var template = promptSelector.load(PromptSelector.Kind.TASK_FINALIZE, ctx == null ? null : ctx.stack());
        var parts = PromptParts.split(template);
        if (onChunk != null) onChunk.accept("");  // signal: synthesis starting

        var raw = streamSynthesise(parts.system(), parts.user(), taskDescription, history, ctx);
        var doc = buildDocument(taskDescription, history, raw, filtered);
        var warnings = validator.validate(doc);

        // Single retry on hard structural failure. We treat "missing required
        // section: ## Goal" or "synthesised prompt was empty" as retriable -
        // the model fundamentally failed to produce the marker shape. Cosmetic
        // warnings (quote-only bullets, placeholders) are NOT retriable; the
        // user sees them and can decide.
        if (isRetriable(warnings)) {
            var rawRetry = streamSynthesise(
                parts.system() + FINALIZE_RETRY_REMINDER,
                parts.user(),
                taskDescription, history, ctx);
            var docRetry = buildDocument(taskDescription, history, rawRetry, filtered);
            var warningsRetry = validator.validate(docRetry);
            // Keep the retry only if it is actually better. Otherwise fall back
            // to the first attempt so we never replace a partially-good doc with
            // a worse one.
            if (warningsRetry.size() < warnings.size()) {
                doc = docRetry;
                warnings = warningsRetry;
            }
        }

        if (onChunk != null) onChunk.accept(doc);
        return new SynthesiseResult(doc, warnings);
    }

    private static boolean isRetriable(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) return false;
        for (var w : warnings) {
            if (w.startsWith("missing required section")) return true;
            if (w.contains("empty")) return true;
            // Goal-too-short is a substance failure the retry can plausibly
            // fix: the FINALIZE_RETRY_REMINDER pushes the model toward emitting
            // the marker sections cleanly, which is the path that produces a
            // meaningful Goal paragraph.
            if (w.contains("too short to be actionable")) return true;
        }
        return false;
    }

    private String streamSynthesise(String system, String userTemplate,
            String taskDescription, List<TaskTurn> history, ProjectContext ctx) {
        var sb = new StringBuilder();
        chatClient.prompt()
            .system(system)
            .user(u -> u.text(userTemplate)
                .param("task", taskDescription)
                .param("stack", formatStack(ctx == null ? null : ctx.stack()))
                .param("history", formatHistory(history)))
            .stream()
            .content()
            // Per-chunk inactivity timeout: if the stream stops emitting tokens
            // for longer than finalizeTimeout, surface a TimeoutException instead
            // of blocking the TUI thread forever.
            .timeout(finalizeTimeout)
            .doOnNext(sb::append)
            .blockLast();
        return sb.toString();
    }

    private String buildDocument(String taskDescription, List<TaskTurn> history,
            String raw, RelevantStandards filtered) {
        var sections = parseMarkerSections(raw == null ? "" : raw);
        // The model often dumps bullets into the GOAL section even though GOAL
        // expects pure prose. Extract the leading paragraph as the real goal and
        // shunt any trailing bullets into ACCEPTANCE where the grounding filter
        // can act on them.
        sections = separateGoalProseFromBullets(sections);
        sections = groundAcceptance(sections, taskDescription, history);
        var optedOutRuleIds = detectOptedOutRules(filtered.rules(), history);
        sections = groundOutOfScope(sections, history, optedOutRuleIds, filtered.rules());

        return assembleFinalMarkdown(taskDescription, sections,
            filtered.rules(), filtered.skills(), filtered.checklists());
    }

    /** Parsed LLM output sections. Context was tried twice and hallucinated both
     *  times - the model abstracts the example shape and invents content. Acceptance
     *  + Goal capture the user's decisions adequately; a separate Context isn't
     *  worth the hallucination risk. */
    record FinalizeSections(String goal, String acceptance, String outOfScope) {}

    /**
     * Filter Acceptance bullets to only those grounded in the user's actual task
     * or interview answers. Drops two failure modes:
     *   (a) quote-only bullets like {@code "create new api"} - the model echoing
     *       user fragments verbatim as bullets instead of producing real criteria.
     *   (b) bullets whose significant words don't appear in the user's task or
     *       answers at all - typically hallucinations the model invented (e.g.
     *       "internationalization", "encryption", "WebSocket connection").
     * Keeps the standard fallback bullet ("matches the task description...")
     * since it's intentionally generic.
     */
    static FinalizeSections groundAcceptance(FinalizeSections sections, String userTask, List<TaskTurn> history) {
        if (sections == null || sections.acceptance() == null || sections.acceptance().isBlank()) {
            return sections;
        }
        var grounding = new HashSet<String>();
        grounding.addAll(significantWords(userTask));
        if (history != null) {
            for (var turn : history) {
                if (turn != null && turn.answer() != null) {
                    grounding.addAll(significantWords(turn.answer()));
                }
            }
        }
        if (grounding.isEmpty()) return sections;

        var kept = new ArrayList<String>();
        for (var raw : sections.acceptance().split("\\R")) {
            var l = raw.strip();
            if (l.isEmpty()) continue;
            var text = stripAllBulletPrefixes(l);

            // Keep the explicit fallback line by exact match.
            if (text.toLowerCase().contains("matches the task description")) {
                kept.add(l);
                continue;
            }

            // Drop quote-only fragments - those are the model bullet-ifying user words.
            var unquoted = text;
            if ((unquoted.startsWith("\"") && unquoted.endsWith("\""))
                || (unquoted.startsWith("'") && unquoted.endsWith("'"))) {
                continue;
            }

            var bulletWords = significantWords(text);
            if (bulletWords.isEmpty()) continue;
            if (bulletWords.stream().anyMatch(grounding::contains)) {
                kept.add(l);
            }
        }
        return new FinalizeSections(sections.goal(), String.join("\n", kept), sections.outOfScope());
    }

    /**
     * GOAL expects a prose paragraph but the model often emits "<paragraph>\n\n-
     * bullet\n- bullet". Take only the leading prose for GOAL and append the
     * trailing bullets to ACCEPTANCE - the grounding filter and downstream
     * acceptance assembly can then handle them properly.
     */
    static FinalizeSections separateGoalProseFromBullets(FinalizeSections sections) {
        if (sections == null || sections.goal() == null || sections.goal().isBlank()) return sections;
        var proseLines = new ArrayList<String>();
        var bulletLines = new ArrayList<String>();
        boolean inBullets = false;
        for (var raw : sections.goal().split("\\R")) {
            var l = raw.strip();
            if (l.isEmpty()) {
                if (!inBullets) proseLines.add("");  // preserve paragraph breaks before bullets
                continue;
            }
            if (l.startsWith("- ") || l.startsWith("* ")) {
                inBullets = true;
                bulletLines.add(l);
            } else if (inBullets) {
                // Non-bullet line after we entered bullets - treat as continuation of last bullet.
                int last = bulletLines.size() - 1;
                if (last >= 0) bulletLines.set(last, bulletLines.get(last) + " " + l);
            } else {
                proseLines.add(l);
            }
        }
        var prose = String.join(" ", proseLines).replaceAll(" +", " ").strip();
        if (bulletLines.isEmpty()) return sections;
        var mergedAcceptance = String.join("\n", bulletLines);
        if (sections.acceptance() != null && !sections.acceptance().isBlank()) {
            mergedAcceptance = mergedAcceptance + "\n" + sections.acceptance();
        }
        return new FinalizeSections(prose, mergedAcceptance, sections.outOfScope());
    }

    /**
     * Drop Out-of-scope bullets that aren't grounded in something the user
     * actually opted out of. The grounding set is built from significant words of
     * (a) every interview question whose answer was a negation, and (b) titles of
     * any rule that was opted out. A bullet survives only if its significant
     * words overlap that grounding set.
     */
    static FinalizeSections groundOutOfScope(
        FinalizeSections sections,
        List<TaskTurn> history,
        Set<String> optedOutRuleIds,
        List<Rule> allRules
    ) {
        if (sections == null || sections.outOfScope() == null || sections.outOfScope().isBlank()) {
            return sections;
        }
        var grounding = buildOptOutGrounding(history, optedOutRuleIds, allRules);
        if (grounding.isEmpty()) {
            return new FinalizeSections(sections.goal(), sections.acceptance(), "");
        }
        var kept = new ArrayList<String>();
        for (var raw : sections.outOfScope().split("\\R")) {
            var l = raw.strip();
            if (l.isEmpty()) continue;
            var text = l.startsWith("- ") ? l.substring(2)
                : l.startsWith("* ") ? l.substring(2) : l;
            // Skip placeholder text like "(none)" or "(no exclusions)" - the
            // synthesise prompt's documented sentinel when the user did not opt
            // out of anything.
            if (text.startsWith("(") && text.endsWith(")")) continue;
            var bulletWords = significantWords(text);
            if (bulletWords.stream().anyMatch(grounding::contains)) {
                kept.add(l);
            }
        }
        return new FinalizeSections(sections.goal(), sections.acceptance(),
            String.join("\n", kept));
    }

    private static Set<String> buildOptOutGrounding(
        List<TaskTurn> history,
        Set<String> optedOutRuleIds,
        List<Rule> allRules
    ) {
        var keywords = new HashSet<String>();
        if (history != null) {
            for (var turn : history) {
                if (turn == null) continue;
                if (isNegation(turn.answer())) {
                    keywords.addAll(significantWords(
                        turn.question() == null ? "" : turn.question()));
                }
            }
        }
        if (allRules != null && optedOutRuleIds != null) {
            for (var rule : allRules) {
                if (optedOutRuleIds.contains(rule.id()) && rule.title() != null) {
                    keywords.addAll(significantWords(rule.title()));
                }
            }
        }
        return keywords;
    }

    /**
     * Splits the marker-delimited LLM response. Missing sections default to safe
     * empty content - we never throw because of a misbehaving model.
     */
    static FinalizeSections parseMarkerSections(String raw) {
        var clean = raw == null ? "" : raw.strip();
        return new FinalizeSections(
            extractMarkerSection(clean, "GOAL"),
            extractMarkerSection(clean, "ACCEPTANCE"),
            extractMarkerSection(clean, "OUT_OF_SCOPE")
        );
    }

    private static final List<String> MARKER_NAMES = List.of(
        "GOAL", "ACCEPTANCE", "OUT_OF_SCOPE"
    );

    /**
     * Find a marker section by name. The matcher is intentionally lenient:
     * models routinely emit variations like {@code ===NAME===}, {@code ---NAME---},
     * {@code ===NAME---}, or even drop one delimiter entirely. We accept any
     * combination of {@code =} or {@code -} (3+) on either side. Without this
     * leniency a single bad delimiter swallows two sections into one.
     */
    private static String extractMarkerSection(String text, String name) {
        var marker = java.util.regex.Pattern.compile(
            "[=\\-]{3,}\\s*" + java.util.regex.Pattern.quote(name) + "\\s*[=\\-]{3,}");
        var m = marker.matcher(text);
        if (!m.find()) return "";
        int contentStart = m.end();
        int end = text.length();
        for (var other : MARKER_NAMES) {
            if (other.equals(name)) continue;
            var otherMarker = java.util.regex.Pattern.compile(
                "[=\\-]{3,}\\s*" + java.util.regex.Pattern.quote(other) + "\\s*[=\\-]{3,}");
            var om = otherMarker.matcher(text);
            if (om.find(contentStart) && om.start() < end) {
                end = om.start();
            }
        }
        return text.substring(contentStart, end).strip();
    }

    /**
     * Builds the final markdown deterministically. The LLM's contribution is the
     * prose of Goal / Acceptance / Out-of-scope; everything else is generated
     * from the standards pack. Null-safe on {@code userTask} - the assembler
     * runs even when called with a null task description.
     * <p>
     * Sections are kept compact: constraints render as one-line bullets (first
     * sentence of the rule's content + severity + id suffix for traceability)
     * rather than ###-headed blocks with embedded rationale. The downstream
     * implementing agent needs the actionable directive, not the persuasive
     * argument that convinced the human to ship the rule.
     */
    static String assembleFinalMarkdown(
        String userTask,
        FinalizeSections sections,
        List<Rule> rules,
        List<Skill> skills,
        List<Checklist> checklists
    ) {
        var sb = new StringBuilder();
        var safeTask = userTask == null ? "" : userTask.strip();

        sb.append("## Goal\n");
        var goal = sections.goal() == null || sections.goal().isBlank() ? safeTask : sections.goal();
        if (goal.isBlank()) goal = "(no goal provided)";
        sb.append(goal).append("\n\n");

        sb.append("## Constraints\n");
        if (rules == null || rules.isEmpty()) {
            sb.append("- _No standards rules apply to this task._\n\n");
        } else {
            var sorted = rules.stream()
                .sorted((a, b) -> {
                    int s = Integer.compare(severityRank(a.severity()), severityRank(b.severity()));
                    if (s != 0) return s;
                    return Integer.compare(a.priorityValue(), b.priorityValue());
                })
                .toList();
            int cap = Math.min(MAX_CONSTRAINTS, sorted.size());
            for (int i = 0; i < cap; i++) sb.append(renderRuleBullet(sorted.get(i)));
            if (sorted.size() > cap) {
                var overflowIds = sorted.subList(cap, sorted.size()).stream()
                    .map(Rule::id).toList();
                sb.append("- _Also applicable (")
                  .append(overflowIds.size())
                  .append(" more): ")
                  .append(String.join(", ", overflowIds))
                  .append("_\n");
            }
            sb.append("\n");
        }

        sb.append("## Acceptance criteria\n");
        sb.append(normaliseBullets(sections.acceptance(),
            "- Behaviour described in the Goal section is implemented."));
        sb.append("\n");

        if (sections.outOfScope() != null && !sections.outOfScope().isBlank()) {
            sb.append("## Out of scope\n");
            sb.append(normaliseBullets(sections.outOfScope(), ""));
            sb.append("\n");
        }

        boolean hasSkills = skills != null && !skills.isEmpty();
        boolean hasChecklists = checklists != null && !checklists.isEmpty();
        if (hasSkills || hasChecklists) {
            sb.append("## Standards consulted\n");
            if (hasSkills) {
                for (var s : skills) {
                    sb.append("- skill:").append(s.id());
                    if (s.title() != null && !s.title().isBlank()) sb.append(" - ").append(s.title());
                    sb.append("\n");
                }
            }
            if (hasChecklists) {
                for (var c : checklists) {
                    sb.append("- checklist:").append(c.id());
                    if (c.title() != null && !c.title().isBlank()) sb.append(" - ").append(c.title());
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString().stripTrailing() + "\n";
    }

    // Scope matching for rules/skills/checklists. An item applies when its YAML
    // scope (frameworks / languages / tags / tasks) is non-restrictive OR overlaps
    // with the task. Empty list = no restriction at that axis = applies broadly.

    static boolean scopeApplies(Scope scope, StackProfile stack, Set<String> taskTags) {
        if (scope == null) return true;
        String fwSlug = normaliseFramework(stack);
        String langSlug = normaliseLanguage(stack);
        if (!matchAxis(scope.frameworks(), fwSlug)) return false;
        if (!matchAxis(scope.languages(), langSlug)) return false;
        if (!matchAxisAny(scope.tags(), taskTags)) return false;
        if (!matchAxisAny(scope.tasks(), taskTags)) return false;
        return true;
    }

    private static boolean matchAxis(List<String> ruleValues, String projectValue) {
        if (ruleValues == null || ruleValues.isEmpty()) return true;
        if (projectValue == null || projectValue.isBlank()) return false;
        var target = projectValue.toLowerCase();
        return ruleValues.stream().anyMatch(v -> v != null && v.toLowerCase().equals(target));
    }

    private static boolean matchAxisAny(List<String> ruleValues, Set<String> projectValues) {
        if (ruleValues == null || ruleValues.isEmpty()) return true;
        if (projectValues == null || projectValues.isEmpty()) return false;
        return ruleValues.stream().anyMatch(v -> v != null && projectValues.contains(v.toLowerCase()));
    }

    /** Normalises StackProfile.framework ("Spring Boot") to the YAML slug ("spring-boot"). */
    static String normaliseFramework(StackProfile stack) {
        if (stack == null || stack.framework() == null) return null;
        return stack.framework().toLowerCase().replaceAll("\\s+", "-").replaceAll("\\.", "");
    }

    /** Normalises StackProfile.language ("Java") to the YAML slug ("java"). */
    static String normaliseLanguage(StackProfile stack) {
        if (stack == null || stack.language() == null) return null;
        return stack.language().toLowerCase();
    }

    /**
     * Classify the task into a set of tag slugs that map to the YAML scope.tags
     * vocabulary used across rules/skills/checklists. Tokenises the haystack into
     * whole words (so "create new api" doesn't trigger the `ai` tag via substring
     * match of "api"). Every task implicitly gets "feature" as a baseline.
     */
    static Set<String> classifyTaskTags(String taskDescription, List<TaskTurn> history) {
        var words = tokenize(buildHaystack(taskDescription, history));
        var tags = new HashSet<String>();
        tags.add("feature");
        if (anyWord(words, "api", "endpoint", "controller", "route", "rest", "http", "endpoints")) {
            tags.add("rest");
            tags.add("http");
            tags.add("delivery");
            if (anyWord(words, "list", "lists", "search", "browse", "all", "many",
                    "collection", "collections", "page", "paginate", "paginated")) {
                tags.add("rest-collection");
            }
            if (anyWord(words, "post", "put", "patch", "delete", "create", "creates",
                    "update", "updates", "modify", "modifies", "edit", "add", "adds",
                    "save", "saves", "remove", "removes", "destroy", "mutation")) {
                tags.add("rest-mutation");
            }
        }
        if (anyWord(words, "ui", "view", "screen", "form", "render", "tui", "cli")) tags.add("ui");
        if (anyWord(words, "auth", "authentication", "login", "token", "session", "password", "credential")) tags.add("security");
        if (anyWord(words, "database", "migration", "schema", "sql", "query", "table")) tags.add("data");
        if (anyWord(words, "config", "setting", "property", "env", "environment")) tags.add("configuration");
        if (anyWord(words, "test", "spec", "fixture")) tags.add("testing");
        if (anyWord(words, "log", "logging", "metric", "trace", "observability", "monitor")) tags.add("observability");
        if (anyWord(words, "ai", "llm", "model", "prompt", "embedding", "chatclient")) tags.add("ai");
        if (anyWord(words, "refactor", "rename", "extract", "inline")) tags.add("refactoring");
        if (anyWord(words, "bug", "fix", "defect", "regression")) tags.add("debugging");
        return tags;
    }

    private static Set<String> tokenize(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());
    }

    private static boolean anyWord(Set<String> words, String... needles) {
        for (var n : needles) if (words.contains(n)) return true;
        return false;
    }

    /** Ranks rule severity for stable ordering: must=0, never=1, should=2, avoid=3, other=4. */
    private static int severityRank(String severity) {
        if (severity == null) return 4;
        return switch (severity.toLowerCase().strip()) {
            case "must" -> 0;
            case "never" -> 1;
            case "should" -> 2;
            case "avoid" -> 3;
            default -> 4;
        };
    }

    private static String renderRuleBullet(Rule r) {
        var sb = new StringBuilder();
        sb.append("- ");
        if (r.severity() != null && !r.severity().isBlank()) {
            sb.append("[").append(r.severity()).append("] ");
        }
        var directive = firstSentence(r.description());
        if (directive.isBlank()) {
            directive = r.title() == null ? r.id() : r.title();
        }
        sb.append(directive);
        if (!directive.endsWith(".") && !directive.endsWith("!") && !directive.endsWith("?")) {
            sb.append(".");
        }
        sb.append("  *(").append(r.id()).append(")*\n");
        return sb.toString();
    }

    /**
     * First sentence of the input, with newlines collapsed to spaces. A sentence
     * ends at ". " ONLY when the next non-space character is a capital letter -
     * this avoids splitting at common abbreviations like "e.g.", "i.e.", "vs.",
     * "etc." where the next character is lowercase, a backtick, or punctuation.
     * Falls back to the first line capped at 240 chars if no real boundary exists.
     */
    static String firstSentence(String text) {
        if (text == null) return "";
        var collapsed = text.strip().replaceAll("\\R+", " ").replaceAll(" +", " ");
        if (collapsed.isEmpty()) return "";
        var matcher = java.util.regex.Pattern.compile("\\. (?=[A-Z])").matcher(collapsed);
        if (matcher.find()) return collapsed.substring(0, matcher.start() + 1);
        if (collapsed.endsWith(".")) return collapsed;
        return collapsed.length() > 240 ? collapsed.substring(0, 240) + "..." : collapsed;
    }

    private static String normaliseBullets(String section, String defaultLine) {
        if (section == null || section.isBlank()) return defaultLine + "\n";
        var bullets = new ArrayList<String>();
        for (var raw : section.split("\\R")) {
            var l = raw.strip();
            if (l.isEmpty()) continue;
            if (l.startsWith("- ") || l.startsWith("* ")) {
                bullets.add(stripAllBulletPrefixes(l));
            } else if (bullets.isEmpty()) {
                bullets.add(l);
            } else {
                int last = bullets.size() - 1;
                bullets.set(last, bullets.get(last) + " " + l);
            }
        }
        if (bullets.isEmpty()) return defaultLine + "\n";
        var sb = new StringBuilder();
        for (var b : bullets) sb.append("- ").append(b).append("\n");
        return sb.toString();
    }

    private static String stripAllBulletPrefixes(String line) {
        var l = line.strip();
        while (l.startsWith("- ") || l.startsWith("* ")) {
            l = l.substring(2).strip();
        }
        return l;
    }

    private static String buildHaystack(String taskDescription, List<TaskTurn> history) {
        var sb = new StringBuilder();
        if (taskDescription != null) sb.append(taskDescription.toLowerCase()).append(' ');
        if (history != null) {
            for (var t : history) {
                if (t.question() != null) sb.append(t.question().toLowerCase()).append(' ');
                if (t.answer() != null) sb.append(t.answer().toLowerCase()).append(' ');
            }
        }
        return sb.toString();
    }

    private static final Set<String> NEGATION_TOKENS = Set.of(
        "no", "none", "nope", "nah", "don't", "dont", "do not", "without",
        "skip", "skipped", "skipping", "exclude", "excluded", "no need",
        "not now", "not needed", "n/a", "na", "bypass", "ignore"
    );

    /**
     * Maps "negation answer + keyword in the question" → "this whole tag-domain
     * is opted out". When the user says "no" to a question containing security
     * keywords like "auth" / "password" / "token", we drop EVERY rule whose scope
     * tags include "security" / "crypto" / "auth" - not just the specific rule
     * whose title matched.
     */
    static Set<String> detectOptedOutTags(List<TaskTurn> history) {
        if (history == null || history.isEmpty()) return Set.of();
        var optedOut = new HashSet<String>();
        for (var turn : history) {
            if (turn == null || turn.question() == null || turn.answer() == null) continue;
            if (!isNegation(turn.answer())) continue;
            var q = turn.question().toLowerCase();
            if (containsAnyKeyword(q, "auth", "authentication", "login", "token", "session", "password", "credential", "security")) {
                optedOut.add("security");
                optedOut.add("auth");
                optedOut.add("crypto");
            }
            if (containsAnyKeyword(q, "rate", "limit", "throttle", "quota")) {
                optedOut.add("reliability");
            }
            if (containsAnyKeyword(q, "log", "logging", "metric", "trace", "observability", "monitor")) {
                optedOut.add("observability");
                optedOut.add("operability");
            }
            if (containsAnyKeyword(q, "pagination", "paginate", "cursor")) {
                optedOut.add("scale");
            }
        }
        return optedOut;
    }

    private static boolean containsAnyKeyword(String haystack, String... needles) {
        for (var n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    /** True if the item's scope tags overlap any opted-out tag. */
    static boolean overlapsTags(Scope scope, Set<String> optedOutTags) {
        if (scope == null || optedOutTags == null || optedOutTags.isEmpty()) return false;
        if (scope.tags() == null || scope.tags().isEmpty()) return false;
        return scope.tags().stream().anyMatch(t -> t != null && optedOutTags.contains(t.toLowerCase()));
    }

    static Set<String> detectOptedOutRules(List<Rule> rules, List<TaskTurn> history) {
        if (rules == null || rules.isEmpty() || history == null || history.isEmpty()) return Set.of();
        var optedOut = new HashSet<String>();
        for (var turn : history) {
            if (turn.question() == null || turn.answer() == null) continue;
            if (!isNegation(turn.answer())) continue;
            var qWords = significantWords(turn.question());
            if (qWords.isEmpty()) continue;
            for (var rule : rules) {
                if (rule.title() == null) continue;
                var titleWords = significantWords(rule.title());
                if (titleWords.isEmpty()) continue;
                int hits = stemTolerantOverlap(titleWords, qWords);
                if (hits >= Math.min(2, titleWords.size())) {
                    optedOut.add(rule.id());
                }
            }
        }
        return optedOut;
    }

    private static boolean isNegation(String answer) {
        if (answer == null) return false;
        var trimmed = answer.toLowerCase().strip();
        if (trimmed.isEmpty()) return false;
        for (var token : NEGATION_TOKENS) {
            if (trimmed.equals(token) || trimmed.startsWith(token + " ")
                    || trimmed.endsWith(" " + token) || trimmed.contains(" " + token + " ")) {
                return true;
            }
        }
        return false;
    }

    /** Exposed for the runner so it can surface a per-turn timeout deadline. */
    public Duration interviewTimeout() {
        return interviewTimeout;
    }

    // === formatters ===

    private static String formatHistory(List<TaskTurn> history) {
        if (history == null || history.isEmpty()) return "(no questions asked yet)";
        var sb = new StringBuilder();
        int i = 1;
        for (var turn : history) {
            sb.append("Q").append(i).append(": ").append(turn.question()).append("\n");
            sb.append("A").append(i).append(": ").append(turn.answer()).append("\n\n");
            i++;
        }
        return sb.toString().stripTrailing();
    }

    // === compact formatters (interview menu) ===

    private static String formatRulesCompact(List<Rule> rules) {
        if (rules == null || rules.isEmpty()) return "(no rules apply to this task)";
        var sb = new StringBuilder();
        for (var r : rules) {
            sb.append("- rule:").append(r.id());
            if (r.title() != null && !r.title().isBlank()) sb.append("  -  ").append(r.title());
            if (r.severity() != null && !r.severity().isBlank()) sb.append("  [").append(r.severity()).append("]");
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String formatSkillsCompact(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) return "(no skills apply to this task)";
        var sb = new StringBuilder();
        for (var s : skills) {
            sb.append("- skill:").append(s.id());
            if (s.title() != null && !s.title().isBlank()) sb.append("  -  ").append(s.title());
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String formatChecklistsCompact(List<Checklist> checklists) {
        if (checklists == null || checklists.isEmpty()) return "(no checklists apply to this task)";
        var sb = new StringBuilder();
        for (var c : checklists) {
            sb.append("- checklist:").append(c.id());
            if (c.title() != null && !c.title().isBlank()) sb.append("  -  ").append(c.title());
            sb.append("\n");
            if (c.items() != null) {
                for (ChecklistItem item : c.items()) {
                    sb.append("    - ").append(item.id()).append(": ").append(item.text()).append("\n");
                }
            }
        }
        return sb.toString().stripTrailing();
    }
}
