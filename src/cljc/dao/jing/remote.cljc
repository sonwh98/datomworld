(ns dao.jing.remote
  "Remote content adapter over dao.stream.rpc.
   Server side: dao.jing.remote/default-handlers exposes a local dao.jing
   content handle as the :jing/put-content and :jing/get-content RPC ops.
   Client side: dao.jing.remote/content-client wraps an RPC client as a
   dao.jing content handle map {:client c :closed-atom a :put-content-fn f
   :get-content-fn g :close-fn h} that jing/materialize!, jing/get, and
   jing/close! dispatch through automatically. The synchronous WebSocket
   constructor connect-content! is JVM-only."
  (:require [clojure.string :as str]
            [dao.jing :as jing]
            [dao.stream.v2.apply :as apply]
            [dao.stream.v2.rpc :as rpc]
            [dao.stream.v2.ws :as ws]
            #?(:clj [dao.stream.rpc.client :as rpc-client])
            #?(:clj [dao.stream.rpc.ws :as rpc-ws]))
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
   `yin.repl.v2.connect/repl-target` applies)."
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
                (:dao.stream.v2.rpc/state (rpc/request! state nil nil))
                state)
        state (:dao.stream.v2.rpc/state (rpc/poll! state budget))
        [completions state] (rpc/take-completed state)
        [_ state] (rpc/take-diagnostics state)
        mine (first (filter #(= id (:dao.stream.v2.rpc/id %)) completions))]
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
  (let [op (:dao.stream.v2.rpc/op completion)
        response (:dao.stream.v2.rpc/response completion)]
    (if (some? response)
      (if-let [error (apply/response-error response)]
        (throw (ex-info "remote error response"
                        {:operation op :error error}))
        (apply/response-ok response))
      (throw (ex-info "remote completion lost"
                      {:operation op
                       :reason (:dao.stream.v2.rpc/reason completion)})))))


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
        state' (drain-outboxes (:dao.stream.v2.rpc/state result))]
    (cond
      (= :dao.stream.v2.rpc/established (:dao.stream.v2.rpc/outcome result))
      {:state state' :status :established}

      (:terminal state')
      {:state state' :status :terminal :reason (:terminal state')}

      :else
      {:state state' :status :pending})))


#?(:clj
   (defn connect-content!
     "Connect to a remote dao.jing content server over WebSocket and return a
      content client handle map (see content-client)."
     ([url] (connect-content! url {}))
     ([url opts]
      (let [client (rpc-ws/connect! url opts)]
        (content-client client rpc-client/call! rpc-client/close!)))))


#?(:clj (comment
          (require '[dao.jing.file :as file])
          (def store
            (file/create-content-file "target/dao-jing-remote-demo.log"))
          (def server (rpc-ws/start! (default-handlers store) 7070))
          (def client (connect-content! "ws://localhost:7070"))
          (def address (jing/materialize! client {:hello "world"}))
          (jing/get client address nil)
          (jing/close! client)
          (rpc-ws/stop! server)
          (jing/close! store)))
