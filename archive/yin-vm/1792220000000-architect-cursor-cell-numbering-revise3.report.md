Created-Local: 2026-10-12 +07
# Report: discovery-order numbering, round 4 (base design) — claude-fable-5-1

## Summary for the owner

**The rule.** A cursor cell is named by when a fixed walk over the task first meets it: the n-th cursor cell met is `cc-n`. The walk goes through the task in the carrier's table order, so everything is ordered except the members of guest sets and the entries of guest maps. Those are turned into sorted vectors first: each member gets a key, which is the member spelled out with already-numbered cursor cells by number and the others by their body, by how many times they occur in the task, and by the order they first appear inside the member. Members with equal keys are tied; they are set aside until the rest of the walk has numbered what it can, then keyed again. A tie that still remains is entered in any order if a cheap test shows that swapping its members changes nothing in the whole state; otherwise the lift refuses.

**Example.** `[P Q P]` numbers `P = cc-1, Q = cc-2` whatever the bodies. In `{'a #{X Y}, 'b X}` with equal bodies, `X` occurs twice and `Y` once, so `Y` is entered first: `Y = cc-1, X = cc-2`, and the root id is the same one phase 6a computes.

**What is refused, and why.** `#{[A B] [B A]}`: both pairs key alike and share both cells, so the test cannot prove the swap harmless (it is), and proving it would need a search; refused as `:unresolved-cursor-cells`, and a receiver that recomputes it refuses `:yin.k/undecodable` of the same kind. Same for `[#{X Y} #{X Y}]` and a few shapes like them. The seven-cell triangle plus square is refused too, and that one genuinely needs a search: entering a triangle edge first or a square edge first gives different bytes. Over generated states the refusal rate is 19 of 2000 random values, 4 of 200 frame-bearing states, 2 of 100 with install children, and 168 of 1000 of the reviewer's deliberately tie-heavy states; of all refusals, 21 were truly order-dependent and the rest were harmless ties the test could not certify.

**Size and difficulty against 6a, honestly.** The prototype's procedure is 296 non-blank lines (about 80 of them docstrings, trace and two diagnostic switches); 6a's refinement plus search plus label bound is 136. In `rows.cljc` expect about 200 lines added and 135 deleted. So it is not smaller in code. It is simpler in what has to be understood and proved: no search, no growing labels, one sort, one test, one page of proof (§2 of the note) with a plain termination measure; the definition can be stated in a paragraph, which 6a's could not.

**Two things to decide.** (1) The key spells each cell's occurrence count besides its body and local index, one integer from one pass; it is what numbers `#{[A B] [B C]}` and `[#{X Y} #{Y Z}]` without a search and what makes several fixtures land on 6a's own roots; dropping it is a one-line change. (2) The `:work` budget now also counts bytes, so its default becomes 4194304 (6a had 200000 positions plus 1048576 label bytes).

## What was done

- `docs/design/yin.vm.semantic-register-vm.cursor-cell-numbering.md` rewritten (745 lines). Normative §§1–7: lines 1–569; the readable core (rule §1, proof §2, coverage §3) is lines 1–315, 15 over the 300 target; §4 (6a cut list with `srvm-phase6a` line numbers), §5 (acceptance), §6 (amendments A1–A10) and §7 (owner questions) are implementer material. Appendices (non-normative): A prototype and measurements, B round 3's D4/D5/D6 as an extension not adopted (proof sketch, the review's gap, round-3 measurements), C portable ids, D history. The round-3 text stays on disk as `collab/1792220000000-numbering-note.partial-round4.md`.
- `collab/numbering-probe/src/probe/discovery.clj` rewritten to the base design exactly (no components, no trials, no `:search`); round 3's prototype kept unchanged as `probe/discovery_r3.clj` and its log as `run-r3.log`. New in the probe: the `:cursor-cells` gate before any work, the `:depth` gate in the walk, charging of the representation bound before every comparison encoding (6a's `size`), occurrence counts, the tie test, the `:member` path step, exhaustive interchangeability classification of refused states (every listed order of every container when at most 720 orders, else 48 samples), the reviewer's incidence-family generator, a `:count-spelling false` switch for the measurement of question (1). `run.sh` uses `mise exec -- java -Xmx2g @java.args clojure.main …`. Run in the foreground; log in `collab/numbering-probe/run.log`.

## Codex r3 findings, status

| Finding | Status in round 4 |
|---|---|
| HIGH: byte accounting absent | Every CBOR encoding for a comparison goes through one charged function that spends 6a's representation bound before encoding (keys, rank labels, structural keys, set spellings); positions, cell scans, rank and count preparation, memo keys charged; literals never copied into spellings (by reference), never encoded in ordered positions. Default raised to 4194304. Measured: max 624926 over 3300 states; 544554 for the 65536-char descriptor (encoded bytes 132540, all charged first). The 400000-element literal: free in a vector (work 1273), `:resource-limit [:work]` in a set, before any encoding. |
| HIGH: D6 induction incomplete | D6 and the component runs are gone from the base. One proof (§2): induction on (sub-term size, unnumbered cells); the only recursion is the key walk over a proper sub-term; each W3 pass or tie step numbers a cell or replaces queued members by proper sub-terms. D6's gap is recorded in appendix B. |
| MEDIUM: coverage paragraph wrong | §3 states the exact predicate (a surviving group fails (i) incomplete key, (ii) shared within group, (iii) shared across groups) with the reviewer's mixed-body and anchored examples as refused fixtures and the exhaustive verdicts (both interchangeable; the seven-cell state not). |
| MEDIUM: `:cursor-cells` and `:depth` gates dropped | Both in §1.5 and the probe: 2 cells under `{:cursor-cells 1}` and 65 under the default refuse `[:cursor-cells]`; `[[[[X]]]]` under `{:depth 3}` refuses `[:depth]`. §4 keeps 6a's `:256` and `:291-292`. |
| MEDIUM: large-literal test vs totality | §4 rewrites `input-row-bodies-do-not-spend-label-bytes`: `base` asserts the totality defect `{:missing [:x] :extra []}` (no `:yin.k/status`); `reachable`/`custody` keep succeeding; plus the in-set refusal. |
| "D6 not materially simpler than 6a" | Accepted; D6 not adopted. Honest size comparison above and in appendix A. |
| rank order vs 6a's label order | Ranks are from the structural label `[:body [:stream …] position]`, bytewise; 6a's nested-byte-string label order is not preserved (noted in round 3; discovery already changes other roots). |

## Probe results (round 4)

- Invariance failures: **0** of 3300 states (each compared with itself renamed and with every set, map and census shuffled; refusals compared by verdict and path).
- Decode: 0 failures; 2710 carriers decode and re-emit to their root; 397 stop at 6a's retained `[:label-bytes]` recompute inside the probe's decoder (probe limitation, as in round 3).
- Numbered / refused: random 1981/19 (16 interchangeable-but-refused, 3 truly non-interchangeable), frame-bearing 196/4 (3/1), with children 98/2 (1/0/1 undecided), incidence families 832/168 (92/17/59 undecided). Reasons: every refusal was (ii) or (iii), none (i).
- A more permissive test: host-order entry at every tie would have wrongly accepted the 21 non-interchangeable states (the justification for having a test). The round-3 component rule could recover at most 7 + 0 + 1 + 74 = 82 states (those failing only by sharing across containers). The occurrence count numbers 20 + 4 + 0 + 295 states that the literal "body plus local index" key would refuse; it loses none.
- Fixture pins (appendix A table): `fab223…` (6a), `2d9873a8…` (review counterexample, now 6a's root), `07eda35d…`, `da69c679…`, `49d4478c…`, `387f294a…` and `be62ba43…` (codex A/B, 6a's roots), `18b70de6…`, `a850fce6…` (`[#{X Y} X]`, 6a's), `0193312…` (witness, 6a's), `726a9fbb…` (path), `d84480d4…`, `e67405ec…`, `6e7c7a6b…`, frame fixtures `0b9ef61e…`, `ff124bfc…`. Round-3 pins that moved: the review counterexample, `[#{X Y} X]`, codex A/B, the witness, `[#{#{X Y} #{Z W}} X]` (all because of the occurrence count); nothing is landed, so nothing breaks.
- 6a's own tests in the probe project: 21 tests, 324 assertions, 0 failures.

## Verdict

**READY** for an implementation brief, conditional on the owner's two answers in §7 (occurrence count in the key; `:work` default). Remaining risks:

1. The proof is hand-written and reviewed by nobody yet; the tie step (automorphism from index-matched bijections plus confinement) and the termination measure are the parts a reviewer should attack first. Codex's 5000-state generator from round 3 should be rerun against the base probe.
2. Refusals in real programs: a guest set of pairs of cursors on one stream, with nothing else referencing them (the 2-cycle shape), refuses. No version-3 corpus exists to measure how often; the frame-bearing generator refused 2%.
3. Representation-bound charging makes a literal of more than about 30000 scalars inside a guest set or map refuse `[:work]` under the default (6a's limit was about 8000). Ordered positions are unaffected.
4. Portability of the key walk in `rows.cljc` (recursion depth bounded by `:depth`; `for` over possibly long sequences must become `mapv`/`reduce`; `bytes-key` signature differs between 6a and the probe), listed in §4.
5. The probe decodes through 6a's decoder with its recompute budget retained, so 12% of the generated carriers were not decode-checked; the real codec has no such second recompute.
