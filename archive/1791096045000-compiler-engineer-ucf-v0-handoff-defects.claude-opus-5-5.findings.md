Completed-GMT: 2026-10-04 06:44:41 GMT
Completed-Local: 2026-10-04 13:44:41 +07 (+0700)
Coding-Agent: claude
Session-ID: ca67385c-91bf-415b-b38b-aadb290f6936

# Findings: the two version-0 handoff defects (UCF v1 amendment)

Worktree /Users/sto/workspace/datomworld-v0fix, branch ucf-v0-defects,
based on master ff1e0195. No git writes.

## Both defects are real (red first, on unchanged source)

I added two tests to test/yin/vm/ucf/handoff_test.cljc (.cljc, the
same helpers as the neighbouring tests) and ran them against the
unchanged handoff.cljc:

    clojure -M:test -n yin.vm.ucf.handoff-test
    Ran 24 tests containing 158 assertions.
    6 failures, 0 errors.

- `a-park-beside-ordered-waits-keeps-the-waits` (2 failures). The
  machine is a real one: the reader blocked at depth 1 with A
  consumed, then an explicit `:vm/park` loaded onto it. Its wait set
  has 1 entry. Export answered :ok, with kind :parked and a :next
  frame. That part was already right: export serializes the waits.
  The failures:
  `expected (= [:next] (mapv :reason (:wait-set recv)))`, actual
  `[]`. Also wait-set count parity after resume+drive:
  `(not (= 1 0))`.
- `an-install-pending-without-its-entry-refuses-before-restoration`
  (4 failures). The test strips `:yin.k/installs` from a real
  install-waiter export, or renames its entry to `host.other`. Both
  bodies lowered as `{:status :ok :kind :blocked ...}` instead of
  refusing. The attach counter was non-zero, which means streams were
  attached first.

The 22 pre-existing tests passed in that run.

## Fixes (src/cljc/yin/vm/ucf/handoff.cljc, +15 -1)

1. Parked frames. In `resume-task`, the `:parked` branch now assigns
   `:wait-set entries` instead of `[]`. These are the same lowered
   entries the `:blocked` branch uses. Nothing else in that branch
   changed: it still sets `:halted? true :blocked? false` and the
   value is the parked record. Export needed no change: it already
   serialized the frames beside the park.
2. Missing install entry. `validate-body` now walks every frame
   pending. An `:install` pending whose `:yin.k/name` has no entry in
   `:yin.k/installs` refuses with the EXISTING `:yin.k/undecodable`
   status, carrying `:yin.k/name`, `:yin.k/kind :incomplete-install`
   and `:yin.k/path [:yin.k/frames i :yin.k/pending]`. No new status
   was added. The check runs inside `validate-body`, so:
   - it applies before any attachment or restoration (the test
     asserts zero attach calls);
   - it applies recursively to child bodies;
   - it applies on the export side's self-check too. There it is
     unreachable because lift refuses first, with
     :non-portable/:incomplete-install.

Unchanged: opcode semantics, code stamps, and the wire format for
valid version-0 bodies. An export of a valid body emits the same
bytes as before. Extra install entries that no waiter names are still
accepted, as before: tightening that was out of scope.

## Green

    clojure -M:test -n yin.vm.ucf.handoff-test
    Ran 24 tests containing 158 assertions.
    0 failures, 0 errors.

    clojure -M:test -n yin.vm.ucf.remote-test -n yin.vm.ucf-test \
      -n yin.vm.linker-test
    Ran 104 tests containing 754 assertions.
    0 failures, 0 errors.

No existing assertion was changed. Only new tests and one helper
(`parked-with-a-wait`) were added.

## Docs

docs/design/yin.vm.ucf-revisions.md section 6: I added one status
sentence after the item that records the two defects. The sentence
wraps to 2 lines at 80 columns. It says both defects are fixed in
version 0, each with a red-then-green test in
yin.vm.ucf.handoff-test, and the wire is unchanged.

## Checks

- cljstyle: ran `fix`, then `check` on both .cljc files. Both passed
  clean, with no output.
- Added lines: all ASCII, and none over 80 columns.
- kondo: NOT RUN. `clojure -M:kondo --lint ...` was refused for
  approval in this session, and there is no standalone clj-kondo
  binary under mise. The orchestrator should run
  `clojure -M:kondo --lint src/cljc/yin/vm/ucf/handoff.cljc
  test/yin/vm/ucf/handoff_test.cljc`.
- Node and Dart: not run, per the brief; the orchestrator runs the
  full lanes. The new tests use only portable .cljc helpers that
  already run on all three hosts.
- No Python prelude was touched, so no address goldens moved.

## Deviations / unfinished

- kondo is unrun (above).
- The status note is one sentence but 2 physical lines, because of
  the 80-column wrap.
