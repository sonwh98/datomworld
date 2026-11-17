(ns yang.correspondence-measure-test
  (:require [clojure.test :refer [deftest is]]
            [yang.correspondence-measure :as measure]))


(deftest progress-refuses-rewriting-and-unknown-fixtures
  (let [file (str "target/test-d1-measure-" (random-uuid) ".edn")]
    (try
      (measure/initialize! file [:a :b])
      (measure/record! file :a {:status :refused :stage :example})
      (is (= {:a {:status :refused :stage :example :pre-declaration true} :b {:status :not-run}}
             (:outcomes (measure/read-progress file))))
      (is (thrown? Exception (measure/record! file :a {:status :refused :stage :again})))
      (is (thrown? Exception (measure/record! file :c {:status :refused :stage :unknown})))
      (is (thrown? Exception (measure/initialize! file [:a])))
      (finally (.delete (java.io.File. file))))))


(deftest retained-closure-sweep-validates-both-trees
  (let [store ((requiring-resolve 'dao.jing.mem/create-content-mem))
        tree ((requiring-resolve 'yin.vm/ast->semantic-bytecode)
              {:type :literal :value 42})
        root ((requiring-resolve 'yin.vm/materialize-tree!) store tree)
        origin [:source :program ["test-admission" 0] 0]
        input ((requiring-resolve 'dao.jing/materialize!) store
                                                          {:fixture {:id :fixture} :admission-origin origin})
        snapshot ((requiring-resolve 'dao.jing/materialize!) store
                                                             {:fixture :fixture :origin origin :root root :stamped-root root})
        outcome {:status :pass :admission-origin origin
                 :addresses {:input input :snapshot snapshot :root root :stamped-root root}}]
    (is (= {:pre-stamp 1 :stamped 1} (:rows (measure/check-retained-trees! store :fixture outcome))))
    (is (= :admission (try (measure/check-retained-trees! store :wrong outcome) nil
                           (catch clojure.lang.ExceptionInfo e (:stage (ex-data e))))))
    (is (thrown? Exception (measure/check-retained-trees!
                             store :fixture (assoc-in outcome [:addresses :stamped-root]
                                                      :segment/sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa))))))


(deftest static-progress-is-not-labelled-a-tagged-run
  (let [file (str "target/test-d1-static-" (random-uuid) ".edn")]
    (try
      (measure/initialize! file [:a] {:scope :d1-row-closure-recheck
                                      :pre-declaration-tagged-execution false})
      (is (= :d1-row-closure-recheck (:scope (measure/read-progress file))))
      (is (false? (:pre-declaration-tagged-execution (measure/read-progress file))))
      (finally (.delete (java.io.File. file))))))


(deftest static-tree-sizes-include-occurrences-and-paths
  (let [tree ((requiring-resolve 'yin.vm/ast->semantic-bytecode)
              {:type :application :operator {:type :variable :name 'f}
               :operands [{:type :literal :value 1}]})
        stats (measure/tree-statistics tree)]
    (is (= 3 (:occurrences stats)))
    (is (= 1 (:applications stats)))
    (is (= 1 (:depth stats)))
    (is (pos-int? (:application-path-bytes stats)))))


(deftest maxima-are-checked-and-missing-measurements-refuse
  (let [limits {:steps 10 :records 5}]
    (is (true? (measure/check-bounds! limits {:steps 10 :records 0})))
    (is (= {:stage :bound-exceeded :quantity :steps :observed 11 :ceiling 10}
           (try (measure/check-bounds! limits {:steps 11 :records 0}) nil
                (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e)
                                                                 [:stage :quantity :observed :ceiling])))))
    (is (= :bound-measurement (try (measure/check-bounds! limits {:steps 1}) nil
                                   (catch clojure.lang.ExceptionInfo e (:stage (ex-data e))))))
    (is (= :bound-declaration (try (measure/check-bounds! {:steps nil} {:steps 1}) nil
                                   (catch clojure.lang.ExceptionInfo e (:stage (ex-data e))))))))


(deftest runtime-checker-enforces-time-heap-and-ring-records
  (let [h ((requiring-resolve 'yin.vm.test-utils/new-stream) 2)
        append! (requiring-resolve 'dao.stream/append!)
        now (atom 1000000)
        heap (atom 20)
        checker (measure/runtime-checker
                  {:steps 9 :elapsed-ms 2 :heap-bytes 40 :trace-records 1
                   :trace-record-bytes 100 :trace-bytes 200}
                  1000000 #(deref now) #(deref heap))
        machine {:store {'gate-execution-trace {:id :trace}}
                 :resources {:trace h}}]
    (is (nil? (checker 0 machine)))
    (append! h ['f 1 1])
    (is (nil? (checker 1 machine)))
    (append! h ['f 1 2])
    (is (= :bound-exceeded (try (checker 2 machine) nil
                                (catch clojure.lang.ExceptionInfo e (:stage (ex-data e))))))
    (reset! heap 41)
    (is (= :bound-exceeded (try (checker 1 {}) nil
                                (catch clojure.lang.ExceptionInfo e (:stage (ex-data e))))))
    (reset! heap 20)
    (reset! now 4000001)
    (is (= :bound-exceeded (try (checker 1 {}) nil
                                (catch clojure.lang.ExceptionInfo e (:stage (ex-data e))))))))


(deftest runtime-checker-refuses-trace-byte-overflow
  (let [h ((requiring-resolve 'yin.vm.test-utils/new-stream) 4)
        append! (requiring-resolve 'dao.stream/append!)
        machine {:store {'gate-execution-trace {:id :trace}} :resources {:trace h}}
        limits {:steps 9 :elapsed-ms 100 :heap-bytes 100 :trace-records 4
                :trace-record-bytes 6 :trace-bytes 100}
        checker (measure/runtime-checker limits 0 (constantly 0) (constantly 1))]
    (is (nil? (checker 0 machine)))
    (append! h ['f 1 1])
    (let [failure (try (checker 1 machine) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :bound-exceeded (:stage failure)))
      (is (= :trace-record-bytes (:quantity failure)))
      (is (= 7 (:observed failure))))))


(deftest runtime-checker-refuses-cumulative-trace-bytes
  (let [h ((requiring-resolve 'yin.vm.test-utils/new-stream) 4)
        append! (requiring-resolve 'dao.stream/append!)
        machine {:store {'gate-execution-trace {:id :trace}} :resources {:trace h}}
        limits {:steps 9 :elapsed-ms 100 :heap-bytes 100 :trace-records 4
                :trace-record-bytes 20 :trace-bytes 16}
        checker (measure/runtime-checker limits 0 (constantly 0) (constantly 1))]
    (append! h ['f 1 1])
    (is (nil? (checker 0 machine)))
    (append! h ['g 2 2])
    (let [failure (try (checker 1 machine) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :trace-bytes (:quantity failure)))
      (is (= 17 (:observed failure)))
      (is (= 16 (:ceiling failure))))))


(deftest bound-exceedance-is-recorded-as-a-final-failure
  (let [file (str "target/test-d1-bounds-" (random-uuid) ".edn")
        failure {:status :failed-bound :stage :bound-exceeded :quantity :steps
                 :observed 11 :ceiling 10}]
    (try
      (measure/initialize! file [:a] {:scope :d1-bounded-run
                                      :pre-declaration-tagged-execution false})
      (measure/record! file :a failure)
      (is (= failure (get-in (measure/read-progress file) [:outcomes :a])))
      (is (thrown? Exception (measure/record! file :a {:status :pass})))
      (finally (.delete (java.io.File. file))))))


(deftest retained-tree-fetch-uses-declared-row-limit
  (let [store ((requiring-resolve 'dao.jing.mem/create-content-mem))
        tree ((requiring-resolve 'yin.vm/ast->semantic-bytecode)
              {:type :application :operator {:type :variable :name 'f}
               :operands [{:type :literal :value 1}]})
        root ((requiring-resolve 'yin.vm/materialize-tree!) store tree)
        origin [:source :program ["test-admission" 0] 0]
        input ((requiring-resolve 'dao.jing/materialize!) store {:fixture {:id :f} :admission-origin origin})
        snapshot ((requiring-resolve 'dao.jing/materialize!) store
                                                             {:fixture :f :origin origin :root root :stamped-root root})
        outcome {:status :pass :admission-origin origin
                 :addresses {:input input :snapshot snapshot :root root :stamped-root root}}]
    (is (= {:stage :bound-exceeded :quantity :rows :observed 2 :ceiling 1}
           (try (measure/check-retained-trees! store :f outcome {:max-rows 1}) nil
                (catch clojure.lang.ExceptionInfo e
                  (select-keys (ex-data e) [:stage :quantity :observed :ceiling])))))
    (is (= {:pre-stamp 3 :stamped 3}
           (:rows (measure/check-retained-trees! store :f outcome {:max-rows 3}))))))


(deftest tree-statistics-stop-at-declared-occurrence-and-depth-limits
  (let [tree ((requiring-resolve 'yin.vm/ast->semantic-bytecode)
              {:type :application :operator {:type :variable :name 'f}
               :operands [{:type :literal :value 1}]})
        expected (measure/tree-statistics tree)
        limits {:rows 3 :occurrences 3 :applications 1 :depth 1 :application-path-bytes 100}]
    (is (= expected (measure/bounded-tree-statistics! tree limits)))
    (let [failure (try (measure/bounded-tree-statistics! tree (assoc limits :occurrences 2)) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :bound-exceeded (:stage failure)))
      (is (= :occurrences (:quantity failure)))
      (is (= 3 (:observed failure)))
      (is (= 2 (:ceiling failure))))
    (is (= :depth (try (measure/bounded-tree-statistics! tree (assoc limits :depth 0)) nil
                       (catch clojure.lang.ExceptionInfo e (:quantity (ex-data e))))))))


(deftest progress-syncs-directory-after-atomic-rename
  (let [file (str "target/test-d1-dir-sync-" (random-uuid) ".edn")
        seen (atom [])
        sync-var (ns-resolve 'yang.correspondence-measure 'sync-directory!)]
    (try
      (is (some? sync-var))
      (when sync-var
        (with-redefs-fn {sync-var #(swap! seen conj (.getPath ^java.io.File %))}
          (fn []
            (measure/initialize! file [:a])
            (measure/record! file :a {:status :refused :stage :synthetic})))
        (is (= ["target" "target"] @seen)))
      (finally (.delete (java.io.File. file))))))
