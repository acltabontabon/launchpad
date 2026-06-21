# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- **Project Virtualization Engine**: New canonical model (`ProjectContext`) with automated persistence to `.launchpad/project-context.json` and a roadmap in `docs/roadmap.adoc`.
- **Machine-readable project-model graph**: A deterministic, LLM-free sidecar (`.launchpad/project.model.json`) emitted alongside `AGENTS.md`. It projects the project structure as a graph of nodes (`project`, `package`, `component`, `endpoint`, `entrypoint`, `dependency`) and edges (`contains`, `exposes`, `implements`, `depends-on`), each node carrying a stable `id`, a content hash, and a `source` pointer with line ranges. Versioned via a top-level `schemaVersion`; documented in `docs/project-model.adoc` (closes #104).
- **Machine-readable standards sidecar**: A deterministic, LLM-free sidecar (`.launchpad/standards.index.json`) emitted alongside `AGENTS.md`, with one self-contained record per resolved rule (`id`, `title`, `severity`, `scope`, `description`, `rationale`, `source`, `auditable`/`checkKind`, `priority`, `contentHash`). Rules and their `source` are resolved in a single decision so they never disagree; records are ordered by priority then id and carry a content hash that excludes `generatedAt`. The rule `id` is the join key shared with audit findings (`audit.sarif.json` `ruleId`). Versioned via a top-level `schemaVersion`; documented in `docs/standards-index.adoc` (closes #79).
- **Stable ids and content hashes on every standards record**: Every resolved rule, skill, and checklist now carries a stable, unique `id` (authored ids are used verbatim; a blank id is filled with a deterministic slug of the title or item text, suffixed on collision) and a `contentHash` over a canonical labeled payload via the shared `StandardsContentHash`. The standards sidecar now projects skills and checklists alongside rules, each with its own hash (`schemaVersion` bumped to 2). Audit findings now emit `ruleHash` (in `audit.sarif.json` result/rule `properties`, `audit.md`, and the `get_audit_findings` MCP payload) equal to the matching rule's sidecar `contentHash`, so consumers can detect findings produced against stale standards text. Documented in `docs/standards-index.adoc` (closes #81).
- **Intelligent Discovery**: Automated mapping of multi-trigger workflows (HTTP/Event/Scheduled) and inference of architectural risks/guardrail suggestions.
- **Model-Grounded `/new-task`**: Task synthesis now injects execution context (impacted systems, workflows, and risks) directly from the virtualized model.
- **MCP Expansion**: New tools for model access (`get_workflows`, `get_risks`, etc.) and a comprehensive integration test harness.
- **Spring Boot Gradle support**: Gradle projects (`build.gradle` / `build.gradle.kts`, Groovy or Kotlin DSL) now pass the support gate via a new `SpringBootGradleSupportSignal` and structured `GradleBuildParser`.
- **Canonical skills companion**: Resolved skills are now written to `.ai/skills.md`, one H2 section per skill with bounded `Trigger`/`Steps`/`Expected output`/`Notes` subsections, instead of only appearing as bullets in `.ai/index.md`. A dedicated `FileKind.SKILLS` distinguishes the aggregated companion from the per-vendor skill projection files (closes #80).
- **Provenance header on generated context files**: `AGENTS.md` and the `.ai/*.md` companions now open with a single-line, machine-readable lineage stamp - an HTML comment carrying a stable `launchpad:provenance` marker followed by a compact JSON payload (`schema`, `launchpadVersion`, `generatedAt`, resolved standards-pack `source`, and the `aiModel` used or `deterministic-only`). On `AGENTS.md` the stamp lives inside the managed block so a re-run refreshes it in place. Readers can answer "what produced this?" without digging through git history (closes #82).
- **Standards-pack schema version**: The `standards-pack.yml` manifest now carries a required integer `schemaVersion` (the format version, distinct from the content `version`). On load Launchpad rejects any pack whose `schemaVersion` is missing or outside the supported range (`1`), reporting the manifest path, the version found, and the supported range. The MCP standards tools (`get_standards`, `compare_standards`, `get_audit_findings`) surface this as a new `incompatible_pack_schema` error; documented in `docs/standards-packs.adoc` and `docs/mcp-errors.adoc` (closes #84).

### Changed
- **MCP body-heavy tools now return references by default**: `get_standards`, `compare_standards`, and `get_documentation` now return references -- stable ids, project-relative paths, `.ai/` companion anchors, content hashes, and short summaries -- instead of inlining full rule/skill/checklist bodies and document content, so an in-session sandbox/indexer fetches bodies on demand rather than being re-flooded. This changes the default response shape of those tools. Model-backed tools (`get_workflows`, `get_systems`, `get_risks`, `get_project_overview`) additionally carry a pointer to `.launchpad/project.model.json`; `find_documentation` is unchanged. Set `LAUNCHPAD_MCP_RESPONSE_MODE=inline` (or `launchpad.mcp.response-mode=inline`) to restore the legacy inline responses. Documented in `docs/architecture.adoc` (closes #78).
- **Chunk-friendly standards companions**: The standards companion files (`.ai/engineering-rules.md`, `.ai/skills.md`, `.ai/checklists.md`) now emit one stable, descriptive heading per rule, skill, and checklist, each carrying an explicit `{#slug}` anchor derived from the record's stable `id` (not its title), so sandbox indexers chunk one item at a time with clean BM25 titles. Rule severity moved out of the heading into a `` `[must]` `` badge beneath it. Two ids that collapse to the same anchor slug now fail the scan rather than being silently de-duplicated (closes #80).
- **AppState Decomposition**: Refactored monolithic `AppState` into focused state components with independent reset logic.
- **Model-Driven Context**: `AGENTS.md` now projects operations, workflows, and risks directly from the virtualized model.
- **Build-tool-agnostic scanner**: A `BuildSystem` abstraction now resolves the build tool, so dependencies, stack label, and build commands follow Maven or Gradle instead of assuming Maven. The unsupported-project message now reads "Maven or Gradle".
- **TaskAdvisorService decomposed**: Broke the 1,150-line god class into focused collaborators (`StandardsSelector`, `TaskClassifier`, `InterviewQuestionPlanner`, `MarkdownPostProcessor`, `PromptFormatter`, `SynthesisPipeline`, `TaskTextSupport`); `TaskAdvisorService` is now a thin orchestrator wiring them together. Public entry points and behaviour unchanged.
- **Standardised MCP error envelope**: All `@McpTool` methods now emit failures through a single nested envelope (`error.code`, `error.type`, `error.message`, optional `error.remediation` and `error.details`) constructed via the new `McpError` record. Clients can branch on the coarse `type` (`INVALID_ARGUMENT`, `NOT_FOUND`, `PERMISSION_DENIED`, `UNSUPPORTED`, `RESOURCE_EXHAUSTED`, `INTERNAL`) without enumerating every code. Schema and full code registry documented in `docs/mcp-errors.adoc` (closes #76).
- **Manifest now required for a pack**: A standards source directory resolves only when it contains a `standards-pack.yml` manifest; the manifest must declare a supported `schemaVersion` (closes #84).

### Removed
- **Redundant Renderers**: Removed `BuildProfilesRenderer` and duplicate synthesis paths.
- **Legacy Artifacts**: Removed obsolete planning markers and redundant tests.
- **Legacy flat-file standards mode**: Dropped the manifest-less fallback that read bare `rules.yml` / `skills.yml` from the root of a standards source. Every pack is now a versioned manifest, so there is no second, unversioned load path (closes #84).

### Fixed
- **Responsive project picker on slow disks**: The on-demand live search now has a 3s wall-clock deadline; partial results render and the picker stays responsive even on slow or wide home directories (closes #77).
- **Path containment in write service**: Reject standards-pack file plans whose target escapes the project root, closing a write-anywhere primitive in the file-mutating component (closes #65).
- **Standards-pack remote URL validation**: The configured remote URL is now allow-listed (https://, ssh://, or `user@host:path`) and rejected if it carries shell metacharacters, whitespace, or `ext::` / `file://` schemes, removing a supply-chain ingress where a pasted URL could trigger arbitrary code execution via `git clone` (closes #66).

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
