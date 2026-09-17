(ns dao.stream.rpc.ws
  "The WebSocket decoder for the DaoStream v2 RPC core.

   This is the only layer above the transport that knows `:ws/…` events.  It
   translates deposited WebSocket envelopes into the transport-neutral events
   of `dao.stream.rpc` before that core's transition algebra sees them,
   and it constructs RPC client state from a whole WebSocket `attach!`
   result.

   Everything here is a pure function of explicit data.  No socket, callback,
   promise, cursor, or scheduler is created or retained; the caller owns all
   state and chooses when to step the RPC core."
  (:require [dao.stream.rpc :as rpc]))


;; =============================================================================
;; Deposited envelope vocabulary
;; =============================================================================

(def envelope-attachment-key :ws/attachment)
(def envelope-event-key :ws/event)
(def envelope-value-key :ws/value)

(def payload-event :ws/payload)
(def accepted-event :ws/accepted)
(def opened-event :ws/opened)
(def closed-event :ws/closed)
(def ended-event :ws/ended)
(def error-event :ws/error)
(def not-found-event :ws/not-found)
(def transport-error-event :ws/transport-error)


(def known-events
  "Every deposited event kind this decoder recognizes from the WebSocket
   transport (`dao.stream.ws.md`, Deposited events are envelopes)."
  #{payload-event accepted-event opened-event closed-event ended-event
    error-event not-found-event transport-error-event})


(def lifecycle-translation
  "Exact translation of the deposited vocabulary into the neutral lifecycle
   vocabulary owned by `dao.stream.apply`.

   `:ws/opened` and `:ws/accepted` are the same neutral fact from its two
   ends: the client's accept control and the serving side's acceptance offer
   each establish the attachment.  The offer's host-local `:ws/handle` is a
   capability, never RPC data, and does not cross this boundary."
  {opened-event :dao.stream.apply/established
   accepted-event :dao.stream.apply/established
   closed-event :dao.stream.apply/detached
   ended-event :dao.stream.apply/ended
   error-event :dao.stream.apply/diagnostic
   not-found-event :dao.stream.apply/not-found
   transport-error-event :dao.stream.apply/transport-error})


(def malformed-envelope-code :dao.stream.rpc.ws/malformed-envelope)
(def unhandled-event-code :dao.stream.rpc/unhandled-event)


;; =============================================================================
;; The decoder
;; =============================================================================

(defn decoder
  "Return the envelope decoder for one attachment identity.

   The returned function takes one deposited WebSocket envelope and returns
   an event constructed by `dao.stream.rpc`:

   - an envelope of another attachment (or, on a shared medium, one that
     cannot be attributed at all) becomes `ignore-event` traffic; it is
     consumed once by the caller's cursor and is never a response;
   - a `:ws/payload` frame unwraps to exactly its carried `:ws/value`, which
     the RPC core classifies as a response, a diagnostic, or an ignored
     value;
   - each known lifecycle or diagnostic kind becomes its exact neutral
     translation, so a reconnectable `:ws/closed` stays distinct from the
     terminal `:ws/ended` and a survivable `:ws/error` stays non-terminal;
   - a well-formed event of unknown vocabulary is forwarded once as an
     `unhandled-event` diagnostic with no RPC state change, keeping the
     decoder open to additive transport vocabulary;
   - a malformed envelope (not a map, no event key, or a payload without a
     value key) is rejected as diagnostic data, never retried and never
     mistaken for a response.

   `me` may be nil for a private medium, in which case no attachment
   filtering is applied.  The decoder closes over `me` only; reattachment
   must build a fresh decoder through `rebind`."
  [me]
  (fn [envelope]
    (let [event (get envelope envelope-event-key)]
      (cond
        (not (map? envelope))
        (rpc/diagnostic-event malformed-envelope-code envelope)

        (and (some? me) (not= me (get envelope envelope-attachment-key)))
        (rpc/ignore-event)

        (not (keyword? event))
        (rpc/diagnostic-event malformed-envelope-code envelope)

        (= payload-event event)
        (if (contains? envelope envelope-value-key)
          (get envelope envelope-value-key)
          (rpc/diagnostic-event malformed-envelope-code envelope))

        (contains? lifecycle-translation event)
        (rpc/lifecycle-event (get lifecycle-translation event))

        :else
        (rpc/diagnostic-event unhandled-event-code envelope)))))


(def decode
  "Alias supplying the `:decode` slot of `dao.stream.rpc/client-state`."
  decoder)


;; =============================================================================
;; Client construction from a whole attach! result
;; =============================================================================

(defn- checked-attach
  [attach-result]
  (when-not (and (map? attach-result)
                 (= :dao.stream/ok (:dao.stream/outcome attach-result))
                 (contains? attach-result :dao.stream/handle))
    (throw (ex-info "RPC client requires a successful WebSocket attach! result"
                    {:ws/attach attach-result})))
  attach-result)


(defn init-client
  "Construct RPC client state from a whole WebSocket `attach!` result.

   The result supplies the request writer handle and, when the transport
   mints one, the attachment identity the response decoder filters on; a
   transport without attachment identity leaves `:me` nil and decoding
   unfiltered.  `reader` and `response-cursor` name the host-composed
   response medium and its already-minted cursor.  No cursor is fabricated
   here.  A failed or contract-violating attach result is a composition
   defect and throws."
  [attach-result reader response-cursor]
  (let [attach (checked-attach attach-result)
        me (:dao.stream/attachment attach)]
    (rpc/client-state (:dao.stream/handle attach) reader response-cursor
                      {:me me :decode (decoder me)})))


(defn rebind
  "Reattach a detached WebSocket client to a fresh `attach!` result.

   Only a `/detached` terminal is reconnectable; every other terminal reason
   returns `state` unchanged.  A rebind keeps the response reader, cursor,
   and monotonic id allocator, swaps in the new writer, and rebuilds the
   decoder around the new attachment identity -- without that the client
   would keep filtering on the dead attachment and drop everything the new
   boundary deposits."
  [state attach-result]
  (if-not (= :dao.stream.apply/detached (:terminal state))
    state
    (let [attach (checked-attach attach-result)
          me (:dao.stream/attachment attach)]
      (assoc (rpc/rebind state (:dao.stream/handle attach) me)
             :decode (decoder me)))))
