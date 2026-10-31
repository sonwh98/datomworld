Completed-GMT: 2026-10-25 09:33:30 GMT
Completed-Local: 2026-10-25 16:33:30 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a16d38-d4fc-7253-8098-51e80bd692b9

**SIGNED OFF**

For committing Phase 4a within its documented materialized-source scope. Both r5 P1 findings are resolved. This is not final Datomic compatibility qualification or approval of Phase 4b.

Reviewed the working tree at HEAD `38d52cfc`. No files were edited.

**P1-1 — resolved.** [bind-fn-result](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:1407) now supplies an incremental producer; `emit-fn` charges each attempted binding, including rejection. Input expansion no longer enumerates inputs through `(count val)` before accounting. Folds stop before advancing after terminal or first-witness outcomes.

Independently reproduced:

- Original 10,000 rejected-candidate query: core and adapter exhaust at **100 steps**, phase `:join`.
- Infinite relation-result candidates: structured exhaustion at 100 steps.
- Infinite collection input: structured exhaustion at 100 steps.
- First-witness query with a lazy result that throws on its second element: completes without realizing that element.

**P1-2 — resolved.** [Source admission](/Users/sto/workspace/datomworld/src/cljc/dao/space/query/datomic.cljc:580) is an accounted loop; [realization](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:577) descends and applies views iteratively.

The original 10,000-view chain now:

- Exhausts structurally at **100 steps**, phase `:parse`.
- Completes with a 100,000-step allowance.
- Completes unbounded without stack overflow.

The focused suite also passed deep-chain published-terminal refusal and zero-read assertions.

**Syntax residual — accepted for Phase 4a.** The explicit `max-syntax-depth = 64` profile limit and iterative extent check cover native query syntax, pull patterns and `%` rules before recursive interpretation. My deep-native-query, deep-pull and deep-rule probes each returned `:unsupported/:syntax-depth-limit`.

This syntax limit is distinct from the rejected rule-expansion depth heuristic: recursive execution remains trampolined and governed by invocation bounds. E14 appropriately discloses the limits concerning opaque argument data, direct core callers with deep syntax, and the separate `rows` helper.

**Verification:** independently ran `execution-test` and `datomic-execution-test`: **64 tests, 449 assertions, zero failures/errors**, plus the probes above. I did not rerun the reported complete three-host or downstream gates; those remain supplied evidence.

The corrections preserve the single evaluator, explicit immutable progress, first-witness demand and fail-closed published-source boundary. No remaining blocker was found in this re-review.