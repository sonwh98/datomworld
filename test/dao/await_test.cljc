(ns dao.await-test
  "Tests for dao.await — the v1 await suite, ported to yin.vm and
   dao.stream.

   Same expectations as test/dao/await_test.cljc wherever the surface is
   unchanged. The v2-specific divergences asserted here: no :woke (resume
   polls the wait set instead of splicing a wake list), outcome-map reads,
   and a parked writer's retry being the poll's append."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.await :as await]
            [dao.stream :as ds]
            [dao.stream.ringbuffer :as ringbuffer])
  #?(:cljs (:require-macros [dao.await])))


(defn- new-stream
  "A v2 ring buffer handle, or throw. The test's composition choice, not
   the await layer's."
  [capacity]
  (let [result (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type,
                                    ringbuffer/capacity-key capacity})]
    (if (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/handle result)
      (throw (ex-info "Test stream creation failed" {:result result})))))


(defn- value-at-oldest
  "Read the value at the oldest-anchor cursor of handle, as data."
  [handle]
  (let [minted (ds/cursor handle ds/anchor-oldest)]
    (when (= :dao.stream/ok (:dao.stream/outcome minted))
      (let [read (ds/next handle (:dao.stream/cursor minted))]
        (when (= :dao.stream/ok (:dao.stream/outcome read))
          (:dao.stream/value read))))))


;; =============================================================================
;; Compilation: forms compile to AST without invoking the VM
;; =============================================================================

(deftest go-returns-process-descriptor-test
  (testing "go yields a :dao.await/process map with ast + datoms"
    (let [proc (await/go 42)]
      (is (= :dao.await/process (:type proc)))
      (is (some? (:ast proc)))
      (is (vector? (:datoms proc)))
      (is (seq (:datoms proc))))))


(deftest go-do-sequencing-test
  (testing "multiple body forms are sequenced like (do ...)"
    (let [proc (await/go 1 2 3)
          {:keys [value]} (await/run proc)]
      (is (= 3 value) "result of the last form is the process value"))))


(deftest go-let-binding-test
  (testing "let bindings work inside a go body"
    (let [proc (await/go (let [x 7 y 8] (+ x y)))
          {:keys [value]} (await/run proc)]
      (is (= 15 value)))))


;; =============================================================================
;; Stream operations: cursor / <! / >!
;; =============================================================================

(deftest read-prefilled-stream-test
  (testing "await/<! returns the value pre-written to the stream"
    (let [s (new-stream 10)
          _ (ds/append! s 42)
          proc (await/go {:env {'s s}} (let [c (await/cursor s)] (await/<! c)))
          {:keys [value blocked?]} (await/run proc)]
      (is (false? blocked?))
      (is (= 42 value)))))


(deftest write-to-stream-test
  (testing "await/>! appends to the stream and the program sees the value"
    (let [out (new-stream 10)
          proc (await/go {:env {'out out}} (await/>! out :hello))
          {:keys [value blocked?]} (await/run proc)]
      (is (false? blocked?))
      (is (= :hello value) "the write returns the value written")
      (is (= :hello (value-at-oldest out))
          "the host can read what the program wrote"))))


(deftest read-then-write-test
  (testing "a go body can read, transform, and write"
    (let
      [in (new-stream 10)
       out (new-stream 10)
       _ (ds/append! in 10)
       ;; go* with quoted body: arithmetic on the value read from the
       ;; stream is meaningful at runtime but trips kondo's type inference
       ;; in the host scope (where <! returns the effect descriptor).
       proc
       (await/go*
         '[(let [c (await/cursor in) x (await/<! c)] (await/>! out (+ x 1)))]
         {'in in, 'out out})
       {:keys [value]} (await/run proc)]
      (is (= 11 value))
      (is (= 11 (value-at-oldest out))))))


;; =============================================================================
;; Blocking and resume
;; =============================================================================

(deftest empty-read-blocks-test
  (testing "reading from an empty open stream blocks"
    (let [s (new-stream 10)
          proc (await/go {:env {'s s}} (let [c (await/cursor s)] (await/<! c)))
          result (await/run proc)]
      (is (true? (:blocked? result)))
      (is (= :yin/blocked (:value result))))))


(deftest blocked-read-resumes-after-put-test
  (testing "a blocked read resumes after the host writes a value"
    (let [s (new-stream 10)
          proc (await/go {:env {'s s}} (let [c (await/cursor s)] (await/<! c)))
          blocked (await/run proc)
          _ (is (true? (:blocked? blocked)))
          ;; The append wakes nothing; it just makes the next poll resolve.
          _ (ds/append! s 99)
          done (await/resume blocked)]
      (is (false? (:blocked? done)))
      (is (= 99 (:value done))))))


(deftest resume-without-data-stays-blocked-test
  (testing "resume is a poll: with nothing appended the process stays parked"
    (let [s (new-stream 10)
          proc (await/go {:env {'s s}} (let [c (await/cursor s)] (await/<! c)))
          blocked (await/run proc)
          still (await/resume blocked)]
      (is (true? (:blocked? still)))
      (is (= :yin/blocked (:value still))))))


(deftest blocked-read-resumes-to-end-on-close-test
  (testing "a read parked on a stream that then closes learns end from its
            own next, per the :stream/next contract"
    (let [s (new-stream 10)
          proc (await/go {:env {'s s}} (let [c (await/cursor s)] (await/<! c)))
          blocked (await/run proc)
          _ (is (true? (:blocked? blocked)))
          ;; close! wakes nothing either
          _ (ds/close! s)
          done (await/resume blocked)]
      (is (false? (:blocked? done)))
      (is (nil? (:value done)) "end reads as nil"))))


(deftest parked-writer-resumed-by-poll-test
  (testing "a >! that parks on full is retried by the wait-set poll's append"
    (let [append-count (atom 0)
          s (reify
              ds/IDaoStreamWriter
              (append!
                [_ _v]
                (if (= 1 (swap! append-count inc))
                  {:dao.stream/outcome :dao.stream/full}
                  {:dao.stream/outcome :dao.stream/ok})))
          proc (await/go {:env {'s s}} (await/>! s :payload))
          {:keys [value blocked?]} (await/run proc)]
      ;; The v2 ring buffer never answers full, so the handle is scripted:
      ;; full once, ok thereafter. The run's own blocked-branch poll retries
      ;; the append and resolves the writer in the same run.
      (is (false? blocked?))
      (is (= :payload value))
      (is (= 2 @append-count)
          "the parked write was re-attempted exactly once by the poll"))))


;; =============================================================================
;; Host lexical capture via explicit :env (V1 fallback per design doc)
;; =============================================================================

(deftest explicit-env-captures-host-bindings-test
  (testing "host bindings flow through :env to the await program"
    (let [my-val 123
          proc (await/go {:env {'my-val my-val}} my-val)
          {:keys [value]} (await/run proc)]
      (is (= 123 value)))))


(deftest env-without-streams-keeps-values-as-literals-test
  (testing ":env may contain non-stream values used as plain references"
    (let [;; Use go* directly so the quoted body avoids host-level symbol
          ;; resolution (the free vars x, y are bound only in the await
          ;; env).
          proc (await/go* '[(+ x y)] {'x 5, 'y 7})
          {:keys [value]} (await/run proc)]
      (is (= 12 value)))))


;; =============================================================================
;; Process construction is explicit data, not a hidden side-effect
;; =============================================================================

(deftest process-descriptor-shape-test
  (testing "process value carries ast + datoms + env"
    (let [s (new-stream 10)
          proc (await/go {:env {'s s}} (await/cursor s))]
      (is (= :dao.await/process (:type proc)))
      (is (some? (:ast proc)))
      (is (vector? (:datoms proc)))
      (is
        (= {'s s} (:env proc))
        "the env on the process is the user-supplied env (not yet store-prepared)"))))


;; =============================================================================
;; Layer boundary: cursor effect creates a cursor in the VM store
;; =============================================================================

(deftest cursor-creates-cursor-ref-test
  (testing "await/cursor evaluates to a cursor-ref"
    (let [s (new-stream 10)
          proc (await/go {:env {'s s}} (await/cursor s))
          {:keys [value]} (await/run proc)]
      (is (= :cursor-ref (:type value)))
      (is (keyword? (:id value))))))


;; =============================================================================
;; Ordering: multiple reads via the same cursor advance correctly
;; =============================================================================

(deftest cursor-advances-across-reads-test
  (testing "reads through the same cursor see successive values"
    (let [s (new-stream 10)
          _ (ds/append! s :a)
          _ (ds/append! s :b)
          proc (await/go {:env {'s s}}
                         (let [c (await/cursor s)
                               _ (await/<! c)
                               second-val (await/<! c)]
                           second-val))
          {:keys [value]} (await/run proc)]
      (is (= :b value)))))


;; =============================================================================
;; Sanity: a process descriptor is reusable (same ast/datoms across runs)
;; =============================================================================

(deftest process-is-repeatable-data-test
  (testing "the compiled ast/datoms are independent of any particular run"
    (let [proc (await/go (+ 1 2))
          r1 (await/run proc)
          r2 (await/run proc)]
      (is (= 3 (:value r1)))
      (is (= 3 (:value r2)))
      (let [proc2 (await/go (+ 1 2))]
        (is
          (identical? (:ast proc) (:ast proc2))
          "ast is cached and shared across independent invocations of the same forms")))))
