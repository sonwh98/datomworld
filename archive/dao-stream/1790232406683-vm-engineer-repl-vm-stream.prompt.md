Created-GMT: 2026-09-24 06:46:46 GMT
Created-Local: 2026-09-24 13:46:46 +0700
Coding-Agent: claude
Session-ID: a8c92f1b-569d-48de-9e90-826c7ef6e9f9

# Task: Wire All 4 VMs to yin.repl via dao.stream Universal Public Boundary

Role: Yin.VM Runtime Engineer

Implementers:
- Status-Event: 2026-09-24 13:49:00 +0700 | Model: claude-fable-5-1 | Status: superseded | Rationale: Owner correction: fable reserved for architecture; reassign to implementation model
- Model: claude-opus-5.5 | Assigned: 2026-09-24 13:49:48 +0700 | Status: active | Rationale: Implementation per team.md

## Context and Invariant

The governing invariant for this task is:
1. `dao.stream` is the public interface to interact with all `yin.vm` VMs, as
   established in `docs/design/yin.vm.streams-all-the-way-down.md`.
2. `dao.stream` being the public interface does not mean it is the only
   interface -- programmatic in-memory execution protocols (`vm/IVM`,
   `vm/step`, `vm/run`) remain intact as substrates for low-level kernels
   and embedding.
3. `yin.repl` is a shell that calls into all VMs via `dao.stream`, and the VMs
   respond back via `dao.stream`. Currently, `yin.repl.core` uses `dao.stream`
   for program ingress and print side-effects, but directly inspects the VM
   record `(vm/value vm')` for the final evaluation return value.
4. All four `yin.vm` implementations must be runnable within `yin.repl`:
   - `:ast-walker` (`yin.vm.ast-walker`)
   - `:semantic` (`yin.vm.semantic`)
   - `:stack` (`yin.vm.debruijn.stack`)
   - `:register` (`yin.vm.debruijn.register`)

## What to Read First

1. `docs/design/datom.world.md` -- governing axioms and invariants.
2. `docs/design/yin.vm.streams-all-the-way-down.md` -- stream principles.
3. `src/cljc/yin/vm/docs/co-routines.md` -- continuation and stream mechanics.
4. `src/cljc/yin/repl/core.cljc` -- the local shell, session, and loader logic.
5. `src/cljc/yin/repl/driver.cljc` -- the single state owner and line handler.
6. `src/cljc/yin/vm/debruijn/stack.cljc` -- de Bruijn Stack VM kernel.
7. `src/cljc/yin/vm/debruijn/register.cljc` -- de Bruijn Register VM kernel.
8. `src/cljc/yin/vm/debruijn_code.cljc` -- Stack VM lowering from AST.
9. `src/cljc/yin/vm/debruijn_register_compile.cljc` -- Register VM lowering.
10. `test/yin/repl_test.cljc` -- existing REPL integration test suite.

## What to Produce

Modify `/Users/sto/workspace/worktree-yin-repl-stream`:
1. `src/cljc/yin/repl/core.cljc`:
   - Extend `vm-constructors` to include `:stack` and `:register`.
   - Update `vm-labels` and `help-text` to include `:stack` and `:register`.
   - Update `program-loaders`:
     * For `:stack`: lower expanded AST rows to nameless de Bruijn stack
       bytecode ($H$) via `yin.vm.debruijn-code` and load into
       `DebruijnStackVM`.
     * For `:register`: lower expanded AST rows to nameless de Bruijn register
       bytecode ($R$) via `yin.vm.debruijn-register-compile` and load into
       `DebruijnRegisterVM`.
   - Universal `dao.stream` response interface:
     * When evaluation halts/completes, ensure the result value is deposited
       onto the output/response stream (`{:type :repl/result :value ...}` or
       canonical response map) rather than extracted solely via out-of-band host
       record inspection.
     * `finalize-eval` drains the response from the stream to produce the
       printed result text and record `*1`, `*2`, `*3`.
2. `test/yin/repl_test.cljc`:
   - Add test coverage verifying that all four VMs (`:ast-walker`, `:semantic`,
     `:stack`, `:register`) can be switched to via `(vm <type>)`, evaluate
     expressions, and return results across `dao.stream`.
   - Verify that standard REPL commands, value history (`*1`, `*2`, `*3`), and
     error recovery operate cleanly across all four VMs.

## Acceptance Criteria

1. Universal `dao.stream` Boundary:
   - Evaluation requests enter through a stream; results and print output exit
     through a stream.
2. 4-VM Parity in `yin.repl`:
   - `(vm :ast-walker)`, `(vm :semantic)`, `(vm :stack)`, and `(vm :register)`
     are all fully functional and switchable at runtime.
   - Basic arithmetic, functions, let bindings, conditionals, and prints work
     identically across all 4 VMs in `yin.repl`.
3. Non-negotiable Code Rules:
   - Pure Markdown / pure Clojure (.cljc).
   - Lines strictly <= 80 columns.
   - 100% pure ASCII only (no Unicode quotes, em-dashes, or symbols).
   - Zero linter errors/warnings (`clj -M:kondo`).
   - Clean formatting under `cljstyle`.
   - Tri-host portability: all tests pass on JVM, Node/CLJS, and Dart.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
