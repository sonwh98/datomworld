# Engineer report: linker over dao.jing.dht, slice L2

- Model: claude-opus-5-5 (Engineer)
- Worktree: /Users/sto/workspace/datomworld-linker-l2, branch `linker-l2` at `a5e47c8d` (L0 and L1 merged).
- Nothing staged or committed.

## Files touched (only the L2 list of section 12)

| File | Change |
|---|---|
| `src/cljc/yin/vm/linker/closure.cljc` | **new**: `walk`, the 4.2 closure walker |
| `src/cljc/yin/vm/linker/dht.cljc` | **new**: `publish!`, `closure-walk`, `load-module`, `module-status`, `snapshots`, `names`, `dependency-bindings`, `link` |
| `src/cljc/yin/vm/linker/publish.cljc` | requirements are walked before any write; the minted closure is walked before any link; new public `code-formats` and `link-local` (shared by the publisher and `yin.vm.linker.dht/link`) |
| `test/yin/vm/linker/closure_test.cljc` | **new**, 8 tests; public fixtures reused by the DHT test |
| `test/yin/vm/linker/dht_test.cljc` | **new**, 7 tests over `test/dao/jing/dht/mesh.cljc` |

No other file was edited. The DHT test reuses `dao.space.dht-test/publish-datoms!` (public) to publish index snapshots. It does not edit that file.

## Acceptance bullets → evidence

All test paths below are under `test/yin/vm/linker/`.

1. **`publish-module!` walks the closure it minted and refuses unless the walk is `:complete`; a `:requires` address that holds no valid manifest is refused by that walk.**
   - `closure_test.cljc:318` `publish-refuses-unless-the-minted-closure-is-complete`: the backend loses one row's write. The result is `:yin.link.publish/incomplete-closure` with `:walk` = `:missing` naming that row.
   - `closure_test.cljc:332` `a-requirement-holding-no-valid-manifest-is-refused-by-the-walk`:
     - Forged bytes at the pinned address give `{:status :refused :reason :yin.link.publish/invalid-requirement :name 'base}` and nothing is written. The L0 shape is unchanged, so `publish_test` passes as before. The HEAD publisher threw `stored bytes do not hash` here; that was the red run.
     - A pinned closure with an absent row gives `:yin.link.publish/incomplete-requirement` with its walk, and nothing is written.

2. **Removing, in turn, the manifest, a leaf row, an interior row, each of the three records, each of the three images, and a blob of a transitively required module yields `:missing` naming exactly that address and role.**
   - `closure_test.cljc:237` `removing-any-one-blob-is-missing-naming-it`: 12 cases, each asserted as the exact map `{:outcome :missing :address :role :path}`.
   - The required module is checked twice: its manifest (role `:require`, path `[app base]`) and its tree root row (role `:row`, path `[app base]`).

3. **Each closed code of 4.2 is produced by a test, and each yields `:invalid` with that code; the load fails without retry.**
   - `closure_test.cljc:275` `each-closed-code-is-produced` covers all eight codes:
     - `:address-mismatch`: a forged row
     - `:manifest-defect`: schema 2
     - `:row-defect`: an unknown tag
     - `:record-defect`: a non-record
     - `:derivation-mismatch`: an H record from another tree
     - `:index-entry-missing`: H dropped from the index
     - `:identity-mismatch`: the index sends H to the R image
     - `:parts-limit`
   - `closure_test.cljc:288` covers each bound (`:max-parts`, `:max-depth`, `:max-bytes`).
   - `dht_test.cljc:263` `each-closed-code-fails-the-load-without-retry`: each code goes through `dao.space.dht/load`. The load is `:failed` `{::dht/failure :invalid :defect {:code c}}` with exactly one `:load-failed` event. Three further steps leave the status unchanged, emit no event and issue zero `:jing/get`.
   - In that test, `:parts-limit` uses `deep-fixture`, a tree nested past the default `:max-depth`, because `load-module` takes default bounds.

4. **A complete walk's `:requires` lists every requirement of every module in the closure with its pinned address.**
   - `closure_test.cljc:209`: top requires base and mid, and mid requires base.
   - `:requires` = `[{top base} {top mid} {mid base}]` in walk order, and `:modules` = `[top base mid]`, so base is walked once.
   - Also `closure_test.cljc:197`.

5. **`dependency-bindings` over two principals and two snapshots.** Covered by `dht_test.cljc:357`, with real Ed25519 envelopes from `yin.vm.linker.sign`, index snapshots published and loaded on a node:
   - **Matching** gives `{:module app :name base :pinned b :binding :ok}`.
     - `app` links on all four formats.
     - It runs: base's ast image runs, its `f` is bound as `base/f`, and app's run stores `g` = 43.
   - **Missing**, P1's snapshot not loaded: `:absent` with `:diagnostics []`.
   - **Missing**, P1 not declared: `:absent` with one `:undeclared-principal` diagnostic naming P1.
   - **Mismatch**: P1 retracts and re-asserts `base` at `base2` in its snapshot, giving `:mismatch :resolved base2 :asserters [P1]`.
   - **Ambiguous**: P2 also asserts `base` at `base2`, giving `:ambiguous` with both addresses and both asserters.
   - **Both principals on the pinned address**: `:ok`, and `names` provenance holds both asserters.
   - **Direct entry** `{:name-env {'base pinned}}` with no principals: `:ok`, provenance `:composition`.
   - `dht_test.cljc:435`: an envelope present in two snapshots is one event (no diagnostics when declared, one diagnostic per envelope when undeclared).

6. **Two mesh nodes: A publishes and announces, acknowledged; B loads by address; then it links on all four formats while the request ring records zero `:jing/get`, and each result is B0-equal to A's local run.**
   - `dht_test.cljc:157`. A publishes with two acknowledging peers, and `:published` reports `:acknowledged`.
   - B's `load-module` reaches `:loaded` with `:fetched` > 0 and `:blobs` equal to A's store.
   - For each of the four formats, B's `link` is ok and equals A's `link-local` under `b0/normalize`. Running both images gives B0-equal value and store.
   - B's `:jing/get` count is the same before and after all four links.

7. **Linking one format never makes a load `:loaded`.**
   - `dht_test.cljc:195`: B holds the whole closure except the H image.
   - After every step, the semantic format links from B's local store (`link-local` is `:ok`), yet `ld/link` stays `:yin.link.dht/not-loaded`.
   - The status is never `:loaded`.

8. **A missing image no peer holds ends `:failed` with the miss cause; A with `publish?` false ends B's load `/exhausted`.**
   - The missing image is the same test, `dht_test.cljc:195`: status `{:failed :reason {:miss :address H-image :cause ::jing.dht/exhausted}}`.
   - The non-publishing A is `dht_test.cljc:223`: `:miss` on the manifest with `/exhausted`, in both the status and the `:load-failed` event.

9. **`link` before `:loaded` is `:yin.link.dht/not-loaded`.**
   - `dht_test.cljc:237` checks the exact refusal with no load (`:load nil`) and while loading (`:load {:status :loading ...}`).
   - `dependency-bindings` refuses the same way.
   - After one step the same call links, with zero gets.

**Time.** Every DHT test advances only by the readings handed to `dht/step` and by mesh ticks. No clock is used.

## Tests first, red runs, and mutations

### Red runs

- **Closure tests against a stub walker** that answers nil, with the HEAD publisher: `44 failures, 1 errors` (every test failed; the error is the HEAD publisher throwing on forged requirement bytes). Log: `target/l2-red/closure-red.log`.
- **DHT tests against a stub `yin.vm.linker.dht`**: `6065 failures, 4 errors`. All 7 tests failed. Log: `target/l2-red/dht-red.log`.
  - Against the HEAD publisher this namespace does not compile at all (`No such var: publish/code-formats`).

### Mutations

`target/l2-red/mutate.py` applies each mutation to a saved copy, runs both namespaces, and restores the file. Restoration was verified with `git diff --no-index` (empty). Every mutation was killed:

| Mutation | Property | Killed by |
|---|---|---|
| M1 the image identity check always passes | image identity | `each-closed-code-is-produced`, `each-closed-code-fails-the-load-without-retry` |
| M2 row-local defect ignored | row checks | same two |
| M3 requires never recursed | transitive closure | `a-complete-walk…`, `removing-any-one-blob…`, `requires-lists…` |
| M4 record input not compared with the tree | derivation-mismatch | the two closed-code tests |
| M5 post-mint walk removed | publisher refuses incomplete | `publish-refuses-unless-the-minted-closure-is-complete` |
| M6 requirements not walked | requirement refused by walk | `a-requirement-holding-no-valid-manifest…` (error: throw) |
| M7 `link` ignores the load record | no link before `:loaded` | `link-before-loaded-is-not-loaded`, `linking-one-format-never-makes-a-load-loaded` |
| M8 pinned address not compared | `:mismatch` | `dependency-bindings-over-two-principals…` |
| M9 cross-snapshot dedupe removed | one event per envelope | `an-envelope-in-two-snapshots-is-one-event` |
| M10 direct `:name-env` ignored | direct entry wins | `dependency-bindings-over-two-principals…` |
| M11 a `:missing` walk answered as invalid, so nothing is fetched | staged fetch | the mesh load, exhausted and missing-image tests |

## Lanes, each run in the foreground to its verdict

| Lane | Result |
|---|---|
| kondo on the 5 changed files (`clj -M:kondo --lint …`) | `errors: 0, warnings: 0`. `clojure -M:kondo` was refused by the sandbox's approval gate; `clj -M:kondo` ran. |
| `bb test:clj` | `Ran 2643 tests containing 223729 assertions. 0 failures, 0 errors.` exit 0 |
| `bb test:cljs` | `Ran 2557 tests containing 89884 assertions. 0 failures, 0 errors.` exit 0; `Testing yin.vm.linker.closure-test` and `Testing yin.vm.linker.dht-test` both appear |
| `bb build:yin-repl-peer` | exit 0, `Generated: …/build/yin-repl-peer` |
| `bb test:cljd`, after `rm -rf test/cljd-out` | `+2512: All tests passed!` exit 0 |

**Dart detail.** `test/cljd-out/yin/vm/linker/closure-test_test.dart` and `dht-test_test.dart` were compiled and are in the glob the lane runs. The compact reporter overwrites its progress lines, so the log names only `dht-test/each-closed-code-fails-the-load-without-retry`. A per-test run with `dart test -r expanded` on the two files was refused by the sandbox's approval gate. Per-test Dart names are therefore not shown; the lane's overall verdict is.

## Decisions and deviations for the reviewer

1. **`snapshots` and `names` were built in L2, not L4.**
   - Section 12 lists them under L4. L2's `dependency-bindings` bullet needs the 7.3 fold over loaded snapshots, so they exist now in `yin.vm.linker.dht`.
   - **HEAD is not in the snapshot set yet.** The node exposes no current-HEAD reading, and `dao/space/dht.cljc` is not an L2 file. 7.2's HEAD member is left to L4.
2. **`snapshots` enumerates `(keys (:loads node))`.** This is a read of the node value's internal key, because `dao.space.dht` has no public "list loads" function. Each candidate is then filtered through the public `dht/loaded-datoms`. A public accessor belongs to a slice that owns `dao/space/dht.cljc`.
3. **Two publication refusal reasons were added**, beyond section 9's list:
   - `:yin.link.publish/incomplete-requirement`: a pinned closure is present but `:missing` deeper, refused before any write.
   - `:yin.link.publish/incomplete-closure`: the minted closure is not complete, refused before any link.
   - `:invalid-requirement` (L0) keeps its exact shape `{:name n}` because `publish_test` asserts it with equality.
   - An absent pinned manifest still falls through to the footprint's `:missing-requirement`, as before.
4. **`link` maps a link refusal `:absent` after `:loaded` to `:yin.link.dht/closure-incomplete`** (4.1, 9). No test reaches it, because the walker and the linker agree on the closure.
5. **Shape of the `:ok` entry.** `dependency-bindings` keeps 7.4's exact shape for `:ok`. The "both asserters" evidence is read from `names` provenance rather than added to the entry. When a direct entry produces a `:mismatch`, its `:asserters` is `[:composition]`.
6. **The authority argument is `{:principals {principal decl} :name-env {name address}}`.** `:principals` is passed to `authority/name-environment` unchanged, with the snapshot vector as `:snapshot`.
7. **A cross-snapshot "one event" is an identical `[envelope proof]` pair.** The fold already deduplicates proven envelopes by id; the dedupe here removes duplicate diagnostics.
8. **Shared fixtures.** `closure_test` makes `lit`, `def!`, `entries`, `without`, `invalid-fixtures` and `deep-fixture` public, so `dht_test` reuses them.
9. **`app-ast` calls `base/f` at definition time** (`(yin/def g (+ (base/f) 1))`), so "app runs" has a checkable value (43) without the L3 install flow.

**Invariants.**
- `yin.vm.linker.dht` requires nothing under `yin.repl`.
- The walker is pure, with no stream operation.
- `dao.space.dht` learns nothing of `yin.*`: the walk is an argument.
- No crypto is implemented here; signing and verification are `yin.vm.linker.sign` (host primitives).
- In the new namespaces, `:cljd` comes first in every mixed reader conditional. There is no `array-map` and no cross-namespace `#'private`.

## Fix round 1

This round answers gemini-3.1-pro-high's withheld sign-off (`collab/1790822000000-architect-linker-L2-signoff.gemini-3.1-pro-high.findings.md`).

The orchestrator authorized three extra files, and they are the only ones added this round:
- `src/cljc/dao/space/dht.cljc`
- `test/dao/space/dht_test.cljc`
- `docs/design/yin.vm.linker.dht.md`

Nothing is staged or committed.

### 1. MAJOR: the `:yin.link.dht/closure-incomplete` mapping is now tested

`test/yin/vm/linker/dht_test.cljc:270` `a-blob-lost-after-loaded-is-closure-incomplete`:
- A solo node's local store is wrapped by `hiding`. It answers not-found for any address the test adds to a set, which simulates a blob lost after the walk.
- The closure loads to `:loaded`, and the semantic link is ok.
- The test then hides the semantic image. `ld/link` answers exactly `{:status :refused :reason :yin.link.dht/closure-incomplete :address <image>}`.
- The load record stays `:loaded`, so there is no silent retry.

**Mutation F1.** Replacing `(= :absent (:reason res))` with `false` in `yin.vm.linker.dht/link` fails this test: `1 failures`, in `a-blob-lost-after-loaded-is-closure-incomplete`.

**No red run against unchanged code.** The mapping already existed, so this test passed when first written. The mutation is its failing demonstration.

### 2. MINOR: a public accessor `dao.space.dht/loaded-indexes`

- **Accessor:** `src/cljc/dao/space/dht.cljc:1186`. It answers the covered-index manifests whose load is `:loaded`, as a vector sorted by address text.
- **Caller:** `yin.vm.linker.dht/snapshots` now calls it. Nothing outside `dao.space.dht` reads `(:loads node)` any more.
- **Doc:** it is listed in §10 (`docs/design/yin.vm.linker.dht.md:1111`).

**Test.** `test/dao/space/dht_test.cljc:420` `loaded-indexes-lists-only-loaded-index-manifests`:
- While everything is loading, the list is `[]`.
- After one step it lists exactly the two loaded indexes, sorted. A loaded load of another kind and a failed index load are excluded.
- After `forget`, that index is no longer listed.

**Red.** Before the accessor existed the namespace failed to compile: `No such var: dht/loaded-indexes`.

**Mutation F2.** Dropping the `loaded-datoms` filter, so every load is listed, gives `4 failures` in two tests:
- `loaded-indexes-lists-only-loaded-index-manifests`
- `an-envelope-in-two-snapshots-is-one-event` (through `snapshots`)

**Restore check.** Both mutated files were restored, and `git diff --no-index` against the saved copies is empty.

### 3. MINOR: §9 amended

`docs/design/yin.vm.linker.dht.md` §9 now lists the three closure refusals, each with its shape `{:status :refused :reason r ...}`, its carried data and when it is raised:

| `:reason` | Carried data | When it is raised |
|---|---|---|
| `:yin.link.publish/invalid-requirement` | `:name` | The walk of a pinned manifest answers `:invalid`. Raised before any write. |
| `:yin.link.publish/incomplete-requirement` | `:name`, `:walk` | A pinned manifest is present but `:missing` beyond it. Raised before any write. An absent pinned manifest stays `/missing-requirement`. |
| `:yin.link.publish/incomplete-closure` | `:address`, `:walk` | The minted closure is not `:complete`. Raised after the writes and before any link. |

### Lanes, each run in the foreground to its verdict

| Lane | Result |
|---|---|
| kondo on the 7 changed Clojure files | `errors: 0, warnings: 0` |
| `bb test:clj` | `Ran 2645 tests containing 223746 assertions. 0 failures, 0 errors.` exit 0 |
| `bb test:cljs` | `Ran 2559 tests containing 89896 assertions. 0 failures, 0 errors.` exit 0; `Testing dao.space.dht-test` and `Testing yin.vm.linker.dht-test` both appear |
| `bb build:yin-repl-peer` | exit 0, `Generated: …/build/yin-repl-peer` |
| `bb test:cljd`, after `rm -rf test/cljd-out` | `+2514: All tests passed!` exit 0 (two tests more than round 0) |
