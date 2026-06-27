# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- **Output contract docs**: `docs/output-contract.adoc` documents every file a scan writes - layout, section structure, provenance stamp, write/merge actions, stable-vs-experimental tiers, and versioning - so downstream tools integrate against a contract instead of snapshot-testing prose. Adds a `docs/index.adoc` landing page (closes #91).
- **MCP task-interview tools**: `ask_task_question`, `finalize_task`, and `regenerate_section` expose the local `/new-task` interview over MCP so a cloud agent can de-risk a task first. Stateless (the caller carries the `history` transcript), reuses `TaskAdvisorService`, caps at 8 rounds with a critic after round 2 (closes #19).
- **MCP semantic-graph query tools**: `get_repo_map`, `get_architecture`, and `get_task_context(project, query)` read the `.launchpad/project.model.json` graph and return structured node/edge records; `get_standards` gains an optional `ruleId`. All honor `LAUNCHPAD_MCP_RESPONSE_MODE` (toward #19).
- **MCP model-projection tools**: `get_workflows`, `get_systems`, `get_risks`, and `get_project_overview` expose the virtualized model over MCP, with an integration test harness.
- **Project Virtualization Engine**: Canonical `ProjectContext` model persisted to `.launchpad/project-context.json`; roadmap in `docs/roadmap.adoc`.
- **Project-model graph sidecar**: Deterministic, LLM-free `.launchpad/project.model.json` projecting structure as typed nodes and edges, each node with a stable `id`, content hash, and `source` pointer. Versioned via `schemaVersion` (closes #104).
- **Standards-index sidecar**: Deterministic, LLM-free `.launchpad/standards.index.json` with one record per rule (`id`, `title`, `severity`, `source`, `contentHash`, ...); the `id` joins to audit findings' `ruleId`. Versioned via `schemaVersion` (closes #79).
- **Stable ids and content hashes on standards records**: Every rule, skill, and checklist carries a stable `id` and `contentHash`; the sidecar now includes skills and checklists (`schemaVersion` 2), and audit findings emit a matching `ruleHash` so consumers can spot findings made against stale text (closes #81).
- **Intelligent Discovery**: Maps multi-trigger workflows (HTTP/Event/Scheduled) and infers architectural risks and guardrail suggestions.
- **Model-grounded `/new-task`**: Task synthesis injects impacted systems, workflows, and risks from the virtualized model.
- **Spring Boot Gradle support**: Gradle projects (Groovy or Kotlin DSL) now pass the support gate via `SpringBootGradleSupportSignal` and `GradleBuildParser`.
- **Canonical skills companion**: Resolved skills are written to `.ai/skills.md`, one H2 section each with `Trigger`/`Steps`/`Expected output`/`Notes`; a `FileKind.SKILLS` distinguishes it from per-vendor projections (closes #80).
- **Provenance header on context files**: `AGENTS.md` and `.ai/*.md` open with a single-line `launchpad:provenance` stamp (JSON payload: version, `launchpadVersion`, `generatedAt`, standards `source`, `aiModel`). On `AGENTS.md` it sits inside the managed block (closes #82).
- **Standards-pack schema version**: `standards-pack.yml` requires an integer `schemaVersion`; missing or unsupported versions are rejected on load and surfaced by the MCP standards tools as `incompatible_pack_schema` (closes #84).

### Changed
- **Graceful degrade when local AI is unreachable**: A configured-but-unreachable provider now downgrades instead of stalling - the scan resolves provider health before assembly, skips the three LLM synthesis jobs (no per-job stream timeout), generates deterministic context, stamps the files `deterministic-only`, and surfaces a warning on the review screen (closes #88).
- **Provenance version field renamed to `schemaVersion`**: The provenance stamp's version field is now `schemaVersion` (was `schema`, value still `1`) to match the sidecars. Changes the JSON key in the `launchpad:provenance` comment.
- **MCP tool descriptions clarify the sandbox/Launchpad boundary**: Descriptions now separate sandbox-owned raw inspection from Launchpad-owned synthesized intelligence to guide tool selection. Text only - no behavior change (closes #73).
- **MCP body-heavy tools return references by default**: `get_standards`, `compare_standards`, and `get_documentation` return references (ids, paths, `.ai/` anchors, hashes, summaries) instead of full bodies. Set `LAUNCHPAD_MCP_RESPONSE_MODE=inline` to restore the old shape (closes #78).
- **Chunk-friendly standards companions**: `.ai/engineering-rules.md`, `.ai/skills.md`, and `.ai/checklists.md` emit one heading per item with an explicit `{#slug}` anchor from its stable `id`; severity moved to a `` `[must]` `` badge; slug collisions now fail the scan (closes #80).
- **AppState decomposition**: Split the monolithic `AppState` into focused components with independent reset logic.
- **Model-driven context**: `AGENTS.md` projects operations, workflows, and risks from the virtualized model.
- **Build-tool-agnostic scanner**: A `BuildSystem` abstraction resolves Maven or Gradle for dependencies, stack label, and build commands.
- **TaskAdvisorService decomposed**: Split the 1,150-line class into focused collaborators; entry points and behaviour unchanged.
- **Standardised MCP error envelope**: All `@McpTool` failures use one nested envelope (`error.code`/`type`/`message`, optional `remediation`/`details`); clients branch on the coarse `type` (closes #76).
- **Manifest now required for a pack**: A standards source resolves only with a `standards-pack.yml` declaring a supported `schemaVersion` (closes #84).

### Removed
- **Redundant Renderers**: Removed `BuildProfilesRenderer` and duplicate synthesis paths.
- **Legacy Artifacts**: Removed obsolete planning markers and redundant tests.
- **Legacy flat-file standards mode**: Dropped the manifest-less fallback that read bare `rules.yml` / `skills.yml` from the root of a standards source. Every pack is now a versioned manifest, so there is no second, unversioned load path (closes #84).

### Fixed
- **Responsive project picker on slow disks**: The on-demand live search now has a 3s wall-clock deadline; partial results render and the picker stays responsive even on slow or wide home directories (closes #77).
- **Path containment in write service**: Reject standards-pack file plans whose target escapes the project root, closing a write-anywhere primitive (closes #65).
- **Standards-pack remote URL validation**: The remote URL is now allow-listed (https://, ssh://, or `user@host:path`) and rejected for shell metacharacters, whitespace, or `ext::` / `file://` schemes, closing an arbitrary-code-execution ingress via `git clone` (closes #66).

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
