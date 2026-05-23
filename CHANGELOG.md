# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- **MCP Integration**
  - AI tool flow in `/settings` for automatic MCP config (Claude, Cursor).
  - MCP server mode via `launchpad mcp` exposing tools and SARIF resources.
  - Registry-aware tools (`list_projects`, `scan_project`, etc.) with short-name support.
  - `ProjectRegistry` for persistent, name-based project addressing.
  - Automatic project enrollment after TUI scans.
  - New `/projects` command to manage the project registry.

- **Automated Audit System**
  - Integrated Audit phase with live progress and TUI summary.
  - Standards engine supporting `PatternChecker` and `ImportChecker`.
  - `LlmChecker` for semantic audit rules using local LLMs.
  - `check:` blocks in `Rule` schema for automated audit logic.
  - `ScanStore` for persisting scans to `.launchpad/scan.json`.

- **TUI & User Experience**
  - Rocket lift-off animation on Welcome screen.
  - Live path-prefix matches and autocomplete in Project Select.
  - `SAVED` chips in Review screen for session-written files.
  - New `BENEFITS.md` (token-cost analysis) and `USAGE.md` (setup guide).

### Changed
- **Visual & UI Refinement**
  - Redesigned TUI using "Cosmic Console" visual system.
  - Simplified Welcome screen: Reworked tagline and moved help to `/help`.
  - Improved Header: Breadcrumb shows active Ollama model.
  - Optimized Review Screen: Consolidated warnings and moved save status to top strip.
  - Enabled diff views for new files in Review.

- **Core Logic & AI**
  - Default Ollama model to `qwen2.5-coder:7b`.
  - Improved grounding with mandatory file-list blocks and anti-invention rules.
  - `OutputValidator` automatically strips hallucinated file references.

### Fixed
- Native image failures in Summary/Assemble phases (templates & reflection hints).
- Welcome screen freeze on stray keystrokes; `q` correctly quits.
- Slash-command palette alignment and description truncation.
- `/new-task` Scanning screen no longer mislabels itself as "Generating Claude Code": header now reads "Scanning for New Task", the stepper shows the task journey (Scan/Audit/Describe/Interview/Prompt), and the phases card hides the LLM/Assemble rows that never run on this path.
- Settings "Connect to AI tool" picker now pads client name and status badge columns so the path column lines up across rows.

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
