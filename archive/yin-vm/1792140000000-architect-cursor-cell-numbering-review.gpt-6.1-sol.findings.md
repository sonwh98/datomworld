I’ll read the architecture rules, note, and carrier draft, then challenge the numbering rule with the prototype and check its claims against the phase 6a code. I’ll keep the worktree read-only.
I found a gap in the orbit lemma: equal content encodings erase aliasing within an element, so `[X X]` can tie with `[Y Z]` even though they aren’t interchangeable. I’m testing that case and nested ties. The Clojure launcher tried to write its classpath cache; I’ll use an existing classpath to keep the review read-only.
The prototype produces different BLAKE3 roots for the same set when I reverse `#{[X X] [Y Z]}`. The same failure occurs for map entries `{X X, Y Z}`. Nested sets also return an empty numbering, leaving source ids in the carrier. The reported fixtures and all 21 phase 6a row tests reproduce, but those tests miss these cases.
Completed-GMT: 2026-10-11 19:42:34 GMT
Completed-Local: 2026-10-12 02:42:34 Asia/Bangkok

Discovery-order numbering remains a viable direction, but **this note is not sound as written**. D4 admits choices that change the root id, and nested ties can leave cursor cells unnumbered.

Review was read-only at HEAD `7a6d7cc9`; phase 6a was inspected through Git at `d77da9f0`. No files were edited, staged, or committed. Completion timestamps use the available clock, which precedes the brief’s Created-GMT.

Findings below use `note` for `docs/design/yin.vm.semantic-register-vm.cursor-cell-numbering.md`, `probe` for `collab/numbering-probe/src/probe/discovery.clj`, and `rows` for phase 6a’s `src/cljc/yin/vm/ucf/rows.cljc`.

| Severity | File:line | Evidence | Correction |
|---|---|---|---|
| **Blocking** | note:153, 183 | D4’s occurrence-locality condition does not preserve alias topology **within** an element. Equal `CE` does not imply interchangeability. `#{[X X] [Y Z]}` passes D4 and produces different roots after element reversal. | Require a proven alias-preserving correspondence between tied elements, including their internal traversal. Conservatively refuse uncertified cases. Equal bodies and disjoint identities alone are insufficient. |
| **Blocking** | note:153; probe:146–162 | Entering D4 elements can discover nested tie groups. The prototype processes only the old residue, then returns success. `#{#{X Y} #{Z W}}` returns an empty numbering and emits source ids. | Specify and implement processing of newly discovered deferred groups, including further fixpoint/orbit checks. Require total numbering before emission. |
| **High** | note:70–88, 219 | The stated refusal class is too narrow. `[#{X Y} #{X Y}]` refuses even though X and Y are interchangeable and there are no guest pairs or map keys. | Describe D5 by its actual predicate. Separate “not certified by this procedure” from “non-interchangeable,” and remove the unsupported rarity claim. |
| **High** | note:352–354; rows:570–575 | Decode invokes `emit`. A newly introduced lift-side `:yin.k/non-portable` exception will propagate unless explicitly translated. Recompute-and-compare alone does not implement the proposed decode taxonomy. | Translate only `:unresolved-cursor-cells` to `:yin.k/undecodable` at recomputation, preserving resource refusals and validator precedence. |
| **Medium** | probe:48, 111–113, 131–139; note:299 | The prototype differs from D1–D5: numbered `CE` uses keyword `:yin.k/cc-n`, not integer `n`; parked-table values are incorrectly visited as `V`, although they are structural refs; the orbit walker omits `:refs`. Install children still use 6a numbering. | Make the prototype schema-driven and match the specified encoding. Test parked states, K frames, and children under discovery numbering in every scope. |
| **Medium** | note:18, 124, 192–196 | “Linear work” is not established. `CE` sorts unordered containers; repeated encoding and nested expansion add work. Acyclic DAGs can have large expanded traversals. | State a bound in terms of expanded positions, sorting, rounds, and encoded bytes. Account for that work before allocation under the retained budgets. |
| **Medium** | note:223–262 | Amendments leave stale wording: A2 leaves “no encounter-order tie-break ever decides validity”; A5 leaves §10.10’s “never falls back to encounter order”; A8 leaves the old byte-string body-label explanation and step-1 reference. | Update those passages explicitly. Distinguish canonical traversal from arbitrary host enumeration. |
| **Low** | note:274–289 | The portable-id example changes the carried `id-counter`, so the two complete tasks are already structurally different. Some engine line references are inaccurate. | Hold every carried field, including the counter, equal in the example; correct the citations. |

**1. Position inventory**

F1 is substantially correct: I found **no omitted identity-bearing position** in the carrier §5 tables or phase 6a’s row schema.

The ordered positions cover:

- Root free environment, store, module stores, parked table, waits, and result.
- Act environments, windows, K frames; tail K frames; captured acts.
- Closure bindings and module-store contents.
- Pending bare cursor-cell slots and guest value/envelope slots.
- Install children, each with an independent task-local numbering scope.

Only guest set elements and guest map entries lack a structural order. Census declaration edges are correctly excluded.

One wording correction: D1 should explicitly retain **numeric ascending register order** for windows, as the carrier specifies, rather than grouping windows indiscriminately under byte ordering. The prototype’s parked-table omission is an implementation defect, not an inventory omission.

**2. Attempts to break D1–D5**

I ran the prototype using `java -cp` with an existing classpath because the requested Clojure launcher attempted a prohibited cache write. All custom inputs were evaluated in memory.

The following constructor fixes the surrounding task exactly:

```clojure
(require '[probe.discovery :as p]
         '[yin.vm.ucf.rows :as r]
         '[yin.vm.ucf.rows-test :as t])

(def b (t/cursor-cell-body :s 0))
(def c #(vector :cursor-cell %))
(def row #(vector :row %))
(def v #(row (r/node :vec (mapv c %))))
(def s #(row (r/node :set %)))
(def task
  (fn [ids result]
    (t/task-node (mapv #(vector % b) ids) result)))
```

All named cursor cells below have the same body.

**Counterexample A — D4’s orbit lemma**

```clojure
(task [:x :y :z] (s [(v [:x :x]) (v [:y :z])]))
(task [:x :y :z] (s [(v [:y :z]) (v [:x :x])]))
```

These differ only in set enumeration. Both pass D4.

| Enumeration | Numbering | BLAKE3 root digest |
|---|---|---|
| `[X X]`, `[Y Z]` | X=1, Y=2, Z=3 | `65ba4530bd8908e734a8be4fcc594d2989c5a8a7d1017779ef8f62991df38dab` |
| `[Y Z]`, `[X X]` | Y=1, Z=2, X=3 | `387f294a2f1963f2f2241e8527d8698dedff5d50de4f46bb762b456a01375090` |

The emitted multisets contain respectively `[1 1], [2 3]` and `[1 2], [3 3]`. There is no alias-preserving automorphism exchanging the two elements. Both abstract inputs successfully emit and decode under existing 6a.

**Counterexample B — guest map keys and values**

```clojure
(task [:x :y :z]
      (row (r/node :map [(c :x) (c :x) (c :y) (c :z)])))

(task [:x :y :z]
      (row (r/node :map [(c :y) (c :z) (c :x) (c :x)])))
```

The roots differ:

```text
409548a71f080cae6f3f66911409370d83a5df9e928135d95c497e9b770d9328
be62ba43d9d07dc7b5e29375e195ab839ab37b721074af816e540615f6167ee9
```

This is the same missing alias distinction across an entry’s key and value.

**Counterexample C — nested unordered containers**

```clojure
(task [:x :y :z :w]
      (s [(s [(c :x) (c :y)])
          (s [(c :z) (c :w)])]))
```

The prototype returns `{:numbering {}}`. Renaming X/Y/Z/W to Q/R/S/T changes the emitted root:

```text
fddde4522dbebc4ee6fa2d78a1bd1d2691faccc26c4ca14ee17c620ecdcddf0b
abb32996b0ac30b720f17c6cfacf0323dcb16a47d58cc8e54c33571ce5d58080
```

The outer orbit step discovers inner ties but never processes them.

Other requested challenges:

- `[#{X Y} X]` correctly resolves to X=1, Y=2 through D3.
- `[#{X Y} #{X Y}]` refuses because identities cross tie groups.
- A parked act containing an ordered X reference is ignored by the prototype, causing an unnecessary refusal.
- An install child using the review fixture gets its old 6a numbering. Switching all scopes to discovery changes the parent root, confirming that current child results do not validate the proposed rule.

**3. Proof**

The proof fails at the orbit lemma’s claim that equal `CE` yields “the same internal pattern.” Body erasure removes exactly the alias information needed to establish that claim.

The renaming induction is reasonable for D1–D3 with a complete schema walk and fixed encoding. “No source id is read” is insufficient for permutation invariance: listed order remains an input to D4.

D3’s monotone numbering supports a finite bound on productive rounds. It does not prove that D4 finishes nested discovery or that every reached cursor cell receives a number. Consequently, the asserted bijection, determinism, and cross-engine conclusion are not established.

Sorting and repeated expansion also invalidate the unqualified linear-work claim.

**4. Refusal taxonomy and practical class**

The proposed families are appropriate:

- Lift: `:yin.k/non-portable`, kind `:unresolved-cursor-cells`.
- Decode recomputation: `:yin.k/undecodable`, same kind.
- Actual budget exhaustion: retain qualified unsupported/resource refusal.

A deliberate representational restriction is not a resource exhaustion. Run it at carrier validation step 6, after structural/canonical-form checks and before frame validation. Implement the decode translation explicitly.

The remaining class is **not demonstrated rare**. The two-group example above is a simple reusable collection, not an exotic graph. Moreover, repairing D4 conservatively will enlarge the refused class beyond what F4 currently describes. That limitation needs an accurate owner-facing description before adoption.

**5. Impact on 6a and pins**

The proposed deletions are broadly correct: refinement, positions, class construction, exhaustive search, growing-label checks, and search/label-byte budget keys.

Keep the carrier schema, BLAKE3 addressing, census numeric order, final row ordering, recompute-and-compare, and numbering-before-frame-validation order. Add a complete traversal, deferred processing, certified orbit rule, total-numbering assertion, refusal translation, and meaningful work accounting.

I recomputed these pins:

| Fixture | BLAKE3 digest | Result |
|---|---|---|
| Two-cell fixture | `fab223d9b3540fdce0ccf2132f8a4041917a158a32295470a4936754742d3ac4` | Unchanged |
| Worked frame | `c4a7c7e6de3b44400efec5930bc3d85d86b29153b4b2d548acba630bfbc3da7d` | Unchanged |
| Review counterexample | `a3713a5e1d1a051d1c0aab255bdc48349993fbf21d68ba947e74fc4408c68c28` | Matches proposed pin |
| `[X X Y]` | `4993b5b192de7d903a90f7bc87dde8c7d5ac007c2b013ef62b998dc988282576` | Matches proposed pin |
| `[X Y Y]` | `536c810b07c7cab1dd812fcb1bbf0c7433dc41af4e39037c5f278e82abf1e032` | Matches; unchanged from 6a |

The symmetric-pair, repeated-pair, map, and ten-cursor-cell pins also reproduced. The prototype reproduced the reported 400-state statistics. Existing 6a row tests passed: **21 tests, 324 assertions**.

Consumers include `emit`, child emission, `decode*`, and `canonical-under`. Continuation §5.4’s round-trip and cross-engine laws depend on canonical re-emission. `canonical-under` still recomputes through `emit`; its mathematical guarantee therefore inherits this defect.

Acceptance items 12–20 need the three counterexamples above, multi-group cases, parked/K coverage, discovery-numbered children, total-numbering checks, and decode round trips. Random testing alone missed both blocking defects. Keep a large-input regression even when deleting its obsolete label-budget expectation.

**6. Portable ids**

The rejection is sound **for immutable creation-history ids carried unchanged** under the owner’s structure-only root law. Local counters, execution sequence, occurrence/counter hashes, and random names cannot replace structural canonicalization.

Correct the example by making both tasks’ carried counters equal—for example, consume an unrelated fresh name in the second history. Do not claim every imaginable portable naming scheme is impossible: ids recomputed from snapshot structure are effectively the canonicalization problem again.

The note calls this F7; F6 is prototype evidence.

**7. Grounding, amendments, and portability**

I spot-checked more than twelve cited locations against branch code:

| Reference | Check |
|---|---|
| rows:37–57 | Row schema and identity-bearing positions |
| rows:140–145 | Numeric census order |
| rows:148–156 | Emitted collection ordering |
| rows:185–189 | Independent child numbering |
| rows:200–202 | Budget keys |
| rows:212–230 | Label-byte checking |
| rows:252–255 | Initial labels |
| rows:257–284 | Content encoding |
| rows:285–325 | Positions |
| rows:331–354 | Refinement and search |
| rows:570–578 | Recompute, compare, then frames |
| rows_test:28 | Two-cell fixture |
| rows_test:159 | Alias fixtures |
| rows_test:171 | Symmetric pair |
| rows_test:206 | Numbering precedes frame defect |
| rows_test:307–338 | Pins and numeric census |
| rows_test:392–421 | Seven-cursor-cell and large-input tests |

The scratch rows implementation and tests matched their branch versions by checksum. Engine citations need correction: `issue-ref` starts at 311, `gensym` at 2312, and unminted cursor creation is principally 557–568.

Amendments should also:

- State that `[:child]` deliberately replaces the existing `[:child <child root id>]`; “one change; nothing else changes” is inaccurate.
- Define `[:n k]` unambiguously as an integer and align the prototype.
- Remove the stale body-label explanation in continuation §5.5.
- Update §10.10’s encounter-order wording.
- Change the diagnostics’ “two kinds” count when adding a third lift refusal.

The direction is compatible with phase 5’s pure image derivations and scanners. Phase 6’s engine-independent frames and sharing requirements remain unchanged, but its equality laws require the repaired canonicality proof.

Retain continuation §12’s portability rules: nil-valued keys remain present; membership uses `contains?`; large sequences use `mapv`/`into`/`reduce`; pinned fixtures are `.cljc` data rather than whitespace-sensitive EDN strings on Dart. These were inspected, not validated by new Node or Dart runs.

VERDICT: CHANGES_REQUESTED
