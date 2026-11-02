Created-GMT: 2026-10-31 02:10:00 GMT
Created-Local: 2026-10-31 09:10:00 +07 (+0700)
Coding-Agent: muse
Session-ID: pending (caller UUID generated at dispatch)

# Task: fix the yang.python nightly reds — the stale dht_import fixture and the linked_prelude expectations

Role: Compiler Engineer.

WORK TREE: /Users/sto/workspace/datomworld-nightr (detached at origin/master c02c7c5fa; the parser is
BUILT, node_modules installed). Commit to a new branch `nightly-red-fix` created from HEAD. You are
the one writer.

THE PEER BRIEF (read first): the orchestrator's inbox —
/Users/sto/workspace/datomworld/collab/1793456746000-orchestrator-yangpython-nightly-red.prompt.md.
The nightly (both hosts, sharded JVM at master) shows exactly 3 failures in yang.python:

1. `dht_import_test.cljc:107` an-unsupplied-import-is-refused-at-publication-test — the fixture
   passes the placeholder `:test/address` as the py address to publication/publish-units!; dao.jing
   (dao.jing.cljc:445, the H1 guard — LONG-STANDING correct behavior, do not touch dao.jing) now
   refuses it because the S3 wiring's publication path reads the address's bytes (the call-address
   derivation). THE FIX: mint a real content address for the stand-in (publish the stand-in unit or
   use the store's own minting) while PRESERVING the test's intent — the unpublished-import refusal
   path must still be exercised (k must remain unpublished when t publishes). Check
   test/yang/python/antlr/dht_import_test.cljc's helpers (stand-in-address, unit) and the mem store.
2. `linked_prelude_test.cljc:103 and :112` module-uast-is-one-wide-application-test — the assertions
   predate the yang.call emission: the linked module uast now includes the yang.call runtime
   machinery (the runtime-keys/placeholder-count and the definition-order expectations). THE FIX:
   update the expectations to the new module shape per the SAME assertions' spirit (the runtime
   placeholders first, then the definitions in order; Rule R holds). Read what
   prelude/runtime-keys holds now vs the test's expectation; the correct fix is updating the
   EXPECTED shape, never weakening the structural invariants (Rule R, the stripped-symbol check at
   :118+ must keep passing — if THEY fail too, that is a finding, not an expectation).

ADJUDICATION (record in the report): the dao.jing guard is correct long-standing behavior (H1,
0aa710e7, Sept 23) — the defect location is yang.python's fixtures/expectations, stale against the
S3 emission changes. Do NOT edit src/cljc/dao/jing/** or anything outside yang.python test files
unless the diagnosis proves otherwise (if it does, STOP and report).

VERIFY (the G0→G1→tri-host cycle from the peer brief): G0 `bb test:sub:clj yang.python` iterate; G1
`bb test:sub yang.python`; then `bb test:changed` tri-host — the JVM focused namespaces
(dht_import_test, linked_prelude_test, float_address_test as the pin guard), the Node lane, and the
Dart lane for the same fixtures (the cljs tier showed a dht_import failure cluster on shiva's
nightly — confirm those clear too). The laptop-unload rule: run the heavy lanes on
shiva.stigmergy.systems (ssh; the scratch clone ~/ci/datomworld-python-s3 fetches your branch —
push `nightly-red-fix` to origin when your local gates pass, then run the shiva lanes). Local: only
single focused namespace runs. kondo 0/0 + cljstyle on changed files.

CONSTRAINTS: single turn; no approvals; commit to nightly-red-fix; push the BRANCH only (never
master); collab/ stays untracked. End with: the adjudication, the fix diff summary, the
verification counts per host, and anything open.
