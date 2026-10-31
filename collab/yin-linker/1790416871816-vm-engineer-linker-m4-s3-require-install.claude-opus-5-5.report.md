Completed-GMT: 2026-09-26 10:42:30 GMT
Completed-Local: 2026-09-26 17:42:30 +07
Created-GMT: 2026-09-26 10:01:11 GMT
Created-Local: 2026-09-26 17:01:11 +0700
Coding-Agent: claude
Session-ID: 59731830-4c7a-442c-ab9a-350c68c8a122

# Report: yin.vm.linker M4 slice S3, require lowering and the install child

Role: VM Runtime Engineer. Implementer: claude-opus-5-5.
Worktree: /Users/sto/workspace/datomworld-m4-s3 (branch m4-s3, base 9428c3d2).
Nothing is committed, staged, or stashed. linker.cljc, the UCF doc, and
yin.repl are untouched.

## Summary

Most of S3 is done and tested on all four backends. `(require 'foo)` now
lowers to the link pair. The engine polls both link wait states and
restores a response only when its id matches. The module installs as a
child task that the parent steps in its own round. At `linked`, the
child's exports and module stores are lifted to the portable encoding and
lowered into each receiving task. Module closures route their reads and
writes to their own module store on every kernel.

One sub-part is not done: the private `:resources` table for stream
handles, cursor cells, and the FFI pair, and the sealed references and
resource lowering that depend on it. Only the link pair lives in
`:resources` today. Finishing it means editing files outside this slice's
allowed set (see "What is left"). That is why the status is BLOCKED and
not COMPLETE.

## Lane counts

| Lane | Baseline (master 9428c3d2, recorded before any edit) | Final |
|------|------|------|
| JVM  | 2,138 tests / 181,890 assertions, 0 fail | 2,160 tests / 182,332 assertions, 0 fail |
| Node | 2,051 tests / 48,703 assertions, 0 fail | 2,073 tests / 49,080 assertions, 0 fail |
| Dart | not run (per instructions) | not run |

My baseline assertion counts differ from the prompt's by a few (181,890
against 181,898 on the JVM, 48,703 against 48,706 on Node). Both lanes
were green at baseline and are green at the end. The Node log shows
`Testing yin.vm.linker-require-test`.

- **clj-kondo** (all 12 touched files): 0 errors. The 5 warnings are all
  pre-existing; I checked this by linting the HEAD copies of `vm.cljc`
  and `ast_walker.cljc`, which give the same five.
- **cljstyle check**: clean on all touched files.
- **ASCII and 80 columns**: every added or edited line checked with a
  script, 0 violations. Two pre-existing comment lines in `semantic.cljc`
  that I edited had non-ASCII characters (an em dash, a left arrow); they
  are now ASCII.

## Files changed

- `src/cljc/yin/vm/engine.cljc`:
  - module-store helpers: `store-of-key`, `env-store-of`,
    `without-store-of`, `store-context`, `active-store`, `put-active`;
    `:vm/store-put` routes through them;
  - UCF 7.5.1 encoder and decoder, `lift-slice` (act 1), and
    `receive-module` (acts 3 and 4);
  - the link waits: `poll-link-response` (id correlation), `settle`
    (step 5b against the live state, then the install starts), skip
    diagnostics, `take-link-diagnostics`, and `abandon-link`;
  - the install child: `start-install` (loading), `advance-install`
    (running and parked), `link-install` (validated, act 2 origin check,
    linked), and `refuse-install` (refused);
  - `check-wait-set` polls the link entries and steps the install
    children before its stream sweep; a refused link or install is
    raised at resume.
- `src/cljc/yin/vm/module.cljc`:
  - the `IModuleKernel` protocol;
  - the `require-handler` state machine (hit, join an install, miss);
    `append-link-request`;
  - `module-entry`, `assoc-module`, `module-entries`, `link-module`;
  - `resolve-module` reads the task-local `:bindings` of a linked module.
- `src/cljc/yin/vm.cljc`: in `empty-state`, the registry Rule R check
  (follow-up a, with a private helper) and the new
  `:resources`/`:origin`/`:ancestry` keys; the role list in
  `reserved-name-defect`'s docstring gains `:registry`.
- The four kernels, each with store routing, a `:module/require`
  wait-entry builder, an `IModuleKernel` implementation, and the link
  options in `create-vm`:
  - `debruijn/stack.cljc` and `debruijn/register.cljc`: the `:store-of`
    register, and closures and frames that carry it.
  - `semantic.cljc`: the store context rides in the environment and a
    halt strips it.
  - `ast_walker.cljc`: the same as semantic, plus new record fields (its
    positional `cesk-return` drops keys that are not fields) and the
    `row-nodes` memo (follow-up b).
- `src/cljc/yin/vm/debruijn_register_effects.cljc`: when the code space
  holds more than one image, a register payload carries the offset table,
  and `continuation-defect` checks each image on its own. This fixes a
  latent S1 bug: on the register kernel, any entry parked after an
  `attach-image` was refused as `:continuation-segment`, because the
  single-image validator rejects a concatenation.
- Tests:
  - new `test/yin/vm/linker_require_test.cljc`;
  - edits to `attach_image_test.cljc`, `rule_r_test.cljc`, and
    `store_write_audit_test.clj` (allowlist: program writes now go
    through `engine/put-active`).

## Tests added

The new namespace `yin.vm.linker-require-test` has 19 deftests. Tests 1
to 17 below run on all four backends through a stub responder.

1. A require links, installs, and resumes with the export applied.
2. Two outstanding requires, answered in reverse order, each restore on
   their own `[origin counter]` id, and the other task's response is
   skipped as `:unknown`.
3. A response that lands before the first poll (a synchronous responder
   inside the append) is not skipped, and history from before the mint
   is never read.
4. A transitive require links while a third task runs to completion. The
   parent waits on `:install`, and the child is `:parked` on its own
   `:link-response`.
5. Two install children and the root mint distinct ids: the children's
   counters are equal and their origins differ.
6. A require cycle is `:require-cycle` with chain `[foo bar foo]`.
7. Step 5b: `:shadowed-free`, `:unresolved-free`, `:unresolved-free` for
   a same-named primitive of another profile, and an equal profile
   discharging.
8. A read in a body applied before its definition keeps its obligation.
   The obligations come from the real linker's `verify`.
9. Module stores: the closure reads the module's value, and a parent
   binding neither supplies nor shadows it; a write is visible to the
   next application in the same task; a `:store-put` inside an export
   writes the module store; another task keeps its own instance.
10. A module closure calling another module's closure writes each
    module's own store.
11. A dependency's store snapshot crosses with the child's mutations, and
    a task that already holds that store keeps its own instance.
12. Forged resource keys (`:stream-N`, `:cursor-N`, the FFI pair keys)
    read nothing from inside a module closure while the task holds a live
    stream.
13. A fabricated reference export fails the lift with
    `:yin.k/non-portable` and `:forged-resource-reference`.
14. Input after a tail-applied module closure defines into the task's own
    store, and no store context is left behind.
15. A `full` request stream retries the envelope verbatim.
16. Duplicate, late, and abandoned links. The abandoned link is raised to
    the program; its late response is skipped as `:late`, and a duplicate
    as `:duplicate`.
17. An install refuses `:export-missing`, and a loader defect refuses it
    at `:loading`.
18. `:binding-mismatch` for a marker of another discipline or format.
19. `:missing-module-store` refuses the lift (a unit test on the stack
    kernel).

Tests 18 and 19 (`:binding-mismatch` and `:missing-module-store`) are
unit-level and run once.

Added to existing files:

- `attach_image_test`: the walker's `row-nodes` are decoded once and
  held; a register entry parked after an attach restores, and a forged
  table row is refused.
- `rule_r_test`: construction refuses a module registry that binds
  `yin/def`, both on the `yin.def` path and as a slice key, on all four
  kernels.

## What is done

- **require-handler**:
  - A registry hit answers at once; a module already installing makes
    the requirer join its waiters.
  - A miss mints a `:dao.stream/newest` cursor on the response stream
    first, then builds the `:link-request` entry, then appends.
  - Link ids are `[origin counter]`. On `ok` the entry moves to
    `:link-response`; on `full` it stays in `:link-request` and is
    retried.
  - A VM with no link pair still throws as before.
- **Id correlation** in `check-wait-set`. Each entry polls its own kept
  cursor, skips non-matching ids with a diagnostic (`:duplicate`,
  `:late`, `:unknown`), and restores only on its own id. `abandon-link`
  raises the reason to the program and retires the id.
- **The `:installs` child** with the phases loading, running, parked,
  validated, linked, and refused. Children are stepped in the parent's
  ordinary round, and a refusal restores every waiter with the error.
- **`link-module`** (module.cljc), plus lift and lower with the
  `:yin.k/binding` and `:yin.k/store-of` markers on all four kernels.
- **Module stores**: `:module-stores`, active-store routing, frame
  threading, and a halt that clears the context, on all four kernels.
- **Cycle detection** over install ancestry (`:require-cycle`).
- **Follow-up (a)**: `empty-state` refuses a registry that binds a
  reserved name, with role `:registry`.
- **Follow-up (b)**: I memoized the walker's row decode safely, as data
  (`:row-nodes {id node}`) filled when rows are loaded or attached. There
  is no function or atom in VM state, so VM equality is unaffected.
  `row-node` is now a lookup, and the closure's body is the held node
  itself.

## What is left

1. **The private `:resources` table (r8).** Only the link pair lives
   there. Stream handles, cursor cells, and the FFI pair are still in
   `:store`. Moving them needs edits outside this slice's allowed files:
   - `yin.vm.ffi` (call-pair reads in `attach`, `bridge-step`, and
     `deliver-response`);
   - `yin.vm.completion` (UCF discovery pulls stream and cursor cells
     out of `:store`);
   - `dao.await` and the handoff demo.

   The module-store half of the forgery defence does work today: a
   module closure's `[:store-get :stream-0]` reads its own module store
   (test 12). Top-level task code can still read its own engine keys.
2. **Sealed references (r10).** Not done: the task capability secret,
   the seal on every issued reference, and checking it at effect
   dispatch (`:forged-resource-reference`). What exists is fail-closed:
   the lift refuses every stream or cursor reference as
   `:forged-resource-reference`, since none can be authenticated yet.
3. **Resource lowering (r9)**, and the test "reference-carrying exports
   lift and lower into `:resources`". Both depend on items 1 and 2.
4. **No test yet for `:foreign-image`.** The code path exists in
   `link-install`; the prompt's S3 list does not include it.
5. **The UCF round-trip of parked link and install entries** (criterion
   12). That is S5 and UCF work.

## Deviations

- **Tasks and the scheduler.** There is no multi-task scheduler in the
  tree, so a task is a VM value and a VM's own round (`check-wait-set`)
  is "the scheduler":
  - `:installs` lives in VM state.
  - An install record keeps the response, not `:waiters`; the waiters
    are the `:install` wait entries.
  - Child origins are hierarchical (`:t0.0`, `:t0.0.1`), so they are
    unique without a counter shared across nesting.
- **Registry entries.** A task's registry entry holds the portable
  `:slice`, `:stores`, and `:images` plus its own lowered `:bindings`,
  because one VM is one task. `:images` (the verified origin images)
  sits beside them so a later receiver, such as a child's module view,
  can attach them without re-fetching.
- **Link pair on the wait entry.** The entry names the link pair by
  resource id (`:yin.link/request`, `:yin.link/response`), not by
  `{:dao.stream/identity :dao.stream/descriptor}`.
- **Store context.** The named kernels carry `:store-of` in the
  environment under `:yin.k/store-of`; the positional kernels carry it
  in a `:store-of` register.
- **Closure markers** carry `:yin.k/format`, so a marker lowers only
  into a kernel of the same format (`:binding-mismatch` otherwise).
- **Step 5b.**
  - Profile equality is checked in the engine, because
    `linker/discharge` checks presence only.
  - A namespaced obligation of a module the manifest lists under
    `:yin.module/requires` is deferred to the child, and the receiving
    task receives the child's linked dependencies at `linked`.
  - A read before its definition surfaces as `:unresolved-free`, not
    `:undeclared-free`: manifest declaration checking is S4.
- **Scheduler guards and errors.**
  - The scheduler also refuses `:module-name-mismatch` as a defensive
    guard; the name check proper is S4's.
  - The cycle check runs at require time, before any link request is
    sent.
  - A `gap` or terminal outcome on the response stream is raised as the
    effect's error (`:reason :link-stream`).
- **Encoding.**
  - It is inline only, with no value table; UCF allows this.
  - A module store snapshot excludes the FFI pair keys, as UCF 7.6.2
    does. A stream handle in a module store refuses the install as a
    host object.
- **Not added: the `yin.repl` assertion.** The spec says the REPL's
  append asserts there is no active store context. Halts clear the
  context on every kernel instead (test 14), and yin.repl is outside
  the slice.

## Unrun checks

- The Dart lane (per instructions). Cross-host notes:
  - catches use `#?(:cljd Object :clj Throwable :cljs :default)`;
  - no `#'` private access in tests;
  - `reify` of `stream/IDaoStreamWriter` is used in tests;
  - the walker record gained fields and every field is threaded through
    `cesk-return`.
- No repeat run was needed: neither JVM run showed an intermittent
  single failure.

Status: BLOCKED - S3 is done except the private :resources table for streams, cursors and the FFI pair (r8) and the sealed references and resource lowering built on it (r9-r11); those need edits to yin.vm.ffi, yin.vm.completion and dao.await, outside this slice's allowed files
