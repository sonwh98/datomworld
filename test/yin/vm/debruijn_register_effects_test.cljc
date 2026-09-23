(ns yin.vm.debruijn-register-effects-test
  "R2 (docs/design/yin.vm.debruijn.register.md S5.2): test suite for
   pure effect descriptors, sparse continuation snapshots, wait-entry
   validators, and engine seam equivalence for the de Bruijn register VM."
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn]
               :cljd [clojure.edn :as edn])
            [clojure.test :refer [deftest is testing]]
            [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn-register-effects :as effects]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]))


;; =============================================================================
;; Helpers & Fixtures
;; =============================================================================

(defn- ast-datoms
  [ast]
  (second (vm/ast->datoms-with-root ast)))


(defn- adapted
  [ast]
  (rc/adapt (ast-datoms ast)))


(defn- lit
  [v]
  {:type :literal, :value v})


(defn- v
  [s]
  {:type :variable, :name s})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- tail
  [node]
  (assoc node :tail? true))


;; A fixture program with a non-tail call having live registers
(def ^:private live-call-ast
  (app (v '+) (v 'a)
       {:type :if, :test (v 'test),
        :consequent (app (v 'f) (lit 1)),
        :alternate (lit 0)}))


;; A fixture program with a tail call
(def ^:private tail-call-ast
  (lam '[x] (tail (app (v 'f) (v 'x)))))


;; =============================================================================
;; 1. Effect descriptor tests
;; =============================================================================

(deftest effect-descriptor-table-test
  (let [regs ["reg0" "reg1" "reg2" "reg3" nil "reg5"]
        table
        [{:inst [:store-put 0 :foo "bar"]
          :expected {:effect :vm/store-put, :key :foo, :val "bar"}}
         {:inst [:stream-make 0 64]
          :expected {:effect :stream/make, :capacity 64}}
         {:inst [:stream-put 0 1 2 [3]]
          :expected {:effect :stream/put, :stream "reg1", :val "reg2"}}
         {:inst [:stream-cursor 0 1]
          :expected {:effect :stream/cursor, :stream "reg1"}}
         {:inst [:stream-next 0 2 [3]]
          :expected {:effect :stream/next, :cursor "reg2"}}
         {:inst [:stream-close 0 1]
          :expected {:effect :stream/close, :stream "reg1"}}
         {:inst [:stream-put 0 4 2 []]
          :expected {:effect :stream/put, :stream nil, :val "reg2"}}
         {:inst [:const 0 42] :expected nil}
         {:inst [:load-bound 0 0 0] :expected nil}
         {:inst [:load-free 0 'x] :expected nil}
         {:inst [:closure 0 1 10] :expected nil}
         {:inst [:call 0 1 [2] false []] :expected nil}
         {:inst [:branch-false 1 5] :expected nil}
         {:inst [:jump 10] :expected nil}
         {:inst [:return 0] :expected nil}
         {:inst [:halt 0] :expected nil}
         {:inst [:store-get 0 :foo] :expected nil}
         {:inst [:gensym 0 "id"] :expected nil}
         {:inst [:ffi-call 0 :math/add [1 2] []] :expected nil}
         {:inst [:current-continuation 0 []] :expected nil}
         {:inst [:park 0 []] :expected nil}
         {:inst [:resume :p1 1] :expected nil}]]
    (doseq [{:keys [inst expected]} table]
      (testing (str "effect-descriptor for " (first inst))
        (is (= expected (effects/effect-descriptor inst regs)))))))


(deftest stream-put-operand-order-test
  (let [regs [:stream-val :payload-val]
        inst [:stream-put 0 0 1 []]
        desc (effects/effect-descriptor inst regs)]
    (is (= :stream-val (:stream desc)))
    (is (= :payload-val (:val desc)))))


;; =============================================================================
;; 2. Continuation payload tests
;; =============================================================================

(deftest continuation-payload-construction-test
  (let [{:keys [image]} (adapted live-call-ast)
        insts (:instructions image)
        call-pc (first (keep-indexed
                         (fn [pc t]
                           (when (and (= :call (first t)) (= 3 (nth t 1)))
                             pc))
                         insts))
        call-inst (nth insts call-pc)
        reg-count (:registers (first (:bodies image)))
        test-regs (mapv #(str "val-" %) (range reg-count))
        runtime {:segment image,
                 :hash (rcode/register-hash image),
                 :pc call-pc,
                 :frames [],
                 :registers test-regs,
                 :continuation []}
        payload (effects/continuation-payload runtime call-inst)]
    (testing "payload structure and capture"
      (is (= effects/format-tag (:format payload)))
      (is (= (:hash runtime) (:hash payload)))
      (is (= call-pc (:site-pc payload)))
      (is (= (inc call-pc) (:pc payload)))
      (is (= :write-result (:resume-mode payload)))
      (is (= 3 (:dest payload)))
      (is (= [1 2] (:live payload)))
      (is (= [[1 "val-1"] [2 "val-2"]] (:regs payload)))
      (is (= [] (:frames payload)))
      (is (= [] (:continuation payload)))
      (is (nil? (effects/continuation-defect payload))))))


(deftest tail-call-continuation-payload-test
  (let [{:keys [image]} (adapted tail-call-ast)
        insts (:instructions image)
        tail-pc (first (keep-indexed
                         (fn [pc t]
                           (when (and (= :call (first t)) (true? (nth t 4)))
                             pc))
                         insts))
        tail-inst (nth insts tail-pc)
        reg-count (:registers (second (:bodies image)))
        test-regs (mapv #(str "reg-" %) (range reg-count))
        runtime {:segment image,
                 :hash (rcode/register-hash image),
                 :pc tail-pc,
                 :frames [],
                 :registers test-regs,
                 :continuation []}
        payload (effects/continuation-payload runtime tail-inst)]
    (testing "tail call payload has return-result, nil dest, empty regs"
      (is (= :return-result (:resume-mode payload)))
      (is (nil? (:dest payload)))
      (is (= [] (:live payload)))
      (is (= [] (:regs payload)))
      (is (nil? (effects/continuation-defect payload))))))


(deftest boundary-opcodes-continuation-payload-test
  (testing "continuation-payload captures all 6 boundary opcodes"
    (let [boundary-asts
          [{:op :current-continuation
            :ast {:type :vm/current-continuation}}
           {:op :park
            :ast {:type :vm/park}}
           {:op :stream-put
            :ast {:type :stream/put
                  :target (lit :s)
                  :val (lit 1)}}
           {:op :stream-next
            :ast {:type :stream/next
                  :source (lit :c)}}
           {:op :ffi-call
            :ast {:type :dao.stream.apply/call
                  :op :math/add
                  :operands [(lit 10)]}}]]
      (doseq [{:keys [op ast]} boundary-asts]
        (let [{:keys [image]} (adapted ast)
              insts (:instructions image)
              pc (first (keep-indexed
                          (fn [i t] (when (= op (first t)) i))
                          insts))
              inst (nth insts pc)
              reg-count (:registers (first (:bodies image)))
              test-regs (mapv #(str "reg-" %) (range reg-count))
              runtime {:segment image,
                       :hash (rcode/register-hash image),
                       :pc pc,
                       :frames [],
                       :registers test-regs,
                       :continuation []}
              payload (effects/continuation-payload runtime inst)]
          (testing (str "boundary opcode " op)
            (is (= effects/format-tag (:format payload)))
            (is (= pc (:site-pc payload)))
            (is (= (inc pc) (:pc payload)))
            (is (= :write-result (:resume-mode payload)))
            (is (= (nth inst 1) (:dest payload)))
            (is (= (nth inst (rcode/live-slot-index op)) (:live payload)))
            (is (nil? (effects/continuation-defect payload)))))))))


;; =============================================================================
;; 3. Continuation defect mutation matrix
;; =============================================================================

(defn- make-valid-payload
  []
  (let [{:keys [image]} (adapted live-call-ast)
        insts (:instructions image)
        call-pc (first (keep-indexed
                         (fn [pc t]
                           (when (and (= :call (first t)) (= 3 (nth t 1)))
                             pc))
                         insts))
        call-inst (nth insts call-pc)
        reg-count (:registers (first (:bodies image)))
        test-regs (mapv #(str "val-" %) (range reg-count))
        runtime {:segment image,
                 :hash (rcode/register-hash image),
                 :pc call-pc,
                 :frames [],
                 :registers test-regs,
                 :continuation []}]
    (effects/continuation-payload runtime call-inst)))


(deftest continuation-defect-mutation-matrix-test
  (let [base (make-valid-payload)]
    (testing "valid base payload passes"
      (is (nil? (effects/continuation-defect base))))

    (testing "foreign format tag"
      (is (= :continuation-format
             (:rule (effects/continuation-defect
                      (assoc base :format :stack))))))

    (testing "hash mismatch"
      (let [defect (effects/continuation-defect
                     (assoc base :hash "00000000000000000000000000000000"))]
        (is (= :continuation-hash (:rule defect)))
        (is (= (:hash base) (:expected defect)))))

    (testing "invalid segment"
      (is (= :continuation-segment
             (:rule (effects/continuation-defect
                      (assoc-in base [:segment :instructions 0 1] 9999))))))

    (testing "negative or out-of-range site-pc"
      (is (= :continuation-pc
             (:rule (effects/continuation-defect (assoc base :site-pc -1)))))
      (is (= :continuation-pc
             (:rule (effects/continuation-defect (assoc base :site-pc 9999))))))

    (testing "non-boundary site-pc"
      (is (= :continuation-pc
             (:rule (effects/continuation-defect
                      (assoc base :site-pc 0 :pc 1))))))

    (testing "pc not equal (inc site-pc)"
      (is (= :continuation-pc
             (:rule (effects/continuation-defect
                      (assoc base :pc (+ 2 (:site-pc base))))))))

    (testing "resume-mode mismatch"
      (is (= :continuation-resume-mode
             (:rule (effects/continuation-defect
                      (assoc base :resume-mode :return-result))))))

    (testing "destination mismatch"
      (is (= :continuation-destination
             (:rule (effects/continuation-defect (assoc base :dest 0))))))

    (testing "destination in live set"
      (is (= :continuation-destination
             (:rule (effects/continuation-defect (assoc base :dest 1))))))

    (testing "live indices unsorted or duplicate"
      (is (= :continuation-live
             (:rule (effects/continuation-defect (assoc base :live [2 1])))))
      (is (= :continuation-live
             (:rule (effects/continuation-defect (assoc base :live [1 1]))))))

    (testing "live indices do not match instruction"
      (is (= :continuation-live
             (:rule (effects/continuation-defect (assoc base :live [1]))))))

    (testing "regs do not match live"
      (is (= :continuation-registers
             (:rule (effects/continuation-defect
                      (assoc base :regs [[1 "val-1"]]))))))

    (testing "regs contain non-plain-data"
      (is (= :continuation-registers
             (:rule (effects/continuation-defect
                      (assoc base :regs [[1 "val-1"] [2 (atom 42)]]))))))

    (testing "regs with invalid shape (not pairs or not numbers)"
      (is (= :continuation-registers
             (:rule (effects/continuation-defect (assoc base :regs [1 2])))))
      (is (= :continuation-registers
             (:rule (effects/continuation-defect
                      (assoc base :regs [[:not-int "val"]])))))
      (is (= :continuation-registers
             (:rule (effects/continuation-defect
                      (assoc base :regs [[1 "val" :extra]]))))))

    (testing "frames not a vector"
      (is (= :continuation-frames
             (:rule (effects/continuation-defect (assoc base :frames nil))))))

    (testing "continuation not a vector"
      (is (= :continuation-continuation
             (:rule (effects/continuation-defect
                      (assoc base :continuation "not-vec"))))))

    (testing "invalid nested return frame"
      (let [bad-frame {:segment (:segment base),
                       :hash (:hash base),
                       :site-pc (:site-pc base),
                       :return-pc (:pc base),
                       :frames [],
                       :regs (:regs base),
                       :live (:live base),
                       :dest 0} ; dest mismatch (should be 3)
            with-bad-frame (assoc base :continuation [bad-frame])]
        (is (= :continuation-destination
               (:rule (effects/continuation-defect with-bad-frame))))))

    (testing "invalid nested return frame with malformed regs"
      (let [bad-regs-frame {:format effects/format-tag,
                            :segment (:segment base),
                            :hash (:hash base),
                            :site-pc (:site-pc base),
                            :return-pc (:pc base),
                            :frames [],
                            :regs [1 2],
                            :live (:live base),
                            :dest (:dest base)}
            with-bad-frame (assoc base :continuation [bad-regs-frame])]
        (is (= :continuation-registers
               (:rule (effects/continuation-defect with-bad-frame))))))))


;; =============================================================================
;; 4. Wait-entry defect tests
;; =============================================================================

(defn- make-valid-wait-entries
  []
  (let [payload (make-valid-payload)]
    {:stream-writer (assoc payload
                           :reason :put
                           :stream-id :test-stream
                           :datom {:sample "data"})
     :stream-reader (assoc payload
                           :reason :next
                           :stream-id :test-stream
                           :cursor-ref {:type :cursor-ref, :id :test-cursor})
     :ffi-writer (assoc payload
                        :request-sent true
                        :reason :put
                        :stream-id vm/call-in-stream-key
                        :call-id :call-1
                        :op :math/add
                        :datom (apply2/request :call-1 :math/add [1 2]))
     :ffi-reader (assoc payload
                        :call-id :call-1
                        :reason :next
                        :stream-id vm/call-out-stream-key
                        :cursor-ref {:type :cursor-ref,
                                     :id vm/call-out-cursor-key})}))


(deftest wait-entry-defect-test
  (let [entries (make-valid-wait-entries)]
    (doseq [[shape entry] entries]
      (testing (str "valid wait entry for " shape)
        (is (nil? (effects/wait-entry-defect entry))))))

  (testing "stale wake keys rejected on ffi-reader"
    (let [reader (:ffi-reader (make-valid-wait-entries))
          stale-keys [:value :status :cursor :store-updates :stream
                      :datom :type :id :request-sent :op]]
      (doseq [k stale-keys]
        (let [bad (assoc reader k "stale-val")
              d (effects/wait-entry-defect bad)]
          (is (= :wait-stale (:rule d))
              (str "failed to reject stale key " k))))))

  (testing "ffi-writer rejects missing or mismatched request"
    (let [writer (:ffi-writer (make-valid-wait-entries))
          bad-op (assoc writer :op :other-op)
          bad-call-id (assoc writer :call-id :other-id)]
      (is (= :wait-resource (:rule (effects/wait-entry-defect bad-op))))
      (is (= :wait-resource (:rule (effects/wait-entry-defect bad-call-id))))))

  (testing "ffi entries require correct reason"
    (let [entries (make-valid-wait-entries)
          writer (:ffi-writer entries)
          reader (:ffi-reader entries)]
      (is (= :wait-resource
             (:rule (effects/wait-entry-defect (dissoc writer :reason)))))
      (is (= :wait-resource
             (:rule (effects/wait-entry-defect (assoc writer :reason :next)))))
      (is (= :wait-resource
             (:rule (effects/wait-entry-defect (dissoc reader :reason)))))
      (is (= :wait-resource
             (:rule (effects/wait-entry-defect (assoc reader :reason :put)))))))

  (testing "response-wait-entry from ffi produces valid wait entry"
    (let [writer (:ffi-writer (make-valid-wait-entries))
          ;; Fully decorated woken writer with stale response keys
          woken-writer (assoc writer
                              :status :ok
                              :value "res"
                              :cursor :cur
                              :store-updates {}
                              :stream :st
                              :type :resp
                              :id :id1)
          reader-entry (ffi/response-wait-entry woken-writer :call-1)]
      (is (nil? (effects/wait-entry-defect reader-entry))))))


;; =============================================================================
;; 5. EDN round-trip tests
;; =============================================================================

(deftest edn-round-trip-test
  (let [payload (make-valid-payload)
        entries (make-valid-wait-entries)]
    (testing "continuation payload EDN round trip"
      (let [serialized (pr-str payload)
            deserialized (edn/read-string serialized)]
        (is (= payload deserialized))))
    (doseq [[shape entry] entries]
      (testing (str "wait entry EDN round trip for " shape)
        (let [serialized (pr-str entry)
              deserialized (edn/read-string serialized)]
          (is (= entry deserialized)))))))


;; =============================================================================
;; 6. Engine seam equivalence tests
;; =============================================================================

(deftest engine-seam-stream-descriptors-equivalence-test
  (let [regs ["stream-val" "arg-val"]
        put-inst [:stream-put 0 0 1 []]
        desc (effects/effect-descriptor put-inst regs)]
    (testing "effect descriptor matches engine handle-effect expectation"
      (is (= {:effect :stream/put, :stream "stream-val", :val "arg-val"}
             desc)))))


(deftest engine-seam-store-put-equivalence-test
  (let [put-inst [:store-put 0 :counter 42]
        desc (effects/effect-descriptor put-inst [])
        init-state {:store {:counter 0}}
        result (engine/handle-effect init-state desc {})]
    (testing "store-put descriptor matches engine expectations"
      (is (= {:effect :vm/store-put, :key :counter, :val 42} desc))
      (is (= 42 (get-in result [:state :store :counter])))
      (is (= 42 (:value result)))
      (is (false? (:blocked? result))))))


(deftest engine-seam-gensym-test
  (let [init-state {:id-counter 0}
        [id state'] (engine/gensym init-state "id")]
    (is (= :id-0 id))
    (is (= 1 (:id-counter state')))))


(deftest engine-seam-ffi-request-and-call-result-test
  (let [req (apply2/request :call-42 :math/add [10 20])
        ok-resp (apply2/success-response :call-42 30)
        err-resp (apply2/error-response :call-42 :failure "boom")]
    (testing "apply2/request construction and preservation"
      (is (= :call-42 (apply2/request-id req)))
      (is (= :math/add (apply2/request-op req)))
      (is (= [10 20] (apply2/request-args req))))
    (testing "ffi/call-result error classification"
      (is (= 30 (ffi/call-result ok-resp :call-42)))
      (is (thrown? #?(:cljd Object :cljs js/Error :default Exception)
            (ffi/call-result err-resp :call-42))))))
