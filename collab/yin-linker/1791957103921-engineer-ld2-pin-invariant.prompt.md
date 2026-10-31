Created-GMT: 2026-10-14 05:53:00 GMT
Created-Local: 2026-10-14 12:53:00 +07
Coding-Agent: codex (gpt-6.1-sol)
Session-ID: not-applicable (prompt brief)

# Task: L-d-2, the registry pin invariant (`module/pin-conflict`, `receive-module` check, `link-install` present-and-unequal)

Role: Engineer (implementation). Worktree: `/Users/sto/workspace/datomworld-ld2` (branch `linker-ld2`, from master f484b5f3; already created and trusted with `mise trust`). Stage this brief and your report INSIDE the worktree's `collab/` (acceptEdits sandbox cannot reach main `collab/`).

BINDING spec:
- Scoping design: `collab/1792341000000-architect-i4-scoping.claude-fable-5-1.stdout.log` section 2.2 ("The registry pin invariant") and section 3 acceptance table row L-d-2.
- `docs/design/yin.vm.linker.dht.md` 7.4 (ruling 8: deferred admission and the registry pin invariant).
- `docs/design/yin.vm.linker.md` 4.3 (the dependency-binding row).
- Code: `src/cljc/yin/vm/module.cljc`, `src/cljc/yin/vm/engine.cljc` (`receive-module`, `link-install`), and existing pin tests in `test/yin/vm/linker_require_test.cljc` (`dependency-pins-are-checked-without-module-obligations-test`).

## Context & Defect to Fix

Today `link-install` (in `src/cljc/yin/vm/engine.cljc:1380-1391`) refuses when a pinned name is absent from the halted child's registry:
`(when (or (nil? entry) (not= pinned linked)) ... :reason :dependency-pin-mismatch ...)`.
Deferred dependencies (and statement-time `try-require`) make absence normal during initial module installation: an optional or deferred dependency has not been required yet, so it is absent from the child's registry.

The contradiction to amend: `yin.vm.linker.dht.md` 7.4's "An absent or unequal entry refuses `:dependency-pin-mismatch`" and the landed test's `:absent` mode contradict ruling 8's deferred admission.

## The Change (pure, language-blind, registry invariant)

1. **`module/pin-conflict` in `src/cljc/yin/vm/module.cljc`**:
   Derives from the manifests already held in a registry and the incoming entry:
   A registry never holds a linked module under a name at an address that differs from the address any linked manifest in that registry pins for that name.
   Specifically, check conflict between `entry` (with its `:address` and its `(:yin.module/requires (:manifest entry))`) and the registry's existing linked entries (`(linked-entries registry)` or `(module-entries registry)` where entries have `:address` and `:manifest`):
   - Case (a): some linked manifest already in the registry pins `module-name` at an address other than `(:address entry)`.
   - Case (b): the incoming `entry`'s own manifest pins a name that the registry already holds at an address other than the pinned address.
   When a conflict is detected, `pin-conflict` returns:
   `{:status :refused, :reason :dependency-pin-mismatch, :module module-name, :name conflicting-name, :pinned pinned-addr, :linked linked-addr}`.
   Otherwise `nil`. Deterministic sorting if multiple conflicts exist.

2. **`receive-module` in `src/cljc/yin/vm/engine.cljc`**:
   Before updating `:modules`, check `(module/pin-conflict (:modules state) module-name entry)`.
   If conflict exists, throw ex-info with the refusal map so that `advance-install` catches it via `error-refusal` and turns it into `refuse-install` at phase `:linked` (or returns refusal in child spawn).
   Verify that this covers:
   - Child's inheritance at spawn (`spawn-child`).
   - Parent's receive of the installed entry (act 3).
   - Parent's receive of the child's linked entries (act 4).

3. **`link-install` present-and-unequal only**:
   In `link-install`, for `(:yin.module/requires manifest)`:
   **Present-and-unequal refuses; absent is permitted.**
   `(when (and (some? entry) (not= pinned linked)) ...)`
   An absent dependency entry in the halted child is no longer an install-time refusal.

4. **Documentation**:
   Update `docs/design/yin.vm.linker.dht.md` 7.4 and `docs/design/yin.vm.linker.md` 4.3 row to document that present-and-unequal refuses, absence is permitted at install, and the registry pin invariant guarantees safety at receive.

5. **Tests**:
   - Flip the `:absent` mode of `dependency-pins-are-checked-without-module-obligations-test` in `test/yin/vm/linker_require_test.cljc` to link ok (it now installs and links successfully when the dependency is absent from the install child).
   - Test deferred pin followed by a `try-require` (or `require`) resolved at another address: refused with `:dependency-pin-mismatch` as data (under `try-require`) or raise (under `require`), registry and stores untouched.
   - Test concurrent-install conflict: parent receives module at address Q after spawning child whose module pins at address P: refused at parent's receive.
   - Retain and verify existing `:matching`, `:inherited`, `:fresh` modes remain green.

## Test Process & Constraints
- Iteration on laptop: JVM only (`clojure -M:test -n yin.vm.linker-require-test` and `bb test:sub:clj yin.vm`).
- Portability: no `with-redefs`, no host-specific interop, start maps from `{}`, cljd first.
- Lints: `clj -M:kondo` clean, `mise exec -- cljstyle check` clean.
- Write `collab/1791957103921-engineer-ld2-pin-invariant.findings.md` in the worktree early and keep it updated.
