Completed-GMT: 2026-11-10 17:54:07 UTC  
Completed-Local: 2026-11-11 00:54:07 +07:00

**Yes—approve `(mapv #(:kind (nth % 10)) events)`.**

[`intern-row`](/Users/sto/workspace/datomworld/src/cljc/yin/vm/macro.cljc:54) prepends the content address. Consequently, [`event-row`](/Users/sto/workspace/datomworld/src/cljc/yin/vm/macro.cljc:1301) places `output-root` at index 9, the **error map at index 10**, and `correspondence` at index 11. `:kind` is inside that error map. These are positional vectors; no named error accessor is defined.

The expected `[nil :arity nil]` and assertion strength remain unchanged. Also correct the adjacent stale message, `"the error slot is an event row's last"`, to `"the error map occupies event-row slot 10"`.

The actual target is [`test/yin/vm/macro_build_test.clj:144`](/Users/sto/workspace/datomworld/test/yin/vm/macro_build_test.clj:144).

Read-only verification completed; no files changed or tests run.