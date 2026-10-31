Completed-GMT: 2026-10-05 15:33:54 GMT
Completed-Local: 2026-10-05 22:33:54 +07
Coding-Agent: claude (glm-5.3)

**COMPLETE.** D7 is implemented test-first on branch `ucf-d7-v1-reader`
(base 52a0383b, D6 landed): the version-aware reader with the two-codec
split, the version gate before the address check, the v1 custody step
through the landed inspector, and the both-versions clause-5 grammar.
The version-0 wire is byte-for-byte unchanged (`emitted-version` 0, the
same stream codec); a valid v0 body still lowers as a fork and is
refused `:yin.k/profile-mismatch` under `{:exclusive true}`. `git diff`
touches exactly `src/cljc/yin/vm/ucf/handoff.cljc` and the new
`test/yin/vm/ucf/handoff_v1_test.cljc`; `handoff_test.cljc` was NOT
touched (the stage-1 harness was duplicated into the new file rather
than moving a shared helper — the criteria allowed the move, but a
self-contained file keeps the diff to the two named paths; say-so
fulfilled here). No git writes; no commits made.

## Changed files

- `src/cljc/yin/vm/ucf/handoff.cljc` (+177/−32):
  - `handoff-version` is now the set `#{0 1}` (the reader's versions);
    the lift emits a new private `emitted-version` = 0, so the frozen
    version-0 wire is untouched — `handoff-version` is the only
    version-0-facing constant that changed.
  - New reader pipeline in `resume-task`, before any attachment or
    restoration, in the contract's order:
    1. `decode-two` — jing canonical decode first, then the stream
       codec; the accepting codec is retained (`{:codec c :body b}`);
       neither accepting is `:yin.k/undecodable {:yin.k/kind :bytes}`.
       Verified on bytes: a real v0 body's stream bytes carry tag-39
       identifiers, which jing's decoder refuses, and jing bytes carry
       tag-27 identifier frames, which the stream codec refuses — the
       dispatch is deterministic both ways; scalar-only overlap resolves
       to the first (jing) accepter and dies at the tag check.
    2. `require-tag!` — the S7.5.1 tag.
    3. `version-gate!` — the accepting codec's body-version contract
       (`{:stream #{0} :jing #{1}`), on jing's integer kind (an integral
       float never passes on any host), refusing
       `:yin.k/profile-mismatch` with found and supported versions,
       BEFORE the address check. Under `opts`' `:exclusive` a version-0
       body is the same refusal carrying `:yin.k/policy
       :yin.k/exclusive`.
    4. v1 only: `custody-inspect!` = `checkpoint/inspect address bytes`
       — the address (`:yin.k/hash-mismatch` with the claimed and
       computed addresses) then the custody grammar, including an entry
       for every install pending. The address is `opts`' `:address`
       (what the bytes were fetched under); unnamed it is the address of
       the bytes themselves. v0 skips this entirely.
    5. `validate-body` (both versions), then the contract stamp, then
       the unchanged restoration.
  - `validate-body` grammar: version membership in `#{0 1}`;
    install-entry phase must be `:running`/`:parked` when present (both
    versions — the clause-5 row); v1 additionally requires `:yin.k/phase`
    and `:yin.k/parent` outright (`:yin.k/undecodable`, kind
    `:install-header`, path naming the missing key).
  - `resume-installs` re-encodes each child for its recursive resume
    under the codec its version rides (`child-bytes`), and marks the
    child's pass with `::install-child` so it skips step 4 — an install
    child is part of its root's custody subject (checkpoint's own
    child-header rule), its ids were already checked in the root's
    context by the root's inspect.
- `test/yin/vm/ucf/handoff_v1_test.cljc` (new, portable `.cljc`): 8
  deftests, 160 assertions. Every v1 fixture is a real export body (a
  machine the engine parked, lifted by `export-task`) raised to v1 with
  the 7.2.1 header and encoded through the landed jing codec — decisions
  are made on canonical bytes; the float-version fixture uses
  `jing.cbor/float64`, never a `1.0` literal.

## Test evidence (JVM lane during iteration)

- Red first: the new suite against the unchanged reader — **8 tests,
  160 assertions, 54 failures, 0 errors** (every jing-bytes row answered
  `:yin.k/undecodable {:yin.k/kind :bytes}`, no codec gate, no custody
  step, no phase grammar; the rows that passed red were the v0
  clause-5 rows the stage-1 grammar already refused).
- Green after: `yin.vm.ucf.handoff-v1-test` — **8 tests, 160
  assertions, 0 failures, 0 errors**.
- Stage-1 regression: `yin.vm.ucf.handoff-test` +
  `yin.vm.ucf.handoff-v1-test` + `yin.vm.ucf.checkpoint-test` +
  fixtures — **46 tests, 485 assertions, 0 failures, 0 errors** (the
  checkpoint fixtures and their pins are untouched; regeneration is
  D9's commit).
- The whole UCF tree `yin.vm.ucf.*-test` — **281 tests, 2749
  assertions, 0 failures, 0 errors**.
- The wider fast lane `clojure -M:test -e :slow -r "^(yin|dao)\..*-test$"`
  — **2967 tests, 228176 assertions, 0 failures, 0 errors** (exit 0).

The full-lane `clojure -M:test` cannot run in this worktree as-is: the
`yang.python` tests need the ANTLR-generated parser (`gen:python-antlr`,
a bb pre-step) whose classes are absent here — a worktree-state issue
unrelated to this slice; the scoped run above covers every `yin`/`dao`
namespace including all consumers of `handoff` (none exist outside its
tests — verified by grep).

## Coverage against the D7 test contract

- Version fixtures on bytes: version 2, absent, float `1.0`
  (`:yin.k/profile-mismatch`, found+supported; the float asserted with
  `jing.cbor/float64?`), a v0 child in a v1 root
  (`:yin.k/undecodable :mixed-version` with the child path), and
  unsupported-version-wins-over-a-wrong-address (plus the contrast row:
  a supported v1 under a wrong address answers `:yin.k/hash-mismatch`
  with claimed and computed addresses).
- Two-codec split: a v1 body in stream bytes and a v0 body in jing
  bytes are each `:yin.k/profile-mismatch` (`supported #{0}` / `#{1}`);
  bytes neither codec decodes (tag-99 raw bytes) are
  `:yin.k/undecodable`; a v1 structural failure (fork policy) stands as
  `:yin.k/undecodable` — no reinterpretation as v0; a valid v1 body
  rides jing bytes end to end (`:ok`, attaches its stream); a valid v0
  body still lowers as a fork and is refused under `:exclusive`.
- Custody header fixtures: each required key omitted (`:missing-header`
  with path), fork policy, nil occurrence, origin equal to the body's
  occurrence (`:origin-is-self`), header keys on a child
  (`:child-header`), a halted child with an origin refused and without
  one validating (and the whole body then lowers, child present in the
  machine's installs), header keys other than the origin on a halted
  root (`:halted-header`) and a halted root without origin
  (`:missing-header`); all `:yin.k/undecodable` with the path; a
  halted root with its origin alone validates (`:ok`, zero attaches).
- Carried-id fixtures: id on `:next` (`:op-id-on-variant`), sequence at
  the counter (`:op-seq-range`), duplicate id (`:duplicate-op-id`), id
  without an origin (`:op-id-without-origin`, on a first-park header
  with counter 0), id naming the body's own occurrence
  (`:op-id-own-occurrence`), and a child's id checked against the
  ROOT's counter (`:op-seq-range` at the child's path).
- Clause 5 on both versions: `:park` and `:call-effect` reasons refuse
  at decode (v0 through the stage-1 pending grammar, v1 through the
  inspector), a missing install entry refuses on both
  (`:incomplete-install`), a phase outside `:running`/`:parked`
  (`:linked`) refuses on both with the entry path, v1 requires phase
  and parent, and a runnable child is NOT refused by the reader (it
  lowers `:ok`) — the lift-side refusal of a running child is D9's, and
  `export-task` is unchanged in that respect.
- Every refusal asserts zero attach calls (counting `attach!`) and no
  machine (`:vm` absent). Zero proposals is vacuously satisfied and
  asserted as such: the D7 reader has no proposal path (D13's driver
  owns proposals), so a refusal that attaches nothing and assembles no
  machine leaves nothing that could propose.

## Decisions an architect should confirm

1. `:address` in `resume-task`'s `opts`: the claimed address the bytes
   were fetched under; unnamed, the reader passes the computed address
   of the bytes (a no-op check). D13/D14 can make claiming mandatory.
2. `:exclusive` in `opts` (truthy): a version-0 body refuses
   `:yin.k/profile-mismatch` carrying `{:yin.k/version 0
   :yin.k/supported #{1} :yin.k/policy :yin.k/exclusive}` — one status
   vocabulary for "the body's version does not satisfy the required
   profile".
3. Missing phase/parent at v1 answers kind `:install-header` (checkpoint
   uses `:missing-header`/`:child-header` for the custody header proper;
   these are install-entry keys, so a distinct kind name was chosen).
4. The phase check binds the shared grammar on v0 too (the clause-5 row
   says "both versions"): a v0 install entry carrying a phase outside
   `:running`/`:parked` now refuses. The stage-1 lifter only ever wrote
   those two, so the frozen wire is unaffected.
5. A valid v1 body flows through the existing restoration (it is
   version-blind); custody restoration semantics — the baseline,
   protection classes, the counter — remain D10's. `custody-inspect!`
   already answers the baseline for D10 to consume.

## Unresolved concerns and gaps

- kondo was NOT run: the session sandbox blocked every route
  (`clojure -M:kondo` in three forms, `bb`'s built-in clj-kondo — no bb
  on PATH, no `clj-kondo` binary, home-dir mise shims unreadable). The
  files compile and run on the JVM lane; a kondo pass at orchestration
  is advised. cljstyle is the orchestrator's per the constraints.
- Node and Dart lanes were not run (acceptance is JVM during iteration;
  the three-lane run is the landing step). Portability was kept by
  construction: no reader conditionals beyond the file's existing catch
  idiom, kind-aware version checks (no `0.0` literals — the float
  fixture uses `jing.cbor/float64`), and fixture bytes built only
  through the codecs. The one cross-host risk worth a glance at landing
  is `decode-two`'s catch guard `#?(:cljd Object :clj Throwable :cljs
  :default)` — it mirrors the file's existing guards exactly.
- The link-cursor ruling's liftable-safepoint sentence (cursorless
  `:link-request` entries) changes nothing here: the wire grammar has
  always required `:yin.k/cell` on a link pending, so a cursorless entry
  has no body for any reader to see; its export refusal lands in D8/D9.
- `handoff-version`'s docstring and `validate-body`'s now describe the
  two-version grammar; the UCF 7.2.1 document amendment itself (r3
  section 5) remains for the orchestrator's docs pass, as do 7.4.3's
  phase rule and 7.9's address refusal — no docs were edited in this
  slice per its diff scope.

## Incomplete work

None within D7's scope. Deliberately left to their slices: the v1 lift
and its export refusals (D8/D9, including the runnable-child refusal and
the fixture regeneration), the v1 custody lower (D10), and any consumer
of the `:exclusive`/`:address` opts (D13/D14).
