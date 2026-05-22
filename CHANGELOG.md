# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- Inline path autocomplete on the Project Select screen. As you type, a dimmed ghost suffix shows the next matching directory; press `Tab` (or `Right`) to accept it and continue into the chosen subdirectory.
- Runtime Ollama configuration via TUI settings screen (press `c` from the Welcome screen). Persists to `~/.launchpad/config.properties` and hot-reloads the live `ChatClient` without restarting the app.
### Removed
- Project-info footer bar from the TUI. The selected path and target were already implied by the header stepper, and the 3 rows are now reclaimed for the active view's content area.


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
