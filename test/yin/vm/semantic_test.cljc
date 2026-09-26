(ns yin.vm.semantic-test
  "Hand-assembled segments on the linear machine (Phase 1, no compiler).

   Every fixture is a §2.7-shaped batch: one segment entity, dense pcs,
   branch and closure targets as refs to instruction entities. The programs
   are the §5.3 lowering shapes written by hand, so the transitions being
   exercised are exactly the ones the Phase 2 linearizer will emit."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.malformed-rows :as malformed]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Hand assembly
;; =============================================================================

(def ^:private seg -1)


(defn- eid
  "The instruction entity at pc in the fixtures below."
  [pc]
  (- -10 pc))


(defn- entity-datoms
  [e attr-values]
  (mapv (fn [[a v]] [e a v 0 datom/default-op]) attr-values))


(defn- segment
  [length]
  (entity-datoms seg [[:yin.code/type :segment] [:yin.code/length length]]))


(defn- instruction
  [pc op & operands]
  (entity-datoms (eid pc)
                 (into [[:yin.code/segment seg] [:yin.code/pc pc]
                        [:yin.code/op op]]
                       (partition 2 operands))))


(defn- assemble
  [& parts]
  (vec (apply concat parts)))


(defn- make-vm
  ([] (make-vm {}))
  ([opts] (semantic/create-vm (merge {:make-stream tu/make-stream,
                                      :capability-secret tu/secret} opts))))


(defn- run-segment
  "Load one batch into a fresh VM and run it to its stop point."
  ([datoms] (run-segment (make-vm) datoms))
  ([vm datoms]
   (-> vm (semantic/vm-load-program datoms vm/semantic-contract) vm/run)))


(defn- worked-segment
  "`((fn [x] (+ x 1)) 10)` as lowered in yin.vm.semantic.md §2.7."
  []
  (assemble (segment 14)
            (instruction 0 :closure :yin.code/params '[x] :yin.code/body (eid 6))
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


;; =============================================================================
;; Construction and loading
;; =============================================================================

(deftest create-vm-test
  (let [vm (make-vm)]
    (testing "Initial state carries the FFI pair and no code"
      (is (contains? (:resources vm) vm/call-in-stream-key))
      (is (contains? (:resources vm) vm/call-out-stream-key))
      (is (contains? (:resources vm) vm/call-out-cursor-key))
      (is (= {} (:code vm)))
      (is (nil? (vm/control vm)))
      (is (nil? (vm/continuation vm)))
      (is (vm/halted? vm))
      (is (= {} (vm/environment vm)))))
  (testing "An obsolete :in-stream option is rejected before allocation"
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (make-vm {:in-stream :anything})))))


(deftest load-image-decodes-refs-and-opcodes-test
  (let [image (semantic/load-image (worked-segment))
        inst (fn [pc] (nth (:code image) pc))]
    (testing "The image is indexed by pc with the §2.4 length"
      (is (= seg (:segment image)))
      (is (= 14 (:length image)))
      (is (= 14 (count (:code image)))))
    (testing "Mnemonics decode to mechanical opcodes; refs resolve to pcs"
      (is (= [(:literal vm/opcode-table) 10] (inst 2)))
      (is (= [(:load-var vm/opcode-table) 'x] (inst 8)))
      (is (= [(:call vm/opcode-table) 1] (inst 4)))
      (is (= [(:tailcall vm/opcode-table) 2] (inst 12))
          "a :call carrying :yin.code/tail? decodes to :tailcall")
      (is (= [(:lambda vm/opcode-table) '[x] 6] (inst 0))
          "the :yin.code/body ref resolves to the body pc")
      (is (= [(:halt vm/opcode-table)] (inst 5))))))


(deftest load-image-rejects-ill-formed-batches-test
  (testing "A load error names the rule and the entity (§2.6)"
    (let [bad (assemble (segment 2)
                        (instruction 0 :const :yin.code/value 1)
                        (instruction 1 :push))]
      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error :cljd Object)
            #"missing-terminator"
            (semantic/load-image bad)))
      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error :cljd Object)
            #"missing-terminator"
            (semantic/vm-load-program (make-vm) bad vm/semantic-contract))))))


(deftest load-image-verifies-a-claimed-address-test
  (let [v [[:const 7] [:halt]]
        hashed-batch (fn [hash]
                       (assemble (entity-datoms
                                   seg [[:yin.code/type :segment]
                                        [:yin.code/length 2]
                                        [:yin.code/hash hash]])
                                 (instruction 0 :const :yin.code/value 7)
                                 (instruction 1 :halt)))]
    (testing "A batch earning its claim records the address (§2.2)"
      (is (= (jing/segment-key v)
             (:address (semantic/load-image
                         (hashed-batch (jing/segment-key v)))))))
    (testing "A false claim is a mismatch defect on both loaders, so the
              alias column is never poisoned"
      (let [tampered (hashed-batch :segment/sha256-fake)
            refuse (fn [load]
                     (try (load) nil
                          (catch #?(:clj Exception :cljs js/Error :cljd Object)
                                 e
                            (:defect (ex-data e)))))]
        (is (= {:rule :hash-mismatch, :entity seg}
               (refuse #(semantic/load-image tampered))))
        (is (= {:rule :hash-mismatch, :entity seg}
               (refuse #(semantic/vm-load-program (make-vm) tampered
                                                  vm/semantic-contract))))))))


;; =============================================================================
;; §7.1 direct path: the canonical instruction vector (U5)
;; =============================================================================

(def ^:private worked-vector
  "`((fn [x] (+ x 1)) 10)` as the canonical instruction vector of UCF
   §7.3.2 — `lower-rows` output for the §2.7 segment."
  [[:closure '[x] 6] [:push] [:const 10] [:push] [:call 1 false] [:halt]
   [:var '+] [:push] [:var 'x] [:push] [:const 1] [:push] [:call 2 true]
   [:return]])


(deftest load-vector-decodes-and-aliases-test
  (let [vm (semantic/load-vector (make-vm) worked-vector vm/semantic-contract)
        image (get-in vm [:code (:program vm)])]
    (testing "A fresh local id below the empty floor; the address aliases it"
      (is (= -1 (:program vm)))
      (is (= {(jing/segment-key worked-vector) -1} (:code-aliases vm)))
      (is (= (jing/segment-key worked-vector) (:address image))))
    (testing "The image is load-image's, [:call argc tail?] folded"
      (let [datom-image (semantic/load-image (worked-segment))]
        (is (= (:code datom-image) (:code image)))
        (is (= (:length datom-image) (:length image)))))
    (testing "Control enters at pc 0 and the machine runs"
      (is (= {:segment -1, :pc 0} (vm/control vm)))
      (is (= 11 (vm/value (vm/run vm)))))))


(deftest load-vector-mints-below-the-loaded-floor-test
  (let [vm (-> (make-vm)
               (semantic/load-vector worked-vector vm/semantic-contract)
               (semantic/load-vector [[:const 7] [:halt]]
                                     vm/semantic-contract))]
    (is (= #{-1 -16} (set (keys (:code vm)))))
    (is (= -16 (:program vm)))
    (is (= 7 (vm/value (vm/run vm))))
    (testing "The same address reloads under its aliased id"
      (let [vm' (semantic/load-vector vm worked-vector vm/semantic-contract)]
        (is (= 2 (count (:code vm'))))
        (is (= -1 (:program vm')))))))


(deftest load-vector-refuses-malformed-vectors-test
  (doseq [[name [expected v]] malformed/malformed-vectors]
    (testing name
      (let [e (try (semantic/load-vector (make-vm) v vm/semantic-contract)
                   nil
                   (catch #?(:clj Exception :cljs js/Error :cljd Object) e
                     e))]
        (is (= expected (:defect (ex-data e))))))))


(deftest live-id-conflict-fires-on-the-direct-path-test
  (let [other [[:const 7] [:halt]]]
    (testing "An :id claim over a live id is a load error"
      (is (= {:segment -9}
             (try (-> (make-vm)
                      (semantic/load-vector worked-vector
                                            vm/semantic-contract {:id -9})
                      (semantic/load-vector other
                                            vm/semantic-contract {:id -9}))
                  nil
                  (catch #?(:clj Exception :cljs js/Error :cljd Object) e
                    (select-keys (ex-data e) [:segment]))))))
    (testing "An identical reload is accepted"
      (let [vm (-> (make-vm)
                   (semantic/load-vector worked-vector
                                         vm/semantic-contract {:id -9})
                   (semantic/load-vector worked-vector
                                         vm/semantic-contract {:id -9}))]
        (is (= 1 (count (:code vm))))))))


;; =============================================================================
;; Arithmetic and closures
;; =============================================================================

(deftest arithmetic-test
  (testing "(+ 1 2)"
    (is (= 3 (vm/value (run-segment
                         (assemble (segment 8)
                                   (instruction 0 :var :yin.code/name '+)
                                   (instruction 1 :push)
                                   (instruction 2 :const :yin.code/value 1)
                                   (instruction 3 :push)
                                   (instruction 4 :const :yin.code/value 2)
                                   (instruction 5 :push)
                                   (instruction 6 :call :yin.code/argc 2
                                                :yin.code/tail? false)
                                   (instruction 7 :halt)))))))
  (testing "(* (+ 1 2) 4) nests calls"
    (is (= 12 (vm/value (run-segment
                          (assemble (segment 14)
                                    (instruction 0 :var :yin.code/name '*)
                                    (instruction 1 :push)
                                    (instruction 2 :var :yin.code/name '+)
                                    (instruction 3 :push)
                                    (instruction 4 :const :yin.code/value 1)
                                    (instruction 5 :push)
                                    (instruction 6 :const :yin.code/value 2)
                                    (instruction 7 :push)
                                    (instruction 8 :call :yin.code/argc 2
                                                 :yin.code/tail? false)
                                    (instruction 9 :push)
                                    (instruction 10 :const :yin.code/value 4)
                                    (instruction 11 :push)
                                    (instruction 12 :call :yin.code/argc 2
                                                 :yin.code/tail? false)
                                    (instruction 13 :halt))))))))


(deftest closure-call-test
  (testing "The worked segment of §2.7 evaluates to 11"
    (let [vm (run-segment (worked-segment))]
      (is (= 11 (vm/value vm)))
      (is (vm/halted? vm))
      (is (nil? (vm/continuation vm)))))
  (testing "A closure body out of line captures the defining environment"
    ;; ((fn [y] ((fn [x] (+ x y)) 5)) 10) — the inner body reads y from the
    ;; outer closure's env, through a tail call and a return.
    (is (= 15 (vm/value (run-segment
                          (assemble (segment 20)
                                    (instruction 0 :closure
                                                 :yin.code/params '[y]
                                                 :yin.code/body (eid 6))
                                    (instruction 1 :push)
                                    (instruction 2 :const :yin.code/value 10)
                                    (instruction 3 :push)
                                    (instruction 4 :call :yin.code/argc 1
                                                 :yin.code/tail? false)
                                    (instruction 5 :halt)
                                    (instruction 6 :closure
                                                 :yin.code/params '[x]
                                                 :yin.code/body (eid 12))
                                    (instruction 7 :push)
                                    (instruction 8 :const :yin.code/value 5)
                                    (instruction 9 :push)
                                    (instruction 10 :call :yin.code/argc 1
                                                 :yin.code/tail? true)
                                    (instruction 11 :return)
                                    (instruction 12 :var :yin.code/name '+)
                                    (instruction 13 :push)
                                    (instruction 14 :var :yin.code/name 'x)
                                    (instruction 15 :push)
                                    (instruction 16 :var :yin.code/name 'y)
                                    (instruction 17 :push)
                                    (instruction 18 :call :yin.code/argc 2
                                                 :yin.code/tail? false)
                                    (instruction 19 :return))))))))


(deftest under-arity-call-binds-missing-param-to-nil-test
  (testing "An under-arity call binds the missing parameter to nil rather than
            falling through to the closure's captured outer binding (§7.7.2)"
    ;; ((fn [y] ((fn [x y] y) 5)) 10) — the inner call passes only one
    ;; argument though the closure declares two; y must come back nil, not
    ;; the outer closure's y (10).
    (is (nil? (vm/value (run-segment
                          (assemble (segment 13)
                                    (instruction 0 :closure
                                                 :yin.code/params '[y]
                                                 :yin.code/body (eid 6))
                                    (instruction 1 :push)
                                    (instruction 2 :const :yin.code/value 10)
                                    (instruction 3 :push)
                                    (instruction 4 :call :yin.code/argc 1
                                                 :yin.code/tail? false)
                                    (instruction 5 :halt)
                                    (instruction 6 :closure
                                                 :yin.code/params '[x y]
                                                 :yin.code/body (eid 11))
                                    (instruction 7 :push)
                                    (instruction 8 :const :yin.code/value 5)
                                    (instruction 9 :push)
                                    (instruction 10 :call :yin.code/argc 1
                                                 :yin.code/tail? true)
                                    (instruction 11 :var :yin.code/name 'y)
                                    (instruction 12 :return))))))))


(deftest over-arity-call-drops-extra-args-test
  (testing "A call passing more arguments than the closure declares still
            drops the extras (unchanged behavior)"
    ;; ((fn [x] x) 5 99) — the closure declares one param; the extra
    ;; argument must be dropped, not bound or errored on.
    (is (= 5 (vm/value (run-segment
                         (assemble (segment 10)
                                   (instruction 0 :closure
                                                :yin.code/params '[x]
                                                :yin.code/body (eid 8))
                                   (instruction 1 :push)
                                   (instruction 2 :const :yin.code/value 5)
                                   (instruction 3 :push)
                                   (instruction 4 :const :yin.code/value 99)
                                   (instruction 5 :push)
                                   (instruction 6 :call :yin.code/argc 2
                                                :yin.code/tail? false)
                                   (instruction 7 :halt)
                                   (instruction 8 :var :yin.code/name 'x)
                                   (instruction 9 :return))))))))


;; =============================================================================
;; Branches
;; =============================================================================

(defn- if-segment
  "`(if TEST 1 2)` in the §5.3 shape: pc 4 falls into the labelled pc 5."
  [test]
  (assemble (segment 6)
            (instruction 0 :const :yin.code/value test)
            (instruction 1 :branch-false :yin.code/target (eid 4))
            (instruction 2 :const :yin.code/value 1)
            (instruction 3 :jump :yin.code/target (eid 5))
            (instruction 4 :const :yin.code/value 2)
            (instruction 5 :halt)))


(deftest branch-false-test
  (testing "A truthy test takes the fall-through"
    (is (= 1 (vm/value (run-segment (if-segment true))))))
  (testing "false and nil both take the branch target"
    (is (= 2 (vm/value (run-segment (if-segment false)))))
    (is (= 2 (vm/value (run-segment (if-segment nil)))))))


;; =============================================================================
;; Store and gensym
;; =============================================================================

(deftest store-and-gensym-test
  (testing "store-put writes the store and yields the value; store-get reads it"
    (let [vm (run-segment
               (assemble (segment 4)
                         (instruction 0 :store-put :yin.code/key 'a
                                      :yin.code/value 5)
                         (instruction 1 :push)
                         (instruction 2 :store-get :yin.code/key 'a)
                         (instruction 3 :halt)))]
      (is (= 5 (vm/value vm)))
      (is (= 5 (get (vm/store vm) 'a)))))
  (testing "gensym mints fresh ids as the counter advances"
    (is (= :id-0 (vm/value (run-segment
                             (assemble (segment 2)
                                       (instruction 0 :gensym
                                                    :yin.code/prefix "id")
                                       (instruction 1 :halt))))))
    (is (= :g-2 (vm/value (run-segment
                            (assemble (segment 6)
                                      (instruction 0 :gensym :yin.code/prefix "g")
                                      (instruction 1 :push)
                                      (instruction 2 :gensym :yin.code/prefix "g")
                                      (instruction 3 :push)
                                      (instruction 4 :gensym :yin.code/prefix "g")
                                      (instruction 5 :halt)))))
        "the third gensym sees the counter twice advanced")))


;; =============================================================================
;; Tail calls and bounded memory
;; =============================================================================

(defn- countdown
  "`(fn [n f] (if (= n 0) END (f (- n 1) f)))` self-applied at `depth`,
   with the recursive call tail or not. END occupies pc 16 and is followed
   by the return at pc 17, so a park there is resumable and a value there
   returns through the main frame. The tail call's operator is f (pc 18);
   the subtraction inside it is the non-tail call at pc 26."
  [depth end-op end-operands tail?]
  (assemble (segment 32)
            (instruction 0 :closure :yin.code/params '[n f]
                         :yin.code/body (eid 8))
            (instruction 1 :push)
            (instruction 2 :const :yin.code/value depth)
            (instruction 3 :push)
            (instruction 4 :closure :yin.code/params '[n f]
                         :yin.code/body (eid 8))
            (instruction 5 :push)
            (instruction 6 :call :yin.code/argc 2 :yin.code/tail? false)
            (instruction 7 :halt)
            (instruction 8 :var :yin.code/name '=)
            (instruction 9 :push)
            (instruction 10 :var :yin.code/name 'n)
            (instruction 11 :push)
            (instruction 12 :const :yin.code/value 0)
            (instruction 13 :push)
            (instruction 14 :call :yin.code/argc 2 :yin.code/tail? false)
            (instruction 15 :branch-false :yin.code/target (eid 18))
            (apply instruction 16 end-op end-operands)
            (instruction 17 :return)
            (instruction 18 :var :yin.code/name 'f)
            (instruction 19 :push)
            (instruction 20 :var :yin.code/name '-)
            (instruction 21 :push)
            (instruction 22 :var :yin.code/name 'n)
            (instruction 23 :push)
            (instruction 24 :const :yin.code/value 1)
            (instruction 25 :push)
            (instruction 26 :call :yin.code/argc 2 :yin.code/tail? false)
            (instruction 27 :push)
            (instruction 28 :var :yin.code/name 'f)
            (instruction 29 :push)
            (instruction 30 :call :yin.code/argc 2 :yin.code/tail? tail?)
            (instruction 31 :return)))


(def ^:private seg2
  "A second segment entity id, so a resume segment never collides with the
   segment it resumes."
  -2)


(defn- resume-segment
  "A second segment delivering `v` to the parked continuation `id`. Its
   entity ids are disjoint from segment one's, as two batches would be."
  [id v]
  (let [instruction2 (fn [pc op & operands]
                       (entity-datoms (- -20 pc)
                                      (into [[:yin.code/segment seg2]
                                             [:yin.code/pc pc]
                                             [:yin.code/op op]]
                                            (partition 2 operands))))]
    (assemble (entity-datoms seg2 [[:yin.code/type :segment]
                                   [:yin.code/length 3]])
              (instruction2 0 :const :yin.code/value v)
              (instruction2 1 :resume :yin.code/parked-id id)
              (instruction2 2 :halt))))


(deftest tail-countdown-test
  (testing "10⁵ tail calls halt with the result and an empty machine"
    (let [vm (run-segment (countdown 100000 :const [:yin.code/value "done"]
                                     true))]
      (is (= "done" (vm/value vm)))
      (is (vm/halted? vm))
      (is (nil? (vm/continuation vm)))))
  (testing "At depth 10⁵ a park point sees one frame and an empty stack"
    (let [vm (run-segment (countdown 100000 :park [] true))
          parked (vm/value vm)]
      (is (= :parked-continuation (:type parked)))
      (is (= 1 (count (:k parked)))
          "the tail call grew neither K nor St: only the main frame remains")
      (is (= [] (:stack parked)))
      (is (= 0 (get (:env parked) 'n))
          "the parked env is the body's, not the caller's")
      (is (= 17 (:pc parked)))
      (is (vm/halted? vm))
      (testing "and resuming it completes the countdown"
        (let [vm' (run-segment vm (resume-segment :parked-0 :resumed))]
          (is (= :resumed (vm/value vm')))
          (is (vm/halted? vm'))
          (is (empty? (:parked vm')))))))
  (testing "Without the tail flag the same program grows a frame per call"
    (let [vm (run-segment (countdown 1000 :park [] false))
          parked (vm/value vm)]
      (is (= 1001 (count (:k parked)))
          "the main frame plus one per non-tail recursive call")
      (testing "and resuming it unwinds every frame"
        (let [vm' (run-segment vm (resume-segment :parked-0 :unwound))]
          (is (= :unwound (vm/value vm')))
          (is (vm/halted? vm')))))))


;; =============================================================================
;; Park and resume
;; =============================================================================

(deftest park-resume-test
  (let [a (assemble (segment 4)
                    (instruction 0 :const :yin.code/value 7)
                    (instruction 1 :push)
                    (instruction 2 :park)
                    (instruction 3 :halt))
        vm (run-segment a)
        parked (vm/value vm)]
    (testing "Park halts with the machine as its value"
      (is (vm/halted? vm))
      (is (not (vm/blocked? vm)))
      (is (= {:type :parked-continuation,
              :id :parked-0,
              :segment seg,
              :pc 3,
              :env {},
              :stack [7],
              :k []}
             parked)))
    (testing "The parked continuation survives pr-str/read-string"
      (is (= parked (edn/read-string (pr-str parked)))))
    (testing "A second segment resumes the first's continuation"
      (let [vm' (run-segment vm (resume-segment :parked-0 :resumed))]
        (is (= :resumed (vm/value vm')))
        (is (vm/halted? vm'))
        (is (empty? (:parked vm')) "the resumed continuation leaves :parked")))
    (testing "Resuming an unknown parked id is a load-time-style error"
      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error :cljd Object)
            #"not found"
            (run-segment vm (resume-segment :parked-9 :x)))))))


(deftest segment-id-is-stable-test
  (let [a (assemble (segment 4)
                    (instruction 0 :const :yin.code/value :expected)
                    (instruction 1 :push)
                    (instruction 2 :park)
                    (instruction 3 :halt))
        ;; A different batch that reuses A's segment entity id — two batches
        ;; minted independently, colliding on the id continuations name.
        b (assemble (segment 3)
                    (instruction 0 :const :yin.code/value :wrong-segment)
                    (instruction 1 :park)
                    (instruction 2 :halt))
        vm (run-segment a)]
    (testing "A different image under the live id is rejected"
      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error :cljd Object)
            #"already holds different code"
            (semantic/vm-load-program vm b vm/semantic-contract))))
    (testing "The rejected load leaves the image and parked continuation intact"
      (is (= seg (:segment (vm/value vm))))
      (is (= 1 (count (:parked vm))))
      (is (= :resumed (vm/value (run-segment vm (resume-segment :parked-0 :resumed))))
          "the parked continuation still resumes into A's code, not B's"))
    (testing "An identical reload is accepted and keeps the parked continuation"
      (let [vm' (-> vm (semantic/vm-load-program a
                                                 vm/semantic-contract) vm/run)]
        (is (= (:code vm) (:code vm')))
        (is (= 2 (count (:parked vm')))
            "the reload ran to its own park without touching the first")
        (is (= :parked-1 (:id (vm/value vm'))))))))


;; =============================================================================
;; Current continuation
;; =============================================================================

(deftest current-continuation-test
  (testing "At the top of a body K holds the caller's return frame"
    (let [vm (run-segment
               (assemble (segment 8)
                         (instruction 0 :closure :yin.code/params '[x]
                                      :yin.code/body (eid 6))
                         (instruction 1 :push)
                         (instruction 2 :const :yin.code/value 5)
                         (instruction 3 :push)
                         (instruction 4 :call :yin.code/argc 1
                                      :yin.code/tail? false)
                         (instruction 5 :halt)
                         (instruction 6 :current-continuation)
                         (instruction 7 :halt)))
          reified (vm/value vm)]
      (is (= :reified-continuation (:type reified)))
      (is (= seg (:segment reified)))
      (is (= 7 (:pc reified)) "the reified pc is the pc after the instruction")
      (is (= [{:type :return, :segment seg, :pc 5, :env {}, :stack-base 0}]
             (:k reified)))
      (is (= [] (:stack reified)))
      (testing "and the reification round-trips through pr-str/read-string"
        (is (= reified (edn/read-string (pr-str reified)))))))
  (testing "Env and operand stack are captured with the frame"
    (let [vm (run-segment (make-vm {:env {'x 42}})
                          (assemble (segment 4)
                                    (instruction 0 :const :yin.code/value 10)
                                    (instruction 1 :push)
                                    (instruction 2 :current-continuation)
                                    (instruction 3 :halt)))
          reified (vm/value vm)]
      (is (= {'x 42} (:env reified)))
      (is (= [10] (:stack reified)))
      (is (= 3 (:pc reified)))
      (is (= [] (:k reified))))))


;; =============================================================================
;; Single stepping
;; =============================================================================

(deftest step-test
  (testing "An idle VM steps to itself: queued input waits for the observer"
    (is (= (:code (make-vm)) (:code (vm/step (make-vm))))))
  (testing "One step executes exactly one instruction"
    (let [vm (semantic/vm-load-program (make-vm) (worked-segment)
                                       vm/semantic-contract)
          vm' (vm/step vm)]
      (is (= {:segment seg, :pc 1} (vm/control vm')))
      (is (not (vm/halted? vm')))
      (is (= {:segment seg, :pc 2} (vm/control (vm/step vm'))))))
  (testing "The same transition code path runs to completion"
    (let [vm (semantic/vm-load-program (make-vm) (worked-segment)
                                       vm/semantic-contract)]
      (is (= 11 (vm/value (vm/run vm)))))))


;; =============================================================================
;; Reset
;; =============================================================================

(deftest reset-test
  (testing "Reset restores pc 0 with the loaded image preserved"
    (let [vm (run-segment (worked-segment))
          vm' (vm/reset vm)]
      (is (= {:segment seg, :pc 0} (vm/control vm')))
      (is (not (vm/halted? vm')))
      (is (= 11 (vm/value (vm/run vm'))))
      (is (= (:code vm) (:code vm'))))))


;; =============================================================================
;; Blocking on a stream and waking
;; =============================================================================

(deftest blocked-stream-next-wakes-test
  (let [vm (make-vm {:make-stream tu/make-stream, :capability-secret tu/secret})
        program (assemble (segment 4)
                          (instruction 0 :stream-make :yin.code/buffer 8)
                          (instruction 1 :stream-cursor)
                          (instruction 2 :stream-next)
                          (instruction 3 :halt))
        v (semantic/vm-load-program vm program vm/semantic-contract)
        blocked (vm/run v)]
    (testing "A next on an empty stream parks in the wait set"
      (is (vm/blocked? blocked))
      (is (not (vm/halted? blocked)))
      (is (= 1 (count (:wait-set blocked))))
      (is (= 3 (:pc (first (:wait-set blocked))))
          "the entry resumes at the pc after the blocked instruction"))
    (let [entry (first (:wait-set blocked))
          handle (get (:resources blocked) (:stream-id entry))]
      (stream/append! handle :hello)
      (let [woken (vm/run blocked)]
        (testing "and the appended value wakes it and lands in val"
          (is (vm/halted? woken))
          (is (= :hello (vm/value woken)))
          (is (empty? (:wait-set woken))))))))


;; =============================================================================
;; Blocked continuations are pure data
;; =============================================================================

(defn- blocked-reader-program
  "make; cursor; next — parks reading an empty stream, resuming at pc 3."
  []
  (assemble (segment 4)
            (instruction 0 :stream-make :yin.code/buffer 8)
            (instruction 1 :stream-cursor)
            (instruction 2 :stream-next)
            (instruction 3 :halt)))


(defn- one-slot-stream
  "A `:make-stream` over a handle that answers `full` while one value sits in
   it — the one append outcome a ring buffer never gives, without which a
   writer cannot park in the polling wait set. Reading the value frees the
   slot; `close!` makes later appends answer `closed`."
  [_capacity]
  (let [slot (atom ::empty)
        closed? (atom false)]
    {:dao.stream/outcome :dao.stream/ok,
     :dao.stream/handle
     (reify
       stream/IDaoStreamReader
       (cursor
         [_ _anchor]
         (if @closed?
           {:dao.stream/outcome :dao.stream/closed}
           {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor 0}))

       (next
         [_ cursor]
         (if (and (= 0 cursor) (not= ::empty @slot))
           (let [v @slot]
             (reset! slot ::empty)
             {:dao.stream/outcome :dao.stream/ok,
              :dao.stream/value v,
              :dao.stream/cursor 1})
           (if @closed?
             {:dao.stream/outcome :dao.stream/end}
             {:dao.stream/outcome :dao.stream/blocked})))


       stream/IDaoStreamWriter

       (append!
         [_ v]
         (cond
           @closed? {:dao.stream/outcome :dao.stream/closed}
           (not= ::empty @slot) {:dao.stream/outcome :dao.stream/full}
           :else (do (reset! slot v)
                     {:dao.stream/outcome :dao.stream/ok})))


       stream/IDaoStreamClosable

       (close!
         [_]
         (reset! closed? true)
         {:dao.stream/outcome :dao.stream/ok}))}))


(defn- blocked-writer-program
  "make; put :a; put :b — under a one-slot stream the second append answers
   `full` and parks, resuming at pc 7 with :b as its retry value."
  []
  (assemble (segment 8)
            (instruction 0 :stream-make :yin.code/buffer 1)
            (instruction 1 :push)
            (instruction 2 :push)
            (instruction 3 :const :yin.code/value :a)
            (instruction 4 :stream-put)
            (instruction 5 :const :yin.code/value :b)
            (instruction 6 :stream-put)
            (instruction 7 :halt)))


(defn- run-blocked-writer
  "A VM parked on the second append of the writer program."
  []
  (vm/run (semantic/vm-load-program (make-vm {:make-stream one-slot-stream})
                                    (blocked-writer-program)
                                    vm/semantic-contract)))


(defn- pure-entry?
  "True when a wait entry holds registers, reason, ids, and data — and no
   host function and no resolved stream handle."
  [entry]
  (and (not (some (fn [v] (fn? v)) (vals entry)))
       (not (contains? entry :stream))))


(deftest blocked-entries-are-pure-data-test
  (testing "A blocked reader entry carries registers and ids, nothing live"
    (let [blocked (vm/run (semantic/vm-load-program (make-vm)
                                                    (blocked-reader-program)
                                                    vm/semantic-contract))
          entry (first (:wait-set blocked))]
      (is (vm/blocked? blocked))
      (is (= {:segment seg, :pc 3, :env {}, :stack [], :k [],
              :reason :next,
              ;; The id counter is shared across prefixes: the stream took
              ;; :stream-0, so the cursor mints :cursor-1. The reference is
              ;; the one the task issued, sealed under its secret.
              :cursor-ref (engine/issue-ref blocked :cursor-ref :cursor-1),
              :stream-id :stream-0}
             entry)
          "the entry is exactly the §3.5 registers plus reason and ids")
      (is (pure-entry? entry))
      (is (= entry (edn/read-string (pr-str entry)))
          "the entry survives an EDN round-trip")
      (testing "and an entry restored from EDN still wakes by id"
        (let [shipped (edn/read-string (pr-str entry))
              handle (get (:resources blocked) (:stream-id shipped))]
          (stream/append! handle :hello)
          (let [woken (vm/run (assoc blocked :wait-set [shipped]))]
            (is (= :hello (vm/value woken)))
            (is (vm/halted? woken))
            (is (empty? (:wait-set woken))))))))
  (testing "A blocked writer entry round-trips and retries its value"
    (let [blocked (run-blocked-writer)
          entry (first (:wait-set blocked))]
      (is (vm/blocked? blocked))
      (is (= :put (:reason entry)))
      (is (= :b (:datom entry)) "the writer keeps the value it retries")
      (is (pure-entry? entry))
      (is (= entry (edn/read-string (pr-str entry))))
      (testing "freeing capacity wakes the round-tripped entry"
        (let [handle (get (:resources blocked) (:stream-id entry))
              cursor (:dao.stream/cursor
                       (stream/cursor handle stream/anchor-oldest))]
          (is (= :a (:dao.stream/value (stream/next handle cursor))))
          (let [woken (vm/run
                        (assoc blocked
                               :wait-set [(edn/read-string (pr-str entry))]))]
            (is (= :b (vm/value woken)))
            (is (vm/halted? woken)))))))
  (testing "A terminal outcome on wake fails exactly as the immediate one did"
    (let [blocked (run-blocked-writer)
          entry (first (:wait-set blocked))
          handle (get (:resources blocked) (:stream-id entry))]
      (stream/close! handle)
      (is (thrown-with-msg?
            #?(:clj Exception :cljs js/Error :cljd Object)
            #"Stream append failed"
            (vm/run blocked))
          "a parked append on a now-closed stream errors, not resumes"))))


;; =============================================================================
;; FFI through the bridge
;; =============================================================================

(deftest ffi-call-test
  (testing "An ffi-call parks, the bridge answers, the call resumes"
    (let [vm (make-vm {:make-stream tu/make-stream, :capability-secret tu/secret
                       :bridge {:op/echo identity}})
          program (assemble (segment 4)
                            (instruction 0 :const :yin.code/value 21)
                            (instruction 1 :push)
                            (instruction 2 :ffi-call :yin.code/ffi-op :op/echo
                                         :yin.code/argc 1)
                            (instruction 3 :halt))
          result (run-segment vm program)]
      (is (= 21 (vm/value result)))
      (is (vm/halted? result))
      (is (empty? (:parked result)) "the completed call leaves :parked"))))
