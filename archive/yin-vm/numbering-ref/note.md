# Cursor-cell numbering by discovery order (companion to the row carrier)

Status: **Adopted by the owner 2026-10-12 (replaces carrier §6's
refinement and search); not yet implemented.** Round 4 of the same day
(Architect, claude-fable-5-1): the base design the owner asked for after
the third review (`collab/1792210000000-…review-r3…findings.md`), with
the round-3 variants moved to appendix B as an extension that is not
adopted; revised the same day after the fourth review
(`collab/1792230000000-…review-r4…findings.md`: no canonicality
counterexample in 5,000 states; the depth gate moved before every
recursive scan, the representation estimator made budget-aware, the
probe's decoder put in the production order, and the wording of the
termination measure, the incomplete-key example and the refusal figures
corrected). Subordinate to
[`yin.vm.ucf-transport-tuples.md`](./yin.vm.ucf-transport-tuples.md) (the
carrier, body version 3) and
[`yin.vm.semantic-register-vm.continuation.md`](./yin.vm.semantic-register-vm.continuation.md);
vocabulary is UCF §7.5.3 ("Terminology: cursor, cursor cell, cursor-ref,
cell") exactly. Evidence is slice 6a's `src/cljc/yin/vm/ucf/rows.cljc`
and `test/yin/vm/ucf/rows_test.cljc` (branch `srvm-phase6a` at
`d77da9f0`) and the prototype of appendix A. §§1–6 are normative; the
appendices are not.

**The rule in one paragraph.** A cursor cell is named by the order in
which a fixed walk over the task meets it: the n-th cursor cell met is
`:yin.k/cc-n`, as a de Bruijn index names a binder by position. The walk
is pre-order in the carrier's table order, so every position is ordered
except the members of a guest set and the entries of a guest map. Those
the carrier *transforms* into ordered vectors before numbering: each
member is given a key, the spelling of the member in which cursor cells
already numbered appear by number and the others by body, occurrence
count and the order in which they first appear inside the member; the
members are sorted by key. Members whose keys are equal are *tied*; they
are postponed until the rest of the walk has numbered what it can, and
re-keyed. A tie that survives is entered in any order if a cheap test
proves its members interchangeable (swapping them leaves the whole state
unchanged); otherwise the lift refuses. Guest programs are unrestricted:
sets and maps may hold cursor-refs anywhere, including as map keys.

**Example.** `[P Q P]` numbers `P = cc-1`, `Q = cc-2` whatever the bodies.
In `{'a #{X Y}, 'b X}` with equal bodies, the set is reached first; `X`
occurs twice in the task and `Y` once, so their keys differ and `Y` is
entered first: `Y = cc-1`, `X = cc-2` (the root is 6a's, `2d9873a8…`).
In `#{[A B] [B A]}` the two pairs have equal keys and share both cells;
the test cannot prove them interchangeable (they are) and the lift
refuses `:unresolved-cursor-cells`. In `#{[A B] [B C]}` the keys differ
(`B` occurs twice) and the path numbers `A, B, C = cc-1, cc-2, cc-3`.

## 1. The rule

**Vocabulary.** An **item** is a row or a value. The **walk** is the
procedure of §1.2 over one item from a numbering; the **root walk** is
over the `:task` row from the empty numbering and its numbers are the
output; a **key walk** is the same procedure over one member of a set or
map from the enclosing walk's numbering, run only to produce the member's
**key**. A **member** is a `:set` element or a `:map` entry taken as the
synthetic ordered row `[:entry key value]`. A **tie group** is a run of
members of one container with equal keys. A cursor cell's **body rank**
is the index of its census body label `[:body [:stream identity
descriptor] position]` among the census's distinct labels in bytewise
order; its **occurrence count** is the number of positions of the task
(census and install children excluded) that hold it. Install children
are numbered as their own tasks by the same rule, in any order.

### 1.1 The transformation and the key

When the walk reaches a `:set` or `:map` it replaces the container, for
its own purposes, by the vector of its members in key order. The key of
a member is the **spelling** of that member produced by a key walk under
the numbering at that moment (every member of one container is keyed
from that one numbering):

- a cursor cell numbered by the enclosing walk spells `[:cursor-cell
  [:n k]]` (root numbers) or as the enclosing key walk spelled it;
- a cursor cell the key walk meets unnumbered receives a **local index**
  `j` (the order of first meeting, counted from the enclosing walk's
  local counter so a nested key walk never reuses an index of its
  enclosing one) and spells `[:cursor-cell [:u j r m]]`, `r` its body
  rank and `m` its occurrence count, all exact CBOR integers;
- `[:lit d]` as itself, structural data as itself (by reference, never
  copied), `[:row B]` expanded in place, the census as `[:cursor-cells]`,
  an install child as `[:child]`, keyed structural rows in canonical key
  order, `:window` in register order, and a nested `:set` or `:map` as
  the bytewise sorted vector of its members' spellings in which a tie
  group the key walk could not certify (§1.3) is spelled in place as
  `[:tie K … K]`, its common key once per member.

Keys are compared bytewise on canonical CBOR; the spelling is hash-free.
`[X X]` keys `[:vec [u0 r m] [u0 r m]]` and `[Y Z]` keys
`[:vec [u0 r m] [u1 r m']]`: the alias pattern inside a member is part
of its key, and so is each cell's occurrence count, which separates
`#{[A B] [B C]}` and `[#{X Y} #{Y Z}]` without a search (appendix A).

### 1.2 The walk

**W1, order.** Depth-first, pre-order, from the walk's item. A row's
slots in table order (`rows/row-table`, `rows.cljc:37-57`); ordered
repeated slots (`frames`, `:vec`, `:list`, `:k`) in order; keyed rows
with structural keys (`:bindings`, `:store`, `:parked-table`, the root's
`installs` and `module-stores`) in the Jing canonical byte order of the
key; `:window` in ascending register order. The census is not entered;
an install child's `:task` row is not entered. A `:set` or `:map` is
entered as §1.1 orders it: members with a unique key in key order, an
entry's key before its value; each tie group is **postponed** to the
walk's queue (queue order is canonical: containers in walk order, groups
within one container in key order).

**W2, meeting.** When the walk reaches a `[:cursor-cell C]` value or a
`C` slot of a `:pending` row whose cursor cell has no number, the cell
receives the next number (the root walk counts from 1). A numbered cell
is passed over. Every occurrence emits `[:cursor-cell :yin.k/cc-n]`; the
census row stays the contents table.

**W3, postponed ties.** After the pre-order walk, the queue is taken in
order: each group's members are re-keyed under the current numbering and
treated as in W1 (unique keys entered in key order, surviving ties
re-queued in order; a tie met inside an entered member joins the same
queue). Repeat while a pass numbers at least one cursor cell and groups
remain. When a pass numbers nothing, the surviving groups go to the tie
test.

### 1.3 The tie test

A surviving group **passes** when all of (i) its common key is complete
(contains no `[:tie …]`), (ii) the unnumbered cursor cells of its members
are pairwise disjoint, and (iii) none of them occurs in a member of any
other surviving group of the same walk. (At this point every unnumbered
cell occurs only in postponed members, so (ii) and (iii) say: the cells
of each member are confined to that member.) If every surviving group
passes, all of them are entered, each group's members in the order the
host lists them (§2 shows the rows do not depend on it), and the walk
returns to W3. If any group fails, the **root walk refuses** (§1.4) and a
**key walk stops**: its surviving groups are spelled as `[:tie …]`
residue and its key is incomplete. The test inside a key walk reads only
the member being keyed; it never reads the enclosing state.

This is a sound test, and a cheap one: (i)–(iii) are exactly what the
proof of §2 needs for the index-matched swap of two members to be an
automorphism of the state; whatever it refuses may still be
interchangeable (§3 says what), but nothing it accepts can be host-order
dependent. No claim is made that it is the cheapest such test, or that
every sound test needs a search for what it refuses.

### 1.4 Refusal, and decode

The root walk refuses `:yin.k/non-portable` kind
`:unresolved-cursor-cells`, `:yin.k/path` the row path of the container
holding the first failing group in queue order (a container nested under
a member of a set or map has the enclosing container's path followed by
`:member`, since no index into an unordered container is canonical; for
an install child the path is prefixed `[12 <install name> …]`), and
`:yin.k/cursor-cells` the sorted unnumbered cursor cells of the failing
groups' members. The verdict and the path are functions of the state up
to renaming and host enumeration; the cell list names local source ids.
This is not a resource refusal and never `:noncanonical-cursor-cells`.

The receiver decodes, recomputes the numbering of the root and of every
install child, re-emits and compares root ids
(`:yin.k/undecodable :noncanonical-cursor-cells` on mismatch). In 6a's
`decode*` the recomputation is the `emit` call (`rows.cljc:570-575`);
the **whole** of that call is wrapped so that only a lift-side
`:yin.k/non-portable :unresolved-cursor-cells` (root or child) becomes
`:yin.k/undecodable :unresolved-cursor-cells` with `:yin.k/path` prefixed
by the carried root id (a child's path reads `[<root> 12 <name> …]`); a
resource refusal propagates as itself; nothing else can arise there,
since step 5 has checked literals and markers. The validator order stays:
numbering (step 6) after step 5 and before `validate-frames`
(`rows.cljc:576-578`), so a wrong numbering is reported as
`:noncanonical-cursor-cells` and an unresolved one as
`:unresolved-cursor-cells` even when the task also has a frame defect
(test `numbering-precedes-frame-defect`, run against the rewritten
decoder with both carriers; the prototype's decoder reproduces this
placement, appendix A).

### 1.5 Budgets and gates

- `:cursor-cells`: the census's pair count is charged first, before the
  census map, ranks, counts or any walk is built (6a's gate at
  `rows.cljc:256`, kept, moved before the label preparation).
- `:depth`: enforced second, **before any recursive traversal of the
  task**, by one bounded iterative scan (an explicit stack, never the
  host stack) over rows, members, structural and literal data and the
  census, install children excluded (each is gated by its own run). The
  depth of a position is its slot steps from the task (a member step
  counts one, a map entry's key or value two) plus, inside data, its
  collection nesting; a position deeper than `:depth` refuses
  `[:depth]`. After the scan every recursive read of the task (cell
  scans, occurrence counts, memo-key hashing, spelling, encoding) nests
  at most `:depth` levels, so no input can overflow the host stack
  before the gate fires: one cell inside 3000 nested vectors under
  `{:depth 3}` refuses `[:depth]`, as does a literal nested 3000 deep.
  The walk's own check (6a's gate at `rows.cljc:291-292`) is kept as
  well. Default 256, unchanged.
- `:work`: one unit per position scanned (the depth scan), visited
  (walk, key walk, cell scans, count and rank preparation), keyed (one
  per member per keying, so the number of key walks and memo lookups is
  charged) or spelled, one unit per cell in a key walk's memo key, and,
  **before every CBOR encoding made for a comparison** (keys, rank
  labels, structural keys), the representation bound of the value
  encoded: 6a's `size` of `check-label-bytes!` (`rows.cljc:218-229`),
  128 per node or scalar, 4 per character, 16 plus the length per byte
  payload, an upper bound of the encoding's bytes. The estimator is
  iterative and budget-aware: it runs against the budget remaining at
  the call and stops as soon as its running total exceeds it, so it
  takes at most `remaining/16` steps on any input and never reads to the
  end of a value it cannot afford (a set holding a 400000-scalar literal
  under `{:work 900000}` refuses `[:work]` after 779 estimator steps;
  under `{:work 2000}` the depth scan refuses at its 2001st position and
  the estimator is never entered). Spellings hold input literals by
  reference, so their own size is their spelled positions. What this
  buys, stated exactly: every comparison encoding is charged before it
  is made, by a conservative abstract bound of its encoded bytes and of
  the encoder's intermediate nodes; the encoder's intermediates and the
  comparison byte vector occupy more host heap than the payload length,
  so the budget bounds allocation up to a constant factor of the host's
  representation, not byte for byte, and the input itself is not
  counted. Preparation is bounded and charged: hashing a memo key reads
  data the scan has already charged, and the number of such reads is
  charged. No polynomial bound is claimed; the budget is the bound.
  Default `4194304`, the scale of the deleted `:label-bytes` (appendix
  A: at most 625518 over 3300 generated states, 544659 with a
  65536-character stream descriptor). A literal of more than about 30000
  scalars inside a guest set or map exceeds it (6a allowed about 8000);
  in an ordered position a literal is never spelled or encoded and costs
  one unit per position of its data in the depth scan (a 400000-scalar
  literal beside a cell numbers at 401323).
- `:rows`, `:bytes`: unchanged. `:label-bytes` and `:search` are deleted.

Exceeding any budget is the resource refusal of carrier §10.10
(`:yin.k/unsupported :resource-limit [<key>]`), never a canonicality
verdict, at lift and at decode alike.

### 1.6 Totality

The root walk ends with an empty queue or a refusal. Before emission the
implementation asserts that the numbering is a bijection from the
census's cursor cells onto `1 … n`; a violation (a census cell no walk
reaches) is a defect, an exception without `:yin.k/status` carrying the
missing and extra cells, never a refusal and never a partially numbered
carrier. 6a's test `input-row-bodies-do-not-spend-label-bytes`
(`rows_test.cljc:406-421`) declares exactly such a cell in `base` and
expects `emit` to succeed (`:414`); §4 rewrites it.

## 2. Proof of canonicality

Call two configurations (abstract task, numbering, queue) **isomorphic**
when a bijection `π` of cursor cells carries one to the other up to the
host order of every set, map and census, with `N'(π(c)) = N(c)` and
queues corresponding group by group. **Theorem:** on isomorphic
configurations the procedure refuses at corresponding points or emits
byte-identical rows. The proof is an induction on the measure
`(size of the walk's item as a sub-term, unnumbered cells of the walk)`.

*Sort.* The key of a member is a function of the member and the
numbering up to isomorphism. The key walk reads structure, numbers,
ranks (a function of the census up to isomorphism) and occurrence counts
(an isomorphism invariant); it orders nested containers by the keys of
their members, canonical by the induction (a member is a proper sub-term,
so the measure decreases); its only other listed-order input is the
order in which it enters an interchangeable group, harmless by the tie
step below; and what it cannot certify it spells as `[:tie K … K]`, a
function of `K` and the group's size. Equal keys therefore mean
isomorphic members when the key is complete (a complete spelling is
faithful: structure slot by slot, every cell by local index, rank and
count, so matching indices is a bijection of the members' cells under
which the members agree).

*Walk.* W1 and W2 read only table order and keys; W3 repeats W1 on the
queue in a canonical order. So the numbering before the tie test, the
surviving groups and their keys are functions of the configuration up to
isomorphism, and so is the verdict of the test, whose inputs (key
completeness, the sets of unnumbered cells of members) are structural.

*Tie step.* Let `G` pass with members `e_1 … e_k`. Their keys are equal
and complete, so for each pair there is an index-matched bijection
`π_ij` of their unnumbered cells carrying `e_i` to `e_j`; the cells of
distinct members are disjoint (ii) and occur nowhere else in the state
(iii), so the permutation composed from these bijections, extended by
the identity, is an automorphism of the whole state that permutes the
members of `G` and fixes everything else. Entering `G` in two host orders
`σ`, `σ'` gives configurations related by that automorphism: numbers
agree by construction (the cells of the t-th entered member receive the
same block, by local index), structures agree, queues agree. The rest of
the procedure is equivariant by the theorem's induction, so the rows are
the same. Passing groups are mutually independent by (iii), so entering
all of them before the next pass changes nothing.

*Emission.* Cells are emitted by number only and every unordered slot is
sorted by canonical bytes under the final numbering (carrier §5), so
isomorphic final configurations emit the same rows. ∎

*Termination.* Premise: the members of one guest set or map are distinct
values (carrier §8 step 5, `check-abstract!`); so a passing group whose
members have no unnumbered cell cannot arise (equal complete spellings
with every cell numbered are equal members), and every passing group
numbers a cell or carries one into a sub-container. There is no search
and no re-entry: the only recursion is the key walk, over a proper
sub-term of its caller's item, so key walks nest at most as deep as the
structure (`:depth`). Within one walk the measure is the pair
`(unnumbered cells of the walk, multiset of the sizes of the queued
members)`, ordered lexicographically with the multiset order on the
second component, in three phases: the pre-order walk (W1–W2) is a
finite traversal that only numbers cells and enqueues; a W3 pass either
numbers a cell (first component decreases) or numbers none, in which
case either it changes nothing and the walk proceeds to the tie test, or
it enters members with unique keys and re-queues the rest (each entered
member leaves the queue, replaced by at most the tied members of its own
sub-containers, proper sub-terms: the multiset decreases); a tie step
enters passing groups, and each entered member numbers a cell (first
component decreases) or is replaced in the queue by proper sub-terms of
itself (second component decreases); a failing step ends the walk. Every
step decreases the measure or ends the walk, and the budget bounds the
count of steps.

Consequences: a function of structure, invariant under renaming and host
enumeration (the theorem with `π` a renaming, a permutation of
containers, or both); deterministic, so two lifts of one state are
byte-identical and the receiver's recomputation is well defined; sharing
survives (one number per cell, every occurrence spells it); the
cross-engine law of continuation §2.1 follows as before.

## 3. What is refused

Refused iff, when the root walk's last pass numbers nothing, some
surviving tie group fails §1.3. Concretely one of:

- **(i) an incomplete key**: a tied member contains a container whose
  own ties the key walk could not certify. A residue `[:tie K … K]`
  records the common key and the group's size, not how the tied members
  share cells, so equal incomplete keys need not mean isomorphic members:
  in `#{#{[A B] [B C] [C A]} #{[D E] [F E] [D F]}}` both inner sets have
  three members and every cell occurs twice, so both key `[:set [:tie K
  K K]]` with the same `K`, yet a directed 3-cycle is not a 3-edge
  orientation with a source and a sink; the state is refused and is
  **not** interchangeable (entering the inner sets in the two host
  orders gives different rows). `#{#{[A B] [B A]} #{[C D] [D C]}}` is
  refused by the same clause and is interchangeable.
- **(ii) two tied members share a cell**: the 2-cycle `#{[A B] [B A]}`,
  the 3-cycle, the loop plus 2-cycle `#{[L L] [A B] [B A]}`
  (`rows_test.cljc:312-329`, where `L` is separated by its key, `A B`
  are not), the mixed-body `#{[A B] [A C]}` with `A` on another stream
  position than `B, C` (all interchangeable and refused), and the
  seven-cell triangle plus square (`rows_test.cljc:392-397`), which is
  **not** interchangeable: a triangle edge entered first and a square
  edge entered first give different rows, so entering tied edges in host
  order is unsafe and this test rightly refuses it (a richer structural
  analysis could tell a triangle from a square; this test does not try).
- **(iii) a tied member's cell occurs in a tied member of another
  container**: `[#{X Y} #{X Y}]`, `[#{#{X Y}} #{[X Z] [Y W]}]`, and the
  reviewer's anchored state `[#{[A B] [A C] [B D] [C E] [D B] [E C]} A]`
  (`A` is numbered by its ordered position, which does not break the tie
  of the rest; all three interchangeable and refused).

Equal bodies are not required (the mixed-body case) and an ordered anchor
does not certify a whole component (the anchored case). 6a numbers the
2-cycle, the 3-cycle, the loop plus 2-cycle, `[#{X Y} #{X Y}]`, the
mixed and the anchored states, and refuses the seven-cell state and the
two-2-cycle state at `[:label-bytes]`. Discovery order numbers what 6a
refuses at `[:label-bytes]` when the keys separate the members, and
numbers the path and `[#{X Y} #{Y Z}]`, which 6a also numbers.

Measured (appendix A; refusals classified by entering the ties in every
listed order of every container when there are at most 720 such orders,
else 48 sampled orders):

| Generator | States | Numbered | Refused | of which interchangeable | not interchangeable | undecided |
|---|---:|---:|---:|---:|---:|---:|
| random values (1–6 cells, two bodies, nested sets, maps with cursor-ref keys, edge sets, sets of sets) | 2000 | 1981 | 19 | 16 | 3 | 0 |
| frame-bearing (windows, K frames, environments, parked state) | 200 | 196 | 4 | 3 | 1 | 0 |
| with install children | 100 | 98 | 2 | 1 | 0 | 1 |
| reviewer's tie-heavy incidence families | 1000 | 832 | 168 | 92 | 17 | 59 |

Every refusal was (ii) or (iii); none was (i). Invariance failures and
decode failures: 0 of 3300. The fourth review's independent 5,000-state
tie-heavy corpus (its own generator, renamer and classifier; rerun by the
author with the same seed, same counts):

| Independent corpus | States | Numbered | Refused | exhaustively interchangeable | demonstrably not | sampled equal, undecided |
|---|---:|---:|---:|---:|---:|---:|
| reviewer's incidence families, 250 with install children | 5000 | 4110 | 890 | 470 | 100 | 320 |

Root or verdict-and-path mismatches over two renamed and shuffled copies
per state: 0. "Undecided" means 48 sampled orders gave one root; that is
not a proof of interchangeability, and the 320 are reported as such. The
rates (0.95% of random values, 2% of frame-bearing states, 17.8% of the
tie-heavy corpus) are generator rates: no production corpus exists yet
(no version-3 lift before 6b), and they are not evidence of practical
coverage either way. Valid small shapes that 6a numbers (the 2-cycle)
refuse under this rule; that is the accepted tradeoff of A1, a
refusal-capable carrier whose callers keep the live task.

**Fixtures.** Every state named in this section and in §1's example is a
fixture of §5 (items 12–16), including the refused ones.

## 4. Replacing 6a: deleted, rewritten, kept

Against `srvm-phase6a` (`d77da9f0`), `src/cljc/yin/vm/ucf/rows.cljc` and
`test/yin/vm/ucf/rows_test.cljc` (line numbers of those files).

**rows.cljc, deleted.** `check-label-bytes!` (`:212-230`) as a bound on
labels (its `size` function, `:218-229`, survives as the representation
bound of §1.5, rewritten with an explicit stack and its `remaining`
argument taken from the `:work` budget); inside `canonical-numbering`
(`:236-355`): the label
seeding (`:252-255`), `ce-slot`/`ce` (`:257-284`), `positions`
(`:285-325`) except its depth gate, `classes` (`:326-330`), `refine`
(`:331-337`), `search` (`:338-354`), the `checked-*` wrappers
(`:245-251`); in `default-budget` (`:200-202`) the keys `:label-bytes`
and `:search`.

**rows.cljc, rewritten.**
- `canonical-numbering` (`:236-355`), same signature and contract
  (`task`, optional `budget` → `{source-id :yin.k/cc-n}`; empty census
  → `{}`): the census gate (`:256`) first, on the census row's pair
  count, before the `cursor-cells` map (`:243`) and the labels (`:252`)
  are built; the depth scan of §1.5 second; then body ranks and
  occurrence counts; then the root walk of §1.2 with its queue, the tie
  test, the refusal and the totality assertion, as in the prototype's
  sections "budgets", "cell scans", "runs", "spelling" and "procedure"
  (`collab/numbering-probe/src/probe/discovery.clj:84-520`), charging as
  §1.5 says. The depth scan and the estimator are the only traversals
  that may meet unbounded nesting; both use an explicit stack of lazy
  element sequences, never the host stack, and charge each position
  before doing work proportional to it. Portable `.cljc` per
  continuation §12: no `@#'` reflection; `mapv`/`into`/`reduce`, not
  `for`, over sequences that may exceed 32 elements (the prototype's
  `group-failure` uses `for`: replace); `(assoc {} …)` never `(assoc nil
  …)`; atoms are fine on all three hosts; the byte-payload branch of the
  estimator keeps 6a's host conditional (`:221`). 6a's `bytes-key`
  (`:91-94`) takes encoded bytes; the prototype's takes a value: keep
  6a's and encode first, after the charge.
- `descriptors` (`:117-124`): reused, with the synthetic `:entry` row
  added (`[:V :V]`).
- `default-budget` (`:200-202`): `:work` default `4194304`.
- `emit-node` (`:181-197`): thread a row path so a child's refusal
  reports `[12 <install name> …]`.
- `decode*` recompute (`:570-575`): the translation of §1.4 around the
  whole `emit` call.
- Namespace docstring (`:1-17`): "canonical cursor-cell numbering" →
  "discovery-order cursor-cell numbering (design note §1)".

**rows.cljc, kept unchanged.** `row-table`, `pending-table`, `node`,
`refuse!`/`bad!`, the marker checks, `bytes-key`/`compare-bytes`/
`byte-compare`/`sorted-bytes`, the census order (`:137-156`),
`emit-slot`/`emit-node` apart from the path, `spend!` (`:205-209`),
`emit-numbered` (`:358-363`), `emit` (`:366-372`), `structural?`,
`literal?`, `check-abstract!`, all of `decode*` before `:570` and after
`:575`, `decode`, `canonical-under`, `frame->rows`, `rows->frame`,
`validate-frames`, and the `:bytes`, `:rows`, `:depth`, `:cursor-cells`
keys with their defaults.

**rows_test.cljc, kept.** `task-node`, `cursor-cell-body`, `fixture`
(`:16-37`), `portable-frame` and `bijection`, `row-ids-are-jing-default`,
`replace-row`, `window-sorted-and-unique`,
`canonical-under-is-renaming-invariant` (`:143-156`),
`alias-topology-and-census` (`:159-168`), `gates-and-graph-before-hashes`,
`numbering-precedes-frame-defect` (`:206-213`),
`schema-and-literal-canonicality`, `child-scope-and-shared-child-dag`
(`:240-249`), `pending-envelope-and-nil-literal-are-preserved`,
`task-root-checks-carried-code-and-frame`, `reversed` and
`unordered-containers-are-order-free` (`:285-304`),
`census-is-numeric-from-ten-cursor-cells` (`:331-338`), the marker tests
(`:341-381`), `cursor-cell-and-identity-shaped-literal-are-distinct`
(`:384-389`), the fixture `seven-cursor-cell-triangle-square`
(`:392-397`).

**rows_test.cljc, rewritten.**
- `refinement-search-on-symmetric-cursor-cells-is-bounded` (`:171-178`)
  → `symmetric-cursor-cells-are-interchangeable`: the two assertions on
  equal roots and two numbers stay; the `{:search 1}` assertions become
  `{:work 1}` → `:yin.k/unsupported :resource-limit [:work]`.
- `canonical-roots-are-pinned` (`:307-328`): `fab223…` and `c4a7c7…`
  stay; the loop plus 2-cycle block (`:312-328`) asserts the
  `:unresolved-cursor-cells` refusal with path `[15]` and cells `[:a :b]`
  for the task and the same verdict and path for `renamed`; the
  `{:search 2}` assertion and the pin `55ff168e…` go.
- `intermediate-label-bytes-are-bounded` (`:400-403`) →
  `seven-cursor-cells-are-refused`: `:yin.k/non-portable
  :unresolved-cursor-cells`, path `[15]`, cells `[:a … :g]`, and the
  renamed and shuffled state gives the same verdict and path.
- `input-row-bodies-do-not-spend-label-bytes` (`:406-421`) →
  `large-literals-in-ordered-positions-are-not-spelled`: `base` (its
  census cell `:x` is unreachable) now asserts the totality defect of
  §1.6 (an exception whose data is `{:missing [:x] :extra []}` and has
  no `:yin.k/status`) in place of `(is (nil? (error #(rows/emit
  base))))` (`:414`); `reachable` and `custody` keep emitting and
  decoding to the expected tasks (`:415-421`); add: the same
  400000-element literal inside a guest set refuses `:yin.k/unsupported
  :resource-limit [:work]` under the default budget, under `{:work
  2000}` and under `{:work 900000}`, each within a bounded number of
  steps (§5 item 21).

## 5. Acceptance for the rewritten 6a numbering

12. `rows-test/discovery-order-numbering`: `canonical-numbering` returns
    the maps of appendix A for `fixture :s1 :s2 :x :y`, the review
    counterexample, `[x x y]`, `[x y y]`, `[#{X Y} X]`, the witness
    `[#{#{[A B] [B A]}} A]`, `[#{X Y} #{Y Z}]`, the path and the two
    frame fixtures.
13. `rows-test/occurrence-count-separates-members`: the review
    counterexample numbers `Y cc-1, X cc-2`, pinned `2d9873a8…` (6a's
    root); the path pinned `726a9fbb…`; `[#{X Y} #{Y Z}]` pinned
    `d84480d4…`; each equal after renaming plus reversal of every set.
14. `rows-test/interchangeable-ties`: `#{X Y}`, `#{[X X] [Y Y]}`,
    `{X 1, Y 1}`, `#{#{X Y} #{Z W}}` number without refusal; reversal and
    renaming give equal roots; pins `07eda35d…`, `da69c679…`,
    `49d4478c…`, `18b70de6…`.
15. `rows-test/alias-pattern-is-part-of-the-key`: `#{[X X] [Y Z]}` and
    `{X X, Y Z}` give one root for both enumerations and after renaming,
    pinned `387f294a…` and `be62ba43…` (6a's roots); the witness numbers
    `B cc-1, A cc-2`, pinned `0193312…` (6a's root), and decodes; the
    balanced-alias witness `#{[X X Y Y] [Z W Z W]}` (four cells of one
    body, every count 2, only the alias pattern and the local index
    separate the members) gives one root for both enumerations, pinned
    `5376f133…`, and the key without the local index would not.
16. `rows-test/unresolved-cursor-cells-refuse`: the 2-cycle, the
    3-cycle, the loop plus 2-cycle, the seven-cell state,
    `[#{X Y} #{X Y}]`, the mixed-body, the anchored, the two-2-cycle
    and the 3-cycle-beside-fan (§3 (i)) states refuse at `emit` with
    `:yin.k/non-portable`
    `:unresolved-cursor-cells`, the paths and cells of appendix A, the
    same verdict and path after renaming plus shuffling; a carrier of
    each built with `emit-numbered` decodes to `:yin.k/undecodable
    :unresolved-cursor-cells` with the path prefixed by the root, never
    `:noncanonical-cursor-cells`; a parent whose install `'a` holds the
    2-cycle refuses at `emit` with path `[12 a 15]` and its
    `emit-numbered` carrier decodes to the `:yin.k/undecodable` kind with
    path `[<root> 12 a 15]`.
17. `rows-test/canonical-roots-are-pinned`: `fab223…` and `c4a7c7…`
    unchanged; `a850fce6…` (`[#{X Y} X]`), `4993b5b1…`, `536c810b…`,
    `e67405ec…` (`[#{#{X Y} #{Z W}} X]`), `6e7c7a6b…` (ten cells) added;
    `55ff168e…` removed.
18. `rows-test/counterexample-search`: a seeded generator as in appendix
    A (at least 2000 random value states, 200 frame-bearing, 100 with
    install children) in CI: every numbered state's carrier decodes and
    re-emits to its root; renaming every cursor cell plus shuffling every
    set, map and census gives an equal root, or the same refusal verdict
    and path; refusals are counted and reported; the largest `:work` is
    reported.
19. `rows-test/numbering-is-total`: a census cell the walk does not reach
    raises the totality defect, not a refusal, before any row is emitted.
20. `numbering-precedes-frame-defect`, `alias-topology-and-census`,
    `canonical-under-is-renaming-invariant`,
    `unordered-containers-are-order-free`,
    `child-scope-and-shared-child-dag` and
    `census-is-numeric-from-ten-cursor-cells` pass unchanged against
    the rewritten decoder; `numbering-precedes-frame-defect` is
    extended: the 2-cycle with the same reserved-name binding, carried by
    `emit-numbered`, decodes to `:yin.k/undecodable
    :unresolved-cursor-cells`, not `:reserved-name`; the rewritten tests
    of §4 pass.
21. `rows-test/gates-and-work`: 65 cells refuse `:resource-limit
    [:cursor-cells]` under the default budget and 2 cells under
    `{:cursor-cells 1}`; `[[[[X]]]]` under `{:depth 3}` refuses
    `[:depth]`, and so do one cell inside 3000 nested vectors under
    `{:depth 3}` and under the default budget, and a literal nested 3000
    deep beside a cell (no host stack overflow on any of the three
    hosts; 200 nested vectors number); a set holding a 400000-scalar
    literal refuses `[:work]` under `{:work 2000}` with the work counter
    at 2001 and under `{:work 900000}` with at most 1000 estimator
    steps; a state whose bodies carry a 65536-character descriptor
    numbers under the default budget; `{:work 1}` refuses `[:work]` at
    `emit` and, for a valid carrier, at `decode`.
22. Budgets and docs: `:label-bytes` and `:search` gone, `:work` default
    `4194304`; A1–A9 of §6 applied; kondo clean; `bb test:sub yin.vm`
    green on three hosts; Node log shows `Testing yin.vm.ucf.rows-test`.

## 6. Amendments to the carrier and continuation documents

**A1 — carrier §6, from "The procedure." to the end of step 5.** Replace
with §1 above. Keep the section's first paragraph (the counterexample)
and amend "What is defined, and what is not claimed" to: "The canonical
numbering is defined as the output of the discovery-order procedure
below, a function of the state up to renaming of cursor cells and host
enumeration of guest sets, maps and the census (proof in
`yin.vm.semantic-register-vm.cursor-cell-numbering.md` §2). It is not the
minimum of the root bytes over all bijections. The one choice the walk
cannot make by itself, between tied members of one guest set or map that
the tie test cannot prove interchangeable, is refused
(`:unresolved-cursor-cells`); the refusal certifies nothing about
interchangeability and can be narrowed later without changing any
numbering assigned today."

**A2 — carrier §6 "Validation".** Replace the paragraph's last two
sentences, from "Every labeling that yields the selected commitment is
thereby accepted, and no encounter-order tie-break ever decides validity"
to the end, with: "Every labeling that yields the selected commitment is
thereby accepted. The emitted commitment and the acceptance decision are
invariant under the host's enumeration order of every set, map and
census (numbering note §2); the procedure's one use of host order,
entering the members of a tie group proved interchangeable, cannot
change the rows, and a receiver never decides validity by its own
enumeration order. This is a lower-side structural refusal, not a lift
kind. A receiver whose work bound (§10.10) is exceeded before the
procedure completes refuses with the structured resource refusal before
any attachment, never labels the state `:noncanonical-cursor-cells`, and
never accepts an unfinished check; a receiver that reaches the tie
refusal refuses `:yin.k/undecodable` kind `:unresolved-cursor-cells`."

**A3 — carrier §5 preamble.** "The class-erased skeletons of §6 are used
only inside the numbering procedure and never decide the order of an
emitted row" → "The spellings (keys) of §6 are used only inside the
numbering procedure and never decide the order of an emitted row".

**A4 — carrier §8 step 6.** "Recompute §6 under the work bound, for the
root and every install child, and compare (`:yin.k/undecodable`, kind
`:noncanonical-cursor-cells`; kind `:unresolved-cursor-cells` if the tie
refusal is reached in the root or in a child, translated from the
recomputation's lift-side refusal of that kind only, its path prefixed
by the carried root id; or the resource refusal of §10.10 if the bound
is exceeded, which propagates untranslated)." In the diagnostics
paragraph after step 12 add `:unresolved-cursor-cells` to the
`:yin.k/undecodable` kinds; "the two kinds this carrier adds to UCF
§7.5.4's set: `:byte-string` and `:metadata`" → "the three kinds …,
`:metadata` and `:unresolved-cursor-cells`"; in §11 "two lift kinds and
two `:yin.k/undecodable` kinds are added" → "three … and three …".

**A5 — carrier §10.** Answer 9: "a renaming-invariant
refinement-and-search procedure" → "the discovery-order procedure, which
is invariant under renaming and host enumeration". Answer 10: "a bound
on decoded bytes, rows, cursor cells, traversal depth, refinement work
and search nodes for §6" → "a bound on decoded bytes, rows, cursor cells,
traversal depth and traversal-and-spelling work for §6 (its `:work` unit
counts positions and representation bytes, charged before any spelling
is encoded)"; "never falls back to encounter order" → "never substitutes
host enumeration order for the canonical walk". Proposals list: "as
defined by its procedure" → "as defined by discovery order, and the
`:unresolved-cursor-cells` refusal".

**A6 — carrier §3, identity rows.** After "minted by the lift under §6's
canonical numbering" add "(the n-th cursor cell met in §6's canonical
walk)".

**A7 — continuation §2.2.** "numbered by the draft's §6 procedure" →
"numbered by discovery order (draft §6 as amended by
`yin.vm.semantic-register-vm.cursor-cell-numbering.md`)".

**A8 — continuation §5.5.** (i) "… and the only other identity it
consumes is the stream marker in each cursor cell's initial label
(`[:body <stream-marker-bytes> <position-bytes>]`, draft §6 step 1) and
in cursor-cell bodies" → "… and the only other identity it consumes is
the stream marker in each cursor cell's body label (`[:body [:stream
identity descriptor] position]`, draft §6 as amended, spelled by rank
inside keys) and in cursor-cell bodies". (ii) "Unordered containers
(`:set`, `:map`, `:store`, `:bindings`, the parked table) are encoded by
sorting their elements' encodings bytewise (draft §6 step 2), never by
host traversal order" → "Guest sets and maps are entered in the bytewise
order of their members' keys, and `:store`, `:bindings` and the parked
table in the byte order of their structural keys (draft §6 W1), never in
host enumeration order". (iii) "never derived from traversal order" →
"never derived from *host* traversal order (the canonical walk of draft
§6 is a function of the state and is what the procedure reads)".

**A9 — continuation §8.1 acceptance.** Item 10 stands; append items
12–22 of §5.

**A10 — UCF §7.5.3.** No change.

## 7. Owner questions

1. The key spells each unnumbered cell's **occurrence count** besides its
   body rank and local index (§1.1). This is one integer per cell from
   one pass; it is what numbers the path and `[#{X Y} #{Y Z}]` without a
   search and what makes the review counterexample, `[#{X Y} X]`, the
   codex A/B states and the witness reach 6a's own roots. Appendix A
   measures its effect (295 of 1000 tie-heavy states, 20 of 2000 random
   ones numbered only because of it; nothing lost). Keep, or drop to the
   literal "body plus local index" (a one-line change, fewer states
   numbered, different pins)?
2. `:work` default `4194304`, replacing 6a's `200000` positions plus
   `1048576` label bytes by one budget that counts both. Agreed?

---

# Appendices (non-normative)

## A. Prototype and measurements (round 4)

`collab/numbering-probe/src/probe/discovery.clj` (scratch project under
`collab/`, never committed) implements §1 exactly: one walk procedure
used for the root walk and for key walks, memoized on (member, numbering
restricted to the member's cells, local counter); the gates and the
charging of §1.5 (the census pair count, then the iterative depth scan,
before anything recursive; the iterative budget-aware estimator before
every comparison encoding); the totality assertion; an emitter that
numbers install children by the same procedure; a decode path in the
production order: 6a's `decode*` steps 1–5 with its numbering check off
and its frame validation suppressed, then the whole discovery
recomputation under §1.4's translation, then the root comparison, then
6a's `validate-frames` (`rows.cljc:576-578`). Two diagnostic switches: `:permissive` (a failing
group is entered in listed order, one group at a time, instead of
refusing; used only to decide whether a refused state is in fact
interchangeable, by trying every listed order of every container when
there are at most 720 such orders, else 48 samples) and `:count-spelling
false` (the occurrence count left out of the key). The round-3 prototype
is kept unchanged as `probe/discovery_r3.clj` for appendix B. Run from
the probe directory (`run.sh`; JVM only; the 6a copies there match
`srvm-phase6a` by SHA-1):

```
mise exec -- java -Xmx2g @java.args clojure.main -e "(require 'probe.discovery) (probe.discovery/-main)"
```

Results of 2026-10-12, round 4 (`collab/numbering-probe/run.log`; 6a's
own tests pass unchanged in the same project, 21 tests, 324 assertions).
Roots marked (6a) equal 6a's root for the same state.

| State | Numbering | Root | `:work` |
|---|---|---|---:|
| `fixture :s1 :s2 :x :y` (`rows_test.cljc:28`) | `x cc-1, y cc-2` | `fab223…` (6a) | 37328 |
| review counterexample `'a #{X Y}`, `'b X` | `y cc-1, x cc-2` | `2d9873a8…` (6a) | 3524 |
| `#{X Y}` | `x cc-1, y cc-2` | `07eda35d…` (6a) | 3251 |
| `[x x y]` / `[x y y]` (`:159`) | `x cc-1, y cc-2` | `4993b5b1…` / `536c810b…` (6a) | 1338 |
| `#{[X X] [Y Y]}` / `{X 1, Y 1}` | `x cc-1, y cc-2` | `da69c679…` (6a) / `49d4478c…` (6a) | 6269 / 5177 |
| codex A `#{[X X] [Y Z]}`, both enumerations | `y cc-1, z cc-2, x cc-3` | `387f294a…` (6a) | 6281 |
| codex B `{X X, Y Z}` | `y cc-1, z cc-2, x cc-3` | `be62ba43…` (6a) | 6291 |
| codex C `#{#{X Y} #{Z W}}`, and renamed | first listed inner set `cc-1, cc-2` | `18b70de6…` (6a) | 13945 |
| `[#{X Y} X]` | `y cc-1, x cc-2` | `a850fce6…` (6a) | 3256 |
| `[#{#{X Y} #{Z W}} X]` | `y cc-1, x cc-2, z cc-3, w cc-4` | `e67405ec…` | 13946 |
| witness `[#{#{[A B] [B A]}} A]` | `b cc-1, a cc-2` | `0193312…` (6a) | 16642 |
| balanced-alias witness `#{[X X Y Y] [Z W Z W]}`, both enumerations | `x cc-1, y cc-2, z cc-3, w cc-4` | `5376f133…` (the reviewer's) | 10127 |
| `[#{X Y} #{Y Z}]` | `x cc-1, y cc-2, z cc-3` | `d84480d4…` | 4933 |
| path `#{[A B] [B C]}` | `a cc-1, b cc-2, c cc-3` | `726a9fbb…` | 6282 |
| ten cells in a vector (`:331`) | `cc-1 … cc-10` in order | `6e7c7a6b…` (6a) | 12587 |
| frame fixture: wait window `x`, K-frame window `y`, environment `y`, parked K-frame window `#{x y}` | `y cc-1` (free environment, slot 5), `x cc-2` (parked table, slot 8), `:test/cursor cc-3`; decodes | `0b9ef61e…` | 4058 |
| frame fixture: wait window `#{x y}`, parked K-frame window `y` | `y cc-1, x cc-2, :test/cursor cc-3`; decodes | `ff124bfc…` | 3316 |
| parent with two installs sharing the review-counterexample child | child `y cc-1, x cc-2`; one child row shared; decodes | `09124ab9…` | — |
| 400000-element literal beside a cell in a vector | numbers | `be7fc44e…` | 401323 |
| one cell inside 200 nested vectors | numbers | `f39ae042…` | — |

Every root above is unchanged from the first round-4 run; the `:work`
figures rose by the depth scan's positions and the per-member keying
charge.

Refused (all `:yin.k/non-portable :unresolved-cursor-cells`; verdict
and path equal over 24 renamings plus shuffles; "interchangeable" is the
exhaustive permissive verdict):

| State | Path | Cells | Reason | Interchangeable | 6a |
|---|---|---|---|---|---|
| 2-cycle `#{[A B] [B A]}` | `[15]` | `a b` | shared within group | yes | `0fe898cc…` |
| 3-cycle | `[15]` | `a b c` | shared within group | yes | `0b181cd4…` |
| loop plus 2-cycle (`:312`) | `[15]` | `a b` | shared within group | yes | `55ff168e…` |
| seven-cell triangle plus square (`:392`) | `[15]` | `a … g` | shared within group | **no** | `[:label-bytes]` |
| `[#{X Y} #{X Y}]` | `[15 0]` | `x y` | shared across groups | yes | `92747c0f…` |
| mixed bodies `#{[A B] [A C]}` | `[15]` | `a b c` | shared within group | yes | numbered |
| anchored `[#{…} A]` | `[15 0]` | `b c d e` | shared across groups | yes | numbered |
| `#{#{[A B] [B A]} #{[C D] [D C]}}` | `[15]` | `a b c d` | incomplete key | yes | `[:label-bytes]` |
| `#{#{[A B] [B C] [C A]} #{[D E] [F E] [D F]}}` (§3 (i)) | `[15]` | `a … f` | incomplete key | **no** | `[:label-bytes]` |
| `[#{#{X Y}} #{[X Z] [Y W]}]` | `[15 0 :member]` | `w x y z` | shared across groups | yes | numbered |
| `#{[#{X Y} #{X Y}] [#{P Q} #{R S}]}` | `[15 :member 0]` | `x y` | shared across groups | yes | `[:label-bytes]` |
| install child holding the 2-cycle | lift `[12 a 15]`; decode of an `emit-numbered` carrier: `:yin.k/undecodable`, path `[<root> 12 a 15]`, cells `[cc-1 cc-2]` | | | | |

Gates: two cells under `{:cursor-cells 1}` and 65 cells under the
default budget refuse `[:cursor-cells]`; `[[[[X]]]]`, one cell inside
3000 and inside 10000 nested vectors, and a literal nested 3000 deep
beside a cell, all under `{:depth 3}`, refuse `[:depth]`, as do the 3000
nested vectors and a 3000-deep literal inside a set under the default
budget (the first round-4 prototype overflowed the host stack on these,
the fourth review's HIGH finding); `{:work 1}` refuses `[:work]` at lift
and, for a valid carrier, at decode; the 400000-element literal inside a
set refuses `[:work]` under the default budget (work counter 801355 at
the refusal, 26516 estimator steps), under `{:work 2000}` (the depth
scan refuses at position 2001; the estimator is never entered; the first
round-4 estimator took 400012 steps here) and under `{:work 900000}`
(the scan's 800k positions pass, the estimator aborts after 779 steps);
an unreachable census cell is the defect `{:missing [:x], :extra []}`.
The 65536-character descriptor state numbers at `:work` 544659 (21395 at
128 characters; encoded comparison bytes 132540 against 1718, all
charged before encoding).

Decoder order (`rows.cljc:570-578` reproduced): the 6a fixture with a
reserved-name binding carried under a wrong numbering decodes to
`:noncanonical-cursor-cells`, under the right numbering to
`:reserved-name`, and the 2-cycle with the same binding to
`:unresolved-cursor-cells`; a valid carrier decodes.

Random search (seeded; each state compared with itself renamed and
shuffled, and decoded through 6a's decoder, whose own retained
recomputation budget stops 397 of the carriers at `[:label-bytes]`: a
probe limitation, not a failure):

| Generator | Numbered | Refused (interch. / not / undecided) | Only across groups | Count gain | Invariance fail | Decode ok / fail | max `:work` |
|---|---:|---:|---:|---:|---:|---:|---:|
| random values, 2000 | 1981 | 19 (16 / 3 / 0) | 7 | 20 | 0 | 1789 / 0 | 625518 |
| frame-bearing, 200 | 196 | 4 (3 / 1 / 0) | 0 | 4 | 0 | 179 / 0 | 203320 |
| with children, 100 | 98 | 2 (1 / 0 / 1) | 1 | 0 | 0 | 75 / 0 | 434483 |
| incidence families, 1000 | 832 | 168 (92 / 17 / 59) | 74 | 295 | 0 | 667 / 0 | 453499 |

The fourth review's independent generator (`collab/numbering-probe/codex-gen.clj`,
the reviewer's r3 script with the D5/D6 lines removed, seed `179221`,
two renamed and shuffled copies per state; log `codex-gen.log`), rerun
by the author: 5000 states, 250 with install children; 4110 numbered,
890 refused, 0 mismatches of root or of verdict and path; among the
refused, 470 exhaustively interchangeable, 100 demonstrably not, 320
sampled-equal and undecided (the author's classifier reproduces the
reviewer's counts exactly); of the first 100 states, 60 decode and 14
stop at 6a's retained recompute budget, 0 other decode failures.

"Only across groups" counts refusals whose every failing group failed
only clause (iii): an upper bound on what the component rule of appendix
B could recover. "Count gain" counts states numbered only because the
key carries the occurrence count; the reverse count is 0 everywhere. A
test that simply entered ties in host order would have accepted the 21
states classified not interchangeable, with host-order-dependent rows:
that is why a sound test is needed at all.

Size: the prototype's procedure region (`discovery.clj:84-520`) is 405
non-blank lines (comment lines included), of which roughly 120 are
docstrings, comments, trace and the two diagnostic switches, and about
110 are the depth scan and the estimator added for the fourth review;
6a's `canonical-numbering` plus `check-label-bytes!` is 136 non-blank
lines. In `rows.cljc` the replacement reuses `descriptors`, `bytes-key`,
`compare-bytes`, `spend!`, `refuse!` and `emit-node`; expect about 280
added or replacement lines and about 135 deleted, excluding tests: a
planning estimate, not a diff, and not a smaller implementation than 6a.

## B. Round 3's variants: components (D4), conflicts (D5) and the residue search (D6). Optional extension, not adopted

Round 3 (`collab/1792220000000-numbering-note.partial-round4.md`, 980
lines, and `probe/discovery_r3.clj`) resolved surviving ties differently:
link tied members that share an unnumbered cell into **components**;
encode each component by a local run over a synthetic
`[:component g₁ i₁ g₂ i₂ …]` row; enter the components of least
encoding; a component holding two members of one group is a **conflict**,
refused (D5) or resolved by **trials** (D6): one local run per member of
the duplicated group with that member entered first, the least complete
spelling adopted, charged to `:search`. The base design of §1 keeps none
of it: no component runs, no trials, no `:search` budget. What the
extension would add over the base: `[#{X Y} #{X Y}]` and the other
"shared across groups" refusals (at most 82 of 3300 generated states,
appendix A) via components, and with D6 every state of appendix A's
refused table, including the seven-cell state (11 trials) and 6a's own
roots on the 2-cycle and 3-cycle.

*Proof sketch (round 3, lemmas C and E).* Equal complete component
encodings are faithful spellings and exhibit a bijection under which the
components are the same structure group by group; since the cells of
distinct components are disjoint and confined, the block swap is an
automorphism, so entering a run of equal encodings in host order gives
the same rows. For D6, the multiset of trial spellings is canonical if
each trial is, and equal least spellings give isomorphic numberings. The
third review found the induction incomplete: a trial starts from the
same numbering and the same cells as its parent, the forced member need
not number a cell before recursive work begins, and key runs under D6
search instead of spelling residue; a well-founded measure covering key
runs, synthetic component runs, forced traversal and recursive trials
was never supplied. That, with the reviewer's judgement that D6 is not
materially simpler than 6a, is why it is not adopted.

*Measurements (round 3, `collab/numbering-probe/run-r3.log`).* Under D6:
2000 random value states, 200 frame-bearing and 100 with children all
numbered, 0 invariance failures, 0 decode failures, at most 13 trials
and 1470 `:work` units (round-3 accounting, positions only); the 2-cycle
and 3-cycle reached 6a's roots (`0fe898cc…`, `0b181cd4…`), the loop plus
2-cycle `858a891e…`, the seven-cell state `d76d787a…` in 11 trials and
488 units; the reviewer's independent 5000-state run found no
canonicality counterexample in either mode and 2117 D5 refusals.

## C. Why not portable ids minted in the VM

The owner asked whether a cursor cell could carry a portable id from
creation. Every candidate (branch `srvm-phase6a`,
`src/cljc/yin/vm/engine.cljc`: the task's id counter `gen-id` `:227-230`
and `gensym` `:2312-2318`; the creation sequence of `:stream/cursor`
executions, `handle-cursor-unminted` `:557-568`, minted at `:2595-2602`;
a hash of occurrence and counter; a random id) names history, not
structure. Two tasks with structurally equal snapshots (two cursor cells
with equal bodies at equal positions, equal counters) carry different
id sets when one created and dropped a third cursor and the other
consumed an unrelated fresh name; the cross-engine law would then make
an execution trace part of the frame's identity, and lower would have to
keep a second global name beside its private resource entry. Discovery
order gives the identity a structural name instead. Recommended against.

## D. History

Round 1 (2026-10-12): discovery order proposed; the orbit lemma was
unsound (`#{[X X] [Y Z]}`) and nested ties were never processed
(`#{#{X Y} #{Z W}}`). Round 2: alias-carrying keys, the fixpoint, the
component rule; the review found speculative key-run conflicts refusing
wrongly and an install child's refusal escaping decode. Round 3: key
runs never refuse, the child translation, rank spelling, the `:work`
budget, D6; the review found the byte accounting unimplemented, the D6
induction incomplete, the coverage paragraph wrong, the `:cursor-cells`
and `:depth` gates dropped and the large-literal test incompatible with
totality. Round 4 (this text): the base design, with every one of those
findings addressed in §§1–4 and the variants moved here. The fourth
review found no canonicality counterexample in 5,000 states and
required: the depth gate before every recursive scan (the occurrence
scan overflowed the host stack at 3000 nested vectors), the estimator's
early budget abort restored, the probe's decoder in the production
order, a stale incomplete-key example, a precise termination measure
and honest refusal figures; all applied in this revision.
