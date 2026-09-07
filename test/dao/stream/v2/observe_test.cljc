(ns dao.stream.v2.observe-test
  "The core observation step: one read, one effect, and the cursor advanced
   only when the effect answered ok."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.observe :as observe]))


(def ^:private at {:pos 0})
(def ^:private successor {:pos 1})


(defn- reader
  "A reader answering `result` to every `next`, recording each call."
  [result calls]
  (reify
    stream/IDaoStreamReader
    (cursor [_ _] {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor at})

    (next
      [_ cursor]
      (swap! calls conj cursor)
      result)))


(defn- read-answer
  "A contract-valid `next` answer for `outcome`."
  [outcome]
  (case outcome
    :dao.stream/ok {:dao.stream/outcome :dao.stream/ok,
                    :dao.stream/value :payload,
                    :dao.stream/cursor successor}
    :dao.stream/gap {:dao.stream/outcome :dao.stream/gap,
                     :dao.stream/cursor successor}
    {:dao.stream/outcome outcome}))


(defn- ok-effect
  [_value]
  {:dao.stream/outcome :dao.stream/ok})


(defn- step-on
  "Run one step over a reader scripted to answer `read-outcome`."
  ([read-outcome] (step-on read-outcome ok-effect))
  ([read-outcome effect]
   (observe/step (reader (read-answer read-outcome) (atom [])) at effect)))


(deftest the-source-is-read-once-at-the-given-cursor
  (doseq [outcome stream/outcomes-next]
    (let [calls (atom [])]
      (observe/step (reader (read-answer outcome) calls) at ok-effect)
      (is (= [at] @calls)
          (str "one read, at the cursor it was handed, on " outcome)))))


(deftest every-read-outcome-maps-to-exactly-one-status
  (testing "the seven declared read outcomes are total over the step's statuses"
    (let [expected {:dao.stream/ok :advance,
                    :dao.stream/blocked :retry,
                    :dao.stream/end :ended,
                    :dao.stream/gap :gap,
                    :dao.stream/cursor-mismatch :defect,
                    :dao.stream/invalid-cursor :defect,
                    :dao.stream/transport-error :defect}]
      (is (= stream/outcomes-next (set (keys expected)))
          "the table covers the declared set exactly, and fails if it grows")
      (doseq [[outcome status] expected]
        (is (= status (:status (step-on outcome)))
            (str outcome " must classify as " status))))))


(deftest every-effect-outcome-maps-to-exactly-one-status
  (testing "the five declared append outcomes are total over the step's statuses"
    (let [expected {:dao.stream/ok :advance,
                    :dao.stream/full :retry,
                    :dao.stream/invalid-value :failed,
                    :dao.stream/closed :failed,
                    :dao.stream/transport-error :failed}]
      (is (= stream/outcomes-append (set (keys expected)))
          "the table covers the declared set exactly, and fails if it grows")
      (doseq [[outcome status] expected]
        (is (= status
               (:status (step-on :dao.stream/ok
                                 (fn [_] {:dao.stream/outcome outcome}))))
            (str "an effect answering " outcome " must classify as " status))))))


(deftest the-cursor-advances-only-on-advance
  (testing "the successor is taken from the read, and only when the effect answered ok"
    (is (= successor (:cursor (step-on :dao.stream/ok))))
    (doseq [outcome [:dao.stream/blocked :dao.stream/end :dao.stream/gap
                     :dao.stream/cursor-mismatch :dao.stream/invalid-cursor
                     :dao.stream/transport-error]]
      (is (= at (:cursor (step-on outcome)))
          (str "the cursor is unchanged on " outcome)))
    (doseq [outcome [:dao.stream/full :dao.stream/invalid-value
                     :dao.stream/closed :dao.stream/transport-error]]
      (is (= at (:cursor (step-on :dao.stream/ok
                                  (fn [_] {:dao.stream/outcome outcome}))))
          (str "a read ok whose effect answered " outcome " keeps the cursor")))))


(deftest recovery-is-present-exactly-on-gap
  (doseq [outcome stream/outcomes-next]
    (let [result (step-on outcome)]
      (if (= :dao.stream/gap outcome)
        (is (= successor (:recovery result)) "gap hands back the recovery cursor")
        (is (not (contains? result :recovery))
            (str "no :recovery on " outcome))))))


(deftest the-effect-runs-exactly-once-and-only-on-a-read-ok
  (testing "a read ok runs the effect once, with the observed value"
    (let [seen (atom [])
          result (step-on :dao.stream/ok (fn [v] (swap! seen conj v) {:dao.stream/outcome :dao.stream/ok}))]
      (is (= [:payload] @seen))
      (is (= :advance (:status result)))))
  (testing "no other read outcome runs the effect at all"
    (doseq [outcome (disj stream/outcomes-next :dao.stream/ok)]
      (let [seen (atom [])]
        (step-on outcome (fn [v] (swap! seen conj v) {:dao.stream/outcome :dao.stream/ok}))
        (is (empty? @seen) (str "the effect must not run on " outcome))))))


(deftest a-throwing-effect-propagates-with-no-result
  (testing "the throw escapes before any successor exists, so the caller keeps its cursor"
    (is (thrown? #?(:clj Exception
                    :cljs js/Error
                    :cljd Object)
          (step-on :dao.stream/ok
                   (fn [_] (throw (ex-info "loader failed" {}))))))))


(deftest a-malformed-read-is-a-defect-with-the-raw-answer-retained
  (doseq [answer [{:dao.stream/outcome :dao.stream/wholly-unexpected}
                  {:dao.stream/outcome :dao.stream/ok}
                  {}
                  nil
                  :not-a-map]]
    (let [result (observe/step (reader answer (atom [])) at ok-effect)]
      (is (= :defect (:status result)) (str "malformed: " (pr-str answer)))
      (is (= :dao.stream/transport-error (:outcome result)))
      (is (= at (:cursor result)) "a malformed answer never moves the cursor")
      (is (= answer (:dao.stream/answer (:read result)))
          "the raw answer is retained so a caller can report what it was told"))))


(deftest a-malformed-effect-answer-is-a-failure-with-the-raw-answer-retained
  (doseq [answer [{:dao.stream/outcome :dao.stream/end} {} nil :not-a-map]]
    (let [result (step-on :dao.stream/ok (fn [_] answer))]
      (is (= :failed (:status result)) (str "malformed effect: " (pr-str answer)))
      (is (= :dao.stream/transport-error (:outcome result)))
      (is (= at (:cursor result)))
      (is (= answer (:dao.stream/answer (:effect result)))))))


(deftest the-read-is-retained-on-every-status
  (doseq [outcome stream/outcomes-next]
    (is (some? (:read (step-on outcome)))
        (str "the raw read answer is available on " outcome))))
