Completed-GMT: 2026-09-25 15:56:19 GMT
Completed-Local: 2026-09-25 22:56:19 +07
Coding-Agent: claude
Session-ID: 078d0a96-daf0-4cef-b994-076601d800d6

# Report: Rule R, commit one (yin/def is syntax, never a name)

Role: VM Runtime Engineer. Worktree: /Users/sto/workspace/datomworld-ucf-rule-r
(branch ucf-rule-r, base 96657a4f). Nothing was staged or committed. All
changes are in the working tree: 60 tracked files changed, 1498 lines added
and 468 removed, plus 2 new test files.

## Lane counts

| Lane | Baseline (96657a4f) | Final |
|---|---|---|
| JVM `clojure -M:test` | 2027 tests, 180638 assertions, 0 fail, 0 error | 2045 tests, 180913 assertions, 0 fail, 0 error |
| Node `compile slice-peer test` | 1943 tests, 47718 assertions, 0 fail | 1960 tests, 47956 assertions, 0 fail |
| Dart `bb test:cljd` (fresh cljd-out) | 1905 passed | 1922 passed |

The lanes ran one at a time under mise. The Node output shows "Testing
yin.vm.rule-r-test", and the Dart output lists its tests. kondo on the
changed files reports 0 errors. Its 7 warnings all exist at base: I linted
the HEAD copies to confirm. `cljstyle check` passes on every changed file.
Every added or edited line in source and tests is ASCII and at most 80
columns. The docs have one exception, noted below.

Environment note: the Node baseline first failed with `MODULE_NOT_FOUND`
because the new worktree had no node_modules. I ran `npm ci` in the
worktree. node_modules is gitignored.

## What was implemented

Everything below follows the design's "Commit one" paragraph.

- **Resolver.** `engine/resolve-var` refuses a reserved name before it looks
  at env or store: `{:rule :reserved-name :role :variable}`. The reserved
  set is only `yin/def` (`vm/reserved-names`). `require` is unchanged and
  still an ordinary primitive.
- **Definition transition, all four engines.** No engine resolves the
  operator.
  - Walker: only the value operand is evaluated, under a pure-data
    continuation `{:type :eval-define :name k}`, then the store is written.
    The unused hot loop hands definitions to that same path.
  - Semantic VM: `[:define name]`, opcode 24, writes the value register.
  - Stack VM: `[:define name]` pops the value, writes it, and pushes it back
    as the expression's value.
  - Register VM: `[:define rd name rs]`.
- **Lowerers and lifts.**
  - The named datom lane and row lane emit the value, then `:define`.
  - The de Bruijn resolver, `lower-stack` and `lower-register` do the same.
  - The stack lift passes `:define` through. The register lift parses it.
  - UCF `transitions` gets `:define :step`.
  - The footprint table is keyed by the current contract and has a
    `:define #{}` row.
- **`yin/def` is removed** from `vm/primitives` and its profiles.
  `empty-state` refuses a registry or profile table that binds it.
- **`engine/store-put`** (with `check-store-key!`) refuses the reserved key.
  These write through it:
  - the `:vm/store-put` dispatcher;
  - the direct store instructions in the four engines, including walker
    `:vm/store-update`;
  - all four definition transitions.

  `resume-from-run-queue` refuses a store-update key that is not a keyword
  (`:store-update-key`). dao.await makes the same check.
- **Load-time validators.**
  - `validate-rows`: new last rule `:reserved-name`. It is occurrence-aware:
    a shared `[:variable yin/def]` row is judged at every parent slot and at
    the root.
  - `code/well-formed?`: batch rule 8, `:reserved-name`.
  - `code/well-formed-vector?`: vector rule 9, `:reserved-name`.
  - `debruijn-code/image-defect`: `reserved-defect`, run before the scope
    check.
  - Register validator: structural `reserved-rule`.
- **Loaders.** The walker datom loader now validates the whole tree
  (`vm/ast-reserved-defect`). The stack loader now validates
  (`image-defect`).
- **Transition-time refusal on raw walker control** covers: a reserved
  variable, a lambda binding it, a store-get, store-put or store-update key
  naming it, a malformed definition, and a raw `:eval-define` with the
  reserved key.
- **Contract stamps.** Defined in `yin.vm`: `ast-contract` "v3",
  `semantic-contract` "v3", `stack-contract` "b2", `register-contract`
  "r2", and `check-contract!`, which refuses with `:contract-missing` or
  `:contract-mismatch` before validation. The UCF `contract-stamp` and the
  ledger lowering profile use "v3".
  - `debruijn-code/lowering-contract-version` goes to 2, and
    `debruijn-register-code/contract-version` goes to 4. That moves every H,
    R and descriptor hash.
  - Loaders that now require a contract:
    - walker `vm-load-program [vm datoms contract]`;
    - walker `vm-load-rows [vm bc contract]`;
    - semantic `vm-load-program [vm datoms contract]`;
    - semantic `load-vector [vm v contract]` and `[vm v contract opts]`;
    - stack and register `load-image [vm segment contract]`;
    - stack and register `create-vm`, with `:contract` in opts, required
      when the segment is non-empty.
  - Fresh-code producers that pass the constant themselves:
    - `vm/eval`;
    - `linearize/ast-loader` and `rows-loader`;
    - the REPL program loaders (expander output);
    - the transformer runner (`bounded-row-evaluator`);
    - the cljs and cljd demos.

    The handoff demo ships its stamp inside its registers datom and never
    assigns one to a received batch.
- **Expander.** `make-ctx` refuses a seeded store that binds `yin/def` (a
  macro named `yin/def`). `expand-batch` refuses a ctx store that binds it,
  by throwing. `harvest` refuses it as a definition key.
- **Constructor checks.**
  - Walker and semantic refuse an `:env` that binds the name.
  - Stack and register refuse a `:free-env` or `:store` that binds it.
  - All four refuse a `:primitives` registry that binds it, through
    `empty-state`.
- **`free-names`** never returns the definition operator.
- **Docs.** I delegated the doc edits to a subagent, gave it the design and
  these facts, and reviewed the result. The docs are the ones the design
  lists:
  - UCF S7.3.3, S7.5.2, S7.6.1 and S7.11, plus a header revision entry (r4).
    The UCF has no separate revisions log.
  - linker.md 4.1, 4.2, section 5, 6.3, 6.4, 8.1 and section 11. The
    commit-two fetch requirement is only noted as landing with the M2
    format records.
  - yin.vm.semantic.md 2.4, 2.6 and 4.2.
  - code-as-tuples 4.5, 7.4, 7.5, 7.7.1 and 7.7.2, plus the "free at that
    site" clause.
  - yin.vm.macro.md 2.2, 4.1 and 4.2.
  - debruijn.stack.md: loader and instruction sections.
  - debruijn.register.md 4.4.
  - yin.vm.engine.md: new section 1.1.
  - yin-repl-design.md.
  - datom.world.md: one line naming the store-write audit.

## Files changed

- **Source (23 files).** All under src/cljc/ unless noted:
  - `yin/vm.cljc`
  - `yin/vm/`: `engine.cljc`, `ast_walker.cljc`, `semantic.cljc`,
    `code.cljc`, `ucf.cljc`, `linearize.cljc`, `debruijn_code.cljc`,
    `debruijn_resolve.cljc`, `debruijn_linearize.cljc`,
    `debruijn_register_code.cljc`, `debruijn_register_compile.cljc`,
    `macro.cljc`, `ledger.cljc`, `completion.cljc`
  - `yin/vm/debruijn/`: `stack.cljc`, `register.cljc`
  - `yin/repl.cljc`
  - `dao/await.cljc`
  - `datomworld/demo/continuation_handoff.cljc`
  - `src/cljs/datomworld/demo/`: `compilation_pipeline.cljs`,
    `continuation_stream.cljs`
  - `src/cljd/yin/register_bench_cljd.cljd`
- **Tests (27 files).**
  - New: `test/yin/vm/rule_r_test.cljc` and
    `test/yin/vm/store_write_audit_test.clj`.
  - Updated: 25 files, for three reasons:
    - loader call sites now pass stamps;
    - fixtures that relied on `yin/def` as a primitive now use `:define` or
      a `rec` primitive around a definition;
    - golden H, R and descriptor hashes and golden bytes changed. The bytes
      moved because `:define` joins the sorted mnemonic tag table.

    Other updates in those 25 files:
    - the R0 frozen register contract version is now 4;
    - the corpus gained definitions and the reserved literal;
    - `rows-loader` and `ast-loader` adapters now take 3 arguments;
    - the stack loader now refuses an undefined opcode at load time
      (`unknown-opcode-test`);
    - the completion test asserts that `yin/def` is not a primitive
      requirement.
- **Docs (10 files):** the ones listed above.

## Tests added

`rule_r_test.cljc` runs on all three hosts: 17 tests, 178 assertions on the
JVM.

1. The resolver refuses the name before env and store. `require` stays
   shadowable.
2. There is no `yin/def` primitive or profile.
3. All four backends write the same stores for five definition programs:
   - define then read;
   - redefine `x` twice, which reads the second value;
   - a definition's own value;
   - a stored closure called by name;
   - the reserved symbol as a literal value, which is data.
4. An env cannot redirect a definition.
5. A read parked inside a definition's value operand resumes in-process and
   writes the store, on the walker and on the semantic VM.
6. Each reserved role is refused on the walker datom loader, the walker row
   loader, `lower-ast` and the de Bruijn resolver. The roles are: variable,
   operand, wrong arity, non-literal key, definition key, binder,
   store-get key and store-put key.
7. A shared row is judged at every occurrence.
8. The semantic vector loader and datom loader refuse reserved operands,
   including a lowered image in the old call shape.
9. The stack loader (`create-vm` and `load-image`) refuses reserved
   operands, including the old call shape.
10. The register loader refuses reserved operands, including the old call
    shape.
11. Raw walker control is refused at transition time for each role,
    including store-get, store-put and store-update keys and a raw
    `:eval-define`.
12. `engine/store-put` and the `:vm/store-put` effect refuse the key.
13. A ready entry may carry only minted keyword keys.
14. Construction refuses an env, store or registry that binds the name, on
    all four VMs.
15. The expander refuses the name in `make-ctx` (a seeded macro), in an
    `expand-batch` ctx store, and as a harvested definition key. A
    transformer run succeeds under the current stamp.
16. On all six loaders, an unstamped load is refused (`:contract-missing`)
    and an old-stamped image with no definition is refused by stamp
    (`:contract-mismatch`). `vm/eval` succeeds.
17. `free-names` excludes the definition operator.

`store_write_audit_test.clj` is JVM-only because it reads source files. It
is the exact-allowlist audit described in the next section.

## Store-write allowlist (exact trimmed lines, by file)

- **engine.cljc**
  - `store-put`'s own `(assoc store key val)`.
  - The dispatcher's `:store (store-put (:store state)`.
  - Engine-minted keys:
    - the stream id in `handle-make`;
    - the cursor id in `handle-cursor`;
    - the cursor advance in `handle-next`;
    - the cursor advance in the waitset resolver `:advance`.
  - The ready-queue merge, which asserts keyword keys.
- **ast_walker.cljc**: the definition transition, and `:vm/store-put` and
  `:vm/store-update`. All go through `engine/store-put`.
- **semantic.cljc**: opcode 11 (`store-put`) and opcode 24 (`define`),
  through `engine/store-put`.
- **debruijn/stack.cljc**:
  - the constructor merge (checked);
  - `:store-put` and `:define`, through `engine/store-put`.
- **debruijn/register.cljc**:
  - the constructor merge (checked);
  - `:store-put` and `:define`, through `engine/store-put`.
- **dao/await.cljc**: the `store-updates` merge (keyword keys, checked).
- **yin/repl.cljc**: the history keys `*1`, `*2` and `*3`.
- **datomworld/demo/continuation_handoff.cljc**: the shipped `defs` merge
  (checked).
- **src/clj/yin/demo.clj**: a JVM demo moves one VM's whole store to
  another.
- **yin/vm/macro.cljc**: the expander's macro store. That is two lines in
  harvest, which refuses the key, and two in post-harvest, which installs
  only harvested names.

## Deviations and findings

1. **The design's store-write count is short.** The design lists eleven
   sites. It misses these engine-internal writes of engine-minted keys:
   - `handle-make`, which writes the stream id;
   - `handle-cursor`, which writes the cursor id;
   - `handle-next`, which advances the cursor;
   - the waitset resolver `:advance`.

   It also misses the JVM demo store transfer in `src/clj/yin/demo.clj`. I
   put all of these on the allowlist with their reasons. None of them can
   carry a program-chosen key.
2. **Loader contract shape.** The design says "take a required contract". I
   made it a required positional argument (`opts :contract` for the de
   Bruijn `create-vm`).

   The consequence: an observer composition whose medium carries fresh
   producer output has to pass the constant itself. `test_utils/run-session`
   does this, and so does one semantic observer test. This is my reading of
   "producers supply the current constant". A composition over an external
   medium has no stamp to pass unless the medium carries one. If the owner
   wants stamps to travel inside batches instead, that is a design change.
3. **Stack VM `:define`.** It pops the value and pushes it back as the
   expression's value, so the net stack effect is 0. This keeps a
   definition an expression, as the old `yin/def` call was, and it keeps
   the stack lift equal to the named vector (`value, [:define name]`).
4. **Contract names are new code.** "b1" and "r1" existed only in
   linker.md. Before this change the code carried only integer versions for
   stack and register. I added the string constants to `yin.vm` and bumped
   the integer versions (stack lowering 1 to 2, register 3 to 4) so that H
   and R move with the contract.
5. **Footprint.** `:define` contributes `#{}`, the same as `:store-put`. As
   a result, completion no longer reports `yin/def` or `:vm/store-put` for a
   definition, where the old primitive profile did. The completion test now
   asserts this. AST-side and segment-side requirements remain equal.
6. **Construction refusal is by throwing.** The expander's `expand-batch`
   throws on a ctx store that binds the name; it does not return error
   data. I treat that as a composition defect, not a batch failure.
7. **TDD order.** Most of the implementation came before its new tests,
   which I wrote once the change surface was mapped. I did not do strict
   red then green per feature. The new tests are load-bearing against the
   base: the APIs they call and the refusals they assert do not exist at
   96657a4f.
8. **Walker hot loop.** `ast-walker-run-active-continuation` is private and
   unused at base (kondo reports this before and after my change). I
   updated it anyway so it stays consistent.
9. **Findings the docs subagent reported** (not changed; for the owner):
   - The UCF header still says "Nothing below is implemented". Its line-4
     banner still says r3.
   - `yin-repl-design.md` still says the ast-walker is the only backend.
10. **One source edit after the final lanes.** I changed a docstring in
    `code.cljc` ("Rule 7" to "Rule 8") after the final lanes finished. I
    ran kondo and cljstyle on that file afterwards. The lanes did not rerun
    after this docstring-only change.

## Unrun checks

- The parity harness ran only as part of the JVM, Node and Dart suites
  (`parity_test`, `stack_parity_test`). I did not run a separate harness
  command, and I did not find one in build-n-test.md.
- New grid-table rows in semantic.md 2.4 and code-as-tuples 7.4, 7.5 and
  7.7.1 are wider than 80 columns. A grid table needs every row at full
  width. Every other added doc line passes the ASCII and 80-column check.
- The docs subagent could not run git, so its own width check was partial.
  I re-ran the ASCII and 80-column check over the full docs diff myself.
- Commit two (the M2 linker records, the fetch contract requirement, and
  deleting the round 3 and 4 guards) and the M4 bucket are out of scope and
  were not touched.

Status: COMPLETE
