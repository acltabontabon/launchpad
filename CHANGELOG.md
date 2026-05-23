# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
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
