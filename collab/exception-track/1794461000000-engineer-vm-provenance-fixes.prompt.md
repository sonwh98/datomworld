Created-GMT: 2026-11-11 17:25:00 GMT
Created-Local: 2026-11-12 00:25:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: dbb0cd2c-f711-4043-bc06-7e12f8b7206c
# Task: engineer — fix the three review findings on the provenance primitive (REQUEST CHANGES)
Role: Engineer (fix round; your own implementation)
Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-12 00:25 +0700 | Status: active | Rationale: your implementation; the findings carry exact repros.

Read the review first: `collab/exception-track/1794460000000-reviewer-vm-provenance-impl.gpt-6-astra.stdout.log`.
Work in `/Users/sto/workspace/datomworld-vmprov` on `exception-vm-provenance` @ `ff8b980a`. Files:
the same six as before (engine/module/macro/vm.cljc/state.md/provenance test). NOTHING else.

## The three findings (all reproduced by the reviewer — treat the repros as the acceptance tests)

**P1-1 — REPL single-form bypass.** `yin/repl/frontends.cljc:58` calls `yang.clojure/compile`
directly, skipping `compile-program`'s reserved-prefix pre-pass. Repro:
`(cell/dsg__new-provenance 42 {:direct true})` through `repl/eval-input` mints a sealed cell.
Fix: enforce source admission on the REPL single-form entry path while preserving trusted
compiler construction. NOTE the file is OUTSIDE your previous six — this finding cannot be fixed
in the VM files alone; the admission must be enforced where the REPL entry compiles (or a
program-level admission the REPL path routes through). If the right fix belongs to the
yang.clojure REPL frontend rather than yin.vm, implement it there and say so explicitly.

**P1-2 — Macro-output admission hole.** `macro.cljc:1467` admits packets without construction
provenance when they contain no applications: a guest macro returning a quoted, correctly
addressed `:variable cell/dsg__new-provenance` packet passes expansion; `((leak) 42 {:forged true})`
mints authenticated provenance. Definition targets bypassable by assembling `yin/def` with
`yin/application` instead of `yin/make-def`. Fix per the consult: validate executable names at
admission using trusted construction provenance, including references returned as values.

**P2 — mint accepts nil provenance.** `module.cljc:419` / `engine.cljc:2812` allocate
`:provenance nil` — violates the consult precondition and makes the reader indistinguishable
from unmarked. Reject nil before allocation; cover the refusal in a pin.

## Also required

- **Strengthen the macro-door test**: the current test calls only `m/invoke` (unvalidated output)
  and missed both bypasses. It must exercise `expand-batch` + evaluation AND indirect application
  of a returned door, for each bypass class — the reviewer's repros are the minimal set.
- **Test filename**: you used `provenance_test.cljc` (the brief said `.clj`). `.cljc` is the
  better target (multi-lane) — keep it, no action.
- Do NOT touch anything outside the files this finding list requires. Do NOT run the contract
  test locally. `clojure -M:test -n yin.vm.provenance-test` (or -n yin.vm.provenance-test with
  the .cljc name as registered) + kondo on changed files + the seam suites (cell, module,
  effect-forgery, rule-r) focused.

Commit + push `exception-vm-provenance`. End with Completed-GMT/Completed-Local, per-finding
dispositions with file:line, and: **FINDINGS CLOSED** or **STILL OPEN: <what>**.
