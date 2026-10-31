Completed-GMT: 2026-10-05 21:17:55 GMT
Completed-Local: 2026-10-06 04:17:55 +0700

CHANGES

1. **Complete the required restoration regression.** [accepted-for!](/Users/sto/workspace/datomworld-d9/test/yin/vm/ucf/lift_v1_test.cljc:479) only calls inspector/`validate-body`; neither restores anything. Despite its docstring and the report, these rows do not exercise restoration. For both versions, lower the emitted bytes through `resume-task` into a fresh receiver with attachment support, supplying the version-1 address. Assert successful restoration, preserved cursor aliasing and distinctness, and the result closure’s restored module store and cursor references.

The source correction satisfies the ordering contract: result encoding now precedes module-store collection, installs, cells/profiles, segments and code finalization, and the body reuses the encoded result. The five rows cover the requested value shapes and repeatable bytes, but omit the explicitly required restoration check. The correction retains the version-0 codec and does not change the fixture pins; the report records successful fresh-process pin checks. Both earlier must-fixes remain closed. Node/Dart verification remains assigned to the landing run. No files were edited and no suites were run.