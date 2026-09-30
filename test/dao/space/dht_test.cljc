(ns dao.space.dht-test
  "The plain Clojure path to code in the DHT (dao.space.dht; DHT epic
   S5): with no yin.repl anywhere, one node publishes a covered index, and
   another joins, loads it from its manifest address over the network,
   and queries it with dao.space.query/q.  yin.repl.dht-test drives the
   same functions through the REPL's host module and gets the same
   answer.  The sockets are dao.stream.datagram host seams over the
   dao.jing.dht test mesh; time is the reading each step is handed."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.dht :as jing.dht]
            [dao.jing.dht.mesh :as mesh]
            [dao.jing.mem :as mem]
            [dao.space.dht :as dht]
            [dao.space.transactor :as transactor]
            [dao.stream :as stream]
            [dao.stream.memory-log :as memory-log]))


(defn- memory-log
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type memory-log/transport-type})))


(defn publish-datoms!
  "Publish `tx-data` as one transaction's covered index through `node`'s
   store — the transactor's publish, its intake drained into the store —
   and announce it.  Answers the manifest address.  This is what any
   Clojure publisher does; yin.repl.index is one."
  [node tx-data]
  (let [intake (memory-log)
        tx (transactor/create! {:local-stream (memory-log)
                                :intake-pool [intake]})
        _ (transactor/transact! tx tx-data)
        {:keys [manifest-address]} (transactor/publish! tx {})]
    (loop [pool (jing/observer-state
                  [{:stream intake
                    :cursor (:dao.stream/cursor
                              (stream/cursor intake :dao.stream/oldest))}])]
      (let [{:keys [signal state]} (jing/observe-step! (dht/store node) pool)]
        (when (= :dao.stream/ok signal) (recur state))))
    (dht/announce! node manifest-address)
    manifest-address))


(def facts
  [{:db/id datom/first-user-id :code/name "alpha" :code/arity 1}
   {:db/id (inc datom/first-user-id) :code/name "beta" :code/arity 2}])


(def names-query
  '[:find ?n ?a :where [?e :code/name ?n] [?e :code/arity ?a]])


(defn run-nodes
  "Step every node at readings 0, 10, … until `done?` holds over the
   collected events or `limit` passes.  Answers `[nodes events]`."
  [nodes limit done?]
  (loop [nodes nodes
         events []
         now 0]
    (if (or (done? events) (> now limit))
      [nodes events]
      (let [stepped (map #(dht/step % now) nodes)]
        (recur (mapv first stepped)
               (into events (mapcat second) stepped)
               (+ now 10))))))


(defn- event
  [events kind manifest]
  (some #(when (and (= kind (::dht/event %)) (= manifest (:manifest %))) %)
        events))


(defn- node-at
  [net port peers opts]
  (dht/join (merge {:local (mem/create-content-mem)
                    :peers (mapv (fn [p] {:host mesh/host :port p}) peers)
                    :bind! (mesh/seam net port)}
                   opts)))


(deftest plain-clojure-joins-loads-a-remote-index-and-queries-it
  (let [net (mesh/mesh)
        publisher (node-at net 81 [82] {:publish? true})
        reader (node-at net 82 [81] {})
        manifest (publish-datoms! publisher facts)]
    (testing "before it is loaded the reader has nothing to query"
      (is (nil? (dht/load-status reader manifest)))
      (is (thrown? #?(:cljd Object :clj Exception :cljs :default)
            (dht/db reader manifest))))
    (let [reader (dht/load-index reader manifest)
          _ (is (= :loading (:status (dht/load-status reader manifest))))
          [[publisher reader] events]
          (run-nodes [publisher reader] 20000 #(event % :loaded manifest))
          loaded (event events :loaded manifest)]
      (is (some? loaded) (pr-str events))
      (is (pos? (:fetched loaded)) "the blobs came over the network")
      (is (= 4 (:datoms loaded)) "two entities, two facts each")
      (is (= #{["alpha" 1] ["beta" 2]} (set (dht/q reader manifest names-query))))
      (is (= #{["beta"]}
             (set (dht/q reader manifest
                         '[:find ?n :in $ ?a :where [?e :code/arity ?a]
                           [?e :code/name ?n]]
                         2)))
          "inputs follow the query")
      (is (some? (jing/get (dht/local reader) manifest nil))
          "the loaded blobs are the reader's own now")
      (dht/close! publisher)
      (dht/close! reader))))


(deftest a-publication-reports-its-acknowledgement-as-an-event
  (let [net (mesh/mesh)
        peers (mapv (fn [p]
                      (let [c (mesh/join! net p {::jing.dht/publish? true})]
                        [c (jing.dht/state c)]))
                    [91 92])
        publisher (node-at net 90 [91 92] {:publish? true})
        manifest (publish-datoms! publisher facts)]
    (loop [publisher publisher
           peers peers
           now 0
           events []]
      (if-let [published (event events :published manifest)]
        (do (is (true? (:acknowledged? published)))
            (is (= 2 (:peers published)))
            (dht/close! publisher))
        (if (> now 5000)
          (is false (pr-str events))
          (let [[publisher more] (dht/step publisher now)
                peers (mapv (fn [[c s]] (mesh/tick! c now) [c (jing.dht/step s 64)])
                            peers)]
            (recur publisher peers (+ now 10) (into events more))))))))


(deftest a-solo-node-opens-no-socket-and-reports-why-nothing-was-sent
  (let [net (mesh/mesh)
        binds (atom 0)
        node (dht/join {:local (mem/create-content-mem)
                        :publish? true
                        :bind! (mesh/seam net 99 binds)})
        manifest (publish-datoms! node facts)
        [[node] events] (run-nodes [node] 100 #(event % :published manifest))]
    (is (zero? @binds))
    (is (nil? (get-in node [:composition ::jing.dht/secret])))
    (is (= {:acknowledged? false :reason ::jing.dht/solo :peers 0}
           (select-keys (event events :published manifest)
                        [:acknowledged? :reason :peers])))
    (dht/close! node)))


(deftest a-manifest-no-peer-holds-fails-its-load
  (let [net (mesh/mesh)
        other (node-at net 71 [70] {:publish? true})
        reader (dht/load-index (node-at net 70 [71] {})
                               (jing/segment-key "nobody published this"))
        m (jing/segment-key "nobody published this")
        [nodes events] (run-nodes [reader other] 30000
                                  #(event % :load-failed m))]
    (is (= :failed (:status (dht/load-status (first nodes) m))))
    (is (re-find #"no peer produced" (:reason (event events :load-failed m))))
    (run! dht/close! nodes)))


(deftest join-refuses-options-it-cannot-honour
  (doseq [opts [{}
                {:local (mem/create-content-mem) :peers [{:host "localhost" :port 1}]
                 :bind! (fn [_])}
                {:local (mem/create-content-mem) :bind-host "::zz"}
                {:local (mem/create-content-mem) :publish? :yes}
                {:local (mem/create-content-mem) :max-inbound-bytes -1}]]
    (is (thrown? #?(:cljd Object :clj Exception :cljs :default) (dht/join opts))
        (pr-str (dissoc opts :local :bind!)))))


;; =============================================================================
;; join {:dir ...} owns its directory: the durable store's exclusive lock
;; =============================================================================

(defn- temp-dir
  []
  (str "target/test-space-dht-" (random-uuid)))


(defn- cleanup-dir!
  [dir]
  #?(:cljd (try (.deleteSync (dart-io/Directory. dir) .recursive true)
                (catch Object _ nil))
     :clj (let [f (java.io.File. dir)]
            (when (.isDirectory f)
              (doseq [child (.listFiles f)]
                (.delete child))
              (.delete f)))
     :cljs (try (.rmSync (js/require "fs") dir #js {:recursive true :force true})
                (catch :default _ nil))))


(defn- refusal-of
  "The error `thunk` throws, or nil (see yin.repl.store-test)."
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         e)))


(deftest a-joined-directory-is-locked-until-the-node-closes
  (let [dir (temp-dir)]
    (try
      (let [node (dht/join {:dir dir})]
        (testing "a second join of the same directory is refused, naming it"
          (let [e (refusal-of #(dht/join {:dir dir}))]
            (is (some? e))
            (is (str/includes? (str (ex-message e)) dir))
            (is (str/includes? (str (ex-message e)) "locked"))))
        (testing "the first node is unaffected"
          (let [manifest (publish-datoms! node facts)]
            (is (some? (jing/get (dht/local node) manifest nil)))))
        (dht/close! node))
      (testing "closing the node releases the directory"
        (let [again (dht/join {:dir dir})]
          (is (some? again))
          (dht/close! again)))
      (finally
        (cleanup-dir! dir)))))
