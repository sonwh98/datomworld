Created-Date: 2026-10-21
Coding-Agent: claude
Model: claude-opus-5-5
Role: Implementation Engineer
Worktree: /Users/sto/workspace/datomworld/.claude/worktrees/host-h0
Branch: host-h0
Responds to: collab/1792527800000-reviewer-host-boundary-h0.gpt-6-astra.findings.md

# Slice H0: fixes for the Codex review (H0-R1..R4)

**Status:** all four findings are fixed in the working tree and nothing is committed. H0 still needs an independent re-review; this report does not sign it off. The review's "later design reconciliation" item (`:pending` vs `:conditional-retention-oq11`, and the category-mapping differences) was not in this brief and is **not addressed**.

Snapshot: HEAD `9f6fc27d` plus uncommitted edits. The five files the reviewer reviewed are still modified, and this work adds `src/dev/subsystems.edn` and `docs/design/test.subsystems.md`.
- SHA-256 of `git diff --binary`: `5e64259201d93dba5685c8feee28d27c33f7d57dc32be92d90ba60f37e163615`
- SHA-256 of `test/dao/host/gate_test.clj`: `70753f2ae053393c208f62c7ab3efc3fa45b16be4cd90c09a67859bf10997e36`

No runtime source (`src/clj{,s,d,c}`), closure manifest, corpus file or `list-1`/`list-2` entry changed in this work.

## H0-R1: function-body vectors (fixed)

`walk-fn` is split into three functions in `test/dao/host/gate_test.clj`:
- `walk-fn` removes the name, the defmethod dispatch value (which is now walked as an expression), the docstring and the attr-map.
- `walk-arities` tells a single arity `[params] body...` apart from several `([params] body...)...`.
- `walk-arity` binds the parameters and walks every body form, including vectors.

Only the parameter vector is a binding. Two more gaps of the same kind are also closed:
- `:or` default expressions in destructuring (in fn params and in `let`-family bindings) are now walked through the new `binding-exprs`.
- `binding-syms` no longer treats `:or` values as bound symbols.

Multi-arity methods in deftype/extend bodies (`(m ([x] ..) ([x y] ..))`) now go through `walk-arities` too. `letfn` already did.

## H0-R2: scopes merged across arities and lanes (fixed)

- **Arities:** each arity binds its own parameters only. `walk-arity` starts from the enclosing `bound` every time.
- **Lanes:** `all-arms` is removed. The new `lane-effect-calls` takes one feature set and works like this:
  - It selects arms with the traversal's `select-arms`.
  - It computes `file-scope` (ns aliases, refers, `:refer-clojure` excludes, top-level defs) from that lane's text only.
  - It walks with those bindings.
- **Which feature sets run:** `effect-calls` runs `lane-effect-calls` for every set in `scan-features` over a file's lanes. A `.clj`/`.cljs`/`.cljd` file is read on its own lane; a `.cljc` file is read on all three.
  - :cljd runs twice: once as `#{:cljd :clj}` (the traversal's convention, matching the host-eval pass) and once as `#{:cljd}`. The second pass catches a `:cljd` arm that sits behind a `:clj` arm.
- **Counting convention:** a source site still counts once. Hits are de-duplicated by `[kind line column]` across lanes, so a site counts if any lane resolves it to the effect, and two arms on one line count as two sites.
- **Effect on list 2:** `list-2-equals-the-observed-calls` still passes with no count changes, so the rewrite did not change any observed count on the current tree.

## H0-R3: read failures (fixed)

`read-sources` wraps `slurp` per file. A thrown read becomes `{:read-error msg}` and `scan` reports it as `cannot read file: <msg>` under that file name. A missing file still maps to nil, as before. The gate result is `:scan-failure`, and every other file is still scanned.

## H0-R4: subsystem registration (fixed)

`src/dev/subsystems.edn` gains a `dao.host` subsystem (prefix `dao.host`, layer 0), and the §3.1 table in `docs/design/test.subsystems.md` gains a row for it. Registering it also clears a failure that existed before: `affected-test`'s orphan check was failing with "test namespace has no subsystem: dao.host.gate-test".

## Regression tests added

- `effect-calls-escape-neither-body-vectors-nor-other-scopes` runs the reviewer's three probes against the real closure. It appends each one to the first `.cljc` file outside list 2 and asserts the exact violation `{:kind :random-uuid :count 1 :allowed 0}`:
  - R1, body vector: `(defn injected [] [(random-uuid)])`
  - R2, cross-arity: `(defn injected ([random-uuid] random-uuid) ([] (random-uuid)))`
  - R2, cross-lane: `#?(:cljs (defn random-uuid [] 1)) (defn injected [] (random-uuid))`. This one also asserts the arm violation.
- `a-scan-failure-is-not-a-pass`: a new R3 case redefines `slurp` so that reading `src/cljc/dao/data.cljc` throws `IOException`. It asserts `:scan-failure`, the error `[[file "cannot read file: injected read failure"]]`, and 154 files scanned.
- `exemptions-and-resolution`: new unit cases for:
  - per-arity scope, in both directions (a parameter shadows in its own arity, not in another)
  - body vectors
  - `:or` defaults in fn and let
  - a vector dispatch value on defmethod
  - multi-arity extend-type
  - a `#?(:clj (declare gensym))` shadow that holds only on `:clj`
  - a spliced `:refer-clojure` exclude that holds only on `:cljs`
  - a `:cljd` arm behind a `:clj` arm
  - two sites → 2, and one site read on three lanes → 1

## Verification (all run in this worktree)

| Command | Result |
|---|---|
| `clojure -M:test -n dao.host.gate-test` | **11 tests, 98 assertions, 0 failures, 0 errors** |
| `bb test:sub:clj dao.host` | selects 1 of 268 test namespaces (`dao.host.gate-test`); clj lane exit 0: 11 tests, 0 failures |
| `clj -M:kondo --lint test/dao/host/gate_test.clj src/dev/subsystems.edn` | 0 errors, 0 warnings |
| closure tool `--check` | exit 0; totals 142/140/141, 133 shared |
| `bb src/dev/host_boundary/corpus_hashes.clj --check` | exit 0; 18 files identical |
| reviewer probe `target/h0-review/probe.clj` | `:vector-body`, `:multi-arity` and `:conditional-shadow` now return `:fail` with one `:random-uuid` violation (allowed 0) at `src/cljc/dao/data.cljc:130`. `:required-arm`/`:required-effect`/`:empty`/`:missing`/`:malformed` are unchanged. The IOException probe no longer escapes: it prints nothing, because the gate now returns a result. |
| `bb -cp src/dev -m affected-test` | 22 tests, 425 assertions, **1 failure**: `(= 40 (count ucf-tests))`, actual 28. This existed before the change: with HEAD's `subsystems.edn` it fails the same way, plus the dao.host orphan failure. It is unrelated to H0. |

The full three-host `bb test` and the slow tier were not run. The changes are confined to a JVM-only test namespace and dev config.

## Known limits, not changed

- `specify`/`specify!` still skip their target expression (`(drop 2 form)`), as before.
- Arms for features outside the gate's lanes (the one `#?(:jolt ...)` in `dao/data/btree.cljc:1237`) are no longer walked by the effect scan, because it now reads only clj/cljs/cljd text. The arm scan (`non-exempt-arms`) still sees every arm. No list-2 count changed.
