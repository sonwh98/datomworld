Completed-GMT: 2026-10-01 06:31:59 GMT
Completed-Local: 2026-10-01 13:31:59 +07
Coding-Agent: claude
Session-ID: 2947d033-73d2-4318-8c54-54eab162cb00

# Heap reclamation slice 1: deterministic mark-sweep over the task `:heap`, on all four VMs

The work is in `/Users/sto/workspace/datomworld-heap-gc` on branch `vm-heap-reclamation`. Nothing is staged or committed.

**Status:** all seven build items are done. Full JVM suite: 0 failures, 0 errors. `bb test:cljs`: 0 failures, 0 errors. Every acceptance part was mutation-proven (19 mutations, all caught).

**One check could not run:** `cljstyle check` needs a permission approval this session did not have. Formatting is unverified.

## Changed files

| File | Change |
|---|---|
| `src/cljc/yin/vm.cljc` | Adds `default-gc-threshold` (4096) and `fresh-gc`, which builds `{:since 0 :base b :threshold b :pinned #{}}`. `empty-state` takes a new `:gc-threshold` option (the composition parameter) and returns `:gc`. |
| `src/cljc/yin/vm/module.cljc` | `IModuleKernel` gains `gc-roots [vm]` and `gc-children [vm x]`. |
| `src/cljc/yin/vm/engine.cljc` | Adds the collector section: `heap-ref-id`, `trace`, `gc-roots`, `mark`, `sweep`, a public `collect`, `count-allocation` and a public `pin-refs`. The `:cell/new` arm now counts allocations and collects with the effect's `:val` as an extra root. The `:stream/put` arm pins refs. `check-ref!` refuses a cell ref whose id is absent from the heap with `:dead-or-forged-reference`. |
| `src/cljc/yin/vm/ast_walker.cljc` | `gc` is a declared record field, threaded through the positional `cesk-return`. `park-and-call` pins the request args. `create-vm` takes `:gc-threshold`, and `spawn-module` passes the parent's base. Adds the `gc-roots`/`gc-children` kernel methods. |
| `src/cljc/yin/vm/semantic.cljc` | Same pattern: record field, FFI op 21 pins args, `:gc-threshold`, spawn-module, kernel methods. |
| `src/cljc/yin/vm/debruijn/stack.cljc` | Same pattern; the constructor map sets `:gc (:gc base)`. |
| `src/cljc/yin/vm/debruijn/register.cljc` | Same pattern as the stack VM. |
| `src/cljc/yin/vm/docs/state.md` | New section on `:heap` and `:gc` (task heap and its collector). |
| `src/cljc/yin/vm/docs/ast.md` | New Part 6 §5, "Cells and the Task Heap", including reclamation. |
| `test/yin/vm/heap_reclamation_test.cljc` | New file: 16 deftests across the four VMs. |
| `test/yin/vm/cell_test.cljc` | `every-vm=` now runs every program at gc threshold 1 and at 1e9. The "unknown id" forged case now expects `:dead-or-forged-reference`, per owner decision 4. |
| `test/yin/vm/linker_require_test.cljc` | The test-only `FaultyTask` kernel delegates the two new protocol methods. |

## What was built, against the brief

1. **`:gc` on all four records.** It is a declared field everywhere. On the walker it is threaded positionally in `cesk-return`. Every constructor and spawn-module child gets a fresh `:gc`; the child keeps the parent's `:base`, since that is the composition's parameter.
   - The map carries `:base` beside `:since`, `:threshold` and `:pinned`, because the `max(base, 2 x live)` rule needs it.
2. **Engine.** Collection is a pure `collect : vm -> vm'` and is stop-the-world.
   - **Snapshot:** marking runs over the heap as it was at cycle start. The sweep removes only ids of that snapshot, so any id allocated at or after the counter start is spared.
   - **No id parsing:** "allocated before the cycle" is decided by snapshot membership rather than by parsing `:cell-N`. The two are equivalent because ids are monotonic.
   - **Worklist:** `trace` uses an explicit worklist, so deep `k` chains do not grow the host stack. It visits each value once (an equality-keyed seen-set), so shared environments are walked once and self-referential cells terminate.
   - **Budgeting later:** a budgeted marker can reuse the same `trace` step.
3. **Kernels.**

   | VM | `gc-roots` | `gc-children` |
   |---|---|---|
   | Walker | `control`, `env`, `k`, `value` | A frame continuation contributes only its frame's `:evaluated` and `:fn`, plus its non-frame keys; operand subtrees are skipped. A closure contributes everything except `:body` and `:params`. |
   | Semantic | `control`, `env`, `stack`, `k`, `value` | `nil`: nothing of this kernel's shape holds code, so every value is walked as plain data. |
   | Stack | `frames`, `stack`, `continuation`, `value` | A register payload (`:format` is the kernel's tag) contributes everything except `:segment` (code). |
   | Register | `frames`, `registers`, `continuation`, `value` | Same as the stack VM. |

   The engine never reads a continuation's keys itself. Any state that does not implement the protocol (a plain-map state) is walked generically, which can only keep more cells alive.
4. **Roots.** The engine adds `:store`, `:module-stores`, `:parked`, `:wait-set` and `:ready-queue` to the kernel's registers. It does not trace code, images, the registry, primitives, `:callable-effects`, telemetry or `:resources`.
5. **Pinning.**
   - At `:stream/put` dispatch, pinning happens before the append. A put that parks is therefore covered when the wait set later retries it.
   - At FFI park-and-call on all four VMs.
   - Pinned ids are mark roots, so their contents stay live too.
   - Pinning is skipped for scalar values and when the heap is empty.
6. **Ids and refusal.** Ids are never reused, because `:id-counter` is untouched. An absent heap id is refused with `:dead-or-forged-reference` and the message "Dead or forged cell reference". A wrong seal on a live id is still `:forged-resource-reference`.
7. **Docs.** No in-repo doc carried the "reclamation can wait" note; it exists only in the uncommitted collab cell ruling. Both new doc sections therefore state that they supersede it.

## Test outcomes

All runs were in the foreground.

- **Lint:** `clj -M:kondo --lint` on all 10 changed `.clj(c)` files gave 0 errors and 5 warnings plus 1 info. All of them are pre-existing and none is on a changed line: `vm.cljc:1281/1437/1445/1459`, `ast_walker.cljc:613`, and the info at `stack.cljc:52`. My first lint pass caught a duplicate `scalar?` I had added; I removed it and reused the existing one.
- **Formatting:** `cljstyle check` was blocked (see Status). I re-indented the one form I knew I had changed (`forged-refusal`) by hand.
- **Focused JVM:** `clj -M:test -n yin.vm.store-write-audit-test -n yin.vm.heap-reclamation-test -n yin.vm.cell-test` ran 34 tests and 484 assertions, with 0 failures and 0 errors.
- **Full JVM:** `clj -M:test` ran 2597 tests and 186968 assertions, with 0 failures and 0 errors. The log is `collab/heap-gc-jvm-full.log`.
  - I ran `bb build:yin-repl-node` first, as build-n-test.md requires.
  - The first full run had one failure: `store-write-audit-test` flagged `(conj (:store vm) …)` in `gc-roots` as a store mutation. That code only reads the store; I rewrote it as a vector literal and the audit passes. I made no change to the allowlist.
- **CLJS:** `bb test:cljs` ran 2511 tests and 53160 assertions, with 0 failures and 0 errors. Both "Testing yin.vm.cell-test" and "Testing yin.vm.heap-reclamation-test" appear in the output. The log is `collab/heap-gc-cljs.log`.
- **CLJD:** `bb test:cljd` was not run, as instructed.

## Acceptance tests (heap_reclamation_test, four VMs each)

- **Bounded heap:** `spin 200` at threshold 16 returns `:done`, `:id-counter >= 200`, and `(count :heap) <= 16 + 1`.
- **Roots:** a quiesced VM keeps nothing. A cell reachable only through each of the following survives:
  - the store;
  - a module store;
  - a wait-set entry's `:datom`;
  - a ready-queue entry;
  - a `:parked` entry, tested both synthetically and with a real `:vm/park` continuation;
  - the value register;
  - a closure's environment, using a real closure;
  - a nested cell inside another cell;
  - an evaluated operand during allocation at threshold 1: the walker frame's `:evaluated`, the semantic and stack operand stacks, and a register-VM live register. This is tested both through a call and with double-nested operands so that `:value` cannot mask the register root;
  - the allocating effect's `:val`, tested directly through `handle-effect`.
- **Swept refs:** a ref to a swept cell is refused with `{:reason :dead-or-forged-reference :effect :cell/get :kind :cell-ref :id …}`. The next allocation gets a fresh id, and the dead ref stays dead.
- **Pinning:** a ref put on an in-task stream, unreachable otherwise, survives collections at every allocation and reads back as authentic.
- **Determinism:** two runs of three programs give equal values, heap key sets and `:gc`.
- **Semantics:**
  - every `cell_test` program gives the same result at threshold 1 and at 1e9;
  - three cell-heavy programs give their expected values at thresholds 1, 2 and 1e9. They are `spin`, a 30-long linked list of cells summed (465), and a cell bumped 50 times with garbage allocated each iteration.
- **Construction:** every VM starts with an empty `:gc` with base 4096 and honours `:gc-threshold`. A spawned child gets an empty `:gc` that keeps the parent's base.

## Mutation proof

Method: one mutation at a time was applied to a file backed up first. The focused heap-reclamation and cell tests were then run, and the file was restored. The mutation script and backups were deleted afterwards, and `git diff` was grepped to confirm no mutation remnant is left.

| Mutation | Tests that failed |
|---|---|
| trigger disabled | bounded-heap, allocating-effect-val (12 failures) |
| no `:val` extra root | allocating-effect-val |
| no `:store` root | each-root, cell-inside-cell |
| no `:module-stores` root | each-root |
| no `:parked` root | each-root, parked-continuation |
| no `:wait-set` root | each-root |
| no `:ready-queue` root | each-root |
| `trace` does not follow cell contents | cell-inside-cell, determinism, cell-heavy |
| no pin at `:stream/put` | stream-pinning |
| `:dead-or-forged-reference` reason disabled | swept-ref, forged-cell-ref (unknown id) |
| walker skips `:evaluated` | evaluated-operand, cell_test distinct-cells |
| walker closure contributes nothing | closure-env, cell_test distinct-cells |
| semantic drops the `:stack` root | evaluated-operand, cell_test distinct-cells |
| stack VM drops the `:stack` root | evaluated-operand |
| register VM drops the `:registers` root | evaluated-operand (the double-nested case) |
| stack VM payload contributes nothing | parked-continuation |
| register VM payload contributes nothing | parked-continuation |
| nondeterministic trigger (`rand-int` added) | determinism, spawned-child (17 failures) |

The `:registers` mutation first went uncaught. The engine sets `:value` to each effect's result, so `:value` was also holding the ref; with both `:registers` and `:value` removed, the test failed. I added the double-nested operand case so `:value` has moved past the ref before the collection that matters, and that case catches the mutation.

## Unresolved concerns

1. **Guest-forged kernel shapes.**
   - A guest map that imitates a kernel shape is traced by that shape. Examples are `{:type :closure :body <ref>}` on the walker, a walker frame continuation with a ref inside `:frame`, or a de Bruijn map carrying the kernel's `:format` with a ref in `:segment`.
   - The hidden ref would be swept, and a later use is refused `:dead-or-forged-reference`. This fails closed rather than being unsafe, but it is an observable difference.
   - The walker already treats any `:type :closure` map as a closure when applying it. Closing this fully needs host-typed closures and frames, which is the D7 seam the design names.
2. **Host closures and lazy seqs are opaque.**
   - A ref captured inside a host fn (for example `partial`) or an unrealized lazy seq is invisible to `trace`. It would be swept, then refused when used.
   - No current primitive builds these over guest values, but the gap exists.
   - Separately, a realized but infinite seq would never terminate `trace`.
3. **Walker `control` is a root.** I followed the design's Q1 table, so each collection on the walker walks the program AST once (memoized per collection). Excluding it as code would save time, but the table lists it as a root.
4. **No tail calls in the de Bruijn compilers.** `dl/adapt` emits `[:call n false]` even in tail position. A recursive loop that binds a cell in its body therefore keeps every iteration's cell live through pending return frames. The register VM likewise saves live registers into return frames.
   - This is correct liveness, not a collector bug.
   - The bounded-heap test drops each cell in its own frame (`discard`) for this reason. The guest-side consequence is that a long recursive loop on these VMs grows both continuation and heap.
5. **Equality-keyed seen-set.** It hashes every traced collection. Hashes are cached on persistent collections, so the cost is one-time per value, but a walker closure's AST body is hashed the first time it is traced.
6. **CLJD unverified (not run, per brief).**
   - `trace` and `gc-roots` call `satisfies?` once per trace; I have not checked that ClojureDart supports it.
   - The only new reader conditionals are in the new test file, and each lists `:cljd` first.
7. **Not wired yet.**
   - Halt and park are not used as extra collection points; the design marks that optional.
   - Budgeted marking (the design's slice-2 form) is not built.
8. **Diagnostics are not traced.** Values held only in `:ffi-diagnostics` or `:link-diagnostics` are not roots, following the design's root list. A ref held only there would be swept.

## Incomplete work

- `cljstyle check` did not run because approval was needed; formatting is unverified.
- Untracked files in `collab/` (not to be committed): this report and the two test logs.
