(ns dao.stream.journal-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as cbor]
            [dao.stream :as stream]
            [dao.stream.chunks :as chunks]
            [dao.stream.conformance :as conformance]
            [dao.stream.journal :as journal]))


(defn- open
  ([frames] (open frames nil))
  ([frames cut]
   (journal/open! (journal/memory-backend frames cut))))


(defn- handle
  ([frames] (handle frames nil))
  ([frames cut] (:dao.stream/handle (open frames cut))))


(defn- cur
  [h a]
  (:dao.stream/cursor (stream/cursor h a)))


(defn- outcome
  [r]
  (:dao.stream/outcome r))


(defn- identity-of
  [h]
  (:dao.stream/identity (stream/descriptor h)))


(defn- replay
  "Every value from cursor c to the tail, and the terminal outcome.
   Bounded so a defect cannot hang the suite."
  [h c]
  (loop [c c vs [] n 0]
    (let [r (stream/next h c)]
      (if (and (= :dao.stream/ok (outcome r)) (< n 1000))
        (recur (:dao.stream/cursor r) (conj vs (:dao.stream/value r)) (inc n))
        {:values vs :terminal (outcome r)}))))


(defn- values
  [h]
  (:values (replay h (cur h :dao.stream/oldest))))


(defn- frame
  "The canonical bytes of one hand-built frame."
  [v]
  (cbor/encode v))


(defn- header
  [i]
  (frame {:dao.stream.journal/header {:version 1 :identity i}}))


(defn- entry
  [p v]
  (frame {:dao.stream.journal/position p :dao.stream.journal/value v}))


(deftest fresh-journal-writes-its-header
  (let [frames (atom [])
        r (open frames)
        i (:dao.stream/identity r)]
    (is (= :dao.stream/ok (outcome r)))
    (is (string? i))
    (is (= 1 (count @frames)) "the header is the only frame")
    (is (= {:dao.stream.journal/header {:version 1 :identity i}}
           (cbor/decode (first @frames))))
    (is (= i (identity-of (:dao.stream/handle r))))
    (is (= {:dao.stream/type journal/transport-type :dao.stream/identity i}
           (:dao.stream/descriptor
             (stream/descriptor (:dao.stream/handle r)))))))


(deftest the-memory-backend-declares-its-durability
  (let [d ((::journal/durability (journal/memory-backend (atom []) nil)))]
    (is (= journal/memory-durability d))
    (is (= {:dao.stream.journal/backend :memory
            :dao.stream.journal/failure-model :none
            :dao.stream.journal/lock-kind :none
            :dao.stream.journal/persisted #{}}
           d)
        "the file backend's shape: survives nothing, persists nothing")))


(deftest repeated-equal-appends-keep-separate-positions
  (let [frames (atom [])
        h (handle frames)]
    (is (= [:dao.stream/ok :dao.stream/ok :dao.stream/ok]
           (mapv #(outcome (stream/append! h %)) [:x :x :x])))
    (is (= [:x :x :x] (values h)))
    (is (= 4 (count @frames)) "one header and three distinct frames")
    (is (= [0 1 2]
           (mapv #(:dao.stream.journal/position (cbor/decode %))
                 (rest @frames))))
    (is (= [:x :x :x] (values (handle frames)))
        "reopen replays every repeated value")))


(deftest identity-and-positions-survive-reopen
  (let [frames (atom [])
        h (handle frames)
        origin (cur h :dao.stream/oldest)
        _ (stream/append! h :a)
        after-a (:dao.stream/cursor (stream/next h origin))
        _ (stream/append! h nil)
        _ (stream/append! h {:k [1 "two" #{3}]})
        tail (cur h :dao.stream/newest)
        h2 (handle frames)]
    (is (= (identity-of h) (identity-of h2)))
    (is (= [:a nil {:k [1 "two" #{3}]}] (values h2)))
    (is (= origin (cur h2 :dao.stream/oldest))
        "the origin cursor is the same value after reopen")
    (is (= tail (cur h2 :dao.stream/newest)))
    (is (= [nil {:k [1 "two" #{3}]}] (:values (replay h2 after-a)))
        "a cursor kept from before reopen resumes on the new handle")
    (is (= :dao.stream/ok (outcome (stream/append! h2 :d))))
    (is (= [:a nil {:k [1 "two" #{3}]} :d] (values (handle frames))))
    (is (= :dao.stream/cursor-mismatch
           (outcome (stream/next (handle (atom [])) origin)))
        "another journal is another logical stream")))


(deftest non-portable-value-is-refused-without-a-write
  (let [frames (atom [])
        h (handle frames)
        before @frames]
    (is (= :dao.stream/invalid-value
           (outcome (stream/append! h (fn [] :not-data)))))
    (is (= before @frames) "nothing was written")
    (is (= [] (values h)) "nothing became visible")
    (is (= :dao.stream/ok (outcome (stream/append! h :b)))
        "a refusal does not poison the handle")
    (is (= [:b] (values (handle frames))))))


(defn- crash-at
  "Append :a and :b cleanly, then :c through a backend armed with cut.
   Returns the frames, the cut handle and the answers it gave."
  [cut]
  (let [frames (atom [])
        h (handle frames)]
    (stream/append! h :a)
    (stream/append! h :b)
    (let [hc (handle frames cut)
          before @frames
          c (stream/append! hc :c)
          poisoned @frames
          d (stream/append! hc :d)]
      {:frames frames
       :handle hc
       :c c
       :d d
       :before before
       :poisoned poisoned
       :after-d @frames})))


(deftest crash-cuts-poison-until-reopen
  (doseq [[cut persisted] [[:before-frame [:a :b]]
                           [:after-frame-before-visible [:a :b :c]]
                           [:torn-frame [:a :b]]]]
    (testing (name cut)
      (let [{:keys [frames c d poisoned after-d] hc :handle} (crash-at cut)]
        (is (= :dao.stream/transport-error (outcome c)))
        (is (= :dao.stream/transport-error (outcome d))
            "the handle stays poisoned for appends")
        (is (= poisoned after-d) "a poisoned handle writes nothing")
        (is (= [:a :b] (values hc))
            "the failed append never became visible")
        (let [reopened (handle frames)]
          (is (= persisted (values reopened))
              "reopen shows exactly what was persisted")
          (is (= :dao.stream/ok (outcome (stream/append! reopened :e)))
              "reopen clears the poison")
          (is (= (conj persisted :e) (values (handle frames)))
              "positions stay dense after the recovered append"))))))


(deftest a-torn-header-alone-opens-a-fresh-empty-journal
  (let [frames (atom [(chunks/slice (header "old-id") 0 2)])
        r (open frames)
        h (:dao.stream/handle r)]
    (is (= :dao.stream/ok (outcome r)))
    (is (not= "old-id" (:dao.stream/identity r)))
    (is (= [] (values h)))
    (is (= 1 (count @frames)) "only the fresh header stands")
    (is (= :dao.stream/ok (outcome (stream/append! h :a))))
    (is (= 2 (count @frames)))))


(deftest readers-see-the-decoded-stored-form
  (let [frames (atom [])
        h (handle frames)]
    (is (= :dao.stream/ok (outcome (stream/append! h 1.5))))
    (is (= (:dao.stream.journal/value
             (cbor/decode (last @frames)))
           (first (values h)))
        "the visible value is the stored one, as a reopen will read it")))


(deftest torn-frame-is-dropped-at-reopen
  (let [{:keys [frames poisoned]} (crash-at :torn-frame)]
    (is (= 4 (count poisoned)) "header, :a, :b and the torn frame")
    (is (thrown? #?(:cljd Object :clj Exception :cljs js/Error)
          (cbor/decode (last poisoned)))
        "the torn frame does not decode")
    (handle frames)
    (is (= 3 (count @frames)) "reopen truncated the torn tail")))


(deftest unreadable-media-refuse-the-open
  (let [i "journal-id"
        refused? (fn [fs]
                   (let [frames (atom fs)
                         r (open frames)]
                     (and (= :dao.stream/transport-error (outcome r))
                          (= fs @frames))))]
    (testing "a missing header"
      (is (refused? [(entry 0 :a)]))
      (is (refused? [(frame {:dao.stream.journal/header {:version 2
                                                         :identity i}})]))
      (is (refused? [(frame {:dao.stream.journal/header {:version 1}})])))
    (testing "a position gap"
      (is (refused? [(header i) (entry 0 :a) (entry 2 :c)]))
      (is (refused? [(header i) (entry 1 :b)]))
      (is (refused? [(header i) (entry 0 :a) (entry 0 :a)])))
    (testing "a malformed frame"
      (is (refused? [(header i) (frame {:dao.stream.journal/position 0})]))
      (is (refused? [(header i) (frame [:a])])))
    (testing "an unreadable frame before the tail is corruption, not a tear"
      (is (refused? [(header i) (chunks/slice (entry 0 :a) 0 2) (entry 1 :b)])))
    (testing "a final frame that decodes to a keyword is malformed, not torn"
      (is (refused? [(header i) (frame :dao.stream.journal/unreadable)])))
    (testing "a well-formed medium opens"
      (let [r (open (atom [(header i) (entry 0 :a) (entry 1 :a)]))]
        (is (= :dao.stream/ok (outcome r)))
        (is (= i (:dao.stream/identity r)))
        (is (= [:a :a] (values (:dao.stream/handle r))))))))


(deftest position-bound-refuses-without-a-write
  (let [frames (atom [])
        h (:dao.stream/handle
            (journal/open! (journal/memory-backend frames nil)
                           {:dao.stream.journal/max-position 1}))]
    (is (= :dao.stream/ok (outcome (stream/append! h :p0))))
    (is (= :dao.stream/ok (outcome (stream/append! h :p1))))
    (let [before @frames]
      (is (= :dao.stream/transport-error (outcome (stream/append! h :p2))))
      (is (= before @frames)))
    (is (= 4503599627370495 journal/max-position))))


(defn- fresh
  []
  (handle (atom [])))


(def journal-manifest
  "The journal's outcome declaration.  Every fixture operates a fresh
   real handle over a fresh memory backend.  The exclusions are proof
   obligations discharged here: gap by complete retention (the replays
   above never observe one), full by the position bound answering
   transport-error instead, and the rest by the transport's structure.
   `open!` is a host act over a backend, not `create!`, so no creation
   operation is declared."
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
              ;; The header is written by the clean open, so the cut
              ;; fires on the value frame.
              #(let [frames (atom [])]
                 (handle frames)
                 (stream/append! (handle frames :before-frame) :value))}}})


(deftest journal-manifest-is-valid
  (let [v (conformance/validate-manifest journal-manifest)]
    (is (:valid? v) (str "Manifest validation errors: " (:errors v)))))


(deftest journal-conformance-test
  (let [result (conformance/run-conformance-suite journal-manifest)]
    (is (:passed? result) (str "Conformance failures: " (:failures result)))))
