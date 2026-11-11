Created-GMT: 2026-11-09 08:42:00 GMT
Created-Local: 2026-11-09 15:42:00 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, exception-track orchestrator seat)
Session-ID: not-applicable (interactive seat)

# REPAIR NOTICE to the exception R3 brief — rebase onto post-cc1 master, re-pinned sites

Supersedes the site citations in `collab/yang-clojure/1795040000000-engineer-exception-r3.prompt.md`
(which is NOT edited — `collab/` is append-only; this notice is the authoritative addendum).

Supersedes the base commit: the brief says "off master 2093ba50". cc1 has since landed
(`b1b9abae`, round 5 `847aa885`), which rewrote `src/cljc/yang/clojure.cljc` (+233 lines,
3026 → 3259). **Every line citation in the old brief has drifted. Use the sites below.**

## Why cc1 matters to R3's brief specifically

cc1's occurrence-identity work is in the SAME file, and it moved the escape machinery.
The old brief's "outcome-token channel (:1115, :1125-1133) is R4's scope — do not touch"
now points at nothing at those lines. The correct split on post-cc1 master:

| Region | Lines (master `b1b9abae`) | Owner |
| --- | --- | --- |
| `condition->outcome` — the `:yang.call/thrown` reclassifier | clojure.cljc:1518–1530 | **R3 (this round)** |
| `raise` lambda / `:clj/escape-token` + `dsg__call_token` auth | clojure.cljc:1527–1531, 1548–1558, 1590–1602 | **R4 (do not touch)** |
| entry-body tail reclassifier `(u/app raise (u/app (u/v 'yang.call/thrown) …))` | clojure.cljc:1307–1310 | **R3** |
| `:yang.call/thrown` literal at the `condition->outcome` comparison | clojure.cljc:1519–1520 | **R3** |

## The R3 defect, restated against post-cc1 master

`condition->outcome` (1518–1530) is the single place where a caught condition becomes an
outcome packet. Its else-branch wraps EVERY non-`:yang.call/thrown` reason in
`(outcome-node error-tag condition)` — the condition survives as catch data — but the
`:yang.call/thrown` branch **unwraps to `:yang.call/value`**, discarding the qualified
reason and all evidence. Entry-body's tail path (1307–1310) re-applies
`yang.call/thrown` to an already-outcome value, so an unchanged rethrow through the
public escape reclassifies twice and the original condition does not survive.

**R3's required change (ruling v2 §R3):** an unchanged rethrow forwards the COMPLETE
original condition — fully qualified `:yang.call/{not-callable, invalid-invocation,
unsupported-binding}` reason AND plain evidence — with no new wrapper. The
`:yang.call/thrown` unwrap at 1519–1526 becomes conditional on the absence of a
retained condition, or is removed in favor of carrying the condition through.

**Do NOT touch:** the escape packet mechanism (`:clj/escape-token`, `dsg__call_token`
authentication at 1548–1558 and 1590–1602). That is the intra-unit token channel —
R4's scope. R3 changes WHAT is carried in the outcome, not HOW the packet is authenticated.

**R5 stays:** forged or source-writable attributes never authenticate a retained
condition; authenticity comes from the guest's own protocol machinery
(`yang.call/condition` construction), not value inspection.

## Kit status (the old brief's blocking flag is resolved)

The old brief says "the kit is NOT YET ON MASTER." It landed at `0fa3ff7a` (+ `215d9ba6`
for the `^:slow` gate). Your round MUST now run the kit acceptance, not defer it:

```sh
clj -M:test -n yang.call.exception-contract-test -n yang.python.antlr.exception-call-test
```

Baseline of record, verified by this seat on 2026-11-08 (`acceptance-ledger.md`):
**10 tests / 279 assertions, 159 failures / 39 errors** (= 81 passes). Your round's R3
deltas must flip the Clojure protocol rows from red to green in
`collab/exception-track/acceptance-ledger.md`: `:clojure/protocol-{not-callable,
invalid-invocation, unsupported-binding}` (seed: red) and their `saved-protocol/*`
(R3/R5, red). Do not touch `:clojure/shape-*` (already green) or anything Clojure→Python
(R2, the python seat's).

## Base and verification

- Branch from **current master `b1b9abae`** (or later — re-derive; cc1 is the only
  movement that matters to this file).
- Focused local (G0): the new regressions + existing D2 pins + attribution battery +
  cell-ref pins + kondo 0/0 on touched files.
- Suite-scale (remote, shiva/jupiter): `bb test:changed --changed src/cljc/yang/clojure.cljc`
  (all three lanes; Dart in its own clone per the overlap rules). The oracle namespace
  is the critical regression surface — the escape path is the oracle's own machinery.
- Plus the kit acceptance run above. Round acceptance = your record + ledger deltas + kit green.

## Deliverables unchanged

1. The rethrow fix in `yang.clojure.cljc`.
2. Regressions in `test/yang/clojure_test.clj` (root cause, qualified reason, plain
   evidence through an unchanged rethrow; the public escape forwards the complete condition).
3. Existing pins stay green. 4. kondo 0/0. Commit on your branch; push; do NOT merge.
   File your round record + ledger deltas here as `collab/exception-track/<ts>-engineer-exception-r3.*`.

— exception-track orchestrator
