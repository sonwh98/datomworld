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
   `dao.stream.apply/serve-once!` and `yin.vm.engine`'s wait set thread
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

   A conforming operation already returns one of the contract outcomes. An
   answer that is not a well-formed outcome map -- a non-map, a missing or
   unqualified outcome keyword, a declared outcome missing its required
   keys -- folds into `transport-error` with the raw answer retained, so
   this step's branches stay total over whatever a composition supplies.
   A well-formed outcome map whose outcome lies outside the operation's
   declared set is a newer contract, not a defect in this host's assembly:
   it is preserved exactly as it arrived, and `step` classifies it as
   `refused` (Result Convention, unrecognized outcomes)."
  [operation result]
  (let [defect (stream/validate-outcome operation result)]
    (if (or (nil? defect) (= :unauthorized-outcome (:error defect)))
      result
      {:dao.stream/outcome :dao.stream/transport-error
       :dao.stream/error :dao.stream/invalid-operation-result
       :dao.stream/answer result})))


(defn step
  "Read one value from `source` at `cursor` and run `effect` on it.

   `effect` is `(fn [value] -> outcome-map)` answering with the writer outcome
   set: `ok`, `full` (\"not yet\"), `invalid-value`, `closed`, `refused`, or
   `transport-error`.  An answer that is not a well-formed outcome map is
   classified as `transport-error` with the raw answer retained.  A
   well-formed outcome outside the declared set is unrecognized -- a newer
   contract, not a defect -- and is preserved as it arrived: on a read it
   classifies as `:refused`, on an effect as `:failed`, exactly as
   `refused` classifies.  An effect that throws propagates.

   Returns a map carrying `:cursor` and `:read` (the raw read answer) in every
   case:

   | status     | when                              | cursor    | also        |
   | ---------- | --------------------------------- | --------- | ----------- |
   | `:advance` | read ok, effect ok                | successor | `:effect`   |
   | `:retry`   | read blocked, or effect full      | unchanged | `:outcome`  |
   | `:ended`   | read end                          | unchanged | `:outcome`  |
   | `:gap`     | read gap                          | unchanged | `:recovery` |
   | `:refused` | read refused or unrecognized;     | unchanged | `:outcome`  |
   |            | nothing was observed              |           |             |
   | `:defect`  | read cursor-mismatch,             | unchanged | `:outcome`  |
   |            | invalid-cursor, transport-error,  |           |             |
   |            | or malformed                      |           |             |
   | `:failed`  | effect invalid-value, closed,     | unchanged | `:outcome`, |
   |            | refused, transport-error,         |           | `:effect`   |
   |            | unrecognized, or malformed        |           |             |

   A `:retry` from the effect also carries `:effect`.  The cursor is the
   successor on `:advance` and unchanged on every other status.  `refused`
   is a policy answer, not a defect: nothing was observed and nothing was
   recorded, and the cursor stays where it was.  An unrecognized outcome
   answers the same: a newer contract is a refusal, never a defect."
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

      :dao.stream/refused {:status :refused
                           :cursor cursor
                           :outcome read-outcome
                           :read read-result}

      (:dao.stream/cursor-mismatch
        :dao.stream/invalid-cursor
        :dao.stream/transport-error)
      {:status :defect
       :cursor cursor
       :outcome read-outcome
       :read read-result}

      ;; A well-formed outcome outside this contract version, preserved by
      ;; valid-or-transport-error: refused, never a defect.  Only a folded
      ;; malformed answer carries transport-error, named in the clause
      ;; above.
      {:status :refused
       :cursor cursor
       :outcome read-outcome
       :read read-result})))
