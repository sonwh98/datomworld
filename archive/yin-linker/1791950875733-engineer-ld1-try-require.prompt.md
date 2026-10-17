Created-GMT: 2026-10-14 04:10:00 GMT
Created-Local: 2026-10-14 11:10:00 +07
Coding-Agent: codex (gpt-6.1-sol)

# Task: Slice L-d-1, `try-require` primitive and refusal-as-data

Role: Engineer (implementation).
Worktree: `/Users/sto/workspace/datomworld-ld1` (branch `linker-ld1`).
Operate exclusively inside this worktree.
Stage this brief and your findings report INSIDE the worktree's `collab/`:
`collab/1791950875733-engineer-ld1-try-require.prompt.md` and
`collab/1791950875733-engineer-ld1-try-require.findings.md`.

## Context & Consensual Mob Rulings
Read first:
- `docs/design/datom.world.md` (Foundational Axioms and Invariants)
- `collab/1792341000000-architect-i4-scoping.claude-fable-5-1.stdout.log` (§1, §3)
- `collab/1791950575268-mob-consolidation-i4.md` (Unanimous mob consensus on Q1, Q2, Q3)
- `docs/design/yin.vm.linker.md` (§7.2 step 7)
- `docs/design/yin.vm.linker.dht.md` (§7.5.3, §9)
- `src/cljc/yin/vm.cljc` (`primitives`, `require` around line 410)
- `src/cljc/yin/vm/engine.cljc` (`refused-entry`, `ready`, `wake-installed`, `settle`, `link-read-step`, `abandon-link`, `abandon-installs`)
- `src/cljc/yin/vm/module.cljc` (`require-handler`, `default-registry`, `mint-link-id`)
- `test/yin/vm/linker_require_test.cljc` (test harness across VM backends)

## The Scope of L-d-1

Implement the bare VM primitive `try-require` (language-blind, linker seat) such that linking refusal or failure is delivered to the requiring continuation as structured data rather than raising an uncatchable effect error.

### 1. Primitive & Effect Profile
In `src/cljc/yin/vm.cljc`:
- Add primitive `try-require` beside `require`:
  ```clojure
  ['try-require (primitive-entry
                  'try-require
                  (fn [spec]
                    (let [ns-sym (if (vector? spec) (first spec) spec)]
                      (effect/make :module/try-require {:module ns-sym})))
                  :effectful [1] #{:module/try-require})]
  ```
- Profile is `:effectful [1] #{:module/try-require}`.

### 2. Handler & Effect Registration
In `src/cljc/yin/vm/module.cljc`:
- Refactor `require-handler` into `(require-handler [state effect opts])` and `(try-require-handler [state effect opts])` or parameterized `(handle-require [state effect opts try?])`.
- When `try?` is true:
  - If module is already linked/in registry: answer immediately with `{:value {:status :ok, :module module-name}, :state state, :blocked? false}` (consistent with data return).
  - If ancestry cycle detected (`some #{module-name} (:ancestry state)`): do NOT throw `ex-info`; answer as data:
    `{:value {:status :refused, :reason :require-cycle, :module module-name, :chain (conj (vec (:ancestry state)) module-name)}, :state state, :blocked? false}`.
  - Wait entries created for `:install` or `:link-request` must carry `:try? true`.
  - Kernel park-entry fallback: `(or (get park-entry-fns :module/try-require) (get park-entry-fns :module/require))` allows existing kernels to park without edits.
- Register `:module/try-require` in `default-registry` using `try-require-handler`.

### 3. Engine Outcome Delivery
In `src/cljc/yin/vm/engine.cljc`:
- In `refused-entry` (or its call sites):
  - When entry has `(:try? entry)`: deliver the refusal map as the continuation's resumed value:
    `(ready entry refusal)`
    instead of `(assoc (ready entry nil) :status :link-refused :refusal refusal)`.
- In success wake points (`wake-installed`, and `settle`'s linked-meanwhile case):
  - When `(:try? entry)`: wrap module-name in `{:status :ok, :module module-name}`.
  - When plain `require` (no `:try?`): deliver `module-name` directly as today.
- In `settle` and `advance-installs`:
  - Preserve `:try?` when converting `:link-response` entries into `:install` waiters.
- In `abandon-link` and `abandon-installs`:
  - When `(:try? entry)`: delivers `{:status :lost, :reason reason, ...}` as data.

### 4. Response Datum Shapes
Consistent with §1.3:
- Registry hit / install ok: `{:status :ok, :module 'foo}`
- Refusal: `{:status :refused, :reason <reason>, :module <module>, ...}`
- Loss: `{:status :lost, :reason <reason>, :link-id <link-id>}`

### 5. Documentation Updates
In `docs/design/yin.vm.linker.md` (§7.2 step 7) and `docs/design/yin.vm.linker.dht.md` (§7.5.3, §9):
- Clarify that `try-require` receives refusal data instead of raising; mention `:module/try-require` as the effect. Keep diffs minimal, clean ASCII, <= 80 columns.

## Acceptance Criteria (Automated in `test/yin/vm/linker_require_test.cljc`)
Implement tests covering every backend in `backends` (`:semantic-register`, `:stack`, `:register`, `:semantic`):
1. **Stub `:absent`**: `try-require` of an absent module receives the refusal map (`{:status :refused, :reason :absent, ...}`), does not raise, and continuation continues execution.
2. **Success `:ok`**: `try-require` of an available module receives `{:status :ok, :module 'foo}`, and `foo/f` is callable.
3. **Registry hit**: `try-require` of an already-linked or host module receives `{:status :ok, :module 'foo}` immediately with no link request appended.
4. **Co-waiters independence**: A plain `require` and a `try-require` waiting on the same installing module that fails with `:export-missing`:
   - Plain `require` waiter raises the refusal as an uncatchable effect error.
   - `try-require` waiter receives `{:status :refused, :reason :export-missing, ...}` as data.
5. **Abandon link**: `abandon-link` delivers `{:status :lost, :reason ...}` as data to a `try-require` waiter.
6. **Cycle detection**: `try-require` of an ancestor module returns `{:status :refused, :reason :require-cycle, ...}` as data without throwing.
7. **Regression**: All existing `require` tests remain 100% green.

## Test Process & Rules
- Iterate on JVM only:
  `clojure -M:test -n yin.vm.linker-require-test` and `bb test:sub:clj yin.vm`
- Tests taking 5 s or more get `^:slow` and `dao.test-slow/guard`.
- Do NOT run Node, Dart, or full slow tiers on this machine; orchestrator runs them on Shiva.
- Portability constraints:
  - No `with-redefs`, `binding` of fn vars, `Thread/future`, `#'` private access.
  - Java interop only inside `#?(:cljd nil :clj ...)` (cljd first).
  - No 0.0/1.0 literals; start maps from `{}`.
- Lint and style: `clj -M:kondo` clean (0 errors, 0 warnings); `mise exec -- cljstyle check` clean.
- Mutations: Test at least 3 distinct mutants (e.g. drop `:try?` check in `refused-entry`; deliver plain symbol on `:try?` success; throw on cycle under `try?`), demonstrate each goes red, and revert.
- Write `collab/1791950875733-engineer-ld1-try-require.findings.md` early and update it with:
  - Files modified/added
  - Acceptance checklist with exact assertion counts
  - Mutation table
  - JVM test results
  - Deviations / open issues
- No git write commands (`git commit`, `git push`, `git rebase`); the orchestrator commits.
