---
name: monitor-tool-as-shell
description: When a session has no run_shell_command, the `monitor` tool is a working shell substitute — the file-redirect + heartbeat pattern that makes it usable
source: auto-skill
extracted_at: '2026-11-07T16:10:43.772Z'
---

# `monitor` as the only shell

Confirm first: `tool_search "shell command execution bash run terminal ssh"`. If only `monitor`,
`computer_use`, worktree and task tools come back, there is **no `run_shell_command`** — but
`monitor` runs real bash, so ssh / git / clj / node are all still available. Do not downgrade to
"read `.git` by hand and report that no run happened" (that is what `git-state-without-shell`
assumes; it is the fallback, not the ceiling).

## The five rules that make it work

1. **Notifications never reach your context.** Every command must redirect to a file you then
   `read_file`: `bash /tmp/x.sh 2>&1 | tee /tmp/x.out`. A command whose output you did not
   capture is a command whose result you will never see.
2. **Silence kills it.** The process is killed when the monitor stops (idle timeout, max
   `idle_timeout_ms: 600000`). Anything that produces no stdout for that long dies mid-run —
   including the `ssh` and the remote job. Print a heartbeat every 10–20 s. Never redirect
   *inside* the script and then rely on the monitor staying alive.
3. **No top-level `&`.** Rejected with "Monitor commands must not contain non-final top-level
   background operators". Put the job in a script (`write_file` → `bash /tmp/x.sh`) and background
   *inside* it.
4. **Write scripts, don't inline them.** Quoting through `monitor` → `bash -c` → `ssh` → remote
   bash is three levels deep. `write_file /tmp/x.sh`, then run it. For remote work use
   `ssh -o BatchMode=yes host 'bash -s' <<'EOS' … EOS` — the **quoted** delimiter keeps `$vars`
   from expanding locally.
5. **`task_stop` needs loading.** `tool_search "select:task_stop"` first, then stop by monitor id.

## Long remote job (one monitor call covers a 45-minute run)

```bash
( …all the work, echoing progress… ) > /tmp/remote.log 2>&1 &
pid=$!
while kill -0 "$pid" 2>/dev/null; do
  echo "hb $(date -u +%T) | $(tail -1 /tmp/remote.log | cut -c1-110)"; sleep 20
done
echo "===== VERBATIM REMOTE LOG ====="; cat /tmp/remote.log
```

The heartbeat doubles as live progress; the final `cat` is the evidence you quote in the report.
Launch it as `bash /tmp/x.sh 2>&1 | tee /tmp/x.out` with `idle_timeout_ms: 600000`.

## Other things learned the hard way

- **Parallelise sweeps**: launch N independent remote processes with `&`, then
  `while [ -n "$(jobs -rp)" ]; do …; sleep 20; done`. Eight 6-minute test runs finished in one
  ~9-minute round on a 24-core host.
- **`grep -c … || echo 0` prints twice** (grep emits `0` *and* exits 1) → `[: 0\n0: integer
  expression expected`. Use `grep -q … && s=1 || s=0`.
- **Waiting costs turns.** There is no blocking sleep. To let wall-clock pass while a run
  finishes, launch an `agent` subagent on a genuinely useful read-only research task — it blocks
  until done. Polling a file costs one turn per ~20 s of real time.
- **Guard your context**: never `grep_search` a broad pattern across `collab/`,
  `orchestrator-logs/` or fixture dirs like `test/yang/latte_oracle/*.edn` — those hold
  megabyte single-line JSON/EDN records and one match can flood the session. Restrict the glob
  (`*.{clj,cljc,edn,sh,md}`) and the path, and prefer narrow patterns over numbers like `6144`.
- Scratch scripts and logs belong in `/tmp` on both the laptop and the remote hosts — never
  inside a repo tree, and never in a shared CI clone.
