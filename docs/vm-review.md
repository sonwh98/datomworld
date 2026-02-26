# VM Review Notes

## Findings
- `resume-from-run-queue` is duplicated in `src/cljc/yin/vm/stack.cljc:680`, `src/cljc/yin/vm/register.cljc:759`, and `src/cljc/yin/vm/semantic.cljc:511`; each copy pops an entry, merges `:store-updates`, and restores VM-specific state, so a shared helper/API would keep queue semantics aligned across engines.
- Continuation parking/resuming repeats the same shape (`:park`/`:resume` handlers starting around `src/cljc/yin/vm/stack.cljc:628`, `src/cljc/yin/vm/register.cljc:687`, and `src/cljc/yin/vm/semantic.cljc:463`) with only the VM-specific fields changing; a helper that builds the parked entry and restores fields would reduce risk when extending the stored state.
- Stream-effect handling (`:stream/make`, `:stream/put`, `:stream/cursor`, `:stream/next`, `:stream/close`) is almost identical inside the apply/apply-effect branches of the three VMs (`src/cljc/yin/vm/stack.cljc:320`, `src/cljc/yin/vm/register.cljc:414`, `src/cljc/yin/vm/semantic.cljc:120`); factoring this dispatch into a shared helper would avoid the large parallel `case` statements.

## Recommendations
1. Add a shared `resume-from-run-queue` utility in `yin.vm.engine` that takes the queue entry and a VM-specific restorer to eliminate the duplicated scheduler logic.
2. Introduce shared helpers for parking/resuming continuations so each VM only supplies its own frame metadata instead of rebuilding the maps from scratch.
3. Centralize stream-effect handling into a single dispatcher that returns the updated state (wait/run queues and store) plus any continuation updates, and let each VM wrap the result with its own stack/register updates.

## Additional Opportunities
1. Implement `yin.vm.engine/handle-effect` to unify the `:stream/*` and `:vm/store-put` effect handling that currently repeats across `ast_walker.cljc`, `register.cljc`, and `stack.cljc`.
2. Build a generic AST-to-ASM transducer in `yin.vm.engine` so `ast-datoms->asm` can share traversal/label generation while each VM supplies its own emitters (stack vs register instruction shapes).
3. Factor opcode metadata and primitives into `src/cljc/yin/ops.cljc` so register/stack VMs and Dart/CLJ runtimes reference the same opcode/primitives definitions.
4. Generalize `resume-from-run-queue` into an engine template that accepts a VM-specific restore callback for registers, stack frames, or AST control nodes.
5. Port `yin.vm.engine`’s `run-loop` and `check-wait-set` scheduler primitives into `src/dart/yin_vm.dart` so the Dart VM can manage wait-sets, run-queues, and parking the same way as the Clojure implementations.
6. Follow the corrections in `docs/vm-todo.md`: have every VM use the `engine/resolve-var` helper (stack VM still uses a plain `get`) and optimize the stack opcode dispatch hotspots (`case` instead of `condp`, `subvec` instead of `take-last`/`drop-last`).

## Additional Opportunities
1. Implement `yin.vm.engine/handle-effect` to unify the `:stream/*` and `:vm/store-put` effect handling that currently repeats across `ast_walker.cljc`, `register.cljc`, and `stack.cljc`.
2. Build a generic AST-to-ASM transducer in `yin.vm.engine` so `ast-datoms->asm` can share traversal/label generation while each VM supplies its own emitters (stack vs register instruction shapes).
3. Factor opcode metadata and primitives into `src/cljc/yin/ops.cljc` so register/stack VMs and Dart/CLJ runtimes reference the same opcode/primitives definitions.
4. Generalize `resume-from-run-queue` into an engine template that accepts a VM-specific restore callback for registers, stack frames, or AST control nodes.
5. Port `yin.vm.engine`’s `run-loop` and `check-wait-set` scheduler primitives into `src/dart/yin_vm.dart` so the Dart VM can manage wait-sets, run-queues, and parking the same way as the Clojure implementations.
6. Follow the corrections in `docs/vm-todo.md`: have every VM use the `engine/resolve-var` helper (stack VM still uses a plain `get`) and optimize the stack opcode dispatch hotspots (`case` instead of `condp`, `subvec` instead of `take-last`/`drop-last`).
