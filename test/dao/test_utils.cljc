(ns dao.test-utils
  (:require [dao.stream :as ds]))


(defrecord NonWaitableStream
  [state-atom]
  ;; A mock stream that simulates a polled (non-waitable) transport.
  ;; Returns :blocked from ds/next and :full from ds/append! when
  ;; appropriate, but never registers waiters or provides wakeups. Used to
  ;; verify that runtime schedulers correctly fall back to polling.
  ds/IDaoStreamReader

  (next
    [_this cursor]
    (let [s @state-atom
          pos (:position cursor)]
      (if (contains? (:buffer s) pos)
        {:ok (get (:buffer s) pos), :cursor {:position (inc pos)}}
        :blocked)))


  ds/IDaoStreamWriter

  (append!
    [_this val]
    (let [s @state-atom
          tail (:tail s)]
      (if (and (:capacity s) (>= (count (:buffer s)) (:capacity s)))
        {:result :full}
        (do (swap! state-atom (fn [s]
                                (-> s
                                    (assoc-in [:buffer tail] val)
                                    (update :tail inc))))
            {:result :ok}))))


  ds/IDaoStreamBound

  (close! [_this] (swap! state-atom assoc :closed true) {:woke []})


  (closed? [_this] (:closed @state-atom)))
