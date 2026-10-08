Completed-GMT: 2026-10-03 17:39:08 GMT
Completed-Local: 2026-10-04 00:39:08 Asia/Ho_Chi_Minh

- P2 | src/cljc/yang/python/antlr/prelude.cljc:448 | A closed delegate answers a thrown `StopIteration(7)` with `[:raise e]` (line 262); delegation re-raises it (line 434), producing PEP 479’s `RuntimeError`. If the inner is independently closed after `next(outer)`, CPython instead consumes `outer.throw(StopIteration(7))` as delegation completion with value `7`. [CPython 3.9.6 implementation](https://github.com/python/cpython/blob/v3.9.6/Objects/genobject.c#L422-L436). | Translate a delegate’s raised StopIteration, including subclasses, into `[:return e.value]`. Preserve inner-body PEP 479 conversion. Add a regression test.

- P2 | src/cljc/yang/python/antlr/prelude.cljc:1644 | A dictionary size-change exception escapes before iterator state updates. Restoring the original dictionary size therefore lets this iterator resume; CPython permanently invalidates it after the first size-change error. [CPython 3.9.6 implementation](https://github.com/python/cpython/blob/v3.9.6/Objects/dictobject.c#L3416-L3420). | Persist an invalidated state before raising and retain the error on subsequent advancement; add a parity regression.

- P2 | test/yang/python/antlr/prelude_parity_test.cljc:369 | The portable acceptance form covers two generators with one resuming caller. It does not exercise deeper chains, changing callers, or suspension during exception unwinding. The retained-state check at e2e_c2_test.clj:780 measures shallow continuation payloads; `size` explicitly excludes referenced contents (line 753), contrary to §8.5.3’s complete reachable-graph requirement. | Add these delegation scenarios on all four evaluators across hosts, and measure reachable continuation/heap retention under changing callers and forced collection.

The requested depth ruling cannot be verified on this checkout: safepoint.cljc:30 implements only signals hooks; §8.5.2 still describes recursion accounting as pending. New delegation entries at prelude.cljc:448–450 perform no limit comparison or `:base` rebasing. This is an integration gap, not evidence of a newly broken existing depth implementation.

The five acceptance outputs match hand tracing, including `sum(range(3000)) = 4498500`. Assertions remain effective, existing slow tags are preserved, and the new slow tag is correctly placed. Exactly five content-address goldens changed; the hook golden remains unchanged. Two-argument `iter` is rejected by normal arity checking. No additional portability or scope issue found.

Read-only review completed; no suites run.

**Ready-to-commit verdict: NOT READY.**