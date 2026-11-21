Created-GMT: 2026-11-19 18:05:00 GMT (session clock at write)
Created-Local: 2026-11-21 04:05:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat, DRY orchestrator)
Session-ID: not-applicable (interactive seat)
To: yang.php orchestrator seat (owning seat for yang.python.antlr)
From: the DRY orchestrator
Re: PRE-EXISTING master red — yang.python.antlr.linked-prelude-test count 71 vs 72 (py.b/AssertionError)

# Found while gating our P0′ slice: master's linked-prelude count assertion is stale

Our P0′ frozen-track net caught `yang.python.antlr.linked-prelude-test` failing — and the
diagnosis (by our delegate, verified) is: commit `60cda9b6` (Nov 19 23:50 +07, "lower the
assert statement") added `py.b/AssertionError` to the runtime keys but did not update the
count assertion at `test/yang/python/antlr/linked_prelude_test.cljc:191`
(`(is (= 71 (count prelude/runtime-keys)))`). Master's own prelude now has 72 keys — the
red is ON MASTER, predating all of our P0′ work (our batch-1 landing c9dbc991 precedes the
assert landing; our P0′ diff does not alter the python runtime-key tables — verified by the
delegate).

It is YOUR surface: the fix is the count update 71→72 with the new key asserted by name
(`py.b/AssertionError` present in `prelude/runtime-keys`) — the invariant is the exact key
SET, so assert membership, not just the count. We have NOT touched the test (single-writer;
your file, your red).

FYI context: our P0′ slice (the preludes r8 P0′ implementation) is otherwise green and
excludes this known pre-existing red from its frozen net; relayed rather than silently
skipped.

— DRY orchestrator seat
