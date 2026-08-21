;; Protocol-level fault values shared by the reducer core and the pointer
;; lifecycle: diagnostic construction, generation adoption, and timer
;; invalidation. Specification: docs/design/dao.gui.event.md section
;; Diagnostics.
(ns dao.gui.event.fault)


(defn diagnostic
  [kind severity reason & kvs]
  (merge {:diagnostic/kind kind, :severity severity, :reason reason}
         (apply hash-map kvs)))


(defn finite-number?
  [v]
  (and (number? v)
       (<= -1.7976931348623157E308 (double v) 1.7976931348623157E308)))


(defn generation-fault
  [gen]
  (diagnostic :dao.gui.event/stale-generation-input
              :error :generation-mismatch
              :generation-id gen))


(defn adopt-or-stale-generation
  "Adopt the generation when the interpreter value has none; report a
  stale-generation fault otherwise."
  [state gen]
  (if (nil? (:generation-id state))
    {:state (assoc state :generation-id gen)}
    (if (not= gen (:generation-id state))
      {:fault (generation-fault gen)}
      {:state state})))


(defn timer-key
  [generation-id coordinate-space-id arena-id recognizer-id timer-id timer-seq]
  [generation-id coordinate-space-id arena-id recognizer-id timer-id timer-seq])


(defn timer-request
  [tkey op]
  {:effect/kind :dao.gui.event/timer-request,
   :generation-id (tkey 0),
   :coordinate-space-id (tkey 1),
   :arena-id (tkey 2),
   :recognizer/id (tkey 3),
   :timer-id (tkey 4),
   :timer-seq (tkey 5),
   :timer/op op})


(defn cancel-timer
  "Invalidate one timer record, emitting the cancel effect when it was
  still scheduled. Returns [timers effect?]."
  [timers tkey]
  (if (= :scheduled (get-in timers [tkey :status]))
    [(assoc-in timers [tkey :status] :cancelled) (timer-request tkey :cancel)]
    [(assoc-in timers [tkey :status] :cancelled) nil]))


(defn scheduled-timer-cancels
  "Cancel effects for every still-scheduled timer plus the invalidated
  timers map."
  [timers]
  (let [scheduled-keys
        (vec (filter (fn [tkey] (= :scheduled (get-in timers [tkey :status])))
                     (keys timers)))
        effects (vec (keep (fn [tkey] (timer-request tkey :cancel))
                           scheduled-keys))
        cancelled (reduce (fn [timers tkey]
                            (assoc-in timers [tkey :status] :cancelled))
                          timers
                          scheduled-keys)]
    [cancelled effects]))
