(ns dao.await-test
  "Tests for dao.await — homomorphic async syntax compiled into Yin VM programs.

   Tests follow TDD: written before implementation. They drive the public API
   defined in docs/design/dao.await.md V1: explicit cursors and explicit :env."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.await :as await]
    [dao.stream :as ds]
    [dao.stream.ringbuffer])
  #?(:cljs
     (:require-macros
       [dao.await])))


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
    (let [s (ds/open! {:type :ringbuffer, :capacity 10})
          _ (ds/put! s 42)
          proc (await/go {:env {'s s}} (let [c (await/cursor s)] (await/<! c)))
          {:keys [value blocked?]} (await/run proc)]
      (is (false? blocked?))
      (is (= 42 value)))))


(deftest write-to-stream-test
  (testing "await/>! appends to the stream and the program sees the value"
    (let [out (ds/open! {:type :ringbuffer, :capacity 10})
          proc (await/go {:env {'out out}} (await/>! out :hello))
          {:keys [value blocked?]} (await/run proc)]
      (is (false? blocked?))
      (is (= :hello value) "the write returns the value written")
      (let [c {:position 0}
            {:keys [ok]} (ds/next out c)]
        (is (= :hello ok) "the host can read what the program wrote")))))


(deftest read-then-write-test
  (testing "a go body can read, transform, and write"
    (let
      [in (ds/open! {:type :ringbuffer, :capacity 10})
       out (ds/open! {:type :ringbuffer, :capacity 10})
       _ (ds/put! in 10)
       ;; go* with quoted body: arithmetic on the value read from the
       ;; stream is meaningful at runtime but trips kondo's type inference
       ;; in the host scope (where <! returns the effect descriptor).
       proc
       (await/go*
         '[(let [c (await/cursor in) x (await/<! c)] (await/>! out (+ x 1)))]
         {'in in, 'out out})
       {:keys [value]} (await/run proc)]
      (is (= 11 value))
      (let [{:keys [ok]} (ds/next out {:position 0})] (is (= 11 ok))))))


;; =============================================================================
;; Blocking and resume
;; =============================================================================

(deftest empty-read-blocks-test
  (testing "reading from an empty open stream blocks"
    (let [s (ds/open! {:type :ringbuffer, :capacity 10})
          proc (await/go {:env {'s s}} (let [c (await/cursor s)] (await/<! c)))
          result (await/run proc)]
      (is (true? (:blocked? result)))
      (is (= :yin/blocked (:value result))))))


(deftest blocked-read-resumes-after-put-test
  (testing "a blocked read resumes after the host writes a value"
    (let [s (ds/open! {:type :ringbuffer, :capacity 10})
          proc (await/go {:env {'s s}} (let [c (await/cursor s)] (await/<! c)))
          blocked (await/run proc)
          _ (is (true? (:blocked? blocked)))
          {:keys [woke]} (ds/put! s 99)
          done (await/resume blocked {:woke woke})]
      (is (false? (:blocked? done)))
      (is (= 99 (:value done))))))


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
    (let [s (ds/open! {:type :ringbuffer, :capacity 10})
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
    (let [s (ds/open! {:type :ringbuffer, :capacity 10})
          proc (await/go {:env {'s s}} (await/cursor s))
          {:keys [value]} (await/run proc)]
      (is (= :cursor-ref (:type value)))
      (is (keyword? (:id value))))))


;; =============================================================================
;; Ordering: multiple reads via the same cursor advance correctly
;; =============================================================================

(deftest cursor-advances-across-reads-test
  (testing "reads through the same cursor see successive values"
    (let [s (ds/open! {:type :ringbuffer, :capacity 10})
          _ (ds/put! s :a)
          _ (ds/put! s :b)
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
