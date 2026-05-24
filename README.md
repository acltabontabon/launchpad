# Launchpad

![Launchpad Demo](docs/resources/launchpad.gif)

Launchpad is a local-first desktop tool that generates high-quality context files for AI assistants (Claude Code, Cursor). It scans your codebase and creates the instructions your AI needs to actually understand your project's structure, patterns, and dependencies.

[**Get Started (USAGE.md)**](USAGE.md) | [**View Benefits**](BENEFITS.md) | [**Changelog**](CHANGELOG.md)

---

### 🚀 How it works

1.  **Launch** the app in your terminal.
2.  **Point** it at your project folder.
3.  **Select** your AI assistant (Claude or Cursor).
4.  **Review & Save** the generated context files (`CLAUDE.md`, `.cursorrules`, etc).

### ✨ Key Features

-   **Project Context Awareness:** Automatically extracts architectural patterns, dependency graphs, and configuration signals (e.g., Spring Boot bean wiring and controllers).
-   **Local & Private:** Your code never leaves your machine. All extraction and synthesis run locally.
-   **Vendor Neutral:** Define your engineering standards once in YAML; Launchpad formats them for any assistant.
-   **Zero Config:** No accounts, no cloud, and no API keys required.

### 🛠 Supported Local AI Providers

Launchpad uses local AI to summarize project signals. It auto-detects:
-   **Ollama** (Default: `http://localhost:11434`)
-   **OpenAI-compatible** (LM Studio, llama.cpp, vLLM)

### ⚠️ Status

Launchpad is in **early development**. Currently, it specifically supports **Spring Boot Java + Maven** projects. Support for Gradle, Kotlin, and other stacks is on the roadmap.

---

[MIT License](LICENSE) | [Share Feedback](https://github.com/acltabontabon/launchpad/issues)
