(ns dao.star.driver
  "The dao.star Mode 1 driver (docs/design/dao.star.md, Flow and Liveness).

   Per session the driver parks a real `yin.vm` continuation through
   `dao.await`: one VM per session, whose program is pure sequential logic
   reading its input medium (`in`) and emitting model values on its view medium
   (`out`).  The driver does all the I/O.  It reads the session's routed
   inputs with its own cursor, steps the parked continuation, drains the view
   medium for the new model, mints the successor occurrence, appends the
   consumption fact, and only after that append answers `ok` projects the
   model to the egress medium.  The program never appends an effect.

   Projection is the composition's pure `(project-fn occurrence-id model)`,
   answering the complete SSE wire text; the root begin page is a separate
   `(begin-fn lineage)`.  Subscribe and stale requests re-project the stored
   latest `{:occurrence O :model m}` without resuming the VM.

   The driver observes its grants before it consumes or projects, renews the
   lineage lease on each consumed input and on its tick while a subscription is
   open, stops at its bound (an input arriving then is handled as begin), and
   releases on halt."
  (:require [dao.await :as await]
            [dao.star.facts :as facts]
            [dao.star.lease :as lease]
            [dao.star.signals :as signals]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]))


(defn- ok?
  [r]
  (= :dao.stream/ok (:dao.stream/outcome r)))


(defn- new-ring
  [capacity]
  (let [r (ring/create! {:dao.stream/type ring/transport-type
                         ring/capacity-key capacity})]
    (when-not (ok? r) (throw (ex-info "cannot create a session medium" {:outcome r})))
    (:dao.stream/handle r)))


(defn make-driver
  "Assemble the driver.  Config:
     :facts :egress :begin     media handles (the session-facts writer, the
                               egress writer, the begin handler's medium)
     :tick                     the drivers' tick stream (handle)
     :lineage-lease            the lineage-lease medium (handle, read for grants)
     :exchange-lease           the exchange-lease medium (handle, read for grants)
     :program                  a `dao.await` process reading `in`, writing `out`
     :project-fn :begin-fn     the composition's projections
     :units :lineage-renewal-interval :exchange-renewal-interval
     :session-capacity         declared capacity of each session medium
     :facts-declaration        the session-facts medium's declaration; refused
                               (`:refused :session-facts`) unless
                               `:retention :complete`
     :lease-capacity           declared capacity of the lease media"
  [{:keys [program project-fn begin-fn] :as config}]
  (when-not (and (map? program) (fn? project-fn) (fn? begin-fn))
    (throw (ex-info "the driver needs :program, :project-fn and :begin-fn"
                    {:refused :config})))
  (lease/check-facts-declaration! (:facts-declaration config))
  (atom {:config config
         :begin-cursor (lease/oldest-cursor (:begin config))
         :sessions {}}))


(defn session-medium
  "The routed-input medium of lineage `s`, or nil: the router's forward target."
  [driver s]
  (get-in @driver [:sessions s :medium]))


(defn sessions
  [driver]
  (set (keys (:sessions @driver))))


(defn latest
  "The session's stored latest `{:occurrence O :model m}`, or nil."
  [driver s]
  (get-in @driver [:sessions s :latest]))


(defn subscriptions
  "The exchange ids the session currently holds open as subscriptions."
  [driver s]
  (set (keys (get-in @driver [:sessions s :subs]))))


(defn lineage-agent
  [driver s]
  (get-in @driver [:sessions s :agent]))


(defn retire!
  "Retire the parked record of lineage `s`: the VM result and its media handles.
   The reclaim procedure of the lineage lease; facts are never touched."
  [driver s]
  (swap! driver update :sessions dissoc s)
  driver)


;; -----------------------------------------------------------------------------
;; Effects out
;; -----------------------------------------------------------------------------

(defn- emit!
  [config effects]
  (doseq [e effects]
    (stream/append! (:egress config) e)))


(defn- head
  [x type]
  {:http/exchange x :http/effect :http/head :http/status 200
   :http/headers {"content-type" type "cache-control" "no-cache"}})


(defn- chunk-fx
  [x body]
  {:http/exchange x :http/effect :http/chunk :http/body body})


(defn- end
  [x]
  {:http/exchange x :http/effect :http/end})


(defn- answer!
  "Head, one SSE projection, end: the reply to a one-shot exchange."
  [config x occurrence model]
  (emit! config [(head x "text/event-stream")
                 (chunk-fx x ((:project-fn config) occurrence model))
                 (end x)]))


(defn- patch!
  "Patch every open subscription whose exchange holder is holding.  The caller
   has observed the holders' state for this pass and checked the lineage's."
  [config s]
  (let [{:keys [occurrence model]} (:latest s)]
    (doseq [[x a] (:subs s)
            :when (and (not (contains? (:sub-staged s) x))
                       (lease/holding? a))]
      (emit! config [(chunk-fx x ((:project-fn config) occurrence model))]))))


;; -----------------------------------------------------------------------------
;; Session construction
;; -----------------------------------------------------------------------------

(defn- decl
  [capacity attribution]
  (lease/medium-declaration capacity attribution))


(defn- lineage-agent-config
  [{:keys [tick lineage-lease facts units lineage-renewal-interval lease-capacity
           facts-declaration]} s]
  (let [subject {:dao.star/lineage s}]
    {:self subject :grantor lease/lineage-grantor :subject subject
     :units units :renewal-interval lineage-renewal-interval
     :resolver lease/resolver :resolver-bindings lease/resolver-bindings
     :tick tick
     :fact {:handle lineage-lease :source lease/lineage-lease-source
            :medium (decl lease-capacity :per-author-media)}
     :writer {:handle facts :medium (assoc facts-declaration :attribution :envelope-key)}
     :envelope {:dao.star/lineage s}}))


(defn- exchange-agent-config
  [{:keys [tick exchange-lease egress units exchange-renewal-interval lease-capacity]} x]
  {:self :egress :grantor lease/exchange-grantor :subject {:http/exchange x}
   :units units :renewal-interval exchange-renewal-interval
   :resolver lease/resolver :resolver-bindings lease/resolver-bindings
   :tick tick
   :fact {:handle exchange-lease :source lease/exchange-lease-source
          :medium (decl lease-capacity :per-author-media)}
   :writer {:handle egress :medium (decl lease-capacity :per-author-media)}})


(defn- last-model
  "Drain the view medium: the newest model emitted, the advanced cursor, and
   whether the cursor answered a gap."
  [handle cursor]
  (let [r (lease/drain handle cursor)]
    {:cursor (:cursor r) :model (last (:values r)) :emitted? (seq (:values r))
     :gap? (:gap? r)}))


(defn- update-session!
  [driver s f & args]
  (swap! driver (fn [d]
                  (if (contains? (:sessions d) s)
                    (apply update-in d [:sessions s] f args)
                    d))))


(defn- forget!
  "A gap on a medium the session reads: forget every subscription and renew
   nothing from then on.  The lineage's holder is marked gapped, so it renews
   no lease; the leases lapse by silence."
  [driver s]
  (when-let [sess (get-in @driver [:sessions s])]
    (swap! (:agent sess) assoc :gapped? true)
    (update-session! driver s assoc :subs {} :sub-staged #{})))


(defn- begin!
  "Begin a lineage for exchange `x`: a fresh session with its own VM, the root
   fact appended before anything is answered.  The begin page itself is staged
   until the lineage grant has been observed (`flush-begin!`).  A root fact that
   is not accepted starts nothing and answers nothing; the exchange lapses by
   silence."
  [driver x]
  (let [{:keys [program facts] :as config} (:config @driver)
        s (str (random-uuid))
        in (new-ring (:session-capacity config))
        out (new-ring (:session-capacity config))
        medium (new-ring (:session-capacity config))
        result (await/run (update program :env merge {'in in 'out out}))
        {:keys [cursor model]} (last-model out (lease/oldest-cursor out))]
    (when (ok? (stream/append! facts (facts/root s)))
      (swap! driver assoc-in [:sessions s]
             {:lineage s :medium medium :cursor (lease/oldest-cursor medium)
              :vm-in in :vm-out out :out-cursor cursor :result result
              :latest {:occurrence s :model model} :subs {} :sub-staged #{}
              :begin-pending x
              :agent (lease/make-agent (lineage-agent-config config s))}))))


(defn begin-step!
  "Read the begin medium to `blocked`, beginning a lineage per message.  A gap
   on it loses begin requests; every session forgets its subscriptions."
  [driver]
  (let [{:keys [config begin-cursor]} @driver
        r (lease/drain (:begin config) begin-cursor)]
    (swap! driver assoc :begin-cursor (:cursor r))
    (when (:gap? r)
      (doseq [s (sessions driver)] (forget! driver s)))
    (doseq [m (:values r)
            :when (and (map? m) (= :begin (:kind m)) (:exchange m))]
      (begin! driver (:exchange m)))
    driver))


;; -----------------------------------------------------------------------------
;; One session
;; -----------------------------------------------------------------------------

(defn- flush-begin!
  "Answer the staged begin page once the lineage grant is observed and the
   holder is holding; drop it if the lease is already past its bound."
  [driver s]
  (let [{:keys [config]} @driver
        sess (get-in @driver [:sessions s])
        x (:begin-pending sess)]
    (when x
      (cond
        (lease/past-bound? (:agent sess)) (update-session! driver s dissoc :begin-pending)
        (lease/holding? (:agent sess))
        (do (update-session! driver s dissoc :begin-pending)
            (emit! config [(head x "text/html")
                           (chunk-fx x ((:begin-fn config) s))
                           (end x)]))))))


(defn- observe-subscriptions!
  "Observe every subscription holder's state against this pass's ticks before
   anything is projected to it.  A gap on an exchange holder's cursor is a gap
   the session reads: every subscription is forgotten and nothing renews.  A
   lapsed holder, or one past its bound, drops its subscription."
  [driver s]
  (doseq [[x a] (get-in @driver [:sessions s :subs])]
    (lease/step! a)
    (cond
      (lease/gapped? a) (forget! driver s)
      (or (:lapsed? @a) (lease/past-bound? a))
      (update-session! driver s (fn [y] (-> y (update :subs dissoc x) (update :sub-staged disj x)))))))


(defn- publish!
  "Append the pending transition's fact; only when it answers `ok` does the new
   occurrence become the latest and any UI carrying its id get projected.  The
   holder's tenure is rechecked first, fail closed: a holder past its bound or
   lapsed drops the staged transition (its exchange is left to lapse and the
   requester sees silence); a holder not holding for any other reason (a gap)
   keeps it staged and publishes nothing."
  [driver s]
  (let [{:keys [config]} @driver
        sess (get-in @driver [:sessions s])
        {:keys [successor predecessor model exchange halted?]} (:unpublished sess)
        agent (:agent sess)]
    (when (and sess (:unpublished sess))
      (cond
        (lease/past-bound? agent)
        (update-session! driver s dissoc :unpublished)

        (not (lease/holding? agent)) nil

        :else
        (let [fact (if halted?
                     (facts/ended s :halted)
                     (facts/consumption s successor predecessor))]
          (when (ok? (stream/append! (:facts config) fact))
            (update-session! driver s
                             (fn [x]
                               (-> x
                                   (dissoc :unpublished)
                                   (cond-> (not halted?)
                                     (assoc :latest {:occurrence successor :model model})))))
            (let [occ (if halted? predecessor successor)]
              (answer! config exchange occ model)
              (when-not halted?
                (observe-subscriptions! driver s)
                (patch! config (get-in @driver [:sessions s])))
              (if halted?
                (lease/release! agent)
                (lease/renew! agent)))))))))


(defn- consume!
  "Step the parked continuation with one input and stage its successor.  Every
   consumed input yields a successor, whether or not the program emitted a new
   model: when it did not, the previous model is retained."
  [driver s m]
  (let [sess (get-in @driver [:sessions s])
        input {:signals (signals/decode (:event m))}]
    (stream/append! (:vm-in sess) input)
    (let [result (await/resume (:result sess))
          {:keys [cursor model emitted? gap?]} (last-model (:vm-out sess) (:out-cursor sess))]
      (update-session! driver s assoc :result result :out-cursor cursor
                       :unpublished {:successor (str (random-uuid))
                                     :predecessor (get-in sess [:latest :occurrence])
                                     :model (if emitted? model (get-in sess [:latest :model]))
                                     :exchange (:exchange m)
                                     :halted? (not (:blocked? result))})
      (when gap? (forget! driver s))
      (publish! driver s))))


(defn- subscribe!
  "Open the subscription: its holder starts observing its exchange grant now,
   and the head and first projection are staged until the grant is observed."
  [driver s m]
  (let [{:keys [config]} @driver
        x (:exchange m)]
    (update-session! driver s
                     (fn [sess]
                       (-> sess
                           (assoc-in [:subs x] (lease/make-agent (exchange-agent-config config x)))
                           (update :sub-staged conj x))))))


(defn- flush-subscriptions!
  "Project the current occurrence to each staged subscription whose exchange
   holder is now holding."
  [driver s]
  (let [{:keys [config]} @driver
        lineage (:agent (get-in @driver [:sessions s]))]
    (doseq [x (:sub-staged (get-in @driver [:sessions s]))
            :let [a (get-in @driver [:sessions s :subs x])]
            :when (and a (lease/holding? a) (lease/holding? lineage))]
      (let [{:keys [occurrence model]} (get-in @driver [:sessions s :latest])]
        (update-session! driver s update :sub-staged disj x)
        (emit! config [(head x "text/event-stream")
                       (chunk-fx x ((:project-fn config) occurrence model))])))))


(defn- handle!
  "Handle one routed message.  Answers :wait when the message must stay unread
   (no grant observed yet, or the holder is not holding for a reason short of
   its bound), :consumed otherwise.  Leased actions are gated on `holding?`,
   which fails closed on a missing or invalid reading and on a gap."
  [driver s m]
  (let [{:keys [config]} @driver
        sess (get-in @driver [:sessions s])
        agent (:agent sess)]
    (cond
      (= :terminal (:kind m))
      (do (update-session! driver s (fn [x]
                                      (-> x
                                          (update :subs dissoc (:exchange m))
                                          (update :sub-staged disj (:exchange m)))))
          :consumed)

      (not (lease/granted? agent)) :wait

      (lease/past-bound? agent)
      (do (begin! driver (:exchange m)) :consumed)

      (not (lease/holding? agent)) :wait

      (= :subscribe (:kind m)) (do (subscribe! driver s m) :consumed)

      (and (= :input (:kind m))
           (= (:token m) (get-in sess [:latest :occurrence])))
      (do (consume! driver s m) :consumed)

      :else
      (let [{:keys [occurrence model]} (:latest sess)]
        (answer! config (:exchange m) occurrence model)
        :consumed))))


(defn- read-messages!
  [driver s]
  (loop []
    (let [sess (get-in @driver [:sessions s])]
      (when (and sess (not (:unpublished sess)))
        (let [r (stream/next (:medium sess) (:cursor sess))]
          (case (:dao.stream/outcome r)
            :dao.stream/ok
            (when (= :consumed (handle! driver s (:dao.stream/value r)))
              (update-session! driver s assoc :cursor (:dao.stream/cursor r))
              (recur))

            :dao.stream/gap
            (do (update-session! driver s assoc :cursor (:dao.stream/cursor r))
                (forget! driver s)
                (recur))

            nil))))))


(defn- tick-subscriptions!
  "On the tick: each subscription's holder observes its grant and any lapse,
   and renews when due; a lapsed or gapped exchange is dropped."
  [driver s]
  (let [sess (get-in @driver [:sessions s])]
    (observe-subscriptions! driver s)
    (doseq [[_ a] (get-in @driver [:sessions s :subs])]
      (lease/renew-if-due! a))
    (flush-subscriptions! driver s)
    (when (seq (:subs (get-in @driver [:sessions s])))
      (lease/renew-if-due! (:agent sess)))))


(defn session-step!
  [driver s]
  (when-let [sess (get-in @driver [:sessions s])]
    (let [agent (:agent sess)]
      (lease/step! agent)
      (when (lease/gapped? agent) (forget! driver s))
      (observe-subscriptions! driver s)
      (flush-begin! driver s)
      (when (lease/release-pending? agent) (lease/release! agent))
      (when (:unpublished sess) (publish! driver s))
      (read-messages! driver s)
      (tick-subscriptions! driver s)))
  driver)


(defn driver-step!
  "One pass: begin handler, then every session.  Answers the driver."
  [driver]
  (begin-step! driver)
  (doseq [s (sessions driver)]
    (session-step! driver s))
  driver)
