;; Pointer packet lifecycle, capture, gesture arenas, recognizer machine
;; stepping, arena resolution, and subscriber dispatch. Everything here is
;; a pure function of the interpreter value and canonical input data.
;; Specification: docs/design/dao.gui.event.md sections Normalized Pointer
;; Packets, Frame And Input Causality, Hit Testing And Capture,
;; Multi-Pointer Joining, Gesture Arena, Gesture Output, Subscriber Model,
;; Timers.
(ns dao.gui.event.pointer
  (:require [dao.gui.event.decl :as decl]
            [dao.gui.event.fault :as fault]
            [dao.gui.event.geom :as geom]
            [dao.gui.event.recognizer :as recognizer]
            [dao.gui.event.trace :as trace]))


(declare revive-deferred
         capture-in-arena
         create-arena
         merge-arenas
         step-one-candidate*
         remove-contact
         gesture-event)


(def window-capacity 32)


;; ---------------------------------------------------------------------------
;; Accumulator
;; ---------------------------------------------------------------------------

(defn- acc-new
  [state]
  {:state state, :effects [], :traces [], :events [], :diagnostics []})


(defn- acc-outputs
  "Effects, then traces, then each event followed by its dispatches, then
  diagnostics."
  [{:keys [effects traces events diagnostics]}]
  (vec (concat effects traces events diagnostics)))


(defn- acc-event
  "Append one event followed by its fan-out dispatches."
  [acc subscriptions event]
  (let [dispatches (keep (fn [sub]
                           (when (if (= :pointer (:event-kind sub))
                                   (and (:raw? sub)
                                        (= :pointer (:event/kind event))
                                        (= (:node-id sub) (:node-id event)))
                                   (and (= :gesture (:event/kind event))
                                        (= (:node-id sub) (:node-id event))
                                        (= (:event-kind sub)
                                           (:gesture/kind event))
                                        (or (nil? (:gesture/phases sub))
                                            (contains? (:gesture/phases sub)
                                                       (:phase event)))))
                             {:dispatch/kind :dao.gui.event/subscriber,
                              :subscription/id (:subscription/id sub),
                              :subscriber/id (:subscriber/id sub),
                              :node-id (:node-id sub),
                              :event-kind (:event-kind sub),
                              :event event}))
                         subscriptions)]
    (update acc :events into (concat [event] dispatches))))


;; ---------------------------------------------------------------------------
;; Candidate and arena construction
;; ---------------------------------------------------------------------------

(defn- make-candidate
  [path-index decl-index path-entry profile]
  (let [decl (nth (:recognizers path-entry) decl-index)
        machine (:machine decl)
        required (recognizer/required-capabilities machine)
        capabilities (set (get profile :capabilities))
        dormant? (boolean (some (fn [cap] (not (contains? capabilities cap)))
                                required))]
    {:candidate-id [(:node-id path-entry) (:recognizer/id decl)],
     :node-id (:node-id path-entry),
     :recognizer-id (:recognizer/id decl),
     :gesture/kind (:gesture/kind decl),
     :machine machine,
     :config (:config decl {}),
     :arena-decl (:arena decl {}),
     :path-index path-index,
     :decl-index decl-index,
     :decision :possible,
     :accepted? false,
     :dormant? dormant?,
     :machine-state (recognizer/initial-state machine (:config decl {})),
     :windows {:motion []},
     :timer-seqs {},
     :active-timers {},
     :last-payload nil,
     :emitted-start? false}))


(defn- candidates-from-path
  [path omit-ids profile]
  (into {}
        (comp (map-indexed (fn [i entry]
                             (keep-indexed (fn [j decl]
                                             (let [id [(:node-id entry)
                                                       (:recognizer/id decl)]]
                                               (when-not (contains? omit-ids id)
                                                 [i j])))
                                           (:recognizers entry))))
              cat
              (map (fn [[i j]]
                     (let [candidate (make-candidate i j (nth path i) profile)]
                       [(:candidate-id candidate) candidate]))))
        path))


(defn- candidate-declaration-order
  "Candidate ids in path order then declaration order, omitting ids."
  [path omit-ids]
  (into []
        (comp (map-indexed (fn [_i entry]
                             (keep-indexed (fn [_j decl]
                                             (let [id [(:node-id entry)
                                                       (:recognizer/id decl)]]
                                               (when-not (contains? omit-ids id)
                                                 id)))
                                           (:recognizers entry))))
              cat)
        path))


(defn- candidate-rank
  "Greater priority, then greater path index, then earlier declaration
  index, then stable EDN order of the recognizer id."
  [candidate]
  [(- (get-in candidate [:arena-decl :priority] 0)) (- (:path-index candidate))
   (:decl-index candidate) (:recognizer-id candidate)])


(defn- by-rank
  [candidates]
  (vec (sort-by candidate-rank trace/edn-compare candidates)))


(defn- snapshot-subscriptions
  "Matching registrations at arena creation: gesture interests for nodes on
  the captured path, raw pointer interests for the terminal target node."
  [state path hit-node-id]
  (let [path-nodes (set (map :node-id path))]
    (vec (filter (fn [sub]
                   (if (= :pointer (:event-kind sub))
                     (and (:raw? sub) (= hit-node-id (:node-id sub)))
                     (contains? path-nodes (:node-id sub))))
                 (map (fn [id] (get-in state [:subscriptions id]))
                      (:subscription-order state))))))


;; ---------------------------------------------------------------------------
;; Hit testing
;; ---------------------------------------------------------------------------

(defn- region-contains?
  [bounds x y]
  (and (<= (:x bounds) (double x))
       (< (double x) (+ (:x bounds) (:width bounds)))
       (<= (:y bounds) (double y))
       (< (double y) (+ (:y bounds) (:height bounds)))))


(defn hit-region
  "Topmost region containing the point, or nil, or ::ambiguous when two
  regions share the greatest effective paint order."
  [regions x y]
  (let [hits (vec (filter (fn [region] (region-contains? (:bounds region) x y))
                          regions))]
    (cond (empty? hits) nil
          (= 1 (count hits)) (first hits)
          :else (let [top (apply max (map :paint-order hits))
                      tops (filter (fn [region] (= (:paint-order region) top))
                                   hits)]
                  (if (= 1 (count tops)) (first tops) ::ambiguous)))))


;; ---------------------------------------------------------------------------
;; Windows and contacts
;; ---------------------------------------------------------------------------

(defn- final-sample
  [packet]
  (peek (vec (:samples packet))))


(defn- push-window-entries
  "For each coalesced or actual sample: temporarily replace the pointer's
  position, push one centroid entry, prune by velocity window and
  capacity."
  [window contacts pointer-id samples window-us]
  (reduce (fn [window sample]
            (let [temp (assoc-in contacts
                                 [pointer-id :position]
                                 (:position sample))
                  entry {:time-us (:time-us sample),
                         :position (geom/centroid temp),
                         :pointer-ids (geom/contact-ids temp)}
                  latest (:time-us sample)
                  pruned (filter (fn [e] (>= (:time-us e) (- latest window-us)))
                                 window)]
              (vec (take-last window-capacity (conj (vec pruned) entry)))))
          (vec window)
          samples))


(defn- window-of
  [candidate]
  (get-in candidate [:windows :motion]))


(defn- assoc-window
  [candidate window]
  (assoc-in candidate [:windows :motion] window))


;; ---------------------------------------------------------------------------
;; Machine stepping
;; ---------------------------------------------------------------------------

(defn- ctx-for
  [arena candidate packet machine-input time-us]
  {:contacts (:contacts arena),
   :sample (final-sample packet),
   :pointer (:pointer packet),
   :input machine-input,
   :packet packet,
   :time-us time-us,
   :config (:config candidate),
   :profile (:profile arena),
   :window (get-in candidate [:windows :motion]),
   :coordinate-space {:id (:coordinate-space-id arena),
                      :viewport (:viewport arena)}})


(defn- apply-timer-effect
  [state arena candidate effect time-us]
  (case (first effect)
    :timer/start
    (let [[_op timer-id duration-us] effect
          timer-seq (inc (get-in candidate [:timer-seqs timer-id] 0))
          tkey (fault/timer-key (:generation-id arena)
                                (:coordinate-space-id arena)
                                (:arena-id arena)
                                (:recognizer-id candidate)
                                timer-id
                                timer-seq)
          output (assoc (fault/timer-request tkey :start)
                        :deadline-us (+ time-us duration-us))]
      {:state (assoc-in state
                        [:timers tkey]
                        {:status :scheduled, :deadline-us (+ time-us duration-us)}),
       :candidate (-> candidate
                      (assoc-in [:timer-seqs timer-id] timer-seq)
                      (assoc-in [:active-timers timer-id]
                                {:timer-seq timer-seq})),
       :effects [output]})
    :timer/cancel (let [timer-id (first (rest effect))
                        active (get-in candidate [:active-timers timer-id])]
                    (if (nil? active)
                      {:state state, :candidate candidate, :effects []}
                      (let [tkey (fault/timer-key (:generation-id arena)
                                                  (:coordinate-space-id arena)
                                                  (:arena-id arena)
                                                  (:recognizer-id candidate)
                                                  timer-id
                                                  (:timer-seq active))
                            [timers cancel-output]
                            (fault/cancel-timer (:timers state) tkey)]
                        {:state (assoc state :timers timers),
                         :candidate
                         (update candidate :active-timers dissoc timer-id),
                         :effects (vec (keep identity [cancel-output]))})))
    ;; unknown effects are ignored defensively; machines are validated
    {:state state, :candidate candidate, :effects []}))


(defn- step-one-candidate
  "Deliver one machine input to one candidate. Returns the accumulator."
  [acc arena candidate machine-input packet time-us]
  (let [ctx (ctx-for arena candidate packet machine-input time-us)
        result (recognizer/step (:machine candidate)
                                ctx
                                machine-input
                                (:machine-state candidate)
                                (:config candidate))]
    (if (:fault? result)
      (-> acc
          (update :state
                  assoc-in
                  [:arenas (:arena-id arena) :candidates
                   (:candidate-id candidate) :decision]
                  :rejected)
          (update :diagnostics
                  conj
                  (fault/diagnostic :dao.gui.event/recognizer-fault
                                    :error (:fault-reason result :machine-fault)
                                    :arena-id (:arena-id arena)
                                    :recognizer/id (:recognizer-id candidate)
                                    :node-id (:node-id candidate))))
      (let [previous (::proposed candidate)
            ;; multiple machine inputs may arrive within one runtime input
            ;; (down/up followed by contacts-changed); a hold never erases
            ;; an earlier accept or reject proposal
            decision (if (= :hold (:decision result))
                       (:decision previous :hold)
                       (:decision result))
            emissions (concat (:emissions previous) (:emissions result))
            ;; emissions of an already-accepted continuous candidate are
            ;; committed output, not a proposal held for arena resolution
            already-accepted? (:accepted? candidate)
            committed-emissions (if already-accepted? (:emissions result) [])
            candidate'
            (assoc candidate
                   :machine-state (:state result)
                   :last-payload (or (:payload (last (:emissions result)))
                                     (:last-payload candidate))
                   :emitted-start? (boolean (or (:emitted-start? candidate)
                                                (some (fn [e]
                                                        (contains? #{:start :update
                                                                     :end :cancel}
                                                                   (:phase e)))
                                                      (:emissions result))))
                   ::proposed (if already-accepted?
                                {:decision decision, :emissions []}
                                {:decision decision, :emissions emissions}))
            timer-accumulator
            (reduce (fn [{:keys [state candidate effects]} effect]
                      (let [applied (apply-timer-effect state
                                                        arena
                                                        candidate
                                                        effect
                                                        time-us)]
                        {:state (:state applied),
                         :candidate (:candidate applied),
                         :effects (concat effects (:effects applied))}))
                    {:state (:state acc), :candidate candidate', :effects []}
                    (:effects result))
            updated-acc (-> acc
                            (assoc :state (:state timer-accumulator))
                            (update :effects into (:effects timer-accumulator))
                            (update-in [:state :arenas (:arena-id arena)
                                        :candidates (:candidate-id candidate)]
                                       merge
                                       (dissoc (:candidate timer-accumulator)
                                               ::proposed)
                                       {::proposed (::proposed candidate')}))]
        (if (seq committed-emissions)
          (reduce (fn [acc emission]
                    (acc-event acc
                               (:subscriptions arena)
                               (gesture-event arena
                                              candidate
                                              (:phase emission)
                                              (:payload emission)
                                              (or (:position emission)
                                                  (get-in candidate
                                                          [:last-payload
                                                           :position]))
                                              time-us
                                              packet
                                              (:pointer-ids arena))))
                  updated-acc
                  committed-emissions)
          updated-acc)))))


(defn- live-candidates
  [arena]
  (filter (fn [candidate]
            (and (not (:dormant? candidate))
                 (or (contains? #{:possible :held} (:decision candidate))
                     (:accepted? candidate))))
          (map (fn [id] (get-in arena [:candidates id])) (:candidate-order arena))))


(defn- step-arena-candidates
  "Deliver one machine input to every live candidate of the arena in
  declaration order."
  [acc arena-id machine-input packet time-us]
  (let [arena (get-in (:state acc) [:arenas arena-id])]
    (reduce (fn [acc candidate]
              (let [arena (get-in (:state acc) [:arenas arena-id])]
                (step-one-candidate acc
                                    arena
                                    candidate
                                    machine-input
                                    packet
                                    time-us)))
            acc
            (live-candidates arena))))


(defn- current-arena
  [acc arena-id]
  (get-in (:state acc) [:arenas arena-id]))


;; ---------------------------------------------------------------------------
;; Arena resolution
;; ---------------------------------------------------------------------------

(defn- gesture-event
  [arena candidate phase payload position time-us packet _pointer-ids]
  {:event/kind :gesture,
   :gesture/id [(:arena-id arena) (:recognizer-id candidate)],
   :gesture/kind (:gesture/kind candidate),
   :phase phase,
   :node-id (:node-id candidate),
   :recognizer/id (:recognizer-id candidate),
   :arena-id (:arena-id arena),
   :generation-id (:generation-id arena),
   :origin-frame-id (:frame-id arena),
   :observed-frame-id (:frame-id packet),
   :coordinate-space-id (:coordinate-space-id arena),
   :time-us time-us,
   :pointer-ids (set (:participant-ids arena)),
   :position position,
   :payload payload})


(defn- decision-trace
  [arena candidate decision cause]
  {:event/kind :dao.gui.event/arena-decision,
   :arena-id (:arena-id arena),
   :candidate-id (:candidate-id candidate),
   :decision decision,
   :cause cause})


(defn- cancel-candidate-timers
  [acc arena candidate]
  (reduce (fn [{:keys [state effects]} [timer-id active]]
            (let [tkey (fault/timer-key (:generation-id arena)
                                        (:coordinate-space-id arena)
                                        (:arena-id arena)
                                        (:recognizer-id candidate)
                                        timer-id
                                        (:timer-seq active))
                  [timers cancel-output] (fault/cancel-timer (:timers state)
                                                             tkey)]
              {:state (assoc state :timers timers),
               :effects (concat effects (keep identity [cancel-output]))}))
          {:state (:state acc), :effects []}
          (:active-timers candidate)))


(defn- cancel-loser
  "A losing accepted continuous candidate emits exactly one semantic
  :cancel with its last payload plus reason; a losing possible candidate
  simply rejects."
  [acc arena candidate cause time-us packet]
  (let [arena-id (:arena-id arena)
        acc (update acc
                    :state
                    assoc-in
                    [:arenas arena-id :candidates (:candidate-id candidate)
                     :decision]
                    :rejected)
        ;; deliver the arena lifecycle input
        acc (step-one-candidate (update acc
                                        :state
                                        assoc-in
                                        [:arenas arena-id :candidates
                                         (:candidate-id candidate) :accepted?]
                                        false)
                                (current-arena acc arena-id)
                                (get-in (:state acc)
                                        [:arenas arena-id :candidates
                                         (:candidate-id candidate)])
                                {:machine/input :arena/cancelled, :cause cause}
                                packet
                                time-us)
        acc (let [canceled (get-in (:state acc)
                                   [:arenas arena-id :candidates
                                    (:candidate-id candidate)])
                  timers (cancel-candidate-timers acc arena canceled)]
              (-> acc
                  (assoc :state (:state timers))
                  (update :effects into (:effects timers))
                  (update :state
                          update-in
                          [:arenas arena-id :candidates
                           (:candidate-id candidate)]
                          dissoc
                          ::proposed)))
        acc (update acc
                    :traces
                    conj
                    (decision-trace arena candidate :cancelled cause))]
    (if (:emitted-start? (get-in (:state acc)
                                 [:arenas arena-id :candidates
                                  (:candidate-id candidate)]))
      (let [payload (assoc (or (:last-payload candidate) {}) :reason cause)
            event (gesture-event arena
                                 candidate
                                 :cancel
                                 payload
                                 (get-in candidate [:last-payload :position])
                                 time-us
                                 packet
                                 (:pointer-ids arena))]
        (acc-event acc (:subscriptions arena) event))
      acc)))


(defn- commit-winner
  "Commit an accepted candidate's held emissions as semantic events."
  [acc arena candidate emissions time-us packet]
  (let [arena-id (:arena-id arena)
        events (map (fn [{:keys [phase payload position]}]
                      (gesture-event arena
                                     candidate
                                     phase
                                     payload
                                     position
                                     time-us
                                     packet
                                     (:pointer-ids arena)))
                    emissions)]
    (as-> acc acc'
          (reduce (fn [acc event] (acc-event acc (:subscriptions arena) event))
                  acc'
                  events)
          (update acc'
                  :traces
                  into
                  (map (fn [_e]
                         (decision-trace arena candidate :accepted :arena-winner))
                       events))
          (update acc' :state assoc-in [:arenas arena-id :status] :accepted)
          (update acc'
                  :state
                  assoc-in
                  [:arenas arena-id :winner]
                  (:candidate-id candidate))
          (update acc'
                  :state
                  update-in
                  [:arenas arena-id :candidates (:candidate-id candidate)]
                  (fn [c]
                    (-> c
                        (assoc :decision :accepted
                               :accepted? true
                               ::proposed nil)
                        (assoc :last-payload (or (:payload (last emissions))
                                                 (:last-payload c)))
                        (assoc :emitted-start? (boolean (some (fn [e]
                                                                (contains?
                                                                  #{:start :update
                                                                    :end :cancel}
                                                                  (:phase e)))
                                                              emissions)))))))))


(defn- tap-alternative-viable?
  [arena winner]
  (let [n (get-in winner [:machine-state :completed-count] 0)
        contacts (get-in winner [:config :contacts])]
    (boolean (some (fn [candidate]
                     (and (not= (:candidate-id candidate)
                                (:candidate-id winner))
                          (= (:node-id candidate) (:node-id winner))
                          (= :tap (:gesture/kind candidate))
                          (= :dao.gui.event/tap (:machine candidate))
                          (= (get-in candidate [:config :contacts]) contacts)
                          (> (get-in candidate [:config :count] 1) n)
                          (contains? #{:possible :held} (:decision candidate))))
                   (vals (:candidates arena))))))


(defn- resolve-arena
  "Apply proposed decisions, choose winners, handle cooperative groups,
  tap deferral and revival, and detect arena end."
  [acc arena-id time-us packet]
  (let [arena (current-arena acc arena-id)
        candidates (vals (:candidates arena))
        ;; 1. proposed rejects
        acc (reduce (fn [acc candidate]
                      (if (= :reject (get-in candidate [::proposed :decision]))
                        (-> acc
                            (update :state
                                    assoc-in
                                    [:arenas arena-id :candidates
                                     (:candidate-id candidate) :decision]
                                    :rejected)
                            (update :state
                                    update-in
                                    [:arenas arena-id :candidates
                                     (:candidate-id candidate)]
                                    dissoc
                                    ::proposed)
                            (update :traces
                                    conj
                                    (decision-trace arena
                                                    candidate
                                                    :rejected
                                                    :machine-reject)))
                        acc))
                    acc
                    candidates)
        ;; 2. accepters
        accepters (keep (fn [candidate]
                          (when (= :accept
                                   (get-in candidate [::proposed :decision]))
                            candidate))
                        (vals (:candidates (current-arena acc arena-id))))
        exclusive (filter (fn [c]
                            (= :exclusive
                               (get-in c [:arena-decl :mode] :exclusive)))
                          accepters)
        ;; losing or previously accepted candidates that are not the winner
        cancel-not-winner
        (fn [acc winner-id]
          (reduce (fn [acc candidate]
                    (if (and (:accepted? candidate)
                             (not= (:candidate-id candidate) winner-id))
                      (cancel-loser acc
                                    (current-arena acc arena-id)
                                    candidate
                                    :arena-lost
                                    time-us
                                    packet)
                      acc))
                  acc
                  (vals (:candidates (current-arena acc arena-id)))))
        reject-loser (fn [acc loser]
                       (-> acc
                           (update :state
                                   assoc-in
                                   [:arenas arena-id :candidates
                                    (:candidate-id loser) :decision]
                                   :rejected)
                           (update :state
                                   update-in
                                   [:arenas arena-id :candidates
                                    (:candidate-id loser)]
                                   dissoc
                                   ::proposed)
                           (update :traces
                                   conj
                                   (decision-trace (current-arena acc arena-id)
                                                   loser
                                                   :rejected
                                                   :arena-lost))))
        acc
        (if (seq exclusive)
          (let [ranked (by-rank exclusive)
                winner (first ranked)
                arena-now (current-arena acc arena-id)
                defer? (and (= :tap (:gesture/kind winner))
                            (tap-alternative-viable? arena-now winner))
                after-losers (reduce (fn [acc loser]
                                       (if (:accepted? loser)
                                         (cancel-loser
                                           acc
                                           (current-arena acc arena-id)
                                           loser
                                           :arena-lost
                                           time-us
                                           packet)
                                         (reject-loser acc loser)))
                                     acc
                                     (rest ranked))
                acc (cancel-not-winner after-losers (:candidate-id winner))]
            (if defer?
              (let [proposed (get-in (current-arena acc arena-id)
                                     [:candidates (:candidate-id winner)
                                      ::proposed])]
                (-> acc
                    (update :state
                            assoc-in
                            [:arenas arena-id :candidates
                             (:candidate-id winner) :decision]
                            :held)
                    (update :state
                            update-in
                            [:arenas arena-id :candidates
                             (:candidate-id winner)]
                            dissoc
                            ::proposed)
                    (update :state
                            update-in
                            [:arenas arena-id :deferred-accepts]
                            assoc
                            (:candidate-id winner)
                            {:emissions (:emissions proposed),
                             :time-us time-us})))
              (commit-winner acc
                             (current-arena acc arena-id)
                             winner
                             (get-in winner [::proposed :emissions])
                             time-us
                             packet)))
          ;; no exclusive accepter: cooperative groups accept together
          (reduce (fn [acc group-accepters]
                    (let [arena-now (current-arena acc arena-id)
                          exclusive-winner?
                          (some (fn [c]
                                  (and (:accepted? c)
                                       (= :exclusive
                                          (get-in c
                                                  [:arena-decl :mode]
                                                  :exclusive))))
                                (vals (:candidates arena-now)))]
                      (if exclusive-winner?
                        (reduce (fn [acc loser]
                                  (if (:accepted? loser)
                                    (cancel-loser acc
                                                  (current-arena acc arena-id)
                                                  loser
                                                  :arena-lost
                                                  time-us
                                                  packet)
                                    (reject-loser acc loser)))
                                acc
                                group-accepters)
                        (reduce (fn [acc member]
                                  (commit-winner
                                    acc
                                    (current-arena acc arena-id)
                                    member
                                    (get-in member [::proposed :emissions])
                                    time-us
                                    packet))
                                acc
                                group-accepters))))
                  acc
                  (vals (group-by
                          (fn [c] (get-in c [:arena-decl :coexistence/group]))
                          (filter (fn [c]
                                    (= :cooperative (get-in c [:arena-decl :mode])))
                                  accepters)))))
        ;; 3. revive deferred taps when no viable higher-count alternative
        ;;    remains
        acc (revive-deferred acc arena-id time-us packet)
        arena (current-arena acc arena-id)
        ended? (and arena
                    (empty? (:pointer-ids arena))
                    (every? (fn [candidate]
                              (or (= :rejected (:decision candidate))
                                  (and (:accepted? candidate)
                                       (contains? #{:ended :rejected}
                                                  (:machine/state
                                                    (:machine-state
                                                      candidate))))))
                            (vals (:candidates arena))))]
    (if ended?
      (update acc
              :state
              (fn [state]
                (-> state
                    (update :arenas dissoc arena-id)
                    (update :pointers
                            (fn [pointers]
                              (into {}
                                    (filter (fn [[_id pointer]]
                                              (not= arena-id
                                                    (:arena-id pointer))))
                                    pointers))))))
      acc)))


(defn revive-deferred
  "Revive deferred tap accepts whose higher-count alternatives are gone.
  The greatest completed count accepts first; rank breaks ties. One
  deferred accept commits per resolution step."
  [acc arena-id _time-us packet]
  (let [arena (current-arena acc arena-id)
        deferred (:deferred-accepts arena)]
    (if (empty? deferred)
      acc
      (let [candidates (:candidates arena)
            entries (sort-by (fn [[candidate-id _record]]
                               (let [candidate (get candidates candidate-id)]
                                 [(- (get-in candidate
                                             [:machine-state :completed-count]
                                             0)) (:path-index candidate 0)
                                  (:decl-index candidate 0)
                                  (:recognizer-id candidate)]))
                             trace/edn-compare
                             deferred)
            blockers-gone?
            (fn [candidate-id n]
              (not (some (fn [c]
                           (and (not= (:candidate-id c) candidate-id)
                                (= (:node-id c) (first candidate-id))
                                (= :tap (:gesture/kind c))
                                (> (get-in c [:config :count] 1) n)
                                (contains? #{:possible :held} (:decision c))))
                         (vals candidates))))
            revivable (keep (fn [[candidate-id record]]
                              (let [candidate (get candidates candidate-id)]
                                (when (and (some? candidate)
                                           (= :held (:decision candidate))
                                           (blockers-gone? candidate-id
                                                           (get-in
                                                             candidate
                                                             [:machine-state
                                                              :completed-count]
                                                             0)))
                                  [candidate-id record])))
                            entries)]
        (if (empty? revivable)
          acc
          (let [[candidate-id record] (first revivable)
                candidate (get candidates candidate-id)]
            (-> acc
                (update :state
                        update-in
                        [:arenas arena-id :deferred-accepts]
                        dissoc
                        candidate-id)
                (commit-winner (current-arena acc arena-id)
                               (assoc candidate :decision :possible)
                               (:emissions record)
                               (:time-us record)
                               packet))))))))


;; ---------------------------------------------------------------------------
;; Arena cancellation
;; ---------------------------------------------------------------------------

(defn cancel-arenas
  "Cancel the named arenas and every pointer record captured to them.
  Returns [state outputs]."
  [state reason arena-ids packet]
  (let [time-us (or (:runtime/time-us packet) (:last-runtime-time-us state) 0)
        cancel-one
        (fn [acc arena-id]
          (let [arena (get-in (:state acc) [:arenas arena-id])]
            (if (nil? arena)
              acc
              (let [live (filter (fn [c]
                                   (or (:accepted? c)
                                       (contains? #{:possible :held}
                                                  (:decision c))))
                                 (vals (:candidates arena)))
                    after-cancels (reduce (fn [acc candidate]
                                            (cancel-loser
                                              acc
                                              (current-arena acc arena-id)
                                              candidate
                                              reason
                                              time-us
                                              packet))
                                          acc
                                          live)]
                (update after-cancels
                        :state
                        (fn [state]
                          (-> state
                              (update :arenas dissoc arena-id)
                              (update :pointers
                                      (fn [pointers]
                                        (into {}
                                              (filter (fn [[_id pointer]]
                                                        (not= arena-id
                                                              (:arena-id
                                                                pointer))))
                                              pointers))))))))))
        acc (reduce cancel-one (acc-new state) (vec (sort arena-ids)))]
    [(:state acc) (acc-outputs acc)]))


(defn cancel-all-arenas
  "Cancel every arena and pointer record for reset, space change, input
  loss, teardown, or input-sequence gaps. Returns [state outputs]."
  ([state reason] (cancel-all-arenas state reason nil))
  ([state reason packet]
   (let [[cancelled-state outputs]
         (cancel-arenas state reason (set (keys (:arenas state))) packet)]
     [(assoc cancelled-state :pointers {}) outputs])))


;; ---------------------------------------------------------------------------
;; Pointer record helpers
;; ---------------------------------------------------------------------------

(defn- pointer-record
  [arena packet region profile-id]
  {:pointer/id (get-in packet [:pointer :id]),
   :generation-id (:generation-id packet),
   :frame-id (:frame-id packet),
   :coordinate-space-id (:coordinate-space-id packet),
   :profile-id profile-id,
   :hit-node-id (:node-id region),
   :path (:interaction/path region),
   :arena-id (:arena-id arena),
   :position (get-in (final-sample packet) [:position])})


(defn- raw-pointer-event
  [packet pointer arena]
  {:event/kind :pointer,
   :phase (:phase packet),
   :generation-id (:generation-id packet),
   :frame-id (:frame-id packet),
   :coordinate-space-id (:coordinate-space-id packet),
   :profile-id (:profile-id packet),
   :input-seq (:input-seq packet),
   :pointer (:pointer packet),
   :samples (:samples packet),
   :node-id (:hit-node-id pointer),
   :arena-id (:arena-id pointer),
   :arena/status (:status arena),
   :origin-frame-id (:frame-id pointer),
   :target-path (vec (map :node-id (:path pointer)))})


(defn- fan-raw
  [acc pointer arena packet subscriptions]
  (acc-event acc subscriptions (raw-pointer-event packet pointer arena)))


;; ---------------------------------------------------------------------------
;; Packet validation shared by all phases
;; ---------------------------------------------------------------------------

(defn- absorb-outputs
  "Route produced outputs into the accumulator's buckets by discriminator."
  [acc outputs]
  (reduce (fn [acc output]
            (cond (:effect/kind output) (update acc :effects conj output)
                  (:diagnostic/kind output)
                  (update acc :diagnostics conj output)
                  (:event/kind output) (update acc :events conj output)
                  :else (update acc :traces conj output)))
          acc
          outputs))


(defn- validate-packet
  "Generation, coordinate space, and input-sequence validation. Returns
  {:acc ...} on success or {:drop [...]} when the packet is dropped."
  [acc packet]
  (let [{:keys [generation-id coordinate-space-id input-seq]} packet
        state (:state acc)
        {adopted :state, fault :fault}
        (fault/adopt-or-stale-generation state generation-id)]
    (cond (some? fault) {:drop [fault]}
          (not= coordinate-space-id (:active-coordinate-space-id adopted))
          {:drop [(fault/diagnostic :dao.gui.event/coordinate-space-mismatch
                                    :error :coordinate-space-mismatch
                                    :coordinate-space-id
                                    coordinate-space-id)]}
          :else
          (let [expected (:last-input-seq adopted)
                gap? (and (some? expected) (not= input-seq (inc expected)))]
            (if gap?
              (let [[cancelled-state cancel-outputs]
                    (cancel-all-arenas adopted :input-loss)]
                {:acc (-> acc
                          (assoc :state (assoc cancelled-state
                                               :last-input-seq input-seq))
                          (absorb-outputs cancel-outputs)
                          (update :diagnostics
                                  conj
                                  (fault/diagnostic
                                    :dao.gui.event/input-sequence-gap
                                    :error :missing-input-seq
                                    :input-seq input-seq)))})
              {:acc (assoc acc
                           :state (assoc adopted :last-input-seq input-seq))})))))


;; ---------------------------------------------------------------------------
;; Down
;; ---------------------------------------------------------------------------

(defn- join-or-create-arena
  "Resolve accepted intersections first, then unresolved joins and merges.
  Returns the accumulator with the pointer captured and machine inputs
  delivered."
  [acc packet region time-us]
  (let [state (:state acc)
        path (:interaction/path region)
        identities (set (candidate-declaration-order path #{}))
        live-arenas (sort (keys (:arenas state)))
        intersecting (fn [arena]
                       (some (fn [candidate]
                               (contains? identities (:candidate-id candidate)))
                             (vals (:candidates arena))))
        accepted-arenas (filter (fn [id]
                                  (let [arena (get-in state [:arenas id])]
                                    (and (= :accepted (:status arena))
                                         (intersecting arena))))
                                live-arenas)
        eligible-accepted
        (filter (fn [id]
                  (let [arena (get-in state [:arenas id])
                        winner (get-in arena [:candidates (:winner arena)])]
                    (and winner
                         (decl/join-after-accept
                           (first
                             (keep (fn [entry]
                                     (some
                                       (fn [d]
                                         (when (and (= (:node-id winner)
                                                       (:node-id entry))
                                                    (= (:recognizer-id winner)
                                                       (:recognizer/id d)))
                                           d))
                                       (:recognizers entry)))
                                   path)))
                         (recognizer/admits-join? (:machine winner)
                                                  (:machine-state winner)
                                                  (:config winner)
                                                  (inc (count (:contacts
                                                                arena)))))))
                accepted-arenas)]
    (if (seq eligible-accepted)
      ;; join the highest-ranked winner, then the oldest arena
      (let [target-id (first (sort-by (fn [id]
                                        (let [arena (get-in state [:arenas id])]
                                          [(candidate-rank (get-in arena
                                                                   [:candidates
                                                                    (:winner
                                                                      arena)]))
                                           ;; oldest arena breaks ties
                                           (:arena-id arena)]))
                                      trace/edn-compare
                                      eligible-accepted))
            acc (capture-in-arena acc packet region target-id time-us :join)]
        acc)
      (let [unresolved (filter (fn [id]
                                 (let [arena (get-in state [:arenas id])]
                                   (and (not= :accepted (:status arena))
                                        (intersecting arena))))
                               live-arenas)
            ;; candidate identities already owned by accepted arenas that
            ;; the new pointer cannot join
            owned (set (mapcat (fn [id]
                                 (keep (fn [[candidate-id candidate]]
                                         (when (:accepted? candidate)
                                           candidate-id))
                                       (get-in state [:arenas id :candidates])))
                               accepted-arenas))]
        (cond
          (empty? unresolved) (create-arena acc packet region time-us owned)
          (= 1 (count unresolved)) (capture-in-arena acc
                                                     packet
                                                     region
                                                     (first unresolved)
                                                     time-us
                                                     :join)
          :else
          (let [oldest (first unresolved)
                absorbed (rest unresolved)
                acc (merge-arenas acc oldest absorbed packet time-us)]
            (capture-in-arena acc packet region oldest time-us :join)))))))


(defn- create-arena
  [acc packet region time-us owned-ids]
  (let [state (:state acc)
        arena-id (:next-arena-id state)
        path (:interaction/path region)
        profile-id (:profile-id packet)
        profile (get-in state [:profiles profile-id])
        candidates (candidates-from-path path owned-ids profile)
        order (candidate-declaration-order path owned-ids)
        viewport (get-in state
                         [:coordinate-spaces (:coordinate-space-id packet)
                          :viewport])
        pointer-id (get-in packet [:pointer :id])
        subscriptions (snapshot-subscriptions state path (:node-id region))
        contacts {pointer-id {:position (get-in (final-sample packet)
                                                [:position]),
                              :down-time-us time-us}}
        arena {:arena-id arena-id,
               :status :open,
               :creation-input-seq (:runtime/seq packet),
               :winner nil,
               :hit-node-id (:node-id region),
               :path path,
               :frame-id (:frame-id packet),
               :coordinate-space-id (:coordinate-space-id packet),
               :profile-id profile-id,
               :profile profile,
               :viewport viewport,
               :generation-id (:generation-id packet),
               :candidates candidates,
               :candidate-order order,
               :pointer-ids #{pointer-id},
               :participant-ids #{pointer-id},
               :contacts contacts,
               :subscriptions subscriptions,
               :deferred-accepts {}}
        acc (-> acc
                (update :state assoc-in [:arenas arena-id] arena)
                (update :state assoc :next-arena-id (inc arena-id))
                (update :state
                        assoc-in
                        [:pointers pointer-id]
                        (pointer-record arena packet region profile-id)))
        ;; dormant candidates warn once, when installed
        acc (update acc
                    :diagnostics
                    into
                    (keep (fn [candidate]
                            (when (:dormant? candidate)
                              (fault/diagnostic
                                :dao.gui.event/unsupported-capability
                                :warning :dormant-recognizer
                                :node-id (:node-id candidate)
                                :recognizer/id (:recognizer-id candidate))))
                          (vals candidates)))
        acc (step-arena-candidates acc
                                   arena-id
                                   {:machine/input :pointer/down,
                                    :pointer/id pointer-id}
                                   packet
                                   time-us)
        acc (resolve-arena acc arena-id time-us packet)
        arena (current-arena acc arena-id)]
    (if (and arena (contains? (:pointers (:state acc)) pointer-id))
      (fan-raw acc
               (get-in (:state acc) [:pointers pointer-id])
               arena
               packet
               (:subscriptions arena))
      ;; arena ended immediately (fault); pointer record was removed with
      ;; it
      acc)))


(defn- capture-in-arena
  "Capture a new contact into an existing arena: contacts-changed machine
  input before the down tuple."
  [acc packet region arena-id time-us cause]
  (let [pointer-id (get-in packet [:pointer :id])
        state (:state acc)
        arena (get-in state [:arenas arena-id])
        contacts' (assoc (:contacts arena)
                         pointer-id {:position (get-in (final-sample packet)
                                                       [:position]),
                                     :down-time-us time-us})
        pointer-ids' (conj (:pointer-ids arena) pointer-id)
        acc (-> acc
                (update :state assoc-in [:arenas arena-id :contacts] contacts')
                (update :state
                        assoc-in
                        [:arenas arena-id :pointer-ids]
                        pointer-ids')
                (update :state
                        update-in
                        [:arenas arena-id :participant-ids]
                        (fn [ids] (into (or ids #{}) #{pointer-id})))
                (update :state
                        assoc-in
                        [:pointers pointer-id]
                        (pointer-record (assoc arena :arena-id arena-id)
                                        packet
                                        region
                                        (:profile-id arena))))
        trace-value {:event/kind :dao.gui.event/contacts-changed,
                     :arena-id arena-id,
                     :cause cause,
                     :added-pointer-ids #{pointer-id},
                     :removed-pointer-ids #{},
                     :pointer-ids pointer-ids',
                     :contact-count (count pointer-ids')}
        acc (update acc :traces conj trace-value)
        acc (step-arena-candidates acc
                                   arena-id
                                   {:machine/input :contacts/changed,
                                    :added-pointer-ids #{pointer-id},
                                    :removed-pointer-ids #{},
                                    :pointer-ids pointer-ids',
                                    :contact-count (count pointer-ids')}
                                   packet
                                   time-us)
        acc (step-arena-candidates acc
                                   arena-id
                                   {:machine/input :pointer/down,
                                    :pointer/id pointer-id}
                                   packet
                                   time-us)
        acc (resolve-arena acc arena-id time-us packet)
        arena (current-arena acc arena-id)]
    (if arena
      (fan-raw acc
               (get-in (:state acc) [:pointers pointer-id])
               arena
               packet
               (:subscriptions arena))
      acc)))


(defn- merge-arenas
  "Merge unresolved arenas into the oldest surviving arena."
  [acc surviving-id absorbed-ids packet time-us]
  (let [state (:state acc)
        surviving (get-in state [:arenas surviving-id])
        absorbed (map (fn [id] (get-in state [:arenas id])) absorbed-ids)
        merged-candidates
        (reduce (fn [candidates arena]
                  (reduce (fn [candidates candidate]
                            (if (contains? candidates
                                           (:candidate-id candidate))
                              candidates
                              (assoc candidates
                                     (:candidate-id candidate) candidate)))
                          candidates
                          (vals (:candidates arena))))
                (:candidates surviving)
                absorbed)
        ;; later duplicate candidate instances are dropped with an explicit
        ;; cancelled decision trace
        duplicate-ids (vec (mapcat (fn [arena]
                                     (keep (fn [candidate]
                                             (when (contains?
                                                     (:candidates surviving)
                                                     (:candidate-id candidate))
                                               (:candidate-id candidate)))
                                           (vals (:candidates arena))))
                                   absorbed))
        merged-pointers (reduce (fn [ids arena] (into ids (:pointer-ids arena)))
                                (:pointer-ids surviving)
                                absorbed)
        merged-participants (reduce (fn [ids arena]
                                      (into (or ids #{})
                                            (or (:participant-ids arena)
                                                (:pointer-ids arena))))
                                    (:participant-ids surviving)
                                    absorbed)
        merged-contacts (reduce (fn [contacts arena]
                                  (merge contacts (:contacts arena)))
                                (:contacts surviving)
                                absorbed)
        merged-subs (reduce (fn [subs arena]
                              (into (vec subs)
                                    (remove (fn [sub]
                                              (some
                                                (fn [existing]
                                                  (= (:subscription/id existing)
                                                     (:subscription/id sub)))
                                                subs)))
                                    (:subscriptions arena)))
                            (:subscriptions surviving)
                            absorbed)
        acc (update acc
                    :traces
                    conj
                    {:event/kind :dao.gui.event/arena-merged,
                     :arena-id surviving-id,
                     :merged-arena-ids (vec (sort absorbed-ids)),
                     :pointer-ids merged-pointers,
                     :candidate-ids (vec (map :candidate-id
                                              (vals merged-candidates)))})
        acc (update acc
                    :traces
                    into
                    (map (fn [duplicate-id]
                           {:event/kind :dao.gui.event/arena-decision,
                            :arena-id surviving-id,
                            :candidate-id duplicate-id,
                            :decision :cancelled,
                            :cause :duplicate-merge})
                         duplicate-ids))
        ;; absorbed arenas' timer records: invalidate records of duplicate
        ;; candidates, re-key records of moved ones
        acc
        (reduce
          (fn [acc arena]
            (let [arena-id (:arena-id arena)]
              (as-> acc a
                    ;; pointers retargeted
                    (reduce (fn [acc pointer-id]
                              (update acc
                                      :state
                                      assoc-in
                                      [:pointers pointer-id :arena-id]
                                      surviving-id))
                            a
                            (:pointer-ids arena))
                    ;; timers re-keyed or invalidated: only timers that
                    ;; belong to the absorbed arena move; every other
                    ;; timer in the runtime is untouched
                    (reduce
                      (fn [acc [tkey timer]]
                        (if (not= (:arena-id arena) (tkey 2))
                          acc
                          (if (= :scheduled (:status timer))
                            (let [moved-key (assoc tkey 2 surviving-id)]
                              (->
                                acc
                                (update :state update :timers dissoc tkey)
                                (update :state assoc-in [:timers moved-key] timer)
                                (update :effects
                                        conj
                                        (assoc (fault/timer-request moved-key
                                                                    :start)
                                               :deadline-us (:deadline-us timer)))))
                            (update acc :state update :timers dissoc tkey))))
                      a
                      (:timers (:state a)))
                    (update a :state update :arenas dissoc arena-id))))
          acc
          absorbed)
        acc
        (update
          acc
          :state
          (fn [state]
            (->
              state
              (assoc-in [:arenas surviving-id :candidates] merged-candidates)
              (assoc-in
                [:arenas surviving-id :candidate-order]
                (vec (concat (:candidate-order surviving)
                             (keep
                               (fn [id]
                                 (when-not (contains? (set (:candidate-order
                                                             surviving))
                                                      id)
                                   id))
                               (mapcat (fn [arena]
                                         (vec (filter
                                                (fn [id]
                                                  (contains?
                                                    (set (keys
                                                           merged-candidates))
                                                    id))
                                                (:candidate-order arena))))
                                       absorbed)))))
              (assoc-in [:arenas surviving-id :pointer-ids] merged-pointers)
              (assoc-in [:arenas surviving-id :participant-ids]
                        merged-participants)
              (assoc-in [:arenas surviving-id :contacts] merged-contacts)
              (assoc-in [:arenas surviving-id :subscriptions] merged-subs))))
        ;; contact-change input for the merged set, before the down tuple
        acc (update acc
                    :traces
                    conj
                    {:event/kind :dao.gui.event/contacts-changed,
                     :arena-id surviving-id,
                     :cause :merge,
                     :added-pointer-ids #{},
                     :removed-pointer-ids #{},
                     :pointer-ids merged-pointers,
                     :contact-count (count merged-pointers)})
        acc (step-arena-candidates acc
                                   surviving-id
                                   {:machine/input :contacts/changed,
                                    :added-pointer-ids #{},
                                    :removed-pointer-ids #{},
                                    :pointer-ids merged-pointers,
                                    :contact-count (count merged-pointers)}
                                   packet
                                   time-us)]
    acc))


(defn- process-down
  [acc packet time-us]
  (let [pointer-id (get-in packet [:pointer :id])
        state (:state acc)]
    (if (contains? (:pointers state) pointer-id)
      ;; duplicate down: cancel and remove the existing pointer, drop this
      ;; one
      (let [existing (get-in state [:pointers pointer-id])
            arena-id (:arena-id existing)
            acc
            (if (and (some? arena-id)
                     (contains? (:arenas (:state acc)) arena-id))
              (let [acc (step-one-candidate* acc
                                             arena-id
                                             pointer-id
                                             packet
                                             time-us)
                    ;; remove the contact and deliver the change
                    acc
                    (remove-contact acc arena-id pointer-id packet time-us)]
                acc)
              (update acc :state update :pointers dissoc pointer-id))
            acc (update acc :state update :pointers dissoc pointer-id)
            acc (update acc
                        :diagnostics
                        conj
                        (fault/diagnostic :dao.gui.event/duplicate-pointer-down
                                          :error :duplicate-pointer-down
                                          :pointer-id pointer-id))]
        acc)
      (let [active-frame (get-in (:state acc) [:geometry :active :frame-id])]
        (cond (nil? active-frame) (update acc
                                          :diagnostics
                                          conj
                                          (fault/diagnostic
                                            :dao.gui.event/no-active-frame
                                            :error :no-active-frame
                                            :frame-id (:frame-id packet)))
              (nil? (get-in (:state acc) [:profiles (:profile-id packet)]))
              (update acc
                      :diagnostics
                      conj
                      (fault/diagnostic :dao.gui.event/profile-mismatch
                                        :error :profile-mismatch
                                        :profile-id (:profile-id packet)))
              (not= (:profile-id packet) (get (:state acc) :latest-profile-id))
              (update acc
                      :diagnostics
                      conj
                      (fault/diagnostic :dao.gui.event/profile-mismatch
                                        :error :non-latest-profile
                                        :profile-id (:profile-id packet)))
              (> (:frame-id packet) active-frame)
              (update acc
                      :diagnostics
                      conj
                      (fault/diagnostic :dao.gui.event/future-frame-input
                                        :error :future-frame
                                        :frame-id (:frame-id packet)))
              (< (:frame-id packet) active-frame)
              (update acc
                      :diagnostics
                      conj
                      (fault/diagnostic :dao.gui.event/stale-frame-input
                                        :error :stale-frame
                                        :frame-id (:frame-id packet)))
              :else
              (let [sample (final-sample packet)
                    hit (hit-region (get-in (:state acc) [:geometry :regions])
                                    (get-in sample [:position :x])
                                    (get-in sample [:position :y]))]
                (cond (= ::ambiguous hit)
                      (update acc
                              :diagnostics
                              conj
                              (fault/diagnostic
                                :dao.gui.event/unsupported-region
                                :error :ambiguous-precedence
                                :frame-id (:frame-id packet)))
                      (nil? hit)
                      ;; uncaptured pointer record
                      (update acc
                              :state
                              assoc-in
                              [:pointers pointer-id]
                              {:pointer/id pointer-id,
                               :generation-id (:generation-id packet),
                               :frame-id (:frame-id packet),
                               :coordinate-space-id (:coordinate-space-id
                                                      packet),
                               :profile-id (:profile-id packet),
                               :hit-node-id nil,
                               :path nil,
                               :arena-id nil,
                               :position (get-in sample [:position])})
                      :else
                      (join-or-create-arena acc packet hit time-us))))))))


(defn- step-one-candidate*
  "Deliver a synthesized pointer cancel for one pointer before removal."
  [acc arena-id pointer-id packet time-us]
  (-> acc
      (step-arena-candidates arena-id
                             {:machine/input :pointer/cancel,
                              :pointer/id pointer-id}
                             packet
                             time-us)
      (as-> a (resolve-arena a arena-id time-us packet))))


(defn- remove-contact
  "Remove a contact after its up/cancel tuple and deliver the
  contacts-changed input to the remaining candidates."
  [acc arena-id pointer-id packet time-us]
  (let [arena (get-in (:state acc) [:arenas arena-id])]
    (if (nil? arena)
      acc
      (let [contacts' (dissoc (:contacts arena) pointer-id)
            pointer-ids' (disj (:pointer-ids arena) pointer-id)
            acc
            (->
              acc
              (update :state assoc-in [:arenas arena-id :contacts] contacts')
              (update :state
                      assoc-in
                      [:arenas arena-id :pointer-ids]
                      pointer-ids')
              (update :traces
                      conj
                      {:event/kind :dao.gui.event/contacts-changed,
                       :arena-id arena-id,
                       :cause :lift,
                       :added-pointer-ids #{},
                       :removed-pointer-ids #{pointer-id},
                       :pointer-ids pointer-ids',
                       :contact-count (count pointer-ids')})
              (step-arena-candidates arena-id
                                     {:machine/input :contacts/changed,
                                      :added-pointer-ids #{},
                                      :removed-pointer-ids #{pointer-id},
                                      :pointer-ids pointer-ids',
                                      :contact-count (count pointer-ids')}
                                     packet
                                     time-us)
              (as-> a (resolve-arena a arena-id time-us packet)))]
        acc))))


;; ---------------------------------------------------------------------------
;; Move, up, cancel, hover
;; ---------------------------------------------------------------------------

(defn- push-samples-into-windows
  "Per-sample window pushes against the temp contact set, for every
  candidate of the arena."
  [acc arena-id pointer-id packet]
  (let [arena (get-in (:state acc) [:arenas arena-id])
        window-us (or (get-in arena [:profile :thresholds :velocity/window-us])
                      100000)]
    (when arena
      (update acc
              :state
              (fn [state]
                (update-in state
                           [:arenas arena-id :candidates]
                           (fn [candidates]
                             (into {}
                                   (map (fn [[id candidate]]
                                          [id
                                           (assoc-window candidate
                                                         (push-window-entries
                                                           (window-of candidate)
                                                           (:contacts arena)
                                                           pointer-id
                                                           (:samples packet)
                                                           window-us))])
                                        candidates)))))))))


(defn- process-move
  [acc packet time-us]
  (let [pointer-id (get-in packet [:pointer :id])
        pointer (get-in (:state acc) [:pointers pointer-id])
        active-frame (get-in (:state acc) [:geometry :active :frame-id])]
    (cond
      (nil? pointer) (update acc
                             :diagnostics
                             conj
                             (fault/diagnostic
                               :dao.gui.event/orphan-pointer-packet
                               :error :orphan-pointer-packet
                               :pointer-id pointer-id))
      (> (:frame-id packet) (or active-frame (:frame-id pointer)))
      (update acc
              :diagnostics
              conj
              (fault/diagnostic :dao.gui.event/future-frame-input
                                :error :future-frame
                                :frame-id (:frame-id packet)))
      (nil? (:arena-id pointer))
      ;; uncaptured moves have no output
      (update acc
              :state
              assoc-in
              [:pointers pointer-id :position]
              (get-in (final-sample packet) [:position]))
      :else
      (let [arena-id (:arena-id pointer)
            arena-before (get-in (:state acc) [:arenas arena-id])
            subscriptions (when arena-before (:subscriptions arena-before))
            acc (or (push-samples-into-windows acc arena-id pointer-id packet)
                    acc)
            acc (if (nil? arena-before)
                  acc
                  (-> acc
                      ;; the actual sample becomes the stored contact
                      ;; position
                      (update :state
                              assoc-in
                              [:arenas arena-id :contacts pointer-id
                               :position]
                              (get-in (final-sample packet) [:position]))
                      (step-arena-candidates arena-id
                                             {:machine/input :pointer/move,
                                              :pointer/id pointer-id}
                                             packet
                                             time-us)
                      (resolve-arena arena-id time-us packet)))
            acc (if (contains? (:pointers (:state acc)) pointer-id)
                  (update acc
                          :state
                          assoc-in
                          [:pointers pointer-id :position]
                          (get-in (final-sample packet) [:position]))
                  acc)]
        (if (contains? (:pointers (:state acc)) pointer-id)
          (fan-raw acc
                   (get-in (:state acc) [:pointers pointer-id])
                   (get-in (:state acc) [:arenas arena-id])
                   packet
                   (or subscriptions []))
          acc)))))


(defn- process-up
  [acc packet time-us]
  (let [pointer-id (get-in packet [:pointer :id])
        pointer (get-in (:state acc) [:pointers pointer-id])
        active-frame (get-in (:state acc) [:geometry :active :frame-id])]
    (cond
      (nil? pointer) (update acc
                             :diagnostics
                             conj
                             (fault/diagnostic
                               :dao.gui.event/orphan-pointer-packet
                               :error :orphan-pointer-packet
                               :pointer-id pointer-id))
      (> (:frame-id packet) (or active-frame (:frame-id pointer)))
      (update acc
              :diagnostics
              conj
              (fault/diagnostic :dao.gui.event/future-frame-input
                                :error :future-frame
                                :frame-id (:frame-id packet)))
      :else
      (let [arena-id (:arena-id pointer)
            arena-before (when arena-id
                           (get-in (:state acc) [:arenas arena-id]))
            subscriptions (when arena-before (:subscriptions arena-before))
            acc (or (push-samples-into-windows acc arena-id pointer-id packet)
                    acc)
            acc (if (nil? arena-before)
                  acc
                  (-> acc
                      (update :state
                              assoc-in
                              [:arenas arena-id :contacts pointer-id
                               :position]
                              (get-in (final-sample packet) [:position]))
                      (step-arena-candidates arena-id
                                             {:machine/input :pointer/up,
                                              :pointer/id pointer-id}
                                             packet
                                             time-us)
                      (remove-contact arena-id pointer-id packet time-us)))]
        (-> acc
            (update :state update :pointers dissoc pointer-id)
            (fan-raw pointer arena-before packet (or subscriptions [])))))))


(defn- process-cancel
  [acc packet time-us]
  (let [pointer-id (get-in packet [:pointer :id])
        pointer (get-in (:state acc) [:pointers pointer-id])]
    (cond
      (nil? pointer) (update acc
                             :diagnostics
                             conj
                             (fault/diagnostic
                               :dao.gui.event/orphan-pointer-packet
                               :error :orphan-pointer-packet
                               :pointer-id pointer-id))
      :else
      (let [arena-id (:arena-id pointer)
            acc
            (if (nil? arena-id)
              acc
              (let [arena (get-in (:state acc) [:arenas arena-id])]
                (if (nil? arena)
                  acc
                  (->
                    acc
                    (step-arena-candidates arena-id
                                           {:machine/input :pointer/cancel,
                                            :pointer/id pointer-id}
                                           packet
                                           time-us)
                    (remove-contact arena-id pointer-id packet time-us)))))
            arena (when arena-id (get-in (:state acc) [:arenas arena-id]))]
        (-> acc
            (update :state update :pointers dissoc pointer-id)
            (fan-raw pointer
                     arena
                     packet
                     (if arena (:subscriptions arena) [])))))))


(defn- process-hover
  [acc packet _time-us]
  (let [pointer-id (get-in packet [:pointer :id])
        active-contact? (contains? (:pointers (:state acc)) pointer-id)
        sample (final-sample packet)]
    (cond active-contact? (update acc
                                  :diagnostics
                                  conj
                                  (fault/diagnostic
                                    :dao.gui.event/duplicate-pointer-down
                                    :error :hover-for-active-contact
                                    :pointer-id pointer-id))
          :else (let [hit (hit-region (get-in (:state acc) [:geometry :regions])
                                      (get-in sample [:position :x])
                                      (get-in sample [:position :y]))]
                  (if (or (nil? hit) (= ::ambiguous hit))
                    acc
                    (let [event {:event/kind :pointer,
                                 :phase :hover,
                                 :generation-id (:generation-id packet),
                                 :frame-id (:frame-id packet),
                                 :coordinate-space-id (:coordinate-space-id
                                                        packet),
                                 :profile-id (:profile-id packet),
                                 :input-seq (:input-seq packet),
                                 :pointer (:pointer packet),
                                 :samples (:samples packet),
                                 :node-id (:node-id hit),
                                 :arena-id nil,
                                 :arena/status nil,
                                 :origin-frame-id nil,
                                 :target-path (vec (map :node-id
                                                        (:interaction/path hit)))}
                          subscriptions
                          (filter (fn [sub]
                                    (and (= :pointer (:event-kind sub))
                                         (:raw? sub)
                                         (= (:node-id hit) (:node-id sub))))
                                  (map (fn [id]
                                         (get-in (:state acc) [:subscriptions id]))
                                       (:subscription-order (:state acc))))]
                      (acc-event acc subscriptions event)))))))


;; ---------------------------------------------------------------------------
;; Entry points
;; ---------------------------------------------------------------------------

(defn step-pointer
  [state runtime-input]
  (let [packet (:runtime/value runtime-input)
        time-us (:runtime/time-us runtime-input)
        validated (validate-packet (acc-new state) packet)]
    (if (:drop validated)
      {:state state, :outputs (vec (filter some? (:drop validated)))}
      (let [acc (case (:phase packet)
                  :down (process-down (:acc validated) packet time-us)
                  :move (process-move (:acc validated) packet time-us)
                  :up (process-up (:acc validated) packet time-us)
                  :cancel (process-cancel (:acc validated) packet time-us)
                  :hover (process-hover (:acc validated) packet time-us)
                  (:acc validated))]
        {:state (:state acc), :outputs (acc-outputs acc)}))))


(defn step-timer
  [state runtime-input]
  (let [value (:runtime/value runtime-input)
        time-us (:runtime/time-us runtime-input)
        tkey (fault/timer-key (:generation-id value)
                              (:coordinate-space-id value)
                              (:arena-id value)
                              (:recognizer/id value)
                              (:timer-id value)
                              (:timer-seq value))
        timer (get-in state [:timers tkey])]
    (cond (not (contains? state :timers)) {:state state, :outputs []}
          (or (nil? timer)
              (not= :scheduled (:status timer))
              (not= (:generation-id value) (:generation-id state))
              (not= (:coordinate-space-id value)
                    (:active-coordinate-space-id state)))
          {:state state,
           :outputs [(fault/diagnostic :dao.gui.event/late-timer
                                       :warning :late-timer
                                       :arena-id (:arena-id value)
                                       :recognizer/id (:recognizer/id
                                                        value))]}
          :else (let [acc (acc-new
                            (assoc-in state [:timers tkey :status] :fired))
                      arena-id (:arena-id value)
                      acc (step-arena-candidates acc
                                                 arena-id
                                                 {:machine/input :timer/fired,
                                                  :timer/id (:timer-id value),
                                                  :timer-seq (:timer-seq value)}
                                                 value
                                                 time-us)
                      acc (update acc
                                  :state
                                  update-in
                                  [:arenas arena-id :candidates]
                                  (fn [candidates]
                                    (into {}
                                          (map (fn [[id candidate]]
                                                 ;; only the candidate the
                                                 ;; timer belongs to loses
                                                 ;; its active timer
                                                 (if (= (:recognizer/id value)
                                                        (second id))
                                                   [id
                                                    (update candidate
                                                            :active-timers
                                                            dissoc
                                                            (:timer-id value))]
                                                   [id candidate])))
                                          candidates)))
                      acc (resolve-arena acc arena-id time-us value)]
                  {:state (:state acc), :outputs (acc-outputs acc)}))))
