# Cursor-cell numbering review r3

Reviewed HEAD `1bb2638cfcf00e509f34bf463e3083e51d593697`, including the uncommitted note and prototype. Phase 6a reference: `d77da9f04276fca34db6fe6c36bea38a576c09b2`. Only this findings file was edited. Review ran in the foreground, without agents. JVM commands used `mise exec -- java -Xmx1g @java.args clojure.main -` from the probe directory, with scripts supplied on stdin.

The previous speculative-run and install-child translation defects are fixed. The previous enumeration wording and size estimate are corrected. The work-bound defect is **not fixed**: dropping the polynomial claim is appropriate, but the replacement claim that budgets account for every allocated spelling byte is false. I found no accepted-state canonicality counterexample in the independent 5,000-state run. D6's canonicality argument nevertheless has concrete gaps, and F4's purported exact refusal coverage is incorrect.

References below use `note` = `docs/design/yin.vm.semantic-register-vm.cursor-cell-numbering.md`, `probe` = `collab/numbering-probe/src/probe/discovery.clj`, and `rows` / `rows_test` = the phase 6a files named in the prompt.

| Severity | Location | Finding and required correction |
|---|---|---|
| **HIGH** | note:369–395; probe:81–87, 112–125, 348, 355–403 | The claimed preallocation byte accounting is absent for cell spellings, row/tag/container overhead, ties, and repeated comparison encodings. `literal-bytes` itself allocates a complete CBOR encoding before checking the budget. Body-rank preparation and cell-incidence traversal are uncharged. Restore a conservative preallocation representation bound, charge encoding/comparison work consistently, and distinguish input-size allowance from cumulative allocation/work. |
| **HIGH** | note:404–420, 456–468, 487–494; probe:270–272, 331–340 | The simultaneous induction does not cover the actual D6 procedure. Lemma A says key runs reaching conflicts do not choose; under D6 those runs recursively search. A trial starts with the same numbering and cell set, not strictly fewer unnumbered cells. Forcing a nested unordered element need not number a cell before recursive work begins. Supply a well-founded measure for key runs, synthetic component runs, forced traversal and recursive trials, and state the D6 version of lemma A. |
| **MEDIUM** | note:157–177 | The exact-coverage paragraph overstates both the equal-body condition and the power of a single ordered anchor. Mixed-body components can refuse; an ordered anchor can leave a connected component unresolved. Retain the operational fixpoint/component predicate and replace the inaccurate structural paraphrase with qualified examples. |
| **MEDIUM** | note:828–862; probe:414–433; rows:256, 289–292 | The replacement slice omits enforcement of `:cursor-cells` and the numbering traversal's `:depth` check. The prototype accepts 65, 1,000 and 20,000 cells under default budgets, and accepts them even with `:cursor-cells 1`. The deleted phase 6a procedure enforced these bounds. Add these gates explicitly to the replacement instructions and prototype; decoder input validation does not restore the lift-side gates. |
| **MEDIUM** | note:896–899; rows_test:406–421 | “Same body” for the large-literal test is incompatible with the new totality assertion. The old `base` declares an unreachable cell and line 414 expects successful emit; discovery raises `{:missing [:x], :extra []}`. Rewrite that assertion as a totality-defect check, retaining successful large-literal tests for the reachable variants. |

## Previous findings and independent reruns

| Previous r2 finding | r3 status |
|---|---|
| HIGH: ambiguity over speculative key-run conflicts | **Fixed for D5.** D5, F4 and lemma D now distinguish speculative keys from required complete runs. The witness numbers and decodes in both modes. D6 needs its own proof wording, as above. |
| HIGH: install-child refusal escapes decode | **Fixed.** D5 lift gives `[12 a 15]`; decode gives `:yin.k/undecodable :unresolved-cursor-cells`, path `[<root> 12 a 15]`. D6 numbers and decodes the child. |
| HIGH: unjustified work bound | **Partially addressed, still blocking.** The polynomial guarantee is withdrawn and descriptor bytes disappear from provisional cell spellings. The replacement allocation guarantee is not implemented. |
| MEDIUM: “never reads enumeration order” | **Fixed.** A2 now describes commitment/acceptance invariance and explicitly permits D4's equal-component order. |
| MEDIUM: optimistic size/review estimate | **Size corrected.** The 330-line estimate is credible. A guaranteed implementation round plus review round remains a planning assumption, not evidence of reduced review cost. |

These targeted counterexamples were rerun independently in **both** modes:

| Case | Result |
|---|---|
| `#{[X X] [Y Z]}` | X=1,Y=2,Z=3; root `65ba4530…` |
| `{X X, Y Z}` | X=1,Y=2,Z=3; root `409548a7…` |
| `#{#{X Y} #{Z W}}` | Complete numbering; root `18b70de6…` |
| `[#{X Y} #{X Y}]` | Complete numbering; root `92747c0f…` |
| `[#{#{[A B] [B A]}} A]` | A=1,B=2; root `9600fe01…`; D5 work 50, D6 work 106/search 2 |
| Store `'a #{X Y}, 'b X` | X=1,Y=2; 1 root over 24 renamed/shuffled copies; decode succeeds |
| Both frame fixtures from r2 | y=1,x=2,test/cursor=3; 1 root over 24 copies each; decode succeeds |
| Unreachable census cell | Totality defect with missing cell, not a refusal |
| 3-cycle with D6 `:search 1` | `:yin.k/unsupported :resource-limit [:search]` |

The five residue fixtures were also compared over 100 renamed/shuffled copies each:

| Shape | D5 | D6 root | D6 trials/work |
|---|---|---|---:|
| 2-cycle | Refused | `0fe898cc…` | 2 / 77 |
| 3-cycle | Refused | `0b181cd4…` | 3 / 144 |
| Path `#{[A B] [B C]}` | Refused | `726a9fbb…` | 2 / 77 |
| Loop plus 2-cycle | Refused after numbering L | `858a891e…` | 2 / 86 |
| Triangle plus square | Refused | `d76d787a…` | 11 / 488 |

All D6 fixtures had one root. The first four decoded successfully. The seven-cell carrier hit the prototype decoder's retained **6a** recomputation limit `[:label-bytes]`; this is the documented probe limitation, not a D6 mismatch. D5 acceptance/refusal comparisons ignore source-name diagnostic text; renamed diagnostic lists are not expected to be byte-identical.

The independent generator was reconstructed around the prior review's ten incidence families, rather than using the author's recursive generator. Seed `179221`; 2–8 candidate cells with equal bodies; pair relations, overlapping groups, aliased maps, repeated/nested containers, reversed edges and ordered anchors. There were 250 install-child states. Each state was compared with **two independently renamed copies**, permuting every set, map and census. The renamer was implemented independently of `p/rename-and-shuffle`.

An initial run exposed duplicate decoded sets in my generator. I corrected that using a separate decoded-equality projection and reran the full 5,000 states. Only the corrected run is counted below:

| Check | D5 | D6 |
|---|---:|---:|
| States | 5,000 | 5,000 |
| Numbered | 2,883 | 5,000 |
| Structurally refused | 2,117 | 0 |
| Root or acceptance/refusal mismatches across copies | **0** | **0** |
| Decode successes among first 100 states | 46 | 80 |
| Decode resource limits in that subset | 11 | 20 |
| Other decode failures | **0** | **0** |
| D5-accepted roots changed by D6, across all 5,000 | **0** | **0** |

No general guarantee follows from these samples. The high refusal rate is deliberate tie-heavy incidence coverage, not an estimate for suspended production tasks.

## Budget evidence

I instrumented `cbor/encode` with an in-memory wrapper that counted calls and returned byte lengths, without changing source files:

| Fixture | Charged work | Recorded local key bytes | All CBOR output bytes / calls |
|---|---:|---:|---:|
| `#{10 equal-body cells}` | 105 | 1,850 | 6,667 / 79 |
| `#{64 equal-body cells}` | 591 | 11,840 | 43,333 / 511 |
| `#{128 equal-body cells}` | 1,167 | 23,680 | 86,789 / 1,023 |
| Four cells/two bodies, descriptor 128 chars | 78 | 1,396 | 5,132 / 56 |
| Same structure, descriptor 65,536 chars | 78 | 1,396 | 135,954 / 56 |

The set measurements are identical in D5 and D6. The descriptor fixture confirms the narrower rank-spelling benefit in both modes: local key bytes remain 1,396. It also demonstrates that rank construction's real encoded-byte cost still increases and is not charged to work. These totals are cumulative encoding bytes, **not** peak heap measurements.

The `#{64 cells}` result alone contradicts the note's one-unit-per-spelling-byte statement: 11,840 local key bytes versus 591 work units, before counting comparison encodings. Keys, ties and copied cursor-cell spellings are not literals, and therefore escape the byte charge. Repeated `bytes-key` calls also normalize/encode/materialize comparison vectors without charge. Memo keys and incidence scans have costs beyond the paid spellings.

A 400,000-element literal in an ordered vector with a reachable cell numbers using 17 work units; it is not expanded into a local spelling, which is appropriate. Moving that value under an unordered element causes a work refusal. But `literal-bytes` obtains the full encoded bytes **before** that refusal: it cannot enforce a preallocation guarantee. A small work budget is a useful refusal test, not proof that every costly operation is charged before allocation.

Ordered cell vectors charged 25, 79, 80, 1,015 and 20,015 work for 10, 64, 65, 1,000 and 20,000 cells. All accepted, including with `:cursor-cells 1`. Restore the census gate **before** ranks and memo preparation. Restore the structural depth gate and describe whether it applies to input structures or synthetic recursion; the latter needs its own well-foundedness argument.

A fix should cache each paid encoded comparison key, conservatively check/count representation size before encoding, account for synthetic/tag/cell/tie overhead, and charge incidence/rank preparation. Alternatively narrow the budget promise and introduce an explicit conservative byte bound for intermediates. The current section promises more than the prototype delivers.

## D6 proof and coverage

Lemma E's final minimum argument is sound **conditional on** each trial being canonical and complete: corresponding trial sets have the same multiset of faithful spellings; equal complete spellings exhibit isomorphic numberings; selecting the minimum is invariant. Choosing a different least conflicting group after a trial is also harmless if subsequent selection is already proved equivariant.

The missing part is the asserted induction establishing that condition. The actual trial at probe:338 is initialized with the parent's numbering. The forced element is encountered during traversal, not pre-numbered. In

```clojure
#{ #{[A B] [B A]}
   #{[A C] [C A]} }
```

forcing one outer element enters a nested set whose edges are deferred. Merely forcing it does not immediately number A or B. Component items add synthetic sets and ordered group wrappers, so “strictly fewer nested unordered containers” must specify which structural measure excludes those wrappers and how it decreases. D6 numbered this example with 10 trials, work 393, 28 local runs and one root over 100 copies; D5 refused it. It is evidence of functioning recursion, not a justification of the stated induction.

Moreover, D6 applies to speculative **key runs**: the r2 witness uses two search trials while computing keys even though D5 accepted the root without search. Lemma A iii's “does not choose, spells residue” argument therefore proves the D5 case only. The proof must distinguish modes and show nested key/component/trial transitions decrease a defined measure. I found no canonicality counterexample, but cannot certify lemma E as presently proved.

F4's operational predicate about duplicate group membership in a residual sharing component is the useful exact characterization. Its following “exactly which structures” paragraph is not equivalent:

1. `#{[A B] [A C]}`, with A at stream position 0 and B,C at position 1, refuses with cells `[A B C]`. The equal-body cells B,C are not both mentioned by either element; shared A has a different body. The claimed equal-body multi-cell-per-element pattern is too narrow.
2. `[#{[A B] [A C] [B D] [C E] [D B] [E C]} A]`, all bodies equal, numbers A and then refuses at `[15 0]`, cells `[B C D E]`. An ordered reference to one vertex does **not** break the tie for the whole connected component. D6 numbers it using four trials, work 201, root `ba717359…`.
3. The prose's “four distinct roots” for the seven-cell case is also stale: §6 itself reports one under the repaired permissive component entry. Such sampled equality is not proof of interchangeability.

Do not infer a production-frequency or universal anchored-component claim from the cycle/path examples.

## Rank order and section 8 audit

For the note's **own round-2 labels** `[:body [:stream …] position]`, rank replacement is order-preserving: distinct complete CBOR data encodings are prefix-free, comparison reaches the first differing rank position, and nonnegative CBOR integers sort in numeric order. The fixture pins and both-mode tests support that narrower claim.

It does **not** preserve the ordering of phase 6a's initial labels at rows:252–255. Those are:

```clojure
(cbor/encode [:body
              (cbor/encode [:stream …])
              (cbor/encode position)])
```

Their embedded byte strings compare length headers before payload bytes. Direct structural labels compare the stream's contents instead. A body with stream name `:a`, descriptor `{:peer <128 x characters>}`, position 0, versus one named `:zz`, descriptor `{:peer ""}`, position 0, gives:

- 6a label comparison: **+1** (a sorts after zz);
- note's direct-label comparison: **−1**;
- new ranks: `{:a 0, :z 1}`.

Thus answer to the requested 6a-order check is **no**. Clarify whether retaining 6a label order is intended. If so, compute ranks from exactly those label bytes, under an appropriate bound. If only round-2 discovery compatibility is intended, say so explicitly; discovery already changes other 6a roots, so this need not block adoption on its own.

The probe's rows and rows_test files match `srvm-phase6a` by SHA-1:
`e02350337c1b48a8d02901e46499d4cd0ab90f19` and
`d8aeed70b39a1f4770ba99fb699d06e6561a4128`.
I inspected the named deletion/rewrite spans against those copies:

- rows:212–230, 245–255, 257–284, 285–325, 326–354: the named label/check/encoding/position/refinement/search helpers and boundaries are accurate.
- rows:185–189, 200–209, 358–372, 570–578: child numbering, budgets, emission entry points, recomputation and subsequent frame validation are accurately identified.
- rows_test:171–178, 307–328, 331–338, 392–403, 406–421: names, fixture locations, removed pins and old budget checks are accurately identified.
- The cut must preserve/reintroduce rows:256's census count gate and rows:291–292's depth gate, as above.
- `descriptors` is listed both as extended and kept unchanged. Describe the base schema helper as reused with synthetic support added.
- The 400,000-element test is not kept with the same body; its unreachable-census assertion must change. Successful reachable ordered-literal cases can remain.
- Reusing rows' `bytes-key` needs care: it accepts already encoded bytes; the probe helper accepts a value and encodes it. They are not interchangeable without an encoding wrapper.

Phase 6a row tests passed: **21 tests, 324 assertions, zero failures/errors**. This verifies the reference slice, not a production integration of discovery. No Node or Dart execution was performed.

## Simplicity and recommendation

D6 is **not materially simpler than 6a**, honestly. It removes iterative label growth/refinement but retains recursive search, now combined with alias-preserving local spelling, deferred worklists, incidence components, adoption, context-sensitive memoization, forced traversal and resource accounting. It has not reintroduced the same refinement algorithm; it has reintroduced much of the search/proof burden while adding different bookkeeping.

The probe's lines 67–449 contain 337 nonblank, noncomment lines (including multiline docstrings). Lines 34–449 contain 361. Reuse in rows reduces that, but correct byte/census/depth accounting adds work back. The note's estimated 250–300 added/replacement and 135–150 deleted lines is broadly plausible; my retained planning range is **about 100–195 net added lines, excluding tests**, with the budget fixes still unimplemented. D5 removes roughly 25 lines of D6 but adds refusal diagnostics and decode translation; the net simplification is small.

Compared with 6a, D6 trades growing-label failure for nested trial/context/memo/forcing and accounting failures. Its proofs must establish canonical keys, residual alias completeness, component swaps, equivariant trial selection and a valid recursion measure. “Five lemmas” is not a meaningful reduction in those obligations. It does have a measured advantage on the seven-cell fixture: 11 residue trials instead of hitting 6a's label bound.

**Recommendation within the owner's discovery-order decision: choose D6 after the blocking fixes.** D5's structural rejection is broader than the prose admits, and rejects simple legal symmetric states that 6a handles. The small code saving does not outweigh the refusal semantics, decoder translation and coverage regression. D6 repairs that regression and passed this adversarial run, but adopt it for those concrete benefits, not on a claim of materially simpler implementation or proof. Neither variant is ready under the present accounting and proof text.

## Reproducible independent generator

The following is the corrected stdin script used for the reported 5,000-state run. It uses the prototype only for schema descriptors, fixture construction, numbering/emission/decode and census collection; generation, decoded equality, shuffling and renaming are independent.

```clojure
(require '[probe.discovery :as p] '[yin.vm.ucf.rows :as r] '[yin.vm.ucf.rows-test :as t])
(def rng (java.util.Random. 179221))
(defn sh [xs] (let [a (java.util.ArrayList. (vec xs))] (java.util.Collections/shuffle a rng) (vec a)))
(defn c [x] [:cursor-cell x])
(defn v [xs] [:row (r/node :vec (vec xs))])
(defn dv [x] (case (first x) :row (let [n (second x) ss (:slots n)] (case (:kind n) :vec (mapv dv ss) :set (set (map dv ss)) :map (into {} (map (fn [[a b]] [(dv a) (dv b)]) (partition 2 ss))))) x))
(defn s [xs] [:row (r/node :set (second (reduce (fn [[seen out] x] (let [k (dv x)] (if (contains? seen k) [seen out] [(conj seen k) (conj out x)]))) [#{} []] xs)))])
(defn m [ps] [:row (r/node :map (vec (mapcat identity ps)))])
(def b (t/cursor-cell-body :s 0))
(defn task [value] (t/task-node (mapv #(vector % b) (sort (p/reached value))) value))
(defn rename2 [n bij]
 (letfn [(val2 [x] (case (first x) :cursor-cell (c (bij (second x))) :row [:row (go (second x))] x))
         (go [n] (let [ss (:slots n) k (:kind n)]
          (r/node k (case k
           :set (sh (mapv val2 ss))
           :map (vec (mapcat identity (sh (mapv (fn [[a z]] [(val2 a) (val2 z)]) (partition 2 ss)))))
           :cursor-cells (vec (mapcat identity (sh (mapv (fn [[a z]] [(bij a) (go z)]) (partition 2 ss)))))
           (mapv (fn [d x] (cond (p/ref? d) (go x) (p/refs? d) (mapv go x) (= :C d) (bij x) (p/v? d) (when x (val2 x)) :else x)) (p/descriptors k ss) ss)))))] (go n)))
(defn gen [i]
 (let [n (+ 2 (.nextInt rng 7)) cs (vec (range n)) cc #(c (nth cs (.nextInt rng n)))
       edge #(v [(cc) (cc)]) es (vec (repeatedly (+ 2 (.nextInt rng 9)) edge))
       group #(s (repeatedly (+ 2 (.nextInt rng 4)) cc))
       value (case (mod i 10)
         0 (s es)
         1 (v [(s es) (s (sh es))])
         2 (s (repeatedly 4 group))
         3 (v [(s es) (cc)])
         4 (s (map #(v [% (group)]) es))
         5 (m (mapv (fn [x] [(c x) (cc)]) cs))
         6 (v [(s es) (s (map #(v (reverse (:slots (second %)))) es))])
         7 (s (map #(m [[(cc) %] [[:lit :anchor] (group)]]) es))
         8 (s [(v [(s es) (group)]) (v [(group) (s (sh es))])])
         9 (v [(s es) (s (repeatedly 4 group)) (cc)]))
       root (task value)]
  (if (zero? (mod i 20)) (p/with-child (t/task-node [] [:lit nil]) root) root)))
(def stats (atom {}))
(def different (atom []))
(defn norm [x] (if (keyword? x) x (if (:refused x) :refused x)))
(doseq [i (range 5000)]
 (let [q (gen i) ids (p/all-census-cells q)
       copies (vec (repeatedly 2 #(rename2 q (zipmap ids (sh (mapv (fn [j] (keyword "fresh" (str j))) (range (count ids))))))))]
  (let [a (binding [p/*numbering-opts* {}] (p/do-root q)) z (binding [p/*numbering-opts* {:residue-search true}] (p/do-root q))] (when (and (keyword? a) (not= a z)) (swap! different conj i) (when (= 1 (count @different)) (println "D5-D6-CHANGE" i (pr-str q) a z))))
  (doseq [mode [false true]]
   (binding [p/*numbering-opts* {:residue-search mode}]
    (let [outcomes (mapv p/do-root (cons q copies))]
     (swap! stats update [mode (if (keyword? (first outcomes)) :numbered (norm (first outcomes)))] (fnil inc 0))
     (when-not (apply = (map norm outcomes)) (println "MISMATCH" i mode (pr-str q) outcomes) (throw (ex-info "mismatch" {})))
     (when (and (< i 100) (keyword? (first outcomes)))
       (let [e (p/error #(p/decode* (p/emit* q)))]
        (swap! stats update [mode :decode (if (nil? e) :ok (:yin.k/kind e))] (fnil inc 0))))))))
 (when (zero? (mod (inc i) 1000)) (println "PROGRESS" (inc i))))
(println "COUNTS" @stats "D5-D6-CHANGES" (count @different))
```

VERDICT: CHANGES_REQUESTED

