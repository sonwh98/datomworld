Created-GMT: 2026-11-13 00:40:00 GMT
Created-Local: 2026-11-13 07:40:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a1d1ee-523c-7be2-b321-8eb8aae65cb7 (terra thread resume; capture new thread.started for THIS run)
# Task: engineer — oracle reconstruction fixture per sol's ruling (RESOLVED FIXTURE-SIDE)
Role: Engineer
Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-13 07:40 +0700 | Status: active | Rationale: you surfaced the knot; sol's ruling names the exact edit site.

## The authority

`collab/exception-track/1794565000000-consult-oracle-reconstruction-transport.gpt-6.1-sol.findings.md`
(consult verdict — read it fully; the §4 instruction is your spec verbatim). Worktree
`/Users/sto/workspace/datomworld-r3-orch`, branch `exception-r3-impl` @ `18870268`.

## The task (sol §4, in brief — but follow the ruling's detail)

Edit ONLY `test/yang/latte_oracle_test.clj` (the reconstruction test + fixture-local helpers):
- fixture-local UAST driver per sol's sequence: build under normal lowering; AFTER construction
  returns and handlers unwind, execute `yin/def clj.rt/ctx nil` (runtime retirement via EXECUTED
  guest UAST, not field edits); bind checkpoint; UCF checkpoint; re-execute the original ctx init
  node (reuse `continuation-uast`'s initialization node); enter original `top-boundary`; invoke
  existing `unparse` via `yang.call/invoke(checkpoint, 3)`.
- RETAIN the original compilation's descriptors/admission helpers (separate compilation fails
  `:not-callable` — admission captures the private token).
- Compose with `yang/annotate-call-sites`; fresh address/source-index.
- Preserve EVERY existing assertion: code-row execution both lanes, genuine UCF/CBOR
  reconstruction, closure checks, both resumed results, host-callback rejection, the captured
  registry. Change both validation limits to **500,000**. Ship no mocks/redefinitions.
- Retain upstream LaTTe pin `86fca0d1d054af005cb37395eae04500d45e6279`.

## Verification

`clj -M:test -n yang.latte-oracle-test` → 0F/0E (all 156 workloads — the reconstruction
included); `yang.clojure-test` + `yang.core.call-test` stay green; kondo 0/0 on the test file.
No kit, no VM, no depth-evidence edits (the committed 1.20× record stands; the fixture change
doesn't alter depth measurement — if it does, re-record per the P2-4 discipline and say so).
Commit + push.

End with Completed-GMT/Completed-Local and: **RECONSTRUCTION FIXED: <sha> | latte-oracle 0F/0E**
or **FINDING: <what>**.
