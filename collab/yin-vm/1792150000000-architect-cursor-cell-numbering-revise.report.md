Completed-GMT: 2026-10-11 20:34:00 GMT
Completed-Local: 2026-10-12 03:34:00 Asia/Bangkok

(Host clock as read by `date -u` at completion; as in the previous
report, this host's clock precedes the brief's Created-GMT.)

# Architect report: revision of the discovery-order numbering note after the codex review

Edited: `docs/design/yin.vm.semantic-register-vm.cursor-cell-numbering.md`
(rewritten, 724 lines) and the prototype
`collab/numbering-probe/src/probe/discovery.clj` (rewritten, 751 lines;
run log at `collab/numbering-probe/run.log`). Nothing staged or
committed. The 6a code was read from branch `srvm-phase6a` (`d77da9f0`)
through the probe project's copies, as before.

## The repair in one paragraph

The content encoding now numbers the cursor cells *inside* an element
provisionally, in the element's own discovery order, relative to the
enclosing numbering (de Bruijn style: `[X X]` spells `[(u0) (u0)]`,
`[Y Z]` spells `[(u0) (u1)]`), and the encoding of an element is the
spelling produced by a *local run* of the same procedure over it (nested
containers sorted, nested ties deferred and re-examined, what it cannot
resolve spelled `[:tie …]`). There is one worklist per run, so a tie
discovered inside an entered element joins the root worklist and is
re-examined. The orbit rule is replaced by a **component rule**: at a
fixpoint, tied elements that share an unnumbered cursor cell form a
component; a component holding two elements of one group is the D5
refusal; otherwise components are encoded by a local run over their
elements in group order, sorted, and the least run is entered (equal
encodings are isomorphic, so host order among them is harmless, proved
as lemma C). The proof is an isomorphism-invariance theorem with four
lemmas (§3 of the note).

## Per item

**1. BLOCKING, orbit lemma unsound.** Changed: the spelling (note §2
"Spelling"), D4 (components, component encodings, least-run entry), §3
(theorem, lemmas A–D). I evaluated the orchestrator's direction and
adopted it, with one addition it needs to be sound: local first-occurrence
indices inside an element are not enough when the element itself holds
nested unordered containers or when tied elements share cells across
groups, so the element's key is the output of a full local run (which
resolves nested ties by the same rules and spells unresolved ones as
`[:tie]`), and the orbit candidates are components, not elements. The
note shows why the weaker version fails: `[#{X Y} #{X Y}]` and
`[#{P Q} #{R S}]` would key equal under single-pass local indices with
`[:tie]` parts, yet they are not isomorphic, and entering them in either
order gives different rows; the local run with the component rule keys
them differently (prototype fixture `b35b8e12…`). Verified: review A
`#{[X X] [Y Z]}` and B `{X X, Y Z}` give one root for both enumerations
and after renaming plus shuffling; the roots `65ba4530…` and `409548a7…`
equal the digests codex computed for the `X=1, Y=2, Z=3` numbering, an
independent cross-check of the emission. Hand derivations in note §6.

**2. BLOCKING, nested tie groups.** Changed: D3 states that entering an
element inside a group runs D1 on it, so newly discovered groups join
the one worklist of the run and are re-examined next round; D4 and D5
recurse through component local runs; a "Totality" paragraph requires a
bijection onto `1…n` before emission, asserted by the implementation as
a defect (never a refusal, never a partial carrier). Prototype:
`discovery-numbering` throws "numbering is not total" with the missing
and extra cells; it fired once during revision on a malformed fixture of
mine (a census missing the worked frame's pending cursor cell), which is
the behaviour wanted. Verified: review C `#{#{X Y} #{Z W}}` numbers all
four cells, root `18b70de6…` for the state and for its renaming (and 6a
reaches the same root); `[#{#{X Y} #{Z W}} X]` numbers `X cc-1` first and
resolves the inner sets by the fixpoint and the component rule, root
`6ea03f9b…`.

**3. HIGH, the refusal class.** Changed: F4 states the predicate in one
sentence (two elements of one guest set or map with equal keys, linked by
a chain of shared unnumbered cursor cells at a fixpoint), separates "not
certified by this procedure" from "non-interchangeable" with examples
on each side, withdraws the rarity claim, and says the real-state rate
is unmeasured because the repository has no version-3 task corpus (Stage
E scenarios and the v2 handoff census tests hold cursor cells but no v3
lift exists before 6b). `[#{X Y} #{X Y}]` now numbers (root `92747c0f…`,
equal to 6a's). Verified by a permissive diagnostic mode in the
prototype (D5 replaced by host-order entry, 24 shuffles): the loop plus
2-cycle, the 2-cycle and the 3-cycle give one root (interchangeable,
refused anyway); the path `#{[A B] [B C]}` gives 2 roots and the
seven-cell state 4 roots (genuinely need a tie-break). Fixture census:
of the repository's own fixtures only the two built for the purpose hit
D5. Random census under a tie-biased generator: 25 of 2000 value states
(15 witnessed non-interchangeable, 10 not distinguished by 12 shuffles),
8 of 200 frame-bearing, 2 of 100 with children; the note reports these
as rates under that distribution, not as a claim about real states.

**4. HIGH, decode.** Changed: §2 "Validation and decode" specifies that
only a lift-side `:yin.k/non-portable` of kind `:unresolved-cursor-cells`
raised by the recomputation is translated to `:yin.k/undecodable` of the
same kind (path prefixed by the root id), resource refusals propagate as
themselves, no other non-portable kind can arise there because step 5
ran, and the validator order (after step 5, before `validate-frames`)
stays; A4 carries it into carrier §8 step 6 and the diagnostics
paragraph. Prototype `decode*` implements the translation and the
comparison. Unverified: the prototype's recompute runs after 6a's
`decode*` returns (so after `validate-frames`), because 6a's `decode*` is
private and monolithic; the note states the required order and the
acceptance item 16 tests it.

**5. MEDIUM, prototype follows the spec.** Changed: the prototype is
schema-driven (one `enter-node!`/`spell-node` pair over `row-table`
descriptors plus two synthetic kinds, `:entry` and `:component`);
numbered cells spell `[:cursor-cell [:n k]]` with `k` an integer and
local ones `[:cursor-cell [:u j body]]`; parked-table values are entered
through their `[:ref :act :tail]` descriptor (the old `:V` bug is gone);
install children are numbered by the same procedure in the prototype's
own emitter; `:window` is entered in numeric register order. New
fixtures: act window, K-frame window, environment matched to the free
environment, and a parked act at a park site (code from the frame tests'
`:park` segment), all decodable. Search: 2000 random value states with
nested sets, sets of pairs including `[X X]`, cursor-cell map keys and
cells aliased across containers, plus 200 frame-bearing and 100
with-children states; renaming every cursor cell and shuffling every
set, map and census: **0 invariance failures, 0 decode failures**,
2033 decode round trips ok, 232 not decodable through 6a's own
recompute under its default budget (the probe decodes through 6a's
`decode*`, which still runs 6a's numbering; a probe limitation, not a
numbering outcome), 35 refusals. The decode round trip caught one real
prototype bug during revision: a memoized local run returned the whole
numbering of another element's context, so an unrelated cursor cell was
adopted and shifted the provisional indices (seed 1016 gave two roots
over 40 shuffles). The memo now returns the numbering restricted to the
item's own cells; the note's "Memoization" paragraph records the
requirement. Also fixed in the generator during revision: reference-free
vectors, and set elements equal under decoded equality, both refused by
decode and both generator artifacts.

**6. MEDIUM, "linear work".** Changed: §2 "Work" states
`W = O(P · (d + 1) · (n + 1) · log m)` with memoized local runs (`P`
expanded positions, counted per reference so DAG expansion is bounded by
`P` rather than the row count; `d` container depth; `n` cursor cells;
`m` largest container) and `O(P · (n + 1)^(d+1) · log m)` without, and
makes `:work` charge one unit per position visited or spelled and one
per literal byte copied, so every spelling byte is paid for before
allocation; `:depth`, `:cursor-cells`, `:rows` and `:bytes` stay and
together bound what a spelling can copy; `:search` and `:label-bytes`
go. The recommendation line now says "work bounded and accounted" instead
of "linear work". A5 updates carrier §10.10's wording accordingly.
Unverified: the bound is argued, not measured; the prototype does not
charge budgets.

**7. MEDIUM, stale wording.** Changed: §0 defines canonical traversal
order versus host enumeration order; A2 now replaces the whole tail of
the Validation paragraph including "no encounter-order tie-break ever
decides validity" and "never falls back to encounter order"; A5 replaces
§10.10's "never falls back to encounter order" and answer 9's
"refinement-and-search"; A8 gives three explicit replacements for
continuation §5.5 (the body-label sentence with its "step 1" reference,
the "sorting their elements' encodings (step 2)" sentence, and a
qualification of "never derived from traversal order" to *host*
traversal order); A4 corrects the "two kinds" counts in carrier §8 and
§11 and states the `[:child]` and integer `[:n k]` changes in A1.

**8. LOW.** Changed: the portable-id example holds the id counter equal
(task B consumes one unrelated fresh name), and the paragraph concedes
the review's point that an id recomputed from structure is the
canonicalization problem again; engine citations are given against
branch `srvm-phase6a` (`gen-id` 227–230, `issue-ref` 311–323,
`handle-cursor-unminted` 557–568, `gensym` 2312–2318, the `:stream/cursor`
effect site 2595–2602, read with `git show` on the branch); D1 and the
F1 table say `:window` is in numeric ascending register order as the
carrier specifies.

## Pinned ids, old → new

- Unchanged from the first draft and from 6a: `fab223…`, `c4a7c7…`,
  `07eda35d…`, `da69c679…`, `49d4478c…`, `536c810b…`, ten-cell root
  `6e7c7a6b…`.
- Unchanged from the first draft (differ from 6a): `a3713a5e…`,
  `4993b5b1…`.
- First draft gave two roots depending on enumeration → one root:
  review A `#{[X X] [Y Z]}` `65ba4530…`; review B `{X X, Y Z}` `409548a7…`.
- First draft refused → numbered: `[#{X Y} #{X Y}]` `92747c0f…`.
- First draft left unnumbered (emitted source ids) → numbered: review C
  `#{#{X Y} #{Z W}}` `18b70de6…`.
- New pins: `[#{X Y} X]` `7dc3d5c4…`; `[#{#{X Y} #{Z W}} X]` `6ea03f9b…`;
  `#{[#{X Y} #{X Y}] [#{P Q} #{R S}]}` `b35b8e12…`.
- Still refused: loop plus 2-cycle (6a's `55ff168e…` stays removed; `L`
  now gets `cc-1` before the refusal), seven-cell.
- Every numbering was derived by hand in note §6 and the digest taken
  from the codec under that numbering; A, B, C and `[#{X Y} #{X Y}]`
  agree with an independent computation (codex's, or 6a's).

## Unverified or disputed

- No codex counterexample is disputed; all three reproduced on the first
  prototype and are fixed.
- The prototype's decode check runs after 6a's frame validation
  (item 4); the real codec must place it at step 6.
- Three-host runs, kondo, budgets charging: not run or not implemented
  (documents and a JVM scratch prototype only).
- The real-state rate of D5 is unmeasured (no v3 task corpus; item 3).
- The work bound is argued, not measured (item 6).
- Uncommitted changes in the p6a worktree, if any, remain invisible.

## Owner questions

Unchanged from the first report, restated in note §9: accept the D5
class as documented (recommended yes); refusal kind
`:unresolved-cursor-cells` rather than `:resource-limit` (recommended).
