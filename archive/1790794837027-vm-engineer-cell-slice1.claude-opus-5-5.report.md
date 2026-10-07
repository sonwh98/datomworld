Completed-GMT: 2026-09-30 19:23:12 GMT
Completed-Local: 2026-10-01 02:23:12 +07
Coding-Agent: claude
Session-ID: 82a33a9c-8ccb-4f4a-9aaa-40c4d57a1363

# Cell slice 1: report

Cell slice 1 is implemented and passes on all four VMs, on both CLJ and CLJS. Nothing is staged or committed, and I worked only in `datomworld-cell-slice1`.

## Changed files

| File | Change |
|---|---|
| `src/cljc/yin/vm/module.cljc` | Adds the `cell` host module: `new-cell`, `get-cell`, `set-cell!`, published as `cell/new`, `cell/get`, `cell/set!`, plus `cell-module`, `cell-profiles` and `register-cell-module`. Each export is `:effectful`, declares one kind (`#{:cell/new}`, `#{:cell/get}`, `#{:cell/set!}`), and returns a D4 effect minted by `make-effect`. Arities are `[1]`, `[1]` and `[2]`. |
| `src/cljc/yin/vm/engine.cljc` | `authentic-ref?` gets a `:cell-ref` branch that checks against `:heap`: `contains?` liveness, then the ref's seal is compared with the seal stored in the heap entry, so get and set compute no hash. `check-ref!` is reused unchanged. New private `heap-write`. Three `handle-effect` arms, `:cell/new`, `:cell/get` and `:cell/set!` (details below). The encoder gets a fail-closed `:cell-ref` arm: `:yin.k/non-portable` kind `:cell` (F3). Docstrings of `issue-ref` and the encoder are updated. |
| `src/cljc/yin/vm/completion.cljc` | `abstract-value` gets a fail-closed `:cell-ref` arm. It throws `"Value is not portable"` with `{:yin.k/status :yin.k/non-portable, :yin.k/kind :cell, :yin.k/hint id}`, the same shape the encoder uses, so a completion that reaches a cell never reports `:complete` (F3). |
| `src/cljc/yin/vm.cljc` | `empty-state` gets `:heap {}`. The semantic and AST-walker constructors, and every `spawn-module` child (all four call `create-vm`), inherit it. |
| `src/cljc/yin/vm/debruijn/stack.cljc`, `debruijn/register.cljc` | Their explicit constructor maps get `:heap (:heap base)`. |
| `src/cljc/yin/vm/ast_walker.cljc` | `heap` becomes a declared `ASTWalkerVM` record field and is threaded through `cesk-return`. **This was required, not cosmetic:** `cesk-return` rebuilds the record positionally, so an undeclared `:heap` key was silently dropped on the first step. The first test run showed it: every walker cell op after allocation was refused as forged. |
| `test/yin/vm/cell_test.cljc` (new) | 17 deftests, each over all four VMs, CLJ and CLJS. |

**D4 touch:** none. D4's code is used as-is (`make-effect`, `register-host-module`, `:callable-effects`, `check-callee-effect!`). The only engine function I extended is `authentic-ref?`, which predates D4.

### Semantics chosen

- **Allocation:** `cell/new` gets its id from `gensym state "cell"` (`:cell-N` from `:id-counter`), issues the ref with `issue-ref` (one SHA-256 per allocation), and stores `{:value v :seal s}`.
- **Read:** `cell/get` returns `(get-in heap [id :value])` as data and never re-dispatches it.
- **Write:** `cell/set!` writes the value, keeps the seal, and **returns the written value**.
- **Refusals:** a forged, foreign, unknown or wrong-type ref is refused by `check-ref!` with `{:reason :forged-resource-reference, :effect :cell/get|:cell/set!, :kind :cell-ref, :id id}`. This is the same qualified refusal the stream refs use.
- **Box semantics:** the heap is VM state. No continuation path captures or restores it, so it is shared across re-entries and never rolled back.

## Acceptance tests (`yin.vm.cell-test`, every one on all four VMs)

| Required | Test(s) |
|---|---|
| Counter shared by two closures | `counter-shared-by-two-closures-test` → `[2 2]` |
| Distinct cells per activation | `distinct-cells-per-activation-test` → `[2 1]` |
| Mutation survives multi-shot re-entry | `mutation-survives-multi-shot-re-entry-test`: the continuation is held in a second cell and re-entered three times → `[30 3]` |
| Mutation survives an abortive escape | `mutation-survives-an-abortive-escape-test` → `[:out :written]` |
| Forged ref refused | `forged-cell-ref-refused-test`, for both `cell/get` and `cell/set!`: wrong seal on a live id, another live cell's id, unknown id, nil seal, `:stream-ref` type tag. `foreign-task-ref-refused-test`: a ref minted under another secret whose id (`:cell-0`) is live in the receiver; I checked that the id collides on all four VMs. |
| Cell holding nil | `cell-holding-nil-test` → `[nil :later]` |
| Effect-shaped map returned as data | `cell-holding-an-effect-shaped-map-test`: the map comes back and the store gets no `k` |
| Lift/encode refused; completion not `:complete` | `lift-of-a-closure-over-a-cell-is-refused-test` (through each kernel's `lift-closure`), `lift-of-a-bare-cell-ref-is-refused-test`, `completion-over-a-cell-is-not-complete-test` (semantic VM, the only one `completion` serves) |
| `=` on refs is true iff same cell | `ref-equality-is-cell-identity-test` → `[true false]` |
| Ref as map key and inside nested values | `ref-as-map-key-and-nested-value-test` |
| Self-referential cell | `self-referential-cell-test` |
| Fresh `:heap` (brief item 3) | `every-vm-starts-with-an-empty-heap-test`, `spawned-module-child-starts-with-an-empty-heap-test` (calls each kernel's `spawn-module` on a parent that holds a cell) |

Also `set-answers-the-written-value-test`.

## Mutation proof

A temporary harness applied each mutation, ran only the targeted tests with `clj -M:test -v …`, and restored the original bytes. Afterwards `git diff` was byte-identical to a snapshot taken before the run, and the harness file and snapshot were deleted. Every targeted test failed under its mutation:

| Mutation | Targeted tests failing |
|---|---|
| M1 `cell/set!` does not write | counter, abortive escape, nil, self-ref: 4/4 (16 failures) |
| M2 every `cell/new` gets the same id | distinct activations, ref equality, ref as map key: 3/3 (12) |
| M3 walker continuation captures and restores `:heap` (continuation state) | multi-shot, abortive escape: 2/2 |
| M4 seal not compared | forged, foreign: 2/2 (28) |
| M5 liveness by `some?` value instead of `contains?` | nil: 1/1 (4) |
| M6 `cell/get` re-dispatches effect-shaped content | effect-shaped map: 1/1 (8) |
| M7 encoder `:cell-ref` arm removed | both lift tests: 2/2 (16) |
| M8 completion `:cell-ref` arm removed | completion: 1/1 |
| M9 no `:heap` in `empty-state` | both empty-heap tests: 2/2 (8) |

- **M1 was not run against the multi-shot test.** With writes disabled, that program loops forever (the counter never advances). The counter and abortive-escape tests prove writes instead.
- **M3 was applied to the AST walker only** as the representative rollback. The other three VMs have no heap-capturing code to revert; their box semantics follow from state threading, and the same tests pass on them.

## Verification (all run in the foreground)

- **`clj -M:kondo --lint`** on all 8 changed or new files, each passed as its own argument: 0 errors and 5 warnings. All 5 warnings are pre-existing: I linted the `HEAD` blobs of `vm.cljc` and `ast_walker.cljc` and got the same 4 + 1 (unused private vars and bindings in untouched regions, plus an info on `stack.cljc:52`). `cell_test.cljc` gives 0 warnings.
- **`cljstyle check`: blocked.** The command needs an approval this session could not grant, so it did not run, and there is no `cljstyle` alias in `deps.edn` or `bb.edn`. I matched the surrounding style by hand (comma-separated map entries, `defn` argument vectors on their own line). Please run `cljstyle check` on the 8 files.
- **Focused JVM, `clj -M:test -n yin.vm.cell-test`:** 17 tests, 123 assertions, 0 failures, 0 errors.
- **Full JVM, `clj -M:test`:** 2439 tests, 185168 assertions, 0 failures, 0 errors. `Testing yin.vm.cell-test` present.
- **`bb test:cljs`:** 2344 tests, 51605 assertions, 0 failures, 0 errors. `Testing yin.vm.cell-test` present.
- **`bb test:cljd`:** not run, as instructed.

## Unresolved concerns

1. **Completion refuses by throwing, not by a recorded refusal.** `completion` normally records `:refusals` and leaves `:yin.k/discovery` to reflect only `:missing`, so a recorded refusal alone would still report `:complete`. Throwing satisfies "no silent `:complete`" and matches the encoder's refusal shape. It is a different mechanism from completion's other refusals, though. If the Architect prefers a recorded refusal plus a discovery rule, both change together in slice 2 when the heap slice is pulled.
2. **The forged-cell error uses the existing unqualified `:reason :forged-resource-reference` shape** from `check-ref!`, as the brief says to reuse. D4's newer errors use `:yin.k/status`. Unifying the two is out of scope.
3. **The AST-walker record-field requirement is a trap for future VM state.** Any new state field must be declared on `ASTWalkerVM` and threaded through `cesk-return`, or the walker drops it silently. The other three VMs keep undeclared keys.
4. **Cells are never freed** (reclamation is out of scope, as ruled), and `:heap` grows for the life of a task.
5. **CLJD is unverified.** The three effect arms use only core functions already used elsewhere in `engine.cljc`, and the test namespace avoids var-quote and `ExceptionInfo`, but `bb test:cljd` was excluded by the brief.

## Incomplete work

- `cljstyle check` did not run (blocked; see above).
- Slice-1 item 5 of the cell ruling (the box-every-local rule in the frontend) was out of scope for this brief ("frontend lowering rules").
