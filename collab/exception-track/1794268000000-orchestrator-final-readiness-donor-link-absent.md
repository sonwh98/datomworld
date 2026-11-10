Created-GMT: 2026-11-10 08:25 GMT
Created-Local: 2026-11-10 15:25 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, exception-track problem seat)
Session-ID: not-applicable (interactive seat)

# READINESS (FINAL): the donor-link-absent problem is fixed, verified cross-host, and the ledger of record is restored

Supersedes the interim report `1794267000000-orchestrator-readiness-donor-link-absent.md`.

## Answer to the problem statement's three asks

1. **Attribution**: causal commit `b825498e` ("C1 survives the production
   pipeline", merged via `b1b9abae`). Its C1 call-site coverage validator
   in `yin.vm.linker.publish` refused publication of the exception kit's
   synthetic Clojure donor tree (unmarked wrapper applications) →
   `publish-donor!` took a nil address → the serving name-env mapped
   `xlang.donor.clojure` to nil → every link refused `:absent`
   (`interpreter.cljc` name-env miss). Not the manifest pin; not registry
   admission — both hypotheses ruled out with evidence. The regression's
   entire footprint was `clojure-to-python-test`'s 24 assertions (the
   problem statement's row list overstated: python→php and php-provenance
   rows are error-at-baseline ledger state).
2. **Fix**: `fix/exception-kit-donor-publication` @ `b0463cd2` (pushed to
   origin, based on `83b52a0b` = master-green's post-fix master): C1-
   finalize the donor tree via `u/annotate-call-sites`; universal fail-fast
   publication (`publish-guest!`) at all four kit sites; per-row error
   routing (`kit/error-results`) so any future refusal lands as per-backend
   `:error` with the refusal map in `:host/data`, never as misleading
   `:absent` rows. Architectural routing note per the problem statement:
   the fix is kit-side because the linker was CORRECT — looking up a nil
   name-env entry must refuse `:absent`; the defect was the kit's
   unpublished-by-contract donor tree.
3. **Acceptance re-run**: shiva `~/ci/datomworld-xtrack-fix` @ `b0463cd2`,
   tmux, ANTLR cache-pass, `clj -M:test -n yang.call.exception-contract-test
   -n yang.python.antlr.exception-call-test`:
   **Ran 10 tests containing 279 assertions. 159 failures, 39 errors.**
   = the ledger of record `exception_r0_results.edn` `:accepted-runtime`
   exactly. Log: `shiva:~/ci/xtrack-fix-acceptance.log`.

## Verification chain (all counts by this seat unless noted)

| Gate | Result |
|---|---|
| Reproduction at `b1b9abae`/`a5a3ac0e` (problem statement + mine) | 24 errors on the single -v test ✓ |
| Focused tests post-fix, laptop, `a5a3ac0e` (mine, serial) | 9F/0E, 6F/18E, 18F/21E ✓ |
| Focused spot-check on rebased `83b52a0b` (mine) | 9F/0E ✓ |
| Focused tests post-fix (delegate r2/r3 claims) | same counts ✓ |
| **Full acceptance command, shiva, `b0463cd2`** | **159 F / 39 E** ✓ |

## Review

kimi `moonshot-ai/kimi-k3.1-pro` (different family from the gemini author),
three adversarial rounds: CHANGES-REQUIRED ×2 → **ACCEPT** with file:line
evidence on all confirmation points. All findings fixed and
reviewer-confirmed; none rejected or deferred. Artifacts:
`1794244200000-*` (review), `1794241000000-*` (engineer findings r1–r3),
briefs `1794241000000/1794245000000/1794260000000/1794264000000-*.prompt.md`.

## Unresolved risks & notes

- **Cross-host runtime-module addresses**: master-green's `83b52a0b`
  (call-sites out of the hashed manifest) is a dependency of this run; the
  shiva acceptance result doubles as the first cross-host datapoint for
  the post-fix kit — green.
- **Unrun checks**: none owed beyond the above; the daily nightly remains
  the collateral net (2026-11-01 ruling). Node/Dart lanes untouched (the
  change is JVM test-kit only; `conformance.clj` is `.clj`).
- **Commit authority**: `b0463cd2` exists on a dedicated fix branch
  (pushed; local-only merge to master has NOT happened). Merge to master
  awaits architect sign-off or owner instruction per the coordination
  contract.
- Laptop-side serial-verification discipline correction (concurrent JVM
  runs) was applied mid-round and recorded.

## Round artifacts (for archive)

`collab/exception-track/`: problem statement, engineer briefs r1–r3 +
findings r1–r3 + stdout logs, review briefs r1–r3 + findings + stdout
logs, interim + final readiness. `collab/orchestration/`: relay thread +
addendum + reply + ack2 + landing ping. Session log:
`orchestrator-logs/1794242858-zcode-glm-5.3-flash-donor-link-absent-orchestration.md`.

— exception-track problem seat. The track's acceptance instrument is
unblocked: rounds touching the 24 formerly-blind rows can now be accepted
against ledger deltas again.
