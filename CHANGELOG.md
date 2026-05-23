# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Changed
- Welcome screen footer simplified to just `q quit`. The `/` commands and `r refresh` hints were removed (the `/ to begin` prompt in the body already advertises the palette, and `r` was never wired). The `?` help hint was also dropped - help is now discoverable via the palette as `/help` instead.

### Added
- New `/help` command in the Welcome palette opens a Help screen listing every available command with its description plus quick usage tips. `esc` returns to Welcome.

### Changed
- Welcome screen tagline reworked to better align with Launchpad's purpose: hero subline is now "Local AI Context Prep" (was "AI Context Generator") and tagline is "Sharper prompts. Smaller bills." (was "Save tokens. Ship faster."). The new pair names the local-first edge and the token-cost payoff in one breath.
- Welcome header breadcrumb now surfaces the active Ollama model (e.g. `model  qwen2.5-coder:7b`) instead of echoing the hero subline. Removes the visual redundancy and gives the user useful at-a-glance info: which local model will do the work. `AppState.activeModel` is seeded from `LaunchpadSettings` on startup and refreshed via the existing `OllamaSettingsChanged` event so a model swap in `/settings` reflects immediately. Falls back to `model: not configured` when blank.

### Fixed
- Welcome screen no longer "freezes" on stray keystrokes. Pressing `q` (or any non-`/` character) used to silently accumulate into the command-palette buffer without opening the palette, leaving the screen visually unchanged while subsequent keys did nothing. `q` now quits from Welcome when the palette is closed (matching the footer hint), and `WelcomeView` only accepts text input when the palette is already open or the keystroke is the `/` that opens it.

### Added
- New `BENEFITS.md` document quantifying Launchpad's three value levers (project context, ambiguity reduction, cross-project reference) with tables, ASCII diagrams, and concrete token-cost numbers per session / per developer / per team. Honest section listing what Launchpad does NOT help with. Two practical experiments readers can run to measure their own savings. README now points to it from the top.
- New `USAGE.md` step-by-step guide covering install, first TUI run, MCP client setup (Claude Desktop, Claude Code, Cursor, Cline, Continue, Zed), name-based project addressing, registry management, auditing, troubleshooting, and a full reference of every file Launchpad reads or writes. README now points to it from the top.
- New `/projects` command in the Welcome palette opens a Projects screen that lists every entry in the registry (name, stack, path, last-scanned relative time). `↑↓` browses, `enter` re-opens a project (sets `projectPath` and returns to Welcome with a flash hint), `d` removes the selected entry, `p` prunes entries whose path no longer exists. The empty state nudges the user to run `/init` or `/new-task` first. Surfaces the MCP-addressable name set so users can see what to call from Claude Code / Cursor / Cline / Continue / Zed.
- MCP tools are now project-registry-aware. New `list_projects` tool returns every project the user has used Launchpad on (name, path, stack, timestamps). The three existing tools (`scan_project`, `get_standards`, `get_audit_findings`) now accept a `project` argument that can be either a short name from the registry or an absolute path - clients no longer need to remember absolute paths. When the argument is omitted or unknown, the tool returns a self-describing error payload listing the available projects so the AI client can ask the user or retry. Resolution is stateless per call (no "current project" server state, no cross-session interference).
- TUI scan pipeline now enrolls each scanned project in `ProjectRegistry` right after the scan persists. Registration is a side-effect of normal use (no new step for the user); failures are non-fatal and the scan/audit/generation flow continues regardless. The stack label stored in the registry is derived from `ProjectContext.stack()` (e.g. "Java / Spring Boot"). This sets up MCP name-based addressing without requiring an extra TUI action.
- `ProjectRegistry` persists a user-global list of projects to `~/.launchpad/projects.json` so MCP clients (and future TUI surfaces) can address projects by short name instead of an absolute path. The registry stores name, path, stack label, and timestamps; supports name-or-path resolution, case-insensitive lookup, collision-suffixed naming when two repos share a basename, and a `pruneMissing` pass that drops entries whose path no longer exists. This commit only adds the service and tests; TUI auto-enrollment and the MCP `list_projects` tool follow in subsequent commits.
- GraalVM native image still builds and runs the TUI cleanly; `LaunchpadMcpRuntimeHints` registers reflection metadata for the MCP tool/resource classes plus the `LlmCheckResult` structured-output target. MCP mode under native currently fails inside Spring AI's tool-schema generator (a known AOT gap in 2.0.0-M6) - documented as a limitation; for now the supported MCP path is `java -jar launchpad.jar mcp`.
- MCP tools and resources: `launchpad mcp` now exposes three tools (`scan_project`, `get_standards`, `get_audit_findings`) and one resource template (`launchpad://audit/{projectPath}` returning SARIF 2.1.0) to any MCP client. Tools read cached `.launchpad/scan.json` / `.launchpad/audit.sarif.json` when fresh and fall back to running the underlying services on demand, so the MCP server works standalone - a developer who never opens the TUI still gets value. `logback-spring.xml` routes all logs to stderr under the `mcp` profile so stdout remains clean for the JSON-RPC stream. Tool surface is deliberately scoped to what is unique to Launchpad (project intelligence + standards enforcement); generic filesystem and git operations are left to the dedicated MCP servers that already cover them.
- MCP server scaffold: `launchpad mcp` boots an MCP (Model Context Protocol) server over stdio via the `spring-ai-starter-mcp-server` starter, activating the `mcp` Spring profile. The TUI runner is gated with `@Profile("!mcp")` so MCP mode owns the process without rendering. `application-mcp.properties` keeps stdout reserved for JSON-RPC. Tools and resources are wired in a follow-up commit; this commit only stands up the subcommand and dependency footprint so MCP clients (Claude Code, Cursor, Cline, Continue, Zed - any open-protocol client) can register the server.
- Audit phase wired into the scan pipeline: after a project scan completes, Launchpad persists `<project>/.launchpad/scan.json`, runs `AuditService`, and writes `.launchpad/audit.{md,sarif.json}` before generation starts. The phase silently no-ops when no rules carry a `check` block, so projects without an audit-defining standards pack behave exactly as before. The TUI's phases card now shows an "Audit against standards" row with live rule progress and a post-completion summary (`N must, M should` violations, or `clean`). Configurable via `launchpad.audit.enabled` (default true).
- `LlmChecker` rounds out the audit engine with a `check.kind: llm` mode for rules that need semantic judgement. Spring AI's structured-output binding (`.entity(LlmCheckResult.class)`) is used in place of free-form parsing, so the local model returns parsed JSON or the call is retried once and then skipped (logged, not failed). File selection supports `controllers`, `services`, `configs`, and `files-matching` shorthands; per-rule cap of 20 files keeps cost predictable. Cloud models are never called.
- Standards audit engine (deterministic kinds): `PatternChecker` flags regex matches over selected source files; `ImportChecker` flags forbidden Java/Python imports inside scoped packages. `AuditService` orchestrates a pass, writes a SARIF 2.1.0 document to `.launchpad/audit.sarif.json` (the OASIS standard consumed by GitHub code-scanning, VS Code SARIF Viewer, IntelliJ Qodana) and a human-readable `.launchpad/audit.md` grouped by severity. SARIF level mapping: `never`/`must` → `error`, `should` → `warning`, `avoid` → `note`. The LLM checker arrives in a follow-up commit; this commit only runs the free, deterministic kinds.
- `Rule` schema now accepts an optional `check:` block (kinds: `forbid-pattern`, `forbid-import`, `llm`) so a rule can describe how to audit itself. Rules without a `check` remain doc-only - existing standards packs keep loading unchanged. The matching `RuleChecker` implementations are wired in a follow-up commit.
- `ScanStore` persists each completed scan to `<project>/.launchpad/scan.json` so out-of-process consumers (the upcoming MCP server, audit engine) can read the scan without re-walking the tree. Includes a freshness window check and is forward-compatible: scanner records carry `@JsonIgnoreProperties(ignoreUnknown = true)` so older readers tolerate newer fields.
- Welcome screen now plays a rocket animation when both Ollama and Standards are ready: rocket sits on the launch pad with flickering flames and drifting smoke (long dwell), then lifts off through the full vertical area on a ~2.5s ascent, then smoke fades and the cycle resets. The scene anchors to a ground line at the bottom of the panel and adapts to the terminal height on resize. The system-check card only returns when something is actively `CHECKING` or in `ERROR`, since the persistent footer already carries the green/yellow/red status dots and the card was redundant on a clean boot.
- Project Select now shows a live "matches" card listing every sibling directory that prefix-matches the unfinished path segment. `↑↓` browses, `tab` accepts the highlighted match (or the ghost suggestion when the list is hidden), `enter` continues.
- Review screen now shows a green `SAVED` chip on files successfully written this session, distinguishing them from the planned `NEW` / `MERGE` / `OVERWRITE` chips.
- Review screen now binds `esc` to return to the Welcome screen, matching every other view in the app and unblocking users who finished a scan and want to start over. Resets the scan latch and review buffers so the next pass through Scanning fires a fresh generation.
- Welcome screen surfaces a yellow tip beneath the Ollama model line when the configured model is in a known hallucination-prone bucket (llama3.2, phi3.5:mini, sub-3B variants), recommending `qwen2.5-coder` or `llama3.1:8b`.

### Changed
- Default Ollama model switched from `llama3.2` (3B generalist) to `qwen2.5-coder:7b` to reduce file-path hallucination on framework projects. README, CLAUDE.md, and the eval harness default updated to match.
- `ContextGeneratorService` now appends a file-list grounding block + anti-invention rule to every prompt so the model can only reference paths from the scanned project; works against all existing templates without modification.
- `OutputValidator` now strips hallucinated file references from the winning content (bullet lines containing fake paths are dropped, inline references are replaced with "an unrelated file"); records a `stripped N hallucinated file references` warning so the action stays visible.
- Hallucination handling no longer double-reports: the per-reference `model referenced X` warnings are dropped because the cleaner already strips them and records a single `stripped N` audit line. Spring profile config files (`application-<profile>.{properties,yml,yaml}`) are now allowlisted as a convention, so citing `application-dev.yml` on a project that only ships `application.properties` is no longer flagged.
- Review warning strip now shows only user-actionable signals: `empty model output` and `suspiciously short output`. The internal `phase retried once after a format violation`, `missing required section: ## X`, and `stripped N hallucinated file references` notices are dropped - the retry already produced the winning output, the assembler embeds the content under a labeled section even when the model's intermediate heading is off, and hallucinated paths are silently cleaned (the user has nothing to act on). When a scan completes cleanly the warning strip vanishes entirely.
- Redesign TUI with the Cosmic Console visual system: shared `Theme` / `Styles` / `Icons` tokens, rounded `Card` panels with internal padding, persistent `Header` + `Footer` chrome on every screen, a horizontal `Stepper` on the scan screen, a refined compact wordmark + signature glyph hero on Welcome, a modal command palette that swaps the system-check card on Welcome rather than overlaying it, and a duotone indigo + amber RGB palette in place of the ANSI-16 fallbacks.
- Review warning strip now stacks all warnings as bulleted lines (capped at 4 with a `+N more` footer) instead of showing only the first one.
- Review save status renders in a dedicated top strip instead of overlapping the file list's bottom border; the `d` (diff) toggle now renders NEW files as an all-add diff instead of silently no-op-ing.
- Header breadcrumb separators no longer duplicate (e.g. `Review · 26 files generated` instead of `Review · · · 26 files generated`).

## [0.2.0] - 2026-05-23
### Added
- New `/new-task` interactive interview drives task creation based on team engineering standards (rules, skills, checklists).
- Generates structured task definitions (`## Goal`, `## Constraints`, `## Acceptance criteria`) saved as Markdown in `.launchpad/tasks/`.
- Features multi-line input, two-phase discovery/standards walk, and smart grounding to filter irrelevant criteria.
- Hybrid finalization: LLM synthesizes natural language while Java deterministically assembles constraints to eliminate hallucinations.
- Built-in safety: Safety cap on rounds and mandatory coverage of `[must]`-severity rules.
- Framework-aware `StackProfile` detects specific frameworks (Spring Boot, Next.js, etc.) and build tools for more accurate prompts.
- `DependencyExtractor` parses full build files (`pom.xml`, `package.json`, `requirements.txt`, etc.) with version and scope awareness.
- `StructureSummarizer` groups source files by package with symbol summaries instead of flat file dumps.
- Existing-context awareness: summarizes existing `CLAUDE.md` or `.cursorrules` to avoid duplicating existing project context.
- Standards (rules, skills, checklists, prompts) are now defined in YAML and resolvable via remote Git sources or local overrides.
- New `Checklist` and `Prompt` types for reusable templates and required verification items.
- Curated skills now generate as native Claude Code skills (`.claude/skills/`) or Cursor rules (`.cursor/rules/`).
- Rule rendering includes severity chips (`must`, `should`, `avoid`, `never`) and rationale lines.
- Inline path autocomplete with ghost text and `Tab` completion on Project Select.
- Improved header: collapsed 3-row wizard into a 1-row contextual status line, reclaiming vertical space.
- New `/settings` command to configure Ollama and remote standards URLs on the fly.
- Per-file action chips (`NEW`, `OVERWRITE`, `MERGE`, `SKIP`) with unified diff view for write safety.
- `WriteService` automatically backs up modified files to `.launchpad/backups/`.
- `OutputValidator` rejects hallucinated paths and ensures required headings are present.
- Extensive eval harness and unit tests for classification, grounding, and scanner accuracy.
### Changed
- `ProjectContext` refactored into a structured record with token-budget aware serialization.
- `ProjectScanner` and `ContextTemplateEngine` converted to Spring components with constructor injection.
- Target Select screen now displays the full set of generated skill and rule files.
### Removed
- Project-info footer bar (information moved to the header status line).
- Hardcoded `EngineeringRule` enum and bundled YAML defaults; standards are now fully externalized.


## [0.1.0] - 2026-05-22
### Added
- Initial Launchpad scaffold: Spring Boot CLI/TUI that scans a target project and generates AI assistant context files.
- TamboUI-based terminal UI with view state machine (Welcome, ProjectSelect, TargetSelect, Scanning, Review).
- Project scanner that walks the file tree, detects the tech stack, and extracts dependencies into a `ProjectContext`.
- Spring AI `ChatClient` integration with a locally-running Ollama model for summary, skills, and Cursor-rules prompts.
- Context template engine producing `CLAUDE.md` + `.ai/` files for Claude Code and `.cursorrules` + `.cursor/rules/` for Cursor.
- `EngineeringRule` enum with predefined principles (Clean Code, SOLID, Fail Fast, Immutability First, etc.) injected into every generated file.
- Ollama readiness check on startup with a status badge on the Welcome screen and manual re-check (`r`) before proceeding.
- Architecture documentation describing Launchpad's components and data flow.
- GitHub Actions workflow that builds GraalVM native images for Linux x64, macOS arm64, and Windows x64 and attaches them to GitHub releases.
