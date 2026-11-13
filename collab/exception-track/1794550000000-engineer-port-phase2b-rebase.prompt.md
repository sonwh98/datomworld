Created-GMT: 2026-11-12 20:15:00 GMT
Created-Local: 2026-11-13 03:15:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: 7cff0992-9998-415b-b583-6b8198fc8efa
# Task: engineer — PORT Phase 2b+3: rebase onto moved master, then the LaTTe depth harness
Role: Engineer (port continuation)
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-13 03:15 +0700 | Status: active | Rationale: continuity.

## Part 1 — rebase onto moved master (2b)

Master moved after your Phase A rebase: the yin.repl shell refactor landed (steps 1–4,
`yin.shell.*` namespaces; `yin/vm/linker/pair.cljc` reworked; `test/yang/clojure_test.clj`
moved ~509 lines for `repl-query-link-forwarding-test`). Rebase `exception-r3-impl`
(`d60038ef`) onto current `origin/master`.

Resolution rule of record: **master wins the shell/link-query test changes**
(`repl-query-link-forwarding-test` → `yin.shell.query`); **the port wins the
exception-semantics test changes** (your Phase 2 re-expressions). The yin/vm linker files are
master's (your branch must show NO yin/vm diff after the rebase except through master's own
content). Every resolved hunk noted.

## Part 2 — Phase 3: the LaTTe depth harness + pre-port baselines (the mob's prerequisite)

`test/yang/latte_oracle_test.clj` collects no continuation depth today. Add:
- max-live-continuation-depth collection per workload (instrument the runner — the semantic
  lane's K length at peak; astra's re-verification located the runner at :239),
- committed baselines EDN: workload → max depth, recorded PRE-PORT (these are the yardstick
  for the owner's escalation thresholds: >10× depth or >10k frames post-port → owner review).
The harness runs focused locally (`yang.latte-oracle-test` namespace); the CORPUS scale run
happens on shiva later by the orchestrator.

## Acceptance

Rebase clean (only the port's files in `git diff origin/master..HEAD --stat`), depth harness
green on the focused namespace, baselines committed. kondo where runnable. Commit + push.

End with Completed-GMT/Completed-Local and: **PHASE 2B+3 LANDED: <sha>, baselines: <count
workloads>** or **BLOCKED: <what>**.
