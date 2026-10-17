Created-GMT: 2026-10-14 07:06:00 GMT
Created-Local: 2026-10-14 14:06:00 +07
Coding-Agent: codex (gpt-6.1-sol)
Session-ID: not-applicable (prompt brief)

# Task: L-d-3, deferred pins in the DHT source (`publish/requirements` public, walker `:eager?`, `dependency-bindings` skips deferred)

Role: Engineer (implementation). Worktree: `/Users/sto/workspace/datomworld-ld3` (branch `linker-ld3`, from master 739227db; already created and trusted with `mise trust`). Stage this brief and your report INSIDE the worktree's `collab/` (acceptEdits sandbox cannot reach main `collab/`).

BINDING spec:
- Scoping design: `collab/1792341000000-architect-i4-scoping.claude-fable-5-1.stdout.log` section 2.1, section 2.4 ("The DHT source"), and section 3 acceptance table row L-d-3.
- `docs/design/yin.vm.linker.dht.md` 4.2, 7.4.
- Code:
  - `src/cljc/yin/vm/linker/publish.cljc`: `requirements` function (~line 240).
  - `src/cljc/yin/vm/linker/closure.cljc`: `walk-requires` and `walk-module`.
  - `src/cljc/yin/vm/linker/dht.cljc`: `dependency-bindings` (~line 302).
  - Tests: `test/yin/vm/linker/closure_test.cljc`, `test/yin/vm/linker/dht_test.cljc`, `test/yin/vm/linker/dht_end_to_end_test.cljc`.

## Context & The Change

A dependency is **eager** when the module-level form requires it unconditionally (via top-level `(require 'm)`), and **deferred** when the module pins it in `:yin.module/requires` but requires it only at statement time (e.g. inside functions or via statement-time `try-require`). Eagerness is derived from the tree, never persisted in the manifest:

1. **`publish/requirements` made public**:
   In `src/cljc/yin/vm/linker/publish.cljc`, make `(defn requirements [ast] ...)` public so other linker namespaces can derive eager requirements from a module's AST/tree.
   Note: per L-d-1 review P3-3, make sure it scans top-level `require` (and also notes `try-require` if applicable, but only unconditional top-level applications count as eager).

2. **Closure walker (`src/cljc/yin/vm/linker/closure.cljc`)**:
   - In `walk-requires` / `walk-module`: read the module's tree (which is loaded in step 2 via `walk-rows` or already present) or decode it to determine which requirements in `(:yin.module/requires manifest)` are eager vs deferred:
     `eager? (contains? eager-set n)`.
   - Records each requirement in `:requires` as:
     `{:module manifest-address :name n :manifest pinned :eager? eager?}`.
   - **Descent**: The walker descends (`walk-module`) into eager pins as today (`:missing` remains fatal for eager dependencies).
     **It does NOT descend into deferred pins in v1**: their content arrives through the importer's statement-time request. If a deferred pin's blobs are absent, the walk does NOT stop with `:missing` for that deferred dependency; the closure is still `:complete`.

3. **`dependency-bindings` in `src/cljc/yin/vm/linker/dht.cljc`**:
   - `dependency-bindings` iterates `(:requires w)`.
   - For deferred requirements (`(not (:eager? req))`): **skip them entirely** (or do not fail/refuse early on them). An early `:absent` would break optional imports, and early `:mismatch` would refuse a module for a dependency it may never import.

4. **Documentation**:
   Update `docs/design/yin.vm.linker.dht.md` 4.2 and 7.4 to specify the derived eagerness check, closure walk behavior for deferred pins, and `dependency-bindings` filtering.

5. **Acceptance Tests**:
   - `closure_test.cljc`: A module pinning a dependency it only requires at statement time (deferred) walks `:complete` even when the dependency is absent from the store; when an eager pin is absent, it walks `:missing`.
   - `dht_test.cljc`: `dependency-bindings` lists and skips deferred entries (or omits deferred from early refusal).
   - `dht_end_to_end_test.cljc`: A deferred dependency absent from the reader's set allows the dependent to link; a statement-time `try-require` answers `:absent` with the fold's diagnostics.
   - Regression: existing closure, dht, and dht_end_to_end tests remain green.

## Constraints & Process
- Iterate JVM only: `clojure -M:test -n yin.vm.linker.closure-test -n yin.vm.linker.dht-test` and `bb test:sub:clj yin.vm`.
- Portability: no `with-redefs`, start maps from `{}`, cljd first.
- Lints: `clj -M:kondo` clean on changed files, `mise exec -- cljstyle check` clean on changed files.
- Write `collab/1791961569446-engineer-ld3-dht-deferred.findings.md` in the worktree early and keep it current.
