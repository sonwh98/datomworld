(ns dao.postgraphics.terminal
  (:require
    [dao.stream :as ds]))


(defn new-generation-id
  []
  #?(:clj (str (java.util.UUID/randomUUID))
     :cljs (str (random-uuid))
     :cljd (str (rand-int 2147483647)
                "-" (rand-int 2147483647)
                "-" (rand-int 2147483647))))


(defn emit-signal!
  [signal-stream signal]
  (when signal-stream (ds/put! signal-stream signal)))


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


(defn put-frame!
  [frame-stream frame]
  (let [{:keys [result woke]} (ds/put! frame-stream frame)]
    (doseq [{:keys [entry value position]} woke]
      (let [ready (assoc entry
                         :value value
                         :status :ok
                         :position position)]
        ((:resume ready) nil ready value)))
    result))


(defn geometry-from-report
  "Convert lowering geometry into the canonical terminal hit-region shape."
  [geometry-report]
  {:nodes
   (vec
     (mapcat
       (fn [paint-order {:keys [screen-rect op/meta]}]
         (map (fn [event-kind]
                {:node-id (:node-id meta)
                 :event event-kind
                 :regions [{:bounds screen-rect
                            :paint-order paint-order
                            :bytecode-index paint-order}]})
              (:interactive-events meta)))
       (range)
       geometry-report))})


(defn- emit-geometry!
  "Emits presented geometry onto the geometry stream.  If geom is nil, emits an
   explicit empty geometry value to clear stale hit regions."
  [geometry-stream frame-id geom]
  (when geometry-stream
    (ds/put! geometry-stream
             (if geom
               (assoc geom :frame-id frame-id)
               {:frame-id frame-id, :nodes []}))))


(defn- accept-tap-impl
  "Emits a tap event on the tap stream, tagged with the current frame-id."
  [tap-stream frame-id position]
  (when tap-stream
    (ds/put! tap-stream {:frame-id frame-id, :position position})))


(defn- accept-input-impl
  [input-stream frame-id input]
  (when input-stream
    (ds/put! input-stream (assoc input :frame-id frame-id))))


(defn bind-stream!
  [frame-stream
   {:keys [validate-frame! present-frame! on-error signal-stream generation-id
           generation-id-fn geometry-stream tap-stream input-stream],
    :or {validate-frame! identity,
         present-frame! (fn [_] nil),
         generation-id-fn new-generation-id}}]
  (let [generation-id (or generation-id (generation-id-fn))
        frame-id (atom -1)
        open? (atom true)]
    (emit-signal! signal-stream (reset-signal generation-id))
    (letfn
      [(reject-frame!
         [submission-id e]
         (let [reason (rejection-reason e)]
           (emit-signal! signal-stream (rejection-signal submission-id reason))
           (if on-error (on-error e) nil)))
       (accept-frame!
         [submission-id frame]
         (try (validate-frame! frame)
              (let [geom (present-frame! frame)]
                (swap! frame-id inc)
                (emit-geometry! geometry-stream @frame-id geom))
              (catch #?(:clj Exception
                        :cljs :default
                        :cljd Object)
                     e
                (reject-frame! submission-id e))))
       (handle-gap!
         [cursor]
         (emit-signal! signal-stream (frame-skipped-signal (:position cursor)))
         (await-next! (update cursor :position inc)))
       (await-next!
         [cursor]
         (when @open?
           (let [result (try (ds/next frame-stream cursor)
                             (catch #?(:clj Exception
                                       :cljs :default
                                       :cljd Object)
                                    e
                               (emit-signal! signal-stream
                                             (protocol-error-signal
                                               :stream-read-failed
                                               (:position cursor)))
                               (when on-error (on-error e))
                               ::stream-read-failed))]
             (cond (map? result) (do (accept-frame! (:position cursor)
                                                    (:ok result))
                                     (recur (:cursor result)))
                   (= :blocked result) (ds/register-reader-waiter!
                                         frame-stream
                                         (:position cursor)
                                         {:resume resume,
                                          :reason :next,
                                          :stream frame-stream,
                                          :cursor cursor})
                   (= :daostream/gap result) (handle-gap! cursor)
                   :else nil))))
       (resume
         [rt entry value]
         (when (and @open? (some? value))
           (accept-frame! (:position entry) value)
           (await-next! (update (:cursor entry) :position inc)))
         rt)]
      (await-next! {:position 0}) {:generation-id generation-id,
                                   :close! #(reset! open? false),
                                   :accept-tap! (fn [position]
                                                  (accept-tap-impl tap-stream @frame-id position)),
                                   :accept-input! (fn [input]
                                                    (accept-input-impl
                                                      input-stream
                                                      @frame-id
                                                      input)),
                                   :presented-frame-id (fn [] @frame-id)})))
