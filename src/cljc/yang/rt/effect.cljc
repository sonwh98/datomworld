(ns yang.rt.effect
  "Stateless guest control over a task-owned context cell and %capture."
  (:require [yang.core.prelude :as core]
            [yang.core.prelude-admission :as prelude-admission]
            [yang.core.uast :as u]))


(def module-name 'yrt.effect)

(def host-names '#{cell/get cell/set! cell/new data/nth})


(def definitions
  "The ordered source for bundled and linked emission."
  '[[yrt.effect/ctx
     (fn [hs depth base frame]
       (assoc {} :handlers hs :depth depth :base base :frame frame))]
    [yrt.effect/handlers
     (fn [ctx-cell] (get (cell/get ctx-cell) :handlers))]
    [yrt.effect/set-handlers!
     (fn [ctx-cell hs]
       (cell/set! ctx-cell (assoc (cell/get ctx-cell) :handlers hs)))]
    [yrt.effect/restore!
     (fn [ctx-cell saved]
       (cell/set! ctx-cell (assoc saved :base (get (cell/get ctx-cell) :base))))]
    [yrt.effect/frame-depth
     (fn [hs] (if (= hs nil) 0 (data/nth hs 3)))]
    [yrt.effect/frame
     (fn [kind payload rest depth]
       (conj [] kind payload rest (+ 1 (yrt.effect/frame-depth rest)) depth))]
    [yrt.effect/pop-frame
     (fn [ctx-cell hs]
       (cell/set! ctx-cell
                  (assoc (cell/get ctx-cell)
                         :handlers (data/nth hs 2) :depth (data/nth hs 4))))]
    [yrt.effect/raise
     (fn [ctx-cell e on-empty]
       (let [hs (yrt.effect/handlers ctx-cell)]
         (if (= hs nil)
           (on-empty e)
           (let [kind (data/nth hs 0)]
             (if (= kind :finally)
               (do (yrt.effect/pop-frame ctx-cell hs)
                   ((data/nth hs 1) e)
                   (yrt.effect/raise ctx-cell e on-empty))
               (if (= kind :effect)
                 (do (yrt.effect/pop-frame ctx-cell hs)
                     (yrt.effect/raise ctx-cell e on-empty))
                 ((data/nth hs 1) e)))))))]
    [yrt.effect/try-orelse
     (fn [ctx-cell body handler orelse]
       (let [saved (cell/get ctx-cell)
             first? (cell/new true)]
         ((fn [r]
            (if (cell/get first?)
              (do (cell/set! first? false)
                  (yrt.effect/set-handlers!
                    ctx-cell (yrt.effect/frame :handler r (get saved :handlers)
                                               (get saved :depth)))
                  (let [v (body)]
                    (do (yrt.effect/restore! ctx-cell saved)
                        (if (= orelse nil) v (orelse)))))
              (do (yrt.effect/restore! ctx-cell saved) (handler r))))
          (%capture))))]
    [yrt.effect/try-value
     (fn [ctx-cell body handler]
       (yrt.effect/try-orelse ctx-cell body handler nil))]
    [yrt.effect/try-finally
     (fn [ctx-cell body fin normal-arg]
       (let [saved (cell/get ctx-cell)]
         (do (yrt.effect/set-handlers!
               ctx-cell (yrt.effect/frame :finally fin (get saved :handlers)
                                          (get saved :depth)))
             (let [v (body)]
               (do (yrt.effect/restore! ctx-cell saved) (fin normal-arg) v)))))]
    [yrt.effect/unwind-to
     (fn [ctx-cell n normal-arg]
       (let [hs (yrt.effect/handlers ctx-cell)]
         (if (<= (yrt.effect/frame-depth hs) n)
           nil
           (do (yrt.effect/pop-frame ctx-cell hs)
               (if (= (data/nth hs 0) :finally) ((data/nth hs 1) normal-arg) nil)
               (yrt.effect/unwind-to ctx-cell n normal-arg)))))]
    [yrt.effect/call-ec
     (fn [ctx-cell f normal-arg]
       (let [saved (cell/get ctx-cell)
             first? (cell/new true)]
         ((fn [r]
            (if (cell/get first?)
              (do (cell/set! first? false)
                  (f (fn [v]
                       (do (yrt.effect/unwind-to ctx-cell
                             (yrt.effect/frame-depth (get saved :handlers)) normal-arg)
                           (yrt.effect/restore! ctx-cell saved)
                           (r v)))))
              r))
          (%capture))))]
    [yrt.effect/abandon-to
     (fn [ctx-cell n]
       (let [hs (yrt.effect/handlers ctx-cell)]
         (if (<= (yrt.effect/frame-depth hs) n)
           nil
           (do (yrt.effect/pop-frame ctx-cell hs)
               (yrt.effect/abandon-to ctx-cell n)))))]
    [yrt.effect/call-terminal
     (fn [ctx-cell f]
       (let [saved (cell/get ctx-cell)
             first? (cell/new true)]
         ((fn [r]
            (if (cell/get first?)
              (do (cell/set! first? false)
                  (f (fn [v]
                       (do (yrt.effect/abandon-to ctx-cell
                             (yrt.effect/frame-depth (get saved :handlers)))
                           (yrt.effect/restore! ctx-cell saved)
                           (r v)))))
              r))
          (%capture))))]

    [yrt.effect/handle
     (fn [ctx-cell tag handler-fn body]
       (let [saved (cell/get ctx-cell)
             first? (cell/new true)]
         ((fn [outer-k]
            (if (cell/get first?)
              (do (cell/set! first? false)
                  (yrt.effect/set-handlers!
                    ctx-cell (yrt.effect/frame :effect (conj [] tag handler-fn outer-k)
                                               (get saved :handlers) (get saved :depth)))
                  (let [v (body)] (do (yrt.effect/restore! ctx-cell saved) v)))
              ;; perform transfers a thunk here so the handler runs in the
              ;; exact context outside the matched delimiter.
              (do (yrt.effect/restore! ctx-cell saved) (outer-k))))
          (%capture))))]
    [yrt.effect/_find-effect
     (fn [hs tag]
       (if (= hs nil)
         nil
         (if (if (= (data/nth hs 0) :effect)
               (= (data/nth (data/nth hs 1) 0) tag)
               false)
           hs
           (yrt.effect/_find-effect (data/nth hs 2) tag))))]
    [yrt.effect/_dispatch
     (fn [ctx-cell hs payload]
       (let [saved (cell/get ctx-cell)
             h (data/nth hs 1)
             handler (data/nth h 1)
             outer-k (data/nth h 2)
             pending? (cell/new true)]
         ((fn [k]
            (if (cell/get pending?)
              (outer-k
                (fn []
                  ;; The guard also runs if the handler raises or escapes.
                  ;; Resuming bypasses it and reinstates the suspended frames.
                  (let [outside (cell/get ctx-cell)]
                    (yrt.effect/try-finally
                      ctx-cell
                      (fn []
                        (handler payload
                          (fn [v]
                            (if (cell/get pending?)
                              (do (cell/set! pending? false)
                                  (yrt.effect/restore! ctx-cell saved)
                                  (k v))
                              (:yrt.effect/used-resumption v)))))
                      (fn [e]
                        (do (cell/set! pending? false)
                            (yrt.effect/restore! ctx-cell saved)
                            (yrt.effect/unwind-to ctx-cell
                              (yrt.effect/frame-depth (get outside :handlers)) e)
                            (yrt.effect/restore! ctx-cell outside)))
                      nil))))
              k))
          (%capture))))]
    [yrt.effect/perform
     (fn [ctx-cell tag payload on-unhandled]
       (let [hs (yrt.effect/_find-effect (yrt.effect/handlers ctx-cell) tag)]
         (if (= hs nil)
           (on-unhandled tag payload)
           (yrt.effect/_dispatch ctx-cell hs payload))))]])


(def module-exports
  '#{ctx handlers set-handlers! restore! frame-depth frame pop-frame raise
     try-value try-orelse try-finally unwind-to call-ec abandon-to call-terminal
     handle perform})


(def uast
  (u/mark-tails (core/definitions->uast definitions)))


(def module-uast
  (u/annotate-call-sites
    (core/module-tree (core/module-definitions "yrt.effect" nil [] definitions) [])))


(def ^:private module-free-names (core/free-name-vec module-uast))


(defn module-spec
  [registry]
  (prelude-admission/module-spec
    {:module-name module-name
     :ast module-uast
     :exports module-exports
     :requires {}
     :free-names module-free-names
     :registry registry
     :message "the yrt.effect module reads an undeclared name"
     :refusal-ns "yrt.effect"}))
