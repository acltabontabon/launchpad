package com.acltabontabon.launchpad.ai;

import com.acltabontabon.launchpad.scanner.ProjectContext;
import com.acltabontabon.launchpad.template.ContextTarget;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class ContextGeneratorService {

    private static final String PROJECT_SUMMARY_PROMPT = """
        You are an expert software architect. Analyse the following project context and produce a concise, \
        accurate summary for use in an AI agent context file.

        Your output must include:
        1. A one-paragraph project overview (what it is, what it does, key design decisions)
        2. The primary architectural patterns used (MVC, hexagonal, event-driven, etc.)
        3. A bullet list of the most important modules/packages and their responsibilities
        4. Key conventions the project follows (naming, error handling, testing style)
        5. Anything an AI agent must know before touching this codebase (gotchas, constraints)

        Be specific. Do not pad with generic advice. If you don't know something, say so rather than guess.

        PROJECT CONTEXT:
        {context}
        """;

    private static final String SKILLS_PROMPT = """
        Based on the following project context, suggest 5-8 common developer tasks that an AI agent \
        should know how to perform in this specific project. Format each as a skill definition:

        ## Skill: <short-name>
        **Trigger:** <when to use this skill>
        **Steps:** <numbered steps specific to this project>
        **Notes:** <project-specific gotchas>

        PROJECT CONTEXT:
        {context}
        """;

    private static final String CURSOR_RULES_PROMPT = """
        You are writing .cursorrules for a Cursor AI user. Based on the project context below, \
        write concise, actionable rules that will make Cursor more effective in this codebase.

        Rules must be:
        - Specific to this project, not generic best practices
        - Actionable (what to do or not do, not vague advice)
        - Ordered by importance

        PROJECT CONTEXT:
        {context}
        """;

    private final ChatClient chatClient;

    public ContextGeneratorService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public String generateProjectSummary(ProjectContext ctx) {
        return chatClient.prompt()
            .user(u -> u.text(PROJECT_SUMMARY_PROMPT).param("context", ctx.toPromptString()))
            .call()
            .content();
    }

    public String generateSkills(ProjectContext ctx) {
        return chatClient.prompt()
            .user(u -> u.text(SKILLS_PROMPT).param("context", ctx.toPromptString()))
            .call()
            .content();
    }

    public String generateTargetSpecificContent(ProjectContext ctx, ContextTarget target) {
        return switch (target) {
            case CURSOR -> chatClient.prompt()
                .user(u -> u.text(CURSOR_RULES_PROMPT).param("context", ctx.toPromptString()))
                .call()
                .content();
            case CLAUDE -> generateSkills(ctx);
        };
    }
}
