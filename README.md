# Launchpad

Launchpad prepares a repository for AI-assisted development before the agent starts.

It scans the project, resolves engineering standards, generates grounded project context, and emits durable preparation artifacts through `AGENTS.md`, `.ai/*`, and `.launchpad/*` sidecars.

**Standards-first, not standards-after.**

> **Project status: philosophy hardening / maintenance mode**
>
> Launchpad is currently in a focused hardening phase.
>
> The project is being refined around a clearer philosophy: **agent-agnostic, preparation-first, standards-aware, and complementary to existing AI development tools**.
>
> Launchpad is not trying to replace Claude context mode, Cursor repo context, Windsurf indexing, Copilot workspace features, MCP servers, or IDE-native assistants. Those tools are already good at helping agents inspect a repository during a coding session.
>
> Launchpad's role is different: it prepares durable project context **before** the session begins, so any agent or context-aware tool starts from a clearer, standards-aligned project contract.
>
> Local AI remains a first-class execution option, but Launchpad is not intended to be limited to local AI only. The long-term direction is to support user choice: deterministic preparation, local AI, paid/cloud providers, hybrid workflows, and agent-specific output adapters.
>
> Launchpad is still in its early stages and currently supports **Spring Boot Java** projects on **Maven or Gradle** only.

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
- **Emits durable preparation sidecars** -- structured `.launchpad/*` artifacts for project context, models, standards, and readiness signals
- **Complements existing AI tools** -- works alongside Claude context mode, Cursor, Windsurf, Copilot, MCP servers, IDE assistants, and local agents
- **Keeps local AI first-class** -- supports local/private workflows while leaving room for cloud, paid, hybrid, and deterministic preparation modes

---

## Philosophy

Launchpad is the preparation layer for AI-assisted development.

Context-aware tools help agents read and reason over a repository during a coding session.

Launchpad prepares the repository before that session begins.

The goal is not to replace agents.  
The goal is not to replace context mode.  
The goal is to make every agent start smarter.

Launchpad is:

- **Agent-agnostic** -- it should work with the user's preferred agent, IDE, model, or workflow
- **Preparation-first** -- its value is in making the repo ready before AI-assisted work begins
- **Standards-aware** -- engineering rules and checklists are part of the generated project contract
- **Local-capable** -- local AI and deterministic workflows remain first-class options
- **Not local-limited** -- paid/cloud providers and hybrid workflows should be supported where they produce better results

---

## Local AI providers

Ollama, LM Studio, llama.cpp, vLLM -- anything with an OpenAI-compatible endpoint works.

Default: Ollama at `http://localhost:11434`.

Local AI remains a first-class option for privacy-sensitive, offline, air-gapped, or cost-sensitive workflows.

---

[Docs](docs/index.adoc) | [Getting Started](docs/getting-started.adoc) | [MIT License](LICENSE) | [Issues](https://github.com/acltabontabon/launchpad/issues)
