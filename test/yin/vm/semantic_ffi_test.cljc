(ns yin.vm.semantic-ffi-test
  "`ffi_test.cljc` on the semantic VM: `:ffi-call` parks with the pure-data
   registers after the call and waits for its correlated response in the
   polling wait set. Programs are lowered ASTs, so the call sites exercised are
   the ones `yin.vm.linearize` emits."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.linearize :as linearize]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


(defn- throws?
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


;; this suite is the AST's only producer: the trusted fresh path
(def ^:private load-ast
  (vm/fresh-code-loader
    (linearize/ast-loader semantic/vm-load-program)
    vm/ast-contract))


(defn- make-vm
  ([] (make-vm {}))
  ([opts] (semantic/create-vm
            (cond-> (merge {:make-stream tu/make-stream,
                            :capability-secret tu/secret} opts)
              ;; a supplied call-out needs the cursor a composition mints:
              ;; `:oldest`, as construction minted it before
              (:call-out opts)
              (assoc :call-out-cursor
                     (vm/mint-oldest (:call-out opts) :test))))))


(defn- run-ast
  [vm ast]
  (vm/run (load-ast vm (vm/ast->datoms ast))))


(defn- resume
  [vm]
  (vm/eval vm nil))


(defn- call-ast
  ([op] (call-ast op []))
  ([op values]
   {:type :dao.stream.apply/call,
    :op op,
    :operands (mapv (fn [v] {:type :literal, :value v}) values)}))


;; =============================================================================
;; The round trip
;; =============================================================================

(deftest installed-handlers-round-trip-test
  (testing "A host call is dispatched and its parked continuation resumes"
    (let [result (run-ast (make-vm {:bridge {:op/echo identity}})
                          (call-ast :op/echo [42]))]
      (is (vm/halted? result))
      (is (= 42 (vm/value result)))))
  (testing "Arguments are evaluated left to right before the call"
    (let [result (run-ast (make-vm {:bridge {:op/list vector}})
                          (call-ast :op/list [1 2 3]))]
      (is (= [1 2 3] (vm/value result)))))
  (testing "The result feeds the instructions after the call site"
    (let [result (run-ast (make-vm {:bridge {:op/echo identity}})
                          {:type :application,
                           :operator {:type :variable, :name '+},
                           :operands [{:type :literal, :value 1}
                                      (call-ast :op/echo [41])]})]
      (is (= 42 (vm/value result))))))


(deftest manual-bridge-step-test
  (testing "Without installed handlers the VM parks and a driver steps it"
    (let [parked (run-ast (make-vm) (call-ast :op/echo [7]))
          entry (first (:wait-set parked))]
      (is (vm/blocked? parked))
      (is (= :yin/blocked (vm/value parked)))
      (is (= 1 (count (:wait-set parked)))
          "The continuation waits in the polling wait set, not on a waiter")
      (is (= :next (:reason entry)))
      (is (contains? (:parked parked) (:call-id entry)))
      (is (= (:wait-set parked) (edn/read-string (pr-str (:wait-set parked))))
          "The response reader is registers and ids, never a closure")
      (let [attached (ffi/attach parked {:op/echo identity})
            {:keys [handled? vm]} (ffi/bridge-step attached)]
        (is (true? handled?))
        (let [done (resume vm)]
          (is (vm/halted? done))
          (is (= 7 (vm/value done)))
          (is (empty? (:parked done))))))))


(deftest error-responses-surface-as-errors-test
  (testing "An unknown op is a portable error response, not an escaping throw"
    (let [parked (run-ast (make-vm) (call-ast :op/missing [1]))
          {:keys [vm handled?]} (ffi/bridge-step (ffi/attach parked
                                                             {:op/other
                                                              identity}))]
      (is (true? handled?) "The response was delivered")
      (is (throws? (fn [] (resume vm))))))
  (testing "A failing handler is an error response too"
    (let [parked (run-ast (make-vm) (call-ast :op/boom []))
          bang (fn [] (throw (ex-info "boom" {})))
          {:keys [vm]} (ffi/bridge-step (ffi/attach parked {:op/boom bang}))]
      (is (throws? (fn [] (resume vm)))))))


(deftest a-response-for-another-call-does-not-resume-this-one-test
  (let [parked (run-ast (make-vm) (call-ast :op/echo [1]))
        call-id (:call-id (first (:wait-set parked)))
        call-out (get (:resources parked) vm/call-out-stream-key)]
    (apply2/put-response! call-out (apply2/success-response [:other call-id] 1))
    (let [still (resume parked)]
      (is (vm/blocked? still)
          "A mis-correlated response is skipped, never this call's value")
      (is (= call-id (:call-id (first (:wait-set still)))))
      (is (= [{:kind :unmatched, :response-id [:other call-id],
               :cell vm/call-out-cursor-key}]
             (first (engine/take-ffi-diagnostics still))))
      (apply2/put-response! call-out (apply2/success-response call-id 2))
      (is (= 2 (vm/value (resume still))) "Its own response resumes it"))))


(deftest a-response-for-a-woken-writer-is-left-for-its-reader-test
  (let [parked (run-ast (make-vm {:ffi-caller-id "t"}) (call-ast :op/echo [1]))
        mine (:call-id (first (:wait-set parked)))
        other ["t" :parked-9]
        call-out (get (:resources parked) vm/call-out-stream-key)
        cell (get-in parked [:resources vm/call-out-cursor-key :cursor])
        ;; a retained writer the sweep already woke: its request landed,
        ;; and it is not yet restored as the reader of `other`
        woken-writer {:request-sent true, :call-id other, :reason :put}]
    (apply2/put-response! call-out (apply2/success-response other 9))
    (apply2/put-response! call-out (apply2/success-response mine 1))
    (let [checked (engine/check-wait-set
                    (assoc parked :ready-queue [woken-writer]))]
      (is (= [mine] (map :call-id (:wait-set checked)))
          "the reader behind it keeps waiting")
      (is (= cell (get-in checked [:resources vm/call-out-cursor-key :cursor]))
          "the writer's response was not consumed")
      (is (empty? (first (engine/take-ffi-diagnostics checked)))))
    (let [checked (engine/check-wait-set parked)]
      (is (empty? (:wait-set checked)) "with no such writer, it is skipped")
      (is (= [mine] (map :call-id (:ready-queue checked))))
      (is (= [other] (map :response-id
                          (first (engine/take-ffi-diagnostics checked))))))))


;; =============================================================================
;; The step is once-only
;; =============================================================================

(defn- scripted-writer
  "A call-out handle whose append outcomes are scripted."
  [outcomes]
  (reify
    stream/IDaoStreamReader
    (cursor
      [_ _]
      {:dao.stream/outcome :dao.stream/ok,
       :dao.stream/cursor :out-0})

    (next [_ _] {:dao.stream/outcome :dao.stream/blocked})


    stream/IDaoStreamWriter

    (append!
      [_ _]
      (let [o (first @outcomes)]
        (swap! outcomes rest)
        {:dao.stream/outcome o}))))


(deftest full-retains-the-response-and-runs-the-handler-once-test
  (let [calls (atom 0)
        outcomes (atom [:dao.stream/full :dao.stream/ok])
        vm0 (make-vm {:call-in (tu/new-stream 8),
                      :call-out (scripted-writer outcomes)})
        parked (ffi/attach (run-ast vm0 (call-ast :op/count []))
                           {:op/count (fn [] (swap! calls inc))})
        cursor-before (get-in parked [:bridge :cursor])
        step1 (ffi/bridge-step parked)]
    (testing "A full response stream retains the response and the successor"
      (is (false? (:handled? step1)))
      (is (true? (:retained? step1)))
      (is (= 1 @calls))
      (is (= cursor-before (get-in (:vm step1) [:bridge :cursor])))
      (is (some? (get-in (:vm step1) [:bridge :pending-response]))))
    (testing "The retry appends the retained response without re-running"
      (let [step2 (ffi/bridge-step (:vm step1))]
        (is (true? (:handled? step2)))
        (is (= 1 @calls) "The handler executed exactly once")
        (is (not= cursor-before (get-in (:vm step2) [:bridge :cursor])))
        (is (nil? (get-in (:vm step2) [:bridge :pending-response])))))))


(defn- gated-call-in
  "A call-in handle delegating to a real stream, whose first `gate-count`
   appends return `full` without reaching the delegate."
  [delegate gate-count]
  (let [gate (atom gate-count)]
    (reify
      stream/IDaoStreamReader
      (cursor [_ anchor] (stream/cursor delegate anchor))

      (next [_ cursor] (stream/next delegate cursor))


      stream/IDaoStreamWriter

      (append!
        [_ v]
        (if (pos? @gate)
          (do (swap! gate dec)
              {:dao.stream/outcome :dao.stream/full})
          (stream/append! delegate v))))))


(deftest full-retains-the-unsent-request-test
  (let [attempts (atom [])
        call-in (reify
                  stream/IDaoStreamReader
                  (cursor
                    [_ _]
                    {:dao.stream/outcome :dao.stream/ok,
                     :dao.stream/cursor :in-0})

                  (next [_ _] {:dao.stream/outcome :dao.stream/blocked})


                  stream/IDaoStreamWriter

                  (append!
                    [_ v]
                    (swap! attempts conj v)
                    {:dao.stream/outcome :dao.stream/full}))
        parked (run-ast (make-vm {:call-in call-in, :call-out (tu/new-stream 8)})
                        (call-ast :op/echo [3]))]
    (testing "A full call-in parks the call with its request retained"
      (is (vm/blocked? parked))
      (is (= 1 (count (:wait-set parked))))
      (let [waiter (first (:wait-set parked))
            request (:datom waiter)
            [parked-id] (keys (:parked parked))]
        (is (= :put (:reason waiter)))
        (is (true? (:request-sent waiter)))
        (is (apply2/request? request))
        (is (= parked-id (apply2/request-id request) (:call-id waiter))
            "The retained request keeps the allocated id for the retry")
        (is (= :op/echo (apply2/request-op request)))
        (is (= [3] (apply2/request-args request)))))
    (testing "The polling wait set retries the identical encoded request"
      (is (vm/blocked? (resume parked)))
      (is (> (count @attempts) 1) "The wait set retried the append")
      (is (apply = @attempts)))))


(deftest full-request-is-sent-on-retry-and-answered-test
  (let [parked (run-ast (make-vm {:call-in (gated-call-in (tu/new-stream 8) 1),
                                  :call-out (tu/new-stream 8)})
                        (call-ast :op/echo [5]))]
    (testing "The retry sent the request; the call waits for its response"
      (is (vm/blocked? parked))
      (is (= 1 (count (:wait-set parked))))
      (is (= :next (:reason (first (:wait-set parked))))))
    (let [{:keys [handled? request-id vm]}
          (ffi/bridge-step (ffi/attach parked {:op/echo identity}))
          done (resume vm)]
      (is (true? handled?))
      (is (= (first (keys (:parked parked))) request-id))
      (is (vm/halted? done))
      (is (= 5 (vm/value done)))
      (is (empty? (:parked done))))))


(deftest terminal-append-outcome-ends-the-bridge-test
  (let [vm0 (make-vm {:call-in (tu/new-stream 8),
                      :call-out (scripted-writer (atom [:dao.stream/closed]))})
        parked (ffi/attach (run-ast vm0 (call-ast :op/echo [1]))
                           {:op/echo identity})
        {:keys [handled? terminal vm]} (ffi/bridge-step parked)]
    (is (false? handled?))
    (is (= :dao.stream/closed terminal))
    (is (= :dao.stream/closed (:terminal (ffi/bridge-step vm))))))


;; =============================================================================
;; Reading the request side
;; =============================================================================

(deftest gap-at-the-bridge-cursor-is-fatal-test
  (testing "An evicted request can never be answered, so it is reported"
    (let [call-in (tu/new-stream 2)
          vm0 (make-vm {:call-in call-in,
                        :call-out (tu/new-stream 8),
                        :bridge {:op/echo identity}})]
      (dotimes [n 5] (stream/append! call-in (apply2/request n :op/echo [n])))
      (is (throws? (fn [] (ffi/bridge-step vm0)))))))


(deftest history-before-the-cursor-is-not-skipped-test
  (testing "A pre-filled call-in is read from oldest, not from newest"
    (let [call-in (tu/new-stream 8)
          _ (stream/append! call-in (apply2/request :pre :op/echo [:early]))
          vm0 (make-vm {:call-in call-in,
                        :call-out (tu/new-stream 8),
                        :bridge {:op/echo identity}})
          {:keys [handled? request-id]} (ffi/bridge-step vm0)]
      (is (true? handled?))
      (is (= :pre request-id)))))


;; =============================================================================
;; A VM with no pair, and repeated calls
;; =============================================================================

(deftest no-call-pair-test
  (let [bare (semantic/create-vm {})]
    (testing "A VM without :make-stream or explicit streams holds no pair"
      (is (nil? (ffi/call-pair (vm/store bare)))))
    (testing "A non-nil bridge on such a VM is a construction error"
      (is (throws? (fn [] (semantic/create-vm {:bridge {:op/echo identity}})))))
    (testing "A call fails before park-continuation"
      (is (throws? (fn [] (run-ast bare (call-ast :op/echo [1]))))))))


(deftest completed-calls-leave-no-parked-entries-test
  (let [vm0 (make-vm {:bridge {:op/echo identity}})
        first-done (run-ast vm0 (call-ast :op/echo [1]))]
    (is (empty? (:parked first-done)))
    (let [second-done (run-ast first-done (call-ast :op/echo [2]))]
      (is (vm/halted? second-done))
      (is (= 2 (vm/value second-done)))
      (is (empty? (:parked second-done))
          "Repeated host calls do not accumulate in :parked"))))
