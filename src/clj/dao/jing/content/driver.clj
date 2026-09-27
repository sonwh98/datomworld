(ns dao.jing.content.driver
  "The JVM blocking driver over the stepped content client
   (dao.jing.content.step): host policy that steps the client and
   sleeps, returning a synchronous dao.jing content handle. It replaces
   the deleted dao.jing.remote blocking constructor; its consumers are
   the coordinate opener (dao.jing.coordinate/open!) and, through it,
   dao.space and yin.repl.link on the JVM. yin.vm's materialize-tree!
   and yin.vm.code's materialize-vector! have no remote path of their
   own: they gain one only by being handed a coordinate-built handle.

   The returned handle is the plain-data map dao.jing/materialize!,
   dao.jing/get, and dao.jing/close! dispatch through:
   {:client c :closed-atom a :put-bytes-fn f :get-bytes-fn g
    :close-fn h}. A put sends the bytes as Base64 text and answers the
   remote verdict; a get decodes the answer the server answers and
   admits it as ingress -- the dao.jing/accept-bytes! check, which the
   stepped client runs on every found reply -- before its bytes leave
   this handle. JVM-only, so the whole namespace is plain Clojure."

  (:require [dao.jing :as jing]
            [dao.jing.content.step :as step]
            [dao.stream :as stream]))


(def default-request-timeout-ms
  "How long one call may wait for its completion, in milliseconds."
  5000)


(def default-poll-interval-ms
  "How long the driver sleeps between advances, in milliseconds."
  10)


(def ^:private poll-budget
  "How many answers one blocking-driver advance may consume. A poll
   stops at the first blocked read, so this bounds a burst, not a
   wait."
  32)


(def max-timing-ms
  "The largest supported timing-option value, in milliseconds: one
   day. A timing option bounds how long one call may wait, and a day
   is a chosen policy limit on that; the upper bound is what keeps the
   deadline arithmetic inside long range after submission."
  86400000)


(defn- valid-handle!
  "Throw unless the step state's pair is present: the driver drives
   what client-state built, and a composition defect must throw here
   rather than surface as a stuck call."
  [step-state]
  (when-not (and (stream/writer? (:requests step-state))
                 (stream/reader? (:answers step-state)))
    (throw (ex-info
             "the driver's step state needs a requests writer and an
              answers reader"
             {:step-state step-state})))
  step-state)


(defn- validate-timing-options!
  "Timing options are client data a caller can assoc onto, so the
   driver validates them before submitting anything: an argument
   defect throws here, before the wire, leaving the stored state
   untouched. The supported domain is int-shaped milliseconds from 1
   to max-timing-ms inclusive; the upper bound is what keeps the
   deadline arithmetic safe over every value that passes the gate."
  [options]
  (doseq [[option value] options]
    (when-not (and (int? value) (<= 1 value max-timing-ms))
      (throw (ex-info (str "timing option " (name option)
                           " must be an integer of milliseconds from 1 to "
                           max-timing-ms " (one day)")
                      {:option option
                       :value value
                       :maximum-ms max-timing-ms
                       :options options})))))


(defn driver
  "Compose a synchronous dao.jing content handle over one stepped
   client state (dao.jing.content.step/client-state) on an attached
   content pair. The driver thread is the caller's: each call locks
   the handle, submits its request, and steps the client -- every
   stream operation underneath returns without parking -- sleeping
   :poll-interval-ms between advances until the request's completion
   arrives.

   Options: :request-timeout-ms (default 5000) and
   :poll-interval-ms (default 10), each an integer of milliseconds
   from 1 to max-timing-ms inclusive, validated at entry. A timed-out
   call retires its bookkeeping through step/retire -- the id leaves
   :outstanding, its late answer is dropped as unsolicited, and a
   materialization record it claimed completes :lost -- and throws
   {:request-id id :timeout-ms ms} without cancelling the remote
   execution, which the server may still be serving. An interruption
   of the driver's sleep retires the call the same way, re-asserts
   the thread's flag, and throws {:request-id id :reason
   :dao.jing.content/interrupted}.

   Calls on one handle serialize under its lock -- one locally
   awaited call per handle. The lock covers call-versus-call only:
   close! runs outside it and is safe during an in-flight call, which
   then fails with the pair's refusal or its loss. A found reply that
   fails the ingress check surfaces as an :error completion and
   throws; a lost completion throws with its reason."
  ([step-state] (driver step-state {}))
  ([step-state {:keys [request-timeout-ms poll-interval-ms]
                :or {request-timeout-ms default-request-timeout-ms
                     poll-interval-ms default-poll-interval-ms}}]
   (valid-handle! step-state)
   (validate-timing-options! {:request-timeout-ms request-timeout-ms
                              :poll-interval-ms poll-interval-ms})
   (let [client (atom step-state)
         closed-atom (atom false)
         lock (Object.)
         ensure-open
         (fn []
           (when @closed-atom
             (throw (ex-info "content client is closed" {}))))
         settle!
         (fn [state]
           (reset! client state)
           state)
         resolve-get
         (fn [completion]
           (cond
             (contains? completion :lost)
             (throw (ex-info "remote request lost"
                             {:reason (:lost completion)}))
             (contains? completion :error)
             (throw (ex-info "remote error answer"
                             {:error (:error completion)}))
             ;; a stored nil decodes to the value nil, whose canonical
             ;; bytes are present: only the not-found half is absent
             (:found? completion)
             (jing/canonical-bytes (:value completion))
             :else ::absent))
         resolve-put
         (fn [completion]
           (cond
             (contains? completion :lost)
             (throw (ex-info "remote request lost"
                             {:reason (:lost completion)}))
             (contains? completion :error)
             (throw (ex-info "remote error answer"
                             {:error (:error completion)}))
             :else (get completion :result)))
         await!
         (fn [state id deadline]
           (loop [state state]
             (let [r (step/step state poll-budget)
                   mine (some #(when (= id (:id %)) %)
                              (:completions r))
                   s (:state r)]
               (cond
                 mine (do (settle! s) mine)

                 (:terminal s)
                 (do (settle! s)
                     (throw (ex-info "remote request lost"
                                     {:request-id id
                                      :reason (:terminal s)})))

                 (< (System/currentTimeMillis) deadline)
                 (do
                   (settle! s)
                   (try
                     (Thread/sleep ^long poll-interval-ms)
                     (catch InterruptedException _
                       ;; The request is already on the wire; retire
                       ;; it from the latest state, store through
                       ;; settle!, re-assert the flag, and throw --
                       ;; so the next call cannot reuse this id.
                       (settle! (step/retire s id
                                             :dao.jing.content/interrupted))
                       (.interrupt (Thread/currentThread))
                       (throw (ex-info "remote call interrupted"
                                       {:request-id id
                                        :reason
                                        :dao.jing.content/interrupted}))))
                   (recur @client))

                 :else
                 (do (settle! (step/retire s id
                                           :dao.jing.content/timeout))
                     (throw (ex-info "remote request timed out"
                                     {:request-id id
                                      :timeout-ms
                                      request-timeout-ms})))))))
         call!
         (fn [submit! raise!]
           (locking lock
             (ensure-open)
             (let [submitted (submit! @client)
                   outcome (:outcome submitted)
                   id (:id submitted)]
               (if (and (contains? #{:requested :pending-request}
                                   outcome)
                        (some? id))
                 (raise! (await! (:state submitted) id
                                 (+ (System/currentTimeMillis)
                                    request-timeout-ms)))
                 (throw (ex-info "remote request refused"
                                 {:outcome outcome
                                  :reason (:reason submitted)}))))))]
     {:client client
      :closed-atom closed-atom
      :put-bytes-fn
      (fn [address bs]
        (call! #(step/request-put-bytes % address bs) resolve-put))
      :get-bytes-fn
      (fn [address not-found]
        (let [result (call! #(step/request-get % address) resolve-get)]
          (if (= ::absent result) not-found result)))
      :close-fn
      (fn []
        (when-not @closed-atom
          (doseq [handle [(:requests @client) (:answers @client)]]
            (when (stream/closable? handle)
              (stream/close! handle)))
          (reset! closed-atom true)))})))
