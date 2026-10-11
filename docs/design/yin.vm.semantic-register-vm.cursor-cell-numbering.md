# Cursor-cell numbering by discovery order (companion to the row carrier)

Status: **Proposed, not landed.** Written 2026-10-12 (Architect,
claude-fable-5-1) for the owner's observation that "the problem is not
that the cursor cell has identity, but that the identity is local", and
the orchestrator's proposal to number cursor cells by **discovery order**
in a canonical traversal. Subordinate to
[`yin.vm.ucf-transport-tuples.md`](./yin.vm.ucf-transport-tuples.md)
(the carrier, body version 3) and
[`yin.vm.semantic-register-vm.continuation.md`](./yin.vm.semantic-register-vm.continuation.md);
vocabulary is UCF §7.5.3 ("Terminology: cursor, cursor cell, cursor-ref,
cell") exactly. Evidence is the carrier draft §5/§6 tables, slice 6a's
`src/cljc/yin/vm/ucf/rows.cljc` and `test/yin/vm/ucf/rows_test.cljc`
(branch `srvm-phase6a` at `d77da9f0`), and a read-only prototype run
against that code (§6). Nothing here is implemented.

**Recommendation in one line:** adopt discovery-order numbering now (§7,
option a): a function of structure, renaming-invariant, linear work,
provable by hand; it deletes carrier §6's refinement and search and
keeps every pinned root id except the one state only the search could
number.

## 1. Findings

**F1. Every non-guest position is ordered.** From the carrier §5 tables
(and `rows/row-table`, `rows.cljc:37-57`), the positions that can hold a
cursor-ref (`[:cursor-cell C]`) or a bare `C` slot, and their order:

| Position | Order | Canonical index |
|---|---|---|
| `:task` slots 5 `free-env`, 6 `store`, 8 `parked`, 15 `result`; `:wait` state, pending; `:act` env, window, k; `:tail` k; `:kframe` env, window; `:reified` act; `:env` and `:closure` bindings; `:module-store` store; `:pending` `cursor-cell-C`, `response-cursor-cell-C`, `value-V`, `envelope-V` | fixed slot | slot index |
| `:task` 7 `module-stores`, 12 `installs` | keyed by `M`, by module name | Jing bytes of the key; the child is a task boundary |
| `:task` 13 `frames`; `:k`; `:vec`, `:list` elements | ordered vector | position `i` |
| `:window` | keyed by register `N` | `N` ascending |
| `:bindings` (environments, closures, the free environment) | keyed by symbol `S` | Jing canonical bytes of `S` (§5 preamble: "sorted") |
| `:store`, `:parked-table` | keyed by `D`, by `P` | bytes of the key |
| `:set` elements | **unordered** | none |
| `:map` keys and values | **unordered** (keys are `V`) | none |
| `:cursor-cells` census | not a position (carrier §6 step 2, §8 step 3) | — |

So the environment's key order is canonical (bytes of the symbol as
spelled, the order `:bindings` rows are emitted in), and the only
unordered positions are guest `:set` elements and guest `:map` entries.
The example state in `collab/example-suspended-state.clj` holds its one
cursor cell (`:cursor-1`) in three ordered positions: the act's
environment under `c`, the window under register 2, and the `:next`
pending's `cursor-cell-C` slot.

**F2. Discovery order subsumes the positions step.** The carrier's §6
step 3 (positions) exists to let a cursor cell's number depend on where
it occurs. A canonical traversal (§2, D1) visits every ordered position
in a fixed order, so "the n-th cursor cell met" already encodes the
first position of each cursor cell; later positions cannot change an
assigned number and need not be collected. Refinement (step 4) and
search (step 5) existed only to turn position multisets into a total
order; discovery order is a total order by construction.

**F3. Unordered containers are ordered by content, then by numbers
already assigned.** A `:set` or `:map` is entered in the bytewise order
of a *content encoding* of its elements in which a numbered cursor cell
is spelled by its number and an unnumbered one by its body (stream
identity, descriptor, position). Elements with equal encodings form a
*tie group*. A tie group can be split by numbers assigned later through
ordered positions or through other containers (the review's
counterexample: `'a` bound to `#{X Y}` with equal bodies and `'b` bound
to `X`; `'b` numbers `X`, after which `#{X Y}` is no longer tied), so tie
groups are deferred and re-examined to a fixpoint (D3). No label ever
grows: the encoding of an element is linear in the element.

**F4. The residual tie class and the orbit rule.** What survives the
fixpoint is a tie group whose unnumbered cursor cells have equal bodies
and occur only inside tie groups. Two cases:
- *Orbit case*: every such cursor cell occurs only inside one element of
  the group. Then the elements are interchangeable and the group may be
  entered in any order: the emitted rows do not depend on the order
  (§3, lemma). `#{X Y}`, `#{[X X] [Y Y]}` and `{X 1, Y 1}` are this case.
- *Residue*: a cursor cell of the group occurs in two elements, or
  twice across groups. Examples: `#{[L L] [A B] [B A]}` (the 6a pinned
  loop plus 2-cycle, `rows_test.cljc:312-329`) and the seven-cell
  triangle plus square (`rows_test.cljc:392-404`). Here the choice of
  which element to enter first can change the rows (a triangle vertex
  numbered 1 versus a square vertex), so discovery order alone cannot
  decide. The honest answer is a refusal (D5): `:yin.k/non-portable`
  kind `:unresolved-cursor-cells` at lift, naming the container's path
  and the cursor cells. Such a state needs two or more cursor cells on
  one stream at one position held only through symmetric guest
  collections of pairs; 6a refuses the seven-cell state too, by its
  label-byte budget (`rows_test.cljc:400-404`).

**F5. The scheme is canonical.** Proved in §3: a function of structure
alone, renaming-invariant, invariant under every permutation of
unordered containers, terminating in at most `n` rounds of linear work.

**F6. Prototype evidence (§6).** Over 400 random states (one to five
cursor cells, bodies drawn from four values so equal bodies are common,
nested vectors, sets and maps under keyed bindings and the result slot)
the prototype gave equal root ids after renaming every cursor cell and
shuffling every set and map, with no failure; it refused one state, the
6a procedure two. The pinned two-cell fixture root
`fab223…` and the ten-cell census root are unchanged; the review
counterexample and the alias fixtures receive new, hand-derived ids.

**F7. Portable ids minted in the VM are the wrong fix.** §5.

## 2. The rule (replaces carrier §6 "The procedure")

Input: the abstract task of carrier §6 (the §5 structure with cursor
cells as identities, before numbering). Output: a bijection from the
task's reached cursor cells to `:yin.k/cc-1 … :yin.k/cc-n`, or a
refusal. Install children are numbered as their own tasks by the same
rule (task boundary, unchanged), in any order, since scopes are disjoint.

**Body label.** `[:body [:stream identity descriptor] position]`, the
cursor cell's census body as plain data.

**Content encoding `CE(x)`** under the numbering assigned so far:
carrier §6 step 2 with one change. An identity position encodes a
numbered cursor cell as `[:cursor-cell [:n k]]` and an unnumbered one as
`[:cursor-cell <body label>]`; nothing else changes: `[:lit d]` as
itself, `[:row B]` expanded in place, structural data as itself, the
census as `[:cursor-cells]`, an install child as `[:child]`, and the
elements of `:set` rows and the `[key value]` pairs of `:map`, `:store`,
`:bindings` and `:parked-table` rows sorted bytewise, duplicates
retained. `CE` is hash-free, label-free and linear in its argument.

**D1. Traversal.** Depth-first, pre-order, from the root `:task` row.
A row's slots are visited in table order. Ordered repeated slots
(`frames`, `:vec`, `:list`, `:k`) in order. Keyed rows with structural
keys (`:bindings`, `:store`, `:parked-table`, `:window`, the root's
`installs` and `module-stores`) in the Jing canonical byte order of the
key, the order §5 emits. The census is not entered; an install child's
`:task` row is not entered. A `:set`'s elements and a `:map`'s entries
(each entry the pair `[key value]`) are sorted bytewise by `CE` under
the numbering at the moment the container is entered; a run of equal
encodings is a **tie group** and is deferred; the remaining elements are
entered in sorted order, an entry's key before its value.

**D2. Meeting.** When the traversal reaches an identity position (a
`[:cursor-cell C]` value or a `C` slot of a `:pending` row) whose cursor
cell has no number, the cursor cell receives `1 + (count of numbered
cursor cells)`. An already numbered cursor cell is passed over. There is
no first-occurrence spelling: every occurrence emits
`[:cursor-cell :yin.k/cc-n]` and the census row (carrier §3) stays the
contents table, so content rows keep sharing by Merkle id whether or
not they hold a cursor cell's first occurrence.

**D3. Fixpoint.** After the traversal, the deferred tie groups are
re-examined in deferral order: each is re-sorted by `CE` under the
current numbering; elements now alone in their encoding class are
entered (D1, D2 apply inside them); equal runs are deferred again.
Repeat while a round numbers at least one cursor cell.

**D4. Orbit rule.** For each surviving tie group let `U` be the
unnumbered cursor cells occurring in its elements. If every cursor cell
of `U` has all its occurrences in the task inside one element of this
group, enter the elements in the container's listed order. (By §3's
lemma the emitted rows are the same for every order.)

**D5. Refusal.** Otherwise the lift refuses `:yin.k/non-portable` kind
`:unresolved-cursor-cells`, `:yin.k/path` the container's row path, and
a `:yin.k/cursor-cells` list of the undecided cursor cells. A receiver
that reaches D5 while recomputing refuses `:yin.k/undecodable` kind
`:unresolved-cursor-cells`. Neither is a resource refusal and neither
is `:noncanonical-cursor-cells`.

**Validation** is unchanged in form: the receiver decodes, runs D1-D5,
re-emits and compares root ids (`:noncanonical-cursor-cells` on
mismatch). The work bound (carrier §10.10) keeps rows, cursor cells,
depth and work; the search and label-byte bounds disappear.

## 3. Proof of canonicality

Let `S` be an abstract task and `ρ` a renaming of its cursor cells.

1. *Function of structure, renaming-invariant.* By induction over the
   procedure's steps: each step reads only structural data, body labels
   and numbers assigned by earlier steps; no step reads a source id. So
   the run on `ρ(S)` makes the same decisions as the run on `S` and
   assigns `N∘ρ⁻¹`; the emitted rows, which spell cursor cells by number
   only, are byte-identical.
2. *Invariant under permutation of unordered containers.* A `:set` or
   `:map` is read in `CE` order, never in listed order (D1, D3), except
   in D4, where the lemma applies. **Lemma (orbit).** Let a surviving tie
   group have elements `e₁ … eₘ` with equal `CE`, and let every cursor
   cell of `U` occur only inside one `eᵢ`. Equal `CE` means the elements
   are identical once `U` is erased to body labels, with equal bodies at
   corresponding places. Entering `e_{σ(1)} … e_{σ(m)}` assigns the
   `j`-th block of fresh numbers to `e_{σ(j)}` in the same internal
   pattern for every `j`; the emitted container is the same multiset of
   elements for every `σ`, the census pairs the same numbers with equal
   bodies, and no other row mentions a cursor cell of `U`. ∎
3. *Terminating.* The structural edge family is acyclic (carrier §8
   step 3), so D1 is finite; D3 runs at most `n` rounds because each
   round numbers a cursor cell or stops; each round is linear in the
   number of positions times the cost of `CE`, which is linear in the
   element. No label grows.
4. *Deterministic*, hence two lifts of one state are byte-identical and
   the receiver's recomputation is well defined.
5. *Sharing survives*: the numbering is a bijection on reached cursor
   cells (D2 never renumbers), every occurrence of one cursor cell spells
   one number, and two cursor cells at one position get two numbers.
6. *Cross-engine law*: two engines lifting one canonical frame produce
   abstract tasks equal up to renaming (the frame is engine-independent,
   continuation §2.1) and therefore, by 1, equal rows.

What is given up: the procedure does not number every state. D5 names
the refused class; a later search over that residue would not change
the numbering of any state D1-D4 number today, so the refusal can be
narrowed without churning ids.

## 4. Amendments

**A1 — carrier §6, from "The procedure." to the end of step 5.** Replace
with §2 above (the body label, `CE`, D1-D5). Keep the section's first
paragraph (the counterexample) and amend "What is defined, and what is
not claimed" to: "The canonical numbering is defined as the output of
the discovery-order procedure below, which is a function of the state
alone (proof in `yin.vm.semantic-register-vm.cursor-cell-numbering.md`
§3). It is not the minimum of the root bytes over all bijections. A
state whose cursor cells are distinguishable only by a symmetric guest
collection of pairs is refused (D5), not searched."

**A2 — carrier §6 "Validation".** Replace "A receiver whose work bound
(§10.10) is exceeded before the procedure completes refuses with a
structured resource refusal" with "A receiver whose work bound (§10.10)
is exceeded, or that reaches D5, refuses with the structured refusal
named there". Delete "never falls back to encounter order" (the
procedure *is* canonical encounter order; the clause now reads as a
contradiction) and keep "never accepts an unfinished check".

**A3 — carrier §5 preamble.** Replace "The class-erased skeletons of §6
are used only inside the numbering procedure and never decide the order
of an emitted row" with "The content encodings of §6 are used only
inside the numbering procedure and never decide the order of an emitted
row".

**A4 — carrier §8 step 6.** "Recompute §6 under the work bound and
compare (`:yin.k/undecodable`, kind `:noncanonical-cursor-cells`;
`:yin.k/undecodable` kind `:unresolved-cursor-cells` if D5 is reached;
or the resource refusal of §10.10 if the bound is exceeded)." Add
`:unresolved-cursor-cells` to the diagnostics paragraph after step 12 as
a `:yin.k/undecodable` kind and a `:yin.k/non-portable` kind.

**A5 — carrier §10.** Answer 9: "refinement-and-search procedure" →
"discovery-order procedure". Answer 10: "refinement work and search
nodes for §6" → "and traversal work for §6". Proposals list: "as
defined by its procedure" → "as defined by discovery order, and the D5
refusal class".

**A6 — carrier §3, identity rows.** After "minted by the lift under §6's
canonical numbering" add "(the n-th cursor cell met in §6's traversal)".

**A7 — continuation §2.2.** Replace "numbered by the draft's §6
procedure" with "numbered by discovery order (draft §6 as amended by
`yin.vm.semantic-register-vm.cursor-cell-numbering.md`)".

**A8 — continuation §5.5.** Replace "(its labels never encode an
incoming cursor-cell id)" with "(it reads no incoming cursor-cell id)"
and "are encoded by sorting their elements' encodings bytewise (draft §6
step 2)" with "are entered in the bytewise order of their elements'
content encodings (draft §6 D1)". "Never derived from traversal order"
stays true: the traversal is canonical, not the host's.

**A9 — continuation §8.1 acceptance.** Item 10 stands; append items 12-19
of §8 below as the acceptance of the rewritten numbering.

**A10 — UCF §7.5.3.** No change: "minted by the emitter (stable within
one lift; remapped on lowering) and not content-addressed" remains true.

## 5. The alternative: portable ids minted in the VM

The owner asked whether a cursor cell could carry a portable id from
creation so the carrier sends it as is. To work across engines the id
must be computable by every engine from shared inputs. The candidates
are: the task's id counter (`engine.cljc:227-230`, `gensym`
`:2317-2323`: local, and lower adopts `max(local, carried)`), a creation
sequence of `:stream-cursor` executions (`handle-cursor-unminted`'s
`seq-n`, `engine.cljc:562-573`), a hash of (occurrence, counter), or a
random id. Every one of them names *history*, not structure:

- *Same structure, same hash fails.* A task that minted cursor cells 1,
  2, 3 and dropped 2 is structurally equal to one that minted 1, 2; the
  carried ids differ, so the root ids differ.
- *The cross-engine harness law* would hold only if both engines agree
  on the minting history, which makes an execution trace part of the
  frame's identity; a random or occurrence-bound id breaks it outright
  (a fork halt has no occurrence; a child has none of its own).
- *The VM grows a second name.* Lower mints a fresh private resource
  entry per cursor cell (carrier §8 step 12) and must keep the carried
  id beside it; a relift after further execution must mint ids that
  cannot collide with carried ones, which needs an (origin, counter)
  pair: a global naming scheme for a thing that only needs a name
  relative to its state.

Discovery order answers the owner's observation differently: the
identity stays local and the carrier gives it a *structural* name, the
n-th cursor cell met, exactly as a de Bruijn index names a binder by
position rather than by a global symbol. Recommend against portable
ids.

## 6. Prototype

`collab/numbering-probe/src/probe/discovery.clj` (not repo code; a
scratch project under `collab/`, never committed) implements D1-D5 over
6a's abstract task representation (the orbit rule in D4's general
form), emits through 6a's `rows/emit-numbered`, and compares with 6a's
`rows/emit`. Run from the probe directory:

```
clojure -M -e "(require 'probe.discovery) (probe.discovery/-main)"
```

Results of 2026-10-12 (6a tests pass in the same project, 21 tests,
324 assertions):

| State | Discovery order | 6a |
|---|---|---|
| `fixture :s1 :s2 :x :y` (`rows_test.cljc:28`) | `x cc-1, y cc-2`, root `fab223…` | same root |
| review counterexample `'a #{X Y}`, `'b X` | `x cc-1, y cc-2`, root `a3713a5e…` | root `2d9873a8…` |
| symmetric pair `#{X Y}` | orbit rule, root `07eda35d…` | same root |
| `#{[X X] [Y Y]}` / `{X 1, Y 1}` | orbit rule, roots `da69c679…` / `49d4478c…`; equal after element reversal and after renaming plus shuffling | not compared |
| `[x x y]` / `[x y y]` (`rows_test.cljc:159`) | roots `4993b5b1…` / `536c810b…` | `71543fe2…` / same |
| loop plus 2-cycle (`rows_test.cljc:312`) | refused `:unresolved-cursor-cells` | root `55ff168e…` |
| seven-cell triangle plus square (`:392`) | refused `:unresolved-cursor-cells` | refused `:resource-limit [:label-bytes]` |
| ten cells in a vector (`:331`) | `cc-1 … cc-10` in vector order | same root |
| 400 random states, renamed and shuffled | 399 numbered, 1 refused, 0 invariance failures | 398 numbered, 2 refused, 0 failures; 188 equal roots, 210 different |

Hand derivation, verified against the codec above. Fixture
`:s1 :s2 :x :y`: the store's `'payload` map is entered by `CE` of its
`[key value]` pairs; whichever of `'one` (`[:cursor-cell x]`), `'both`
(`#{x y}`) or `'cursors` (`{x :x, y :y}`) sorts first, `x` is met before
`y`, since `x` is alone under `'one` and its body (`:s1`) sorts before
`y`'s (`:s2`) in the set and the map; so `x = cc-1`, `y = cc-2`. Review
counterexample: `'a` before `'b`; `#{X Y}` ties and is deferred; `'b`
meets `X = cc-1`; the fixpoint re-sorts the set, `[:n 1]` against a body
label, and `Y = cc-2`. Alias `[x x y]`: `x = cc-1` at index 0, `y = cc-2`
at index 2. The example state of `collab/example-suspended-state.clj`
has one cursor cell, first met in the act's environment under `c`, so
`cc-1`; a one-cell state has the same rows under both procedures.

## 7. Recommendation

**(a) Adopt discovery order now.** 6b has not started and 6a is
unmerged, so the cost is one implementation slice of `rows.cljc` and its
tests plus the amendments of §4: delete `check-label-bytes!`
(`rows.cljc:212-230`), `positions` (`:285-325`), `classes` (`:326-330`),
`refine` (`:331-337`), `search` (`:338-354`) and the label seeding
(`:252-255`); keep `ce` (`:257-284`) with the numbered/unnumbered
spelling; add the traversal, fixpoint, orbit rule and refusal (the
prototype is 110 lines with helpers; 60-80 in place); delete the
`:search` and `:label-bytes` budget keys (`:200-202`). Unchanged:
`emit`, `emit-numbered`, `canonical-under`, the census and its numeric
order (`:140-156`), `decode*`'s recompute-and-compare (`:570-575`),
numbering before `validate-frames` (`:576-578`; test
`numbering-precedes-frame-defect`, `rows_test.cljc:206`), the `:bytes`,
`:rows`, `:depth` and `:work` bounds. Tests: `rows_test.cljc:171`
becomes the orbit-rule test (no `{:search 1}`); `:307-329` keeps
`fab223…` and `c4a7c7…`, drops `55ff168e…`, `{:search 2}` and `cc-3`;
`:400-404` becomes the D5 refusal test; `:406-421` is deleted. Cost:
one implementation round and one review round, and the review can check
§3 by hand. Risk: a canonicality bug, mitigated by §8 item 18's
generator in CI and by the proof; and the D5 class, documented and
narrowable later without id churn.

**(b) Keep the 6a design.** Zero rounds now, but 6b, the cross-engine
harness and every reviewer of a pinned id inherit an exhaustive search,
labels that grow until the seven-cell state exceeds a megabyte, and a
definition that is "whatever the procedure outputs". Not recommended.

**(c) Portable ids.** Changes the VM, the census, the laws and the
harness (§5); three or more rounds across two designs. Against.

## 8. Acceptance for the rewritten 6a numbering

12. `rows-test/discovery-order-numbering`: for `fixture :s1 :s2 :x :y`,
    the review counterexample, `[x x y]` and `[x y y]`,
    `canonical-numbering` returns exactly the maps of §6.
13. `rows-test/review-counterexample-resolves-by-fixpoint`: `'a #{X Y}`,
    `'b X` numbers `X cc-1, Y cc-2`; the task renamed and with the set
    reversed gives the same root; the root is pinned `a3713a5e…`.
14. `rows-test/orbit-rule`: `#{X Y}`, `#{[X X] [Y Y]}` and `{X 1, Y 1}`
    number without refusal; element reversal and renaming give equal
    roots; `#{X Y}` keeps root `07eda35d…`; `#{[X X] [Y Y]}` is pinned
    `da69c679…` and `{X 1, Y 1}` is pinned `49d4478c…`.
15. `rows-test/unresolved-cursor-cells-refuse`: the loop plus 2-cycle and
    the seven-cell state refuse at `emit` with `:yin.k/non-portable`,
    kind `:unresolved-cursor-cells`, a path naming the set and the
    undecided cursor cells; a carrier of either built with
    `emit-numbered` decodes to `:yin.k/undecodable` kind
    `:unresolved-cursor-cells`, never `:noncanonical-cursor-cells`.
16. `rows-test/canonical-roots-are-pinned`: `fab223…` and `c4a7c7…`
    unchanged; `a3713a5e…`, `4993b5b1…`, `536c810b…` added;
    `55ff168e…` removed.
17. `numbering-precedes-frame-defect`, `alias-topology-and-census`,
    `canonical-under-is-renaming-invariant`,
    `unordered-containers-are-order-free`, `child-scope-and-shared-child-dag`
    and `census-is-numeric-from-ten-cursor-cells` pass unchanged.
18. `rows-test/counterexample-search`: a seeded generator (one to six
    cursor cells, bodies from a four-value pool, nested `:vec`, `:set`,
    `:map` under keyed bindings and the result, unique set elements and
    map keys under decoded equality) over at least 400 states: every
    numbered state's carrier decodes, and renaming every cursor cell
    plus shuffling every set and map gives an equal root id.
19. Budgets: the `:search` and `:label-bytes` keys are gone;
    `{:work 1}` still refuses `:yin.k/unsupported :resource-limit`;
    `input-row-bodies-do-not-spend-label-bytes` is deleted.
20. Docs: A1-A9 applied; kondo clean; `bb test:sub yin.vm` green on three
    hosts; Node log shows `Testing yin.vm.ucf.rows-test`.

## 9. Owner questions

1. Is the D5 refusal class acceptable as a documented limitation of
   body version 3 (recommended: yes; it can be narrowed later by a
   search over the residue without changing any id numbered today)?
2. Should the refusal kind be `:unresolved-cursor-cells` as proposed,
   or the orchestrator's `:resource-limit` (recommended against: it is
   not a budget, and carrier §10.10 says a resource refusal never
   stands in for a canonicality verdict)?
