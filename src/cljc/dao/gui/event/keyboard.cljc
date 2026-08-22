(ns dao.gui.event.keyboard
  (:require [dao.gui.event.fault :as fault]
            [dao.gui.event.trace :as trace]))


(defn- keyboard-sub?
  [sub focus-node phase]
  (and (= (:node-id sub) focus-node)
       (= :keyboard (:event-kind sub))
       (if (nil? (:keyboard/phases sub))
         (or (= phase :down) (= phase :up))
         (contains? (:keyboard/phases sub) phase))))


(defn cancel-held-keys
  "Emits a keyboard :cancel phase and clears :keys-down if keys are held."
  [state runtime-seq reason]
  (let [keys-down (:keys-down state)]
    (if (empty? keys-down)
      [state []]
      (let [focus (:focus state)
            cancel-event {:event/kind :keyboard,
                          :runtime/seq runtime-seq,
                          :event/phase :cancel,
                          :focus-id (:id focus),
                          :node-id (:node-id focus),
                          :reason reason,
                          :released-key-codes (set keys-down)}
            ;; Dispatches
            dispatches
            (into []
                  (keep (fn [sub-id]
                          (let [sub (get-in state [:subscriptions sub-id])]
                            (when (keyboard-sub? sub (:node-id focus) :cancel)
                              {:dispatch/kind :dao.gui.event/subscriber,
                               :subscription/id (:subscription/id sub),
                               :subscriber/id (:subscriber/id sub),
                               :node-id (:node-id sub),
                               :event-kind (:event-kind sub),
                               :event cancel-event}))))
                  (:subscription-order state))]
        [(assoc state :keys-down []) (into [cancel-event] dispatches)]))))


(defn clear-focus-and-keys
  ([state runtime-seq reason]
   (clear-focus-and-keys state runtime-seq reason true))
  ([state runtime-seq reason emit-effect?]
   (let [[st outputs] (cancel-held-keys state runtime-seq reason)
         effect {:effect/kind :dao.gui.event/focus-request, :operation :clear}]
     [(assoc st :focus {:id nil, :node-id nil, :generation-id nil})
      (if emit-effect? (conj outputs effect) outputs)])))


(defn set-focus
  [state runtime-seq focus-value]
  (let [[st outputs] (cancel-held-keys state runtime-seq :focus-lost)
        effect {:effect/kind :dao.gui.event/focus-request,
                :operation :set,
                :focus-id (:focus/id focus-value),
                :node-id (:node-id focus-value),
                :generation-id (:generation-id state)}]
    [(assoc st
            :focus {:id (:focus/id focus-value),
                    :node-id (:node-id focus-value),
                    :generation-id (:generation-id state)})
     (into (vec outputs) [effect])]))


(defn- malformed-keyboard-event?
  [value]
  (let [{:keys [generation-id input-seq time-us phase repeat? modifiers]} value
        key-map (:key value)]
    (or (not (some? generation-id))
        (not (integer? input-seq))
        (not (integer? time-us))
        (not (map? key-map))
        (seq (remove #{:code :logical :location} (keys key-map)))
        (not (keyword? (:code key-map)))
        (not (contains? #{:standard :left :right :numpad :unknown}
                        (:location key-map)))
        (not
          (or
            (nil? (:logical key-map))
            (and
              (string? (:logical key-map))
              (let [s (:logical key-map)
                    len (count s)]
                (if (= 1 len)
                  true
                  (if (= 2 len)
                    #?(:clj (and
                              (Character/isHighSurrogate (.charAt ^String s 0))
                              (Character/isLowSurrogate (.charAt ^String s 1)))
                       :cljs (and (<= 0xD800 (.charCodeAt s 0) 0xDBFF)
                                  (<= 0xDC00 (.charCodeAt s 1) 0xDFFF))
                       :cljd (and (<= 0xD800 (.codeUnitAt ^String s 0) 0xDBFF)
                                  (<= 0xDC00 (.codeUnitAt ^String s 1) 0xDFFF))
                       :default (and
                                  (<= 0xD800 (.codeUnitAt ^String s 0) 0xDBFF)
                                  (<= 0xDC00 (.codeUnitAt ^String s 1) 0xDFFF)))
                    false))))
            (keyword? (:logical key-map))))
        ;; modifiers must be a set, nil is rejected
        (not (set? modifiers))
        (not (every? #{:alt :control :meta :shift :caps-lock :num-lock
                       :scroll-lock}
                     modifiers))
        (not (contains? #{:up :down} phase))
        (not (boolean? repeat?))
        (and (= :up phase) repeat?)
        (not (contains? value :focus-id))
        (seq (remove #{:input/kind :generation-id :input-seq :time-us :phase
                       :focus-id :repeat? :modifiers :key}
                     (keys value))))))


(defn step-keyboard
  [state runtime-input]
  (let [value (:runtime/value runtime-input)
        rt-seq (:runtime/seq runtime-input)
        {:keys [generation-id input-seq focus-id phase repeat? modifiers]} value
        key-map (:key value)
        {adopted :state, fault :fault}
        (fault/adopt-or-stale-generation state generation-id)]
    (cond
      (malformed-keyboard-event? value)
      {:state state,
       :outputs [(fault/diagnostic :dao.gui.event/malformed-keyboard-event
                                   :error
                                   :malformed-keyboard-event)]}
      (some? fault) {:state state, :outputs [fault]}
      :else
      (let [expected (:last-keyboard-seq adopted)
            ;; The first keyboard event in a generation must be seq 0.
            gap? (if (nil? expected)
                   (not= input-seq 0)
                   (not= input-seq (inc expected)))
            [adopted gap-outs]
            (if gap?
              (let [[st cancels] (clear-focus-and-keys adopted
                                                       rt-seq
                                                       :input-sequence-gap
                                                       false)
                    diag (fault/diagnostic :dao.gui.event/input-sequence-gap
                                           :error :input-sequence-gap
                                           :expected-seq (if (nil? expected)
                                                           0
                                                           (inc expected))
                                           :actual-seq input-seq)]
                [st (into [diag] cancels)])
              [adopted []])
            focus (:focus adopted)
            mismatch? (and (some? focus-id) (not= focus-id (:id focus)))
            code (:code key-map)
            keys-down (:keys-down adopted)
            held? (boolean ((set keys-down) code))
            ;; Check diagnostic conditions
            [diag dropped?]
            (cond mismatch? [(fault/diagnostic :dao.gui.event/focus-mismatch
                                               :warning :focus-mismatch
                                               :focus-id focus-id) true]
                  (and (= :down phase) (not repeat?) held?)
                  [(fault/diagnostic :dao.gui.event/duplicate-key-down
                                     :warning :duplicate-key-down
                                     :key-code code) true]
                  (and (= :up phase) (not held?))
                  [(fault/diagnostic :dao.gui.event/orphan-key-up
                                     :warning :orphan-key-up
                                     :key-code code) true]
                  :else [nil false])]
        (if dropped?
          {:state (assoc adopted :last-keyboard-seq input-seq),
           :outputs (cond-> gap-outs diag (conj diag))}
          (let [;; Update keys-down
                next-keys-down
                (cond (and (= :down phase) (not held?))
                      (vec (trace/edn-sort (conj keys-down code)))
                      (= :up phase) (filterv #(not= code %) keys-down)
                      :else keys-down)
                next-state (assoc adopted
                                  :keys-down next-keys-down
                                  :last-keyboard-seq input-seq)
                ;; Construct envelope
                event {:event/kind :keyboard,
                       :runtime/seq rt-seq,
                       :event/phase phase,
                       :focus-id focus-id,
                       :node-id (:node-id focus),
                       :key key-map,
                       :modifiers modifiers,
                       :repeat? repeat?,
                       :time-us (:time-us value)}
                ;; Dispatches (only if focused)
                dispatches
                (if (and (some? focus-id) (not mismatch?))
                  (keep (fn [sub-id]
                          (let [sub (get-in next-state
                                            [:subscriptions sub-id])]
                            (when (keyboard-sub? sub (:node-id focus) phase)
                              {:dispatch/kind :dao.gui.event/subscriber,
                               :subscription/id (:subscription/id sub),
                               :subscriber/id (:subscriber/id sub),
                               :node-id (:node-id sub),
                               :event-kind (:event-kind sub),
                               :event event})))
                        (:subscription-order next-state))
                  [])]
            {:state next-state,
             :outputs (cond-> gap-outs
                        diag (conj diag)
                        true (conj event)
                        (seq dispatches) (into dispatches))}))))))
