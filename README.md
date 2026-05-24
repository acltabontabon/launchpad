# Launchpad

![Launchpad Demo](docs/resources/launchpad.gif)

Launchpad is a local-first desktop tool that prepares repository context for AI coding assistants like Claude Code and Cursor.

It currently focuses on Spring Boot + Maven repositories and helps reduce ambiguity, improve consistency, and avoid wasting paid-model tokens on repetitive repository discovery.

Launchpad is designed to complement AI assistants - not replace them.

[**Get Started (USAGE.md)**](USAGE.md) | [**View Benefits**](BENEFITS.md) | [**Changelog**](CHANGELOG.md)

---

## 🚀 How it works

1. **Launch** the app in your terminal.
2. **Point** it at your project folder.
3. **Select** your AI assistant (Claude or Cursor).
4. **Review & Save** the generated context files (`CLAUDE.md`, `.cursorrules`, `.ai/*`, etc).

---

## ✨ What Launchpad focuses on

- **Repository Grounding**  
  Extracts project structure, Maven metadata, Spring Boot signals, architectural hints, and documentation references into reusable context files.

- **Local-First Context Preparation**  
  Runs locally using Ollama or OpenAI-compatible local endpoints. Your source code stays on your machine.

- **Reduce Paid Token Waste**  
  Helps AI assistants spend less time rediscovering repository structure, conventions, and dependencies.

- **Cross-Project Context Awareness**  
  Useful for active multi-repository development where related projects, shared libraries, or internal starters evolve together.

- **Assistant-Agnostic Standards**  
  Define engineering standards once in YAML and generate assistant-specific outputs.

- **Deterministic-First Design**  
  Prefer structured extraction and repository evidence first. Local AI is used for bounded summarization and synthesis - not autonomous reasoning.

---

## 🛠 Supported Local AI Providers

Launchpad uses local AI models for summarization and synthesis. Currently supported:

- **Ollama** (default: `http://localhost:11434`)
- OpenAI-compatible endpoints:
    - LM Studio
    - llama.cpp
    - vLLM

---

## ⚠️ Current Scope

Launchpad is in **early development** and intentionally opinionated.

Current support is focused on:

- Spring Boot
- Java 21+
- Maven repositories

Additional stacks may be added later, but the current priority is building a strong deterministic foundation for Spring Boot projects first.

---

[MIT License](LICENSE) | [Share Feedback](https://github.com/acltabontabon/launchpad/issues)