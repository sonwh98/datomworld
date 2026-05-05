(ns dao.flow
  (:require
    [dao.flow.hiccup :as hiccup]
    [dao.flow.walk :as walk]
    [dao.runtime :as rt]
    [dao.stream :as ds]))


(def hiccup->datoms hiccup/hiccup->datoms)
(def datoms->hiccup hiccup/datoms->hiccup)
(def walk-xf walk/walk-xf)
(def walk-once walk/walk-once)


(defn- transduce-task-resume
  [rt task-entry value]
  (let [task (or (:task task-entry) task-entry)
        {:keys [step acc-atom stream]} task
        acc @acc-atom]
    (if (contains? task-entry :value)
      (let [[status next-acc]
            (cond (nil? value) [:end (step acc)]
                  (= value :daostream/gap)
                  (throw (ex-info "Stream gap encountered"
                                  {:cursor (:cursor task-entry)}))
                  :else (let [res (step acc value)]
                          (if (reduced? res) [:end (step @res)] [:ok res])))]
        (reset! acc-atom next-acc)
        (if (= status :end)
          (assoc rt :result next-acc)
          (let [res (rt/handle-read rt stream (:cursor task-entry) task)]
            (cond (= (:result res) :ok) (recur (:state res)
                                               {:task task,
                                                :cursor (:cursor res),
                                                :value (:value res),
                                                :resume transduce-task-resume}
                                               (:value res))
                  (= (:result res) :end) (assoc (:state res)
                                                :result (step next-acc))
                  :else (:state res)))))
      ;; Initial call: try to read
      (let [res (rt/handle-read rt stream (:cursor task) task)]
        (cond (= (:result res) :ok) (recur (:state res)
                                           {:task task,
                                            :cursor (:cursor res),
                                            :value (:value res),
                                            :resume transduce-task-resume}
                                           (:value res))
              (= (:result res) :end) (assoc (:state res) :result (step acc))
              :else (:state res))))))


(defn stream-transduce
  "Bridging dao.stream to standard Clojure transducer machinery.
   Reads inputs from `stream` at the given `cursor` and drives the composed
   `(xf rf)` step function until the stream ends or signals `reduced?`.
   Returns the final accumulator."
  [xf rf init stream cursor]
  (let [acc-atom (atom init)
        task {:step (xf rf),
              :acc-atom acc-atom,
              :stream stream,
              :cursor cursor,
              :resume transduce-task-resume}
        rt (rt/initial-state)
        rt' (rt/enqueue-ready rt [task])
        rt-final (rt/run-loop rt')]
    (or (:result rt-final) @acc-atom)))
