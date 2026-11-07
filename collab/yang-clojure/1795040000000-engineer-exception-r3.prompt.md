Created-GMT: 2026-11-06 07:00:00 GMT
Coding-Agent: muse (muse-wonder-1.0, API lane)
# Task: exception-uniformity R3 — protocol failures retain and forward the complete condition (production yang.clojure)

Role: compiler engineer (muse). THE SPEC: ruling v2 — `collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` on master (owner ruling of record; read §R3 and §R5 fully). The steward's relay: `collab/exception-track/1794970000000-to-yangclojure-your-r3-then-r4.md`. A fresh codex gate reviews against ruling v2.

Worktree: /Users/sto/workspace/datomworld-r3, branch exception-r3 (off master 2093ba50). You have shell.

## The gap (confirmed at the cited sites by this seat)

- `src/cljc/yang/clojure.cljc:1306` — a protocol failure delivered to the guest becomes catch data.
- `src/cljc/yang/clojure.cljc:1089` (the public escape) — reclassifies through unconditional `yang.call/thrown`: the original qualified reason (`:yang.call/invalid-invocation`, `:yang.call/not-callable`, `:yang.call/unsupported-binding` — fully qualified keywords) and the plain evidence do NOT survive an unchanged rethrow.

## R3's requirement (ruling v2, ALL-FOUR rule)

The generated local exception form is caught and rethrown UNCHANGED — the COMPLETE original condition (qualified reason AND plain evidence) forwards with no new wrapper. The fully qualified keywords map to a documented local exception form retaining the complete original condition. `:yang.call/thrown` as an unconditional reclassifier at the public escape goes away or becomes conditional on the absence of a retained condition.

## Constraints (ruling v2)

- **R5 — no new shape-routing**: forged or source-writable attributes on a wrapper-looking value never authenticate a retained condition. The retained condition's authenticity comes from the guest's own protocol machinery, not from value inspection.
- **The restricted catch surface (single catch, no `finally`) is a source-semantics decision the ruling explicitly preserves** — your rejection tests stay; R3 is about the continuation, not the catch surface.
- The outcome-token channel (:1115, :1125-1133) is R4's scope — DO NOT touch it this round.

## The round-0 kit

The steward's FAILING fixtures + the conformance-kit exception-leg scaffolding: **NOT YET ON MASTER** (the steward's relay promises them "on master before your round starts"; as of this dispatch they are not in the exception-track tree — flagged to the steward). Dispatch rule: implement the fix now (it is fully specified by ruling v2's R3 text); the kit's acceptance fixtures run when the steward lands them — list the exact commands you would run and leave the kit-acceptance as a stated pending gate in your report.

## Deliverables

1. The rethrow fix in yang.clojure.cljc (the retained condition: qualified reason + evidence, forwarded unchanged through the public escape).
2. The regression tests: a protocol failure caught guest-side retains the qualified reason + evidence (unchanged rethrow); the public escape forwards the complete condition; the existing rejection tests (single catch, no finally) stay green.
3. The existing D2 pins, the attribution battery, and the cell-ref pins stay green (the escape path change must not disturb them — run the focused set).
4. kondo 0/0 on touched files.

## Verification (test-boundary ruling)

- Focused local: the new regressions, the D2/attribution/cell-ref pins, kondo. Real outputs.
- Suite-scale: list the exact commands for the seat (the oracle namespace is the critical regression surface — the escape path is the oracle's own machinery).

## Boundaries

- yang.clojure.cljc + test/yang/clojure_test.clj (the new regressions). The outcome-token channel untouched (R4). The catch surface untouched. No yin.vm changes. Commit on the branch; push; do NOT merge.
