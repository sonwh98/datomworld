(ns dao.runtime.v2.driver
  "Composition-side cadence for dao.runtime.v2 on the JVM.

   The runtime never schedules itself; this namespace is one answer to \"what
   calls run-loop again, and when\": a blocking loop over a LinkedBlockingQueue
   of entries enqueued from other threads. The driver holds no state of its
   own — make-driver returns a value the host keeps in an atom the host
   creates, and every function here takes that atom. The poll interval is a
   parameter, because the right cadence for a REPL is not the right cadence
   for a serving endpoint, and neither is the runtime's to know."
  (:require [dao.runtime.v2 :as rt])
  (:import (java.util.concurrent
             LinkedBlockingQueue
             TimeUnit)))


(def ^:private shutdown-sentinel (Object.))


(defn make-driver
  "Returns a driver state: the runtime state under :rt, a blocking queue for
   entries enqueued from other threads, and the poll interval in ms. The host
   holds it in an atom the host creates."
  ([] (make-driver 50))
  ([poll-ms]
   {:rt (rt/initial-state)
    :queue (LinkedBlockingQueue.)
    :poll-ms poll-ms}))


(defn enqueue-ready!
  "Put entries on the driver's queue. Safe from any thread."
  [driver-atom entries]
  (let [q (:queue @driver-atom)] (doseq [e entries] (.put q e))))


(defn stop!
  "Post the shutdown sentinel; run-loop! returns its final driver state."
  [driver-atom]
  (let [q (:queue @driver-atom)] (.put q shutdown-sentinel)))


(defn run-loop!
  "Blocking run-loop for the JVM.

   Blocks on the queue with the configured timeout. An external entry is
   applied through its :resume — an entry without one carries no work for this
   loop — and followed by one rt/run-loop, which drains the ready queue and
   polls the wait set. On timeout with a non-empty wait set, runs rt/run-loop
   again: a parked task is retried on cadence, with or without external work.
   Otherwise idles, staying alive for later enqueue-ready! calls. Returns
   when stop! posts the sentinel."
  [driver-atom]
  (let [q (:queue @driver-atom)]
    (loop []
      (let [entry (.poll q (long (:poll-ms @driver-atom)) TimeUnit/MILLISECONDS)]
        (cond (= entry shutdown-sentinel) @driver-atom
              (some? entry)
              (let [rt-state (:rt @driver-atom)
                    next-rt (if-let [resume (:resume entry)]
                              (resume rt-state entry (:value entry))
                              rt-state)
                    final-rt (rt/run-loop next-rt)]
                (swap! driver-atom assoc :rt final-rt)
                (recur))
              :else
              (let [rt-state (:rt @driver-atom)]
                (if (seq (:wait-set rt-state))
                  (let [final-rt (rt/run-loop rt-state)]
                    (swap! driver-atom assoc :rt final-rt)
                    (recur))
                  ;; Idle: stay alive.
                  (recur))))))))
