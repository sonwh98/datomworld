(ns yin.vm.ffi-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.ffi :as ffi]
            [yin.vm.linearize :as linearize]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


(defn- throws?
  [thunk]
  (try (thunk) false
       (catch #?(:clj Exception :cljs js/Error :cljd Object) _ true)))


(defn- create-vm-for-caller
  "`ast-walker/create-vm`, handing a supplied `:call-out` the cursor its
   caller's composition must now mint for it: `:oldest`, as construction
   minted before the cursor became a composition input."
  [opts]
  (ast-walker/create-vm
    (cond-> opts
      (:call-out opts)
      (assoc :call-out-cursor (vm/mint-oldest (:call-out opts) :test)))))


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
    (let [result (vm/eval (tu/create-vm {:bridge {:op/echo identity}})
                          (call-ast :op/echo [42]))]
      (is (vm/halted? result))
      (is (= 42 (vm/value result)))))
  (testing "Arguments are evaluated left to right before the call"
    (let [result (vm/eval (tu/create-vm {:bridge {:op/list vector}})
                          (call-ast :op/list [1 2 3]))]
      (is (= [1 2 3] (vm/value result))))))


(deftest manual-bridge-step-test
  (testing "Without installed handlers the VM parks and a driver steps it"
    (let [parked (vm/eval (tu/create-vm) (call-ast :op/echo [7]))]
      (is (vm/blocked? parked))
      (is (= :yin/blocked (vm/value parked)))
      (is (= 1 (count (:wait-set parked)))
          "The continuation waits in the polling wait set, not on a waiter")
      (let [attached (ffi/attach parked {:op/echo identity})
            {:keys [handled? vm]} (ffi/bridge-step attached)]
        (is (true? handled?))
        (let [done (vm/eval vm nil)]
          (is (vm/halted? done))
          (is (= 7 (vm/value done))))))))


(deftest error-responses-surface-as-errors-test
  (testing "An unknown op is a portable error response, not an escaping throw"
    (let [parked (vm/eval (tu/create-vm) (call-ast :op/missing [1]))
          {:keys [vm handled?]} (ffi/bridge-step (ffi/attach parked
                                                             {:op/other
                                                              identity}))]
      (is (true? handled?) "The response was delivered")
      (is (throws? (fn [] (vm/eval vm nil))))))
  (testing "A failing handler is an error response too"
    (let [parked (vm/eval (tu/create-vm) (call-ast :op/boom []))
          bang (fn [] (throw (ex-info "boom" {})))
          {:keys [vm]} (ffi/bridge-step (ffi/attach parked {:op/boom bang}))]
      (is (throws? (fn [] (vm/eval vm nil)))))))


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
        call-in (tu/new-stream 8)
        call-out (scripted-writer outcomes)
        vm0 (create-vm-for-caller {:make-stream tu/make-stream,
                                   :capability-secret tu/secret
                                   :call-in call-in,
                                   :call-out call-out})
        ;; Handlers are attached after the call parks, so the scripted
        ;; outcomes are consumed by the explicit steps below and not by
        ;; `maybe-run` inside `eval`.
        parked (ffi/attach (vm/eval vm0 (call-ast :op/count []))
                           {:op/count (fn [] (swap! calls inc))})
        cursor-before (get-in parked [:bridge :cursor])
        step1 (ffi/bridge-step parked)]
    (testing "A full response stream retains the response and the successor"
      (is (false? (:handled? step1)))
      (is (true? (:retained? step1)))
      (is (= 1 @calls))
      (is (= cursor-before (get-in (:vm step1) [:bridge :cursor]))
          "The bridge cursor does not advance until the append succeeds")
      (is (some? (get-in (:vm step1) [:bridge :pending-response]))))
    (testing "The retry appends the retained response without re-running"
      (let [step2 (ffi/bridge-step (:vm step1))]
        (is (true? (:handled? step2)))
        (is (= 1 @calls) "The handler executed exactly once")
        (is (not= cursor-before (get-in (:vm step2) [:bridge :cursor])))
        (is (nil? (get-in (:vm step2) [:bridge :pending-response])))))))


(deftest terminal-append-outcome-ends-the-bridge-test
  (let [call-in (tu/new-stream 8)
        call-out (scripted-writer (atom [:dao.stream/closed]))
        vm0 (create-vm-for-caller {:make-stream tu/make-stream,
                                   :capability-secret tu/secret
                                   :call-in call-in,
                                   :call-out call-out})
        parked (ffi/attach (vm/eval vm0 (call-ast :op/echo [1]))
                           {:op/echo identity})
        {:keys [handled? terminal vm]} (ffi/bridge-step parked)]
    (testing "The response is reported undeliverable and the bridge ends"
      (is (false? handled?))
      (is (= :dao.stream/closed terminal))
      (is (= :dao.stream/closed (get-in vm [:bridge :terminal]))))
    (testing "A terminated bridge does not read again"
      (is (= :dao.stream/closed (:terminal (ffi/bridge-step vm)))))))


;; =============================================================================
;; Reading the request side
;; =============================================================================

(deftest gap-at-the-bridge-cursor-is-fatal-test
  (testing "An evicted request can never be answered, so it is reported"
    (let [call-in (tu/new-stream 2)
          vm0 (create-vm-for-caller {:make-stream tu/make-stream,
                                     :capability-secret tu/secret
                                     :call-in call-in,
                                     :call-out (tu/new-stream 8),
                                     :bridge {:op/echo identity}})]
      (dotimes [n 5] (stream/append! call-in (apply2/request n :op/echo [n])))
      (is (throws? (fn [] (ffi/bridge-step vm0)))))))


(deftest end-on-the-request-stream-terminates-test
  (let [call-in (tu/new-stream 4)
        vm0 (create-vm-for-caller {:make-stream tu/make-stream,
                                   :capability-secret tu/secret
                                   :call-in call-in,
                                   :call-out (tu/new-stream 4),
                                   :bridge {:op/echo identity}})]
    (stream/close! call-in)
    (is (= :dao.stream/end (:terminal (ffi/bridge-step vm0))))))


(deftest malformed-request-without-an-id-is-a-diagnostic-test
  (let [call-in (tu/new-stream 4)
        call-out (tu/new-stream 4)
        vm0 (create-vm-for-caller {:make-stream tu/make-stream,
                                   :capability-secret tu/secret
                                   :call-in call-in,
                                   :call-out call-out,
                                   :bridge {:op/echo identity}})]
    (stream/append! call-in {:not :a-request})
    (stream/append! call-in (apply2/request 1 :op/echo [:ok]))
    (let [step1 (ffi/bridge-step vm0)]
      (testing "It never reaches a handler and advances exactly once"
        (is (false? (:handled? step1)))
        (is (= :dao.stream.apply/malformed-request (:diagnostic step1))))
      (testing "The request behind it is served normally"
        (let [step2 (ffi/bridge-step (:vm step1))]
          (is (true? (:handled? step2)))
          (is (= 1 (:request-id step2))))))))


;; =============================================================================
;; A VM with no pair
;; =============================================================================

(deftest no-call-pair-test
  (let [bare (ast-walker/create-vm {})]
    (testing "A VM without :make-stream or explicit streams holds no pair"
      (is (nil? (get (:resources bare) vm/call-in-stream-key)))
      (is (nil? (ffi/call-pair (vm/store bare)))))
    (testing "A non-nil bridge on such a VM is a construction error"
      (is (throws? (fn []
                     (ast-walker/create-vm {:bridge {:op/echo
                                                     identity}})))))
    (testing "A call fails before park-continuation, stranding nothing"
      (is (throws? (fn [] (vm/eval bare (call-ast :op/echo [1])))))
      (is (empty? (:parked bare)))
      (is (zero? (:id-counter bare))))))


(deftest bridge-cursor-is-minted-not-fabricated-test
  (testing "attach mints against call-in, where the store is visible"
    (let [vm0 (tu/create-vm)
          attached (ffi/attach vm0 {:op/echo identity})]
      (is (= (:dao.stream/cursor (stream/cursor (get (:resources vm0)
                                                     vm/call-in-stream-key)
                                                stream/anchor-oldest))
             (get-in attached [:bridge :cursor]))
          "Minted at :dao.stream/oldest, whatever shape the transport gives")
      (is (nil? (:position (get-in attached [:bridge :cursor])))
          "The VM stores no position of its own")))
  (testing "normalize alone mints nothing"
    (is (= {:handlers {:op/echo identity}}
           (ffi/normalize {:op/echo identity})))
    (is (nil? (ffi/normalize nil)))))


(deftest history-before-the-cursor-is-not-skipped-test
  (testing "A pre-filled call-in is read from oldest, not from newest"
    (let [call-in (tu/new-stream 8)
          _ (stream/append! call-in (apply2/request :pre :op/echo [:early]))
          vm0 (create-vm-for-caller {:make-stream tu/make-stream,
                                     :capability-secret tu/secret
                                     :call-in call-in,
                                     :call-out (tu/new-stream 8),
                                     :bridge {:op/echo identity}})
          {:keys [handled? request-id]} (ffi/bridge-step vm0)]
      (is (true? handled?))
      (is (= :pre request-id)))))


;; =============================================================================
;; The caller's unsent request
;; =============================================================================

(defn- gated-call-in
  "A call-in handle delegating to a real stream, whose first `gate-count`
   appends return `full` without reaching the delegate. Reading and minting
   always delegate, so the bridge sees the stream once the gate opens."
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
        call-out (tu/new-stream 8)
        vm0 (create-vm-for-caller {:make-stream tu/make-stream,
                                   :capability-secret tu/secret
                                   :call-in call-in,
                                   :call-out call-out})
        parked (vm/eval vm0 (call-ast :op/echo [3]))]
    (testing "A full call-in parks the call with its request retained"
      (is (vm/blocked? parked))
      (is (= 1 (count (:wait-set parked))))
      (let [waiter (first (:wait-set parked))
            request (:datom waiter)
            [parked-id] (keys (:parked parked))]
        (is (= :put (:reason waiter)))
        (is (= :dao.stream.apply/request-sent
               (get-in waiter [:k :type])))
        (is (apply2/request? request))
        (is (= parked-id (apply2/request-id request))
            "The retained request keeps the allocated id for the retry")
        (is (= :op/echo (apply2/request-op request)))
        (is (= [3] (apply2/request-args request)))))
    (testing "The polling wait set retries the identical encoded request"
      (is (vm/blocked? (vm/eval parked nil)))
      (is (> (count @attempts) 1) "The wait set retried the append")
      (is (apply = @attempts)
          "Every retry appends the identical encoded request"))))


(deftest full-request-is-sent-on-retry-and-answered-test
  (let [delegate (tu/new-stream 8)
        call-in (gated-call-in delegate 1)
        call-out (tu/new-stream 8)
        vm0 (create-vm-for-caller {:make-stream tu/make-stream,
                                   :capability-secret tu/secret
                                   :call-in call-in,
                                   :call-out call-out})
        parked (vm/eval vm0 (call-ast :op/echo [5]))]
    (testing "The retry sent the request; the call waits for its response"
      (is (vm/blocked? parked))
      (is (= 1 (count (:wait-set parked))))
      (is (= :next (:reason (first (:wait-set parked))))
          "The waiter moved to the response side once the append succeeded"))
    (let [attached (ffi/attach parked {:op/echo identity})
          {:keys [handled? request-id vm]} (ffi/bridge-step attached)
          done (vm/eval vm nil)]
      (is (true? handled?))
      (is (= (first (keys (:parked parked))) request-id)
          "The answered request is the one the retry sent")
      (is (vm/halted? done))
      (is (= 5 (vm/value done)))
      (is (empty? (:parked done))))))


(deftest completed-calls-leave-no-parked-entries-test
  (testing "A completed host call removes the continuation it parked"
    (let [vm0 (tu/create-vm {:bridge {:op/echo identity}})
          first-done (vm/eval vm0 (call-ast :op/echo [1]))]
      (is (vm/halted? first-done))
      (is (empty? (:parked first-done)))
      (let [second-done (vm/eval first-done (call-ast :op/echo [2]))]
        (is (vm/halted? second-done))
        (is (= 2 (vm/value second-done)))
        (is (empty? (:parked second-done))
            "Repeated host calls do not accumulate in :parked")))))


;; =============================================================================
;; Caller-scoped call ids
;; =============================================================================

(def ^:private load-semantic
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(def ^:private vm-kinds
  "Each VM kind's run of `ast` on a fresh VM built from `opts`."
  {:ast-walker (fn [opts ast] (vm/eval (ast-walker/create-vm opts) ast)),
   :semantic (fn [opts ast]
               (vm/run (load-semantic (semantic/create-vm opts)
                                      (vm/ast->datoms ast)))),
   :debruijn-stack
   (fn [opts ast]
     (vm/run (dvm/create-vm (:image (dl/adapt (vm/ast->datoms ast)))
                            (assoc opts
                                   :contract vm/stack-contract
                                   :primitives vm/primitives)))),
   :debruijn-register
   (fn [opts ast]
     (vm/run (rvm/create-vm (:image (rc/adapt (second
                                                (vm/ast->datoms-with-root
                                                  ast))))
                            (assoc opts :contract vm/register-contract))))})


(deftest every-vm-kind-parks-and-resumes-under-a-composite-id-test
  (doseq [[kind run] vm-kinds]
    (testing (str kind)
      (let [parked (run {:make-stream tu/make-stream,
                         :capability-secret tu/secret,
                         :ffi-caller-id "tenure-1"}
                        (call-ast :op/echo [7]))
            call-id ["tenure-1" :parked-0]
            waiter (first (:wait-set parked))]
        (is (vm/blocked? parked))
        (is (= [call-id] (keys (:parked parked)))
            "the composite id is the parked map key: one id, no mapping")
        (is (= call-id (ffi/response-call-id waiter))
            "the response reader waits for the composite id")
        (testing "a vector parked key survives a round trip as a key"
          (is (contains? (:parked parked) (edn/read-string (pr-str call-id))))
          (is (= :v (get (edn/read-string (pr-str {call-id :v}))
                         ["tenure-1" :parked-0]))))
        (let [{:keys [handled? request-id vm]}
              (ffi/bridge-step (ffi/attach parked {:op/echo identity}))
              done (vm/run vm)]
          (is (true? handled?))
          (is (= call-id request-id) "the request carried the composite id")
          (is (vm/halted? done))
          (is (= 7 (vm/value done)))
          (is (empty? (:parked done))))))))


(deftest a-vm-without-a-caller-token-keeps-its-local-ids-test
  (let [parked (vm/eval (tu/create-vm) (call-ast :op/echo [1]))]
    (is (= [:parked-0] (keys (:parked parked))))
    (is (= :parked-0 (ffi/response-call-id (first (:wait-set parked)))))))


(deftest a-caller-token-is-plain-portable-data-test
  (is (vm/ffi-caller-id? "tenure-1"))
  (is (vm/ffi-caller-id? :tenure/one))
  (doseq [bad [nil "" "  " 7 [:a] (random-uuid)]]
    (is (not (vm/ffi-caller-id? bad)) (pr-str bad))
    (is (throws? #(ast-walker/create-vm {:ffi-caller-id bad}))))
  (is (vm/ffi-call-id? :parked-0))
  (is (vm/ffi-call-id? ["tenure-1" :parked-0]))
  (is (not (vm/ffi-call-id? ["tenure-1" 0])))
  (is (not (vm/ffi-call-id? ["" :parked-0])))
  (is (not (vm/ffi-call-id? ["tenure-1" :parked-0 :x]))))


(defn- raised-data
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e
         {:message (ex-message e), :data (ex-data e)})))


(deftest a-lost-response-raises-the-portable-ended-error-test
  (let [call-id ["tenure-1" :parked-0]
        parked-on (fn [call-out]
                    (vm/eval (create-vm-for-caller
                               {:make-stream tu/make-stream,
                                :capability-secret tu/secret,
                                :ffi-caller-id "tenure-1",
                                :call-in (tu/new-stream 8),
                                :call-out call-out})
                             (call-ast :op/echo [1])))]
    (testing "end: the response stream closed before this call was answered"
      (let [call-out (tu/new-stream 8)
            parked (parked-on call-out)
            _ (stream/close! call-out)
            {:keys [message data]} (raised-data #(vm/eval parked nil))]
        (is (= {:call-id call-id,
                :error {:dao.stream.apply/code :dao.stream.apply/ended,
                        :dao.stream.apply/message
                        "FFI response stream ended before this call was answered",
                        ::ffi/loss :dao.stream/end}}
               data))
        (is (apply2/error? (:error data)) "a valid portable apply error")
        (is (not= "FFI response envelope is malformed" message))))
    (testing "gap: responses were evicted before this call read them"
      (let [call-out (tu/new-stream 2)
            parked (parked-on call-out)
            _ (dotimes [n 3]
                (apply2/put-response! call-out
                                      (apply2/success-response [:other n] n)))
            {:keys [message data]} (raised-data #(vm/eval parked nil))]
        (is (= call-id (:call-id data)))
        (is (= :dao.stream.apply/ended
               (get-in data [:error :dao.stream.apply/code]))
            "the same terminal code")
        (is (= :dao.stream/gap (get-in data [:error ::ffi/loss]))
            "a machine key tells the gap from an end")
        (is (= "FFI response stream lost values to a gap before this call was answered"
               (get-in data [:error :dao.stream.apply/message])))
        (is (apply2/error? (:error data)))
        (is (not= "FFI response envelope is malformed" message))))))


(deftest a-supplied-call-out-needs-a-composition-cursor-test
  (let [call-in (tu/new-stream 4)
        call-out (tu/new-stream 4)]
    (testing "refused without :call-out-cursor"
      (is (throws? #(ast-walker/create-vm {:call-in call-in,
                                           :call-out call-out}))))
    (testing "refused with a cursor on no supplied call-out"
      (is (throws? #(ast-walker/create-vm
                      {:make-stream tu/make-stream,
                       :call-out-cursor (vm/mint-oldest call-out :test)}))))
    (testing "installed as given"
      (stream/append! call-out :before)
      (let [c (:dao.stream/cursor (stream/cursor call-out
                                                 stream/anchor-newest))
            built (ast-walker/create-vm {:call-in call-in,
                                         :call-out call-out,
                                         :call-out-cursor c})]
        (is (= c (get-in built [:resources vm/call-out-cursor-key
                                :cursor])))))
    (testing "a locally made pair still mints its own"
      (is (some? (get-in (tu/create-vm) [:resources vm/call-out-cursor-key
                                         :cursor]))))))
