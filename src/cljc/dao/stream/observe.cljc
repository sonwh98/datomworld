(ns dao.stream.observe
  "One observation step over a DaoStream reader: read a value, act on it, and
   advance the cursor only if the act succeeded.

   `step` owns no scheduler, loop, state, callback or policy.  It classifies
   every outcome the contract declares as data and hands the caller what it
   needs to decide: the successor cursor on progress, the recovery cursor on a
   gap, the raw answer on a defect.  What to do about a gap, whether a defect
   is fatal, how many values to take per turn, and what state to carry between
   turns all belong to the caller.

   The cursor advances in exactly one place -- after the effect has answered
   `ok` -- so effect-before-commit is a property of this seam rather than a
   rule each caller keeps.  An effect that throws propagates before any
   successor exists, which gives a throwing effect the same guarantee for
   free.

   Callers are `dao.stream.forward` (effect: append to another stream),
   `dao.stream.observer` (effect: load a program into a VM) and
   `dao.jing` (effect: materialize content).  Each adds its own loop and its
   own policy above this step.

   The law every interpreter here obeys is that an element's cursor advances
   exactly when its disposition has been durably recorded.  This step serves
   the interpreters whose disposition is an external, refusable effect -- an
   append, a load, a materialization -- where that means advancing only after
   the effect answered ok.  Interpreters whose disposition is a local, total
   state transition keep their own read loops: `dao.stream.rpc/poll!`,
   `dao.stream.apply/serve-once!` and `dao.runtime`'s wait set thread
   whole caller state under a budget with terminal short-circuits, and for
   them advancing first and advancing after the commit are the same fact,
   because no window exists in which one happened and the other did not.
   `serve-once!` is the proof that this is one law and not two families: it
   advances after delivery for a correlatable request, on the diagnostic for
   an uncorrelatable one, and on terminal loss for an undeliverable response.

   There is no ordering parameter here and none is needed.  A consumer that
   wants an element consumed even when its interpretation is defective writes
   a *total* effect -- one that classifies every element as data and so always
   answers ok -- and gets consume-exactly-once through this same step."
  (:require [dao.stream :as stream]))


(defn- valid-or-transport-error
  "Fold a defective host answer into the contract's outcome algebra.

   A conforming operation already returns one of the contract outcomes; this
   keeps the step's branch total when a composition supplies a bad handle or
   an effect answers with something that is not an outcome map."
  [operation result]
  (if (stream/valid-outcome? operation result)
    result
    {:dao.stream/outcome :dao.stream/transport-error
     :dao.stream/error :dao.stream/invalid-operation-result
     :dao.stream/answer result}))


(defn step
  "Read one value from `source` at `cursor` and run `effect` on it.

   `effect` is `(fn [value] -> outcome-map)` answering with the writer outcome
   set: `ok`, `full` (\"not yet\"), `invalid-value`, `closed`, or
   `transport-error`.  Any other answer is classified as `transport-error`
   with the raw answer retained.  An effect that throws propagates.

   Returns a map carrying `:cursor` and `:read` (the raw read answer) in every
   case:

   | status     | when                                    | cursor    | also         |
   | ---------- | --------------------------------------- | --------- | ------------ |
   | `:advance` | read ok, effect ok                      | successor | `:effect`    |
   | `:retry`   | read blocked, or effect full            | unchanged | `:outcome`   |
   | `:ended`   | read end                                | unchanged | `:outcome`   |
   | `:gap`     | read gap                                | unchanged | `:recovery`  |
   | `:defect`  | read cursor-mismatch, invalid-cursor,
                  transport-error, or malformed                | unchanged | `:outcome`   |
   | `:failed`  | effect invalid-value, closed,
                  transport-error, or malformed                | unchanged | `:outcome`, `:effect` |

   A `:retry` from the effect also carries `:effect`.  The cursor is the
   successor on `:advance` and unchanged on every other status."
  [source cursor effect]
  (let [read-result (valid-or-transport-error :next (stream/next source cursor))
        read-outcome (:dao.stream/outcome read-result)]
    (case read-outcome
      :dao.stream/ok
      (let [effect-result (valid-or-transport-error
                            :append!
                            (effect (:dao.stream/value read-result)))
            effect-outcome (:dao.stream/outcome effect-result)]
        (case effect-outcome
          :dao.stream/ok {:status :advance
                          :cursor (:dao.stream/cursor read-result)
                          :read read-result
                          :effect effect-result}
          :dao.stream/full {:status :retry
                            :cursor cursor
                            :outcome effect-outcome
                            :read read-result
                            :effect effect-result}
          {:status :failed
           :cursor cursor
           :outcome effect-outcome
           :read read-result
           :effect effect-result}))

      :dao.stream/blocked {:status :retry
                           :cursor cursor
                           :outcome read-outcome
                           :read read-result}

      :dao.stream/end {:status :ended
                       :cursor cursor
                       :outcome read-outcome
                       :read read-result}

      :dao.stream/gap {:status :gap
                       :cursor cursor
                       :outcome read-outcome
                       :recovery (:dao.stream/cursor read-result)
                       :read read-result}

      {:status :defect
       :cursor cursor
       :outcome read-outcome
       :read read-result})))
