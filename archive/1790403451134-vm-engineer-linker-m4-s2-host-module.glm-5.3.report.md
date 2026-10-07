Completed-GMT: 2026-09-26 06:34:29 GMT
Completed-Local: 2026-09-26 13:34:29 +07
Coding-Agent: glm
Session-ID: b8683d48-38fd-4418-8bf3-9815e82192e3

# yin.vm.linker M4 slice S2: host-module registration, registry value shape

All work in /Users/sto/workspace/datomworld-m4-s2 (branch m4-s2, master
b3d0b5a1), uncommitted per task rules. TDD: the new module tests were
written first and failed on `No such var: module/register-host-module`
before any implementation existed.

## What landed

- `register-host-module r name fns profiles` in src/cljc/yin/vm/module.cljc,
  with UCF 7.5.2 profile-class enforcement (yin.vm.linker.md section 8.3,
  enforcement text at L1842-1855; UCF section 7.5.2): every binding must
  carry a profile in the shape of `yin.vm/primitive-profiles` (the five
  `:yin.k/*` keys), and registration throws, as a host assembly defect,
  unless the class is `:pure`, or `:effectful` with a declared non-empty
  `:yin.k/effects` set, and `:yin.k/host-state` is `:none`. Refusal ex-data:
  `{:rule r :module name :name sym}` with rules `:missing-profile`,
  `:malformed-profile`, `:host-state`, `:host-class` (also the default for
  any unknown class), `:undeclared-effects`. The first defective binding in
  sorted name order is named, deterministically on every host.
- `register-stream-module` rebuilt over `register-host-module`, one-arg
  signature kept, registering the unchanged `stream-module` bindings under
  the new `stream-profiles`: each binding `:effectful` with exactly its one
  effect kind (`:stream/make` `[0 1]`, `:stream/put` `[2]`,
  `:stream/cursor` `[1]`, `:stream/next` `[1]`, `:stream/close` `[1]`),
  built by `yin.vm/primitive-profile`, so the records are byte-shape
  identical to `primitive-profiles` entries.
- Registry value shape changed to
  `{name {:manifest m :address a :derivation d :slice {sym export}
  :stores {}}}` -- a clean break, NO shim: `register-module` is removed
  (repo-wide grep: zero remaining references). A host module is entered as
  an already-linked manifest: `:yin.module/name`, `:yin.module/tree`
  absent, `:yin.module/derivations {}`, and its exports under
  `:yin.module/primitives` by profile address. `:address` and
  `:derivation` are nil (no content was fetched or derived; S3/S4's
  `link-module` fills real ones), `:stores` is `{}`, and the exports
  themselves are the `:slice`, because a host module has no kernel
  coordinates to encode: "no code fetched, because the host is the
  boundary".
- `resolve-module` keeps its `[registry sym]` signature. Its walk descends
  dotted namespace segments through plain maps until it reaches a module
  entry (a node carrying `:manifest`), returns that entry for a module
  name, and reads the entry's `:slice` for the remaining binding segments,
  which is what `resolve-var`'s module step answers. The walk returns
  whatever node a path lands on, so hand-built receivers in the old nested
  shape (linker_test's discharge fixture) still resolve unchanged.

## JVM counts

- Before (baseline, no edits): 2,118 tests / 181,736 assertions,
  0 failures, 0 errors. (The task's stated 181,744 differs by 8 assertions;
  the actual baseline on this worktree is 181,736.)
- After: 2,120 tests / 181,764 assertions, 0 failures, 0 errors
  (+2 tests, +28 assertions).
- Anomaly, reported faithfully: one intermediate full run reported
  "1 failures, 0 errors"; the failure detail scrolled out of that run's
  captured tail, and two subsequent full runs (one grep-audited for
  FAIL/ERROR lines, one with the complete log kept) were 0 failures /
  0 errors. Not reproducible; the suite carries known timing-sensitive
  tests (project memory: poll-until flakiness on slow hosts).

## Files changed (5)

- src/cljc/yin/vm/module.cljc -- register-host-module + enforcement,
  stream-profiles, register-stream-module over it, resolve-module walk,
  register-module removed, ns docstring example updated.
- src/cljc/dao/await.cljc -- registry now registers 'await and 'dao.await
  via register-host-module with new `await-profiles` (each binding
  `:effectful` declaring `:stream/cursor`, `:stream/next`, `:stream/put`).
- test/yin/vm/module_test.cljc -- rewritten (below).
- test/yin/vm/engine_test.cljc -- resolve-var test's registration call.
- test/yin/vm/debruijn/stack_effects_test.cljc -- require-through-registry
  test's registration call.

kondo: 0 errors, 0 warnings; cljstyle: clean, on all five (after one
cljstyle fix adding the second blank line before `await-profiles`).
All added/edited lines ASCII, <= 80 columns.

## Tests added (module_test.cljc)

- `register-host-module-enforces-profile-classes-test`: accepts
  well-profiled `:pure` and `:effectful` bindings (and they resolve);
  refuses `:host-class`, declared host state, no profile, and `:effectful`
  with an empty effect set, asserting each `:rule`; the refusal's ex-data
  names the module and the first defective binding.
- `host-module-entry-shape-test`: the registry value shape (exactly
  `:manifest :address :derivation :slice :stores`; exports in `:slice`,
  empty `:stores`, nil `:address`/`:derivation`) and the already-linked
  host manifest (name, `:yin.module/tree` absent, `{}` derivations,
  primitives by profile address).
- `stream-module-test` extended with the TRAP check: `stream-profiles`
  key-set parity with `stream-module`, shape parity with
  `primitive-profiles` entries, and the effect-kind union is exactly the
  five stream effects; register-stream-module therefore does not refuse.
- Existing tests kept their assertions except where the registry shape
  itself was asserted: `dotted-path-resolution-test` no longer expects the
  bindings map from a module-name lookup (now the entry); the other
  rewritten tests changed only their registration call form, not their
  assertions (engine_test and stack_effects_test still assert 42).

## Callers rewritten / verified

- Rewritten: src/cljc/dao/await.cljc (was lines 88-89),
  test/yin/vm/engine_test.cljc (was line 190),
  test/yin/vm/debruijn/stack_effects_test.cljc (was line 401 -- a caller
  the task's list did not name; removing `register-module` requires it).
- Verified as needing NO edit, so left untouched (the task listed them as
  callers to rewrite): src/cljc/yin/repl.cljc:436,
  src/cljs/datomworld/demo/compilation_pipeline.cljs:87,
  src/cljs/datomworld/demo/continuation_stream.cljs:83. All three read
  `(module/register-stream-module (module/default-registry))`, whose
  one-arg signature is unchanged by design ("register-stream-module calls
  register-host-module"); there is nothing to rewrite. repl.cljc is
  otherwise untouched (the parallel S1 worker owns its append functions).
- Consumers that keep resolving with no edits, all green in the JVM lane:
  engine `resolve-var` (engine.cljc:73), `require-handler` (module.cljc),
  completion `module-of` (completion.cljc:443), linker discharge
  `resolvable?` (linker.cljc:782), dao.await tests, repl tests,
  yang/clojure_test, completion_test, linker_test, stack/register effects
  tests.

## Deviations

None blocking; three judgment calls recorded:

1. Host entry `:address`/`:derivation` are nil and the `:slice` holds the
   exports themselves. The spec pins the five entry keys and that a host
   module is an already-linked manifest; it does not assign a host module
   a storage address, and host fns cannot be UCF-encoded without a
   primitives-map lookup that does not exist for module exports, while
   `resolve-var` must keep answering them. "No code fetched" is the
   warrant for exports-as-slice.
2. `:malformed-profile` and `:undeclared-effects` refusal rules exist to
   complete "profile shape and class at registration" (an unknown class
   keyword folds into `:host-class`); the spec names only the three
   required refusals, which map 1:1 to `:host-class`, `:host-state`,
   `:missing-profile`.
3. engine_test/stack_effects_test bind the non-fn value 42 under a
   `:pure` `[0]` profile to keep their assertions byte-identical; nothing
   in the enforcement text requires `fn?`, and value bindings were
   registerable before.

## Unrun checks

- Node (`bb test:cljs`) and Dart (`bb test:cljd`) lanes: the orchestrator
  runs them, per the task. Cross-host traps addressed in the code: no
  `letfn` (private defn instead), no `#'private` cross-namespace access
  (the five profile keys are restated locally; `vm/primitive-profile-keys`
  is private), no `#?(:clj ...)` ungated code added, `stream-profiles`
  built by the existing pure `vm/primitive-profile` (no load-time side
  effect; CLJD def-init laziness), plain keyword literals only.
