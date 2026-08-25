---
description: Delegation instructions, invocation CLI scripts, and guidelines for calling external coding agents (agy, cmd, glm, deepseek, codex)
---

# AGENT DELEGATION & INVOCATION GUIDE

This document defines how to invoke and delegate tasks, code reviews, and autonomous coding sessions to external LLM agents and specialized CLI runners.

## Agent Dispatch Matrix

| Model Family / Provider | Primary CLI Tool / Script | Alternative / Fallback | Notes |
| :--- | :--- | :--- | :--- |
| **Claude Models** | `agy` or `cmd -m <claude-model>` | `cmd -m claude-sonnet-5` | `agy` compiles prompts directly; `cmd` routes through Command Code |
| **Gemini Models** | `agy` (or `agy --model <gemini-model>`) | N/A | Dedicated Antigravity CLI (do NOT use `cmd` for Gemini models) |
| **GLM Models** | `~/.local/bin/glm` (or `glm`) | N/A | Dedicated local CLI wrapper (do NOT use `cmd` for GLM models) |
| **DeepSeek Models** | `~/.local/bin/deepseek` (or `deepseek`) | `cmd -m deepseek/deepseek-v4-pro` | Local wrapper for DeepSeek models |
| **GPT Models** | `codex` | N/A | Use dedicated `codex` CLI exclusively (do NOT use `cmd` for GPT models) |
| **All Other Models** (Qwen, MiniMax, Kimi, etc.) | `cmd -m <model>` | N/A | Available models from [Command Code Catalog](https://commandcode.ai/docs/reference/cli/models) |

---

## 1. Claude Models: `agy` and `cmd`

### Invoking Claude Models with `agy`

`agy` is a compiled CLI that supports direct model selection via the `--model` flag.

#### 1. Non-Interactive / Print Mode (`-p`)
In print mode, `-p` / `--print` immediately consumes the next argument as the prompt text. **Always place `--model` and all options before `-p`**:

```sh
# Run a read-only plan mode review using Claude Sonnet
agy --model claude-3-7-sonnet --mode plan --sandbox --effort high --print-timeout 5m \
  --output-format text -p "Review the supplied diff. Do not edit or run tests."

# Architecture / formal verification with Claude Opus
agy --model claude-3-opus --mode plan --sandbox \
  --output-format text -p "Verify CESK state transitions in src/cljc/yin/vm.cljc."
```

#### 2. Interactive Mode
Launch an interactive session directly with a specific Claude model:

```sh
# Start interactive TUI session with Claude
agy --model claude-3-7-sonnet

# Start with an initial interactive prompt
agy --model claude-3-7-sonnet -i "Let's inspect the DaoSpace B-Tree implementation."
```

#### 3. In-Session Model Switching
Inside an active `agy` TUI session, switch models on the fly:
- Run `/model claude-3-7-sonnet` (or `/model claude-3-opus`)
- Or press `F2` to open the interactive model picker.

#### 4. Persistent Configuration
To set a Claude model as the default for `agy`, configure `~/.gemini/antigravity-cli/settings.json`:
```json
{
  "model": "claude-3-7-sonnet"
}
```

### Invoking Claude Models with `cmd`

Command Code (`cmd`) can also run Claude models directly:

```sh
# Non-interactive review with Claude Sonnet 5
cmd -m claude-sonnet-5 -p "Review docs/design/datom.md for invariant consistency."

# In-depth architectural review with Claude Opus 5
cmd -m claude-opus-5 -p "Audit capability token confinement in docs/design/dao.space.security.md."
```

---

## 2. Gemini Models: `agy` (Exclusively)

> **Important**: Do **not** use `cmd` for Gemini models. All Gemini models must be invoked exclusively via `agy`.

`agy` is Google's native Antigravity CLI, specifically engineered for Gemini models.

### 1. Interactive Mode
```sh
# Start interactive session with default Gemini model
agy

# Specify high-reasoning Gemini model
agy --model gemini-3.1-pro-high
```

### 2. Non-Interactive / Print Mode (`-p`)
Always place `--model` and all flags before `-p`:

```sh
# Read-only code review in sandbox
agy --model gemini-3.1-pro-high --mode plan --sandbox \
  --output-format text -p "Review this code without editing it."

# Fast verification sweep with flash
agy --model gemini-2.5-flash --mode plan --sandbox \
  --output-format text -p "Audit bracket balance and linting across test/dao/."
```

---

## 3. GLM and DeepSeek: Dedicated Local Scripts

`glm` (`~/.local/bin/glm`) and `deepseek` (`~/.local/bin/deepseek`) are local CLI wrappers configured for their respective provider models.

> **Important**: Do **not** use `cmd` to invoke GLM models. All GLM models must be invoked exclusively through `~/.local/bin/glm` (or `glm`).

### DeepSeek Invocation
```sh
# Read-only review with deepseek
~/.local/bin/deepseek --bare --permission-mode plan \
  --allowed-tools Read --no-session-persistence --output-format text \
  -p "Read docs/design/dao.stream.md only. Do not edit files. Return a concise review."
```

### GLM Invocation
```sh
# Read-only review with glm
~/.local/bin/glm --bare --permission-mode plan \
  --allowed-tools Read --no-session-persistence --output-format text \
  -p "Inspect the Yin.VM instruction set in src/cljc/yin/vm.cljc. Return an architectural critique."
```

---

## 4. GPT Models: `codex` (Exclusively)

> **Important**: Do **not** use `cmd` to call GPT models. All GPT models must be invoked exclusively via the dedicated `codex` CLI.

### 1. Interactive Mode
```sh
# Start interactive session with default GPT model
codex

# Specify model and launch
codex -m gpt-5.3-codex "Review the Yang compiler AST lowering in src/cljc/yang/clojure.cljc."
```

### 2. Non-Interactive Execution (`codex exec` / `codex review`)
```sh
# Non-interactive command execution with read-only sandbox
codex exec -s read-only "Analyze the B-Tree transient mutations in src/cljc/dao/data/btree.cljc."

# Non-interactive code review
codex review "Check src/cljc/dao/space/transact.cljc against non-negotiable invariants."
```

---

## 5. All Other Models: `cmd -m <model>`

For all other models (e.g. Qwen, MiniMax, Kimi / Moonshot AI, etc.), use `cmd -m <model>` referencing the model IDs from the [Command Code Catalog](https://commandcode.ai/docs/reference/cli/models):

### Examples:
```sh
# MiniMax M3 for stream / protocol logic
cmd -m MiniMaxAI/MiniMax-M3 -p "Review WebSocket transport reconnection logic in src/cljc/dao/stream/ws.cljc."

# Kimi K2.7 Code for graphics / shaders / UI
cmd -m moonshotai/Kimi-K2.7-Code -p "Review WebGL shader packing in src/cljc/dao/postgraphics/packing.cljc."

# Qwen 3.8 Max for compiler parsing
cmd -m Qwen/Qwen3.8-Max -p "Review macro expansion logic in src/cljc/yang/macro.cljc."
```

---

## Security, Privacy, and Review Protocols

### 1. External Disclosure & User Authorization
- **Direct Authorization**: Private repository contents are an external disclosure. Before sending source or a diff to Antigravity (`agy`), DeepSeek, GLM, Codex, or Command Code, obtain direct, explicit user authorization naming the exact payload (for example, the staged diff).
- **No Scope Broadening**: Do not broaden staged-only authorization to unstaged files or general workspace access.
- **No Relayed Consent**: Invoke the external CLI directly from the agent that received user authorization; a delegated agent may not rely on relayed consent.

### 2. Payload Protection & Process List Obfuscation
- **Hiding Payloads from `ps`**: Do not put a private diff, document, or source payload directly after `-p`: command-line arguments are visible in process tables (`ps`) and shell logs.
- **NDJSON Stream Input**: For `agy`, send large private payloads through `--input-format stream-json` on standard input instead, using the CLI's NDJSON protocol and `--output-format stream-json`; this keeps the payload out of the process argument list.
- **Isolated Execution Directory**: For payload-limited reviews, run from an empty directory such as `/private/tmp` and omit workspace inspection tools.

### 3. Permissions, Sandboxing, and Host Requirements
- **Host Permissions for `agy`**: The `agy` CLI requires write access to its state under `~/.gemini` and permission to bind its localhost language-server socket, even when running in `--mode plan --sandbox`. If a sandboxed invocation fails on either requirement, retry the command with only those host permissions approved. Keep `--mode plan --sandbox`; do not weaken it to work around startup failures. Network permission is likewise required to reach the provider.
- **Restricted Tool Allowlist for Claude Code Wrappers**: For workspace read-only reviews with `deepseek` or `glm`, use `--permission-mode plan` and explicitly allow only the `Read` tool via `--allowed-tools Read`. Do not use the obsolete `--tools ""` syntax.
- **Non-Fatal Warnings**: `glm` and `deepseek` wrappers may print `unrecognized_model` warnings from Claude Code's internal model registry; these are non-fatal when the provider request returns a response.
- **Unattended Review Verification**: Keep `--print-timeout 5m`, `--output-format text`, and a prompt that requires a text response. Capture and verify a non-empty final response before treating the review as completed.
- **Dangerously Skip Permissions**: Never use `--dangerously-skip-permissions` or `--allow-dangerously-skip-permissions` unless the user explicitly authorizes the resulting commands, file access, and edits.

### 4. Credential Confidentiality
- Treat wrapper configuration, API tokens, and local provider credentials as strictly confidential. Never print, echo, copy, or commit them.
