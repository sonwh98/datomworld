Created-GMT: 2026-11-09 17:55 GMT
Created-Local: 2026-11-10 00:55 +0700
Coding-Agent: agy
Session-ID: ccc35339-416b-44a2-8e7a-771636b20152 (resume)

# Task: reconcile the reviewer's two blocking findings on your donor-link fix

Role: Yin.VM Runtime Engineer (round 2, same session)

Implementers:
- Model: gemini-3.8-flash-high | Assigned: 2026-11-10 00:55 +0700 | Status: active | Rationale: same session; findings reconciliation

Resume your work in `/Users/sto/workspace/datomworld-xlink` (unchanged rules:
only that worktree, only the G0 commands below, no commit, no ledger edits).

Read first:
- Review findings (the round-1 review of your fix):
  `/Users/sto/workspace/datomworld/collab/exception-track/1794244200000-reviewer-donor-link-absent-fix.kimi-k3.1-pro.stdout.log`
  — the review body is in the final response; verdict CHANGES-REQUIRED,
  blocking findings 3 and 4.

## Finding 4 (fix it): silent nil on refused publication

`publish-donor!` collapses ANY publication refusal into
`{:address nil :links nil}`. Change it to throw immediately on
`(:status result)` being `:refused`, carrying the complete refusal map in
the ex-data. Keep the success path identical. This is a test-kit
diagnosability fix, not a behavior weakening.

## Finding 3 (resolve with evidence): which rows does the fix actually restore?

The reviewer is right that PHP provenance rows use the PYTHON boundary
donor (`test/yang/call/exception_probes.clj:19-20`), not the Clojure donor
— so your findings' claim that the fix repairs the 6 PHP provenance rows is
unsupported. But the reviewer lacked the ledger: the ledger of record
`test/yang/call/exception_r0_results.edn` (e.g. line 135) records the PHP
provenance rows as `:error` AT BASELINE with
`"data primitive refused"` — they were never reds. Determine, with
evidence:

1. Run the second authorized confirmation point:
   `clj -M:test -n yang.python.antlr.exception-call-test -v yang.python.antlr.exception-call-test/python-to-php-test`
   — report the exact tail. The Python→PHP pair rows use the CLOJURE donor
   via `published-donors` (`exception_legs.clj:206-219`), so they should be
   restored to their ledger rows (green/red as recorded, zero absent
   refusals). If they are not, diagnose.
2. For the PHP provenance rows: their ledger state is `:error` with the
   "data primitive refused" signature. Determine from the code (and, if
   needed, ONE focused run of
   `clj -M:test -n yang.call.exception-contract-test -v yang.call.exception-contract-test/php-provenance-test`
   — this is now an authorized third command) whether the current error
   signature still matches the ledger's (whatever its text) or has drifted
   to `Module link refused: absent`. If it drifted to absent, find the
   publication that now fails (suspect: the `php` runtime module published
   by `php-prelude/module-spec`, or another C1-claiming tree) and fix it in
   the kit the same way. If it matches the ledger, say so and stop.
3. Recompute the honest row accounting: state exactly which of the
   problem statement's "24 affected rows" your fix restores and which were
   already-error-at-baseline rows whose state must EQUAL the ledger rather
   than be green. Do not overclaim.

## Deliverable

Append a round-2 section to your findings file
`/Users/sto/workspace/datomworld/collab/exception-track/1794241000000-engineer-donor-link-absent.gemini-3.8-flash-high.findings.md`
(append; never rewrite earlier sections), covering: the finding-4 diff, the
python-to-php-test output tail, the provenance-row determination with
evidence, and the corrected row accounting.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
