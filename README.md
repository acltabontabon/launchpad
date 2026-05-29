# Launchpad

Cloud AI burns tokens re-reading your project on every task. Launchpad front-loads that work -- scans locally, resolves your engineering standards, and hands the agent a grounded brief before the first prompt runs.

Standards-first, not standards-after.

<img src="docs/resources/launchpad.gif" width="800" alt="Launchpad Demo">

<img src="docs/resources/launchpad-commands.png" width="800" alt="Launchpad Commands">

---

## How it works

![Launchpad Overview](docs/resources/launchpad-overview.drawio.png)

---

## What it does

- **Scans your project deterministically** -- extracts structure, dependencies, endpoints, and documentation without guessing
- **Resolves your engineering standards** -- rules, skills, and checklists from a YAML pack you control
- **Generates grounded context files** -- `AGENTS.md` + `.ai/*` ready before the agent starts
- **Runs entirely locally** -- Ollama or any OpenAI-compatible endpoint; your code never leaves your machine
- **Works with any AI tool** -- Claude Code, Cursor, Windsurf, or anything that reads `AGENTS.md`

---

## Local AI providers

Ollama, LM Studio, llama.cpp, vLLM -- anything with an OpenAI-compatible endpoint works.
Default: Ollama at `http://localhost:11434`.

---

## Current scope

Early development. Currently supports **Spring Boot + Maven** projects only.
Other stacks are on the roadmap.

---

[Docs](docs/index.adoc) | [Getting Started](docs/getting-started.adoc) | [MIT License](LICENSE) | [Issues](https://github.com/acltabontabon/launchpad/issues)
