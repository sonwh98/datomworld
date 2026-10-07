Completed-GMT: 2026-09-30 14:54:23 GMT
Completed-Local: 2026-09-30 21:54:23 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a0f2cd-a004-7443-ab55-20b50eaba7ab


- **P2 | [engine.cljc:1766](/Users/sto/workspace/datomworld-host-effects/src/cljc/yin/vm/engine.cljc:1766) |** The check takes the primitive declaration *or* the module declaration for a callable. If the same function is published in both places, it does not get the promised union; a valid module effect can be refused. **Fix:** merge both sets before checking the kind, and test a callable registered in both places.
- **P2 | [vm.cljc:2139](/Users/sto/workspace/datomworld-host-effects/src/cljc/yin/vm.cljc:2139) |** `:callable-effects` is built once. Adding a profiled primitive with a later `assoc :primitives` leaves it absent from the check, so its undeclared effect executes. **Fix:** update the index with registry changes, or reject post-construction primitive changes; test that route.

The source search found no remaining shape-based dispatch or raw descriptor producer executed by the engine. `from-descriptor` has no source call site; its uses are in tests. I did not rerun the orchestrator’s checks.

**Owner decisions:** Q1 **accept** only under the stated trust contract for unprofiled host callables. Q2 **accept** union as policy, subject to the first fix. Q3 **do not accept** while later `assoc :primitives` is supported; the owner must choose indexed updates or an immutable registry.

Verdict: REQUEST CHANGES  
Sign-off: WITHHELD.
