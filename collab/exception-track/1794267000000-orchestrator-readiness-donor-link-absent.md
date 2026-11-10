Created-GMT: 2026-11-09 20:50 GMT
Created-Local: 2026-11-10 03:50 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, exception-track problem seat)
Session-ID: not-applicable (interactive seat)

# READINESS (interim): the donor-link-absent defect is fixed, reviewed, and verified — final gates pending one cross-seat landing

## Shipped (uncommitted in /Users/sto/workspace/datomworld-xlink, detached @ a5a3ac0e)

Three kit files, +113/−68: `test/yang/call/conformance.clj`,
`exception_legs.clj`, `exception_probes.clj`.

- **The fix**: `publish-donor!` C1-finalizes the synthetic donor tree
  (`u/annotate-call-sites` when the tree claims call sites), mirroring
  `yang.core.call` and `yang.clojure.prelude`.
- **Fail-fast everywhere**: one private `publish-guest!` helper guards all
  four kit publication sites; a refused publication throws with the full
  refusal map. No silent-nil address path remains.
- **Per-row error routing**: `kit/error-results` + Throwable catches at all
  five kit entry points deliver any future publication refusal as
  per-backend `:error` rows with `:host/data` carrying the refusal map —
  the original "24 rows away from its cause" failure mode is structurally
  closed.

## Attribution

Causal commit `b825498e` ("C1 survives the production pipeline", merged
via `b1b9abae`): its C1 call-site coverage validator refused publication of
the unmarked synthetic donor wrapper tree → nil address → `:absent` at
link (`interpreter.cljc` name-env miss). The regression's entire footprint
was `clojure-to-python-test`'s 24 assertions; the problem statement's row
list overstated (python→php and php-provenance rows are error-at-baseline
ledger state).

## Verification (all by this seat, serially, on the r3 diff)

| Test | Result | Ledger |
|---|---|---|
| clojure-to-python-test | 9 F / 0 E | 9 F / 0 E ✓ |
| python-to-php-test | 6 F / 18 E | 6 F / 18 E ✓ |
| php-provenance-test | 18 F / 21 E | 18 F / 21 E ✓ |

## Review: ACCEPT (kimi k3.1-pro, 3 rounds, different family from the gemini author)

Round 1 CHANGES-REQUIRED (#3 coverage overclaim, #4 silent nil) → fixed in
r2; round 2 CHANGES-REQUIRED (#4 partial, #3-accepted, new P2 reporting
path) → fixed in r3; round 3 **ACCEPT** with file:line evidence on all
four confirmation points. Full trail:
`1794244200000-reviewer-donor-link-absent-fix.kimi-k3.1-pro.findings.md`.

## Remaining gates (honest accounting)

1. **Rebase onto post-fix master** — the master-green seat's architect
   ruling (relay thread
   `collab/orchestration/1794270000000-*` and its reply) lands a
   `publish.cljc` change; my acceptance run must base on that surface.
   **Gated on their landing ping.**
2. **Acceptance-command re-run on shiva** (`clj -M:test -n
   yang.call.exception-contract-test -n yang.python.antlr.exception-call-test`,
   target 159 F / 39 E) — after the rebase.
3. **Commit** — waits on architect sign-off or owner instruction per the
   coordination contract; nothing is staged.
4. Unrun checks: none owed by this seat beyond the above; the daily
   nightly is the collateral net per the 2026-11-01 ruling.

Session log: `orchestrator-logs/1794242858-zcode-glm-5.3-flash-donor-link-absent-orchestration.md`.
