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


(defn- project-under
  "`project` under an explicit batch token: the same medium and the same
   member index, a DIFFERENT admission. `distinct-admission-token-test` uses
   it to model two admissions of one packet, which is the only way to
   exercise design `:139`'s fresh-admission-identity requirement from
   inside a frontend suite."
  [ast batch-token]
  (let [projected (encoder/project
                    (encoder/source-envelope medium batch-token [ast]))]
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
  "The `[origin root-address path]` of the C1 rows of `metadata` claimed
   under `origin` — the ORIGIN-SCOPED marker set. Filtering on the row's
   own origin coordinate is what makes the query meaningful: a relation
   admitted under a DIFFERENT origin answers empty here, which is how
   `stage-envelope-test` proves the frontend stamps no origin of its own.
   (The origin-free projection a consumer derives from canonical rows alone
   is `marked-pairs`, below.)"
  [metadata origin]
  (into #{}
        (comp (filter #(and (= :yang/call-site (nth % 3))
                            (= origin (nth % 0))))
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


(defn- row-origins
  "The distinct occurrence origins a C1 marker relation carries."
  [metadata]
  (into #{} (map first) (marker-rows metadata)))


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
              "no C1 row of the envelope carries THIS file's origin: the
               frontend finalizes the tree and the composition admits it, so
               the frontend stamps no origin of its own")
          (is (= #{admission} (into #{} (map first) (marker-rows metadata)))
              "and every C1 row carries exactly the stage's admission
               origin — one origin, supplied by the composition, per the
               design's streaming-stage inventory row: the stage supplies
               medium plus packet unit as batch token"))))))


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

(def ^:private guest-surface-deny-strings
  "Substrings no guest-visible name may carry: the occurrence contract's own
   key, and the exception track's reserved-door family."
  ["call-site" "callsite" "call_site" "dsg__" "provenance"])


(defn- exposed-name?
  "True when guest-visible name `x` carries a deny-listed substring."
  [x]
  (some #(str/includes? (str x) %) guest-surface-deny-strings))


(def ^:private exception-track-doors
  "The exception track's reserved runtime doors and its private condition
   constructor — a DIFFERENT key family from `:yang/call-site` (landed
   `8c10c5b7`). Neither the `js` nor the `jssp` prelude may reach them: the
   js runtime authenticates a retained condition through its own sealed
   companion cell (`prelude.cljc:1653-1714`), never a VM door."
  '#{cell/dsg__new-provenance
     cell/dsg__provenance
     dsg__condition-exception})


(defn- audit-definition-users
  "The keys of `definitions` (`[key form]` pairs) whose form TREE references
   any of `names`. A closed set over trees, not a deny-list over names, so a
   RENAMED wrapper is caught too — which is what makes the python F2 shape
   strictly stronger than the ts twin it mirrored."
  [definitions names]
  (into #{}
        (keep (fn [[k form]]
                (when (some names (tree-seq coll? seq form))
                  k)))
        definitions))


(defn- audit-guest-c1-keys
  "The keys of `definitions` whose form tree carries the compile-time C1
   key. Always empty: C1 is frontend metadata on the lowered tree, never a
   guest-readable definition, so a runtime key that answered for it would be
   a second, parallel mechanism."
  [definitions]
  (audit-definition-users definitions #{:yang/call-site}))


(def ^:private renamed-hook-wrapper-definitions
  "The NEGATIVE fixture for the closed-set audit: two `js.sp` definitions
   whose OWN NAMES are neutral — no `dsg__`, no `provenance` — but whose
   bodies reach the exception track's reserved doors. This is the shape a
   name-based deny-list passes and a tree-based closed set catches."
  '[[js.sp/condition-slot (fn [c] (cell/dsg__new-provenance c))]
    [js.sp/read-slot (fn [s] (cell/dsg__provenance s))]])


(deftest export-audit-pin-test
  (testing "no C1 or exception-track provenance surface is guest-visible"
    (doseq [[label exports runtime-keys]
            [["js" prelude/module-exports prelude/runtime-keys]
             ["jssp" hooks/module-exports hooks/runtime-keys]]]
      (testing label
        (is (not-any? exposed-name? exports)
            "no export names the occurrence contract or the runtime doors")
        (is (not-any? exposed-name? runtime-keys)
            "no runtime key the task allocates names either"))))
  (testing "NEITHER prelude reaches the exception track's reserved doors, and
            neither carries the compile-time C1 key"
    (doseq [[label definitions] [["js" prelude/definitions]
                                 ["jssp" hooks/function-definitions]]]
      (testing label
        (is (= #{} (audit-definition-users definitions exception-track-doors))
            "a DIFFERENT key family: nothing here re-mints or reuses the VM's
             provenance doors, so no neutrally named wrapper reaches them")
        (is (= #{} (audit-guest-c1-keys definitions))
            "C1 is compile-time frontend metadata on the lowered tree, never
             a guest definition the runtime can read"))))
  (testing "the closed-set audit is not vacuous: a RENAMED wrapper is caught"
    (is (= '#{js.sp/condition-slot js.sp/read-slot}
           (audit-definition-users renamed-hook-wrapper-definitions
                                   exception-track-doors))
        "both neutral-named wrappers reaching a reserved door are named, so
         the empty sets above are a real audit, not an unexercised predicate")
    (is (= #{} (audit-guest-c1-keys renamed-hook-wrapper-definitions))
        "the fixture reaches the doors and not the C1 key, so the two audits
         stay independent of each other")
    (testing "the name-based deny-list leg alone would have MISSED it"
      (is (not-any? exposed-name?
                    (map first renamed-hook-wrapper-definitions))
          "neither wrapper's own name carries a deny-listed substring — this
           is exactly why the closed-set leg scans form trees")))
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


;; =============================================================================
;; A reviewed literal coordinate pin, and the distinct-admission-token
;; negative fixture (design §5.2 :272, §3.2 :139)
;; =============================================================================

(def ^:private reviewed-var-x-paths
  "The REVIEWED literal coordinate paths of `(lower/lower-module-body var-x)`
   — `var x = 1;`, the smallest fixture in this suite. Hand-derived from the
   row grammar (`yin/vm.cljc:1017-1038`) and the design's path rule (`:39`: a
   scalar child slot step is a row position; a child in a `nodes` slot is
   `[slot-position member-index]`; the root is `[]`), and NOT read back from
   `vm/application-occurrences` — so the expected side of
   `coordinate-pin-test` is independent of the extraction and enumeration
   machinery every other test here uses, in the style of
   `yang.clojure-callsite-test:119`.

   The tree is the golden at `lower_portable_test.cljc:206-217`: one
   cell-binding application over a `cell/new` operand, whose lambda body is
   two nested sequencing applications ending in `cell/set!`. Five
   applications, therefore five paths:

     []                     the cell-binding application, i.e. the root
     [[3 0]]                its operand 0, `(cell/new :js/undefined)`
     [2 3]                  the binding lambda's body: the outer `then`
     [2 3 [3 0]]            that `then`'s operand 0: the inner `then`
     [2 3 [3 0] [3 0]]      the inner `then`'s operand 0: `(cell/set! …)`

   The lambdas at `[2]`, `[2 3 2]` and `[2 3 [3 0] 2]`, and every literal,
   are not occurrences and must not appear."
  #{[]
    [[3 0]]
    [2 3]
    [2 3 [3 0]]
    [2 3 [3 0] [3 0]]})


(def ^:private reviewed-var-x-root
  "The REVIEWED canonical root address of `(lower/lower-module-body var-x)`.

   A root is `segment/blake3-<hex>` over the root row and cannot be derived
   by hand, so this slot is nil until the first green run freezes it — see
   findings FIX-ROUND-2 item (1). While it is nil, `coordinate-pin-test` pins
   the root RELATIONALLY instead: well-formed, identical with the markers
   stripped, and identical after a second finalization walk. All three are
   host-independent and need no hash. Fill this in and the pin additionally
   becomes a frozen absolute golden."
  nil)


(deftest coordinate-pin-test
  (testing "the reviewed literal coordinate set of the smallest fixture"
    (let [{:keys [tree metadata]} (project (lower/lower-module-body var-x))
          expected (into #{}
                         (map (fn [path] [origin (:root tree) path]))
                         reviewed-var-x-paths)]
      (is (= 5 (count reviewed-var-x-paths))
          "five applications in `var x = 1;`'s module body")
      (is (= expected (marked metadata))
          "the hand-derived paths ARE the whole marker set: the expected side
           consults no occurrence-enumeration machinery")
      (is (= expected (eligible tree))
          "and they are exactly the eligible set the canonical rows imply, so
           the hand derivation and the occurrence relation agree")
      (is (= reviewed-var-x-paths
             (into #{} (map #(nth % 2)) (marker-rows metadata)))
          "the same five paths, origin-free")
      (is (true? (yang/validate-call-site-coverage! tree metadata origin)))))
  (testing "the reviewed root pin"
    (let [produced (lower/lower-module-body var-x)
          root (:root (:tree (project produced)))]
      (if-let [frozen reviewed-var-x-root]
        (is (= frozen root)
            "the frozen absolute canonical root address")
        (testing "not yet frozen: the three host-independent root pins that
                  stand in for it"
          (is (and (keyword? root) (= "segment" (namespace root))
                   (str/starts-with? (name root) "blake3-"))
              "the root is a well-formed canonical segment address")
          (is (= root (:root (vm/ast->semantic-bytecode
                               (strip-call-sites produced))))
              "identical with the markers stripped, so the address cannot
               depend on C1 — provenance stays out of hashed content")
          (is (= root (:root (:tree (project (u/annotate-call-sites produced)))))
              "and identical after a second finalization walk"))))))


(deftest distinct-admission-token-test
  (testing "identical text admitted twice under DISTINCT supplied tokens
            demands different origins (design §5.2 :272's negative fixture;
            §3.2 :139 — C1 requires fresh admission identity)"
    (let [token-a [:hand 0]
          token-b [:hand 1]
          origin-a (vm/source-origin medium token-a 0)
          origin-b (vm/source-origin medium token-b 0)
          a (project-under (lower/lower-module-body var-x) token-a)
          b (project-under (lower/lower-module-body var-x) token-b)]
      (is (= token-a (:yang.cst/unit var-x))
          "both admissions carry the SAME packet identity, which is exactly
           why the supplied token and not the packet must carry admission
           freshness — design :139: packet identity alone is insufficient
           if reused for distinct admissions")
      (is (not= origin-a origin-b)
          "the two admissions are distinct occurrence origins")
      (is (= #{origin-a} (row-origins (:metadata a))))
      (is (= #{origin-b} (row-origins (:metadata b))))
      (is (not= (row-origins (:metadata a)) (row-origins (:metadata b)))
          "so the two coordinate SETS differ")
      (is (= (:root (:tree a)) (:root (:tree b)))
          "one content address for both: an occurrence is (content address,
           origin), never content alone")
      (is (= (marked-pairs (:metadata a)) (marked-pairs (:metadata b)))
          "and one origin-free path set — cross-host equality is REPLAY
           equality, and must not turn occurrences into content identities")
      (is (not= (marked-under (:metadata a) origin-a)
                (marked-under (:metadata b) origin-b))
          "yet the full coordinates differ, because their origins do")
      (is (true? (yang/validate-call-site-coverage!
                   (:tree a) (:metadata a) origin-a)))
      (is (true? (yang/validate-call-site-coverage!
                   (:tree b) (:metadata b) origin-b)))))
  (testing "a RETRY keeps its token: re-admitting identical text under the
            SAME token reproduces the SAME origin, so a retry fabricates no
            fresh admission identity (design :139)"
    (let [retry-token [:hand 0]
          retry-origin (vm/source-origin medium retry-token 0)
          first-admission (project-under (lower/lower-module-body var-x)
                                         retry-token)
          retry (project-under (lower/lower-module-body var-x) retry-token)]
      (is (= #{retry-origin} (row-origins (:metadata first-admission))))
      (is (= #{retry-origin} (row-origins (:metadata retry)))
          "the retry keeps the token, so it keeps the origin")
      (is (= (marked-under (:metadata first-admission) retry-origin)
             (marked-under (:metadata retry) retry-origin))
          "and reproduces exactly the same coordinate set"))))


;; =============================================================================
;; Obligation TS-SP-C1 — the ts derived envelope's PARTIAL correspondence,
;; documented. This test PASSES by pinning the limitation and naming the
;; obligation; it is deliberately not a red-light on the suite.
;; =============================================================================

(def ^:private derived-medium
  "The medium a ts host derives under: `safepoint/derive-envelope`'s third
   argument. No ts caller supplies `:injected-origin`."
  :yang.ts/derived)


(defn- derive
  "The safepoint-derived envelope of `ast` under `profile`, admitted on this
   test's medium and derived onto `derived-medium` — `derive-envelope` at its
   3-arity, exactly as every ts caller invokes it: `safepoint/transform`
   (`yang/safepoint.cljc:455`), `e2e-test`'s `derive-program` (`:71-86`),
   `safepoint-test`'s `derive*` (`:132-136`), `linked-prelude-test`'s
   `derived-ast` (`:249-262`)."
  [ast profile]
  (let [projected (encoder/project (encoder/source-envelope medium token [ast]))]
    (:envelope (safepoint/derive-envelope projected profile derived-medium))))


(deftest ts-sp-c1-obligation-test
  (testing "TS-SP-C1 part 1 — a ts derived envelope carries PARTIAL
            correspondence by named obligation, not by oversight: surviving
            occurrences keep their own origins, injected hook applications
            are recorded WITHOUT one, because no ts caller supplies
            :injected-origin and inventing an origin is forbidden"
    (let [derived (derive (lower/lower-packet while-x {:prelude :linked})
                          hooks/profile)
          rows (marker-rows (:yin/frontend-metadata derived))
          tree (nth (:yin/batch derived) (:yin/root derived))
          correspondence (:yang.safepoint/correspondence derived)
          entries (:entries correspondence)
          injected (filterv #(= :safepoint/injected (:kind %)) entries)
          surviving (filterv #(= :surviving (:kind %)) entries)
          eligible-paths (into #{} (map second)
                               (vm/application-occurrences tree))
          claimed-paths (into #{} (map #(nth % 2) rows))]
      (is (some? correspondence)
          "the derived envelope carries an explicit correspondence record")
      (is (seq surviving) "the surviving occurrences are mapped")
      (is (seq rows) "and keep their C1 rows")
      (is (seq injected)
          "the injected hook applications are named in the correspondence")
      (is (not-any? :origin injected)
          "TS-SP-C1: each injected occurrence is recorded WITHOUT an origin —
           partial correspondence, deliberately, never a fabricated claim")
      (is (every? #(some? (get-in % [:input 0])) surviving)
          "while every surviving entry keeps its own incoming origin, inside
           its :input coordinate")
      (is (= (into #{} (map #(nth % 0) rows))
             (into #{} (map #(get-in % [:input 0]) surviving)))
          "and each claimed row carries exactly one surviving entry's origin")
      (is (every? eligible-paths claimed-paths)
          "every claimed path is a real application of the derived tree")
      (is (not= claimed-paths eligible-paths)
          "TS-SP-C1: but the claim is a STRICT subset — partial
           correspondence, NOT complete derived-tree adoption")))
  (testing "TS-SP-C1 part 2 — re-projecting that derived envelope DROPS its
            C1 rows silently, so derived-envelope re-projection stays OUTSIDE
            the accepted C1 routes until the shared encoder gap is resolved.
            Pinned so the behaviour is documented, and so a fix in the shared
            encoder flips this test rather than passing quietly."
    (let [derived (derive (lower/lower-packet while-x {:prelude :linked})
                          hooks/profile)
          reprojected (encoder/project derived)]
      (is (seq (marker-rows (:yin/frontend-metadata derived)))
          "the derived envelope carries C1 rows on entry")
      (is (= [] (:yin/frontend-metadata reprojected))
          "TS-SP-C1: they are GONE after re-projection, with no refusal and
           no diagnosis. The already-projected member arm at
           encoder.cljc:115-125 reads :frontend-metadata off the MEMBER,
           where derive-envelope never puts it — the rows ride the ENVELOPE —
           so check-projected-claim! sees no claim to check; encoder.cljc
           :156-160 then overwrites the envelope-level relation with the
           empty concatenation over members. That gap is in the SHARED
           encoder and is owned cross-track, out of scope for this round")
      (is (= (:yin/batch derived) (:yin/batch reprojected))
          "the canonical rows are untouched: the drop is provenance-only")
      (is (= (:yang.safepoint/correspondence derived)
             (:yang.safepoint/correspondence reprojected))
          "and the correspondence record survives, because project-envelope
           assocs onto the incoming envelope — so the evidence of what was
           dropped is still retrievable"))))
