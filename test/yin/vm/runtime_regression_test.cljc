(ns yin.vm.runtime-regression-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as ds]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]
    [yin.vm.engine :as engine]
    [yin.vm.register :as register]
    [yin.vm.semantic :as semantic]
    [yin.vm.stack :as stack]))


(defrecord NonWaitableStream
  [state-atom]

  ds/IDaoStreamReader

  (next
    [_this cursor]
    (println "DEBUG STREAM: next called with cursor" cursor)
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


(defn- vm-factories
  []
  {:ast-walker ast-walker/create-vm,
   :semantic semantic/create-vm,
   :register register/create-vm,
   :stack stack/create-vm})


(deftest blocked-non-waitable-stream-next-advances-cursor-after-resume-test
  (testing
    "blocked stream/next on non-waitable transports resumes through the VM adapter and advances the cursor"
    (doseq [[vm-type create-vm] (vm-factories)]
      (let [stream-key :test-stream
            cursor-key :test-cursor
            stream (->NonWaitableStream (atom
                                          {:buffer {}, :tail 0, :closed false}))
            block-next (fn [cursor] {:effect :stream/next, :cursor cursor})
            vm0 (-> (create-vm {:env {'block-next block-next}})
                    (assoc-in [:store stream-key] stream)
                    (assoc-in [:store cursor-key]
                              {:stream-id stream-key, :position 0}))
            cursor-ref {:type :cursor-ref, :id cursor-key}
            ast {:type :application,
                 :operator {:type :variable, :name '+},
                 :operands [{:type :literal, :value 1}
                            {:type :application,
                             :operator {:type :variable, :name 'block-next},
                             :operands [{:type :literal, :value cursor-ref}]}]}
            blocked (vm/eval vm0 ast)]
        (is (vm/blocked? blocked)
            (str vm-type " should block on empty non-waitable stream"))
        (is (= :yin/blocked (vm/value blocked))
            (str vm-type " should expose blocked sentinel"))
        (is (= {:stream-id stream-key, :position 0}
               (get (vm/store blocked) cursor-key))
            (str vm-type " should preserve cursor position while parked"))
        (ds/put! stream 41)
        (let [resumed (vm/eval blocked nil)]
          (is (= 42 (vm/value resumed))
              (str vm-type " should resume with the newly readable value"))
          (is (= {:stream-id stream-key, :position 1}
                 (get (vm/store resumed) cursor-key))
              (str vm-type
                   " should advance the parked cursor after resume")))))))


(deftest
  waitable-stream-next-resumes-after-put-with-original-vm-continuation-test
  (testing
    "waitable stream wakeups resume through the original VM continuation instead of a re-wrapped runtime task"
    (doseq [[vm-type create-vm] (vm-factories)]
      (let [stream-key :test-stream
            cursor-key :test-cursor
            stream (ds/open! {:type :ringbuffer, :capacity nil})
            block-next (fn [cursor] {:effect :stream/next, :cursor cursor})
            vm0 (-> (create-vm {:env {'block-next block-next}})
                    (assoc-in [:store stream-key] stream)
                    (assoc-in [:store cursor-key]
                              {:stream-id stream-key, :position 0}))
            cursor-ref {:type :cursor-ref, :id cursor-key}
            ast {:type :application,
                 :operator {:type :variable, :name '+},
                 :operands [{:type :literal, :value 1}
                            {:type :application,
                             :operator {:type :variable, :name 'block-next},
                             :operands [{:type :literal, :value cursor-ref}]}]}
            blocked (vm/eval vm0 ast)]
        (is (vm/blocked? blocked)
            (str vm-type " should block on empty waitable stream"))
        (let [{:keys [woke]} (ds/put! stream 41)
              resumed-state (update blocked
                                    :ready-queue
                                    into
                                    (engine/make-woken-run-queue-entries blocked
                                                                         woke))
              resumed (vm/eval resumed-state nil)]
          (is (= 42 (vm/value resumed))
              (str vm-type " should resume with the woken value"))
          (is (= {:stream-id stream-key, :position 1}
                 (get (vm/store resumed) cursor-key))
              (str vm-type " should advance cursor after waitable wakeup")))))))


(deftest
  close-wakes-waitable-stream-next-without-double-wrapping-runtime-tasks-test
  (testing
    "closing a waitable stream while a VM continuation is parked on stream/next resumes through the original VM restore path"
    (doseq [[vm-type create-vm] (vm-factories)]
      (let [stream-key :test-stream
            cursor-key :test-cursor
            stream (ds/open! {:type :ringbuffer, :capacity nil})
            block-next (fn [cursor] {:effect :stream/next, :cursor cursor})
            vm0 (-> (create-vm {:env {'block-next block-next}})
                    (assoc-in [:store stream-key] stream)
                    (assoc-in [:store cursor-key]
                              {:stream-id stream-key, :position 0}))
            cursor-ref {:type :cursor-ref, :id cursor-key}
            ast {:type :application,
                 :operator {:type :variable, :name 'nil?},
                 :operands [{:type :application,
                             :operator {:type :variable, :name 'block-next},
                             :operands [{:type :literal, :value cursor-ref}]}]}
            blocked (vm/eval vm0 ast)]
        (is (vm/blocked? blocked)
            (str vm-type " should block on empty waitable stream"))
        (let [{:keys [woke]} (ds/close! stream)
              ;; Translate woken entries into VM-ready tasks
              resumed-state (update blocked
                                    :ready-queue
                                    into
                                    (engine/make-woken-run-queue-entries blocked
                                                                         woke))
              resumed (vm/eval resumed-state nil)]
          (is
            (true? (vm/value resumed))
            (str
              vm-type
              " should resume with nil from stream close and continue evaluation"))
          (is (false? (vm/blocked? resumed))
              (str vm-type
                   " should not remain blocked after close wakeup")))))))
