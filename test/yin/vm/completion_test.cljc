(ns yin.vm.completion-test
  "U14 acceptance tests (`docs/design/yin.vm.dependency-completion.md` §11).
   Every fixture goes through `completion/walk`, whose §8 step 4
   verification pass throws on a state change — so a passing fixture is
   also acceptance test 9 for that fixture."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [yin.vm :as vm]
            [yin.vm.completion :as completion]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.parity-test :as parity]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- lit
  [v]
  {:type :literal, :value v})


(defn- variable
  [n]
  {:type :variable, :name n})


(defn- lambda
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [f & args]
  {:type :application, :operator f, :operands (vec args)})


(defn- define
  [k v]
  (app (variable 'yin/def) (lit k) v))


(defn- in-order
  "Evaluate `exprs` left to right, for effect."
  [& exprs]
  (apply app
         (lambda (mapv #(symbol (str "_" %)) (range (count exprs))) (lit 0))
         exprs))


(defn- lower
  [ast]
  (:vector (linearize/lower-rows (vm/ast->semantic-bytecode ast))))


(defn- load-ast
  "Load `ast` by the direct path, leaving it un-run: a quiescent machine
   whose frame is the program's pc 0."
  [machine ast]
  (semantic/load-vector machine (lower ast) vm/semantic-contract))


(defn- new-vm
  ([] (new-vm {}))
  ([opts] (semantic/create-vm (merge {:make-stream tu/make-stream,
                                      :capability-secret tu/secret} opts))))


(def ^:private maker-uses
  "`a` captures a stream, `b` a number: two closures of the maker segment,
   two envs. Loaded as its own segment: a definition key is a store-slice
   requirement of the segment that writes it (Rule R, like a `:store-put`
   key), so defining `a` and `b` inside the maker segment would put both
   keys in every slice that reaches `mk`'s code."
  (in-order
    (define 'a (app (variable 'mk) {:type :stream/make, :buffer 8}))
    (define 'b (app (variable 'mk) (lit 1)))))


(def ^:private maker-program
  "`mk` closes over its argument; `fact` recurs through the store."
  (in-order
    (define 'mk (lambda ['s] (lambda [] (variable 's))))
    (define 'fact
      (lambda ['n]
              {:type :if,
               :test (app (variable '<) (variable 'n) (lit 1)),
               :consequent (lit 1),
               :alternate (app (variable '*)
                               (variable 'n)
                               (app (variable 'fact)
                                    (app (variable '-) (variable 'n)
                                         (lit 1))))}))))


(defn- maker-vm
  []
  (-> (new-vm)
      (load-ast maker-program)
      vm/run
      (load-ast maker-uses)
      vm/run))


(def ^:private maker-address (jing/segment-key (lower maker-program)))


(defn- get-key
  [k]
  {:type :vm/store-get, :key k})


;; =============================================================================
;; 1. Conformance
;; =============================================================================

(def ^:private kitchen-sink
  {:type :if,
   :test (get-key 'k),
   :consequent {:type :stream/put,
                :target {:type :stream/make, :buffer 8},
                :val {:type :stream/next,
                      :source {:type :stream/cursor, :source (variable 's)}}},
   :alternate {:type :vm/resume,
               :parked-id :p1,
               :val {:type :dao.stream.apply/call,
                     :op :op/echo,
                     :operands [{:type :vm/store-put, :key 99, :val 1}
                                {:type :vm/gensym, :prefix "g"}
                                {:type :vm/current-continuation}
                                {:type :vm/park}]}}})


(def ^:private scoping-corpus
  "§12.3's test obligation: nested lambdas and sibling closures in one
   segment, plus shadowing and a name bound at one occurrence and free at
   another."
  [["nested lambdas"
    (lambda ['x] (lambda ['y] (app (variable '+) (variable 'x) (variable 'y)
                                   (variable 'z))))]
   ["sibling closures"
    (app (lambda ['f 'g] (app (variable 'f) (variable 'g)))
         (lambda ['x] (app (variable '+) (variable 'x) (variable 'free-a)))
         (lambda ['y] (app (variable '*) (variable 'x) (variable 'y))))]
   ["deep nesting with siblings"
    (lambda ['a]
            (app (lambda ['b] (lambda ['c] (app (variable 'a) (variable 'b)
                                                (variable 'c) (variable 'd))))
                 (lambda ['d] (app (variable 'a) (variable 'd) (variable 'e)))))]
   ["shadowing" (lambda ['x] (lambda ['x] (variable 'x)))]
   ["bound here, free there"
    (app (lambda ['x] (variable 'x)) (variable 'x))]
   ["lambda in an if"
    {:type :if,
     :test (variable 'p),
     :consequent (lambda ['q] (app (variable 'q) (variable 'p))),
     :alternate (lambda ['r] (variable 'q))}]])


(def ^:private conformance-corpus
  (concat (map (fn [[name ast]] [name ast]) parity/corpus)
          scoping-corpus
          [["kitchen sink" kitchen-sink]
           ["maker program" maker-program]]))


(deftest code-facts-from-tree-and-segment-are-equal
  (doseq [[name ast] conformance-corpus]
    (testing name
      (let [tree (vm/ast->semantic-bytecode ast)
            v (:vector (linearize/lower-rows tree))
            surface #(dissoc % :kind :closures)]
        (is (= (surface (completion/code-facts tree))
               (surface (completion/code-facts v)))
            "equal in store keys, FFI ops, parked ids, effects, and free
              names — the range rule proved against the occurrence rules")))))


(deftest segment-free-names-cases
  (is (= '#{+ z}
         (completion/segment-free-names (lower (second (first scoping-corpus))))))
  (is (= '#{+ * x free-a}
         (completion/segment-free-names (lower (second (second scoping-corpus)))))
      "a sibling's param does not bind: x is free in the second sibling")
  (testing "an unknown lowering profile rules every :var free"
    (is (= '#{+ x y z}
           (completion/segment-free-names
             (lower (second (first scoping-corpus))) "other-profile"))))
  (testing "a vector without the ast-to-bytecode layout is ruled all-free"
    (is (= '#{x}
           (completion/segment-free-names
             [[:closure ['x] 0] [:var 'x] [:return]])))))


(deftest loaded-images-answer-fetch
  (doseq [[name ast] conformance-corpus]
    (testing name
      (let [v (lower ast)
            machine (semantic/load-vector (new-vm) v vm/semantic-contract)
            image (get (:code machine) (:program machine))]
        (is (= v (completion/image->vector image))
            "the image decodes back to the vector that hashes to its address")))))


;; =============================================================================
;; 2–4. Work items, stored closures, def-recursion
;; =============================================================================

(defn- complete-getting
  [& ks]
  (let [machine (load-ast (maker-vm) (apply in-order (map get-key ks)))]
    (completion/complete {:vm machine})))


(defn- maker-items
  [result]
  (filter #(= maker-address (first %)) (::completion/work-items result)))


(deftest two-closures-one-segment-two-envs
  (let [a (complete-getting 'a)
        b (complete-getting 'b)
        both (complete-getting 'a 'b)]
    ;; `mk` and `fact` are free names of the maker segment, so their
    ;; closures (empty context) are reached whichever key is read
    (is (= #{#{} #{[:stream :stream-0]}} (set (map second (maker-items a))))
        "one address, two contexts: two work items")
    (is (= #{#{}} (set (map second (maker-items b)))))
    (is (= (set (maker-items a)) (set (maker-items both))))
    (is (= 1 (count (get-in a [:yin.k/requires :yin.k/streams]))))
    (is (empty? (get-in b [:yin.k/requires :yin.k/streams])))
    (is (= (get-in a [:yin.k/requires :yin.k/streams])
           (get-in both [:yin.k/requires :yin.k/streams])))
    (testing "the slice is pulled, never copied"
      (let [stream-id (first (get-in a [:yin.k/requires :yin.k/streams]))]
        (is (= #{'a 'mk 'fact} (set (keys (:yin.k/store a)))))
        (is (= #{stream-id} (set (keys (:yin.k/resources a))))
            "the stream is pulled beside the store slice, never inside it")
        (is (= #{'b 'mk 'fact} (set (keys (:yin.k/store b)))))
        (is (empty? (:yin.k/resources b)))))
    (is (= :complete (get-in both [:yin.k/requires :yin.k/discovery])))))


(deftest stored-closure-is-a-work-item-and-its-names-are-discharged
  (let [result (complete-getting 'b)
        requires (:yin.k/requires result)]
    (is (= [[maker-address #{}]] (maker-items result))
        "reachable only through the :store-get operand")
    (is (contains? (:yin.k/segments requires) maker-address))
    (is (= (vm/profile-of vm/primitives '*)
           (get-in requires [:yin.k/primitives '*])))
    (is (not (contains? (:yin.k/primitives requires) 'yin/def))
        "Rule R: a definition is syntax, never a free name to discharge")
    (is (not (contains? (:yin.k/effects requires) :vm/store-put))
        "a definition's store write is machine state, as :store-put's is")
    (is (contains? (:yin.k/store result) 'fact)
        "fact is a free name of the segment, discharged by the store")
    (is (= :complete (:yin.k/discovery requires)))
    (is (empty? (:yin.k/refusals result)))))


(deftest def-recursion-terminates
  (let [result (complete-getting 'fact)]
    (is (= [[maker-address #{}]] (maker-items result))
        "one work item per [address context]")
    (is (= 2 (count (::completion/work-items result))))
    (is (= :complete (get-in result [:yin.k/requires :yin.k/discovery])))))


;; =============================================================================
;; 5. Missing parked ids — the §12.1 split
;; =============================================================================

(deftest code-side-missing-pid-is-incomplete
  (let [machine (load-ast (new-vm)
                          {:type :vm/resume, :parked-id :parked-99,
                           :val (lit 1)})
        result (completion/complete {:vm machine})]
    (is (= :incomplete (get-in result [:yin.k/requires :yin.k/discovery])))
    (is (= #{:parked-99} (get-in result [:yin.k/missing :parked])))
    (is (empty? (:yin.k/refusals result))
        "an unsatisfied requirement, not an encoding failure")))


(deftest value-side-missing-pid-is-refused
  (let [machine (load-ast (new-vm {:env {'p {:type :parked-continuation,
                                             :id :parked-99}}})
                          (lit 1))
        result (completion/complete {:vm machine})]
    (is (= [{:kind :foreign-parked-ref, :parked-id :parked-99}]
           (:yin.k/refusals result)))
    (is (empty? (get-in result [:yin.k/missing :parked])))))


(deftest present-parked-record-is-pulled
  (let [parked (vm/run (load-ast (new-vm) {:type :vm/park}))
        pid (first (keys (:parked parked)))
        machine (load-ast parked {:type :vm/resume, :parked-id pid,
                                  :val (lit 1)})
        result (completion/complete {:vm machine})]
    (is (= #{pid} (set (keys (get-in result [:yin.k/scheduler :yin.k/parked])))))
    (is (= (:id-counter machine)
           (get-in result [:yin.k/scheduler :yin.k/id-counter])))
    (is (= 2 (count (::completion/work-items result)))
        "the parked record's segment is a work item")
    (is (= :complete (get-in result [:yin.k/requires :yin.k/discovery])))))


;; =============================================================================
;; 6. Blocked
;; =============================================================================

(deftest unfetchable-segment-blocks-and-the-rest-is-walked
  (let [gone :segment/sha256-0000
        machine (-> (load-ast (maker-vm) (get-key 'a))
                    (assoc-in [:code -999]
                              {:segment -999, :length 1, :code [[23]],
                               :address gone})
                    (assoc :env {'c {:type :closure, :params [], :entry 0,
                                     :segment -999, :env {}}}))
        result (completion/complete {:vm machine})]
    (is (= :blocked (get-in result [:yin.k/requires :yin.k/discovery])))
    (is (= #{gone} (get-in result [:yin.k/missing :segments])))
    (is (contains? (get-in result [:yin.k/requires :yin.k/segments]) gone))
    (is (= 2 (count (maker-items result))) "the remainder is still walked")
    (is (= 1 (count (get-in result [:yin.k/requires :yin.k/streams]))))))


(deftest caller-fetch-answers-what-the-vm-does-not-hold
  (let [v (lower (lambda ['x] (variable 'unbound-name)))
        address (jing/segment-key v)
        machine (-> (load-ast (new-vm) (lit 1))
                    (assoc-in [:code -999]
                              {:segment -999, :length 1, :code [[23]],
                               :address address})
                    (assoc :env {'c {:type :closure, :params [], :entry 0,
                                     :segment -999, :env {}}}))
        result (completion/complete {:vm machine, :fetch {address v}})]
    (is (= :incomplete (get-in result [:yin.k/requires :yin.k/discovery])))
    (is (= '#{unbound-name} (get-in result [:yin.k/missing :obligations])))))


;; =============================================================================
;; 7. Env never discharges
;; =============================================================================

(deftest env-binding-does-not-discharge-a-free-name
  (let [machine (load-ast (new-vm {:env {'y 5}}) (variable 'y))
        result (completion/complete {:vm machine})]
    (is (= :incomplete (get-in result [:yin.k/requires :yin.k/discovery])))
    (is (= '#{y} (get-in result [:yin.k/missing :obligations])))))


(deftest primitive-without-a-profile-is-incomplete
  (let [machine (load-ast (new-vm {:primitives (assoc vm/primitives 'bare (fn [x] x))})
                          (variable 'bare))
        result (completion/complete {:vm machine})]
    (is (= :incomplete (get-in result [:yin.k/requires :yin.k/discovery])))
    (is (= '#{bare} (get-in result [:yin.k/missing :profiles])))))


;; =============================================================================
;; 8. Refusals
;; =============================================================================

(deftest ffi-pair-key-operand-is-refused
  (let [machine (load-ast (new-vm) (get-key vm/call-in-stream-key))
        result (completion/complete {:vm machine})]
    (is (= [{:kind :ffi-pair-key, :key vm/call-in-stream-key}]
           (:yin.k/refusals result)))
    (is (empty? (:yin.k/store result)) "the pair is never pulled")))


(deftest host-functions-in-values
  (testing "a primitive reached as a value is named by reverse lookup"
    (let [machine (load-ast (new-vm {:env {'plus +}}) (lit 1))
          result (completion/complete {:vm machine})]
      (is (contains? (get-in result [:yin.k/requires :yin.k/primitives]) '+))
      (is (empty? (:yin.k/refusals result)))))
  (testing "a function no registry names is refused"
    (let [machine (load-ast (new-vm {:env {'f (fn [x] x)}}) (lit 1))]
      (is (= [{:kind :unnamed-function}]
             (:yin.k/refusals (completion/complete {:vm machine})))))))


(deftest early-refusals
  (testing "an image without an address has nothing portable to name"
    (let [machine (semantic/vm-load-program
                    (new-vm) (linearize/lower-ast (lit 1)) vm/semantic-contract)
          result (completion/complete {:vm machine})]
      (is (= [:unaddressed-segment] (map :kind (:yin.k/refusals result))))))
  (testing "a non-empty ready queue is not quiescent"
    (let [machine (assoc (load-ast (new-vm) (lit 1)) :ready-queue [{}])]
      (is (= [{:kind :yin.k/not-quiescent}]
             (:yin.k/refusals (completion/complete {:vm machine})))))))


;; =============================================================================
;; Modules and wait entries
;; =============================================================================

(deftest module-names
  (let [modules (-> (module/default-registry)
                    (module/register-stream-module))
        machine (load-ast (new-vm {:modules modules}) (variable 'stream/make))]
    (testing "an undeclared footprint is :incomplete"
      (let [result (completion/complete {:vm machine})]
        (is (= :incomplete (get-in result [:yin.k/requires :yin.k/discovery])))
        (is (= '#{stream} (get-in result [:yin.k/missing :footprints])))))
    (testing "a declared footprint contributes its manifest and effects"
      (let [result (completion/complete
                     {:vm machine,
                      :modules {'stream {:yin.k/manifest :segment/sha256-m,
                                         :yin.k/effects #{:stream/make}}}})
            requires (:yin.k/requires result)]
        (is (= :complete (:yin.k/discovery requires)))
        (is (= {'stream :segment/sha256-m} (:yin.k/modules requires)))
        (is (= #{:stream/make} (:yin.k/effects requires)))))))


(deftest blocked-reader-wait-entry-is-a-root
  (let [machine (vm/run
                  (load-ast (new-vm)
                            (app (lambda ['s]
                                         {:type :stream/next,
                                          :source {:type :stream/cursor,
                                                   :source (variable 's)}})
                                 {:type :stream/make, :buffer 8})))
        result (completion/complete {:vm machine,
                                     :cursor-profile (constantly :ringbuffer)})
        requires (:yin.k/requires result)]
    (is (vm/blocked? machine))
    (is (= 1 (count (:yin.k/streams requires))))
    (is (= #{:ringbuffer} (:yin.k/cursor-profiles requires)))
    (is (= 2 (count (:yin.k/resources result)))
        "the cursor entry and its stream, beside the store slice")
    (is (empty? (:yin.k/store result)))
    (is (not-any? completion/ffi-pair-keys (keys (:yin.k/resources result))))
    (is (= #{#{} #{[:stream (first (:yin.k/streams requires))]}}
           (set (map second (::completion/work-items result))))
        "the entry's frame captures the stream; its K frame captures nothing")
    (is (= :complete (:yin.k/discovery requires)))))
