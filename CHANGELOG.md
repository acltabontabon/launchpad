# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- **Project Virtualization Engine**: New canonical model (`ProjectContext`) with automated persistence to `.launchpad/project-context.json` and a roadmap in `docs/roadmap.adoc`.
- **Intelligent Discovery**: Automated mapping of multi-trigger workflows (HTTP/Event/Scheduled) and inference of architectural risks/guardrail suggestions.
- **Model-Grounded `/new-task`**: Task synthesis now injects execution context (impacted systems, workflows, and risks) directly from the virtualized model.
- **MCP Expansion**: New tools for model access (`get_workflows`, `get_risks`, etc.) and a comprehensive integration test harness.

### Changed
- **AppState Decomposition**: Refactored monolithic `AppState` into focused state components with independent reset logic.
- **Model-Driven Context**: `AGENTS.md` now projects operations, workflows, and risks directly from the virtualized model.

### Removed
- **Redundant Renderers**: Removed `BuildProfilesRenderer` and duplicate synthesis paths.
- **Legacy Artifacts**: Removed obsolete planning markers and redundant tests.

## [0.5.0] - 2026-05-30
### Added
- **`/new-task` quality critic:** Added a second local-AI pass to ensure interview depth and inject follow-up questions if needed.
- **Task substance heuristics:** New validation rules to flag shallow goals or filler acceptance criteria in generated tasks.
- **Standards-pack authoring guide:** New guide (`docs/standards-pack.adoc`) for tech leads on layout, schema, and skill creation.
- **Fuzzy project picker:** Filterable list for recent and discovered projects with background search.
- **Enhanced Scan screen:** Real-time activity log, statistics, and detailed progress tracking.
- **Interactive log navigation:** Keyboard support (j/k, PageUp/Down) for the scan log.
- **Legacy file detector:** Shows a warning when older agent-instruction files are found.
- **Agent Projections:** Modular extension point for agent-specific output files (Claude, Cursor, Windsurf).
- **AI tool picker:** Settings selection to drive which agent projections are emitted.
- **New projections:** Opt-in support for Cursor (`.mdc`) and Windsurf (`.rules`) formats.
### Changed
- **Vendor-neutral primary file:** The primary agent-instructions file is now `AGENTS.md`.
- **Unified adapter ID:** Renamed default adapter to `agents` with legacy fallback support.
- **Standards-pack control:** Explicit projection opting via `standards-pack.yml`.
- **Rich Markdown preview:** Styled rendering for headings and code blocks in the Review screen.
- **Brand positioning:** Refined messaging focusing on local-first repository context.
- **Single output shape:** Simplified to a vendor-neutral output set on every run.
- **Streamlined TUI flow:** Simplified navigation (Welcome -> ProjectSelect -> Scanning -> Review).
- **Settings redesign:** Grouped configuration with focus-rail navigation and status indicators.
- **Improved system checks:** Sanitized and simplified service health cards.
### Fixed
- **Primary file normalization:** Automatically normalizes legacy paths to `AGENTS.md`.
- **Project-aware headers:** File headers and version badges now sync with project metadata.
- **JDK 25 compatibility:** Resolved native-access warnings when running via Maven.
- **Shutdown reliability:** Improved background process cleanup for clean JVM exits.

### Removed
- **Redundant UI:** Removed the Scan screen stepper and project-info footer.
- **Non-deterministic outputs:** Dropped AI-generated prompts and notes for a context-only layer.
- **Legacy schemas:** Removed obsolete fields from standards and YAML bindings.
- **Dedicated Cursor target:** Replaced hardcoded paths with vendor-neutral `AGENTS.md` and projections.

## [0.4.0] - 2026-05-25
### Changed
- **Modular context engine:** Refactored `ContextTemplateEngine` into focused collaborators for better maintainability and synthesis validation.
- **Unified generation path:** Both Claude and Cursor targets now share a deterministic skeleton and bounded LLM synthesis logic.
- **Strict project validation:** Narrowed scope to Spring Boot Java + Maven projects, rejected at entry point by a shared detector.
- **Deterministic discovery:** Replaced fuzzy parsing with structured Maven model analysis and deterministic file classification.
- **Streamlined documentation:** Scoped discovery to Markdown and AsciiDoc with purpose-based tagging (setup, architecture, etc.).
- **Enhanced scanner efficiency:** Extended skip lists for build/vendor directories and optimized file walking.
- **Improved MCP tools:** Documentation tools now support purpose-based filtering and structured metadata.
### Fixed
- **Improved TUI responsiveness:** `ESC` now cancels in-flight scans and interviews, and `q` requires confirmation before quitting during active operations.
### Removed
- **Generic MCP tools:** Removed `get_file` and `list_files` in favor of standard `mcp-server-filesystem`.
- **Legacy pipelines:** Retired the single-pass mega-prompt summary and unsupported non-Spring framework detectors.
- **Obsolete parsers:** Removed site-generator config parsers (MkDocs/Antora) in favor of direct file inspection.

## [0.3.0] - 2026-05-24
### Added
- **Multi-backend AI support:** Integration for LM Studio, llama.cpp, vLLM, and OpenAI-compatible endpoints with auto-detection.
- **MCP ecosystem expansion:** New tools for documentation discovery, cross-project references, and registry management.
- **Enhanced scanning:** Automated detection of project documentation (MkDocs, Antora) and Databricks framework facets.
- **Architectural insights:** New `## Architecture` section in context files providing a structural overview and narrative summary.
- **Rich context generation:** "Evidence-based" synthesis that uses real repository content to ground AI descriptions.
- **Improved `/new-task` interview:** Hardened task discovery with one-shot examples, better streaming, and strict validation.
- **Automated Audit System:** Standards engine with regex, import, and semantic LLM-based checks.
### Changed
- **Deterministic context files:** Redesigned generation pipeline using a stable skeleton with scoped local-AI synthesis.
- **Refined UI/UX:** New "Cosmic Console" TUI theme, improved headers, and optimized review screens.
- **Provider-neutral configuration:** Renamed settings and prompted logic to be tool-agnostic.
- **Consolidated context:** Reduced duplication by moving project-specific notes and standards to companion files.
### Fixed
- **Performance & Stability:** Fixed TUI freezes with explicit AI timeouts and added concurrent safety for scan state.
- **I/O Resilience:** Improved handling of unreadable files and corrupted markers during merge operations.
- **Native Image compatibility:** Full support for MCP and TUI modes under GraalVM native image.
- **Documentation:** Simplified `USAGE.md` and added `BENEFITS.md` for token-cost analysis.
### Removed
- **Redundant sections:** Dropped obsolete synthesis helpers and overlapping developer-workflow blocks.
- **Mega-prompt pipeline:** Retired the single-pass generation in favor of chunked, validated synthesis jobs.

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
