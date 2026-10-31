Completed-GMT: 2026-10-04
Coding-Agent: claude (opus-5-5)

**Summary: C1 journal is in. New `dao.stream.journal` (memory backend with three named crash cuts) plus its spec doc; 10 tests, green on JVM, Node and Dart. One small edit outside the listed files: `dao.stream.memory-log/restore`.**

## Changed files

- `src/cljc/dao/stream/journal.cljc` (new): frame grammar, `open!`
  (create-or-replay, torn-tail truncation, refusals as data with
  `::defect`), `JournalHandle` (descriptor, cursor/next delegated to the
  visible memory log, `append!` doing encode, persist, visible under one
  lock), poison, the 2^52-1 bound, `memory-backend` with cuts
  `:before-frame`, `:after-frame-before-visible` and `:torn-frame`.
- `src/cljc/dao/stream/memory_log.cljc`: added `restore [identity values]`,
  a handle on an existing memory log. It is needed so that the visible log
  keeps the persisted identity, and with it the cursors, across reopen.
  `create!` always mints a fresh identity. Nothing else in the file changed.
- `test/dao/stream/journal_test.cljc` (new).
- `docs/design/dao.stream.journal.md` (new): frames, identity, cursors,
  open and refusals, outcomes, poison, bound, exclusions, backend seam.

## Tests

Red was run against a stub namespace (`open!` answering a non-ok outcome);
green is the real implementation.

| Test | Red | Green JVM | Green Node |
|---|---|---|---|
| fresh-journal-writes-its-header | fail/error | pass | pass |
| repeated-equal-appends-keep-separate-positions | fail/error | pass | pass |
| identity-and-positions-survive-reopen | error | pass | pass |
| non-portable-value-is-refused-without-a-write | error | pass | pass |
| crash-cuts-poison-until-reopen (3 cuts) | error | pass | pass |
| torn-frame-is-dropped-at-reopen | error | pass | pass |
| unreadable-media-refuse-the-open (missing header, gap, malformed, mid-file unreadable, well-formed opens) | fail | pass | pass |
| position-bound-refuses-without-a-write | fail/error | pass | pass |
| journal-conformance-test (dao.stream.conformance suite) | error | pass | pass |
| journal-manifest-is-valid | pass (pure data, as expected) | pass | pass |

- JVM: `clojure -M:test -n dao.stream.journal-test -n
  dao.stream.memory-log-test -n dao.space.transactor-test` gave 33 tests,
  229 assertions, 0 failures.
- Node: shadow `test` build with `--config-merge` ns-regexp limited to
  journal and memory-log gave 16 tests, 116 assertions, 0 failures.
- Dart: see the Dart section below.
- kondo: 0 errors, 0 warnings on the three touched cljc files.
- **cljstyle was not run.** Both `cljstyle fix` and `cljstyle check`
  require a permission this session does not have. Lines are at most 80
  columns and the new files are ASCII; checked by hand.

## Dart

The full fast lane, `bb test:cljd`, passed: +2723, "All tests passed!",
exit 0. `dao.stream.journal-test` was among the compiled namespaces. It ran
past the 600 s foreground limit, so the harness moved it to the background
and I waited on it. Dart was not run red.

## Deviations (smallest choices where the plan is silent)

1. **`open!`, not `create!`.** Opening a backend either creates or
   replays, so it is a host act (like datagram binding) taking a backend
   map, not a creation spec. Functions do not belong in a spec. No
   `attach!` either, because no registry maps a descriptor to a backend.
2. **No close surface.** The frame grammar has no close frame, and a close
   that a reopen undoes would break "close is irrevocable". So the journal
   also excludes `closed`, `end` and `refused`, beyond the plan's `full`
   and `gap`.
3. **Bound.** An append past `max-position` answers `transport-error` with
   `::defect :position-bound` and writes nothing. It does not poison the
   handle, since its effect is known to be nothing. A test-only option
   `{:dao.stream.journal/max-position n}` on `open!` makes the bound
   reachable.
4. **Backend seam has three functions:** `::frames`, `::write-frame!` and
   `::truncate!`. Truncation is needed so that a torn tail is gone before
   the next append, or a later reopen would see it mid-file. C2 may make
   `truncate!` trivial if `dao.jing.file/records` already drops tears.
5. **Tear rule.** Only the final frame may fail to decode, and it is
   dropped. An undecodable frame anywhere else refuses the open as
   `:unreadable-frame`. If the only frame is torn (a torn header), the
   medium counts as empty and gets a fresh identity.
6. **What is visible.** The visible value is the one decoded from the
   persisted bytes (`decode-snapshot`), not the caller's host value. This
   way a reader before reopen and a reader after reopen see the same
   value, and caller-mutable byte arrays are not aliased.
7. **Reads after poison.** Reads keep serving the visible log, per 1.6
   "poisons the handle for appends". Authority-level poison of reads (1.4)
   is C3's job.
8. **Header is strict.** Exactly `{:version 1 :identity <string>}`; extra
   keys refuse as `:missing-header`.
9. **Cursor shape.** Cursors are the memory log's
   (`:dao.stream.memory-log/identity`/`position`) over the journal
   identity. They are opaque to consumers, but the key namespace is not
   `dao.stream.journal`.

## Unresolved questions

- Should the journal have a durable close (a close frame in the grammar)?
  Neither the plan nor the ledger needs one today.
- Is `memory-log/restore` acceptable? The alternative was reaching into
  `->MemoryLogHandle` with its private state shape.
- Strict or open header keys? Strict was chosen.
- Should a torn header on an otherwise empty medium refuse instead of
  re-minting the identity?
