I’ll independently rerun the counterexamples, stress the revised numbering rule, and check its proofs and implementation cost against 6a. I’ll keep the worktree read-only.
The revised component rule addresses the original aliasing flaw. I’m now checking whether local runs remain canonical when sharing crosses several containers, and whether memoization supports the claimed work bound. The proof needs more scrutiny than the new fixtures alone provide.
The original counterexamples now produce matching roots, and `[#{X Y} #{X Y}]` numbers successfully. I found a spec/prototype discrepancy: D5 says a conflict in any local run causes refusal, while key-building local runs can retain ties and let the enclosing run resolve them later. I’m testing a small witness and the memoization bound.
My independent 5,000-state run finished with no root mismatches across two renamed and shuffled copies per state: 4,527 numbered and 473 refused. A targeted test found another gap: decode correctly translates a root D5 refusal, but a refusal in an install child escapes as `:yin.k/non-portable`. The revised implementation is also substantially larger than the first draft’s simplicity estimate.
Completed-GMT: 2026-10-11 20:46:32 GMT
Completed-Local: 2026-10-12 03:46:32 Asia/Bangkok

The original counterexamples are fixed. My independent 5,000-state search found no enumeration-dependent roots, but the revised note still has specification, decode, and work-bound defects. It also no longer delivers the promised simplification.

Review was read-only at HEAD `1bb2638cfcf00e509f34bf463e3083e51d593697`. No files were edited, staged, or committed. Timestamps use the available clock, which precedes the supplied Created-GMT.

Below, `note` means [the numbering note](/Users/sto/workspace/datomworld-srvm-numbering/docs/design/yin.vm.semantic-register-vm.cursor-cell-numbering.md), `probe` means [discovery.clj](/Users/sto/workspace/datomworld-srvm-numbering/collab/numbering-probe/src/probe/discovery.clj), and `rows` means phase 6a’s `src/cljc/yin/vm/ucf/rows.cljc`.

| Severity | File:line | Evidence | Correction |
|---|---|---|---|
| **High** | note:254; probe:262 | D5 requires lift refusal at a conflicting fixpoint of **any run**. However, key-building local runs return incomplete spellings without propagating refusal. `[#{#{[A B] [B A]}} A]` encounters such a conflict locally, then numbers and decodes successfully. | Distinguish speculative key runs from runs whose completeness is required. State precisely when local D5 becomes a lift refusal, and update F4, lemma D, and amendments accordingly. |
| **High** | probe:419, 425; note:280 | Decode translates root D5, but an install child’s D5 escapes from `emit-node*` as `:yin.k/non-portable`, with path `[:child 15]`. | Wrap the entire discovery recomputation, including child emission, in the narrow translation specified by the note. Prefix the receiver path with the carried root id. |
| **High** | note:290–306 | `P` counts expanded positions, but the claimed bound counts bytes too. With identical structure and fixed `P,d,n,m`, enlarging a stream descriptor from 128 to 65,536 characters increased encoded key bytes from 3,509 to 592,208. The memoization argument also ignores its counter dependency and different simulated numbering contexts. | Include expanded byte volume, including repeated body-label bytes, in the bound. Prove a bound on distinct memo contexts; monotonicity within one run does not establish it across local runs. |
| **Medium** | note:423 | A2 says the procedure “never reads” host enumeration order. D4 explicitly enters equal components in host order. | Say the emitted commitment and acceptance decision are invariant under host enumeration, with the permitted D4 use justified by lemma C. |
| **Medium** | note:635–647 | The prototype’s schema-and-algorithm region occupies roughly 330 physical lines; lines 67–364 alone contain 262 nonblank, noncomment lines. This does not support “about 230 lines with the schema helpers,” nor the assertion that adoption costs one review round. | Replace the optimistic size and review estimates with an implementation-based estimate, including work accounting and child refusal translation. |

**1. Previous counterexamples and mismatches**

I reran the prototype using `java -cp` and the existing classpath, avoiding launcher cache writes.

| Case | Independent rerun |
|---|---|
| `#{[X X] [Y Z]}`, both enumerations | X=1, Y=2, Z=3; identical roots |
| `{X X, Y Z}`, both enumerations | X=1, Y=2, Z=3; identical roots |
| `#{#{X Y} #{Z W}}` | Total numbering; reversal and renaming preserve the root |
| `[#{X Y} #{X Y}]` | Numbers successfully; reversal and renaming preserve the root |
| Root D5 decode translation | Correctly returns `:yin.k/undecodable :unresolved-cursor-cells` |
| Install-child D5 decode translation | **Still incorrect**, as reported above |
| Integer `[:n k]`, parked structural references, K frames | Previous prototype mismatches fixed |
| Install children using discovery numbering | Fixed for successful children |
| Totality | An unreachable declared cursor cell raises the expected defect, with `{:missing [:a], :extra []}` |

The author’s complete 2,300-state run reproduced its reported statistics. Existing 6a row tests also passed: **21 tests, 324 assertions, zero failures or errors**.

**2. Independent adversarial search and proof review**

My generator used ten families of incidence structures rather than the author’s recursive value generator: overlapping groups, randomized pair relations, aliased map keys, repeated collections, nested components, ordered anchors, and mixtures of equal and unequal bodies. It used 2–8 cursor cells. Of the 5,000 states, 250 included an install child.

For every state, I compared the original with **two independently renamed copies**, each permuting every set, map, and census. Renaming and traversal were implemented separately from the author’s transformation.

| Check | Result |
|---|---:|
| Random states | 5,000 |
| Numbered | 4,527 |
| Refused | 473 |
| Root/refusal-outcome mismatches | **0** |
| Exceptions during those comparisons | **0** |
| Decode round trips in the first 300 states | 193 successful |
| Decode resource refusals in that subset | 80 |
| Other decode failures in that subset | **0** |
| Cached versus cache-cleared local runs | 150 states, **0 differences** |

I initially generated some containers with duplicate decoded values despite different listed representations. I corrected that generator artifact before the reported 5,000-state run.

No additional memoization poisoning or accepted-state canonicality counterexample was found. Equal element keys can still conceal sharing with other elements, but the component construction correctly accounts for that sharing in the cases tested.

The two targeted failures have these exact constructions:

```clojure
(require '[probe.discovery :as p]
         '[yin.vm.ucf.rows :as r]
         '[yin.vm.ucf.rows-test :as t])

(def b (t/cursor-cell-body :s 0))
(def c #(vector :cursor-cell %))
(def v #(vector :row (r/node :vec (vec %))))
(def s #(vector :row (r/node :set (vec %))))

(def cycle-value
  (s [(v [(c :a) (c :b)])
      (v [(c :b) (c :a)])]))

;; A key-building local run reaches D5, but lift succeeds.
(def local-d5-witness
  (t/task-node [[:a b] [:b b]]
               (v [(s [cycle-value]) (c :a)])))

;; A valid carried child reaches D5 during discovery recomputation.
(def cycle-task
  (t/task-node [[:a b] [:b b]] cycle-value))

(def child
  (r/node :task (assoc (:slots cycle-task) 3 :child)))

(def parent
  (r/node :task
          (assoc (:slots (t/task-node [] [:lit nil]))
                 12 [(r/node :install
                             ['a :running nil {} child])])))

(p/discovery-numbering local-d5-witness)
;; {:numbering {:a :yin.k/cc-1, :b :yin.k/cc-2}}

(p/error #(p/decode* (r/emit-numbered parent {})))
;; :yin.k/status :yin.k/non-portable
;; :yin.k/kind   :unresolved-cursor-cells
;; :yin.k/path   [:child 15]
```

`local-d5-witness` also decodes successfully. Its root is:

```text
9600fe01f89d891085e7352c81da45d022e9088b0b897cce46dad755848485b5
```

Installing a child **inside a tied guest element** is outside the carrier schema: `:install` and `:task` are not permitted value-row kinds (`rows:404, 531`). I therefore tested tied values within legal install children rather than treating that malformed shape as a numbering counterexample.

The line-by-line proof assessment is:

| Passage | Assessment |
|---|---|
| Lemma A, note:323–331 | Reading structure, bodies, and corresponding numbers is appropriate. The nesting induction needs to cover component local runs explicitly. Its appeal to C is reasonable only with a stated simultaneous induction and decreasing measure. |
| Lemma B, note:333–334 | Correct by construction. It establishes key equality, not structural interchangeability. |
| Lemma C, note:336–353 | The substantive repair is sound under its assumptions: unnumbered-cell sets are disjoint; complete indexed spellings preserve alias topology; group numbers preserve group membership; block swaps therefore preserve the configuration. The completeness requirement is essential. |
| Lemma D, note:355–359 | Conflict incidence is invariant. The completeness argument depends on A. Which local conflicts actually cause refusal remains inconsistent between D5 and the prototype. |
| Theorem, note:361–371 | Plausible after fixing refusal semantics and formalizing the mutual induction. Testing found no contrary accepted-state result. |
| Termination, note:378–384 | Root-run progress is credible. “One level down” should identify the decreasing unordered-container measure despite synthetic component wrappers. It does not prove the advertised memoized work bound. |

**3. Simplicity audit**

The revised design is **not materially simpler or cheaper to review** than 6a.

My estimate, in existing `rows.cljc` formatting, is approximately **250–330 added or replacement lines and 135–150 deleted lines**, yielding roughly **100–195 net added lines**, excluding tests. Existing helpers reduce the prototype size, but portable budget accounting, refusal paths, and complete decode translation add work back.

| Aspect | 6a refinement/search | Revised discovery |
|---|---|---|
| Main proof obligations | Invariant signatures/positions; terminating refinement; exhaustive branch set with invariant least commitment | Canonical recursive keys; tie bookkeeping; complete component isomorphisms; invariant refusal; additionally memo correctness and recursive progress |
| Refusal class | Resource exhaustion under documented budgets; no D5 structural exclusion | Resource exhaustion **plus** uncertified sharing components |
| Lost coverage | Seven-cell fixture exceeds the label budget | Also refuses simple 2-cycles and 3-cycles that 6a numbers |
| Principal failure modes | Growing labels, exhaustive search cost, budget exhaustion | Context-sensitive memo bugs, incomplete local spellings, worklist/group bookkeeping, premature refusal, child exception leakage |
| Cost advantage | Poor worst-case search | Avoids exhaustive search, but its claimed polynomial accounting remains unproved |

The lemma count understates discovery’s burden: lemma B is almost tautological, while A and C carry substantially more machinery than their count suggests.

The revised note remains valuable for its complete position inventory, alias-preserving local spelling, totality assertion, distinction between canonical order and host enumeration, expanded-DAG accounting, and narrow decode translation. Keep those findings and the counterexample fixtures. If 6a remains, the new D5 translation is a future requirement rather than a currently necessary change.

**4. Grounding, amendments, pins, and portability**

I checked more than twelve cited locations. The scratch `rows.cljc` and `rows_test.cljc` matched their `srvm-phase6a` versions by checksum.

| Reference checked | Result |
|---|---|
| rows:37–57 | Position inventory matches the schema |
| rows:140–145 | Numeric census ordering confirmed |
| rows:148–156 | Final emitted collection ordering confirmed |
| rows:185–189 | Independent child numbering confirmed |
| rows:200–202 | Budget keys confirmed |
| rows:212–230 | Label-size checker confirmed |
| rows:252–255 | Initial labels confirmed |
| rows:257–284 | Content encoding confirmed |
| rows:285–325 | Position collection confirmed |
| rows:326–354 | Classes, refinement, and search confirmed |
| rows:570–575 | Decode recomputation and comparison confirmed |
| rows:576–578 | Frame validation follows numbering |
| rows_test:28, 159, 171, 206 | Named fixtures/tests confirmed |
| rows_test:307, 331, 392, 406 | Pins, numeric census, seven-cell and large-input tests confirmed |
| engine:227, 311, 557, 2312, 2595 | Revised citations correct against the named branch |

A1–A10 target the intended passages, and the integer encoding, child spelling change, diagnostic counts, and revised body label are explicit. A2 still contradicts D4’s permitted host-order use; the refusal descriptions inherit the D5 ambiguity.

These pinned BLAKE3 digests were recomputed:

| Fixture | Digest |
|---|---|
| Two-cell fixture | `fab223d9b3540fdce0ccf2132f8a4041917a158a32295470a4936754742d3ac4` |
| Review A | `65ba4530bd8908e734a8be4fcc594d2989c5a8a7d1017779ef8f62991df38dab` |
| Review B | `409548a71f080cae6f3f66911409370d83a5df9e928135d95c497e9b770d9328` |
| Nested sets | `18b70de6d4533d5ca797464568b5dd7812ca4b2f36a1e8fb64ef823a2eb3da45` |
| Repeated set | `92747c0f27e9c64876267b19dafa34edd359822341eca32d6067e199a926f116` |

For portability, a guest map containing a nil key and a nil-valued entry numbered and decoded successfully. A 20,000-element cursor-ref vector numbered and emitted successfully. Continuation §12 correctly retains `contains?` membership, `mapv`/`into`/`reduce` for large sequences, and `.cljc` fixture data instead of whitespace-sensitive EDN strings. **Node and Dart execution were not performed**; the scratch prototype’s private-var access is not portable.

**Recommendation: keep 6a’s refinement design with its documented resource limitation, retaining the revised note’s useful inventory, counterexamples, totality requirement, and decode guidance while deferring adoption of discovery-order numbering.**

VERDICT: CHANGES_REQUESTED
