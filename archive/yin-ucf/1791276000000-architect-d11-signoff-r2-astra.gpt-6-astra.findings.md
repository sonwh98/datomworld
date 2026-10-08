Completed-GMT: 2026-10-06 11:17:27 GMT
Completed-Local: 2026-10-06 18:17:27 +0700

CHANGES

1. **Remove live cursor minting from the writer.** [writer.cljc:255](/Users/sto/workspace/datomworld-d11/src/cljc/yin/vm/ucf/holder/writer.cljc:255) calls `stream/cursor` with `:oldest` and immediately applies its result. The governing link-cursor ruling requires a **recorded `:newest` observation**, acknowledged before `apply-link-cursor`; D12 owns that operation. D11 must retain cursorless link requests without sending or observing. Test zero cursor calls and zero sends until the recorded cursor is installed.

2. **Fix bare link target selection and outcome handling.** At [writer.cljc:368](/Users/sto/workspace/datomworld-d11/src/cljc/yin/vm/ucf/holder/writer.cljc:368), bare append still looks up `(:stream-id entry')`, although link requests use `module/link-request-resource`. Moreover, [apply-effect:278](/Users/sto/workspace/datomworld-d11/src/cljc/yin/vm/ucf/holder/writer.cljc:278) calls `apply-link-sent` regardless of the append outcome. Select the actual link-request handle; transition only on successful append, retain on `full`, and handle terminal outcomes consistently with the link protocol. Pin these cases for bare writes and recorded admission results.

3. **Perform at-least-once closes before resolving them.** [writer.cljc:327](/Users/sto/workspace/datomworld-d11/src/cljc/yin/vm/ucf/holder/writer.cljc:327) removes the close record through `apply-close` without calling the stream’s `close!`. This silently discards the requested effect. Call `close!` before resolution and test that a subsequent write sees `closed`, including an install child.

4. **Refuse undeclared close targets.** The close branch’s `:else` treats a missing protection declaration as enrolled, emits an enrolled-close diagnostic, and removes the record. Return `:yin.k/unsatisfied` naming the stream, with the close retained and no stream effect. Add a regression alongside the three declared classes.

The root-path guard correctly addresses the reported empty-path/OOM defect, and the enrolled put/close ordering test exercises that repair. However, the new tests do not cover the incorrect live mint, bare-link outcomes, actual at-least-once closure, or undeclared close behavior. These remain contract defects despite the reported green suites. No files were edited and no suites were run.