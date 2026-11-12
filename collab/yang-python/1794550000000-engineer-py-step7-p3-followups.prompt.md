Created-GMT: 2026-11-13 0x:xx GMT
Created-Local: 2026-11-13 0x:xx +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: py step-7 follow-ups — the three P3 findings from the adoption review (F1/F2/F3), one small round

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6.1-terra | Assigned: 2026-11-13 | Status: active | Rationale: three small, well-defined repairs on freshly-merged code

WORKTREE: /Users/sto/workspace/datomworld-f12. Branch `py-step7-followups`, cut from current master (the adoption merge `8018005d` is landed). You are the only writer.

Authority: the steward's disposition note `collab/exception-track/1794478000000-to-python-merged-and-p3-dispositions.md` (the three findings are recorded; none block anything — this round closes them).

## F1 — the mint's nil-guard split behavior (the substantive one)

Current: mint nil → fatal `{:reason :nil-provenance}`; non-map garbage → opaque mint forwarded verbatim. The review wants EITHER a shape-check at reception (non-map/nil condition → plain fresh TypeError, NO provenance mint) OR the split contract recorded explicitly — pick the first (the shape-check) and write the missing pin: no green test exercises malformed conditions today. The pin: mint with nil condition and with non-map garbage → the boundary emits a plain fresh `:yang.call/thrown` whose payload is the malformed thing, with no provenance retained (egress then treats it as ordinary). Locate the mint site by content (the reserved door `cell/dsg__new-provenance` call sites in the ceded regions; regions :912/:1034/:1045/:1046 by content, lines have shifted).

## F2 — the python export-surface audit pin (mirror the ts twin)

One assertion in the python kit test (`test/yang/call/python_exception_test.clj`): the module exports/runtime-keys surface contains NO retention provenance surface — no `dsg__new-provenance`/`dsg__provenance` reachable name, no `py/condition-exception`-style constructor taking condition data. Mirror the ts twin's shape (find it: the ts round's audit pin asserting `module-exports`/`runtime-keys` contain no retention surface — grep `module-exports` in the ts tests). The assertion should flip RED if any future `py/`-prefixed mint-wrapping helper leaks into exports/projections/aliases.

## F3 — the stale scanner-deferred comment

`test/resources/yin/vm/semantic/scanner-golden-c4.generate.clj:47-53` — the comment block contradicts the `deferred #{:py}` currently set (the header itself warns unexplained deferred groups absorb silently). Rewrite the comment to state the current rule: the deferred set is per-run, set to exactly the groups expected to move (the last run moved `:py` — the exception-provenance prelude repairs); a deferred group that does NOT actually move fails the gate on the next run, so stale sets surface immediately.

## Rules

- Regions :912/:1034/:1045/:1046 by content only; do not touch the `type` builtin (:3452, accepted).
- JVM lane, focused `-n` runs; background nohup pattern for anything long; conclusions from "Ran N tests" lines only. NEVER `yang.call.exception-contract-test` locally (owner ruling).
- No scanner-golden regen expected (no prelude change); if the prelude moves, run the generator (deferred set per run, three-run byte-identical).
- Kondo 0/0 on touched files. Commit per finding (three commits).

## Deliverable

Final agent message: F1's chosen semantics + the pin's before/after, F2's assertion and what it protects, F3's rewritten comment, all counts.
