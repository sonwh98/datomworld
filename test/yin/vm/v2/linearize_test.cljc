(ns yin.vm.v2.linearize-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.code :as code]
            [yin.vm.v2.linearize :as linearize]
            [yin.vm.v2.test-utils :as test-utils]))


;; =============================================================================
;; AST fixtures
;; =============================================================================

(defn- lit
  [v]
  {:type :literal, :value v})


(defn- v
  [s]
  {:type :variable, :name s})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- tail
  [node]
  (assoc node :tail? true))


(defn- if-node
  [t c a]
  {:type :if, :test t, :consequent c, :alternate a})


(defn- def!
  [k val]
  (app (v 'yin/def) (lit k) (if (map? val) val (lit val))))


(def ^:private worked-example
  "`((fn [x] (+ x 1)) 10)`, yin.vm.semantic.md §2.7."
  (app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10)))


(def ^:private corpus
  {:worked-example worked-example,
   :literal (lit 42),
   :nil-literal (lit nil),
   :zero-arity-call (app (lam [] (lit 1))),
   :nested-lambdas (app (app (lam '[a] (tail (lam '[b] (tail (app (v '+) (v 'a) (v 'b))))))
                             (lit 1))
                        (lit 2)),
   :if-in-tail (app (lam '[n] (if-node (app (v '<) (v 'n) (lit 1))
                                       (lit :done)
                                       (tail (app (v '-) (v 'n) (lit 1)))))
                    (lit 3)),
   :nested-if (if-node (if-node (lit true) (lit false) (lit true))
                       (lit :a)
                       (if-node (lit nil) (lit :b) (lit :c))),
   :streams {:type :stream/put,
             :target {:type :stream/make, :buffer 4},
             :val {:type :stream/next,
                   :source {:type :stream/cursor,
                            :source {:type :stream/close,
                                     :source (lit nil)}}}},
   :ffi {:type :dao.stream.apply/call, :op :op/echo, :operands [(lit 1) (lit 2)]},
   :ffi-no-args {:type :dao.stream.apply/call, :op :op/ping, :operands []},
   :store-and-control (app (lam '[a b c d]
                                (tail (app (v 'vector) (v 'a) (v 'b) (v 'c) (v 'd))))
                           {:type :vm/store-put, :key 'k, :val 5}
                           {:type :vm/store-get, :key 'k}
                           {:type :vm/gensym, :prefix "g"}
                           {:type :vm/current-continuation}),
   :park-resume (if-node (lit false)
                         {:type :vm/park}
                         {:type :vm/resume, :parked-id :p1, :val (lit 7)})})


;; =============================================================================
;; Reading lowered output
;; =============================================================================

(defn- entities
  [datoms]
  (reduce (fn [acc [e a val]] (assoc-in acc [e a] val)) {} datoms))


(defn- instructions
  "Instruction entities in pc order, each with its eid under :e."
  [code-datoms]
  (->> (entities code-datoms)
       (keep (fn [[e attrs]] (when (:yin.code/op attrs) (assoc attrs :e e))))
       (sort-by :yin.code/pc)
       vec))


(defn- segment
  [code-datoms]
  (let [[[e attrs]] (filter #(= :segment (:yin.code/type (val %)))
                            (entities code-datoms))]
    (assoc attrs :e e)))


(defn- lowered
  "An AST, its datoms and root, and the code lowered from those datoms."
  [ast]
  (let [[root ast-datoms] (vm/ast->datoms-with-root ast)]
    {:root root, :ast-datoms ast-datoms, :code (linearize/lower ast-datoms)}))


;; =============================================================================
;; Structural contract
;; =============================================================================

(deftest lowered-segments-are-well-formed
  (doseq [[label ast] corpus]
    (testing label
      (is (nil? (code/well-formed? (:code (lowered ast))))))))


(deftest pcs-are-dense
  (doseq [[label ast] corpus]
    (testing label
      (let [code (:code (lowered ast))
            insts (instructions code)
            seg (segment code)]
        (is (= 1 (count (filter #(= :yin.code/type (second %)) code))))
        (is (= (count insts) (:yin.code/length seg)))
        (is (= (range (:yin.code/length seg)) (map :yin.code/pc insts)))
        (is (= (map :yin.code/pc insts)
               (keep #(when (= :yin.code/pc (second %)) (nth % 2)) code))
            "the batch is emitted in pc order")))))


(deftest every-ref-resolves
  (doseq [[label ast] corpus]
    (testing label
      (let [{:keys [root ast-datoms code]} (lowered ast)
            ast-nodes (set (keep #(when (= :yin/type (second %)) (first %))
                                 ast-datoms))
            insts (instructions code)
            inst-eids (set (map :e insts))
            seg (segment code)]
        (is (= root (:yin.code/derived-from seg)))
        (doseq [inst insts]
          (is (= (:e seg) (:yin.code/segment inst)))
          (doseq [attr [:yin.code/target :yin.code/body]
                  :when (contains? inst attr)]
            (is (contains? inst-eids (get inst attr))
                (str attr " at pc " (:yin.code/pc inst))))
          (is (contains? ast-nodes (:yin.code/source inst))
              (str "source at pc " (:yin.code/pc inst))))
        (is (empty? (filter (set (map first ast-datoms))
                            (conj inst-eids (:e seg))))
            "code tempids do not collide with AST entities")))))


(deftest every-instruction-has-a-source
  (doseq [[label ast] corpus]
    (testing label
      (let [code (:code (lowered ast))]
        (is (every? #(contains? % :yin.code/source) (instructions code)))))))


(deftest worked-example-matches-the-design
  (let [{:keys [ast-datoms code]} (lowered worked-example)
        insts (instructions code)
        pc-of (into {} (map (juxt :e :yin.code/pc) insts))
        types (into {} (keep #(when (= :yin/type (second %)) [(first %) (nth % 2)])
                             ast-datoms))]
    (is (= [:closure :push :const :push :call :halt
            :var :push :var :push :const :push :call :return]
           (map :yin.code/op insts)))
    (is (= 6 (pc-of (:yin.code/body (nth insts 0)))))
    (is (= '[x] (:yin.code/params (nth insts 0))))
    (is (= [10 1] [(:yin.code/value (nth insts 2)) (:yin.code/value (nth insts 10))]))
    (is (= ['+ 'x] [(:yin.code/name (nth insts 6)) (:yin.code/name (nth insts 8))]))
    (is (= [1 false] ((juxt :yin.code/argc :yin.code/tail?) (nth insts 4))))
    (is (= [2 true] ((juxt :yin.code/argc :yin.code/tail?) (nth insts 12))))
    (is (= :application (types (:yin.code/source (nth insts 4)))))
    (is (= :lambda (types (:yin.code/source (nth insts 13)))))))


(deftest if-lowers-to-branch-and-jump
  (let [insts (instructions (linearize/lower-ast
                              (if-node (lit true) (lit :yes) (lit :no))))
        pc-of (into {} (map (juxt :e :yin.code/pc) insts))]
    (is (= [:const :branch-false :const :jump :const :halt]
           (map :yin.code/op insts)))
    (is (= 4 (pc-of (:yin.code/target (nth insts 1)))) "else label")
    (is (= 5 (pc-of (:yin.code/target (nth insts 3)))) "end label")))


(deftest options-set-t-and-segment-id
  (let [code (linearize/lower-ast (lit 1) {:t 7, :id-start -500})]
    (is (every? #(= 7 (nth % 3)) code))
    (is (= -500 (:e (segment code))))
    (is (= [-501 -502] (map :e (instructions code))))))


(defn- thrown
  [f]
  (try (f) nil (catch #?(:cljd Object :clj Exception :cljs :default) e e)))


(deftest id-start-is-validated-over-the-whole-range
  (let [[root ast-datoms] (vm/ast->datoms-with-root (lit 1))]
    (testing "a range reaching an input entity"
      (let [e (thrown #(linearize/lower ast-datoms {:id-start (inc root)}))]
        (is (= [root] (:collisions (ex-data e))))))
    (testing "the segment id itself colliding"
      (is (= [root] (:collisions (ex-data (thrown #(linearize/lower ast-datoms
                                                                    {:id-start root})))))))
    (testing "non-negative, non-integer, or falsey"
      (doseq [bad [1 0 -1.5 :k false nil]]
        (is (= {:id-start bad}
               (ex-data (thrown #(linearize/lower ast-datoms {:id-start bad}))))
            (str bad))))
    (testing "a clear range is accepted"
      (is (= -100 (:e (segment (linearize/lower ast-datoms {:id-start -100}))))))
    (testing "a supplied :t is validated even when falsey"
      (doseq [bad [false nil :k]]
        (is (= {:t bad} (ex-data (thrown #(linearize/lower ast-datoms {:t bad}))))
            (str bad))))))


(def ^:private min-safe-id -9007199254740991)


(deftest id-range-stays-within-host-safe-integers
  ;; `(lit 1)` lowers to two instructions, :const and :halt.
  (let [ast-datoms (vm/ast->datoms (lit 1))]
    (testing "a range ending exactly at the safe bound is accepted and distinct"
      (let [code (linearize/lower ast-datoms {:id-start (+ min-safe-id 2)})
            ids (cons (:e (segment code)) (map :e (instructions code)))]
        (is (= [(+ min-safe-id 2) (+ min-safe-id 1) min-safe-id] ids))
        (is (apply distinct? ids))))
    (testing "a range crossing the bound is rejected"
      (doseq [bad [(+ min-safe-id 1) min-safe-id (dec min-safe-id)]]
        (is (= {:id-start bad, :length 2, :min-safe-id min-safe-id}
               (ex-data (thrown #(linearize/lower ast-datoms {:id-start bad}))))
            (str bad)))))
  (testing "the default range below an input entity at the bound is rejected"
    (let [e min-safe-id
          datoms [[e :yin/type :literal 0 :db/add]
                  [e :yin/value 1 0 :db/add]
                  [e :yin/root true 0 :db/add]]]
      (is (= (dec min-safe-id)
             (:id-start (ex-data (thrown #(linearize/lower datoms)))))))))


(deftest literals-are-opaque-data
  (doseq [type [:vm/store-update :yin/macro-expand]]
    (testing type
      (let [ast (lit {:type type, :key 'k})
            code (linearize/lower-ast ast)]
        (is (= (linearize/lower (vm/ast->datoms ast)) code))
        (is (= {:type type, :key 'k}
               (:yin.code/value (first (instructions code)))))))))


(deftest host-values-are-rejected-naming-the-node
  (testing "nested in a literal"
    (let [[root ast-datoms] (vm/ast->datoms-with-root (lit {:nested [inc]}))
          e (thrown #(linearize/lower ast-datoms))]
      (is (= root (:node (ex-data e))))
      (is (= :yin.code/value (:attr (ex-data e))))))
  (testing "in a stored value"
    (let [e (thrown #(linearize/lower-ast {:type :vm/store-put, :key 'k, :val #{inc}}))]
      (is (= :yin.code/value (:attr (ex-data e))))
      (is (some? (:node (ex-data e))))))
  (testing "in a literal's metadata, nested anywhere"
    (doseq [value [(with-meta {:x 1} {:host inc})
                   {:x (with-meta [1] {:nested {:host inc}})}
                   (with-meta {:x 1} (with-meta {:k 1} {:host inc}))]]
      (let [[root ast-datoms] (vm/ast->datoms-with-root (lit value))
            e (thrown #(linearize/lower ast-datoms))]
        (is (= root (:node (ex-data e))))
        (is (= :yin.code/value (:attr (ex-data e)))))))
  (testing "plain metadata is carried"
    (let [value (with-meta {:x 1} {:line 3})]
      (is (= {:line 3}
             (meta (:yin.code/value (first (instructions (linearize/lower-ast
                                                           (lit value))))))))))
  (testing "a primitive is named by symbol instead"
    (is (= '+ (:yin.code/name (first (instructions (linearize/lower-ast (v '+)))))))))


(deftest unsupported-nodes-are-rejected-naming-the-node
  (testing "from an AST map, nested anywhere"
    (doseq [type [:vm/store-update :yin/macro-expand]]
      (let [bad {:type type, :key 'k}
            e (try (linearize/lower-ast (app (lam '[x] (v 'x)) bad))
                   nil
                   (catch #?(:cljd Object :clj Exception :cljs :default) e e))]
        (is (some? e))
        (is (= type (:type (ex-data e))))
        (is (= bad (:node (ex-data e)))))))
  (testing "from AST datoms, naming the entity"
    (doseq [type [:vm/store-update :yin/macro-expand]]
      (let [datoms [[-20 :yin/type type 0 :db/add]
                    [-21 :yin/type :application 0 :db/add]
                    [-21 :yin/operator -20 0 :db/add]
                    [-21 :yin/operands [] 0 :db/add]
                    [-21 :yin/root true 0 :db/add]]
            e (try (linearize/lower datoms)
                   nil
                   (catch #?(:cljd Object :clj Exception :cljs :default) e e))]
        (is (= {:type type, :node -20} (ex-data e))))))
  (testing "an unknown or missing node type"
    (let [e (try (linearize/lower [[-20 :yin/root true 0 :db/add]
                                   [-20 :yin/type :no/such 0 :db/add]])
                 nil
                 (catch #?(:cljd Object :clj Exception :cljs :default) e e))]
      (is (= {:type :no/such, :node -20} (ex-data e))))))


(deftest ast-loader-lowers-before-loading
  (let [ast-datoms (vm/ast->datoms worked-example)
        load (linearize/ast-loader (fn [vm code-datoms] (assoc vm :code code-datoms)))]
    (is (= (linearize/lower ast-datoms) (:code (load {} ast-datoms))))))


;; =============================================================================
;; Evaluation order against the walker
;; =============================================================================

(defn- run-lowered
  "A reference reading of the §4.2 core transitions over a lowered segment:
   const var closure push call return jump branch-false halt, with
   `:vm/store-put` effects applied to the store. It exists to check the
   lowering's evaluation order, not to stand in for the semantic VM."
  [code-datoms primitives]
  (let [insts (instructions code-datoms)
        pc-of (into {} (map (juxt :e :yin.code/pc) insts))
        image (mapv #(cond-> %
                       (:yin.code/target %) (update :yin.code/target pc-of)
                       (:yin.code/body %) (update :yin.code/body pc-of))
                    insts)]
    (loop [pc 0
           val nil
           st []
           env {}
           store {}
           k []]
      (let [i (nth image pc)]
        (case (:yin.code/op i)
          :const (recur (inc pc) (:yin.code/value i) st env store k)
          :var (let [s (:yin.code/name i)
                     x (cond (contains? env s) (get env s)
                             (contains? store s) (get store s)
                             :else (get primitives s))]
                 (recur (inc pc) x st env store k))
          :closure (recur (inc pc)
                          {:type :closure,
                           :params (:yin.code/params i),
                           :entry (:yin.code/body i),
                           :env env}
                          st env store k)
          :push (recur (inc pc) val (conj st val) env store k)
          :jump (recur (:yin.code/target i) val st env store k)
          :branch-false (recur (if val (inc pc) (:yin.code/target i)) val st env store k)
          :call (let [base (- (count st) (:yin.code/argc i) 1)
                      f (nth st base)
                      args (subvec st (inc base))
                      st' (subvec st 0 base)]
                  (if (and (map? f) (= :closure (:type f)))
                    (recur (:entry f)
                           val
                           st'
                           (merge (:env f) (zipmap (:params f) args))
                           store
                           (if (:yin.code/tail? i)
                             k
                             (conj k {:pc (inc pc), :env env, :base base})))
                    (let [r (apply f args)]
                      (if (and (map? r) (= :vm/store-put (:effect r)))
                        (recur (inc pc) (:val r) st' env (assoc store (:key r) (:val r)) k)
                        (recur (inc pc) r st' env store k)))))
          :return (if-let [frame (peek k)]
                    (recur (:pc frame) val (subvec st 0 (:base frame)) (:env frame)
                           store (pop k))
                    val)
          :halt val)))))


(defn- recording-primitives
  [log]
  (assoc vm/primitives
         'yin/def (fn [key val]
                    (swap! log conj [key val])
                    {:effect :vm/store-put, :key key, :val val})))


(def ^:private def-program
  "`yin/def` side effects in operator position, in operands, in an `if` test
   and both arms, in a closure body run later, in a tail call, and read back
   through the store."
  (app (if-node (def! 'pick true)
                (lam '[f x]
                     (tail (app (v 'f)
                                (def! 'c (v 'x))
                                (def! 'd (app (v '+) (v 'b) (lit 1))))))
                (lam '[f x] (def! 'never 0)))
       (lam '[p q]
            (if-node (def! 'e false)
                     (def! 'never (v 'q))
                     (tail (app (v '+)
                                (def! 'f (v 'p))
                                (def! 'g (app (v '*) (v 'q) (lit 2)))))))
       (def! 'b (app (v '+) (def! 'a 1) (def! 'a 2)))))


(deftest evaluation-order-matches-the-walker
  (let [walker-log (atom [])
        walker (vm/eval (test-utils/create-vm {:primitives (recording-primitives
                                                             walker-log)})
                        def-program)
        lowered-log (atom [])
        result (run-lowered (linearize/lower-ast def-program)
                            (recording-primitives lowered-log))]
    (is (= '[[pick true] [a 1] [a 2] [b 3] [c 3] [d 4] [e false] [f 3] [g 8]]
           @walker-log)
        "the walker's order is the reference")
    (is (= @walker-log @lowered-log))
    (is (= (vm/value walker) result 11))))
