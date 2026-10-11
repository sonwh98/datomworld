# Cursor-cell numbering by discovery order (companion to the row carrier)

Status: **Proposed, not landed.** Written 2026-10-12 (Architect,
claude-fable-5-1) for the owner's observation that "the problem is not
that the cursor cell has identity, but that the identity is local", and
the orchestrator's proposal to number cursor cells by **discovery order**
in a canonical traversal. **Revised 2026-10-12** after the independent
review (gpt-6.1-sol, `collab/1792140000000-…findings.md`), which broke
the first draft in two places: the orbit lemma was unsound (equal
content encodings did not imply interchangeable elements: `#{[X X] [Y Z]}`)
and nested tie groups were never processed (`#{#{X Y} #{Z W}}`). The
rule, the proof, the refusal class, the decode path, the work bound, the
amendments and the prototype are rewritten below; every pinned id that
moved is listed in §6. Subordinate to
[`yin.vm.ucf-transport-tuples.md`](./yin.vm.ucf-transport-tuples.md)
(the carrier, body version 3) and
[`yin.vm.semantic-register-vm.continuation.md`](./yin.vm.semantic-register-vm.continuation.md);
vocabulary is UCF §7.5.3 ("Terminology: cursor, cursor cell, cursor-ref,
cell") exactly. Evidence is the carrier draft §5/§6 tables, slice 6a's
`src/cljc/yin/vm/ucf/rows.cljc` and `test/yin/vm/ucf/rows_test.cljc`
(branch `srvm-phase6a` at `d77da9f0`), and a prototype run against that
code (§6). Nothing here is implemented.

**Recommendation in one line:** adopt discovery-order numbering now (§7,
option a): a function of structure, renaming- and enumeration-invariant,
provable by hand (§3), with work bounded and accounted before allocation
(§2 "Work"); it deletes carrier §6's refinement and search, keeps every
pinned root id except the one state only the search could number, and
refuses a precisely described class (D5) that it cannot certify.

## 0. Vocabulary used below

- **Canonical traversal order** is the order D1 defines: a function of
  the abstract task alone. **Host enumeration order** (also "listed
  order") is the order in which a host happens to list the elements of a
  guest set or the entries of a guest map, or the census; it is an
  accident of the host and the procedure must never let it decide a
  number. The first draft's amendments blurred the two; §4 keeps them
  apart.
- An **element** is a `:set` element or a `:map` entry `[key value]`
  (the key is a guest value and may be a cursor cell).
- A **numbering** `N` is a partial map from the task's cursor cells to
  positive integers, injective, assigned in increasing order; `|N|` is
  its size. The emitted id of a cell numbered `k` is `:yin.k/cc-k`.
- A **run** is one execution of the traversal-and-resolution procedure
  over one item (a row or a value) from a starting numbering. The **root
  run** is the run over the `:task` row from the empty numbering; its
  numbers are the output. A **local run** is a run over one element (or
  one component, D4) from the enclosing run's current numbering; it
  exists only to compute a sort key and is discarded.

## 1. Findings

**F1. Every non-guest position is ordered.** From the carrier §5 tables
(and `rows/row-table`, `rows.cljc:37-57`), the positions that can hold a
cursor-ref (`[:cursor-cell C]`) or a bare `C` slot, and their order:

| Position | Order | Canonical index |
|---|---|---|
| `:task` slots 5 `free-env`, 6 `store`, 8 `parked`, 15 `result`; `:wait` state, pending; `:act` env, window, k; `:tail` k; `:kframe` env, window; `:reified` act; `:env` and `:closure` bindings; `:module-store` store; `:pending` `cursor-cell-C`, `response-cursor-cell-C`, `value-V`, `envelope-V` | fixed slot | slot index |
| `:task` 7 `module-stores`, 12 `installs` | keyed by `M`, by module name | Jing bytes of the key; the child is a task boundary |
| `:task` 13 `frames`; `:k`; `:vec`, `:list` elements | ordered vector | position `i` |
| `:window` | keyed by register `N` | `N` ascending, numerically (carrier §5.2) |
| `:bindings` (environments, closures, the free environment) | keyed by symbol `S` | Jing canonical bytes of `S` (§5 preamble: "sorted") |
| `:store`, `:parked-table` | keyed by `D`, by `P` | bytes of the key; a parked-table value is a structural `:act`/`:tail` reference, entered like any row |
| `:set` elements | **unordered** | none |
| `:map` entries | **unordered** (keys are `V`) | none |
| `:cursor-cells` census | not a position (carrier §6 step 2, §8 step 3) | — |

So the environment's key order is canonical (bytes of the symbol as
spelled, the order `:bindings` rows are emitted in), and the only
unordered positions are guest `:set` elements and guest `:map` entries.
The review confirmed the inventory complete. The example state in
`collab/example-suspended-state.clj` holds its one cursor cell
(`:cursor-1`) in three ordered positions: the act's environment under
`c`, the window under register 2, and the `:next` pending's
`cursor-cell-C` slot.

**F2. Discovery order subsumes the positions step.** The carrier's §6
step 3 (positions) exists to let a cursor cell's number depend on where
it occurs. A canonical traversal (D1) visits every ordered position in a
fixed order, so "the n-th cursor cell met" already encodes the first
position of each cursor cell; later positions cannot change an assigned
number and need not be collected. Refinement (step 4) and search (step 5)
existed only to turn position multisets into a total order; discovery
order is a total order by construction.

**F3. Unordered containers are ordered by a content encoding that
carries the alias pattern.** The first draft spelled an unnumbered cursor
cell by its body alone, so `[X X]` and `[Y Z]` encoded alike and the
review's counterexample followed. The repaired encoding (§2 "Spelling")
numbers the cursor cells *inside* an element provisionally, in the
element's own discovery order, relative to the enclosing numbering: `[X X]`
spells `[(u0) (u0)]`, `[Y Z]` spells `[(u0) (u1)]`, so alias topology
inside an element is part of its sort key, as a de Bruijn index is part
of a term. The key of an element is the spelling produced by a *local
run* over it, which applies the same rules recursively (nested
containers sorted, nested ties deferred and re-examined, D4 inside) and
spells what it cannot resolve as `[:tie …]`. Equal keys form a **tie
group**, deferred to the run's worklist and re-examined to a fixpoint
(D3) as other positions assign numbers. No key ever grows beyond the
size of its element's expansion.

**F4. The residue, the component rule, and the refusal predicate.** What
survives the fixpoint is a worklist of tie groups whose unnumbered cursor
cells occur only inside tied elements. Link two tied elements when they
share an unnumbered cursor cell; a connected set of tied elements is a
**component**. Two cases:
- *Certified*: no component holds two elements of one group. Every
  component is then encoded by a local run over its elements in group
  order (so a component spanning two groups, `[#{X Y} #{X Y}]`, spells its
  shared cell once with one index), components are sorted by that
  encoding, and the least run of equal encodings is entered in host
  order. Equal encodings here mean *isomorphic* components, and the
  entered state is the same up to isomorphism whichever order the host
  lists them in (§3, lemma C). `#{X Y}`, `#{[X X] [Y Y]}`, `{X 1, Y 1}`,
  `#{#{X Y} #{Z W}}` and `[#{X Y} #{X Y}]` are this case.
- *Not certified* (D5): some component holds two elements of one tie
  group, at some fixpoint of some run. Then entering either element first
  is a choice this procedure cannot show to be harmless, and it refuses.
  This is **"not certified by this procedure"**, not "non-interchangeable":
  `#{[A B] [B A]}` and the 3-cycle `#{[A B] [B C] [C A]}` are refused
  although every entering order gives the same rows (the swap and the
  rotation are automorphisms); the path `#{[A B] [B C]}` and the
  seven-cell triangle plus square (`rows_test.cljc:392-404`) are refused
  and genuinely need a tie-break (§6 shows two orders with different
  roots). The loop plus 2-cycle `#{[L L] [A B] [B A]}`
  (`rows_test.cljc:312-329`) now numbers `L = cc-1` by its key and
  refuses on `{A, B}`.
  The predicate, in one sentence: *a state is refused iff, at a
  fixpoint, two elements of one guest set or map have equal keys and are
  connected by a chain of shared unnumbered cursor cells through tied
  elements.* Such a state needs two or more cursor cells with equal bodies
  that are held *only* through a guest set or map whose elements mention
  more than one of them each. How often real states do that is **not
  measured**: the repository has no version-3 task corpus yet (the Stage E
  scenarios and the v2 handoff census tests hold cursor cells but no v3
  lift exists before 6b), so §6 reports only the fixture inventory (two
  of the repository's fixtures hit D5, both built to) and the random
  generator's rate under a tie-biased distribution. The first draft's
  rarity claim is withdrawn. D5 can be narrowed later by a bounded search
  over the residue without changing the numbering of any state D1–D4
  number today, because D1–D4's decisions precede it.

**F5. The scheme is canonical.** Proved in §3: the output (rows or
refusal) is a function of the abstract task up to isomorphism (renaming
of cursor cells, permutation of every unordered container and of the
census), terminating, with work bounded by §2 "Work".

**F6. Prototype evidence (§6).** 2000 random value states (one to six
cursor cells over two bodies, so ties are common; nested vectors, sets,
maps with cursor-cell keys, sets of cursor-cell pairs including aliases
such as `[X X]`, sets of sets, cells aliased across containers), 200
frame-bearing states (act window, K-frame window, environments, a parked
state) and 100 states with install children: the prototype gave equal
root ids after renaming every cursor cell and shuffling every set, map
and census, with no failure; every numbered carrier decoded through 6a's
decoder and recomputed to its own root; it refused 35 of 2300. The
review's three counterexamples number canonically; the pinned fixture
root `fab223…` and the ten-cell census root are unchanged; the new pins
for the counterexamples equal the digests the review computed
independently for the same numbering.

**F7. Portable ids minted in the VM are the wrong fix.** §5.

## 2. The rule (replaces carrier §6 "The procedure")

Input: the abstract task of carrier §6 (the §5 structure with cursor
cells as identities, before numbering). Output: a bijection from the
task's reached cursor cells to `:yin.k/cc-1 … :yin.k/cc-n`, or a
refusal. Install children are numbered as their own tasks by the same
rule (task boundary, unchanged), in any order, since scopes are disjoint.

**Body label.** `[:body [:stream identity descriptor] position]`, the
cursor cell's census body as plain data.

**Spelling (the content encoding `CE`).** A run spells the item it has
traversed under its final numbering. The root run spells a cursor cell
numbered `k` as `[:cursor-cell [:n k]]`, `k` an exact integer; a local
run spells a cursor cell it numbered itself (index `j`, counted from the
enclosing run's local counter) as `[:cursor-cell [:u j <body label>]]`
and a cursor cell numbered by an enclosing run as that run spelled it.
Nothing else changes from carrier §6 step 2: `[:lit d]` as itself,
`[:row B]` expanded in place, structural data as itself, the census as
`[:cursor-cells]`, an install child as `[:child]` (this deliberately
replaces step 2's `[:child <child root id>]`: a child sits only under a
name-keyed install entry, never inside an unordered container, so its
content never decides an order, and the encoding stays hash-free). Keyed
structural rows are spelled in their canonical key order, `:window` in
numeric register order; a `:set` or `:map` is spelled as the bytewise
sorted vector of its elements' spellings (an entry as `[:entry K V]`),
duplicates retained, where a tie group the run could not resolve is
spelled in place as `[:tie K … K]` (its common key, once per element).
All comparisons are bytewise on canonical CBOR of these synthetic
vectors. `CE` is hash-free and linear in the expansion of its argument.

**D1. Traversal.** Depth-first, pre-order, from the run's root item. A
row's slots are visited in table order. Ordered repeated slots
(`frames`, `:vec`, `:list`, `:k`) in order. Keyed rows with structural
keys (`:bindings`, `:store`, `:parked-table`, the root's `installs` and
`module-stores`) in the Jing canonical byte order of the key, the order
§5 emits; `:window` in ascending numeric register order as the carrier
specifies (the same as byte order for `N`, but the rule is numeric). The
census is not entered; an install child's `:task` row is not entered. A
`:set`'s elements and a `:map`'s entries are each given a **key**: the
spelling of a local run over the element under the numbering at the
moment the container is entered (every element keyed from that one
numbering). Elements are then taken in bytewise key order: a run of
equal keys is a **tie group**, appended to the run's worklist (deferral
order is canonical: containers in traversal order, groups within one
container in key order); every other element is entered in key order,
an entry's key before its value.

**D2. Meeting.** When the traversal reaches an identity position (a
`[:cursor-cell C]` value or a `C` slot of a `:pending` row) whose cursor
cell has no number, the cursor cell receives `|N| + 1` (the root run
counts from 1; a local run counts its own cells from the enclosing run's
local counter, so a nested local run never reuses an index of its
enclosing run). An already numbered cursor cell is passed over. There is
no first-occurrence spelling: every occurrence emits
`[:cursor-cell :yin.k/cc-n]` and the census row (carrier §3) stays the
contents table, so content rows keep sharing by Merkle id whether or not
they hold a cursor cell's first occurrence.

**D3. Fixpoint.** After the traversal, the worklist's groups are
re-examined in deferral order: each group's elements are re-keyed under
the current numbering and taken as in D1 (singletons entered, equal runs
re-deferred in order). Repeat while a round numbers at least one cursor
cell and groups remain. Entering an element inside a group runs D1 on
it, so a group discovered *inside* an entered element (the review's
`#{#{X Y} #{Z W}}`) joins the same worklist and is re-examined in the
next round; there is one worklist per run, and the root run's worklist
collects deferred groups from every depth.

**D4. Components.** When a fixpoint is reached with groups remaining,
link two tied elements (of any groups) that share an unnumbered cursor
cell; a connected set of tied elements is a **component**. (At a
fixpoint every unnumbered cursor cell occurs only inside tied elements,
so all of its occurrences lie in one component.) If no component holds
two elements of one group: encode each component by a local run over the
synthetic ordered row `[:component g₁ e₁ g₂ e₂ …]` of its elements in
group order (`gᵢ` the group's deferral sequence number, a datum), require
that run to be complete (no `[:tie]` in its spelling, else D5), sort the
components by their encodings, and **enter the run of least encoding**:
each of its components has its local run's cursor cells numbered, in that
run's local-index order, continuing the enclosing run's numbering;
components of equal encoding are entered in host order (§3, lemma C:
equal encodings are isomorphic and the result is the same). Remove the
entered elements from their groups and return to D3. A group whose
elements mention no unnumbered cursor cell holds equal values (the
carrier refuses the duplicate at §8 step 5); its elements are entered in
host order, which numbers nothing and emits identical rows.

**D5. Refusal.** If at any fixpoint of any run a component holds two
elements of one group (directly, or inside a component's local run), the
lift refuses `:yin.k/non-portable` kind `:unresolved-cursor-cells`,
`:yin.k/path` the row path of the container holding that group, and a
`:yin.k/cursor-cells` list of the component's unnumbered cursor cells.
A receiver that reaches D5 while recomputing refuses `:yin.k/undecodable`
kind `:unresolved-cursor-cells`. Neither is a resource refusal and neither
is `:noncanonical-cursor-cells`.

**Totality.** The root run ends with an empty worklist or a refusal.
Before emission the implementation asserts that the numbering is a
bijection from the census's cursor cells onto `1 … n`; a violation is a
defect (an exception naming the missing or extra cursor cells), never a
refusal, and never a partially numbered carrier.

**Memoization (implementation note, not part of the definition).** A
local run's result is a function of its item, of the enclosing numbering
restricted to the item's cursor cells, and of the enclosing local
counter; an implementation may memoize on that key and must return the
numbering restricted to the item's own cursor cells (the prototype's one
bug during revision was returning the whole numbering of another
element's context, which the decode round trip caught).

**Validation and decode.** Unchanged in form: the receiver decodes, runs
the root run, re-emits and compares root ids (`:noncanonical-cursor-cells`
on mismatch). In 6a's `decode*` the recomputation is `emit` itself
(`rows.cljc:570-575`); it must be wrapped so that **only** a lift-side
`:yin.k/non-portable` of kind `:unresolved-cursor-cells` raised by the
recomputation is translated to `:yin.k/undecodable` kind
`:unresolved-cursor-cells` with `:yin.k/path` prefixed by the root id;
a resource refusal (`:yin.k/unsupported :resource-limit`) propagates as
itself; no other non-portable kind can arise there, because step 5 has
already checked literals and markers. The validator order stays: step 6
(numbering) after step 5 and before `validate-frames` (`rows.cljc:576-578`;
test `numbering-precedes-frame-defect`, `rows_test.cljc:206`).

**Work.** Let `P` be the number of expanded positions of the task (the
tree after expanding every `[:row B]` and structural reference in place;
a shared row counts once per reference, so `P` can exceed the row count
and the implementation must bound it, not the row count), `d` the
container nesting depth, `n` the number of cursor cells, `m` the largest
element count of one container. Then, with memoized local runs, the
procedure visits each position at most `(n + 1)` times per nesting level
(a local run over an element is recomputed only when the numbering
restricted to that element's cursor cells changes, at most `n` times),
every encoding is linear in its element's expansion, and each container
sort costs `O(B · log m)` compare work for `B` key bytes; so
`W = O(P · (d + 1) · (n + 1) · log m)` positions-and-bytes, and the bytes
materialized at any time are `O(W)` plus the memo, itself `O(P · (d + 1)
· (n + 1))`. Without memoization the bound is `O(P · (n + 1)^(d+1) · log m)`.
Both are accounted before allocation by the budgets that stay: `:work`
is charged one unit per position visited or spelled **and** one unit per
literal byte copied into a spelling (so every allocated spelling byte
has been paid for), `:depth` bounds `d` and the structural depth as
today, `:cursor-cells` bounds `n`, `:rows` bounds the carried rows and
`:bytes` the carried bytes, which together bound every literal a spelling
can copy. The `:search` and `:label-bytes` budgets disappear with the
search and the growing labels. Exceeding a budget is the resource refusal
of carrier §10.10, never a canonicality verdict.

## 3. Proof of canonicality

Call two configurations (abstract task, partial numbering, worklist)
**isomorphic** when a bijection `π` of cursor cells carries one to the
other: the structures agree up to renaming by `π` and up to the host
order of every unordered container and of the census, numbers agree
(`N'(π(c)) = N(c)`), and worklists correspond group by group in order.
Let `S` be an abstract task.

**Lemma A (keys are canonical).** The key of an element is the spelling
of a local run over it, and a local run reads only structure, body
labels and numbers; its own listed-order inputs are (i) element order
inside nested containers, which it never uses except through keys, by
induction on nesting depth, and (ii) the host order of components of
equal encoding in its D4 step, covered by lemma C. So isomorphic items
under corresponding numberings have byte-identical keys, and the sorted
order of a container's elements is a function of the configuration up
to isomorphism.

**Lemma B (ties are exactly what keys cannot separate).** By
construction: elements are deferred iff their keys are equal bytes.

**Lemma C (components).** At a fixpoint let `C₁ … Cₘ` be the components
of one run of equal encodings, none holding two elements of one group,
and let `U₁ … Uₘ` be their unnumbered cursor cells. The `Uᵢ` are
pairwise disjoint and each `Uᵢ` is confined to `Cᵢ` (every occurrence of
an unnumbered cell lies in a tied element, hence in its own component).
Equal, complete encodings of `[:component g₁ e₁ …]` rows are a faithful
spelling (literals as themselves, rows expanded, every cursor cell by
index and body, groups by sequence number), so they exhibit, for each
pair `Cᵢ, Cⱼ`, a bijection `Uᵢ → Uⱼ` under which the components are the
same structure group by group. Entering the run in host order `σ` gives
the cells of `C_{σ(t)}` the numbers `b + (t−1)·|U| + 1 …` by local index;
in order `σ'` the cells of `C_{σ'(t)}` receive the same numbers. The
block-swap bijection composed from the pairwise bijections is an
isomorphism of the two resulting configurations: numbers agree by
construction, structures agree because the swapped components are
isomorphic and nothing outside them mentions their cells, and the
worklists agree because the same groups remain, with the same elements
removed. ∎

**Lemma D (refusal is invariant).** Whether a component holds two
elements of one group depends only on which tied elements share
unnumbered cursor cells, which is a function of the configuration up to
isomorphism; and whether a component's local run is complete is, by
lemma A applied to that run, likewise invariant.

**Theorem.** The procedure's output on isomorphic configurations is the
same: both refuse at corresponding points, or both emit byte-identical
rows. *Proof.* By induction on the number of steps. D1 and D2 decisions
are functions of the configuration up to isomorphism (lemma A); D3 only
repeats D1 on deferred groups in a canonical order; D4's choice of the
least run is canonical (lemma A on component encodings) and its only
listed-order input is harmless (lemma C); D5 is invariant (lemma D).
Emission spells cursor cells by number only and sorts every emitted
unordered slot by canonical bytes under the final numbering (carrier §5),
so isomorphic final configurations emit the same rows. ∎

Consequences:

1. *Function of structure, renaming-invariant, enumeration-invariant*:
   the theorem with `π` a renaming, a permutation of the containers, or
   both. No decision reads a source id or a host order except at the
   point lemma C covers.
2. *Terminating.* The structural edge family is acyclic (carrier §8 step
   3), so every traversal is finite. In the root run each D3 round that
   is not the last numbers a cursor cell; each D4 step numbers at least
   one cursor cell or removes a duplicate group; local runs are finite
   by the same argument one level down and there are finitely many
   nesting levels. So the procedure ends after at most `n` numbering
   events per run plus one terminal round, within the bound of §2 "Work".
3. *Deterministic*, hence two lifts of one state are byte-identical and
   the receiver's recomputation is well defined.
4. *Sharing survives*: the numbering is a bijection on reached cursor
   cells (D2 never renumbers; totality is asserted), every occurrence of
   one cursor cell spells one number, and two cursor cells at one
   position get two numbers.
5. *Cross-engine law*: two engines lifting one canonical frame produce
   abstract tasks equal up to renaming (the frame is engine-independent,
   continuation §2.1) and therefore, by the theorem, equal rows.

What is given up: the procedure does not number every state. D5 names
the refused class exactly (§1 F4); a later bounded search over that
residue would not change the numbering of any state D1–D4 number today,
so the refusal can be narrowed without churning ids.

## 4. Amendments

**A1 — carrier §6, from "The procedure." to the end of step 5.** Replace
with §2 above (vocabulary, the body label, the spelling, D1–D5, totality,
work). Keep the section's first paragraph (the counterexample) and amend
"What is defined, and what is not claimed" to: "The canonical numbering
is defined as the output of the discovery-order procedure below, which is
a function of the state up to renaming of cursor cells and host
enumeration of guest sets, maps and the census (proof in
`yin.vm.semantic-register-vm.cursor-cell-numbering.md` §3). It is not the
minimum of the root bytes over all bijections. A state in which two
equal-keyed elements of one guest set or map are linked by shared
unnumbered cursor cells at a fixpoint is refused (D5), not searched; the
refusal certifies nothing about interchangeability and can be narrowed
later without changing any numbering assigned today." State in step 2's
replacement that `[:n k]` is an exact CBOR integer and that `[:child]`
replaces `[:child <child root id>]` (reason in §2 "Spelling").

**A2 — carrier §6 "Validation".** Replace the paragraph's last two
sentences, from "Every labeling that yields the selected commitment is
thereby accepted, and no encounter-order tie-break ever decides validity"
to the end, with: "Every labeling that yields the selected commitment is
thereby accepted. The procedure reads the canonical traversal order of
D1, which is a function of the state; it never reads the host's
enumeration order of a set, map or census, and a receiver never decides
validity by its own enumeration order. This is a lower-side structural
refusal, not a lift kind. A receiver whose work bound (§10.10) is
exceeded before the procedure completes refuses with the structured
resource refusal before any attachment, never labels the state
`:noncanonical-cursor-cells`, never substitutes host enumeration order,
and never accepts an unfinished check; a receiver that reaches D5
refuses `:yin.k/undecodable` kind `:unresolved-cursor-cells`."

**A3 — carrier §5 preamble.** Replace "The class-erased skeletons of §6
are used only inside the numbering procedure and never decide the order
of an emitted row" with "The spellings (content encodings) of §6 are
used only inside the numbering procedure and never decide the order of
an emitted row".

**A4 — carrier §8 step 6.** "Recompute §6 under the work bound and
compare (`:yin.k/undecodable`, kind `:noncanonical-cursor-cells`;
`:yin.k/undecodable` kind `:unresolved-cursor-cells` if D5 is reached,
translated from the recomputation's lift-side refusal of that kind only;
or the resource refusal of §10.10 if the bound is exceeded, which
propagates untranslated)." In the diagnostics paragraph after step 12,
add `:unresolved-cursor-cells` to the `:yin.k/undecodable` kinds named,
and change "the two kinds this carrier adds to UCF §7.5.4's set:
`:byte-string` and `:metadata`" to "the three kinds this carrier adds to
UCF §7.5.4's set: `:byte-string`, `:metadata` and
`:unresolved-cursor-cells`". In §11, "two lift kinds and two
`:yin.k/undecodable` kinds are added" becomes "three lift kinds and three
`:yin.k/undecodable` kinds are added".

**A5 — carrier §10.** Answer 9: "a renaming-invariant
refinement-and-search procedure" → "the discovery-order procedure, which
is invariant under renaming and host enumeration". Answer 10: "a bound
on decoded bytes, rows, cursor cells, traversal depth, refinement work
and search nodes for §6" → "a bound on decoded bytes, rows, cursor cells,
traversal depth, and traversal-and-spelling work for §6 (its `:work`
unit counts positions and copied literal bytes, so spellings are paid
for before they are allocated)"; and "never falls back to encounter
order" → "never substitutes host enumeration order for the canonical
traversal". Proposals list: "as defined by its procedure" → "as defined
by discovery order, and the D5 refusal class".

**A6 — carrier §3, identity rows.** After "minted by the lift under §6's
canonical numbering" add "(the n-th cursor cell met in §6's canonical
traversal)".

**A7 — continuation §2.2.** Replace "numbered by the draft's §6
procedure" with "numbered by discovery order (draft §6 as amended by
`yin.vm.semantic-register-vm.cursor-cell-numbering.md`)".

**A8 — continuation §5.5.** Three replacements. (i) "Draft §6 is
invariant under renaming of the **cursor cells** (its labels never encode
an incoming cursor-cell id), and the only other identity it consumes is
the stream marker in each cursor cell's initial label
(`[:body <stream-marker-bytes> <position-bytes>]`, draft §6 step 1) and
in cursor-cell bodies" → "Draft §6 is invariant under renaming of the
**cursor cells** (it reads no incoming cursor-cell id), and the only
other identity it consumes is the stream marker in each cursor cell's
body label (`[:body [:stream identity descriptor] position]`, draft §6
as amended) and in cursor-cell bodies". (ii) "Unordered containers
(`:set`, `:map`, `:store`, `:bindings`, the parked table) are encoded by
sorting their elements' encodings bytewise (draft §6 step 2), never by
host traversal order" → "Guest sets and maps are entered in the bytewise
order of their elements' spellings, and `:store`, `:bindings` and the
parked table in the byte order of their structural keys (draft §6 D1),
never in host enumeration order". (iii) Leave "never derived from
traversal order" but qualify it: "never derived from *host* traversal
order (the canonical traversal of draft §6 D1 is a function of the
state and is what the procedure reads)".

**A9 — continuation §8.1 acceptance.** Item 10 stands; append items 12–21
of §8 below as the acceptance of the rewritten numbering.

**A10 — UCF §7.5.3.** No change: "minted by the emitter (stable within
one lift; remapped on lowering) and not content-addressed" remains true.

## 5. The alternative: portable ids minted in the VM

The owner asked whether a cursor cell could carry a portable id from
creation so the carrier sends it as is. To work across engines the id
must be computable by every engine from shared inputs. The candidates
(branch `srvm-phase6a`, `src/cljc/yin/vm/engine.cljc`) are: the task's id
counter (`gen-id`, `:227-230`; `gensym`, `:2312-2318`, which lower adopts
as `max(local, carried)`), the creation sequence of `:stream/cursor`
executions (`handle-cursor-unminted`'s `seq-n`, `:557-568`, minted at the
`:stream/cursor` effect, `:2595-2602`, with the id from `gensym` and the
sealed reference from `issue-ref`, `:311-323`), a hash of (occurrence,
counter), or a random id. Every one of them names *history*, not
structure:

- *Same structure, same hash fails.* Hold every carried field equal:
  task A executes `:stream/cursor` three times (ids `cursor-0`,
  `cursor-1`, `cursor-2`; counter 3) and drops the second; task B executes
  `:stream/cursor` twice and then consumes one unrelated fresh name
  (`gensym` for a non-cursor id; counter 3) and keeps both. The two
  snapshots are structurally equal (two cursor cells with equal bodies at
  equal positions, the same id counter, the same everything else), yet A
  carries `{cursor-0, cursor-2}` and B `{cursor-0, cursor-1}`, so the root
  ids differ. Any id recomputed from the snapshot's *structure* instead
  is the canonicalization problem of §2 again, not an alternative to it.
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
ids for immutable creation-history ids carried unchanged; that is the
only kind proposed.

## 6. Prototype

`collab/numbering-probe/src/probe/discovery.clj` (not repo code; a
scratch project under `collab/`, never committed) implements D1–D5 as
one schema-driven run procedure (root run and local runs share the code;
a local run differs only in its number tag and in spelling residues as
`[:tie]`), memoized as §2 describes, with the totality assertion, an
emitter that numbers install children by the same procedure, a decode
path that runs 6a's `decode*` with its numbering check off and then
recomputes, compares and translates only `:unresolved-cursor-cells`, and
a permissive diagnostic mode that replaces D5 by host-order entry to
test whether a refused state is in fact interchangeable. Run from the
probe directory (JVM only; `@#'rows/…` reaches 6a's private helpers):

```
clojure -M -e "(require 'probe.discovery) (probe.discovery/-main)"
```

Results of 2026-10-12 (revised prototype; 6a tests pass in the same
project, 21 tests, 324 assertions; full log `collab/numbering-probe/run.log`):

| State | Discovery order | 6a |
|---|---|---|
| `fixture :s1 :s2 :x :y` (`rows_test.cljc:28`) | `x cc-1, y cc-2`, root `fab223…` | same root |
| review counterexample `'a #{X Y}`, `'b X` | `x cc-1, y cc-2`, root `a3713a5e…` | root `2d9873a8…` |
| symmetric pair `#{X Y}` | component rule, root `07eda35d…` | same root |
| `#{[X X] [Y Y]}` / `{X 1, Y 1}` | roots `da69c679…` / `49d4478c…`; equal after reversal and after renaming plus shuffling | same roots |
| `[x x y]` / `[x y y]` (`rows_test.cljc:159`) | roots `4993b5b1…` / `536c810b…` | `71543fe2…` / same |
| **review A** `#{[X X] [Y Z]}` | `X cc-1, Y cc-2, Z cc-3` for both enumerations, root `65ba4530…` (the review's digest for this numbering) | `387f294a…` |
| **review B** `{X X, Y Z}` | `X cc-1, Y cc-2, Z cc-3` for both enumerations, root `409548a7…` (the review's digest) | `be62ba43…` |
| **review C** `#{#{X Y} #{Z W}}` | total numbering, inner set listed first gets `cc-1, cc-2`; root `18b70de6…` for the state and for its renaming | same root |
| `[#{X Y} #{X Y}]` | numbered (two components, each one element per group), root `92747c0f…` | same root |
| `[#{X Y} X]` | `X cc-1, Y cc-2`, root `7dc3d5c4…` | `a850fce6…` |
| `[#{#{X Y} #{Z W}} X]` | `X cc-1, Y cc-2, Z cc-3, W cc-4`, root `6ea03f9b…` | `e61d3709…` |
| `#{[#{X Y} #{X Y}] [#{P Q} #{R S}]}` | keys differ (the first element has two cursor cells, the second four); root `b35b8e12…` | refused `[:label-bytes]` |
| loop plus 2-cycle (`rows_test.cljc:312`) | `L cc-1`, then refused `:unresolved-cursor-cells` on `{A, B}` | root `55ff168e…` |
| 2-cycle, 3-cycle | refused; permissive mode gives one root over 24 shuffles (interchangeable, not certified) | numbered |
| path `#{[A B] [B C]}` | refused; permissive mode gives 2 roots (not interchangeable) | numbered |
| seven-cell triangle plus square (`:392`) | refused; permissive mode gives 4 roots (not interchangeable) | refused `:resource-limit [:label-bytes]` |
| ten cells in a vector (`:331`) | `cc-1 … cc-10` in vector order | same root |
| frame fixture: wait window `x`, K-frame window `y`, environment `y`, parked K-frame window `#{x y}` | `y cc-1` (free environment, slot 5), `x cc-2` (parked table, slot 8), `:test/cursor cc-3` (the wait's pending); decodes | different root (6a numbers by its own search) |
| frame fixture: wait window `#{x y}`, parked K-frame window `y` | `y cc-1` (slot 8 precedes frames, slot 13), `x cc-2`, `:test/cursor cc-3`; decodes | different root |
| parent with two installs sharing the review-counterexample child | child `X cc-1, Y cc-2` by discovery order; one child row shared; decodes | — |
| 2000 random value states | 1975 numbered, 25 refused (15 witnessed non-interchangeable by 12 shuffles, 10 not distinguished), **0 invariance failures**, 1783 decode round trips ok, 0 decode failures, 192 not decodable through 6a's own recompute budget (probe limitation) | — |
| 200 frame-bearing states | 192 numbered, 8 refused (5 witnessed, 3 not distinguished), 0 failures, 175 decoded, 17 at 6a's budget | — |
| 100 states with children | 98 numbered, 2 refused (not distinguished), 0 failures, 75 decoded, 23 at 6a's budget | — |

Hand derivations, verified against the codec above (the digest comes
from `emit-numbered` under the hand-derived numbering; where 6a or the
review reached the same numbering independently the digest agrees).
Fixture `:s1 :s2 :x :y`: the store's `'payload` map is entered by the
keys of its entries; whichever of `'one` (`[:cursor-cell x]`), `'both`
(`#{x y}`) or `'cursors` (`{x :x, y :y}`) sorts first, `x` is met before
`y`, since `x` is alone under `'one` and its body (`:s1`) sorts before
`y`'s (`:s2`) in the set and the map; so `x = cc-1`, `y = cc-2`. Review
counterexample: `'a` before `'b`; `#{X Y}` ties (`[u0 b]` twice) and is
deferred; `'b` meets `X = cc-1`; the fixpoint re-keys the set, `[:n 1]`
against `[:u 0 b]`, and `Y = cc-2`. Review A: keys `[:vec [u0 b] [u0 b]]`
and `[:vec [u0 b] [u1 b]]` differ at the second cell's index, so `[X X]`
is entered first: `X = cc-1`, then `Y = cc-2`, `Z = cc-3`. Review B: the
entries `[:entry [u0 b] [u0 b]]` and `[:entry [u0 b] [u1 b]]` likewise.
Review C: both inner sets key as `[:set [u0 b] [u1 b]]` (each local run
resolves its own `{X, Y}` tie by D4 with two one-element components),
the outer tie is deferred, the fixpoint changes nothing, the two
components are isomorphic, and the one listed first gets `cc-1, cc-2`:
either order emits `#{#{1 2} #{3 4}}`. `[#{X Y} #{X Y}]`: both sets tie
and defer; components `{X¹, X²}` and `{Y¹, Y²}` (one element per group,
equal encodings `[:component 1 [u0 b] 2 [u0 b]]`): `X = cc-1`, `Y = cc-2`
in host order, same rows either way. `[#{X Y} X]`: the set ties, index 1
meets `X = cc-1`, the fixpoint splits the set, `Y = cc-2`. Loop plus
2-cycle: `[L L]` keys as `[u0 b] [u0 b]`, the two others as `[u0 b] [u1 b]`,
so `L = cc-1` by key order; `{[A B], [B A]}` ties, the fixpoint changes
nothing, and the component `{[A B], [B A]}` holds two elements of one
group: D5. The example state of `collab/example-suspended-state.clj`
has one cursor cell, first met in the act's environment under `c`, so
`cc-1`; a one-cell state has the same rows under both procedures.

Pins that moved from the first draft: none of the first draft's pins
change (`fab223…`, `c4a7c7…`, `a3713a5e…`, `07eda35d…`, `da69c679…`,
`49d4478c…`, `4993b5b1…`, `536c810b…`, the ten-cell root); the first
draft refused `[#{X Y} #{X Y}]` (now `92747c0f…`), numbered review A and
B inconsistently (now `65ba4530…`, `409548a7…`) and left review C
unnumbered (now `18b70de6…`); `7dc3d5c4…`, `6ea03f9b…` and `b35b8e12…`
are new pins.

## 7. Recommendation

**(a) Adopt discovery order now.** 6b has not started and 6a is
unmerged, so the cost is one implementation slice of `rows.cljc` and its
tests plus the amendments of §4: delete `check-label-bytes!`
(`rows.cljc:212-230`), `positions` (`:285-325`), `classes` (`:326-330`),
`refine` (`:331-337`), `search` (`:338-354`) and the label seeding
(`:252-255`); replace `ce` (`:257-284`) by the spelling of §2; add the
run procedure (traversal, worklist, fixpoint, components, D5, totality
assertion, memo) and the decode translation (the prototype's procedure
is about 230 lines with the schema helpers; 150–200 in place, portable
`.cljc`: no `@#'` reflection, `mapv`/`into`/`reduce` over long sequences,
`contains?` for membership, continuation §12); delete the `:search` and
`:label-bytes` budget keys (`:200-202`) and charge `:work` per position
and per copied literal byte. Unchanged: `emit`, `emit-numbered`,
`canonical-under`, the census and its numeric order (`:140-156`),
`decode*`'s recompute-and-compare (`:570-575`) apart from the
translation, numbering before `validate-frames` (`:576-578`; test
`numbering-precedes-frame-defect`, `rows_test.cljc:206`), the `:bytes`,
`:rows`, `:depth`, `:cursor-cells` and `:work` bounds. Tests: see §8.
Cost: one implementation round and one review round, and the review can
check §3 by hand. Risk: a canonicality bug, mitigated by §8 item 18's
generator in CI (it caught the prototype's memo bug), by the decode round
trip in that generator, and by the proof; and the D5 class, documented
exactly and narrowable later without id churn.

**(b) Keep the 6a design.** Zero rounds now, but 6b, the cross-engine
harness and every reviewer of a pinned id inherit an exhaustive search,
labels that grow until the seven-cell state exceeds a megabyte, and a
definition that is "whatever the procedure outputs". Not recommended.

**(c) Portable ids.** Changes the VM, the census, the laws and the
harness (§5); three or more rounds across two designs. Against.

## 8. Acceptance for the rewritten 6a numbering

12. `rows-test/discovery-order-numbering`: for `fixture :s1 :s2 :x :y`,
    the review counterexample, `[x x y]`, `[x y y]`, `[#{X Y} X]` and the
    two frame fixtures of §6, `canonical-numbering` returns exactly the
    maps of §6.
13. `rows-test/review-counterexample-resolves-by-fixpoint`: `'a #{X Y}`,
    `'b X` numbers `X cc-1, Y cc-2`; the task renamed and with the set
    reversed gives the same root; the root is pinned `a3713a5e…`.
14. `rows-test/component-rule`: `#{X Y}`, `#{[X X] [Y Y]}`, `{X 1, Y 1}`,
    `#{#{X Y} #{Z W}}` and `[#{X Y} #{X Y}]` number without refusal;
    element reversal and renaming give equal roots; pins `07eda35d…`,
    `da69c679…`, `49d4478c…`, `18b70de6…`, `92747c0f…`.
15. `rows-test/alias-pattern-is-part-of-the-key`: `#{[X X] [Y Z]}` and
    `{X X, Y Z}` give one root for both enumerations and after renaming,
    pinned `65ba4530…` and `409548a7…`; `#{[#{X Y} #{X Y}] [#{P Q} #{R S}]}`
    numbers and is pinned `b35b8e12…`.
16. `rows-test/unresolved-cursor-cells-refuse`: the loop plus 2-cycle,
    the 2-cycle, the path `#{[A B] [B C]}` and the seven-cell state refuse
    at `emit` with `:yin.k/non-portable`, kind `:unresolved-cursor-cells`,
    a path naming the set and the component's cursor cells; a carrier of
    each built with `emit-numbered` decodes to `:yin.k/undecodable` kind
    `:unresolved-cursor-cells`, never `:noncanonical-cursor-cells`; a
    carrier of the loop plus 2-cycle with a `{:work 1}` budget refuses
    `:yin.k/unsupported :resource-limit`, not `:unresolved-cursor-cells`.
17. `rows-test/canonical-roots-are-pinned`: `fab223…` and `c4a7c7…`
    unchanged; `a3713a5e…`, `4993b5b1…`, `536c810b…`, `7dc3d5c4…`,
    `6ea03f9b…` added; `55ff168e…` removed.
18. `rows-test/counterexample-search`: a seeded generator (one to six
    cursor cells over two bodies; nested `:vec`, `:set`, `:map` with
    cursor-cell keys, sets of cursor-cell pairs including `[X X]`, sets of
    sets, under keyed bindings and the result; unique elements and keys
    under decoded equality) over at least 2000 states, plus at least 200
    frame-bearing states (cursor cells in an act window, a K-frame
    window, environments matched to the free environment, and a parked
    state at a park site) and at least 100 states with install children:
    every numbered state's carrier decodes and re-emits to its root, and
    renaming every cursor cell plus shuffling every set, map and census
    gives an equal root id; refusals are counted and reported.
19. `rows-test/numbering-is-total`: a task whose census declares a
    cursor cell the traversal does not reach raises the totality defect
    (not a refusal) before any row is emitted.
20. `numbering-precedes-frame-defect`, `alias-topology-and-census`,
    `canonical-under-is-renaming-invariant`,
    `unordered-containers-are-order-free`, `child-scope-and-shared-child-dag`
    and `census-is-numeric-from-ten-cursor-cells` pass unchanged; the
    large-input regression of `input-row-bodies-do-not-spend-label-bytes`
    is kept as `large-literals-do-not-spend-work-beyond-their-bytes`
    without its label-budget expectation.
21. Budgets and docs: the `:search` and `:label-bytes` keys are gone;
    `{:work 1}` still refuses `:yin.k/unsupported :resource-limit`; A1–A9
    applied; kondo clean; `bb test:sub yin.vm` green on three hosts; Node
    log shows `Testing yin.vm.ucf.rows-test`.

## 9. Owner questions

1. Is the D5 class, as stated in §1 F4 (exact predicate, no rarity
   claim, unmeasured on real states until 6b lifts one), acceptable as a
   documented limitation of body version 3 (recommended: yes; it can be
   narrowed later by a bounded search over the residue without changing
   any id numbered today)?
2. Should the refusal kind be `:unresolved-cursor-cells` as proposed,
   or the orchestrator's `:resource-limit` (recommended against: it is
   not a budget, and carrier §10.10 says a resource refusal never
   stands in for a canonicality verdict)?
