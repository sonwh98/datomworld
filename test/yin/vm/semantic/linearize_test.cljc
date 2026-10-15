(ns yin.vm.semantic.linearize-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.parity-test :as parity]
            [yin.vm.semantic :as sr]
            [yin.vm.test-utils :as tu]
            [yin.vm.malformed-rows :as malformed]
            [yin.vm.semantic.code :as code]
            [yin.vm.semantic.corpus :as corpus
             :refer [app def! if-node lam lit tail v]]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.ucf :as ucf]))


(defn- throws-ex-data
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         (or (ex-data e) {}))))


(defn- datom-lane
  [ast]
  (linearize/project-datoms (vm/ast->datoms ast)))


(deftest corpus-is-large-enough
  (is (<= 25 (count corpus/programs)))
  (is (= (set (map first corpus/programs)) (set (keys corpus/goldens)))))


(deftest row-lane-matches-the-goldens
  (doseq [[n ast] corpus/programs]
    (testing n
      (let [{:keys [vector address]} (linearize/project ast)
            golden (get corpus/goldens n)]
        (is (= (:vector golden) vector))
        (is (= (:address golden) address))))))


(deftest datom-lane-matches-the-goldens
  (doseq [[n ast] corpus/programs]
    (testing n
      (let [{:keys [vector address]} (datom-lane ast)
            golden (get corpus/goldens n)]
        (is (= (:vector golden) vector))
        (is (= (:address golden) address))))))


(deftest golden-addresses-are-the-code-address-on-this-host
  (doseq [[n {:keys [vector address]}] corpus/goldens]
    (testing n
      (is (= address (ucf/code-address vector))
          "the canonical bytes hash identically on every host"))))


(deftest worked-example-is-byte-for-byte-the-design
  ;; §3.3 item 2: rd(f-call)=0, rd(f)=1, rd(g-call)=2, rd(g)=3, rd(x)=4,
  ;; rd(y)=5, emitted in evaluation order.
  (let [expected '[[:var 1 f] [:var 3 g] [:var 4 x] [:call 2 3 [4] false]
                   [:var 5 y] [:call 0 1 [2 5] false] [:halt 0]]
        {:keys [vector address]} (linearize/project
                                   (app (v 'f) (app (v 'g) (v 'x)) (v 'y)))]
    (is (= expected vector))
    (is (= (ucf/code-address expected) address))
    (is (= '[[:var 1 f] [:var 3 g] [:var 4 x] [:call 2 3 [4] false]
             [:var 5 y] [:call 0 1 [2 5] true] [:halt 0]]
           (:vector (linearize/project
                      (tail (app (v 'f) (app (v 'g) (v 'x)) (v 'y))))))
        "the tail flag is copied from the AST, nothing else moves")))


(deftest every-production-is-covered
  (let [ops (set (mapcat (fn [[_ {:keys [vector]}]] (map first vector))
                         corpus/goldens))]
    (is (= code/mnemonics ops)
        "every §2.3 opcode, hence every §3.1 production, is in the corpus")))


(deftest goldens-are-well-formed
  (doseq [[n {:keys [vector]}] corpus/goldens]
    (testing n
      (is (nil? (code/well-formed? vector))))))


(deftest projection-is-deterministic
  (doseq [[n ast] corpus/programs]
    (testing n
      (is (= (linearize/project ast) (linearize/project ast)))
      (is (= (:vector (linearize/project ast))
             (:vector (linearize/project
                        (vm/semantic-bytecode->ast
                          (vm/ast->semantic-bytecode ast)))))
          "rows -> map -> rows projects identically"))))


(deftest saturation-materializes-defaults
  (is (= (linearize/project {:type :vm/gensym})
         (linearize/project {:type :vm/gensym, :prefix "id"})))
  (is (= (linearize/project {:type :stream/make})
         (linearize/project {:type :stream/make, :buffer vm/default-stream-capacity})))
  (is (= (linearize/project (app (v 'f)))
         (linearize/project (assoc (app (v 'f)) :tail? false))))
  (is (= '[[:gensym 0 "id"] [:halt 0]]
         (:vector (linearize/project-datoms
                    [[-1 :yin/type :vm/gensym 0 0] [-1 :yin/root true 0 0]])))
      "the datom lane saturates an omitted prefix"))


(deftest shared-occurrences-expand-positionally
  (let [ast (get (into {} corpus/programs) :shared-occurrence)
        bc (vm/ast->semantic-bytecode ast)
        {:keys [vector provenance]} (linearize/project-rows bc)]
    (is (nil? (nth (first provenance) 1)))
    (is (= 5 (count (:rows bc)))
        "+, g, 1, the root, and one (g 1) row: the shared node is one row")
    (is (= [[:call 2 3 [4] false] [:call 5 6 [7] false]]
           (mapv #(nth vector %) [3 6]))
        "the two occurrences of (g 1) lower twice, at two places")
    (is (= [[[3 0]] [[3 1]]]
           (mapv #(last (nth provenance %)) [3 6]))
        "the two occurrences carry distinct structural paths")))


(deftest provenance-is-one-row-per-pc
  (doseq [[n ast] corpus/programs]
    (testing n
      (let [bc (vm/ast->semantic-bytecode ast)
            origin [:source :medium :token 0]
            {:keys [vector provenance]} (linearize/project-rows bc origin)
            datoms (vm/ast->datoms ast)
            entities (set (map first datoms))
            d (linearize/project-datoms datoms)]
        (is (= (count vector) (count provenance) (count (:provenance d))))
        (is (= (range (count vector)) (map first provenance)))
        (is (every? #(= [origin (:root bc)] (subvec % 1 3)) provenance))
        (is (every? #(contains? entities (second %)) (:provenance d))
            "every datom-lane source names an AST entity")))))


(deftest bodies-are-out-of-line-in-fifo-order
  (let [{:keys [vector]} (get corpus/goldens :body-queue-order)]
    (is (= [4 8 10]
           (keep (fn [t] (when (= :closure (first t)) (nth t 3))) vector))
        "main's two lambdas first, then the one discovered inside a body")
    (is (= [:halt 0] (nth vector 3)))
    (is (every? #(= [:return 0] (nth vector %)) [7 9 11]))))


(deftest resume-mints-a-definition-less-id
  (let [{:keys [vector]} (get corpus/goldens :resume-operand)]
    (is (= [:call 0 1 [2 4] false] (nth vector 4)))
    (is (not-any? #(= 2 (code/rd %)) vector)
        "id 2 is minted by the resume and named only by its consumer")))


(deftest unsupported-and-reserved-nodes-are-refused
  (is (= :reserved-name
         (:rule (throws-ex-data
                  #(linearize/project-datoms
                     [[-1 :yin/type :variable 0 0]
                      [-1 :yin/name 'yin/def 0 0]
                      [-1 :yin/root true 0 0]])))))
  (is (= :reserved-name
         (:rule (throws-ex-data
                  #(linearize/project-datoms
                     (vm/ast->datoms (lam '[yin/def] (lit 1))))))))
  (is (some? (throws-ex-data
               #(linearize/project-datoms
                  [[-1 :yin/type :vm/store-update 0 0]
                   [-1 :yin/root true 0 0]]))))
  (is (some? (throws-ex-data #(linearize/project-datoms [])))))


(deftest malformed-row-sets-are-refused
  (doseq [[n [_expected bc]] malformed/malformed-row-sets]
    (testing n
      (is (some? (throws-ex-data #(linearize/project-rows bc)))))))


(defn- recording-primitives
  "Rule R: a definition is syntax, so nothing can intercept it. `rec`
   records each definition instead: `(rec 'k (yin/def 'k v))` logs right
   after the definition runs and returns its value."
  [log]
  (assoc vm/primitives
         'rec (fn [key val]
                (swap! log conj [key val])
                val)))


(defn- rdef!
  [k val]
  (app (v 'rec) (lit k) (def! k (if (map? val) val (lit val)))))


(def ^:private def-program
  "Definitions in operator position, in operands, in an `if` test and both
   arms, in a closure body run later, in a tail call, and read back
   through the store."
  (app (if-node (rdef! 'pick true)
                (lam '[f x]
                     (tail (app (v 'f)
                                (rdef! 'c (v 'x))
                                (rdef! 'd (app (v '+) (v 'b) (lit 1))))))
                (lam '[f x] (rdef! 'never 0)))
       (lam '[p q]
            (if-node (rdef! 'e false)
                     (rdef! 'never (v 'q))
                     (tail (app (v '+)
                                (rdef! 'f (v 'p))
                                (rdef! 'g (app (v '*) (v 'q) (lit 2)))))))
       (rdef! 'b (app (v '+) (rdef! 'a 1) (rdef! 'a 2)))))


(deftest evaluation-order-matches-the-walker
  (let [walker-log (atom [])
        walker (vm/eval (tu/create-vm {:primitives (recording-primitives
                                                     walker-log)})
                        def-program)
        lowered-log (atom [])
        result (vm/value (vm/run
                           (sr/load-ast (sr/create-vm {:primitives (recording-primitives lowered-log)})
                                        (vm/ast->datoms def-program) vm/ast-contract)))]
    (is (= '[[pick true] [a 1] [a 2] [b 3] [c 3] [d 4] [e false] [f 3] [g 8]]
           @walker-log)
        "the walker's order is the reference")
    (is (= @walker-log @lowered-log))
    (is (= (vm/value walker) result 11))))


(deftest lower-rows-evaluation-order-matches-the-walker
  (let [walker-log (atom [])
        walker (vm/eval (tu/create-vm {:primitives (recording-primitives walker-log)}) def-program)
        row-log (atom [])
        result (vm/value (vm/run
                           (sr/load-vector (sr/create-vm {:primitives (recording-primitives row-log)})
                                           (:vector (linearize/project-rows (vm/ast->semantic-bytecode def-program)))
                                           vm/semantic-contract)))]
    (is (= @walker-log @row-log))
    (is (= (vm/value walker) result 11))))


(deftest literals-are-opaque-data
  (doseq [type [:vm/store-update :yin/macro-expand]]
    (let [ast (lit {:type type :key 'k})
          rows (linearize/project ast)
          datoms (linearize/project-datoms (vm/ast->datoms ast))]
      (is (= (:vector rows) (:vector datoms)))
      (is (= {:type type :key 'k} (nth (first (:vector rows)) 2))))))


(deftest host-values-are-rejected-naming-the-node
  (doseq [value [{:nested [inc]}
                 (with-meta {:x 1} {:host inc})
                 {:x (with-meta [1] {:nested {:host inc}})}
                 (with-meta {:x 1} (with-meta {:k 1} {:host inc}))]]
    (let [[root datoms] (vm/ast->datoms-with-root (lit value))
          data (throws-ex-data #(linearize/project-datoms datoms))]
      (is (= root (:node data)))
      (is (= :value (:field data)))))
  (let [data (throws-ex-data #(linearize/project-datoms
                                (vm/ast->datoms {:type :vm/store-put :key 'k :val #{inc}})))]
    (is (= :val (:field data)))
    (is (some? (:node data))))
  (let [value (with-meta {:x 1} {:line 3})]
    (is (= {:line 3}
           (meta (nth (first (:vector (linearize/project-datoms
                                        (vm/ast->datoms (lit value))))) 2)))))
  (is (= '+ (nth (first (:vector (linearize/project (v '+)))) 2))))


(deftest lower-rows-rejects-incomplete-source-occurrence-identities
  (let [bc (vm/ast->semantic-bytecode (lit 1))]
    (doseq [origin [[:source :medium :batch]
                    [:source :medium :batch -1]
                    [:source nil :batch 0]
                    [:source :medium nil 0]
                    [:expansion :not-an-address]
                    {:source :medium}]]
      (is (= :origin (:rule (throws-ex-data #(linearize/project-rows bc origin))))
          (pr-str origin)))))


(def ^:private projection-worked-example
  "`((fn [x] (+ x 1)) 10)`, yin.vm.semantic.derivations.md §6.3 (hand-derived goldens, row 6)."
  (app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10)))


(def ^:private projection-corpus
  {:projection-worked-example projection-worked-example,
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
  (doseq [[label ast] (into projection-corpus (map (fn [[n a _]] [n a]) parity/corpus))]
    (testing label
      (let [bc (vm/ast->semantic-bytecode ast)
            {:keys [vector provenance]} (linearize/project-rows bc)
            occ (vm/occurrences bc)]
        (is (= (count vector) (count provenance)))
        (doseq [[pc _origin root path] provenance
                :let [mnem (nth (nth vector pc) 0)
                      id (path-row bc path)]]
          (is (contains? (:rows bc) id)
              (str "path resolves at pc " pc))
          (is (contains? occ [root path id])
              "the §5.3 join key [root path node] is an occurrence")
          (when-let [tag (get emitting-tag mnem)]
            (is (= tag (nth (get (:rows bc) id) 1))
                (str mnem " at pc " pc " is emitted by a " tag))))))))


(deftest unsupported-nodes-are-rejected-naming-the-node
  (doseq [type [:vm/store-update :yin/macro-expand]]
    (let [datoms [[-20 :yin/type type 0 :db/add]
                  [-21 :yin/type :application 0 :db/add]
                  [-21 :yin/operator -20 0 :db/add]
                  [-21 :yin/operands [] 0 :db/add]
                  [-21 :yin/root true 0 :db/add]]]
      (is (= {:type type :node -20}
             (throws-ex-data #(linearize/project-datoms datoms))))))
  (is (= {:type :no/such :id -20}
         (throws-ex-data #(linearize/project-datoms
                            [[-20 :yin/root true 0 :db/add]
                             [-20 :yin/type :no/such 0 :db/add]])))))
