# Launchpad

A friendly terminal companion that gets your project ready for AI coding assistants.

> 📘 **Looking for step-by-step instructions?** [USAGE.md](USAGE.md) walks you from install through hooking Launchpad up to your AI tool as an MCP server, addressing projects by name, and troubleshooting the common gotchas.
>
> 💰 **Want to know what this actually saves you?** [BENEFITS.md](BENEFITS.md) breaks down the three benefit levers (cost, ambiguity, cross-reference) with tables, diagrams, and concrete token-cost numbers per session, per developer, and per team.

## What is Launchpad?

Launchpad is a small desktop tool that runs in your terminal. Point it at any project on your computer, pick the AI assistant you use (Claude or Cursor), and it produces the context files those assistants need to understand your codebase.

It runs entirely on your own machine. Your code never leaves your laptop.

## Why does it exist?

AI coding assistants work best when they know what your project actually is - what it does, how it's organized, what libraries it leans on, and what conventions the team follows. Writing those guide files by hand is tedious, and most people skip it. So the assistant guesses, and the suggestions feel generic.

Launchpad does that setup for you. In a couple of minutes you get tailored, project-specific instructions that your assistant can read on every prompt.

## What it produces

Depending on the assistant you choose, Launchpad writes:

- **For Claude Code** - a `CLAUDE.md` at your project root plus an `.ai/` folder with supporting notes.
- **For Cursor** - a `.cursorrules` file plus a `.cursor/rules/` folder with focused rule files.

Each file describes your project in plain language and includes a baseline set of engineering principles and workflow skills so the assistant follows sensible defaults from the start.

## Standards live with your project

Launchpad treats engineering rules and workflow skills as your team's durable knowledge. They are defined in YAML, not embedded in a vendor's tool.

- **Bundled defaults** ship inside Launchpad: 10 engineering rules (clean code, SOLID, fail-fast, and so on) and a handful of generic workflow skills (add-feature, fix-bug, add-test, refactor-module, review-pr).
- **Override per project** by creating `.launchpad/standards/rules.yml` and/or `.launchpad/standards/skills.yml` inside the target project. When those files are present, Launchpad uses them in place of the bundled defaults. Check them into the repo so everyone on the team shares the same standards.

Because the standards are vendor-neutral, switching from Claude to Cursor (or to the next tool) does not require rewriting them - Launchpad re-renders the same source into whatever shape the new assistant expects.

## How you'll use it

1. Start the app from your terminal.
2. Pick the project folder you want to set up.
3. Pick the assistant you use.
4. Wait while Launchpad reads through the project and drafts the files.
5. Review what it wrote, then save.

That's the whole loop. No accounts, no cloud, no API keys.

## What you need

- A computer running macOS, Linux, or Windows.
- [Ollama](https://ollama.com) installed and running locally - this is the engine that powers the writing. Launchpad talks to it on your machine; nothing is sent anywhere else.
- A pulled model (the default is `qwen2.5-coder:7b`, but you can switch to any model Ollama supports).

## Configuration

Out of the box Launchpad talks to Ollama at `http://localhost:11434` using the `qwen2.5-coder:7b` model (a code-aware 7B model chosen for its grounded handling of file paths and project structure - swap in any model Ollama supports if you prefer). If Ollama lives somewhere else - a homelab box, a remote dev machine, or just a different port - you can point Launchpad at it in any of three ways:

1. **In the TUI (recommended)** - press `c` on the Welcome screen. Edit the base URL and model, hit Enter to save. Changes apply immediately, no restart, and persist to `~/.launchpad/config.properties`.
2. **Edit the config file directly** - `~/.launchpad/config.properties` (created on first save). Keys are `spring.ai.ollama.base-url` and `spring.ai.ollama.chat.options.model`. Note: Java's properties format escapes `:` in URLs, so the file shows `http\://host\:11434`; that round-trips correctly when read back.
3. **Environment variable or CLI argument** - useful for one-off overrides or running in CI:
   ```bash
   SPRING_AI_OLLAMA_BASE_URL=http://remote:11434 ./mvnw spring-boot:run
   # or
   ./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.ai.ollama.base-url=http://remote:11434
   ```

The TUI config file takes precedence over the bundled defaults; env vars and CLI args feed the defaults, so they win only when no user file exists.

## Use Launchpad as an MCP server

Once you have generated context for a project, Launchpad can stay useful in your editor too. The `launchpad mcp` subcommand starts a Model Context Protocol server over stdio. Any MCP-aware client (Claude Code, Cursor, Cline, Continue, Zed, and others) can register it and call four tools that are unique to Launchpad:

- `list_projects()` - lists every project the user has used Launchpad on (name, path, stack, last-scanned time). Call this first when the user says "my projects" or doesn't remember a path.
- `scan_project(project)` - returns a structured summary (stack, framework, dependencies, packages, source files). Cached for 24 hours so repeat calls are instant. `project` accepts a short name from `list_projects` or an absolute path.
- `get_standards(project)` - returns the rules, skills, and checklists that apply to the project, as structured data.
- `get_audit_findings(project)` - runs the standards audit (or returns the cached SARIF) and lists every violation with file path, line, and rule.

A SARIF resource at `launchpad://audit/<abs-project-path>` exposes the same findings in the OASIS-standard format that VS Code SARIF Viewer, IntelliJ Qodana, and GitHub code-scanning consume natively.

Register Launchpad with your MCP client using the same shape every client accepts:

```json
{
  "launchpad": {
    "command": "java",
    "args": ["-jar", "/abs/path/to/launchpad-0.1.0-SNAPSHOT.jar", "mcp"]
  }
}
```

### Address projects by name, not by path

The registry at `~/.launchpad/projects.json` enrolls every project the TUI scans (no extra step - it happens automatically when you reach the Review screen of `/init` or `/new-task`). From any MCP client you can then say:

> "Use launchpad to audit Pragmata."
> "Which of my projects in launchpad use Spring Boot? Pull the standards for the newest one."

The agent calls `list_projects` to learn the names, then passes the chosen name into the other tools as `project`. No absolute paths typed, no per-session state on the server (every call is self-contained, so concurrent sessions in Claude Code and Cursor cannot confuse each other). Browse and prune the registry from the TUI via `/projects` (`enter` re-opens a project, `d` removes an entry, `p` prunes paths that no longer exist).

> **Privacy note:** The registry stays on your machine, but any value `list_projects` returns is visible to the AI tool that called it. If you would rather not have a particular repo path leak to a cloud model, remove it via `/projects → d` or edit `~/.launchpad/projects.json` directly.

> **Note:** MCP mode is currently supported on the JVM jar. The GraalVM native binary builds successfully and runs the TUI just fine, but the Spring AI MCP server (2.0.0-M6) bootstrap doesn't yet reflect cleanly under AOT - the tool schema generator fails when the JSON schema for tool inputs is built at runtime. Use `java -jar` for `mcp`; the JVM startup is sub-second on a warm system.

Launchpad deliberately does not expose file reads, file writes, or git operations - the official `mcp-server-filesystem` and `mcp-server-git` already cover those. Use Launchpad's MCP server for what is unique to Launchpad: project intelligence and standards enforcement, computed locally with no cloud tokens spent.

## Who it's for

- Developers who want their AI assistant to actually understand the project, not just the file on screen.
- Teams who want a shared, consistent set of coding rules baked into every repo.

## Status

Launchpad is early and evolving. Expect rough edges, and please share feedback - what works, what doesn't, and which assistants you'd like to see supported next.

## License

Launchpad is released under the [MIT License](LICENSE). You're free to use, modify, and share it.
