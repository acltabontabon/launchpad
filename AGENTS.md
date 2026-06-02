# Launchpad - Contributor Guide

Launchpad is a local-AI-powered TUI tool that scans a Spring Boot project (Maven or Gradle) and generates vendor-neutral context files for AI coding assistants: `AGENTS.md`, `.ai/` companion files, and agent-native projections (Claude skills, Cursor `.mdc`, Windsurf `.rules`). It runs as a Spring Boot app with no web server. GraalVM native image is supported.

**Scope:** Spring Boot Java projects on Maven or Gradle. All other stacks are rejected at `ProjectSupportDetector` before any scanner phase runs.

## Commands

```bash
./mvnw spring-boot:run          # run (requires Ollama on port 11434)
./mvnw package                  # build JAR
./mvnw -Pnative native:compile  # build GraalVM native image
./mvnw test                     # unit + scanner tests (no Ollama needed)
./mvnw test -Peval              # full LLM eval against local Ollama
./mvnw test -Dtest=ClassName    # single class
./mvnw compile                  # fast compile check
```

Ollama setup: `ollama serve` + `ollama pull qwen2.5-coder:7b-instruct`

## Architecture

**Scan pipeline** - runs in a background thread in `LaunchpadRunner.triggerScanIfNeeded()`:

```
ProjectSupportDetector.detect()        gate: rejects non-Spring Boot projects (Maven or Gradle)
  → ProjectScanner.scan()              walks file tree → produces ProjectContext
      ├─ BuildSystemDetector           resolves Maven/Gradle: deps, stack label, build commands
      ├─ MavenModelParser              structured pom.xml parse (deps, parent, plugins)
      ├─ GradleBuildParser             structured build.gradle(.kts) parse (plugins, deps)
      ├─ SpringProfileDetector         detects sub-stack facets from dependencies
      ├─ EndpointExtractor             HTTP routes from @RequestMapping annotations
      └─ DocumentationDetector         Markdown/AsciiDoc files with purpose tagging
  → ScanStore.save()                   persists ProjectContext to .launchpad/scan.json
  → AuditService.run()                 runs check:-enabled rules; writes audit.md + SARIF
  → ContextTemplateEngine.buildFiles() assembles GeneratedFile list
      ├─ SectionSynthesizer            bounded per-section LLM synthesis
      ├─ CompanionFileBuilder          .ai/*.md companion files
      ├─ AgentProjection[]             agent-native projections (Claude, Cursor, Windsurf)
      └─ FilePlan.compute()            per-file write action (WRITE_NEW/MERGE/SKIP/OVERWRITE)
  → ReviewView                         user approves; WriteService applies plans with backup
```

Deterministic extractors run first and produce structured data. The local AI only synthesizes narrative from that data - it never drives discovery or classification.

**TUI state machine** - `AppState.currentScreen` drives which view renders:

```
WELCOME → PROJECT_SELECT → SCANNING → REVIEW
                              ↓ (if /new-task)
                          TASK_INPUT → TASK_INTERVIEW → TASK_RESULT
```

`AppState` is the only shared state between the TUI render thread (~12 fps) and the background scan thread. All fields are `volatile` or `Atomic*` - the render loop reads them without locking.

**Key packages:**

| Package | Role |
|---|---|
| `tui/` | Render loop, view state machine, `AppState` |
| `scanner/` | `ProjectSupportDetector` (gate), `ProjectScanner`, `BuildSystem` abstraction (Maven/Gradle), `MavenModelParser`, `GradleBuildParser`, `SpringProfileDetector` |
| `ai/` | `ContextGeneratorService` (synthesis), `PromptSelector`, `FacetPromptComposer`, `ProviderHealthChecker` |
| `template/` | `ContextTemplateEngine`, `FilePlan`, `WriteService`, `MergeMarkers`, `AgentProjection` impls |
| `standards/` | `StandardsLoader` - resolves rules/skills/checklists from remote git or per-project override |
| `audit/` | `AuditService`, `PatternChecker`, `ImportChecker`, `LlmChecker` - writes `audit.md` + SARIF |
| `mcp/` | `LaunchpadMcpTools` / `LaunchpadMcpResources` - active only under the `mcp` Spring profile |
| `task/` | `TaskAdvisorService` - drives `/new-task` interview and finalization via local AI |

## Code Style

- You MUST use Java 25 language features - records, sealed types, pattern matching, text blocks, `var`
- You MUST use constructor injection - never `@Autowired` on fields
- You MUST use records for data carriers (`ProjectContext`, `Rule`, `Skill`, `Dependency`, etc.)
- You MUST use `assertThat(...)` from AssertJ in tests - not JUnit `assertEquals`
- You MUST instantiate collaborators directly in unit tests (`new PatternChecker()`) - no `@SpringBootTest` except integration tests
- You MUST keep prompt content in `src/main/resources/prompts/` - never hardcode prompt text in Java
- You MUST use imports instead of fully qualified names in code
- You SHOULD NOT add comments unless the *why* is non-obvious - no Javadoc on internal classes
- You SHOULD NOT name a method `getXXX` if it's not a simple field accessor - prefer `compute`, `resolve`, `build`, `extract`

## Guardrails

- You MUST NOT add provider-specific logic to `ContextTemplateEngine`, `CompanionFileBuilder`, or any canonical renderer - agent-specific files are produced only by `AgentProjection` implementations
- You MUST NOT write project files directly - all writes go through `WriteService`, which runs `FilePlan.compute()` and backs up anything it changes
- You MUST NOT add plain mutable fields to `AppState` - all fields must be `volatile`, `AtomicInteger`, or `AtomicReference`
- You MUST NOT add defensive `if (isSpring)` checks downstream of `ProjectSupportDetector` - the gate guarantees a valid Spring Boot project (Maven or Gradle) for all downstream code; the build tool is resolved via `BuildSystemDetector`
- You MUST NOT add silent fallbacks to AI calls - a missing prompt template is a bug and should fail loudly
- You MUST NOT add bundled default standards - if no remote repo or per-project override is configured, `ContextTemplateEngine` emits a placeholder and the scan completes normally