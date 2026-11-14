Created-GMT: 2026-11-12 22:25:00 GMT
Created-Local: 2026-11-13 05:25:00 +07 (+0700)
Coding-Agent: codex
Session-ID: f7d92464-ee27-40f0-bbbb-ab0de139eb1a
# Task: engineer — PORT Phase 5: the closure carrier for outbound protocol rethrow (P1-2) + review P2s
Role: Engineer (implementer family codex; qwen reviewed; sol reserved review lane — you implement)
Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-13 05:25 +0700 | Status: active | Rationale: continuity (you hold baselines/coverage context); owner routing (deepseek low → codex).

Read first: the qwen port review (`1794554000000-...qwen3.8-max.findings.md`) — P1-2, P2-1, P2-2,
P2-3 are your scope. Worktree `/Users/sto/workspace/datomworld-r3-orch` (launch cwd = here;
writes permitted), branch `exception-r3-impl` @ `35d09aaa`.

## P1-2 (blocking) — the closure carrier for outbound protocol rethrow

The public entry's handler reclassifies everything as fresh `:yang.call/thrown`, so a guest
catching a boundary-delivered PROTOCOL condition and rethrowing it unchanged arrives outbound as
`:reason :yang.call/thrown` — the 18 `:clojure/protocol-*` / `:saved-protocol-*` ledger rows stay
red. The owner's pre-authorized remedy is the **(B) closure carrier**:

- When `compile-guest-call`'s raise-exit reception delivers a QUALIFIED protocol condition into a
  clojure catch, bind the catch variable to a **compiler-minted immutable closure carrier**
  (the witness pattern — a kernel `Closure` whose captured env holds the complete condition;
  NOT a cell — nothing guest-writable). Kernel closure equality (owner+payload+env) makes
  forgery impossible; `cell/set!` does not apply to closures.
- At the public escape: if the raised value is such a carrier (authenticated by the compiler-held
  token in its env), forward the stored condition byte-identical; otherwise the ordinary fresh
  `:yang.call/thrown`.
- R5: shape is never evidence — the carrier is identified by construction provenance, and a
  guest-written condition-shaped map is still data.
- This closes R3's outbound half for the ledger rows without any VM change and without
  re-introducing the declined seam.

## P2-1 — finally coverage pins (make the mechanism PROVEN)

Add: `finally` runs on a THROW through it (this exercises `clj/unwind-to` — see P2-3),
`finally` + `catch` ordering, nested `finally`, a `finally` that throws, on BOTH lanes. If these
expose a real gap in the finally-on-unwind path (because unwind-to is currently unwired — P2-3),
FIX the wiring; the mechanism must work, not just compile.

## P2-2 — re-pin the shared `:missing-raise-exit` refusal

The port deleted the only pin on `yang.call/invoke`'s `missing-raise-exit` refusal (now
production-only, zero tests). Re-pin host-side in `test/yang/core/call_test.cljc` (NOT a kit file
— the shared convention belongs to the shared host).

## P2-3 — the dead-surface decision

`clj/unwind-to` / `clj/call-ec` are exported, zero callers, zero tests. Either wire them (P2-1's
pins prove `unwind-to` in the finally-on-throw path — call-ec may stay unused if catch/else use
direct returns) or drop the truly unused one from the prelude. Decide, justify, pin what you keep.

## Constraints

- NO kit edits (`test/yang/call/exception_*` and `*_source_test` are the acceptance instrument;
  the P1-1 finally-refusal row is an OWNER decision being prepared separately — your branch must
  NOT change kit expectations).
- `src/cljc/yin/vm/` stays EMPTY vs master (the declined seam stays declined).
- Acceptance: focused `clojure -M:test -n yang.clojure-test` green; `yang.call.python-exception-
  test`/shared host suites where P2-2 touches them; kondo 0/0. Contract test remote-only.
- Commit + push. End with Completed-GMT/Completed-Local and: **CARRIER + PINS LANDED: <sha>** or
  **BLOCKED/FINDINGS: <what>**.
