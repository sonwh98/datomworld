Created-GMT: 2026-11-09 19:00 GMT
Created-Local: 2026-11-10 02:00 +0700
Coding-Agent: agy
Session-ID: ccc35339-416b-44a2-8e7a-771636b20152 (resume, round 3)

# Task: close the reviewer's round-2 findings on the donor-link fix

Role: Yin.VM Runtime Engineer (round 3, same session)

Resume in `/Users/sto/workspace/datomworld-xlink` (same rules: only that
worktree, no commit, no ledger edits, G0-only test commands — the same
three authorized single-`-v` tests).

The reviewer's round-2 confirmation is in
`/Users/sto/workspace/datomworld/collab/exception-track/1794244200000-reviewer-donor-link-absent-fix.kimi-k3.1-pro.findings.md`
under "## Round 2 confirmation". Finding 3 is ACCEPTED (do not touch the
row accounting again; do fix the prose mislabel in your findings file by
APPENDING a correction note — never rewrite earlier sections).

## Two remaining items

1. **Finding 4, remaining half**: the kit's other publication sites still
   collapse refusals into nil addresses — `published-php`, `published-py`,
   `published-clj` (`test/yang/call/conformance.clj`) and any other
   `h/publish-guest-module!`/`publish-module!` call in the kit whose
   `:address` is read without a status check. Give each the same fail-fast
   throw with the complete refusal map. Keep it uniform: prefer one small
   helper over four copies of the `when`-throw.
2. **Throw reporting path (P2)**: the fail-fast throw currently fires
   outside the per-row error reporting (`run-pair`/`run-frontend` deref
   the donor before the per-backend futures and the per-scenario
   `Throwable` branches), so a future refusal aborts the deftest with one
   test-level error instead of the intended per-backend `:error` rows.
   Restructure MINIMALLY so a publication refusal surfaces as the
   per-backend `Throwable` results the tests already report — e.g. catch
   the publication exception where the donor is dereferenced and deliver
   it as each backend's `:host/error` result — while keeping the
   fail-fast semantics (no silent nil anywhere). If you see a cleaner
   minimal structure, take it and justify it in the findings; if you
   believe the reviewer's bar cannot be met without a design change, STOP
   and write the options instead.

## Verification (same three authorized commands, unchanged)

- `clj -M:test -n yang.python.antlr.exception-call-test -v yang.python.antlr.exception-call-test/clojure-to-python-test`
  → must stay 9 failures / 0 errors.
- `clj -M:test -n yang.python.antlr.exception-call-test -v yang.python.antlr.exception-call-test/python-to-php-test`
  → must stay 6 failures / 18 errors (baseline-equal).
- `clj -M:test -n yang.call.exception-contract-test -v yang.call.exception-contract-test/php-provenance-test`
  → must stay 18 failures / 21 errors (baseline-equal).

Append a "# Round 3" section to your findings file
(`/Users/sto/workspace/datomworld/collab/exception-track/1794241000000-engineer-donor-link-absent.gemini-3.8-flash-high.findings.md`):
the diffs, the three output tails, and the mechanism by which a refusal now
reaches the per-row path.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
