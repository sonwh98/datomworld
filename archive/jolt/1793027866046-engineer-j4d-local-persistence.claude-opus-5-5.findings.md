Created-GMT: 2026-10-26 16:30:00 GMT
Created-Local: 2026-10-26 23:30:00 +07
Coding-Agent: claude
Session-ID: 8f45af4f-0557-4cfa-a375-78525ee6a40e
# Findings: J4d — local REPL persistence and authority integration (closes J4)

Role: Engineer (jolt lane). Authorities:
- **Design:** collab/1792769713135 §5 (J4d row), §6 and its 42-entry appendix;
- **Reviews:** collab/1792791000000 (F7: falsifiable lanes) and the J3/J4 label drift (F6; transit-rejoin findings).

**Not signed off.** Deepseek is the assigned independent reviewer.

- **Branch:** `jolt-j4d` off master `671cd21f` (current origin/master at start; it contains the brief's `c85eccc8`). Pushed. Commits:
  - `080618d4` feat(jolt): add the owner-only key-file create to native/jolt-file (layout 3, J4d)
  - `f4ccd054` feat(yin.repl): open file: stores and persist saved flags on jolt (J4d)
  - `9b0c04b2` test(jolt): retire every J4-owned REPL applicability entry by per-var evidence (J4d). This is **the acceptance change**: tests, manifest, known-gaps, spec, README.
  - `cebffc9f` test(jolt): add the J4d gates to bb probe:jolt-file
  - `2c00707f` test(jolt): exclude dao.stream.http-test from the J2 manifest. **Separable, out of slice**; see §8.
  - `f35df68a` test(yin.repl): bind the key-file refusal text as a string in the portable load-key test. A Node-only fix found by the shiva `test:cljs` comparison; see the addendum.
- **Worktree:** `.claude/worktrees/jolt-j4d`, freshly bootstrapped (`b389f1a7`, 7 submodules verified). Linux logs are in `shiva:~/ci/datomworld-j4d-logs/` (branch) and `~/ci/datomworld-j4d-master-logs/` (baseline).

## Verdict

J4d acceptance is met on macOS arm64 and Linux x86_64, with identical counts:
- **No J4 owner remains.** No `:owner "J4"` row (or J4/J7, or J3/J4) remains in `jolt-applicability.edn` (24 rows: J3 ×2, J3/J6 host-expectation ×1, J5 ×5, J6 ×6, J7 ×11, jolt-lang reader ×1). No J4 exclusion remains in the J2 lane.
- **Every per-test exclusion is explained:** each remaining guard has a matching manifest row with its real dependency.
- **Suites:** the store, CLI, state, index and keys suites are green on jolt.
- **Key files and signing:** keygen is 0600 and refuses overwrite; signing vectors are green.
- **Cross-host reopen:** JVM, Node and Dart each write, jolt replays and appends, and the original host reopens.

With J4a–J4c landed, **this closes J4** (statement in §9). The `bb test:clj`/`test:cljs`/`test:cljd` unchanged-counts comparison and the full jolt lane are in the addendum.

## 1. Store, CLI and keys diffs

### `yin.repl.store` (f4ccd054)

- **Narrowed, not deleted** (F8/§6): the jolt refusal now covers the `dht:` store only. It **moves from `parse-arg` to `checked-spec`**, so every flag is parsed and validated as on the other hosts, and only composing a `dht:` store answers "the dht: index store arrives at J7 on jolt".
- **Evidence the move matters:** `an-oversized-number-is-a-refusal-on-every-numeric-flag` failed 7 assertions under the parse-time refusal and passes with the composition-time one.

### `yin.repl.main`

- **The jolt `-main`** calls `startup` with `{:persist? true}`, the JVM's own call. Its docstring now says saved flags persist.
- **`write-new-file!`** (keygen) gains a `:jolt` arm through `posix-file/write-new-private!`. EEXIST raises the existing "the file exists; a key file is never overwritten" refusal.
- **Requires:** `dao.host.text` and `dao.posix.file` join the `:jolt` require splice.

### `yin.repl.state`

`read-text` gains a `:jolt` arm:
- **Absence:** lstat-absent answers nil.
- **Refusals:** a dangling link ("is a dangling symlink"), a non-regular entry, an unreadable file (fs-error) or invalid UTF-8 (strict `text.jolt`) all throw. So `load-flags` refuses them with "it cannot be read", never skipping the file.
- **Why an arm:** the `:clj` arm's `Files/notExists` + `LinkOption` is not relied on.

### `native/jolt-file` layout 3 (080618d4)

- **New entries:** `jolt_file_open_private` (O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, **0600**: private from creation, the key-file requirement per F4, unlike HEAD's 0666 & ~umask) and `jolt_file_eexist` (the constant from C).
- **Exports:** 24 on both platforms, libc only. A layout-2 library is refused at load.
- **Facade:** `write-new-private!` answers `:exists` and writes nothing for an existing entry; otherwise it writes whole, fsyncs and closes checked. The HEAD temp and the key file share `write-new-from!`.
- **Fail-closed note (no code change):** `append-frame!`'s docstring now carries deepseek's J4c observation that the mark is set by any write throwable, which is broader than the persistence-uncertain criterion, conservative and safe.

## 2. Manifest retirement with per-var evidence (9b0c04b2)

**Method:**
- **Unwrapping:** each guard was unwrapped by paren matching, and **every restored deftest was checked equal to its text before the guard commit** (`0c2bb870^` for the J3 guards; `66c34bfa5^` for linker.head's, which I added in J2b).
- **Verbatim restore:** where the J3 formatter had realigned a line, the pre-guard text was taken verbatim after a whitespace-insensitive equality check.
- **Results:** `store_test`, `index_test` and `head_test` are byte-identical to pre-guard, apart from additions (the jolt second-process arm) and one formatter realignment the pre-commit hook enforced (`git diff -w` shows no removed line). `state_test` differs only in the re-guarded J7 test.

**The 42 J4-only entries (design appendix) + linker.head's `seq-of-across-head-moves`**, each run on jolt:

| suite | retired | jolt result (macOS = Linux) |
|---|---|---|
| `yin.repl.store-test` | 30 guards | 27 tests / 132 assertions, 0 fail. The 3 Node-only (`:cljs`-arm) bodies don't run on jolt; the JVM-process body's jolt equivalent is below |
| `yin.repl.state-test` | 7 of 8 | 13 / 98, 0 fail. `dht-join-saves-its-follow-and-a-bare-start-resumes-it` **reclassified J7**: it composes `dht join`, and on jolt it fails only at the J7 refusal |
| `yin.repl.index-test` | 1 | 14 / 140, 0 fail |
| `yin.repl.main-test` | 3 custody tests (drain-enters, bounded-drain, moved?) | pass (inside main's 29 / 276) |
| `yin.vm.linker.head-test` | `seq-of-across-head-moves` | 9 / 181, 0 fail: **the JVM's full 181** |

**The 18 J4/J7 CLI guards, split by dependency (run evidence):**
- **Rejoined (8), pass on jolt:**
  - `the-index-store-flag-parses-on-every-hosts-arguments`
  - `startup-composes-or-refuses-before-any-shell-or-server`
  - `keygen-writes-a-new-key-file-and-never-overwrites-one`
  - `a-token-with-a-third-part-is-refused-with-the-pin-message`
  - `dht-follow-parses-a-principal-at-a-loopback-address`
  - `an-oversized-number-is-a-refusal-on-every-numeric-flag`
  - `a-release-held-back-for-the-whole-drain-stays-durable-and-a-restart-sends-it-test` (^:slow)
  - `the-exit-path-closes-the-custody-journals-before-the-host-exits-test`
- **Re-guarded as J7 (10):**
  - **Which:** `a-key-file-loads-…`, `declared-principals-…`, `trusted-principals-…`, `dht-join-expands-…`, `a-printed-join-token-…`, `dht-follow-of-an-undeclared-principal-…`, `dht-manifest-suspends-…`, `dht-init-publishes-…`, `dht-serve-fetches-only`, `with-the-dht-store-still-hydrating-…`.
  - **Why:** each composes a `dht:` store. On jolt every failing assertion was the J7 refusal or its consequence (state nil); their parsing and refusal assertions passed.
  - **Guard comment:** "J7: composes a dht: index store, which needs the UDP transport (J4d run: fails only at that refusal)".
  - **Manifest rows:** owner `J7`.

**Jolt equivalents (host-specific fixtures, never a false claim):**
- **Store lock:**
  - **Problem:** `a-real-second-jvm-process-is-refused-and-its-death-releases` spawns `java`.
  - **Fix:** its conditional gains a `:jolt` arm, `a-real-second-jolt-process-is-refused-and-its-death-releases`. A fresh jolt runtime owns the directory, this one is refused, the child is SIGKILLed and the lock is released. It passes. The JVM test text is unchanged.
- **Key-file read:**
  - **Problem:** the key-file test reaches `load-key` through a `dht:` store (J7).
  - **Fix:** a new host-portable `the-key-file-read-is-strict-and-never-falls-back-test` calls `load-key` directly. A well-formed file is the key; absent, malformed and mismatched files refuse naming the path, never the seed. It runs on every host.

## 3. The J3/J4 label drift (F6), reconciled

| site | was | now | manifest |
|---|---|---|---|
| `main_test.cljc:277` `a-typed-quit-exits-without-waiting-for-end-of-input` | "J4/J6: JVM process/socket or durable journal fixture" | "J3: JVM subprocess fixture; the held-pipe process corpus (bb test:jolt-shell) covers jolt's typed quit" | J3 (unchanged) |
| `:611` the JVM-peer `do` block | same generic label | "J6: JVM WebSocket peer processes; portable injected endpoints remain active" | its five J6 rows (unchanged) |
| `:1926` `the-jvm-loop-drains-custody-without-an-endpoint-and-closes-its-journals-test` | same generic label | "J3: the JVM poll loop; its durable-journal half runs on jolt in the-exit-path-closes-the-custody-journals-before-the-host-exits-test (J4d)" | **J3/J4 → J3**, reason/capability "JVM loop" |

Per F6's disposition: J4d owns the durable-journal half. The exit-path test, which is portable, closes the custody journals before the host exits, and it passes on jolt. The JVM-loop half keeps its JVM test.

## 4. Per-gate results, both platforms

### `bb probe:jolt-file`

ALL PASS: **93** PASS lines on each platform (J4a/J4b/J4c's 84 unchanged, plus 9 J4d):

| J4d gate | macOS | Linux |
|---|---|---|
| keygen on jolt: a new key file, **mode 600** (parents created), principal printed, seed never printed | PASS | PASS (umask 0002, still 600) |
| a second keygen refuses ("the file exists; a key file is never overwritten"), the file byte-identical | PASS | PASS |
| jolt `-main --dir N --index-store file:S` saves `state.edn` naming the file: store | PASS | PASS |
| a bare `--dir N` resumes it ("state: resumed from"), and the file: store answers the history `q` with the previous run's facts | PASS | PASS |
| `--reset` forgets: mem store, old facts absent, the reset state saved | PASS | PASS |
| `--no-state` reads and writes nothing (`state.edn` unchanged) and the file: store opens | PASS | PASS |
| cross-host **JVM**: JVM `yin-repl` writes, jolt replays (history q finds it) and appends, JVM reopens and sees both | PASS | PASS |
| cross-host **Node** (`target/yin-repl.js`) | PASS | PASS |
| cross-host **Dart** (`clj -M:clojuredart:cljd-yin-repl-build compile` + `dart compile exe lib/cljd-out/yin/repl/main.dart -o build/yin-repl-dart`) | PASS | PASS |

- **Wrapper build digests** (layout 3): darwin `839d7acad2f393542afa4318696795f2fcb4e27c1321f4541c3c557e43cadd59`; Linux in the shiva build record.
- **Locks in the cross-host runs:** the Node runs take the claim-file lock, so these are **sequential** reopens. The mixed Node/OS-lock row is unchanged and not claimed.

### Lanes

| lane | macOS | Linux |
|---|---|---|
| `test:jolt-core --only` every `yin.repl*` suite + linker.head + linker.sign | 18 ns, 282 tests, 2,155 assertions; **4 fail: `yin.repl-test`, the known jolt-lang reader row** (`a-percent-reads-alike…`, `tagged-literals-and-reader-conditionals-refuse-alike`), not J4 | identical |
| JVM `store`, `state`, `main`, `index`, `linker.head` | 112 tests, 956 assertions, 0 failures | (in `test:clj`, addendum) |
| the lane's printed exclusions | 24 `EXCLUDED-TEST` rows (J3 2, J5 5, J6 6, J7 11), **0 J4** | identical |

## 5. Audit of adjacent helpers (§6)

Working pwrite is not proof. Each helper was observed on jolt:

| helper | jolt evidence |
|---|---|
| `dao.space.store` `dir-refusal` (`:clj` `java.io.File` arm on jolt) | Probed directly. "is not writable" for a chmod-500 directory (`.canWrite` false), "is not a directory" for a file, nested `mkdirs` creating a new directory, "cannot create" under a file: the JVM's answers. `an-unopenable-directory-is-refused-with-its-reason` passes |
| `dao.stream.journal.file/ensure-dir!` | J4c's journal suites (dao.stream.journal.file-test 11/66) and this run's custody exit-path test |
| `yin.repl.state` `mkdirs!` | `state-round-trips-through-its-file` and the probe's saved-state rows |
| `yin.repl.main` `ensure-parent!` | the probe's keygen into a missing `keys/` directory |
| `yin.repl.state` `read-text` | **replaced** by the `:jolt` arm (§1); the dangling-symlink, unreadable and refusal tests pass |
| test helpers (`temp-dir`, `cleanup-dir!`, `write-text!`, `read-text`) | every restored suite above |

## 6. Signing and keys

- **Signing vectors:** `yin.vm.linker.sign-test` is 4 / 39 on jolt on both platforms; JVM in the addendum.
- **keygen:** refuses overwrite, and mode 0600 is verified from bb on the real file.
- **Strict key read:** the portable `load-key` test.

## 7. Ledger and spec (9b0c04b2)

- **known-gaps, closed in place** (no rewrite; still 95 lines):
  - **Row 75** (flag persistence / --reset / --no-state): mechanism and evidence.
  - **Row 64** (the duplicated open fs-test row): closed citing J4b's closure row, per the merge rule (union, old schema).
- **Spec:**
  - **§10.1 J4 row:** "J4a-J4c landed (890dc68f, 9c4ce1df, f5397a15); J4d on jolt-j4d, review pending", with the J4d gate text.
  - **§5 Signing row:** O_CREAT+O_EXCL 0600 key files and strict `load-key`.
  - **§5 Main row:** saved flags through the jolt `-main`.
- **`src/jolt/README.md`:** the J4d paragraph (layout 3, 24 exports, the probe rows); the "file:/dht: refuse until J4/J7" and "saved state not enabled until J4" sentences corrected.
- **`jolt-applicability.edn`:** 24 rows, none J4; header note amended.

## 8. Master-side items met on the way

- **`dao.stream.http-test`:**
  - **What happened:** b55fdfe1 (dao.star Mode 1) added it without a manifest entry, so every jolt lane mode refused again (the fourth time a track has done this).
  - **Why excluded:** it loads `dao.stream.http.jvm` (http-kit) and cannot load on jolt.
  - **Fix:** 2c00707f excludes it, owner `dao.star`. That commit is separable.
- **Pre-existing failures carried forward:** the `yin.repl-test` reader row, and `yin.vm.data-test`'s unpaired-surrogate errors (814ace57). See the addendum for the full lane.

## 9. J4 closure statement

J4 is complete on macOS arm64 and Linux x86_64, subject to deepseek's review of J4d.

| slice | what it established |
|---|---|
| **J4a** | the content-file seams over native/jolt-file; 3 namespace exclusions and 25 guards retired |
| **J4b** | fcntl directory ownership, atomic HEAD, bounded reads; fs-test rejoined; jolt↔JVM↔Dart lock contention gated both ways |
| **J4c** | the fail-closed content-log handle, the file journal and UCF suites; 6 exclusions and the crash-cut subset retired |
| **J4d** | file: stores, saved state, key files and custody shutdown on jolt; all 42 J4-only REPL entries retired by per-var evidence, with the J4/J7 entries split |

- **Totals:** all ten whole-namespace exclusions and all seven guarded-suite groups are retired. No `:owner "J4"` entry remains anywhere.
- **Mixed-runtime lock:** the limitation is disposed of as the documented known-gaps P2 row.
- **Remaining jolt exclusions belong to other slices:** J3 (JVM-host fixtures with jolt coverage), J5 (Python), J6 (WebSocket/Transit), J7 (dht: stores and UDP).
- **Not claimed:** J5/J6/J7 closure.

## Commands

- **Setup:** `git worktree add …/jolt-j4d -b jolt-j4d origin/master`; `bb bootstrap:jolt`; `bb gen:antlr`; `npm ci`; `bb build:jolt-native`; `bb build:yin-repl-node`.
- **Dart REPL:** `clj -M:clojuredart:cljd-yin-repl-build compile`; `dart compile exe lib/cljd-out/yin/repl/main.dart -o build/yin-repl-dart`.
- **Probe:** `bb probe:jolt-file`.
- **Lanes:** `bb test:jolt-core --jobs 4 --only '^(yin\.repl(\.[a-z.-]+)?-test|yin\.vm\.linker\.head-test|yin\.vm\.linker\.sign-test)$'`; JVM `clojure -M:test -n …`.
- **Guard unwrap/re-guard tools:** `/tmp/j4d/unwrap.py` and `/tmp/j4d/reguard.py`, both verifying against the pre-guard revisions.
- **shiva:** `~/ci/run-j4d.sh` (tmux `j4d`, branch `2c00707f`) and `~/ci/run-j4d-master.sh 671cd21f` (tmux `j4dm`, baseline).

## Addendum: full lanes and the unchanged-counts comparison (shiva, Linux x86_64)

Clones: branch `~/ci/datomworld-j4d` at `2c00707f` (cljs re-run at `f35df68a`) and baseline `~/ci/datomworld-j4d-master` at the base `671cd21f`, run side by side.

| lane | baseline `671cd21f` | branch | reading |
|---|---|---|---|
| `bb test:jolt-core` full minus the 6.5 h safepoint harness | n/a | 174 ns, 3,070 tests, 210,406 assertions; 4 fail (`yin.repl-test` reader row) + 3 error (`yin.vm.data-test` unpaired-surrogate, 814ace57), both pre-existing on master as in J4b/J4c; 0 load failures, 0 timeouts | no J4d-related failure; the 4 + 3 are master-side and carried forward |
| `bb test:clj` | 4,638 tests / 296,111 assertions, 0 failures; **re-run: 4,638 / 296,119** | 4,639 / 296,130, 0 failures | +1 test, +10 assertions = the new portable key-file test (measured: the three changed files run 88/625 at the base vs 89/635 on the branch). The remaining +1..+9 is within the baseline's own run-to-run variance (296,111 vs 296,119). Same 300 namespaces, same skip notices |
| `bb test:cljs` | 4,496 / 156,120, 0 failures | first run at `2c00707f`: 4,497 / 156,130, **6 failures**, all in the new key-file test (`(str r)` of the refusal read back `""` inside cljs.test's `is` on Node only). Fixed at `f35df68a` (Node main-test 59/497/0 locally); **re-run at `f35df68a`: 4,497 / 156,133, 0 failures** | +1 test, +13 assertions = the new test on Node; 0 failures |
| `bb test:cljd` | +4,395 −1 | +4,396 −1 | +1 = the new test; the −1 on both is the pre-existing `dao.stream.datagram.dart-test/refusals-and-lifecycle-events` (no bind-failed event, timeout), master-side |

**Shared-source edits are jolt-arms-only**, except the one new portable test, so no JVM/Node/Dart behaviour changed. The J7 re-guards and the `:jolt` second-process arm are invisible to those hosts. The test text restored to pre-guard is what those hosts already ran under `:default`.

## Correction (2026-10-28, J4d fixes, kimi F2)

§2's store-test row overstated the evidence. "The 3 Node-only (`:cljs`-arm)
bodies" was wrong: `store_test.cljc` has five `:cljs`-only bodies and one
`:cljd`-only body.

The accurate accounting of the 42 entries: 34 ran under their original names
on jolt, 1 has a jolt process equivalent (the JVM process test), and 1 was
reclassified J7. That left 6 store-test vars with no per-var jolt evidence.
They are now accounted:

| var | host | on jolt |
|---|---|---|
| `two-worker-threads-of-one-process-never-both-own-the-directory` | Node | **jolt arm, original name**: two threads race `fs/lock!` 20 times; exactly one owner each round (the in-process `held` record) |
| `a-short-write-never-becomes-a-partial-head` | Node | **jolt arm, original name**: `pwrite-once` stores at most 3 bytes a call; HEAD holds the whole record |
| `a-live-foreign-owner-is-refused-and-a-dead-ones-lock-is-replaced` | Node | named equivalent: `a-real-second-jolt-process-is-refused-and-its-death-releases` |
| `a-takeover-that-crashed-midway-does-not-brick-the-directory` | Node | named equivalent: the same jolt process test (SIGKILL, then reopen) |
| `a-concurrent-takeover-of-a-dead-owners-lock-leaves-one-owner` | Node | named equivalent: the two-threads jolt arm (fcntl has no takeover; the kernel releases a dead owner's lock) |
| `a-real-second-dart-process-is-refused-and-its-death-releases` | Dart | named equivalent: the jolt process test |

The four named equivalents are recorded per var as "host fixture" rows in
`test/resources/yin/repl/jolt-applicability.edn`, each with its
`:jolt-equivalent`. The jolt store-test count rises by the two arms.
