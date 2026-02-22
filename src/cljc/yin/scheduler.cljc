(ns yin.scheduler
  "Shared cooperative scheduler for all Yin VMs.

  Provides check-wait-set which is VM-agnostic: it only touches
  :wait-set, :run-queue, :store and calls yin.stream/handle-next
  and handle-put.

  Each VM keeps its own resume-from-run-queue because the resume
  fields differ per execution model."
  (:require [yin.stream :as stream]))


(defn check-wait-set
  "Check wait-set entries against current store.
   Returns updated state with newly runnable entries moved to run-queue."
  [state]
  (let [wait-set (or (:wait-set state) [])
        run-queue (or (:run-queue state) [])]
    (if (empty? wait-set)
      state
      (let [store (:store state)]
        (loop [remaining wait-set
               new-wait []
               new-run run-queue]
          (if (empty? remaining)
            (assoc state
              :wait-set new-wait
              :run-queue new-run)
            (let [entry (first remaining)
                  rest-entries (rest remaining)]
              (case (:reason entry)
                :next (let [cursor-ref (:cursor-ref entry)
                            cursor-id (:id cursor-ref)
                            cursor-data (get store cursor-id)
                            stream-ref (:stream-ref cursor-data)
                            stream-id (:id stream-ref)
                            _stream (get store stream-id)
                            effect {:effect :stream/next, :cursor cursor-ref}
                            result (stream/handle-next (assoc state
                                                         :store store)
                                                       effect)]
                        (if (:park result)
                          ;; Still blocked
                          (recur rest-entries (conj new-wait entry) new-run)
                          ;; Now has data or stream closed
                          (let [updated-store (:store (:state result))]
                            (recur rest-entries
                                   new-wait
                                   (conj new-run
                                         (assoc entry
                                           :value (:value result)
                                           :store-updates
                                             (when (not= store updated-store)
                                               updated-store)))))))
                :put (let [stream-id (:stream-id entry)
                           _stream (get store stream-id)
                           datom (:datom entry)
                           stream-ref {:type :stream-ref, :id stream-id}
                           effect {:effect :stream/put,
                                   :stream stream-ref,
                                   :val datom}
                           result (stream/handle-put (assoc state :store store)
                                                     effect)]
                       (if (:park result)
                         ;; Still full
                         (recur rest-entries (conj new-wait entry) new-run)
                         ;; Has capacity now
                         (recur rest-entries
                                new-wait
                                (conj new-run
                                      (assoc entry
                                        :value (:value result)
                                        :store-updates (:store (:state
                                                                 result)))))))
                ;; Unknown reason, keep waiting
                (recur rest-entries (conj new-wait entry) new-run)))))))))
