(ns yin.vm.ucf-test
  "UCF Phase 1 (`docs/design/yin.vm.universal-continuation-format.md` S7.3,
   S7.4): the canonical instruction vector, its address and contract stamp,
   and the static safepoint map -- checked against the reference machine.

   Fixtures are S2.7-shaped batches assembled by hand, as in
   `yin.vm.semantic-test`, so the resolved interpretation under test is the
   loader's own."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]
            [yin.vm.ucf :as ucf]))


;; =============================================================================
;; Hand assembly
;; =============================================================================

(def ^:private seg -1)


(defn- eid
  [pc]
  (- -10 pc))


(defn- entity-datoms
  [e attr-values]
  (mapv (fn [[a v]] [e a v 0 datom/default-op]) attr-values))


(defn- segment
  ([length] (segment length []))
  ([length extra]
   (entity-datoms seg (into [[:yin.code/type :segment]
                             [:yin.code/length length]]
                            extra))))


(defn- instruction*
  [e pc op operands]
  (entity-datoms e (into [[:yin.code/segment seg] [:yin.code/pc pc]
                          [:yin.code/op op]]
                         (partition 2 operands))))


(defn- instruction
  [pc op & operands]
  (instruction* (eid pc) pc op operands))


(defn- assemble
  [& parts]
  (vec (apply concat parts)))


(defn- make-vm
  ([] (make-vm {}))
  ([opts] (semantic/create-vm (merge {:make-stream tu/make-stream} opts))))


(defn- ex-data-of
  [thunk]
  (try (thunk) nil
       (catch #?(:cljd Object :clj Exception :cljs js/Error) e
         (ex-data e))))


(defn- worked-segment
  "`((fn [x] (+ x 1)) 10)` as lowered in yin.vm.semantic.md S2.7."
  []
  (assemble (segment 14)
            (instruction 0 :closure :yin.code/params '[x]
                         :yin.code/body (eid 6))
            (instruction 1 :push)
            (instruction 2 :const :yin.code/value 10)
            (instruction 3 :push)
            (instruction 4 :call :yin.code/argc 1 :yin.code/tail? false)
            (instruction 5 :halt)
            (instruction 6 :var :yin.code/name '+)
            (instruction 7 :push)
            (instruction 8 :var :yin.code/name 'x)
            (instruction 9 :push)
            (instruction 10 :const :yin.code/value 1)
            (instruction 11 :push)
            (instruction 12 :call :yin.code/argc 2 :yin.code/tail? true)
            (instruction 13 :return)))


(def ^:private worked-vector
  [[:closure '[x] 6] [:push] [:const 10] [:push] [:call 1 false] [:halt]
   [:var '+] [:push] [:var 'x] [:push] [:const 1] [:push] [:call 2 true]
   [:return]])


(defn- effects-segment
  "Every defaulted operand stated: a gensym, a stream-make, an ffi-call
   and a non-tail call, then halt."
  [& {:keys [prefix buffer argc tail?]
      :or {prefix "id", buffer vm/default-stream-capacity, argc 0,
           tail? false}}]
  (assemble (segment 8)
            (instruction 0 :gensym :yin.code/prefix prefix)
            (instruction 1 :push)
            (instruction 2 :stream-make :yin.code/buffer buffer)
            (instruction 3 :push)
            (instruction 4 :ffi-call :yin.code/ffi-op :op/add
                         :yin.code/argc argc)
            (instruction 5 :push)
            (instruction 6 :call :yin.code/argc 2 :yin.code/tail? tail?)
            (instruction 7 :halt)))


(defn- without-attrs
  "The batch with every datom on one of `attrs` removed."
  [batch attrs]
  (vec (remove (fn [[_ a]] (contains? attrs a)) batch)))


;; =============================================================================
;; S7.3.2 the canonical instruction vector
;; =============================================================================

(deftest canonical-vector-of-the-worked-segment-test
  (let [v (ucf/batch->canonical-instruction-vector (worked-segment))]
    (testing "pc is the index; tuples are mnemonic plus S2.4 operands"
      (is (= worked-vector v))
      (is (= 14 (count v))))
    (testing "No entity id, attribute keyword or provenance survives"
      (is (not-any? (fn [t] (some keyword? (rest t))) v))
      (is (every? vector? v)))
    (testing "Refs are the loader's resolved pcs"
      (is (= 6 (nth (nth v 0) 2))))
    (testing "The direct path accepts and runs what canonicalization mints"
      (let [loaded (semantic/load-vector (make-vm) v)]
        (is (= 11 (vm/value (vm/run loaded))))
        (is (= (:code (semantic/load-image (worked-segment)))
               (:code (get-in loaded [:code (:program loaded)])))
            "projection and direct paths yield one image (S7.3.4)")))))


(deftest code-identity-and-contract-stamp-test
  (let [outcome (ucf/canonicalize (worked-segment))]
    (testing "The address is dao.jing's segment key of the vector"
      (is (= :yin.k/ok (:yin.k/status outcome)))
      (is (= worked-vector (:yin.code/vector outcome)))
      (is (= (jing/segment-key worked-vector) (:yin.code/hash outcome)))
      (is (= (ucf/code-address worked-vector) (:yin.code/hash outcome)))
      (is (jing/segment-address? (:yin.code/hash outcome))))
    (testing "The stamp names the v2 contract and the envelope version"
      (is (= {:yin.code/contract "v2", :yin.k/version 0}
             (:yin.k/contract outcome)))
      (is (= ucf/contract-stamp (:yin.k/contract outcome))))))


(deftest loader-verifies-the-address-canonicalization-mints-test
  (let [address (:yin.code/hash (ucf/canonicalize (effects-segment)))
        claimed (fn [batch]
                  (into (segment 8 [[:yin.code/hash address]])
                        (remove (fn [[e]] (= seg e)) batch)))]
    (testing "A batch stating its defaults earns the claim"
      (is (= address (:address (semantic/load-image
                                 (claimed (effects-segment)))))))
    (testing "A batch omitting them earns the same claim: one canonicalizer"
      (is (= address
             (:address (semantic/load-image
                         (claimed (without-attrs
                                    (effects-segment)
                                    #{:yin.code/prefix :yin.code/buffer
                                      :yin.code/tail?})))))))
    (testing "A different segment does not"
      (is (= {:rule :hash-mismatch, :entity seg}
             (:defect (ex-data-of
                        #(semantic/load-image
                           (claimed (effects-segment :prefix "g"))))))))))


;; =============================================================================
;; Equivalence laws
;; =============================================================================

(deftest equivalence-under-relabelling-and-reordering-test
  (let [reference (ucf/canonicalize (worked-segment))
        same? (fn [batch]
                (let [o (ucf/canonicalize batch)]
                  (and (= (:yin.code/vector reference) (:yin.code/vector o))
                       (= (:yin.code/hash reference) (:yin.code/hash o)))))]
    (testing "Entity ids are relabelled: alpha-equivalent batches agree"
      (is (same? (mapv (fn [[e a v t m]]
                         [(* 1000 e)
                          a
                          (if (contains? #{:yin.code/segment :yin.code/body}
                                         a)
                            (* 1000 v)
                            v)
                          t m])
                       (worked-segment)))))
    (testing "Datom order within an entity, and the segment's place, are
              transport accidents"
      (let [by-entity (group-by first (worked-segment))
            shuffled (into []
                           (mapcat (fn [e] (vec (reverse (get by-entity e)))))
                           (conj (mapv eid (range 14)) seg))]
        (is (same? shuffled))))
    (testing "Provenance is excluded"
      (is (same? (into (worked-segment)
                       (into [[seg :yin.code/derived-from -99 0
                               datom/default-op]]
                             (map (fn [pc]
                                    [(eid pc) :yin.code/source (- -100 pc)
                                     0 datom/default-op]))
                             (range 14))))))))


(deftest equivalence-under-saturation-and-resolution-test
  (let [stated (ucf/canonicalize (effects-segment))]
    (testing "Omitted defaulted operands are materialized (S7.3.2); an
              argc is not omissible, S2.6 rule 7 requires it on the batch"
      (let [omitted (ucf/canonicalize
                      (without-attrs (effects-segment)
                                     #{:yin.code/prefix :yin.code/buffer
                                       :yin.code/tail?}))]
        (is (= [:gensym "id"] (nth (:yin.code/vector omitted) 0)))
        (is (= [:stream-make vm/default-stream-capacity]
               (nth (:yin.code/vector omitted) 2)))
        (is (= [:ffi-call :op/add 0] (nth (:yin.code/vector omitted) 4)))
        (is (= [:call 2 false] (nth (:yin.code/vector omitted) 6)))
        (is (= (:yin.code/vector stated) (:yin.code/vector omitted)))
        (is (= (:yin.code/hash stated) (:yin.code/hash omitted)))))
    (testing "A repeated attribute keeps its last value, as execution does"
      (let [twice (assemble (segment 2)
                            (instruction 0 :const :yin.code/value 1)
                            [[(eid 0) :yin.code/value 10 0
                              datom/default-op]]
                            (instruction 1 :halt))
            once (assemble (segment 2)
                           (instruction 0 :const :yin.code/value 10)
                           (instruction 1 :halt))]
        (is (= [[:const 10] [:halt]]
               (ucf/batch->canonical-instruction-vector twice)))
        (is (= (:yin.code/hash (ucf/canonicalize once))
               (:yin.code/hash (ucf/canonicalize twice))))))))


;; =============================================================================
;; Distinction laws
;; =============================================================================

(deftest distinction-test
  (let [address (fn [batch] (:yin.code/hash (ucf/canonicalize batch)))
        vector-of ucf/batch->canonical-instruction-vector
        const-program (fn [& values]
                        (assemble (segment 2)
                                  (instruction 0 :const)
                                  (mapv (fn [v]
                                          [(eid 0) :yin.code/value v 0
                                           datom/default-op])
                                        values)
                                  (instruction 1 :halt)))]
    (testing "Different constants differ"
      (is (not= (vector-of (const-program 1)) (vector-of (const-program 2))))
      (is (not= (address (const-program 1)) (address (const-program 2)))))
    (testing "Resolution order is identity: 1,2 and 2,1 execute differently"
      (is (= [[:const 2] [:halt]] (vector-of (const-program 1 2))))
      (is (= [[:const 1] [:halt]] (vector-of (const-program 2 1))))
      (is (not= (address (const-program 1 2)) (address (const-program 2 1)))))
    (testing "A tail call is not a call"
      (is (not= (address (effects-segment :tail? true))
                (address (effects-segment :tail? false)))))
    (testing "A gensym prefix, a buffer, and an argc are identity"
      (is (not= (address (effects-segment)) (address (effects-segment
                                                       :prefix "g"))))
      (is (not= (address (effects-segment)) (address (effects-segment
                                                       :buffer 1))))
      (is (not= (address (effects-segment)) (address (effects-segment
                                                       :argc 1)))))
    (testing "A different branch target is a different control graph"
      (let [branch (fn [target]
                     (assemble (segment 5)
                               (instruction 0 :const :yin.code/value true)
                               (instruction 1 :branch-false
                                            :yin.code/target (eid target))
                               (instruction 2 :const :yin.code/value 1)
                               (instruction 3 :const :yin.code/value 2)
                               (instruction 4 :halt)))]
        (is (not= (address (branch 3)) (address (branch 4))))))))


;; =============================================================================
;; Non-canonicalizable batches
;; =============================================================================

(defn- refusal-of
  [batch]
  (ex-data-of #(ucf/batch->canonical-instruction-vector batch)))


(deftest non-canonicalizable-test
  (testing "An unknown mnemonic is refused as non-portable"
    (let [bad (assemble (segment 2)
                        (instruction 0 :frobnicate)
                        (instruction 1 :halt))
          r (refusal-of bad)]
      (is (= :yin.k/non-portable (:yin.k/status r)))
      (is (= :non-canonicalizable (:yin.k/kind r)))
      (is (= r (ucf/canonicalize bad))
          "canonicalize returns the same outcome rather than throwing")))
  (testing "An operand the tuple has no slot for is refused, naming the pc"
    (let [r (refusal-of (assemble (segment 2)
                                  (instruction 0 :const :yin.code/value 1
                                               :yin.code/argc 3)
                                  (instruction 1 :halt)))]
      (is (= :non-canonicalizable (:yin.k/kind r)))
      (is (= [:pc 0] (:yin.k/path r)))
      (is (= :operand-slot (get-in r [:yin.k/defect :rule])))
      (is (= [:yin.code/argc] (get-in r [:yin.k/defect :attrs])))))
  (testing "A segment attribute beyond the folded and excluded set is refused"
    (let [r (refusal-of (assemble (segment 2 [[:yin.code/entry 1]])
                                  (instruction 0 :const :yin.code/value 1)
                                  (instruction 1 :halt)))]
      (is (= :non-canonicalizable (:yin.k/kind r)))
      (is (= [:entity seg] (:yin.k/path r)))
      (is (= :segment-attribute (get-in r [:yin.k/defect :rule])))))
  (testing "An operand of the wrong kind resolves to an inadmissible tuple"
    (let [r (refusal-of (assemble (segment 2)
                                  (instruction 0 :var :yin.code/name "x")
                                  (instruction 1 :halt)))]
      (is (= :non-canonicalizable (:yin.k/kind r)))
      (is (= :operand-kind (get-in r [:yin.k/defect :rule])))
      (is (= 0 (get-in r [:yin.k/defect :pc])))))
  (testing "A batch that is not a segment is refused with the loader's rule"
    (let [r (refusal-of (assemble (segment 2)
                                  (instruction 0 :const :yin.code/value 1)
                                  (instruction 1 :push)))]
      (is (= :non-canonicalizable (:yin.k/kind r)))
      (is (= :missing-terminator (get-in r [:yin.k/defect :rule])))))
  (testing "Nothing in the folded and excluded set is refused"
    (is (vector? (ucf/batch->canonical-instruction-vector
                   (assemble (segment 2 [[:yin.code/derived-from -50]
                                         [:yin.code/hash :segment/x]])
                             (instruction 0 :const :yin.code/value 1)
                             (instruction 1 :halt)))))))


;; =============================================================================
;; S7.4 safepoints: the static half
;; =============================================================================

(deftest transitions-cover-the-vocabulary-test
  (testing "Every S2.4 mnemonic is classified exactly once"
    (is (= #{:const :var :closure :push :call :return :jump :branch-false
             :halt :gensym :store-get :store-put :stream-make :stream-put
             :stream-cursor :stream-next :stream-close :park :resume
             :current-continuation :ffi-call}
           (set (keys ucf/transitions)))))
  (testing "The parking transitions are S7.4.1's, effectful calls included"
    (is (= #{:park :stream-next :stream-put :ffi-call :call}
           (set (keep (fn [[m k]] (when (= :parking k) m))
                      ucf/transitions)))))
  (testing "Reifying a continuation is not a safepoint"
    (is (= :step (get ucf/transitions :current-continuation)))))


(deftest safepoints-of-the-worked-segment-test
  (let [sps (ucf/safepoints worked-vector)]
    (testing "The two calls are the safepoints; resume is pc+1"
      (is (= [4 12] (mapv :yin.safepoint/at sps)))
      (is (= [5 13] (mapv :yin.safepoint/pc sps)))
      (is (= [[:call-effect] [:call-effect]]
             (mapv :yin.safepoint/reasons sps))))
    (testing "Stack effect pops operator and arguments"
      (is (= [-2 -3] (mapv :yin.safepoint/stack-effect sps))))
    (testing "Nothing is read from E after either call"
      (is (= [[] []] (mapv :yin.safepoint/lexically-required sps))))))


(deftest stack-effect-per-parking-kind-test
  (is (= 0 (ucf/stack-effect [:park])))
  (is (= 0 (ucf/stack-effect [:stream-next])))
  (is (= -1 (ucf/stack-effect [:stream-put])))
  (is (= -2 (ucf/stack-effect [:ffi-call :op/add 2])))
  (is (= -1 (ucf/stack-effect [:call 0 false])))
  (is (= -3 (ucf/stack-effect [:call 2 true])))
  (is (nil? (ucf/stack-effect [:const 1]))))


(deftest safepoint-table-and-lookup-test
  (let [v [[:stream-make 8] [:stream-cursor] [:stream-next] [:push]
           [:var 'q] [:halt]]
        table (ucf/safepoint-table v)]
    (testing "The table is keyed by the code's address and an engine profile"
      (is (= (jing/segment-key v) (:yin.safepoint/segment table)))
      (is (= :yin.vm.semantic/reference (:yin.safepoint/engine table)))
      (is (= :wasm32/v1
             (:yin.safepoint/engine (ucf/safepoint-table v :wasm32/v1))))
      (is (= [{:yin.safepoint/at 2,
               :yin.safepoint/pc 3,
               :yin.safepoint/reasons [:next],
               :yin.safepoint/stack-effect 0,
               :yin.safepoint/lexically-required '[q]}]
             (:yin.safepoint/safepoints table))))
    (testing "A pc that is not a safepoint is named"
      (is (= 3 (:yin.safepoint/pc (ucf/safepoint-at v 3))))
      (is (= {:yin.k/status :yin.k/not-at-safepoint, :yin.k/pc 4}
             (ucf/safepoint-at v 4))))))


(deftest lexically-required-test
  (let [v [[:park]
           [:var 'x]
           [:push]
           [:closure '[y] 7]
           [:push]
           [:call 1 false]
           [:halt]
           [:var 'z]
           [:push]
           [:var 'y]
           [:push]
           [:closure '[z] 13]
           [:return]
           [:var 'z]
           [:push]
           [:var 'w]
           [:return]]]
    (testing "Names read on every path from the resume pc; a closure body
              entered from its creation has its params bound"
      (is (= '[w x z] (ucf/lexically-required v 1)))
      (is (= '[w x z] (:yin.safepoint/lexically-required
                        (first (ucf/safepoints v)))))
      (is (= '[w] (ucf/lexically-required v 11))
          "z is bound by the inner closure created at 11"))
    (testing "Resuming inside a body, its params are E's business: the
              static walk reports them as read, the frame carries them"
      (is (= '[w y z] (ucf/lexically-required v 7)))
      (is (= '[w z] (ucf/lexically-required v 13)))))
  (testing "Both arms of a branch are read; a jump skips nothing reachable"
    (let [v [[:const true] [:branch-false 4] [:var 'a] [:jump 5] [:var 'b]
             [:halt]]]
      (is (= '[a b] (ucf/lexically-required v 0)))
      (is (= '[b] (ucf/lexically-required v 4)))))
  (testing "A loop terminates and reads each name once"
    (let [v [[:var 'n] [:push] [:jump 0]]]
      (is (= '[n] (ucf/lexically-required v 0)))))
  (testing "A resume transfers control: nothing after it is reachable"
    (is (= '[] (ucf/lexically-required [[:resume :parked-0] [:var 'x]
                                        [:halt]]
                                       0)))))


;; =============================================================================
;; S7.4 safepoints: conformance against the reference machine
;; =============================================================================

(defn- step-to
  "Step the loaded machine until control sits at `pc`."
  [vm pc]
  (loop [vm vm
         n 0]
    (cond
      (= pc (:pc (vm/control vm))) vm
      (< 64 n) (throw (ex-info "pc never reached" {:pc pc}))
      :else (recur (vm/step vm) (inc n)))))


(defn- observe-park
  "Run the batch to the parking instruction at `at`, take the transition,
   and return the operand depth before it with the frame the machine left:
   a wait entry, or the parked record for an explicit park."
  [vm batch at]
  (let [before (step-to (semantic/vm-load-program vm batch) at)
        after (vm/step before)
        frame (or (first (:wait-set after))
                  (first (vals (:parked after))))]
    {:depth (count (:stack before)), :frame frame, :vm after}))


(defn- conforms?
  "The static safepoint entry agrees with the observed frame: the frame's
   pc is the canonical resume pc and its stack depth is the depth before
   the instruction plus the static stack effect."
  [batch at {:keys [depth frame]}]
  (let [sp (ucf/safepoint-at (ucf/batch->canonical-instruction-vector batch)
                             (inc at))]
    (and (= at (:yin.safepoint/at sp))
         (= (:yin.safepoint/pc sp) (:pc frame))
         (= (+ depth (:yin.safepoint/stack-effect sp))
            (count (:stack frame))))))


(defn- one-slot-writer
  "A `:make-stream` whose handle answers `full` once one value sits in it,
   so a second put parks. The reader surface is the minimum the FFI pair's
   construction needs: a cursor that never advances."
  [_capacity]
  (let [slot (atom ::empty)]
    {:dao.stream/outcome :dao.stream/ok,
     :dao.stream/handle
     (reify
       stream/IDaoStreamReader
       (cursor
         [_ _anchor]
         {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor 0})

       (next
         [_ _cursor]
         {:dao.stream/outcome :dao.stream/blocked})


       stream/IDaoStreamWriter

       (append!
         [_ v]
         (if (= ::empty @slot)
           (do (reset! slot v) {:dao.stream/outcome :dao.stream/ok})
           {:dao.stream/outcome :dao.stream/full})))}))


(deftest explicit-park-conformance-test
  (let [batch (assemble (segment 4)
                        (instruction 0 :const :yin.code/value 5)
                        (instruction 1 :push)
                        (instruction 2 :park)
                        (instruction 3 :halt))
        obs (observe-park (make-vm) batch 2)]
    (is (= 1 (:depth obs)))
    (is (= 3 (:pc (:frame obs))))
    (is (conforms? batch 2 obs))))


(deftest blocked-read-conformance-test
  (let [batch (assemble (segment 6)
                        (instruction 0 :const :yin.code/value :below)
                        (instruction 1 :push)
                        (instruction 2 :stream-make :yin.code/buffer 8)
                        (instruction 3 :stream-cursor)
                        (instruction 4 :stream-next)
                        (instruction 5 :halt))
        obs (observe-park (make-vm) batch 4)]
    (is (vm/blocked? (:vm obs)))
    (is (= :next (:reason (:frame obs))))
    (is (= 1 (:depth obs)))
    (is (conforms? batch 4 obs))))


(deftest blocked-write-conformance-test
  (let [batch (assemble (segment 8)
                        (instruction 0 :stream-make :yin.code/buffer 1)
                        (instruction 1 :push)
                        (instruction 2 :push)
                        (instruction 3 :const :yin.code/value :a)
                        (instruction 4 :stream-put)
                        (instruction 5 :const :yin.code/value :b)
                        (instruction 6 :stream-put)
                        (instruction 7 :halt))
        obs (observe-park (make-vm {:make-stream one-slot-writer}) batch 6)]
    (is (vm/blocked? (:vm obs)))
    (is (= :put (:reason (:frame obs))))
    (is (= 1 (:depth obs)) "one target ref remains below the popped one")
    (is (conforms? batch 6 obs))))


(deftest ffi-call-conformance-test
  (let [batch (assemble (segment 6)
                        (instruction 0 :const :yin.code/value 1)
                        (instruction 1 :push)
                        (instruction 2 :const :yin.code/value 2)
                        (instruction 3 :push)
                        (instruction 4 :ffi-call :yin.code/ffi-op :op/add
                                     :yin.code/argc 1)
                        (instruction 5 :halt))
        obs (observe-park (make-vm) batch 4)]
    (is (vm/blocked? (:vm obs)))
    (is (= 2 (:depth obs)))
    (is (= [1] (:stack (:frame obs))) "the argument is popped, not the 1")
    (is (conforms? batch 4 obs))))


(deftest effectful-call-conformance-test
  (let [blocking-next (fn [cursor] {:effect :stream/next, :cursor cursor})
        batch (assemble (segment 7)
                        (instruction 0 :var :yin.code/name 'blocking-next)
                        (instruction 1 :push)
                        (instruction 2 :stream-make :yin.code/buffer 8)
                        (instruction 3 :stream-cursor)
                        (instruction 4 :push)
                        (instruction 5 :call :yin.code/argc 1
                                     :yin.code/tail? false)
                        (instruction 6 :halt))
        obs (observe-park (make-vm {:primitives {'blocking-next blocking-next}})
                          batch 5)]
    (is (vm/blocked? (:vm obs)))
    (is (= :next (:reason (:frame obs))))
    (is (= 2 (:depth obs)) "operator and cursor were on the stack")
    (is (= [] (:stack (:frame obs))))
    (is (conforms? batch 5 obs))))


;; =============================================================================
;; The dynamic half rides in the frame
;; =============================================================================

(deftest activation-state-and-requirements-test
  (let [v [[:stream-make 8] [:stream-cursor] [:stream-next] [:push]
           [:var 'q] [:push] [:var '+] [:halt]]
        sp (ucf/safepoint-at v 3)
        frame {:segment seg, :pc 3, :env {'q 1, 'r 2},
               :stack [:x :y], :k [{:type :return, :stack-base 1}]}]
    (testing "Static: what the code will read"
      (is (= '[+ q] (:yin.safepoint/lexically-required sp))))
    (testing "Dynamic: depth, bound names and bases come from the frame"
      (is (= {:yin.k/depth 2, :yin.k/bound '[q r], :yin.k/stack-bases [1]}
             (ucf/activation-state frame))))
    (testing "A requirement is met by E or by the rest of resolution"
      (is (= '[+] (ucf/unsatisfied-names sp frame (constantly false))))
      (is (= '[] (ucf/unsatisfied-names sp frame #{'+})))
      (is (= '[+ q]
             (ucf/unsatisfied-names sp {:env {}} (constantly false)))))))
