(ns dao.space.stream-test
  "Contract tests for the :dao-stream transport (dao.space.stream): a
  dao.stream whose appends persist as datoms into a dao.jing store, per
  docs/design/dao.space.md, The Write Path / Coordination: Stigmergy.
  Opening attaches a feeding stream to the space; ds/put! deposits datoms
  that query/q over the same store immediately sees."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.space.stream]
            [dao.stream :as ds]))


(deftest open-put-query-roundtrip
  (testing "an entity map put! through a :dao-stream is queryable in the store"
    (let [store (jing/create-kv-mem)
          log (ds/open! {:type :dao-stream, :store store, :name "producer"})]
      (is (= {:result :ok, :woke []}
             (ds/put!
               log
               {:db/id 100, :work/posted true, :work/task "process payment"})))
      (is (= #{[100 "process payment"]}
             (query/q '[:find ?w ?task :where [?w :work/posted true]
                        [?w :work/task ?task]]
                      store))))))


(deftest raw-datom-put
  (testing "a raw [e a v] datom vector is padded and appended"
    (let [store (jing/create-kv-mem)
          log (ds/open! {:type :dao-stream, :store store, :name "w"})]
      (ds/put! log [1 :work/status :todo])
      (is (= #{[:todo]}
             (query/q '[:find ?v :where [1 :work/status ?v]] store)))))
  (testing "an explicit 5-tuple keeps its m slot — retraction works"
    (let [store (jing/create-kv-mem)
          log (ds/open! {:type :dao-stream, :store store, :name "w"})]
      (ds/put! log {:db/id 1, :work/status :todo})
      (ds/put! log [1 :work/status :todo nil 0])
      (is (= #{} (query/q '[:find ?v :where [1 :work/status ?v]] store))))))


(deftest stigmergy-example-end-to-end
  (testing
    "the dao.space.md worker loop: post, query unclaimed, claim, re-query"
    (let [store (jing/create-kv-mem)
          producer (ds/open!
                     {:type :dao-stream, :store store, :name "producer"})
          worker (ds/open! {:type :dao-stream, :store store, :name "worker-1"})
          unclaimed '[:find ?w ?task :where [?w :work/posted true]
                      [?w :work/task ?task] (not [_ :work/claims ?w])]]
      (ds/put! producer
               {:db/id 100, :work/posted true, :work/task "process payment"})
      (ds/put! producer
               {:db/id 101, :work/posted true, :work/task "send invoice"})
      (is (= #{[100 "process payment"] [101 "send invoice"]}
             (query/q unclaimed store)))
      (ds/put! worker {:db/id 200, :work/claims 100, :work/by "worker-1"})
      (is (= #{[101 "send invoice"]} (query/q unclaimed store)))
      (ds/put! worker {:db/id 201, :work/claims 101, :work/by "worker-1"})
      (is (= #{} (query/q unclaimed store))))))


(deftest cursor-reading
  (testing "a cursor walks appended datoms in append order"
    (let [store (jing/create-kv-mem)
          log (ds/open! {:type :dao-stream, :store store, :name "w"})]
      (ds/put! log {:db/id 1, :a 1})
      (ds/put! log {:db/id 2, :a 2})
      (let [r1 (ds/next log {:position 0})
            r2 (ds/next log (:cursor r1))]
        (is (= [1 :a 1 0 1] (:ok r1)))
        (is (= [2 :a 2 1 1] (:ok r2)))
        (testing "the log end blocks while the stream is open"
          (is (= :blocked (ds/next log (:cursor r2)))))
        (testing "and yields :end once closed"
          (ds/close! log)
          (is (= :end (ds/next log (:cursor r2))))))))
  (testing "a second stream on the same store sees the first one's appends"
    (let [store (jing/create-kv-mem)
          a (ds/open! {:type :dao-stream, :store store, :name "a"})
          b (ds/open! {:type :dao-stream, :store store, :name "b"})]
      (ds/put! a {:db/id 1, :x true})
      (is (map? (ds/next b {:position 0}))))))


(deftest lifecycle
  (let [store (jing/create-kv-mem)
        log (ds/open! {:type :dao-stream, :store store, :name "w"})]
    (is (false? (ds/closed? log)))
    (is (= {:woke []} (ds/close! log)))
    (is (true? (ds/closed? log)))
    (testing "put! on a closed stream throws"
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"closed"
            (ds/put! log {:db/id 1, :a 1}))))
    (testing "closing does not erase deposited datoms"
      (let [store2 (jing/create-kv-mem)
            log2 (ds/open! {:type :dao-stream, :store store2, :name "w"})]
        (ds/put! log2 {:db/id 1, :a 1})
        (ds/close! log2)
        (is (= #{[1]} (query/q '[:find ?e :where [?e :a 1]] store2)))))))


(deftest validation
  (testing "opening without a jing store throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"store"
          (ds/open! {:type :dao-stream, :name "w"}))))
  (testing "an entity map without :db/id throws"
    (let [store (jing/create-kv-mem)
          log (ds/open! {:type :dao-stream, :store store, :name "w"})]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #":db/id"
            (ds/put! log {:work/posted true})))))
  (testing "a value that is neither entity map nor datom vector throws"
    (let [store (jing/create-kv-mem)
          log (ds/open! {:type :dao-stream, :store store, :name "w"})]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"entity map or datom"
            (ds/put! log 42))))))


(deftest appends-compose-with-existing-roots
  (testing "a :dao-stream append preserves datoms already seeded wholesale"
    (let [store (jing/create-kv-mem)]
      (jing/cas! store
                 index/default-datoms-key
                 0
                 {:datoms [[1 :work/status :todo 0 1]]})
      (let [log (ds/open! {:type :dao-stream, :store store, :name "w"})]
        (ds/put! log {:db/id 2, :work/status :todo})
        (is (= #{[1] [2]}
               (query/q '[:find ?e :where [?e :work/status :todo]] store))))))
  #?(:clj
     (testing
       "a :dao-stream append folds a publish-index!-ed root back to wholesale"
       (let [store (jing/create-kv-mem)]
         (index/publish-index! store [[1 :work/status :todo 0 1]])
         (let [log (ds/open! {:type :dao-stream, :store store, :name "w"})]
           (ds/put! log {:db/id 2, :work/status :todo})
           (is (= #{[1] [2]}
                  (query/q '[:find ?e :where [?e :work/status :todo]]
                           store))))))))
