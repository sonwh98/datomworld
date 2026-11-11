Completed-GMT / Completed-Local: 2026-11-09 07:45:49 GMT / 2026-11-09 14:45:49 +0700

**APPROVE (merge-ready). P1-1 is closed.**

- [macro.cljc:708](/tmp/cc1-p1/src/cljc/yin/vm/macro.cljc:708): exact rows-container identity takes precedence, recovering the correct occurrence’s origin despite shared row objects.
- [macro.cljc:705](/tmp/cc1-p1/src/cljc/yin/vm/macro.cljc:705): first-match selection is eliminated. Row-level recovery requires exactly one distinct origin function; ambiguity returns nil. Content equality alone remains insufficient.
- Recovery remains connected through constructors, slot extraction, collection reassembly, and invocation return. Missing application provenance is explicitly refused, including within constructed children. No recoverable-origin regression found in the reviewed delta.
- [clojure_callsite_test.cljc:1096](/tmp/cc1-p1/test/yang/clojure_callsite_test.cljc:1096) pins the reported collision; earlier P1-1/P1-2 blocks remain.

Reviewed read-only against `fa70516f`; no tests run. Node/JVM results are orchestrator-reported. Ready for the codex re-gate.