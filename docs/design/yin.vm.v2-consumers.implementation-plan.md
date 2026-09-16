
# yin.vm.v2 consumers — the deletion plan

Status: implementation plan for deleting the experimental v1 VM models
(`yin.vm.{semantic, register, stack, space}`) and the macro engine
(`yin.vm.macro`), with every file that requires them either deleted or
migrated so the build stays green. Subordinate to
[`yin.vm.v2.divergence-register.md`](./yin.vm.v2.divergence-register.md),
[`dao.runtime.v2.implementation-plan.md`](./dao.runtime.v2.implementation-plan.md)
and [`dao.stream.md`](./dao.stream.md).

Revised 2026-09-10, three architect rounds: r1 against a consumer sweep of
the tree, r2 against an independent adversarial review of r1, r3 against a
confirmation review of r2. The first draft named two files to check for
stale references; the census now holds thirty-one. The revision history at
the end records what each round changed.

## The problem and context

The divergence register scopes `yin.vm.v2` to the ast-walker slice:
"`semantic`, `register`, `stack`, `space`, `macro` and `wasm` are not ported,
so nothing here speaks for them" (`yin.vm.v2.divergence-register.md:20-21`).
The v2 corpus is macro-free by construction (its change 2), and `yin.repl.v2`
ships one evaluator (its change 1). Porting the experimental models to
`dao.stream.v2` would be a rewrite of each for no consumer; they are deleted.

`dao.stream.md:806-808` records the decision: "The v1 VM lineage is deleted
rather than migrated, under `yin.vm.v2-consumers.implementation-plan.md`,
because a v2 twin already exists for every one of its consumers." This plan
is the first slice of that deletion — the models that have no v2 twin and no
place in the ast-walker scope. It is not the whole v1 lineage; see *Boundary*.

## What is verified and what is judged

Everything in the census below is a verified fact from a `grep`/`git` sweep
on 2026-09-10 unless marked **[J]** (judgment call). Facts a reviewer found
and a later round re-verified are marked **[R]**. Line numbers are as of the
sweep.

### The wasm entry is already gone

`src/cljc/yin/vm/wasm.cljc` and `test/yin/vm/wasm_test.cljc` were deleted in
commit `b8a6fce` ("docs(vm): remove wasm backend as it is out of scope",
2026-09-08, 854 lines). Neither was moved or renamed; no `wasm.clj*` exists
under `src/` or `test/`. The first draft of this plan listed both for
deletion; they are dropped from the delete list. Residue that still names the
backend: `src/cljc/yin/vm/docs/wasm-vm.md` (a whole document for a deleted
backend) and `docs/agy-test.md:59,104`. Build outputs under `.shadow-cljs/`
and `lib/cljd-out/` still carry `wasm` artifacts, but `lib/cljd-out` has zero
git-tracked files, so there is nothing to delete from the repository.

### Dependency order between the VMs and the macro engine

`yin.vm.macro` requires only `dao.datom` and `yin.vm` (`macro.cljc:12-13`).
The four VMs require it: `semantic.cljc:11`, `register.cljc:26`,
`stack.cljc:27`, `space.cljc:70`. `register`, `stack` and `space` also require
`semantic`. Nothing else under `src/` requires `macro`; `yang.clojure` emits
`:yin/macro-expand` nodes but does not require the engine.

So `macro` is a leaf *below* the VMs: deleting the VMs first leaves `macro`
compiling but orphaned, and deleting `macro` first breaks all four VMs. The
first draft's order (VMs, then macro) was dependency-correct for `src/` but
wrong for `test/`: `test/yin/vm/macro_test.cljc` requires `register`,
`semantic`, `space` and `stack` (`:10-13`) and `test/yang/macro_test.clj`
requires `semantic` (`:9`), so both macro tests break the moment the VMs go
and cannot wait for a "Phase 2". The two phases are collapsed into one
deletion set, landed as one change.

### What the sweep has to cover

r1's census was built from a namespace grep over `.clj*` sources and missed
three things the r2 review caught: a test that requires the models directly,
an explicit `:vm-type :semantic` argument in a `.cljd` host file, and a Dart
launcher under `bin/` plus an alias count in `deps.edn`. r3's review caught a
fourth kind: a *use* of a namespace alias outside the one code path the plan
named (`demo.cljs`'s toolbar, below). None of these is a namespace
`:require` in a `.clj*` file, which is all the r1 sweep looked for. The
Phase 0 sweep is therefore three greps over `src test bin deps.edn bb.edn
shadow-cljs.edn public`, and for every migrated file the census lists *every*
use of the alias, not the first one found.

## Consumer census

Every file that requires a deleted namespace, names a deleted model, or
launches deleted code, grouped by disposition. The brief's sweep listed three
live consumers; there are nine non-test, non-benchmark ones.

### Deleted with their dependency

| file | requires | why deletion, not migration |
|---|---|---|
| `src/cljc/yin/vm/semantic.cljc` | `macro` | the model itself |
| `src/cljc/yin/vm/register.cljc` | `macro`, `semantic` | the model itself |
| `src/cljc/yin/vm/stack.cljc` | `macro`, `semantic` | the model itself |
| `src/cljc/yin/vm/space.cljc` | `macro`, `semantic` | the model itself |
| `src/cljc/yin/vm/macro.cljc` | — | leaf; required only by the four above |
| `src/cljc/datomworld/demo/continuation_handoff.cljc` | `register`, `stack` | **[J]** no `src/` consumer at all — only its own test requires it. Its v2 twin `continuation_handoff_v2.cljc` is what `continuation_stream_v2.cljs:36` uses. Migration already happened; this is the residue |
| `src/clj/yin/demo.clj` | `register` | **[J]** twin `yin.demo-v2` (`demo_v2.clj:27-29`, on `yin.vm.v2`) exists; no `deps.edn` alias or doc runs `yin.demo`; the only run instruction is its own docstring |
| `src/cljs/datomworld/demo/compilation_pipeline.cljs` | `register`, `semantic`, `stack` | **[J]** twin `compilation_pipeline_v2.cljs` is wired into `datomworld.demo` beside it — but the twin lacks the Python and PHP frontends; see D3, which closes that gap before this file goes |
| `src/cljs/datomworld/demo/continuation_stream.cljs` | `register`, `stack` | **[J]** twin `continuation_stream_v2.cljs`, wired in; feature diff owed in Phase 0 |
| `src/cljs/datomworld/demo/equation_plotter.cljs` | `register` | **[J]** twin `equation_plotter_v2.cljs`, wired in; feature diff owed in Phase 0 |
| `src/cljd/yin/register_bench_cljd.cljd` | `register` | benchmark of a deleted model; no `bb.edn`/`deps.edn` entry compiles it |
| `bin/register_bench_cljd.dart` | imports `lib/cljd-out/yin/register-bench-cljd.dart`, the compiled output of the file above | **[R]** a Dart launcher for a deleted bench; goes with it. Its twin `bin/register_bench_cljd_v2.dart` (for `src/cljd/yin/register_bench_cljd_v2.cljd`) stays. `bin/yin_repl_main.dart` imports v1 `repl.dart` and stays under D1 |
| `src/clj/yin/vm/bytecode_bench.clj` | `space`, `register`, `semantic`, `stack`, `ast-walker` | **[J]** it exists to compare the bytecode models against the walker (`--register-only`, `--stack-only`, `--semantic-only`, `--cesk-space-only`); with one model left it compares nothing. Delete rather than trim to `--ast-walker-only`. **[R]** Its **five** `deps.edn` aliases go with it: `:bench`, `:profile`, `:profile-fast`, `:profile-cesk-space`, `:profile-ast-walker` (`deps.edn:17-30`). A v2 walker bench, if wanted, is a new file under the VM plan |

### Migrated to keep working without the deleted VMs

| file | change |
|---|---|
| `src/cljc/yin/repl.cljc` | drop the three requires (`:19-21`); `vm-constructors` (`:33-37`) and `vm-labels` (`:40-44`) shrink to `:ast-walker`; `create-state`'s default `vm-type :semantic` (`:183`) becomes `:ast-walker`; both `help-text` branches (`:65`, `:74`) read `(vm :ast-walker)`. Nothing else in the file names a model. See D1 for why migrate and not delete |
| `src/cljd/yin/repl/flutter.cljd` | **[R]** `start-server!` calls `(repl/create-state {:vm-type :semantic})` at `:60` — an explicit argument, untouched by the default change, and `make-vm` throws "Unknown Yin REPL VM type" the moment `:semantic` is gone. Both Flutter demos take this path (`dao_gui.cljd:321`, `solar_system.cljd:123`). **[J]** Change the call to `(repl/create-state {})` so the widget takes the REPL's default and never names a model again. See D1 for how this class of defect is caught |
| `src/cljs/datomworld/demo.cljs` | **[R]** the three v1 aliases are used in exactly these places, and nowhere else (verified by a full read and `grep -nE "\b(pipeline\|cont-demo\|plotter-demo)/"`): the requires (`:2`, `:4`, `:8`); the picker cards (`:32-45`, `:62-…`, ids `:pipeline`, `:continuation`, `:plotter`); `hash->demo` rows (`:77,79,81`); `demo->hash` rows (`:95,97,99`); the `case` branches in `root-shell` (`:230,232,234`); and **a toolbar block at `:277-288`** that renders only `(when (= selected-demo :pipeline))` and calls `pipeline/show-explainer-video!`, `pipeline/layout-controls` and `pipeline/app-state`. r2 missed the toolbar block. The `cont-demo` and `plotter-demo` aliases have no such block; their only uses are the `case` branches. **Disposition:** delete the requires, the cards, the `case` branches, the `demo->hash` rows and the toolbar block; in `hash->demo` **keep** `"#pipeline"`, `"#plotter"`, `"#continuation"` and point them at `:pipeline-v2`, `:plotter-v2`, `:continuation-v2` (D5). The toolbar block goes with v1 rather than moving to the twin — see D3 for why. The `-v2` ids stay as they are; renaming them is not this plan's |
| `src/cljs/datomworld/demo/compilation_pipeline_v2.cljs` | receives the Python and PHP frontends from v1 before v1 is deleted — see D3 |
| `test/yin/vm/test_utils.cljc` | `vm-factories` (`:14-18`) becomes `{:ast-walker ast-walker/create-vm}`; drop the four requires (`:5-8`). `run-all-vms`, `queue-vm`, `queue-ast` are unchanged |
| `test/yin/vm/runtime_regression_test.cljc` | **[R]** requires `register`, `semantic`, `stack` directly (`:8-10`); the body never uses those aliases — every deftest iterates `vtu/vm-factories` (`:17`, `:46`, `:84`, `:121`). Drop the three requires; nothing else changes, and the tests keep running over the walker alone until `dao.runtime.v2` R4 deletes the file |
| `test/yin/vm/telemetry_test.cljc` | `eval-emits-step-and-halt-snapshots-across-cljc-vms-test` (`:50-56`) iterates a four-model map; reduce it to `:ast-walker` and drop the three requires (`:7-9`). The other four deftests already use only the walker |
| `test/yin/repl_test.cljc` | `(vm :register)` at `:103` and `:142` becomes `(vm :ast-walker)` and the `"RegisterVM"` label assertion becomes `"ASTWalkerVM"`; the three `:semantic` default assertions at `:272`, `:296`, `:312` become `:ast-walker`. Add one deftest, see D1 |

### Deleted test suites

| file | why |
|---|---|
| `test/yin/vm/semantic_test.cljc`, `register_test.cljc`, `stack_test.cljc`, `space_test.cljc` | contracts of deleted models. The first draft named only `space_test` |
| `test/yin/vm/macro_test.cljc` | contract of the deleted engine; requires all four models |
| `test/yang/macro_test.clj` | 3 of 4 deftests go through `semantic` with a macro registry. **[J]** the one walker-only test, `test-nested-defn-ast-walker` (`:186-196`), exercises `yang.clojure/compile-program` on nested `defn` and does not need macros; move it and the `compile-program-and-run` helper (`:23-30`) into `test/yang/clojure_test.clj` rather than lose it |
| `test/yin/vm/parity_test.cljc` (v1) | 15 of 16 deftests compare the walker against the four models; the 16th, `stream-make-default-capacity-parity-test`, compares the walker's capacity to theirs. With one model there is no parity to assert. Delete whole. v2 parity is `test/yin/vm/v2/parity_test.cljc`, which requires v1 `ast-walker` only (`:16`) and is untouched |
| `test/yin/vm/stream_listen_test.cljc` | its `datom-vms` factory (`:16-20`) is `semantic`/`register`/`stack` only; the walker is not in it. Delete whole. Ingress-on-a-stream for the walker is covered by `stream_driver_test` (v1) and `dao.stream.v2.observer`'s tests |
| `test/datomworld/demo/continuation_handoff_test.cljc` | tests a deleted file |
| `test/datomworld/demo/vm_state_keys_test.cljs` | requires `continuation-stream` (v1), `register`, `stack` |

### Unchanged, named so the reader can check

`src/cljc/yin/vm/{ast_walker, engine, ffi, telemetry, stream_driver,
runtime_adapter}.cljc` and `src/cljc/yin/vm.cljc` do not require any deleted
namespace and stay (see *Boundary*). `public/chp/yin.chp:18` links to
`/demo.html#pipeline` and is left as written because D5 keeps that hash
working. `public/The_Performance_Paradox.mp4`, the video v1's explainer modal
embeds (`compilation_pipeline.cljs:1848-1849`), is a static asset and is not
touched.

## Decisions

### D1 — v1 `yin.repl` is migrated to `:ast-walker`, not deleted here [J]

The brief asked whether v1 `yin.repl` is "fully superseded by `yin.repl.v2`
and slated for its own deletion under a different plan". Verified against the
tree:

- `yin.repl.v2` is a twin, not an in-place migration
  (`dao.jing.remote.implementation-plan.md:90-92`;
  `yin.repl.v2.implementation-plan.md:17-22` says v1 `yin.repl` is
  "untouched, keep[s] running, and keep[s] their consumers ... until each
  consumer migrates under its own plan").
- v1 `yin.repl`'s deletion is owed to "the v1 deletion, per `dao.stream.md`"
  (`dao.jing.remote.implementation-plan.md:1037`). `dao.stream.md:803-812`
  lists what that deletion covers — the v1 transports and `dao.stream.rpc.*` —
  and names no date and no document. **No plan currently schedules v1
  `yin.repl`'s deletion.**
- v1 `yin.repl` still has live consumers with no v2 counterpart:
  `src/cljd/yin/repl/flutter.cljd:7` (the Flutter REPL-server widget, used by
  `src/cljd/datomworld/demo/dao_gui.cljd:7` and `solar_system.cljd:10`),
  `src/clj/yin/vm/telemetry_server/jvm.clj:5`,
  `src/cljs/yin/vm/telemetry_server/node.cljs:4`, `src/clj/yin/repl/runner.clj`,
  `bin/yin_repl_main.dart`, the `:yin-repl` shadow build (`shadow-cljs.edn:31`)
  and three `deps.edn` aliases (`:68`, `:74`, `:101`). `src/cljd/yin/repl/v2/`
  holds only `host.cljd`; there is no v2 Flutter widget.

Deleting v1 `yin.repl` here would drag the Flutter GUI demos and both
telemetry servers into a VM-deletion plan. Migrating it is five edits in
`repl.cljc`, one in `flutter.cljd`, and six lines of test. So: migrate. The
command surface becomes `(vm :ast-walker)`, the same shape
`yin.repl.v2.core:50-53` already has, and the default changes from
`:semantic` to `:ast-walker` — the same change the divergence register
records as v2's user-visible change 1.

**The explicit-argument defect and what catches it.** `flutter.cljd:60`
passes `:vm-type :semantic` explicitly, so changing the default alone would
leave both Flutter demos throwing at startup, and compilation would not
notice because `:semantic` is a keyword, not a symbol. The telemetry servers
(`jvm.clj:11`, `node.cljs:10`) pass only `:telemetry-stream` and take the
default, so they are safe; `flutter.cljd` is the only explicit site in the
tree. Three things catch this class of defect, and this plan uses all three:

1. **The Phase 0 keyword sweep** — a grep for the model keywords as literals,
   not as namespaces, over every host's source (`.clj`, `.cljc`, `.cljs`,
   `.cljd`). It is how r2 found `flutter.cljd:60`.
2. **A cljc test on every host** — add to `test/yin/repl_test.cljc` a
   `create-state-accepts-every-advertised-vm-type-test` that iterates the keys
   of `vm-constructors` and calls `create-state` with each, and asserts that
   `(create-state {:vm-type :semantic})` throws with `:supported [:ast-walker]`.
   It pins the contract the Flutter widget depends on; it cannot see the
   widget's own argument, which is why item 1 exists.
3. **A startup smoke on a Dart host** — `flutter.cljd` cannot be required by
   a test outside a Flutter runtime, so the only check that exercises the
   actual line is launching the app and reaching `start-server!`. **[R]** The
   path is: `lib/main.dart` exports `datomworld.demo.main` (`main.cljd:1`),
   whose `main` opens a picker; selecting the **"dao.gui Prototype"** entry
   (`main.cljd:40-49`) calls `dao-gui/start!` (`dao_gui.cljd:318-321`), which
   calls `flutter-repl/start-server!`. The "Solar System" entry
   (`main.cljd:82` → `solar_system.cljd:123`) is a second route to the same
   line. Phase 1's criteria spell out the commands. r2 cited
   `dao_gui.md:41` for the build command; that line names a
   `datomworld.main` namespace that does not exist, so the doc is corrected
   in Phase 1 too.

One consequence to state plainly: after this, `defmacro` and every macro call
stop working in the v1 REPL too, because v1's `ast-walker` has no
`macro-expand` branch (divergence register, change 2). Only `semantic` ever
evaluated macros. That is the intended outcome of the v2 scope decision, now
applied to v1.

### D2 — `continuation-handoff` and `yin.demo` are this plan's, not "the demo surfaces'" [J]

`dao.jing.remote.implementation-plan.md:1043-1044` and `dao.stream.md:806`
leave "the demo surfaces" to their own plans. Both are read as: the surfaces
migrate — get a v2 twin — under their own plan. For these two files the twin
exists and is wired in (`continuation_handoff_v2.cljc`, `demo_v2.clj`), so
the migration is done and only the v1 file is left. A file with no consumer
that requires a file this plan deletes has exactly one plan that can delete
it: this one. `dao.runtime.v2.implementation-plan.md:219-223` names both as
v1-VM consumers gating R4, which this plan therefore clears.

### D3 — the three cljs browser demos are deleted here; the pipeline twin gets its frontends back first [J]

Same reasoning as D2, with one difference: they *are* live surfaces, reachable
from the `:demo` browser build (`shadow-cljs.edn:9-16`) through
`datomworld.demo`'s picker. Their `-v2` twins sit beside them in the same
picker (`demo.cljs:46-58`, `:230-236`). Leaving the v1 three in place is not
an option — the `:demo` build fails to compile without `yin.vm.register` — and
no other document plans their removal.

**A known feature gap, not a hypothetical [R].** v1 `compilation_pipeline.cljs`
offers Clojure, Python and PHP input (CodeMirror modes at `:2-3`, compilers
`yang.python`/`yang.php` at `:19-20`, dispatch at `:862-863`, eight
Python/PHP examples at `:894-917`, the selector at `:1552-1553`), while
`compilation_pipeline_v2.cljs` compiles Clojure only (`:15`, `:40`). Deleting
v1 without more would silently remove Python and PHP from the public demo
that `yin.chp` links to as "Try the Live Demo". That is a product regression,
and nobody has signed off on one.

**Disposition [J]:** the plan does not regress. Before v1 is deleted, port the
language selector, the two compile branches, and the Python/PHP example
snippets into `compilation_pipeline_v2.cljs`. The cost is small and the
pieces are all in place: `yang.python/compile` and `yang.php/compile` are
`.cljc` (`src/cljc/yang/`) and each returns a `:type`-keyed AST map
(`python.cljc:402-404`, `php.cljc:519-521`) of the same shape
`yang.clojure/compile-program` returns, which `vm/ast->datoms` then turns
into datoms — the twin already does exactly that at `:129-130`, so its
Source→AST step gains a `case` on language and nothing downstream changes.
The CodeMirror language packages are already dependencies. What is *not*
ported is the rest of v1's 1856-line UI; the twin keeps its own shape.
If the owner would rather drop Python and PHP from the demo, that is their
explicit decision to record in this section, with the `yin.chp` copy checked
for claims it would break — the plan's default is the port, so no sign-off is
needed to proceed as written.

**The toolbar block goes with v1, not to the twin [J].** `demo.cljs:277-288`
is v1-only chrome: an "Explainer Video" button that flips
`:show-explainer-video?` in v1's own `app-state` (`compilation_pipeline.cljs:349-351`,
modal at `:1816-1849`), and a "Layout" selector that drives v1's
`relayout-ui!` pane-ratio system (`:1018-…`). The twin has neither a modal
nor layout modes (`grep -n "explainer\|layout" compilation_pipeline_v2.cljs`
is empty), so there is nothing in it for these controls to control; porting
them means porting v1's layout machinery, which the paragraph above
excludes. Both functions have no other caller in `src/` or `test/`. The video
file stays served from `public/`; whether the twin should link to it is a
demo-content question for its owner, not part of this deletion.

The other two pairs (`equation_plotter`, `continuation_stream`) still get the
Phase 0 diff. Neither mentions Python or PHP, and their line counts are within
a few percent of their twins', but "similar size" is not "same features", so
the check stands.

### D4 — `bytecode_bench` is deleted, not trimmed [J]

Recorded in the census table. The alternative — keep an `--ast-walker-only`
bench named "bytecode" with no bytecode model in it — is the kind of leftover
the repo's minimal-diff preference argues against, and the dao.runtime plan
already lists the bench as a v1 consumer to clear. The five aliases go with
it; `docs/cesk-space-optimization.md` (`:20`, `:158-173`, `:206`, `:218`) and
`docs/design/yin.vm.streams-all-the-way-down.md:401` reference `clj -M:bench`
and the profile aliases and are covered by Phase 2's status notes.

### D5 — the v1 demo hashes stay as aliases to the v2 entries [J]

**[R]** `public/chp/yin.chp:18` advertises `/demo.html#pipeline` as "Try the
Live Demo". Removing the `#pipeline` route would turn a public link into the
picker's home page. No other document under `public/`, `docs/`, `README.md`
or `src/` links to a v1 demo hash.

**Disposition [J]:** keep the three v1 hashes in `hash->demo` and point them
at the v2 ids (`"#pipeline"` → `:pipeline-v2`, and likewise for `#plotter` and
`#continuation`); drop the v1 rows from `demo->hash`. `yin.chp` is not
edited: an external link that keeps working is better than an edited link,
and any bookmark or third-party link to `#pipeline` keeps working too.

**What the user actually sees [R].** On arrival, `sync-demo-from-hash!`
(`demo.cljs:113-117`) reads `location.hash`, maps it through `hash->demo`,
and sets the selected demo; nothing rewrites the hash. So a visitor who opens
`/demo.html#pipeline` gets the v2 pipeline rendered **with `#pipeline` still
in the address bar**. The hash changes to `#pipeline-v2` only if they
navigate through the picker or the toolbar, because `select-demo!`
(`:130-137`) is the one place that writes `location.hash`, via `demo->hash`.
r2 said the address bar would show the v2 hash on arrival; it does not, and
this plan does not add a canonicalising rewrite — an alias that renders the
right thing is the whole requirement.

### D6 — one deletion set, one change

Per the dependency analysis above. A single commit deletes the five `src`
files, the ten test files, the nine consumer files, and applies the eight
migrations; the build is green after it. Splitting into "VMs then macro"
would require the two macro tests to move to the first commit anyway,
leaving the second commit as one orphaned file, which is not worth a phase.
An atomic commit exposes no partially-edited state.

## Phases

### Phase 0 — pre-checks, no edits

1. Run the three sweeps and confirm the census is still exact. Every hit must
   be a row in the census or a documented non-hit (e.g. `dao.gui.compiler`'s
   layout keyword `:stack`, which is unrelated). A hit that is not is a new
   consumer and gets a row before anything is deleted.
   ```
   # namespaces, every host and config
   grep -rnE "yin\.vm\.(space|semantic|stack|register|macro|wasm)\b" \
     src test bin deps.edn bb.edn shadow-cljs.edn public
   # model keywords as literals (catches explicit :vm-type arguments)
   grep -rnE ":(semantic|register|stack|space)\b" \
     --include='*.clj' --include='*.cljc' --include='*.cljs' --include='*.cljd' src test
   # launchers and aliases for deleted entry points
   grep -rnE "bytecode-bench|register-bench-cljd\b|register_bench_cljd\b|yin\.demo\b" \
     deps.edn bb.edn shadow-cljs.edn bin
   ```
   For each file in the *Migrated* table, also grep for every use of the
   alias it binds to a deleted namespace (`grep -nE "\b<alias>/"`), and
   confirm the census row lists every hit. This is the check r2 skipped for
   `demo.cljs`.
2. Diff `equation_plotter.cljs` and `continuation_stream.cljs` against their
   `-v2` twins for user-visible features (D3). Record the result in the
   commit message. Any gap is closed in the twin first. The pipeline gap is
   already known and is a Phase 1 work item, not a check.
3. Confirm `test/yang/clojure_test.clj` is the right home for
   `test-nested-defn-ast-walker` (it tests `yang.clojure/compile-program`).

### Phase 1 — the deletion set

Port first (so the browser demo never loses a feature between commits, even
though this lands as one commit): the Python/PHP frontends into
`compilation_pipeline_v2.cljs` (D3).

Delete:

- `src/cljc/yin/vm/{semantic, register, stack, space, macro}.cljc`
- `src/cljc/datomworld/demo/continuation_handoff.cljc`
- `src/clj/yin/demo.clj`
- `src/cljs/datomworld/demo/{compilation_pipeline, continuation_stream, equation_plotter}.cljs`
- `src/cljd/yin/register_bench_cljd.cljd` and `bin/register_bench_cljd.dart`
- `src/clj/yin/vm/bytecode_bench.clj` and its five `deps.edn` aliases
  (`:bench`, `:profile`, `:profile-fast`, `:profile-cesk-space`,
  `:profile-ast-walker`; `deps.edn:17-30`)
- `test/yin/vm/{semantic, register, stack, space, macro, parity, stream_listen}_test.cljc`
- `test/yang/macro_test.clj` (after moving its walker test)
- `test/datomworld/demo/continuation_handoff_test.cljc`
- `test/datomworld/demo/vm_state_keys_test.cljs`
- `src/cljc/yin/vm/docs/wasm-vm.md`

Migrate, exactly as the census table specifies: `src/cljc/yin/repl.cljc`,
`src/cljd/yin/repl/flutter.cljd`, `src/cljs/datomworld/demo.cljs` (including
the `:277-288` toolbar block), `test/yin/vm/test_utils.cljc`,
`test/yin/vm/runtime_regression_test.cljc`, `test/yin/vm/telemetry_test.cljc`,
`test/yin/repl_test.cljc` (five edits plus the new deftest from D1),
`test/yang/clojure_test.clj` (receives one deftest).

Also in this change, because they describe the code being changed:

- `src/cljc/yin/repl/v2/core.cljc:50-52` docstring and
  `src/cljc/yin/vm/docs/yin.repl.v2.md:55-58` say the remaining evaluators
  "follow in the VM plan". They do not; rewrite to say they were deleted under
  this plan and `:ast-walker` is the only evaluator.
- `src/cljc/yin/vm/docs/yin.repl.md:81` (v1 REPL command table) and
  `docs/design/yin-repl-design.md` (`:10`, `:60`, `:76`, `:121`, `:150-155`,
  `:208`, `:249`, `:357-358`): the backend list and the `:semantic` default.
- **[J]** `src/cljd/datomworld/demo/dao_gui.md:41`: the compile command names
  `datomworld.main`, which does not exist; the entry namespace is
  `datomworld.demo.main`. A one-word fix, made here because this plan's
  verification leans on that document.

**Criteria:**

- `clj -M:test`, the shadow `:test` node build, and `clojure -M:cljd test`
  all pass; the `:demo` browser build compiles.
- `clj -M:clj-yin-repl` starts; `(vm :ast-walker)` and `(reset)` work;
  `(vm :register)` reports the error naming `[:ast-walker]` as supported.
- `clj -M -m yin.demo-v2` still prints 5050.
- **Flutter startup smoke (D1 item 3) [R]:** compile the real entry point,
  then run the app and take the picker route that reaches `start-server!`:
  ```
  mise exec -- clj -M:cljd compile dao.stream.ws yin.repl datomworld.demo.dao-gui datomworld.demo.main
  flutter run
  ```
  In the running app, select **"dao.gui Prototype"** from the picker
  (`main.cljd:40-49`); this calls `dao-gui/start!` → `start-server!`. The
  server-status notifier must reach "listening"
  (`repl/connection-status-text` with zero clients) rather than the app
  raising "Unknown Yin REPL VM type". Opening the app alone does not exercise
  the line; the selection step is the test.
- `/demo.html#pipeline` renders the v2 pipeline with `#pipeline` still in the
  address bar (D5); in it, a Python and a PHP example compile and run (D3).
  The toolbar over the v2 pipeline shows only "Datom.world" and "Back to
  Demos" — no "Explainer Video" button, no "Layout" selector.
- All three Phase 0 sweeps return only hits under `docs/` and `collab/`, plus
  the documented non-hits; the per-alias greps for the migrated files return
  nothing.

### Phase 2 — prose that names what was deleted

Historical documents get a one-line status note at the top, not a rewrite:

- `docs/cesk-space-optimization.md` (already "superseded"; add that
  `yin.vm.space` and the `:bench`/`:profile-*` aliases it cites are deleted)
- `docs/design/yin.vm.streams-all-the-way-down.md:163,401` (names
  `yin.vm.space` as "closest to this shape" and cites `clj -M:bench`)
- `docs/cross-language-macro.md:46` (the design depends on
  `yin.vm.macro/expand-all`, which no longer exists)
- `docs/design/yin.vm-portability.md:217`
- `src/cljc/yin/vm/docs/yin-defmacro.md` and `bytecode-vm-optimization.md`
- `docs/agy-test.md:59,104` (proposes fuzzing a deleted wasm backend)

Living documents get their facts corrected:

- `docs/design/yin.vm.v2.divergence-register.md:30` says "`yin.vm.macro` was
  required only by `semantic`". It was required by all four models. Correct
  the sentence; the conclusion (the walker has no macro branch) stands.
- `docs/design/dao.runtime.v2.implementation-plan.md:219-223`: strike the
  consumers this plan cleared — `yin.demo`, `yin.vm.bytecode-bench`,
  `yin.register-bench-cljd`, `datomworld.demo.continuation-handoff`,
  `datomworld.demo.continuation-stream`, `datomworld.demo.compilation-pipeline`,
  `datomworld.demo.equation-plotter`, and `datomworld.demo` itself (after this
  plan it requires no `yin.vm.*` namespace; its remaining v1 dependence is
  `dao.stream` through `yin_repl.cljs` and `telemetry_viewer.cljs`, which is
  the stream gate, not the VM gate). Leave `yin.repl` v1 and `dao.await`.
- `docs/design/dao.stream.md:806-808` is accurate as written and is left alone.

**Criteria:** no file outside `collab/` and `docs/orchestrator-log.md` refers
to a deleted namespace, model, alias or launcher as if it existed. Verified by
the three Phase 0 sweeps plus
`grep -rn "SemanticVM\|RegisterVM\|StackVM" docs src`.

## Completion criteria

Every file this plan touches, in one list, so the reviewer can tick it:

- Deleted (26): the 5 models/engine, `continuation_handoff.cljc`, `demo.clj`,
  3 cljs demos, `register_bench_cljd.cljd`, `bin/register_bench_cljd.dart`,
  `bytecode_bench.clj`, 7 VM tests, `yang/macro_test.clj`,
  `continuation_handoff_test.cljc`, `vm_state_keys_test.cljs`, `wasm-vm.md`.
- Migrated (9): `repl.cljc`, `flutter.cljd`, `demo.cljs`,
  `compilation_pipeline_v2.cljs`, `test_utils.cljc`,
  `runtime_regression_test.cljc`, `telemetry_test.cljc`, `repl_test.cljc`,
  `yang/clojure_test.clj`.
- Config (1): `deps.edn` loses the five aliases `:bench`, `:profile`,
  `:profile-fast`, `:profile-cesk-space`, `:profile-ast-walker`.
- Prose (14): the two `yin.repl.v2` sources, `yin.repl.md`,
  `yin-repl-design.md`, `dao_gui.md`, the six historical docs, the divergence
  register, the dao.runtime plan.
- Unchanged but verified (2): `public/chp/yin.chp:18` still resolves;
  `public/The_Performance_Paradox.mp4` is untouched.
- Build green on clj, cljs (`:test` and `:demo`), cljd; the Flutter startup
  smoke and the browser-demo checks under Phase 1 pass.

## Boundary — what stays, and what is owed elsewhere

> **Status (2026-09-16):** `yin.vm.v1-retirement.implementation-plan.md`
> cleared the first four rows of the table below and deleted the v1
> ast-walker lineage; `dao.runtime` R4 is open. Gate 2 is unchanged.

**Stays after this plan, deliberately:** the v1 ast-walker lineage —
`yin.vm`, `yin.vm.{ast-walker, engine, ffi, telemetry, stream-driver,
runtime-adapter}`, `dao.runtime`, and their tests. They are still required by
v1 `yin.repl` (`repl.cljc:17-18`), `dao.await` (`await.cljc:25-27`) and
`test/yin/vm/v2/parity_test.cljc:16` (v2 parity is asserted against v1 in the
same process). Deleting them is the second slice of the v1 VM deletion that
`dao.stream.md:806` assigns to this document's name; it needs v1 `yin.repl`'s
consumers (D1) and `dao.await`'s migration to `dao.await.v2` resolved first,
and no document schedules either.

There are two different gates downstream of this plan. They are separated
here.

**Gate 1 — `dao.runtime.v2` R4, the VM gate.** R4 is "gated on v1
`yin.vm.engine` no longer requiring `dao.runtime`, which is gated on the v1
VM's ten consumers migrating" (`dao.runtime.v2.implementation-plan.md:352-354`,
census at `:219-223`). Of those ten, this plan clears eight (the seven struck
in Phase 2 plus `datomworld.demo`) and leaves two: v1 `yin.repl` and
`dao.await`. Plus one the runtime plan does not list because it is a test:
`yin.vm.v2.parity-test`'s require of v1 `ast-walker`. **This plan does not
claim to open R4.** The first draft said it "fulfills the prerequisites for
safely executing Phase R4"; it clears most of the gate and names what is left.

**Gate 2 — the `dao.stream.v2` rename, the stream gate.** `dao.stream.md:790-796`
renames `dao.stream.v2` to `dao.stream` only "when the last consumer has
migrated under its own plan and legacy `dao.stream` is deleted". That
consumer list (`dao.stream.md:803-812`) is `yin.io`'s file transports with
`dao.gui.event` and `dao.postgraphics.terminal`, `dao.runtime` (gate 1),
`agent.tools`, the demo and server surfaces, the v1 VM lineage, and the v1
transports themselves. This plan removes exactly four entries from it — the
four models, which required v1 `dao.stream` — and touches nothing else on it.
`datomworld.demo`, v1 `yin.repl`, both telemetry servers and the Flutter
widget all still require v1 `dao.stream` after this plan. The stream gate is
much larger than the VM gate and is not nearly done; nothing here should be
read as implying otherwise.

| owed | by | recorded where |
|---|---|---|
| deletion of v1 `yin.repl` with `yin.repl.flutter`, both telemetry servers, `yin.repl.runner`, `bin/yin_repl_main.dart`, the `:yin-repl` build and its aliases | the v1 deletion, once `dao_gui.cljd`/`solar_system.cljd` have a v2 REPL widget or drop the REPL | `dao.stream.md` *remaining v1 consumers*; nothing more specific exists |
| `dao.await` off v1 `yin.vm` | `dao.await`'s own plan (`dao.runtime.v2.implementation-plan.md:393-394`) | there |
| v2 parity test off v1 `ast-walker` | the second VM deletion slice — parity against a deleted v1 needs pinned values instead | this table |
| the second VM deletion slice (v1 walker lineage, then `dao.runtime` R4) | a successor to this plan, written when the three rows above are clear | this table; this is gate 1 |
| the `dao.stream.v2` rename | whoever lands the last row of `dao.stream.md:803-812`; not a VM plan | `dao.stream.md:790-796`; this is gate 2 |
| `equation_plotter`/`continuation_stream` v1→v2 feature diffs | this plan, Phase 0 | above |
| whether the v2 pipeline should link the explainer video | the demo's owner; optional | D3 |
| a v2 walker benchmark | nobody; optional | this table |

## Revision history

- **2026-09-10, architect r3**, against a confirmation review of r2. Two
  findings and one prose nit, all re-verified and resolved: `demo.cljs`'s
  census row now lists every use of the three v1 aliases, including the
  `:277-288` toolbar block r2 missed (`pipeline/show-explainer-video!`,
  `pipeline/layout-controls`, `pipeline/app-state`), with a delete-with-v1
  disposition and the reason in D3; the `cont-demo`/`plotter-demo` aliases
  were checked for the same pattern and have none. The Flutter startup smoke
  now names the real entry point (`datomworld.demo.main` via `lib/main.dart`)
  and the picker selection ("dao.gui Prototype") that reaches
  `start-server!`; `dao_gui.md:41`'s nonexistent `datomworld.main` is added
  as a one-word doc fix. D5 corrected: an old hash renders the v2 demo but
  stays in the address bar; only `select-demo!` writes the hash. D3's
  compiler description corrected: the Python/PHP compilers return
  `:type`-keyed AST maps that `vm/ast->datoms` converts, as the Clojure
  compiler does. Phase 0 gains a per-alias grep for every migrated file.
- **2026-09-10, architect r2**, against an adversarial review (gpt-6-astra) of
  r1. Six findings, all re-verified against the tree and resolved:
  `runtime_regression_test.cljc` moved from "unchanged" to "migrated" (it
  requires the three models at `:8-10`; body untouched). `flutter.cljd:60`'s
  explicit `:vm-type :semantic` added to the migration table, with the three
  checks that catch the class (keyword sweep, cross-host `create-state` test,
  Dart-host startup smoke). Alias count corrected to five and named;
  `bin/register_bench_cljd.dart` given a deletion row; the Phase 0 sweep
  widened to three greps over `src test bin deps.edn bb.edn shadow-cljs.edn
  public`. D3's "assumption" replaced by the verified Python/PHP frontend gap
  in `compilation_pipeline_v2.cljs`, with a port-before-delete disposition.
  D5 added: `#pipeline`, `#plotter`, `#continuation` kept as alias routes so
  `yin.chp:18` keeps working. Boundary split into the R4 VM gate (this plan
  clears eight of ten consumers) and the `dao.stream.v2` rename gate (this
  plan clears four entries of a much longer list). Census: 28 → 31 files.
- **2026-09-10, architect r1.** Consumer census added (28 files; the draft
  named 2). `wasm` dropped from the delete list — gone in `b8a6fce`. Phases 1
  and 2 merged because both macro tests require the models. v1 `yin.repl` given
  a migrate disposition with evidence that no plan schedules its deletion (D1).
  `continuation-handoff`, `yin.demo`, the three cljs demos, the cljd bench and
  `bytecode_bench` given deletion dispositions (D2–D4). End condition corrected:
  this plan clears part of the R4 gate, not all of it. Divergence register
  line 30 flagged as factually wrong about `macro`'s dependents.
- **Original draft.** Three phases; two files named in completion criteria.
