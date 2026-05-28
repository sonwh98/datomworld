(ns dao.gui.runtime-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.gui.compiler :as compiler]
    [dao.gui.runtime :as runtime]))


(deftest coalescing-works
  (testing "multiple synchronous atom changes produce only one re-render"
    (let [a (atom 0)
          renders (atom 0)
          scheduler-calls (atom [])
          ;; Mock scheduler that just records the task
          scheduler (fn [task] (swap! scheduler-calls conj task))
          _ (runtime/create-runtime
              {:root-form (fn []
                            (swap! renders inc)
                            [:rect
                             {:width (compiler/read-atom a), :height 10}]),
               :atoms [a],
               :scheduler scheduler,
               :consumer (fn [_ops])})]
      ;; Initial compile is scheduled
      (is (= 1 (count @scheduler-calls)))
      ;; Run the initial compile
      ((first @scheduler-calls))
      (is (= 1 @renders))
      ;; Synchronous changes
      (reset! scheduler-calls [])
      (swap! a inc)
      (swap! a inc)
      (swap! a inc)
      ;; Only one scheduler call
      (is (= 1 (count @scheduler-calls)))
      ;; Run it
      ((first @scheduler-calls))
      (is (= 2 @renders)))))


(deftest re-entrancy-guard-works
  (testing "mutating a watched atom during compile should throw"
    (let [a (atom 0)
          scheduler-calls (atom [])
          scheduler (fn [task] (swap! scheduler-calls conj task))
          _ (runtime/create-runtime {:root-form (fn []
                                                  (swap! a inc) ; Illegal
                                                  ;; mutation!
                                                  [:rect
                                                   {:width 10, :height 10}]),
                                     :atoms [a],
                                     :scheduler scheduler,
                                     :consumer (fn [_ops])})]
      ;; Run the initial compile
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Re-entrant mutation"
            ((first @scheduler-calls)))))))


(deftest re-entrant-mutation-schedules-follow-up-compile
  (testing
    "when a compile-time mutation is rejected, runtime should schedule another compile for the updated state"
    (let [a (atom 0)
          scheduler-calls (atom [])
          renders (atom [])
          errors (atom [])
          mutate-once? (atom true)
          _ (runtime/create-runtime
              {:root-form
               (fn []
                 (when @mutate-once? (reset! mutate-once? false) (reset! a 1))
                 (swap! renders conj (compiler/read-atom a))
                 [:rect {:width (compiler/read-atom a), :height 10}]),
               :atoms [a],
               :scheduler (fn [task] (swap! scheduler-calls conj task)),
               :consumer (fn [_ops]),
               :on-error (fn [e] (swap! errors conj e))})]
      (is (= 1 (count @scheduler-calls)))
      ((first @scheduler-calls))
      (is (= 1 (count @errors)))
      (is
        (= 2 (count @scheduler-calls))
        "a follow-up compile should be queued for the mutation that happened during compile")
      (when (= 2 (count @scheduler-calls))
        ((second @scheduler-calls))
        (is (= [1] @renders)
            "the follow-up compile should render the updated atom value")))))


(deftest multiple-runtimes-on-one-atom-do-not-clobber-each-other
  (testing "each runtime should keep an independent watch when sharing atoms"
    (let [a (atom 0)
          scheduler-calls-1 (atom [])
          scheduler-calls-2 (atom [])
          renders-1 (atom 0)
          renders-2 (atom 0)
          runtime-1 (runtime/create-runtime
                      {:root-form
                       (fn []
                         (swap! renders-1 inc)
                         [:rect {:width (compiler/read-atom a), :height 10}]),
                       :atoms [a],
                       :scheduler (fn [task]
                                    (swap! scheduler-calls-1 conj task)),
                       :consumer (fn [_ops])})
          _runtime-2
          (runtime/create-runtime
            {:root-form (fn []
                          (swap! renders-2 inc)
                          [:rect
                           {:width (compiler/read-atom a), :height 10}]),
             :atoms [a],
             :scheduler (fn [task] (swap! scheduler-calls-2 conj task)),
             :consumer (fn [_ops])})]
      (doseq [task @scheduler-calls-1] (task))
      (doseq [task @scheduler-calls-2] (task))
      (is (= 1 @renders-1))
      (is (= 1 @renders-2))
      (reset! scheduler-calls-1 [])
      (reset! scheduler-calls-2 [])
      (swap! a inc)
      (is
        (= 1 (count @scheduler-calls-1))
        "first runtime should still receive atom updates after the second runtime is created")
      (is (= 1 (count @scheduler-calls-2)))
      (doseq [task @scheduler-calls-1] (task))
      (doseq [task @scheduler-calls-2] (task))
      ((:dispose runtime-1))
      (reset! scheduler-calls-1 [])
      (reset! scheduler-calls-2 [])
      (swap! a inc)
      (is (= 0 (count @scheduler-calls-1)))
      (is
        (= 1 (count @scheduler-calls-2))
        "disposing one runtime should not remove the other runtime's watch"))))


(deftest missing-atom-in-runtime-snapshot-throws
  (testing
    "reading an atom that was not included in the runtime snapshot should fail fast"
    (let [a (atom 42)
          scheduler-calls (atom [])
          _ (runtime/create-runtime
              {:root-form (fn []
                            [:rect
                             {:width (compiler/read-atom a), :height 10}]),
               :atoms [],
               :scheduler (fn [task] (swap! scheduler-calls conj task)),
               :consumer (fn [_ops])})]
      (is (= 1 (count @scheduler-calls))))
    (let [a (atom 42)
          scheduler-calls (atom [])
          _ (runtime/create-runtime
              {:root-form (fn []
                            [:rect
                             {:width (compiler/read-atom a), :height 10}]),
               :atoms [],
               :scheduler (fn [task] (swap! scheduler-calls conj task)),
               :consumer (fn [_ops])})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"atom|snapshot"
            ((first @scheduler-calls)))))))


(deftest on-error-receives-compile-exceptions
  (testing "compile-ui throws should be routed to :on-error when provided"
    (let [errors (atom [])
          scheduler-calls (atom [])
          _ (runtime/create-runtime
              {:root-form (fn [] (throw (ex-info "boom" {:why :test}))),
               :atoms [],
               :scheduler (fn [task] (swap! scheduler-calls conj task)),
               :consumer (fn [_ops]),
               :on-error (fn [e] (swap! errors conj e))})]
      ((first @scheduler-calls))
      (is (= 1 (count @errors)))
      (is (= "boom" (ex-message (first @errors))))))
  (testing
    "without :on-error, compile exceptions bubble out of the scheduled task"
    (let [scheduler-calls (atom [])
          _ (runtime/create-runtime
              {:root-form (fn [] (throw (ex-info "boom" {:why :test}))),
               :atoms [],
               :scheduler (fn [task] (swap! scheduler-calls conj task)),
               :consumer (fn [_ops])})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"boom"
            ((first @scheduler-calls)))))))


(deftest snapshot-build-runs-under-compile-guard
  (testing
    "mutating a watched atom during snapshot assembly should trip the re-entrancy guard"
    (let [a (atom 0)
          b (atom 0)
          scheduler-calls (atom [])
          seen (atom nil)]
      (with-redefs-fn {#'dao.gui.runtime/create-snapshot (fn [[a b]]
                                                           (let [a-val @a]
                                                             (reset! b 1)
                                                             {a a-val, b @b}))}
        (fn []
          (runtime/create-runtime
            {:root-form (fn []
                          (reset! seen [(compiler/read-atom a)
                                        (compiler/read-atom b)])
                          [:rect {:width 10, :height 10}]),
             :atoms [a b],
             :scheduler (fn [task] (swap! scheduler-calls conj task)),
             :consumer (fn [_ops])})
          (is (= 1 (count @scheduler-calls)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Re-entrant mutation"
                ((first @scheduler-calls))))
          (is
            (nil? @seen)
            "compile should fail before a mixed snapshot reaches the root form"))))))


(deftest cross-thread-mutation-during-compile-is-rejected
  (testing
    "a watched atom mutated from another thread during snapshot assembly should also trip the compile guard"
    (let [a (atom 0)
          b (atom 0)
          scheduler-calls (atom [])
          seen (atom nil)
          snapshot-started (promise)
          allow-snapshot-finish (promise)]
      (with-redefs-fn {#'dao.gui.runtime/create-snapshot
                       (fn [[a b]]
                         (let [a-val @a]
                           (deliver snapshot-started true)
                           @allow-snapshot-finish
                           {a a-val, b @b}))}
        (fn []
          (runtime/create-runtime
            {:root-form (fn []
                          (reset! seen [(compiler/read-atom a)
                                        (compiler/read-atom b)])
                          [:rect {:width 10, :height 10}]),
             :atoms [a b],
             :scheduler (fn [task] (swap! scheduler-calls conj task)),
             :consumer (fn [_ops])})
          (is (= 1 (count @scheduler-calls)))
          (let [compile-future (future ((first @scheduler-calls)))
                _ @snapshot-started
                mutation-future (future (reset! b 1))]
            ;; `@future` wraps the throw in ExecutionException whose
            ;; getMessage includes the cause's toString.
            (is (thrown-with-msg? java.util.concurrent.ExecutionException
                                  #"Re-entrant mutation"
                  @mutation-future))
            (deliver allow-snapshot-finish true)
            (is (thrown-with-msg? java.util.concurrent.ExecutionException
                                  #"Re-entrant mutation"
                  @compile-future))
            (is
              (nil? @seen)
              "compile should fail before a cross-thread mutation can produce a mixed snapshot")))))))


(deftest cross-thread-mutation-during-compile-ui-does-not-deliver-stale-frame
  (testing
    "a watched atom mutated after snapshot creation but before compile-ui returns should abort delivery"
    (let [a (atom 0)
          b (atom 0)
          scheduler-calls (atom [])
          delivered (atom [])
          root-entered (promise)
          allow-root-finish (promise)]
      (runtime/create-runtime {:root-form (fn []
                                            (deliver root-entered true)
                                            @allow-root-finish
                                            [:rect
                                             {:width (compiler/read-atom a),
                                              :height (compiler/read-atom b)}]),
                               :atoms [a b],
                               :scheduler (fn [task]
                                            (swap! scheduler-calls conj task)),
                               :consumer (fn [ops] (swap! delivered conj ops))})
      (is (= 1 (count @scheduler-calls)))
      (let [compile-future (future ((first @scheduler-calls)))
            _ @root-entered
            mutation-future (future (reset! b 1))]
        (is (thrown-with-msg? java.util.concurrent.ExecutionException
                              #"Re-entrant mutation"
              @mutation-future))
        (deliver allow-root-finish true)
        (is (thrown-with-msg? java.util.concurrent.ExecutionException
                              #"Re-entrant mutation"
              @compile-future))
        (is
          (empty? @delivered)
          "runtime should not deliver a stale frame when a cross-thread mutation happens during compile-ui")))))
