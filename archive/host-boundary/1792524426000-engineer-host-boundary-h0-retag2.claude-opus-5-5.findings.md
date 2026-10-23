Created-GMT: 2026-10-20 19:40:00 GMT
Created-Local: 2026-10-21 02:40:00 +07
Coding-Agent: claude
Model: claude-opus-5-5
Role: QA & Verification engineer
Prompt: /Users/sto/workspace/datomworld/collab/1792524426000-engineer-host-boundary-h0-retag2.claude-opus-5-5.prompt.md
Review addressed: /Users/sto/workspace/datomworld/collab/1792524256000-architect-host-boundary-review-r8.gpt-6-astra.findings.md (Part 2, H0-1..H0-3)
Design read: /Users/sto/workspace/datomworld/docs/design/dao.stream.host-boundary.md (r8: §3.1, §8.1 rows and the reconciliation paragraph, §13 OQ11)
Worktree: /Users/sto/workspace/datomworld/.claude/worktrees/host-h0 (branch host-h0, uncommitted on top of 9f6fc27d)
Status: DONE. All three review findings are fixed. Nothing is staged, committed or pushed.

# H0 retag, round 2: astra's findings H0-1, H0-2 and H0-3

This round changes only `test/dao/host/gate_test.clj`. The closure tool,
the closure edn files, the corpus hashes and all `src/` code are unchanged
from round 1; the tool's `--check` and the corpus `--check` both exit 0.

## H0-1: the ring buffer is now in the seams row

`src/cljc/dao/stream/ringbuffer.cljc` moved from `:dialect-arm` to
**`:seam-driver`, `:stays`**. Its reason reads: ":168 the in-memory stream
implementation's own host atom test (§4.1, implementation-private)".

r8's §8.1 seams row names it, and its closure class is already
`#{:deferred-entropy :external-stream-seam}`, which the seams category
admits.

The dialect row now holds the five files r8 lists. The category counts
are now:

| Category | Files |
|---|---|
| migration-source | 24 |
| seam-driver | 22 |
| deferred-scope | 16 |
| dialect-arm | 5 |
| codec-track | 5 |
| runtime-prereq | 3 |
| profile | 1 |
| **Total** | **76** |

## H0-2: `yin/vm.cljc` is retained conditionally, pending OQ11

- `src/cljc/yin/vm.cljc` now clears with the new value
  **`:conditional-retention-oq11`**. Its reason: H2 migrates the one-arity
  `bytes->str` to strict UTF-8 (a named behaviour change), while the JVM
  encoding arity stays behind a retained arm until OQ11 is decided.
- The value is documented in the `list-1-categories` docstring and is
  allowed **only** in the `:migration-source` row's `:clears` set.
- The other eight profile consumers keep `"H2"`.

## H0-3: validation now runs per lane and checks the category-to-class mapping

- **The mapping is data.** Each row in `list-1-categories` now has
  `:classes`: the §3.1 closure classes a file in that row may carry. The
  seams row also has `:lane-specific-classes`, which only `.clj`, `.cljs`
  and `.cljd` files may use instead (see the table below).
- **The checks are a pure function.** `(list-1-problems list-1
  lane-classes)` returns `[kind file detail]` triples. The input is
  `{lane {file classes}}`, built per lane from the committed edn by
  `committed-lane-classes`. Lanes are checked one at a time and never
  merged. The problem kinds are:
  - `:unknown-category`
  - `:slice-not-allowed` (the slice is not in that row's `:clears`)
  - `:in-no-lane`
  - for each lane whose closure holds the file:
    - `:empty-classes`
    - `:class-none` (the class set is exactly `#{:none}`)
    - `:class-not-in-category` (no class the category admits)
- **The real data has no problems.** The test asserts
  `(= [] (list-1-problems list-1 (committed-lane-classes)))`.
- **Each check is shown to fail.** The same test has seven in-memory
  negative cases, each on a deliberately wrong entry:
  - category `:h0-unlisted` gives `#{:unknown-category}`;
  - slice `"H9"` gives `#{:slice-not-allowed}`;
  - `:conditional-retention-oq11` on `dao/pretty.cljc` (runtime-prereq
    row) gives `#{:slice-not-allowed}`;
  - the ring buffer recategorised to `:dialect-arm` gives
    `#{:class-not-in-category}`;
  - `yin/vm.cljc` set to `#{:none}` on **cljs only**, with clj and cljd
    untouched, gives exactly
    `[[:class-none "src/cljc/yin/vm.cljc" [:cljs :migration-source #{:none}]]]`,
    so another lane's tags cannot hide it;
  - `#{}` on cljd only gives exactly the one `:empty-classes` problem;
  - a list-1 file that no lane holds gives `#{:in-no-lane}`.

The mapping, derived from §3.1 and §8.1 of r8. Where the design was
ambiguous I implemented the narrowest checkable set; the ambiguities are
listed below.

| Category | Admitted closure classes |
|---|---|
| `:migration-source` | `:profile-internal-pure`, `:profile-hash-sign`, `:profile-consumer` |
| `:profile` | `:profile-internal-pure` |
| `:runtime-prereq` | `:runtime-prereq`, `:deferred-storage`, `:deferred-timers`, `:internal-pure-display` |
| `:codec-track` | `:codec-track` |
| `:dialect-arm` | `:dialect-arm` |
| `:seam-driver` | `:external-stream-seam`, `:driver`; lane-specific files may instead carry `:deferred-stage`, `:deferred-timers` or `:codec-track` |
| `:deferred-scope` | `:deferred-storage`, `:deferred-entropy`, `:deferred-timers`, `:deferred-stage`, `:deferred-gensym`, `:runtime-prereq` |

## Kept unchanged

- Totals 142/140/141 with 133 shared.
- The traversal and its independent agreement test.
- The two injection tests and the scan-failure test.
- The corpus hash set.
- list 2.
- The closure tool and its edn outputs, which round 1 last changed.

## Results (all run in the foreground in the worktree)

```
$ clojure -M:test -n dao.host.gate-test
Ran 10 tests containing 73 assertions.
0 failures, 0 errors.

$ clojure -Sdeps '{:aliases {:hb ...}}' -M:hb -m host-boundary.closure --check
clj 142 / cljs 140 / cljd 141, shared 133   -> exit 0

$ bb src/dev/host_boundary/corpus_hashes.clj --check
18 files; corpus identical                  -> exit 0
```

clj-kondo reports 0 errors and 0 warnings. cljstyle check is clean.

**Assertion count.** The count went from 293 in round 1 to **73**: the
original 65, plus 1 aggregate `list-1-problems` assertion, plus 7
negative cases. It went down because round 1's 228 per-file `is` calls
were replaced by one assertion over the full problem vector. That vector
names every offending file, lane and category when it fails, so no
diagnostic is lost.

The test count is still 10, as no deftest was added. The derived JVM-lane
total is 4131 tests and 286103 - 65 + 73 = **286111** assertions. I did
not re-run the full lane.

## Ambiguities in the design (r8)

1. **The seams row versus §3.1 for lane-specific files.** §8.1's seams row
   takes every lane-specific file except the Dart Jing writer, whatever
   that file's §3.1 row is. The `yang.antlr.cst` files are
   `:deferred-stage`, `content/driver.clj` is `:deferred-timers`, and the
   cbor/transit Dart twins are `:codec-track`. I admitted those three
   classes **only for lane-specific files**, not for `.cljc` files in the
   row.
2. **`:runtime-prereq` admits deferred and display classes.** §8.1 puts
   `btree/storage.cljc` (§3.1: deferred storage and timers, "promise
   facade, deferred §9.1") and `dao/pretty.cljc` (§3.1: its own
   internal-pure display row) in the table C row. I admitted
   `:deferred-storage`, `:deferred-timers` and `:internal-pure-display`
   there. A narrower design would give `pretty` a "display" category of
   its own.
3. **`:deferred-scope` admits `:runtime-prereq`.** §8.1 lists
   `transactor.cljc` and `ucf/authority.cljc` (`locking`) in the deferred
   row, but §3.1 classes `locking` as table C (`:runtime-prereq`), and
   those two files carry nothing else.
4. **`sign.cljc` clears as `:stays`.** r8 says H3 migrates it but it stays
   for `generate`. It keeps `:stays` (round 1's choice), not a
   conditional value like OQ11's, because its retention is unconditional
   (deferred entropy).
5. **The check requires overlap, not that every class maps.** A file
   passes if it has *at least one* admitted class on each lane, not if
   *every* class it has maps to its category. Many files sit in several
   §3.1 rows: `yin.repl` is driver and entropy, `journal` is entropy and
   table C. Requiring every class to match would contradict r8's
   "many-to-one" wording and §3.1's multi-row files.

Not done:
- no design-doc edit;
- no `src/` behaviour change;
- no `deps.edn` or `bb.edn` change;
- no full-lane run;
- no stage, commit or push.

Report: /Users/sto/workspace/datomworld/collab/1792524426000-engineer-host-boundary-h0-retag2.claude-opus-5-5.findings.md
