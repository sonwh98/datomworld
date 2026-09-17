(ns dao.jing.remote
  "Remote content adapter over DaoStream v2.
   Server side: dao.jing.remote/default-handlers exposes a local dao.jing
   content handle as the :jing/put-content and :jing/get-content operations,
   and serve-content! serves any {op fn} handler map over a v2 WebSocket
   endpoint. Client side: dao.jing.remote/content-client wraps an injected
   call function as a dao.jing content handle map {:client c :closed-atom a
   :put-content-fn f :get-content-fn g :close-fn h} that jing/materialize!,
   jing/get, and jing/close! dispatch through automatically; the synchronous
   WebSocket constructor connect-content! and its server twin serve-content!
   are JVM-only."
  (:require [clojure.string :as str]
            [dao.jing :as jing]
            [dao.stream.apply :as apply]
            [dao.stream.rpc :as rpc]
            [dao.stream.ws :as ws]
            ;; The :cljd branch must come first: ClojureDart's emit pass also
            ;; matches :clj, and dao.stream.ws.jvm has no Dart twin, so a
            ;; :clj-first spelling would put the JVM glue into the Dart
            ;; imports.  The :cljd [] splice keeps every namespace the host
            ;; composition uses — its media, its decoder, its codec check,
            ;; and the JVM glue itself — out of the emitted Dart.
            #?@(:cljd []
                :clj [[dao.stream :as stream]
                      [dao.stream.rpc.ws :as rpc.ws]
                      [dao.stream.transit :as transit]
                      [dao.stream.ringbuffer :as ring]
                      [dao.stream.serving :as serving]
                      [dao.stream.ws.jvm :as jvm]]))
  #?(:cljs (:require-macros [dao.jing])))


(defn- validate-address-payload!
  "Throw unless address is a strict segment address matching the payload's
   content-derived hash."
  [address payload]
  (when-not (jing/segment-address? address)
    (throw (ex-info "content address must be a segment address"
                    {:address address, :payload payload})))
  (when-not (= (jing/segment-hash address) (jing/content-hash payload))
    (throw (ex-info "content address does not match payload hash"
                    {:address address,
                     :content-hash (jing/content-hash payload)}))))


#_{:clj-kondo/ignore [:unused-binding]}


(defn- with-lock
  [lock f]
  #?(:clj (locking lock (f))
     :default (f)))


(defn- valid-presence-envelope?
  "True only for the exact wire envelope {:found? boolean, :value value}."
  [x]
  (and (map? x)
       (= #{:found? :value} (set (keys x)))
       (let [f (:found? x)] (or (true? f) (false? f)))))


(defn default-handlers
  "Build the RPC handler map from a local dao.jing content handle."
  [handle]
  (let [put (:put-content-fn handle)
        local-missing #?(:clj (Object.)
                         :cljs (js-obj)
                         :cljd (Object.))]
    (when-not (ifn? put)
      (throw (ex-info "handle must expose a :put-content-fn" {:handle handle})))
    {:jing/put-content
     (fn [address payload]
       (validate-address-payload! address payload)
       (let [result (put address payload)]
         (if (#{:inserted :present} result)
           result
           (throw (ex-info
                    "backend returned an invalid put result"
                    {:address address, :payload payload, :result result}))))),
     :jing/get-content (fn [address]
                         (let [result (jing/get handle address local-missing)]
                           (if (identical? result local-missing)
                             {:found? false, :value nil}
                             {:found? true, :value result})))}))


(defn content-client
  "Wrap an RPC client as a dao.jing content handle.

   call-fn is invoked as (call-fn client op args) and close-fn as
   (close-fn client). Close is guarded so concurrent JVM closes call the
   underlying close-fn exactly once; if close-fn throws, the client stays
   open for retry."
  [client call-fn close-fn]
  (when-not (ifn? call-fn)
    (throw (ex-info "call-fn must be a function" {:call-fn call-fn})))
  (when-not (ifn? close-fn)
    (throw (ex-info "close-fn must be a function" {:close-fn close-fn})))
  (let [closed-atom (atom false)
        close-lock #?(:clj (Object.)
                      :default nil)
        ensure-open (fn []
                      (when @closed-atom
                        (throw (ex-info "content client is closed"
                                        {:client client}))))]
    {:client client,
     :closed-atom closed-atom,
     :put-content-fn (fn [address payload]
                       (ensure-open)
                       (call-fn client :jing/put-content [address payload])),
     :get-content-fn
     (fn [address not-found]
       (ensure-open)
       (let [resp (call-fn client :jing/get-content [address])]
         (if (valid-presence-envelope? resp)
           (if (:found? resp) (:value resp) not-found)
           (throw
             (ex-info
               "malformed RPC response: presence envelope must contain exactly :found? (boolean) and :value keys"
               {:operation :jing/get-content,
                :address address,
                :response resp}))))),
     :close-fn (fn []
                 (with-lock close-lock
                   (fn []
                     (when-not @closed-atom
                       (close-fn client)
                       (compare-and-set! closed-atom false true)))))}))


;; =============================================================================
;; The DaoStream v2 portable core (migration Phase 1)
;; =============================================================================

(def content-path
  "The request-target path a content endpoint serves, and the path an absent
   URL path names.  An explicit path, including an explicit /, is kept as
   written (D7)."
  "/jing")


(def service-identity
  "The logical identity of the stream a content endpoint serves.  It is gated
   locally only; the wire carries host, port and path, and the serving side
   checks its own descriptor against itself, so a stable service name is
   correct here (D7)."
  "dao.jing.remote/content")


(def traffic-capacity
  "Declared capacity of the client boundary's one traffic medium, in elements
   (D5's composition; the JVM constructor builds it in Phase 2)."
  8192)


(def traffic-admission
  "Declared admission of the client boundary's one traffic medium."
  {:retention :evict-oldest
   :capacity traffic-capacity
   :value-domain :portable-values})


(def default-connect-timeout-ms 5000)
(def default-request-timeout-ms 5000)
(def default-poll-interval-ms 10)


(def max-timing-ms
  "The largest supported timing-option value, in milliseconds: one day.
   A timing option bounds how long one connect or one call may wait, and a
   day is a chosen policy limit on that — generous for any single wait,
   while far enough below long range that the deadline arithmetic has nine
   orders of magnitude of headroom, so no value in the supported domain can
   overflow after submission.  It is a supported-domain limit, not a
   universal line between a timeout and connection policy."
  86400000)


(def non-portable-result-code
  "The error code a serving step substitutes for a handler result outside the
   portable value domain, so the client learns the loss as a correlated error
   rather than as a timeout (S5)."
  :dao.jing.remote/non-portable-result)


(def ^:private default-port
  "The WebSocket scheme's own port, assumed when the URL names none (D7)."
  80)


(def ^:private digit-values
  (zipmap (map str (seq "0123456789")) (range 10)))


(defn- parse-positive-port
  "The port as a positive integer, or nil when `text` is not one.  Host
   character-to-integer conversions differ, so digits are matched as
   one-character strings."
  [text]
  (when (and (seq text)
             (every? #(contains? digit-values %) (map str (seq text))))
    (loop [i 0
           acc 0]
      (if (= i (count text))
        (when (pos? acc) acc)
        (recur (inc i)
               (+ (* 10 acc) (get digit-values (subs text i (inc i)))))))))


(defn- authority-end
  "The index `s`'s authority ends at: the first /, ? or #, or its length."
  [s]
  (->> [(str/index-of s "/") (str/index-of s "?") (str/index-of s "#")]
       (filter some?)
       (reduce min (count s))))


(defn- path-component
  "The URI path component of `raw`: everything before the first query or
   fragment delimiter.  Query and fragment are not part of a request target
   and never reach the descriptor."
  [raw]
  (let [raw (str raw)
        cut (->> [(str/index-of raw "?") (str/index-of raw "#")]
                 (filter some?)
                 (reduce min (count raw)))]
    (subs raw 0 cut)))


(defn- content-target
  "The served path a URL path names: an absent path means `content-path`,
   an explicit path stays as written (D7, the rule
   `yin.repl.connect/repl-target` applies)."
  [raw]
  (if (str/blank? (path-component raw))
    content-path
    (path-component raw)))


(defn- invalid-url!
  [url message]
  (throw (ex-info (str message ": " (pr-str url))
                  {:url url
                   :reason :dao.jing.remote/invalid-url})))


(defn content-descriptor
  "Derive the servable WebSocket descriptor of a content endpoint from its
   URL: `ws://host[:port][/path]`, port defaulting to 80, absent path naming
   `content-path`, identity the fixed `service-identity` (D7).  `wss://`,
   another scheme, a missing host, a port that is not a positive integer, a
   bracketed IPv6 authority, or a path that is not a canonical request target
   throw here, before any socket."
  [url]
  (let [url (str/trim (str url))]
    (cond
      (str/starts-with? url "wss://")
      (invalid-url! url "wss:// has no settled descriptor form here; use ws://")

      (not (str/starts-with? url "ws://"))
      (invalid-url! url "a content endpoint URL is ws://host[:port][/path]")

      :else
      (let [rest-url (subs url (count "ws://"))
            end (authority-end rest-url)
            authority (subs rest-url 0 end)
            raw-path (subs rest-url end)
            colon (str/last-index-of authority ":")
            host (if colon (subs authority 0 colon) authority)
            port (if colon (parse-positive-port (subs authority (inc colon)))
                     default-port)]
        (cond
          (str/starts-with? authority "[")
          (invalid-url! url "bracketed IPv6 authorities are not supported")

          (str/blank? host)
          (invalid-url! url "the URL names no host")

          (nil? port)
          (invalid-url! url "the URL port must be a positive integer")

          :else
          (let [descriptor {:dao.stream/type ws/transport-type
                            :dao.stream/identity service-identity
                            :ws/host host
                            :ws/port port
                            :ws/path (content-target raw-path)}]
            (if-not (ws/descriptor? descriptor)
              (invalid-url! url "the URL does not name a servable stream")
              descriptor)))))))


(defn call-step
  "One non-waiting advance of the call awaiting `id`. Retries a retained
   unsent envelope, polls at most `budget` response elements, takes and
   drains completions and diagnostics exactly once. Returns
   {:state s' :status :done|:pending|:terminal
    :completion c?          ; the completion for id, when :done
    :reason r?}             ; the terminal reason, when :terminal
   A completion for another id is discarded: it belongs to a call that
   timed out (N6). This is an interpreter step — it performs stream
   operations — under a single-owner, single-awaited-call precondition."
  [state id budget]
  (let [state (if (rpc/unsent? state)
                (:dao.stream.rpc/state (rpc/request! state nil nil))
                state)
        state (:dao.stream.rpc/state (rpc/poll! state budget))
        [completions state] (rpc/take-completed state)
        [_ state] (rpc/take-diagnostics state)
        mine (first (filter #(= id (:dao.stream.rpc/id %)) completions))]
    (cond
      mine {:state state :status :done :completion mine}
      (:terminal state) {:state state :status :terminal :reason (:terminal state)}
      :else {:state state :status :pending})))


(defn drain-outboxes
  "Take and discard both RPC outboxes (N11). A blocking driver with one
   awaited call has no outlet for completions it did not ask for or for
   diagnostics; leaving them in the state retains their payloads. Every
   exit of the driver stores a drained state, including the exits that
   never reach call-step: a request refused at the writer completes at once
   with its :args attached, an invalid request appends a diagnostic with
   them, and an allocation failure loses every outstanding request into the
   completion outbox."
  [state]
  (let [[_ state] (rpc/take-completed state)
        [_ state] (rpc/take-diagnostics state)]
    state))


(defn retire-call
  "Give up on the call awaiting `id` (N6). The id leaves :outstanding so a
   late response is classified unsolicited and dropped; a still-unsent
   envelope for it is abandoned with `reason`, which completes it on the
   ordinary path — and that completion is drained here, not left for a next
   step that may never come. :next-id never moves."
  [state id reason]
  (drain-outboxes
    (cond-> (update state :outstanding dissoc id)
      (= id (get-in state [:unsent :id])) (rpc/abandon-unsent reason))))


(defn completion-value
  "Interpret one completion. An ok response yields its value; an error
   response throws {:operation op :error {code message}}; a completion that
   carries a :reason instead of a response is a lost call and throws
   {:operation op :reason r} (N9)."
  [completion]
  (let [op (:dao.stream.rpc/op completion)
        response (:dao.stream.rpc/response completion)]
    (if (some? response)
      (if-let [error (apply/response-error response)]
        (throw (ex-info "remote error response"
                        {:operation op :error error}))
        (apply/response-ok response))
      (throw (ex-info "remote completion lost"
                      {:operation op
                       :reason (:dao.stream.rpc/reason completion)})))))


(defn await-established-step
  "One non-waiting advance of the establishment wait (D2). Polls exactly one
   response-medium element, so an establishment event is never hidden behind
   later events, and observes lifecycle only: :established when this element
   established the attachment, :terminal with :reason once the state is
   terminal, :pending otherwise. The wait has no call in flight, so a
   response element arriving before /established is consumed as an
   unsolicited diagnostic and does not establish; both outboxes are drained
   here for the same reason call-step drains them."
  [state]
  (let [result (rpc/poll! state 1)
        state' (drain-outboxes (:dao.stream.rpc/state result))]
    (cond
      (= :dao.stream.rpc/established (:dao.stream.rpc/outcome result))
      {:state state' :status :established}

      (:terminal state')
      {:state state' :status :terminal :reason (:terminal state')}

      :else
      {:state state' :status :pending})))


;; =============================================================================
;; The JVM host composition (migration Phase 2)
;; =============================================================================

;; Every defn in this section is spelled #?(:cljd nil :clj …) with :cljd
;; first, so the ClojureDart emit pass produces nothing for it while the
;; host-eval pass still defines it on the JVM.


#?(:cljd nil
   :clj
   (def ^:private response-poll-budget
     "How many response-medium elements one blocking-driver advance may
      consume (D1).  A poll stops at the first blocked read, so this bounds a
      burst, not a wait."
     32))


(def service-capacity
  "Declared capacity of the served identity's anchor stream.  It is never
   appended to, so capacity 1 cannot evict; its roles are to anchor the
   served identity and to make the endpoint's own media exist before any
   binding is attempted."
  1)


(def control-capacity
  "Declared capacity of the endpoint's pre-accept control medium, in elements."
  1024)


(def lifecycle-capacity
  "Declared capacity of the composition's host-listener lifecycle medium, in
   elements — the medium `dao.stream.ws.jvm/listen!` deposits into."
  256)


(def handoff-slot-count
  "How many connection upgrades the endpoint may hold between acceptance and
   acknowledgement before it answers full."
  8)


(def handoff-admission
  "Declared admission of every capacity-one host-value handoff medium."
  {:retention :evict-oldest :capacity 1 :value-domain :host-values})


(def control-admission
  "Declared admission of the endpoint's control medium."
  {:retention :evict-oldest
   :capacity control-capacity
   :value-domain :portable-values})


(def default-bind-host
  "The interface a content endpoint binds when its options name none, as
   `yin.repl.serve`'s default does."
  "127.0.0.1")


(def default-tick-ms
  "The server driver's default tick, in milliseconds."
  1)


#?(:cljd nil
   :clj
   (defn- validate-timing-options!
     "The tenth exit's gate, upper end included: timing options are client
      data a caller can assoc onto, so every entry point that will submit
      anything validates them first.  A bad option is an argument defect
      and throws here, before the wire — thrown after `rpc/request!`
      instead, from the deadline arithmetic or from `Thread/sleep`'s own
      argument check, it would leave by raw exception past `settle!`,
      store nothing, and let the next call reuse the abandoned id: the
      eighth exit's failure mode by another door.

      The supported domain is int-shaped milliseconds from 1 to
      `max-timing-ms` inclusive.  `int?` deliberately narrows the accepted
      numeric representation to fixed-precision integers rather than
      admitting everything `integer?` does: a BigInt satisfies `integer?`,
      and a small one would convert to a long, but accepting arbitrary
      precision here buys nothing and widens what the deadline arithmetic
      must be safe over.  The upper bound is what keeps that arithmetic
      inside long range — a value like Long/MAX_VALUE passes a mere
      positivity check and then overflows the deadline addition, after the
      request was already appended."
     [options]
     (doseq [[option value] options]
       (when-not (and (int? value) (<= 1 value max-timing-ms))
         (throw (ex-info (str "timing option " (name option)
                              " must be an integer of milliseconds from 1 to "
                              max-timing-ms " (one day)")
                         {:option option
                          :value value
                          :maximum-ms max-timing-ms
                          :options options}))))))


#?(:cljd nil
   :clj
   (defn- settle!
     "The one exit of `call!` (N11): drain both RPC outboxes, store the
      drained state in the client, and hand it to `raise`, which returns the
      call's value or throws its error.  No other path writes the client's
      stored state, so whatever the caller does next, the state a call leaves
      behind is bounded by the one call that just ended."
     [client state raise]
     (let [drained (drain-outboxes state)]
       (reset! (:rpc client) drained)
       (raise drained))))


#?(:cljd nil
   :clj
   (defn call!
     "The blocking driver of one remote operation (D1): a JVM thread that
      steps the portable call machine — every stream operation underneath
      returns without parking — and sleeps :poll-interval-ms between
      advances.  Cadence and deadlines are the client's options, not
      constants of the step.

      Every exit goes through settle!, so the stored RPC state leaves with
      empty :completed and :diagnostics outboxes and no retired id in
      :outstanding (N11).  A request refused at the writer throws N9's loss
      with the append reason; an invalid request and an allocator failure
      throw defect maps; a terminal attachment throws with its reason (N8);
      the deadline retires the call — the id leaves :outstanding and its
      late response is classified unsolicited and dropped — and throws
      {:request-id id :timeout-ms ms} (N6) without cancelling the remote
      execution, which the server may still be running; a remote error
      response throws {:operation op :error {code message}} and a lost
      completion {:operation op :reason r} (N9).  An interruption of the
      driver's sleep is the eighth exit: by then the request is on the wire
      and the advanced allocator, cursor and outstanding table exist only
      in the loop's local state, so the call is retired from that latest
      state and stored through settle! before the interruption is
      re-asserted — the thread's flag re-set — and thrown as
      {:request-id id :reason :dao.jing.remote/interrupted}.  Without this,
      the next call would allocate the same id and the interrupted call's
      late response could satisfy it.  The timing options are validated at
      entry, before anything is submitted (the tenth exit): the client is
      a value a caller can change, and an option that threw after
      `rpc/request!` — from the deadline arithmetic, or from
      `Thread/sleep`'s own argument check — would bypass settle!, store
      nothing, and let the next call reuse the abandoned id.  Their
      supported domain is an integer of milliseconds from 1 to
      `max-timing-ms` inclusive, so no value that passes the gate can
      overflow a deadline after submission.

      Calls on one client serialize under the client's lock — one locally
      awaited call per client (N5).  The lock covers call-versus-call only:
      close! runs outside it and is safe during an in-flight call, which
      then throws the terminal reason (N10)."
     [client op args]
     (let [lock (:lock client)
           request-timeout-ms (:request-timeout-ms client)
           poll-interval-ms (:poll-interval-ms client)]
       (validate-timing-options! {:request-timeout-ms request-timeout-ms
                                  :poll-interval-ms poll-interval-ms})
       (locking lock
         (let [requested (rpc/request! @(:rpc client) op args)
               outcome (:dao.stream.rpc/outcome requested)
               state (:dao.stream.rpc/state requested)]
           (case outcome
             :dao.stream.rpc/request-undeliverable
             (settle! client state
                      (fn [_]
                        (throw (ex-info "remote request undeliverable"
                                        {:operation op
                                         :request-id (:dao.stream.rpc/id requested)
                                         :reason (:dao.stream.rpc/reason requested)}))))

             :dao.stream.rpc/invalid-request
             (settle! client state
                      (fn [_]
                        (throw (ex-info
                                 "invalid RPC request: op must be a keyword and args a vector"
                                 {:op op
                                  :args args
                                  :reason :dao.stream.rpc/invalid-request}))))

             :dao.stream.rpc/allocator-error
             (settle! client state
                      (fn [_]
                        (throw (ex-info "the RPC request allocator failed"
                                        {:operation op
                                         :reason :dao.stream.rpc/allocator-error}))))

             :dao.stream.rpc/terminal
             (settle! client state
                      (fn [_]
                        (throw (ex-info "remote call lost on a terminal attachment"
                                        {:operation op :reason (:terminal state)}))))

             ;; :requested and :pending-request enter the loop with the
             ;; allocated id; the timing options were read and validated
             ;; before the lock.
             (let [id (:dao.stream.rpc/id requested)
                   deadline (+ (System/currentTimeMillis) request-timeout-ms)]
               (loop [state state]
                 (let [step (call-step state id response-poll-budget)]
                   (case (:status step)
                     :done
                     (settle! client (:state step)
                              (fn [_] (completion-value (:completion step))))

                     :terminal
                     (settle! client (:state step)
                              (fn [_]
                                (throw (ex-info "remote call lost"
                                                {:operation op
                                                 :reason (:reason step)}))))

                     ;; :pending — one non-waiting advance bought nothing
                     ;; the caller can use yet.  The sleep is the loop's
                     ;; only interruptible operation, and an interruption
                     ;; is the eighth exit: the request is already appended
                     ;; and the advanced allocator, cursor and outstanding
                     ;; table exist only in the loop's state, so leaving by
                     ;; the raw exception would store nothing and let the
                     ;; next call reuse this id — whose late response could
                     ;; then satisfy it.  Retire from the step's latest
                     ;; state, store through settle!, then re-assert the
                     ;; thread's flag and throw; the catch never returns,
                     ;; so the recur below is reached only after a full
                     ;; sleep (and stays outside the try, as recur
                     ;; requires).
                     (if (< (System/currentTimeMillis) deadline)
                       (do
                         (try
                           (Thread/sleep ^long poll-interval-ms)
                           (catch InterruptedException _
                             (settle! client
                                      (retire-call (:state step) id
                                                   :dao.jing.remote/interrupted)
                                      (fn [_]
                                        (.interrupt (Thread/currentThread))
                                        (throw (ex-info "remote call interrupted"
                                                        {:request-id id
                                                         :reason
                                                         :dao.jing.remote/interrupted}))))))
                         (recur (:state step)))
                       (settle! client
                                (retire-call (:state step) id
                                             :dao.jing.remote/timeout)
                                (fn [_]
                                  (throw (ex-info "remote request timed out"
                                                  {:request-id id
                                                   :timeout-ms request-timeout-ms})))))))))))))))


#?(:cljd nil
   :clj
   (defn close!
     "Close the connection's stream handle.  The once-only guard is
      content-client's (C4); this runs outside call!'s lock, which N10 makes
      safe during an in-flight call."
     [client]
     (stream/close! (:handle client))))


#?(:cljd nil
   :clj
   (defn- buffer
     "One ring-buffer medium of `capacity` elements (D5's composition)."
     [capacity]
     (:dao.stream/handle
       (ring/create! {:dao.stream/type ring/transport-type
                      ring/capacity-key capacity}))))


#?(:cljd nil
   :clj
   (defn- mint
     "The cursor `handle` mints at `anchor`, or nil — a composition failure
      to report, never a fabricated position."
     [handle anchor]
     (let [result (stream/cursor handle anchor)]
       (when (= :dao.stream/ok (:dao.stream/outcome result))
         (:dao.stream/cursor result)))))


#?(:cljd nil
   :clj
   (defn- writer-target
     [handle]
     {:dao.stream/handle handle :dao.stream/surface #{:writer}}))


#?(:cljd nil
   :clj
   (defn connect-content!
     "Connect to a remote content endpoint over WebSocket and return a
      content-client handle (see content-client) over a live DaoStream v2
      attachment (N1).  JVM-only.

      The URL is parsed by content-descriptor; a URL that names no servable
      stream throws before any socket.  The return waits for the
      attachment's /established lifecycle (D2): a terminal lifecycle first
      throws with its reason, :connect-timeout-ms passing without either
      throws {:url url, :timeout-ms ms}, and an interruption of the wait's
      sleep throws {:url url, :reason :dao.jing.remote/interrupted} with
      the thread's flag re-asserted.  In all three failure cases
      stream/close! has run on the handle before the throw, so no handle,
      medium or state escapes to the caller.

      The traffic cursor is minted at :dao.stream/newest BEFORE attach! —
      the order is the point and is observable: attach! starts the
      connection, the host may deposit :ws/opened on its own thread at any
      moment after, and a cursor minted afterwards would sit past that
      event, the wait would never see /established, and every connect would
      hang to its timeout on a perfectly good server.

      The limit N2 states, in one sentence: on the JVM a close before the
      socket opens does not tear down the JDK's establishment, so a peer
      that accepts TCP and never completes the upgrade costs one held
      connection per attempt for the process lifetime.

      Options: :connect-timeout-ms (default 5000), :request-timeout-ms
      (default 5000), :poll-interval-ms (default 10) — each an integer of
      milliseconds from 1 to max-timing-ms (one day) inclusive, validated
      before anything is submitted, so an argument defect throws before
      the wire and never past a submission."
     ([url] (connect-content! url {}))
     ([url {:keys [connect-timeout-ms request-timeout-ms poll-interval-ms]
            :or {connect-timeout-ms default-connect-timeout-ms
                 request-timeout-ms default-request-timeout-ms
                 poll-interval-ms default-poll-interval-ms}}]
      (validate-timing-options!
        {:connect-timeout-ms connect-timeout-ms
         :request-timeout-ms request-timeout-ms
         :poll-interval-ms poll-interval-ms})
      (let [descriptor (content-descriptor url)
            traffic (buffer traffic-capacity)
            cursor (mint traffic stream/anchor-newest)
            attach! (ws/make-attacher {:traffic (writer-target traffic)
                                       :admission traffic-admission
                                       :connect! jvm/connect!})
            result (attach! descriptor)]
        (if-not (= :dao.stream/ok (:dao.stream/outcome result))
          (throw (ex-info "the content endpoint refused the attachment"
                          {:url url :attach result}))
          (let [handle (:dao.stream/handle result)
                deadline (+ (System/currentTimeMillis) connect-timeout-ms)
                established
                (loop [state (rpc.ws/init-client result traffic cursor)]
                  (let [r (await-established-step state)]
                    (cond
                      (= :established (:status r)) (:state r)

                      (= :terminal (:status r))
                      (do (stream/close! handle)
                          (throw (ex-info
                                   "the content endpoint connection ended before establishment"
                                   {:url url :reason (:reason r)})))

                      (< (System/currentTimeMillis) deadline)
                      (do
                        (try
                          (Thread/sleep ^long poll-interval-ms)
                          (catch InterruptedException _
                            ;; The ninth exit, and the reason it closes the
                            ;; handle first: N2's promise is about every
                            ;; failure exit of this constructor, not two of
                            ;; them — an interruption that propagated raw
                            ;; would leave an attached handle and its socket
                            ;; live while the caller holds nothing.  Then
                            ;; re-assert the flag and throw the interrupted
                            ;; reason, as the eighth exit does.  The catch
                            ;; never returns, so the recur below is reached
                            ;; only after a full sleep (recur cannot cross
                            ;; a try anyway).
                            (stream/close! handle)
                            (.interrupt (Thread/currentThread))
                            (throw (ex-info
                                     "the content endpoint connect was interrupted"
                                     {:url url
                                      :reason :dao.jing.remote/interrupted}))))
                        (recur (:state r)))

                      :else
                      (do (stream/close! handle)
                          (throw (ex-info
                                   "the content endpoint did not establish in time"
                                   {:url url :timeout-ms connect-timeout-ms}))))))]
            (content-client {:rpc (atom established)
                             :handle handle
                             :attachment (:dao.stream/attachment result)
                             :lock (Object.)
                             :request-timeout-ms request-timeout-ms
                             :poll-interval-ms poll-interval-ms}
                            call!
                            close!)))))))


#?(:cljd nil
   :clj
   (defn- make-slots
     "The bounded acceptance handoff pool: `n` capacity-one offer and
      acknowledgement media, each with both cursors minted before the
      endpoint ever sees it."
     [n]
     (mapv (fn [_]
             (let [offer (buffer 1)
                   ack (buffer 1)]
               {:offer offer
                :ack ack
                :offer-cursor (mint offer stream/anchor-newest)
                :ack-cursor (mint ack stream/anchor-newest)}))
           (range n))))


#?(:cljd nil
   :clj
   (defn- endpoint-slots
     [slots]
     (mapv (fn [slot]
             {:offer (writer-target (:offer slot))
              :offer-admission handoff-admission
              :ack (writer-target (:ack slot))
              :ack-admission handoff-admission
              :ack-cursor (:ack-cursor slot)})
           slots)))


#?(:cljd nil
   :clj
   (defn- serving-slots
     [slots]
     (mapv (fn [slot]
             {:offer-reader (:offer slot)
              :offer-cursor (:offer-cursor slot)
              :ack-writer (writer-target (:ack slot))})
           slots)))


#?(:cljd nil
   :clj
   (defn- make-traffic
     "One per-attachment traffic medium — the same declared shape as the
      client boundary's, 8192 portable elements — with its reader cursor
      minted before the acknowledgement is deposited, so an open or payload
      event can never outrun the reader position."
     [_offer]
     (let [traffic (buffer traffic-capacity)]
       {:traffic (writer-target traffic)
        :admission traffic-admission
        :reader traffic
        :cursor (mint traffic stream/anchor-newest)})))


#?(:cljd nil
   :clj
   (defn- portable-response
     "The response as it will cross the wire.  A handler result outside the
      portable domain cannot be encoded; the client must learn that as a
      correlated error rather than as a timeout."
     [response]
     (if (transit/portable-value? response)
       response
       (apply/error-response (apply/response-id response)
                             non-portable-result-code
                             "Handler result is outside the portable value domain"))))


#?(:cljd nil
   :clj
   (defn- inbound-step
     "S5's per-attachment interpreter: each :ws/payload request is dispatched
      over `handlers` and its response appended to the attachment's socket
      handle.  The composition that runs this step is the JVM host's, and
      says so: its sends never answer a transient full after establishment,
      so nothing here needs a pending-response retention."
     [handlers]
     (fn [session envelope]
       (when (= rpc.ws/payload-event (get envelope rpc.ws/envelope-event-key))
         (when-let [response (apply/dispatch-request
                               handlers
                               (get envelope rpc.ws/envelope-value-key))]
           (let [result (stream/append! (:socket-handle session)
                                        (portable-response response))]
             ;; ok: delivered.  Anything else — closed, transport-error, a
             ;; full the JVM edge excludes by nature, or an invalid-value
             ;; the substitution above makes impossible — means this
             ;; attachment can no longer be answered; retiring it makes the
             ;; loss observable to the client as a terminal lifecycle
             ;; rather than as silence.
             (when-not (= :dao.stream/ok (:dao.stream/outcome result))
               (stream/close! (:socket-handle session)))))))))


#?(:cljd nil
   :clj
   (defn serve-content!
     "Serve `handlers` — any {op fn} map, `default-handlers` for a content
      store — over a DaoStream v2 WebSocket endpoint at content-path (S1).

      Returns {:port p :stop! f :serving s :lifecycle l}: the port the host
      reported bound, the idempotent stop, the serving composition, and the
      medium carrying the host listener's deposits.  A bind failure throws
      from here and retains nothing (S3).

      A daemon ticker thread steps the serving composition every :tick-ms
      (default 1) until `stop!`; that thread is host policy whose cadence
      the caller owns through the returned function (S2).  The handler runs
      in that single driver thread, so a handler that never returns stalls
      every session (S4).  A handler result outside the portable domain is
      answered as a correlated :dao.jing.remote/non-portable-result error,
      and a response that cannot be appended retires its attachment (S5).

      Options: :bind-host (default \"127.0.0.1\") and :tick-ms."
     ([handlers port] (serve-content! handlers port {}))
     ([handlers port {:keys [bind-host tick-ms]
                      :or {bind-host default-bind-host
                           tick-ms default-tick-ms}}]
      (let [bind-host (str bind-host)
            service (buffer service-capacity)
            control (buffer control-capacity)
            lifecycle (buffer lifecycle-capacity)
            control-cursor (mint control stream/anchor-newest)
            pool (make-slots handoff-slot-count)
            descriptor {:dao.stream/type ws/transport-type
                        :dao.stream/identity service-identity
                        :ws/host bind-host
                        :ws/port port
                        :ws/path content-path}]
        (when-not (ws/descriptor? descriptor)
          (throw (ex-info "a content endpoint needs a positive integer port"
                          {:port port :bind-host bind-host})))
        (let [ws-endpoint (ws/make-endpoint {:served {content-path descriptor}
                                             :control (writer-target control)
                                             :control-admission control-admission
                                             :slots (endpoint-slots pool)})
              running (atom true)
              listener (atom nil)
              bound-port (atom nil)
              deposit! (fn [kind value]
                         (when (and (= :bind-succeeded kind) (map? value))
                           (reset! bound-port (:port value)))
                         (stream/append! lifecycle
                                         {:dao.jing.remote/event kind
                                          :dao.jing.remote/value value}))
              composition (serving/make-serving
                            {:endpoint ws-endpoint
                             :served {content-path {:descriptor descriptor
                                                    :stream service}}
                             :control-reader control
                             :control-cursor control-cursor
                             :slots (serving-slots pool)
                             :make-traffic make-traffic
                             :inbound-step (inbound-step handlers)
                             :start-endpoint!
                             (fn [_]
                               (try
                                 (let [bound
                                       (jvm/listen!
                                         {:bind-host bind-host
                                          :bind-port port
                                          :accept!
                                          (fn [request-path socket now]
                                            (ws/accept-connection!
                                              ws-endpoint request-path socket now))
                                          :deposit! deposit!})]
                                   (reset! listener bound)
                                   {:dao.stream/outcome :dao.stream/ok})
                                 (catch Throwable _
                                   ;; A synchronous host bind failure is
                                   ;; classified and deposited here; no host
                                   ;; error object crosses over.
                                   (deposit! :bind-failed
                                             {:code :dao.jing.remote/bind-failed
                                              :message "the host listener failed to bind"})
                                   {:dao.stream/outcome
                                    :dao.stream/transport-error})))
                             :stop-endpoint!
                             (fn [_]
                               (if-let [bound @listener]
                                 (jvm/stop-listening!
                                   bound
                                   #(deposit! :stopped
                                              {:code :dao.jing.remote/stopped
                                               :message "the host listener stopped"}))
                                 {:dao.stream/outcome
                                  :dao.stream/transport-error}))})
              start-result (serving/start! composition)]
          (if (= :dao.stream/transport-error (:dao.stream/outcome start-result))
            (throw (ex-info "the content endpoint failed to bind"
                            {:port port :bind-host bind-host}))
            (do
              ;; The ticker is host policy: a daemon thread advancing the
              ;; composition once per tick until stop!, because the JVM
              ;; client blocks in the caller's thread and cannot step the
              ;; server itself.
              (doto (Thread.
                      (fn []
                        (while @running
                          (serving/step! composition (System/currentTimeMillis))
                          (Thread/sleep ^long tick-ms))))
                (.setDaemon true)
                (.setName "dao.jing.remote/content-endpoint")
                (.start))
              {:port (or @bound-port port)
               :stop! (fn []
                        (when (compare-and-set! running true false)
                          (serving/stop! composition)
                          nil))
               :serving composition
               :lifecycle lifecycle})))))))


#?(:clj (comment
          (require '[dao.jing.file :as file])
          (def store
            (file/create-content-file "target/dao-jing-remote-demo.log"))
          (def server (serve-content! (default-handlers store) 7070))
          (def client (connect-content! "ws://127.0.0.1:7070"))
          (def address (jing/materialize! client {:hello "world"}))
          (jing/get client address nil)
          (jing/close! client)
          ((:stop! server))
          (jing/close! store)))
