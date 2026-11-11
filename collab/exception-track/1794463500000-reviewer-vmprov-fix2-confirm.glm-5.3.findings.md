Completed-GMT: 2026-11-11 18:16:00 GMT
Completed-Local: 2026-11-12 01:16:00 +0700 (+0700)
Reviewer: glm-5.3 (round 1794463500000, re-verification of 50c9485a)
Worktree: /Users/sto/workspace/datomworld-vmprov @ 50c9485a (branch exception-vm-provenance,
clean, identical to origin). Nothing modified there; the only artifact of this round is this
file. yang.call.exception-contract-test was NOT run (remote-only, owner ruling).

## 1. The three identity-macro passthrough probes — all :ok

Re-run individually (identity macro `id` = `(fn [x] x)`, `expand-batch` under a fresh ctx),
with a dsg__-site count proving each probe is non-vacuous (the prior round's trap):

| form | dsg__ binders/references in lowered input | expansion |
|---|---|---|
| `(id (let [[x] [42]] x))` | 1 binder + 1 reference | `:ok` |
| `(id (fn [[x]] x))` | 2 binders + 2 references | `:ok` |
| `(id (loop [x 0] x))` | 2 binders + 2 references | `:ok` |

The permanent `identity-macro-preserves-retained-syntax-test` asserts the same three `:ok`s
and ran green in the suite (item 5). The over-rejection of 566c5450 is gone.

## 2. The admission rejections still reject

- **Source** (`yang.clojure/compile` pre-pass): `(let [dsg__x 1] dsg__x)`,
  bare `cell/dsg__new-provenance`, `(fn [dsg__b] 1)`, and `(dsg__call 1)` each refuse with
  `{:symbol <the dsg__ symbol>, :prefix "dsg__"}`. Control: `(quote cell/dsg__provenance)`
  still compiles (quoted names stay inert data).
- **Macro expansion**: the quoted door packet (leak) refuses with
  `{:kind :invalid-output :reason {:rule :reserved-prefix :role :variable :name cell/dsg__new-provenance}}`;
  `yin/def` assembled over the door name via `yin/application` refuses with
  `:role :definition-key :name cell/dsg__provenance`.
- **Indirect application** (the restored invocation-time regression, re-run by hand):
  `m/invoke` of a macro body calling `(yin/variable 'cell/dsg__new-provenance)` refuses with
  `:body-error` and message "yin/variable refuses the compiler-private name
  cell/dsg__new-provenance". Prelude twins: `yin/variable`, `yin/lambda`, `yin/make-def` all
  refuse with `{:yin.macro/prelude true :name <door>}`; a quoted name via `yin/literal`
  stays inert.

## 3. Sibling class coverage — origin, not prefix, decides

Every forged shape went through a `leak` macro quoting a raw packet (no construction
origin → the pending fallback marks every path forged); every legitimate shape is admitted
syntax (source origin, retained). All 46 probe assertions passed.

| class | legitimate (must pass) | forged (must reject) |
|---|---|---|
| reserved lambda binder | `(id (fn [[x]] x))`, `(id (let [[x y] …]))` → `:ok` | quoted `{:type :lambda :params [other/dsg__hidden] …}` → `:error :role :binder` |
| store-get key | — (frontend mints none; see A6 for the mechanism) | quoted `{:type :vm/store-get :key cell/dsg__new-provenance}` → `:error :role :store-key` |
| store-put key | **A6**: admitted-source input `{:type :vm/store-put :key dsg__kept …}` through `id` → `:ok` | quoted twin with `:key cell/dsg__provenance` → `:error :role :store-key` |
| nested reference | dsg__ references nested in every A-form's lowering → `:ok` | dsg__ reference under an `:if` → `:error :role :variable` |
| reader query door | (no legitimate guest occurrence exists; the door is never compiler-minted in guest code) | quoted `cell/dsg__provenance` variable → `:error :role :variable` |
| splice into constructed output | **A5**: retained dsg__ operand spliced into a macro-CONSTRUCTED `yin/application` → `:ok` | **C7** (added this round): a quoted door packet spliced as the operand of a constructed `yin/application` → `:error :role :variable` (the missing-provenance path feeds the same scan) |

A6/A5 vs C7 is the direct proof of the engineer's claim: identical shapes take opposite
verdicts by origin alone — retained compiler-minted syntax passes, macro-constructed or
unaccounted packets reject, fail-closed (`origin-of-fn` absent ⇒ every path forged).

Code-level checks behind the probes: `reserved-prefix-defect` now iterates
`vm/occurrences` (`[root path id]` triples, one per place — every `:variable`/`:lambda`/
`:vm/store-*`/`:application` row is visited) and consults `forged?` = origin ∈
`#{pending-expansion-origin missing-provenance-origin}`; the occurrence path convention
(row positions, `[pos i]` for `:nodes`) matches the origin functions' coordinate keys in
`construct`/`slot-of`; the expansion site's fallback `(fn [_] pending-expansion-origin)`
keeps origin-less outputs refused. The row-guard and the `:missing-provenance` refusal are
unchanged and still run.

## 4. Restored test + no weakening

- `macro-cannot-construct-the-mint-door-test`: present at ff8b980a, **deleted at 566c5450**,
  restored at 50c9485a. Body byte-identical to the ff8b980a original (the only textual delta
  is two blank lines between it and the next deftest).
- `git diff ff8b980a..50c9485a -- test/yin/vm/provenance_test.cljc` has **zero deleted
  lines** — the whole arc is additive (16 deftests now, 13 at the feature commit).
- `git diff 566c5450..50c9485a` touches only `src/cljc/yin/vm/macro.cljc` and
  `test/yin/vm/provenance_test.cljc`; `yin.vm.macro-test` is untouched; the expansion
  row-guard branch is preserved verbatim inside the new `cond`.

## 5. Suite + kondo counts

- `clojure -M:test -n yin.vm.provenance-test -n yin.vm.macro-test`:
  **Ran 41 tests containing 334 assertions. 0 failures, 0 errors.** — exactly the
  engineer's claim.
- clj-kondo 2026.08.04 on the two changed files: **errors: 0, warnings: 1** —
  `src/cljc/yin/vm/macro.cljc:847:6: warning: Redundant let expression.` Linting the
  ff8b980a blob shows the same warning at 780:6 (pre-existing from the feature commit; the
  corrective commits only shifted its line number). `provenance_test.cljc`: no findings.
- Probes ran from a temporary namespace (created for the run, deleted after; the worktree
  ended `git status --porcelain`-clean): 2 test vars, **47 assertions, 0 failures,
  0 errors**. Direct clj-kondo invocation is permission-gated in this session, so lint ran
  through ProcessBuilder from that namespace.

## Verdict

The second corrective commit keeps every admission closed (source, expansion, indirect
application; all four roles) while legitimate compiler-minted `dsg__` syntax passes through
macros again, decides by construction provenance rather than the prefix, and restores the
deleted invocation-time regression verbatim. One pre-existing (non-blocking) kondo warning.

**CONFIRMED**
