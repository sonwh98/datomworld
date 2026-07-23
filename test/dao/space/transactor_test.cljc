(ns dao.space.transactor-test
  "Contract tests for the :transactor transport (dao.space.transactor): a
  dao.stream whose appends persist as datoms into a dao.jing store, per
  docs/design/dao.space.md, The Write Path / Coordination: Stigmergy.
  Opening attaches a feeding stream to the space; ds/append! deposits datoms
  that query/q over the same store immediately sees."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.space.transactor :as transactor]
            [dao.stream :as ds]))


(deftest open-put-query-roundtrip
  (testing "an entity map put! through a :transactor is queryable in the store"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "producer"})]
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
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log [1 :work/status :todo])
      (is (= #{[:todo]}
             (query/q '[:find ?v :where [1 :work/status ?v]] store)))))
  (testing "an explicit 5-tuple keeps its m slot — retraction works"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log {:db/id 1, :work/status :todo})
      (ds/append! log [1 :work/status :todo nil 0])
      (is (= #{} (query/q '[:find ?v :where [1 :work/status ?v]] store)))))
  (testing "a 4-tuple [e a v t] carries its own t, m defaults to assert"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log [1 :work/status :todo 5])
      (is (= #{[5]}
             (query/q '[:find ?t :where [1 :work/status :todo ?t]] store)))))
  (testing
    "a far-future explicit t on a retraction outranks a later,
          legitimate auto-t reassertion of the same value — the
          documented footgun (ns docstring, pad-datom)"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log {:db/id 1, :work/status :todo}) ; auto t=0, assert
      (ds/append! log [1 :work/status :todo 99999 0]) ; explicit far-future
      ;; retract
      (ds/append! log {:db/id 1, :work/status :todo}) ; auto t=2, a later
      ;; reassert
      (is
        (= #{} (query/q '[:find ?v :where [1 :work/status ?v]] store))
        "the future-dated retraction sorts after every real datom by t,
           so it wins recency resolution and masks the legitimate
           reassert even though the reassert was appended after it"))))


(deftest stigmergy-example-end-to-end
  (testing
    "the dao.space.md worker loop: post, query unclaimed, claim, re-query"
    (let [store (mem/create-kv-mem)
          producer (ds/open!
                     {:type :transactor, :store store, :name "producer"})
          worker (ds/open! {:type :transactor, :store store, :name "worker-1"})
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
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
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
    (let [store (mem/create-kv-mem)
          a (ds/open! {:type :transactor, :store store, :name "a"})
          a2 (ds/open! {:type :transactor, :store store, :name "a"})]
      (ds/append! a {:db/id 1, :x true})
      (is (map? (ds/next a2 {:position 0})))))
  (testing
    ":closed is per-handle, not per-root — a second handle keeps
          blocking (not :end) after the first handle on the same name
          closes"
    (let [store (mem/create-kv-mem)
          a (ds/open! {:type :transactor, :store store, :name "a"})
          a2 (ds/open! {:type :transactor, :store store, :name "a"})]
      (ds/append! a {:db/id 1, :x true})
      (ds/next a2 {:position 0})
      (ds/close! a)
      (is (= :blocked (ds/next a2 {:position 1}))
          "a2 was never closed — the log isn't done from a2's view")))
  (testing "a differently-named stream's cursor does not see another's log"
    (let [store (mem/create-kv-mem)
          a (ds/open! {:type :transactor, :store store, :name "a"})
          b (ds/open! {:type :transactor, :store store, :name "b"})]
      (ds/append! a {:db/id 1, :x true})
      (is (= :blocked (ds/next b {:position 0}))))))


(deftest per-stream-roots
  (testing "each stream's datoms land under its own :root/<name>"
    (let [store (mem/create-kv-mem)
          producer (ds/open!
                     {:type :transactor, :store store, :name "producer"})
          worker (ds/open! {:type :transactor, :store store, :name "worker-1"})]
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
    (let [store (mem/create-kv-mem)
          log
          (ds/open!
            {:type :transactor, :store store, :name "a", :key :root/custom})]
      (ds/append! log {:db/id 1, :x true})
      (is (some? (jing/get store :root/custom nil)))
      (is (contains? (:members (jing/get store index/members-key nil))
                     :root/custom))))
  (testing "a nameless, keyless descriptor throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"name"
          (ds/open! {:type :transactor,
                     :store (mem/create-kv-mem)}))))
  (testing "a stream named members throws due to membership root collision"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"cannot target the membership root"
          (ds/open! {:type :transactor,
                     :store (mem/create-kv-mem),
                     :name "members"}))))
  (testing
    "a stream with custom key :root/members throws due to membership root collision"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"cannot target the membership root"
          (ds/open! {:type :transactor,
                     :store (mem/create-kv-mem),
                     :name "a",
                     :key :root/members}))))
  (testing "a stream with empty name key throws with register-member! context"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"register-member! requires a non-empty"
          (ds/open! {:type :transactor,
                     :store (mem/create-kv-mem),
                     :key (keyword "root" "")}))))
  (testing "a stream with segment key throws with register-member! context"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"register-member! requires a :root"
          (ds/open! {:type :transactor,
                     :store (mem/create-kv-mem),
                     :key :segment/foo})))))


(deftest lifecycle
  (let [store (mem/create-kv-mem)
        log (ds/open! {:type :transactor, :store store, :name "w"})]
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
      (let [store2 (mem/create-kv-mem)
            log2 (ds/open! {:type :transactor, :store store2, :name "w"})]
        (ds/append! log2 {:db/id 1, :a 1})
        (ds/close! log2)
        (is (= #{[1]} (query/q '[:find ?e :where [?e :a 1]] store2)))))))


(deftest append-throws-after-max-retries
  (testing
    "append! throws rather than spin forever when the root cas!
          never wins (the bound cas-append! exists to catch)"
    ;; A wrapper store whose cas! always loses on the stream's own root,
    ;; but still lets open!'s membership-root registration succeed —
    ;; with-redefs does not reach this: mem's deftype implements
    ;; IKVStore's interface directly, so protocol calls on it dispatch
    ;; straight to the interface method, bypassing Var indirection.
    (let [real (mem/create-kv-mem)
          always-loses-cas (reify
                             jing/IKVStore
                             (put! [_ k v-map] (jing/put! real k v-map))

                             (cas!
                               [_ k old-rev v-map]
                               (if (= k index/members-key)
                                 (jing/cas! real k old-rev v-map)
                                 false))

                             (get [_ k not-found] (jing/get real k not-found))

                             (delete! [_ k] (jing/delete! real k))

                             (close! [_] (jing/close! real)))
          log (ds/open!
                {:type :transactor, :store always-loses-cas, :name "w"})]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"max-append-retries"
            (ds/append! log {:db/id 1, :x true}))))))


(deftest validation
  (testing "opening without a jing store throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"store"
          (ds/open! {:type :transactor, :name "w"}))))
  (testing "an entity map without :db/id throws"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #":db/id"
            (ds/append! log {:work/posted true})))))
  (testing "a value that is neither entity map nor datom vector throws"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"entity map or datom"
            (ds/append! log 42))))))


(deftest appends-compose-with-existing-roots
  (testing "a :transactor append preserves datoms already seeded wholesale"
    (let [store (mem/create-kv-mem)]
      (index/register-member! store :root/w)
      (jing/cas! store :root/w 0 {:datoms [[1 :work/status :todo 0 1]]})
      (let [log (ds/open! {:type :transactor, :store store, :name "w"})]
        (ds/append! log {:db/id 2, :work/status :todo})
        (is (= #{[1] [2]}
               (query/q '[:find ?e :where [?e :work/status :todo]] store))))))
  (testing "an append folds a published stream root back to wholesale"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log {:db/id 1, :work/status :todo})
      (transactor/publish! log)
      (is (some? (:indexes (jing/get store :root/w nil))))
      (ds/append! log {:db/id 2, :work/status :todo})
      (is (nil? (:indexes (jing/get store :root/w nil)))
          "the append must fold the published root back to wholesale")
      (is (= #{[1] [2]}
             (query/q '[:find ?e :where [?e :work/status :todo]] store))))))


(deftest publish!
  (testing "publish! builds covered indexes over everything appended so far"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log {:db/id 1, :work/status :todo})
      (ds/append! log {:db/id 2, :work/status :done})
      (let [before (query/q '[:find ?e ?v :where [?e :work/status ?v]] store)
            manifest (transactor/publish! log)
            root (jing/get store :root/w nil)]
        (is (= #{:eavt :aevt :avet :vaet} (set (keys manifest))))
        (is (= manifest (:indexes root)))
        (is (= 2 (:count root)))
        (is (nil? (:datoms root)) "the wholesale vector is gone")
        (is (= before
               (query/q '[:find ?e ?v :where [?e :work/status ?v]] store))
            "q answers identically before and after publish"))))
  (testing "opts pass through to publish-index!, :key cannot be overridden"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log {:db/id 1, :work/status :todo})
      (transactor/publish! log {:branching-factor 16, :key :root/other})
      (is (= 16 (:branching-factor (jing/get store :root/w nil))))
      (is (nil? (jing/get store :root/other nil))
          "opts cannot redirect publish! away from the stream's own root")))
  (testing
    "opts :rev/:reorder-epoch are ignored — publish! always uses its
          own read-root snapshot, not caller-supplied ones"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log {:db/id 1, :work/status :todo})
      ;; a wildly wrong :rev/:reorder-epoch in opts must not reach cas! —
      ;; if it did, this cas! would either throw (wrong rev) or corrupt
      ;; :reorder-epoch (wrong epoch); neither happens because publish!
      ;; overwrites both from its own read-root call.
      (transactor/publish! log {:rev 999999, :reorder-epoch 999999})
      (is (= 1 (:reorder-epoch (jing/get store :root/w nil)))
          "epoch advanced from the real prior epoch (0), not the bogus opt")))
  (testing "publishing an empty stream is readable, not an error"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (transactor/publish! log)
      (is (= [] (query/match store ['_ '_ '_]))))))


(deftest transact!
  (testing
    "transact! commits multiple entity maps as one atomic write, sharing one t"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "producer"})
          {:keys [result tx-datoms]}
          (transactor/transact!
            log
            [{:db/id 100, :work/posted true, :work/task "process payment"}
             {:db/id 101, :work/posted true, :work/task "send invoice"}])]
      (is (= :ok result))
      (is (= 4 (count tx-datoms)))
      (is (apply = (map #(nth % 3) tx-datoms))
          "every datom in the batch shares one t")
      (is (= #{[100 "process payment"] [101 "send invoice"]}
             (query/q '[:find ?w ?task :where [?w :work/posted true]
                        [?w :work/task ?task]]
                      store)))))
  (testing "transact! accepts a mix of entity maps and raw datom vectors"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (transactor/transact! log [{:db/id 1, :a 1} [2 :b 2]])
      (is (= #{[1 :a 1] [2 :b 2]}
             (query/q '[:find ?e ?a ?v :where [?e ?a ?v]] store)))
      (is (= #{0} (into #{} (map #(nth % 3)) (query/match store ['_ :b '_])))
          "raw datom [2 :b 2] gets t=0 from the batch")))
  (testing "a batch gets its own t, distinct from a prior append's"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log {:db/id 1, :a 1})
      (transactor/transact! log [{:db/id 2, :a 2} {:db/id 3, :a 3}])
      (is
        (= #{0 1} (into #{} (map #(nth % 3)) (query/match store ['_ :a '_])))
        "the first append's datom keeps t=0; the batch's two datoms both land at t=1")))
  (testing "an empty tx-data throws"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"at least one"
            (transactor/transact! log [])))))
  (testing "transact! on a closed stream throws"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/close! log)
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"closed"
            (transactor/transact! log [{:db/id 1, :a 1}])))))
  (testing
    "transact! throws rather than spin forever when the root cas! never wins"
    (let [real (mem/create-kv-mem)
          always-loses-cas (reify
                             jing/IKVStore
                             (put! [_ k v-map] (jing/put! real k v-map))

                             (cas!
                               [_ k old-rev v-map]
                               (if (= k index/members-key)
                                 (jing/cas! real k old-rev v-map)
                                 false))

                             (get [_ k not-found] (jing/get real k not-found))

                             (delete! [_ k] (jing/delete! real k))

                             (close! [_] (jing/close! real)))
          log (ds/open!
                {:type :transactor, :store always-loses-cas, :name "w"})]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"max-append-retries"
            (transactor/transact! log
                                  [{:db/id 1, :x true}]))))))


(deftest cursor-gap-on-publish
  (testing
    "a cursor mid-walk in append order gaps rather than silently
          re-pointing to a different datom in eavt order once publish!
          changes what position n refers to"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log [2 :a 2])
      (ds/append! log [1 :a 1])
      (let [r1 (ds/next log {:position 0})]
        (is (= [2 :a 2 0 1] (:ok r1)) "append order: e=2 was appended first")
        (transactor/publish! log)
        (is
          (= :daostream/gap (ds/next log (:cursor r1)))
          "the root reordered to eavt (e=1 before e=2) since r1 was minted"))))
  (testing "a fresh cursor at position 0 never gaps — nothing to invalidate"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log [1 :a 1])
      (transactor/publish! log)
      (is (map? (ds/next log {:position 0})))))
  (testing
    "a cursor minted after publish! keeps reading normally across a
          later fold-back append — fold-back does not reorder anything
          already minted"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log [1 :a 1])
      (transactor/publish! log)
      (let [r1 (ds/next log {:position 0})]
        (ds/append! log [2 :a 2])
        (is (= [1 :a 1 0 1] (:ok r1)))
        (is (map? (ds/next log (:cursor r1)))
            "no gap: fold-back only appends after the eavt-ordered prefix"))))
  (testing
    "a cursor already caught up when a republish reorders the log
          does not gap on a no-op it has nothing left to lose from"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log [1 :a 1])
      (let [r1 (ds/next log {:position 0})]
        (is (= :blocked (ds/next log (:cursor r1))) "caught up, pre-publish")
        (transactor/publish! log)
        (is
          (= :blocked (ds/next log (:cursor r1)))
          "still caught up post-publish: no unread data to reinterpret, so :blocked not :daostream/gap")))))


(deftest republish-unchanged-data-is-a-true-no-op
  (testing
    "republishing identical content does not bump :reorder-epoch or
          gap a live cursor — content addressing means nothing actually
          reordered"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "w"})]
      (ds/append! log [2 :a 2])
      (ds/append! log [1 :a 1])
      (let [r1 (ds/next log {:position 0})]
        (transactor/publish! log)
        (is (= :daostream/gap (ds/next log (:cursor r1)))
            "the first publish did reorder — sanity check")
        (let [r2 (ds/next log {:position 0})
              root-after-first (jing/get store :root/w nil)
              epoch-after-first (:reorder-epoch root-after-first)]
          (transactor/publish! log) ; republish: identical datoms, no
          ;; change
          (is (= epoch-after-first
                 (:reorder-epoch (jing/get store :root/w nil)))
              "unchanged republish must not bump :reorder-epoch")
          (is
            (= (:indexes root-after-first)
               (:indexes (jing/get store :root/w nil)))
            "unchanged republish must not even rewrite the manifest —
               same content-addressed segment keys, byte-identical
               (this is the cause the epoch check above is only a
               consequence of)")
          (is
            (map? (ds/next log (:cursor r2)))
            "a cursor minted after the first publish does not gap on the
               second, unchanged republish"))))))


(deftest publish!-rejects-concurrent-append
  (testing
    "publish! throws rather than silently commit indexes over a stale
          snapshot when a same-name append lands between the datom read
          and the cas! — the lost-update race publish-index!'s :rev
          option exists to close"
    (let [store (mem/create-kv-mem)]
      (index/register-member! store :root/w)
      (jing/cas! store :root/w 0 {:datoms [[1 :a 1 0 1]]})
      ;; simulate the exact window publish! races against: read the
      ;; datoms/rev/epoch snapshot, then a concurrent append commits,
      ;; then try to publish against the now-stale snapshot.
      (let [{:keys [datoms rev reorder-epoch]} (index/read-root store :root/w)]
        (jing/cas! store :root/w rev {:datoms (conj datoms [2 :a 2 1 1])})
        (is (thrown-with-msg?
              #?(:cljs js/Error
                 :cljd Object
                 :default Exception)
              #"lost the root cas!"
              (index/publish-index!
                store
                datoms
                {:key :root/w, :rev rev, :reorder-epoch reorder-epoch})))
        (testing "the exception carries a :likely-cause for callers to log"
          (try (index/publish-index!
                 store
                 datoms
                 {:key :root/w, :rev rev, :reorder-epoch reorder-epoch})
               (is false "should have thrown")
               (catch #?(:cljs js/Error
                         :cljd Object
                         :default Exception)
                      e
                 (is (some? (:likely-cause (ex-data e)))))))
        (is
          (= #{[2]} (query/q '[:find ?e :where [?e :a 2]] store))
          "the concurrent append survives — publish! did not overwrite it")))))


(deftest cross-stream-entity-id-collision-contract
  (testing
    "documents query behavior when multiple transactor handles append datoms with identical local entity IDs"
    (let [store (mem/create-kv-mem)
          s1 (ds/open! {:type :transactor, :store store, :name "s1"})
          s2 (ds/open! {:type :transactor, :store store, :name "s2"})]
      (ds/append! s1 {:db/id 100, :user/name "Alice-Stream1"})
      (ds/append! s2 {:db/id 100, :user/name "Alice-Stream2"})
      ;; Document that current query/q folds both member roots without
      ;; automatic namespace disambiguation
      (let [res (query/q '[:find ?name :where [100 :user/name ?name]] store)]
        (is (= #{["Alice-Stream1"] ["Alice-Stream2"]} res))))))


(deftest multi-stage-index-lifecycle-reorder
  (testing "multi-stage append -> publish! -> append -> gapping -> resync cycle"
    (let [store (mem/create-kv-mem)
          log (ds/open! {:type :transactor, :store store, :name "producer"})]
      ;; Stage 1: append wholesale
      (ds/append! log {:db/id 1, :val "a"})
      (let [c0 {:position 0}
            r1 (ds/next log c0)
            cur1 (:cursor r1)]
        (is (= 1 (first (:ok r1))))
        ;; Stage 2: publish! index (advances reorder-epoch)
        (transactor/publish! log)
        ;; Stage 3: append again (folds indexed root back to wholesale, 2
        ;; datoms now)
        (ds/append! log {:db/id 2, :val "b"})
        ;; Stale cursor cur1 (epoch 0) reading position 1 when root has
        ;; reordered epoch detects gap
        (is (= :daostream/gap (ds/next log cur1)))
        ;; Resyncing cursor with updated epoch reads second datom
        (let [{:keys [reorder-epoch]} (index/read-root store :root/producer)
              resynced-cur (assoc cur1 :epoch reorder-epoch)
              r2 (ds/next log resynced-cur)]
          (is (= 2 (first (:ok r2)))))))))
