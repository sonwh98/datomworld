(ns yang.clojure-collection-order-test
  "Constructed collections v1 (architect ruling 1795017757000, RULING 1-4):
   the canonical operand ordering of dynamically constructed map and set
   literals at yang.clojure's collection emitter, the evaluation semantics
   that ordering makes executable, and the manifest/legacy boundaries of the
   c1-2 revision.

   Every order expectation here comes from builders independent of the
   emitter's own sort: children project through
   yin.vm/ast->semantic-bytecode and order through dao.jing/canonical-compare
   (RFC 8949 canonical bytes, unsigned bytewise, proper prefix first), the
   same comparator the ruling names -- never through the helper under test."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.stream.base64 :as base64]
            [yang.clojure :as yang]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.data :as data]
            [yin.vm.encoder :as encoder]
            [yin.vm.macro :as macro]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.semantic.derive :as derive]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Independent oracle builders (RULING 1's comparator, not the emitter's sort)
;; =============================================================================

(defn- collection-applications
  "Every constructor application of compiled `ast` -- an assoc over the {}
   seed or a conj over the []/#{} seed -- in outermost-first walk order. A
   program under test contains either only emitter output or only the
   explicit constructor calls a control names, never both."
  [ast]
  (let [acc (atom [])]
    (walk/postwalk
      (fn [n]
        (when (and (map? n)
                   (= :application (:type n))
                   (= :variable (get-in n [:operator :type]))
                   (= :literal (get-in n [:operands 0 :type]))
                   (contains? #{[(quote assoc) {}] [(quote conj) []] [(quote conj) #{}]}
                              [(get-in n [:operator :name])
                               (get-in n [:operands 0 :value])]))
          (swap! acc conj n))
        n)
      ast)
    @acc))


(defn- child-roots
  "The canonical semantic-bytecode root of each operand of `node`,
   projected independently of the emitter."
  [node]
  (mapv (fn [o] (:root (vm/ast->semantic-bytecode o))) (:operands node)))


(defn- map-constructors
  [ast]
  (into [] (filter #(= (quote assoc) (get-in % [:operator :name])))
        (collection-applications ast)))


(def ^:private empty-map-seed-root
  "The content address of the {} seed literal: host- and revision-stable."
  :segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10)


(defn- pair-keys
  "The RULING 1 sort keys of a map constructor's pairs: `[key-root
   value-root]` per source pair, in emitted order."
  [node]
  (mapv vec (partition 2 (rest (child-roots node)))))


(defn- element-keys
  "The RULING 1 sort keys of a set constructor's elements (one root each),
   in emitted order."
  [node]
  (mapv vector (rest (child-roots node))))


(defn- strictly-ascending?
  "True when `ks` sorts strictly ascending by jing/canonical-compare: every
   adjacent pair compares negative. The strict form deliberately does NOT
   certify a tie (equal adjacent keys are equal executable content and a
   legal emitted order, but not a strictly ascending one) -- blocks that
   construct a tie assert it directly or compare against the sequence's
   own non-descending sort instead."
  [ks]
  (every? (fn [[a b]] (neg? (jing/canonical-compare a b)))
          (partition 2 1 ks)))


(defn- key-literals
  "The literal values at the key positions of a map constructor (every
   second operand after the seed), for reading an emitted order."
  [node]
  (mapv (fn [o] (when (= :literal (:type o)) (:value o)))
        (take-nth 2 (rest (:operands node)))))


(defn- project-batch
  "One compiled ast through the production encoder envelope under `token`."
  ([ast] (project-batch ast "order-a"))
  ([ast token]
   (let [p (encoder/project (encoder/source-envelope :cc4/replay token [ast]))]
     {:tree (first (:yin/batch p))
      :rows (:yin/frontend-metadata p)})))


(defn- digest-snapshot
  "The equality snapshot of one projected batch: root, the canonical-sorted
   C1 marker relation, the sorted occurrence-key vector, and all three
   instruction formats' call-provenance -- the cohort's model, with nothing
   normalized away."
  [{:keys [tree rows]}]
  (let [c1 (vec (sort jing/canonical-compare
                      (filter #(= :yang/call-site (nth % 3)) rows)))
        keys (vec (sort jing/canonical-compare
                        (into [] (comp (filter #(= :yang/call-site (nth % 3)))
                                       (map #(vec (take 3 %)))) rows)))
        sem (linearize/project-rows tree nil rows)
        stack (derive/stack-image (:vector sem)
                                  {:segment-address :order/stack
                                   :call-provenance (:call-provenance sem)})
        register (derive/register-image (:vector sem)
                                        {:segment-address :order/register
                                         :call-provenance (:call-provenance sem)})]
    {:root (:root tree)
     :applications (count keys)
     :keys-digest (jing/content-hash keys)
     :c1-digest (jing/content-hash c1)
     :instruction-provenance-digest
     (jing/content-hash [(:call-provenance sem)
                         (:call-provenance stack)
                         (:call-provenance register)])}))


(defn- compile-snapshot
  "Compile `form`, project it, digest it."
  [form]
  (digest-snapshot (project-batch (yang/compile form))))


;; =============================================================================
;; Execution routes (RULING 2/3: walker, semantic, stack, register)
;; =============================================================================

(def ^:private registry
  (-> (module/empty-registry)
      module/register-cell-module
      data/register-data-module))


(def ^:private base-opts
  {:make-stream tu/make-stream
   :capability-secret tu/secret
   :primitives vm/primitives
   :modules registry})


(def ^:private load-semantic-ast
  (vm/fresh-code-loader semantic/load-ast vm/ast-contract))


(def ^:private lanes
  "The four execution routes the behavior tests run."
  [:ast-walker :semantic :stack :register])


(defn- run-lane
  "Run compiled `ast` on one execution route."
  [lane ast env]
  ;; the walker/semantic machines take :env; the debruijn machines take
  ;; :free-env for free names -- both spell the same test-supplied bindings
  (let [opts (assoc base-opts :env env :free-env env)]
    (case lane
      :ast-walker (vm/eval (tu/create-vm opts) ast)
      :semantic (vm/run (load-semantic-ast (semantic/create-vm opts)
                                           (vm/ast->datoms ast)))
      :stack (vm/run (dvm/create-vm
                       (:image (derive/stack-image
                                 (:vector (linearize/project ast))))
                       (assoc opts :contract vm/stack-contract)))
      :register (vm/run (rvm/create-vm
                          (derive/register-image
                            (:vector (linearize/project ast)))
                          (assoc opts :contract vm/register-contract))))))


(defn- run-on
  "Run compiled `ast` on every execution route with `env`, returning
   lane -> value."
  ([ast] (run-on ast {}))
  ([ast env]
   (into {} (map (fn [lane] [lane (vm/value (run-lane lane ast env))]))
         lanes)))


(defn- run-form
  "Compile and run `form` on every route with `env`."
  ([form] (run-form form {}))
  ([form env] (run-on (yang/compile form) env)))


(defn- trace-of
  "The observable evaluation of `form` on every route: the lane's value
   (or its raised ex-data) together with the ordered log the env-bound
   `tap` host function recorded during THAT lane's run. `tap` echoes its
   argument, so a log is exactly the order that lane consumed the operands
   in; a lane whose operand raises still reports the events that happened
   before the raise."
  [form env]
  (let [ast (yang/compile form)
        log (atom [])
        env (assoc env (quote tap) (fn [x] (swap! log conj x) x))]
    (into {} (map (fn [lane]
                    (reset! log [])
                    (let [result (try
                                   {:value (vm/value (run-lane lane ast env))}
                                   (catch #?(:clj Exception, :cljs :default, :cljd Object) e
                                     {:raised (ex-data e)}))]
                      [lane (assoc result :log @log)])))
          lanes)))


(defn- permutations-of
  "Every ordering of the items, the plain recursive enumeration."
  [items]
  (if (empty? items)
    [[]]
    (mapcat (fn [i]
              (map (fn [rest-perm] (into [i] rest-perm))
                   (permutations-of (vec (remove #{i} items)))))
            items)))


(defn- all-permutations
  "Every ordering of (range n)."
  [n]
  (permutations-of (vec (range n))))


(defn- rotate
  "The kth rotation of `v`."
  [k v]
  (let [k (mod k (count v))]
    (into (subvec v k) (subvec v 0 k))))


(defn- lcg
  "The recorded-seed Lehmer generator x' = 48271*x mod (2^31-2). Every
   product stays below 2^53, so every host computes the identical sequence
   in its double arithmetic (a long-math generator would not be portable)."
  [seed n]
  (loop [out [] x seed i 0]
    (if (= i n)
      out
      (let [x' (mod (* 48271 x) 2147483646)]
        (recur (conj out x') x' (inc i))))))


(defn- lcg-shuffle
  "Fisher-Yates over (range n) driven by the recorded-seed Lehmer
   generator."
  [seed n]
  (if (< n 2)
    (vec (range n))
    (let [states (lcg seed (dec n))]
      (loop [i (dec n), s states, acc (vec (range n))]
        (if (zero? i)
          acc
          (let [j (mod (first s) (inc i))]
            (recur (dec i) (rest s)
                   (assoc acc i (nth acc j) j (nth acc i)))))))))


(defn- recorded-permutations
  "The recorded permutation set for sizes beyond full enumeration: forward,
   reverse, four rotations and two seeded shuffles."
  [n seed]
  [(vec (range n))
   (vec (reverse (range n)))
   (rotate 1 (vec (range n)))
   (rotate 2 (vec (range n)))
   (rotate 3 (vec (range n)))
   (lcg-shuffle seed n)
   (lcg-shuffle (inc seed) n)])


(defn- permute
  "The entries of `v` reordered by the index vector `p`."
  [v p]
  (into [] (map v) p))


(defn- insertion-map
  "One host map value over the flat `k v` seq in insertion order, through
   the array-map representation where the host has one (JVM, Node) and the
   hash-map representation otherwise (ClojureDart has no array-map; its
   ordinary map is the available representation)."
  [flat]
  #?(:clj (apply array-map flat)
     :cljs (apply array-map flat)
     :cljd (apply hash-map flat)
     :default (apply array-map flat)))


(defn- build-map
  "The logical entries as one host map value in entry `order`, through the
   array-map or the hash-map representation."
  [rep entries]
  (let [flat (into [] (mapcat identity) entries)]
    (if (= :array-map rep)
      (insertion-map flat)
      (apply hash-map flat))))


(defn- build-set
  "The logical elements as one host set value in element `order`, through
   the set-over-vector or the hash-set representation."
  [rep elements]
  (if (= :vec-set rep)
    (set (vec elements))
    (apply hash-set elements)))


(defn- sized-map-entries
  "n recorded entries with constant keys :k0.. and dynamic values."
  [n]
  (into [] (map (fn [i] [(keyword (str "k" i)) (list '+ 'n i)])) (range n)))


(defn- sized-set-elements
  "n recorded dynamic elements."
  [n]
  (into [] (map (fn [i] (list '+ 'n i))) (range n)))


;; =============================================================================
;; RULING 3 test 2: map representation/permutation property
;; =============================================================================

(def ^:private quoted-pair-key
  "A quoted collection key, one plain and one carrying semantic (non
   position) metadata inside the literal payload."
  (list 'quote [1 2]))


(def ^:private quoted-meta-key
  ;; a different value than quoted-pair-key: host = ignores metadata, so
  ;; two metadata-variants of one value cannot coexist as map keys (the
  ;; host map collapses them); metadata-as-content is asserted separately
  ;; in the oracle test
  (list 'quote (with-meta [3 4] {:sem true})))


(def ^:private map-corpus
  "The recorded corpus of distinct source-entry pairs. Every case holds at
   least one dynamic expression, so each reaches the emitter as a
   constructed collection."
  [[:constant-keys-dynamic-values
    [[:a (list '+ 'n 0)] [:b (list '+ 'n 1)]]]
   [:dynamic-keys
    [[(list 'first 'xs) :v1] [(list 'first (list 'rest 'xs)) :v2]]]
   [:heterogeneous-keys
    [[:kw (list '+ 'n 0)] ["str" 1] [3 1.5] ['sym 2]
     [quoted-pair-key 3] [quoted-meta-key 4] [true 5] [nil 6]]]
   [:nested-collections
    [[:inner-map {:inner (list '+ 'n 1)}]
     [:inner-set #{(list '+ 'n 2) 3}]
     [:inner-vec [(list '+ 'n 3)]]
     [:deep {:a #{[(list '+ 'n 4)]}}]]]
   [:floats
    [[1.5 (list '+ 'n 1.5)] [2.25 -0.5] [0.0 2.5]]]
   [:duplicate-subtrees
    [[:a (list 'first 'xs)] [:b (list 'first 'xs)] [:c (list 'first 'xs)]]]])


(def ^:private map-corpus-sizes
  "Sizes crossing the array-map/hash-map representation thresholds; each
   gets the full recorded permutation set under both representations."
  [8 9 16 32])


(deftest map-permutation-invariance-test
  (testing "sizes 0-4: every insertion permutation, both representations"
    (doseq [n (range 5)
            :let [entries (sized-map-entries n)]]
      (testing (str "size " n)
        (let [variants (into []
                             (mapcat (fn [p]
                                       (for [rep [:array-map :hash-map]]
                                         (build-map rep (permute entries p)))))
                             (all-permutations n))
              snaps (mapv compile-snapshot variants)]
          (is (pos? (count variants)))
          (is (apply = snaps)
              (str n "-entry permutations diverge: "
                   (pr-str (distinct snaps))))
          (when (pos? n)
            (doseq [form variants
                    :let [ctors (map-constructors (yang/compile form))]]
              (is (= 1 (count ctors)))
              (is (every? #(strictly-ascending? (pair-keys %)) ctors))))))))
  (testing "recorded corpus: permutations of the recorded entries"
    (doseq [[label entries] map-corpus]
      (testing (str label)
        (let [variants (into []
                             (mapcat (fn [p]
                                       (for [rep [:array-map :hash-map]]
                                         (build-map rep (permute entries p)))))
                             (all-permutations (count entries)))
              snaps (mapv compile-snapshot variants)]
          (is (apply = snaps))
          (doseq [form variants
                  :let [ctors (map-constructors (yang/compile form))]]
            (is (every? #(strictly-ascending? (pair-keys %)) ctors)))))))
  (testing "sizes 8/9/16/32: recorded permutations cross the thresholds"
    (doseq [n map-corpus-sizes
            :let [entries (sized-map-entries n)]]
      (testing (str "size " n)
        (let [variants (into []
                             (mapcat (fn [p]
                                       (for [rep [:array-map :hash-map]]
                                         (build-map rep (permute entries p)))))
                             (recorded-permutations n 1795017757000))
              snaps (mapv compile-snapshot variants)]
          (is (apply = snaps))
          (doseq [form variants
                  :let [ctors (map-constructors (yang/compile form))]]
            (is (every? #(strictly-ascending? (pair-keys %)) ctors)))))))
  (testing "the constant fast path stays a literal"
    (is (= {:type :literal :value {:a 1 :b 2}}
           (yang/compile {:a 1 :b 2})))))


(def ^:private set-corpus
  "The recorded corpus of dynamic set element lists."
  [[:dynamic-elements
    [(list '+ 'n 0) (list '+ 'n 1) (list 'first 'xs)]]
   [:nested-dynamic-sets
    [#{(list '+ 'n 1) 2} (list 'first 'xs) 3]]
   [:colliding-value-expressions
    ;; two different expressions that evaluate to the same value
    [(list '- 'n 1) (list '- 1 'n) (list '+ 'n 0)]]
   [:heterogeneous-elements
    [:kw "str" 3 1.5 quoted-pair-key quoted-meta-key (list '+ 'n 2)]]])


(deftest set-permutation-invariance-test
  (testing "sizes 0-4: every element permutation, both representations"
    (doseq [n (range 5)
            :let [elements (sized-set-elements n)]]
      (testing (str "size " n)
        (let [variants (into []
                             (mapcat (fn [p]
                                       (for [rep [:vec-set :hash-set]]
                                         (build-set rep (permute elements p)))))
                             (all-permutations n))
              snaps (mapv compile-snapshot variants)]
          (is (pos? (count variants)))
          (is (apply = snaps))
          (when (pos? n)
            (doseq [form variants
                    :let [ctors (collection-applications (yang/compile form))]]
              (is (= 1 (count ctors)))
              (is (every? #(strictly-ascending? (element-keys %)) ctors))))))))
  (testing "recorded corpus"
    (doseq [[label elements] set-corpus]
      (testing (str label)
        (let [variants (into []
                             (mapcat (fn [p]
                                       (for [rep [:vec-set :hash-set]]
                                         (build-set rep (permute elements p)))))
                             (all-permutations (count elements)))
              snaps (mapv compile-snapshot variants)]
          (is (apply = snaps))
          (doseq [form variants
                  :let [ctors (collection-applications (yang/compile form))]]
            (is (every? #(strictly-ascending? (element-keys %)) ctors)))))))
  (testing "sizes 8/9/16/32: recorded permutations"
    (doseq [n map-corpus-sizes
            :let [elements (sized-set-elements n)]]
      (testing (str "size " n)
        (let [variants (into []
                             (mapcat (fn [p]
                                       (for [rep [:vec-set :hash-set]]
                                         (build-set rep (permute elements p)))))
                             (recorded-permutations n 1795017757001))
              snaps (mapv compile-snapshot variants)]
          (is (apply = snaps))
          (doseq [form variants
                  :let [ctors (collection-applications (yang/compile form))]]
            (is (every? #(strictly-ascending? (element-keys %)) ctors)))))))
  (testing "compiled element occurrences survive: expressions that evaluate
            to one value keep their compiled occurrences"
    (let [form #{(list '- 'n 1) (list '- 1 'n)}
          ctor (first (collection-applications (yang/compile form)))]
      (is (= 3 (count (:operands ctor))))
      (is (= 2 (count (element-keys ctor))))))
  (testing "the constant fast path stays a literal"
    (is (= {:type :literal :value #{1 2 3}}
           (yang/compile #{1 2 3})))))


(defn- root-of
  [ast]
  (:root (vm/ast->semantic-bytecode ast)))


(defn- independent-pair-keys
  "The expected pair keys of a source map `form`, built independently of the
   emitter: each entry's key and value compile standalone (empty
   environment, as the emitter's children do) and project to their roots."
  [form]
  ;; children compile as the emitter compiles them: tail? false, empty env
  (->> (map (fn [k v]
              (let [ck (yang/compile-form k false {})
                    cv (yang/compile-form v false {})]
                (vec [(root-of ck) (root-of cv)])))
            (keys form) (vals form))
       (sort jing/canonical-compare)
       vec))


(deftest order-pairing-multiplicity-oracle-test
  (testing "pairs stay intact, the seed stays first, the pair vector sorts"
    (doseq [form [{:a (list '+ 'n 1), :b (list '+ 'n 2)}
                  {(list 'first 'xs) :v, :k [1 (list '+ 'n 3)]}
                  {:x #{(list '+ 'n 4)}, :y {:in (list '+ 'n 5)}}]
            ;; the outermost constructor: the walk collects innermost first
            :let [ctor (last (map-constructors (yang/compile form)))
                  roots (child-roots ctor)
                  pairs (pair-keys ctor)]]
      (is (= empty-map-seed-root (first roots)))
      (is (odd? (count roots)))
      (is (= (+ 1 (* 2 (count form))) (count roots)))
      (is (strictly-ascending? pairs))
      (is (= (independent-pair-keys form)
             (vec (sort jing/canonical-compare pairs)))
          "the emitted pairs are exactly the independently compiled source
           pairs, none lost or mixed")))
  (testing "the value root breaks equal key roots"
    ;; two NaN keys are never = to each other (both survive the host map)
    ;; and the codec makes every NaN one quiet NaN, so both compile to one
    ;; key code: only the value root can order these pairs
    (let [form (assoc {} ##NaN :left ##NaN (list '+ 'n 1))
          ctor (last (map-constructors (yang/compile form)))
          pairs (pair-keys ctor)]
      (is (= 2 (count pairs)) (str "both NaN entries survive: " (count pairs)))
      (is (strictly-ascending? pairs))
      (is (= (first (first pairs)) (first (second pairs))))
      (is (= (mapv second pairs)
             (vec (sort jing/canonical-compare (mapv second pairs)))))))
  (testing "identical compiled pairs tie without occurrence loss"
    (let [form (assoc {} ##NaN (list '+ 'n 0) ##NaN (list '+ 'n 0))
          ctor (last (map-constructors (yang/compile form)))
          pairs (pair-keys ctor)]
      (is (= 2 (count pairs)))
      (is (= (first pairs) (second pairs)))
      (is (= 5 (count (:operands ctor))) "seed plus both pair occurrences")))
  (testing "identical compiled set elements tie without occurrence loss"
    ;; two NaN elements are host-unequal (a NaN is never = to itself) and
    ;; compile to one quiet-NaN row, so they are the whole-element tie.
    ;; Retention is per constructor, not a map-vs-set difference: on the
    ;; JVM, into over exactly the NaN pair keeps both (a persistent conj
    ;; chain collapses them under Java equals, and one transient session
    ;; that also mixes in a seq element loses one -- the r2 findings
    ;; record the probe), so the pair is built and finalized before the
    ;; dynamic element joins. That element is what keeps the set off the
    ;; constant fast path so it reaches the emitter at all. On Node and
    ;; Dart no constructor collapses the pair (= is === / Dart ==, and
    ;; NaN never equals itself there).
    (let [nan-key [(root-of (yang/compile-form ##NaN false {}))]
          form (conj (into #{} [##NaN ##NaN]) (list '+ 'n 0))
          ctor (first (collection-applications (yang/compile form)))
          keys (element-keys ctor)]
      (is (= 3 (count keys)) (str "both NaN occurrences survive: " (count keys)))
      (is (= 2 (count (filter #(= nan-key %) keys)))
          "the tied element root appears exactly twice, not deduplicated")
      (is (= 1 (count (distinct (remove #(= nan-key %) keys))))
          "the third, distinct occurrence is retained beside the tie")
      (is (= (vec (sort jing/canonical-compare keys)) (vec keys))
          "the element vector equals its own non-descending sort: the tied
           roots sit adjacent and nothing is reordered around them")
      (is (= 4 (count (:operands ctor))) "seed plus all three occurrences")))
  (testing "distinct dynamic set elements keep their compiled occurrences"
    (let [inner (list '+ 'n 0)
          form #{inner (list '+ 'n 1)}
          ctor (first (collection-applications (yang/compile form)))
          keys (element-keys ctor)]
      (is (= 2 (count keys)))
      (is (strictly-ascending? keys))
      (is (= 3 (count (:operands ctor))))))
  (testing "source-position and node metadata never choose the order"
    (let [plain {:a (list '+ 'n 1), :b (list '+ 'n 2)}
          positioned (with-meta plain {:line 3 :column 4 :end-line 3 :end-column 9})
          noted (with-meta plain {:note :source-form})
          value-meta {(with-meta (list '+ 'n 1) {:line 8}) :a
                      (list '+ 'n 2) :b}
          snap (fn [f] (compile-snapshot f))
          ctor-roots (fn [f] (child-roots (first (map-constructors (yang/compile f)))))]
      (is (= (snap plain) (snap positioned)))
      (is (= (snap plain) (snap noted)))
      (is (= (ctor-roots plain) (ctor-roots positioned)))
      (is (= (ctor-roots plain) (ctor-roots noted)))
      (is (strictly-ascending? (pair-keys (first (map-constructors (yang/compile value-meta))))))))
  (testing "semantic metadata in literal payloads stays content"
    (let [with-sem {(list 'quote (with-meta [1 2] {:sem true})) (list '+ 'n 1)
                    :plain 2}
          without {:plain 2}]
      (is (not= (root-of (yang/compile with-sem))
                (root-of (yang/compile without))))
      (is (strictly-ascending? (pair-keys (first (map-constructors (yang/compile with-sem)))))))))


;; The outcome lane the arity payload check runs on: the same light
;; convention clojure_test runs (default registry + stream/cell/data
;; modules + the portable adapter), portable across the four hosts.
(def ^:private outcome-modules
  (let [host (fn [name entries]
               (fn [registry]
                 (module/register-host-module
                   registry name
                   (into {} (map (fn [[op f _]] [op f])) entries)
                   (into {} (map (fn [[op _ p]] [op p])) entries))))
        portable [['nth
                   (yang/outcome-adapter data/refusal-message
                                         (get data/data-module 'nth))
                   (get data/data-profiles 'nth)]]]
    (-> (module/default-registry)
        module/register-stream-module
        module/register-cell-module
        (data/register-data-module {::data/max-items 1048576})
        ((host 'portable portable)))))


(def ^:private outcome-opts
  {:convention :arg-vector
   :outcomes true
   :native (set (keys vm/primitives))
   :adapters {'data/nth 'portable/nth}})


(defn- run-outcome
  "The raw outcome the last form of `forms` returns on the walker outcome
   lane."
  [forms]
  (-> (tu/make-observer-session (tu/create-vm {:modules outcome-modules}))
      (tu/queue-ast! (yang/compile-program forms true outcome-opts))
      tu/run-session
      :consumer
      vm/value))


;; =============================================================================
;; RULING 3 test 1: the reproduced arity-map regression
;; =============================================================================

(def ^:private cohort-forms
  "The cc4 cohort member source (r2 defect evidence): the destructuring,
   floats and repeated subtrees of the reproduced divergence, compiled with
   its original options and supplied coordinates."
  '[(defn replay
      [x]
      (let [[a b] [x 1.5]]
        (+ (identity ^:replay a) (identity ^:replay a) b)))
    (replay 2)])


(def ^:private named-arity-operand-roots
  "The ruled operand vector of the named arity map, recorded on the JVM.
   The children compile identically before and after the ordering fix
   (each child row is unchanged content), so these addresses held under
   c1-1 and hold under c1-2: only their order is the fix."
  [;; {} seed
   :segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10
   ;; :yin.k/fn  'replay
   :segment/blake3-1e03667ec9fa05b20761de3b84f623962235ff218fce26401bbcdd9d3aabeca8
   :segment/blake3-3156a0708410c1e18d4eb15f567790a8c1ac683f219e720a847b0e21e54ca778
   ;; :yin.vm.data/reason  :arity
   :segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf
   :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178
   ;; :yin.k/argc  (data/count dsg__av)
   :segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90
   :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b])


(def ^:private named-arity-key-order
  "The ruled pair order of the named arity map (key roots 1e0366, 81b62c,
   8930bf -- the ruling's pinned child rows)."
  [:yin.k/fn :yin.vm.data/reason :yin.k/argc])


(defn- arity-map-constructors
  "The constructed maps naming an arity error: an operand literal key
   :yin.k/fn (named) or :yin.k/kind (anonymous)."
  [ast]
  (into []
        (filter (fn [c]
                  (some (fn [o]
                          (and (= :literal (:type o))
                               (contains? #{:yin.k/fn :yin.k/kind}
                                          (:value o))))
                        (:operands c))))
        (map-constructors ast)))


(deftest arity-map-canonical-order-test
  (testing "the reproduced named case: argument-vector and lower-unit members"
    (doseq [[label ast] [[:argument-vector
                          (yang/compile-program cohort-forms true yang/unit-opts)]
                         [:lower-unit
                          (yang/lower-unit cohort-forms)]]
            :let [ctors (arity-map-constructors ast)]]
      (testing (str label)
        (is (pos? (count ctors)))
        (doseq [c ctors]
          (is (= named-arity-key-order (key-literals c)))
          ;; the recorded vector pins the replay member; other named arity
          ;; maps (the library's identity) differ only in the fn value root
          (is (= (assoc named-arity-operand-roots 2 (nth (child-roots c) 2))
                 (child-roots c)))
          (is (strictly-ascending? (pair-keys c)))
          (is (= empty-map-seed-root (first (child-roots c))))
          ;; the fn value is the named function's symbol, the argc value the
          ;; count application, the reason value :arity
          (is (contains? #{'replay 'identity}
                         (:value (nth (:operands c) 2))))
          (is (= :arity (:value (nth (:operands c) 4))))
          (is (= 'data/count
                 (get-in (nth (:operands c) 6) [:operator :name])))))))
  (testing "the anonymous arity error, without and with reader position"
    (doseq [[label forms] [[:anonymous (list (with-meta '(fn [x] x) nil))]
                           [:anonymous-with-source
                            (list (with-meta '(fn [x] x)
                                    {:line 7 :column 2}))]]
            :let [ast (yang/compile-program forms true yang/unit-opts)
                  ctors (arity-map-constructors ast)]]
      (testing (str label)
        (is (= 1 (count ctors)))
        (let [c (first ctors)]
          (is (strictly-ascending? (pair-keys c)))
          (if (= label :anonymous)
            (do (is (= [:yin.k/kind :yin.vm.data/reason :yin.k/argc]
                       (key-literals c)))
                (is (= :anonymous (:value (nth (:operands c) 2)))))
            (do (is (= [:yin.k/kind :yin.k/source :yin.vm.data/reason :yin.k/argc]
                       (key-literals c)))
                (is (= {:line 7 :column 2}
                       (:value (nth (:operands c) 4))))))))))
  (testing "the payload values an arity error raises at runtime"
    (is (= [:yin.k/error {:yin.k/fn 'two
                          :yin.vm.data/reason :arity
                          :yin.k/argc 2}]
           (run-outcome '[(defn two
                            [x]
                            x) (two 1 2)])))
    (is (= [:yin.k/error {:yin.k/kind :anonymous
                          :yin.vm.data/reason :arity
                          :yin.k/argc 2}]
           (run-outcome [(list (with-meta '(fn [x] x) nil) 1 2)])))))


;; =============================================================================
;; RULING 3 test 5: executable behavior on every route
;; =============================================================================

(deftest executable-order-and-trace-test
  (testing "keys precede their values; pairs evaluate in canonical order"
    ;; the expected trace comes from compiling the source pairs standalone
    ;; and ordering them with the ruling comparator, never from the tree
    ;; the emitter produced
    (let [entries [[(list 'tap :k1) (list 'tap :v1)]
                   [(list 'tap :k2) (list 'tap :v2)]]
          tap-arg (fn [form] (get-in (yang/compile form) [:operands 0 :value]))
          ordered (sort-by (fn [[k v]]
                             [(root-of (yang/compile-form k false {}))
                              (root-of (yang/compile-form v false {}))])
                           jing/canonical-compare entries)
          expected (into [] (mapcat (fn [[k v]] [(tap-arg k) (tap-arg v)]))
                         ordered)
          inserter (fn [es] (insertion-map (into [] (mapcat identity) es)))
          traces-a (trace-of (inserter entries) {})
          traces-b (trace-of (inserter (reverse entries)) {})]
      (is (= 2 (count ordered)))
      (is (= (into #{} (map :log) (vals traces-a))
             (into #{} (map :log) (vals traces-b)))
          "both insertion orders evaluate one canonical trace")
      (doseq [[lane {:keys [log]}] traces-a]
        (is (= expected log) (str lane))))
    (testing "no duplication: every occurrence evaluates exactly once"
      (let [entries [[(list 'tap :k) (list 'tap :v)]
                     [(list 'tap :k2) (list 'tap :v2)]]
            tap-arg (fn [form] (get-in (yang/compile form) [:operands 0 :value]))
            ordered (sort-by (fn [[k v]]
                               [(root-of (yang/compile-form k false {}))
                                (root-of (yang/compile-form v false {}))])
                             jing/canonical-compare entries)
            expected (into [] (mapcat (fn [[k v]] [(tap-arg k) (tap-arg v)]))
                           ordered)
            traces (trace-of (insertion-map (into [] (mapcat identity) entries)) {})]
        (is (= 4 (count expected)))
        (doseq [[lane {:keys [log]}] traces]
          (is (= expected log) (str lane)))))
    (testing "set value collisions keep one member; pure results unchanged"
      (doseq [[lane {:keys [value]}]
              ;; (+ n 1) and (- n -1) both evaluate to 2 at n=1: the value
              ;; collides while the compiled occurrences stay three
              (trace-of #{(list '+ 'n 1) (list '- 'n -1) 3} {'n 1})]
        (is (= #{2 3} value) (str lane)))
      (doseq [[lane {:keys [value]}]
              (trace-of {:a (list '+ 'n 1) :b (list '+ 'n 2)} {'n 1})]
        (is (= {:a 2 :b 3} value) (str lane))))
    (testing "no later event after an operand failure"
      ;; one pair evaluates; the failing key stops the constructor before any
      ;; later pair runs
      (let [traces (trace-of {(list 'tap :early) (list 'tap :early-v)
                              (list 'boom) :never}
                             {'boom (fn []
                                      (throw (ex-info "boom" {:order-test :boom})))})]
        (doseq [[lane {:keys [log]}] traces]
          (is (some #{[:early :early-v] []} [log])
              (str lane " saw " (pr-str log))))
        (is (some :raised (vals traces))
            "at least one route surfaces the operand failure"))))
  (testing "explicit assoc/conj, vectors, let and do keep written order"
    (let [swapped-code? (fn [a b]
                          (not= (root-of (yang/compile a))
                                (root-of (yang/compile b))))
          written (fn [form]
                    (into #{} (map (fn [[_ {:keys [log]}]] log)
                                   (trace-of form {}))))]
      (is (= #{[:a :b]} (written (list 'assoc {}
                                       (list 'tap :a) (list 'tap :b)))))
      (is (= #{[:b :a]} (written (list 'assoc {}
                                       (list 'tap :b) (list 'tap :a)))))
      (is (= #{[:x :y]} (written (list 'conj []
                                       (list 'tap :x) (list 'tap :y)))))
      (is (= #{[:v1 :v2]} (written [(list 'tap :v1) (list 'tap :v2)])))
      (is (= #{[:v2 :v1]} (written [(list 'tap :v2) (list 'tap :v1)])))
      (is (= #{[:l1 :l2]}
             (written (list 'let ['a (list 'tap :l1) 'b (list 'tap :l2)]
                            [:a :b]))))
      (is (= #{[:d1 :d2]}
             (written (list 'do (list 'tap :d1) (list 'tap :d2)))))
      ;; swapping effectful explicit-call/vector arguments stays observable
      ;; in the trace and the two written orders remain distinct code
      (is (swapped-code? (list 'assoc {} (list 'tap :a) (list 'tap :b))
                         (list 'assoc {} (list 'tap :b) (list 'tap :a))))
      (is (swapped-code? [(list 'tap :v1) (list 'tap :v2)]
                         [(list 'tap :v2) (list 'tap :v1)])))))


(deftest colliding-keys-canonical-winner-test
  "RULING 2: for equal evaluated keys the later pair wins, and which pair
   is later is the canonical order -- so both insertion orders agree, and
   the winner is the one the independent comparator puts last."
  (let [pairs [[(list '- 'n 1) :a]
               [(list '- 1 'n) :b]]
        pair-key (fn [[k v]]
                   [(root-of (yang/compile-form k false {}))
                    (root-of (yang/compile-form v false {}))])
        ordered (sort-by pair-key jing/canonical-compare pairs)
        winner (second (last ordered))
        inserter (fn [es] (insertion-map (into [] (mapcat identity) es)))]
    (is (= 2 (count ordered)))
    (testing "both insertion orders yield the canonically last value"
      (doseq [[label es] [[:a-first pairs] [:b-first (reverse pairs)]]
              [lane {:keys [value]}] (trace-of (inserter es) {'n 1})]
        (is (= {0 winner} value) (str label " " lane))))
    (testing "the two pair keys really differ (the tie-break is exercised)"
      (is (not= (pair-key (first pairs)) (pair-key (second pairs)))))))


;; =============================================================================
;; RULING 3 test 6: entry points and generation
;; =============================================================================

(def ^:private entry-map-form
  "One dynamic map whose canonical order the oracle below derives; the
   written insertion deliberately differs from a sorted reading."
  {(list (quote tap) :k2) (list (quote tap) :v2)
   (list (quote tap) :k1) (list (quote tap) :v1)})


(defn- map-operand-roots
  "The ordered child-address vectors of every constructed map of ast."
  [ast]
  (->> (collection-applications ast)
       (filter (fn [c] (= (quote assoc) (get-in c [:operator :name]))))
       (mapv child-roots)))


(deftest entry-points-order-test
  (testing "compile-form, compile and native compile-program emit one
            canonical operand vector"
    (let [asts {:compile-form (yang/compile-form entry-map-form)
                :compile (yang/compile entry-map-form)
                :compile-program-native (yang/compile-program [entry-map-form])}
          reference (map-operand-roots (:compile-form asts))]
      (is (= 1 (count reference)))
      (doseq [[entry ast] asts]
        (testing (str entry)
          (is (= (vec reference) (map-operand-roots ast)))))))
  (testing "the convention entries (argument-vector unit, lower-unit) emit
            one canonical operand vector, ordered by the same comparator"
    ;; the convention compiles free names through its adapters, so the
    ;; child code differs from the native entries by design; the two
    ;; convention entries compile the map under one environment and must
    ;; agree with each other
    (let [unit-forms [(list (quote def) (quote m) entry-map-form)]
          asts {:compile-program-unit (yang/compile-program unit-forms true yang/unit-opts)
                :lower-unit (yang/lower-unit unit-forms)}
          ;; the entry's own map is the 5-operand constructor (seed + 2
          ;; pairs); the convention preamble adds its own single-pair maps
          entry-map-roots (fn [ast]
                            (into #{}
                                  (map child-roots)
                                  (filter #(= 5 (count (:operands %)))
                                          (map-constructors ast))))
          roots-a (entry-map-roots (:compile-program-unit asts))
          roots-b (entry-map-roots (:lower-unit asts))]
      (is (= 1 (count roots-a)))
      (is (= roots-a roots-b))
      (is (every? #(strictly-ascending? (pair-keys %))
                  (map-constructors (:lower-unit asts))))))
  (testing "compiler-generated maps (the arity helper) are covered"
    (is (strictly-ascending?
          (pair-keys (first (arity-map-constructors
                              (yang/compile-program cohort-forms
                                                    true yang/unit-opts))))))))


(defn- expand-like-the-repl
  "The REPL production expansion stages over compiled ast (the callsite
   battery helper, local copy, under this namespace tokens)."
  [ast]
  (let [store (macro/seed-store
                (macro/make-ctx {:token :order-test/standard-forms
                                 :source-medium :order-test/standard-forms})
                [macro/stdlib-forms])
        ctx (macro/make-ctx {:token "order-test"
                             :source-medium :program-in
                             :store store})]
    {:ctx ctx
     :result (macro/expand-batch (encoder/program-batch ast) ctx)}))


(deftest expansion-origins-unfabricated-test
  "The real production macro/twin-call fixture (the equality gate keeps its
   complete expansion-record chain): a program that also contains a
   constructed map expands cleanly, and pre-expansion collection
   normalization fabricates no expansion origin and erases none."
  (let [ast (yang/compile-program
              (quote [(defmacro twin
                        [x]
                        (yin/if (yin/literal true) x
                                (yin/application (yin/variable (quote f))
                                                 [(yin/literal 1)])))
                      (defn holder
                        [x]
                        {:kept (first (list x))})
                      (twin (holder 1))]))
        {:keys [ctx result]} (expand-like-the-repl ast)
        {:keys [status tree frontend-metadata log]} result
        admission [:source :program-in (:t ctx) 0]
        event-addrs (into #{} (map first) (nth log 1))
        origins (into #{}
                      (comp (filter (fn [row] (= :yang/call-site (nth row 3))))
                            (map first))
                      frontend-metadata)
        input-tree (vm/ast->semantic-bytecode ast)
        final (macro/packet->row-set tree)]
    (is (= :ok status))
    (is (not= (:root input-tree) (:root final))
        "the twin call really expanded")
    (is (seq frontend-metadata))
    (is (every? (fn [o]
                  (or (= o admission)
                      (and (vector? o) (= :expansion (first o))
                           (contains? event-addrs (second o)))))
                origins)
        "every origin is the admission or names a recorded expansion event")
    (is (pos? (count (map-operand-roots ast)))
        "the program carries a constructed map through expansion")))


;; =============================================================================
;; RULING 3 test 7: legacy loading and identity boundaries
;; =============================================================================

(def ^:private legacy-c1-1-payload-b64
  "The retained pre-fix (manifest c1-1) artifact, recorded on the JVM at
   dispatch revision 2687ab0c before this round: the complete canonical row
   set of the program ((fn [x] {(- x 1) :a (- 1 x) :b}) 1), its root, its
   executed value, and its pre-fix operand vector. The binary original is
   test/resources/yang/clojure/constructed-collections-legacy-c1-1.cbor;
   the base64 form is the portable copy every host decodes."
  (apply str
         ["ptgbgnBkYW8uamluZy9rZXl3b3JkgvZkcm9vdNgbgnBkYW8uamluZy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtNDlkOWVmMzE5"
          "MWIzMDViMjZlNjdhODAzYzllNzQxMDRkYzU3ZDFjNWYwMzYxNDdiM2ZkMjM1MmQyNTZmNWU2NNgbgnBkYW8uamluZy9rZXl3b3Jk"
          "gvZkcm93c6zYG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhHYmxha2UzLTFhNGE3ZDg0YTc0ZDU4ZWFhNzMyYzlhYTAwNjk5"
          "ZjMyMzg1MDVlNjYwNDNhZGY3MmMzNjMxMjE5OGVmNGE3NmOF2BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy0x"
          "YTRhN2Q4NGE3NGQ1OGVhYTczMmM5YWEwMDY5OWYzMjM4NTA1ZTY2MDQzYWRmNzJjMzYzMTIxOThlZjRhNzZj2BuCcGRhby5qaW5n"
          "L2tleXdvcmSC9mthcHBsaWNhdGlvbtgbgnBkYW8uamluZy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtMzM5ZDA3MGVmNzM5YjAw"
          "Njc0ODRmN2ZmNDY5MzBhYjllOTkyY2IzZTRhYmFkYzI3ZDQwM2YyOTRiOGRlYTk5N4XYG4JwZGFvLmppbmcva2V5d29yZIJnc2Vn"
          "bWVudHhHYmxha2UzLTIwMWYyMzVjYmFjOTk4ZjQzOTU0NDU0ZTlkZjBlYjYzMzcxN2U3OWRlNTM1Nzc2ZjNmNTFlMTk4YTM3NTli"
          "MTDYG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhHYmxha2UzLWY0ZTNkMzZjNDVkYTE1YjFjOTVlODk1ZGU3ZTQ2NGVhYjVi"
          "ZjIzNzNkYTk1ZjVhZWU3ZGNjNmU2ZWY4NmY2YjjYG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhHYmxha2UzLWIxODU5MTg5"
          "OTBiMTFlZTE0MmRlNzY4OTI3N2I0OTg3Mzg3YjI4MzUyMTVjYjViMjMwYzJhMDU2ODYxNmE1ODTYG4JwZGFvLmppbmcva2V5d29y"
          "ZIJnc2VnbWVudHhHYmxha2UzLTYyODQ5NWYzNjc1ODk4ZGUyOTAyNWZjMDdmYTc1YWExZDE2ZGY5YTYzZWRhZjA4ZGFkN2MzYzI3"
          "ODYwNmNlNjPYG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhHYmxha2UzLTM5OGY2OGUwZTVlYjMwZmQ5NjI1NTgyY2IwNjNm"
          "OWUwMjEzMzM4N2RlN2IzZWZlMDdjNTFlYzVkNTA0NzI4Nzn12BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy0y"
          "MDFmMjM1Y2JhYzk5OGY0Mzk1NDQ1NGU5ZGYwZWI2MzM3MTdlNzlkZTUzNTc3NmYzZjUxZTE5OGEzNzU5YjEwg9gbgnBkYW8uamlu"
          "Zy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtMjAxZjIzNWNiYWM5OThmNDM5NTQ0NTRlOWRmMGViNjMzNzE3ZTc5ZGU1MzU3NzZm"
          "M2Y1MWUxOThhMzc1OWIxMNgbgnBkYW8uamluZy9rZXl3b3JkgvZnbGl0ZXJhbKDYG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVu"
          "dHhHYmxha2UzLTMzOWQwNzBlZjczOWIwMDY3NDg0ZjdmZjQ2OTMwYWI5ZTk5MmNiM2U0YWJhZGMyN2Q0MDNmMjk0YjhkZWE5OTeD"
          "2BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy0zMzlkMDcwZWY3MzliMDA2NzQ4NGY3ZmY0NjkzMGFiOWU5OTJj"
          "YjNlNGFiYWRjMjdkNDAzZjI5NGI4ZGVhOTk32BuCcGRhby5qaW5nL2tleXdvcmSC9mh2YXJpYWJsZdgbgm9kYW8uamluZy9zeW1i"
          "b2yC9mVhc3NvY9gbgnBkYW8uamluZy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtMzk4ZjY4ZTBlNWViMzBmZDk2MjU1ODJjYjA2"
          "M2Y5ZTAyMTMzMzg3ZGU3YjNlZmUwN2M1MWVjNWQ1MDQ3Mjg3OYPYG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhHYmxha2Uz"
          "LTM5OGY2OGUwZTVlYjMwZmQ5NjI1NTgyY2IwNjNmOWUwMjEzMzM4N2RlN2IzZWZlMDdjNTFlYzVkNTA0NzI4NznYG4JwZGFvLmpp"
          "bmcva2V5d29yZIL2Z2xpdGVyYWzYG4JwZGFvLmppbmcva2V5d29yZIL2YWLYG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhH"
          "Ymxha2UzLTQxYmQ5Y2EwYzYzODQ4NDdiYzRlMDI0YTdmZmVhZTg4ODVjOTIwNjQ4YWZiMWI2OWQ2MjI2ZmJjMWFjMTY5OWWD2BuC"
          "cGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy00MWJkOWNhMGM2Mzg0ODQ3YmM0ZTAyNGE3ZmZlYWU4ODg1YzkyMDY0"
          "OGFmYjFiNjlkNjIyNmZiYzFhYzE2OTll2BuCcGRhby5qaW5nL2tleXdvcmSC9mdsaXRlcmFsAdgbgnBkYW8uamluZy9rZXl3b3Jk"
          "gmdzZWdtZW50eEdibGFrZTMtNDlkOWVmMzE5MWIzMDViMjZlNjdhODAzYzllNzQxMDRkYzU3ZDFjNWYwMzYxNDdiM2ZkMjM1MmQy"
          "NTZmNWU2NIXYG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhHYmxha2UzLTQ5ZDllZjMxOTFiMzA1YjI2ZTY3YTgwM2M5ZTc0"
          "MTA0ZGM1N2QxYzVmMDM2MTQ3YjNmZDIzNTJkMjU2ZjVlNjTYG4JwZGFvLmppbmcva2V5d29yZIL2a2FwcGxpY2F0aW9u2BuCcGRh"
          "by5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy1kYTNmMTZlMDc2NWMxMGJlNDc2YTc1MmZmMDIzNjNmYjc3ZjIxZDRiODQ4"
          "MWJhM2NjMGY4YWUwZDJiZTNiNjg0gdgbgnBkYW8uamluZy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtNDFiZDljYTBjNjM4NDg0"
          "N2JjNGUwMjRhN2ZmZWFlODg4NWM5MjA2NDhhZmIxYjY5ZDYyMjZmYmMxYWMxNjk5ZfXYG4JwZGFvLmppbmcva2V5d29yZIJnc2Vn"
          "bWVudHhHYmxha2UzLTYyODQ5NWYzNjc1ODk4ZGUyOTAyNWZjMDdmYTc1YWExZDE2ZGY5YTYzZWRhZjA4ZGFkN2MzYzI3ODYwNmNl"
          "NjOF2BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy02Mjg0OTVmMzY3NTg5OGRlMjkwMjVmYzA3ZmE3NWFhMWQx"
          "NmRmOWE2M2VkYWYwOGRhZDdjM2MyNzg2MDZjZTYz2BuCcGRhby5qaW5nL2tleXdvcmSC9mthcHBsaWNhdGlvbtgbgnBkYW8uamlu"
          "Zy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtOTEzYzhhNmYxMDk3MDAyMTc5ZDQ4NWMwNWRiNTNlMDhmNmMwODkxYzJjYmU1MDdh"
          "MmVjODJhNGIzNGY1Yzc4ZYLYG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhHYmxha2UzLTQxYmQ5Y2EwYzYzODQ4NDdiYzRl"
          "MDI0YTdmZmVhZTg4ODVjOTIwNjQ4YWZiMWI2OWQ2MjI2ZmJjMWFjMTY5OWXYG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhH"
          "Ymxha2UzLTc2NGM1ZTUwZWMyZGJlN2U0OGFhMWEwY2RkYmJlNGM4NzkxOThiODk2ZjczMWEzMzkwZjNlY2VlYmRjYmIzNjX02BuC"
          "cGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy03NjRjNWU1MGVjMmRiZTdlNDhhYTFhMGNkZGJiZTRjODc5MTk4Yjg5"
          "NmY3MzFhMzM5MGYzZWNlZWJkY2JiMzY1g9gbgnBkYW8uamluZy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtNzY0YzVlNTBlYzJk"
          "YmU3ZTQ4YWExYTBjZGRiYmU0Yzg3OTE5OGI4OTZmNzMxYTMzOTBmM2VjZWViZGNiYjM2NdgbgnBkYW8uamluZy9rZXl3b3JkgvZo"
          "dmFyaWFibGXYG4JvZGFvLmppbmcvc3ltYm9sgvZheNgbgnBkYW8uamluZy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMtOTEzYzhh"
          "NmYxMDk3MDAyMTc5ZDQ4NWMwNWRiNTNlMDhmNmMwODkxYzJjYmU1MDdhMmVjODJhNGIzNGY1Yzc4ZYPYG4JwZGFvLmppbmcva2V5"
          "d29yZIJnc2VnbWVudHhHYmxha2UzLTkxM2M4YTZmMTA5NzAwMjE3OWQ0ODVjMDVkYjUzZTA4ZjZjMDg5MWMyY2JlNTA3YTJlYzgy"
          "YTRiMzRmNWM3OGXYG4JwZGFvLmppbmcva2V5d29yZIL2aHZhcmlhYmxl2BuCb2Rhby5qaW5nL3N5bWJvbIL2YS3YG4JwZGFvLmpp"
          "bmcva2V5d29yZIJnc2VnbWVudHhHYmxha2UzLWIxODU5MTg5OTBiMTFlZTE0MmRlNzY4OTI3N2I0OTg3Mzg3YjI4MzUyMTVjYjVi"
          "MjMwYzJhMDU2ODYxNmE1ODSD2BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy1iMTg1OTE4OTkwYjExZWUxNDJk"
          "ZTc2ODkyNzdiNDk4NzM4N2IyODM1MjE1Y2I1YjIzMGMyYTA1Njg2MTZhNTg02BuCcGRhby5qaW5nL2tleXdvcmSC9mdsaXRlcmFs"
          "2BuCcGRhby5qaW5nL2tleXdvcmSC9mFh2BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy1kYTNmMTZlMDc2NWMx"
          "MGJlNDc2YTc1MmZmMDIzNjNmYjc3ZjIxZDRiODQ4MWJhM2NjMGY4YWUwZDJiZTNiNjg0hNgbgnBkYW8uamluZy9rZXl3b3Jkgmdz"
          "ZWdtZW50eEdibGFrZTMtZGEzZjE2ZTA3NjVjMTBiZTQ3NmE3NTJmZjAyMzYzZmI3N2YyMWQ0Yjg0ODFiYTNjYzBmOGFlMGQyYmUz"
          "YjY4NNgbgnBkYW8uamluZy9rZXl3b3JkgvZmbGFtYmRhgdgbgm9kYW8uamluZy9zeW1ib2yC9mF42BuCcGRhby5qaW5nL2tleXdv"
          "cmSCZ3NlZ21lbnR4R2JsYWtlMy0xYTRhN2Q4NGE3NGQ1OGVhYTczMmM5YWEwMDY5OWYzMjM4NTA1ZTY2MDQzYWRmNzJjMzYzMTIx"
          "OThlZjRhNzZj2BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy1mNGUzZDM2YzQ1ZGExNWIxYzk1ZTg5NWRlN2U0"
          "NjRlYWI1YmYyMzczZGE5NWY1YWVlN2RjYzZlNmVmODZmNmI4hdgbgnBkYW8uamluZy9rZXl3b3JkgmdzZWdtZW50eEdibGFrZTMt"
          "ZjRlM2QzNmM0NWRhMTViMWM5NWU4OTVkZTdlNDY0ZWFiNWJmMjM3M2RhOTVmNWFlZTdkY2M2ZTZlZjg2ZjZiONgbgnBkYW8uamlu"
          "Zy9rZXl3b3JkgvZrYXBwbGljYXRpb27YG4JwZGFvLmppbmcva2V5d29yZIJnc2VnbWVudHhHYmxha2UzLTkxM2M4YTZmMTA5NzAw"
          "MjE3OWQ0ODVjMDVkYjUzZTA4ZjZjMDg5MWMyY2JlNTA3YTJlYzgyYTRiMzRmNWM3OGWC2BuCcGRhby5qaW5nL2tleXdvcmSCZ3Nl"
          "Z21lbnR4R2JsYWtlMy03NjRjNWU1MGVjMmRiZTdlNDhhYTFhMGNkZGJiZTRjODc5MTk4Yjg5NmY3MzFhMzM5MGYzZWNlZWJkY2Ji"
          "MzY12BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy00MWJkOWNhMGM2Mzg0ODQ3YmM0ZTAyNGE3ZmZlYWU4ODg1"
          "YzkyMDY0OGFmYjFiNjlkNjIyNmZiYzFhYzE2OTll9NgbgnBkYW8uamluZy9rZXl3b3JkgvZncHJvZ3JhbXgkKChmbiBbeF0geygt"
          "IHggMSkgOmEgKC0gMSB4KSA6Yn0pIDEp2BuCcGRhby5qaW5nL2tleXdvcmSC9mlvbGQtdmFsdWWhANgbgnBkYW8uamluZy9rZXl3"
          "b3JkgvZhYtgbgnBkYW8uamluZy9rZXl3b3JkgvZxbWFuaWZlc3QtcmV2aXNpb25kYzEtMdgbgnBkYW8uamluZy9rZXl3b3JkgvZx"
          "b2xkLW9wZXJhbmQtcm9vdHOF2BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy0yMDFmMjM1Y2JhYzk5OGY0Mzk1"
          "NDQ1NGU5ZGYwZWI2MzM3MTdlNzlkZTUzNTc3NmYzZjUxZTE5OGEzNzU5YjEw2BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4"
          "R2JsYWtlMy1mNGUzZDM2YzQ1ZGExNWIxYzk1ZTg5NWRlN2U0NjRlYWI1YmYyMzczZGE5NWY1YWVlN2RjYzZlNmVmODZmNmI42BuC"
          "cGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy1iMTg1OTE4OTkwYjExZWUxNDJkZTc2ODkyNzdiNDk4NzM4N2IyODM1"
          "MjE1Y2I1YjIzMGMyYTA1Njg2MTZhNTg02BuCcGRhby5qaW5nL2tleXdvcmSCZ3NlZ21lbnR4R2JsYWtlMy02Mjg0OTVmMzY3NTg5"
          "OGRlMjkwMjVmYzA3ZmE3NWFhMWQxNmRmOWE2M2VkYWYwOGRhZDdjM2MyNzg2MDZjZTYz2BuCcGRhby5qaW5nL2tleXdvcmSCZ3Nl"
          "Z21lbnR4R2JsYWtlMy0zOThmNjhlMGU1ZWIzMGZkOTYyNTU4MmNiMDYzZjllMDIxMzMzODdkZTdiM2VmZTA3YzUxZWM1ZDUwNDcy"
          "ODc5"]))


(defn- legacy-payload
  "The decoded pre-fix artifact."
  []
  (cbor/decode (base64/decode legacy-c1-1-payload-b64)))


(deftest legacy-artifact-loads-and-runs-test
  "RULING 4: a retained pre-fix artifact loads at its old address and
   executes without recompilation, keeping its old operand order and old
   behavior; the same source freshly compiled under c1-2 canonicalizes."
  (let [payload (legacy-payload)
        rows (select-keys payload [:root :rows])
        program (list (list (quote fn) [(quote x)]
                            (insertion-map
                              [(list (quote -) (quote x) 1) :a
                               (list (quote -) 1 (quote x)) :b]))
                      1)
        recompiled (yang/compile program)]
    (testing "the artifact is complete and self-consistent"
      (is (= (:root payload) (get-in payload [:rows (:root payload) 0])))
      (is (= "c1-1" (:manifest-revision payload))))
    (testing "the rows load through the walker loader and run unchanged"
      (let [vm (ast-walker/vm-load-rows (tu/create-vm base-opts)
                                        rows vm/ast-contract)]
        (is (= (:old-value payload) (vm/value (vm/run vm)))
            "old operand order stays old executable behavior")))
    (testing "the old order is not the new canonical order"
      (is (pos? (count (get-in payload [:old-operand-roots]))))
      (is (not= (:root (vm/ast->semantic-bytecode recompiled))
                (:root payload))
          "recompiling the same source mints the canonicalized tree"))
    (testing "the fresh compilation canonicalizes and changes the winner"
      (let [ctor (first (map-constructors recompiled))]
        (is (strictly-ascending? (pair-keys ctor)))
        (is (not= (child-roots ctor)
                  (vec (:old-operand-roots payload))))
        ;; both keys evaluate to 0; the canonically last pair wins now
        (is (= (:old-value payload) {0 :b}))
        (is (not= {0 :b} (:ast-walker (run-form program {}))))))))


(deftest token-admission-distinct-origins-test
  "Admitting the same content under two tokens yields one root and two
   distinct origin sets and occurrence-key vectors (the cohort negative
   member rule)."
  (let [form {(list (quote tap) :k) (list (quote tap) :v)}
        a (project-batch (yang/compile form) "order-a")
        b (project-batch (yang/compile form) "order-b")
        origins (fn [batch]
                  (into #{} (comp (filter (fn [row] (= :yang/call-site (nth row 3))))
                                  (map first))
                        (:rows batch)))]
    (is (= (:root (:tree a)) (:root (:tree b))))
    (is (not= (origins a) (origins b)))
    (is (not= (digest-snapshot a) (digest-snapshot b)))))
