(ns yin.vm.prototype.cesk-space-test
  "Contract for the CESK-as-datoms prototype (src/cljc/yin/vm/prototype/cesk_space.cljc).

  The claim: the WHOLE machine — program (C), environment (E), store (S),
  continuation (K), and the step trace — is datoms in one dao.space, so a
  single datalog query spans code and state. These tests are the query
  catalog: the set of questions the machine must answer through the real
  dao.space.query API. The catalog is the contract that any future
  optimization (two-tier store, capability-filtered reads) must preserve."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn :as edn])
            [dao.space.query :as q]
            [yin.vm.prototype.cesk-space :as m]))


(def ^:private factorial
  '(letrec [fact (lambda (n) (if (< n 2) 1 (* n (fact (- n 1)))))] (fact 5)))


(deftest evaluates-the-language
  (testing "literals, primitives, if/let, closures, and recursion"
    (is (= 42 (:value (m/run '42))))
    (is (= 5 (:value (m/run '(+ 2 3)))))
    (is (= 20 (:value (m/run '(let [x 10] (if (< x 5) 1 (* x 2)))))))
    (is (= 49 (:value (m/run '((lambda (x) (* x x)) 7)))))
    (is (= 120 (:value (m/run factorial)))
        "letrec closes over its own binding, so recursion terminates at 120")))


(deftest cesk-time-is-datom-time
  (testing
    "every step appends datoms under a fresh t; the highest recorded
            :cfg/step equals the machine's step count"
    (let [{:keys [st steps]} (m/run factorial)
          space (:space st)
          highest-step (apply max
                              (map peek
                                   (q/q '[:find ?s :where [_ :cfg/step ?s]] space)))]
      ;; steps counts transitions; each transition stamps one config at its
      ;; t.
      (is (= steps (inc highest-step))
          "the datom log's t-axis IS the machine's step counter"))))


(deftest introspection-is-datalog
  (testing
    "live machine state answers datalog queries with no bespoke traversal"
    (let [space (:space (:st (m/run factorial)))]
      (is (= 5
             (ffirst (q/q '[:find (count ?e) :where [?e :cell/value _]] space)))
          "one scalar recursion cell per fact frame (5 downto 1)")
      (is (= 1 (ffirst (q/q '[:find (count ?e) :where [?e :cell/ref _]] space)))
          "the fact binding is a closure: a ref cell, joinable by q")
      (is (= [1 2 3 4 5]
             (sort (map first
                        (q/q '[:find ?v :in $ ?name :where [?b :bind/name ?name]
                               [?b :bind/addr ?a] [?a :cell/value ?v]]
                             space
                             'n))))
          "the whole live recursion stack's bindings of n, read by query")
      (is (contains? (set (map first
                               (q/q '[:find ?t :where [_ :k/tag ?t]] space)))
                     :letrec)
          "the continuation is reified — its frame tags are queryable data"))))


(deftest time-travel-is-as-of
  (testing "a recursion cell is absent before it is set and present after"
    (let [space (:space (:st (m/run factorial)))
          ;; the first-allocated cell bound to n
          cell (first (sort (map first
                                 (q/q '[:find ?a :in $ ?name :where
                                        [?b :bind/name ?name] [?b :bind/addr ?a]
                                        [?a :cell/value _]]
                                      space
                                      'n))))
          set-t (nth (first (q/match space [cell :cell/value '_])) 3)]
      (is (nil? (:cell/value
                  (q/pull space cell [:cell/value] {:as-of (dec set-t)})))
          "as-of just before the set, the cell holds no value")
      (is (= 5 (:cell/value (q/pull space cell [:cell/value] {:as-of set-t})))
          "as-of the set, the value (5, the outermost fact arg) is visible"))))


(deftest migration-is-the-space
  (testing
    "the machine is nothing but its datoms: serialize mid-flight to
            EDN text, revive, and resume to the same answer"
    (let [seed (m/inject factorial)
          ;; run a few steps by hand to reach a mid-flight configuration
          [st cfg] (nth (iterate (fn [[s c]] (m/step s c)) seed) 20)]
      (is (number? cfg) "mid-flight: not yet halted")
      (is (map? st) "the whole machine is one value")
      #?(:clj
         (let [wire (pr-str st) ; the entire machine — program included —
               ;; as text
               revived (edn/read-string wire)]
           (is
             (= 120 (:value (m/drive revived cfg 100000)))
             "a machine revived from text resumes to the correct value"))))))


;; =============================================================================
;; C is datoms too: the program lives in the same space as the machine
;; =============================================================================

(deftest program-is-datoms
  (testing "the program's AST answers the same queries as semantic.cljc's db"
    (let [space (:space (:st (m/run factorial)))]
      (is (= 1
             (ffirst (q/q '[:find (count ?e) :where [?e :yin/type :lambda]]
                          space)))
          "the factorial lambda is an entity in the machine's own space")
      (is (seq (q/q '[:find ?e :where [?e :yin/type :application]] space))
          "application nodes are entities")
      (is (seq (q/q '[:find ?e :where [?e :yin/type :lambda]] space {:as-of 0}))
          "the program is genesis: visible as-of t=0, before any step ran"))))


(deftest code-and-state-join
  (testing "one q spans code (C), environment (E), and store (S)"
    (let [space (:space (:st (m/run factorial)))]
      (is (= [1 2 3 4 5]
             (sort (map first
                        (q/q '[:find ?v :where [?var :yin/type :variable]
                               [?var :yin/name ?nm] [?b :bind/name ?nm]
                               [?b :bind/addr ?a] [?a :cell/value ?v]]
                             space))))
          "join AST variable nodes to live bindings to cell values"))))


(deftest control-trace-and-dead-code
  (testing "the control trace is data: never-evaluated nodes found by query"
    (let [space (:space (:st (m/run '(if (< 1 2) 10 20))))
          ctrl-nodes (set (map first
                               (q/q '[:find ?n :where [_ :cfg/ctrl ?n]] space)))
          live (ffirst
                 (q/q '[:find ?e :in $ ?v :where [?e :yin/value ?v]] space 10))
          dead (ffirst
                 (q/q '[:find ?e :in $ ?v :where [?e :yin/value ?v]] space 20))]
      (is (contains? ctrl-nodes live) "the taken branch entered control")
      (is (not (contains? ctrl-nodes dead))
          "the untaken branch never entered control: dead code by query"))))


(deftest closures-are-entities
  (testing "closure values are ref-joinable eids, not opaque host values"
    (let [space (:space (:st (m/run factorial)))]
      (is (seq (q/q '[:find ?clo ?body :where [?a :cell/ref ?clo]
                      [?clo :clo/body ?body]]
                    space))
          "the letrec-bound closure is unifiable from cell to body node")
      (is (seq (q/q '[:find ?cfg :where [?cfg :cfg/val-ref ?clo]
                      [?clo :clo/param ?p]]
                    space))
          "configs that produced closure values join through :cfg/val-ref"))))


(deftest provenance-is-joinable
  (testing "why does this cell hold 5? — :cell/set-by names the config"
    (let [space (:space (:st (m/run factorial)))
          steps (map first
                     (q/q '[:find ?s :where [?a :cell/value 5]
                            [?a :cell/set-by ?cfg] [?cfg :cfg/step ?s]]
                          space))]
      (is (seq steps) "the write is attributed to a config entity")
      (is (every? integer? steps) "and the config knows its step"))))


;; =============================================================================
;; The open door: ownership (m), shared spaces, Linda coordination
;; =============================================================================

(deftest datoms-are-owned
  (testing "every datom's m is the machine's reified owner entity"
    (let [st (:st (m/run factorial))
          space (:space st)
          owner (:owner st)]
      (is (pos-int? owner))
      (is (every? #(= owner (peek %)) space)
          "m = owner on every datom the machine wrote")
      (is (= 'machine (:agent/name (q/pull space owner [:agent/name])))
          "the owner is itself an entity in the space"))))


(deftest linda-coordination-in-shared-space
  (testing "out then rd in one machine round-trips through the space"
    (is (= 41 (:value (m/run '(let [x (out answer 41)] (rd answer)))))))
  (testing "a lone rd parks as a queryable wait entity"
    (let [[st cfg] (m/inject '(+ 1 (rd answer)) {:owner-name 'alice})
          alone (m/run-shared [[st cfg]] 100)]
      (is (= :blocked (get-in alone [:machines 'alice :status])))
      (is (seq (q/q '[:find ?w :in $ ?p :where [?w :wait/pattern ?p]]
                    (:space alone)
                    ['_ :tuple/key 'answer]))
          "rd is a blocking match: the template itself is queryable data")))
  (testing "two machines, one space: bob's out resumes alice's rd"
    (let [[st-a cfg-a] (m/inject '(+ 1 (rd answer)) {:owner-name 'alice})
          [st-b cfg-b]
          (m/inject '(out answer 41)
                    {:owner-name 'bob, :space (:space st-a), :eid-base 65536})
          {:keys [space machines]} (m/run-shared [[st-a cfg-a] [st-b cfg-b]]
                                                 1000)]
      (is (= 42 (get-in machines ['alice :value]))
          "alice resumed with bob's tuple")
      (is (= 41 (get-in machines ['bob :value])))
      (let [alice (ffirst (q/q '[:find ?o :in $ ?n :where [?o :agent/name ?n]]
                               space
                               'alice))
            bob (ffirst (q/q '[:find ?o :in $ ?n :where [?o :agent/name ?n]]
                             space
                             'bob))]
        (is (= #{alice bob} (set (map peek space)))
            "the shared space partitions by writer: m says who wrote what")))))


;; =============================================================================
;; Edge cases: shadowing, deadlocks, empty inputs
;; =============================================================================

(deftest primitive-shadowing
  (testing "a let-bound name that collides with a primitive shadows it"
    (is (= 7 (:value (m/run '(let [+ (lambda (x) x)] (+ 7)))))
        "the shadowed + is an ordinary closure and applies as one")
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                             :cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"cannot apply non-closure"
          (m/run '(let [+ 5] (+ 1 2))))
        "the user-bound + is 5, not the primitive: applying it must fail")))


(deftest deadlock-in-single-machine
  (testing "drive throws when a lone machine blocks on a tuple nobody writes"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                             :cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"deadlock"
          (m/run '(rd nobody-home))))))


(deftest deadlock-in-shared-space
  (testing "two machines mutually blocked on each other's tuples: no progress"
    (let [[st-a cfg-a] (m/inject '(rd from-bob) {:owner-name 'alice})
          [st-b cfg-b]
          (m/inject '(rd from-alice)
                    {:owner-name 'bob, :space (:space st-a), :eid-base 65536})
          result (m/run-shared [[st-a cfg-a] [st-b cfg-b]] 100)]
      (is (= :blocked (get-in result [:machines 'alice :status])))
      (is (= :blocked (get-in result [:machines 'bob :status])))
      (is (nil? (get-in result [:machines 'alice :value]))
          "neither machine could proceed: both blocked forever"))))


(deftest run-shared-with-empty-seeds
  (testing "an empty seed list returns an empty result, not nil space"
    (let [result (m/run-shared [] 100)]
      (is (= {} (:machines result)))
      (is (vector? (:space result))
          "space should be an empty vector, not nil"))))
