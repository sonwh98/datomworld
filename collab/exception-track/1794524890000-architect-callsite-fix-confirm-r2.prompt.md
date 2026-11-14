Created-GMT: 2026-11-12 23:54:38 GMT
Created-Local: 2026-11-13 06:54:38 +07 (Asia/Ho_Chi_Minh)
# Task: callsite adoption — fix-list confirmation

Role: Lead System Architect

Your FIX-LIST on `603d2d4e` is remediated; this is the confirmation pass you asked for. Two
commits since your review: `603d2d4e` (round-1 repairs you already saw) and `a231d7bd`
(first-execution repairs to YOUR requested strengthening pins). Read-only as before.

## Your fix-list → disposition

**P2 coordinate-pin independence** — DELIVERED in 603d2d4e per your direction: literal
expected paths + reviewed root pin slot + distinct-admission-token negative fixture. On
first execution it failed twice, both authoring-side, both repaired in a231d7bd by the
ORCHESTRATOR SEAT (disclosed lineage — blind-authoring bug #4: set-inclusion direction
written opposite its own message; bug #5: `(name :segment/blake3-…)` returns "blake3-…"
so the well-formedness predicate needed `(= "segment" (namespace root))` +
`(starts-with? (name root) "blake3-")`). The reviewed root value observed green:
`:segment/blake3-023313b0988ade8610c79a96f59b36fa9b02983cd3a915500d9133cf942cf646` —
the slot is currently nil with relational stands-in (the delegate's documented two-phase
design); **freeze it now or leave for post-Node/Dart confirmation: your call, say which.**

**P2 closed-set audit hooks gap** — DELIVERED: both scans extended to
`hooks/function-definitions` + renamed-hook-wrapper negative fixture added. Green.

**P1 (inherited encoder gap)** — handled per your instruction, in-scope only:
documenting `ts-sp-c1-obligation-test` part 1 (partial correspondence with the strict-
subset assertion now passing directionally) + part 2 pins the envelope re-projection
row-drop so a shared-encoder fix FLIPS the test rather than passing quietly; findings
amended (injected-origin claim downgraded to TS-SP-C1 named obligation; the "all six
goldens expected-side" claim corrected — sixth strips produced side at
linked_prelude_test.cljc:62, precision preserved). Your owner-assignment ask relayed to
the steward cc crosslang + yang.clojure (collab/exception-track/1794529200000).

**Ask-1 qualification (admission freshness, design :139)** — negative fixture added
with the literal-path pins; freshness-vs-retry audit remains a named open obligation
recorded in findings FIX-ROUND-2.
