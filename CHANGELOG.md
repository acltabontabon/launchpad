# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- **Pluggable local-AI provider so Launchpad now works with LM Studio,
  llama.cpp's server, vLLM, and any other OpenAI-compatible local endpoint**
  (#13). A new `launchpad.ai.provider` setting (`ollama` / `openai-compatible` /
  `auto`) selects the concrete backend; `auto` probes Ollama's `/api/tags`
  first, then OpenAI's `/v1/models`, and surfaces the resolved provider on the
  Welcome readiness badge. `LlmProviderRouter` (replacing
  `OllamaClientProvider`) holds a volatile `ChatModel` delegate and hot-swaps
  it on `LlmProviderSettingsChanged` so a provider switch via /settings takes
  effect without restarting the JVM. `ProviderHealthChecker` replaces
  `OllamaHealthChecker` and speaks both endpoints' model-listing formats.
  Settings screen gains a Provider toggle row and an optional API key field
  (blank = no Authorization header, matching unauthenticated local servers).
  Docs: `docs/providers.md` covers Ollama, LM Studio, llama.cpp server, and
  vLLM setup.

### Changed
- **Config keys renamed to provider-neutral `launchpad.ai.*`.** The user
  config at `~/.launchpad/config.properties` now writes
  `launchpad.ai.provider`, `launchpad.ai.base-url`, `launchpad.ai.model`, and
  optional `launchpad.ai.api-key`. Existing configs that only carry the legacy
  `spring.ai.ollama.*` keys still load on first run (pinned to provider=ollama)
  and are rewritten on next save. The `LAUNCHPAD_LLM_API_KEY` env var
  overrides any api-key in the file so secrets can stay out of plaintext.

### Fixed
- **Ollama calls now have explicit timeouts so the TUI no longer freezes on a
  stalled local model** (#1). A new `LaunchpadAiProperties`
  (`launchpad.ai.connect-timeout`, `launchpad.ai.read-timeout`, defaults
  10s / 2m) is bound through `@ConfigurationProperties`. `OllamaClientProvider`
  wires those timeouts into both the `RestClient.Builder` (covers the
  synchronous `.call()` path used by the `/new-task` interview) and the
  `WebClient.Builder` (Netty connect + response timeout for streaming).
  `ContextGeneratorService.streamPrompt` adds a Reactor `.timeout(readTimeout)`
  on the streaming chain so a daemon that opens a connection but stops
  emitting tokens surfaces a `TimeoutException` within budget instead of
  hanging on `blockLast()`. `LaunchpadRunner.buildLlmErrorMessage` translates
  the timeout into a "local model stalled - check Ollama, or raise
  `launchpad.ai.read-timeout`" hint, and the scan catch routes its message
  through the same helper so the Scanning screen shows the same advice.
- **Unreadable target files no longer silently map to `SKIP`** (#6).
  `FilePlan.compute` previously swallowed `IOException` from `Files.readString`
  and returned `Action.SKIP` with `existingContent = null`, which made
  permission-denied, locked, and transient I/O failures indistinguishable from
  a normal "exists, no markers" file and risked clobbering the file via
  `WRITE_NEW` on a later re-run. A new `Action.UNREADABLE` now carries the
  underlying error message, never writes, and is treated as skipped by
  `WriteService`. The Review screen shows the IOException detail in the preview
  pane and refuses overwrite/merge overrides for unreadable files - only `x`
  (skip) is accepted.
- **Corrupted managed-block markers no longer silently discard user content
  on merge** (#3). `MergeMarkers.classify` now reports `NONE` / `VALID` /
  `CORRUPTED`; reversed-order markers, duplicates, and one-sided markers
  all map to `CORRUPTED`. `FilePlan.compute` resolves corrupted files to a
  new `Action.CORRUPTED` whose `resolvedContent()` returns the existing
  content unchanged. The Review screen renders a `CORRUPTED` chip and
  requires an explicit `o` (overwrite) keypress to write; `m` (merge) is
  refused. `mergeInto` no longer silently rewrites a malformed file - it
  throws so the corruption surfaces instead of eating prelude/trailer text.

### Added
- **Databricks framework path** (Terraform-deployed data pipelines, with
  DLT / Python / SQL facets)
  - New `DatabricksProfile` record on `StackProfile`, populated when the
    project is classified as Databricks. Four facets composed from positive
    signals: `terraform-deployment` (any `*.tf` files), `dlt-pipeline`
    (`import dlt` / `@dlt.table` in Python or `CREATE STREAMING LIVE TABLE`
    in SQL), `python-source` (any `.py`), `sql-source` (any `.sql`).
  - Classification is a post-walk override: a Python / SQL / Terraform
    project is promoted to `framework = "Databricks"` when any of
    `databricks.yml`, the `databricks/databricks` Terraform provider,
    notebook-magic header (`# Databricks notebook source`), or DLT markers
    in source files fire. Spring projects are never promoted (Spring wins);
    Python projects with an existing framework (Django / FastAPI / Flask /
    ...) are never promoted.
  - New `databricks/base/{summary,skills,rules}.txt` + four
    `databricks/facets/*.txt` files. Same `=== SUMMARY/SKILLS/RULES ===`
    delimited section format as Spring.
  - New `DependencyExtractor` Terraform support: parses
    `terraform { required_providers { ... } }` blocks across `*.tf` files
    and emits `Dependency` records with scope `"provider"` (e.g.
    `databricks/databricks@1.41.0`). Regex-based; no HCL library dep.
  - New `databricks-recon` eval fixture under
    `src/test/resources/fixtures/databricks-recon/` exercises the full
    pipeline end-to-end (`pyproject.toml`, three `.tf` files declaring the
    databricks provider, a DLT notebook with `@dlt.table` / `@dlt.expect`,
    plain Python publish script, two parameterised SQL files).
  - Existing `spring-boot`, `spring-boot-starter`, `nextjs`, `fastapi`,
    `rust-cli` fixtures now carry quality-guard assertions: none of them may
    be misclassified as Databricks. Pairs with the Spring negative-guards
    to prevent silent regressions when signals expand.

- **`FacetPromptComposer` replaces `SpringPromptComposer`**
  - Composer is now framework-agnostic: `compose(kind, frameworkSlug, facetIds)`
    reads `prompts/<slug>/base/<kind>.txt` and inserts matching facet sections.
    Spring and Databricks use the same instance. Adding the next framework
    requires no new Java class for the composer - drop a `prompts/<slug>/`
    tree and a routing branch in `PromptSelector`.
  - `ScanSignals` consolidates per-walk file-tree signals (previously a pile
    of `boolean[1]` holders in `ProjectScanner`). Both `SpringProfileDetector`
    and `DatabricksProfileDetector` read from it.

- **Composable Spring prompt system**
  - New `SpringProfile` record on `StackProfile`, populated from the dependency
    list when the framework is Spring. Carries sub-stack signals: web (mvc /
    webflux), persistence (jpa / jdbc / r2dbc), spring-ai, spring-cloud,
    spring-security, messaging (kafka / rabbit), graalvm-native, and
    starter-library.
  - New `SpringPromptComposer` that assembles each generation prompt from a
    base template (`prompts/spring/base/{summary,skills,cursor-rules}.txt`)
    plus per-facet additions (`prompts/spring/facets/<id>.txt`). Lets the
    Spring prompt scale by composition instead of single-file duplication.
  - Composed facet list is logged at INFO per generation for visibility.
  - `starter-library` facet distinguishes Spring Boot auto-configuration
    libraries from runnable applications. Detected via three positive,
    library-exclusive signals (any one is sufficient): presence of
    `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
    legacy `META-INF/spring.factories`, or an `@AutoConfiguration` annotation
    in any `.java` source file (catches libraries whose generated imports
    file hasn't been built yet). When set, the facet reframes the prompt for
    the library perspective (auto-config classes, `@ConditionalOn*` gating,
    `@ConfigurationProperties` surface, never-shadow-user-beans guidance) and
    is composed first so the framing applies to every later sub-stack facet.
  - New `spring-boot-starter` eval fixture under
    `src/test/resources/fixtures/spring-boot-starter/` exercises the full
    detection pipeline end-to-end (real `pom.xml`, real
    `AutoConfiguration.imports`, real `@AutoConfiguration`-annotated source).
    The existing `spring-boot` app fixture now carries an explicit
    negative-guard assertion: it must NOT be misclassified as a starter
    library. This pairing protects against future signal additions that
    might silently misfire on regular apps.

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
- **Narrowed specialized prompts to Spring only.** Deleted the per-framework
  templates for Next.js, Django, FastAPI, and Rails. Non-Java projects now use
  the generic prompt. This is a deliberate scope cut so the Spring path can be
  hardened end-to-end before broadening again. `PromptSelector` delegates to
  `SpringPromptComposer` for the three generation kinds (summary, skills,
  rules) when the detected framework is Spring; otherwise it falls back to
  the generic per-kind template.

- **De-vendored the rules prompt pipeline.** What was named after Cursor is
  now named after the output format. Concretely:
  - `PromptSelector.Kind.CURSOR_RULES` → `PromptSelector.Kind.RULES`.
  - Directory `src/main/resources/prompts/cursor-rules/` → `prompts/rules/`.
  - Base file `prompts/spring/base/cursor-rules.txt` → `prompts/spring/base/rules.txt`.
  - Facet section header `=== CURSOR ===` → `=== RULES ===` in all 12 facet files.
  - Generic and Spring rules prompts rewritten to drop the
    "writing .cursorrules for a Cursor AI user" framing; they now produce
    tool-agnostic rules consumable by any AI coding agent (Claude Code,
    Cursor, Copilot, Aider, ...). Vendor-specific output decisions stay at
    the file-emission layer (`ContextTemplateEngine`), not in the prompts.

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
- Projects screen no longer renders empty under GraalVM native because of missing reflection hints for `ProjectRegistry$Document`, `RegisteredProject`, and the `ScanStore` record graph (`ProjectContext`, `StackProfile`, `SpringProfile`, `Dependency`, `PackageSummary`). `ProjectRegistry.loadFromDisk()` and `ScanStore.load()` previously swallowed the deserialization `IOException` and returned an empty list, so the failure mode was invisible; both now log at WARN with the cause, and the Projects view renders a yellow warning when the registry could not be read instead of the misleading "no projects yet" empty state.
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
