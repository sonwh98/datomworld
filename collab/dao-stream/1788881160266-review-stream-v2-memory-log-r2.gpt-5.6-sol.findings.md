I’m reapplying the review checklist to the corrected predicate and its fixtures. I’ll verify the exact key partition—contract key, foreign qualified extensions, transport-owned keys, and malformed keys—without rerunning any lane.

Completed-GMT: 2026-09-08 15:26:52 GMT  
Completed-Local: 2026-09-08 22:26:52 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Confirmation — creation-spec fix

**No remaining review findings. The P1 is closed.**

In [memory_log.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/stream/memory_log.cljc:41), `permitted-key?` now partitions keys correctly:

- `:dao.stream/type` is permitted.
- Foreign qualified keyword keys remain permitted and ignored.
- Keys in `dao.stream.memory-log` are rejected because this transport defines no options.
- Unqualified keywords and non-keyword keys are rejected as malformed.
- The removed helper leaves no residue.

This does not reject anything the contract permits. In particular, it preserves the open-map extension rule for every foreign qualified keyword.

The new assertions in [memory_log_test.cljc](/Users/sto/workspace/datomworld/test/dao/stream/memory_log_test.cljc:52) directly pin both corrected cases. The conformance `invalid-spec` fixture remains valid: `:dao.stream.memory-log/capacity` fails `permitted-key?`, so it still induces exactly `:dao.stream/invalid-spec`.

The implementer report’s value-variety overstatement is immaterial to correctness. The tests append integers, `nil`, and keywords—not strings—but `invalid-value` is structurally unreachable because `append!` applies no validation or encoding. A correction is optional only if that report is retained as an exact audit record.

**Static review verdict: approved and ready.** The only remaining commit gate is the owner’s stated requirement that the full lanes, including the still-running CLJD lane, finish successfully.
