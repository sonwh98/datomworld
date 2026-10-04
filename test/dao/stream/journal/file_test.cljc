(ns dao.stream.journal.file-test
  "The journal's file backend (dao.stream.journal.file): the journal laws
   over a real directory, a hand-torn tail, the directory lock, the
   durability declaration and, on the JVM, a process killed mid-append."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.test :refer [deftest is]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.file :as jing-file]
            [dao.space.store.fs :as fs]
            [dao.stream :as stream]
            [dao.stream.conformance :as conformance]
            [dao.stream.journal :as journal]
            [dao.stream.journal.file :as file]))


;; ==========================================================================
;; Host helpers: a scratch directory, its cleanup, and a hand tear.  These
;; are the only host-aware lines in the suite.
;; ==========================================================================

(defn- temp-dir
  []
  (str "target/test-journal-file-" (random-uuid)))


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


(defn- file-length
  [path]
  #?(:cljd (.lengthSync (dart-io/File. path))
     :clj (.length (java.io.File. ^String path))
     :cljs (.-size (.statSync (js/require "fs") path))))


(defn- truncate-file!
  "Cut the file at path to n bytes, as a crash mid-write leaves it."
  [path n]
  #?(:cljd (let [raf (.openSync (dart-io/File. path)
                                .mode dart-io/FileMode.append)]
             (try (.truncateSync raf n) (finally (.closeSync raf))))
     :clj (with-open [raf (java.io.RandomAccessFile. ^String path "rw")]
            (.setLength raf (long n)))
     :cljs (.truncateSync (js/require "fs") path n)))


;; ==========================================================================
;; Journal helpers
;; ==========================================================================

(defn- outcome
  [r]
  (:dao.stream/outcome r))


(defn- backend
  [dir]
  (::file/backend (file/backend! dir)))


(defn- open
  "The journal on b's directory, its handle and identity."
  [b]
  (journal/open! b))


(defn- handle
  [b]
  (:dao.stream/handle (open b)))


(defn- cur
  [h a]
  (:dao.stream/cursor (stream/cursor h a)))


(defn- identity-of
  [h]
  (:dao.stream/identity (stream/descriptor h)))


(defn- values
  "Every value from the origin to the tail.  Bounded so a defect cannot
   hang the suite."
  [h]
  (loop [c (cur h :dao.stream/oldest) vs [] n 0]
    (let [r (stream/next h c)]
      (if (and (= :dao.stream/ok (outcome r)) (< n 1000))
        (recur (:dao.stream/cursor r) (conj vs (:dao.stream/value r)) (inc n))
        vs))))


(defn- crash!
  "Model this process dying while it owns b: the host releases the lock
   and nothing is closed."
  [b]
  (fs/unlock! (::file/lock b)))


(defn- content-path
  [dir]
  (str dir "/" file/content-name))


(defn- with-dir
  "Run (f dir) on a scratch directory, closing every backend f opens via
   the `open-backend` it is handed, then deleting the directory."
  [f]
  (let [dir (temp-dir)
        opened (atom [])]
    (try
      (f dir (fn [] (let [b (backend dir)] (swap! opened conj b) b)))
      (finally
        (doseq [b @opened] (file/close! b))
        (cleanup-dir! dir)))))


;; ==========================================================================
;; Tests
;; ==========================================================================

(deftest a-fresh-directory-opens-an-empty-journal
  (with-dir
    (fn [dir open-backend]
      (let [b (open-backend)
            r (open b)]
        (is (= :dao.stream/ok (outcome r)))
        (is (= [] (values (:dao.stream/handle r))))
        (is (= 1 (count (jing-file/records (content-path dir))))
            "the header is the only frame")
        (is (= {:dao.stream.journal/header
                {:version 1 :identity (:dao.stream/identity r)}}
               (second (first (jing-file/records (content-path dir))))))))))


(deftest dropped-without-close-the-path-reopens
  (with-dir
    (fn [_ open-backend]
      (let [b (open-backend)
            h (handle b)
            origin (cur h :dao.stream/oldest)]
        (stream/append! h :a)
        (stream/append! h {:k [1 "two" #{3}]})
        (crash! b)
        (let [h2 (handle (open-backend))]
          (is (= (identity-of h) (identity-of h2))
              "the identity survives the reopen")
          (is (= [:a {:k [1 "two" #{3}]}] (values h2)))
          (is (= origin (cur h2 :dao.stream/oldest))
              "a cursor kept from before the reopen is accepted")
          (is (= :dao.stream/ok (outcome (stream/append! h2 :c))))
          (is (= [:a {:k [1 "two" #{3}]} :c] (values h2))))))))


(deftest repeated-equal-appends-are-distinct-frames-across-reopen
  (with-dir
    (fn [dir open-backend]
      (let [b (open-backend)
            h (handle b)]
        (is (= [:dao.stream/ok :dao.stream/ok :dao.stream/ok]
               (mapv #(outcome (stream/append! h %)) [:x :x :x])))
        (let [records (jing-file/records (content-path dir))]
          (is (= 4 (count records)) "one header and three frames")
          (is (= 4 (count (set (map first records))))
              "equal values never make equal frames: no put deduplicated"))
        (file/close! b)
        (is (= [:x :x :x] (values (handle (open-backend)))))))))


(deftest decode-then-encode-is-the-identity-on-accepted-frames
  (with-dir
    (fn [dir open-backend]
      (let [h (handle (open-backend))]
        (doseq [v [nil 0 -1 4503599627370495 1.5 0.1 -0.0 "text" :k
                   #{1 2} {:m [1.0 "x" nil]} [true false]]]
          (is (= :dao.stream/ok (outcome (stream/append! h v)))))
        (doseq [[address value] (jing-file/records (content-path dir))]
          (is (jing/segment-bytes-match? address (cbor/encode value))
              (str "re-encoded bytes hash to " address)))))))


(deftest a-hand-torn-tail-is-dropped-and-the-journal-continues
  (with-dir
    (fn [dir open-backend]
      (let [b (open-backend)
            h (handle b)
            path (content-path dir)]
        (stream/append! h :a)
        (let [before-b (file-length path)]
          (stream/append! h :b)
          (crash! b)
          (truncate-file! path (- (file-length path) 3))
          (is (< before-b (file-length path)) "the cut is inside :b's frame")
          (let [h2 (handle (open-backend))]
            (is (= [:a] (values h2)) "the torn frame is dropped")
            (is (= before-b (file-length path)) "and truncated from the file")
            (is (= :dao.stream/ok (outcome (stream/append! h2 :c))))
            (is (= [:a :c] (values h2)))
            (is (= [:a :c] (mapv (comp :dao.stream.journal/value second)
                                 (rest (jing-file/records path))))
                "positions stay dense on the file")))))))


(deftest a-second-opener-of-the-directory-is-refused
  (with-dir
    (fn [dir open-backend]
      (let [b (open-backend)
            h (handle b)
            second-open (file/backend! dir)]
        (is (= :dao.stream/transport-error (outcome second-open)))
        (is (= :locked (:dao.stream.journal/defect second-open)))
        (is (= :dao.stream/ok (outcome (stream/append! h :a)))
            "the first owner keeps working")
        (file/close! b)
        (is (= [:a] (values (handle (open-backend))))
            "close releases the lock")))))


(deftest a-torn-header-refuses-the-open-and-releases-the-lock
  (with-dir
    (fn [dir open-backend]
      (let [b (open-backend)
            _ (handle b)
            path (content-path dir)]
        (file/close! b)
        (truncate-file! path (- (file-length path) 3))
        (let [first-try (file/backend! dir)
              second-try (file/backend! dir)]
          (is (= :dao.stream/transport-error (outcome first-try)))
          (is (= :open-failed (:dao.stream.journal/defect first-try)))
          (is (= :open-failed (:dao.stream.journal/defect second-try))
              "refused again for the damage, not for a leaked lock"))))))


(deftest a-present-put-poisons-the-handle
  (with-dir
    (fn [dir open-backend]
      (let [b (open-backend)
            h (handle b)
            next-frame (cbor/encode {:dao.stream.journal/position 0
                                     :dao.stream.journal/value :a})]
        (is (= :dao.stream/ok
               (outcome ((:dao.stream.journal/write-frame! b) next-frame)))
            "a frame written behind the journal's back")
        (is (= :dao.stream/transport-error (outcome (stream/append! h :a)))
            "the journal's own equal frame answers :present")
        (is (= :dao.stream/transport-error (outcome (stream/append! h :b)))
            "the handle stays poisoned")
        (is (= 2 (count (jing-file/records (content-path dir)))))
        (file/close! b)
        (is (= [:a] (values (handle (open-backend))))
            "reopen reads the one frame that exists")))))


(deftest the-durability-declaration-is-data
  (with-dir
    (fn [_ open-backend]
      (let [d ((:dao.stream.journal/durability (open-backend)))]
        (is (= file/durability d))
        (is (= :file (:dao.stream.journal/backend d)))
        (is (= :process-crash (:dao.stream.journal/failure-model d)))
        (is (= #?(:cljd :os-lock :clj :os-lock :cljs :claim-file)
               (:dao.stream.journal/lock-kind d)))
        (is (= #{:identity :content-references}
               (:dao.stream.journal/persisted d)))))))


;; ==========================================================================
;; Conformance over a file-backed journal
;; ==========================================================================

(def ^:private opened
  "Every [dir backend] the conformance fixtures open, closed and deleted
   once the suite has run."
  (atom []))


(defn- fresh-backend
  []
  (let [dir (temp-dir)
        b (backend dir)]
    (swap! opened conj [dir b])
    b))


(defn- fresh
  []
  (handle (fresh-backend)))


(def file-journal-manifest
  "The journal manifest of dao.stream.journal-test, every fixture over a
   fresh directory.  transport-error comes from an append after the
   backend is closed: the put throws."
  {:dao.stream/type journal/transport-type
   :surfaces #{:reader :writer}
   :handle-factory fresh
   :operations
   {:descriptor {:produces #{:dao.stream/ok} :exclusions {}}

    :cursor
    {:produces #{:dao.stream/ok :dao.stream/invalid-anchor}
     :exclusions
     {:dao.stream/closed
      "there is no close surface: every handle is the stream's owner"
      :dao.stream/refused
      "no policy is composed on a journal handle"
      :dao.stream/transport-error
      "minting reads the visible in-memory log, never the backend"}}

    :next
    {:produces #{:dao.stream/ok :dao.stream/blocked
                 :dao.stream/cursor-mismatch :dao.stream/invalid-cursor}
     :exclusions
     {:dao.stream/gap
      "retention is complete: every persisted frame is replayed at open"
      :dao.stream/end
      "there is no close surface, so the tail always blocks"
      :dao.stream/refused
      "no policy is composed on a journal handle"
      :dao.stream/transport-error
      "a read is one indexed lookup into the visible in-memory log"}}

    :append!
    {:produces #{:dao.stream/ok :dao.stream/invalid-value
                 :dao.stream/transport-error}
     :exclusions
     {:dao.stream/full
      "no capacity is declared; the position bound is a transport-error"
      :dao.stream/closed
      "there is no close surface"
      :dao.stream/refused
      "no policy is composed on a journal handle"}}}

   :fixtures
   {:descriptor {:dao.stream/ok #(stream/descriptor (fresh))}
    :cursor {:dao.stream/ok #(stream/cursor (fresh) :dao.stream/oldest)
             :dao.stream/invalid-anchor
             #(stream/cursor (fresh) ::invalid-anchor)}
    :next {:dao.stream/ok #(let [h (fresh) c (cur h :dao.stream/oldest)]
                             (stream/append! h :value)
                             (stream/next h c))
           :dao.stream/blocked #(let [h (fresh)]
                                  (stream/next h (cur h :dao.stream/newest)))
           :dao.stream/cursor-mismatch
           #(stream/next (fresh) (cur (fresh) :dao.stream/oldest))
           :dao.stream/invalid-cursor #(stream/next (fresh) nil)}
    :append! {:dao.stream/ok #(stream/append! (fresh) :value)
              :dao.stream/invalid-value #(stream/append! (fresh) (fn []))
              :dao.stream/transport-error
              #(let [b (fresh-backend)
                     h (handle b)]
                 (file/close! b)
                 (stream/append! h :value))}}})


(deftest file-journal-conformance-test
  (try
    (is (:valid? (conformance/validate-manifest file-journal-manifest)))
    (let [result (conformance/run-conformance-suite file-journal-manifest)]
      (is (:passed? result) (str "Conformance failures: " (:failures result))))
    (finally
      (doseq [[dir b] @opened]
        (file/close! b)
        (cleanup-dir! dir))
      (reset! opened []))))


;; ==========================================================================
;; JVM only: a real process killed mid-append
;; ==========================================================================

#?(:cljd nil
   :clj
   (deftest a-process-killed-mid-append-leaves-a-dense-prefix
     (let [dir (temp-dir)
           command ["java" "-cp" (System/getProperty "java.class.path")
                    "clojure.main" "-e"
                    (str "(require '[dao.stream :as s]"
                         " '[dao.stream.journal :as j]"
                         " '[dao.stream.journal.file :as f])"
                         "(let [b (:dao.stream.journal.file/backend"
                         " (f/backend! " (pr-str dir) "))"
                         " h (:dao.stream/handle (j/open! b))]"
                         " (loop [i 0]"
                         "  (s/append! h i) (println i) (recur (inc i))))")]
           child (.start (doto (ProcessBuilder. ^java.util.List command)
                           (.redirectErrorStream true)))
           acked (atom -1)
           other (atom [])]
       (try
         (with-open [rdr (java.io.BufferedReader.
                           (java.io.InputStreamReader.
                             (.getInputStream ^Process child)))]
           (let [deadline (+ (System/currentTimeMillis) 60000)]
             (loop []
               (when (and (< @acked 50)
                          (< (System/currentTimeMillis) deadline))
                 (when-let [line (.readLine rdr)]
                   (if-let [n (parse-long line)]
                     (reset! acked n)
                     (swap! other conj line))
                   (recur)))))
           (.destroyForcibly ^Process child)
           (.waitFor ^Process child))
         (is (<= 50 @acked)
             (str "the child appended before it was killed: " @other))
         (let [b (backend dir)]
           (try
             (let [vs (values (handle b))]
               (is (= (range (count vs)) vs)
                   "every frame stands, dense, none torn")
               (is (< @acked (count vs))
                   "every acknowledged append survived"))
             (finally (file/close! b))))
         (finally
           (.destroyForcibly ^Process child)
           (cleanup-dir! dir))))))
