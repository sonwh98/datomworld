(ns yin.vm.ucf.stage-e-scenarios
  "M-next stage E: the crash/partition scenarios of linker-dht 14.2.4
   through the wired composition, each a sequence of lives over one
   durable root (yin.vm.ucf.stage-e-world) and one check over their
   reports.

   A scenario is {:id k :lives [f ...] :check g}.  Each life function
   takes the results so far and answers the next life's spec -- tagged
   :on :src or :rcv, the host role a process runner maps onto an
   ordered host pair -- or nil to skip it.  A result is
   {:spec s :events [...] :report r :killed? bool}.  The check takes
   every result and the final inspection of the durable state, and
   asserts with clojure.test.  Both runners hand the check the same
   plain data: `run-in-process` (every lane; Dart's isolated-runtime
   form) and the JVM process harness (yin.vm.ucf.stage-e-process-test,
   one operating-system process per life, killed with SIGKILL at the
   life's cut)."
  (:require [clojure.test :refer [is testing]]
            [yin.vm.ucf.stage-e-world :as world]))


;; =============================================================================
;; The in-process runner
;; =============================================================================


(defn run-life-in-process
  "Run one life in this process.  A cut kills it: every durable medium
   goes dead at the cut, the life unwinds, and its runtime's locks are
   released the way an operating system releases a killed process's."
  [spec]
  (let [events (atom [])
        ctx (world/context {:cut (:cut spec)
                            :fail (:fail spec)
                            :emit! (fn [e] (swap! events conj e))})
        outcome (try
                  {:report (world/run-life ctx spec)}
                  (catch #?(:cljd Object :clj Throwable :cljs :default) e
                    (if (world/killed? e)
                      {:killed? true}
                      (do (world/release-dead! ctx)
                          (throw e)))))]
    (when (:killed? outcome)
      (world/release-dead! ctx))
    (assoc outcome
           :spec spec
           :events @events
           :killed? (boolean (:killed? outcome)))))


(defn run-scenario
  "Run `scenario` over a fresh root with `run-life!` (spec -> result),
   inspect the durable state, and run its check.  Answers the results."
  [scenario run-life! label]
  (let [root (world/temp-root)]
    (try
      (let [results (reduce (fn [results f]
                              (if-some [spec (f results)]
                                (conj results (run-life! (assoc spec :root root)))
                                results))
                            []
                            (:lives scenario))
            inspection (world/inspect {:root root
                                       :holders (or (:holders scenario) ["h1"])
                                       :targets (keep (fn [r]
                                                        (some #(when (= :enrolled (:event %))
                                                                 (:target %))
                                                              (:events r)))
                                                      results)})]
        (testing (str (:id scenario) " " label)
          ((:check scenario) results inspection))
        results)
      (finally
        (world/cleanup-root! root)))))


(defn run-in-process
  [scenario]
  (run-scenario scenario run-life-in-process "(isolated runtime, this process)"))


;; =============================================================================
;; Reading results
;; =============================================================================


(defn- final
  [result]
  (get-in result [:report :final]))


(defn- holder
  [result h]
  (get-in (final result) [:holders h]))


(defn- records
  [journal kind action]
  (filterv #(and (= kind (:yin.k/journal %))
                 (or (nil? action) (= action (:yin.k/action %))))
           journal))


(defn- enrolled-target
  [results]
  (some (fn [r] (some #(when (= :enrolled (:event %)) (:target %)) (:events r)))
        results))


(defn binding-of
  "Holder `h`'s binding as life `result` emitted it (a killed life's
   report is lost; its events stand)."
  [result h]
  (some #(when (and (= :binding (:event %)) (= h (:holder %))) (:binding %))
        (:events result)))


(defn snapshot-of
  [result k]
  (some #(when (and (= :snapshot (:event %)) (= k (:key %))) (:snapshot %))
        (:events result)))


(defn- cut-fired
  [result]
  (some #(when (= :cut (:event %)) %) (:events result)))


(defn- live-leases
  "The leases a projection shows live (no cause), as entries."
  [p]
  (filterv #(nil? (:dao.lease/cause %)) (vals (:leases p))))


;; =============================================================================
;; Row 7: kills around completion, and the successor run on the receiver
;; =============================================================================


(def row7-cuts
  [nil
   :row7/after-successor-append
   :row7/after-resumed-report
   :row7/after-release-append
   :row7/after-closure])


(defn row7
  "Row 7 over one ordered host pair: the source runs, hands off and exits
   on :src, killed at `cut` (none: a clean handoff); the receiver host
   recovers the source's exit from its journals and finishes it, then
   runs the recorded successor once from the canonical bytes in the
   store, its program input delivered on the receiver -- every value
   crossing between the two runtimes is a durable byte."
  [cut]
  {:id [:row-7 cut]
   :holders ["h1" "h2" "h3" "h4" "h5"]
   :lives
   [(fn [_]
      {:on :src :kind :parked :cut cut
       :ops [[:source "h1"]
             [:until "h1" :running 60]
             [:binding "h1"]
             [:hand-off "h1"]
             [:until "h1" :exited 80]]})
    (fn [results]
      (when (:killed? (peek results))
        {:on :rcv :kind :parked
         :ops (if (= :row7/after-successor-append cut)
                ;; the attempted successor's candidate competes from the
                ;; start; the regranted origin hands off on its activation
                ;; tick (one tick later its machine holds an unapplied
                ;; observation and is no liftable safepoint, UCF 7.4.1)
                [[:at-open]
                 [:source "h1"]
                 [:candidate "h5" {:kind :successor :of "h1"}]
                 [:until "h1" :running 90]
                 [:snapshot :before-closure]
                 [:hand-off "h1"]
                 [:until "h1" :exited 90]]
                [[:at-open]
                 [:source "h1"]
                 [:until "h1" :exited 90]])}))
    (fn [_]
      {:on :rcv :kind :parked
       :ops [[:candidate "h2" {:kind :successor :of "h1"}]
             [:until "h2" :running 60]
             [:binding "h2"]
             [:candidate "h3" {:kind :successor :of "h1"}]
             [:candidate "h4" {:kind :fence :of "h1"}]
             [:ticks 10]
             [:snapshot :eligibility]
             [:append-prog "B"]
             [:until "h2" :exited 80]]})]
   :check
   (fn [results inspection]
     (let [l1 (first results)
           l1-binding (binding-of l1 "h1")
           origin (:occurrence l1-binding)
           origin-lease (:lease l1-binding)
           recovery (when (:killed? l1) (nth results 1))
           l3 (peek results)
           p (:projection inspection)
           h1-journal (get-in inspection [:journals "h1"])
           successor-mint (last (filter #(and (= :yin.k/minted (:yin.k/journal %))
                                              (= :yin.k/successor (:yin.k/role %)))
                                        h1-journal))
           successor (:yin.k/occurrence successor-mint)]
       (is (some? origin) "the source was granted and ran")
       (if (nil? cut)
         (do (is (not (:killed? l1)))
             (is (= :exited (:phase (holder l1 "h1"))) "a clean exit on the source host"))
         (do (is (:killed? l1) "the source host was killed at the cut")
             (is (= cut (:cut (cut-fired l1))))))
       (when recovery
         (let [at-open (:projection (snapshot-of recovery :at-open))]
           (testing "a recoverable authority restart advances the epoch before new admission"
             (when-not (= :row7/after-closure cut)
               (is (= :policy (get-in at-open [:leases origin-lease :dao.lease/cause]))
                   "the reopened authority reclaimed the live tenure, cause :policy")
               (is (= 1 (get-in at-open [:occurrences origin :yin.k/epoch]))
                   "and raised the epoch once, before any holder was admitted")
               (is (nil? (get-in at-open [:occurrences origin :dao.lease/lease])))))
           (if (= :row7/after-successor-append cut)
             (let [before (snapshot-of recovery :before-closure)
                   attempted (get-in before [:holders "h5"])]
               (is (= :running (get-in before [:holders "h1" :phase]))
                   "an unaccepted report recovers the last recorded checkpoint")
               (is (= :exited (:phase (holder recovery "h1")))
                   "whose regrant hands off and exits on the receiver host")
               (is (= origin (get-in before [:holders "h1" :occurrence])))
               (is (nil? (get-in before [:projection :occurrences origin :yin.k/closed])))
               (is (not= :running (:phase attempted))
                   "before closure no successor is eligible")
               (is (nil? (:lease attempted))))
             (is (= :exited (:phase (holder recovery "h1")))
                 "the receiver host finished the recovered exit"))))
       (testing "the origin's completion"
         (is (some? (get-in p [:occurrences origin :yin.k/closed]))
             "the closure stands")
         (is (nil? (get-in p [:occurrences origin :dao.lease/lease])))
         (is (= 1 (count (distinct (map #(get-in % [:yin.k/request :yin.k/request-id])
                                        (filter #(= origin (:yin.k/occurrence %))
                                                (records h1-journal :yin.k/intent :yin.k/resumed))))))
             "every report of the origin is the identical request"))
       (testing "the successor, run on the receiver from durable bytes"
         (let [elig (snapshot-of l3 :eligibility)
               h2 (get-in elig [:holders "h2"])]
           (is (some? successor))
           (is (= successor (:occurrence h2)) "the recorded successor runs")
           (is (= :running (:phase h2)))
           (is (= 0 (:epoch h2)) "a successor's first grant binds epoch 0")
           (is (not= :running (get-in elig [:holders "h3" :phase]))
               "the recorded successor is eligible once")
           (is (nil? (get-in elig [:holders "h3" :lease])))
           (is (not= :running (get-in elig [:holders "h4" :phase]))
               "after closure the origin never grants again")
           (is (nil? (get-in elig [:holders "h4" :lease])))
           (is (= 1 (count (filter #(= successor (:yin.k/occurrence %))
                                   (live-leases (:projection elig)))))
               "one live tenure of the successor")
           (is (= :exited (:phase (holder l3 "h2")))
               "the successor read its input on the receiver and exited")
           (is (some? (get-in p [:occurrences successor :yin.k/closed]))
               "and its own closure stands")))))})


;; =============================================================================
;; Shared readers for the rows-world scenarios
;; =============================================================================


(defn- id0
  [o]
  {:yin.k/occurrence o :yin.k/seq 0})


(defn- admit-replies
  "The :yin.k/admit replies of a reply journal, in order."
  [replies]
  (filterv #(= :yin.k/admit (:yin.k/reply %)) replies))


(defn- admissions
  [replies]
  (mapv #(get-in % [:yin.k/answer :yin.k/admission]) (admit-replies replies)))


(defn- reply-to
  "The answer of the reply whose request id is `rid`."
  [replies rid]
  (some #(when (= rid (:yin.k/request-id %)) (:yin.k/answer %)) replies))


(defn- target-values
  [inspection results]
  (get-in inspection [:targets (enrolled-target results)]))


(defn- cut-event
  [result]
  (some #(when (= :cut (:event %)) %) (:events result)))


(defn- cut-inbound
  "The requests of `kind` holder `h` had sent when its life was killed at
   a tick cut."
  [result h kind]
  (filterv #(= kind (:yin.k/request %)) (get-in (cut-event result) [:at :inbound h])))


(defn- rows-recovery
  "The rows-world life that recovers holder h1 on the receiver host."
  [results ops]
  {:on :rcv :kind :rows :target (enrolled-target results) :ops ops})


;; =============================================================================
;; Row 4: kills around the atomic effect commitment and the result append
;; =============================================================================


(def row4-cuts
  [:row4/before-commit :row4/after-commit :row4/before-result-append])


(defn row4
  [cut]
  {:id [:row-4 cut]
   :lives
   [(fn [_]
      {:on :src :kind :rows :cut cut
       :ops [[:source "h1"]
             [:until "h1" :running 60]
             [:binding "h1"]
             [:until-inbound "h1" :yin.k/admit 40]
             [:snapshot :admit-sent]
             [:until "h1" :safepoint 60]]})
    (fn [results]
      (rows-recovery results [[:at-open] [:source "h1"] [:until "h1" :safepoint 90]]))]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           b (binding-of l1 "h1")
           id (id0 (:occurrence b))
           sent (filterv #(= :yin.k/admit (:yin.k/request %))
                         (get-in (snapshot-of l1 :admit-sent) [:inbound "h1"]))
           resent (filterv #(= :yin.k/admit (:yin.k/request %))
                           (get-in (final l2) [:inbound "h1"]))
           replies (admit-replies (get-in inspection [:replies "h1"]))
           answers (mapv :yin.k/answer replies)
           outcomes (:outcomes inspection)
           committed (filterv #(= :committed (:yin.k/admission %)) answers)]
       (is (:killed? l1) "the first tenure was killed at the cut")
       (is (= cut (:cut (cut-event l1))))
       (is (seq sent))
       (is (every? #(= id (get-in % [:yin.k/fenced-envelope :yin.k/op-id])) (concat sent resent))
           "the write carries the one stable id in both tenures")
       (is (every? #(= "A" (get-in % [:yin.k/fenced-envelope :yin.k/value])) (concat sent resent))
           "and the same intent")
       (is (seq resent) "the regranted tenure retried the write")
       (is (= :policy (get-in (snapshot-of l2 :at-open)
                              [:projection :leases (:lease b) :dao.lease/cause]))
           "the reopened authority reclaimed the killed tenure")
       (is (= ["A"] (target-values inspection results)) "exactly one commit on the enrolled target")
       (is (= 1 (count outcomes)) "one committed outcome in the ledger")
       (is (= id (:yin.k/op-id (first outcomes))))
       (is (every? #(= id (:yin.k/op-id %)) answers) "every answer names the stable id")
       (is (every? #{:committed :replayed} (map :yin.k/admission answers)) (pr-str answers))
       (is (<= (count committed) 1))
       (is (= (if (= :row4/before-commit cut) 1 0) (count committed))
           (str "a commit the cut prevented is the regrant's; one the cut kept "
                "is replayed, its reply lost with the killed process"))
       (is (every? #(= (:yin.k/effect-result (first outcomes)) (:yin.k/effect-result %)) answers)
           "every answer redelivers the recorded result")
       (is (pos? (count (:side inspection)))
           "the at-least-once side effect outside the transaction ran (no exactly-once claimed)")))})


;; =============================================================================
;; Row 3: an effect delayed under epoch e, delivered across a reclaim to e+1
;; =============================================================================


(def row3-variants [:uncarried :committed])


(defn row3
  [variant]
  {:id [:row-3 variant]
   :lives
   [(fn [_]
      {:on :src :kind :rows
       :cut (case variant :uncarried :row4/admit-uncarried :committed :clause8/committed)
       :ops [[:source "h1"]
             [:until "h1" :running 60]
             [:binding "h1"]
             [:until "h1" :safepoint 60]]})
    (fn [results]
      (let [b (binding-of (first results) "h1")
            id (id0 (:occurrence b))
            ;; the delayed effect: the killed tenure's own admit request,
            ;; under its lease and epoch, as it stood when it was killed
            ;; (the committed variant's was carried; it is rebuilt from
            ;; the binding the same way)
            delayed (or (first (cut-inbound (first results) "h1" :yin.k/admit))
                        {:yin.k/request :yin.k/admit
                         :yin.k/request-id [:stage-e/delayed (:lease b)]
                         :yin.k/target (enrolled-target results)
                         :yin.k/fenced-envelope {:yin.k/envelope :yin.k/fenced-v1
                                                 :yin.k/incarnation (:lease b)
                                                 :yin.k/epoch (:epoch b)
                                                 :yin.k/op-id id
                                                 :yin.k/value "A"}})
            equal (fn [n]
                    [:admit "h1" {:rid [:stage-e/equal n]
                                  :lease [:binding "h1" :lease]
                                  :epoch [:binding "h1" :epoch]
                                  :op-id {:yin.k/occurrence [:binding "h1" :occurrence]
                                          :yin.k/seq 0}
                                  :value "A"}])]
        (rows-recovery results
                       [[:at-open]
                        [:source "h1"]
                        [:until "h1" :safepoint 90]
                        [:binding "h1"]
                        [:inject "h1" (assoc delayed :yin.k/request-id [:stage-e/delayed (:lease b)])]
                        (equal 1)
                        (equal 2)])))]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           b1 (binding-of l1 "h1")
           b2 (binding-of l2 "h1")
           replies (get-in inspection [:replies "h1"])
           stale (reply-to replies [:stage-e/delayed (:lease b1)])
           e1 (reply-to replies [:stage-e/equal 1])
           e2 (reply-to replies [:stage-e/equal 2])
           outcome (first (:outcomes inspection))]
       (is (:killed? l1))
       (is (= 0 (:epoch b1)) "the delayed effect was issued under epoch 0")
       (is (= 1 (:epoch b2)) "the reclaim raised the epoch; the regrant holds epoch 1")
       (is (not= (:lease b1) (:lease b2)))
       (is (= :stale (:yin.k/admission stale)) (pr-str stale))
       (is (= 1 (:yin.k/observed-epoch stale)) "the stale answer names the current epoch")
       (is (= [:replayed :replayed] [(:yin.k/admission e1) (:yin.k/admission e2)])
           "the current holder's equal id and intent replays, twice")
       (is (= (:yin.k/effect-result outcome) (:yin.k/effect-result e1) (:yin.k/effect-result e2))
           "both answer the one stored result")
       (is (= 1 (count (:outcomes inspection))) "one commit in the ledger")
       (is (= ["A"] (target-values inspection results))
           (str "never both: " (case variant
                                 :uncarried "refusal after the reclaim, the regrant commits"
                                 :committed "commit before the reclaim, the regrant replays")))
       (is (= (case variant :uncarried 1 :committed 0)
              (count (filter #(and (= :committed (get-in % [:yin.k/answer :yin.k/admission]))
                                   (= (:lease b2) (get-in % [:yin.k/answer :yin.k/incarnation])))
                             (admit-replies replies))))
           "the regrant commits only what the killed tenure never committed")))})


;; =============================================================================
;; Row 5: an evicted kept-cursor value after a real kill
;; =============================================================================


(def row5-variants [:with-inputs :without-inputs])


(defn row5
  [variant]
  {:id [:row-5 variant]
   :lives
   [(fn [_]
      {:on :src :kind :rows
       :cut (case variant :with-inputs :row5/input-carried :without-inputs :row5/input-uncarried)
       :ops [[:source "h1"]
             [:until "h1" :running 60]
             [:binding "h1"]
             [:until "h1" :safepoint 60]]})
    (fn [results]
      (assoc (rows-recovery
               results
               (case variant
                 :with-inputs [[:source "h1"]
                               [:until "h1" :safepoint 90]
                               [:binding "h1"]
                               [:admit "h1" {:rid [:stage-e/divergent 0]
                                             :lease [:binding "h1" :lease]
                                             :epoch [:binding "h1" :epoch]
                                             :op-id {:yin.k/occurrence [:binding "h1" :occurrence]
                                                     :yin.k/seq 0}
                                             :value "Z"}]]
                 :without-inputs [[:source "h1"]
                                  [:ticks 40]]))
             ;; the kept position's value is evicted before the recovery
             :evict-floor 1))]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           counts (get-in (final l2) [:counts])
           replies (get-in inspection [:replies "h1"])
           o (:occurrence (binding-of l1 "h1"))]
       (is (:killed? l1))
       (case variant
         :with-inputs
         (let [conflict (reply-to replies [:stage-e/divergent 0])]
           (is (= 0 (:observes counts))
               "the recovery replayed its durable input, observing nothing live")
           (is (= ["A"] (target-values inspection results))
               "replay reproduced the old intent and result, not the eviction")
           (is (= :intent-conflict (:yin.k/admission conflict)) (pr-str conflict))
           (is (= (id0 o) (:yin.k/op-id conflict)))
           (is (not (contains? conflict :yin.k/effect-result)) "a conflict commits nothing")
           (is (true? (get-in inspection [:projection :occurrences o :yin.k/quarantined]))
               "divergence at the same id quarantined the occurrence"))
         :without-inputs
         (do (is (pos? (:observes counts))
                 "no durable input: the recovery re-read live, into the eviction")
             (is (not-any? #{"A"} (target-values inspection results))
                 "nothing is replayed from evidence that was never recorded")
             (is (not-any? #{:intent-conflict :input-conflict} (admissions replies))
                 "no intent stood at the id, so nothing diverged")
             ;; PINNED, pending an Architect ruling (reported): the
             ;; eviction reaches the program as the gap marker and the
             ;; program's write of it commits at the id as a fresh effect
             (is (= [:dao.stream/gap] (target-values inspection results))
                 "observed: the exposed gap marker is the first and only commit")))))})


;; =============================================================================
;; Row 2: export phases, carrier failures, kill and reopen after each
;; =============================================================================


(def row2-pre-fence-cuts
  [:export/after-minted :export/after-serve-intent :export/after-serve-ack
   :export/after-store-intent :export/store-put-before :export/store-put-after
   :export/body-put-before :export/body-put-after :export/after-store-ack
   :export/before-fenced])


(def row2-post-fence-cuts
  [:export/after-fenced :export/after-offer-intent :export/after-offer-attempt
   :export/after-offer-ack])


(defn row2
  [cut]
  {:id [:row-2 cut]
   :lives
   [(fn [_]
      {:on :src :kind :parked :cut cut
       :ops [[:source "h1"] [:until "h1" :running 60]]})
    (fn [_]
      {:on :rcv :kind :parked
       :ops [[:at-open]
             [:source "h1"]
             [:snapshot :recovered]
             [:abort "h1"]
             [:snapshot :after-abort]
             [:until "h1" :running 60]
             [:ticks 4]]})]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           journal (get-in inspection [:journals "h1"])
           minted (records journal :yin.k/minted nil)
           o (:yin.k/occurrence (first minted))
           aborted (get-in (snapshot-of l2 :after-abort) [:holders "h1"])
           st (holder l2 "h1")
           p (:projection inspection)]
       (is (:killed? l1) "the export driver was killed at the phase")
       (is (<= (count minted) 1) "one occurrence: never re-minted across the kill")
       (if (some #{cut} row2-pre-fence-cuts)
         (do (is (not= :running (:phase st))
                 (str "an incomplete preparation never runs: " (pr-str st)))
             (is (false? (:machine? st)))
             (is (empty? (records journal :yin.k/fenced nil)) "nothing fenced over incomplete objects")
             (is (empty? (records journal :yin.k/intent :yin.k/offer)) "nothing offered")
             (is (or (nil? o) (nil? (get-in p [:occurrences o])))
                 "the authority never heard of the occurrence")
             (is (zero? (get-in (final l2) [:counts :attaches])) "no program IO through the kill"))
         (let [fence (first (records journal :yin.k/fenced nil))
               offers (filterv #(= :yin.k/offer (:yin.k/request %))
                               (get-in (final l2) [:inbound "h1"]))]
           (is (= :yin.k/refused (:status aborted)))
           (is (= {:yin.k/reason :restarted} (:detail aborted))
               "a restarted export never aborts: its offer may have landed")
           (is (= :running (:phase st)) "the fenced source resent its offer, was granted, and runs")
           (is (= 1 (count (get-in p [:occurrences o :yin.k/variants])))
               "duplicate offer evidence admits one variant of one O")
           (is (= 1 (count (filter #(= o (:yin.k/occurrence %)) (vals (:leases p)))))
               "duplicate evidence creates no second grant")
           (is (= 1 (get-in (snapshot-of l2 :recovered) [:counts :attaches]))
               "reopening the fenced source attached once, administratively (D14 rehydration)")
           (is (= 0 (get-in (snapshot-of l2 :recovered) [:counts :observes]))
               "and observed nothing: no program IO before its grant")
           (is (= 2 (get-in (final l2) [:counts :attaches])) "then one activation, under its own grant")
           (when (seq offers)
             (is (every? #(= (get-in (final l2) [:fences (:yin.k/address fence)]) (:yin.k/bytes %))
                         offers)
                 "the rehydrated export resends the exact stored handoff bytes"))))))})


(def row2-carriers
  [:dao.stream/full :dao.stream/refused :dao.stream/transport-error :dao.stream/ok])


(defn row2-carrier
  "A carrier answering `outcome` to every offer append: abort is legal
   only while no offer can have landed; then the export driver is killed
   and reopened on the receiver host, where no restarted export aborts."
  [outcome]
  {:id [:row-2-carrier outcome]
   :lives
   [(fn [_]
      {:on :src :kind :parked :inbound {"h1" :answering}
       :ops [[:carrier outcome]
             [:source "h1"]
             [:until-journal "h1" :yin.k/attempt :yin.k/offer 30 :step]
             [:abort "h1"]
             [:snapshot :after-abort]
             [:die :after-abort]]})
    (fn [_]
      {:on :rcv :kind :parked
       :ops (if (contains? #{:dao.stream/full :dao.stream/refused} outcome)
              [[:source "h1"] [:ticks 10]]
              [[:source "h1"]
               [:abort "h1"]
               [:snapshot :restarted-abort]
               [:ticks 30]])})]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           legal? (contains? #{:dao.stream/full :dao.stream/refused} outcome)
           st (get-in (snapshot-of l1 :after-abort) [:holders "h1"])
           journal (get-in inspection [:journals "h1"])
           o (:yin.k/occurrence (first (records journal :yin.k/minted nil)))
           attempts (records journal :yin.k/attempt :yin.k/offer)
           p (:projection inspection)]
       (is (:killed? l1))
       (is (some #(= outcome (:yin.k/append %)) attempts) "the attempt journaled the carrier's answer")
       (if legal?
         (do (is (= :aborted (:phase st)) (pr-str st))
             (is (= {:yin.k/aborted o} (:detail st)))
             (is (seq (records journal :yin.k/aborted nil)) "the abort is durable")
             (is (= :aborted (:phase (holder l2 "h1"))) "an aborted export stays aborted across the kill")
             (is (nil? (get-in p [:occurrences o])) "the authority never heard of it"))
         (do (is (= :yin.k/refused (:status st)))
             (is (= {:yin.k/reason :offer-possibly-accepted} (:detail st)))
             (is (empty? (records journal :yin.k/aborted nil)))
             (is (= {:yin.k/reason :restarted}
                    (get-in (snapshot-of l2 :restarted-abort) [:holders "h1" :detail])))
             (is (= :running (:phase (holder l2 "h1"))) "the reopened export completes under its grant")
             (is (= 1 (count (get-in p [:occurrences o :yin.k/variants]))))
             (is (= 1 (count (filter #(= o (:yin.k/occurrence %)) (vals (:leases p))))))))))})


;; =============================================================================
;; Row 1: one admitted holder over two encodings of O, two candidates
;; =============================================================================


(def row1
  {:id [:row-1]
   :holders ["h1" "h2" "h3"]
   :lives
   [(fn [_]
      {:on :src :kind :parked :cut :export/after-offer-ack
       :ops [[:source "h1"] [:until "h1" :running 60]]})
    (fn [_]
      ;; the second encoding is minted on the receiver host
      {:on :rcv :kind :parked
       :ops [[:source "h1"]
             [:offer-variant "h1"]
             [:candidate "h2" {:kind :fence :of "h1"}]
             [:candidate "h3" {:kind :variant}]
             [:until-some :running 90]
             [:ticks 10]
             [:snapshot :race]]})
    (fn [_]
      {:on :src :kind :parked :prog ["B"]
       :ops [[:source "h1"]
             [:until "h1" :running 90]
             [:snapshot :source-granted]
             [:until "h1" :safepoint 30]]})]
   :check
   (fn [results _inspection]
     (let [[l1 l2 l3] results
           race (snapshot-of l2 :race)
           hs (:holders race)
           running (filterv #(= :running (:phase (val %))) hs)
           o (:occurrence (val (first running)))
           p (:projection race)]
       (is (:killed? l1) "the source was killed after its offer, before its own grant")
       (is (= 1 (count running)) (str "one admitted holder: " (pr-str hs)))
       (is (every? (fn [[_ st]]
                     (or (= :running (:phase st))
                         (and (nil? (:lease st))
                              (contains? #{:yin.k/awaiting-grant :yin.k/not-holder}
                                         (:status st)))))
                   hs)
           "every competitor awaits or is refused")
       (is (= 2 (count (get-in p [:occurrences o :yin.k/variants])))
           "both encodings are variants of the one occurrence")
       (is (= 1 (count (filter #(= o (:yin.k/occurrence %)) (live-leases p)))))
       (is (= 2 (get-in race [:counts :attaches]))
           (str "one administrative attach reopening the fenced source and one activation: "
                "no competitor attached, the source emitted nothing of its own"))
       (is (= :running (get-in (snapshot-of l3 :source-granted) [:holders "h1" :phase]))
           "the source resumes only under its own grant")
       (is (= o (get-in (snapshot-of l3 :source-granted) [:holders "h1" :occurrence])))
       (is (= :safepoint (:phase (holder l3 "h1"))) "and runs its program under it")))})


;; =============================================================================
;; Row 6: partition -- the holder's requests, the protected consumer's
;; authority, and a lost reply -- with time advanced only by ticks
;; =============================================================================


(def row6-request-partition
  {:id [:row-6 :request-partition]
   :lives
   [(fn [_]
      {:on :src :kind :parked :inbound {"h1" :held}
       :ops [[:source "h1"]
             [:until "h1" :running 60]
             [:binding "h1"]
             ;; the partition: the holder's requests stop reaching the front
             [:hold true]
             [:clock 25]
             [:ticks 4 :control]
             [:snapshot :renewal-held]
             ;; time passes the grant's duration only by appended ticks
             [:clock 45]
             [:ticks 4 :control]
             [:snapshot :lapsed]
             ;; healed: every held request reaches the front at once
             [:hold false]
             [:ticks 12]
             [:snapshot :healed]
             [:die :after-heal]]})
    (fn [_]
      {:on :rcv :kind :parked :clock 50
       :ops [[:at-open] [:source "h1"] [:until "h1" :running 90] [:binding "h1"]]})]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           b1 (binding-of l1 "h1")
           held (snapshot-of l1 :renewal-held)
           lapsed (snapshot-of l1 :lapsed)
           healed (snapshot-of l1 :healed)
           renewals (records (get-in held [:journals "h1"]) :yin.k/intent :yin.k/renewal)
           p (:projection inspection)]
       (is (seq renewals) "the renewal was due and journaled")
       (is (some #(= :dao.stream/ok (:yin.k/append %))
                 (records (get-in held [:journals "h1"]) :yin.k/attempt :yin.k/renewal))
           "and sent into the partition (the carrier took it; the front cannot read it)")
       (is (nil? (get-in held [:projection :leases (:lease b1) :dao.lease/cause]))
           "inside its duration the tenure stands: nothing is inferred from silence")
       (is (some? (get-in lapsed [:projection :leases (:lease b1) :dao.lease/cause]))
           "past the duration, at an appended tick, the judge lapsed it")
       (is (not= :running (get-in lapsed [:holders "h1" :phase]))
           "the partitioned holder stopped running")
       (is (= {:yin.k/status :yin.k/ended :cause :lease-bound}
              {:yin.k/status (get-in lapsed [:holders "h1" :status])
               :cause (get-in lapsed [:holders "h1" :detail :cause])})
           "it ended on its own lease bound: no inference from an absent lapse")
       (let [renewals (filterv #(= :yin.k/renewal (:yin.k/request %)) (get-in healed [:inbound "h1"]))]
         (is (seq renewals) "healed, the held renewals reached the front")
         (is (apply = (map :yin.k/request-id renewals)) "every retry is the identical request"))
       (is (true? (get-in healed [:holders "h1" :detail :dao.lease/released]))
           "the ended run's release was carried once the partition healed")
       (is (<= (count (live-leases (:projection healed))) 1) "never two live tenures")
       (is (:killed? l1))
       (let [b2 (binding-of l2 "h1")]
         (is (= :running (:phase (holder l2 "h1"))))
         (is (not= (:lease b1) (:lease b2)) "the regrant is a fresh tenure")
         (is (= 1 (count (live-leases p))))
         (is (every? (fn [[_ n]] (= 1 n)) (frequencies (keys (:answered p))))
             "each proposal id is answered exactly once"))))})


(def row6-consumer-partition
  {:id [:row-6 :consumer-partition]
   :lives
   [(fn [_]
      {:on :src :kind :rows
       :ops [[:source "h1"]
             [:until "h1" :running 60]
             [:binding "h1"]
             [:until-inbound "h1" :yin.k/admit 40]
             [:snapshot :before-partition]
             [:partition-authority true]
             [:until-reply "h1" :yin.k/admit 40 :control]
             [:snapshot :suspended]
             [:die :partitioned]]})
    (fn [results]
      (rows-recovery results [[:at-open] [:source "h1"] [:until "h1" :safepoint 90]]))]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           b (binding-of l1 "h1")
           snap (snapshot-of l1 :suspended)
           first-reply (:yin.k/answer (first (admit-replies (get-in snap [:replies "h1"]))))]
       (is (= {:yin.k/admission :suspended
               :yin.k/op-id (id0 (:occurrence b))
               :yin.k/incarnation (:lease b)
               :yin.k/arbitration {:dao.stream/identity
                                   (get-in (snapshot-of l1 :before-partition)
                                           [:arbitration :dao.stream/identity])}}
              first-reply)
           "the exact 7.9 suspended map: no result, no epoch claim")
       (is (= :suspended (:yin.k/admission first-reply))
           "protected admission suspends without its authority")
       (is (not (contains? first-reply :yin.k/effect-result)) "a suspension commits nothing")
       (is (= [] (get-in snap [:targets :values])) "nothing reached the target while partitioned")
       (is (:killed? l1))
       (is (= :policy (get-in (snapshot-of l2 :at-open)
                              [:projection :leases (:lease b) :dao.lease/cause]))
           "the recoverable restart reclaimed the tenure before new admission")
       (is (= ["A"] (target-values inspection results)) "the regrant committed once")
       (is (= 1 (count (:outcomes inspection))))))})


(def row6-lost-reply
  {:id [:row-6 :lost-reply]
   :lives
   [(fn [_]
      {:on :src :kind :parked :cut :row6/proposal-reply-lost
       :ops [[:source "h1"] [:until "h1" :running 60]]})
    (fn [_]
      {:on :rcv :kind :parked
       :ops [[:at-open] [:source "h1"] [:until "h1" :running 90] [:binding "h1"]]})]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           journal (get-in inspection [:journals "h1"])
           intents (records journal :yin.k/intent :yin.k/proposal)
           p (:projection inspection)]
       (is (:killed? l1) "the process died before the front appended the proposal's reply")
       (is (= :running (:phase (holder l2 "h1"))))
       (is (every? (fn [[_ n]] (= 1 n)) (frequencies (keys (:answered p))))
           "the judge answered each proposal id exactly once")
       (is (= 1 (count (live-leases p))) "one live tenure")
       (is (= (count (distinct (map #(get-in % [:yin.k/request :yin.k/request-id]) intents)))
              (count (distinct (map #(get-in % [:yin.k/request :dao.lease/proposal]) intents))))
           "every resend of a proposal is its identical request")))})


;; =============================================================================
;; Clause 8: the admission order through the composition after a real kill
;; =============================================================================


(defn- admit-op
  [h rid lease epoch seq value & {:keys [occurrence target await?]}]
  [:admit h (cond-> {:rid rid :lease lease :epoch epoch
                     :op-id {:yin.k/occurrence (or occurrence [:binding "h1" :occurrence])
                             :yin.k/seq seq}
                     :value value}
              target (assoc :target target)
              (some? await?) (assoc :await? await?))])


(def clause8-variants [:order :cross-target])


(defn clause8
  [variant]
  {:id [:clause-8 variant]
   :holders ["h1" "h7"]
   :lives
   [(fn [_]
      {:on :src :kind :rows :cut :clause8/committed
       :ops [[:source "h1"] [:until "h1" :running 60] [:binding "h1"] [:until "h1" :safepoint 60]]})
    (fn [results]
      (let [old (binding-of (first results) "h1")
            L [:binding "h1" :lease]
            E [:binding "h1" :epoch]]
        (rows-recovery
          results
          (into [[:at-open]
                 [:source "h1"]
                 [:until "h1" :safepoint 90]
                 [:binding "h1"]
                 [:candidate "h7" {:kind :fence :of "h1"}]]
                (case variant
                  :order
                  [(admit-op "h7" [:e8 :wrong-author] L E 0 "A" :await? false)
                   (admit-op "h1" [:e8 :stale-epoch] L 0 0 "A")
                   (admit-op "h1" [:e8 :stale-lease] (:lease old) (:epoch old) 0 "A")
                   (admit-op "h1" [:e8 :foreign] L E 0 "A" :occurrence "00000000-0000-4000-8000-000000000000"
                             :await? false)
                   (admit-op "h1" [:e8 :equal] L E 0 "A")
                   (admit-op "h1" [:e8 :fresh] L E 1 "B")
                   (admit-op "h1" [:e8 :different] L E 0 "Z")
                   (admit-op "h1" [:e8 :after-quarantine] L E 2 "C")
                   [:partition-authority true]
                   (admit-op "h1" [:e8 :unreadable-authority] L E 3 "D")
                   [:snapshot :order]]
                  :cross-target
                  [[:enroll-other]
                   (admit-op "h1" [:e8 :cross-target] L E 0 "A" :target :other)
                   [:snapshot :order]])))))]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           old (binding-of l1 "h1")
           now (binding-of l2 "h1")
           o (:occurrence now)
           snap (snapshot-of l2 :order)
           replies (get-in snap [:replies "h1"])
           r (fn [k] (reply-to replies [:e8 k]))
           diag (fn [defect] (filterv #(= defect (:yin.k/defect %)) (:diagnostics snap)))
           outcome (fn [admission id & kvs]
                     (merge {:yin.k/admission admission :yin.k/op-id id
                             :yin.k/incarnation (:lease now)}
                            (apply hash-map kvs)))]
       (is (:killed? l1) "the first tenure was killed after its commit")
       (is (= 1 (:epoch now)) "the regrant runs at the raised epoch")
       (case variant
         :order
         (do
           (is (empty? (get-in snap [:replies "h7"])) "a wrong author is never replied")
           (is (= 1 (count (diag :wrong-author))) "it is diagnosed, committing nothing")
           (is (= (outcome :stale (id0 o) :yin.k/observed-epoch 1 :yin.k/observed-lease (:lease now))
                  (r :stale-epoch))
               "stale epoch")
           (is (= {:yin.k/admission :stale :yin.k/op-id (id0 o) :yin.k/incarnation (:lease old)
                   :yin.k/observed-epoch 1 :yin.k/observed-lease (:lease now)}
                  (r :stale-lease))
               "stale lease")
           (is (nil? (r :foreign)) "a foreign id is never replied")
           (is (= 1 (count (diag :foreign-op-id))))
           (is (= :replayed (:yin.k/admission (r :equal))) "equal intent replays")
           (is (= (:yin.k/effect-result (first (:outcomes inspection)))
                  (:yin.k/effect-result (r :equal)))
               "with the stored result")
           (is (= (outcome :committed {:yin.k/occurrence o :yin.k/seq 1}
                           :yin.k/effect-result {:dao.stream/outcome :dao.stream/ok})
                  (r :fresh))
               "a fresh id commits: the exact map")
           (is (= :intent-conflict (:yin.k/admission (r :different))) (pr-str (r :different)))
           (is (= (id0 o) (:yin.k/op-id (r :different))))
           (is (not (contains? (r :different) :yin.k/effect-result)))
           (is (true? (get-in snap [:projection :occurrences o :yin.k/quarantined]))
               "the conflict quarantined the occurrence")
           (is (= :suspended (:yin.k/admission (r :after-quarantine))) "the quarantine suspends")
           (is (= :suspended (:yin.k/admission (r :unreadable-authority)))
               "an unreadable authority suspends")
           (is (= ["A" "B"] (target-values inspection results))
               "only the first commit and the fresh id ever reached the target"))
         :cross-target
         (let [answer (r :cross-target)
               other (:other (:report l2))]
           (is (= :intent-conflict (:yin.k/admission answer)) (pr-str answer))
           (is (= (id0 o) (:yin.k/op-id answer)))
           (is (true? (get-in snap [:projection :occurrences o :yin.k/quarantined]))
               "the shared id namespace conflicts across targets and quarantines")
           (is (= ["A"] (target-values inspection results)))
           (is (= [] (get-in inspection [:targets other] [])) "the other target received nothing")))))})


(def clause8-closed
  "A closed occurrence's stale answer and a closed ancestor's id at its
   successor, after a real kill of the exit."
  {:id [:clause-8 :closed]
   :holders ["h1" "h2"]
   :lives
   [(fn [_]
      {:on :src :kind :parked :cut :row7/after-closure
       :ops [[:source "h1"] [:until "h1" :running 60] [:binding "h1"]
             [:hand-off "h1"] [:until "h1" :exited 80]]})
    (fn [results]
      (let [old (binding-of (first results) "h1")]
        {:on :rcv :kind :parked
         :ops [[:source "h1"]
               [:until "h1" :exited 60]
               [:candidate "h2" {:kind :successor :of "h1"}]
               [:until "h2" :running 60]
               [:binding "h2"]
               [:enroll-other]
               (admit-op "h1" [:e8 :closed] (:lease old) (:epoch old) 0 "A"
                         :occurrence (:occurrence old) :target :other)
               (admit-op "h2" [:e8 :ancestor] [:binding "h2" :lease] [:binding "h2" :epoch] 0 "A"
                         :occurrence (:occurrence old) :target :other :await? false)
               [:snapshot :closed]]}))]
   :check
   (fn [results _inspection]
     (let [[l1 l2] results
           old (binding-of l1 "h1")
           snap (snapshot-of l2 :closed)
           closed (reply-to (get-in snap [:replies "h1"]) [:e8 :closed])]
       (is (:killed? l1))
       (is (= :stale (:yin.k/admission closed)) (pr-str closed))
       (is (true? (:yin.k/closed closed)) "the stale answer names the closure")
       (is (nil? (reply-to (get-in snap [:replies "h2"]) [:e8 :ancestor]))
           "an inherited id from a closed ancestor is never replied")
       (is (some #(and (= :foreign-op-id (:yin.k/defect %))
                       (= "h2" (:yin.k/author %))
                       (= (id0 (:occurrence old)) (get-in % [:yin.k/claimed :yin.k/op-id])))
                 (:diagnostics snap))
           "it is diagnosed foreign at the successor")))})


;; =============================================================================
;; The inbox retention machine and the journal brackets (D15a, D14)
;; =============================================================================


(def journal-cuts
  [:inbox/before-retention :inbox/after-retention
   :journal/after-proposal-intent :journal/before-proposal-attempt
   :journal/after-grant-ack :journal/after-resumed-intent :journal/after-release-intent])


(def uncertain-retentions
  [{:like :inbox/before-retention :persist? false}
   {:like :inbox/before-retention :persist? true}])


(defn- inbox-key
  [r]
  [(:yin.k/lane r) (:yin.k/identity r) (:yin.k/position r)])


(defn journal-row
  "A kill at one journal boundary (`cut`), or an uncertain append that
   stalls the live driver before the kill (`fail`)."
  [{:keys [cut fail]}]
  (let [exit-cut? (= :journal/after-release-intent cut)
        report-cut? (= :journal/after-resumed-intent cut)]
    {:id [:journal (or cut fail)]
     :lives
     [(fn [_]
        (if fail
          {:on :src :kind :parked :fail fail
           :ops [[:source "h1"] [:until "h1" :running 40] [:snapshot :stalled] [:die :stalled]]}
          {:on :src :kind :parked :cut cut
           :ops [[:source "h1"] [:until "h1" :running 60] [:binding "h1"]
                 [:hand-off "h1"] [:until "h1" :exited 80]]}))
      (fn [_]
        {:on :rcv :kind :parked
         :ops (if exit-cut?
                [[:at-open] [:source "h1"] [:until "h1" :exited 90]]
                [[:at-open] [:source "h1"] [:until "h1" :running 90] [:binding "h1"]])})]
     :check
     (fn [results inspection]
       (let [[l1 l2] results
             journal (get-in inspection [:journals "h1"])
             inbox (records journal :yin.k/inbox nil)
             at (:at (cut-event l1))
             p (:projection inspection)]
         (is (:killed? l1))
         (is (every? (fn [[_ n]] (= 1 n)) (frequencies (map inbox-key inbox)))
             "every source position is retained once: one queue entry, never two")
         (is (<= (count (records journal :yin.k/minted nil)) 2) "no occurrence re-minted")
         (cond
           fail
           (let [stalled (get-in (snapshot-of l1 :stalled) [:holders "h1"])
                 uncertain (some #(when (= :uncertain (:event %)) %) (:events l1))]
             (is (some? uncertain) "the retention append was answered uncertain")
             (is (= :stalled (:phase stalled)) (str "an uncertain append stalls: " (pr-str stalled)))
             (is (false? (:machine? stalled)) "nothing dispatched past it")
             (is (= 1 (count (filter #(= (inbox-key (:record uncertain)) (inbox-key %)) inbox)))
                 "reopen reconciled the position: retained once whether it landed or not")
             (is (= :running (:phase (holder l2 "h1"))) "and the recovered holder ran"))

           (= :inbox/before-retention cut)
           (let [lost (:record at)
                 again (some #(when (= (inbox-key lost) (inbox-key %)) %) inbox)]
             (is (some? again) "the fresh driver re-read the source position")
             (is (= lost again)
                 "the same attributed record at the same position: lane, identity, author, record"))

           (= :inbox/after-retention cut)
           (is (= 1 (count (filter #(= (inbox-key (:record at)) (inbox-key %)) inbox)))
               "retained before the kill, the record is one queue entry after it")

           (= :journal/after-grant-ack cut)
           (let [b1 (binding-of l1 "h1")
                 b2 (binding-of l2 "h1")]
             (is (not= (:lease b1) (:lease b2))
                 "a restarted holder with a journaled grant never resumes it: a fresh tenure")
             (is (= :policy (get-in p [:leases (or (:lease b1)
                                                   (get-in at [:record :dao.lease/lease]))
                                       :dao.lease/cause]))
                 "the journaled grant was reclaimed"))

           report-cut?
           (let [o (:occurrence (binding-of l1 "h1"))]
             (is (empty? (records (get-in (snapshot-of l2 :at-open) [:journals "h1"])
                                  :yin.k/attempt :yin.k/resumed))
                 "the report's intent stood, its attempt never happened")
             (is (= :running (:phase (holder l2 "h1")))
                 "no report was accepted: the last recorded checkpoint is regranted")
             (is (= o (:occurrence (holder l2 "h1"))))
             (is (nil? (get-in p [:occurrences o :yin.k/closed])) "nothing closed"))

           exit-cut?
           (let [intents (records journal :yin.k/intent :yin.k/release)]
             (is (= :exited (:phase (holder l2 "h1"))) "the recovered exit completes")
             (is (= 1 (count (distinct (map #(get-in % [:yin.k/request :yin.k/request-id]) intents))))
                 "every resend after the intent is the identical request"))

           :else
           (do (is (= :running (:phase (holder l2 "h1"))))
               (is (every? (fn [[_ n]] (= 1 n)) (frequencies (keys (:answered p))))
                   "each proposal id answered once")
               (is (= 1 (count (live-leases p))) "one live tenure")))))}))


;; =============================================================================
;; Clause 3: the sequence counter across a kill between assign and
;; discharge, and an id carried by the checkpoint
;; =============================================================================


(def clause3-regrant
  {:id [:clause-3 :regrant]
   :lives
   [(fn [_]
      {:on :src :kind :rows :cut :seq/after-assign
       :ops [[:source "h1"] [:until "h1" :running 60] [:binding "h1"] [:until "h1" :safepoint 60]]})
    (fn [results]
      (rows-recovery results [[:source "h1"]
                              [:until-inbound "h1" :yin.k/admit 90]
                              [:snapshot :reassigned]
                              [:until "h1" :safepoint 60]]))]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           at (:at (cut-event l1))
           o (:occurrence (binding-of l1 "h1"))
           first-admit (first (filter #(= :yin.k/admit (:yin.k/request %)) (get-in at [:inbound "h1"])))
           re (snapshot-of l2 :reassigned)
           second-admit (first (filter #(= :yin.k/admit (:yin.k/request %)) (get-in re [:inbound "h1"])))]
       (is (:killed? l1) "killed between the id's assignment and its discharge")
       (is (= 1 (get-in at [:holders "h1" :next-op-seq])) "assign-before-append: one increment")
       (is (= (id0 o) (get-in first-admit [:yin.k/fenced-envelope :yin.k/op-id])))
       (is (= 1 (get-in re [:holders "h1" :next-op-seq]))
           "the regrant restored the counter exactly: the same one assignment")
       (is (= (get-in first-admit [:yin.k/fenced-envelope :yin.k/op-id])
              (get-in second-admit [:yin.k/fenced-envelope :yin.k/op-id]))
           "the same id")
       (is (= "A" (get-in second-admit [:yin.k/fenced-envelope :yin.k/value])) "the same intent")
       (is (= ["A"] (target-values inspection results)) "committed once")))})


(def clause3-carried
  "An id assigned before the export rides the successor's bytes across a
   kill of the exit and is retried, fenced, by the successor's holder."
  {:id [:clause-3 :carried-put]
   :holders ["h1" "h2"]
   :lives
   [(fn [_]
      {:on :src :kind :rows :cut :row7/after-closure
       :ops [[:source "h1"] [:until "h1" :running 60] [:binding "h1"]
             [:until-inbound "h1" :yin.k/admit 40]
             [:hand-off "h1"] [:until "h1" :exited 80]]})
    (fn [results]
      (rows-recovery results [[:source "h1"]
                              [:until "h1" :exited 60]
                              [:candidate "h2" {:kind :successor :of "h1"}]
                              [:until "h2" :running 60]
                              [:binding "h2"]
                              [:snapshot :successor-running]
                              [:until "h2" :safepoint 60]]))]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           o (:occurrence (binding-of l1 "h1"))
           run (snapshot-of l2 :successor-running)
           admits (filter #(= :yin.k/admit (:yin.k/request %)) (get-in (final l2) [:inbound "h2"]))]
       (is (:killed? l1))
       (is (= 1 (get-in run [:holders "h2" :next-op-seq]))
           "the successor's counter is the carried one")
       (is (every? #(= (id0 o) (get-in % [:yin.k/fenced-envelope :yin.k/op-id])) admits)
           "the successor retries the origin's carried id unchanged")
       (is (every? #{:committed :replayed} (admissions (get-in inspection [:replies "h2"]))))
       (is (= ["A"] (target-values inspection results)) "the carried write commits once across the chain")))})


;; =============================================================================
;; Fork, per host
;; =============================================================================


(def fork-row
  {:id [:fork]
   :lives [(fn [_] {:on :src :fork-row true})]
   :check
   (fn [results _inspection]
     (let [r (:report (first results))]
       (is (= :yin.k/unsatisfied (get-in r [:refused :yin.k/status])))
       (is (= :exclusive-uncapable (get-in r [:refused :yin.k/reason]))
           "exclusive over a memory authority is refused")
       (is (= :yin.k/exclusive (get-in r [:refused :yin.k/policy])) "never downgraded to fork")
       (is (false? (:refused-composed? r)) "nothing was composed")
       (is (true? (get-in r [:fork :held?])))
       (is (= :yin.k/fork (get-in r [:fork :policy])) "fork runs when selected, labelled fork")
       (is (= :yin.k/fork (get-in r [:fork :status-policy])))
       (is (nil? (get-in r [:fork :arbitration])))
       (is (= :lifted (get-in r [:fork :holder :phase])))
       (is (some? (get-in r [:fork :address])))
       (is (= [] (get-in r [:fork :journal])) "no custody bracket")
       (is (= [] (get-in r [:fork :offers])) "nothing offered")
       (is (= 0 (get-in r [:fork :fronts])))
       (is (false? (get-in r [:fork :outcome-reader?])))))})


;; =============================================================================
;; Row 7, continued: a failure-lower successor and a lost authority
;; =============================================================================


(def row7-failure-lower
  "The successor's first candidate cannot attach its stream: its lower
   fails after the grant, it releases, and the successor is offered to
   the next candidate instead of completing."
  {:id [:row-7 :failure-lower]
   :holders ["h1" "h2" "h3"]
   :lives
   [(fn [_]
      {:on :src :kind :parked :cut :row7/after-closure
       :ops [[:source "h1"] [:until "h1" :running 60] [:binding "h1"]
             [:hand-off "h1"] [:until "h1" :exited 80]]})
    (fn [_]
      {:on :rcv :kind :parked
       :ops [[:source "h1"]
             [:until "h1" :exited 60]
             [:candidate "h2" {:kind :successor :of "h1"} {:attach :none}]
             [:until "h2" :failed 60]
             [:ticks 4]
             [:snapshot :failed-lower]
             [:candidate "h3" {:kind :successor :of "h1"}]
             [:until "h3" :running 60]
             [:binding "h3"]]})]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           o (:occurrence (binding-of l1 "h1"))
           failed (snapshot-of l2 :failed-lower)
           h2 (get-in failed [:holders "h2"])
           s (:occurrence h2)
           b3 (binding-of l2 "h3")]
       (is (:killed? l1))
       (is (= :failed (:phase h2)) (pr-str h2))
       (is (= :yin.k/unsatisfied (:status h2)))
       (is (= {:dao.stream/identity world/prog-id :dao.lease/released true} (:detail h2))
           "the failed lower names the stream, after its release")
       (is (false? (:machine? h2)) "no machine was exposed")
       (is (nil? (get-in failed [:projection :occurrences s :yin.k/closed]))
           "a failure-lower release never completes the successor")
       (is (nil? (get-in failed [:projection :occurrences s :dao.lease/lease])))
       (is (some? (get-in inspection [:projection :occurrences o :yin.k/closed]))
           "the origin's closure stands")
       (is (= s (:occurrence b3)) "the successor is reoffered to the next candidate")
       (is (= 1 (:epoch b3)) "at the epoch the release advanced")))})


(def row7-lost-authority
  "The authority's state is permanently lost between lives: the next
   open refuses, nothing is composed and nothing runs -- fail stop,
   never a downgrade to fork."
  {:id [:row-7 :lost-authority]
   :lives
   [(fn [_]
      {:on :src :kind :parked :cut :row7/while-running
       :ops [[:source "h1"] [:until "h1" :running 60]]})
    (fn [_]
      {:on :rcv :kind :parked :lose-authority true
       :ops [[:source "h1"] [:until "h1" :running 30]]})]
   :check
   (fn [results inspection]
     (let [[l1 l2] results
           refusal (get-in l2 [:report :refusal])]
       (is (:killed? l1))
       (is (or (and (= :yin.k/unsatisfied (:yin.k/status refusal))
                    (= :authority-refused (:yin.k/reason refusal)))
               (and (= :authority-backend (:stage refusal))
                    (= :dao.stream/transport-error
                       (get-in refusal [:answer :dao.stream/outcome]))))
           (str "the lost authority is refused as data, by the composition or by "
                "its durable backend before it: " (pr-str refusal)))
       (is (nil? (get-in l2 [:report :final])) "nothing was composed, so nothing ran")
       (is (not= :open (:status inspection)) "the lost ledger opens for no one")))})


(def all-scenarios
  "Every scenario, in run order."
  (concat [fork-row]
          (map row7 row7-cuts)
          [row7-failure-lower row7-lost-authority]
          (map row4 row4-cuts)
          (map row3 row3-variants)
          (map row5 row5-variants)
          (map row2 (concat row2-pre-fence-cuts row2-post-fence-cuts))
          (map row2-carrier row2-carriers)
          [row1 row6-request-partition row6-consumer-partition row6-lost-reply]
          (map clause8 clause8-variants)
          [clause8-closed clause3-regrant clause3-carried]
          (map #(journal-row {:cut %}) journal-cuts)
          (map #(journal-row {:fail %}) uncertain-retentions)))
