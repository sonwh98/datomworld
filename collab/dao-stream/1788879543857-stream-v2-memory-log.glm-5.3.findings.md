---

Completed-GMT: 2026-09-08 15:16 GMT
Completed-Local: 2026-09-08 22:16 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: edf8ae2b-8d0d-4992-a621-530a10f74b27

# dao.stream.memory-log — implementation report

Delivered: `src/cljc/dao/stream/memory_log.cljc` (new),
`test/dao/stream/memory_log_test.cljc` (new), and the
exclusion-principle paragraph in `test/dao/stream/conformance.cljc`'s
namespace docstring (docstring only; no code touched in the harness).
`dao.stream.ringbuffer` and its test are byte-identical to the branch
state (`git status`: unmodified). Nothing staged or committed.

The first implementation turn was killed by the host for low memory
after the transport, tests, and docstring were written and after the
JVM, Node, and Dart suites had already run; this turn re-ran what
remained (demo compile, kondo, cljstyle idempotency) on the identical
tree — no file changed between the runs and this report (`cljstyle fix`
second pass produced no diff).

## Verification, exact commands and counts

`bb test:clj` / `bb test:cljs` are not runnable in my sandbox (not on
the permission allowlist); I ran the exact commands those tasks shell
out to. `bb test:cljd` is allowlisted and ran as itself.

- `clojure -M:test` (= `bb test:clj`; run before the kill, not repeated
  per your instruction — you also ran it): **Ran 1431 tests, 165319
  assertions, 0 failures, 0 errors.** Output contains `Testing
  dao.stream.memory-log-test` and `Testing
  dao.stream.ringbuffer-test`. Your scoped run
  (`clj -M:test -n dao.stream.memory-log-test`): 7 tests, 60
  assertions, 0 failures — matches the 7 deftests below.
- `clj -M:cljs -m shadow.cljs.devtools.cli compile slice-peer test` (=
  `bb test:cljs`): **Ran 1341 tests, 34891 assertions, 0 failures,
  1 error.** `Testing dao.stream.memory-log-test` appears in the
  Node output, as does `Testing dao.stream.ringbuffer-test`. The one
  error is pre-existing and unrelated:
  `wasm-eval-emits-telemetry-test` in `yin.vm.telemetry-test`
  (`ReferenceError: wasm is not defined`, `telemetry_test.cljc:103`) —
  the `wasm` namespace does not exist anywhere in the tree, the test
  file is unmodified in git, and it requires only v1 `dao.stream`
  namespaces; the deftest is `#?(:cljs …)`-guarded, which is why the
  clj and cljd lanes are clean.
- `bb test:cljd`: peers built (`build/slice-peer`,
  `build/yin-repl-peer`), then **`01:43 +1294: All tests passed!`**
  (Dart runner's own summary). The compile phase lists both
  `dao.stream.memory-log` and `dao.stream.memory-log-test`, and
  the Dart runner loaded
  `test/cljd-out/dao/stream/memory-log-test_test.dart`. The
  thread-based block is `#?(:cljd nil :clj …)`-excluded there as
  designed.
- `clj -M:cljs -m shadow.cljs.devtools.cli compile demo`: `[:demo]
  Build completed. (212 files, 1 compiled, 0 warnings, 2.32s)`.
- `clj -M:kondo --lint src/cljc/dao/stream/memory_log.cljc
  test/dao/stream/memory_log_test.cljc`: **errors: 0, warnings: 0**
  (334 ms).
- `mise exec -- cljstyle fix` on the two new files: clean, and a second
  run is a no-op — the tested tree is the tree that gets committed.
- `dao.stream.ringbuffer-test` passes **unchanged** on all three
  hosts; `git status` confirms neither ringbuffer file was modified.

## Acceptance list → the test that proves it

Seven deftests: `creation-spec-rule`, `complete-retention`,
`next-is-total`, `memory-log-manifest-is-valid`,
`memory-log-conformance-test`,
`memory-log-projector-linearizability-test`,
`bounded-concurrent-linearizability-histories` (the last is JVM-only;
the rest carry no reader conditional, so all three hosts run them).

- **Origin cursor minted before any append** — `complete-retention`
  binds `origin (cur h :dao.stream/oldest)` in the same `let` whose
  next binding runs all 1000 `append!`s; `let` bindings evaluate in
  textual order, so the mint strictly precedes every append.
- **Every test append returns `ok`** — `complete-retention`:
  `(is (= #{:dao.stream/ok} (set (map :dao.stream/outcome appends))))`
  over all 1000 appends; also the exclusion-side evidence for `full`.
- **Substantial sequence including `nil` retained exactly and in
  order** — `retained-values` is `(mapv #(when (odd? %) %) (range
  1000))`: 1000 values with `nil` at every even index (500 nils). The
  `replay` helper terminates on the *outcome*, never on the value — nil
  is conj'd like any other element, the exact loop shape the review's
  P2 demanded — and both replays are asserted `=` to `retained-values`
  whole.
- **Both kept-origin and fresh-`:oldest` replays** — `complete-retention`
  replays from the kept `origin` and from a `:dao.stream/oldest` minted
  after all 1000 appends; both equal `retained-values`; the fresh
  cursor is additionally asserted structurally equal to the origin
  cursor (the representation is ours).
- **Neither replay observes `gap`** — `replay` collects its outcome set;
  four `(not (contains? (:outcomes …) :dao.stream/gap))` assertions
  cover kept × fresh × open × closed.
- **Open tail returns `blocked`** — both replays' terminals are
  asserted `:dao.stream/blocked` pre-close; `next-is-total` checks the
  empty tail, the tail after one append (fabricated position = tail),
  and `(cur h :dao.stream/newest)` directly.
- **Both replays after `close!` terminate `end`** — `complete-retention`
  closes, then replays from the same kept origin cursor and a fresh
  `:oldest`: full values, terminal `:dao.stream/end`, no `gap`, and a
  post-close append answers `:dao.stream/closed`.
- **`next` totality** — `next-is-total`, no host conditional, verified
  on all three hosts: non-map (`nil`, `42`, `"cursor"`), map missing
  the identity key (`{}`, `{:dao.stream.memory-log/position 0}`),
  non-integer positions (`nil`, `1.5`, `:pos`, `"1"`), negative
  (`-1`), beyond tail (`2`, `1000000`) → all `:dao.stream/invalid-cursor`
  — the range check fires before any indexed read, so none of these
  reaches `nth`; right identity at tail → `blocked` open, `end` closed;
  foreign identity → `:dao.stream/cursor-mismatch`; the owner handle
  still mints cursors after close.
- **Creation-spec policy** — `creation-spec-rule`: `nil` and `42`
  (non-map), `{}` (missing type), `:dao.stream/ringbuffer` (wrong
  type), and `:dao.stream.memory-log/capacity` (own-namespace key) each
  → `invalid-spec`; `:my.host/label` → `ok` (foreign qualified key
  ignored). The manifest's `invalid-spec` fixture is the own-namespace
  case, so induction coverage checks it too.
- **Manifest and conformance** — `memory-log-manifest-is-valid` passes
  `validate-manifest`; `memory-log-conformance-test` passes
  `run-conformance-suite` (induction coverage, descriptor laws, reader,
  writer, close laws — the fixture set is the ring buffer's shape
  minus `attach!` and `gap`); `memory-log-projector-linearizability-
  test` runs the oracle on all hosts; `bounded-concurrent-
  linearizability-histories` (JVM) runs concurrent append/append,
  append/next, append/close, and two cursors at position 0 reading
  concurrently after one completed append.

The manifest's exclusions, each with its support: `gap` — structural
(the namespace contains no expression that removes an element) plus no
`gap` in any replay; `full` — no capacity branch exists and 1000/1000
appends answer `ok`; `transport-error` — every operation is one state
transition with no observable-failure path it could return from;
`invalid-value` — values held by reference, and the 1000-value domain
includes nils, longs, keywords, and strings; `:dao.stream/closed` on
`cursor` — `next-is-total`'s post-close mint assertion;
`:dao.stream/not-found` on `create!` — dispatch-level by construction.
`attach!` is absent, not excluded, and `validate-manifest` accepts its
absence.

## Not honoured / left owing

- **`run-retention-laws`, the `:retention` manifest-key machinery, and
  the falsification suite were not built** — per the brief, the r4
  review deferred them and I concur with its grounds (the generic law
  remains ungated on `cursor`, `next`, `close!`). Consequence: the
  manifest carries no `:retention`/`:retention-values` keys;
  completeness is declared by the `gap` exclusion and the namespace
  docstring, and proven by this transport's own tests. The
  exclusion-principle paragraph (the one addition permitted) did land
  in `conformance.cljc`, wording per plan §4.
- **Phase A commits not made** (instructed not to stage or commit). The
  tree holds both commits' content: the `dao.stream.md` amendment
  already in the working tree, plus these files.
- **Plan §5 oracle caveat honoured, not worked around**: no
  fabricated-future-cursor history is fed to the abstract model, which
  classifies every `pos >= tail` as `blocked`/`end`; beyond-tail
  totality is proven by `next-is-total` instead.
- **`run-concurrently` duplicated** into `memory_log_test` rather than
  promoted out of `ringbuffer_test`, per plan §4; consolidating the
  copies remains the named separate cleanup.
- **Unbounded growth, no rotation** — the known, named limitation
  inherited unchanged from v1 (plan §3), recorded, not fixed here.
- **Pre-existing, unrelated**: the Node-lane `wasm` failure described
  above; it fails without my files present.
