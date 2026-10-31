Completed-GMT: 2026-10-04
# C2 findings: the journal file backend

**Summary: `dao.stream.journal.file` is in, behind C1's three-function seam,
with `journal.cljc` unchanged. JVM focused is green (10 tests, 63 assertions,
kill test included) and Node focused is green (9 tests, 60 assertions).
Dart and the full lanes were not run. cljstyle was not run (needs approval
in this session).**

## Changed files

- new `src/cljc/dao/stream/journal/file.cljc`
  - `backend!`, `close!`, `durability`, `content-name`
- new `test/dao/stream/journal/file_test.cljc`
- `src/cljc/dao/jing/file.cljc`: the `records` docstring now also names the
  journal's replay use (2 lines, no code change)
- `docs/design/dao.stream.journal.md`: status line and the "File backend"
  section

## Mechanism

- `(backend! dir)` creates `dir`, takes `dao.space.store.fs/lock!`, then
  opens `<dir>/journal.jing` with `dao.jing.file/create-content-file`.
  - It answers `ok` with `::backend`.
  - Otherwise it answers transport-error with defect `:locked` or
    `:open-failed`. A failed content open releases the lock.
- Backend keys:
  - `::frames`: `dao.jing.file/records`. Each `[address value]` is turned
    back into bytes with `jing/segment-bytes`, which re-encodes the value
    and throws unless the bytes hash to the address. A fresh directory
    answers `[]`.
  - `::write-frame!`: a put at the blake3 address of the bytes. `:inserted`
    answers ok. `:present` answers transport-error with defect `:present`,
    and the journal poisons on it.
  - `::truncate!`: always fails with defect `:truncate-unsupported`. This
    path cannot be reached: the content file drops torn tails itself and
    validates every frame it keeps.
  - `:dao.stream.journal/durability`: `(fn [] durability)`.
  - `::lock` and `::close!`.
- No new framing, fsync or truncation code.

## Tests: red before, green after

I did not run a red phase. Moving the source aside to show red needed an
approval this session did not have. Before this slice the namespace did
not exist, so every test below would have failed to compile. All of them
are green now.

| Test | JVM | Node |
|---|---|---|
| a-fresh-directory-opens-an-empty-journal | green | green |
| dropped-without-close-the-path-reopens (identity, cursor, positions) | green | green |
| repeated-equal-appends-are-distinct-frames-across-reopen (asserts 4 distinct addresses) | green | green |
| decode-then-encode-is-the-identity-on-accepted-frames (floats, -0.0, 2^52-1, sets, maps) | green | green |
| a-hand-torn-tail-is-dropped-and-the-journal-continues | green | green |
| a-second-opener-of-the-directory-is-refused | green | green |
| a-present-put-poisons-the-handle | green | green |
| the-durability-declaration-is-data | green | green |
| file-journal-conformance-test (journal manifest, every fixture on a fresh directory; transport-error is an append after `close!`) | green | green |
| a-process-killed-mid-append-leaves-a-dense-prefix (JVM only) | green, about 2.3 s, not tagged slow | n/a |

How the tests model a crash:

- "Drop without close" models process death in-process. `crash!` releases
  only the lock, as the OS would, and closes nothing.
- The kill test is a real `java -cp <classpath> clojure.main` child
  appending in a loop. It is killed with `destroyForcibly` (SIGKILL) after
  at least 50 acknowledged appends. The reopened values must be exactly
  `(range n)`, with `n` greater than the last acknowledgement seen.

## Runs

- **JVM:** `clojure -M:test -n dao.stream.journal.file-test -n
  dao.stream.journal-test -n dao.jing.file-test -n
  dao.jing.cbor-conformance-test`: 53 tests, 273 assertions, 0 failures.
  This includes the cbor guard scan.
- **Node:** shadow `test` with `:ns-regexp` covering
  `stream.journal.file`, `stream.journal` and `jing.file`: 38 tests, 234
  assertions, 0 failures, 0 warnings. "Testing
  dao.stream.journal.file-test" appears in the output.
- **kondo:** 0 errors, 0 warnings on the three touched .cljc files.
- **Not run:** Dart, the full lanes, and cljstyle (`cljstyle fix` and
  `check` both needed approval). Line widths (80 or less) and ASCII-only
  were checked by script.
- All temp directories under `target/` were cleaned up; none were left.

## Deviations

- The kill test's first version read `parse-long` on the child's first
  stdout line, which is Timbre's "Loading initial Timbre config". It now
  skips non-numeric lines and merges stderr into the failure message.
- The durability declaration is a key on the backend map, not on
  `journal.cljc`. The memory backend has no such key, and I did not edit
  `journal.cljc`.

## Open questions

1. **Memory backend durability.** Should `memory-backend` also carry
   `:dao.stream.journal/durability` (`:backend :memory`)? For now C3 must
   treat a missing key as memory, which is never capable.
2. **Power loss on JVM and Node.** These hosts declare `:power-loss` as
   the plan says. But `dao.jing.file` never syncs the directory when it
   creates `journal.jing`, so a power cut right after the first `open!`
   could lose the file and its header identity. Should C2 sync the
   directory once at creation (this is new fsync code, which the brief
   excludes), or should JVM and Node declare `:process-crash` until then?
3. **A torn header on file.** When the header is the only frame and it is
   torn, `dao.jing.file` refuses the open ("no valid first frame"). The
   result is `:open-failed`; the journal never gets to open a fresh empty
   journal the way the memory backend does. It is conservative, and the
   header is never acknowledged. Is that acceptable, or should the backend
   recreate the file?
4. **`persisted` values.** The `persisted` set is
   `#{:identity :content-references}`, taken from the plan's wording.
   Confirm these names suit C3.
5. **Unreachable truncate.** `truncate!` always answers a failure. If
   `dao.jing.file` ever stopped validating payloads, a journal tear could
   reach it and the open would refuse with `:truncate-failed`, failing
   closed.
