# Launchpad Usage Guide

Step-by-step walkthroughs for the most common things you'll do with Launchpad. If you want the high-level "what is this" story, read [README.md](README.md) first; this file assumes you've decided to use it and want concrete instructions.

## Table of contents

1. [Install](#1-install)
2. [First run: generate context for a project](#2-first-run-generate-context-for-a-project)
3. [Hook Launchpad up to your AI tool as an MCP server](#3-hook-launchpad-up-to-your-ai-tool-as-an-mcp-server)
4. [Day-to-day: address projects by name](#4-day-to-day-address-projects-by-name)
5. [Manage your project registry](#5-manage-your-project-registry)
6. [Audit a project against your standards](#6-audit-a-project-against-your-standards)
7. [Troubleshooting](#7-troubleshooting)
8. [Reference: every file Launchpad reads or writes](#8-reference-every-file-launchpad-reads-or-writes)

---

## 1. Install

### Prerequisites

- Java 21 or later (only needed if running from the JVM jar).
- [Ollama](https://ollama.com) running on `http://localhost:11434` with at least one code-aware model pulled:
  ```bash
  ollama serve                       # start the daemon
  ollama pull qwen2.5-coder:7b       # default model
  ```

### Build

From the repo root:

```bash
./mvnw package -DskipTests           # ~30s on first run, sub-10s thereafter
```

You should now have:

```
target/launchpad-0.1.0-SNAPSHOT.jar    # 46 MB, all-in-one
```

Optional: build the GraalVM native binary for sub-second TUI startup.

```bash
./mvnw -Pnative native:compile         # ~3 min
target/launchpad                       # the native binary
```

> The native binary is fine for the TUI. MCP mode currently requires the JVM jar (see [Troubleshooting](#7-troubleshooting)).

---

## 2. First run: generate context for a project

This is the "/init" flow. End state: your project has a `CLAUDE.md` (or `.cursorrules`) tailored to it, and the project is enrolled in Launchpad's registry so MCP clients can address it by name later.

### Start the TUI

```bash
java -jar target/launchpad-0.1.0-SNAPSHOT.jar
# or
./mvnw spring-boot:run
# or, if you built native:
target/launchpad
```

You land on the Welcome screen. The status dots at the bottom should turn green for **Ollama** within a second or two.

### Pick what to do

Type `/` to open the command palette. You'll see:

```
/init        Initialize project    - Generate AI context files for a project
/new-task    New task              - Interview-driven prompt builder
/projects    Projects              - Browse projects you have used Launchpad on
/settings    Settings              - Configure Ollama and remote standards repo
/quit        Quit
```

Highlight `/init` (use `↑↓` if needed) and press `Enter`.

### Walk through the flow

1. **Project path** - start typing your project's path. Tab completes; `↑↓` browses the live match list. Press `Enter` when the right path is on the input line.
2. **Target** - choose `Claude` or `Cursor`. Press `Enter`.
3. **Scanning** - Launchpad walks the file tree, runs the audit (if your standards pack has any `check:` blocks), and asks the local model to draft the context files. Watch the phases card on the right.
4. **Review** - for each generated file you'll see one of these chips:
   - `NEW` - file does not exist yet, will be written fresh.
   - `MERGE` - file exists and has Launchpad's managed-block markers; only the marked section is replaced.
   - `OVERWRITE` - file exists but was hand-edited; press `o` if you really want to clobber it.
   - `SKIP` - default for hand-edited files.
   - Press `d` on any file to toggle the unified diff against what is on disk.
   - Press `s` to apply all plans.

Done. The project now has its AI context files **and** it is enrolled in the Launchpad registry at `~/.launchpad/projects.json` under a short name (usually the directory's basename).

---

## 3. Hook Launchpad up to your AI tool as an MCP server

MCP (Model Context Protocol) is the open standard your AI tool already speaks. One config snippet, identical shape for every client.

### The config snippet (works for every client)

```json
{
  "mcpServers": {
    "launchpad": {
      "command": "java",
      "args": [
        "-jar",
        "/abs/path/to/launchpad-0.1.0-SNAPSHOT.jar",
        "mcp"
      ]
    }
  }
}
```

Replace `/abs/path/to/launchpad-0.1.0-SNAPSHOT.jar` with your actual jar path (output of `ls target/launchpad-*.jar`).

### Where to paste it

| Tool | Config file |
|---|---|
| **Claude Desktop** | `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) |
| **Claude Code** | `~/.claude.json`, or run `claude mcp add launchpad java -- -jar /abs/path/to/launchpad-0.1.0-SNAPSHOT.jar mcp` |
| **Cursor** | `~/.cursor/mcp.json` (global) or `.cursor/mcp.json` (per project) |
| **Cline / Continue / Zed** | Each has its own MCP settings file; same JSON shape |

If your file already has an `mcpServers` block with other servers, add `"launchpad"` as a sibling key (don't duplicate the outer key).

### Restart the client and verify

1. Fully quit the client (`Cmd-Q` on macOS, not just closing the window).
2. Reopen it.
3. Start a new conversation and type: **"What MCP tools do you have access to?"**

You should see four tools listed under `launchpad`:

- `list_projects`
- `scan_project`
- `get_standards`
- `get_audit_findings`

If the client doesn't list them, see [Troubleshooting](#7-troubleshooting).

---

## 4. Day-to-day: address projects by name

This is the payoff. Once your projects are enrolled (see step 2) and your AI tool is wired up (step 3), you can stop typing absolute paths.

### Things you can ask

> "What projects does launchpad know about?"
>
> -> the agent calls `list_projects` and prints a table.

> "Use launchpad to scan Pragmata and tell me about its stack."
>
> -> the agent calls `scan_project(project="Pragmata")`. Resolution happens locally; no path needed.

> "Audit my Launchpad project using launchpad and walk me through the worst violations."
>
> -> the agent calls `get_audit_findings(project="Launchpad")` and you get a punch list grouped by severity.

> "Which of my projects use Java?"
>
> -> the agent calls `list_projects`, filters by `stack`, replies in prose.

> "Pull the engineering rules for Launchpad and tell me which ones the current diff in front of me might violate."
>
> -> the agent calls `get_standards(project="Launchpad")` and reasons over the structured output. No cloud tokens spent re-reading your `CLAUDE.md`.

### Why this saves tokens

Without Launchpad, every conversation that asks "what is this project?" makes the cloud model `grep` and `read_file` dozens of times. With Launchpad, the local Ollama model did that work once, persisted it to `.launchpad/scan.json`, and the cloud model gets the answer in one tool call.

### A name that doesn't match? Pass a path instead

The `project` argument accepts an absolute path too:

> "Use launchpad to scan /Users/me/Workspace/SomethingNew."

It will be enrolled as `SomethingNew` (or `SomethingNew-2` if that name is taken) once the scan completes.

---

## 5. Manage your project registry

Open the TUI and pick `/projects` from the command palette.

### What you see

```
> Pragmata         Java / Spring Boot     2h ago
  /Users/you/Workspace/Pragmata

  Launchpad        Java / Spring Boot     5d ago
  /Users/you/Workspace/Launchpad

  Katha            TypeScript / Next      3w ago
  /Users/you/Workspace/Katha
```

### Keys

| Key | What it does |
|---|---|
| `↑` `↓` | Navigate the list |
| `Enter` | Re-open the selected project (sets project path, returns to Welcome with a hint to pick a command) |
| `d` | Remove the selected entry from the registry (the project files on disk are untouched) |
| `p` | Prune missing - remove every entry whose path no longer exists on disk |
| `Esc` | Back to Welcome |

### Hand-editing

The registry is plain JSON at `~/.launchpad/projects.json`. You can edit it directly; Launchpad re-reads it on next start. The schema:

```json
{
  "projects": [
    {
      "name": "Pragmata",
      "path": "/abs/path/to/Pragmata",
      "stack": "Java / Spring Boot",
      "addedAt": "2026-05-23T14:00:00Z",
      "lastScannedAt": "2026-05-23T14:30:00Z"
    }
  ]
}
```

---

## 6. Audit a project against your standards

If your standards pack carries `check:` blocks (see [Standards in CLAUDE.md](CLAUDE.md#engineering-standards-rules-skills-checklists-prompts-adapters)), Launchpad will audit the project on every scan.

### Where the findings land

After a TUI scan, two files appear in the project:

```
.launchpad/audit.md            # human-readable, grouped by severity
.launchpad/audit.sarif.json    # SARIF 2.1.0, machine-readable
```

### How to consume them

- **Inside the TUI** - the phases card on the Scanning screen shows live progress and a final count (`N must, M should`).
- **From any MCP client** - call `get_audit_findings(project="YourProject")` and you get the same findings as structured JSON.
- **In your IDE** - open the SARIF file with the [SARIF Viewer extension](https://marketplace.visualstudio.com/items?itemName=MS-SarifVSCode.sarif-viewer) for VS Code, or IntelliJ's Qodana integration. Findings show up as squiggles in the code.
- **In CI** - upload `audit.sarif.json` to GitHub code-scanning (`actions/upload-sarif` action). It will appear in the Security tab.

### Three kinds of checks you can write

Edit `rules.yml` in your standards pack (per-project at `.launchpad/standards/` or in a remote repo):

```yaml
- id: no-field-injection
  title: Constructor Injection
  severity: must
  check:
    kind: forbid-pattern
    pattern: "^\\s*@Autowired\\s*$\\n\\s*(private|protected)\\s+\\w"
    includes: ["**/*.java"]

- id: domain-no-spring
  title: Domain Does Not Depend on Infrastructure
  severity: never
  check:
    kind: forbid-import
    in_packages: ["**/domain/**"]
    imports: ["org.springframework.**", "jakarta.persistence.**"]

- id: thin-controllers
  title: Thin Controllers
  severity: must
  check:
    kind: llm
    targets: "controllers"
    question: "Does this controller contain orchestration, validation logic, or persistence calls beyond parsing input and calling one application service?"
```

`forbid-pattern` and `forbid-import` are free and deterministic. `llm` calls your local Ollama model with structured-output JSON binding (no cloud tokens).

---

## 7. Troubleshooting

### MCP tools don't show up in my AI tool

1. **Check the log.** Every MCP-aware client logs subprocess output. For Claude Desktop on macOS:
   ```bash
   tail -50 ~/Library/Logs/Claude/mcp-server-launchpad.log
   ```
   If you see `java: command not found`, the client's PATH is minimal. Replace `"command": "java"` in the config with the absolute path from `which java`.

2. **Validate the JSON.**
   ```bash
   python3 -m json.tool < ~/Library/Application\ Support/Claude/claude_desktop_config.json
   ```
   A missing comma or trailing comma will silently disable every server in the file.

3. **Confirm the jar runs on its own:**
   ```bash
   { echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"me","version":"1"}}}';
     echo '{"jsonrpc":"2.0","method":"notifications/initialized"}';
     sleep 1;
     echo '{"jsonrpc":"2.0","id":2,"method":"tools/list"}';
     sleep 2;
   } | java -jar target/launchpad-0.1.0-SNAPSHOT.jar mcp
   ```
   You should see two JSON-RPC responses on stdout. If yes, the server is fine and the issue is in the client config.

### "Error processing inbound message" with `No content to map`

Harmless. The MCP server saw stdin close (because the terminal ended) and tried to read one more message. Only happens when you launch the jar without a real MCP client attached. Ignore.

### `list_projects` returns an empty array

You have not finished a TUI scan on any project yet. Enrollment happens when the scan persists `.launchpad/scan.json` (i.e. between Scanning and Review). Either:
- Run a TUI scan on at least one project (see step 2), or
- Add an entry to `~/.launchpad/projects.json` by hand (schema in step 5).

### `get_audit_findings` returns 0 findings

Two possibilities:

- The project has no standards pack configured (no remote URL, no `.launchpad/standards/` directory).
- The pack has rules but none of them carry a `check:` block. Without `check:` a rule is doc-only; the audit engine has nothing to evaluate. See [step 6](#6-audit-a-project-against-your-standards) for how to add checks.

### Native binary fails in MCP mode

Known issue with Spring AI 2.0.0-M6's tool schema generator under GraalVM AOT. Workaround: run MCP mode from the JVM jar instead.

```bash
# Native works for the TUI:
target/launchpad

# But for MCP, use the jar:
java -jar target/launchpad-0.1.0-SNAPSHOT.jar mcp
```

JVM startup is sub-second on a warm system, so the practical impact is small.

### Ollama times out or returns 500

- Check the Welcome screen's status dots. If Ollama is red, the daemon isn't reachable on the configured URL.
- Open `/settings` from the Welcome palette, adjust the base URL or model.
- If timeouts are intermittent, your model may be too large for your RAM and is being evicted. Try a smaller model:
  ```bash
  ollama pull qwen2.5-coder:3b
  ```
  Then set it from `/settings`.

---

## 8. Reference: every file Launchpad reads or writes

### Inside your project

```
<project>/
├── CLAUDE.md                          # generated (Claude target)
├── .ai/                               # generated supporting notes (Claude)
│   ├── engineering-rules.md
│   ├── stack.md
│   └── index.md
├── .cursorrules                       # generated (Cursor target)
├── .cursor/rules/                     # generated rule files (Cursor)
├── .launchpad/
│   ├── scan.json                      # cached scan output, read by audit + MCP
│   ├── audit.md                       # human audit report
│   ├── audit.sarif.json               # SARIF audit report
│   ├── backups/<timestamp>/           # backups of any file Launchpad overwrites
│   ├── tasks/                         # /new-task outputs
│   └── standards/                     # optional per-project override of the standards pack
│       ├── rules.yml
│       └── skills.yml
```

### In your home directory

```
~/.launchpad/
├── config.properties                  # Ollama URL, model, remote standards URL
├── projects.json                      # the registry: every project you've used Launchpad on
└── standards-cache/<hash>/            # cached clone of your remote standards repo (if configured)
```

Nothing is ever sent off your machine. Everything Launchpad knows lives in those two directories.

---

## Where to go next

- The [README](README.md) explains the project's intent and design.
- [CHANGELOG.md](CHANGELOG.md) tracks every behavior change.
- [CLAUDE.md](CLAUDE.md) is the AI-assistant context file for working on Launchpad itself - it documents the codebase architecture in detail.
