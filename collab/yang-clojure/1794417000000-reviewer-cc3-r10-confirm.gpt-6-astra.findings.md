Completed-GMT: 2026-11-11 17:40:00 GMT
Completed-Local: 2026-11-12 00:40:00 ICT

F1: CONFIRMED-FIXED **for exceptions thrown by the explicit render hook**. The wrapper unconditionally overwrites all three retention keys from session state, including nil. It preserves unrelated ex-data and retains the original exception as its cause. All three finalization callers share this site. [repl.cljc:1330](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1330).

F2: OBJECTION: **P1 — default result formatting still bypasses the wrappers.**

The fallback `format-value` call remains outside the new `try` at [repl.cljc:1355](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1355). It realizes sequences through `first` and subsequent traversal at [repl.cljc:639](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:639).

A concrete remaining path is a host primitive returning:

```clojure
(lazy-seq (throw (ex-info "late boom" forged-retention-data)))
```

The primitive returns normally; the VM accepts its result, and the output ring stores it without realization. Formatting later triggers the exception outside all three wrappers. See [semantic.cljc:201](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/vm/semantic.cljc:201) and [ringbuffer.cljc:124](/Users/sto/workspace/datomworld-cc3/src/cljc/dao/stream/ringbuffer.cljc:124).

Recovery consequently still merges fabricated handles and accepts fabricated failures at [repl.cljc:2112](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:2112). Pending recovery has the equivalent exposure at [2597](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:2597).

**Required fix:** protect default formatting as well as explicit rendering, or make recovery obtain retention evidence exclusively from trusted shell-owned state. Add a regression where a primitive returns normally but its result throws during formatting, with both healthy and genuinely failed retention.

F3: CONFIRMED-FIXED **for the explicit-hook regression cases**. The immediate and pending tests cover conflicting evidence, genuine failures, handle survival, Warning counts, and absence of a delayed Warning on the following input. [Tests:2430](/Users/sto/workspace/datomworld-cc3/test/yang/clojure_callsite_test.cljc:2430), [pending assertions:2564](/Users/sto/workspace/datomworld-cc3/test/yang/clojure_callsite_test.cljc:2564). The report records **24 failures → 0**; I did not independently replay that historical run. These tests do not cover F2’s fallback formatter.

F4: CONFIRMED-FIXED **for the actual PHP result shape**. PHP constructs an ordinary map containing `:php/out`, so that keyword renderer performs a normal lookup. [PHP result:5581](/Users/sto/workspace/datomworld-cc3/src/cljc/yang/php/antlr/prelude.cljc:5581). “Keyword lookup cannot throw” is not universally true: custom lookup implementations can throw. The new wrapper nevertheless covers an exception from invoking the keyword hook.

F5: CONFIRMED-FIXED — the new `assoc` overwrites only the three retention fields; unrelated exception data remains intact. No additional defect found in that transformation. [repl.cljc:1345](/Users/sto/workspace/datomworld-cc3/src/cljc/yin/repl.cljc:1345).

Static review only; no suites run or files edited. Cross-node acceptance remains conditional on Shiva.

CONFIRMATION VERDICT: REQUEST CHANGES