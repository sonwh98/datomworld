(ns dao.runtime.driver
  (:require
    [dao.runtime :as rt])
  (:import
    (java.util.concurrent
      LinkedBlockingQueue
      TimeUnit)))


(def ^:private shutdown-sentinel (Object.))


(defn make-blocking-driver
  "Returns a driver state that uses a BlockingQueue for ready tasks.
   This allows run-loop to block when no work is available."
  []
  (assoc (rt/initial-state) :queue (LinkedBlockingQueue.)))


(defn enqueue-ready!
  "Thread-safe way to add tasks to a blocking driver."
  [rt-atom entries]
  (let [q (:queue @rt-atom)] (doseq [e entries] (.put q e))))


(defn stop!
  "Send a shutdown signal to the driver loop."
  [rt-atom]
  (let [q (:queue @rt-atom)] (.put q shutdown-sentinel)))


(defn run-loop!
  "Blocking run-loop for the JVM.
   Drains work from the queue and executes it.
   Periodically checks the runtime wait-set for runnable tasks even if the queue is empty.
   Returns when the driver is shut down."
  [rt-atom]
  (let [q (:queue @rt-atom)]
    (loop []
      (let [entry (.poll q 50 TimeUnit/MILLISECONDS)]
        (cond (= entry shutdown-sentinel) @rt-atom
              entry (let [rt @rt-atom
                          ;; Manually apply the task from the blocking
                          ;; queue
                          next-rt ((:resume entry) rt entry (:value entry))
                          ;; DRAIN internal ready-queue and check wait-set
                          ;; until quiescent
                          final-rt (rt/run-loop next-rt)]
                      (reset! rt-atom final-rt)
                      (recur))
              :else
              ;; No external work: check if internal wait-set has become
              ;; ready
              (let [rt @rt-atom]
                (if (seq (:wait-set rt))
                  (let [next-rt (rt/run-loop rt)]
                    (reset! rt-atom next-rt)
                    (recur))
                  ;; Idle: just recur to stay alive
                  (recur))))))))
