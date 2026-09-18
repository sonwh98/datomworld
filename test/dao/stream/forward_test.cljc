(ns dao.stream.forward-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.forward :as forward]
            [dao.stream.ringbuffer :as ring]))


(def spec
  {:dao.stream/type ring/transport-type
   :dao.stream.ringbuffer/capacity 8})


(defn handle
  []
  (:dao.stream/handle (ring/create! spec)))


(defn cursor
  [h anchor]
  (:dao.stream/cursor (stream/cursor h anchor)))


(defn step-options
  ([budget] {:batch-budget budget :gap-policy :terminate})
  ([budget gap-policy] {:batch-budget budget :gap-policy gap-policy}))


(deftest forwards-a-bounded-batch
  (let [source (handle)
        destination (handle)
        c (cursor source :dao.stream/oldest)]
    (doseq [value [:a :b :c]] (stream/append! source value))
    (let [first-step (forward/forward-step source destination
                                           {:cursor c}
                                           (step-options 2))]
      (is (= :continue (:status first-step)))
      (is (= 2 (:forwarded first-step)))
      (let [dc (cursor destination :dao.stream/oldest)
            first-value (stream/next destination dc)
            second-value (stream/next destination (:dao.stream/cursor first-value))]
        (is (= :dao.stream/ok (:dao.stream/outcome first-value)))
        (is (= :a (:dao.stream/value first-value)))
        (is (= :b (:dao.stream/value second-value))))
      (stream/close! source)
      (let [second-step (forward/forward-step source destination first-step
                                              (step-options 2))]
        (is (= :source-ended (:status second-step)))
        (is (= 1 (:forwarded second-step)))))))


(deftest blocked-and-full-do-not-advance-cursor
  (testing "source blocked yields a retry with the same cursor"
    (let [source (handle) destination (handle)
          c (cursor source :dao.stream/newest)
          state {:cursor c}]
      (is (= {:cursor c :status :retry :outcome :dao.stream/blocked :forwarded 0}
             (forward/forward-step source destination state (step-options 3))))))
  (testing "destination full yields a retry with the same cursor"
    (let [source (handle)
          c (cursor source :dao.stream/oldest)]
      (stream/append! source :value)
      ;; A test writer whose append is full makes the retry rule observable.
      (let [full-writer (reify stream/IDaoStreamWriter
                          (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
            result (forward/forward-step source full-writer {:cursor c}
                                         (step-options 1))]
        (is (= :retry (:status result)))
        (is (= :dao.stream/full (:outcome result)))
        (is (= c (:cursor result)))))))


(deftest gap-policy-is-explicit
  (let [source (:dao.stream/handle
                 (ring/create! (assoc spec ring/capacity-key 1)))
        destination (handle)
        c (cursor source :dao.stream/oldest)]
    (stream/append! source :old)
    (stream/append! source :new)
    (testing "terminate preserves the gap as terminal data"
      (let [result (forward/forward-step source destination {:cursor c}
                                         (step-options 2 :terminate))]
        (is (= :source-gap (:status result)))
        (is (= :dao.stream/gap (:outcome result)))
        (is (= c (:cursor result)))))
    (testing "resume follows the recovery cursor"
      (stream/close! source)
      (let [result (forward/forward-step source destination {:cursor c}
                                         (step-options 2 :resume))]
        (is (= :source-ended (:status result)))
        (is (= 1 (:forwarded result)))
        (is (= :new (:dao.stream/value
                      (stream/next destination
                                   (cursor destination :dao.stream/oldest)))))))))


(defn- gapping-source
  "A reader whose every read is a gap.  `recover` maps the read cursor to the
   recovery cursor, which lets one fixture model a defective fixed point and
   another an endlessly retreating history."
  [reads recover]
  (reify stream/IDaoStreamReader
    (cursor [_ _] {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor {:pos 0}})

    (next
      [_ c]
      (swap! reads inc)
      {:dao.stream/outcome :dao.stream/gap :dao.stream/cursor (recover c)})))


(deftest gap-resume-is-bounded-and-total
  (testing "a recovery cursor that does not advance is terminal, not a spin"
    (let [reads (atom 0)
          source (gapping-source reads identity)
          result (forward/forward-step source (handle) {:cursor {:pos 0}}
                                       (step-options 4 :resume))]
      (is (= :source-gap (:status result)))
      (is (= :dao.stream/gap (:outcome result)))
      (is (= {:pos 0} (:cursor result)))
      (is (= 1 @reads))))
  (testing "endless gap recovery is bounded by the batch budget"
    (let [reads (atom 0)
          source (gapping-source reads #(update % :pos inc))
          result (forward/forward-step source (handle) {:cursor {:pos 0}}
                                       (step-options 3 :resume))]
      (is (= :continue (:status result)))
      (is (= 0 (:forwarded result)))
      ;; Three resumes were budgeted; the fourth gap ends the step at the last
      ;; recovery cursor, so the driver keeps its progress.
      (is (= 4 @reads))
      (is (= {:pos 4} (:cursor result))))))


(deftest terminal-outcomes-are-explicit-and-no-close-is-implied
  (let [source (handle) destination (handle)
        c (cursor source :dao.stream/newest)]
    (stream/close! source)
    (let [result (forward/forward-step source destination {:cursor c}
                                       (step-options 1))]
      (is (= :source-ended (:status result)))
      (is (= :dao.stream/end (:outcome result)))
      (is (= :dao.stream/ok
             (:dao.stream/outcome (stream/append! destination :still-open)))))))
