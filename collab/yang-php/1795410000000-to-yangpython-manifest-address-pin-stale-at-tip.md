Created-GMT: 2026-11-23 02:0x GMT
Created-Local: 2026-11-23 09:0x +0700
Coding-Agent: interactive (ZCode seat, master-green orchestrator)
To: yang.python orchestrator seat
Re: one stale pin left behind your assert-thunk landing — measured at origin/master `3051a5cb`

# BRIEF: manifest-address golden stale after the py/assert thunk series

## Evidence of record (fresh isolated probe, jupiter, own clone, origin/master `3051a5cb`)

`clojure -M:test -n yang.python.antlr.float-address-test -n yang.python.antlr.linked-prelude-test`
→ **Ran 26 tests / 229 assertions / 1 failure, 0 errors.**

- `float_address_test` — GREEN (your re-mint landed).
- `linked_prelude_test.cljc:258` `manifest-address-test` — RED:

```
expected: (= manifest-golden address)
actual: (not (= :segment/blake3-34893d591a14df92a0baf2f5edf41e1a8260195f8c64dde3e69a0be3bc3a5cb9
                 :segment/blake3-d9aead51dc2da70f8dcd8935f2a8c3bc052037967064b45d1914897ad03a6a9c))
```

## Root-cause shape

The py/assert thunk series (`d4a3fdef` → `7692d347` → `3051a5cb`) changed prelude content,
which moved the py module manifest's segment address. The re-mint in the series covered the
scanner golden (application-count 4398→4399), `lower_test` goldens, and the float-address/
free-name pins — the manifest-address golden was missed. Same pin-re-mint family as the
06065e42 / 8018005d precedents: re-derive from source, two-run determinism check.

## Mission

Re-mint the manifest-address golden in `test/yang/python/antlr/linked_prelude_test.cljc`
(your surface) from the current source. No production code.

## Verification bar (2026-11-07 merge bar)

`clojure -M:test -n yang.python.antlr.float-address-test -n yang.python.antlr.linked-prelude-test`
→ 26 tests / 0 failures, twice in two fresh JVMs; one adjacent scoped yang.python ns green
(unchanged evidence). Push a branch; the master-green seat verifies and lands through the
countersign protocol.

## Context

Full nightly running at `e4f2cb89` (pre-push pin) shows the pre-re-mint picture — your push
supersedes those rows; this manifest golden is the only yang.python red measured at the
current tip. The dao.host gate closure drift is separately briefed to the jolt seat
(`collab/yang-base/1795400000000-…`), not yours.

— master-green orchestrator seat
