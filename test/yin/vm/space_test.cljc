(ns yin.vm.space-test
  "Contract for yin.vm.space — a CESK peer VM whose configuration is datoms.

  Two obligations, tested in that order:

  1. It is a peer. It executes the same canonical `:yin/*` AST datoms as
     `yin.vm.register` and `yin.vm.semantic`, through `vm/IVM`, with the
     same node vocabulary and the same tail-call behavior. The
     cross-backend agreement itself lives in `yin.vm.parity-test`, which
     runs every case against space as well; what is here is the
     space-specific behavioral surface.

  2. The machine is queryable. Program (C), environment (E), store (S),
     continuation (K) and the step trace are deposited as datoms, so one
     datalog query spans code and live state. The query catalog below is
     the contract any future optimization must preserve: a faster machine
     that answers these differently is wrong, not fast."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.space.query :as q]
            [dao.stream :as ds]
            [dao.stream.apply :as dao.stream.apply]
            [dao.stream.ringbuffer]
            [yang.clojure :as yang]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.space :as space]))


;; =============================================================================
;; Programs used across the catalog
;; =============================================================================

(def ^:private factorial-fn
  "(fn [f n] (if (< n 2) 1 (* n (f f (- n 1))))) — self-application so
   recursion needs no name binding. The recursive call sits inside `*`, so
   it is deliberately NOT in tail position: the continuation grows and K
   becomes interesting."
  {:type :lambda,
   :params ['f 'n],
   :body {:type :if,
          :test {:type :application,
                 :operator {:type :variable, :name '<},
                 :operands [{:type :variable, :name 'n}
                            {:type :literal, :value 2}]},
          :consequent {:type :literal, :value 1},
          :alternate {:type :application,
                      :operator {:type :variable, :name '*},
                      :operands [{:type :variable, :name 'n}
                                 {:type :application,
                                  :operator {:type :variable, :name 'f},
                                  :operands
                                  [{:type :variable, :name 'f}
                                   {:type :application,
                                    :operator {:type :variable, :name '-},
                                    :operands [{:type :variable, :name 'n}
                                               {:type :literal,
                                                :value 1}]}]}]}}})


(def ^:private factorial
  {:type :application,
   :operator factorial-fn,
   :operands [factorial-fn {:type :literal, :value 5}]})


(defn- run-space
  ([ast] (run-space ast {}))
  ([ast opts] (vm/eval (space/create-vm opts) ast)))


(defn- space-of
  [ast]
  (space/machine-space (run-space ast)))


;; =============================================================================
;; 1. Peer behavior — the language, end to end
;; =============================================================================

(deftest evaluates-the-canonical-ast
  (testing "literals, primitives, conditionals, closures, and recursion"
    (is (= 42 (vm/value (run-space {:type :literal, :value 42}))))
    (is (= 5
           (vm/value (run-space {:type :application,
                                 :operator {:type :variable, :name '+},
                                 :operands [{:type :literal, :value 2}
                                            {:type :literal, :value 3}]}))))
    (is (= 20
           (vm/value (run-space {:type :if,
                                 :test {:type :application,
                                        :operator {:type :variable, :name '<},
                                        :operands [{:type :literal, :value 10}
                                                   {:type :literal, :value 5}]},
                                 :consequent {:type :literal, :value 1},
                                 :alternate {:type :literal, :value 20}}))))
    (is (= 49
           (vm/value (run-space
                       {:type :application,
                        :operator {:type :lambda,
                                   :params ['x],
                                   :body {:type :application,
                                          :operator {:type :variable, :name '*},
                                          :operands [{:type :variable, :name 'x}
                                                     {:type :variable,
                                                      :name 'x}]}},
                        :operands [{:type :literal, :value 7}]}))))
    (is (= 120 (vm/value (run-space factorial)))
        "self-application recursion terminates at 120")))


(deftest closures-capture-their-environment
  (testing "an inner lambda sees the outer parameter after the outer returns"
    ;; (((fn [x] (fn [y] (+ x y))) 10) 5) => 15
    (let [make-adder {:type :lambda,
                      :params ['x],
                      :body {:type :lambda,
                             :params ['y],
                             :body {:type :application,
                                    :operator {:type :variable, :name '+},
                                    :operands [{:type :variable, :name 'x}
                                               {:type :variable, :name 'y}]}}}
          ast {:type :application,
               :operator {:type :application,
                          :operator make-adder,
                          :operands [{:type :literal, :value 10}]},
               :operands [{:type :literal, :value 5}]}]
      (is (= 15 (vm/value (run-space ast)))))))


(deftest tail-calls-run-in-constant-continuation-depth
  (testing "a tail-recursive countdown neither grows K nor overflows"
    (let [self-fn {:type :lambda,
                   :params ['self 'n],
                   :body {:type :if,
                          :test {:type :application,
                                 :operator {:type :variable, :name '<},
                                 :operands [{:type :variable, :name 'n}
                                            {:type :literal, :value 1}]},
                          :consequent {:type :literal, :value 0},
                          :alternate {:type :application,
                                      :operator {:type :variable, :name 'self},
                                      :operands
                                      [{:type :variable, :name 'self}
                                       {:type :application,
                                        :operator {:type :variable, :name '-},
                                        :operands [{:type :variable, :name 'n}
                                                   {:type :literal,
                                                    :value 1}]}],
                                      :tail? true},
                          :tail? true}}
          ast {:type :application,
               :operator self-fn,
               :operands [self-fn {:type :literal, :value 1000}],
               :tail? true}]
      (is (= 0 (vm/value (run-space ast))) "1000 tail calls complete")
      ;; step it and watch K stay bounded
      (loop [v (vm/eval (space/create-vm) ast)
             max-d 0]
        (if (vm/halted? v)
          (is (<= max-d 3)
              (str "continuation depth should stay small, got " max-d))
          (let [v' (vm/step v)]
            (recur v' (max max-d (count (vm/continuation v'))))))))))


(deftest store-primitives
  (testing ":vm/store-put writes and :vm/store-get reads the heap"
    (let [vm1 (run-space {:type :vm/store-put, :key :answer, :val 42})]
      (is (= 42 (vm/value vm1)))
      (is (= 42 (get (vm/store vm1) :answer)))
      (is (= 42
             (vm/value (vm/eval vm1 {:type :vm/store-get, :key :answer})))))))


(deftest gensym-primitive
  (testing ":vm/gensym yields a fresh keyword per evaluation"
    (let [vm1 (run-space {:type :vm/gensym, :prefix "tmp"})
          id1 (vm/value vm1)
          id2 (vm/value (vm/eval vm1 {:type :vm/gensym, :prefix "tmp"}))]
      (is (keyword? id1))
      (is (keyword? id2))
      (is (not= id1 id2) "each gensym is distinct"))))


;; =============================================================================
;; Streams
;; =============================================================================

(deftest stream-make-and-put
  (testing "stream/make yields a stream ref with the requested capacity"
    (let [vm1 (run-space {:type :stream/make, :buffer 10})
          ref (vm/value vm1)]
      (is (= :stream-ref (:type ref)))
      (is (keyword? (:id ref)))))
  (testing "stream/put appends and returns the value"
    (let [vm1 (run-space {:type :stream/make, :buffer 5})
          ref (vm/value vm1)
          vm2 (vm/eval vm1
                       {:type :stream/put,
                        :target {:type :literal, :value ref},
                        :val {:type :literal, :value 42}})]
      (is (= 42 (vm/value vm2)))
      (is (= 1 (count (get (vm/store vm2) (:id ref))))))))


(deftest stream-cursor-and-next
  (testing "cursor+next reads back what put wrote"
    (let [vm1 (run-space {:type :stream/make, :buffer 5})
          ref (vm/value vm1)
          vm2 (vm/eval vm1
                       {:type :stream/put,
                        :target {:type :literal, :value ref},
                        :val {:type :literal, :value 99}})
          vm3 (vm/eval vm2
                       {:type :application,
                        :operator {:type :lambda,
                                   :params ['c],
                                   :body {:type :stream/next,
                                          :source {:type :variable, :name 'c}}},
                        :operands [{:type :stream/cursor,
                                    :source {:type :literal, :value ref}}]})]
      (is (= 99 (vm/value vm3)))))
  (testing "next on an empty stream blocks rather than erroring"
    (let [vm1 (run-space {:type :stream/make, :buffer 5})
          ref (vm/value vm1)
          vm2 (vm/eval vm1
                       {:type :application,
                        :operator {:type :lambda,
                                   :params ['c],
                                   :body {:type :stream/next,
                                          :source {:type :variable, :name 'c}}},
                        :operands [{:type :stream/cursor,
                                    :source {:type :literal, :value ref}}]})]
      (is (= :yin/blocked (vm/value vm2))))))


;; =============================================================================
;; Continuations
;; =============================================================================

(deftest park-and-resume
  (testing ":vm/park reifies the continuation and :vm/resume restores it"
    (let [vm0 (space/create-vm)
          vm1 (vm/eval vm0 {:type :vm/park})
          parked (vm/value vm1)
          vm2 (vm/eval vm1
                       {:type :vm/resume,
                        :parked-id (:id parked),
                        :val {:type :literal, :value 42}})]
      (is (= :parked-continuation (:type parked)))
      (is (= 42 (vm/value vm2)))
      (is (nil? (get-in vm2 [:parked (:id parked)]))
          "resuming consumes the parked entry"))))


(deftest current-continuation-is-reified
  (testing ":vm/current-continuation yields an inspectable continuation"
    (let [reified (vm/value (run-space {:type :vm/current-continuation}))]
      (is (= :reified-continuation (:type reified))))))


;; =============================================================================
;; FFI — dao.stream.apply call-out and back
;; =============================================================================

(defn- bridge-step
  "Serve one pending dao.stream.apply request from the VM's call-in stream."
  [vm handlers cursor]
  (let [call-in (get (vm/store vm) vm/call-in-stream-key)
        {:keys [ok], :as result} (ds/next call-in cursor)
        cursor' (:cursor result)]
    (if ok
      (let [{id :dao.stream.apply/id,
             op :dao.stream.apply/op,
             args :dao.stream.apply/args}
            ok
            answer (apply (get handlers op) (or args []))
            call-out (get (vm/store vm) vm/call-out-stream-key)
            woke (:woke (ds/append! call-out
                                    (dao.stream.apply/response id answer)))
            entries (engine/make-woken-run-queue-entries vm woke)]
        [(update vm :ready-queue (fnil into []) entries) cursor'])
      [vm cursor])))


(deftest dao-stream-apply-call-blocks-then-resumes
  (testing "a call-out parks the machine and the response resumes it"
    (let [parked (run-space {:type :dao.stream.apply/call,
                             :op :op/echo,
                             :operands [{:type :literal, :value 42}]})]
      (is (vm/blocked? parked) "the machine parks awaiting the response")
      (let [[vm' _] (bridge-step parked {:op/echo identity} {:position 0})
            done (vm/eval vm' nil)]
        (is (vm/halted? done))
        (is (= 42 (vm/value done)) "the response value flows back into K")))))


(deftest dao-stream-apply-repeated-calls
  (testing "many sequential call-outs run to completion through a bridge"
    (let
      [calls (atom [])
       handlers {:plot/point (fn [x y] (swap! calls conj [x y]) nil)}
       program
       (yang/compile-program
         '[(defn plot-loop
             [i]
             (if
               (> i 19)
               nil
               (do
                 (dao.stream.apply/call :plot/point i (* i i))
                 (plot-loop (+ i 1))))) (plot-loop 0)])
       result (vm/eval (space/create-vm {:bridge handlers}) program)]
      (is (vm/halted? result))
      (is (= 20 (count @calls)))
      (is (= [0 0] (first @calls)))
      (is (= [19 361] (last @calls))))))


;; =============================================================================
;; 2. The query catalog — the machine is datoms
;; =============================================================================

(deftest program-is-datoms
  (testing "the program lives in the machine's own space, as genesis"
    (let [space (space-of factorial)]
      (is
        (= 2
           (ffirst (q/q '[:find (count ?e) :where [?e :yin/type :lambda]]
                        space)))
        "the factorial lambda is an entity in the machine's space — twice,
           because self-application names it in operator and operand position
           and the two occurrences are not :eid-shared")
      (is (seq (q/q '[:find ?e :where [?e :yin/type :application]] space))
          "application nodes are entities")
      (is (seq (q/q '[:find ?e :where [?e :yin/type :lambda]] space {:as-of 0}))
          "the program is genesis: visible as-of t=0, before any step ran"))))


(deftest cesk-time-is-datom-time
  (testing "each step appends under a fresh t and stamps its step number"
    (let [vm (run-space factorial)
          space (space/machine-space vm)
          steps (map first (q/q '[:find ?s :where [_ :cfg/step ?s]] space))]
      (is (seq steps) "configurations record their step")
      (is (apply distinct? steps) "one configuration per step")
      (is (= (range (count steps)) (sort steps))
          "the transaction axis IS the step counter, with no gaps"))))


(deftest introspection-is-datalog
  (testing "live machine state answers queries with no bespoke traversal"
    (let [space (space-of factorial)]
      (is (= [1 2 3 4 5]
             (sort (map first
                        (q/q '[:find ?v :in $ ?name :where [?b :bind/name ?name]
                               [?b :bind/addr ?a] [?a :cell/value ?v]]
                             space
                             'n))))
          "every live binding of n across the recursion, read by query")
      (is (seq (q/q '[:find ?e :where [?e :cell/ref _]] space))
          "the recursive function argument is a closure: a ref cell")
      (is (contains? (set (map first
                               (q/q '[:find ?t :where [_ :k/tag ?t]] space)))
                     :app-op)
          "the continuation is reified — its frame tags are queryable data"))))


(deftest code-and-state-join
  (testing "one q spans code (C), environment (E), and store (S)"
    (is (= [1 2 3 4 5]
           (sort (map first
                      (q/q '[:find ?v :where [?var :yin/type :variable]
                             [?var :yin/name ?nm] [?b :bind/name ?nm]
                             [?b :bind/addr ?a] [?a :cell/value ?v]]
                           (space-of factorial)))))
        "join AST variable nodes to live bindings to cell values")))


(deftest provenance-is-joinable
  (testing "why does this cell hold 5? — :cell/set-by names the config"
    (let [steps (map first
                     (q/q '[:find ?s :where [?a :cell/value 5]
                            [?a :cell/set-by ?cfg] [?cfg :cfg/step ?s]]
                          (space-of factorial)))]
      (is (seq steps) "the write is attributed to a config entity")
      (is (every? integer? steps) "and the config knows its step"))))


(deftest closures-are-entities
  (testing "closure values are ref-joinable eids, not opaque host values"
    (let [space (space-of factorial)]
      (is (seq (q/q '[:find ?clo ?body :where [?a :cell/ref ?clo]
                      [?clo :clo/body ?body]]
                    space))
          "a closure-valued binding is unifiable from cell to body node")
      (is (seq (q/q '[:find ?cfg :where [?cfg :cfg/val-ref ?clo]
                      [?clo :clo/param ?p]]
                    space))
          "configs that produced closure values join through :cfg/val-ref"))))


(deftest control-trace-and-dead-code
  (testing "the control trace is data: never-evaluated nodes found by query"
    (let [space (space-of {:type :if,
                           :test {:type :application,
                                  :operator {:type :variable, :name '<},
                                  :operands [{:type :literal, :value 1}
                                             {:type :literal, :value 2}]},
                           :consequent {:type :literal, :value 10},
                           :alternate {:type :literal, :value 20}})
          entered (set (map first
                            (q/q '[:find ?n :where [_ :cfg/ctrl ?n]] space)))
          live (ffirst
                 (q/q '[:find ?e :in $ ?v :where [?e :yin/value ?v]] space 10))
          dead (ffirst
                 (q/q '[:find ?e :in $ ?v :where [?e :yin/value ?v]] space 20))]
      (is (contains? entered live) "the taken branch entered control")
      (is (not (contains? entered dead))
          "the untaken branch never entered control: dead code by query"))))


(deftest time-travel-is-as-of
  (testing "a recursion cell is absent before it is set and present after"
    (let [space (space-of factorial)
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
      (is (some? (:cell/value (q/pull space cell [:cell/value] {:as-of set-t})))
          "as-of the set, the value is visible"))))


(deftest datoms-are-owned
  (testing "every datom's m is the machine's reified owner entity"
    (let [vm (run-space factorial)
          space (space/machine-space vm)
          owner (:owner vm)]
      (is (pos-int? owner))
      (is (every? #(= owner (peek %)) space)
          "m = owner on every datom the machine wrote")
      (is (= 'machine (:agent/name (q/pull space owner [:agent/name])))
          "the owner is itself an entity in the space"))))


(deftest seeded-space-stays-partitioned-by-writer
  (testing
    "a machine seeded from another's datoms keeps them distinguishable by m.
     Note this is a value copy, not a shared medium: bob starts from a
     snapshot of alice's space and neither sees the other's later writes.
     What is under test is the `m` writer tag, not coordination."
    (let [alice (vm/eval (space/create-vm {:owner-name 'alice, :eid-base 2048})
                         {:type :literal, :value 1})
          bob (vm/eval (space/create-vm {:owner-name 'bob,
                                         :eid-base 65536,
                                         :space (space/machine-space alice)})
                       {:type :literal, :value 2})
          shared (space/machine-space bob)]
      (is (= #{2048 65536} (set (map peek shared)))
          "the shared space partitions by writer: m says who wrote what")
      (is (= 'alice (:agent/name (q/pull shared 2048 [:agent/name]))))
      (is (= 'bob (:agent/name (q/pull shared 65536 [:agent/name])))))))


(deftest trace-is-optional
  (testing "trace? false keeps semantics and drops the machine-state trace"
    (let [traced (run-space factorial)
          untraced (run-space factorial {:trace? false})]
      (is (= (vm/value traced) (vm/value untraced) 120)
          "semantics never depend on the trace")
      (is (empty? (q/q '[:find ?c :where [?c :cfg/step _]]
                       (space/machine-space untraced)))
          "no configuration entities were deposited")
      (is (seq (q/q '[:find ?e :where [?e :yin/type :lambda]]
                    (space/machine-space untraced)))
          "the program is still genesis — C is not part of the trace"))))
