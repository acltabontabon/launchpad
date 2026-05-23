# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Fixed
- **MCP server now works under GraalVM native image.** Previously `launchpad mcp` ran only on the JVM jar because `@ConditionalOnProperty` gating `LaunchpadMcpTools`, `LaunchpadMcpResources`, and `LaunchpadRunner` was evaluated at Spring AOT build time (with no `launchpad.mode` set), pruning the MCP beans and shipping a binary that served an empty tool list. The conditions were removed; Spring AI's annotated-bean AOT processor now sees the MCP beans and registers every `@McpTool` parameter type for native reflection. The TUI runner early-returns in MCP mode so it does not try to grab the terminal. A single native binary now serves both modes.

### Added
- **Documentation discovery and MCP exposure:** The project scan now detects MkDocs (`mkdocs.yml`), Antora (`antora.yml`), and loose Markdown / AsciiDoc / RST files under `docs/`, `documentation/`, `doc/`, and `site/`. Markdown and AsciiDoc are first-class peers - both are titled (H1 / `=` document title) and surfaced through three new MCP tools (`list_documentation`, `find_documentation`, `get_documentation`) and a new `launchpad://docs/{name}` resource. `find_documentation` without a `project` argument searches every registered project, so an AI working on one service can discover the docs of a sibling library referenced via `relatedTo`.
- **Cross-project reference as a first-class citizen:** Generated context files now include a `## Workspace` section listing sibling Launchpad-managed projects so AI agents discover them without prompting. New MCP tools `get_file(project, path)` and `list_files(project, glob)` let agents read sibling source on demand with path-traversal protection. `compare_standards(projectA, projectB)` partitions rules / skills / checklists into common, divergent, a-only, and b-only buckets via content-hash diff. `list_projects` accepts a `workspace` filter.
- **Per-project relationship metadata:** Optional `.launchpad/project.yml` file (schema: `tags`, `workspace`, `relatedTo`) overlaid into the registry on every read. Travels with the project, survives `~/.launchpad/` wipes, never mutates `projects.json`.
- **MCP resources for tree-browsing clients:** `launchpad://projects` (registry listing) and `launchpad://scan/{name}` (per-project scan summary) alongside the existing `launchpad://audit/{path}`.
- **CLI project management:** `launchpad register <path>` adds a single project to the registry; `launchpad register --scan <dir>` enrolls every subdirectory under `<dir>` that already has a `.launchpad/scan.json`; `launchpad unregister <name>` removes one entry. Idempotent and registry-mutating only.
- **MCP default-project handshake:** `LAUNCHPAD_DEFAULT_PROJECT` env var (or `launchpad.mcp.default-project` system property) pins a default project so MCP tool calls work without specifying `project` every time.
- **`/new-task` hardening:** System+user prompt split with one-shot examples; standards scope-filtered once at interview entry and cached across turns (no more full-pack reloads each round); real per-chunk streaming on the synthesise call with `launchpad.task.interview-timeout` / `launchpad.task.finalize-timeout` budgets; new `TaskOutputValidator` with a single retry on structural failure; per-task warnings surface to the result view; saved-file timestamps now ms-precision to avoid collisions; runaway round cap tightened from 15 to 8.
- **Pluggable local-AI providers:** Support for LM Studio, llama.cpp, vLLM, and OpenAI-compatible endpoints (#13).
- **Provider auto-detection:** `launchpad.ai.provider` set to `auto` probes Ollama then OpenAI; settings take effect without restart.
- **Provider Health:** New `ProviderHealthChecker` supports both Ollama and OpenAI listing formats.
- **Databricks framework path:** Support for Terraform-deployed data pipelines, DLT, Python, and SQL facets.
- **Facet-based prompts:** `FacetPromptComposer` replaces framework-specific composers for better scaling.
- **Composable Spring prompts:** Framework-agnostic system using base templates and per-facet additions.

- **MCP Integration**
  - AI tool flow in `/settings` for auto-config (Claude, Cursor).
  - MCP server mode via `launchpad mcp` exposing tools and SARIF.
  - Registry-aware tools with short-name support.
  - `ProjectRegistry` for persistent project addressing and auto-enrollment.
  - New `/projects` command for registry management.

- **Automated Audit System**
  - Integrated Audit phase with live progress and TUI summary.
  - Standards engine with `PatternChecker`, `ImportChecker`, and `LlmChecker`.
  - `check:` blocks in `Rule` schema for automated logic.
  - `ScanStore` for persisting scans to `.launchpad/scan.json`.

- **TUI & User Experience**
  - Rocket lift-off animation on Welcome screen.
  - Path-prefix matches and autocomplete in Project Select.
  - `SAVED` chips in Review screen for session-written files.
  - New `BENEFITS.md` (token-cost analysis) and `USAGE.md` (setup guide).

### Changed
- **Provider-neutral config:** Renamed keys to `launchpad.ai.*` (provider, base-url, model, api-key).
- **Scoped Prompts:** Narrowed specialized prompts to Spring; others use generic templates.
- **Rules Refactor:** Renamed "Cursor rules" to tool-agnostic "Rules" across prompts and files.
- **Visual & UI Refinement**
  - Redesigned TUI using "Cosmic Console" visual system.
  - Simplified Welcome screen: Reworked tagline and moved help to `/help`.
  - Improved Header: Breadcrumb shows active model.
  - Optimized Review Screen: Consolidated warnings and moved save status.
  - Enabled diff views for new files in Review.

- **Core Logic & AI**
  - Default model to `qwen2.5-coder:7b`.
  - Improved grounding with mandatory file-list blocks and anti-invention rules.
  - `OutputValidator` automatically strips hallucinated file references.

### Fixed
- **AI Timeouts:** Added explicit connect/read timeouts to prevent TUI freezes (#1).
- **I/O Safety:** Unreadable files now report `UNREADABLE` instead of silently skipping (#6).
- **Merge Integrity:** Corrupted markers now report `CORRUPTED` to prevent content loss (#3).
- **Native Image:** Fixed failures in Summary/Assemble phases (templates & reflection hints).
- **TUI Stability:** Fixed Welcome screen freeze and palette alignment issues.
- **Navigation:** Fixed `/new-task` labels and settings column alignment.

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
