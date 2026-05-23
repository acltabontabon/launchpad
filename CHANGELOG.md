# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- "Connect to AI tool" flow in `/settings` for automatic MCP configuration of Claude Desktop, Claude Code, and Cursor.
- New `BENEFITS.md` quantifying Launchpad's value with token-cost analysis and practical experiments.
- New `USAGE.md` comprehensive guide covering installation, TUI usage, and MCP client setup.
- New `/projects` command to manage the project registry (browse, re-open, remove, prune).
- Project-registry-aware MCP tools; `list_projects`, `scan_project`, `get_standards`, and `get_audit_findings` now support short project names.
- Automatic project enrollment in `ProjectRegistry` following TUI scans.
- `ProjectRegistry` service for persistent, name-based project addressing (`~/.launchpad/projects.json`).
- MCP server mode via `launchpad mcp` exposing tools and SARIF resources to any MCP client.
- Integrated Audit phase in scan pipeline with live progress and summary in TUI.
- `LlmChecker` for semantic audit rules using local LLMs via Spring AI.
- Standards audit engine with `PatternChecker` (regex) and `ImportChecker` (Java/Python).
- Support for `check:` blocks in `Rule` schema to define automated audit logic.
- `ScanStore` for persisting scans to `<project>/.launchpad/scan.json` for out-of-process consumers.
- Rocket lift-off animation on Welcome screen when Ollama and Standards are ready.
- Live path-prefix matches and autocomplete in Project Select.
- `SAVED` chips in Review screen for files written during the current session.

### Changed
- Simplified Welcome screen footer; removed redundant hints and moved help to `/help` palette command.
- Reworked Welcome screen tagline to "Local AI Context Prep: Sharper prompts. Smaller bills."
- Header breadcrumb now displays the active Ollama model instead of the tagline.
- Switched default Ollama model to `qwen2.5-coder:7b` for better accuracy.
- Improved prompt grounding with mandatory file-list blocks and anti-invention rules.
- Enhanced `OutputValidator` to automatically strip hallucinated file references.
- Consolidated Review screen warnings to focus on user-actionable signals.
- Redesigned TUI using the "Cosmic Console" visual system (cards, stepper, refined palette).
- Stacked warning lines in Review screen with a overflow footer.
- Moved Review save status to a dedicated top strip and enabled diffs for new files.
- Fixed duplicated breadcrumb separators in the header.

### Fixed
- Prompt templates are now bundled into the native image; previously the Summary phase failed with `Missing prompt template` under native.
- Standards record types now carry reflection hints, so `StandardsLoader` can deserialize YAML under native; previously the Assemble phase failed with `Failed to read standards-pack.yml`.
- Welcome screen no longer freezes on stray keystrokes; `q` correctly quits when palette is closed.
- Slash-command palette now pads command ids to a uniform column and sizes the card to its content, so descriptions stay aligned and are no longer truncated.

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
