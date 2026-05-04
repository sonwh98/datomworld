(ns dao.runtime.driver-cljs-test
  (:require
    [cljs.test :refer-macros [async deftest is testing]]
    [dao.runtime :as rt]
    [dao.runtime.driver :as driver]
    [dao.stream :as ds]))


(defrecord NonWaitableStream
  [state-atom]

  ds/IDaoStreamReader

  (next
    [_this cursor]
    (let [s @state-atom
          pos (:position cursor)]
      (if (contains? (:buffer s) pos)
        {:ok (get (:buffer s) pos), :cursor {:position (inc pos)}}
        :blocked)))


  ds/IDaoStreamWriter

  (put!
    [_this val]
    (let [tail (:tail @state-atom)]
      (swap! state-atom (fn [s]
                          (-> s
                              (assoc-in [:buffer tail] val)
                              (update :tail inc))))
      {:result :ok}))


  ds/IDaoStreamBound

  (close! [_this] (swap! state-atom assoc :closed true) {:woke []})


  (closed? [_this] (:closed @state-atom)))


(deftest schedule-work-keeps-polling-while-runtime-has-parked-waits-test
  (async
    done
    (testing
      "CLJS driver should keep polling after a task parks in the wait-set"
      (let [seen (atom [])
            stream (->NonWaitableStream (atom
                                          {:buffer {}, :tail 0, :closed false}))
            task {:resume (fn [rt _entry value] (swap! seen conj value) rt)}
            parked-state
            (:state
              (rt/handle-read (rt/initial-state) stream {:position 0} task))]
        (driver/set-runtime! parked-state)
        (driver/schedule-work! [])
        (js/setTimeout (fn []
                         (ds/put! stream :payload)
                         (js/setTimeout (fn [] (is (= [:payload] @seen)) (done))
                                        20))
                       0)))))
