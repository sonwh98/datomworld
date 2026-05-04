(ns dao.runtime.driver
  (:require
    [dao.runtime :as rt])
  (:import
    (java.util.concurrent
      LinkedBlockingQueue
      TimeUnit)))


(defn make-blocking-driver
  "Returns a driver state that uses a BlockingQueue for ready tasks.
   This allows run-loop to block when no work is available."
  []
  (assoc (rt/initial-state) :queue (LinkedBlockingQueue.)))


(defn enqueue-ready!
  "Thread-safe way to add tasks to a blocking driver."
  [rt-atom entries]
  (let [q (:queue @rt-atom)] (doseq [e entries] (.put q e))))


(defn run-loop!
  "Blocking run-loop for the JVM.
   Drains work from the queue and executes it.
   Periodically checks the runtime wait-set for runnable tasks even if the queue is empty.
   Returns when the driver is shut down or a timeout occurs."
  [rt-atom]
  (let [q (:queue @rt-atom)]
    (loop []
      (let [entry (.poll q 50 TimeUnit/MILLISECONDS)]
        (if entry
          (let [rt @rt-atom
                ;; Manually apply the task from the blocking queue
                next-rt ((:resume entry) rt entry (:value entry))
                ;; DRAIN internal ready-queue and check wait-set until
                ;; quiescent
                final-rt (rt/run-loop next-rt)]
            (reset! rt-atom final-rt)
            (recur))
          ;; No external work: check if internal wait-set has become ready
          (let [rt @rt-atom]
            (if (seq (:wait-set rt))
              (let [next-rt (rt/run-loop rt)]
                (reset! rt-atom next-rt)
                (if (empty? (:wait-set next-rt))
                  ;; Finished all work
                  next-rt
                  (recur)))
              rt)))))))
