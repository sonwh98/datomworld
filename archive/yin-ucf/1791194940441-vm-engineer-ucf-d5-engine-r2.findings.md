Completed-GMT: 2026-10-05 11:12:00 GMT
Completed-Local: 2026-10-05 18:12:00 +0700
Coding-Agent: claude (sonnet-5-5)

# D5 engine round 2 — findings

## What changed

**`src/cljc/yin/vm/engine.cljc`**
- `handle-effect :stream/cursor`: under any gate mode it calls the new `handle-cursor-unminted`: no handle call, not blocked, installs `{:stream-id id :yin.k/unminted {:origin :dao.stream/oldest :seq n}}` (`n` from the id counter). Ungated unchanged.
- New `apply-mint [state cell-id position]`: replaces the cell with `(vm/cursor-entry stream-id position)`; refuses a non-unminted cell and refuses in `:exporting`/`:ended`.
- Close queue: under `:running`, `handle-close` appends `{:stream-id id :yin.k/issue n}` to `:yin.k/closes` and returns nil, no handle call. Ungated unchanged.
- New `apply-close [state stream-id issue]`: removes that record only; refuses in `:exporting`/`:ended`.
- `:yin.k/issued` machine-only counter; `:yin.k/issue n` stamps every gated parked `:put` entry and every close record.
- `check-wait-set`: under `:running` the ordinary sweep no longer polls `:put`/`:next` entries (kept in place, zero handle calls). Ungated sweeps unchanged.
- The poll-branch line reflowed to <= 80 columns (line 361 is over 80 but predates this work).

**`src/cljc/yin/vm/ffi.cljc`**: new `put-request [state call-in request]`: no gate calls `apply2/put-request!`; any gate answers `{:dao.stream/outcome :dao.stream/full}` with no handle call.

**Four kernels**: the one `apply2/put-request!` at each `:ffi-call` site is now `ffi/put-request`; nothing else at the site changed.

**`test/yin/vm/engine_gate_test.cljc`**: sweep rows, cursor rows 1-9, close rows, FFI rows on all four kernels, a `put-request` unit test.

## Outcomes (first round)

- Red: the first runs failed with 29 failures and 1 error: `ffi/put-request` missing (my first edit to `ffi.cljc` had not applied), the VM's own call pair counted by the counting handle (fixed by counting only capacity-4 streams), and the walker dropping the gate (see the walker round).
- Full yin.vm/yin.repl/dao.stream JVM run was green; the complete `clojure -M:test -e :slow` could not run here (`yang.python.antlr.gen.Python3Lexer` missing; `bb gen:python-antlr` not run in this worktree). CLJS and CLJD lanes not run.

## Concerns

- Stop-condition note: `cell-for!` (`engine.cljc` ~834) reads an unminted cell's `:cursor` for export; D8/D9 own the refusal for unminted cells and pending closes.
- Cursor creation under `:exporting`/`:ended` installs an unminted cell (literal reading); the ruling says D8's brief adds the explicit refusal.
- A gated sweep returns gated `:put`/`:next` entries after the other waiters, so wait-set order can change; accepted as recorded.
- No git writes; cljstyle is the orchestrator's.

## Walker round (appended after the ruling)

Changed: `src/cljc/yin/vm/ast_walker.cljc` (file list extended by the ruling) and `test/yin/vm/engine_gate_test.cljc`.

The blocker: `cesk-return` rebuilds `ASTWalkerVM` through the positional constructor, which drops every non-field key, so `:yin.k/gate`, `:yin.k/closes` and `:yin.k/issued` vanished at the first step.

**Deviation from the ruling's mechanism.** Record fields cannot be qualified keywords, so those three keys cannot be added as `ASTWalkerVM` fields; a field named `gate` would not answer `(:yin.k/gate vm)`. The closest minimal fix: `cesk-return` wraps its constructor call in a new `carry-custody` helper that copies those three keys from the old machine into the rebuilt record's extension map when present. The record definition, `create-vm` and all other walker logic are untouched. The keys stay absent on ungated machines, so ungated behaviour is unchanged. If a different mechanism is wanted, say so.

Tests:
- Gated cursor, close and FFI rows now run on all four kernels (`gated-kernels` removed).
- New `walker-step-preserves-the-custody-keys-test`: a gated walker steps once and gate, closes and issued survive on the rebuilt machine; an ungated step leaves no gate key.
- `clojure -M:test -n yin.vm.engine-gate-test`: 18 tests, 211 assertions, 0 failures, 0 errors.
- `clojure -M:test -r "yin\.vm.*|yin\.repl.*|dao\.stream.*" -e :slow`: 1875 tests, 17926 assertions, 0 failures, 0 errors.
- kondo on `ast_walker.cljc` and the test file: 0 errors, 1 existing warning (unused `ast-walker-run-active-continuation`).

The walker blocker is closed. CLJS/CLJD lanes not run; cljstyle not run.
