Completed-GMT: 2026-09-30 04:50:38 GMT
Completed-Local: 2026-09-30 11:50:38 +07
Coding-Agent: claude
Session-ID: 771d73b8-33cb-4277-815e-2b72fe699c1c

# Report: $ast row relation, slice 1 (the observer and the $ast/$occ relations)

Worked on master 2f030c66. Nothing is staged or committed. Every check ran in the foreground.

## Files
- **New: `src/cljc/yin/repl/ast_index.cljc`.** A session-owned observer on `program-out`.
  - `make-indexer {:observer}` starts from `{:rows {} :occurrences #{} :programs 0 :lost? false :failure nil}`.
  - `:rows` maps each node address to its canonical row, so `$ast` holds one row per distinct address.
  - `:occurrences` is the set of `vm/occurrences` tuples, so `$occ` holds one tuple per distinct `[root path node]`.
  - `step` indexes every waiting packet. `skip` drains the reader without indexing. A gap in either one sets `:lost?`.
  - `available?` and `relations` return `{:ast [row ...] :occ #{...}}`, or nil when the indexer is lost or failed. Slice 2 can read these without changing them.
  - `status` returns `{:programs :rows :occurrences :lost? :gaps :failure}`.
  - Each packet is fully derived before anything is merged. Four problems set `:failure` and leave the relations unchanged:
    - a malformed packet (`:shape`)
    - two different rows under one address inside a packet (`:duplicate-address`)
    - a `vm/validate-rows` defect (`:invalid-rows`), which covers unknown tags, missing or unreachable rows, and so on
    - an address whose row differs from the one already held (`:address-conflict`)
  - A thrown exception during derivation is recorded as `:stage :derive`.
  - Once lost or failed, the indexer consumes later packets without indexing them. `(reset)` or `(vm ...)` rebuilds it with the session.
  - There is no dao.jing publication, and no origin or clock slot in either relation.
- **`src/cljc/yin/repl.cljc`:**
  - `make-session` adds `:ast-indexer`, attached with `((:attach program-out))`, so a reset or VM switch rebuilds it.
  - `consume-failed-round` skips its reader.
  - A new `run-ast-index-stage` runs right after `run-index-stage` in `eval-program`. That is after expansion and before evaluation, and only when a packet was forwarded.
  - `repl-state` reports `:ast-index`.
  - Two docstrings are updated.
  - `ingress-gaps` is unchanged: a gap on the AST indexer never stops evaluation. The two indexers share only the medium.
- **New: `test/yin/repl/ast_index_test.cljc`,** with 9 deftests.
- No existing tests were changed. `yin.vm` is unchanged; `vm/occurrences` and `vm/validate-rows` are reused as they are.

## Acceptance coverage (ast_index_test)
- **All four VMs:** rows and occurrences after evaluating `(+ 1 2)` match the packet's rows and `vm/occurrences`, and `:ast-index` status is identical across VMs.
- **Shared rows:** for `(+ 1 2)` then `(* (+ 1 2) 3)`, rows appearing in both packets are stored once, and each shared node has places under both roots at different paths.
  - Finding: the whole inner tree is not shared, because the top-level `(+ 1 2)` root has a different address from the nested application. The shared rows are the `+` variable and the literals.
- **Repeated evaluation:** evaluating the same input three times leaves the relations equal to those after one evaluation. `:programs` still counts 3.
- **Failed expansion:** adds nothing.
- **Raising and parking programs:** a raising program `(nope 1)` and a parking `(require (quote mod))` on the stack VM are both indexed.
- **Failed round:** a round that throws in the expander drains the AST reader without indexing, and a later round never indexes the skipped packet. `skip` still counts a gap.
- **Gap:** a gap makes the indexer lost with `:gaps 1` and `relations` nil. Evaluation and the code indexer continue (`"3"`, `"5"`). Later packets are not indexed, and `(reset)` recovers.
- **VM selection:** rebuilds the AST indexer.
- **Malformed packets:** at unit level, each failure reason makes the relations unavailable, and the packet before it stays indexed. At session level, a malformed packet shows up as `:ast-index :failure` while evaluation still answers `"3"`.
- **Cross-VM `repl-state` equality:** `yang.clojure.stream-eval-test` still passes.

## Mutation proof (each applied, test run, file restored; `git diff` confirmed the restore)
| Mutation | Result |
|---|---|
| drop the occurrence merge | 24 failures |
| occurrences stored as a vector (duplicates) | 13 failures, including repeat-evaluation |
| stage not run in `eval-program` | 42 failures |
| no skip in `consume-failed-round` | 2 failures (failed-round test) |
| gap does not set lost | 5 failures |
| `validate-rows` check removed | 6 failures |
| index after lost/failure | 8 failures |
| `relations` ignores availability | 8 failures |
| conflict check disabled | 2 failures |

## Verification
- **kondo** (`clj -M:kondo --lint` on the 3 files): 0 errors, 0 warnings.
- **cljstyle check: BLOCKED.** The session's permission settings required approval for the `cljstyle` command and no approval was available, so it was not run.
- **Focused JVM** (ast-index-test, repl-test, index-test, query-test, stream-eval-test): 90 tests, 973 assertions, 0 failures, 0 errors.
- **Full `clj -M:test`:** 2394 tests, 184621 assertions, 0 failures, 0 errors.
- **`bb test:cljs`:** 2299 tests, 51076 assertions, 0 failures, 0 errors. "Testing yin.repl.ast-index-test" appears in the output.
- **`bb test:cljd`:** "+2261: All tests passed!" `test/cljd-out/yin/repl/ast-index-test_test.dart` was freshly compiled during this run (11:45).

## Notes for slice 2
- **Loss is not in the round text.** It shows only in `repl-state :ast-index`; the round text carries no AST-index warning. Slice 2 is where AST queries get refused, using `ast-index/available?` or a nil from `relations`. If the owner wants a per-round warning like `yin.repl.index/notice`, it would be a small addition.
- **The failure is sticky until reset.** One bad packet makes the relations unavailable for the rest of the session, matching "never a partial snapshot."
- **Portability:** the code uses only `#?(:cljd Object :clj Exception :cljs js/Error)` catches, with no `#'` private access and no array-map.
