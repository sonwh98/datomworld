(ns yin.vm.ucf.durability-test
  "The authority's durability declaration and the stage-C gate predicate
   (M-next C slice C12, plan 1.8): `authority/durability` is the opened
   journal backend's declaration as data, and `exclusive-capable?` is
   true only for a clean, unpoisoned, open authority over a locked file
   backend whose declared failure model covers the required one.  Every
   host declares :process-crash, so requiring :power-loss answers false
   everywhere."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.stream.journal :as journal]
            [dao.stream.journal.file :as file]
            [yin.vm.ucf.authority :as authority]))


(def ^:private arb "arb-c12-durability")


;; =============================================================================
;; Host helpers: a scratch directory and its cleanup
;; =============================================================================

(defn- temp-dir
  []
  (str "target/test-ucf-durability-" (random-uuid)))


(defn- cleanup-dir!
  [dir]
  #?(:cljd (try (.deleteSync (dart-io/Directory. dir) .recursive true)
                (catch Object _ nil))
     :clj (let [f (java.io.File. ^String dir)]
            (when (.isDirectory f)
              (doseq [child (.listFiles f)]
                (.delete ^java.io.File child))
              (.delete f)))
     :cljs (try (.rmSync (js/require "fs") dir
                         #js {:recursive true :force true})
                (catch :default _ nil))))


(defn- with-file-authority
  "Run (f a backend) over a fresh file-backed authority, then close the
   backend and delete its directory."
  [f]
  (let [dir (temp-dir)
        b (::file/backend (file/backend! dir))]
    (try
      (f (::authority/authority (authority/open! b)) b)
      (finally
        (file/close! b)
        (cleanup-dir! dir)))))


(defn- memory-authority
  ([] (memory-authority nil))
  ([cut]
   (::authority/authority
     (authority/open!
       (journal/memory-backend
         (atom [(cbor/encode {:dao.stream.journal/header
                              {:version 1 :identity arb}})])
         cut)))))


(defn- poison!
  "Hit the armed cut with one enrollment: the authority poisons."
  [a]
  (is (= :suspended (:yin.k/status (authority/enroll! a))))
  (is (nil? (authority/projection a)) "poisoned"))


;; =============================================================================
;; The declaration
;; =============================================================================

(deftest a-file-authority-declares-its-backend
  (with-file-authority
    (fn [a _]
      (is (= {:dao.stream.journal/backend :file
              :dao.stream.journal/failure-model :process-crash
              :dao.stream.journal/lock-kind #?(:cljd :os-lock
                                               :clj :os-lock
                                               :cljs :claim-file)
              :dao.stream.journal/persisted #{:identity :content-references}}
             (authority/durability a)))
      (is (= file/durability (authority/durability a))
          "the declaration the backend made at open, unchanged"))))


(deftest a-memory-authority-declares-nothing-survives
  (is (= journal/memory-durability (authority/durability (memory-authority)))))


(deftest the-declaration-is-kept-from-open
  (testing "a poisoned or closed authority still declares what it was"
    (let [a (memory-authority :before-frame)]
      (poison! a)
      (is (= journal/memory-durability (authority/durability a)))
      (authority/close! a)
      (is (= journal/memory-durability (authority/durability a))))))


;; =============================================================================
;; The gate predicate
;; =============================================================================

(deftest a-clean-file-authority-is-capable-against-a-process-crash
  (with-file-authority
    (fn [a _]
      (is (true? (authority/exclusive-capable? a :process-crash)))
      (is (= :committed (:yin.k/status (authority/enroll! a))))
      (is (true? (authority/exclusive-capable? a :process-crash))
          "a committed transition keeps it capable"))))


(deftest no-host-is-capable-against-power-loss
  (with-file-authority
    (fn [a _]
      (is (false? (authority/exclusive-capable? a :power-loss))
          "dao.jing.file never syncs the directory on any host"))))


(deftest a-memory-authority-is-never-capable
  (let [a (memory-authority)]
    (is (false? (authority/exclusive-capable? a :process-crash)))
    (is (false? (authority/exclusive-capable? a :power-loss)))))


(deftest a-poisoned-file-authority-is-not-capable
  (let [dir (temp-dir)
        b (::file/backend (file/backend! dir))
        ;; The cut wraps the real file seam: the frame write throws.
        cut (assoc b :dao.stream.journal/write-frame!
                   (fn [_] (throw (ex-info "cut" {}))))]
    (try
      (is (= :open (:yin.k/status (authority/open! b))) "the header lands")
      (let [a (::authority/authority (authority/open! cut))]
        (is (true? (authority/exclusive-capable? a :process-crash)))
        (poison! a)
        (is (false? (authority/exclusive-capable? a :process-crash))))
      (finally
        (file/close! b)
        (cleanup-dir! dir)))))


(deftest a-closed-file-authority-is-not-capable
  (with-file-authority
    (fn [a _]
      (authority/close! a)
      (is (false? (authority/exclusive-capable? a :process-crash))))))


(deftest a-required-model-outside-the-two-is-not-satisfied
  (with-file-authority
    (fn [a _]
      (is (false? (authority/exclusive-capable? a :none)))
      (is (false? (authority/exclusive-capable? a nil))))))


(deftest a-backend-without-a-declaration-is-not-capable
  (let [frames (atom [(cbor/encode {:dao.stream.journal/header
                                    {:version 1 :identity arb}})])
        b (dissoc (journal/memory-backend frames nil)
                  :dao.stream.journal/durability)
        a (::authority/authority (authority/open! b))]
    (is (nil? (authority/durability a)))
    (is (false? (authority/exclusive-capable? a :process-crash)))))
