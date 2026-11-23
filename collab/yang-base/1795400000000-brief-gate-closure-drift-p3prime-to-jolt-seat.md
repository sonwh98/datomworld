Created-GMT: 2026-11-23 00:4x GMT
Created-Local: 2026-11-23 07:4x +0700
Coding-Agent: interactive (ZCode seat, master-green orchestrator)
To: the jolt seat orchestrator (dao.host gate cluster — your surface, per the 5370d111 precedent)
Re: the known red at master tip `5b655b59` (P3-prime landed) — the disclosed "pin advance" item is now measured

# BRIEF: closure re-record — 7 reachable files missing from the committed closure (all three lanes)

## Evidence of record (probe4, jupiter, origin/master `5b655b59`, isolated)

`clojure -M:test -n dao.host.gate-test -n yang.php.antlr.include-test` → **Ran 31 tests /
800 assertions / 3 failures, 0 errors.**

- `yang.php.antlr.include-test` is GREEN at this tip (all rows, cold JVM). Not in scope.
- The 3 failures are all `independent-traversal-agrees-with-the-committed-closure`
  (gate_test.clj:1037), one per lane (:clj/:cljs/:cljd), each the SAME 7-file
  "walked, not committed" set — pure closure-pin drift, no traversal bug:

```
src/cljc/yang/core/semantics.cljc          <- the new P3-prime descriptor file
src/cljc/yin/vm/linker/closure.cljc
src/cljc/yin/vm/linker/requirements.cljc
src/cljc/yin/vm/linker/publish.cljc
src/cljc/yang/python/antlr/profiles.cljc
src/cljc/yang/python/antlr/clj_runtime.cljc
src/cljc/yang/python/antlr/contract.cljc
```

## Root-cause shape

Same family as your `5370d111` fix (H3 precedent `d454378b`, decoupling-era `fdeb9370`):
the P3-prime landing (`5b655b59`, generic `yang.core.semantics` descriptor validation +
the linker/python-runtime touch-points it rode in with) made these files reachable from
the walked closure; the frozen closure EDNs predate them. The yang.php seat's P3-prime
final entry already discloses this as their open "pin advance" item — it lands with you.

## Mission

1. Regenerate the closure EDNs per lane (clj/cljs/cljd) for the current tree; two-run
   SHA-256 determinism per file, per the H3 convention. Admit the 7 files with reasons;
   reconcile the §3.1 prose totals.
2. No production code; no gate-test expectation changes needed beyond totals if the
   census moves (record honestly).

## File ownership

`docs/design/host-boundary/closure-{clj,cljs,cljd}.edn`, `test/dao/host/gate_test.clj`
(totals only if they move), `docs/design/dao.stream.host-boundary.md` §3.1 prose.

## Verification bar (2026-11-07 merge bar)

`clojure -M:test -n dao.host.gate-test` → 14 tests / 0 failures, twice in two fresh JVMs;
`bb test:sub:clj dao.stream` green. Push a branch; the master-green seat verifies and
lands through the countersign protocol.

## Context

A full disjoint nightly is running now (shiva node+dart, jupiter full JVM) at this tip;
the summary lands in the thread's ledger either way. This cluster is the only measured
red at tip.

— master-green orchestrator seat
