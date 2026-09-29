(ns yin.vm.ffi.remote-serve.holder
  "The remote lease holder for one identity an export binding
   (`yin.vm.ffi.remote-serve`) serves: the holder half of
   `dao.stream.remote.md` S6 on the peer that attached it, composed with
   `dao.lease/make-holder`, reading its grant and renewing entirely
   over reflections.

   The grant is not carried to it. The binding serves its grantor's
   stream as `lease-grants`; this holder reads that stream through a
   reflection and takes the first grantor-authored `:accepted` whose
   subject is the identity it holds -- which names the renewal medium,
   and so this holder's own identity (`:dao.lease/holder`). Only then
   is `make-holder` assembled, its fact medium that same reflection
   (attributed to the grantor by the medium: per-author media) and its
   outbound medium a reflection of the renewal medium.

   `step` is one pass of the holder's OWN control flow: drain its own
   tick stream for the newest reading, look for the grant until
   observed, settle renewals whose SOURCE outcome arrived on the link's
   event writer -- a reflection's ok is outbound acceptance only
   (S6) -- and renew when `dao.lease/due-to-renew?` says so. An
   append-unknown on the event writer carries no identity, so it
   settles every renewal still pending here as unknown: no bound
   advances on it, which errs early, never late. A not-found answer
   on either reflection marks the lease reclaimed. A gap on the
   `lease-grants` cursor before the grant is observed is terminal loss
   (`lost`): the grant may have been evicted unread, and re-reading
   the recovery cursor could wait forever for it. `release!` is
   `dao.lease/stop`, its release fact kept and re-appended by `step`
   until the reflection accepts it.

   A plain value threaded by its drive owner; nothing here reads a
   clock, loops unboundedly or waits."
  (:require [dao.lease :as lease]
            [dao.stream :as stream]
            [yin.vm.ffi.remote-serve :as rs]))


(defn- remote-descriptor
  [served]
  {:dao.stream/type :dao.stream/remote
   :dao.stream/identity (:dao.stream/identity served)
   :dao.stream/channel (:dao.stream/channel served)})


(defn- attach
  [attach! served]
  (let [r (attach! (remote-descriptor served))]
    (when (= :dao.stream/ok (:dao.stream/outcome r))
      (:dao.stream/handle r))))


(defn- holder-config
  "The `dao.lease/make-holder` config: `self` and the renewal
   reflection are known once the grant is read."
  [opts grants grants-cursor self renewal]
  {:self self
   :grantor rs/grantor
   :units rs/lease-units
   :resolver (fn [source _fact] source)
   :resolver-bindings #{:per-author-media}
   :renewal-interval (::renewal-interval opts)
   :subject (::subject opts)
   :tick (::ticks opts)
   :fact {:handle grants
          :cursor grants-cursor
          :source rs/grantor
          :medium (::grants-medium opts)}
   :writer {:handle renewal :medium (::renewal-medium opts)}})


(defn- assembly-refusal
  "`make-holder`'s own refusal of these declarations and this interval,
   checked before any grant with a stand-in identity and cursor, or nil."
  [opts grants]
  (try (lease/make-holder (holder-config opts grants 0 ::unknown grants))
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         {::option (case (:refused (ex-data e))
                     :renewal-interval ::renewal-interval
                     ::grants-medium)
          ::reason ::malformed
          ::lease-refusal (ex-data e)})))


(defn open!
  "Assemble the holder for served `::subject` (an identity string),
   or refuse. `opts`:
     ::attach!          the composition's :dao.stream/remote attach entry
     ::lease-grants     the binding's served `lease-grants` marker
                        (`rs/lease-grants`)
     ::subject          the served identity whose lease this holds
     ::ticks            `{:handle :cursor}`, this holder's OWN tick stream
     ::events           `{:handle :cursor}`, a reader of the event writer
                        the attacher's links emit append outcomes on
     ::renewal-interval the composition's renewal interval, sized
                        against the duration it expects
                        (`dao.lease/renewal-interval`)
     ::budget           the positive bound on grant reads per step
     ::grants-medium    the explicit medium declaration of `lease-grants`
                        (`dao.lease.md` C2): its retention and capacity
                        are the grantor's, declared by the composition
     ::renewal-medium   the explicit declaration of the renewal medium
                        (`rs/renewal-declaration` of the grantor's
                        capacity)
   The declarations and interval are checked here by `make-holder`'s own
   assembly checks, so the assembly at grant time cannot refuse.
   Returns the holder value, or `{::status ::refused ::refusals [...]}`."
  [{::keys [attach! lease-grants subject ticks events renewal-interval
            budget grants-medium renewal-medium]
    :as opts}]
  (let [missing (cond-> []
                  (not (fn? attach!)) (conj ::attach!)
                  (not (map? lease-grants)) (conj ::lease-grants)
                  (nil? subject) (conj ::subject)
                  (not (and (map? ticks) (:handle ticks) (some? (:cursor ticks))))
                  (conj ::ticks)
                  (not (and (map? events) (:handle events) (some? (:cursor events))))
                  (conj ::events)
                  (not (lease/duration? renewal-interval))
                  (conj ::renewal-interval)
                  (not (and (integer? budget) (pos? budget))) (conj ::budget)
                  (not (map? grants-medium)) (conj ::grants-medium)
                  (not (map? renewal-medium)) (conj ::renewal-medium))]
    (if (seq missing)
      {::status ::refused
       ::refusals (mapv (fn [k] {::option k, ::reason ::malformed}) missing)}
      (if-some [grants (attach attach! lease-grants)]
        (if-some [refusal (assembly-refusal opts grants)]
          {::status ::refused, ::refusals [refusal]}
          {::attach! attach!
           ::subject subject
           ::channel (:dao.stream/channel lease-grants)
           ::grants grants
           ::grants-cursor nil
           ::ticks ticks
           ::events events
           ::renewal-interval renewal-interval
           ::budget budget
           ::grants-medium grants-medium
           ::renewal-medium renewal-medium
           ::reading nil
           ::composed nil
           ::holder nil
           ::renewal nil
           ::pending []
           ::release nil
           ::renewals 0
           ::reclaimed? false
           ::lost nil})
        {::status ::refused
         ::refusals [{::option ::lease-grants, ::reason ::unattachable}]}))))


;; =============================================================================
;; The pass
;; =============================================================================

(defn- drain-ticks
  "The newest reading on the holder's own tick stream, or the last one."
  [h]
  (let [{th :handle} (::ticks h)]
    (loop [c (:cursor (::ticks h))
           newest (::reading h)]
      (let [r (stream/next th c)]
        (if (= :dao.stream/ok (:dao.stream/outcome r))
          (recur (:dao.stream/cursor r)
                 (or (:dao.lease/reading (:dao.stream/value r)) newest))
          (-> h
              (assoc-in [::ticks :cursor] c)
              (assoc ::reading newest)))))))


(defn- gone?
  [outcome]
  (= :dao.stream.remote/not-found (:dao.stream.remote/reason outcome)))


(defn- mint-grants-cursor
  [h]
  (if (some? (::grants-cursor h))
    h
    (let [r (stream/cursor (::grants h) :dao.stream/oldest)]
      (cond
        (= :dao.stream/ok (:dao.stream/outcome r))
        (assoc h ::grants-cursor (:dao.stream/cursor r))
        (gone? r) (assoc h ::reclaimed? true)
        :else h))))


(defn- my-grant?
  [h v]
  (and (lease/lease-fact? v)
       (= :dao.lease/accepted (:dao.lease/status v))
       (= (::subject h) (:dao.lease/subject v))))


(defn- assemble
  "This subject's `grant` was read at the grants cursor: attach the
   renewal medium it names, assemble make-holder with the holder
   identity it names, and observe the grant at the latest reading. Nil
   when the grant cannot be held yet -- no reading, or no attachment --
   so the caller re-reads it on a later pass."
  [h grant]
  (when-some [renewal (attach (::attach! h)
                              {:dao.stream/identity (:dao.lease/holder grant)
                               :dao.stream/channel (::channel h)})]
    (let [composed (lease/make-holder
                     (holder-config h (::grants h) (::grants-cursor h)
                                    (:dao.lease/holder grant) renewal))
          holder (lease/observe-grant (:holder composed) rs/grantor
                                      grant (::reading h))]
      (when (some? (:grant holder))
        (assoc h ::composed composed ::renewal renewal ::holder holder)))))


(defn- seek-grant
  "Read `lease-grants` up to the budget for this subject's grant."
  [h]
  (let [h (mint-grants-cursor h)]
    (if (or (::reclaimed? h) (nil? (::grants-cursor h)))
      h
      (loop [h h
             n (::budget h)]
        (if (zero? n)
          h
          (let [r (stream/next (::grants h) (::grants-cursor h))]
            (cond
              (= :dao.stream/ok (:dao.stream/outcome r))
              (let [v (:dao.stream/value r)
                    c' (:dao.stream/cursor r)]
                (if (my-grant? h v)
                  (if-some [h' (assemble h v)]
                    (assoc h' ::grants-cursor c')
                    h)
                  (recur (assoc h ::grants-cursor c') (dec n))))
              ;; facts were evicted past the cursor before this holder
              ;; read them; its grant may be among them, and which were
              ;; lost cannot be said -- terminal, never retried
              (= :dao.stream/gap (:dao.stream/outcome r))
              (assoc h ::lost :dao.stream/gap)
              (gone? r) (assoc h ::reclaimed? true)
              :else h)))))))


(defn- settle
  "Settle pending renewals from the event writer: an outcome naming the
   renewal identity settles the oldest pending one; append-unknown
   settles all as unknown."
  [h]
  (let [rid (get-in h [::holder :self])
        {eh :handle} (::events h)]
    (loop [h h
           c (:cursor (::events h))]
      (let [r (stream/next eh c)]
        (if-not (= :dao.stream/ok (:dao.stream/outcome r))
          (assoc-in h [::events :cursor] c)
          (let [v (:dao.stream/value r)
                c' (:dao.stream/cursor r)
                observe (fn [h at outcome]
                          (update h ::holder lease/observe-renewal at outcome))
                failed {:dao.stream/outcome :dao.stream/transport-error}]
            (cond
              (= :dao.stream.remote/append-unknown
                 (:dao.stream.remote/event v))
              (recur (-> (reduce #(observe %1 %2 failed) h (::pending h))
                         (assoc ::pending []))
                     c')

              (and (= rid (:dao.stream/identity v))
                   (not (contains? v :dao.stream/descriptor))
                   (seq (::pending h)))
              (let [at (first (::pending h))
                    h (-> h
                          (update ::pending (comp vec rest))
                          (observe at (if (:dao.stream.remote/error v) failed v)))]
                (recur (cond-> h
                         (= :dao.stream.remote/not-found
                            (:dao.stream.remote/error v))
                         (assoc ::reclaimed? true))
                       c'))

              :else (recur h c'))))))))


(defn- append-release
  [h]
  (let [o (stream/append! (::renewal h) (::release h))]
    (cond
      (= :dao.stream/ok (:dao.stream/outcome o)) (assoc h ::release nil)
      (gone? o) (assoc h ::release nil ::reclaimed? true)
      :else h)))


(defn- renew
  [h]
  (let [reading (::reading h)]
    (if (and (lease/due-to-renew? (::holder h) reading)
             (empty? (::pending h)))
      (let [o (stream/append! (::renewal h)
                              (lease/renewal
                                (get-in h [::holder :grant :dao.lease/lease])))]
        (cond
          (= :dao.stream/ok (:dao.stream/outcome o))
          (-> h (update ::pending conj reading) (update ::renewals inc))
          (gone? o) (assoc h ::reclaimed? true)
          :else h))
      h)))


(defn step
  "One pass of the holder's own control flow; returns the holder. A
   lost or reclaimed holder is terminal: the pass reads and appends
   nothing more."
  [h]
  (let [h (drain-ticks h)]
    (cond
      (or (::reclaimed? h) (some? (::lost h))) h
      (nil? (::holder h)) (seek-grant h)
      :else (let [_ (stream/descriptor (::renewal h))
                  h (settle h)]
              (cond
                (::reclaimed? h) h
                (some? (::release h)) (append-release h)
                :else (renew h))))))


(defn release!
  "The holder is done (`dao.lease/stop`): its release is appended on the
   renewal medium now, and re-appended by `step` until accepted.
   Returns the holder; a holder that observed no grant is unchanged."
  [h]
  (if (or (nil? (get-in h [::holder :grant])) (::reclaimed? h))
    h
    (let [{holder' :holder release :release} (lease/stop (::holder h))
          h (assoc h ::holder holder')]
      (if release
        (append-release (assoc h ::release release))
        h))))


(defn holding?
  "True when the holder may act at its latest reading
   (`dao.lease/holding?`)."
  [h]
  (and (not (::reclaimed? h))
       (nil? (::lost h))
       (some? (::holder h))
       (lease/holding? (::holder h) (::reading h))))


(defn grant
  "The grant this holder observed, or nil."
  [h]
  (get-in h [::holder :grant]))


(defn reclaimed?
  "True once a reflection answered not-found: the lease was reclaimed."
  [h]
  (boolean (::reclaimed? h)))


(defn lost
  "Why the holder can never observe its grant, or nil: `:dao.stream/gap`
   when `lease-grants` evicted facts past its cursor before it read
   them. Terminal: `step` does nothing more, and the composition
   decides what the loss means (re-propose, give up the resource)."
  [h]
  (::lost h))
