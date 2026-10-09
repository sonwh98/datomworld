(ns dao.stream.remote-route
  "Finite, caller-composed routes and reader-local discovery facts.
   Peer names are claims. No discovery observation authenticates a peer.
   All work and expiry require a supplied driver tick."
  (:require [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.remote-pair :as pair]))


(def production-bounds
  {:max-depth 4 :max-nodes 64 :max-descriptor-bytes 16384
   :max-candidates 128 :max-attempts 8 :work-budget 4096
   :establishment-ms 60000 :freshness-ms 30000
   :keepalive-ms 10000 :response-ms 30000 :lag-ms 1000 :session-idle-ms 60000})


(defn preflight
  "Bound the descriptor tree before acquiring handles. encoded-size is the
   caller's negotiated codec byte measurement, never a character estimate.
   Returns ordered dependency descriptors, deepest first, or a refusal."
  [cd bounds encoded-size]
  (try
    (let [b (merge production-bounds bounds)]
      (if-not (and (fn? encoded-size)
                   (every? #(and (integer? %) (pos? %)) (vals b))
                   (< (+ (:keepalive-ms b) (:response-ms b) (:lag-ms b)) (:session-idle-ms b))
                   (or (nil? (:nat-idle-ms b)) (< (+ (:keepalive-ms b) (:lag-ms b)) (:nat-idle-ms b))))
        {:status :refused :reason ::invalid-profile}
        (loop [todo [[cd 0 #{}]] nodes 0 dependencies [] bases #{}]
          (if-some [[d depth active] (peek todo)]
            (cond
              (>= nodes (:max-nodes b)) {:status :refused :reason ::nodes}
              (not (stream/valid-descriptor? d)) {:status :refused :reason ::descriptor}
              (contains? active d) {:status :refused :reason ::cycle}
              (> (encoded-size d) (:max-descriptor-bytes b)) {:status :refused :reason ::bytes}
              (= :dao.stream/pair (:dao.stream/type d))
              (if (>= depth (:max-depth b))
                {:status :refused :reason ::depth}
                (let [ends (mapv #(get d %) [:dao.stream.remote/in :dao.stream.remote/out])]
                  (if-not (every? #(and (stream/valid-descriptor? %)
                                        (= :dao.stream/remote (:dao.stream/type %))) ends)
                    {:status :refused :reason ::descriptor}
                    (recur (into (pop todo)
                                 (mapv #(vector % (inc depth) (conj active d)) ends))
                           (inc nodes) (conj dependencies d) bases))))
              (= :dao.stream/remote (:dao.stream/type d))
              (recur (conj (pop todo) [(:dao.stream/channel d) depth (conj active d)])
                     (inc nodes) dependencies bases)
              :else (recur (pop todo) (inc nodes) dependencies (conj bases d)))
            {:status :ok :dependencies (vec (distinct (reverse dependencies)))
             :nodes nodes :bases bases :bounds b}))))
    (catch #?(:cljd dynamic :clj Throwable :cljs :default) _error
      {:status :refused :reason ::codec})))


(defn discovery
  "Local bounded observation state over explicitly chosen boards."
  [bounds]
  {:bounds (merge production-bounds bounds) :candidates [] :ready #{} :ready-order [] :gaps 0})


(defn observe
  "Fold one board fact at local now, retaining board and incarnation provenance.
   A gap invalidates discovery/readiness certainty, never confirmed routes."
  [state board result now]
  (case (:dao.stream/outcome result)
    :dao.stream/gap (-> state (assoc :candidates [] :ready #{} :ready-order []) (update :gaps inc))
    :dao.stream/ok
    (let [v (:dao.stream/value result)]
      (cond
        (and (map? v) (contains? v :meet/lapsed))
        (let [lease-id (:meet/lapsed v)
              records (filterv #(not= lease-id (second %)) (:ready-order state))]
          (assoc state :ready (set records) :ready-order records
                 :candidates (filterv #(not= lease-id (get-in % [:fact :dao.lease/lease]))
                                      (:candidates state))))
        (and (map? v) (contains? v :meet/seen))
        (let [record {:peer (:meet/seen v) :address (:meet/reflexive v)
                      :board board :incarnation (:meet/incarnation v)
                      :observed now :fact v :channel (:meet/channel v)}
              key #(select-keys % [:peer :board :incarnation])
              candidates (filterv #(not= (key record) (key %)) (:candidates state))]
          (assoc state :candidates
                 (vec (take-last (get-in state [:bounds :max-candidates])
                                 (conj candidates record)))))
        (and (map? v) (contains? v :meet/ready)
             (contains? #{:in :out} (:meet/side v))
             (some? (:meet/request v)) (some? (:meet/incarnation v)))
        (let [record [board (:meet/ready v) (:meet/request v) (:meet/incarnation v) (:meet/side v)]
              records (vec (take-last (* 2 (get-in state [:bounds :max-candidates]))
                                      (conj (filterv #(not= record %) (:ready-order state)) record)))]
          (assoc state :ready (set records) :ready-order records))
        :else state))
    state))


(defn candidates
  "Caller board order, then observation order. Alias substitution stays local."
  [state boards aliases target now]
  (let [peer (get aliases target target)]
    (vec (mapcat (fn [board]
                   (filter #(and (= board (:board %)) (= peer (:peer %))
                                 (< (- now (:observed %)) (get-in state [:bounds :freshness-ms])))
                           (:candidates state))) boards))))


(defn ready?
  [state board lease-id request-id incarnation]
  (every? #(contains? (:ready state) [board lease-id request-id incarnation %]) [:in :out]))


(defn punch
  "Portable confirmation of an ordinary descriptor probe. Wrong source or id
   provides no evidence. Production UDP hosts are not supplied by this module."
  [state observation now]
  (cond
    (>= now (:deadline state)) (assoc state :status :relay)
    (and (= (:source state) (:source observation))
         (= (:request state) (get-in observation [:value :dao.stream.remote/id]))
         (map? (:value observation))
         (= (:identity state) (get-in observation [:value :dao.stream/identity]))
         (or (contains? (:value observation) :dao.stream/outcome)
             (contains? #{:dao.stream.remote/not-found :dao.stream.remote/no-surface
                          :dao.stream.remote/oversize}
                        (get-in observation [:value :dao.stream.remote/error]))))
    (assoc state :status :confirmed)
    :else state))


(defn- charged
  [h allowance]
  (let [take! (fn [] (when (pos? @allowance) (swap! allowance dec) true))]
    (reify
      stream/IDaoStreamDescriptor
      (descriptor
        [_]
        (if (take!) (stream/descriptor h)
            {:dao.stream/outcome :dao.stream/transport-error :dao.stream/retry? true}))


      stream/IDaoStreamReader

      (cursor
        [_ anchor]
        (if (take!) (stream/cursor h anchor)
            {:dao.stream/outcome :dao.stream/transport-error :dao.stream/retry? true}))

      (next
        [_ c]
        (if (take!) (stream/next h c) {:dao.stream/outcome :dao.stream/blocked}))


      stream/IDaoStreamWriter

      (append!
        [_ v]
        (if (take!) (stream/append! h v) {:dao.stream/outcome :dao.stream/full}))


      stream/IDaoStreamClosable

      (close! [_] (when (stream/closable? h) (stream/close! h))))))


(defn dial
  "Compose a finite pair descriptor over explicit base channel ends. All base
   media remain caller-owned. Both peers install table/name snapshots and step.
   encoded-size must measure actual negotiated descriptor bytes. Readiness is
   supplied as explicit :ready? evidence by the board interpreter; attach alone
   cannot confirm. :identity selects an exact source, :name resolves at the end."
  [{:keys [route channels bounds encoded-size table names now identity ready?] n :name :as opts}]
  (let [opts (merge {:dao.stream.remote/request-prefix (str (random-uuid))
                     :dao.stream.remote/budget 8
                     :dao.stream.remote/drain-budget 64
                     :dao.stream.remote/max-outstanding 64
                     :dao.stream.remote/max-filed 128
                     :dao.stream.remote/give-up-after 30000
                     :dao.stream.remote.pair/max-entries 64
                     :dao.stream.remote.pair/capacity 64
                     :dao.stream.remote.pair/projection-budget 64} opts)
        checked (preflight route bounds encoded-size)]
    (cond
      (not= :ok (:status checked)) checked
      (not (and (some? now) (map? channels) (or (some? n) (some? identity))))
      {:status :refused :reason ::invalid-target}
      (not (every? #(contains? channels %) (:bases checked)))
      {:status :refused :reason :no-route}
      (not (every? (fn [[_ end]]
                     (and (stream/reader? (:reader end))
                          (stream/writer? (:writer end)))) channels))
      {:status :refused :reason ::channels}
      :else
      (let [b (:bounds checked)
            allowance (or (:allowance opts) (atom (:work-budget b)))
            base (remote/links
                   (merge (select-keys opts pair/policy-keys)
                          {:dao.stream.remote/channels
                           (into {} (map (fn [[cd end]]
                                           [cd {:reader (charged (:reader end) allowance)
                                                :writer (charged (:writer end) allowance)}]) channels))}))
            mirror-cursors (into {} (map (fn [[cd end]]
                                           [cd (:dao.stream/cursor
                                                 (stream/cursor (:reader end) stream/anchor-newest))]) channels))
            driver-now (atom now)
            assembly (atom nil)
            attach! (fn [d]
                      (let [link (if (= :dao.stream/pair (get-in d [:dao.stream/channel :dao.stream/type]))
                                   @assembly base)]
                        ((:step link) (:dao.stream/channel d) @driver-now)
                        (let [r ((:attach link) d)]
                          (if (= :dao.stream/ok (:dao.stream/outcome r))
                            (update r :dao.stream/handle #(charged % allowance))
                            r))))
            pairs (pair/links (assoc opts :dao.stream.remote.pair/attach! attach!))]
        (reset! assembly pairs)
        ;; Acquire dependency projections before publishing any readiness.
        (when-some [cd (first (:dependencies checked))]
          ((:open! pairs) cd now))
        {:route-state true :status :establishing :route route :pairs pairs :base base
         :base-channels (vec (keys channels)) :ends channels :driver-now driver-now
         :dependencies (vec (take 1 (:dependencies checked)))
         :pending-dependencies (vec (rest (:dependencies checked)))
         :allowance allowance :shared-allowance? (some? (:allowance opts)) :bounds b
         :table (or table {}) :names names
         :mirror-table (into {} (map (fn [[id e]] [id (update e :handle #(charged % allowance))]) table))
         :mirror-cursors mirror-cursors :position 0
         :name n :identity identity :handle nil :handles {} :ready? (boolean ready?)
         :since now :deadline (+ now (:establishment-ms b)) :opened? false
         :holders (vec (:holders opts))
         :provenance (:provenance opts)}))))


(defn close!
  "Release pair-owned reflections in reverse dependency order, once."
  [d]
  (doseq [h (:holders d)]
    (when-some [writer (get-in h [:composition :writer :handle])]
      (try
        (let [stopped (lease/stop (get-in h [:composition :holder]))]
          (stream/append! writer (:release stopped)))
        (catch #?(:cljd dynamic :clj Throwable :cljs :default) _error nil))
      (when (stream/closable? writer)
        (try (stream/close! writer)
             (catch #?(:cljd dynamic :clj Throwable :cljs :default) _error nil)))))
  (when (:pairs d)
    (doseq [cd (reverse (:dependencies d))] ((:release! (:pairs d)) cd)))
  (when (:base d)
    (doseq [cd (:base-channels d)] ((:release! (:base d)) cd)))
  (when-some [h (:handle d)]
    (when (stream/closable? h)
      (try (stream/close! h)
           (catch #?(:cljd dynamic :clj Throwable :cljs :default) _error nil))))
  (assoc d :status :closed :holders []))


(defn holder
  "A caller-assembled dao.lease holder with dedicated source outcome media.
   The grant must have been observed through the composition's fact medium.
   Outbound acceptance never advances its bound. Only one renewal is pending."
  [composition events renewal-identity]
  (when-not (and (get-in composition [:holder :grant])
                 (stream/reader? (:handle events)) (contains? events :cursor)
                 (some? renewal-identity))
    (throw (ex-info "route holder requires observed grant and source outcome media" {:refused :holder})))
  {:composition composition :events events :renewal-identity renewal-identity
   :reading nil :pending nil :status :holding})


(defn holder-step
  "One bounded holder visit after driver-deposited ticks. Returned state owns
   its cursors. Source-confirmed outcomes use the pre-send reading; a current
   bound is latched before old evidence can extend activity."
  [state allowance]
  (if-not (= :holding (:status state)) state
          (let [composition (:composition state)
                ticks (:tick composition)
                state (loop [s state remaining 64]
                        (if-not (and (pos? remaining) (pos? @allowance)) s
                                (let [r (stream/next (charged (:handle ticks) allowance)
                                                     (get-in s [:composition :tick :cursor]))]
                                  (case (:dao.stream/outcome r)
                                    :dao.stream/ok
                                    (recur (-> s (assoc :reading (get-in r [:dao.stream/value :dao.lease/reading]))
                                               (assoc-in [:composition :tick :cursor] (:dao.stream/cursor r))) (dec remaining))
                                    :dao.stream/gap
                                    (recur (assoc-in s [:composition :tick :cursor] (:dao.stream/cursor r)) (dec remaining))
                                    s))))
                reading (:reading state)
                lease-holder (get-in state [:composition :holder])]
            (cond
              (nil? reading) state
              (lease/at-bound? lease-holder reading)
              (-> state (assoc :status :lost) (assoc-in [:composition :holder :bound-reached?] true))
              :else
              (let [state (loop [s state remaining 64]
                            (if-not (and (pos? remaining) (pos? @allowance)) s
                                    (let [r (stream/next (charged (get-in s [:events :handle]) allowance)
                                                         (get-in s [:events :cursor]))]
                                      (case (:dao.stream/outcome r)
                                        :dao.stream/gap (assoc s :status :lost :pending nil)
                                        :dao.stream/ok
                                        (let [v (:dao.stream/value r)
                                              s (assoc-in s [:events :cursor] (:dao.stream/cursor r))
                                              unknown? (= :dao.stream.remote/append-unknown (:dao.stream.remote/event v))
                                              outcome? (and (= (:renewal-identity s) (:dao.stream/identity v))
                                                            (not (contains? v :dao.stream/descriptor)))]
                                          (recur (if (and (:pending s) (or unknown? outcome?))
                                                   (-> s (update-in [:composition :holder] lease/observe-renewal (:pending s)
                                                                    (if (or unknown? (:dao.stream.remote/error v))
                                                                      {:dao.stream/outcome :dao.stream/transport-error} v))
                                                       (assoc :pending nil)) s)
                                                 (dec remaining)))
                                        s))))]
                (if (and (= :holding (:status state)) (nil? (:pending state))
                         (lease/due-to-renew? (get-in state [:composition :holder]) reading)
                         (pos? @allowance))
                  (let [lease-id (get-in state [:composition :holder :grant :dao.lease/lease])
                        r (stream/append! (charged (get-in state [:composition :writer :handle]) allowance)
                                          (lease/renewal lease-id))]
                    (if (= :dao.stream/ok (:dao.stream/outcome r))
                      (assoc state :pending reading)
                      state))
                  state))))))


(defn- step-route
  "One globally charged, bounded dependency sweep. Source outcomes and cursors
   are untouched. No append is reissued by route recovery."
  [d now]
  (if-not (contains? #{:establishing :ready :attached} (:status d)) d
          (if (>= now (:deadline d))
            (assoc (close! d) :status :lost :cause :expired)
            (let [allowance (:allowance d)
                  _ (reset! (:driver-now d) now)
                  before @allowance
                  _ (when-not (:shared-allowance? d) (reset! allowance (get-in d [:bounds :work-budget])))
                  d (reduce
                      (fn [state cd]
                        (let [r ((:step (:base d)) cd now)
                              end (get (:ends d) cd)
                              c (or (get-in state [:mirror-cursors cd])
                                    (:dao.stream/cursor (stream/cursor (:reader end) stream/anchor-newest)))]
                          (if (:dao.stream.remote/channel-gone? r)
                            (assoc state :dependency-lost cd)
                            (assoc-in state [:mirror-cursors cd]
                                      (remote/mirror-step (:mirror-table state) (:names state)
                                                          (charged (:reader end) allowance) c
                                                          (charged (:writer end) allowance)
                                                          {:dao.stream.remote/mirror-budget 64
                                                           :dao.stream.remote/chase-budget 8})))))
                      d (:base-channels d))
                  d (if (and (seq (:pending-dependencies d))
                             (every? #(:initialized? ((:channel-end (:pairs d)) %)) (:dependencies d)))
                      (let [cd (first (:pending-dependencies d))
                            r ((:open! (:pairs d)) cd now)]
                        (if (= :dao.stream/ok (:dao.stream/outcome r))
                          (-> d (update :dependencies conj cd)
                              (update :pending-dependencies #(vec (rest %))))
                          (assoc d :dependency-lost cd)))
                      d)
                  d (reduce
                      (fn [state cd]
                        (if-not (pos? @allowance) state
                                (let [r ((:step (:pairs d)) cd now)
                                      end ((:channel-end (:pairs d)) cd)]
                                  (if (or (:dao.stream.remote/channel-gone? r) (nil? end))
                                    (assoc state :dependency-lost cd)
                                    (if-not (:initialized? end) state
                                            (let [c (or (get-in state [:mirror-cursors cd])
                                                        (:dao.stream/cursor (stream/cursor (:reader end) stream/anchor-oldest)))
                                                  next-c (remote/mirror-step (:mirror-table state) (:names state)
                                                                             (charged (:reader end) allowance) c (charged (:writer end) allowance)
                                                                             {:dao.stream.remote/mirror-budget 64 :dao.stream.remote/chase-budget 8})]
                                              (assoc-in state [:mirror-cursors cd] next-c)))))))
                      d (:dependencies d))
                  d (if (:dependency-lost d) d
                        (assoc d :holders (mapv #(holder-step % allowance) (:holders d))))
                  d (cond-> d (some #(= :lost (:status %)) (:holders d)) (assoc :dependency-lost :lease))
                  d (assoc d :work-used (- (if (:shared-allowance? d) before (get-in d [:bounds :work-budget])) @allowance))]
              (cond
                (:dependency-lost d) (assoc (close! d) :status :lost :cause :dropped)
                (or (not (:ready? d)) (seq (:pending-dependencies d))
                    (not (every? #(:initialized? ((:channel-end (:pairs d)) %)) (:dependencies d)))) d
                (nil? (:handle d))
                (let [link (if (= :dao.stream/pair (:dao.stream/type (:route d))) (:pairs d) (:base d))
                      _ ((:step link) (:route d) now)
                      resolved (if (:identity d)
                                 {:dao.stream/outcome :dao.stream/ok
                                  :dao.stream/descriptor {:dao.stream/type :dao.stream/remote
                                                          :dao.stream/channel (:route d)
                                                          :dao.stream/identity (:identity d)}}
                                 ((:resolve link) (:route d) (:name d)))]
                  (if (= :dao.stream/ok (:dao.stream/outcome resolved))
                    (let [descriptor (:dao.stream/descriptor resolved)
                          r ((:attach link) descriptor)]
                      (if (= :dao.stream/ok (:dao.stream/outcome r))
                        (assoc d :handle (:dao.stream/handle r) :identity (:dao.stream/identity descriptor)
                               :handles {(:dao.stream/identity descriptor) (:dao.stream/handle r)})
                        (assoc (close! d) :status :lost :outcome r)))
                    (if (:dao.stream/retry? resolved) d
                        (assoc (close! d) :status :lost :outcome resolved))))
                :else
                (let [h (:handle d)
                      _ (stream/descriptor h)
                      evidence (remote/confirmation h)
                      confirmed (:responses evidence 0)
                      fresh? (> confirmed (:confirmations d 0))
                      d (if fresh?
                          (assoc d :status :attached :opened? true :confirmations confirmed
                                 :deadline (+ now (get-in d [:bounds :keepalive-ms])
                                              (get-in d [:bounds :response-ms]))
                                 :next-keepalive (+ now (get-in d [:bounds :keepalive-ms])))
                          d)]
                  (cond
                    (and fresh? (not= :dao.stream/ok (:dao.stream/outcome (:probe-outcome evidence))))
                    (assoc (close! d) :status :lost :outcome (:probe-outcome evidence))
                    (and (:opened? d) (>= now (:next-keepalive d)))
                    (do (remote/probe! h)
                        (assoc d :next-keepalive (+ now (get-in d [:bounds :keepalive-ms]))))
                    :else d)))))))


(defn step
  "Advance one route at supplied now and report all charged work, including
   establishment and keep-alive probes issued after mirror visits."
  [d now]
  (let [before (when (:allowance d) @(:allowance d))
        result (step-route d now)]
    (if-not (:allowance d) result
            (assoc result :work-used
                   (- (if (:shared-allowance? d) before (get-in d [:bounds :work-budget]))
                      @(:allowance d))))))


(defn update-tables
  "Retain explicit snapshots and charge their source operations to this route."
  [d table names]
  (assoc d :table table :names names
         :mirror-table (into {} (map (fn [[id e]]
                                       [id (update e :handle #(charged % (:allowance d)))]) table))))


(defn resolution
  "Bounded local peer selection. Bootstrap boards/routes are explicitly chosen;
   a peer hash supplies no location. Returned actions are data for the owning
   driver, not executed callbacks. Exact source identity remains fixed on every
   attempt; application append intents are never retained or replayed here."
  [{:keys [target aliases boards routes now bounds identity request incarnation peer] n :name}]
  (let [b (merge production-bounds bounds)
        target (get aliases target target)]
    (if (and (empty? boards) (empty? routes))
      {:status :refused :reason :no-route :target target :actions []}
      {:status :discovering :target target :boards (vec boards)
       :known (vec routes) :observations (discovery b) :attempted []
       :attempts 0 :bounds b :deadline (+ now (:establishment-ms b))
       :name n :identity identity :request request :incarnation incarnation
       :peer peer :next-announcement now :actions []})))


(defn resolution-step
  "Fold a bounded supplied batch, expire before admission, and select at most
   one candidate per visit. A driver supplies :failed/:confirmed attempt facts.
   Observation time is local. Board attachment/cursor acquisition precedes this
   interpreter; announcements are emitted only as explicit action data."
  [state observations attempt-result now]
  (if-not (contains? #{:discovering :establishing} (:status state)) state
          (if (>= now (:deadline state))
            (assoc state :status :lost :reason :deadline :actions [])
            (let [observed (reduce (fn [s {:keys [board result]}]
                                     (if (some #{board} (:boards state))
                                       (observe s board result now) s))
                                   (:observations state)
                                   (take (get-in state [:bounds :work-budget]) observations))
                  gapped? (some (fn [{:keys [board result]}]
                                  (and (= :dao.stream/gap (:dao.stream/outcome result))
                                       (= board (get-in state [:current :board])))) observations)
                  state (assoc state :observations observed :actions [])
                  state (if gapped?
                          (-> state (assoc :status :discovering :current nil)
                              (update :actions conj {:op :release :candidate (:current state) :reason :board-gap}))
                          state)
                  state (case (when-not gapped? (:status attempt-result))
                          :confirmed (assoc state :status :ready :provenance (:current state))
                          :failed (assoc state :status :discovering :current nil)
                          state)
                  state (if (and (= :discovering (:status state))
                                 (>= now (:next-announcement state)))
                          (-> state
                              (assoc :next-announcement (+ now (get-in state [:bounds :keepalive-ms])))
                              (update :actions conj {:op :announce :boards (:boards state)
                                                     :value {:meet/here (:peer state)
                                                             :meet/incarnation (:incarnation state)}}))
                          state)
                  options (concat (filter #(= (:target state) (:peer %)) (:known state))
                                  (candidates observed (:boards state) {} (:target state) now))
                  candidate (first (filter #(and (stream/valid-descriptor? (:channel %))
                                                 (not (some #{%} (:attempted state)))) options))]
              (cond
                (not= :discovering (:status state)) state
                (>= (:attempts state) (get-in state [:bounds :max-attempts]))
                (assoc state :status :lost :reason :attempts :actions [])
                candidate
                (-> state (assoc :status :establishing :current candidate)
                    (update :attempts inc) (update :attempted conj candidate)
                    (update :actions conj {:op :establish :candidate candidate
                                           :name (:name state) :identity (:identity state)
                                           :deadline (:deadline state)}))
                :else state)))))


(defn acknowledge-ready
  "Install the two correlated board readiness observations only after all owned
   input bindings have initialized. Loss of a board fact cannot confirm a route."
  [d observations board lease-id request incarnation]
  (if (and (ready? observations board lease-id request incarnation)
           (empty? (:pending-dependencies d))
           (every? #(:initialized? ((:channel-end (:pairs d)) %)) (:dependencies d)))
    (assoc d :ready? true :readiness-provenance
           {:board board :lease lease-id :request request :incarnation incarnation})
    d))


(defn driver
  "Compose up to max-routes preflighted plans sharing one work allowance.
   Every route owns its pair references; supplied base media stay caller-owned."
  [plans bounds]
  (let [b (merge production-bounds {:max-routes 32} bounds)]
    (cond
      (not (and (vector? plans) (integer? (:max-routes b)) (pos? (:max-routes b))
                (integer? (:work-budget b)) (pos? (:work-budget b))))
      {:status :refused :reason ::invalid-profile}
      (> (count plans) (:max-routes b)) {:status :refused :reason ::routes}
      (let [connections (vec (mapcat #(vals (:channels %)) plans))]
        (not= (count connections) (count (distinct connections))))
      {:status :refused :reason ::shared-base-owner}
      :else
      (let [checks (mapv #(preflight (:route %) (:bounds %) (:encoded-size %)) plans)]
        (if-some [refusal (first (filter #(not= :ok (:status %)) checks))]
          refusal
          (let [allowance (atom (:work-budget b))]
            {:status :running :bounds b :allowance allowance :position 0
             :routes (mapv #(dial (assoc % :allowance allowance)) plans)}))))))


(defn driver-step
  "Persistent round-robin scheduling, one visit per route, one global allowance.
   A route exception retires its owned dependencies and cannot stop siblings."
  [state now]
  (if-not (= :running (:status state)) state
          (let [routes (:routes state)
                n (count routes)
                _ (reset! (:allowance state) (get-in state [:bounds :work-budget]))
                order (mapv #(mod (+ (:position state) %) n) (range n))
                routes (reduce (fn [current index]
                                 (let [d (get current index)
                                       stepped (try (step d now)
                                                    (catch #?(:cljd dynamic :clj Throwable :cljs :default) _error
                                                      (assoc (close! d) :status :lost :cause :dropped
                                                             :failure {:stage :route-step :exception? true})))]
                                   (assoc current index stepped))) routes order)]
            (assoc state :routes routes :position (if (pos? n) (mod (inc (:position state)) n) 0)
                   :work-used (- (get-in state [:bounds :work-budget]) @(:allowance state))))))
