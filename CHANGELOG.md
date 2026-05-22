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
### Changed
- `ContextTemplateEngine` is now a Spring component that depends on a `StandardsLoader`. Rules and skills are loaded fresh per scan from the target project's override path or the bundled classpath defaults.
- Target Select screen now lists the full set of generated files for each target, including `.claude/skills/<id>/SKILL.md` for Claude and `.cursor/rules/skills.mdc` for Cursor. The hardcoded display had drifted from what the engine actually writes.
- Generated `.ai/index.md` now enumerates every curated skill as an invocable `/<skill-id>` entry alongside its trigger, giving Claude a top-level table of contents that links into `.claude/skills/`.
### Removed
- Project-info footer bar from the TUI. The selected path and target were already implied by the header stepper, and the 3 rows are now reclaimed for the active view's content area.
- `EngineeringRule` enum. The 10 principles it carried are now in `src/main/resources/standards/rules.yml` and are editable without recompiling.


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
