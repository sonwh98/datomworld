(ns dao.runtime.driver
  (:require
    [dao.runtime :as rt]))


(defonce ^:private current-rt (atom (rt/initial-state)))
(defonce ^:private scheduled? (atom false))


(defn- run-pending!
  []
  (reset! scheduled? false)
  (let [rt @current-rt]
    (when-let [next-rt (rt/run-once rt)]
      (reset! current-rt next-rt)
      ;; If there's more work, schedule it immediately
      (when (seq (:ready-queue next-rt))
        (reset! scheduled? true)
        (js/queueMicrotask run-pending!)))))


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
