(ns yin.vm.ucf.ledger-fixture-test
  "The cross-host ledger fixture of M-next C slice C12: frames written on
   the JVM and checked in (test/resources/yin/vm/ucf/ledger-v1.txt) fold
   to the pinned projection on every host, over a memory backend and over
   a file backend they are replayed into, and every host's own run of the
   script renders the same bytes, frame by frame."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.stream.journal :as journal]
            [dao.stream.journal.file :as file]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.ledger :as ledger]
            [yin.vm.ucf.ledger-fixtures :as lf]))


;; =============================================================================
;; Host helpers: a scratch directory and its cleanup
;; =============================================================================

(defn- temp-dir
  []
  (str "target/test-ucf-ledger-fixture-" (random-uuid)))


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


;; =============================================================================
;; Helpers
;; =============================================================================

(def ^:private pinned (delay (lf/read-file)))


(defn- open-projection
  "The projection an authority opened over backend `b` folds to."
  [b]
  (let [r (authority/open! b)]
    (is (= :open (:yin.k/status r)) (pr-str r))
    (authority/projection (::authority/authority r))))


(defn- facts
  "Every fact of the decoded ledger frames, as entity maps."
  [frames]
  (for [bs (rest frames)
        :let [ds (get-in (cbor/decode bs) [:dao.stream.journal/value
                                           :dao.space/transaction :datoms])]
        e (distinct (map first ds))]
    (into {} (keep (fn [[e' a v]] (when (= e e') [a v]))) ds)))


;; =============================================================================
;; The file and the script
;; =============================================================================

(deftest the-file-is-the-rendered-script
  (is (= (lf/render) (lf/read-text))))


(deftest the-script-answers-as-documented
  (is (= lf/script-answers (:answers (lf/build)))))


(deftest this-host-rebuilds-every-frame-byte-for-byte
  (let [rebuilt @(:frames (lf/build))
        pinned-frames (:frames @pinned)]
    (is (= (count pinned-frames) (count rebuilt)))
    (doseq [[n p b] (map vector (range) pinned-frames rebuilt)]
      (is (= (lf/hex p) (lf/hex b)) (str "frame " n)))))


(deftest the-ledger-holds-every-fact-kind
  (let [fs (facts (:frames @pinned))
        kinds (set (map ledger/fact-kind fs))
        edges (filter #(= :yin.k/succeeded (ledger/fact-kind %)) fs)]
    (is (= (set (keys ledger/attribute-order)) kinds))
    (is (some #(contains? % :yin.k/successor) edges) "a continuation edge")
    (is (some #(contains? % :yin.k/result) edges) "a terminal edge")))


;; =============================================================================
;; The pinned projection, over each backend
;; =============================================================================

(deftest the-frames-fold-to-the-pinned-projection-over-memory
  (let [p (open-projection
            (journal/memory-backend (atom (:frames @pinned)) nil))]
    (is (= (:digest @pinned) (lf/digest p)))
    (is (= (authority/projection (:a (lf/build))) p)
        "the same projection this host's script leaves")))


(deftest the-frames-fold-to-the-pinned-projection-over-a-file
  (let [dir (temp-dir)]
    (try
      (let [b (::file/backend (file/backend! dir))]
        (testing "replayed frame by frame into a fresh directory"
          (doseq [bs (:frames @pinned)]
            (is (= :dao.stream/ok
                   (:dao.stream/outcome
                     ((:dao.stream.journal/write-frame! b) bs))))))
        (file/close! b))
      (let [b (::file/backend (file/backend! dir))]
        (try
          (let [p (open-projection b)]
            (is (= (:digest @pinned) (lf/digest p)) "after close and reopen")
            (is (= (map lf/hex (:frames @pinned))
                   (map lf/hex (:dao.stream.journal/frames
                                 ((:dao.stream.journal/frames b)))))
                "every frame round-trips the pinned bytes")
            (is (= (lf/hex (first (:frames @pinned)))
                   (lf/hex (first (:dao.stream.journal/frames
                                    ((:dao.stream.journal/frames b))))))
                "the identity is the header's"))
          (finally (file/close! b))))
      (finally (cleanup-dir! dir)))))
