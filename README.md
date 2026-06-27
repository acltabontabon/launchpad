# 🚀 Launchpad

**Launchpad prepares a repository for AI-assisted development before the agent starts.**

It scans the project, resolves engineering standards, generates grounded project context, and emits durable preparation artifacts through `AGENTS.md` and `.ai/*`.

> **Standards-first, not standards-after.**

> [!IMPORTANT]
> **Project status: focused hardening**
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

<p align="center">
  <img src="docs/resources/launchpad.gif" width="800" alt="Launchpad Demo">
</p>

<p align="center">
  <img src="docs/resources/launchpad-commands.png" width="800" alt="Launchpad Commands">
</p>

---

## 🧭 How it works

![Launchpad Overview](docs/resources/launchpad-overview.drawio.png)

---

## ✨ What it does

- **🔍 Scans your project deterministically** -- extracts structure, dependencies, endpoints, and documentation without guessing
- **📏 Resolves your engineering standards** -- rules, skills, and checklists from a YAML pack you control
- **🧠 Generates grounded context files** -- `AGENTS.md` + `.ai/*` ready before the agent starts
- **📦 Emits durable preparation artifacts** -- structured project context, standards, readiness signals, and agent-facing guidance
- **🤝 Complements existing AI tools** -- works alongside Claude context mode, Cursor, Windsurf, Copilot, MCP servers, IDE assistants, and local agents
- **🔌 Provider-pluggable** -- local AI by default (Ollama, LM Studio, or any OpenAI-compatible endpoint); paid and cloud providers also supported

---

## 🧠 Philosophy

Launchpad is the **preparation layer for AI-assisted development**.

Context-aware tools help agents read and reason over a repository during a coding session.

Launchpad prepares the repository **before** that session begins.

```text
Context mode  = runtime awareness
Launchpad     = preparation infrastructure
```

The goal is not to replace agents.  
The goal is not to replace context mode.  
The goal is to make every agent start smarter.

Launchpad is:

- **🧩 Agent-agnostic** -- works with the user's preferred agent, IDE, model, or workflow
- **🛠️ Preparation-first** -- makes the repo ready before AI-assisted work begins
- **📐 Standards-aware** -- treats engineering rules and checklists as part of the generated project contract
- **🏠 Local-capable** -- keeps local AI and deterministic workflows as first-class options
- **🌐 Not local-limited** -- leaves room for paid/cloud providers and hybrid workflows when they produce better results

---

## 🔌 AI providers

Providers are pluggable. Local AI is the default -- Ollama, LM Studio, llama.cpp, vLLM, or anything with an OpenAI-compatible endpoint -- and keeps your code on your machine, which makes it a first-class option for privacy-sensitive, offline, air-gapped, or cost-sensitive workflows. Paid and cloud providers are also supported when they produce better results: Claude (Anthropic) is the first cloud provider, opt-in and explicitly selected. Local stays the default, and auto-detection never selects a paid provider -- choosing one is always a deliberate choice.

Selecting Anthropic is explicit (`launchpad.ai.provider=anthropic`); its key reads from `LAUNCHPAD_ANTHROPIC_API_KEY` (falling back to the shared `LAUNCHPAD_LLM_API_KEY`). If the key is missing or Anthropic is unreachable, preparation degrades cleanly to deterministic-only output.

Default:

```text
http://localhost:11434
```

---

<p align="center">
  <a href="docs/index.adoc">Docs</a> ·
  <a href="docs/getting-started.adoc">Getting Started</a> ·
  <a href="LICENSE">MIT License</a> ·
  <a href="https://github.com/acltabontabon/launchpad/issues">Issues</a>
</p>
