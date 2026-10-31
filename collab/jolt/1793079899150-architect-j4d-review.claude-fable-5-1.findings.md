Created-GMT: 2026-10-27 03:40:00 GMT
Created-Local: 2026-10-27 10:40:00 +07
Coding-Agent: claude
Role: Lead System Architect (technical review, Claude half; a non-Claude co-sign (argon or deepseek) is still required before SIGNED OFF)
Author: claude-fable-5-1
Brief: collab/1793079899150-architect-j4d-review.claude-fable-5-1.prompt.md
Under review: branch `jolt-j4d` at f35df68a (six commits on merge-base 671cd21f); master at 64a80dce
Authorities re-read: design collab/1792769713135 §5 (J4d row), §6 and the 42-entry appendix; design review collab/1792791000000 F4 (private = key files, not HEAD), F6 (the J3/J4 label drift), F7 (falsifiable lanes); spec docs/design/yin.repl-jolt-implementation.md §5 Files/Signing/Main rows and §10.1; implementer findings collab/1793027866046-engineer-j4d-local-persistence.claude-opus-5-5.findings.md (read in full)

# Verdict: APPROVE

Every one of the eight review tasks checks out against the code and the
logs. No change to the branch is required. Two merge-time notes (the spec
§10.1 status line, and the separable http-test exclusion, which now merges
clean) and one accounting correction for the findings record are listed
below; none touches `src/`, `native/` or a gate. The co-signer's open item
is V1: this seat could not execute the lanes.

What I verified myself versus by citation:

- Read every changed source file in full: `jolt_file.c` (layout 3 hunk),
  `file.jolt` (`write-new-from!`, `write-new-private!`, `classify`,
  `read-all`), `store.cljc`, `state.cljc`, `main.cljc` (keygen arm, `startup`,
  the jolt `-main`, `run-owner!`), `jolt_j2_lane.clj`, the manifest, the
  ledger, the spec and README hunks, `probes/run.clj` and `probes/j4d/
  keygen.clj`, and the four test diffs.
- Ran the verbatim-restore check independently: `git diff -w 0c2bb870^
  origin/jolt-j4d` on `store_test`, `index_test`, `state_test` and `git diff
  66c34bfa5^ origin/jolt-j4d` on `head_test`. `index_test` and `head_test`
  are byte-identical to pre-guard. `store_test` differs only by the added
  `:jolt` second-process arm (49 lines). `state_test` differs only by the
  J7 re-guard of `dht-join-saves-…`. The implementer's claim holds exactly.
- Ran `git merge-tree --write-tree origin/master origin/jolt-j4d`: clean,
  no conflict. Master (64a80dce) does not yet list `dao.stream.http-test`.
- Read the macOS logs in the worktree `.claude/worktrees/jolt-j4d/build/`:
  `probe-darwin-final.log` has 93 `PASS` lines, 0 `FAIL`, 0 `PARTIAL`,
  ends `ALL PASS`, and carries all nine J4d rows (keygen mode `"600"`,
  the second keygen's refusal text, `saved-1` naming `file:…/state-store`,
  `saved-3` = `{:version 1 :config {}}`, three cross-host rows with two
  `#{[…]}` answers each). `lane-darwin.log` ends `CORE-SUMMARY {:namespaces
  18 :tests 282 :assertions 2155 :pass 2151 :fail 4 :error 0}` with exactly
  24 `EXCLUDED-TEST` rows, none `J4`. The per-suite logs in `j4d-logs/`
  list every restored var with its own `ok` line (store 27/132, state
  13/98, index 14/140, main 29/276, head 9/181, sign 4/39); `jvm.log`
  reads 112 tests / 956 assertions / 0 failures.
- **Not re-run by me (V1):** `bb probe:jolt-file` and the jolt lanes. This
  seat is non-interactive; `bb` is not on the shell's PATH and every form
  of launching it through the mise shim needs an approval that cannot be
  given here. The gate evidence is the implementer's logs read against the
  gate code, not an independent execution.
- **Linux (shiva) is by citation only**, as in J4b/J4c. The addendum's two
  pre-existing failure groups match master (the `yin.repl-test` reader row,
  the `yin.vm.data-test` unpaired-surrogate errors from 814ace57).

## 1. The narrowed refusal (task 1): VERIFIED

- `parse-arg` (store.cljc:46–) no longer carries a `:jolt` guard: `file:<dir>`
  and `dht:<dir>` parse to their specs on jolt exactly as on the other hosts.
- `checked-spec` (store.cljc:156–159) refuses only `{:type :dht}` with "the
  dht: index store arrives at J7 on jolt"; a `:file` spec falls through to the
  portable validation and `open` reaches `durable/open`.
- Every flag parses and validates before composition: `parse-args` runs
  before `checked-spec`, so numeric/port/principal/token refusals come first.
  The jolt main-test log shows `an-oversized-number-is-a-refusal-on-every-
  numeric-flag` at 20/20 assertions: its ten rows each assert the numeric
  refusal text and `nil` state, and seven of the ten carry a `dht:` store
  (five through `base`, two through `dht join`, which expands to
  `--index-store dht:…` at main.cljc:447). Under the old parse-time guard
  those seven would have answered the J4 message instead of the numeric
  one. **The "7 failed assertions" claim is confirmed by reading**, and the
  20/20 jolt result confirms the fix.
- Only composing a `dht:` store answers the J7 message: the ten re-guarded
  tests each compose one (`key-start` at main_test.cljc:399 composes
  `dht:<dir>`; the dht-join/init/serve tests expand to it; the hydrating
  test opens one). See N1 for the one precision note on where the guard
  sits relative to `dht-refusal`.

## 2. The key files (task 2): VERIFIED

- `jolt_file_open_private`: `O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC`, mode
  `0600`, through the existing `open_flags` (EINTR retried, errno captured).
  A dangling symlink or any existing entry is `EEXIST` (POSIX `O_EXCL`),
  never followed and never overwritten. F4 honoured: HEAD's `open_excl`
  stays `0666`.
- `write-new-private!` answers `:exists` on `-EEXIST`, throws `fs-error`
  on any other errno, otherwise writes whole, fsyncs and closes checked via
  the shared `write-new-from!` (the HEAD temp and the key file share it;
  `write-new-synced!` is a pure refactor, behaviour unchanged).
- `write-new-file!`'s `:jolt` arm throws the existing "the file exists; a key
  file is never overwritten" refusal on `:exists`. `ensure-parent!` runs the
  `:clj` `.mkdirs` arm on jolt; the probe's keygen into a missing `keys/`
  directory passes, so the arm works.
- Probe rows: mode `600` from creation (umask 0002 on Linux still 600 by
  citation), principal printed, seed never printed, second keygen refuses
  and the file is byte-identical.
- Strict `load-key`: unchanged code (main.cljc:179–200); absent → "does not
  exist" naming the path; refused → "the key file is refused (<reason>)"
  naming the path; no branch carries file content. The new portable
  `the-key-file-read-is-strict-and-never-falls-back-test` asserts
  absent/malformed/mismatched each name the reason and the path and never
  the seed; 10/10 on jolt, +1 test on JVM/Node/Dart.
- `yin.vm.linker.sign-test` 4/39 on jolt (lane log line 67).

## 3. Saved state, index, flags (task 3): VERIFIED

- The jolt `-main` calls `(startup args {:persist? true})`, the JVM's call
  (main.cljc:1400 vs 1340); `startup`'s `node-dir`/`saved`/`save-state!`
  path is shared code, untouched.
- `state/read-text` `:jolt` arm: `classify path false` (lstat) decides
  absence; `read-all` (stat + bounded read) answers `:absent` for a dangling
  link → "is a dangling symlink", `:not-regular` → "is not a regular file",
  an unreadable file throws `fs-error`, invalid UTF-8 is refused by the
  strict `text/utf8-decode`. All four reach `load-flags`' catch and become
  "state file <path>: it cannot be read (…); --reset replaces it". Never
  skipped. The state-test log shows `an-unreadable-state-file-is-refused-
  not-skipped` 27/27, `a-dangling-symlink-…` 4/4, `a-state-path-that-
  cannot-be-read-…` 4/4.
- Round-trip, resume, `--reset`, `--no-state`: `state-round-trips-…` 5/5,
  `a-bare-start-resumes-…` 13/13 (the test body covers resume, change,
  `--no-state` not reading/writing, `--reset` saving `{}`), `a-bad-saved-
  state-or-a-clashing-flag-…` 6/6; and the four probe rows through the real
  jolt `-main` (saved, resumed with the previous run's facts, reset forgets
  with mem store, no-state leaves `state.edn` byte-equal).
- Index reopen: `a-durable-round-writes-head-only-after-the-manifest-read-
  back` 11/11 on jolt; cross-host reopen rows JVM/Node/Dart ↔ jolt all
  PASS with two answers each.

## 4. The 42-entry retirement (task 4): VERIFIED, with one accounting note

- Manifest: all 42 appendix entries plus `seq-of-across-head-moves` removed;
  the branch manifest has 24 rows (J3 2, J3/J6 host-expectation 1, J5 5,
  J6 6, J7 11, reader 1), zero `:owner "J4"`, matching the lane's 24
  `EXCLUDED-TEST` lines.
- Per-var evidence: every restored var that has a jolt body appears with
  its own `ok` line in `j4d-logs/`. The 18 J4/J7 CLI guards split as
  claimed: the 8 rejoined vars all pass on jolt (listed individually in the
  main-test log, including the ^:slow release test 8/8 and the exit-path
  test 4/4); the 10 re-guarded vars carry the honest J7 reason in both the
  source guard and the manifest row, and each demonstrably composes a
  `dht:` store.
- Store suite accounting (N2): of the 30 retired store entries, 24 ran on
  jolt (the JVM-process test through its new `:jolt` arm), and **six never
  had a jolt body**: five are `:cljs`-only (`a-live-foreign-owner-…`,
  `a-concurrent-takeover-…`, `a-takeover-that-crashed-midway-…`,
  `two-worker-threads-…`, `a-short-write-never-becomes-a-partial-head`) and
  one is `:cljd`-only (`a-real-second-dart-process-…`). The findings say
  "3 Node-only". Retiring those six manifest rows is still correct (a row
  for a var that does not exist on jolt was never an exclusion), and their
  semantics are covered on jolt by J4b's gates (jolt↔jolt/JVM/Dart
  contention and kill-recovery; HEAD short-write cuts in `fs_gates.clj`).
  The record should say 5 + 1, not 3.
- F6 reconciled at all three sites: `:277` → "J3: JVM subprocess fixture…"
  (manifest J3 unchanged); `:611` → "J6: JVM WebSocket peer processes…"
  (five J6 rows unchanged); `:1926` → "J3: the JVM poll loop; its
  durable-journal half runs on jolt in the-exit-path-… (J4d)", manifest
  row `J3/J4` → `J3`, capability "JVM loop". Matches the F6 disposition.

## 5. Custody shutdown (task 5): VERIFIED

`run-owner!` (shared by JVM and jolt) drains, then calls `close-index-store!`
before `exit!` (main.cljc:1295–1299); the jolt `-main` passes `(fn [status]
(flush) (System/exit status))` as `exit!`. `the-exit-path-closes-the-
custody-journals-before-the-host-exits-test` reopens both journal
directories after `close-index-store!` and passes 4/4 on jolt. EOF: the
jolt `line!` deposits `(quit)` on nil and latches `eof?`, so a pending
custody write is drained by `drain-owner!` before exit; J3 code, unchanged.

## 6. No shared-code change (task 6): VERIFIED

`git diff 671cd21f..origin/jolt-j4d --stat` lists no `dao/jing/file.cljc`,
`dao/space/store/fs.cljc` or `dao/stream/journal/file.cljc`. The only
`.cljc` edits are `main.cljc` (the `:jolt` require splice + keygen arm +
`-main` persist), `state.cljc` (require splice + `read-text` arm) and
`store.cljc` (guard removed from `parse-arg`, narrowed in `checked-spec`).
The `append-frame!` docstring addition in `file.jolt` is prose only.

## 7. The separable http-test exclusion (task 7): HONEST, merges clean

`test/dao/stream/http_test.clj` (b55fdfe1, dao.star Mode 1) requires
`dao.stream.http.jvm` (http-kit) and `java.net.http`; it cannot load on
jolt. The exclusion names reason, capability and owner `dao.star`. Unlike
J4b's C1, master does not yet list it and the merge-tree is clean, so
2c00707f may ride the J4d merge as is; the dao.star seat owns any later
reclassification. The recurrence (fourth manifest break) still argues for
the pre-commit manifest check recommended in the J4b review.

## 8. The ledger rule (task 8): VERIFIED

`known-gaps.md` diff is exactly two rows changed in place, file still 95
lines, no rewrite: row 75 closed with mechanism (`{:persist? true}`, the
`:jolt` `read-text` arm) and evidence (state-test, probe rows, both
platforms); row 64 closed citing the `Closed (J4b)` row below it under the
union rule. Row 63 (J4c closure) and the F5 mixed Node/OS-lock P2 row are
untouched.

## Findings

| # | sev | where | finding | disposition |
|---|---|---|---|---|
| M1 | merge-time | `docs/design/yin.repl-jolt-implementation.md` §10.1 J4 row | Reads "J4d on jolt-j4d, review pending" | At merge, replace with the landing commit and "J4a–J4d landed"; one-line doc edit, may be done in the merge commit |
| M2 | merge-time | 2c00707f (`src/dev/jolt_j2_lane.clj`) | Separable exclusion of `dao.stream.http-test`, owner dao.star | Merges clean against master 64a80dce; land it with the branch (or first, separately); no re-cut needed |
| N1 | note | `store.cljc:156–159` | The jolt guard precedes `dht-refusal`, so a semantically bad `dht:` spec (bad peer shape, non-boolean publish) on jolt answers the J7 message rather than its own reason; parse-level refusals (ports, principals, tokens, "need --index-store dht:<dir>") are unaffected and verified | Optional: move the guard after `dht-refusal` so the operator sees the same reason as on other hosts; no test rejoins either way, so this may wait for J7 |
| N2 | note | findings §2 retirement table | "3 Node-only bodies" undercounts: five store tests are `:cljs`-only and one `:cljd`-only; none has a jolt body | Correct the record (5 + 1); retirement itself is right, J4b gates cover their semantics on jolt |
| N3 | note | brief | F4/F6 are cited as "your J4b review"; they are in the design review collab/1792791000000 (the J4b review used C/N numbering) | Citation drift in the brief only; the implementer cited the right document |
| N4 | note | `probes/run.clj` | `probe-darwin-final.log` contains NUL bytes (the J4a NUL-path record), so `grep` without `-a` reports nothing and `Read` refuses the file | Optional: escape the NUL in the RECORD line; cosmetic for reviewers |
| N5 | note | `state.cljc:238–245` | `classify` (lstat) then `read-all` (stat) is two calls; an entry removed between them reads as a dangling link refusal rather than absence | Harmless: the window only changes which refusal is printed, never skips a file; no change |
| V1 | verification gap | this review | Probe and lanes not re-executed from this seat (toolchain launch needs approval); Linux evidence by citation | Co-signer: re-run `bb probe:jolt-file` and `bb test:jolt-core --only '^yin\.repl(\.[a-z.-]+)?-test$'` once if able; otherwise accept the logs as read here |

## Properties that pass

- P1 `file:` stores open on jolt through the shared `durable/open`; only
  `{:type :dht}` is refused, at `checked-spec`, after every flag parses.
- P2 Key files are created by one `O_CREAT|O_EXCL|O_CLOEXEC` open at 0600,
  written whole, fsynced, closed checked; an existing entry (including a
  dangling symlink) is refused and left byte-identical.
- P3 `load-key` refuses absent/malformed/mismatched files naming the path,
  never the seed; portable test green on four hosts.
- P4 Saved flags persist through the jolt `-main`; bare start resumes,
  `--reset` forgets, `--no-state` neither reads nor writes; an unreadable,
  non-regular, dangling or non-UTF-8 state file refuses startup naming it.
- P5 Cross-host reopen JVM/Node/Dart → jolt → origin host, sequential, both
  platforms (Linux by citation).
- P6 Zero `:owner "J4"` rows remain in the manifest or the J2 lane; every
  remaining exclusion names its real dependency; 24 rows = 24 lane lines.
- P7 Restored test text is byte-identical to pre-guard (modulo the `:jolt`
  arm and the J7 re-guard); JVM/Node/Dart counts move by exactly the one
  new test.
- P8 Custody journals close on the exit path before the host exits; EOF
  deposits `(quit)` and drains.
- P9 No shared-file algorithm changed; `dao.jing.file`, `fs.cljc` and the
  journal are untouched.
- P10 Ledger edited in place under the union rule; 95 lines; F5 row stands.
- P11 Layout bumped to 3; 24 exports on both platforms; an older library
  is refused at load.

## Routing for the dispatcher

- Code: approved as written. Branch f35df68a may merge once the non-Claude
  co-sign (argon or deepseek) lands, per the commissioning condition.
- M1 is a one-line doc edit at merge time; M2 rides the merge.
- With J4a–J4c landed and this gate met, the J4 closure statement (findings
  §9) stands on the technical half; it does not imply J5/J6/J7 closure.
