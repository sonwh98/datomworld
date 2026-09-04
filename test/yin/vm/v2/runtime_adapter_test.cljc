(ns yin.vm.v2.runtime-adapter-test
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm.v2.runtime-adapter :as adapter]))


(defn- restore
  [base entry value]
  (assoc base :restored {:k (:k entry), :value value}))


(deftest vm-task-resumes-with-the-woken-value-test
  (testing "The wrapper carries the original entry and a resume fn"
    (let [task (adapter/vm-task {:k :cont, :env {}} restore)]
      (is (fn? (:resume task)))
      (is (= {:k :cont, :env {}} (:task task)))))
  (testing "Resuming clears blocked and halted and restores the continuation"
    (let [task (adapter/vm-task {:k :cont} restore)
          resumed ((:resume task)
                   {:store {}, :blocked? true, :halted? true}
                   (assoc task :value 7)
                   7)]
      (is (= {:k :cont, :value 7} (:restored resumed)))
      (is (false? (:blocked? resumed)))
      (is (false? (:halted? resumed))))))


(deftest reader-store-updates-carry-the-opaque-successor-test
  (testing "A woken reader's stored cursor becomes the returned successor"
    (let [entry {:k :cont, :cursor-ref {:type :cursor-ref, :id :cursor-0}}
          task (adapter/vm-task entry restore)
          rt {:store {:cursor-0 {:stream-id :stream-0, :cursor :old}},
              :blocked? true}
          resumed ((:resume task) rt (assoc task :value :v :cursor :new) :v)]
      (is (= {:stream-id :stream-0, :cursor :new}
             (get-in resumed [:store :cursor-0]))))))


(deftest writer-entries-keep-their-own-updates-test
  (testing "A writer has no cursor-ref, so its :store-updates are used as-is"
    (let [entry {:k :cont, :store-updates {:k1 :v1}}
          task (adapter/vm-task entry restore)
          resumed ((:resume task) {:store {}} (assoc task :value :done) :done)]
      (is (= {:k1 :v1} (:store resumed))))))


(deftest enqueue-woken-vm-entries-test
  (testing "Woken entries become ready-queue tasks stamped with value and cursor"
    (let [rt (adapter/enqueue-woken-vm-entries
               {:ready-queue []}
               [{:entry {:k :a}, :value 1, :cursor :c1}]
               restore)
          entry (first (:ready-queue rt))]
      (is (= 1 (:value entry)))
      (is (= :c1 (:cursor entry)))
      (is (fn? (:resume entry))))))


(defn- ex-data-of
  "Run a thunk and return the ex-data of whatever it threw, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj Exception :cljs js/Error :cljd Object) e (ex-data e))))


(defn- resume-task
  "Resume a wrapped task whose retry ended with `status`, returning the
   thrown ex-data or the restored state."
  [task status value]
  (try
    ((:resume task)
     {:store {:cursor-0 {:stream-id :stream-0, :cursor :c0}}}
     (assoc task :status status, :value value, :cursor nil)
     value)
    (catch #?(:clj Exception :cljs js/Error :cljd Object) e
      {:thrown (ex-data e)})))


(deftest terminal-resume-statuses-fail-like-the-immediate-path-test
  (testing "A read retry that ended cursor-mismatch, invalid-cursor or
            transport-error fails as a read, not as a value"
    (let [entry {:k :cont,
                 :reason :next,
                 :stream-id :stream-0,
                 :cursor-ref {:type :cursor-ref, :id :cursor-0}}
          task (adapter/vm-task entry restore)
          resume (fn [status] (:thrown (resume-task task status status)))]
      (is (= {:outcome :dao.stream/cursor-mismatch,
              :stream-id :stream-0,
              :cursor-id :cursor-0}
             (resume :dao.stream/cursor-mismatch)))
      (is (= {:outcome :dao.stream/invalid-cursor,
              :stream-id :stream-0,
              :cursor-id :cursor-0}
             (resume :dao.stream/invalid-cursor)))
      (is (= {:outcome :dao.stream/transport-error,
              :stream-id :stream-0,
              :cursor-id :cursor-0}
             (resume :dao.stream/transport-error)))))
  (testing "A write retry that ended closed, invalid-value or transport-error
            fails as an append, not as nil"
    (let [entry {:k :cont, :reason :put, :stream-id :stream-0, :datom :v}
          task (adapter/vm-task entry restore)
          resume (fn [status] (:thrown (resume-task task status nil)))]
      (is (= {:outcome :dao.stream/closed, :stream-id :stream-0}
             (resume :end))
          "A writer's :end is a closed append wearing its task classification")
      (is (= {:outcome :dao.stream/invalid-value, :stream-id :stream-0}
             (resume :dao.stream/invalid-value)))
      (is (= {:outcome :dao.stream/transport-error, :stream-id :stream-0}
             (resume :dao.stream/transport-error))))))


(deftest non-terminal-statuses-resume-as-values-test
  (testing "Reader end, gap and ok resume the continuation with their value"
    (let [entry {:k :cont,
                 :reason :next,
                 :stream-id :stream-0,
                 :cursor-ref {:type :cursor-ref, :id :cursor-0}}
          task (adapter/vm-task entry restore)]
      (is (= {:k :cont, :value nil} (:restored (resume-task task :end nil))))
      (is (= {:k :cont, :value :dao.stream/gap}
             (:restored (resume-task task :dao.stream/gap :dao.stream/gap))))
      (is (= {:k :cont, :value :v} (:restored (resume-task task :ok :v))))))
  (testing "A writer resumed on ok delivers the appended value"
    (let [entry {:k :cont, :reason :put, :stream-id :stream-0, :datom :v}
          task (adapter/vm-task entry restore)]
      (is (= {:k :cont, :value :v} (:restored (resume-task task :ok :v)))))))
