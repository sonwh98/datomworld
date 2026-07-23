(ns yin.vm.runtime-adapter-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.runtime :as rt]
            [yin.vm.runtime-adapter :as adapter]))


(deftest vm-task-test
  (testing "vm-task creates a dao.runtime task wrapping a VM entry"
    (let [vm-entry {:id "task-1", :value 42}
          restore-calls (atom [])
          restore-fn
          (fn [rt entry val] (swap! restore-calls conj [rt entry val]) rt)
          task (adapter/vm-task vm-entry restore-fn)
          rt (rt/initial-state)
          resume-fn (:resume task)]
      (is (fn? resume-fn))
      (let [res (resume-fn rt task 42)]
        (is (= 1 (count @restore-calls)))
        (let [[rt-arg entry-arg val-arg] (first @restore-calls)]
          (is (= 42 val-arg))
          (is (= vm-entry entry-arg))
          (is (false? (:blocked? rt-arg)))))))
  (testing "vm-task calculates store updates when cursor-ref is provided"
    (let [cursor-id :cursor-1
          cursor-data {:position 10}
          vm-entry {:id "task-2", :cursor-ref {:id cursor-id}}
          restore-fn (fn [rt _ _] rt)
          task (adapter/vm-task vm-entry restore-fn)
          rt (assoc (rt/initial-state) :store {cursor-id cursor-data})
          task-entry (assoc task :position 10)
          resume-fn (:resume task)
          res (resume-fn rt task-entry nil)]
      (is (= 11 (get-in res [:store cursor-id :position]))
          "position is advanced past the entry the task was resumed with"))))


(deftest enqueue-woken-vm-entries-test
  (testing "enqueue-woken-vm-entries converts woken entries into ready tasks"
    (let [woken [{:entry {:id "e1"}, :value :val1, :position 0}
                 {:entry {:id "e2"}, :value :val2, :position 1}]
          restore-fn (fn [rt _ _] rt)
          rt (rt/initial-state)
          res (adapter/enqueue-woken-vm-entries rt woken restore-fn)]
      (is (= 2 (count (:ready-queue res)))))))
