(ns dao.runtime.driver
  (:require
    [dao.runtime :as rt]))


(defonce ^:private current-rt (atom (rt/initial-state)))
(defonce ^:private scheduled? (atom false))


(defn- run-pending!
  []
  (reset! scheduled? false)
  (let [rt @current-rt
        next-rt (rt/run-once rt)]
    (when next-rt (reset! current-rt next-rt))
    (let [final-rt (or next-rt rt)]
      (cond (seq (:ready-queue final-rt)) (do (reset! scheduled? true)
                                              (js/queueMicrotask run-pending!))
            (seq (:wait-set final-rt)) (do (reset! scheduled? true)
                                           (js/setTimeout run-pending! 20))))))


(defn schedule-work!
  "Schedule runtime tasks to run on the next JS microtask."
  [entries]
  (swap! current-rt rt/enqueue-ready entries)
  (when-not @scheduled?
    (reset! scheduled? true)
    (js/queueMicrotask run-pending!)))


(defn set-runtime!
  "Initialize or update the active driver state."
  [rt]
  (reset! current-rt rt))
