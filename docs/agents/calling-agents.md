---
description: Delegation instructions and guidelines for invoking external coding agents (agy, glm, deepseek)
---

# AGY DELEGATION

`agy` is a compiled CLI, not an argument-forwarding shell script. In print
mode, `-p` / `--print` consumes the immediately following argument as the
prompt. Put every option before `-p`; options placed after it may be sent to
Antigravity as prompt text.

Use plan mode and the sandbox for a read-only review:

```sh
agy --mode plan --sandbox --effort high --print-timeout 5m \
  --output-format text -p "Review the supplied diff. Do not edit or run tests."
```

To select a model, still place the model option before `-p`. List available
models with `agy models` immediately before the invocation; do not guess an
identifier or silently accept a fallback model.

```sh
agy --model gemini-3.1-pro-high --mode plan --sandbox \
  --output-format text -p "Review this code without editing it."
```

Private repository contents are an external disclosure. Before sending source
or a diff to Antigravity, obtain direct, explicit user authorization naming the
payload, for example the staged diff. Do not broaden staged-only authorization
to unstaged files. Invoke `agy` from the agent that directly received the user
authorization; a delegated agent may not be able to rely on relayed consent.

For a payload-limited review, run from an empty directory such as `/private/tmp`
and tell Antigravity not to inspect a workspace or call tools. Do not put a
private diff, document, or source payload directly after `-p`: command-line
arguments can be visible to process inspection and logs. Send a large private
payload through `--input-format stream-json` on standard input instead, using
the CLI's current NDJSON protocol and `--output-format stream-json`; this keeps
the payload out of the process argument list. If a review instead asks
Antigravity to run `git diff` or read repository files, its plan-mode permission
gate may require another approval.

The CLI needs write access to its state under `~/.gemini` and permission to bind
its localhost language-server socket, even for `--mode plan --sandbox`. If a
sandboxed invocation fails on either requirement, retry the same command with
only those host permissions approved. Keep `--mode plan --sandbox`; do not
weaken it to work around the startup failure. Network permission may likewise
be required to reach the provider.

For an unattended review, keep `--print-timeout 5m`, `--output-format text`,
and a prompt that requires a text response. Capture and inspect a non-empty
response before treating the review as completed.

Do not use `--dangerously-skip-permissions` by default. It may be used only when
the user explicitly authorizes the resulting commands, file access, and edits;
keep read-only reviews in `--mode plan --sandbox`.

# GLM AND DEEPSEEK DELEGATION

`glm` and `deepseek` are local wrappers around Claude Code for their respective
provider models. Invoke the wrapper, rather than `claude`, when selecting either
provider.

For a workspace read-only review, use plan permissions and explicitly allow
only the `Read` tool. The current Claude Code wrapper uses
`--allowed-tools`; do not use the obsolete `--tools ""` form:

```sh
deepseek --bare --permission-mode plan \
  --allowed-tools Read --no-session-persistence --output-format text \
  -p "Read docs/design/dao.stream.md only. Do not edit files. Return a concise review."
```

For a payload-limited review, run from an empty directory such as
`/private/tmp`, omit workspace-reading tools, and embed only the payload the
user explicitly authorized. The wrapper may print `unrecognized_model`
warnings from Claude Code's internal model registry; these are non-fatal when
the provider request returns a response. Always verify a non-empty final
response before treating the review as complete.

For an interactive coding session, invoke `glm` or `deepseek` directly. Use
`--permission-mode plan` for review and exploration. Use edit-accepting modes
only after the user directly authorizes the intended file access and edits.

Private repository contents are an external disclosure. Before sending source,
diffs, tests, or other private workspace material, obtain direct, explicit user
authorization naming the exact payload. A payload-limited authorization does
not authorize workspace inspection. Do not rely on authorization relayed through
another agent.

Do not use `--dangerously-skip-permissions` or
`--allow-dangerously-skip-permissions` unless the user explicitly authorizes the
resulting commands, file access, and edits. Tool-disabled reviews may still
require network permission to reach the configured provider.

Treat wrapper configuration and local provider credentials as secret. Do not
print, copy, or commit them.
