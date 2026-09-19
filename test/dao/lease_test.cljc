(ns dao.lease-test
  "Tests for dao.lease: the vocabulary (V1-V7) and the judge (J1-J16), per
  the revised implementation plan's Phase 1 and Phase 2 prove lists and the
  r2 review's prescribed tests.

   Everything is scripted. Ticks and facts are hand-appended to ring
   buffers, attribution is per-author media (the resolver answers from the
   source each cursor was wired with -- the r2 review's D4 binding), the
   defect reader outcomes come from a scripted fake reader, and no host
   clock, timer or callback is read anywhere."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.lease :as lease]
            [dao.stream :as ds]
            [dao.stream.ringbuffer :as ringbuffer]))


;; =============================================================================
;; Harness
;; =============================================================================

(defn- new-buffer
  [capacity]
  (let [result (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                                    ringbuffer/capacity-key capacity})]
    (if (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/handle result)
      (throw (ex-info "test buffer creation failed" {:result result})))))


(defn- oldest-cursor
  [handle]
  (let [minted (ds/cursor handle ds/anchor-oldest)]
    (if (= :dao.stream/ok (:dao.stream/outcome minted))
      (:dao.stream/cursor minted)
      (throw (ex-info "test cursor mint failed" {:result minted})))))


(defn- drain-values
  "Every value currently retained on handle, oldest first."
  [handle]
  (loop [cursor (oldest-cursor handle)
         out []]
    (let [read (ds/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome read))
        (recur (:dao.stream/cursor read) (conj out (:dao.stream/value read)))
        out))))


(defn- resolver
  "The test composition's attribution: per-author media identity. The source
  a cursor was wired with IS the author; a fact's own claim about its
  author is never consulted."
  [source _fact]
  source)


(defn- setup
  "A judge over ring buffers: tick cursor and fact cursor wired at oldest,
   records written to a writer. The main fact medium is :holder-a's
   (per-author media); other authors get their own medium on first use via
   fact-from!. Options: :capacity (tick/main-fact buffer), :media-capacity
   (ad-hoc authors' media), :drain-budget, and the judge seams :reclaim
   :policy :tolerance :units :answer :self."
  [options]
  (let [capacity (get options :capacity 64)
        ticks (new-buffer capacity)
        facts (new-buffer capacity)
        writer (new-buffer 64)
        judge (-> (lease/initial-judge
                    {:units (get options :units {:ms 1})
                     :tolerance (get options :tolerance)
                     :drain-budget (get options :drain-budget)
                     :resolver resolver
                     :reclaim (get options :reclaim (fn [_] true))
                     :policy (get options :policy)
                     :writer writer
                     :self (get options :self :grantor)
                     :answer (get options :answer)})
                  (lease/wire-tick ticks (oldest-cursor ticks) :tick-driver)
                  (lease/wire-facts facts (oldest-cursor facts) :holder-a))]
    {:ticks ticks
     :facts facts
     :writer writer
     :judge judge
     :media-capacity (get options :media-capacity 64)
     :media {}}))


(defn- append-ok!
  [handle value]
  (let [result (ds/append! handle value)]
    (when-not (= :dao.stream/ok (:dao.stream/outcome result))
      (throw (ex-info "test append failed" {:result result :value value})))
    handle))


(defn- tick!
  [system ms]
  (append-ok! (:ticks system) (lease/tick {:ms ms}))
  system)


(defn- fact!
  "Append fact to the main medium, which is :holder-a's."
  [system fact]
  (append-ok! (:facts system) fact)
  system)


(defn- fact-from!
  "Append fact on the medium attributed to author, wiring a fresh
   per-author medium on first use. Facts read there attribute to author,
   whatever the fact itself claims."
  [system author fact]
  (if (= author :holder-a)
    (fact! system fact)
    (let [existing (get-in system [:media author])
          handle (if (some? existing)
                   existing
                   (new-buffer (:media-capacity system)))
          system (if (some? existing)
                   system
                   (-> system
                       (update :media assoc author handle)
                       (update :judge lease/wire-facts
                               handle (oldest-cursor handle) author)))]
      (append-ok! handle fact)
      system)))


(defn- renewal-from
  [system lease-id author]
  (fact-from! system author (lease/renewal lease-id)))


(defn- fill!
  "Append n filler values to the main fact medium, to evict what the
  judge's cursor still points at."
  [system n value]
  (reduce (fn [system _]
            (append-ok! (:facts system) value)
            system)
          system
          (range n)))


(defn- step!
  [system]
  (update system :judge lease/judge-step))


(defn- grant!
  "Queue the standard test grant: :l1 over :db held by :holder-a."
  ([system] (grant! system {:ms 10}))
  ([system duration]
   (update system :judge lease/author-grant
           (lease/grant :l1 :db :holder-a duration))))


(defn- records
  "The writer's records after the grant."
  [system]
  (rest (drain-values (:writer system))))


(defn- fake-reader
  "A reader that answers one defect outcome forever -- the ring buffer
  cannot produce cursor-mismatch, invalid-cursor or transport-error."
  [outcome]
  (reify
    ds/IDaoStreamReader
    (cursor
      [_ _anchor]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/cursor :broken})

    (next
      [_ _cursor]
      {:dao.stream/outcome outcome})))


;; =============================================================================
;; V1-V7: the vocabulary
;; =============================================================================

(deftest shape-predicates-test
  (testing "V2: duration shapes and causes"
    (is (true? (lease/duration? {:ms 3})))
    (is (true? (lease/duration? {:s 3})))
    (is (false? (lease/duration? {:ms 0})))
    (is (false? (lease/duration? {:ms 1 :s 2})))
    (is (false? (lease/duration? {"ms" 1})))
    (is (false? (lease/duration? {:ms 1.5})))
    (is (false? (lease/duration? 5)))
    (is (false? (lease/duration? {:ms 4503599627370497}))
        "past the raw 2^52 magnitude limit: no host may overflow or lose
         precision in an ordering")
    (is (true? (lease/duration? {:ms lease/magnitude-limit})))
    (is (true? (lease/duration? {:ms 1000001}))
        "a million-and-one milliseconds is an ordinary duration, not a defect")
    (is (true? (lease/tolerance? {:ms 0})))
    (is (false? (lease/tolerance? {:ms -1})))
    (is (false? (lease/tolerance? {:ms 1 :s 2})))
    (is (false? (lease/tolerance? 5)))
    (is (false? (lease/tolerance? {:ms 4503599627370497})))
    (doseq [cause [:silence :release :cap :policy]]
      (is (true? (lease/cause? cause))))
    (is (false? (lease/cause? :bogus)))))


(deftest unit-arithmetic-test
  (testing "V3: normalize to the finer unit; strict comparison"
    (let [units {:ms 1 :s 1000 :min 60000}]
      (is (= {:ms 2000} (lease/normalize units {:s 2} :ms)))
      (is (= {:s 2} (lease/normalize units {:ms 2000} :s)))
      (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
            (lease/normalize units {:ms 1500} :s))
          "an inexact division is an incommensurate table, a composition defect")
      (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
            (lease/normalize units {:hr 2} :s))
          "a unit outside the table is a composition defect")
      (is (= 0 (lease/compare-durations units {:ms 1000} {:s 1})))
      (is (= -1 (lease/compare-durations units {:ms 999} {:s 1})))
      (is (= 1 (lease/compare-durations units {:s 2} {:ms 1999})))
      (is (false? (lease/exceeds? units {:ms 1000} {:s 1}))
          "an interval exactly equal to its bound has not passed it")
      (is (true? (lease/exceeds? units {:ms 1001} {:s 1})))
      (is (= {:ms 1500} (lease/add-duration units {:s 1} {:ms 500})))
      (is (= {:ms 0} (lease/interval units {:ms 5} {:ms 5})))
      (is (= {:ms 7} (lease/interval units {:s 1} {:ms 993})))
      (is (= {:ms 0} (lease/interval units {:ms 5} {:ms 9}))
          "clamped: readings never decrease on one stream")
      ;; S1, stated not enforced: the granted duration exceeds twice the
      ;; holder's renewal interval, computable on the one shared table.
      (let [duration {:s 10} renewal-interval {:s 4}]
        (is (true? (lease/exceeds? units duration
                                   (lease/add-duration units
                                                       renewal-interval
                                                       renewal-interval)))))
      (is (true? (lease/stale-reading? units {:ms 999} {:s 1})))
      (is (false? (lease/stale-reading? units {:s 1} {:ms 999})))
      (is (false? (lease/stale-reading? units {:s 1} {:ms 1000}))
          "equal is not older"))))


(deftest constructors-emit-the-fact-table-test
  (testing "each constructor returns exactly the map the vocabulary table names"
    (is (= {:dao.lease/status :dao.lease/proposed
            :dao.lease/proposal :p1
            :dao.lease/subject :db}
           (lease/proposal :p1 :db)))
    (is (= {:dao.lease/status :dao.lease/proposed
            :dao.lease/proposal :p1
            :dao.lease/subject :db
            :dao.lease/duration {:ms 10}}
           (lease/proposal :p1 :db {:dao.lease/duration {:ms 10}})))
    (is (= {:dao.lease/status :dao.lease/accepted
            :dao.lease/lease :l1
            :dao.lease/subject :db
            :dao.lease/holder :holder-a
            :dao.lease/duration {:ms 10}}
           (lease/grant :l1 :db :holder-a {:ms 10})))
    (is (= {:dao.lease/status :dao.lease/accepted
            :dao.lease/lease :l1
            :dao.lease/subject :db
            :dao.lease/holder :holder-a
            :dao.lease/duration {:ms 10}
            :dao.lease/proposal :p1
            :dao.lease/max {:s 2}}
           (lease/grant :l1 :db :holder-a {:ms 10}
                        {:dao.lease/proposal :p1 :dao.lease/max {:s 2}})))
    (is (= {:dao.lease/status :dao.lease/rejected :dao.lease/proposal :p1}
           (lease/refusal :p1)))
    (is (= {:dao.lease/status :dao.lease/released :dao.lease/lease :l1}
           (lease/release :l1)))
    (is (= {:dao.lease/status :dao.lease/lapsed
            :dao.lease/lease :l1
            :dao.lease/cause :silence}
           (lease/lapsed :l1 :silence)))
    (is (= {:dao.lease/event :dao.lease/renewal :dao.lease/lease :l1}
           (lease/renewal :l1)))
    (is (= {:dao.lease/event :dao.lease/tick :dao.lease/reading {:ms 5}}
           (lease/tick {:ms 5})))))


(deftest constructors-reject-defective-calls-test
  (testing "a call that would produce a defective fact throws at the call site"
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/proposal nil :db)))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/proposal :p1 nil)))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/proposal :p1 :db {:dao.lease/duration {:ms 0}})))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/proposal :p1 :db {:dao.lease/duration 5})))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/proposal :p1 :db "ask")))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/proposal :p1 :db {:dao.lease/lease :l1}))
        "a proposal carries no lease id")
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/grant nil :db :holder-a {:ms 1})))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/grant :l1 nil :holder-a {:ms 1})))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/grant :l1 :db nil {:ms 1})))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/grant :l1 :db :holder-a {:ms 0})))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/grant :l1 :db :holder-a "10")))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/grant :l1 :db :holder-a
                       {:ms 10} {:dao.lease/max {:ms 0}})))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/grant :l1 :db :holder-a
                       {:ms 10} {:dao.lease/max {:ms 4503599627370497}})))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/refusal nil)))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/release nil)))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/lapsed :l1 :nope)))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/lapsed nil :silence)))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/renewal nil)))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/tick {:ms 0})))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/tick {:s 0 :ms 1})))
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
          (lease/tick {:ms 4503599627370497})))))


(defn- assembly-defect
  "The first key of the ex-data an assembly rejection carries, or nil when
  initial-judge succeeds -- so each negative case asserts WHICH check
  refused it, not merely that something threw."
  [config]
  (try (lease/initial-judge config) nil
    (catch #?(:cljd Object :clj Exception :cljs :default) e
      (first (keys (ex-data e))))))


(deftest initial-judge-rejects-misconfiguration-test
  (testing "P3: assembly-time rejection of configurations that would throw mid-pass"
    (doseq [budget [0 -1 1.5 "8"]]
      (is (= :drain-budget
             (assembly-defect {:drain-budget budget
                               :resolver (fn [_source _fact] nil)}))))
    (is (= :larger
           (assembly-defect {:units {:ms 3 :s 1000}
                             :resolver (fn [_source _fact] nil)}))
        "incommensurate magnitudes are rejected at assembly")
    (is (= :unit
           (assembly-defect {:units {:ms 1 :s 4503599627370497}
                             :resolver (fn [_source _fact] nil)}))
        "an over-bound magnitude is rejected at assembly")
    (is (= :unit
           (assembly-defect {:units {:ms 0 :s 1000}
                             :resolver (fn [_source _fact] nil)})))
    (is (= :units
           (assembly-defect {:units {}
                             :resolver (fn [_source _fact] nil)}))
        "an empty table has no basis")
    (is (some? (lease/initial-judge {:units {:ms 1 :s 1000 :min 60000}
                                     :resolver (fn [_source _fact] nil)})))
    (is (some? (lease/initial-judge {:resolver (fn [_source _fact] nil)})))))


(def defective-corpus
  "Structurally defective facts (V4), decided from the fact alone (with the
  unit table where membership matters)."
  [[:missing-proposal-id {:dao.lease/status :dao.lease/proposed
                          :dao.lease/subject :db}]
   [:missing-subject {:dao.lease/status :dao.lease/proposed
                      :dao.lease/proposal :p1}]
   [:missing-lease-id {:dao.lease/status :dao.lease/accepted
                       :dao.lease/subject :db
                       :dao.lease/holder :h
                       :dao.lease/duration {:ms 1}}]
   [:missing-holder {:dao.lease/status :dao.lease/accepted
                     :dao.lease/lease :l1
                     :dao.lease/subject :db
                     :dao.lease/duration {:ms 1}}]
   [:missing-duration {:dao.lease/status :dao.lease/accepted
                       :dao.lease/lease :l1
                       :dao.lease/subject :db
                       :dao.lease/holder :h}]
   [:invalid-duration {:dao.lease/status :dao.lease/accepted
                       :dao.lease/lease :l1
                       :dao.lease/subject :db
                       :dao.lease/holder :h
                       :dao.lease/duration {:ms 1 :s 2}}]
   [:invalid-duration {:dao.lease/status :dao.lease/accepted
                       :dao.lease/lease :l1
                       :dao.lease/subject :db
                       :dao.lease/holder :h
                       :dao.lease/duration {:ms 0}}]
   [:invalid-duration {:dao.lease/status :dao.lease/accepted
                       :dao.lease/lease :l1
                       :dao.lease/subject :db
                       :dao.lease/holder :h
                       :dao.lease/duration {:ms 4503599627370497}}]
   [:invalid-cap {:dao.lease/status :dao.lease/accepted
                  :dao.lease/lease :l1
                  :dao.lease/subject :db
                  :dao.lease/holder :h
                  :dao.lease/duration {:ms 1}
                  :dao.lease/max {:ms -1}}]
   [:invalid-cap {:dao.lease/status :dao.lease/accepted
                  :dao.lease/lease :l1
                  :dao.lease/subject :db
                  :dao.lease/holder :h
                  :dao.lease/duration {:ms 1}
                  :dao.lease/max {:ms 0}}]
   [:invalid-duration {:dao.lease/status :dao.lease/accepted
                       :dao.lease/lease :l1
                       :dao.lease/subject :db
                       :dao.lease/holder :h
                       :dao.lease/duration {:hr 5}}]
   [:unknown-status {:dao.lease/status :dao.lease/nonsense
                     :dao.lease/lease :l1}]
   [:unknown-event {:dao.lease/event :dao.lease/nonsense
                    :dao.lease/lease :l1}]
   [:missing-reading {:dao.lease/event :dao.lease/tick}]
   [:invalid-reading {:dao.lease/event :dao.lease/tick
                      :dao.lease/reading 5}]
   [:invalid-reading {:dao.lease/event :dao.lease/tick
                      :dao.lease/reading {:ms 0}}]
   [:invalid-reading {:dao.lease/event :dao.lease/tick
                      :dao.lease/reading {:hr 5}}]
   [:invalid-cause {:dao.lease/status :dao.lease/lapsed
                    :dao.lease/lease :l1
                    :dao.lease/cause :bogus}]
   [:lease-id-on-proposal (assoc (lease/proposal :p1 :db)
                                 :dao.lease/lease :l1)]
   [:both-dispatch-keys (assoc (lease/renewal :l1)
                               :dao.lease/status :dao.lease/released)]
   ;; the shape gate is universal: any fact carrying these keys answers
   ;; for their shape, whatever its status or event
   [:invalid-duration {:dao.lease/status :dao.lease/rejected
                       :dao.lease/proposal :p1
                       :dao.lease/duration {:ms 0}}]
   [:invalid-cap (assoc (lease/release :l1) :dao.lease/max {:ms -1})]
   [:invalid-reading (assoc (lease/renewal :l1) :dao.lease/reading 5)]])


(def well-formed-corpus
  [(lease/proposal :p1 :db)
   (lease/proposal :p1 :db {:dao.lease/duration {:ms 10}})
   (lease/grant :l1 :db :h {:ms 10})
   (lease/grant :l1 :db :h {:ms 10} {:dao.lease/proposal :p1
                                     :dao.lease/max {:s 2}})
   (lease/refusal :p1)
   (lease/release :l1)
   (lease/lapsed :l1 :cap)
   (lease/renewal :l1)
   (lease/tick {:ms 3})])


(def test-units {:ms 1 :s 1000})


(deftest defective-corpus-test
  (testing "V4: each structurally malformed fact reports its defect"
    (doseq [[expected fact] defective-corpus]
      (let [defects (lease/defective? fact test-units)]
        (is (vector? defects) (str "expected defective: " (pr-str fact)))
        (is (some #(= expected %) defects)
            (str "expected defect " expected " in " (pr-str defects))))))
  (testing "V4: a foreign unit is invisible without a table"
    (is (false? (lease/defective? {:dao.lease/event :dao.lease/tick
                                   :dao.lease/reading {:hr 5}})))
    (is (vector? (lease/defective? {:dao.lease/event :dao.lease/tick
                                    :dao.lease/reading {:hr 5}}
                                   test-units))))
  (testing "V4: a missing cause reports missing, not also invalid"
    (let [defects (lease/defective? {:dao.lease/status :dao.lease/lapsed
                                     :dao.lease/lease :l1}
                                    test-units)]
      (is (some #(= :missing-cause %) defects))
      (is (not (some #(= :invalid-cause %) defects))
          "missing already implies invalid: one defect, not two")))
  (testing "V4: the well-formed corpus is not defective"
    (doseq [fact well-formed-corpus]
      (is (false? (lease/defective? fact test-units)) (pr-str fact))
      (is (true? (lease/valid? fact test-units)) (pr-str fact))))
  (testing "V1: dispatch keys partition lease facts from everything else"
    (is (false? (lease/defective? {:author :x}))
        "neither key: not a lease fact, and not defective")
    (is (false? (lease/lease-fact? {:author :x})))
    (is (false? (lease/lease-fact? 42)))
    (is (false? (lease/valid? {:author :x})))
    (is (true? (lease/lease-fact? (lease/renewal :l1))))
    (is (true? (lease/lease-fact? (lease/release :l1))))))


(defn- inadmissibilities
  "admissible? answers false or a vector; hand the corpus one shape."
  [fact observed]
  (or (lease/admissible? fact observed) []))


(deftest admissibility-corpus-test
  (testing "V7: history-dependent rules are admissibility, not structure"
    (is (some #(= :proposal-already-answered %)
              (inadmissibilities (lease/grant :l9 :db :h {:ms 1}
                                              {:dao.lease/proposal :p1})
                                 {:proposer :h
                                  :answered {[:h :p1]
                                             :dao.lease/rejected}
                                  :units test-units}))
        "a second answer to one proposer's proposal")
    (is (false? (lease/admissible? (lease/grant :l9 :db :h2 {:ms 1}
                                                {:dao.lease/proposal :p2})
                                   {:proposer :h2
                                    :answered {[:h :p1]
                                               :dao.lease/rejected}
                                    :units test-units}))
        "answering a different proposal is not the defect")
    (is (false? (lease/admissible? (lease/grant :l9 :db :h2 {:ms 1}
                                                {:dao.lease/proposal :p1})
                                   {:proposer :h2
                                    :answered {[:h :p1]
                                               :dao.lease/rejected}
                                    :units test-units}))
        "holder h2's grant under its own :p1 is not h's answered :p1: the
         key is the PROPOSER, so one holder's id never blocks another's")
    (is (false? (lease/admissible? (lease/refusal :p1) {}))
        "no proposer supplied: nothing to check the answer against")
    (is (false? (lease/admissible? (lease/proposal :px :db)
                                   {:proposer :holder-b
                                    :answered {[:holder-a :px]
                                               :dao.lease/rejected}
                                    :units test-units}))
        "holder B proposing under a pid the grantor answered for holder A
         poisons nothing: the answer key is the proposer")
    (is (some #(= :repeated-status %)
              (inadmissibilities (lease/grant :l1 :db :h {:ms 1})
                                 {:proposer :h
                                  :seen {:l1 #{:dao.lease/accepted}}
                                  :units test-units}))
        "a repeated :accepted for one lease")
    (is (some #(= :repeated-status %)
              (inadmissibilities (lease/release :l1)
                                 {:proposer :h
                                  :seen {:l1 #{:dao.lease/released}}
                                  :units test-units})))
    (is (some #(= :repeated-status %)
              (inadmissibilities (lease/lapsed :l1 :silence)
                                 {:proposer :h
                                  :seen {:l1 #{:dao.lease/lapsed}}
                                  :units test-units})))
    (is (false? (lease/admissible? (lease/renewal :l1)
                                   {:proposer :h
                                    :seen {:l1 #{:dao.lease/accepted}}
                                    :units test-units}))
        "renewals are evidence, not once-per-lease statuses: never repeated")
    (doseq [fact well-formed-corpus]
      (is (false? (lease/admissible? fact {:proposer :grantor
                                           :answered {[:grantor :p9]
                                                      :dao.lease/rejected}
                                           :seen {:l9 #{:dao.lease/accepted}}
                                           :reading {:ms 1}
                                           :units test-units}))
          (str "well-formed and admissible against an unrelated history: "
               (pr-str fact))))))


;; =============================================================================
;; J1-J16: the judge
;; =============================================================================

(deftest pass-classifies-nothing-before-first-tick-test
  (testing "J6: a pass before any tick has been observed performs nothing"
    (let [system (-> (setup {})
                     (renewal-from :l1 :holder-a)
                     grant!
                     step!)]
      (is (nil? (get-in system [:judge :now])))
      (is (empty? (get-in system [:judge :ledger]))
          "the queued grant was not seeded before a tick")
      (is (= [] (drain-values (:writer system))))
      (let [system (-> system (tick! 1) step!)]
        (is (= {:ms 1} (get-in system [:judge :ledger :l1 :tenure-start]))
            "once a tick arrives the queued grant is seeded with that pass's now")
        (is (= [(lease/grant :l1 :db :holder-a {:ms 10})]
               (drain-values (:writer system))))))))


(deftest never-renewed-grant-lapses-on-silence-test
  (testing "J7: the design's most likely leak -- silence measured from the grant"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :tolerance {:ms 0}})
                     grant!
                     (tick! 1)
                     step!)]
      (is (= {:ms 1} (get-in system [:judge :ledger :l1 :tenure-start])))
      (is (= [] @log) "now=1: nothing is due")
      (let [system (-> system (tick! 11) step!)]
        (is (= [] @log) "exactly the duration has not passed it: strict")
        (let [system (-> system (tick! 12) step!)]
          (is (= [:db] @log) "12 is 11 past the grant, past 10 + 0: :silence")
          (is (= [(lease/grant :l1 :db :holder-a {:ms 10})
                  (lease/lapsed :l1 :silence)]
                 (drain-values (:writer system))))
          (is (empty? (get-in system [:judge :ledger]))
              "on ok the lease leaves the ledger")))))
  (testing "J7: tolerance widens the silence window"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :tolerance {:ms 2}})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 12)
                     step!)]
      (is (= [] @log) "11 past the grant <= 10 + 2")
      (-> system (tick! 14) step!)
      (is (= [:db] @log) "13 past the grant > 10 + 2 reclaims"))))


(deftest timely-renewal-defers-silence-test
  (testing "J1/J2: a delayed holder renewal still counts when observed"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 7)
                     (renewal-from :l1 :holder-a)
                     step!)]
      (is (= {:ms 7} (get-in system [:judge :ledger :l1 :last-observation]))
          "the renewal advances the last relevant observation to its stamp")
      (let [system (-> system (tick! 16) step!)]
        (is (= [] @log) "16 - 7 = 9 <= 10: silence measured from the renewal")
        (let [system (-> system (tick! 18) step!)]
          (is (= [:db] @log) "18 - 7 = 11 > 10")
          (is (= [(lease/lapsed :l1 :silence)] (records system))))))))


(deftest non-holder-renewal-is-not-evidence-test
  (testing "J2: silence measured as though the renewal had not arrived"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 7)
                     (renewal-from :l1 :someone-else)
                     step!)]
      (is (= {:ms 1} (get-in system [:judge :ledger :l1 :last-observation]))
          "a renewal from any other author advances nothing")
      (is (= 0 (get-in system [:judge :dropped]))
          "ignored is not dropped: it is a fact about that author, not a defect")
      (-> system (tick! 12) step!)
      (is (= [:db] @log) "12 - 1 > 10: the silence window never moved"))))


(deftest attribution-comes-from-the-source-test
  (testing "P1-5/D4: the resolver answers from the wired source, not the fact"
    (let [system (-> (setup {})
                     grant!
                     (tick! 1)
                     step!
                     ;; the same renewal text arrives on two media; only the
                     ;; holder's medium makes it evidence
                     (tick! 7)
                     (renewal-from :l1 :someone-else)
                     step!)]
      (is (= {:ms 1} (get-in system [:judge :ledger :l1 :last-observation]))
          "a renewal on someone-else's medium counts for no one"))
    (let [system (-> (setup {})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 7)
                     (renewal-from :l1 :holder-a)
                     step!)]
      (is (= {:ms 7} (get-in system [:judge :ledger :l1 :last-observation]))
          "the same renewal on the holder's medium is evidence"))))


(deftest holder-release-reclaims-with-release-cause-test
  (testing "J7: a valid :released from the holder ends tenure before duration"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 4)
                     (fact! (lease/release :l1))
                     step!)]
      (is (= [:db] @log) "released at 4, well before duration 10")
      (is (= [(lease/lapsed :l1 :release)] (records system)))))
  (testing "J2: a release on any other author's medium marks nothing"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 4)
                     (fact-from! :someone-else (lease/release :l1))
                     step!)]
      (is (= [] @log) "the lease is not released")
      (let [system (-> system (tick! 12) step!)]
        (is (= [:db] @log) "it later lapses for :silence, not :release")
        (is (= [(lease/lapsed :l1 :silence)] (records system)))))))


(deftest cap-ends-tenure-past-max-test
  (testing "J7/J15: tenure start further back than max ends it, renewals or not"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)})
                     (update :judge lease/author-grant
                             (lease/grant :l1 :db :holder-a {:ms 10}
                                          {:dao.lease/max {:ms 5}}))
                     (tick! 1)
                     step!
                     (tick! 4)
                     (renewal-from :l1 :holder-a)
                     step!)]
      (is (= {:ms 4} (get-in system [:judge :ledger :l1 :last-observation])))
      (let [system (-> system (tick! 8) step!)]
        (is (= [:db] @log) "tenure 8-1=7 > max 5 even though silence is 8-4=4")
        (is (= [(lease/lapsed :l1 :cap)] (records system)))))))


(deftest policy-ends-tenure-test
  (testing "J7: the grantor's own predicate ends it with :policy, before silence"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :policy (fn [entry now]
                                       (lease/exceeds? {:ms 1}
                                                       (lease/interval {:ms 1}
                                                                       now
                                                                       (:tenure-start entry))
                                                       {:ms 4}))})
                     (grant! {:ms 100})
                     (tick! 1)
                     step!
                     (tick! 5)
                     step!)]
      (is (= [] @log) "tenure 4 is exactly the bound: not yet past it")
      (let [system (-> system (tick! 6) step!)]
        (is (= [:db] @log) "policy fires long before duration 100 could")
        (is (= [(lease/lapsed :l1 :policy)] (records system)))))))


(deftest eligibility-precedes-policy-test
  (testing "J7: a renewal counts even when policy ends the lease in the same pass"
    (let [log (atom [])
          seen-observation (atom nil)
          policy (fn [entry now]
                   (let [due (lease/exceeds? {:ms 1}
                                             (lease/interval {:ms 1}
                                                             now
                                                             (:tenure-start entry))
                                             {:ms 4})]
                     (when (and due (nil? @seen-observation))
                       (reset! seen-observation (:last-observation entry)))
                     due))
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :policy policy})
                     (grant! {:ms 100})
                     (tick! 1)
                     step!
                     (tick! 6)
                     (renewal-from :l1 :holder-a)
                     step!)]
      (is (= [:db] @log) "policy ended the lease this pass")
      (is (= [(lease/lapsed :l1 :policy)] (records system)))
      (is (= {:ms 6} @seen-observation)
          "classification saw the renewal the drain had already applied:
           the policy predicate was handed last-observation 6, not 1"))))


(deftest cause-precedence-test
  (testing "J7: the first cause that holds, in order, wins"
    (let [later-policy (fn [entry now]
                         (lease/exceeds? {:ms 1}
                                         (lease/interval {:ms 1}
                                                         now
                                                         (:tenure-start entry))
                                         {:ms 4}))
          log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :policy later-policy})
                     (update :judge lease/author-grant
                             (lease/grant :l1 :db :holder-a {:ms 100}
                                          {:dao.lease/max {:ms 5}}))
                     (tick! 1)
                     step!
                     (tick! 9)
                     (fact! (lease/release :l1))
                     step!)]
      ;; release, cap, policy and silence would all hold at now=9
      (is (= [(lease/lapsed :l1 :release)] (records system))
          ":release precedes :cap, :policy and :silence")))
  (testing "J7: cap precedes policy and silence"
    (let [system (-> (setup {:policy (fn [entry now]
                                       (lease/exceeds? {:ms 1}
                                                       (lease/interval {:ms 1}
                                                                       now
                                                                       (:tenure-start entry))
                                                       {:ms 4}))})
                     (update :judge lease/author-grant
                             (lease/grant :l1 :db :holder-a {:ms 100}
                                          {:dao.lease/max {:ms 5}}))
                     (tick! 1)
                     step!
                     (tick! 9)
                     step!)]
      (is (= [(lease/lapsed :l1 :cap)] (records system)))))
  (testing "J7: a pending lease keeps its original cause across retries"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) false)})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 12)
                     step!)]
      (is (= {:state :pending :cause :silence :success false}
             (get-in system [:judge :ledger :l1 :reclaim])))
      ;; a release arrives while the lease sits pending: the cause it
      ;; carries is the cause it keeps
      (-> system
          (tick! 13)
          (fact! (lease/release :l1))
          step!)
      (is (= :silence
             (get-in system [:judge :ledger :l1 :reclaim :cause]))
          "a pending lease is not reclassified")
      (is (= [:db :db] @log)
          "each pass after the lapse re-attempted the idempotent reclaim"))))


(deftest gap-marks-unknown-test
  (testing "J9: a gap on the fact medium marks its leases unknown"
    ;; fact buffer capacity 4: five fillers after one observed renewal evict
    ;; the judge's cursor position, so its next read answers :dao.stream/gap.
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :capacity 4})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 3)
                     (renewal-from :l1 :holder-a)
                     step!
                     (fill! 5 :filler)
                     (tick! 6)
                     step!)]
      (is (= :unknown (get-in system [:judge :ledger :l1 :evidence])))
      (is (= {:ms 6} (get-in system [:judge :ledger :l1 :resumed]))
          "now is the resumed reading")
      (let [system (-> system (tick! 15) step!)]
        (is (= [] @log)
            "15 is past duration from the grant, but only 9 past the resumed 6")
        (let [system (-> system (tick! 17) step!)]
          (is (= [:db] @log) "17 - 6 = 11 > 10: a full duration since resumed")
          (is (= [(lease/lapsed :l1 :silence)] (records system)))))))
  (testing "J9/J15: conditions other than :silence apply to an unknown lease unchanged"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :capacity 4})
                     (update :judge lease/author-grant
                             (lease/grant :l1 :db :holder-a {:ms 100}
                                          {:dao.lease/max {:ms 8}}))
                     (tick! 1)
                     step!
                     (renewal-from :l1 :holder-a)
                     step!
                     (fill! 5 :filler)
                     (tick! 6)
                     step!)]
      (is (= :unknown (get-in system [:judge :ledger :l1 :evidence])))
      (let [system (-> system (tick! 10) step!)]
        (is (= [:db] @log) "cap applies: tenure 10-1=9 > max 8 despite unknown evidence")
        (is (= [(lease/lapsed :l1 :cap)] (records system)))))))


(deftest gap-before-first-renewal-test
  (testing "P2-5: a medium gap before any observed renewal leaves the lease unknown"
    ;; The grant seeds at tick 1; the holder's first renewal is appended and
    ;; then evicted before the judge ever reads it. The gap must leave the
    ;; lease :unknown, not :known across a window never observed.
    (let [system (-> (setup {:capacity 4})
                     grant!
                     (tick! 1)
                     step!
                     (fill! 5 :filler)
                     (tick! 6)
                     step!)]
      (is (= :unknown (get-in system [:judge :ledger :l1 :evidence]))
          "conservatively marked: the lease rides no medium the judge has
           observed, and a gap it cannot attribute is a window it did not see")
      (is (= {:ms 6} (get-in system [:judge :ledger :l1 :resumed]))))))


(deftest per-medium-gap-isolation-test
  (testing "J9: a gap marks the leases on that medium, not every medium"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :capacity 4})
                     (update :judge lease/author-grant
                             (lease/grant :l2 :db2 :holder-b {:ms 100}))
                     grant!
                     (tick! 1)
                     step!
                     (tick! 3)
                     (renewal-from :l1 :holder-a)
                     (renewal-from :l2 :holder-b)
                     step!)]
      ;; both leases known; now gap only the first medium
      (is (= :known (get-in system [:judge :ledger :l1 :evidence])))
      (is (= :known (get-in system [:judge :ledger :l2 :evidence])))
      (let [system (-> system
                       (fill! 5 :filler)
                       (tick! 6)
                       step!)]
        (is (= :unknown (get-in system [:judge :ledger :l1 :evidence]))
            "the gapped medium's lease is unknown")
        (is (= :known (get-in system [:judge :ledger :l2 :evidence]))
            "the other medium's lease is untouched")
        (is (= [] @log) "neither lease is due")))))


(deftest gap-does-not-advance-last-observation-test
  (testing "J9: the gap's marking consults nothing in the tail and advances nothing"
    ;; The direct form of "a surviving older renewal is not inferred to be
    ;; the newest": the lease is registered (an earlier renewal observed at
    ;; 3), the medium gaps at 6 with nothing of the lease's in the retained
    ;; tail, and the marking sets :resumed without touching the last
    ;; observation -- no observation is manufactured for a window the judge
    ;; did not see.
    (let [system (-> (setup {:capacity 4})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 3)
                     (renewal-from :l1 :holder-a)
                     step!
                     (fill! 5 :filler)
                     (tick! 6)
                     step!)]
      (is (= :unknown (get-in system [:judge :ledger :l1 :evidence])))
      (is (= {:ms 6} (get-in system [:judge :ledger :l1 :resumed])))
      (is (= {:ms 3} (get-in system [:judge :ledger :l1 :last-observation]))
          "the gap itself advanced no observation")))
  (testing "Limits: a renewal re-read after recovery is evidence observed now"
    ;; The retained tail holds a renewal from before the gap; once drained
    ;; it is evidence observed now, and a late-observed renewal buys a fresh
    ;; duration -- the contract's Limits tolerance, not a contradiction of
    ;; the rule above.
    (let [system (-> (setup {:capacity 4})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 3)
                     (renewal-from :l1 :holder-a)
                     step!
                     (fact! :filler)
                     (fact! (lease/renewal :l1))
                     (fact! :filler)
                     (fact! :filler)
                     (fact! :filler)
                     (tick! 6)
                     step!)]
      (is (= :known (get-in system [:judge :ledger :l1 :evidence])))
      (is (= {:ms 6} (get-in system [:judge :ledger :l1 :last-observation]))
          "the re-observed renewal counts at its observation time, not its
           authoring time"))))


(deftest tick-gap-is-not-an-evidence-gap-test
  (testing "J9: a tick-cursor gap makes the pass late, not the leases unknown"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :capacity 2})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 3)
                     (tick! 5)
                     (tick! 9)
                     step!)]
      (is (= {:ms 9} (get-in system [:judge :now]))
          "the tick drain resumed past the gap and reached the newest reading")
      (is (= :known (get-in system [:judge :ledger :l1 :evidence]))
          "a tick gap marks nothing unknown")
      (is (= {:ms 1} (get-in system [:judge :ledger :l1 :last-observation])))
      (is (= [] @log) "9 - 1 = 8 <= 10: a late pass is not a wrong pass"))))


(deftest reclaim-before-record-test
  (testing "J8: a reclaim that fails leaves the lease pending and unrecorded"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) false)})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 12)
                     step!)]
      (is (= [:db] @log) "the procedure was attempted and its report recorded")
      (is (= {:state :pending :cause :silence :success false}
             (get-in system [:judge :ledger :l1 :reclaim]))
          "pending with its classified cause and the failed report")
      (is (= [(lease/grant :l1 :db :holder-a {:ms 10})]
             (drain-values (:writer system)))
          "no :lapsed record without a successful reclaim")
      (let [system (-> (update system :judge assoc :reclaim
                               (fn [subject] (swap! log conj subject) true))
                       (tick! 13)
                       step!)]
        (is (= [:db :db] @log)
            "the idempotent procedure is reached again through clause 1")
        (is (= [(lease/grant :l1 :db :holder-a {:ms 10})
                (lease/lapsed :l1 :silence)]
               (drain-values (:writer system)))
            "the record follows the successful act")
        (is (empty? (get-in system [:judge :ledger]))
            "and only then does the lease leave the ledger")))))


(deftest throwing-reclaim-does-not-lose-the-pass-test
  (testing "P2-1: a throwing reclaim is \"did not report\", not a lost pass"
    (let [calls (atom [])
          system (-> (setup {:reclaim (fn [subject]
                                        (swap! calls conj subject)
                                        (if (= subject :db)
                                          (throw (ex-info "reclaim exploded"
                                                          {:subject subject}))
                                          true))})
                     ;; two leases: :l1 over :db (throws) and :l9 over :db9
                     grant!
                     (update :judge lease/author-grant
                             (lease/grant :l9 :db9 :holder-b {:ms 1}))
                     (tick! 1)
                     step!
                     ;; both past their durations
                     (tick! 12)
                     step!)]
      ;; map-iteration order processes :l9 first; whichever goes first, the
      ;; throw must not cost the other lease its reclaim and record
      (is (= 2 (count @calls)) "both procedures were invoked")
      (is (= {:state :pending :cause :silence :success false}
             (get-in system [:judge :ledger :l1 :reclaim]))
          "the throw is recorded as did-not-report, pending and unrecorded")
      (is (empty? (get-in system [:judge :ledger :l9]))
          "the other lease was still reclaimed and recorded in the same pass")
      (is (= 3 (count (drain-values (:writer system))))
          "both grants and the one :lapsed were written")
      (is (= (lease/lapsed :l9 :silence) (last (drain-values (:writer system))))))))


(deftest restart-reclaims-then-regrants-test
  (testing "J11: the reclaim ends the prior tenure, a new grant alone does not"
    (let [calls (atom [])
          system (-> (setup {:reclaim (fn [subject]
                                        (swap! calls conj [:reclaim subject])
                                        true)})
                     grant!
                     (tick! 1)
                     step!)]
      (is (contains? (get-in system [:judge :ledger]) :l1))
      ;; The ledger is lost; the seams and the tick continuity survive.
      (let [result (lease/restart (assoc (:judge system) :ledger {})
                                  [{:subject :db
                                    :holder :holder-a
                                    :duration {:ms 10}}])
            system (assoc system :judge (:judge result))
            queued (first (get-in system [:judge :queue]))]
        (is (= [] (:unreclaimed result)) "everything possessed was reclaimed")
        (is (= [] (:discarded-queue result)))
        (is (= [[:reclaim :db]] @calls) "reclaimed first")
        (is (not= :l1 (:dao.lease/lease queued)) "a fresh identity")
        (is (= [:db :holder-a {:ms 10}]
               ((juxt :dao.lease/subject :dao.lease/holder :dao.lease/duration)
                queued)))
        (let [system (-> system (tick! 21) step!)]
          (is (= [[:reclaim :db]] @calls) "re-granting reclaims nothing further")
          (is (= {:ms 21}
                 (get-in system [:judge :ledger (:dao.lease/lease queued)
                                 :tenure-start]))
              "the fresh grant is seeded with the next pass's now")
          (is (= 1 (count (get-in system [:judge :ledger])))))))))


(deftest restart-reports-unreclaimed-and-clears-state-test
  (testing "P2-6: inventory whose reclaim fails is reported, not skipped"
    (let [calls (atom [])
          system (setup {:reclaim (fn [subject]
                                    (swap! calls conj subject)
                                    (not= subject :stuck))})
          result (lease/restart (:judge system)
                                [{:subject :stuck
                                  :holder :holder-a
                                  :duration {:ms 10}}
                                 {:subject :free
                                  :holder :holder-b
                                  :duration {:ms 5}}])
          judge (:judge result)]
      (is (= [:stuck :free] @calls))
      (is (= [{:subject :stuck :holder :holder-a :duration {:ms 10}}]
             (:unreclaimed result))
          "the unreclaimed item is returned to the composition")
      (is (= 1 (count (:queue judge)))
          "only the freed resource is re-granted")
      (is (= :free
             (get-in judge [:queue 0 :dao.lease/subject])))))
  (testing "P2-6: a throwing inventory reclaim counts as did-not-report"
    (let [result (lease/restart (:judge (setup {:reclaim (fn [_]
                                                           (throw (ex-info "no" {})))}))
                                [{:subject :s :holder :h :duration {:ms 1}}])]
      (is (= 1 (count (:unreclaimed result))))
      (is (= [] (get-in result [:judge :queue])))))
  (testing "P2-6: the stale queue is cleared and its disposition recorded"
    (let [system (-> (setup {})
                     grant!
                     (update :judge lease/author-grant
                             (lease/grant :l7 :db7 :holder-c {:ms 3})))
          result (lease/restart (:judge system) [])
          judge (:judge result)]
      (is (= [] (:queue judge)) "nothing stale survives the restart")
      (is (= [(lease/grant :l1 :db :holder-a {:ms 10})
              (lease/grant :l7 :db7 :holder-c {:ms 3})]
             (:discarded-queue result))
          "the discarded grants are recorded, not silently dropped")))
  (testing "P2-6: :seen survives, so an old self-authored grant cannot re-seed"
    (let [system (-> (setup {})
                     grant!
                     (tick! 1)
                     step!)
          handle (new-buffer 8)]
      (is (contains? (get-in system [:judge :seen]) :l1))
      (append-ok! handle (lease/grant :l1 :db :holder-a {:ms 10}))
      (let [result (lease/restart (assoc (:judge system) :ledger {}) [])
            system (-> system
                       (assoc :judge (lease/wire-facts (:judge result)
                                                       handle
                                                       (oldest-cursor handle)
                                                       :grantor))
                       (tick! 2)
                       step!)]
        ;; the grantor's own :accepted for :l1 was sitting on a medium from
        ;; before the loss; :seen surviving makes it inadmissible
        (is (= 1 (get-in system [:judge :dropped]))
            "the pre-loss :accepted re-reads as inadmissible, not as a new
             tenure for a subject that now belongs to someone else")
        (is (empty? (get-in system [:judge :ledger])))))))


(deftest proposal-creates-no-state-test
  (testing "J3/J12: nothing exists until :accepted; a grantor owes no answer"
    (let [system (-> (setup {})
                     (fact! (lease/proposal :p1 :db))
                     (tick! 1)
                     step!)]
      (is (empty? (get-in system [:judge :ledger])))
      (is (= 1 (count (get-in system [:judge :drained-proposals]))))
      (is (= [] (drain-values (:writer system)))
          "no answer is owed and none was authored"))))


(deftest answer-hook-grants-and-refuses-test
  (testing "J3/J6 step 3: the composition answers drained proposals"
    (let [answer (fn [_judge proposals]
                   (reduce (fn [acc {:keys [fact author]}]
                             (let [pid (get fact :dao.lease/proposal)]
                               (if (= author :holder-a)
                                 (assoc acc :grants
                                        (conj (get acc :grants [])
                                              (lease/grant :g1 :db author {:ms 10}
                                                           {:dao.lease/proposal pid})))
                                 (assoc acc :refusals
                                        (conj (get acc :refusals [])
                                              {:proposer author
                                               :refusal (lease/refusal pid)})))))
                           {}
                           proposals))
          system (-> (setup {:answer answer})
                     (fact! (lease/proposal :p1 :db))
                     (fact-from! :holder-b (lease/proposal :p2 :db))
                     (tick! 1)
                     step!)]
      (is (= [(lease/grant :g1 :db :holder-a {:ms 10} {:dao.lease/proposal :p1})
              (lease/refusal :p2)]
             (drain-values (:writer system)))
          "the grant and the refusal are both written")
      (is (contains? (get-in system [:judge :ledger]) :g1)
          "the answered grant is seeded")
      (is (= {:ms 1} (get-in system [:judge :ledger :g1 :tenure-start]))
          "stamped with this pass's now")
      (is (= :dao.lease/accepted (get-in system [:judge :answered
                                                 [:holder-a :p1]]))
          "the answer is keyed by the PROPOSER -- the grant's holder -- and
           the pid")
      (is (= :dao.lease/rejected (get-in system [:judge :answered
                                                 [:holder-b :p2]])))))
  (testing "J3/V7: a second answer to one proposal is dropped, not delivered"
    (let [answer (fn [_judge proposals]
                   (reduce (fn [acc {:keys [fact author]}]
                             (let [pid (get fact :dao.lease/proposal)]
                               (assoc acc :refusals
                                      (conj (get acc :refusals [])
                                            {:proposer author
                                             :refusal (lease/refusal pid)}))))
                           {}
                           proposals))
          system (-> (setup {:answer answer})
                     (fact-from! :holder-b (lease/proposal :p9 :db))
                     (tick! 1)
                     step!)]
      (is (= [(lease/refusal :p9)] (drain-values (:writer system))))
      (is (= :dao.lease/rejected (get-in system [:judge :answered
                                                 [:holder-b :p9]])))
      ;; A grantor-authored grant answering the already-refused p9 arrives
      ;; on the grantor's own medium: structurally valid, inadmissible
      ;; against the judge's observations, and dropped rather than applied.
      (let [system (-> system
                       (fact-from! :grantor
                                   (lease/grant :l8 :db :holder-b {:ms 5}
                                                {:dao.lease/proposal :p9}))
                       (tick! 2)
                       step!)]
        (is (= 1 (get-in system [:judge :dropped]))
            "answering one proposal with both :accepted and :rejected")
        (is (= [(lease/refusal :p9)] (drain-values (:writer system))))
        (is (false? (contains? (get-in system [:judge :ledger]) :l8)))))))


(deftest cursor-retirement-and-pass-abort-test
  (testing "J10: a drain ending in :dao.stream/end retires that cursor"
    (let [system (-> (setup {})
                     grant!
                     (tick! 1)
                     step!)]
      (ds/close! (:facts system))
      (let [system (step! system)
            entry (first (get-in system [:judge :facts]))]
        (is (true? (:retired entry)))
        (is (nil? (get-in system [:judge :abort])))
        ;; Later passes do not call a retired cursor again: swap in a handle
        ;; that would fail the test if read.
        (let [calls (atom 0)
              counting (reify
                         ds/IDaoStreamReader
                         (cursor
                           [_ _anchor]
                           {:dao.stream/outcome :dao.stream/ok
                            :dao.stream/cursor :c})

                         (next
                           [_ _cursor]
                           (swap! calls inc)
                           (throw (ex-info "retired cursor was read"
                                           {:calls @calls}))))
              system (-> system
                         (update :judge assoc :facts
                                 [(assoc entry :handle counting)])
                         (tick! 2)
                         step!)]
          (is (= 0 @calls) "the retired cursor was not read again")
          (is (true? (get-in system [:judge :facts 0 :retired]))))))))


(doseq [outcome [:dao.stream/transport-error
                 :dao.stream/cursor-mismatch
                 :dao.stream/invalid-cursor]]
  (testing (str "J10: " outcome " ends the pass before classification")
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 21))
          system (-> system
                     (update :judge assoc :facts
                             [{:handle (fake-reader outcome)
                               :cursor :broken
                               :retired false
                               :truncated? false
                               :leases #{}
                               :source :holder-a}])
                     step!)]
      (is (= outcome (get-in system [:judge :abort])))
      (is (contains? (get-in system [:judge :ledger]) :l1)
          "now=21 is 20 past the grant, past duration 10, but an aborted
             pass reclaims nothing: a broken reader is never silence")
      (is (= [] @log) "no reclaim, no record")
      (is (= [(lease/grant :l1 :db :holder-a {:ms 10})]
             (drain-values (:writer system))))
      ;; The abort is per-pass: a later pass with a healthy medium
      ;; classifies.
      (let [healthy (new-buffer 8)
            system (-> system
                       (update :judge assoc :facts [])
                       (update :judge lease/wire-facts
                               healthy (oldest-cursor healthy) :holder-a)
                       (tick! 22)
                       step!)]
        (is (nil? (get-in system [:judge :abort])))
        (is (= [:db] @log)
            "the overdue silence reclaims on the next healthy pass")
        (is (empty? (get-in system [:judge :ledger])))))))


(deftest tick-abort-does-not-starve-fact-cursors-test
  (testing "P2-8: a persistent tick error still drains every fact cursor"
    (let [system (-> (setup {})
                     (update :judge lease/author-grant
                             (lease/grant :l2 :db2 :holder-b {:ms 100}))
                     grant!
                     (tick! 1)
                     step!
                     ;; establish now=3 on a healthy pass...
                     (tick! 3)
                     step!
                     ;; ...then the tick transport dies for good, and the
                     ;; holder's renewal arrives after
                     (update :judge update :ticks
                             (fn [ticks]
                               (mapv (fn [t]
                                       (assoc t :handle
                                              (fake-reader
                                                :dao.stream/transport-error)))
                                     ticks)))
                     (renewal-from :l2 :holder-b)
                     step!)]
      (is (= :dao.stream/transport-error (get-in system [:judge :abort]))
          "the tick error aborts the pass before classification")
      (is (= {:ms 3} (get-in system [:judge :ledger :l2 :last-observation]))
          "but the fact cursor still drained: the renewal was applied,
           stamped with the newest previously observed reading")
      (is (contains? (get-in system [:judge :ledger]) :l1)
          "and nothing was reclaimed during the aborted pass"))))


(deftest drain-budget-test
  (testing "J16: a cursor still yielding ok after the budget waits a pass"
    ;; one medium, three proposals: the budget is per cursor, so all three
    ;; share the two-element budget of this one cursor
    (let [system (-> (setup {:drain-budget 2})
                     (fact! (lease/proposal :p1 :db))
                     (fact! (lease/proposal :p2 :db))
                     (fact! (lease/proposal :p3 :db))
                     (tick! 1)
                     step!)]
      (is (= 2 (count (get-in system [:judge :drained-proposals])))
          "two proposals drained within the budget")
      (is (= {:ms 1} (get-in system [:judge :now]))
          "the pass still completes with the newest reading drained so far")
      (let [system (-> system (tick! 2) step!)]
        (is (= 1 (count (get-in system [:judge :drained-proposals])))
            "the remainder arrives on the next pass: :drained-proposals is
             this pass's drain, so it holds the one left over")))))


(deftest grantor-authored-facts-establish-terms-test
  (testing "J1: a holder-authored grant establishes no term"
    (let [system (-> (setup {})
                     (fact-from! :holder-a (lease/grant :l5 :db :holder-a
                                                        {:ms 10}))
                     (tick! 1)
                     step!)]
      (is (empty? (get-in system [:judge :ledger]))
          "a holder cannot grant itself tenure, whatever it writes")
      (is (= 0 (get-in system [:judge :dropped]))
          "it is valid data that establishes nothing, not a defect")))
  (testing "J1/J4/J13: a grantor-authored grant seeds through the grant path"
    (let [system (-> (setup {})
                     (fact-from! :grantor (lease/grant :l5 :db :holder-a
                                                       {:ms 10}))
                     (tick! 8)
                     step!)]
      (is (= {:ms 8} (get-in system [:judge :ledger :l5 :tenure-start]))
          "a grant authored between passes is seeded with this pass's now")
      (is (= {:ms 8} (get-in system [:judge :ledger :l5 :last-observation]))
          "and its first observation is that same single stamp")
      (is (= :db (get-in system [:judge :ledger :l5 :subject]))
          "an unsolicited grant carries subject and holder outright")
      (is (= :holder-a (get-in system [:judge :ledger :l5 :holder]))))))


(deftest non-lease-facts-and-defective-facts-test
  (testing "V1/D6: a neither-key value is ignored, not dropped"
    (let [system (-> (setup {})
                     (fact! :not-a-fact)
                     (tick! 1)
                     step!)]
      (is (= 0 (get-in system [:judge :dropped])))
      (is (empty? (get-in system [:judge :ledger])))))
  (testing "D6: a structurally defective lease fact is dropped and establishes nothing"
    (let [system (-> (setup {})
                     (fact! {:dao.lease/status :dao.lease/released})
                     (tick! 1)
                     step!)]
      (is (= 1 (get-in system [:judge :dropped]))
          "a release missing its lease id is defective")
      (is (empty? (get-in system [:judge :ledger])))))
  (testing "D6/V7: an inadmissible repeat is dropped, not applied"
    (let [system (-> (setup {})
                     grant!
                     (tick! 1)
                     step!
                     (fact-from! :grantor (lease/grant :l1 :db :holder-a
                                                       {:ms 10}))
                     (tick! 2)
                     step!)]
      (is (= 1 (get-in system [:judge :dropped]))
          "a repeated :accepted for one lease is inadmissible")
      (is (= 1 (count (get-in system [:judge :ledger])))))))


;; =============================================================================
;; r2 review findings: the prescribed tests, written before the fixes
;; =============================================================================

(deftest r2-forged-release-cannot-poison-a-genuine-one-test
  (testing "P1-1/V7/J2: a forged :released does not make the holder's a repeat"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)})
                     grant!
                     (tick! 1)
                     step!
                     ;; a non-holder forges a release for :l1 on their own
                     ;; medium, then the holder releases in earnest
                     (tick! 4)
                     (fact-from! :someone-else (lease/release :l1))
                     step!)]
      (is (= [] @log) "the forged release reclaimed nothing")
      (is (false? (contains? (get-in system [:judge :seen :l1])
                             :dao.lease/released))
          "and entered :seen for no one: it poisoned nothing")
      (let [system (-> system
                       (tick! 5)
                       (fact! (lease/release :l1))
                       step!)]
        (is (= [:db] @log)
            "the holder's genuine release still ends the tenure")
        (is (= [(lease/lapsed :l1 :release)] (records system))
            "with cause :release, not a late :silence")))))


(deftest r2-truncated-drain-cannot-manufacture-silence-test
  (testing "P1-2: a budget-truncated fact drain does not classify :silence"
    ;; The review's scenario: budget 2; filler, filler, then the holder's
    ;; renewal; a tick past duration must NOT reclaim while the renewal sits
    ;; unread.
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :drain-budget 2})
                     grant!
                     (tick! 1)
                     step!
                     (fact! :filler)
                     (fact! :filler)
                     (renewal-from :l1 :holder-a)
                     (tick! 12)
                     step!)]
      (is (= [] @log)
          "silence over a window the drain truncated is absence evidence
           the judge does not have")
      (is (contains? (get-in system [:judge :ledger]) :l1))
      (is (true? (get-in system [:judge :facts 0 :truncated?]))
          "the truncation is recorded on the cursor entry")
      (let [system (-> system (tick! 13) step!)]
        (is (= {:ms 13} (get-in system [:judge :ledger :l1 :last-observation]))
            "the next pass drains the renewal and re-opens the window")
        (is (= [] @log) "13 - 13 = 0: still not due")
        (is (false? (get-in system [:judge :facts 0 :truncated?]))
            "a drain that reaches quiescence clears the mark"))))
  (testing "P1-2: a registered lease is suppressed while its medium truncates"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :drain-budget 2})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 3)
                     (renewal-from :l1 :holder-a)
                     step!
                     (fact! :filler)
                     (fact! :filler)
                     (renewal-from :l1 :holder-a)
                     (tick! 15)
                     step!)]
      (is (= [] @log)
          "15 - 3 = 12 > 10, but the second renewal sits unread behind the
           truncated budget: no silence over an unseen window")
      (is (= :known (get-in system [:judge :ledger :l1 :evidence])))
      (let [system (-> system (tick! 16) step!)]
        (is (= {:ms 16} (get-in system [:judge :ledger :l1 :last-observation])))
        (is (= [] @log) "the window re-opens from the observed renewal"))))
  (testing "P1-2: suppression bars :silence only, never the other causes"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :drain-budget 1})
                     (update :judge lease/author-grant
                             (lease/grant :l1 :db :holder-a {:ms 100}
                                          {:dao.lease/max {:ms 5}}))
                     (tick! 1)
                     step!
                     (tick! 4)
                     (fact! (lease/release :l1))
                     step!
                     ;; the release is applied; now flood the medium so the
                     ;; cursor truncates every later pass
                     (fill! 5 :filler)
                     (tick! 30)
                     step!)]
      (is (= [:db] @log)
          "released and cap both still fire while the medium truncates")
      (is (= [(lease/lapsed :l1 :release)] (records system))))))


(deftest r2-keep-alive-attack-test
  (testing "P1-3: a non-holder cannot keep a lease alive by gap-flooding"
    ;; The attack: register the victim's lease on the attacker's medium with
    ;; a non-holder renewal, then overflow that medium to gap it, marking
    ;; the lease unknown with a fresh resumed reading -- repeat forever and
    ;; silence never lands. With authoritative-only registration, the
    ;; attacker's facts register nothing and their gaps mark nothing.
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :capacity 4
                             :media-capacity 4})
                     grant!
                     (tick! 1)
                     step!
                     ;; the holder's real evidence, on the holder's medium
                     (tick! 3)
                     (renewal-from :l1 :holder-a)
                     step!
                     ;; the attack: a renewal naming :l1 on the attacker's
                     ;; medium, then an overflow of that medium
                     (fact-from! :attacker (lease/renewal :l1))
                     (fact-from! :attacker :filler)
                     (fact-from! :attacker :filler)
                     (fact-from! :attacker :filler)
                     (fact-from! :attacker :filler)
                     (tick! 6)
                     step!)]
      (is (= :known (get-in system [:judge :ledger :l1 :evidence]))
          "the attacker's gap marked nothing: their medium never carried an
           authoritative fact for the lease")
      (is (= {:ms 3} (get-in system [:judge :ledger :l1 :last-observation]))
          "silence is still measured from the holder's real evidence")
      (let [system (-> system (tick! 15) step!)]
        (is (= [:db] @log) "15 - 3 = 12 > 10: the lease lapsed on time")
        (is (= [(lease/lapsed :l1 :silence)] (records system)))))))


(deftest r2-hostile-reading-cannot-wedge-the-drain-test
  (testing "P1-4: an over-bound reading is defective, never an overflow"
    (let [system (-> (setup {:units {:ms 1 :s 1000}})
                     grant!
                     (tick! 1)
                     step!
                     ;; a tick fact on the fact medium whose reading is past
                     ;; the per-unit base bound: {s 4503599627370497} with
                     ;; an ms table
                     (fact! {:dao.lease/event :dao.lease/tick
                             :dao.lease/reading {:s 4503599627370497}})
                     (tick! 2)
                     step!)]
      (is (nil? (get-in system [:judge :abort]))
          "the judge stepped; no exception escaped, no cursor wedged")
      (is (= 1 (get-in system [:judge :dropped]))
          "the hostile fact was dropped as structurally defective")
      (is (contains? (get-in system [:judge :ledger]) :l1)
          "the lease is unharmed")))
  (testing "P1-4: a legal reading is ordered without overflow"
    (let [system (-> (setup {:units {:ms 1 :s 1000}})
                     grant!
                     (tick! 1)
                     step!)]
      (append-ok! (:ticks system) (lease/tick {:s 1000000}))
      (let [system (step! system)]
        (is (= {:s 1000000} (get-in system [:judge :now]))
            "a {s 1000000} tick is processed and kept as the newest reading")
        (is (nil? (get-in system [:judge :abort])))))))


(deftest r2-rejected-grants-are-discarded-not-rewaited-test
  (testing "P2-2: a rejected grant is counted once and leaves the queue"
    (let [writer (new-buffer 64)
          judge (-> (lease/initial-judge
                      {:units {:ms 1}
                       :resolver (fn [source _] source)
                       :reclaim (fn [_] true)
                       :writer writer
                       :self :grantor})
                    (lease/wire-tick (new-buffer 8)
                                     (oldest-cursor (new-buffer 8))
                                     :tick-driver))
          ticks (new-buffer 8)
          judge (update judge :ticks
                        (fn [_]
                          [{:handle ticks
                            :cursor (oldest-cursor ticks)
                            :retired false
                            :source :tick-driver}]))
          ;; a grant in a foreign unit enters the queue via the one-arg gate
          ;; but is rejected at delivery against the judge's table
          judge (lease/author-grant judge
                                    (lease/grant :lx :dbx :h {:hr 5}))]
      (append-ok! ticks (lease/tick {:ms 1}))
      (let [judge (lease/judge-step judge)]
        (is (= 1 (:dropped judge)) "counted once")
        (is (= [] (:queue judge)) "and discarded, not kept for every pass"))
      (let [judge (lease/judge-step (lease/judge-step judge))]
        (is (= 1 (:dropped judge))
            "a later pass neither re-delivers nor re-counts it")))))


(deftest r2-only-accepted-and-rejected-are-deliverable-test
  (testing "P2-3: a hook handing back a :lapsed fact is refused"
    (let [answer (fn [_judge _proposals]
                   {:refusals [(assoc (lease/lapsed :l9 :policy)
                                      :dao.lease/status :dao.lease/lapsed)]})
          system (-> (setup {:answer answer})
                     (tick! 1)
                     step!)]
      (is (= [] (drain-values (:writer system)))
          "no :lapsed was written: a reclaim that never happened cannot be
           recorded by an answer hook")
      (is (= 1 (get-in system [:judge :dropped]))
          "the undeliverable fact was refused with a count"))))


(deftest r2-thrown-read-folds-into-abort-test
  (testing "P1-4c: a hostile transport that throws cannot wedge the step"
    (let [boom (reify
                 ds/IDaoStreamReader
                 (cursor
                   [_ _anchor]
                   {:dao.stream/outcome :dao.stream/ok
                    :dao.stream/cursor :c})

                 (next
                   [_ _cursor]
                   (throw (ex-info "hostile transport" {}))))
          system (-> (setup {})
                     grant!
                     (tick! 1)
                     step!
                     (update :judge assoc :facts
                             [{:handle boom
                               :cursor :c
                               :retired false
                               :truncated? false
                               :leases #{}
                               :source :holder-a}])
                     (tick! 12)
                     step!)]
      (is (= :dao.stream/transport-error (get-in system [:judge :abort]))
          "the throw is classified as a transport error abort")
      (is (contains? (get-in system [:judge :ledger]) :l1)
          "an aborted pass reclaims nothing -- the lease survives the throw"))))


;; The plan's Phase 1 adversarial purity test is deliberately absent: the
;; final design gives `defective?` no seam a clock or a global could be
;; wired into (it is a pure function of fact and units, closing over
;; nothing), so a test asserting "neither is consulted" could not fail and
;; pinned nothing. Purity here is pinned the way C5 is pinned: by the
;; structure of the code, grepped in the end condition.


;; =============================================================================
;; r3 review findings: the prescribed failing tests, written before the fixes
;; =============================================================================

(deftest r3-tick-past-the-old-bound-advances-now-test
  (testing "N1: a reading far past 10^6 magnitudes is legal and ordered"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :units {:ms 1 :s 1000}})
                     (update :judge lease/author-grant
                             (lease/grant :l1 :db :holder-a {:s 5000000000}
                                          {:dao.lease/max {:s 3000000000}}))
                     (tick! 1)
                     step!)]
      (append-ok! (:ticks system) (lease/tick {:s 4000000000}))
      (let [system (step! system)]
        (is (= {:s 4000000000} (get-in system [:judge :now]))
            "a {s 4000000000} tick -- four billion seconds past the old
             10^6 raw bound -- advances now")
        (is (= [:db] @log)
            "and the arithmetic still orders: tenure 4e9 > max 3e9 is :cap")
        (is (= [(lease/lapsed :l1 :cap)] (records system)))))))


(deftest r3-over-bound-reading-is-a-structural-defect-test
  (testing "N1: the bound is per-unit, by division against the table"
    (let [units {:ms 1 :s 1000}]
      (is (vector? (lease/defective? {:dao.lease/event :dao.lease/tick
                                      :dao.lease/reading {:s 4503599627370497}}
                                     units))
          "past 2^52 in base: structurally defective")
      (is (vector? (lease/defective? {:dao.lease/event :dao.lease/tick
                                      :dao.lease/reading {:s 4503599627370496}}
                                     units))
          "raw n under 2^52 but past quot(2^52, 1000) for :s: still over
           the base bound, still defective")
      (is (false? (lease/defective? {:dao.lease/event :dao.lease/tick
                                     :dao.lease/reading {:s 4503599627370}}
                                    units))
          "the largest legal :s magnitude against an ms-based table"))))


(deftest r3-over-bound-unit-table-refused-test
  (testing "N1: a table magnitude past 2^52 is refused at assembly"
    (is (= :unit
           (assembly-defect {:units {:ms 1
                                     :s 4503599627370497}
                             :resolver (fn [_source _fact] nil)})))
    (is (= :unit
           (assembly-defect {:units {:ms 4503599627370497}
                             :resolver (fn [_source _fact] nil)})))
    (is (some? (lease/initial-judge {:units {:ms 1 :h 3600000}
                                     :resolver (fn [_source _fact] nil)}))
        "a plain hour unit is legal again: the old raw bound rejected it")))


(deftest r3-over-bound-tick-on-tick-cursor-is-visible-test
  (testing "N1: an over-bound tick on a tick cursor is counted, never silent"
    (let [system (-> (setup {})
                     grant!
                     (tick! 1)
                     step!)]
      (append-ok! (:ticks system)
                  {:dao.lease/event :dao.lease/tick
                   :dao.lease/reading {:ms 4503599627370497}})
      (let [system (step! system)]
        (is (= 1 (get-in system [:judge :dropped]))
            "the over-bound tick is visible under :dropped")
        (is (= {:ms 1} (get-in system [:judge :now]))
            "it did not advance now")
        (is (nil? (get-in system [:judge :abort])))))))


(deftest r3-two-holders-sharing-a-proposal-id-test
  (testing "N2: B's refused :p1 does not block A's :p1"
    (let [answer (fn [_judge proposals]
                   (reduce (fn [acc {:keys [fact author]}]
                             (let [pid (get fact :dao.lease/proposal)]
                               (if (= author :holder-a)
                                 (assoc acc :grants
                                        (conj (get acc :grants [])
                                              (lease/grant :g1 :db author
                                                           {:ms 10}
                                                           {:dao.lease/proposal pid})))
                                 (assoc acc :refusals
                                        (conj (get acc :refusals [])
                                              {:proposer author
                                               :refusal (lease/refusal pid)})))))
                           {}
                           proposals))
          system (-> (setup {:answer answer})
                     (fact-from! :holder-b (lease/proposal :p1 :db))
                     (tick! 1)
                     step!)]
      (is (= [(lease/refusal :p1)] (drain-values (:writer system)))
          "holder B's :p1 is refused")
      (let [system (-> system
                       (fact! (lease/proposal :p1 :db))
                       (tick! 2)
                       step!)]
        (is (= 0 (get-in system [:judge :dropped]))
            "A's grant answering :p1 is not rejected as B's second answer")
        (is (= [(lease/refusal :p1)
                (lease/grant :g1 :db :holder-a {:ms 10}
                             {:dao.lease/proposal :p1})]
               (drain-values (:writer system)))
            "both answers were delivered: the key is the proposer")
        (is (contains? (get-in system [:judge :ledger]) :g1)
            "A's grant seeded a tenure")))))


(deftest r3-grant-drained-then-holder-medium-truncated-test
  (testing "N3: a grant read from a cursor registers no medium"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)
                             :drain-budget 1})
                     (fact-from! :grantor (lease/grant :l1 :db :holder-a
                                                       {:ms 10}))
                     (tick! 1)
                     step!
                     ;; the holder's medium now truncates before any renewal
                     ;; of this lease was ever observed on it
                     (fact! :filler)
                     (fact! :filler)
                     (tick! 12)
                     step!)]
      (is (= [] @log)
          "the lease never renewed on any medium the judge observed, and
           the holder's medium truncated: no silence over that window")
      (is (contains? (get-in system [:judge :ledger]) :l1))
      ;; once the medium drains to quiescence the window IS observed, and
      ;; the never-renewed lease lapses
      (let [system (-> system (tick! 13) step!)]
        (is (= [:db] @log) "12 - 1 > 10 once the drain is complete")
        (is (= [(lease/lapsed :l1 :silence)]
               (drain-values (:writer system)))
            "the writer holds only the record: a drained grant was never
             written to it")))))


(deftest r3-tolerance-validated-test
  (testing "N4: a tolerance in a foreign unit is refused at assembly"
    (doseq [bad [{:hr 3} {:ms 1 :s 2} 5 {:ms -1}]]
      (is (= :tolerance
             (assembly-defect {:units {:ms 1 :s 1000}
                               :tolerance bad
                               :resolver (fn [_source _fact] nil)})))))
  (testing "N4/R3: the tolerance bound is per-unit, not raw"
    (is (= :tolerance
           (assembly-defect {:units {:ms 1 :s 1000}
                             :tolerance {:s 4503599627370496}
                             :resolver (fn [_source _fact] nil)}))
        "past quot 2^52 1000 against an ms-based table, though raw-legal"))
  (testing "legal tolerances assemble"
    (is (some? (lease/initial-judge {:units {:ms 1 :s 1000}
                                     :tolerance {:ms 0}
                                     :resolver (fn [_source _fact] nil)})))
    (is (some? (lease/initial-judge {:units {:ms 1 :s 1000}
                                     :tolerance nil
                                     :resolver (fn [_source _fact] nil)})))))


(deftest r3-resolver-throw-drops-element-and-continues-test
  (testing "N5: a resolver throwing on a hostile envelope drops that element"
    (let [resolver (fn [_source fact]
                     (when (get fact :poison)
                       (throw (ex-info "hostile envelope" {:fact fact})))
                     :holder-a)
          system (-> (setup {})
                     (update :judge assoc :resolver resolver)
                     grant!
                     (tick! 1)
                     step!
                     (fact! (assoc (lease/renewal :l1) :poison true))
                     (fact! (lease/renewal :l1))
                     (tick! 7)
                     step!)]
      (is (= 1 (get-in system [:judge :dropped]))
          "the poisoned element was dropped")
      (is (= {:ms 7} (get-in system [:judge :ledger :l1 :last-observation]))
          "the cursor advanced past it and the clean renewal after it was
           applied: judging continues")
      (is (nil? (get-in system [:judge :abort]))
          "a processing throw is not a transport abort"))))


;; =============================================================================
;; r4 review findings
;; =============================================================================

(deftest r4-judge-without-a-resolver-throws-test
  (testing "R1: a missing resolver is an assembly defect, not dropped facts"
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
                 (lease/initial-judge {}))
        "a judge assembled with no resolver must throw before any step,
         never silently drop every lease fact into :dropped")
    (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
                 (lease/initial-judge {:resolver :not-a-fn})))
    (is (some? (lease/initial-judge {:resolver (fn [_source _fact] :x)}))))
  (testing "R1: a constructed judge whose facts arrive never lapses via drops"
    (let [log (atom [])
          system (-> (setup {:reclaim (fn [subject] (swap! log conj subject) true)})
                     grant!
                     (tick! 1)
                     step!
                     (tick! 7)
                     (renewal-from :l1 :holder-a)
                     step!
                     (tick! 15)
                     step!)]
      (is (= 0 (get-in system [:judge :dropped]))
          "every fact that arrived was read and applied")
      (is (= [] @log)
          "the renewal counted: no false :silence over an observed holder")
      (is (contains? (get-in system [:judge :ledger]) :l1)))))
