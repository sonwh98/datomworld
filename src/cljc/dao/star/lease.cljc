(ns dao.star.lease
  "The dao.lease compositions of dao.star Mode 1 (docs/design/dao.star.md,
   Liveness): the stream-reading helper, the holder agent a driver threads per
   lease, and the two grantor-side wrappers (exchange lease, lineage lease)
   that author grants and drive the judge.

   Nothing here reads a clock.  Time is the `:dao.lease/reading` data drained
   from a tick stream the composition feeds, so every lapse is driven by
   appending a tick."
  (:require [clojure.string :as str]
            [dao.lease :as lease]
            [dao.star.facts :as facts]
            [dao.stream :as stream]))


(defn- ok?
  [r]
  (= :dao.stream/ok (:dao.stream/outcome r)))


(defn oldest-cursor
  [handle]
  (let [r (stream/cursor handle stream/anchor-oldest)]
    (when-not (ok? r)
      (throw (ex-info "cannot mint a cursor" {:outcome r})))
    (:dao.stream/cursor r)))


(defn drain
  "Read `handle` from `cursor` to `blocked`, never waiting.  Answers
   `{:cursor c :values [v...] :gap? bool :end? bool}`: a `gap` is noted and
   reading continues from its recovery cursor, as the contract has it."
  [handle cursor]
  (loop [c cursor values [] gap? false]
    (let [r (stream/next handle c)]
      (case (:dao.stream/outcome r)
        :dao.stream/ok (recur (:dao.stream/cursor r) (conj values (:dao.stream/value r)) gap?)
        :dao.stream/gap (recur (:dao.stream/cursor r) values true)
        :dao.stream/end {:cursor c :values values :gap? gap? :end? true}
        {:cursor c :values values :gap? gap? :end? false}))))


;; -----------------------------------------------------------------------------
;; Holder agent: one lease, held by a driver's control flow
;; -----------------------------------------------------------------------------

(defn make-agent
  "A holder over `lease/make-holder` plus the cursors its control flow drains.
   `config` is `make-holder`'s, less the cursors (minted here at the oldest
   anchor, so a grant already on the medium is observed).  `:envelope` is merged
   into every fact the agent appends (the lineage lease stamps
   `:dao.star/lineage` here, which is what the judge's resolver attributes by).
   Answers an atom holding the agent state."
  [{:keys [tick fact writer envelope] :as config}]
  (let [wired (lease/make-holder
                (assoc config
                       :tick {:handle tick :cursor (oldest-cursor tick)}
                       :fact {:handle (:handle fact)
                              :cursor (oldest-cursor (:handle fact))
                              :source (:source fact)
                              :medium (:medium fact)}
                       :writer writer))]
    (atom {:holder (:holder wired)
           :tick (:tick wired)
           :fact (:fact wired)
           :writer (:writer wired)
           :envelope (or envelope {})
           :reading nil
           :lapsed? false
           :gapped? false
           :release nil})))


(defn- lease-id
  [a]
  (get-in a [:holder :grant :dao.lease/lease]))


(defn- usable-reading?
  "True for a reading the holder can observe with: a well-shaped duration in
   the holder's unit table and within its per-unit bound (what `observe-grant`
   itself requires)."
  [holder reading]
  (and (lease/duration? reading)
       (let [k (first (keys reading))
             m (get (:units holder) k)]
         (and (some? m)
              (<= (get reading k) (quot lease/magnitude-limit m))))))


(defn step!
  "One control-flow pass of the holder: drain its own tick cursor for the
   newest reading, then observe its grant (before anything else) and any
   `:lapsed` record for its lease.  The fact cursor advances only once a usable
   reading exists: facts published before it stay unread on the medium, in
   order, until a reading can observe them, so a grant is never skipped and a
   lapse that follows it is never read ahead of it.  A `gap` on either cursor
   marks the agent `:gapped?`: it renews nothing from then on.  Answers the new
   state."
  [agent]
  (swap!
    agent
    (fn [a]
      (let [t (drain (get-in a [:tick :handle]) (get-in a [:tick :cursor]))
            reading (or (some-> (filter #(= :dao.lease/tick (:dao.lease/event %))
                                        (:values t))
                                last
                                :dao.lease/reading)
                        (:reading a))
            a (-> a
                  (assoc :reading reading)
                  (assoc-in [:tick :cursor] (:cursor t))
                  (update :gapped? #(or % (:gap? t))))]
        (if-not (usable-reading? (:holder a) reading)
          a
          (let [f (drain (get-in a [:fact :handle]) (get-in a [:fact :cursor]))
                a (-> a
                      (assoc-in [:fact :cursor] (:cursor f))
                      (update :gapped? #(or % (:gap? f))))]
            (reduce
              (fn [a v]
                (cond
                  (nil? (get-in a [:holder :grant]))
                  (update a :holder lease/observe-grant (get-in a [:fact :source]) v reading)

                  (and (= :dao.lease/lapsed (:dao.lease/status v))
                       (some? (lease-id a))
                       (= (lease-id a) (:dao.lease/lease v)))
                  (assoc a :lapsed? true)

                  :else a))
              a
              (:values f))))))))


(defn gapped?
  "True once any cursor the agent reads answered `gap`."
  [agent]
  (boolean (:gapped? @agent)))


(defn release-pending?
  "True while a release fact is staged and not yet appended with `ok`."
  [agent]
  (some? (:release @agent)))


(defn holding?
  "May the control flow act: a grant observed, not stopped, not at its bound,
   not lapsed, and no gap seen."
  [agent]
  (let [a @agent]
    (boolean (and (not (:lapsed? a))
                  (not (:gapped? a))
                  (some? (:reading a))
                  (lease/holding? (:holder a) (:reading a))))))


(defn granted?
  [agent]
  (some? (get-in @agent [:holder :grant])))


(defn past-bound?
  "True once the agent has left the lease for good: it lapsed, was released,
   or reached its bound.  A gap is not that: the agent renews nothing after one
   but the lease is not known to be over."
  [agent]
  (let [a @agent]
    (boolean (or (:lapsed? a)
                 (get-in a [:holder :released?])
                 (get-in a [:holder :bound-reached?])
                 (and (some? (:reading a)) (some? (get-in a [:holder :grant]))
                      (lease/at-bound? (:holder a) (:reading a)))))))


(defn- append-renewal!
  [agent]
  (let [a @agent
        fact (merge (lease/renewal (lease-id a)) (:envelope a))
        outcome (stream/append! (get-in a [:writer :handle]) fact)]
    (swap! agent update :holder lease/observe-renewal (:reading a) outcome)
    outcome))


(defn renew-if-due!
  "Renew when the holder's interval has been reached.  Answers true when a
   renewal was appended."
  [agent]
  (let [a @agent]
    (boolean
      (when (and (holding? agent) (lease/due-to-renew? (:holder a) (:reading a)))
        (append-renewal! agent)
        true))))


(defn renew!
  "Renew now, on a consumed input.  A holder that is not holding renews
   nothing."
  [agent]
  (boolean (when (holding? agent) (append-renewal! agent) true)))


(defn release!
  "Release on halt.  The release fact is kept until its append answers ok, so a
   failed append retries the same fact."
  [agent]
  (let [a @agent]
    (when (and (granted? agent) (not (get-in a [:holder :released?])))
      (let [{:keys [holder release]} (lease/stop (:holder a))]
        (swap! agent assoc :holder holder :release (merge release (:envelope a)))))
    (when-let [fact (:release @agent)]
      (when (ok? (stream/append! (get-in @agent [:writer :handle]) fact))
        (swap! agent assoc :release nil :released-sent? true)))
    agent))


;; -----------------------------------------------------------------------------
;; Grantor side: exchange lease
;; -----------------------------------------------------------------------------

(def resolver-bindings #{:per-author-media :envelope-key})

(def exchange-source :egress)
(def lineage-source :session-facts)
(def exchange-lease-source :exchange-lease)
(def lineage-lease-source :lineage-lease)
(def exchange-grantor :boundary-judge)
(def lineage-grantor :lineage-judge)


(defn resolver
  "The one attribution the star compositions share.  Per-author media: the
   source names the author.  Envelope key: the session-facts medium is written
   only by session drivers, each stamping `:dao.star/lineage`, and that stamp
   names the author.  A fact's own claim of authorship is never consulted."
  [source fact]
  (if (= lineage-source source)
    (when-let [s (:dao.star/lineage fact)] {:dao.star/lineage s})
    (case source
      :egress :egress
      :exchange-lease exchange-grantor
      :lineage-lease lineage-grantor
      nil)))


(defn medium-declaration
  [capacity attribution]
  {:retention :evict-oldest :capacity capacity
   :value-domain :portable-values :attribution attribution})


(defn facts-declaration
  "The declaration of the session-facts medium: complete history, no capacity."
  []
  {:retention :complete :value-domain :portable-values})


(defn check-facts-declaration!
  "Assembly check, on the declaration and not the handle: the session-facts
   medium must be declared `:retention :complete`.  Answers the declaration."
  [decl]
  (when-not (and (map? decl) (= :complete (:retention decl)) (not (contains? decl :capacity)))
    (throw (ex-info "the session-facts medium must be declared :retention :complete"
                    {:refused :session-facts :declaration decl})))
  decl)


(defn make-exchange-lease
  "The grantor half of the exchange lease, composed inside the peer holding the
   HTTP boundary.  Config:
     :ingress :egress :lease-medium  the three media (handles)
     :tick                           the judge's tick stream (handle)
     :open-exchanges :close-exchange the boundary's inventory and reclaim
     :units :duration :cadence :tolerance
     :subscription-max               required duration: the `:dao.lease/max` every
                                     subscription exchange's grant carries, the
                                     dead-peer bound on a host that reports
                                     neither close nor failed write
     :capacity                       declared capacity of the egress medium
   The judge reads the egress medium for the responders' renewals and writes
   grants and `:lapsed` to the lease medium.  Policy ends the lease of an
   exchange the inventory no longer holds (peer close, gap reconciliation), and
   reclaim is the boundary's idempotent `close-exchange`, reporting success when
   the exchange is not open afterwards."
  [{:keys [ingress egress lease-medium tick open-exchanges close-exchange
           units duration cadence tolerance capacity subscription-max]}]
  (when-not (lease/duration? subscription-max)
    (throw (ex-info "a subscription exchange needs a :dao.lease/max (:subscription-max, a duration)"
                    {:refused :subscription-cap :subscription-max subscription-max})))
  (let [reclaim (fn [subject]
                  (let [id (:http/exchange subject)]
                    (close-exchange id)
                    (not (contains? (open-exchanges) id))))
        policy (fn [entry _now]
                 (not (contains? (open-exchanges)
                                 (:http/exchange (:subject entry)))))
        made (lease/make-judge
               {:units units :cadence cadence :tolerance tolerance
                :resolver resolver :resolver-bindings resolver-bindings
                :reclaim reclaim :policy policy
                :writer lease-medium :self exchange-grantor
                :ticks [{:handle tick :cursor (oldest-cursor tick)}]
                :media [{:handle egress :cursor (oldest-cursor egress)
                         :source exchange-source
                         :medium (medium-declaration capacity :per-author-media)}]})]
    (atom {:judge (:judge made) :step (:step made)
           :ingress ingress :ingress-cursor (oldest-cursor ingress)
           :open-exchanges open-exchanges
           :duration duration :subscription-max subscription-max :leased #{}})))


(defn- grant-exchange
  "Grant exchange `id` unsolicited.  A subscription (`/s/…`), and any exchange
   whose kind is unknown (gap reconciliation), carries the tenure cap."
  [x id subscription?]
  (swap! x (fn [s]
             (-> s
                 (update :judge lease/author-grant
                         (lease/grant (lease/mint-lease-id) {:http/exchange id}
                                      :egress (:duration s)
                                      (when subscription?
                                        {:dao.lease/max (:subscription-max s)})))
                 (update :leased conj id))))
  x)


(defn exchange-lease-step!
  "One pass: observe the ingress for each `:http/request` and grant its
   exchange unsolicited; on an ingress gap reconcile against the inventory (an
   open exchange without a lease is granted afresh; a leased exchange absent
   from the inventory is ended by the judge's policy with cause `:policy`);
   then one judge pass.  Answers the atom."
  [x]
  (let [s @x
        r (drain (:ingress s) (:ingress-cursor s))
        open ((:open-exchanges s))]
    (swap! x assoc :ingress-cursor (:cursor r))
    (doseq [v (:values r)
            :when (= :http/request (:http/event v))
            :let [id (:http/exchange v)]
            :when (and (contains? open id) (not (contains? (:leased @x) id)))]
      (grant-exchange x id (str/starts-with? (str (:http/path v)) "/s/")))
    (when (:gap? r)
      (doseq [id open :when (not (contains? (:leased @x) id))]
        (grant-exchange x id true)))
    ;; Forget what is closed: its lease is the judge's to end.
    (swap! x update :leased #(into #{} (filter open) %))
    (swap! x (fn [s] (update s :judge (:step s))))
    x))


;; -----------------------------------------------------------------------------
;; Grantor side: lineage lease
;; -----------------------------------------------------------------------------

(defn make-lineage-lease
  "The grantor half of the lineage lease.  Config:
     :facts          the session-facts medium (handle)
     :lease-medium   where grants and `:lapsed` are written (handle)
     :tick           the judge's tick stream (handle)
     :retire!        (fn [lineage]) retiring the parked record
     :units :duration :max :cadence :tolerance
     :facts-declaration  the facts medium's declaration, `:retention :complete`
   Each root fact read grants unsolicited, subject and holder
   `{:dao.star/lineage S}`.  The judge reads the same medium for the drivers'
   renewals and releases, attributed by the `:dao.star/lineage` envelope key."
  [{:keys [lease-medium tick retire! units duration cadence tolerance]
    cap :max session-facts :facts declaration :facts-declaration}]
  (check-facts-declaration! declaration)
  (let [made (lease/make-judge
               {:units units :cadence cadence :tolerance tolerance
                :resolver resolver :resolver-bindings resolver-bindings
                :reclaim (fn [subject]
                           (retire! (:dao.star/lineage subject))
                           true)
                :writer lease-medium :self lineage-grantor
                :ticks [{:handle tick :cursor (oldest-cursor tick)}]
                :media [{:handle session-facts :cursor (oldest-cursor session-facts)
                         :source lineage-source
                         :medium (assoc declaration :attribution :envelope-key)}]})]
    (atom {:judge (:judge made) :step (:step made)
           :facts session-facts :facts-cursor (oldest-cursor session-facts)
           :duration duration :max cap})))


(defn lineage-lease-step!
  "One pass: grant each newly read root, then one judge pass."
  [x]
  (let [s @x
        r (drain (:facts s) (:facts-cursor s))]
    (swap! x assoc :facts-cursor (:cursor r))
    (doseq [v (:values r)
            :when (and (map? v) (= :root (facts/fact-kind v)))
            :let [subject {:dao.star/lineage (:dao.star/lineage v)}]]
      (swap! x update :judge lease/author-grant
             (lease/grant (lease/mint-lease-id) subject subject (:duration s)
                          (when (:max s) {:dao.lease/max (:max s)}))))
    (swap! x (fn [s] (update s :judge (:step s))))
    x))
