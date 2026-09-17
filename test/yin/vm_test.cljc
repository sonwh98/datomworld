(ns yin.vm-test
  "Codec tests for `yin.vm`: the explicit root fact, its indexing, the
   Phase 0 retirements of yin.vm.macro.md (§2.4, §7), and the host-uniform
   division primitive."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [yang.clojure :as yang]
            [yin.vm :as vm]))


(defn- error-message
  [f]
  (try (f)
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e (ex-message e))))


(def ^:private program
  "A macro-free program touching :if, :application, :lambda and tail flags."
  '(if (< 1 2) ((fn [x] (+ x 1)) 41) 0))


(def ^:private pre-root-snapshot
  "`(vm/ast->datoms (yang/compile program))` captured before the root fact
   was added."
  '[[-17 :yin/tail? true 0 1] [-17 :yin/type :if 0 1]
    [-18 :yin/type :application 0 1] [-19 :yin/type :variable 0 1]
    [-19 :yin/name < 0 1] [-20 :yin/type :literal 0 1]
    [-20 :yin/value 1 0 1] [-21 :yin/type :literal 0 1]
    [-21 :yin/value 2 0 1] [-18 :yin/operator -19 0 1]
    [-18 :yin/operands [-20 -21] 0 1] [-22 :yin/tail? true 0 1]
    [-22 :yin/type :application 0 1] [-23 :yin/type :lambda 0 1]
    [-23 :yin/params [x] 0 1] [-24 :yin/tail? true 0 1]
    [-24 :yin/type :application 0 1] [-25 :yin/type :variable 0 1]
    [-25 :yin/name + 0 1] [-26 :yin/type :variable 0 1]
    [-26 :yin/name x 0 1] [-27 :yin/type :literal 0 1]
    [-27 :yin/value 1 0 1] [-24 :yin/operator -25 0 1]
    [-24 :yin/operands [-26 -27] 0 1] [-23 :yin/body -24 0 1]
    [-28 :yin/type :literal 0 1] [-28 :yin/value 41 0 1]
    [-22 :yin/operator -23 0 1] [-22 :yin/operands [-28] 0 1]
    [-29 :yin/type :literal 0 1] [-29 :yin/value 0 0 1]
    [-17 :yin/test -18 0 1] [-17 :yin/consequent -22 0 1]
    [-17 :yin/alternate -29 0 1]])


(defn- literal-batch
  "A literal program at tempid -101 followed by an unreferenced literal at
   -1, which the structural heuristic would prefer as root."
  []
  (let [[root datoms] (vm/ast->datoms-with-root {:type :literal, :value 7}
                                                {:id-start -100})]
    [root (into datoms [[-1 :yin/type :literal 0 1] [-1 :yin/value 8 0 1]])]))


(deftest schema-carries-root-and-drops-event-attributes
  (is (= {} (:yin/root vm/schema)))
  (is (contains? vm/schema :yin/macro-name))
  (is (contains? vm/schema :yin/macro?))
  (doseq [a [:yin/phase-policy :yin/phase :yin/capability :yin/source-call
             :yin/macro :yin/expansion-root :yin/error]]
    (is (not (contains? vm/schema a)) (str a " belongs to the expander"))))


(deftest compile-output-is-unchanged-modulo-the-root-fact
  (let [ast (yang/compile program)
        [root datoms] (vm/ast->datoms-with-root ast)]
    (is (= -17 root))
    (is (= (conj pre-root-snapshot [-17 :yin/root true 0 1]) datoms)
        "exactly one row is added, and it is the last")
    (is (= datoms (vm/ast->datoms ast)))
    (is (= 1 (count (filter #(= :yin/root (nth % 1)) datoms))))
    (is (= ast (vm/datoms->ast datoms)) "the root fact does not disturb decode")))


(deftest root-fact-wins-over-the-heuristic
  (let [[root datoms] (literal-batch)
        indexed (vm/index-datoms datoms)]
    (is (= -101 root))
    (is (= -1 (:root-id (vm/index-datoms (remove #(= :yin/root (nth % 1))
                                                 datoms))))
        "without the fact the heuristic picks the other entity")
    (is (= -101 (:root-id indexed)))
    (is (nil? (:error indexed)))
    (is (= {:type :literal, :value 7} (vm/datoms->ast datoms)))))


(deftest last-root-fact-wins
  (let [[_ datoms] (literal-batch)
        datoms (conj datoms [-1 :yin/root true 0 1])]
    (is (= -1 (:root-id (vm/index-datoms datoms))))
    (is (= {:type :literal, :value 8} (vm/datoms->ast datoms)))))


(deftest explicit-root-id-overrides-the-root-fact
  (let [[_ datoms] (literal-batch)]
    (is (= -1 (:root-id (vm/index-datoms datoms {:root-id -1}))))))


(deftest dangling-root-is-recorded-not-thrown
  (let [datoms [[-1 :yin/type :literal 0 1] [-1 :yin/value 1 0 1]
                [-9 :yin/root true 0 1]]
        indexed (vm/index-datoms datoms)]
    (is (= -9 (:root-id indexed)))
    (is (= {:rule :dangling-root, :entity -9} (:error indexed)))
    (is (some? (error-message #(vm/datoms->ast datoms)))
        "decoding the dangling root is the loud failure")))


(deftest heuristic-fallback-is-unchanged
  (testing "a batch without a root fact still finds its root structurally"
    (let [indexed (vm/index-datoms pre-root-snapshot)]
      (is (= -17 (:root-id indexed)))
      (is (nil? (:error indexed)))
      (is (= (yang/compile program) (vm/datoms->ast pre-root-snapshot)))))
  (testing "the hand-written datom form v1 accepted still decodes"
    (is (= {:type :literal, :value 99}
           (vm/datoms->ast [[-1 :yin/type :literal 0 1]
                            [-1 :yin/value 99 0 1]])))))


(deftest macro-lambda-keeps-macro-flag-without-phase
  (let [ast {:type :lambda,
             :params '[x],
             :macro? true,
             :body {:type :variable, :name 'x}}
        datoms (vm/ast->datoms ast)]
    (is (= '[[-17 :yin/type :lambda 0 1] [-17 :yin/macro? true 0 1]
             [-17 :yin/params [x] 0 1] [-18 :yin/type :variable 0 1]
             [-18 :yin/name x 0 1] [-17 :yin/body -18 0 1]
             [-17 :yin/root true 0 1]]
           datoms))
    (is (= ast (vm/datoms->ast datoms)))
    (testing "a legacy :yin/phase-policy fact is not decoded"
      (is (= ast
             (vm/datoms->ast (conj datoms
                                   [-17 :yin/phase-policy :compile 0 1])))))))


(deftest macro-expand-node-type-is-retired
  (let [node {:type :yin/macro-expand,
              :operator {:type :variable, :name 'm},
              :operands []}]
    (is (= "Unknown AST node type"
           (error-message #(vm/ast->datoms node))))
    (is (= "Unknown AST node type in datoms"
           (error-message #(vm/datoms->ast [[-1 :yin/type :yin/macro-expand 0 1]
                                            [-1 :yin/root true 0 1]]))))))


;; Ported from `test/yin/vm/ast_conversion_test.cljc` before its U6 deletion:
;; the node types the root-fact deftests above do not exercise — yang-compiled
;; defns, the FFI call node, store effects and stream effects — through the
;; datom codec (`ast->datoms`/`datoms->ast`, not semantic bytecode).
(deftest codec-round-trips-the-v1-corpus-node-types
  (letfn [(rt [ast] (= ast (vm/datoms->ast (vm/ast->datoms ast))))]
    (testing "yang-compiled defns"
      (is (rt (yang/compile '(defn foo
                               [n]
                               (+ n 1)))))
      (is (rt (yang/compile '(defn bar
                               [x y]
                               (println x) (+ x y)))))
      (is (rt (yang/compile
                '(defn fib
                   [n]
                   (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))))))
    (testing ":dao.stream.apply/call"
      (is (rt {:type :dao.stream.apply/call,
               :op :op/eval,
               :operands [{:type :literal, :value "1+2"}]})))
    (testing ":vm/store-get and :vm/store-put"
      (is (rt {:type :vm/store-get, :key 'x}))
      (is (rt {:type :vm/store-put, :key 'y, :val 123})))
    (testing "stream effects"
      (is (rt {:type :stream/make, :buffer 64}))
      (is (rt {:type :stream/put,
               :target {:type :variable, :name 's},
               :val {:type :literal, :value 1}})))))


;; =============================================================================
;; Primitives
;; =============================================================================

(deftest division-by-zero-is-host-uniform
  (let [divide (get vm/primitives '/)]
    (is (= "Divide by zero" (error-message #(divide 1 0)))
        "a zero divisor raises with the JVM's text instead of yielding ##Inf")
    (is (= "Divide by zero" (error-message #(divide 24 2 0)))
        "the variadic path checks every division it reduces over")))


;; =============================================================================
;; Semantic bytecode (docs/design/yin.vm.code-as-tuples.md §2, §7.2)
;; =============================================================================

(defn- error-rule
  [f]
  (try (f)
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (:rule (ex-data e)))))


(defn- lit
  [v]
  {:type :literal, :value v})


(defn- local
  [n]
  {:type :variable, :name n})


(defn- app
  [operator operands tail?]
  {:type :application, :operator operator, :operands operands, :tail? tail?})


(def ^:private semantic-bytecode-corpus
  "Canonical map ASTs (every saturated field stated) covering every §2.3 tag."
  [(lit 1) (lit "s") (lit nil) (lit :k) (lit '[1 (2 3) #{4}]) (lit {:a 1})
   (local 'x)
   (local '+)
   {:type :lambda, :params [], :body (lit 0)}
   {:type :lambda, :params '[x], :body (local 'x)}
   {:type :lambda,
    :params '[x y z],
    :body (app (local '+) [(local 'x) (local 'y) (local 'z)] true)}
   (app (local 'f) [] false)
   (app (local 'f) [(lit 1)] true)
   (app {:type :lambda, :params '[x], :body (local 'x)} [(lit 1) (lit 2)] false)
   {:type :if,
    :test (app (local '<) [(lit 1) (lit 2)] false),
    :consequent (app (local 'f) [(lit 41)] true),
    :alternate (lit 0)}
   {:type :dao.stream.apply/call, :op :op/none, :operands []}
   {:type :dao.stream.apply/call, :op :op/add, :operands [(lit 1) (lit 2)]}
   {:type :vm/gensym, :prefix "id"}
   {:type :vm/gensym, :prefix "g"}
   {:type :vm/store-get, :key 'yin/def}
   {:type :vm/store-get, :key :k}
   {:type :vm/store-get, :key 3}
   {:type :vm/store-put, :key :k, :val [1 {:b 2}]}
   {:type :vm/current-continuation}
   {:type :vm/park}
   {:type :vm/resume, :parked-id :parked/p, :val (lit 1)}
   {:type :stream/close,
    :source {:type :stream/next,
             :source {:type :stream/cursor,
                      :source {:type :stream/put,
                               :target {:type :stream/make, :buffer 16},
                               :val (lit 1)}}}}
   {:type :stream/make, :buffer 1024}
   (lit (first {:a 1}))])


(deftest semantic-bytecode-corpus-covers-every-tag
  (is (= (set (keys vm/semantic-bytecode-grammar))
         (into #{}
               (comp (mapcat (comp vals :rows vm/ast->semantic-bytecode))
                     (map second))
               semantic-bytecode-corpus))))


(deftest semantic-bytecode-round-trip-law
  (doseq [ast semantic-bytecode-corpus]
    (let [bc (vm/ast->semantic-bytecode ast)]
      (is (= ast (vm/semantic-bytecode->ast bc)) "map -> rows -> map")
      (is (= bc (vm/ast->semantic-bytecode (vm/semantic-bytecode->ast bc)))
          "rows -> map -> rows"))))


(deftest semantic-bytecode-list-payloads-mint-no-metadata
  ;; ClojureDart's list mints its result carrying cljd.core's own reader
  ;; metadata; a projected list must come out metadata-free whether or not
  ;; its input carried reader positions, or re-projection changes its address
  (doseq [v ['[1 (2 3) #{4}] [1 (seq [2 3]) #{4}]]]
    (let [{:keys [root rows]} (vm/ast->semantic-bytecode (lit v))
          value (nth (get rows root) 2)]
      (is (= v value))
      (is (nil? (meta (second value))) (pr-str v)))))


(deftest semantic-bytecode-row-shape
  (testing "the §2.1 example projects to [id tag & slots] with child ids"
    (let [ast {:type :lambda,
               :params '[x],
               :body (app (local '+) [(local 'x) (lit 1)] true)}
          {:keys [root rows]} (vm/ast->semantic-bytecode ast)
          [a tag params b] (get rows root)
          [_ _ c [d e] tail?] (get rows b)]
      (is (= [root :lambda '[x]] [a tag params]))
      (is (= :application (second (get rows b))))
      (is (true? tail?))
      (is (= '[:variable +] (subvec (get rows c) 1)))
      (is (= '[:variable x] (subvec (get rows d) 1)))
      (is (= [:literal 1] (subvec (get rows e) 1)))
      (is (= 5 (count rows)))
      (doseq [[id row] rows]
        (is (= id (first row)))
        (is (= "segment" (namespace id)))))))


(deftest semantic-bytecode-structural-sharing
  (let [ast {:type :if,
             :test (lit 1),
             :consequent (app (local 'f) [(lit 1)] true),
             :alternate (lit 1)}
        {:keys [root rows], :as bc} (vm/ast->semantic-bytecode ast)
        [_ _ test-id cons-id alt-id] (get rows root)
        [_ _ _ [operand-id] _] (get rows cons-id)]
    (is (= test-id alt-id operand-id)
        "independently built identical subtrees mint one id")
    (is (= 1 (count (filter #(= :literal (second %)) (vals rows)))))
    (is (= 4 (count rows)) ":if, :application, :variable, one :literal")
    (is (= ast (vm/semantic-bytecode->ast bc)))))


(deftest semantic-bytecode-strips-and-saturates
  (is (= (vm/ast->semantic-bytecode (lit 1))
         (vm/ast->semantic-bytecode
           {:type :literal, :value 1, :tail? true, :eid -5, :yang/pos [1 2]})))
  (is (= (vm/ast->semantic-bytecode (app (local 'f) [] false))
         (vm/ast->semantic-bytecode {:type :application,
                                     :operator (local 'f)})))
  (is (= (vm/ast->semantic-bytecode {:type :lambda,
                                     :params '[x],
                                     :body (lit 1)})
         (vm/ast->semantic-bytecode {:type :lambda,
                                     :params '[x],
                                     :macro? true,
                                     :body (lit 1)})))
  (is (= {:type :vm/gensym, :prefix "id"}
         (vm/semantic-bytecode->ast (vm/ast->semantic-bytecode
                                      {:type :vm/gensym}))))
  (is (= {:type :stream/make, :buffer vm/default-stream-capacity}
         (vm/semantic-bytecode->ast (vm/ast->semantic-bytecode
                                      {:type :stream/make})))))


(deftest semantic-bytecode-reconstruction-validates
  (let [{:keys [root rows], :as bc}
        (vm/ast->semantic-bytecode (app (local 'f) [(lit 1)] true))
        [_ _ op-id _ _] (get rows root)
        reroot (fn [row]
                 (let [id (jing/segment-key (subvec row 1))]
                   {:root id, :rows (assoc rows id (assoc row 0 id))}))]
    (is (= "Unknown AST node type"
           (error-message #(vm/ast->semantic-bytecode {:type :yin/macro-expand}))))
    (testing ":content-address"
      (is (= :content-address
             (error-rule #(vm/semantic-bytecode->ast
                            (assoc-in bc [:rows op-id 2] 'g)))))
      (is (= :content-address
             (error-rule #(vm/semantic-bytecode->ast
                            (assoc-in bc [:rows (:root bc)] (get rows op-id)))))))
    (testing ":id-resolves"
      (is (= :id-resolves
             (error-rule #(vm/semantic-bytecode->ast (update bc :rows dissoc op-id)))))
      (is (= :id-resolves
             (error-rule #(vm/semantic-bytecode->ast (assoc bc :root :segment/sha256-0))))))
    (testing ":tag, :arity, :slot-kind, :saturation"
      (is (= :tag (error-rule #(vm/semantic-bytecode->ast (reroot [nil :nope])))))
      (is (= :arity
             (error-rule #(vm/semantic-bytecode->ast (reroot [nil :literal 1 2])))))
      (is (= :slot-kind
             (error-rule #(vm/semantic-bytecode->ast
                            (reroot [nil :application op-id op-id false])))))
      (is (= :saturation
             (error-rule #(vm/semantic-bytecode->ast (reroot [nil :vm/gensym nil]))))))))


(defn- rerooted
  "A correctly hashed `body` added to `rows` as the new root."
  [rows body]
  (let [id (jing/segment-key body)]
    {:root id, :rows (assoc rows id (into [id] body))}))


(deftest semantic-bytecode-lambda-params-are-syms
  (is (= [:params :syms] (first (:lambda vm/semantic-bytecode-grammar))))
  (let [{:keys [root rows]} (vm/ast->semantic-bytecode
                              {:type :lambda, :params '[x], :body (lit 1)})
        body-id (nth (get rows root) 3)]
    (testing "a hashed [:lambda nil body] is refused, not re-projected as []"
      (is (= :slot-kind
             (error-rule #(vm/semantic-bytecode->ast
                            (rerooted rows [:lambda nil body-id]))))))
    (is (= :slot-kind
           (error-rule #(vm/semantic-bytecode->ast
                          (rerooted rows [:lambda '(x) body-id])))))
    (is (= :slot-kind
           (error-rule #(vm/semantic-bytecode->ast
                          (rerooted rows [:lambda [:x] body-id])))))
    (testing "a hashed params vector with metadata is refused, since projection
              would mint it without that metadata at another address"
      (is (= :slot-kind
             (error-rule #(vm/semantic-bytecode->ast
                            (rerooted rows [:lambda (with-meta '[x] {:purpose 1})
                                            body-id]))))))
    (testing "params lose reader metadata and become a vector"
      (let [bc (vm/ast->semantic-bytecode
                 {:type :lambda,
                  :params (list (with-meta 'x {:line 3, :tag 'long})),
                  :body (lit 1)})
            params (nth (get (:rows bc) (:root bc)) 2)]
        (is (vector? params))
        (is (nil? (meta (first params))))
        (is (= root (:root bc)))))))


(deftest semantic-bytecode-bool-slot-is-validated
  (let [{:keys [root rows]} (vm/ast->semantic-bytecode (app (local 'f) [] false))
        [_ _ op-id operand-ids] (get rows root)]
    (is (= :slot-kind
           (error-rule #(vm/semantic-bytecode->ast
                          (rerooted rows [:application op-id operand-ids
                                          :not-a-bool])))))))


(deftest semantic-bytecode-sharing-refuses-to-merge-distinct-metadata
  (let [literal-if (fn [a b]
                     {:type :if, :test (lit true), :consequent (lit a),
                      :alternate (lit b)})]
    (testing "retained metadata the encoder does not hash is a loud collision"
      (is (= "Semantic bytecode address collision"
             (error-message #(vm/ast->semantic-bytecode
                               (literal-if (with-meta 'x {:meaning 1})
                                           (with-meta 'x {:meaning 2}))))))
      (is (= "Semantic bytecode address collision"
             (error-message #(vm/ast->semantic-bytecode
                               (literal-if [(with-meta 'x {:meaning 1})]
                                           [(with-meta 'x {:meaning 2})]))))))
    (testing "equal metadata still shares one row and survives the round trip"
      (let [ast (literal-if (with-meta 'x {:meaning 1})
                            (with-meta 'x {:meaning 1}))
            bc (vm/ast->semantic-bytecode ast)
            back (vm/semantic-bytecode->ast bc)]
        (is (= 3 (count (:rows bc))))
        (is (= {:meaning 1} (meta (get-in back [:consequent :value]))))
        (is (= {:meaning 1} (meta (get-in back [:alternate :value]))))))
    (testing "names are not retained data: their metadata never collides"
      (is (= 3 (count (:rows (vm/ast->semantic-bytecode
                               {:type :if, :test (lit true),
                                :consequent (local (with-meta 'x {:m 1})),
                                :alternate (local (with-meta 'x {:m 2}))}))))))))


(deftest semantic-bytecode-strips-reader-positions-inside-payloads
  (let [pos {:line 77, :column 9, :end-line 77, :end-column 10}
        slot (fn [ast i]
               (let [{:keys [root rows]} (vm/ast->semantic-bytecode ast)]
                 (nth (get rows root) i)))]
    (is (nil? (meta (slot (lit (with-meta 'x pos)) 2))))
    (is (nil? (meta (first (slot (lit [(with-meta 'x pos)]) 2)))))
    (is (nil? (meta (first (slot (lit [(with-meta [1] pos)]) 2)))))
    (is (nil? (meta (get (slot (lit {:k (with-meta 'x pos)}) 2) :k))))
    (is (nil? (meta (slot {:type :vm/store-put, :key (with-meta 'k pos), :val 1}
                          2))))
    (testing "semantic metadata beside the position is retained"
      (is (= {:meaning 1}
             (meta (first (slot (lit [(with-meta 'x (assoc pos :meaning 1))])
                                2))))))
    (testing "collection types and values are unchanged"
      (let [v (slot (lit [(list 1 2) #{3} {:a [4]}]) 2)]
        (is (= [(list 1 2) #{3} {:a [4]}] v))
        (is (list? (first v)))
        (is (set? (second v)))))
    (testing "a map entry payload stays the vector [k v]"
      (let [v (slot (lit (first {:a 1})) 2)
            bc (vm/ast->semantic-bytecode (lit (first {:a 1})))]
        (is (vector? v))
        (is (= [:a 1] v))
        (is (= (:root (vm/ast->semantic-bytecode (lit [:a 1]))) (:root bc)))
        (is (= [:a 1] (:value (vm/semantic-bytecode->ast bc))))))))


(deftest semantic-bytecode-compares-metadata-inside-metadata
  (let [branch (fn [meaning]
                 (with-meta [] {:note (with-meta 'x {:meaning meaning})}))
        compiled-if (fn [a b] (yang/compile (list 'if true a b)))]
    (testing "(if true ^{:note ^{:meaning 1} x} [] ^{:note ^{:meaning 2} x} [])
              is a loud collision, not a silent merge"
      (is (= "Semantic bytecode address collision"
             (error-message #(vm/ast->semantic-bytecode
                               (compiled-if (branch 1) (branch 2)))))))
    (testing "equal nested metadata still shares one row and round-trips"
      (let [back (vm/semantic-bytecode->ast
                   (vm/ast->semantic-bytecode (compiled-if (branch 1) (branch 1))))]
        (is (= {:meaning 1} (-> back :consequent :value meta :note meta)))
        (is (= {:meaning 1} (-> back :alternate :value meta :note meta)))))))


(deftest semantic-bytecode-strips-reader-positions-inside-metadata
  (let [value-of (fn [ast]
                   (let [{:keys [root rows]} (vm/ast->semantic-bytecode ast)]
                     (nth (get rows root) 2)))]
    (testing "positions on a metadata value are stripped; the note is kept"
      (let [v (value-of (lit (with-meta [] {:note (with-meta '(helper x)
                                                    {:line 1, :column 9})})))]
        (is (= '(helper x) (:note (meta v))))
        (is (nil? (meta (:note (meta v)))))))
    (testing "positions on the metadata map's own metadata are stripped"
      (let [v (value-of (lit (with-meta [] (with-meta {:note 1}
                                             {:line 3, :k 1}))))]
        (is (= {:note 1} (meta v)))
        (is (= {:k 1} (meta (meta v))))))
    (testing "an empty metadata map that carries metadata is kept"
      (let [ast (lit (with-meta [] (with-meta {} {:meaning 1})))
            v (value-of ast)
            back (:value (vm/semantic-bytecode->ast
                           (vm/ast->semantic-bytecode ast)))]
        (is (= {:meaning 1} (meta (meta v))))
        (is (= {:meaning 1} (meta (meta back))))))))


#?(:cljd nil
   :clj
   (deftest semantic-bytecode-strips-reader-generated-positions-in-metadata
     (let [form (read (clojure.lang.LineNumberingPushbackReader.
                        (java.io.StringReader. "^{:note (helper x)} []")))
           _ (is (= {:line 1, :column 9} (select-keys (meta (:note (meta form)))
                                                      [:line :column]))
                 "the reader really attaches positions to the metadata value")
           bc (vm/ast->semantic-bytecode (yang/compile form))
           row-value (nth (get (:rows bc) (:root bc)) 2)
           back (:value (vm/semantic-bytecode->ast bc))]
       (is (nil? (meta (:note (meta row-value)))))
       (is (nil? (meta (:note (meta back)))))
       (is (= '(helper x) (:note (meta back)))))))


(deftest semantic-bytecode-nodes-vector-metadata-is-refused
  (let [{:keys [root rows]} (vm/ast->semantic-bytecode (app (local 'f) [] false))
        [_ _ op-id] (get rows root)]
    (is (= :slot-kind
           (error-rule #(vm/semantic-bytecode->ast
                          (rerooted rows [:application op-id
                                          (with-meta [] {:purpose 1}) false])))))
    (is (= :slot-kind
           (error-rule #(vm/semantic-bytecode->ast
                          (rerooted rows [:dao.stream.apply/call :op/add
                                          (with-meta [op-id] {:purpose 1})])))))))
