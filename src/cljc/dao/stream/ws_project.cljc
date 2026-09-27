(ns dao.stream.ws-project
  "The WebSocket channel composition of dao.stream.remote.md (3.1).

   The mirror and the link of dao.stream.remote speak raw request and
   answer maps, so each channel end composes one step, the projection,
   between the ws deposit medium and the channel: the channel reader is
   a ring buffer the composition wires, the channel writer is the ws
   handle itself. The projection holds the reading cursor on the
   attachment's traffic medium, keeps the events whose :ws/attachment
   names this channel, appends the :ws/value of each :ws/payload onto
   the ring, and drops :ws/error diagnostics. A terminal lifecycle
   event (:ws/closed, :ws/ended) or a failure resolution (:ws/not-found,
   :ws/transport-error) makes the projection close the ring, and so
   does the traffic medium's own end: the projection is over and
   nothing more can arrive, so a link waiting on the ring observes the
   loss instead of blocking forever -- channel loss is then the link's
   own end observation (2.4).

   The composition helpers wire both ends of the spec's section-5 toy
   over real attachments. The accepting peer drives `accept-step!`:
   endpoint steps, one offer adopted per tick (a fresh traffic medium
   and ring per accepted connection, acknowledged per dao.stream.ws.md
   Serving), then per session the projection and, over the projected
   reader and the socket handle, dao.stream.remote/mirror-step. The
   dialing peer composes `dial` over one ws attacher and drives
   `dial-step!`: the projection and then its own mirror step, so either
   peer serves the other on one connection. Reflections come from
   dao.stream.remote/attacher through the channel end each side holds.
   Every step here is driver-paced: the composition owns the cadence,
   as it does for endpoint-step. Nothing schedules itself."
  (:require [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.ws :as ws]))


(def terminal-events
  "The deposited events that end the channel: a terminal lifecycle
   event for the attachment (:ws/closed, :ws/ended) or a failure
   resolution (:ws/not-found, :ws/transport-error). The projection
   closes the ring on each of them. :ws/error is a diagnostic and is
   never terminal by itself (dao.stream.ws.md, Deposited events are
   envelopes)."
  #{:ws/closed :ws/ended :ws/not-found :ws/transport-error})


;; =============================================================================
;; The projection (3.1's ws-project step)
;; =============================================================================

(defn projection
  "Compose one projection over the attachment named `:attachment` (the
   :ws/attachment value every deposited event of this channel carries),
   the traffic medium reader `:traffic`, the already-minted reading
   cursor `:cursor`, and the ring buffer `:ring` the channel reads.
   The projection owns the cursor from here: each `step!` advances it."
  [{:keys [attachment traffic cursor ring]}]
  (when-not (and (some? attachment)
                 (stream/reader? traffic)
                 (some? cursor)
                 (stream/reader? ring)
                 (stream/writer? ring)
                 (stream/closable? ring))
    (throw (ex-info "invalid DaoStream ws projection composition"
                    {:attachment attachment :traffic traffic
                     :cursor cursor :ring ring})))
  (atom {:attachment attachment :traffic traffic :cursor cursor
         :ring ring :closed? false}))


(defn- project!
  "Apply one deposited event (3.1): another attachment's event and
   every non-payload, non-terminal kind are ignored here; a :ws/error
   is dropped as a diagnostic; a :ws/payload's :ws/value is appended
   onto the ring; a terminal event or failure resolution closes the
   ring. True only for the terminal case."
  [state event]
  (when (= (:ws/attachment event) (:attachment state))
    (let [kind (:ws/event event)]
      (cond
        (= :ws/payload kind)
        (do (when (contains? event :ws/value)
              (stream/append! (:ring state) (:ws/value event)))
            nil)

        (= :ws/error kind) nil

        (contains? terminal-events kind)
        (do (stream/close! (:ring state)) true)

        :else nil))))


(defn step!
  "Drive the projection once, at the composition's cadence: read the
   traffic medium to blocked, keeping, forwarding and dropping per
   3.1, stopping at the event that closes the ring. A gap adopts the
   medium's own recovery cursor, the lost events being lost. The
   medium's own end closes the projection and the ring with it:
   nothing more can arrive, so a link waiting on the ring must
   observe the loss (2.4) rather than block forever. Returns the
   projection."
  [project]
  (loop []
    (let [s @project]
      (if (:closed? s)
        project
        (let [r (stream/next (:traffic s) (:cursor s))]
          (case (:dao.stream/outcome r)
            :dao.stream/ok
            (let [event (:dao.stream/value r)]
              (swap! project assoc :cursor (:dao.stream/cursor r))
              (if (project! s event)
                (swap! project assoc :closed? true)
                (recur)))

            :dao.stream/end
            (do (stream/close! (:ring s))
                (swap! project assoc :closed? true)
                project)

            :dao.stream/gap
            (do (swap! project assoc :cursor (:dao.stream/cursor r))
                (recur))

            ;; blocked, and any read the medium refuses: stay put.
            project))))))


(defn closed?
  "True once the projection stopped: the ring was closed by a terminal
   lifecycle event or a failure resolution, or the traffic medium
   itself answered end."
  [project]
  (:closed? @project))


(defn reading-cursor
  "The projection's current reading cursor on the traffic medium."
  [project]
  (:cursor @project))


;; =============================================================================
;; The accepting peer (section 5: accept, project, mirror)
;; =============================================================================

(defn- writer-target?
  [x]
  (and (map? x)
       (stream/writer? (:dao.stream/handle x))
       (contains? (:dao.stream/surface x) :writer)))


(defn- valid-slot?
  [{:keys [offer-reader offer-cursor ack-writer]}]
  (and (stream/reader? offer-reader)
       (some? offer-cursor)
       (writer-target? ack-writer)))


(defn make-acceptor
  "Compose the accepting end of the spec's section-5 toy.
   `:endpoint` is a dao.stream.ws/make-endpoint value whose handoff
   `:slots` are given here, in endpoint order, as {:offer-reader r
   :offer-cursor c :ack-writer target} -- readers, cursors and writers
   the composition minted before the listener started. `:table` is the
   mirror's table (dao.stream.remote.md 2.2). `:make-media` receives
   one accepted offer and returns the attachment's composition:
   :traffic (the deposit target the acknowledgement carries),
   :admission (its declaration), :reader (the traffic reader the
   projection reads), :cursor (its minted reading cursor) and :ring
   (the fresh channel ring buffer). The listener is the host's: it
   calls ws/accept-connection! on this endpoint, per dao.stream.ws.md
   Serving."
  [{:keys [endpoint slots table make-media] :as config}]
  (when-not (and (map? config)
                 (some? endpoint)
                 (fn? make-media)
                 (map? table)
                 (seq slots)
                 (every? valid-slot? slots))
    (throw (ex-info "invalid DaoStream ws acceptor composition"
                    {:config config})))
  (atom {:endpoint endpoint
         :slots (vec slots)
         :table table
         :make-media make-media
         :offer-cursors (mapv :offer-cursor slots)
         :sessions {}}))


(defn- adopt!
  "Adopt one accepted offer as a session: create the attachment's
   media through :make-media, acknowledge the offer per
   dao.stream.ws.md Serving (:ws/command :ws/accept, the deposit
   target and its admission), and register the projection, ring,
   reading cursor and socket handle the mirror works through. An
   offer that is not an acceptance, and an acknowledgement the slot's
   medium refuses, close the offered handle; the slot itself is the
   endpoint's to release."
  [acceptor slot offer]
  (let [make-media (:make-media @acceptor)
        handle (get-in offer [:ws/handle :dao.stream/handle])
        attachment (:ws/attachment offer)]
    (if-not (and (= :ws/accepted (:ws/event offer))
                 (string? attachment)
                 (some? handle)
                 (stream/writer? handle)
                 (stream/closable? handle))
      (when (stream/closable? handle)
        (stream/close! handle))
      (let [media (make-media offer)
            appended (stream/append!
                       (:dao.stream/handle (:ack-writer slot))
                       {:ws/attachment attachment
                        :ws/command :ws/accept
                        :ws/deposit (:traffic media)
                        :ws/admission (:admission media)})]
        (if (= :dao.stream/ok (:dao.stream/outcome appended))
          (swap! acceptor assoc-in [:sessions attachment]
                 {:attachment attachment
                  :handle handle
                  :ring (:ring media)
                  :cursor (:dao.stream/cursor
                            (stream/cursor (:ring media)
                                           stream/anchor-oldest))
                  :project (projection
                             {:attachment attachment
                              :traffic (:reader media)
                              :cursor (:cursor media)
                              :ring (:ring media)})})
          (stream/close! handle))))))


(defn- reaped
  "The sessions without the closed ones. A closed session's tick just
   ran its last mirror pass -- a closed ring forwards no further
   request, so only what it retained could ever be answered, and that
   pass answered what the handle accepted -- so its handle, ring and
   projection have nothing left to do. Keeping them would grow the
   composition without bound across repeated connections; the endpoint
   released the connection's slot at acceptance (dao.stream.ws.md
   Serving), and this is the composition's own bound."
  [m]
  (into {} (remove (fn [[_ s]] (closed? (:project s))) m)))


(defn accept-step!
  "Advance the accepting composition one tick at `now`, in the order
   serving compositions use: a transport step first (releasing dead
   pendings, consuming standing acknowledgements), then one offer per
   slot adopted with its fresh media and acknowledgement, then a
   second transport step, so the fresh acknowledgement is consumed and
   the accept frame is on the wire before this end answers anything,
   then per session the projection and, over the projected reader and
   the socket handle, the mirror step. A session whose projection has
   closed is then removed: the mirror pass just run was its last, and
   repeated connections must not accumulate. Returns the acceptor."
  [acceptor now]
  (let [{:keys [endpoint slots] :as s} @acceptor]
    (ws/endpoint-step endpoint now)
    (doseq [[index slot] (map-indexed vector slots)]
      (let [r (stream/next (:offer-reader slot)
                           (nth (:offer-cursors s) index))]
        (when (= :dao.stream/ok (:dao.stream/outcome r))
          (swap! acceptor assoc-in [:offer-cursors index]
                 (:dao.stream/cursor r))
          (adopt! acceptor slot (:dao.stream/value r)))))
    (ws/endpoint-step endpoint now)
    (doseq [[attachment session] (:sessions @acceptor)]
      (step! (:project session))
      (swap! acceptor assoc-in [:sessions attachment :cursor]
             (remote/mirror-step (:table @acceptor)
                                 (:ring session)
                                 (:cursor session)
                                 (:handle session))))
    (swap! acceptor update :sessions reaped)
    acceptor))


(defn sessions
  "The acceptor's sessions as data: attachment id -> {:attachment
   :handle :ring :cursor :project}."
  [acceptor]
  (:sessions @acceptor))


(defn session-end
  "One accepted attachment's channel end {:reader r :writer w} -- the
   ring this end projects onto and the socket handle it writes
   through: the entry for dao.stream.remote/attacher's
   :dao.stream.remote/channels map at this end, so the accepting peer
   can attach reflections of the other direction over the same
   connection. Nil before the attachment is adopted and again once
   its closed session is reaped."
  [acceptor attachment]
  (when-some [session (get (:sessions @acceptor) attachment)]
    {:reader (:ring session) :writer (:handle session)}))


;; =============================================================================
;; The dialing peer (section 5: the link over its dialed attachment)
;; =============================================================================

(defn dial
  "Compose the dialing end of one WebSocket channel (3.1). `:attach!`
   is the dao.stream.ws/make-attacher product whose :traffic deposit
   medium is the same medium named by `:traffic` (its target), with
   `:cursor` already minted on it before any attach!; `:ring` is the
   channel ring buffer this end projects onto. `:table` names the
   identities this peer serves over the dialed channel, so
   `dial-step!` runs the projection and then this end's own mirror
   step: the dialing end serves the other direction on the same
   connection. `dial-attach!` takes a remote descriptor whose
   :dao.stream/channel is this channel's ws descriptor: it attaches,
   projects the attachment onto the ring, and answers through
   dao.stream.remote/attacher with the channel end {:reader ring
   :writer handle} -- the link's drain then runs inside every
   reflection operation. One dial value carries one active
   attachment; a reattachment composes a fresh dial with a fresh
   cursor, as dao.stream.ws.md composes one medium per active client
   attachment. :dao.stream.remote/events, :dao.stream.remote/resend-
   after and :dao.stream.remote/budget pass to the attacher as its
   policy."
  [{:keys [attach! traffic cursor ring table] :as opts}]
  (when-not (and (fn? attach!)
                 (map? traffic)
                 (stream/writer? (:dao.stream/handle traffic))
                 (stream/reader? (:dao.stream/handle traffic))
                 (some? cursor)
                 (stream/reader? ring)
                 (stream/writer? ring)
                 (stream/closable? ring)
                 (map? table))
    (throw (ex-info "invalid DaoStream ws dial composition"
                    {:opts opts})))
  (let [channel (atom nil)
        mirror-cursor (atom (:dao.stream/cursor
                              (stream/cursor ring stream/anchor-oldest)))
        policy (select-keys opts [:dao.stream.remote/events
                                  :dao.stream.remote/resend-after
                                  :dao.stream.remote/budget])]
    (atom {:attach! attach! :traffic traffic :cursor cursor :ring ring
           :table table :channel channel :mirror-cursor mirror-cursor
           :policy policy})))


(defn dial-attach!
  "Attach one reflection of `descriptor` (a :dao.stream/remote
   descriptor) through the dialed channel: the ws attach runs first,
   the attachment's projection is composed, and the remote attacher
   answers with the reflection -- its confirmation deferred, per
   dao.stream.remote.md 2.4, to the descriptor probe the link sends
   now. A channel attach that fails answers the ws attacher's own
   outcome. One dial value carries one active attachment: a dial that
   has already attached rejects a further dial-attach! -- its ring
   and traffic cursor already belong to the first attachment, and a
   second projection on them would mix two attachments' state -- with
   a composition error; a reattachment composes a fresh dial with a
   fresh cursor, as `dial` documents. Returns the remote attach!
   result."
  [dial descriptor]
  (let [{:keys [attach! traffic cursor ring channel policy]} @dial]
    (when-some [attached @channel]
      (throw (ex-info
               "this dial already attached its one active attachment"
               {:attachment (:attachment attached)
                :reattachment "compose a fresh dial with a fresh cursor"})))
    (let [r (attach! (:dao.stream/channel descriptor))]
      (if-not (= :dao.stream/ok (:dao.stream/outcome r))
        r
        (let [cd (:dao.stream/channel descriptor)
              handle (:dao.stream/handle r)
              project (projection {:attachment (:dao.stream/attachment r)
                                   :traffic (:dao.stream/handle traffic)
                                   :cursor cursor
                                   :ring ring})
              reflect! (remote/attacher
                         (merge policy
                                {:dao.stream.remote/channels
                                 {cd {:reader ring :writer handle}}}))]
          (reset! channel {:attachment (:dao.stream/attachment r)
                           :handle handle :project project})
          (reflect! descriptor))))))


(defn dial-step!
  "Drive the dialing composition one tick, at the composition's
   cadence: the attachment's projection first, so what the wire
   deposited reaches the ring, then this end's own mirror step over
   the projected reader and the socket handle, answering the other
   direction's requests against this end's table. Returns the
   mirror's advanced reading cursor."
  [dial]
  (let [{:keys [ring table channel mirror-cursor]} @dial]
    (when-some [project (:project @channel)]
      (step! project))
    (when-some [handle (:handle @channel)]
      (swap! mirror-cursor
             (fn [c] (remote/mirror-step table ring c handle))))
    @mirror-cursor))


(defn channel
  "The dialed channel as data: {:attachment id :handle h :project p} --
   the ws attachment identity, the ws handle (the channel writer), and
   the attachment's projection. Nil before the first dial-attach!. A
   composition that owns the connection's lifetime closes the handle
   here."
  [dial]
  @(:channel @dial))
