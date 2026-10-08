Created-GMT: 2026-09-26 10:12:20 GMT
Created-Local: 2026-09-26 17:12:20 +0700
Coding-Agent: glm
Session-ID: 5985a4e0-f658-4d44-b6c9-2d2f318994b8
Completed-GMT: 2026-09-26 10:24:14 GMT
Completed-Local: 2026-09-26 17:24:14 +07

# yin.vm.linker M4 slice A3: authority-policy datom ingestion

Role: VM Runtime Engineer. Branch m4-a3
(worktree /Users/sto/workspace/datomworld-m4-a3,
base 9428c3d2 = master). Nothing committed, staged, or merged, per the rules.

## What was built

`yin.vm.linker.authority/events-from-datoms`
(src/cljc/yin/vm/linker/authority.cljc,
new function between the envelope/content-id section and the authentication
section): the section 8.2 authority event sequence assembled from the assertion
datoms a composition read at its snapshot, following linker.cljc's
`index-from-datoms` pattern. Two arities:

- `(events-from-datoms datoms)` and `(events-from-datoms datoms carrier)`.
- Every `[ev :yin.module/envelope env]` datom yields exactly one event
  `{:yin.module/envelope env ...}` whose proof is the FIRST
  `[ev :yin.module/proof proof]` datom of the same entity (first in handed
  order; the dao.space current view hands EAVT-ordered facts, so this is
  deterministic).
- `carrier`, when given, marks every event with that `:dao.stream/identity`
  (the one stream the composition read at the snapshot) for the attested-log
  check; nil means the read names no stream, and an attested proof fails
  :bad-proof there (fail closed).
- Pure over the handed datoms: no ambient read, no snapshot advance, no atoms,
  no clock, no reader conditionals; events keep the handed datoms' order.
  name-environment and every A1/A2 deftest are untouched (verified: the A1/A2
  test file is not in the diff; the namespace still passes).

## Decisions the spec left open (and why)

1. **Orphan proof datom: IGNORED, not reported.** The task allowed either.
   Section 8.2's diagnostics are a closed set -- the four discard kinds plus
   exactly `:malformed-envelope`, `:undeclared-principal`, `:no-proof-kind`
   (L1806-1810) -- and no kind describes a proof with no envelope; the policy's
   event shape is envelope-keyed, so an orphan proof names no event at all. The
   malformed-datoms test pins this: three datoms committed (envelope-no-proof,
   orphan proof, envelope-not-a-map), exactly two events assembled, and the
   diagnostic set is exactly #{:no-proof :malformed-envelope}.
2. **The attested carrier is a per-read argument, not a datom.** Section 8.2
   names only two datom shapes; "every envelope read from that identity"
   (L1777-1780) makes the carrier a property of the read (which stream the
   composition read at the cursor), like the snapshot itself. Recorded in the
   one added spec sentence and tested both ways.
3. **Multiple envelope datoms on one entity: one event per datom** (1:1 with
   the datoms, like index-from-datoms per-index-datom). A second envelope
   sharing one proof cannot both verify (the signature covers the whole
   envelope), so this stays fail closed with no arbitrary winner chosen.
   Not separately tested (degenerate datom shape; the no-proof/not-an-envelope
   paths cover the malformed datoms the task asked for).

## Spec edit

One sentence added to section 8.2 (docs/design/yin.vm.linker.md, after the
`[ev :yin.module/proof proof]` datom sentence, L1719-1724) recording decisions
1 and 2. No other section touched.

## Files changed

- src/cljc/yin/vm/linker/authority.cljc -- +39 lines, one new function
  (`events-from-datoms`), nothing else moved or edited.
- test/yin/vm/linker_authority_ingestion_test.cljc -- NEW, 5 deftests / 29
  assertions, end to end from transacted datoms (see below).
- docs/design/yin.vm.linker.md -- the one sentence above.

## Tests added (all red first: the var did not exist, then green)

End to end: commit real tx-data into an in-memory dao.space (dao.space
transactor over a memory-log local stream, the ledger-test composition), read
at the snapshot through the query API (`current` view as-of bounded at the
committed cursor position, facts drained through `match`), ingest, fold with
name-environment:

1. `transacted-assertion-datoms-resolve-the-name` -- the exact name environment
   asserted (full `:names` map equality, provenance with `:yin.link/snapshot`
   = the cursor position, empty diagnostics, `:honored-seq`).
2. `datom-transacted-after-the-snapshot-is-invisible-until-rebuilt` -- snapshot
   advance: the reads happen after BOTH commits, so the as-of bound alone
   decides; at t1 the later name does not exist (not even a diagnostic), the
   first snapshot is a value the second build never mutates, and the rebuilt
   snapshot at t2 resolves the new name.
3. `transacted-retraction-removes-exactly-its-assertion` -- a retraction
   envelope transacted as datoms removes exactly its named assertion; a
   same-named sibling and another name survive; no dangling retraction.
4. `malformed-authority-datoms-fail-closed` -- envelope with no proof datom
   (exactly one :no-proof, :unauthenticated), orphan proof datom (ignored, no
   event, no diagnostic), envelope datom whose value is not a map (exactly one
   :malformed-envelope, defect :not-an-envelope).
5. `attested-datoms-prove-only-through-the-reads-carrier` -- the same committed
   datoms resolve when read with the declared stream as carrier and fail
   :bad-proof when read from nowhere.

## Verification

- TDD: red confirmed first (compile error, no such var events-from-datoms),
  then green.
- JVM lane `mise exec -- clojure -M:test`:
  - before (recorded first, this worktree at 9428c3d2): 2,138 tests /
    181,890 assertions, 0 failures, 0 errors. (The task brief said 181,898 at
    master; my measured baseline on this tree is 181,890 -- reported as
    measured.)
  - after: 2,143 tests / 181,920 assertions, 0 failures, 0 errors
    (+5 tests, +30 assertions, exactly the new ingestion deftests; the
    selective run of the one namespace reports 29 assertions, the full
    suite 30 -- both from real logs, reported as measured).
- kondo: 0 errors, 0 warnings on both changed source files.
- cljstyle: clean on both changed source files.
- Every added/edited line is ASCII and <= 80 columns (checked mechanically).
- Cross-host safety: no reader conditionals added anywhere; no cross-namespace
  #'private access (test uses only public vars); only corpus-idiomatic core
  (reduce/assoc/contains?/into/keep/cond->/some?), all already used in the
  cljc corpus that compiles on JVM, Node, and Dart; the test's dao.space
  composition (transactor + memory-log + ringbuffer + query current/match) is
  the same one ledger_test.cljc already runs on all three hosts.

## Unrun checks

- Node and Dart lanes (the task assigns those to the orchestrator).
- No A1/A2 or name-environment behavior changed, so their cross-host results
  carry over unchanged except for the new shared-file compile (kondo and JVM
  clean; cljd has no new constructs).
