# Launchpad

A friendly terminal companion that gets your project ready for AI coding assistants.

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
- A pulled model (the default is `llama3.2`, but you can switch to any model Ollama supports).

## Configuration

Out of the box Launchpad talks to Ollama at `http://localhost:11434` using the `llama3.2` model. If Ollama lives somewhere else - a homelab box, a remote dev machine, or just a different port - you can point Launchpad at it in any of three ways:

1. **In the TUI (recommended)** - press `c` on the Welcome screen. Edit the base URL and model, hit Enter to save. Changes apply immediately, no restart, and persist to `~/.launchpad/config.properties`.
2. **Edit the config file directly** - `~/.launchpad/config.properties` (created on first save). Keys are `spring.ai.ollama.base-url` and `spring.ai.ollama.chat.options.model`. Note: Java's properties format escapes `:` in URLs, so the file shows `http\://host\:11434`; that round-trips correctly when read back.
3. **Environment variable or CLI argument** - useful for one-off overrides or running in CI:
   ```bash
   SPRING_AI_OLLAMA_BASE_URL=http://remote:11434 ./mvnw spring-boot:run
   # or
   ./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.ai.ollama.base-url=http://remote:11434
   ```

The TUI config file takes precedence over the bundled defaults; env vars and CLI args feed the defaults, so they win only when no user file exists.

## Who it's for

- Developers who want their AI assistant to actually understand the project, not just the file on screen.
- Teams who want a shared, consistent set of coding rules baked into every repo.

## Status

Launchpad is early and evolving. Expect rough edges, and please share feedback - what works, what doesn't, and which assistants you'd like to see supported next.

## License

Launchpad is released under the [MIT License](LICENSE). You're free to use, modify, and share it.
