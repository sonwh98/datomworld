Created-GMT: 2026-11-23 10:1x GMT
Created-Local: 2026-11-23 17:1x +0700
Dispatched-By: master-green orchestrator seat (interactive; owner-directed dispatch)
Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2)
Task-ID: 1795430000000-qa-engineer-manifest-address-golden

# Task: re-mint the stale py manifest-address golden (the only yang.python red at tip)

Role: QA, TDD and Verification Engineer

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-23 17:1x +0700 | Status: active | Rationale: implementation lane; jupiter POOL 2 (credits restored, probe OK); JVM verification on the 48-core host

## Evidence of record (fresh isolated probe, jupiter, own clone, origin/master `3051a5cb`)

`clojure -M:test -n yang.python.antlr.float-address-test -n yang.python.antlr.linked-prelude-test`
→ Ran 26 tests / 229 assertions / 1 failure, 0 errors.

`linked_prelude_test.cljc:258` `manifest-address-test`:

    expected: (= manifest-golden address)
    actual: (not (= :segment/blake3-34893d591a14df92a0baf2f5edf41e1a8260195f8c64dde3e69a0be3bc3a5cb9
                     :segment/blake3-d9aead51dc2da70f8dcd8935f2a8c3bc052037967064b45d1914897ad03a6a9c))

Root cause: the py/assert thunk series (`d4a3fdef`→`7692d347`→`3051a5cb`) changed prelude
content and moved the py module manifest's segment address; the series' re-mint covered the
scanner/lower/float-address/free-name goldens but missed this one. Pin-re-mint family
(precedents 06065e42, 8018005d): re-derive FROM SOURCE, never copy the observed actual
blindly — confirm the new address is what the documented generator produces.

## Read first

1. `docs/agents/build-n-test.md` — focus rule, worktree discipline, golden re-mint conventions
2. `docs/agents/roles/qa-engineer.md` — this role
3. `test/yang/python/antlr/linked_prelude_test.cljc` — the golden and its provenance comment
4. The manifest/pin generator the test file or its design doc names (find and use it)

## Mission

- Re-mint the manifest-address golden at `linked_prelude_test.cljc:258` from the current
  source via the documented generator; two-run determinism check (two invocations, identical
  output) recorded in the findings.
- File ownership: `test/yang/python/antlr/linked_prelude_test.cljc` ONLY (the one golden).
  No production code, no other pins.
- If the generator's output does not match the observed actual `d9aead51…`, STOP — that is a
  real divergence, findings only.

## Verification (scoped only, jupiter, your own worktree)

1. `clojure -M:test -n yang.python.antlr.float-address-test -n yang.python.antlr.linked-prelude-test`
   → 26 tests / 0 failures, TWICE in two fresh JVMs (record both rc/Ran lines).
2. One adjacent scoped ns green (unchanged evidence), e.g. `yang.python.antlr.lower-test`.
3. clj-kondo on the touched file: 0 new errors/warnings.

## Deliverables

Branch `fix/manifest-address-golden` pushed from base `origin/master`; findings
`collab/exception-track/1795430000000-qa-engineer-manifest-address-golden.codex-gpt-6.1-terra.findings.md`
in the worktree (generator used, both determinism runs, every rc/Ran line, head sha). The
orchestrator verifies and lands through the countersign protocol. Never merge to master
yourself.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
