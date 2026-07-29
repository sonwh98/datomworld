(ns dao.gui.event
  "Downstream runtime that consumes terminal-emitted presented geometry and tap
   input, derives frame-local hit indices, and dispatches taps to subscribers
   keyed by (node-id, event-kind).

   V1 scope: tap events only, rectangular hit regions, topmost-wins dispatch."
  (:require
    [dao.stream :as ds]))


;; =============================================================================
;; Diagnostics
;; =============================================================================

(defn- diagnostic
  ([kind severity frame-id]
   {:diagnostic/kind kind, :severity severity, :frame-id frame-id})
  ([kind severity frame-id node-id reason]
   {:diagnostic/kind kind, :severity severity, :frame-id frame-id,
    :node-id node-id, :reason reason}))

(defn- emit-diagnostic!
  [signal-stream diag]
  (when signal-stream
    (ds/put! signal-stream diag)))


;; =============================================================================
;; Hit-testing
;; =============================================================================

(def ^:private axis-alignment-epsilon 1.0e-6)

(defn- effective-z
  [region]
  (let [paint-order (:paint-order region 0)]
    (bit-or (bit-shift-left (long paint-order) 32)
            (long (get region :bytecode-index 0)))))

(defn- half-open-contains?
  [x y w h px py]
  (let [epsilon axis-alignment-epsilon]
    (and (>= (- px x) (- epsilon))
         (< (- px (+ x w)) (- epsilon))
         (>= (- py y) (- epsilon))
         (< (- py (+ y h)) (- epsilon)))))

(defn- build-hit-index
  [geom]
  (let [regions (for [node (:nodes geom)
                      region (:regions node)]
                  {:node-id (:node-id node)
                   :event-kind (:event node)
                   :effective-z (effective-z region)
                   :bounds (:bounds region)})]
    {:frame-id (:frame-id geom)
     :regions (vec (sort-by :effective-z > regions))}))

(defn- hit-test
  [hit-index tap-position]
  (let [px (:x tap-position)
        py (:y tap-position)]
    (some (fn [region]
            (let [[x y w h] (:bounds region)]
              (when (half-open-contains? x y w h px py)
                region)))
          (:regions hit-index))))


;; =============================================================================
;; Runtime
;; =============================================================================

(def ^:private v1-event-kinds #{:tap})


(defn- arity-mismatch?
  [e]
  #?(:clj (instance? clojure.lang.ArityException e)
     :cljs (boolean (re-find #"Invalid arity" (or (.-message e) "")))
     :cljd (instance? NoSuchMethodError e)))


(defn- invoke-subscriber!
  [subscriber event]
  (try
    (subscriber event)
    (catch #?(:clj clojure.lang.ArityException
              :cljs :default
              :cljd Object)
           e
      (if (arity-mismatch? e)
        (subscriber)
        (throw e)))))


(defn create-runtime!
  [{:keys [geometry-stream tap-stream signal-stream]}]
  (let [hit-index (atom nil)
        subscribers (atom {})
        active-frame-id (atom -1)
        geom-pos (atom 0)
        tap-pos (atom 0)]

    (letfn
      [(dispatch-tap!
         [tap-value]
         (let [tap-frame-id (:frame-id tap-value)
               active @active-frame-id]
           (cond
             (neg? active)
             (emit-diagnostic! signal-stream
               (diagnostic :dao.gui.event/no-active-frame :error tap-frame-id))

             (< tap-frame-id active)
             (emit-diagnostic! signal-stream
               (diagnostic :dao.gui.event/stale-frame-tap :error tap-frame-id))

             (> tap-frame-id active)
             (emit-diagnostic! signal-stream
               (diagnostic :dao.gui.event/future-frame-tap :error tap-frame-id))

             :else
             (when-let [region (hit-test @hit-index (:position tap-value))]
               (doseq [sub (get @subscribers [(:node-id region) (:event-kind region)])]
                 (try
                   (invoke-subscriber!
                     sub
                     {:node-id (:node-id region)
                      :event-kind (:event-kind region)
                      :position (:position tap-value)})
                   (catch #?(:clj Exception :cljs :default :cljd Object) e
                     (emit-diagnostic! signal-stream
                       (diagnostic :dao.gui.event/subscriber-error
                         :warning tap-frame-id (:node-id region)
                         (ex-message e))))))))))

       (install-geometry!
         [geom-value]
         (reset! hit-index (build-hit-index geom-value))
         (reset! active-frame-id (:frame-id geom-value)))

       (process-pending!
         []
         ;; Drain all pending geometry, then all pending taps.
         (loop []
           (let [g-result (ds/next geometry-stream {:position @geom-pos})]
             (when (and (map? g-result) (:ok g-result))
               (install-geometry! (:ok g-result))
               (swap! geom-pos inc)
               (recur))))
         (loop []
           (let [t-result (ds/next tap-stream {:position @tap-pos})]
             (when (and (map? t-result) (:ok t-result))
               (dispatch-tap! (:ok t-result))
               (swap! tap-pos inc)
               (recur)))))]

      {:subscribers subscribers
       :hit-index hit-index
       :active-frame-id active-frame-id
       :signal-stream signal-stream
       :process-pending! process-pending!})))


(defn register!
  [runtime node-id event-kind subscriber-fn]
  (let [{:keys [subscribers signal-stream]} runtime]
    (when-not (v1-event-kinds event-kind)
      (emit-diagnostic! signal-stream
        (diagnostic :dao.gui.event/unrecognized-event-kind
          :warning nil node-id event-kind)))
    (swap! subscribers update [node-id event-kind] (fnil conj []) subscriber-fn)
    nil))


(defn unregister!
  [runtime node-id event-kind subscriber-fn]
  (let [{:keys [subscribers]} runtime]
    (swap! subscribers update [node-id event-kind]
      (fn [subs]
        (when subs
          (let [filtered (vec (remove #{subscriber-fn} subs))]
            (when (seq filtered) filtered)))))
    nil))


(defn dispose!
  [runtime]
  (let [{:keys [subscribers hit-index]} runtime]
    (reset! subscribers {})
    (reset! hit-index nil)
    nil))


(defn process-pending!
  "Synchronously processes any pending geometry and taps on the runtime's streams.
   Returns nil.  Applications drive this from their event loop; tests call it
   explicitly after writing to streams."
  [runtime]
  ((:process-pending! runtime)))
