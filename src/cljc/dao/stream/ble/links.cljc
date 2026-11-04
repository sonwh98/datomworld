(ns dao.stream.ble.links
  "The host-free link state machine of docs/design/dao.stream.ble.md 6: a
   seam's judgment is all link bookkeeping, and link bookkeeping is the
   same on every host, so it lives here -- pure data over radio events --
   and a host seam is a thin adapter that turns plugin streams into radio
   events and returned actions into plugin calls. The dart seam holds no
   policy of its own; a seam cannot drift from the tests that gate this
   namespace.

   Radio events are host-local vectors (never dao.stream values, never
   deposited as such):

     [:adv peer rssi]        an advertisement of the service UUID
     [:link-up peer dir]     one directional GATT link exists
                             (dir :central connected out, :peripheral in)
     [:link-down peer]       that directional link is gone
     [:link-failed peer]     a connect attempt ended without a link
     [:frame peer bytes]     one frame arrived on that link
     [:send-fail peer why]   the host reported a queued send as failed
     [:radio-up self]        the radio started: advertising and scanning
     [:radio-down why]       the radio stopped on its own (permissions,
                             bluetooth off, suspension)
     [:bind-failed why]      the radio never started

   `handle-radio-event` answers {:state s' :deposits [...] :actions [...]}
   : `:deposits` are dao.stream.ble values the caller appends to the
   deposit writer in order, `:actions` are the radio calls the policy
   owns: [:connect peer] -- an advertisement naming a peer with no link
   and :max-links allowing, the medium's own repetition being the retry
   cadence -- and nothing for a peer past the ceiling, whose
   advertisement still deposits peer-seen. The ceiling counts
   established links and pending dials both, and governs this seam's own
   dials only: an inbound link always deposits.

   `seam` is the generic adapter, host-free, that composes the machine
   into the seam contract dao.stream.ble/writer consumes: it takes the
   bind data and a radio map, appends every deposit to the deposit
   writer in order, performs every action on the radio, and exposes
   {:send! f :close! f :radio! f :state f} -- :send!/:close! are the
   seam contract, :radio! is one radio event in, :state observes the
   machine for tests. A send crosses the one directional GATT link the
   peer address names; no link is the clean transport-error of section
   4 plus a send-failed deposit; and both-directions redundancy -- two
   nodes that each connect out to the other -- is a BLE fact the mesh's
   dedup, not the seam's, eats (design 7): the machine cannot correlate
   two opaque peer addresses, and does not pretend to."
  (:require [dao.host.bytes :as bytes]
            [dao.stream :as stream]
            [dao.stream.ble :as ble]))


(def directions
  "The two roles one node can hold on one GATT link."
  #{:central :peripheral})


;; =============================================================================
;; The pure state machine
;; =============================================================================


(defn initial-state
  "The machine's state over the bind data: `:now-ms` is the clock the
   discovery throttle reads, (f) -> integer milliseconds -- a host seam
   supplies the host clock, tests supply a deterministic one. The clock
   is the seam's own operational fact; no protocol state below the seam
   reads it."
  [{:keys [identity max-links announce-window-ms max-frame-bytes now-ms]}]
  (when-not (ble/node-id? identity)
    (throw (ex-info "a dao.stream.ble seam needs :identity, a node id"
                    {:identity identity})))
  (when-not (fn? now-ms)
    (throw (ex-info "a dao.stream.ble seam needs :now-ms, a clock"
                    {:now-ms now-ms})))
  {:identity identity
   :max-links (or max-links ble/default-max-links)
   :announce-window-ms (or announce-window-ms
                           ble/default-announce-window-ms)
   :max-frame-bytes (or max-frame-bytes ble/default-max-frame-bytes)
   :now-ms now-ms
   :links {}
   :connecting #{}
   :announced {}
   :closed? false})


(defn- link-direction
  "The direction last reported for the link `peer` names. One peer
   address is one entry: a platform that reports both directions of one
   pairing under one address re-marks the entry's direction, and the
   first [:link-down ...] for that address drops it."
  [state peer]
  (:direction (get (:links state) peer)))


(defn- throttle-deposit?
  "True iff `state` owes `peer` a peer-seen deposit now: no window is
   open, or its window has elapsed. The first advertisement always
   deposits."
  [state peer]
  (if-some [since (get (:announced state) peer)]
    (>= ((:now-ms state)) (+ since (:announce-window-ms state)))
    true))


(defn- note-announced
  [state peer]
  (assoc-in state [:announced peer] ((:now-ms state))))


(defn- link-count-ok?
  "The ceiling counts established links and pending connects both: a
   burst of advertisements from different peers issues at most
   :max-links dials, whatever the radio does with them."
  [state]
  (< (+ (count (:links state)) (count (:connecting state)))
     (:max-links state)))


(defn- prune-announced
  "Announcement windows older than :announce-window-ms are dropped, so
   a scanner seeing rotating random addresses does not grow the map
   without bound. Pure: answers the pruned state."
  [state]
  (let [now ((:now-ms state))
        horizon (- now (:announce-window-ms state))]
    (assoc state :announced
           (into {} (filter (fn [[_ since]] (>= since horizon)))
                 (:announced state)))))


(defn- answer
  "The machine's answer shape: the next state, the deposits to append in
   order, the actions to perform in order."
  [state deposits actions]
  {:state state :deposits (vec deposits) :actions (vec actions)})


(defn handle-radio-event
  "One radio event in, the machine's answer out. Total: an event the
   machine does not know is answered with the state unchanged, no
   deposits, no actions -- a newer radio, not a defect."
  [state ev]
  (let [[kind peer arg] ev]
    (if (:closed? state)
      (answer state [] [])
      (case kind
        :radio-up
        (answer state [(ble/bound-event (:identity state) peer)] [])

        :bind-failed
        (answer state [(ble/bind-failed-event (:identity state) peer)] [])

        :radio-down
        ;; The radio stopped on its own: every live link is lost, and the
        ;; node this composition held is observably gone (dao.stream.md,
        ;; Surfaces). Sends through the writer answer transport-error
        ;; from here on -- no links -- until the composition re-binds.
        (let [peers (vec (keys (:links state)))]
          (answer (assoc state :links {} :connecting #{} :announced {})
                  (into [(ble/closed-event (:identity state))]
                        (map (fn [p]
                               (ble/link-down-event (:identity state) p)))
                        peers)
                  [[:stop-radio]]))

        :adv
        ;; One observed advertisement of the service UUID. The throttle
        ;; owes at most one peer-seen per peer per :announce-window-ms,
        ;; latest RSSI winning; the connect policy rides the same window,
        ;; so the medium's own repetition is the retry cadence.
        (let [s (prune-announced state)
              owes? (throttle-deposit? s peer)
              s (if owes? (note-announced s peer) s)
              deposits (if owes?
                         [(ble/peer-seen-event (:identity state) peer arg)]
                         [])
              actions (if (and owes?
                               (not (contains? (:links state) peer))
                               (not (contains? (:connecting s) peer))
                               (link-count-ok? s))
                        [[:connect peer]]
                        [])
              s (if (seq actions)
                  (update s :connecting conj peer)
                  s)]
          (answer s deposits actions))

        ;; The ceiling of section 6 governs this seam's own dials, not
        ;; what the OS accepts on its side: an inbound link always
        ;; deposits, whatever :max-links says.
        :link-up
        (answer (-> state
                    (assoc-in [:links peer] {:direction arg})
                    (update :connecting disj peer))
                [(ble/link-up-event (:identity state) peer arg)]
                [])

        ;; A link whose peer address the machine never saw come up is a
        ;; radio fact about nothing this node holds -- the stale
        ;; duplicate of a teardown already processed. It deposits
        ;; nothing: a never-held link is not a lost one.
        :link-down
        (if (contains? (:links state) peer)
          (answer (-> state
                      (update :links dissoc peer)
                      (update :connecting disj peer))
                  [(ble/link-down-event (:identity state) peer)
                   (ble/peer-lost-event (:identity state) peer)]
                  [])
          (answer (update state :connecting disj peer) [] []))

        ;; A connect attempt ended without a link: no deposit -- the next
        ;; advertisement window retries, and a never-up link is not a
        ;; lost one.
        :link-failed
        (answer (update state :connecting disj peer) [] [])

        :frame
        ;; The :max-frame-bytes drop is protocol validation below the
        ;; transform: an inbound frame over the budget is never
        ;; deposited, the same symmetric budget design 4 refuses at
        ;; append!.
        (if (> (bytes/length arg) (:max-frame-bytes state))
          (answer state [] [])
          (answer state
                  [(ble/frame-event (:identity state) peer arg)]
                  []))

        :send-fail
        (answer state
                [(ble/send-failed-event (:identity state) peer arg)]
                [])

        (answer state [] [])))))


(defn send!
  "One frame toward `peer`, decoded host bytes, over the one directional
   GATT link that peer address names: either an outcome and a
   [:send-frame ...] action, or the clean transport-error of section 4
   plus a send-failed deposit when no such link exists at this moment.
   \"No link\" is a normal BLE fact -- links come and go with proximity
   -- and is not distinguished from any other failure; the writer of a
   flooding mesh does not retry it, because the mesh's redundancy is the
   retry."
  [state peer bs]
  (cond
    (:closed? state)
    {:state state
     :outcome {:dao.stream/outcome :dao.stream/closed}
     :deposits [] :actions []}

    (not (ble/peer-address? peer))
    {:state state
     :outcome {:dao.stream/outcome :dao.stream/transport-error
               :dao.stream.ble/reason "malformed destination"}
     :deposits [(ble/send-failed-event (:identity state) peer
                                       "malformed destination")]
     :actions []}

    (nil? (link-direction state peer))
    {:state state
     :outcome {:dao.stream/outcome :dao.stream/transport-error
               :dao.stream.ble/reason "no link to peer"}
     :deposits [(ble/send-failed-event (:identity state) peer
                                       "no link to peer")]
     :actions []}

    :else
    {:state state
     :outcome {:dao.stream/outcome :dao.stream/ok}
     :deposits []
     :actions [[:send-frame peer bs]]}))


(defn close!
  "The close path: the node stops advertising, scanning and serving, and
   is observably gone. Deposits are one closed event followed by a
   link-down for every live link -- the mesh above learns its neighbors
   from those events, and a link fact the reader held must not outlive
   the reader's evidence for it (design 5). Idempotent: a closed node's
   close answers without deposits or actions."
  [state]
  (if (:closed? state)
    (answer state [] [])
    (answer (assoc state :closed? true :links {} :connecting #{})
            (into [(ble/closed-event (:identity state))]
                  (map (fn [p] (ble/link-down-event (:identity state) p)))
                  (keys (:links state)))
            [[:stop-radio]])))


;; =============================================================================
;; The generic adapter: one seam, any radio
;; =============================================================================


(defn- append-deposits!
  "Every deposit onto the deposit writer, in order, each appended even if
   the one before it answered non-ok -- the deposit writer is an evicting
   ring by contract and never refuses (design 5). Total even if the
   composition behind it is broken."
  [deposit deposits]
  (doseq [ev deposits]
    (try (stream/append! deposit ev)
         (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil))))


(defn seam
  "The generic adapter (design 6): the machine composed into the seam
   contract `dao.stream.ble/writer` consumes, over a `radio` map the
   host supplies:

     {:perform! (fn [action])}   machine -> radio: [:connect peer],
                                 [:send-frame peer bytes], [:stop-radio]

   and answers {:send! f :close! f :radio! f :state f}: `:send!`/
   `:close!` are the seam contract (bind data in, those two out, deposits
   flowing to `:deposit` only); `:radio!` is the adapter's one entry, one
   radio event in; `:state` observes the machine, for tests. `:send!`
   hands the frame to the radio and answers `ok` -- it asserts nothing
   about the air -- and a failure the host reports later arrives as a
   [:send-fail ...] radio event, the send-failed channel of design 4.
   The radio's own send failures are deposited; they are not retried."
  [{:keys [identity deposit max-frame-bytes max-links announce-window-ms
           now-ms radio]}]
  (when-not (and (map? radio) (fn? (:perform! radio)))
    (throw (ex-info "a dao.stream.ble seam needs a radio with :perform!"
                    {:radio radio})))
  (let [state (atom (initial-state
                      {:identity identity
                       :max-links max-links
                       :announce-window-ms announce-window-ms
                       :max-frame-bytes max-frame-bytes
                       :now-ms now-ms}))
        run! (fn [a]
               (reset! state (:state a))
               (append-deposits! deposit (:deposits a))
               (doseq [action (:actions a)] ((:perform! radio) action)))]
    {:send!
     (fn [peer bs]
       (let [a (send! @state peer bs)]
         (run! a)
         (:outcome a)))
     :close!
     (fn []
       (run! (close! @state))
       nil)
     :radio!
     (fn [ev]
       (run! (handle-radio-event @state ev))
       nil)
     :state
     (fn [] @state)}))
