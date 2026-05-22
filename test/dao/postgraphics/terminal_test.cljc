(ns dao.postgraphics.terminal-test
  (:require
    [clojure.test :refer [deftest is]]
    [dao.postgraphics.terminal :as term]
    [dao.stream :as ds]
    [dao.stream.ringbuffer]))


(defn- make-stream
  ([] (ds/open! {:type :ringbuffer, :capacity nil}))
  ([capacity eviction-policy]
   (ds/open! {:type :ringbuffer,
              :capacity capacity,
              :eviction-policy eviction-policy})))


(defn- validating-presenter
  [accepted]
  {:validate-frame!
   (fn [frame]
     (when (= :reject frame)
       (throw (ex-info "rejected"
                       {:dao.postgraphics/reason :validation-failure})))
     (when (= :unsupported frame)
       (throw (ex-info "unsupported"
                       {:dao.postgraphics/reason :unsupported-op})))),
   :present-frame! (fn [frame] (swap! accepted conj frame))})


(deftest bind-stream-emits-reset-and-presents-accepted-frames
  (let [frames (make-stream)
        accepted (atom [])
        signals (make-stream)]
    (term/bind-stream! frames
                       (merge (validating-presenter accepted)
                              {:signal-stream signals, :generation-id "gen-a"}))
    (is (= :ok (term/put-frame! frames [:frame/a])))
    (is (= [[:frame/a]] @accepted))
    (let [signal (:ok (ds/next signals {:position 0}))]
      (is (= :dao.terminal/reset (:message/kind signal)))
      (is (= "gen-a" (:generation-id signal))))))


(deftest bind-stream-emits-canonical-rejection
  (let [frames (make-stream)
        accepted (atom [])
        signals (make-stream)
        errors (atom [])]
    (term/bind-stream! frames
                       (merge (validating-presenter accepted)
                              {:signal-stream signals,
                               :on-error #(swap! errors conj %)}))
    (term/put-frame! frames :reject)
    (is (empty? @accepted))
    (is (= 1 (count @errors)))
    (is (= {:message/kind :dao.terminal/rejection,
            :submission-id 0,
            :reason :validation-failure}
           (:ok (ds/next signals {:position 1}))))))


(deftest bind-stream-emits-frame-skipped-on-gap
  (let [frames (make-stream 1 :evict-oldest)
        accepted (atom [])
        signals (make-stream)]
    (ds/put! frames [:frame/evicted])
    (ds/put! frames [:frame/presented])
    (term/bind-stream! frames
                       (merge (validating-presenter accepted)
                              {:signal-stream signals,
                               :generation-id-fn (fn [] "gen-b")}))
    (is (= [[:frame/presented]] @accepted))
    (let [signal (:ok (ds/next signals {:position 0}))]
      (is (= :dao.terminal/reset (:message/kind signal)))
      (is (= "gen-b" (:generation-id signal))))
    (is (= {:message/kind :dao.terminal/frame-skipped, :submission-id 0}
           (:ok (ds/next signals {:position 1}))))
    (is (= :blocked (ds/next signals {:position 2})))))


(deftest closed-binding-stops-presenting-frames
  (let [frames (make-stream)
        accepted (atom [])
        handle (term/bind-stream! frames (validating-presenter accepted))]
    ((:close! handle))
    (is (= :ok (term/put-frame! frames [:frame/after-close])))
    (is (empty? @accepted))))
