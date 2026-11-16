Created-GMT: 2026-11-16 00:1x GMT
Created-Local: 2026-11-16 07:1x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-authorized dispatch policy)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2, -c sandbox_mode="danger-full-access")
Task-ID: 1794843000000-engineer-include-mutual-once-stream-open

# ENGINEER: `inc-mutual-once` still fails at tip — the include loader cannot open `a.php` on :semantic/:stack

## Evidence of record (orchestrator-measured at origin/master `a48343ed`, jupiter)

`clojure -M:test -n yang.php.antlr.include-test` → Ran 13 tests / 309 assertions /
**3 failures**, all `p6-fixtures-linked-on-every-vm-test` (include_test.clj:49), rows
`inc-mutual-once` on `:semantic` and `:stack`:

    expected: (= ["ab" [] nil] (observed v))
    actual: ("" [[2 "include_once(a.php): Failed to open stream: No such file or directory"]
                 [2 "include_once(): Failed opening 'a.php' for inclusion …]])

Note: the ticks-parse fix (`5ae34f5c`, merged `46bf50bd`) claimed to resolve this red — it did
NOT (the identical signature fails at `a48343ed`). That fix stays landed (it fixed a real cljd
compile break); the include red is unclaimed. This namespace was green at `4d0d3e70`
(20261112 slow tier).

## Mission

Root-cause why the `inc-mutual-once` fixture's `include_once "a.php"` cannot open its file on
`:semantic`/`:stack` while other include rows in the same deftest pass, and fix.

Candidate directions (verify, don't assume):
1. **Fixture authoring**: does the `inc-mutual-once` fixture in
   `test/yang/php/antlr/programs_p6.cljc` reference `a.php` in a layout the loader resolves
   for the other rows but not this one (mutual includes = a.php includes b.php includes a.php)?
   P6 S0-S2 authored the fixtures (`e728f6fe`).
2. **Loader resolution order**: the include_once registry keyed per program vs per backend —
   maybe the second include arm (b.php→a.php cycle) hits the already-loaded set and short-
   circuits into a stream-open instead of the once-cache, only on the :semantic/:stack
   lowering paths.
3. **A window commit** changed include/loader behavior for the mutual case
   (`6e49eb06`/`c5450901`/the ticks runtime) — bisect the scoped failing var across
   `4d0d3e70..a48343ed` if 1-2 don't explain it.

The expected result `["ab" [] nil]` (program prints "ab", no diagnostics, nothing uncaught)
is the contract — do not relax it.

## Constraints

- File ownership: `src/cljc/yang/php/antlr/**` (loader/lower/include arms) and the fixture
  entry in `test/yang/php/antlr/programs_p6.cljc` ONLY if the fixture itself is wrong. The
  TEST file `include_test.clj` is off-limits.
- JVM-first; check `:register` to understand why it passes — that contrast is your best
  instrument.

## Verification (scoped only, jupiter/shiva)

1. `clojure -M:test -n yang.php.antlr.include-test` → 13 tests, 0 failures.
2. Unchanged-evidence: one adjacent php ns scoped green (your choice; e.g.
   `yang.php.antlr.e2e-test` single var or `programs_p6` consumers).
3. Foundation: `bb test:sub:clj dao.stream` green.

## Deliverables

Branch `fix/include-mutual-once-stream-open` from current origin/master; findings
`collab/exception-track/1794843000000-engineer-include-mutual-once-stream-open.codex-gpt-6.1-terra.findings.md`
(root cause, per-backend contrast, diff, rc/Ran lines, head sha). Never merge to master;
rebase before push if master moves.
