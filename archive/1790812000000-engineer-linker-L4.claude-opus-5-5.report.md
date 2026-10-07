# Engineer report — linker over dao.jing.dht, slice L4

- Brief: `collab/1790831000000-engineer-linker-L4.prompt.md`
- Contract: `docs/design/yin.vm.linker.dht.md` §12 L4 (+ §§5.1, 5.3, 5.4, 5.5.6, 6, 7, 9, 10), owner decisions `collab/1790808000000-…owner-decisions.md`
- Worktree `/Users/sto/workspace/datomworld-linker-l2`, branch `linker-l4` (from `bd9bfae7`). **Nothing staged or committed.**
- Model: claude-opus-5-5

## Verdict

L4 is implemented. Every acceptance bullet has a test, and every lane is green when run in the foreground:

| Lane | Result |
|---|---|
| `clj -M:kondo --lint` (all 7 changed src + 6 changed test files) | 0 errors, 0 warnings |
| `bb test:clj` | 2683 tests, 224122 assertions, **0 failures, 0 errors** |
| `bb test:cljs` | 2597 tests, 90245 assertions, **0 failures, 0 errors**. All 6 changed namespaces print `Testing …`. Build: 0 warnings |
| `bb build:yin-repl-peer` | Built `build/yin-repl-peer`. Its DYNAMIC WARNINGs are all in untouched files |
| `bb test:cljd` (after `rm -rf test/cljd-out`) | `+2552 … All tests passed!`. All 11 new L4 REPL/plain tests are present in the regenerated `test/cljd-out` |

## Files changed

All are on L4's §12 list (`query_test.cljc` is the test beside `query.cljc`):

- `src/cljc/yin/vm/linker/publish.cljc`: `module-from-index` (5.3); `assertion`, `retraction`, `next-seq`, `name-envelopes` (6.1, 6.5, 6.6)
- `src/cljc/yin/vm/linker/dht.cljc`: `head`; `snapshots` (HEAD + loaded, 7.2); HEAD datoms in the fold; `authority` (7.1); `resolve-name` (§9 name refusals); `publish-name!` (one plain call: derive, publish, sign)
- `src/cljc/yin/repl/index.cljc`: rows every round (5.1); a `:materialize` failure leaves the round unpublished; `commit-names` (6.1: one transaction, session metadata in `m`, published at once so HEAD announces)
- `src/cljc/yin/repl/link.cljc`: the composition carries `:principals` plus the key's own public key (6.5); `authority`; the DHT source refuses names through `resolve-name`
- `src/cljc/yin/repl/query.cljc`: the `yin.link` host module (`publish`, `names`). Each answer checks arguments, makes one plain call (`publish-name!` / `names`), then the indexer commits the envelopes. No walk, fetch, fold or signing in REPL code
- `src/cljc/yin/repl.cljc`: `:dht-key` and `:principals` in `create-state`; the `yin.link` context (authority, key, VM primitives and registry, round) passed to the query serve; the indexer threaded back, and carried across a link raise; help text
- `src/cljc/yin/repl/main.cljc`: `--dht-key`, `--dht-principal` (strict hex, hand-anchored regex), `--dht-keygen`; `load-key` (no fallback); exclusive owner-only write (JVM POSIX perms, Node `wx`/0600, Dart exclusive create); `startup` answers `{:lines :exit}` for keygen; banner lines; all three `-main`s
- docs: `yin.vm.linker.dht.md` (§5.3 opts, §10 rows for the new functions), `yin.repl.dao.space-index.md` (rows every round; names as one transaction)
- tests: `test/yin/vm/linker/{publish,dht}_test.cljc`, `test/yin/repl/{dht,index,main,query}_test.cljc`

## Acceptance bullets → evidence

1. **Rows in the index store each round; a second node loads the tree by `:yin.repl/root`.**
   - `index-test/every-row-of-each-evaluated-program-is-in-the-index-store`: each packet row is stored under its id, and roots equal the `:yin.repl/root` facts.
   - `repl.dht-test/every-round-s-rows-are-published-and-a-second-node-loads-the-tree-by-its-root`: acknowledged; a plain node loads the root by a tree walk, `:loaded`, `:fetched > 0`.
2. **More rows than the pending-write bound, `:acknowledged`, never `/busy`, eight rounds in a row.** `repl.dht-test/rounds-larger-than-the-pending-write-bound-are-acknowledged-never-busy`: 8 rounds evaluated back to back, each `:blobs > max-pending-writes`, all 8 `:acknowledged`, no failed entry `::dht/busy`.
3. **A failed row replication.** `repl.dht-test/a-failed-row-is-partial-unreachable-except-from-the-publisher-and-repaired`:
   - The round is `:partial` with `:failed` = exactly that row, and HEAD moved.
   - The first line has "PARTIAL", "1 of N blobs not sent" and "retrying while the node is open".
   - A reader whose socket cannot reach the publisher fails `:miss` on exactly that row (keyword `:cause`). A reader that can reach it loads.
   - After healing, with no call from anyone, `:republished :acknowledged` arrives ≥ 30000 ticks after the first report, the line says "acknowledged: sent to 2 peers", and a new load succeeds.
   - `repl.dht-test/retry-at-the-prompt-brings-the-same-repair-forward`: `(dao.space.dht/retry m)` answers `:retrying`, and the round is acknowledged well before `:repair-ticks`.
4. **A refused manifest is `:unacknowledged` and repaired the same way.** `repl.dht-test/a-refused-manifest-is-unacknowledged-and-repaired-the-same-way`.
5. **Peers that never accept.** `repl.dht-test/with-peers-that-never-accept-twenty-rounds-report-and-the-ticker-idles`, driven through `main/step-all`:
   - Each of the 20 typed rounds answers its value at once.
   - There are 20 "NOT acknowledged" first reports and no "acknowledged: sent".
   - `main/moved?` is true during repair cycles and goes false again after one.
6. **A module published in a `:partial` round.** `repl.dht-test/a-module-published-in-a-partial-round-is-absent-until-repaired`:
   - The publisher's store of the semantic image is refused.
   - The reader loads the index at the prompt, `(yin.link/names)` resolves `my.lib`, and `(require 'my.lib)` is refused `{:status :refused :reason :absent :address image :cause <kw>}`.
   - After automatic repair, a new require evaluates `(my.lib/f)` → `4242`.
7. **Two-principal dependency at the prompt.** `repl.dht-test/a-two-principal-dependency-evaluates-or-raises-dependency-binding`:
   - Three shells: P1 publishes `base`; P2 loads P1's index, links `base`, publishes `app` pinned to it; R declares both.
   - Only P2's index loaded → `:yin.link.dht/dependency-binding :absent` (`:diagnostics []`).
   - Both loaded → `(app/g)` → `43`.
   - P1 republishes `base` → `:mismatch` with `:resolved` and P1 as asserter.
   - P2 also asserts `base` → `:ambiguous` naming both addresses and both principals.
8. **`(yin.link/publish 'my.lib '[f])`.**
   - `repl.dht-test/publish-at-the-prompt-derives-one-tree-and-declares-what-it-reads`: `base` linked; programs `h`, `unused`, `f`. The tree's module-level defs are `[h f]` in `t` order. The manifest has `:requires {'base addr}`, `:primitives #{+}`, `:exports #{f}`. The assertion is in the HEAD index.
   - `repl.dht-test/publish-at-the-prompt-refuses-and-writes-nothing`: undefined export, undeclared free, host-module name and no key are each refused with their reason. No envelope is indexed, and only the round's own program is committed.
   - Plain level: `linker.dht-test/publish-name-derives-publishes-and-signs-and-refuses-writing-nothing` (no key and undefined export leave the store at 0 entries); `publish-test/module-from-index-…` (both tests).
9. **Republishing a name.**
   - `publish-test/republishing-a-name-retracts-then-asserts-and-the-same-manifest-writes-nothing`.
   - `linker.dht-test/republishing-a-name-resolves-by-the-snapshot-a-reader-holds`: a reader of only the new snapshot gets the new address; only the old, the old; both, the new, with no diagnostics.
   - The prompt-level flow `[:assert 1] [:retract 2] [:assert 3]` is in bullet 8's test.
10. **The fold over the snapshot set.** `linker.dht-test/the-fold-reports-what-does-not-resolve-and-refuses-ambiguity`:
    - An undeclared principal and a bad signature are each reported (`:undeclared-principal`; `:unauthenticated`/`:bad-proof`) and do not resolve.
    - Two principals on different addresses give `:ambiguous-name` naming both.
    - The same address resolves with both in provenance.
    - A retraction removes exactly its assertion.
    - Counted once across snapshots: the L2 test `an-envelope-in-two-snapshots-is-one-event`, plus bullet 9's both-snapshot case.
11. **Only HEAD and loaded index manifests are folded.** `linker.dht-test/only-head-and-loaded-indexes-are-folded`:
    - Before HEAD the snapshot set is empty.
    - HEAD alone is folded with no load.
    - An index in the local store that is not loaded is never considered, until `load-index`.
12. **Key file cases (6.5).**
    - `main-test/a-key-file-loads-the-stable-key-or-refuses-startup-with-its-reason`: absent ("does not exist"), malformed (`malformed-key`) and mismatched public key (`public-mismatch`) each refuse startup with the path and no state. The banner prints the principal and never the seed. No refusal carries the seed.
    - `main-test/keygen-writes-a-new-key-file-and-never-overwrites-one`: the seed is not in the output; a second keygen refuses "exists" and the file is unchanged.
    - `main-test/declared-principals-are-strict-hex-and-reach-the-composition`.
13. **Next sequence derived after a restart.** `repl.dht-test/the-next-sequence-is-derived-from-the-index-after-a-restart`: a new shell over the same dir and key writes `[:assert 1] [:retract 2] [:assert 3]` and resolves with no diagnostics. Plain: `publish-test/the-sequence-is-derived-from-the-publisher-s-own-envelopes`.

Banner (5.1): with `--dht-publish`, a line states that the code index and the code itself (every evaluated program's rows) are shared. Without a key, a line says publish is refused.

## Red before green

- `publish-test`: against the pre-L4 `publish.cljc` → `No such var: publish/module-from-index`.
- `linker.dht-test`: against the pre-L4 `dht.cljc` → `No such var: ld/publish-name!`.
- `index-test`: before `commit-names` there was a compile error. With `commit-names` but no row materialization: 15 failures and 1 error, including 11 row assertions, the `:materialize` stage, and the new transaction test.
- The 6 new REPL/main tests against the unchanged REPL code: 22 failures, 2 errors.
- The row-dependent mesh tests: see mutation M1.

## Mutations (each caught, then restored; restore checked with `cmp`)

| # | Mutation | Caught by |
|---|---|---|
| M1 | rows not materialized (`(when false (vm/materialize-tree! …))`) | 4 tests: rows/tree load, eight big rounds, failed row, index rows (27 failures, 1 error) |
| M2 | `snapshots` omits HEAD | `only-head-and-loaded-indexes-are-folded` (4 failures) |
| M3 | defining program = earliest instead of greatest `t` | `module-from-index-collects-defining-programs-in-t-order` |
| M4 | republish writes no retraction | 2 tests (6 failures) |
| M5 | sequence not derived (always 1) | plain sequence test and restart test (4 failures) |
| M6 | node does not declare its own principal | `publish-at-the-prompt-…` (2 failures) |

Repair, pacing and the backlog are L1 (`dao.space.dht`), unchanged here. The L4 tests above exercise them through the REPL.

## Findings for the Architect / owner

1. **Spec gap in 5.3: a derived module whose dependency was required by a separate program cannot call it on a reader.**
   - Setup: P2 typed `(require 'base)` as its own program, then `(def g (fn [] (+ (base/f) 1)))`.
   - 5.3 collects only defining programs, so the published tree never runs `(require 'base)`.
   - The install child hands the receiver only the modules it linked (`engine` `link-install`). On the reader, `(require 'app)` succeeds, but `(app/g)` raises `Unable to resolve symbol: base/f`. I observed this.
   - The manifest still declares `base` under `:requires` correctly, as bullet 8 asks.
   - I did not invent a rule. In bullet 7's test, P2 types the require and the definition as **one program** (`(require (quote base)) (def g …)`). Per 5.3 "whole programs are taken", so the require is collected and the reader evaluates `43`.
   - Decision needed: should 5.3 also collect each declared module's latest requiring program, or should the publisher prepend the requires?
2. **§9 name refusals on the DHT source.** L3 answered every unresolved name as `{:reason :absent :name n}`. L4 adds `yin.vm.linker.dht/resolve-name`: `:absent` now carries `:diagnostics` for that name, and `:ambiguous-name` carries `:addresses` and `:asserters`, per the §9 table. Existing tests are unaffected.
3. **5.1 changes an existing behaviour, as the spec mandates.** A store that refuses every write now fails at stage `:materialize` (the rows come first), not `:publish`. I updated `index-test/a-publication-failure-…` and `query-test/an-unavailable-index-refuses-the-call`. I read "the round is not reported indexed" as: a `:materialize` failure in a step skips that round's publication, so no HEAD or announcement names rows that are not held.
4. **HEAD comes from disk.** `snapshots` reads `<durable-dir>/HEAD` through `dao.space.store.fs/read-file-text`, and the HEAD datoms through `dao.space.index/read-datoms` on each fold. This is derived, not cached. Its cost is proportional to index size.
5. **`yin.link` follows the other host modules.** `(require 'yin.link)` activates it, and a `(reset)` drops it, along with `dao.space.dht` (which bit a test). The help text says so.
6. **Owner-only permissions on Dart.** `dart:io` has no chmod, so the Dart keygen does not set owner-only permissions ("where the host can set that"). The JVM uses POSIX `rw-------` when supported; Node uses mode 0600.
7. **Where the 5.1 banner line lives.** It is in `yin.repl.main/banner`, not `yin.repl.dht/banner`, because `yin/repl/dht.cljc` is not on L4's file list.
8. **The answer of `(yin.link/publish …)`.** It is `{:module m :address a :links {format :ok|reason}}`, which the REPL prints. Refusals are raised as the call's error under the reason keyword.

## Not done / not run

- Nothing skipped. No sandbox blocked a lane. `clojure -M:kondo` needed approval, so kondo ran as the documented `clj -M:kondo --lint …`.
- Scratch files were used only under git-ignored `target/l4/`, plus a temporary `test/l4_scratch_test.clj`, which I deleted. `git status` shows only the 15 intended files.

## Fix round 1

Response to `collab/1790834000000-architect-linker-L4-signoff.gpt-6-sol.findings.md` (gpt-6-sol, sign-off withheld). All four items are done, the files touched are the same 15, and nothing is staged or committed.

### Lanes (all run in the foreground to their verdict)

| Lane | Result |
|---|---|
| `clj -M:kondo --lint` (all 13 changed src/test files) | 0 errors, 0 warnings |
| `bb test:clj` | 2685 tests, 224140 assertions, **0 failures, 0 errors** |
| `bb test:cljs` | 2599 tests, 90262 assertions, **0 failures, 0 errors**; build has 0 warnings |
| `bb build:yin-repl-peer` | built; no warnings from the changed files |
| `bb test:cljd` (after `rm -rf test/cljd-out`) | `+2554 … All tests passed!`; the new tests are in the regenerated `test/cljd-out` |

### 1. BLOCKING, §5.3 gap: fixed with option (a), in L4

- **Docs.**
  - The Architect's "Closure by linked requirements" paragraph is now in `yin.vm.linker.dht.md` §5.3, verbatim, right after "Closure by definitions".
  - §9 lists `:yin.link.publish/missing-require-program` with `:module`.
- **Code (`publish.cljc`).**
  - `requirements` scans a program for module-level `(require 'm)`: the operator is the variable `require` and the one operand is a literal symbol, using the same module-level rule as `definitions`.
  - In `module-from-index`, each linked module declared by a collected program's free qualified name adds the latest requiring program (greatest `t`).
  - Definition and requirement collection repeat together to a fixed point.
  - Every collected program is sequenced once, in ascending `t`.
  - If no indexed program requires a linked module, publication is refused with `{:status :refused :reason :yin.link.publish/missing-require-program :module m}`.
- **Tests.**
  - `publish-test/module-from-index-collects-the-program-requiring-each-linked-module`: a separately typed `(require (quote base))` is collected once, the tree's defs come in `t` order, and without it the publish is refused, naming `base`.
  - `repl.dht-test/a-two-principal-dependency-evaluates-or-raises-dependency-binding` now has P2 type `(require (quote base))` as **its own program**, then `(def g …)`. The reader's `(app/g)` evaluates to `43` at the prompt, and the `:absent`, `:mismatch` and `:ambiguous` cases are unchanged.
- **Red before green.** The plain test failed: the tree had no `require` and the refusal was not raised. The REPL test failed with `Error: Unable to resolve symbol: base/f in this context`.
- **Consequence.** The collected require program makes `require` a free primitive. It is now declared under `:yin.module/primitives`, as L2's own `app` fixture declares it. Two expectations moved from `#{+}` to `#{+ require}`: `publish-test/module-from-index-collects-defining-programs-in-t-order` and `repl.dht-test/publish-at-the-prompt-…`.

### 2. MODERATE, recovery after a `:materialize` failure: defined, implemented, documented

- **Rule.**
  - A committed program's row set joins the indexer's `:unwritten` set.
  - Every round that commits, and every name transaction (`commit-names`), writes the unwritten row sets first, oldest first, stopping at the first failure.
  - It publishes only when none remain. So no HEAD write and no announcement is made while a committed program's row is missing.
  - Once everything is written, the `:materialize` failure is cleared and the publication covers every committed transaction. The round prints "Note: the code index caught up: rows of earlier rounds are now written, and this round is published".
  - `:unwritten` is process state: `carry-over` keeps it across `(reset)` over a durable store, not across a restart. The key is removed when the set is empty, so the "no earlier publication retained" invariant holds unchanged.
- **Docs.** §5.1 has a "Recovery" bullet, and `yin.repl.dao.space-index.md` (rows paragraph) states the same rule.
- **Test.** `index-test/rows-a-round-could-not-write-are-written-before-any-later-head-moves`:
  - The store refuses writes for two rounds: both rounds report `materialize failed`, HEAD is never written, and 2 transactions are committed.
  - After healing, round 3 holds every row of all three programs, makes exactly one HEAD move whose index holds all 3 program roots, reports `published? true` and no failure, and prints the caught-up note.
- **Red before green.** Before the fix, round 3 moved HEAD with rounds 1–2's rows missing (the reviewer's scenario). 3 assertions failed.

### 3. MODERATE, Dart key-file permissions: documented, and warned at keygen

- **§6.5 states:**
  - The JVM creates the file `rw-------` on POSIX and Node uses `0600`.
  - Dart cannot set permissions: `dart:io` has no chmod.
  - The operator must `chmod 600` the file at once, or create it under `umask 077` or in an owner-only directory. The same applies wherever the file system ignores POSIX modes.
  - Anyone who can read the file holds the principal.
- **Code.** On Dart, `--dht-keygen` adds the line "dht: WARNING: this host cannot restrict the key file's permissions … Restrict it now: chmod 600 <path>". The seed still appears in no output.
- **Test.** `main-test/keygen-writes-a-new-key-file-and-never-overwrites-one` asserts the warning on Dart only, and its absence on the JVM and Node. It passed on all three lanes. It also still asserts that no line contains the seed.

### 4. LOW, per-fold HEAD read: recorded as a limit

§11 has a "Cost of one fold" row, marked **Performance limit**:
- `names`, `resolve-name` and `dependency-bindings` read `<dir>/HEAD` and rebuild the HEAD index's datoms on every fold.
- Every resolved `require` folds once, so cost grows with index size.
- No cache is kept; one is admissible only if it is invalidated on every HEAD change and every index load.

### Mutations (each caught, then restored and checked with `cmp`)

| # | Mutation | Caught by |
|---|---|---|
| M7 | requiring programs not collected | `module-from-index-collects-the-program-requiring-each-linked-module` and the two-principal REPL test (`(app/g)` fails again): 2 failures |
| M8 | `missing-require-program` never raised | the plain refusal assertion: 1 failure |
| M9 | publish even with rows unwritten (the reviewer's bug) | `rows-a-round-could-not-write-…`: 4 failures (HEAD moved early; failure not cleared; no caught-up note) |
| M10 | the permission warning emitted on non-Dart hosts | the keygen test: 1 failure |

### Accepted rulings, no change needed

The Architect accepted the §9 name-refusal shapes, the `:materialize` stage, the plain publish path, the fold scope, the `(reset)` behaviour and the banner location as written in the original report.
