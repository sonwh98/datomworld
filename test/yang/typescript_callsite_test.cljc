(ns yang.typescript-callsite-test
  "Contract C1 for production yang.typescript (Addendum A, design §2): the
   `:yang/call-site 1` frontend-metadata annotation of every final
   `:application` occurrence on this frontend's compilation and publication
   paths, and the complete-coverage validation a consumer runs against
   canonical rows. Mirrors `yang.clojure-callsite-test`'s shape: the
   equality gate, the boundary, the validation failures,
   safepoint-independence and canonical invariance.

   THREE key families, never conflated:

   - `:yang/call-site` (this file) is COMPILE-TIME application-occurrence
     frontend metadata. It rides the EXISTING generic side table:
     projection (`yin.vm/ast-side-tables`) drops it from canonical rows and
     emits `[origin root-address path :yang/call-site 1]`. The walk is
     `yang.core.uast/annotate-call-sites`; the ts frontend consumes it and
     re-mints nothing.
   - `:yang/site` is the SAFEPPOINT mark. It travels as NODE METADATA, not
     as a plain node key; `safepoint-independence-test` pins the
     distinction. The design forbids aliasing one to the other.
   - `cell/dsg__new-provenance` / `cell/dsg__provenance` are the EXCEPTION
     TRACK's runtime VM primitive doors (landed `8c10c5b7`), minting and
     reading retained-condition provenance while a task runs. Different
     family, different lifetime, different table: they are host primitives
     a guest calls, never frontend metadata a lowering emits. The
     TypeScript prelude does not reach them at all — it authenticates a
     retained condition through its own sealed companion cell — and
     `export-audit-pin-test` pins both halves of that: no `dsg__` door
     appears in a `js` definition, and no C1 surface leaks into the
     guest-visible `js`/`jssp` exports or runtime keys.

   Portable (JVM/Node/Dart). Every fixture is a hand-built CST packet from
   `yang.typescript.antlr.lower-portable-test`, so no local parser is
   involved: the same source-derived packet lowers to the same occurrence
   coordinates on every lane. That is the cross-host EQUALITY GATE the
   orchestrator ruled (1795010000000) to be part of THIS frontend's own
   acceptance — same source on the ts lanes, identical coordinates, and
   never a cross-frontend comparison (different frontends compile
   different sources, so there is no cross-frontend coordinate to equal).
   `cross-host-equality-test` is the local half of that gate; the full lane
   matrix is the orchestrator's, on shiva/jupiter."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [yang.clojure :as yang]
            [yang.core.uast :as u]
            [yang.safepoint :as safepoint]
            [yang.typescript.antlr.lower :as lower]
            [yang.typescript.antlr.lower-portable-test :as portable]
            [yang.typescript.antlr.prelude :as prelude]
            [yang.typescript.antlr.safepoint :as hooks]
            [yin.vm :as vm]
            [yin.vm.encoder :as encoder]
            [yin.vm.semantic.linearize :as sr-linearize]))


;; =============================================================================
;; Fixtures: hand-built CST packets, so every lane compiles the same input
;; =============================================================================

(def ^:private var-x
  "`var x = 1;`"
  (portable/program (portable/var-stmt "var" "x" (portable/num-expr "1"))))


(def ^:private log-x
  "`var x = 7; console.log(x);` — a member call, so the generated
   `js/get-value` / `js/call` dispatch is in the tree."
  (portable/program
    (portable/var-stmt "var" "x" (portable/num-expr "7"))
    (portable/log-stmt (portable/name-expr "x"))))


(def ^:private while-x
  "`while (x) ;` — one `:loop` safepoint site."
  (portable/program
    [:iterationStatement
     ["While" "while"] ["OpenParen" "("]
     [:expressionSequence (portable/name-expr "x")]
     ["CloseParen" ")"]
     [:statement [:emptyStatement_ ["SemiColon" ";"]]]]))


(def ^:private function-f
  "`function f() {}` — one `:call` safepoint site."
  (portable/program
    [:functionDeclaration
     ["Function_" "function"]
     [:identifier ["Identifier" "f"]]
     [:callSignature ["OpenParen" "("] ["CloseParen" ")"]]
     ["OpenBrace" "{"]
     [:functionBody]
     ["CloseBrace" "}"]]))


;; =============================================================================
;; The projection and the two coordinate derivations
;; =============================================================================

(def ^:private medium
  "This test's program medium: the origin's first opaque coordinate, which
   the admitting composition supplies and the frontend never mints."
  :yang.ts/callsite-test)


(def ^:private token "ts-callsite-1")


(def ^:private origin [:source medium token 0])


(defn- project
  "The canonical rows and the generic frontend-metadata side table of `ast`
   under this test's admission origin, through the production encoder."
  [ast]
  (let [projected (encoder/project (encoder/source-envelope medium token [ast]))]
    {:tree (first (:yin/batch projected))
     :metadata (:yin/frontend-metadata projected)}))


(defn- eligible-under
  "The eligible C1 site set of canonical `tree` under `origin`, derived
   here directly from the occurrence relation and the row tags —
   independently of the producer's own walk."
  [tree origin]
  (into #{}
        (map (fn [[root path]] [origin root path]))
        (vm/application-occurrences tree)))


(defn- eligible
  [tree]
  (eligible-under tree origin))


(defn- occurrence-pairs
  "The `[root-address path]` of every `:application` occurrence of
   canonical `tree`: the projection a consumer derives from canonical rows
   alone, origin-free."
  [tree]
  (into #{} (map (fn [[root path]] [root path])) (vm/application-occurrences tree)))


(defn- marked-under
  "The `[origin root-address path]` of a C1 marker relation."
  [metadata origin]
  (into #{}
        (comp (filter #(= :yang/call-site (nth % 3)))
              (map (fn [[o r path _ _]] [o r path])))
        metadata))


(defn- marked
  [metadata]
  (marked-under metadata origin))


(defn- marked-pairs
  "The `[root-address path]` projection of a C1 marker relation."
  [metadata]
  (into #{}
        (comp (filter #(= :yang/call-site (nth % 3)))
              (map (fn [[_ r path _ _]] [r path])))
        metadata))


(defn- marker-rows
  [metadata]
  (filterv #(= :yang/call-site (nth % 3)) metadata))


(defn- failure-rule
  [f]
  (try (f) ::no-failure
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (:rule (ex-data e)))))


(defn- nodes
  [tree]
  (tree-seq #(or (map? %) (sequential? %))
            #(if (map? %) (vals %) %)
            tree))


(defn- strip-call-sites
  "`node` with every C1 marker removed, at every depth the row grammar
   knows. Node metadata and every other key survive, so the answer is
   exactly the tree this frontend answered before the adoption."
  [node]
  (let [slots (get vm/semantic-bytecode-grammar (:type node))]
    (reduce (fn [n [field kind]]
              (case kind
                :node (update n field strip-call-sites)
                :nodes (update n field #(mapv strip-call-sites %))
                n))
            (dissoc node :yang/call-site)
            slots)))


(defn- marked-operator-names
  "The operator name of every C1-marked `:application` of `ast`: the K of
   each generated dispatch. An application whose operator is a lambda (a
   scope's cell binding, a sequencing step) has no name and contributes
   nothing."
  [ast]
  (into #{}
        (keep (fn [n]
                (when (and (map? n)
                           (= :application (:type n))
                           (contains? n :yang/call-site))
                  (get-in n [:operator :name]))))
        (nodes ast)))


(defn- application-nodes
  [ast]
  (filter #(and (map? %) (= :application (:type %))) (nodes ast)))


;; =============================================================================
;; The production trees
;; =============================================================================

(defn- source-trees
  "The production trees that carry no bundled prelude, as `[label ast]`:
   every public lowering entry point this frontend answers, plus both
   published linker module trees. `lower-transform`'s envelope member has
   its own test, because its origin is the stage's admission, not this
   file's."
  []
  [["lower-module-body" (lower/lower-module-body var-x)]
   ["lower-unit" (lower/lower-unit var-x)]
   ["lower-units, linked, two units"
    (lower/lower-units [var-x log-x] {:prelude :linked})]
   ["lower-units, linked, no unit" (lower/lower-units [] {:prelude :linked})]
   ["lower-packet, linked" (lower/lower-packet var-x {:prelude :linked})]
   ["lower-packet, linked, a loop"
    (lower/lower-packet while-x {:prelude :linked})]
   ["lower-packet, linked, a function"
    (lower/lower-packet function-f {:prelude :linked})]
   ["js module-uast" prelude/module-uast]
   ["jssp module-uast" hooks/module-uast]])


(defn- bundled-trees
  "The bundled profile: the whole prelude tree composed ahead of the body,
   so the composition — not the unit — is the finalized tree."
  []
  [["lower-packet, bundled" (lower/lower-packet var-x)]
   ["lower-units, bundled" (lower/lower-units [var-x log-x])]])


(defn- cheap-trees
  "The trees a lane may walk repeatedly without paying for the prelude."
  []
  (remove (fn [[label _]]
            (or (str/includes? label "module-uast")
                (str/includes? label "bundled")))
          (source-trees)))


;; =============================================================================
;; The equality gate
;; =============================================================================

(deftest equality-gate-test
  (doseq [[label ast] (concat (source-trees) (bundled-trees))]
    (testing label
      (let [{:keys [tree metadata]} (project ast)]
        (is (seq (marked metadata))
            "the tree claims coverage: it emits C1 rows at all")
        (is (= (eligible tree) (marked metadata))
            "the eligible application set equals the marker set")
        (is (= (occurrence-pairs tree) (marked-pairs metadata))
            "and on the origin-free projection a consumer derives alone")
        (is (true? (yang/validate-call-site-coverage! tree metadata origin)))
        (is (true? (yang/validate-ast-call-sites! ast origin)))
        (is (every? (fn [row]
                      (and (= 5 (count row))
                           (= origin (nth row 0))
                           (= (:root tree) (nth row 1))
                           (= :yang/call-site (nth row 3))
                           (= 1 (nth row 4))))
                    (marker-rows metadata))
            "every row is the core's coordinate tuple, exactly:
             [origin root-address path :yang/call-site 1]")
        (is (every? #(= (:root tree) (nth % 1)) metadata)
            "no row of the relation names another tree")))))


(deftest stage-envelope-test
  (testing "the streaming stage's envelope carries the finalized tree under
            the STAGE's admission origin, which the composition supplies"
    (doseq [profile [:bundled :linked]]
      (testing (str profile)
        (let [[_ [[port envelope]]] (lower/lower-transform {:prelude profile}
                                                           var-x)
              projected (encoder/project envelope)
              tree (nth (:yin/batch projected) (:yin/root projected))
              metadata (:yin/frontend-metadata projected)
              admission (vm/source-origin lower/program-medium [:hand 0] 0)]
          (is (= :program port))
          (is (= admission [:source lower/program-medium [:hand 0] 0])
              "the packet's unit is the batch token, as the stage documents")
          (is (= (eligible-under tree admission) (marked-under metadata admission)))
          (is (true? (yang/validate-call-site-coverage! tree metadata admission)))
          (is (empty? (marked-under metadata origin))
              "the coordinates carry the composition's origin, never this
               file's: the frontend stamps no origin of its own"))))))


;; =============================================================================
;; The boundary: what is an occurrence here, and what is data
;; =============================================================================

(deftest boundary-test
  (let [linked (lower/lower-packet log-x {:prelude :linked})]
    (testing "every application node, and only an application node, carries the key"
      (is (= (count (application-nodes linked))
             (count (filter #(= 1 (:yang/call-site %)) (application-nodes linked))))
          "no application occurrence is left unclaimed")
      (is (not-any? #(and (map? %)
                          (contains? % :yang/call-site)
                          (not= :application (:type %)))
                    (nodes linked))
          "no lambda, literal, variable or if is marked"))
    (testing "the ts frontend emits only grammar node types, so the shared
              walk can never reach its Unknown-AST-node-type refusal"
      (is (every? #(contains? vm/semantic-bytecode-grammar (:type %))
                  (filter #(and (map? %) (contains? % :type)) (nodes linked)))))
    (testing "a literal payload is data, never syntax: the walk does not enter it"
      (is (not-any? #(and (map? %)
                          (= :literal (:type %))
                          (some #{:yang/call-site}
                                (tree-seq coll? seq (:value %))))
                    (nodes linked))))
    (testing "the generated runtime, member and dispatch applications each
              get their own K (design §5.3)"
      (is (every? (marked-operator-names linked)
                  '[js/run-main js/call js/get-value js/global-get
                    cell/new cell/set! require js/init!])
          "source calls lower to js/call over js/get-value, a free name to
           js/global-get, a binding to cell/new + cell/set!, and the linked
           profile's own require/init wrapper applications are occurrences
           too — none of them escapes marking"))
    (testing "an immediately applied lambda is an ordinary occurrence"
      (is (some #(and (= :application (:type %))
                      (contains? % :yang/call-site)
                      (= :lambda (:type (:operator %))))
                (application-nodes linked))
          "a scope's cell binding and a sequencing step are applications"))
    (testing "a published module tree's yin/def occurrences are marked"
      (is (contains? (marked-operator-names hooks/module-uast) 'yin/def))
      (is (contains? (marked-operator-names prelude/module-uast) 'yin/def))
      (is (= 1 (:yang/call-site hooks/module-uast))
          "the module tree's root is one wide application"))))


;; =============================================================================
;; The validation failures a consumer must be able to name
;; =============================================================================

(deftest validation-failure-test
  (let [ast (lower/lower-packet var-x {:prelude :linked})
        {:keys [tree metadata]} (project ast)
        markers (marker-rows metadata)
        site (vec (take 3 (first markers)))
        non-application-path
        (some (fn [[_ path id]]
                (when (not= :application (nth (get (:rows tree) id) 1))
                  path))
              (sort-by (comp pr-str second) (vm/occurrences tree)))]
    (is (seq markers))
    (is (some? non-application-path)
        "the tree holds a non-application occurrence to aim a bad row at")
    (testing "duplicate identical deliveries dedupe"
      (is (true? (yang/validate-call-site-coverage!
                   tree (into metadata markers) origin))))
    (testing "a conflicting value at one site fails"
      (is (= :yang/call-site-conflict
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (conj metadata (conj site :yang/call-site 2))
                  origin)))))
    (testing "an unsupported version fails"
      (is (= :yang/call-site-unsupported-version
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (mapv (fn [row]
                               (if (= :yang/call-site (nth row 3))
                                 (assoc row 4 2)
                                 row))
                             metadata)
                  origin)))))
    (testing "a nonexistent path fails"
      (is (= :yang/call-site-nonexistent-path
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (conj metadata [origin (:root tree) [99 99]
                                       :yang/call-site 1])
                  origin)))))
    (testing "a non-application target fails"
      (is (= :yang/call-site-non-application
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (conj metadata [origin (:root tree) non-application-path
                                       :yang/call-site 1])
                  origin)))))
    (testing "a missing marker under a complete-coverage claim fails"
      (is (= :yang/call-site-incomplete-coverage
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (vec (remove (set markers) metadata)) origin)))))
    (testing "a malformed marker row is rejected, not silently filtered"
      (is (= :yang/call-site-malformed
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (conj metadata [origin (:root tree) []
                                       :yang/call-site])
                  origin))))
      (is (= :yang/call-site-malformed
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (conj metadata [origin (:root tree) []
                                       :yang/call-site 1 :extra])
                  origin)))))
    (testing "a duplicate entry in a declared table is rejected"
      (is (= :yang/call-site-duplicate-entry
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (into metadata markers) origin {:declared? true})))))
    (testing "a second origin claim for one occurrence is rejected"
      (let [other-origin [:source :yang.ts/other-publisher "batch-9" 0]
            double-marked (conj metadata (assoc (first markers) 0 other-origin))]
        (is (= :yang/call-site-multi-claim
               (failure-rule
                 #(yang/validate-call-site-coverage!
                    tree double-marked origin {:declared? true}))))
        (is (= :yang/call-site-multi-claim
               (failure-rule
                 #(yang/validate-call-site-coverage!
                    tree double-marked origin
                    {:per-occurrence-origins? true}))))
        (is (= :yang/call-site-multi-claim
               (failure-rule
                 #(yang/validate-call-site-coverage!
                    tree double-marked origin)))
            "the default mode rejects the second claim with the same
             diagnostic, not a downstream nonexistent-path error")
        (try (yang/validate-call-site-coverage!
               tree double-marked origin {:declared? true})
             (is false "the double marking must fail")
             (catch #?(:cljd Object :clj Exception :cljs :default) e
               (let [d (ex-data e)]
                 (is (= [(:root tree) (nth (first markers) 2)] (:site d))
                     "the diagnostic names the double-claimed occurrence")
                 (is (= #{origin other-origin} (set (:origins d)))
                     "the diagnostic names both origins"))))))))


;; =============================================================================
;; Safepoint independence
;; =============================================================================

(deftest safepoint-independence-test
  (testing ":yang/site marks and :yang/call-site markers are distinct"
    (let [loop-ast (lower/lower-packet while-x {:prelude :linked})
          fn-ast (lower/lower-packet function-f {:prelude :linked})
          loop-tree (vm/ast->semantic-bytecode loop-ast)
          loop-tables (vm/ast-side-tables loop-ast loop-tree origin)
          md (:frontend-metadata loop-tables)
          fn-md (:frontend-metadata (vm/ast-side-tables
                                      fn-ast
                                      (vm/ast->semantic-bytecode fn-ast)
                                      origin))]
      (is (= [:loop] (vec (vals (safepoint/sites md origin (:root loop-tree)))))
          "the lowering's loop mark survives finalization into the same
           generic relation")
      (is (= [[:call "f" 0]]
             (vec (vals (safepoint/sites fn-md origin
                                         (:root (vm/ast->semantic-bytecode fn-ast))))))
          "and so does a named call mark")
      (is (seq (marked md)))
      (is (not-any? #(= :yang/site (nth % 3)) (marker-rows md))
          "no C1 row travels under the safepoint key")
      (is (not-any? #(= :yang/call-site (nth % 3))
                    (filter #(= :yang/site (nth % 3)) md))
          "and no safepoint row under the C1 key")
      (is (= #{:yang/call-site :yang/site}
             (into #{} (comp (map #(nth % 3)) (filter keyword?)) md))
          "one generic relation carries both yang keys: the ts frontend
           mints no table of its own")
      (is (true? (yang/validate-call-site-coverage! loop-tree md origin))
          "coverage validates over the relation that also carries the marks")
      (testing "the mark is node metadata and the marker is a plain node key"
        (is (some #(and (map? %) (= :loop (:yang/site (meta %)))) (nodes loop-ast))
            "the safepoint mark travels as node metadata")
        (is (not-any? #(and (map? %) (contains? % :yang/site)) (nodes loop-ast))
            "and never as a plain node key")
        (is (some #(and (map? %) (= 1 (:yang/call-site %))) (nodes loop-ast))
            "the C1 marker travels as a plain node key")
        (is (not-any? #(and (map? %) (= 1 (:yang/call-site (meta %))))
                      (nodes loop-ast))
            "and never as node metadata")))))


;; =============================================================================
;; Canonical invariance: provenance stays out of hashed content
;; =============================================================================

(deftest canonical-invariance-test
  (testing "marking changes no canonical row or root"
    (doseq [[label ast] (concat (source-trees) (bundled-trees))]
      (testing label
        (let [a (vm/ast->semantic-bytecode ast)
              b (vm/ast->semantic-bytecode (strip-call-sites ast))]
          (is (= (:root a) (:root b))
              "the UAST stays retrievable at one address, marked or not")
          (is (= (:rows a) (:rows b))
              "and every canonical row is byte-identical")))))
  (testing "so the executed instruction vector is identical too"
    (let [ast (lower/lower-packet var-x {:prelude :linked})]
      (is (= (:vector (sr-linearize/project ast))
             (:vector (sr-linearize/project (strip-call-sites ast))))
          "the vector, and with it its address, does not move")
      (is (= (:address (sr-linearize/project ast))
             (:address (sr-linearize/project (strip-call-sites ast)))))))
  (testing "the key reaches the side table and nowhere in the canonical batch"
    (doseq [[label ast] (cheap-trees)]
      (testing label
        (let [projected (encoder/project (encoder/source-envelope medium token [ast]))
              batch (pr-str (:yin/batch projected))
              positions (pr-str (:yin/source-positions projected))]
          (is (seq (marked-under (:yin/frontend-metadata projected) origin))
              "the relation carries the claim")
          (is (not (str/includes? batch "yang/call-site"))
              "no canonical row names the key: provenance stays out of
               hashed content, so the UAST remains retrievable")
          (is (not (str/includes? batch "call-site")))
          (is (not (str/includes? positions "yang/call-site"))
              "and it never rides the source-position relation either"))))))


;; =============================================================================
;; The encoder and the publisher take the claim, and refuse a broken one
;; =============================================================================

(deftest encoder-claim-test
  (testing "canonical-row production takes a complete claim and refuses a broken one"
    (let [ast (lower/lower-packet var-x {:prelude :linked})]
      (is (= 6 (count (encoder/program-batch ast)))
          "a claiming member adds the per-member verdict element")
      (is (= [true] (nth (encoder/program-batch ast) 5))
          "and this member's own verdict is a claim, so the expander
           finalizes its executed tree's table")
      (is (= :yang/call-site-incomplete-coverage
             (failure-rule
               #(encoder/program-batch
                  (update-in ast [:operands 0] dissoc :yang/call-site))))
          "dropping one marker refuses the batch instead of laundering it")
      (is (= :yang/call-site-unsupported-version
             (failure-rule #(encoder/program-batch (assoc ast :yang/call-site 2)))))
      (is (= 5 (count (encoder/program-batch (strip-call-sites ast))))
          "an unclaimed member keeps the five-element batch shape: the
           adoption is additive, and a pre-adoption artifact still loads"))))


(defn- check-publication-claim
  "One published linker module tree's own C1 claim: exactly what
   `yin.vm.linker.publish/publish-module!`'s `call-site-claim` computes
   before it takes the claimed lane instead of refusing
   `:yin.link.publish/incomplete-call-site-coverage`."
  [label ast]
  (testing label
    (is (= 1 (:yang/call-site ast))
        "the module tree's root is one wide application")
    (is (true? (yang/validate-ast-call-sites! ast origin)))
    (is (seq (marked-pairs (:metadata (project ast)))))))


(deftest publication-claim-test
  (testing "each published module tree's own claim is complete"
    (check-publication-claim "js" prelude/module-uast)
    (check-publication-claim "jssp" hooks/module-uast)))


;; =============================================================================
;; Idempotence: a host composing a finalized unit cannot double-claim
;; =============================================================================

(defn- check-idempotent
  [label ast]
  (testing label
    (is (= ast (u/annotate-call-sites ast))
        "finalizing twice answers the same tree")
    (is (= (marked-pairs (:metadata (project ast)))
           (marked-pairs (:metadata (project (u/annotate-call-sites ast)))))
        "and a second walk moves no coordinate")))


(deftest idempotence-test
  (doseq [[label ast] (concat (cheap-trees) (bundled-trees))]
    (check-idempotent label ast)))


;; =============================================================================
;; The cross-host equality gate's local half
;; =============================================================================

(deftest cross-host-equality-test
  (testing "the coordinates are a pure function of the canonical tree, so
            two lowerings of one packet agree"
    (doseq [[label f] [["lower-module-body" #(lower/lower-module-body var-x)]
                       ["lower-unit" #(lower/lower-unit var-x)]
                       ["lower-packet, linked"
                        #(lower/lower-packet var-x {:prelude :linked})]
                       ["jssp module-uast" (constantly hooks/module-uast)]]]
      (testing label
        (let [a (project (f))
              b (project (f))]
          (is (= (:root (:tree a)) (:root (:tree b)))
              "one canonical root")
          (is (= (marked-pairs (:metadata a)) (marked-pairs (:metadata b)))
              "one coordinate set")
          (is (= (marked-pairs (:metadata a)) (occurrence-pairs (:tree a)))
              "and it is the tree's own occurrence relation, so a lane that
               recomputes it from canonical rows alone agrees")))))
  (testing "an independently finalized LITERAL tree answers the same
            coordinates: the paths are literal source data here, so a lane
            that computed different structural paths would fail this test"
    (let [body (u/then (u/then (u/app (u/v 'cell/set!)
                                      (u/v 'x)
                                      (u/lit (cbor/float64 1.0)))
                               (u/lit :js/undefined))
                       (u/lit :js/undefined))
          golden (u/annotate-call-sites
                   (u/app (u/lam ['x] body)
                          (u/app (u/v 'cell/new) (u/lit :js/undefined))))
          a (project (lower/lower-module-body var-x))
          b (project golden)]
      (is (= (:root (:tree a)) (:root (:tree b)))
          "one canonical root: the marking is not in the hashed content")
      (is (= (marked-pairs (:metadata a)) (marked-pairs (:metadata b)))
          "one coordinate set, derived on either side of the comparison")))
  (testing "the root node's kind decides whether [] is a coordinate"
    (let [body (project (lower/lower-module-body var-x))
          unit (project (lower/lower-unit var-x))]
      (is (= :application (:type (lower/lower-module-body var-x))))
      (is (contains? (marked-pairs (:metadata body)) [(:root (:tree body)) []])
          "lower-module-body's root is the cell-binding application, so the
           empty path is one of its coordinates")
      (is (= :lambda (:type (lower/lower-unit var-x))))
      (is (not (contains? (marked-pairs (:metadata unit))
                          [(:root (:tree unit)) []]))
          "lower-unit's root is a lambda, which is not an occurrence: the
           coordinate set follows the row tags, never the tree's size")))
  (testing "every composed production tree claims its own root"
    (doseq [[label ast] [["lower-packet, linked"
                          (lower/lower-packet var-x {:prelude :linked})]
                         ["lower-packet, bundled" (lower/lower-packet var-x)]
                         ["lower-units, linked"
                          (lower/lower-units [var-x] {:prelude :linked})]
                         ["js module-uast" prelude/module-uast]
                         ["jssp module-uast" hooks/module-uast]]]
      (testing label
        (let [{:keys [tree metadata]} (project ast)]
          (is (= :application (:type ast)))
          (is (contains? (marked-pairs metadata) [(:root tree) []])
              "the composed root is itself an application, and is claimed"))))))


;; =============================================================================
;; The export audit (the python F2 pin's shape, itself the ts twin's mirror)
;; =============================================================================

(deftest export-audit-pin-test
  (testing "no C1 or exception-track provenance surface is guest-visible"
    (let [exposed? (fn [x]
                     (some #(str/includes? (str x) %)
                           ["call-site" "callsite" "call_site"
                            "dsg__" "provenance"]))]
      (doseq [[label exports runtime-keys]
              [["js" prelude/module-exports prelude/runtime-keys]
               ["jssp" hooks/module-exports hooks/runtime-keys]]]
        (testing label
          (is (not-any? exposed? exports)
              "no export names the occurrence contract or the runtime doors")
          (is (not-any? exposed? runtime-keys)
              "no runtime key the task allocates names either")))))
  (testing "no js definition reaches the exception track's reserved doors,
            and none carries the compile-time C1 key"
    (let [private-names '#{cell/dsg__new-provenance
                           cell/dsg__provenance
                           dsg__condition-exception}
          door-users (into #{}
                           (keep (fn [[k form]]
                                   (when (some private-names
                                               (tree-seq coll? seq form))
                                     k)))
                           prelude/definitions)
          c1-users (into #{}
                         (keep (fn [[k form]]
                                 (when (some #{:yang/call-site}
                                             (tree-seq coll? seq form))
                                   k)))
                         prelude/definitions)]
      (is (= #{} door-users)
          "a DIFFERENT key family: the js prelude authenticates a retained
           condition through its own sealed companion cell, never the VM's
           provenance doors, so nothing here re-mints or reuses them")
      (is (= #{} c1-users)
          "C1 is compile-time frontend metadata on the lowered tree, never
           a guest definition the runtime can read")))
  (testing "the audited surface is the whole guest-visible surface, not a
            sample of it"
    (is (= (set prelude/module-exports)
           (into #{}
                 (keep (fn [[k _]]
                         (when (= "js" (namespace k)) (prelude/strip k))))
                 prelude/definitions))
        "the js exports are exactly its js-namespaced definitions; the
         internal js.rt/* store keys are not exported")
    (is (= (set hooks/module-exports)
           (set (map (comp hooks/strip first) hooks/function-definitions)))
        "and jssp's are exactly its definition list, mirroring
         linked-prelude-test's own pin")))
