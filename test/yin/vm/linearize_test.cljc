(ns yin.vm.linearize-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.linearize :as linearize]
            [yin.vm.malformed-rows :as malformed]
            [yin.vm.parity-test :as parity]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as test-utils]))


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


(defn- throws-ex-data
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         (or (ex-data e) {}))))


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
   ;; §2.4 saturation: both lanes must materialize the same defaults.
   :default-gensym {:type :vm/gensym},
   :default-buffer {:type :stream/make},
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

(defn- run-image
  "A reference reading of the §4.2 core transitions over an image:
   instruction maps in pc order with `:yin.code/target`/`:yin.code/body`
   already pc integers. It exists to check the lowering's evaluation
   order, not to stand in for the semantic VM."
  [image primitives]
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
                           :else (vm/primitive-function (get primitives s)))]
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
        :halt val))))


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
    (run-image image primitives)))


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


;; =============================================================================
;; Row-lane lowering: the canonical instruction vector (UCF §7.3.2)
;; =============================================================================

(def ^:private worked-example-vector
  "`((fn [x] (+ x 1)) 10)`, `[:closure params pc]` with the body's pc
   resolved, `[:call argc tail?]` unfolded (the decoder folds tail? into
   :tailcall), refs resolved, defaults saturated, no header."
  [[:closure '[x] 6]
   [:push]
   [:const 10]
   [:push]
   [:call 1 false]
   [:halt]
   [:var '+]
   [:push]
   [:var 'x]
   [:push]
   [:const 1]
   [:push]
   [:call 2 true]
   [:return]])


(def ^:private worked-example-paths
  "Each instruction's emitting node as a §2.5 structural path in row
   positions (id 0, tag 1, first slot 2 — the base of §2.5's `[2 [3 0]]`
   example, which `occurrences`' relation also uses): the root
   application at [], its operator :lambda at [2] (operator is the
   application's slot at row position 2) with its body at [2 3], the
   operands at `[[3 j]]` steps under the root and `[2 3 [3 j]]` under the
   body, and :return naming the lambda, as the datom lane's
   `(emit! lambda :return)` does."
  [[2] [] [[3 0]] [] [] [] [2 3 2] [2 3] [2 3 [3 0]] [2 3] [2 3 [3 1]]
   [2 3] [2 3] [2]])


(deftest lower-rows-worked-example-matches-the-design
  (is (= worked-example-vector
         (:vector (linearize/lower-rows (vm/ast->semantic-bytecode
                                          worked-example))))))


(deftest lower-rows-provenance-is-one-row-per-pc-keyed-by-occurrence
  (let [bc (vm/ast->semantic-bytecode worked-example)
        origin [:source :m :b 0]
        {:keys [vector provenance]} (linearize/lower-rows bc origin)]
    (is (= (mapv (fn [pc path] [pc origin (:root bc) path])
                 (range)
                 worked-example-paths)
           provenance)
        "one row per pc in pc order, origin verbatim, the tree's root id")
    (is (= (count vector) (count provenance)))
    (testing "a bare row set has no source occurrence"
      (is (nil? (nth (first (:provenance (linearize/lower-rows bc))) 1)))
      (is (= :origin
             (:rule (throws-ex-data
                      #(linearize/lower-rows bc {:batch 7}))))))))


(deftest lower-rows-rejects-incomplete-source-occurrence-identities
  (let [bc (vm/ast->semantic-bytecode worked-example)]
    (doseq [origin [[:source :medium :batch]
                    [:source :medium :batch -1]
                    [:source nil :batch 0]
                    [:source :medium nil 0]
                    [:expansion :not-an-address]
                    {:source :medium}]]
      (is (= :origin
             (:rule (throws-ex-data #(linearize/lower-rows bc origin))))
          (pr-str origin)))))


(deftest lower-envelope-stamps-the-selected-member-source-origin
  (let [bc (vm/ast->semantic-bytecode worked-example)
        other (vm/ast->semantic-bytecode {:type :literal, :value 1})
        envelope {:yin/source-medium :medium
                  :yin/batch-token "token"
                  :yin/batch [other bc]
                  :yin/root 1}
        {:keys [vector provenance origin]} (linearize/lower-envelope envelope)]
    (is (= [:source :medium "token" 1] origin))
    (is (= (:vector (linearize/lower-rows bc)) vector)
        "occurrence identity never changes the lowered content")
    (is (= (count vector) (count provenance)))
    (is (every? #(= [origin (:root bc)] [(nth % 1) (nth % 2)]) provenance)
        "every pc maps back to the admitted member's occurrence")
    (is (= :batch-envelope
           (:rule (throws-ex-data
                    #(linearize/lower-envelope (assoc envelope :yin/root 2))))))))


(defn- path-row
  "The row id a §2.5 structural path names, walked through `node`/`nodes`
   slots by the §2.3 table's row positions (id 0, tag 1, first slot 2):
   a node slot is its row position, a nodes item a `[position i]` pair —
   the same convention as `occurrences`' relation, which provenance joins."
  [{:keys [root rows]} path]
  (reduce (fn [id step]
            (let [slot (fn [pos] (nth (get rows id) pos))]
              (if (vector? step)
                (nth (slot (first step)) (second step))
                (slot step))))
          root
          path))


(def ^:private emitting-tag
  "The §2.3 tag each directly-emitted mnemonic lowers from; the structural
   mnemonics (:push :jump :branch-false :halt :return) name no single tag."
  {:const :literal,
   :var :variable,
   :closure :lambda,
   :call :application,
   :ffi-call :dao.stream.apply/call,
   :gensym :vm/gensym,
   :store-get :vm/store-get,
   :store-put :vm/store-put,
   :park :vm/park,
   :resume :vm/resume,
   :current-continuation :vm/current-continuation,
   :stream-make :stream/make,
   :stream-put :stream/put,
   :stream-cursor :stream/cursor,
   :stream-next :stream/next,
   :stream-close :stream/close})


(deftest lower-rows-provenance-paths-resolve-to-the-emitting-node
  (doseq [[label ast] (into corpus (map (fn [[n a _]] [n a]) parity/corpus))]
    (testing label
      (let [bc (vm/ast->semantic-bytecode ast)
            {:keys [vector provenance]} (linearize/lower-rows bc)
            occ (vm/occurrences bc)]
        (is (= (count vector) (count provenance)))
        (doseq [[pc _origin root path] provenance
                :let [mnem (nth (nth vector pc) 0)
                      id (path-row bc path)]]
          (is (contains? (:rows bc) id)
              (str "path resolves at pc " pc))
          (is (contains? occ [root path id])
              (str "the §5.3 join key [root path node] is an occurrence"))
          (when-let [tag (get emitting-tag mnem)]
            (is (= tag (nth (get (:rows bc) id) 1))
                (str mnem " at pc " pc " is emitted by a " tag))))))))


(defn- decoded-image
  "The image `semantic/load-vector` (U5) decodes a canonical vector to."
  [v]
  (let [svm (semantic/load-vector (semantic/create-vm) v)]
    (get-in svm [:code (:program svm)])))


(deftest lower-rows-vector-decodes-to-the-datom-path-image
  (doseq [[label ast] (into corpus (map (fn [[n a _]] [n a]) parity/corpus))]
    (testing label
      (let [image (semantic/load-image (linearize/lower (vm/ast->datoms ast)))
            bc (vm/ast->semantic-bytecode ast)
            {:keys [vector]} (linearize/lower-rows bc)
            decoded (decoded-image vector)]
        (is (= (:length image) (:length decoded)))
        (is (= (:code image) (:code decoded))
            "the row lane's canonical vector loads (U5's load-vector) to
             the same image the datom lane's load-image builds")
        (is (= vector
               (:vector (linearize/lower-rows
                          (vm/ast->semantic-bytecode
                            (vm/semantic-bytecode->ast bc)))))
            "the vector is a function of the canonical rows: rows -> map
             -> rows lowers identically")))))


(deftest lower-rows-rejects-malformed-row-sets
  (doseq [[name [_expected bc]] malformed/malformed-row-sets]
    (testing name
      ;; `Object` on Dart as the house idiom: cljd resolves no
      ;; ExceptionInfo type for a typed catch.
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo
                      :cljd Object)
            (linearize/lower-rows bc))))))


(defn- vector-image
  "The map shape `run-image` reads, from a canonical instruction vector:
   only the mnemonics the reference loop executes, which is exactly the
   def-program vocabulary."
  [v]
  (mapv (fn [t]
          (case (nth t 0)
            :const {:yin.code/op :const, :yin.code/value (nth t 1)}
            :var {:yin.code/op :var, :yin.code/name (nth t 1)}
            :closure {:yin.code/op :closure,
                      :yin.code/params (nth t 1),
                      :yin.code/body (nth t 2)}
            :call {:yin.code/op :call,
                   :yin.code/argc (nth t 1),
                   :yin.code/tail? (nth t 2)}
            :jump {:yin.code/op :jump, :yin.code/target (nth t 1)}
            :branch-false {:yin.code/op :branch-false,
                           :yin.code/target (nth t 1)}
            {:yin.code/op (nth t 0)}))
        v))


(deftest lower-rows-evaluation-order-matches-the-walker
  (let [walker-log (atom [])
        walker (vm/eval (test-utils/create-vm
                          {:primitives (recording-primitives walker-log)})
                        def-program)
        row-log (atom [])
        result (run-image (vector-image
                            (:vector (linearize/lower-rows
                                       (vm/ast->semantic-bytecode def-program))))
                          (recording-primitives row-log))]
    (is (= @walker-log @row-log))
    (is (= (vm/value walker) result 11))))
