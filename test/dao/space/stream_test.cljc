(ns dao.space.stream-test
  "Contract tests for the :dao-stream transport (dao.space.stream): a
  dao.stream whose appends persist as datoms into a dao.jing store, per
  docs/design/dao.space.md, The Write Path / Coordination: Stigmergy.
  Opening attaches a feeding stream to the space; ds/append! deposits datoms
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
             (ds/append!
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
      (ds/append! log [1 :work/status :todo])
      (is (= #{[:todo]}
             (query/q '[:find ?v :where [1 :work/status ?v]] store)))))
  (testing "an explicit 5-tuple keeps its m slot — retraction works"
    (let [store (jing/create-kv-mem)
          log (ds/open! {:type :dao-stream, :store store, :name "w"})]
      (ds/append! log {:db/id 1, :work/status :todo})
      (ds/append! log [1 :work/status :todo nil 0])
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
      (ds/append! producer
                  {:db/id 100, :work/posted true, :work/task "process payment"})
      (ds/append! producer
                  {:db/id 101, :work/posted true, :work/task "send invoice"})
      (is (= #{[100 "process payment"] [101 "send invoice"]}
             (query/q unclaimed store)))
      (ds/append! worker {:db/id 200, :work/claims 100, :work/by "worker-1"})
      (is (= #{[101 "send invoice"]} (query/q unclaimed store)))
      (ds/append! worker {:db/id 201, :work/claims 101, :work/by "worker-1"})
      (is (= #{} (query/q unclaimed store))))))


(deftest cursor-reading
  (testing "a cursor walks appended datoms in append order"
    (let [store (jing/create-kv-mem)
          log (ds/open! {:type :dao-stream, :store store, :name "w"})]
      (ds/append! log {:db/id 1, :a 1})
      (ds/append! log {:db/id 2, :a 2})
      (let [r1 (ds/next log {:position 0})
            r2 (ds/next log (:cursor r1))]
        (is (= [1 :a 1 0 1] (:ok r1)))
        (is (= [2 :a 2 1 1] (:ok r2)))
        (testing "the log end blocks while the stream is open"
          (is (= :blocked (ds/next log (:cursor r2)))))
        (testing "and yields :end once closed"
          (ds/close! log)
          (is (= :end (ds/next log (:cursor r2))))))))
  (testing "a second handle with the same name tails the same stream"
    (let [store (jing/create-kv-mem)
          a (ds/open! {:type :dao-stream, :store store, :name "a"})
          a2 (ds/open! {:type :dao-stream, :store store, :name "a"})]
      (ds/append! a {:db/id 1, :x true})
      (is (map? (ds/next a2 {:position 0})))))
  (testing "a differently-named stream's cursor does not see another's log"
    (let [store (jing/create-kv-mem)
          a (ds/open! {:type :dao-stream, :store store, :name "a"})
          b (ds/open! {:type :dao-stream, :store store, :name "b"})]
      (ds/append! a {:db/id 1, :x true})
      (is (= :blocked (ds/next b {:position 0}))))))


(deftest per-stream-roots
  (testing "each stream's datoms land under its own :root/<name>"
    (let [store (jing/create-kv-mem)
          producer (ds/open!
                     {:type :dao-stream, :store store, :name "producer"})
          worker (ds/open! {:type :dao-stream, :store store, :name "worker-1"})]
      (ds/append! producer {:db/id 100, :work/posted true})
      (ds/append! worker {:db/id 200, :work/claims 100})
      (is (nil? (jing/get store :root/datoms nil))
          "no global :root/datoms is ever written by the transport")
      (is (= 1 (count (:datoms (jing/get store :root/producer nil)))))
      (is (= 1 (count (:datoms (jing/get store :root/worker-1 nil)))))
      (testing "open! registers each stream root as a member"
        (is (= #{:root/producer :root/worker-1}
               (:members (jing/get store index/members-key nil)))))
      (testing "a query over the store merges all member roots"
        (is (= #{[100] [200]} (query/q '[:find ?e :where [?e _ _]] store))))
      (testing "match also folds all member roots"
        (is (= [[200 :work/claims 100 0 1]]
               (query/match store ['_ :work/claims '_])))
        (is (= [[100 :work/posted true 0 1]]
               (query/match store ['_ :work/posted true]))))))
  (testing "an explicit :key override is honored and registered"
    (let [store (jing/create-kv-mem)
          log
          (ds/open!
            {:type :dao-stream, :store store, :name "a", :key :root/custom})]
      (ds/append! log {:db/id 1, :x true})
      (is (some? (jing/get store :root/custom nil)))
      (is (contains? (:members (jing/get store index/members-key nil))
                     :root/custom))))
  (testing "a nameless, keyless descriptor throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"name"
          (ds/open! {:type :dao-stream,
                     :store (jing/create-kv-mem)}))))
  (testing "a stream named members throws due to membership root collision"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"cannot target the membership root"
          (ds/open! {:type :dao-stream,
                     :store (jing/create-kv-mem),
                     :name "members"}))))
  (testing
    "a stream with custom key :root/members throws due to membership root collision"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"cannot target the membership root"
          (ds/open! {:type :dao-stream,
                     :store (jing/create-kv-mem),
                     :name "a",
                     :key :root/members}))))
  (testing "a stream with empty name key throws with register-member! context"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"register-member! requires a non-empty"
          (ds/open! {:type :dao-stream,
                     :store (jing/create-kv-mem),
                     :key (keyword "root" "")}))))
  (testing "a stream with segment key throws with register-member! context"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"register-member! requires a :root"
          (ds/open! {:type :dao-stream,
                     :store (jing/create-kv-mem),
                     :key :segment/foo})))))


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
            (ds/append! log {:db/id 1, :a 1}))))
    (testing "closing does not erase deposited datoms"
      (let [store2 (jing/create-kv-mem)
            log2 (ds/open! {:type :dao-stream, :store store2, :name "w"})]
        (ds/append! log2 {:db/id 1, :a 1})
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
            (ds/append! log {:work/posted true})))))
  (testing "a value that is neither entity map nor datom vector throws"
    (let [store (jing/create-kv-mem)
          log (ds/open! {:type :dao-stream, :store store, :name "w"})]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"entity map or datom"
            (ds/append! log 42))))))


(deftest appends-compose-with-existing-roots
  (testing "a :dao-stream append preserves datoms already seeded wholesale"
    (let [store (jing/create-kv-mem)]
      (index/register-member! store :root/w)
      (jing/cas! store :root/w 0 {:datoms [[1 :work/status :todo 0 1]]})
      (let [log (ds/open! {:type :dao-stream, :store store, :name "w"})]
        (ds/append! log {:db/id 2, :work/status :todo})
        (is (= #{[1] [2]}
               (query/q '[:find ?e :where [?e :work/status :todo]] store))))))
  #?(:clj (testing
            "an append folds a publish-index!-ed stream root back to wholesale"
            (let [store (jing/create-kv-mem)
                  log (ds/open! {:type :dao-stream, :store store, :name "w"})]
              (ds/append! log {:db/id 1, :work/status :todo})
              (index/publish-index! store
                                    (index/read-datoms store :root/w)
                                    {:key :root/w})
              (is (some? (:indexes (jing/get store :root/w nil))))
              (ds/append! log {:db/id 2, :work/status :todo})
              (is (= #{[1] [2]}
                     (query/q '[:find ?e :where [?e :work/status :todo]]
                              store)))))))
