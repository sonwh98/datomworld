Created-Date: 2026-10-21
Coding-Agent: claude
Model: claude-opus-5-5
Role: Implementation Engineer
Worktree: /Users/sto/workspace/datomworld/.claude/worktrees/host-h0
Branch: host-h0 (HEAD 9f6fc27d, uncommitted)
Answers: collab/1792529000000-reviewer-host-boundary-h0-r2.gpt-6-astra.findings.md

# Slice H0: fix for H0-R5 and the §8.1 reconciliation

Only `test/dao/host/gate_test.clj` changed in this round. Runtime source,
closure tool, closure edn, corpus and governing design are unchanged.

- Gate source SHA-256: `a10b75e70690c72b1dbea7f9dc01889f2bec263a9d405ed6053422377137d41a`
- `git diff --binary` SHA-256: `63601da9a8000d6d22d3261e3ad7c7566d9db862d7d1d74d19017aa59c3ce635`
- Governing design (`docs/design/dao.stream.host-boundary.md`, main checkout) SHA-256: `4abe683a…c0ec`, the same as in the review.

## H0-R5: the `specify`/`specify!` target is now walked (fixed)

In `lane-effect-calls`, the `deftype-heads` branch now walks the target
`(second form)` of `specify`/`specify!` with the **enclosing** scope
(`bound`, not the field-extended scope), before the method declarations.
Methods are walked as before, so a method parameter still shadows only
inside its own method.

Regressions:
- `effect-calls-escape-neither-body-vectors-nor-other-scopes`, new testing
  block "H0-R5". It injects
  `(defn injected [] (specify! (do (random-uuid) (js-obj)) Object (toString [this] "x")))`.
  Result: `:fail`, exactly one `:random-uuid` violation, count 1 / allowed 0, no arm violations.
- `exemptions-and-resolution`, three unit cases:
  - `(specify (rand) P (m [x] (rand)))` gives two `:rand`: the target and the method body.
  - `(fn [rand] (specify! (rand) P (m [x] (rand))))` gives `[]`: the target sees the enclosing locals.
  - `(specify! (rand) P (m [rand] (rand)))` gives one `:rand`: the method parameter shadows only its own method.

The reviewer's probe `target/h0-review/re-review.clj` exits 0.
`:specify-valid-cljc` (your exact `#?(:cljs …)` form in
`dao/space/index.cljc`) now gives `:fail`, `:random-uuid` 1/0, line 1416,
the same as `:control`. `:specify-target` also fails, at
`dao/data.cljc:130`. `:io-failure` is unchanged (`:scan-failure`, 154 scanned).

## §8.1 / §14 vocabulary: `:pending` (fixed)

`yin/vm.cljc` is now `(entry :migration-source :pending "OQ11; :404 …")`.
The `:clears` set of `:migration-source` replaces
`:conditional-retention-oq11` with `:pending`. The docstring and the
negative test ("`:pending` allowed only in the migration-source row") are
updated to match. The token `:conditional-retention-oq11` no longer
appears anywhere. The OQ11 intent is kept in the reason text.

## §8.1 category-to-class table (code matches the table; 8 design gaps recorded)

`list-1-categories :classes` now matches the §8.1 table exactly:
- `:migration-source` gains `:consumer`.
- `:runtime-prereq` loses `:deferred-timers`.
- `:deferred-scope` loses `:runtime-prereq`.
- `:seam-driver`'s `:lane-specific-classes` (and the lane-specific branch in `list-1-problems`) is removed.

**Against the bare table, list 1 fails on 8 files (12 lane rows).** The
cause is in the governing design, not in the gate. For these files, §8.1's
own file rows, §3.1's classes (`class-rows`, which H0 must not
reclassify against §3.1) and §8.1's mapping table cannot all hold:

| File | §8.1 row places it in | §3.1 class | Table admits for that row |
|---|---|---|---|
| `dao/space/transactor.cljc`, `yin/vm/ucf/authority.cljc` | deferred-scope ("`locking`") | `:runtime-prereq` (table C) | deferred-* only |
| `src/clj/dao/jing/content/driver.clj` | seams ("every lane-specific file") | `:deferred-timers` | `:external-stream-seam`, `:driver` |
| `src/{clj,cljs,cljd}/yang/antlr/cst.*` | seams | `:deferred-stage` | same |
| `src/cljd/dao/stream/{cbor,transit}/cljd.cljd` | seams | `:codec-track` | same |

I did not resolve this by guessing which section is wrong. Instead, the
gate has a new named ledger, `table-gaps`
(`{file {:class c :reason r}}`), which admits that one class for that one
file only. Two pinning assertions stop the ledger from drifting silently:
- list 1 against the bare table (`gaps = {}`) fails on **exactly** the ledger's files;
- removing any single gap makes `list-1-problems` non-empty, so every gap is needed.

This replaces the old implicit widening (the extra classes per category
and the lane-specific seam exception). Each exception is now one named
file with a design citation.

**Architect decision needed (choose one, then delete `table-gaps`):**
- (a) Amend the §8.1 mapping table:
  - `:deferred-scope` also admits `:runtime-prereq` (the `locking` files);
  - `:seam-driver` admits any class for a lane-specific file, as the row text already says.
- (b) Move transactor/authority to the runtime-prereq row in §8.1's file
  listing, and amend the seams row for the 6 lane files.

(a) is the smaller amendment and matches the r8 implementation's old
widening.

## Verification (this round)

- `bb test:sub:clj dao.host`: exit 0. 1 of 268 namespaces; **11 tests, 113 assertions, 0 failures, 0 errors** (`target/profile/sub-clj.log`).
- `clojure -Sdeps '{:aliases {:review {:extra-paths ["test"]}}}' -M:review target/h0-review/re-review.clj`: exit 0. Output is as above.
- `clj -M:kondo --lint test/dao/host/gate_test.clj`: 0 errors, 0 warnings.
- Closure tool `--check`: exit 0. clj 142, cljs 140, cljd 141; shared 133; lane-specific 9/7/8.
- `bb src/dev/host_boundary/corpus_hashes.clj --check`: exit 0, 18 files identical.
- Not run: the full JVM/Node/Dart suites and the slow tier. These are scanner/test-data changes, verified on the JVM gate lane only.

Nothing has been committed. H0 still needs independent review, plus the
Architect's ruling on the `table-gaps` ledger.
