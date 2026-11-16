Created-GMT: 2026-11-16 01:3x GMT
Created-Local: 2026-11-16 08:3x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-assigned unit)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2, -c sandbox_mode="danger-full-access")
Task-ID: 1794854000000-engineer-dyn-continuation-corruption-isolated

# ENGINEER: dyn-* fixtures throw "Corrupt or tampered continuation payload" when `yang.php.antlr.include-test` runs ISOLATED (cold JVM)

## Evidence of record

- Isolated `clojure -M:test -n yang.php.antlr.include-test` at master `8a44b87a` AND at
  `origin/fix/include-ticks-scc-r2` (d19c57e9): every **dyn-\*** fixture row fails with
  `Corrupt or tampered continuation payload {:rule :continuation-registers}` — 14 rows, and
  the per-fixture backends map comes back EMPTY (`#{}` vs `#{:semantic :register :stack}`).
  Orchestrator-measured on jupiter (fresh clone); the yang.php seat independently measured
  the same (their relay `collab/yang-php/1794813045000-to-master-green-seat-include-red-verified-fixed-dyn-unit-new.md`).
- The SAME rows are green when the namespace runs in SUBSYSTEM order (after the full php load
  order). Order-dependent, cold-JVM specific.

## Mission

Root-cause why the `:continuation-registers` rule validates differently on a cold JVM vs after
the full subsystem load order. The seat's suspects (verify, don't assume): an init/registry
populated by an earlier namespace's load (something `def`-initializing global validation
state that the subsystem order happens to warm), or a cold-start path in the continuation
payload encode/decode that skips registration. The defect may equally be in the TEST's setup
(the dyn fixture's own setup may need an explicit init instead of relying on load order) —
the honest disposition could be either; prefer fixing the production cold-start path if the
validation state is genuinely global, prefer the fixture setup fix if the test relies on
incidental warmth. Whatever you change, the isolated run must pass WITHOUT weakening the
continuation-integrity contract ("Corrupt or tampered payload" must still fire on a genuinely
tampered payload — if you cannot see how it could still fire after your change, STOP and
report; that's a contract-weakening smell).

## Constraints

- Base branch: `origin/php-land-staging` (@ 156de2ae — the landing reconcile of the php r2 +
  regset fixes; it will land to master separately, your fix rebases/lands after it). If
  master has moved past it when you finish, rebase onto the newer tip.
- File ownership: `src/cljc/yin/vm/**` (continuation payload encode/decode/validation) and/or
  the dyn fixture's OWN setup lines in `test/yang/php/antlr/include_test.clj`. OFF-LIMITS:
  `src/cljc/yang/php/antlr/siteset.cljc` (r2 landing pending), `lower.cljc`/`prelude.cljc`
  (single-writer seat), any include_test rows other than the dyn setup, the audit/lint tests.
- No assertion relaxation anywhere.

## Verification (scoped only)

1. Isolated: `clojure -M:test -n yang.php.antlr.include-test` → 0 failures (all dyn rows
   green, mutual-include rows stay green).
2. Order-sensitivity gone or explained: after your fix, run the namespace isolated TWICE in
   two fresh JVMs and once after `(require 'yang.php.antlr.e2e-test)` style warm-up if you
   construct it — record all three rc/counts.
3. Non-regression: `clojure -M:test -n yin.vm.provenance-test -n yang.call.python-exception-test`
   (continuation-payload-adjacent suites) → 0 failures.
4. Foundation: `bb test:sub:clj dao.stream` green.

## Deliverables

Branch `fix/dyn-continuation-cold-start` from `origin/php-land-staging`; findings
`collab/exception-track/1794854000000-engineer-dyn-continuation-corruption-isolated.codex-gpt-6.1-terra.findings.md`
(mechanism, the warm-vs-cold contrast evidence, diff, every rc/Ran line, head sha). Never
merge to master; rebase before push if the staging tip moves.
