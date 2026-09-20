(ns dao.stream.waitset.driver
  "Host policy — the JVM wake source for a wait-set owner.

   This namespace is sleep machinery and nothing else. It holds no
   interpreter state — no waitset, no store, no results, no cadence — and
   dispatches nothing. The owner is an ordinary `loop` on one thread with
   the waitset, the store, and the cadence state as loop locals:

     check → consume :woken → cadence-step → sleep! → recur

   A wake source is a `LinkedBlockingQueue` carrying contentless wake
   tokens only: `nudge!` offers one, and `sleep!` polls with the timeout and
   drains any surplus, so repeated nudges coalesce into one wake. This is
   `select` with the wakeup pipe; the sweep is the syscall."
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))


(defn make-wake
  "A wake source: a queue of contentless tokens (Booleans) and nothing
   else. It is never handed a waitset, a store, or a result."
  []
  (LinkedBlockingQueue.))


(defn nudge!
  "Offer one contentless wake token: ask the owner's current sleep to end
   early. Never blocks, runs no owner code inline, and may be called from
   any thread — a line producer, a signal handler, a composition that just
   appended. Repeated nudges coalesce inside `sleep!`'s drain. A nudge when
   the owner is not sleeping costs one token that the next `sleep!`
   consumes immediately."
  [wake]
  (.offer ^LinkedBlockingQueue wake true)
  nil)


(defn sleep!
  "Park up to `sleep-ms` for one wake token. Returns true when a nudge
   arrived — draining every surplus token first, so however many nudges
   landed, this is the one wake they coalesce into — and false when the
   timeout elapsed. `sleep-ms` is the caller's cadence answer; this
   function adds nothing to it and knows nothing about the round it ends."
  [wake sleep-ms]
  (if (.poll ^LinkedBlockingQueue wake (long sleep-ms) TimeUnit/MILLISECONDS)
    (do (loop []
          (when (.poll ^LinkedBlockingQueue wake)
            (recur)))
        true)
    false))
