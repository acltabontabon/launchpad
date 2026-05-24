# Launchpad

![Launchpad Demo](docs/resources/launchpad.gif)

> 📘 **Looking for step-by-step instructions?** [USAGE.md](USAGE.md) walks you from install through hooking Launchpad up to your AI tool as an MCP server, addressing projects by name, and troubleshooting the common gotchas.
>
> 💰 **Want to know what this actually saves you?** [BENEFITS.md](BENEFITS.md) breaks down the three benefit levers (cost, ambiguity, cross-reference) with tables, diagrams, and concrete token-cost numbers per session, per developer, and per team.

## What is Launchpad?

Launchpad is a small desktop tool that runs in your terminal. Point it at a **Spring Boot Java + Maven** project on your computer, pick the AI assistant you use (Claude or Cursor), and it produces the context files those assistants need to understand your codebase.

It runs entirely on your own machine. Your code never leaves your laptop.

> **Scope today: Spring Boot Java + Maven projects.** Launchpad is intentionally narrow while we build deep, deterministic Spring-aware extraction (controller graphs, bean wiring, configuration property inventory, actuator surface analysis, and more). Other stacks (Gradle, Kotlin, Python, Node, Terraform, Databricks, ...) are on the roadmap but not supported today; pointing the tool at one is rejected at the project selection screen with a clear message.

## Why does it exist?

AI coding assistants work best when they know what your project actually is - what it does, how it's organized, what libraries it leans on, and what conventions the team follows. Writing those guide files by hand is tedious, and most people skip it. So the assistant guesses, and the suggestions feel generic.

Launchpad does that setup for you. In a couple of minutes you get tailored, project-specific instructions that your assistant can read on every prompt.

### Deterministic-first, local-first

Launchpad is built on two principles:

- **Deterministic-first.** Project structure - the Maven model, Spring sub-stack signals, source layout, configuration files - is read by ordinary parsers, not by prompts. The local AI is a bounded synthesis layer that summarises and enriches what the deterministic pipeline already produced; it is never the primary path for discovery, classification, or parsing.
- **Local-first.** All deterministic extraction runs on your machine. The local AI does the cheap, repeatable cognitive work before any paid cloud agent sees the project, so the output is high-signal context for the agent rather than a generic dump.

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
2. Pick the Spring Boot Maven project folder you want to set up. If the folder is not a supported project, Launchpad refuses with a clear message before any scan runs.
3. Pick the assistant you use.
4. Wait while Launchpad reads through the project and drafts the files.
5. Review what it wrote, then save.

That's the whole loop. No accounts, no cloud, no API keys.

## Supported local-AI providers

| Provider           | Default base URL              | Notes                                                                                                             |
|--------------------|-------------------------------|-------------------------------------------------------------------------------------------------------------------|
| Ollama             | `http://localhost:11434`      | `ollama serve`, then `ollama pull <model>`.                                                                       |
| OpenAI-compatible  | depends on the server         | LM Studio (`http://localhost:1234/v1`), llama.cpp `server`, vLLM. Optional API key for gateways that require one. |
| Auto-detect        | -                             | Probes `/api/tags` then `/v1/models` and picks whatever responds.                                                 |

Full setup steps per server are in [docs/providers.md](docs/providers.md).

## Status

Launchpad is early and evolving. Expect rough edges, and please share feedback - what works, what doesn't, and which assistants you'd like to see supported next.

## License

Launchpad is released under the [MIT License](LICENSE). You're free to use, modify, and share it.
