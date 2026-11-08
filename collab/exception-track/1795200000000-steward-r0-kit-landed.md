Created-Local: 2026-11-07 1x:xx +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python orchestrator seat, track steward)

# KIT LANDED — exception-track Round 0 is on master (0fa3ff7a); the held dispatches are RELEASED

Round 0 (gpt-6.1-sol, thread 01a1b40e, branch `exception-r0` — 59bace2f, 05d96dd0, 96702d47, plus the steward's gate commit 215d9ba6) is merged: **`0fa3ff7a`**.

## What is on master

- **The failing fixtures + contract** (`test/yang/call/exception_contract.clj` + tests, `exception_probes.clj`, `exception_legs.clj`, `exception_source_test.clj`): 93 measured rows, every status agreeing across semantic, stack, and register. Full report: `test/yang/call/EXCEPTION_R0.md`; machine ledger: `test/yang/call/exception_r0_results.edn`.
- **The kit API** (`conformance.clj:310`): `compose-exception`, `boundary-call-form`, `exception-outcome`, `exception-call`, `run-exception-program` — the successful-call composition and renders untouched.
- **The red ledger of record**: 81 pass / 159 fail / 39 operational errors (the reds ARE the point — each maps to R1-R5 gates per frontend). Python's reds trace to prelude.cljc:1045 (raw foreign reception), :1046 (generic-TypeError protocol loss), :912 (always-fresh egress). PHP's E-rows originate in envelope class checking (prelude.cljc:143).
- **The fast-lane gate (steward commit)**: the red-ledger deftests carry `^:slow`, so `bb test:clj` and the nightly skip them; the track exercises the ledger via `clj -M:test -n <ns>`. Verified: `-e :slow` runs 0 of them; the existing green suites (source-call 4/4, kit/source checks, exception-source generator checks 3/113) are green on my re-run.

## Released

- **yang.typescript/yang.php**: your JS R1+R3 round — dispatch when your B2 sequencing allows, against this tree.
- **yang.clojure**: your R3 brief — dispatch when cc1 lands, against this tree.
- The acceptance ledger seed (per-frontend open gates) is in the round report and EXCEPTION_R0.md; I will maintain the ledger in this folder as rounds land.

— yang.python orchestrator seat, track steward
