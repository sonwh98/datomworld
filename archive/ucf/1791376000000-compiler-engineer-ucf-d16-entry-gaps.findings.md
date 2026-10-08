Completed-GMT: 2026-10-07 14:05:06 GMT
Completed-Local: 2026-10-07 21:05:06 +07
Coding-Agent: claude-opus-5-5 (Claude Code)

# UCF D16 entry gaps: test-only closure of the D10b-B sign-off's F3 rows

Worktree `/Users/sto/workspace/datomworld-d10b`, branch `ucf-d15a-driver-split`.
TEST-ONLY. No production file changed. No git writes.

## Non-overlap with the in-flight D15a slice

I did not touch `src/cljc/yin/vm/ucf/holder/driver.cljc`,
`test/yin/vm/ucf/holder/driver_test.cljc` or
`docs/design/yin.vm.universal-continuation-format.md` (and not the untracked
`src/cljc/yin/vm/ucf/holder/inbox.cljc` either). None of the five closures
needed them.

Files changed by this round:

- `test/yin/vm/ucf/handoff_v2_test.cljc` (row 3)
- `test/yin/vm/ucf/handoff_v2_layout_test.cljc` (row 5)
- `test/yin/vm/ucf/handoff_v2_holder_test.cljc` (rows 9, 11, 12)
- `test/yin/vm/ucf/handoff_v2_census_test.cljc` (row 10)
- `test/resources/yin/vm/ucf/handoff-v2.txt` (new: the version-2 golden fixture)

## Closures

**(A) Row 3: explicit park identity, census and resume.** New test
`an-explicit-park-keeps-its-record-identity-and-census-then-resumes`, run in all
four profiles. For each:
- The lowered machine's active park has the source's parked id, and
  `(:parked recv)` has the source's key set.
- The active value is the record itself.
- Full census: the lowered machine is re-lifted over a second, independent
  served table. Its body must equal the source export's body once served
  stream identities and channels are blinded (`stream-blind`). This covers
  cells, parked records, store and code.
- The controlled `:vm/resume` (`:val "back"`) runs on both source and receiver.
  Both must halt, and the results must be equal and equal to `"back"`.
- Stack and register use a local `run-beside` helper. It attaches the resume
  program's image to the current layout and starts there, because `load-image`
  would replace the layout and orphan the parked record's rows. This is the same
  pattern as `an-empty-base-is-the-first-layout-entry`.

**(B) Row 5: stale layout replaced.** The vestigial `poisoned` binding and the
`(is (some? poisoned))` assertion are gone.
- A new `stale-receiver` builds a stack or register receiver whose own layout is
  `[C A]`: module C's image is loaded, then the root image A is attached. The
  test asserts it really is `[c a]`, two of the body's own images out of order.
- The grown `[A B C]` body is lowered into that receiver with
  `handoff/resume-task`.
- Assertions on the result: layout equals the source layout; `:hash` equals the
  source's (nothing of the stale space is concatenated beside it); `:images`
  offset table equals the source's.
- The task then runs on with the same blocked state and value as the source's
  reference run.

**(C) Row 9: `:link-cursor-not-installed` in the v2 holds matrix.** Added as the
fifth hold. It turns wait 0 into a `:link-request` entry without `:cursor`,
matching `export.cljc:86-88` and the lift's `unliftable-hold`. The existing
matrix test checks it in every profile, in both root and install child:
- `export/enter` refuses `:yin.k/non-portable` naming the hold and the path,
  with no machine.
- The version-2 `export-task` refuses with the same hold.

All five hold kinds are now covered.

**(D) Row 10: golden v2 bytes, and carriers in a code row.**
- New `numeric-carrier-classes-survive-a-code-row`, run in all four profiles.
  The task blocks before a literal `[float64 1.0, float64 -0.0, 1]` has run, so
  the carriers exist only in the code.
  - The decoded body's `:yin.k/code` contains both carriers.
  - Carriers are compared by canonical CBOR hex, never by host `=`, and -0.0 is
    shown to differ from +0.0.
  - After lower and one read, the resumed value keeps both carriers and the
    integer, also compared by hex.
- New `a-version-2-body-is-the-same-bytes-on-every-host` pins one body (the stack
  profile's float-literal task) in `test/resources/yin/vm/ucf/handoff-v2.txt`.
  The file uses the `scalars-v1.txt` layout: name, segment address, then hex in
  64-digit lines. The test asserts:
  - this host's lift equals the pinned address and bytes;
  - the pinned bytes decode as version 2 with the -0.0 carrier in the code;
  - the pinned bytes lower on this host, and the run yields the carriers.
- On an address mismatch the failure message prints the regenerated file text.
- Making the bytes deterministic took two changes:
  - The made stream is a `lift-support/one-slot-stream "made-1"`. The default
    ring buffer writes a random UUID identity into the cursor position.
  - Served identities come from a local `pinned-server` ("s0", "s1", ...) rather
    than `v2-support/server`'s gensym.
- Two separate JVM processes produced identical bytes, and the Node lane matched
  them. Dart was not run this round (not requested); the test is portable
  `.cljc` and reads the file through `fx/read-path`, as the v1 pins do.

**(E) Row 11: recovery snapshot.** `a-version-2-recovery-snapshot-freezes-and-rehydrates-fenced`
now covers two sources × fork/exclusive × four profiles:
- a reader parked on a wait (cells);
- an explicit park whose record holds a cursor (parked record).

What changed:
- **Fresh process.** Documented in-process equivalence, in a comment above the
  test, because a real fresh process does not port across the three hosts.
  - The frozen object crosses only as text: hex of its bytes and the address
    string.
  - The receiver is a new machine sharing nothing with the source.
  - Its only capability is `attach` by descriptor identity, which is what a
    fresh process's transport would offer.
  - `rehydrate-fenced` reads nothing else.
- **Zero program IO.**
  - `serve!` is counted, and no serve happens after `prepare`, so freeze and
    rehydrate serve nothing.
  - The task's stream is observed at positions 0-2 before enter and after
    rehydrate, and the reads are identical.
- **Full contents.**
  - Each wait resolves through its own machine's resources to
    `[reason, stream handle, cell position, entry keys minus :yin.k/issue]`,
    and the source and restored lists are equal.
  - Parked records are compared whole after resolving each stream or cursor ref
    to the handle and position it names. Raw `=` cannot work here: the receiver
    mints its own resource ids and seals (`:yin.k/k-6` vs `:stream-0`), and that
    is the only difference I saw.
  - Non-vacuity guards check that the restored handles and positions are
    non-nil, and that the explicit-park record really carries a resolved cursor.

**Row-12 nuance: the receiver's own code in the poison set.** In
`a-poisoned-receiver-behaves-as-a-clean-one`:
- The poisoned receiver holds its own loaded program (`lit 99`). Under stack and
  register it also has a second image grown onto its layout (`lit 77`, so the
  stale layout has two entries).
- The lowered machine's code is checked on the keys `:code :code-aliases :rows
  :row-index :row-nodes :segment :images :hash`:
  - it differs from the poisoned receiver's code;
  - it equals a clean receiver's lowered code;
  - for stack and register, `:images` and `:hash` equal the source's.

## Lane results (exact counts)

JVM, all ten v2 suites in one run (`clojure -M:test -n yin.vm.ucf.handoff-v2-test -n …-authority… -n …-walker-test`):
**Ran 45 tests containing 1956 assertions. 0 failures, 0 errors.**

Per-suite JVM runs while iterating, final versions:

| suite | tests | assertions |
|---|---|---|
| handoff-v2-test | 10 | 276 |
| handoff-v2-layout-test | 4 | 90 |
| handoff-v2-holder-test | 6 | 658 |
| handoff-v2-census-test | 5 | 163 |
| handoff-v2-walker-test | 2 | 58 (unchanged) |

Node, v2 shard. Built with
`mise exec java@21 -- clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge collab/d16-v2-node.edn`
(ns-regexp `yin\.vm\.ucf\.handoff-v2-.*test$`, separate output file). All ten
suites ran: **Ran 45 tests containing 1956 assertions. 0 failures, 0 errors.**

Node, full fast lane (same `mise exec java@21` form):
**Ran 3509 tests containing 102692 assertions. 3 failures, 0 errors.**
All three failures are outside this round's files:
- `yin/repl/store_test.cljc:704` and `:709`
  (`two-worker-threads-of-one-process-never-both-own-the-directory`), 2
  assertions.
- `yin/vm/ucf/holder/driver_test.cljc:918`
  (`post-cycle-inbox-refusal-survives-result-normalization-test`; expected
  `:yin.k/unsatisfied`, got `:yin.k/ok`). This is the D15a session's in-flight
  file, and I did not touch it.

kondo (`clj -M:kondo --lint` on the four edited test files): 0 errors, 0 warnings.

## Environment notes

- In this shell a bare `clj` resolves Java 17. shadow's closure compiler needs
  21, so the first Node compile died with `UnsupportedClassVersionError`
  (class file 65.0 vs 61.0). `mise exec java@21 -- …` fixed it. I could not run
  `mise trust` (needs approval), which is probably why the worktree's `mise.toml`
  (java 21 first) is not active in this shell.
- Scratch outputs left in `collab/` (never committed): `d16-node-full.log`,
  `d16-node-v2.log`, `d16-v2-node.edn`. The shard build also wrote
  `target/node-tests-d16-v2.js`.

## Production observations

None. Every new assertion passed against the current production code, with no
production change needed.
