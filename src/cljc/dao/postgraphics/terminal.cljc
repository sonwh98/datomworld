(ns dao.postgraphics.terminal
  "Shared terminal binding over a DaoStream v2 frame handle.

   The binding is a value, not a listener: `bind` mints a cursor and returns
   it, and the host calls `step` when it chooses — one `next` per call.
   Nothing is registered with the stream and nothing is invoked from inside a
   stream operation; cadence belongs to the host ticker that owns `step`."
  (:require [dao.stream.v2 :as stream]))


(defn new-generation-id
  []
  #?(:clj (str (java.util.UUID/randomUUID))
     :cljs (str (random-uuid))
     :cljd (str (rand-int 2147483647)
                "-" (rand-int 2147483647)
                "-" (rand-int 2147483647))))


(defn emit-signal!
  "Append signal to the optional signal handle. The outcome is not inspected:
   a full or closed signal lane drops the signal."
  [signal-handle signal]
  (when signal-handle (stream/append! signal-handle signal))
  nil)


(defn rejection-reason
  [e]
  (or (get (ex-data e) :dao.postgraphics/reason) :validation-failure))


(defn reset-signal
  [generation-id]
  {:message/kind :dao.terminal/reset, :generation-id generation-id})


(defn rejection-signal
  [submission-id reason]
  {:message/kind :dao.terminal/rejection,
   :submission-id submission-id,
   :reason reason})


(defn frame-skipped-signal
  [submission-id]
  {:message/kind :dao.terminal/frame-skipped, :submission-id submission-id})


(defn protocol-error-signal
  [error-kind frame-id]
  {:message/kind :dao.terminal/protocol-error,
   :error/kind error-kind,
   :frame-id frame-id})


(defn transport-error-signal
  "Protocol error for a frame stream the terminal can no longer read.
   stream-outcome is the DaoStream `next` outcome that stopped the binding;
   frame-id is the last presented frame's id, or nil if none was presented."
  [stream-outcome frame-id]
  (assoc (protocol-error-signal :dao.terminal/transport-error frame-id)
         :dao.stream/outcome stream-outcome))


(defn put-frame!
  "Append frame to the frame handle and return the append outcome map.
   Wakes nothing; a bound terminal observes the frame on its next `step`."
  [frame-handle frame]
  (stream/append! frame-handle frame))


(defn bind
  "Return a terminal binding value over frame-handle.

   The cursor is minted at :dao.stream/newest, so the terminal presents what
   arrives after it binds. The reset signal is appended to :signal-handle.
   If minting does not answer ok, the binding holds no cursor and its first
   `step` reports :error.

   `:submission-id` is the terminal-local ingress sequence: it counts
   observed submissions (presented, rejected, or skipped), not stream
   positions. `:presented-frame-id` is the id of the last presented frame:
   nil until the first presentation, then 0, 1, ...; rejections and skips do
   not advance it."
  [frame-handle
   {:keys [validate-frame! present-frame! on-error signal-handle generation-id
           generation-id-fn],
    :or {validate-frame! identity,
         present-frame! (fn [_] nil),
         generation-id-fn new-generation-id}}]
  (let [generation-id (or generation-id (generation-id-fn))
        minted (stream/cursor frame-handle :dao.stream/newest)]
    (emit-signal! signal-handle (reset-signal generation-id))
    {:frame-handle frame-handle,
     :cursor (when (= :dao.stream/ok (:dao.stream/outcome minted))
               (:dao.stream/cursor minted)),
     :generation-id generation-id,
     :submission-id 0,
     :presented-frame-id nil,
     :closed? false,
     :validate-frame! validate-frame!,
     :present-frame! present-frame!,
     :on-error on-error,
     :signal-handle signal-handle}))


(defn- accept-frame!
  "Validate and present frame. Returns :presented or :rejected."
  [{:keys [validate-frame! present-frame! on-error signal-handle submission-id]}
   frame]
  (try (validate-frame! frame)
       (present-frame! frame)
       :presented
       (catch #?(:clj Exception
                 :cljs :default
                 :cljd Object)
              e
         (emit-signal! signal-handle
                       (rejection-signal submission-id (rejection-reason e)))
         (when on-error (on-error e))
         :rejected)))


(defn step
  "Read at most one frame. Returns {:binding binding' :status s}, s one of
   :presented, :rejected, :blocked, :gap, :end, :error, :closed.

   On gap the binding adopts the recovery cursor the outcome carries. On end
   or any other non-ok outcome a transport-error signal is appended and the
   binding closes; later steps answer :closed."
  [{:keys [frame-handle cursor closed? submission-id presented-frame-id
           signal-handle],
    :as binding}]
  (if closed?
    {:binding binding, :status :closed}
    (let [read (if (some? cursor)
                 (stream/next frame-handle cursor)
                 {:dao.stream/outcome :dao.stream/invalid-cursor})
          outcome (:dao.stream/outcome read)]
      (case outcome
        :dao.stream/ok
        (let [status (accept-frame! binding (:dao.stream/value read))]
          {:binding (cond-> (assoc binding
                                   :cursor (:dao.stream/cursor read)
                                   :submission-id (inc submission-id))
                      (= :presented status)
                      (assoc :presented-frame-id
                             (if (some? presented-frame-id)
                               (inc presented-frame-id)
                               0))),
           :status status})
        :dao.stream/blocked {:binding binding, :status :blocked}
        :dao.stream/gap
        (do (emit-signal! signal-handle (frame-skipped-signal submission-id))
            {:binding (assoc binding
                             :cursor (:dao.stream/cursor read)
                             :submission-id (inc submission-id)),
             :status :gap})
        (do (emit-signal! signal-handle
                          (transport-error-signal outcome presented-frame-id))
            {:binding (assoc binding :closed? true),
             :status (if (= :dao.stream/end outcome) :end :error)})))))


(def ^:private progress-statuses #{:presented :rejected :gap})


(defn step-until-blocked
  "Call `step` until it stops making progress or max-steps calls have run.
   Returns the last {:binding :status}; status :blocked, :end, :error or
   :closed means the binding is idle, a progress status means the bound was
   reached with frames still pending."
  ([binding] (step-until-blocked binding 64))
  ([binding max-steps]
   (loop [binding binding
          n 0]
     (let [{binding' :binding, status :status, :as result} (step binding)]
       (if (and (contains? progress-statuses status) (< (inc n) max-steps))
         (recur binding' (inc n))
         result)))))


(defn close
  "Mark binding closed. Nothing is registered, so nothing is unregistered."
  [binding]
  (assoc binding :closed? true))
