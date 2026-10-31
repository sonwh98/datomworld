# Engineer report — linker over dao.jing.dht, slice L3

- Brief: collab/1790825000000-engineer-linker-L3.prompt.md
- Worktree: /Users/sto/workspace/datomworld-linker-l2, branch linker-l3 (from 79c1e55d). Nothing staged or committed.
- Contract: docs/design/yin.vm.linker.dht.md §12 L3, with §4.1, §4.3, §7.4, §8.2–8.4, §9, §10.
- Model: claude-opus-5-5.

## Files touched (only the L3 list)

| File | Change |
|---|---|
| `src/cljc/yin/repl/link.cljc` | `composition` takes `:dht?`. It answers `{:kind :dht}` and refuses `:dht?` together with `:content-store` or `:content-client`. New `dht-attempt`: no record starts `linker.dht/load-module` and pends; `:loading` pends; `:failed` refuses in its §9 shape and `dao.space.dht/forget`s; `:loaded` runs `linker.dht/dependency-bindings` (the first non-`:ok` refuses `:yin.link.dht/dependency-binding` with the 7.4 entry), then `dht-link`. `dht-link` is `link-manifest` over `yin.vm.linker/local-runtime` on `(dao.space.dht/store node)`, `:derivation :verifying`, `:defer-discharge true`; an `:absent` refusal becomes `:yin.link.dht/closure-incomplete`. Names resolve through `yin.vm.linker.dht/names` with `{:name-env ...}` (direct addresses in this slice). `serve` takes and returns `:dht`, and a pending entry names its manifest under `:manifest`. The `:local` and `:remote` attempts and `attempt-budget` are unchanged. |
| `src/cljc/yin/repl.cljc` | `create-state` composes the link source before opening the store, so a refused composition never holds the directory lock. `drive-links` threads the node through the link serve, then the query serve. `link-raise` carries `::dht` and `::link-pair`; both catch sites restore them through `carry-served`. New public `recheck-on-load-events`. `help-text` gains the require line. |
| `src/cljc/yin/repl/main.cljc` | `step-all` calls `shell/recheck-on-load-events` with this tick's node events, before the driver's typed lines. Its text prints after the node's lines. |
| `src/cljc/yin/repl/dht.cljc` | `step` answers `[shell lines events]`, so existing two-element destructuring still works. `banner` always adds: "a (require ...) of a module this node does not hold may fetch its code from peers …". |
| `src/cljc/yin/repl/query.cljc` | The `dao.space.dht` host module gains `load-module` and `module-status`. Each is argument checking plus one call into `yin.vm.linker.dht/load-module` or `module-status`; the status is answered without `:value`. |
| `test/yin/repl/dht_test.cljc` | 13 new tests (L3 section) over the mesh seam. |
| `test/yin/repl/require_test.cljc` | 1 new test: composition exclusivity and the unchanged content budget. |

## Acceptance → evidence

All tests named below are in `test/yin/repl/dht_test.cljc` unless marked `require_test`. All pass on the JVM, Node and Dart (lane results at the end).

1. **`yin.repl.link` builds its DHT-source link runtime with `yin.vm.linker/local-runtime`; the `:content-store`/`:content-client` attempt budget is unchanged.**
   - Code: `dht-link` calls `linker/local-runtime` directly.
   - `attempt-runtime`/`attempt` are untouched.
   - `require_test` `a-dht-source-composes-alone-and-the-content-budget-is-unchanged-test` asserts `attempt-budget` = 64, and that a content-client link still pends with no `:manifest`.
   - Every pre-existing require-test still passes.
2. **A require whose module a peer holds parks, and completes on a later tick with no typed line; the export then answers.** `a-require-a-peer-holds-parks-and-completes-on-a-later-tick-with-no-typed-line`. Setup: a holder at port 1 plus plain peers 2 and 3, and the shell at port 4 driven only by `main/step-all`. The test asserts:
   - The require parks with `:links` naming the manifest, and the load is `:loading`.
   - `:checks` stays 0 on every tick until the end.
   - It completes with `:last-value 'mod` and a `dht: loaded <m>` line.
   - `(mod/f)` answers `"42"`.
3. **An unrelated node event causes no re-check and leaves `:checks` unchanged.** `an-unrelated-node-event-causes-no-re-check`. The run waits on a load that a silent peer keeps `:loading`. In the same ticks the node reports a publication (`dht: published`), an index load, and another module's load (`dht: loaded`). Afterwards `:checks` is still 0 and the parked VM is `identical?`.
4. **A repeated or late event for an answered link changes nothing.** In test 2:
   - `recheck-on-load-events` with the answered manifest's `:loaded` event, twice, answers the identical state and nil text.
   - With a new require parked on another load, the late event again answers the identical state and nil text.
5. **A function policy that abandons at N checks counts only re-checks of this run; `:keep` never ends it.**
   - `a-function-policy-counts-only-re-checks-of-this-run`: the policy abandons at 2. The policy's views are `[0 1 0 1 2]`: run 1 reaches 1 and is abandoned; run 2 starts at 0, five node ticks add nothing, and two host re-checks end it with the policy text.
   - `a-keep-policy-never-ends-a-dht-run`: after 6 re-checks under `:keep` (`:checks` 6) the run stands. Only the load's own failure ends it, raised as `Module link refused: absent`, with no policy text.
6. **`(abandon)` during a load ends the require; the load's later completion settles no later require; a new require of the same name finds the closure `:loaded` and links.** `abandon-during-a-load-then-a-new-require-finds-it-loaded`.
   - Abandon prints "abandoned". The load runs on to `:loaded` with no pending run, no `:last-value` and no response appended.
   - The new require answers `'mod` at once with `:fetched` unchanged (no new load).
   - The abandoned id's late answer is the `{:kind :unknown ...}` diagnostic.
   - `(mod/f)` answers 42.
7. **A failed load raises the §9 refusal, and a new require starts a new load; each of the three load failures has its one shape, equal on all hosts.** The "raised data" is asserted as the refusal body the interpreter appends on the link pair, compared to exact literals. The engine raises that map, plus `:link-module`, as the require's ex-data.
   - `:absent` with `:cause`: `a-solo-node-s-require-fails-with-cause-solo-and-a-new-require-starts-a-new-load` asserts `{:status :refused :reason :absent :address a :cause :dao.jing.dht/solo}`. The record is then forgotten (`module-status` nil), and a new require pends with a new `:loading` record.
   - `:descriptor-defect` with `:code`: `a-defective-closure-raises-descriptor-defect-with-its-code` asserts `{… :reason :descriptor-defect :address bad :code :manifest-defect}`. `:detail` and `:text` are excluded from the comparison.
   - `:yin.link.dht/unaskable` with `:outcome`: `a-load-that-cannot-ask-raises-unaskable-with-the-outcome` asserts `{… :address a :outcome :request-undeliverable}`.
8. **A dependency binding that is not `:ok` raises `:yin.link.dht/dependency-binding` with the 7.4 entry, and no install child starts.** `a-dependency-binding-that-is-not-ok-raises-before-any-install`.
   - The refusal body is `{… :module app :name 'base :pinned base :binding :absent :diagnostics []}`.
   - The pair holds only the `app` request: the app's own body requires `base`, so any child would have asked. `:origins` is 0.
   - With `base` named, the same app links through two loads (app, then the child's `base`), and `app/g` answers 43.
9. **A solo node's require fails with cause `/solo` after the node's next step.** Shown by test 7's `:absent` case. Caveat: the test steps until the run ends (limit reading 1000) and does not pin the number of steps.
10. **All four VMs: the closed corpus links and answers; a failed load raises the same refusal on each; the register fallback policy behaves as composed.** `all-four-vms-link-the-closed-corpus-over-the-dht`.
    - `:ast-walker`, `:semantic`, `:stack` and `:register` each park, complete over the mesh, and answer `(mod/f)` → 42.
    - The solo failure body is equal on all four VMs.
    - Register: the REPL composes no `:fallback`. The request format is `:yin.debruijn.register`, the response is `:ok`, and no response carries `:fallback`.
11. **`(dao.space.dht/load-module m)` and `module-status` answer through the plain functions.** `load-module-and-module-status-answer-through-the-plain-functions`: `module-status` answers nil, then `load-module` answers `:loading`. After one step, `module-status` equals `(dissoc (yin.vm.linker.dht/module-status node m) :value)` with `:status :loaded`.
12. **The banner and `(help)` state that a require may fetch from peers.** `the-banner-and-help-say-a-require-may-fetch-from-peers`.

Also covered (§4.1): `a-dht-store-refuses-a-second-content-source` checks that `create-state` with a `dht:` spec plus `:content-store` is refused, and that the directory is not left locked.

## TDD: red, then green

- **Red** (JVM, `-n yin.repl.dht-test -n yin.repl.require-test`, log in `target/l3-red-jvm.txt`). Tests compiled against a no-op `recheck-on-load-events` stub. 50 tests ran; every new test failed or errored except `a-function-policy-counts-only-re-checks-of-this-run`. That one passed in red because, before an event-driven re-check exists, it reduces to existing M5 behaviour; mutation M1 below is the one that covers its DHT-specific claim.
- **Green** (same namespaces): 50 tests, 0 failures. One real fix was needed along the way: the L2 `ct/app-ast` never requires `base`, so the dependency case now publishes an app whose body does `(require 'base)`.

Mutations: each was applied, the targeted tests failed, and the mutation was reverted. I grepped to confirm none remain.

| # | Mutation | Caught by |
|---|---|---|
| M1 | Re-check on any `:loaded`/`:load-failed` event (drop the manifest filter) | unrelated-event test (`:checks` 0 → 1); late-event-while-another-waits (state not identical, text printed) |
| M2 | No event re-check at all (the red stub) | test 2 never completes, and the four-VM, failure and abandon tests also fail (red log) |
| M3 | A `:failed` record is not forgotten | solo test: record still `:failed`, a new require refuses instead of loading |
| M4 | Skip the dependency-binding check | dependency test: wrong refusal, pair shows `[app base]`, `:origins` 1 |
| M6 | Do not carry the node on a link raise | solo test: the forget is lost on rollback |

## Lanes (all run to their verdict)

- kondo on the 7 changed files: errors 0, warnings 0.
- `bb test:clj`: Ran 2659 tests, 223875 assertions, 0 failures, 0 errors. Log: `target/l3-test-clj.txt`.
- `bb test:cljs`: Ran 2573 tests, 90026 assertions, 0 failures, 0 errors. `Testing yin.repl.dht-test` and `yin.repl.require-test` both appear. Log: `target/l3-test-cljs.txt`.
- `bb build:yin-repl-peer`: built `build/yin-repl-peer`. Log: `target/l3-build-peer.txt`.
- `bb test:cljd` after `rm -rf test/cljd-out`: "+2528: All tests passed!". Log: `target/l3-test-cljd.txt`.
  - The compact reporter does not print every test name. Coverage is shown instead by the count: the compiled tree holds exactly 2528 `t_test.test(` registrations, which include the 13 new `yin.repl.dht-test` tests and the new require test.
  - Running `dart test` directly with the expanded reporter was blocked by the sandbox (approval required), so per-test names on Dart are not in a log.

## Observations for the Architect

1. **Behaviour change to M5: a link raise now carries the link pair.** A refused request is no longer re-read and re-answered after the rollback. Without this, a refused DHT require would be re-read on the next serve and would start a fresh load of the failed address. Every existing require test passes.
2. **Head-of-line on the link pair.** `serve` keeps request order with one cursor. A request abandoned while its closure is `:loading` stays unconsumed, so a later require on the same pair waits behind it until that load ends. Both outcomes then answer: the late response is skipped and the later request is served. This is bounded by load termination (per-fetch deadline or exhaustion), not by a lease. It is M5's existing ordering; I did not change it.
3. **Names are direct addresses in L3.** `names` is called with `{:name-env ...}` only, so the absent-name refusal is still `{:reason :absent :name n}` with no `:diagnostics`. L4 brings principals and the diagnostics of §9's first row.
4. **The "raised data" assertion compares the refusal body appended on the link pair**, not a captured ex-info. The shell renders errors to text, and the engine raises that body unchanged as ex-data, plus `:link-module`.
5. **`yin.repl.dht/step` now answers a third element (events).** Callers that destructure two elements are unaffected.
