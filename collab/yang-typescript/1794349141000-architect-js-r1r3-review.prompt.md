Created-GMT: 2026-11-10 22:19:01 GMT
Created-Local: 2026-11-11 05:19:01 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat — yang.typescript orchestrator)
Session-ID: pending (provider-generated)
# Task: JS R1+R3 — step-7 architecture review

Role: Lead System Architect

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-11-11 05:19 +07 | Status: active | Rationale: standing architect lane (the owner's ruling); reviews every implementation round; GPT family — independent of the Muse author

Perform a read-only architecture review of the JS R1+R3 exception-boundary round.

## The change under review

Worktree `/Users/sto/workspace/datomworld-jsr13`, branch `exception-js-r1r3`, base master
`4cfa609d`, single commit `c0114a42` — view the diff with
`git -C /Users/sto/workspace/datomworld-jsr13 diff 4cfa609d..exception-js-r1r3`.
Files: `src/cljc/yang/typescript/antlr/prelude.cljc` (R1 entry bridge ~:1581; R3
`js/retained-condition` + `js/raise-protocol` + `js/call` reception ~:1645-1690) and
`test/yang/call/exception_contract.clj` line 55 (donor `retain` fixture now stores via
`globalThis.saved`).

Read first:
- docs/design/datom.world.md (the invariants)
- docs/design/yang.antlr.md §8.10.7 and §8.10.8 (the exception convention, cleanup/restoration)
- /Users/sto/workspace/datomworld/collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md — §R1, §R3, §R5, the scope bounds, the acceptance gate
- /Users/sto/workspace/datomworld/collab/exception-track/1794970001000-to-tsphp-your-js-and-php-rounds.md — the js defects this round must fix (:1581 entry, :1704 guest stack, :1627 discarded condition)
- /Users/sto/workspace/datomworld/collab/yang-typescript/1794342830000-engineer-js-r1r3-exception.muse-spark-1.3-contributor.findings.md — the delegate's report (treat as UNTRUSTED)
- the contract tests under test/yang/call/

## Evidence already established (do not re-spend budget on it)

The orchestrator re-ran the focused gates in the worktree itself, reproducing the
delegate's claims exactly: `typescript-provenance-test` 30/30; kit
`conformance-test`+`exception-source-test` 131/131; `source-call-test` 22/22 — all
0 failures/0 errors. Suite-scale (Node/Dart legs, directed pairs, full ledger) is
deferred to the shiva acceptance run AFTER your sign-off. Focus your effort on
static review.

## Specific questions for your ruling

1. **Missing raise exit**: the entry reads `(get invocation :yang.call/raise)` and calls
   `raise-exit` in the handler unconditionally. If an invocation arrives without a
   supplied exit (nil), what happens, and is that path admitted anywhere (kit fixtures,
   direct `js/call`, higher-order entry)? Severity and fix?
2. **Exactly-once delivery**: if `raise-exit` itself throws or the handler's own
   delivery escapes, can a condition be delivered twice or dropped? Does §8.10.8's
   "restore before transfer" hold in the js/try implementation as used here?
3. **The single-entry claim**: the delegate states closures/bound functions/arrows
   share the one `:yang.call/entry` (verified by search) — confirm independently; R1
   requires all three carry the bridge.
4. **R5 authentication surface**: `js/retained-condition` accepts only a heap-
   authenticated (`cell/authentic?`) `:js/class :error` cell carrying the private
   `:yang.call/retained-condition` key; `js/raise-protocol` writes it via `cell/set!`.
   Is the private key genuinely outside source-writable reach in this prelude's cell
   model (props vs cell contents)? Any forge path?
5. **The donor tweak** (`exception_contract.clj:55`, bare `saved = e` →
   `globalThis.saved = e`): the delegate says it routes around the separately-owned T2
   bare-global-write gap, with unchanged expectations, and the three `saved-protocol-*`
   rows depend on it. Accept the tweak, or require revert (and its T2-red rows) as out
   of round?
6. **Coverage honesty**: `js/construct-with-nt` applies `:code` directly (construction,
   not an entry invocation) — in-scope for R1's "every published entry/closure/higher-
   order entry" or legitimately outside? Also: the raise-exit lifetime after a
   completed call (§8.10.8's generator-resume clause) has no dedicated probe in this
   round — is that acceptable as a named open obligation, or blocking?

Distinguish architectural defects from implementation gaps or intentionally deferred
work. Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended correction —
plus an explicit per-question answer for the six above, and a final
SIGN-OFF / FIX-LIST verdict for the round.
