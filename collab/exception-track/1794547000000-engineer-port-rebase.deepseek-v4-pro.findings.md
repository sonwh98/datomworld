Created-GMT: 2026-11-12 18:45:00 GMT
Completed-GMT: 2026-11-12 18:12:00 GMT
Completed-Local: 2026-11-13 01:12:00 +0700 (+0700)
Role: Engineer (PORT Phase A — rebase exception-r3-impl onto post-cc3 master)
Coding-Agent: deepseek

# Findings — rebase exception-r3-impl onto post-cc3 master (provenance vs. retained seam)

Rebased branch `exception-r3-impl` (was `e735ebb2`) onto `origin/master`
(`fb493d52`, post-cc3) and force-pushed. Six commits preserved; the only
conflicts were cc3's provenance primitive vs. the port's (since-declined)
retained-carrier seam, in the VM cell surface — not in `yang/clojure.cljc`.

## Conflict resolution (3 hunks, each noted)

All three hunks resolved to **master's version** (provenance survives, retained
machinery gone). This is not a mechanical "ours" — it is the port's own final
state: the port's `e735ebb2` (owner NO, decision 1794372000000) removes the
`:retained` heap mark, `cell/dsg__new-retained` mint, `cell/retained?` query,
and the `set!` refusal. The port's net effect on `engine.cljc`/`module.cljc`
is therefore zero (add retained in `fbd8da0f`, remove it in `e735ebb2`), so the
rebased tip must equal master there. Encountered twice (at `fbd8da0f` and
again at `e735ebb2`), resolved identically both times.

1. `engine.cljc` `heap-write` — master wanted `heap-write` to preserve the
   `:provenance` sibling (`update-in … (assoc entry …)`); the port wanted
   `heap-write` to carry "any other heap-entry fact survives" plus a new
   `heap-write-retained`. **Kept** master's provenance-preserving `heap-write`;
   dropped `heap-write-retained` (the port removes it itself). Note the port's
   own `e735ebb2` would have reverted `heap-write` to `(assoc-in … {:value … :seal …})`,
   which silently drops `:provenance` — that revert was NOT applied; provenance
   preservation is load-bearing for cc3's primitive and must survive.

2. `module.cljc` `cell-module` — master wanted `cell/dsg__new-provenance` /
   `cell/dsg__provenance` doors; the port wanted `cell/dsg__new-retained` /
   `cell/retained?`. **Kept** master's provenance doors; dropped the retained
   bindings (removed by the port's own `e735ebb2`).

3. `module.cljc` `cell-profiles` — same split (provenance vs. retained profiles).
   **Kept** master's provenance profiles; dropped retained profiles.

## What the prompt got slightly wrong (no yang.clojure conflict)

The prompt expected a `yang/clojure.cljc` retention-state conflict (cc3 r7-r11,
"recovery trusts only shell-owned retention state"). There is none: that
retention state lives in `yin/repl.cljc`, which the port never touches, so it
is preserved as master's version automatically (verified: `git diff
origin/master..HEAD -- src/cljc/yin/repl.cljc` is empty). The only cc3 commit
touching `yang/clojure.cljc` in the rebase range is `566c5450`
(`check-program-symbols!` in `compile`), which auto-merged cleanly and survived
(verified present at `yang/clojure.cljc:3319`).

## Verification

- `git diff origin/master..HEAD --stat`: **only** `src/cljc/yang/clojure.cljc`
  (+715/-) and `test/yang/clojure_test.clj` (+509/-). `engine.cljc` and
  `module.cljc` are byte-identical to master. ✓
- Contract test: not run locally (remote-only, per ruling). ✓ (skipped)
- kondo: could not run — the sandbox required approval for `clojure -M:kondo`,
  which was not granted. Code compiles (the test suite loaded and ran 103 tests).
- **`clojure -M:test -n yang.clojure-test`: 38 failures, 13 errors — NOT green.**

## The load-bearing finding (must reach Phase 1)

The test failure is **pre-existing**, present at `e735ebb2` before the rebase,
and is NOT introduced by this rebase. Cause:

- `e735ebb2` removed the VM retained-carrier seam from `module.cljc` (owner NO,
  decision 1794372000000) — `cell/retained?` and `cell/dsg__new-retained` are
  gone from the `cell` module.
- But `yang/clojure.cljc` (untouched by `e735ebb2`) still *emits* those names in
  the generated exception-wrapper forms: `condition-wrapper-form` calls
  `cell/dsg__new-retained` (`yang/clojure.cljc:945`) and `condition-retained-form`
  calls `cell/retained?` (`:957`). The focused test then dies at
  `Unable to resolve symbol: cell/retained?` and `cell/dsg__new-retained`.

`e735ebb2`'s message says the mark/mint/query "land as part of the clojure
exception port (continuation encoding), where no retention object exists" —
that re-implementation is NOT on this branch. So the branch is mid-refactor:
the VM seam is gone, but the compiler-side reference to it is not yet rewritten.
This is exactly the region the prompt flags for "Phase 1 rewrites that region",
and it needs to know: the retained-condition encoding in `yang/clojure.cljc`
(ordinary-payload passthrough + qualified-protocol wrapper) is currently
pointing at a dead VM door and must be re-expressed via continuation encoding
before the port is green.

## Git

Pushed `origin/exception-r3-impl` `e735ebb2…f37bc62b` (forced update). The
`e735ebb2` commit became empty on rebase (its engine/module removal is already
the identity because those files now equal master) and was committed with
`--allow-empty` to preserve the owner-NO decision record.

**REBASE CLEAN: 6 / RESOLVED: 3 conflicts (noted above)** — with the caveat that
the focused test is NOT green for a pre-existing reason (dangling
`cell/retained?`/`cell/dsg__new-retained` in `yang/clojure.cljc` after the
owner-NO seam removal), which Phase 1 must address.
