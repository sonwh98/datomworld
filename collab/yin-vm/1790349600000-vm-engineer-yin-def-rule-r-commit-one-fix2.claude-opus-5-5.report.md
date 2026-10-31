Completed-GMT: 2026-09-25 17:25:20 GMT
Completed-Local: 2026-09-26 00:25:20 +07
Coding-Agent: claude
Session-ID: resume-of-078d0a96-daf0-4cef-b994-076601d800d6

# Report: Rule R commit one, fix round 2

Role: VM Runtime Engineer. Worktree: /Users/sto/workspace/datomworld-ucf-rule-r
(branch ucf-rule-r). Nothing is staged or committed. The whole uncommitted
diff is now 63 tracked files (+1832 / -543), plus two new test files.

## Lanes

Each lane ran solo under mise. Dart ran with `rm -rf test/cljd-out` first.

| Lane | Last round | Now | Change |
|---|---|---|---|
| JVM | 2,049 / 180,956 | 2,051 tests / 180,998 assertions, 0 failures, 0 errors | +2 tests: the macro-stamp test and the residual-fixture test |
| Node | 1,963 / 47,972 | 1,964 tests / 47,987 assertions, 0 failures, 0 errors | +1 test: the macro-stamp test |
| Dart | 1,925 | 1,926 passed | +1 test: the macro-stamp test ran on Dart |

- kondo on every changed file: 0 errors. Its 7 warnings all exist at HEAD.
- cljstyle: clean.
- Every added line in the uncommitted diff is ASCII and at most 80 columns.
  The one exception is Markdown grid-table rows, as before.

## P1: macro packets were relabelled v3 (fixed)

Each macro-store value is now a stamped entry built with
`(m/macro-entry packet contract)`, which gives
`{:yin.macro/tree packet, :yin.macro/contract c}`. Stamps are checked in
three places:

1. **The runner.** `invoke*` verifies the entry's contract against
   `vm/ast-contract` before anything runs, and throws `:contract-missing`
   or `:contract-mismatch`. This covers both routes into it:
   - the expansion path (`expand-batch` -> `expand-call`);
   - direct `m/invoke`, which now takes an entry.

   A bare packet has no contract, so it fails with `:contract-missing`.
2. **Construction.** `make-ctx` and `expand-batch` verify every entry in the
   store they are given, through `check-store!`, before expansion starts.
   The `expand-batch` check catches a store attached with `assoc`, which
   never passes through `make-ctx`.
3. **Stamping.** Only this expander stamps packets as current: harvest and
   post-harvest mark the packets they produce from the batch they admitted
   with `vm/ast-contract`. A seeded entry keeps its own stamp.

`bounded-row-evaluator` is public, so it was a second route. Its request
now carries the verified stamp as `:contract`. It checks that stamp before
running, and loads the body rows under it, never under its own constant.
The check happens before the `try`, so a stamp failure is thrown as a
refusal instead of being turned into `:body-error` data.

**Tests** (in `rule_r_test/a-macro-packet-runs-only-under-its-verified-stamp`):

- These are refused with the right rule on all three routes (`make-ctx`,
  `expand-batch` with a store attached via `assoc`, and direct `invoke`):
  - an unstamped packet: `:contract-missing`;
  - an entry stamped "v2": `:contract-mismatch`;
  - an entry with a nil contract: `:contract-missing`.
- The runner called directly: no stamp gives `:contract-missing`, "v2"
  gives `:contract-mismatch`, and "v3" runs.
- A current-stamped entry expands and runs.
- A harvested macro is stamped `vm/ast-contract`.

**Callers updated:**

- `macro_test`: the `macro-root` helper reads `:yin.macro/tree`; the
  `prelude-and-invoke` test stamps its own fresh packets.
- `repl_test` and `repl/repl-state`: the `:macros` summary reads the root
  from the entry.

**Sweep: every place that hands stored or supplied code to a loader under
the current contract.** I checked each of these:

- **Macro store / `invoke` / runner.** Fixed above.
- **`linearize` adapters and `ucf/canonicalize`.** Verified in round 1.
- **`repl/program-loaders`** (lines 117, 138, 155, 163). These take packets
  from `program-out`, whose only producer is the expander. It is the
  trusted fresh-producer path, and the code says so.
- **`vm/eval`.** Takes an AST map from its caller and validates the whole
  tree; the design names it a fresh producer. Its callers are:
  - `dao.await/run` (the process descriptor's `:ast`);
  - the demos: `demo.clj`, `equation_plotter.cljs`,
    `register_bench_cljd.cljd`.
- **Continuation handoff.** The receiver loads under the stamp shipped in
  the registers and verifies it.
- **`compilation_pipeline.cljs` and `continuation_stream.cljs`.** They lower
  fresh yang output.
- **`completion`** (default `:contract`) and **`ledger`**
  (`lowering-profile`). These only describe a contract or compare against
  one; neither loads anything.
- **Linker fetch (M1).** Returns an image and does not load it. The tests
  load it with an explicit contract.
- **`content.cljc`.** Fetches and materializes values; it does not load.

No other place relabels supplied code. One open point: the expander's
input batches are still unstamped syntax. They are admitted by
`valid-tree?`, and the packets harvested from them count as this
expander's fresh output. If `program-in` batches should carry a stamp too,
that is a design question, not something this change decided.

## P2: the audit missed local aliases (fixed, and its guarantee restated)

**(a) Detection.** The audit walker now tracks store aliases in scope:

- A symbol bound to a store by `let`, `let*`, `loop`, `loop*`, `when-let`,
  `if-let`, `when-some`, `if-some`, `when-first`, or `binding`.
- A binding made through an earlier alias, in the same or an enclosing
  binding form.
- A `{heap :store}` destructuring key.

Each alias is scoped to its binding form's body.

What counts as "a store" also grew:

- `(get-in x [:store ...])` now counts;
- in a `->` or `some->` pipeline, every mutation step after the pipeline
  passes `:store` is flagged.

I did not add alias tracking for fn parameters passed a store at a call
site. I could not do that reliably, so it stays in the documented residual.

There are 10 new negative fixtures, all caught:

- codex's `(let [heap (:store vm)] (assoc heap 'yin/def v))`;
- a chained alias (`h2` bound to `h`);
- a `get-in` alias;
- `when-let`, `if-let`, and `loop`/`recur` aliases;
- destructuring `{heap :store}`;
- a nested `let`;
- `-> vm :store (assoc ..)`;
- `some-> vm (get :store) (update ..)`.

The `src` allowlist did not change: no aliased store write exists in `src`
today.

**(b) The stated guarantee now matches the code.**

- The test's namespace docstring and `yin.vm.engine.md` section 1.1 list
  exactly what is detected, and say the allowlist is exact only for what
  the detector sees.
- Both list the residual that is NOT detected:
  - a store passed across a function boundary;
  - a store carried inside another data structure;
  - dynamic mutations (`apply`, `partial`, `comp`);
  - `as->` and `cond->` pipelines;
  - transients and host interop;
  - macros that expand to a write.
- A new test, `residual-forms-are-not-claimed`, pins four residual fixtures
  as undetected. If the detector ever starts catching one, the test fails,
  and the fixture moves to `negative-fixtures` with the docstring updated.
- I reworded three other statements so they no longer claim an exact audit:
  the `engine/store-put` docstring, the `datom.world.md` line, and the
  engine doc.

## Files changed this round

- **Source:**
  - `src/cljc/yin/vm/macro.cljc`: `macro-entry`, `verified-tree`,
    `check-store!`; changes to `invoke*`, `invoke`, `bounded-row-evaluator`,
    `harvest`, `post-harvest`, `expand-call`, `expand-batch`, `make-ctx`.
  - `src/cljc/yin/repl.cljc`: the `:macros` summary.
  - `src/cljc/yin/vm/engine.cljc`: the `store-put` docstring.
- **Tests:**
  - `test/yin/vm/rule_r_test.cljc`: new macro-stamp test; the existing
    expander test now uses entries.
  - `test/yin/vm/store_write_audit_test.clj`: alias-aware walker, 10 alias
    fixtures, the residual test, and the docstring.
  - `test/yin/vm/macro_test.cljc`.
  - `test/yin/repl_test.cljc`.
- **Docs:**
  - `docs/design/yin.vm.engine.md` (section 1.1);
  - `docs/design/yin.vm.macro.md` (the `invoke` signature, sections 4.1
    and 4.2);
  - `docs/design/yin.vm.universal-continuation-format.md` (section 7.3.3);
  - `docs/design/datom.world.md`.

## Unrun checks

None. All three lanes, kondo, and cljstyle ran. The parity suites ran
inside the lanes.

Status: COMPLETE
