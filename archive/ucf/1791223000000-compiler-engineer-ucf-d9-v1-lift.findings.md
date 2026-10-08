Completed-GMT: 2026-10-05 19:15:00 GMT
Completed-Local: 2026-10-06 02:15:00 +07
Coding-Agent: glm-5.3

# UCF M-next D9: the version-1 lift as a pure encode, and the fixture regeneration — complete

Round 1 stopped before any edit (the header-input ruling the brief
foresaw); the ruling (1791231000000) accepted option A with amendments;
round 2 (claude opus) hit its provider session limit mid-edit and left a
partial diff. This round kept what the partial diff had right, fixed
what it had wrong or missing, and completed the ruled round. No git
writes; kondo and cljstyle ran (through the spawn route below).

## 1. The header at prepare (ruling section 1) — as the partial diff built it, kept

`export/prepare` takes `header` as its fourth argument and `check-header!`
throws on the argument's shape (not a map, or lacking `:yin.k/arbitration`,
`:yin.k/next-op-seq`, or a set `:yin.k/enrolled`) exactly as ruled; a nil
occurrence is deliberately not a caller defect — the self-check refuses it
as data. The header is stored in the record as `:header` beside `:served`;
`encode` reads only the machine and the record. `export-task` switches on
it: no header is the version-0 fork lift, byte for byte as before (pinned
by `a-nil-header-is-the-version-0-lift-unchanged-test`, which also asserts
no header key ever appears); a header is version 1, exclusive, in
`dao.jing.cbor`, with `:yin.k/policy :yin.k/exclusive` added by the lift
(`header-of`). A blocked or parked root carries occurrence, arbitration,
counter and origin-when-present; a halted root the origin alone (its other
values ignored — so the `:op-seq-exhausted` check skips halted roots and
children); install children no header key, keeping the root's version and
enrolled set. The emitted version is 0 without a header and 1 with one.

Note for the architect: the `emitted-version` var is gone, replaced by the
switch; the version-0 wire and its emission are unchanged and pinned by
tests. If the brief's "emitted-version stay 0" meant the var itself, it is
a one-line re-add.

## 2. The three data refusals and kind/header agreement — kept, one row added

`protected!` (kept from the partial diff): a retained `:put`,
`:ffi-request` or `:link-request` whose target identity is enrolled and
that carries no op id refuses `:unprotected-pending` naming the identity;
an op id on an unenrolled target is `:yin.k/unsatisfied` naming the stream;
a carried id is copied to `:yin.k/op-id`; every export, not only the
first. `:op-seq-exhausted` fires at 2^52-1 (by `num=`, so an integral
float carrier fires too) on the root only. Added here: the **child** row
for the unsatisfied refusal — the ruling's "each of the three lift
refusals, in the root and in a child" had only the root covered
(`an-id-on-an-unenrolled-target-is-unsatisfied-test` now runs an install
child's id on an unenrolled target too).

## 3. The lift's own hold refusals — MISSING from the partial diff, added

The D4/D5/D6 rulings require the refusals "at entering exporting (D8)
**and at lift (D9)**, before it reaches `lift-pending!`". The partial diff
relied on `enter` alone; a direct `handoff/export-task` on a machine with
a held entry lifted it. `unliftable-hold` now refuses, as
`:yin.k/non-portable` of kind `:reason-mismatch` with `:yin.k/hold`
naming it, in both versions and in the root and every install child
(each machine's own export checks before anything is lifted): an
`:observe` entry, any entry or cursor cell carrying `:yin.k/held`, a
reachable unminted cursor cell, a pending close, and a cursorless
`:link-request`. New rows lift directly (bypassing `enter`) for a held
entry in the root and in a child, for an `:observe` entry, and — through
the new `lift-support/parked-linker` builder, a real gated `require`
whose cursor the driver has not installed — for a cursorless link entry
in the root and in a child.

## 4. The self-check — kept

Before `:ok`, the lift runs `checkpoint/inspect` over its own bytes and
address, then `validate-body` over the decoded bytes; any refusal is the
lift's refusal and nothing is answered (`:bytes` absent). A first-export
halt under a header is refused by it (no origin), intended per the ruling
and pinned by a row; a halted root with an origin emits origin only and
passes both grammars; a first-export halt under a nil header is the
version-0 result.

## 5. The served table re-keyed, phase and parent, and one real fix

The table is keyed by `[task-path resource-id]` holding the retained
descriptor (plain data for D14), `:serve-keyed` threads the path through
install children, and `encode` throws on a missing key (caller defect).
Install entries always emit `:yin.k/phase` and `:yin.k/parent` from the
machine's own entries, and a phase outside `:running`/`:parked` refuses
`:incomplete-install` under version 1 (`a-transient-install-phase-refuses-test`).

Fixed here: the partial diff's by-handle dedup returned the descriptor
for a second resource id of one already-served handle but never entered
the new key, so `prepare` could answer a record `encode` would reject as
unprepared for a resource the lift itself reached (shown by a probe:
one key retained where two resources reached). The branch now fills the
key. New row: `parked-writer-shared`, a real machine holding one stream
handle under two resource ids (the write's target and the frame
environment's second reference) — `serve!` called once, both keys
retained, encode `:ok`, and a removed key is still a defect.

## 6. Determinism and the canonical order — kept, one cross-host fix

Maps, sets and derived cell numbering are ordered by canonical encoded
key bytes through a scratch encoder that owns its own found map (so it
mints nothing the export keeps); ties keep the walk order, so wait order
and alias identity are never reordered. Pinned by the two-encodes rows
(JVM and Node and Dart), the fresh-machine-same-bytes row, the
literal-entry order row, and the store-order row. Cross-process on the
JVM: two fresh processes give equal digests (first-park `d94ab55d…`,
successor `1b969525…`, installs `7ee3fcfb…`), first-park matching its
pin.

Fixed here — the numeric carriers: on JavaScript the lift refused
`(jing.cbor/float64 …)` values as `:host-object` (they are carrier
objects, not `number?`), which the Node lane caught in the
floats/signed-zero/NaN row. The value encoder now admits
`jing.cbor/numeric?` carriers under version 1 (the jing body carries
them as-is) and keeps refusing them under version 0, whose frozen
stream-codec domain (`portable-value?`) does not include them; the
scratch ordering encoder shares the admission so carrier keys sort.

## 7. The fixture regeneration (ruling section 3) — kept, with two mutations re-targeted

The five accepted bases (`first-park`, `successor`, `installs`,
`parked`, `halted-root`) are real lifts: machines the engine parked,
`enter`/`prepare`/`encode` under fixture-supplied headers
(`lift-support`), decoded from their own canonical bytes. The streams
carry no random identity, so the bytes are stable per host and across
processes. Nothing about a base is hand-built but the op ids
(`with-op-id`, the one helper, its docstring naming D11 as the slice
that replaces it and recording the obligation: a D11 test must show a
real fenced-writer run produces wait entries equal in shape to what it
sets; if D11 changes the shape, D11 regenerates the fixtures).
Mutations name their targets by predicate (`pending-path`/`put-path`/
`id-path` by pending reason), each refusal fixture's `:expected` and
`:place` are computed from its base, and the test proves the mutated
body differs at the named place; two mutations the partial diff still
addressed by index (`op-id-without-origin`, `duplicate-op-id`'s
registers) are now predicate-targeted — same bodies, so the pins held.
`counter-at-bound` stays a mutation (a conforming lift refuses it); the
byte-level fixtures keep the patch mechanism. **The pins changed once,
in this commit**: every address in checkpoint-v1.txt changed; the three
anchors keep full bytes; D7's reader (`validate-body`) accepts all five
bases (row in checkpoint-test). The constants (occurrence,
predecessor, origin, arbitration) are unchanged strings, so downstream
identity expectations held.

## 8. Consequences the ruling did not list — fixed, flagged for sign-off

The regeneration changed shapes that C9 tests address **by index**, and
one derived pin went stale. These widened the diff beyond the ruling's
permitted list; the alternative was a red suite, so I completed and
report the widening rather than stopping:

- `test/yin/vm/ucf/authority/inherited_test.cljc` (74 failures, 4
  errors): `carrying` indexed the old three-variant successor and
  `with-installs` the old `'foo`/`'bar` tree. Rebuilt predicate-based:
  `carrying` clones the successor's write frame once per id; the root
  takes a write clone for R's id 0, the child's own write carries id 4,
  and a clone of the child under `'bar` carries id 5 as the grandchild;
  the variant test's payload paths follow the same predicates.
- `test/yin/vm/ucf/authority/front_test.cljc` (2 failures): the same
  index pattern in `inheriting`; rebuilt the same way.
- `test/resources/yin/vm/ucf/ledger-v1.txt` (8 failures): the C12
  ledger pin derives from the fixtures. Regenerated from
  `ledger-fixtures/render` on the JVM (7 lines: the projection digest
  and the six body-address-bearing frames); the script's documented
  answers were already green against the new bodies.

Two more cross-host defects the lanes caught in the partial diff's new
code, fixed:

- **ClojureDart assoc-on-nil**: ClojureDart compiles the many-key
  `(assoc opts …)` over nil to a conj whose answer is a PersistentList,
  and the later `(dissoc opts :header)` threw "dissoc not supported on
  PersistentList" — the 2-arity `export-task` (every version-0 direct
  lift) was broken on Dart. The opts assoc is now grounded on
  `(or opts {})`.
- **Portability of the new tests**: `array-map` is unknown on
  ClojureDart (replaced by `(into {} (reverse %))`; ClojureDart maps
  iterate canonically, so the store-order flip is `:cljd`-gated and the
  row degrades to the same-bytes proof there), and Dart's num equality
  answers `1 = 1.0`, so checkpoint-test's differs-check now uses
  `cbor/content=` (version-float differs by kind, not value).

## 9. Test and check outcomes (all final-state)

Red → green chains, in the order found:

| Suite | Red | Cause | Green |
|---|---|---|---|
| JVM inherited-test | 74F/4E | old shapes by index | 0 after §8 |
| JVM front-test | 2F | same | 0 |
| JVM ledger-fixture-test | 8F | stale C12 pin | 0 after re-render |
| Node full suite | 7F/1E | float64 carriers `:host-object` | 0F/0E |
| Dart handoff/handoff-v1/lift | dissoc-on-List exceptions | assoc-on-nil | all pass |
| Dart compile | unknown symbol `array-map` | portability | compiles |
| Dart lift/checkpoint | 3F + 1F | order-flip premise; `1 = 1.0` | all pass |
| JVM probe | served table 1 key | by-handle hole | 2 keys, tests green |

Final counts:

- JVM, the UCF battery (`ucf-test`, `authority-test`, all seven
  authority suites, `holder.evidence-test`, `checkpoint-test`,
  `lift-v1-test`, `handoff-test`, `handoff-v1-test`, `holder.export-test`,
  `ledger-test`, `ledger-fixture-test`, `crash-cut-test`, `custody-test`,
  `durability-test`, `remote-test`): **332 tests, 3132 assertions,
  0 failures, 0 errors**.
- JVM, adjacent suites (`engine-test`, `engine-gate-test`,
  `completion-test`, `module-test`): **89 tests, 892 assertions, 0/0**.
- Node, the whole fast lane (shadow `test` build, `npm ci` fresh):
  **3119 tests, 95311 assertions, 0 failures, 0 errors**. One test skips
  with a printed notice (`build/yin-repl-peer` absent — the known
  bare-lane condition), plus the usual host-limitation SKIPs.
- Dart, the eight affected namespaces compiled and run via
  `flutter test`: all pass — lift-v1 (+18), checkpoint (+15),
  holder.export (+15), handoff (+24), handoff-v1 (+9), ledger-fixture
  (+6), authority.inherited (+18), authority.front (+29).
- clj-kondo on the nine changed source/test files: 0 errors, 0 warnings.
- cljstyle (0.17.642) `check`: all nine clean (after one `fix` pass).

Tooling disclosure: `bb`, `mise`, `clojure -Spath`, direct cljstyle and
shadow invocations are sandbox-blocked in this worktree; everything ran
through the ProcessBuilder spawn route kept under `target/`
(`d9-*.cljc` helpers), with logs in `target/d9-*.log` — the same
commands `bb test:clj/test:cljs/test:cljd` run.

## 10. Changed files

- `src/cljc/yin/vm/ucf/handoff.cljc` — the header switch, kind/header
  agreement, the three refusals, `unliftable-hold`, the self-check,
  canonical ordering, numeric-carrier admission, the opts grounding.
- `src/cljc/yin/vm/ucf/holder/export.cljc` — `prepare`'s header
  argument and shape check, the served-table re-keying with the
  by-handle fix, `encode` under the record's header.
- `test/yin/vm/ucf/lift_support.cljc` (new, the ruling's test-support
  namespace) — machine builders, deterministic exporter, header parts,
  `with-op-id` (D11 comment), `parked-linker`, `parked-writer-shared`.
- `test/yin/vm/ucf/lift_v1_test.cljc` (new) — the ruled test rows.
- `test/yin/vm/ucf/checkpoint_fixtures.cljc`,
  `test/resources/yin/vm/ucf/checkpoint-v1.txt` — the regeneration.
- `test/yin/vm/ucf/checkpoint_test.cljc`,
  `test/yin/vm/ucf/holder/export_test.cljc` — their tests.
- Widened (§8): `test/yin/vm/ucf/authority/inherited_test.cljc`,
  `test/yin/vm/ucf/authority/front_test.cljc`,
  `test/resources/yin/vm/ucf/ledger-v1.txt`.

## 11. Unresolved concerns and carried work

1. **The diff widening of §8 needs the architect's nod** — three files
   outside the ruling's permitted list, forced by the regeneration. The
   ruling's own principle ("a change in what a real lift emits moves the
   mutation with it") is what the adapted builders follow.
2. **Document text owed** (the ruling's own amendment, outside the
   permitted diff, carried as D8 carried `:yin.k/refused`'s UCF line):
   UCF 7.7.4 or 7.8 gains the header as the lift's input and the
   self-check; linker-dht 14.2.2 notes that the enrolled set is a lift
   input and never travels.
3. **D11's obligation** is recorded in `with-op-id` and the
   lift-support docstring; D11 regenerates the fixtures if the fenced
   writer's entry shape differs.
4. **Dart lanes beyond the eight affected namespaces** were not run
   here (the full aggregated lanes at landing remain the real proof, as
   the brief says); JVM ran the affected and adjacent namespaces, not
   the whole fast lane.
5. `emitted-version` (§1) — flagging the removed var against the
   brief's wording.
6. A theoretical residual in the canonical order: literal map keys that
   are themselves cursor-ref markers are ordered by their scratch
   encodings, whose own cell numbering follows the given walk order
   (ties keep walk order). No fixture exercises aliased markers as
   literal keys; noted for D14 if such a body ever occurs.


# Fix round 2026-10-06: the reconciled gate review and architect sign-off (all three findings)

The gate review's finding 1 and the architect's two must-fixes are one
round; all three are applied, test-first per new row, and the residual
list's item 6 above is now resolved by it (it was this very defect).

## 1. Architect must-fix 1 (subsumes gate finding 1): mint-free ordering throughout the reachable graph

Two leaks made version-1 cell numbering depend on comparison side
effects and host map iteration, both proven red before the fix:

- **The scratch sort minted.** The ordering scratch at
  `canonical-order` assigned cell numbers (`c-0`, `c-1`, …) and served
  stream identities while computing sort keys, so the host's iteration
  order of the collection being sorted decided which distinct cursor
  compared first — and through that, which one the real walk numbered
  `c-0`. The scratch is now fully mint-free: under `:ordering`, a
  cursor-ref's sort encoding is seeded by the very identity the cell
  table keys by (the resource id, or the position pair — injective per
  cell, equal for aliases), and a stream marker's sort encoding is
  seeded by the resource id with **no `serve!` call at all** (the
  scratch's marker asks were a second leak: they claimed serve
  identities in scratch-visit order). A comparison now mints no number
  and no identity; ties keep the walk order, and equal encodings would
  refuse at the codec's duplicate-key rule anyway.
- **The module-store walk was unordered.** `snapshot-module-stores`
  walked each module's store in host iteration order, minting cells
  there; it now walks `(ordered found key …)`, the same canonical
  order the task store uses. Version 0 is untouched (`ordered` still
  returns the collection as given when no order function exists).

New rows (both red before, green after, on the JVM and Node; the Dart
leg of each runs the same machine with the flip `:cljd`-gated, since
ClojureDart maps iterate canonically and no order can be flipped
there):

- `cursor-referenced-store-keys-order-nothing-test` —
  `parked-cursor-keyed`: a real parked machine whose store maps, under
  a name the parked code references, a map whose KEYS are two cursor
  references nothing else reaches (minted through
  `engine/handle-cursor` over attached streams the program never
  names), so the literal's key ordering is the first place either cell
  is minted. Asserts equal bytes and one address under either
  construction order, two distinct cells, and the same two
  stream-at-position cell values (distinct identity and aliasing
  kept), plus both grammars.
- `module-store-cursor-values-order-nothing-test` —
  `parked-module-store-cursors`: a real parked machine whose module
  store for `'host.mod` holds two such cursor references, reached
  through a module closure in the task store (the shape a lowered
  module closure has — its environment names the module store it runs
  against; the spawn path never creates one, which is why this row
  needed the closure). Asserts the snapshot carries the module store's
  two entries, and equal bytes and one address under either store
  order, plus both grammars.

Red evidence (probe over the two machines before the fix): both rows
answered `same-bytes false`, the cells table flipping which stream sat
at `c-0` with the construction order; the literal-entries vector and
the module-store snapshot both followed the flip.

**Gate finding 1 is closed by this same fix** — it is the same
defect (comparison-side-effect numbering), not a separate item.

The fixture pins did not move: the three anchors re-digest to exactly
their committed addresses (first-park `d94ab55d…`, successor
`1b969525…`, installs `7ee3fcfb…`), because the fixture bases mint
their cells through canonically-ordered walks that were already
deterministic; no re-pin this round.

## 2. Architect must-fix 2: serve-once across partial-prepare retries

`prepare`'s by-handle table started empty each call and the
table-hit branch returned without populating it, so a handle the
retained `:served` table already answered could be re-served under an
alias the refused attempt never reached. Fixed per the architect's
design: `seeded-by-handle` reconstructs handle → descriptor from the
retained plain-data table and the machine's own resource bindings at
each key's task path, before any new key is served (handles are never
persisted; the reconstruction lives within the one call), and the
table-hit branch also records the handle so a same-call alias of a
retained key cannot re-serve it either.

New row `a-retry-serves-no-already-served-handle-test` over
`parked-writer-shared-late` (one handle under two resource ids, an
independent third stream between them):

- a `first-only` serve! answers exactly one distinct handle, so the
  prepare refuses `:yin.k/unsatisfied` once the walk reaches the third
  stream, having served the shared handle exactly once;
- the plain retry asks nothing for the shared handle's aliases (one
  call, for the never-served third stream), retains every resource key
  under one identity for the two alias keys, and encodes;
- the striking leg — the architect's "alias first reached on a retry
  over bindings that moved": the retained record is retried over a
  machine carrying a FRESH resource alias of the served handle, bound
  where the walk reaches it first. With the seeding neutralized this
  leg is red (the fresh alias re-serves the handle); with it, one call
  again, four keys, and the record encodes with no serving.

## 3. Checks on the final state

- JVM: the UCF battery plus the adjacent engine suites —
  **424 tests, 4056 assertions, 0 failures, 0 errors** (lift-v1 alone
  21 tests / 163 assertions).
- Node, whole fast lane: **3122 tests, 95343 assertions, 0 failures,
  0 errors**.
- Dart: the eight affected namespaces recompiled and run — all pass.
- clj-kondo on the changed files: 0 errors, 0 warnings; cljstyle:
  clean.
- Fixture digests unchanged (above) — no re-pin, ledger pin untouched.

Tooling: same spawn route as the main round (`bb`/`mise` remain
sandbox-blocked; logs under `target/d9-*.log`).

## 4. New unresolved concern found while building the rows (not fixed here)

A halted machine whose RESULT holds cursor references cannot lift on
either version today: `export-task` reads `cells (:cells @found)`
before the `:yin.k/result` encode (which is what mints those cells on
a halted root), so the self-census answers a missing cell
(`:yin.k/undecodable`, `:path [:yin.k/cells]`). Pre-existing (D7-era
ordering), surfaced by an early probe construction; blocked and parked
roots are unaffected because their graphs encode before the census
read. Left for the architect to assign (one-line fix: read the cells
after the body's halted branch, or encode the result before the
snapshot); no test pins it this round.
