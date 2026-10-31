Created-GMT: 2026-10-09
Coding-Agent: glm-5.3
Session-ID: 7e73e6ee-06c4-4002-9587-93ebdc6ac3a5
Task: Track A Phase C4 Slice P3 (`pysp` linked & safepoint hooks)
Role: Compiler Engineer
Worktree: /Users/sto/workspace/datomworld-p3 (branch yang-python-c4-p3)

# What landed

## 1. The `pysp` linked module (one source, two emitters)

`src/cljc/yang/python/antlr/safepoint.cljc` now holds both emitters over
one definition list (`function-definitions`), mirroring the P2 shape of
`prelude.cljc`:

- **Bundled** (unchanged topology, revised bodies): `uast` loads before
  the base prelude and allocates only a cell and a cursor
  (`state-definitions`).
- **Linked**: `module-uast` is one wide application — a `:py/uninit`
  placeholder per runtime key (`py.sp/handler`, `py.sp/cursor`), then
  every definition stripped of the module's own namespace `py.sp/` —
  and `module-spec` publishes it as `pysp` with `:requires {'py
  <manifest address>}`. A free name of the tree is declared one of
  three ways: qualified by `py` (covered by the requirement, discharged
  by equal manifest address), a primitive by its `vm/primitives`
  profile, or a host export (`cell/*`, `stream/cursor`, `stream/poll!`,
  `data/str-concat`) by profile address and effect set. Anything else
  is refused `:yang.python.antlr/undeclared-free` before publication.
  The load order is inverted per §8.5.6: linked `pysp` requires `py`.

## 2. The module-boundary bridge

A `pysp` closure's free reads resolve against `pysp`'s own module store
only (`active-store`), so the internal `py` keys `py.rt/ctx`,
`py.rt/limit` and `py.b/*` are unnameable from the hook module. Three
doors, all resolving under both profiles:

- **`py/rt-ctx` / `py/rt-limit`** (new base-prelude exports): return the
  dynamic-context and recursion-limit cells. Bundled, they are store
  keys; linked, they resolve through `py`'s bindings. The hooks call
  them per safepoint (`enter`, `return`, `recursion-limit`,
  `set-recursion-limit!`), so depth accounting reads and writes the very
  cell `py/run-module` resets and the generator machinery rebases.
- **`py.sp/class`**: a builtin class by name through `py/global-get`
  over an empty dict (the builtins fallback), replacing direct
  `py.b/KeyboardInterrupt` / `py.b/RecursionError` / `py.b/ValueError`
  reads in `deliver`, `enter` and `set-recursion-limit!`. Classes are
  fetched on the raise paths only, which are rare by construction.
- **`py.sp/attach!`**: the wrapper's call. A module closure cannot read
  the task's ambient signal stream and a cursor cannot be created at
  install time (a `:cell-ref` in an export slice refuses the lift), so
  the linked wrapper passes the stream reference; `attach!` allocates
  the handler cell and mints the cursor into `pysp`'s module store,
  idempotently (guarding on the `:py/uninit` placeholder, like
  `py/init!`).

## 3. Wiring the hooks with linked programs

- `linked-profile` is `{:loop 'pysp/loop, :call 'pysp/call, :return
  'pysp/return}` — the generic stage is unchanged; only the profile
  differs.
- `linked-program` builds the linked entry wrapper: `(require 'py)`,
  `(require 'pysp)`, `(py/init!)`, `(pysp/attach! py.sp/signals)`, then
  the derived linked program `A'`, whose own require and init are
  idempotent re-entries. The stream reference is read from the task's
  store under `signals-key` (`py.sp/signals`), bound by the composition
  — the one name a safepointed linked run needs beyond `py`'s exports.
- The lowering is untouched: the wrapper stays composition-side, so a
  linked program without safepoints requires no `pysp` and fails closed
  only when derived (the unresolved hook name).

## 4. Tests

- `test/yang/python/antlr/linked_safepoint_test.cljc` (new, cljc): the
  module-emitter shape/spec tests (fast), the stage tests over linked
  envelopes — marks `[:call :loop]` reach the side table, insertion is
  deterministic, the stage's input and output hold no prelude row and
  apply exactly the expected hook set — and the linked runs on the
  three vector VMs: fail-closed without `pysp`, interrupt (bare and
  caught `KeyboardInterrupt`), no-park, a registered Python handler,
  recursion-error at the default limit, escape-restores-depth and
  generator-depth under limit 100, and the pinned `pysp` manifest
  golden with its `:verifying` links.
- `safepoint-programs.cljc` gained the three signal programs
  (`while-true-pass`, `caught`, `def-and-while`), lifted from the
  bundled test so both suites share one fixture; the bundled
  `safepoint-test` now reads them from there, and the JVM parser-parity
  test (`e2e-test`) covers all three.
- `linked-harness` publishes `pysp` into `py`'s store once per process
  (`published-pysp`), serves both names (`safepoint-source`), registers
  the `stream` module (the hook module polls; `py` does not), and
  `run-linked` takes a `:prep` for handing the task its signal stream.

## 5. Golden moves (each moves once, per §8.5.6 "Addresses")

Adding `py/rt-ctx`/`py/rt-limit` to the single definition list and
revising the hook bodies moved:

- the `py` manifest address (linked_prelude_test golden),
- the bundled prelude root, the hook prelude root, and the float
  program's `A`, `A'` and derive-record addresses (float_address_test
  goldens),
- and pinned a new one: the `pysp` manifest address.

The linked `x = 1` program root does not move (prelude independence,
re-verified).

## Verification

- **JVM, affected namespaces** (`clojure -M:test -n` over
  `c3-gate`, `e2e`, `float-address`, `linked-prelude`,
  `linked-safepoint`, `prelude-parity`, `safepoint` and
  `yang.safepoint-test`): **147 tests, 1801 assertions, 0 errors** —
  one failure in the first round, the hand-built `def-and-while` packet
  grouping its DEDENT inside the stmt instead of the block (the lowering
  accepted both shapes; the JVM parser-parity test did not); two bracket
  moves fixed it and the parity test re-ran green in isolation (12
  assertions, all twelve packets parser-identical). The two safepoint
  namespaces re-ran green against the corrected packet.
- **Goldens**: `pysp` publishes to
  `:segment/blake3-6133313d60c4080c0803daa6de5b53b195fa6c650af4b4b9e354ce247950fa87`,
  its three vector formats link `:ok` under `:verifying`, and a second
  publication answers the same address; the moved `py` manifest, the
  prelude and hook roots, and the float program's `A`/`A'`/record
  addresses are pinned in their tests; the linked `x = 1` program root
  did not move.
- **Linter**: `clj -M:kondo` over every touched file: 0 errors,
  0 warnings. Project-wide the pre-existing findings are unchanged (all
  in `.cljd` files and `yin/repl/host.cljc`, none in this diff's files).

(Node and Dart lane results appended below.)

- **Dart, fast** (`bb src/dev/cljd_agg.clj --only` over
  `linked-safepoint-test`, `safepoint-test`, `linked-prelude-test`,
  `float-address-test`): **61 tests, all passed** (slow bodies SKIP'd by
  design), so the new cljc compiles and its fast tests run under
  ClojureDart. The slow Dart lane (`bb test:slow:cljd`) result is
  appended below.
- **Node, compile** (`clj -M:cljs -m shadow.cljs.devtools.cli compile
  test`): **522 files, 521 compiled, 1 warning — the pre-existing
  `yin.vm.linker.head/board` ns-var clash, not this diff's.** The Node
  **runtime** lane is blocked in this delegate session by the documented
  worktree limitation: no `node_modules` in the worktree, and `npm ci`,
  a `NODE_PATH` pointing at the main checkout's install, and a symlink
  are all permission-gated (autorun dies on
  `MODULE_NOT_FOUND: @noble/hashes`). The orchestrator's landing gate
  owns the Node runtime lane, as it did for P2.
