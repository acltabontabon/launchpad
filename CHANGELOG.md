# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- Inline path autocomplete on the Project Select screen. As you type, a dimmed ghost suffix shows the next matching directory; press `Tab` (or `Right`) to accept it and continue into the chosen subdirectory.
- Runtime Ollama configuration via TUI settings screen (press `c` from the Welcome screen). Persists to `~/.launchpad/config.properties` and hot-reloads the live `ChatClient` without restarting the app.
- Engineering standards (rules and workflow skills) are now defined in YAML rather than hardcoded Java. Bundled defaults ship inside the JAR at `standards/rules.yml` and `standards/skills.yml`; teams override per project by checking `.launchpad/standards/*.yml` into the target repo.
- Curated workflow skills now generate as real Claude Code skills: one `.claude/skills/<id>/SKILL.md` file per entry in `skills.yml`, with proper frontmatter (`name`, `description`). Skills become invocable as `/<skill-id>` in Claude Code instead of being descriptive prose inside `CLAUDE.md`. The CLAUDE.md `## Skills` section now points at `.claude/skills/` and only retains an inline `### Project-Specific Skills` subsection for LLM-generated suggestions.
- Dedicated `.cursor/rules/skills.mdc` output for the Cursor target, carrying the same curated skills (Cursor has no invocable skill concept, so they remain rule-style references).
- Remote git source for engineering standards. Set `launchpad.standards.remote.url` (via `/settings` or `~/.launchpad/config.properties`) and Launchpad clones the repo into `~/.launchpad/standards-cache/<hash>/` with a 1-hour TTL. Falls back to the per-project `.launchpad/standards/` override when the cache is unavailable; auth uses the user's existing git credentials (SSH agent, credential helper, gh CLI).
- Welcome screen badge for the remote standards source state (synced / offline using cache / fetch failed / local only), mirroring the Ollama readiness badge.
- Manifest-aware standards pack format. Source directories with a `standards-pack.yml` resolve through the manifest's `includes` lists, allowing rules/skills/checklists/prompts to be split across many YAML files. Legacy flat `rules.yml` / `skills.yml` at the source root still works for per-project overrides.
- Checklists and reusable prompt templates as new standards kinds, loaded from the manifest's `checklists:` and `prompts:` includes. Generate `.ai/checklists.md` + `.ai/prompts.md` for Claude and `.cursor/rules/checklists.mdc` + `.cursor/rules/prompts.mdc` for Cursor.
- Adapter-driven primary context file. A pack's `adapters/<target>.yml` declares the entry-point file's path, the sections to embed (`rules` / `skills` / `checklists` / `prompts`), and `.mdc` frontmatter for Cursor. Secondary discoverability files (per-skill `SKILL.md`, `engineering-rules.md`, etc.) continue at hardcoded paths.
- Richer rule rendering: severity chips (`must` / `should` / `avoid` / `never`) on rule headings and italic `_Why:_` rationale lines. Rule schema accepts the new `content` field as a `@JsonAlias` for the legacy `description`.
- Richer skill rendering: `title` becomes the heading when present, and an "Expected Output" block lists each `output_expectations` entry.
- Empty-state placeholder text in generated files when no source resolves a kind, telling the user how to configure the remote URL or per-project override.
- `/settings` slash command on the Welcome screen, replacing the `c` keyboard shortcut. Includes a third field for the remote standards URL.
### Changed
- `ContextTemplateEngine` is now a Spring component that depends on a `StandardsLoader`. Rules and skills are loaded fresh per scan from the target project's override path or the bundled classpath defaults.
- Target Select screen now lists the full set of generated files for each target, including `.claude/skills/<id>/SKILL.md` for Claude and `.cursor/rules/skills.mdc` for Cursor. The hardcoded display had drifted from what the engine actually writes.
- Generated `.ai/index.md` now enumerates every curated skill as an invocable `/<skill-id>` entry alongside its trigger, giving Claude a top-level table of contents that links into `.claude/skills/`.
- `StandardsLoader` resolves standards through two source tiers (remote cache, per-project override) and two formats per tier (manifest-aware pack mode, legacy flat-file). Each kind (rules / skills / checklists / prompts / adapter) walks the tiers independently.
### Removed
- Project-info footer bar from the TUI. The selected path and target were already implied by the header stepper, and the 3 rows are now reclaimed for the active view's content area.
- `EngineeringRule` enum. The 10 principles it carried are now in `src/main/resources/standards/rules.yml` and are editable without recompiling.
- Bundled `src/main/resources/standards/rules.yml` and `skills.yml`. Launchpad now ships with no opinionated defaults; teams provide their own pack via remote git or per-project override.


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
