(ns dao.space.stigmergy-test
  "Agents collaborating by stigmergy over dao.space: every write is one
  atomic transaction through transactor/transact! on the agent's own
  :transactor wrapper, every read is query/q or query/match over explicit
  query/published-source descriptors. There is no coordinator and no
  stigmergy API — the conventions (self-stamped provenance, wall-clock
  leases, the [t agent] winner rule) are expressed by the datoms agents
  build and the query forms below (docs/dao.space.stigmergy.md).

  Publication is explicit: each agent publishes its local stream into one
  shared DaoJing intake pool, and a DaoJing observer over that pool
  materializes the covered indexes into a server-side dao.jing.file content
  store served over dao.stream.rpc. Publication enqueue alone is not
  visibility — the observer is. Readers query the published manifest
  addresses through the server file handle or a remote
  dao.jing.remote/connect-content! client, and the two must agree
  (transport transparency). JVM-only (blocking rpc, file store,
  wall-clock).

  The space persists after the run for inspection at target/stigmergy-space.db;
  readers reach it through explicit query/published-source descriptors."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.file :as file]
            [dao.jing.remote :as remote]
            [dao.space.query :as query]
            [dao.space.transactor :as transactor]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]
            [dao.stream.rpc.ws :as rpc-ws])
  (:import (java.io File)))


(def space-path "target/stigmergy-space.db")


;; ---------------------------------------------------------------------------
;; The medium: one shared DaoJing intake stream for the whole run, a
;; server-side dao.jing.file content store the observer materializes into,
;; and a websocket server exposing that store (remote/default-handlers +
;; remote/connect-content!). Publication is enqueue; the observer is
;; visibility; readers reach the file store locally or over the wire.
;; ---------------------------------------------------------------------------

(def ^:dynamic *store* nil)        ; the server-side file content store
(def ^:dynamic *url* nil)          ; websocket url of the store's rpc server
(def ^:dynamic *shared-intake* nil) ; one DaoJing intake stream, all agents


(defn- space-fixture
  "Serve a fresh file-backed content store for the whole run. The file from
   a prior run is deleted at START; it is never deleted at the end, so the
   finished simulation stays on disk for inspection with dao.space.query."
  [f]
  (let [fl (.getAbsoluteFile (File. space-path))]
    (.mkdirs (.getParentFile fl))
    (when (.exists fl) (.delete fl)))
  (let [store (file/create-content-file space-path)
        intake (ds/open! {:type :ringbuffer}) ; unbounded: position 0 never
        ;; evicts
        srv (rpc-ws/start! (remote/default-handlers store)
                           (+ 10000 (rand-int 50000)))]
    (try (binding [*store* store
                   *url* (str "ws://127.0.0.1:" (:port srv))
                   *shared-intake* intake]
           (f))
         (finally ((:stop! srv)) (ds/close! intake) (jing/close! store)))))


(use-fixtures :once space-fixture)


(defn- with-remote
  "Run f with a remote dao.jing content client; closes it after."
  [f]
  (let [client (remote/connect-content! *url*)]
    (try (f client) (finally (jing/close! client)))))


;; ---------------------------------------------------------------------------
;; Agent-side write convention (test code, not API): one atomic transaction
;; per entity through the agent's own :transactor wrapper — the wrapper owns
;; datom t. Fresh stream-local integer entity id, :dao/agent self-stamp, and
;; wall-clock
;; :claim/expires = now + lease-ms on claims are ordinary attributes.
;; ---------------------------------------------------------------------------

(defn- open-agent
  "One logical agent as plain data: its own local ringbuffer stream plus a
   single-writer :transactor wrapper publishing into the shared intake pool."
  [id]
  (let [local (ds/open! {:type :ringbuffer})]
    {:id id,
     :local local,
     :log (ds/open! {:type :transactor,
                     :local-stream local,
                     :intake-pool [*shared-intake*],
                     :name (str id)})}))


(defn- put-entity!
  "Deposit one entity as one atomic transaction through transactor/transact!.
   Returns {:e <entity id> :t <wall-clock ms>} — the wall clock separately,
   for lease arithmetic, because the transactor owns datom t."
  ([agent entity] (put-entity! agent entity {}))
  ([agent entity {:keys [lease-ms], :or {lease-ms 300000}}]
   (let [e (+ datom/first-user-id (count (ds/->seq nil (:local agent))))
         wall-t (System/currentTimeMillis)
         entity (cond-> (assoc entity
                               :db/id e
                               :dao/agent (:id agent))
                  (:claim/task entity) (assoc :claim/expires
                                              (+ wall-t lease-ms)))]
     (transactor/transact! (:log agent) [entity])
     {:e e, :t wall-t})))


(defn- publish-and-materialize!
  "Explicitly publish every agent's :transactor into the shared intake pool,
   then run the DaoJing observer over that pool into the server-side file
   content store until quiescent. Publication enqueue alone is not
   visibility; observer materialization is. Returns the manifest addresses,
   one per agent, in order. Re-observing the intake from cursor zero is
   idempotent under content addressing, so this is safe to call repeatedly."
  [agents]
  (let [addresses (mapv (comp :manifest-address transactor/publish! :log)
                        agents)]
    (loop [st (jing/observer-state [*shared-intake*])]
      (let [r (jing/observe-step! *store* st)]
        (case (:signal r)
          :ok (recur (:state r))
          :blocked addresses
          :end addresses
          :daostream/gap (throw (ex-info "test observer hit a gap"
                                         {:result r})))))))


(defn- published-source-pool
  "Immutable query sources over the queried content store (server file
   handle or remote content client), one per manifest address. Sources are
   snapshots: rebuild and republish after writes, never reuse one expecting
   it to advance."
  [content-store addresses]
  (mapv #(query/published-source content-store %) addresses))


(defn- sources
  "Publish the given agents and materialize them, then return a fresh pool
   of published query sources over the queried content store."
  [content-store agents]
  (published-source-pool content-store (publish-and-materialize! agents)))


;; ---------------------------------------------------------------------------
;; The read conventions, as plain query forms over published sources.
;; ---------------------------------------------------------------------------

(def available-q
  "Posted tasks with no live (unexpired) claim and no result."
  '[:find ?w ?title :in $ ?now :where [?w :task/posted true]
    [?w :task/title ?title]
    (not-join
      [?w ?now]
      [?c :claim/task ?w]
      [?c :claim/expires ?exp]
      [(< ?now ?exp)]) (not [_ :result/task ?w])])


(def live-claims-q
  "Unexpired claims on a task, with the datom t for the winner tie-break.
   t is the transactor's own per-agent transaction counter."
  '[:find ?by ?t :in $ ?w ?now :where [?c :claim/task ?w] [?c :claim/by ?by ?t]
    [?c :claim/expires ?exp] [(< ?now ?exp)]])


(def claims-q
  "Every claim ever made on a task — durable facts, expired or not."
  '[:find ?by ?t :in $ ?w :where [?c :claim/task ?w] [?c :claim/by ?by ?t]])


(def results-q
  '[:find ?out :in $ ?w :where [?r :result/task ?w] [?r :result/output ?out]])


(def fns {:fns {'< <}})


(defn- available
  [source now]
  (query/q available-q source now fns))


(defn- winner
  "The documented rule every reader applies identically: smallest [t agent]
   among live claims. t is the transactor-owned per-agent counter, so an
   equal-t race falls through to the agent id."
  [source task now]
  (->> (query/q live-claims-q source task now fns)
       (sort-by (juxt second first))
       first
       first))


;; ---------------------------------------------------------------------------
;; Scenarios
;; ---------------------------------------------------------------------------

(deftest full-stigmergy-loop
  (with-remote
    (fn [remote]
      (let [producer (open-agent "producer")
            glm (open-agent "worker-glm")
            deepseek (open-agent "worker-deepseek")
            {t1 :e} (put-entity! producer
                                 {:task/posted true, :task/title "haiku"})
            {t2 :e} (put-entity! producer
                                 {:task/posted true, :task/title "limerick"})]
        (testing "workers discover posted work associatively"
          (is (= #{[t1 "haiku"] [t2 "limerick"]}
                 (available (sources remote [producer glm deepseek])
                            (System/currentTimeMillis)))))
        (testing "a claim is a deposit on the claimant's own stream"
          (put-entity! glm {:claim/task t1, :claim/by "worker-glm"})
          (is (= #{[t2 "limerick"]}
                 (available (sources remote [producer glm deepseek])
                            (System/currentTimeMillis)))))
        (testing
          "a racing claim is recorded as a fact, never rejected;
                  the [t agent] rule picks one winner for every reader"
          (put-entity! deepseek {:claim/task t1, :claim/by "worker-deepseek"})
          (let [source (sources remote [producer glm deepseek])]
            (is (= 2 (count (query/q claims-q source t1)))
                "both claims are durable facts")
            (is
              (= "worker-deepseek"
                 (winner source t1 (System/currentTimeMillis)))
              "both racing claims carry t=0, each agent's first
                 transaction; the agent id breaks the tie")))
        (testing "the winner deposits the result; the task settles"
          (put-entity! glm
                       {:result/task t1,
                        :result/by "worker-glm",
                        :result/output "tuples drift like leaves"})
          (is (= #{[t2 "limerick"]}
                 (available (sources remote [producer glm deepseek])
                            (System/currentTimeMillis))))
          (is (= #{["tuples drift like leaves"]}
                 (query/q results-q
                          (sources remote [producer glm deepseek])
                          t1))))
        (testing "provenance: every entity carries its writer's stamp"
          (is (set/subset? #{["producer"] ["worker-glm"] ["worker-deepseek"]}
                           (query/q '[:find ?a :where [?e :dao/agent ?a]]
                                    (sources remote
                                             [producer glm deepseek])))))))))


(deftest claim-leases
  (with-remote
    (fn [remote]
      (let [poster (open-agent "lease-poster")
            slow (open-agent "worker-slow")
            fresh (open-agent "worker-fresh")
            source #(sources remote [poster slow fresh])
            {task :e} (put-entity! poster
                                   {:task/posted true,
                                    :task/title "short-lease task"})
            {claim-t :t} (put-entity! slow
                                      {:claim/task task,
                                       :claim/by "worker-slow"}
                                      {:lease-ms 150})]
        (testing "while the lease is live the task is claimed"
          (is (= "worker-slow" (winner (source) task (+ claim-t 100))))
          (is (not (contains?
                     (into #{} (map first) (available (source) (+ claim-t 100)))
                     task))))
        (testing "past the lease, an unfulfilled claim counts for nothing"
          (let [later (+ claim-t 151)]
            (is (nil? (winner (source) task later)))
            (is (contains? (into #{} (map first) (available (source) later))
                           task))
            (is
              (= 1 (count (query/q claims-q (source) task)))
              "the dead claim is still a durable fact — only the
                 interpretation changed")))
        (testing
          "anyone may re-claim after expiry and wins — only live leases
                  count, t order is irrelevant"
          (let [{re-t :t} (put-entity! fresh
                                       {:claim/task task,
                                        :claim/by "worker-fresh"})]
            (is (= "worker-fresh" (winner (source) task (+ re-t 100))))))
        (testing
          "a delivered result settles the task permanently, even after
                  every lease has lapsed"
          (put-entity! fresh
                       {:result/task task,
                        :result/by "worker-fresh",
                        :result/output "done"})
          (let [far-future (+ claim-t (* 1000 60 60))]
            (is (not (contains?
                       (into #{} (map first) (available (source) far-future))
                       task)))))))))


(deftest retracting-a-claim
  (with-remote
    (fn [remote]
      (let [poster (open-agent "retract-poster")
            worker (open-agent "worker-fickle")
            source #(sources remote [poster worker])
            {task :e} (put-entity! poster
                                   {:task/posted true,
                                    :task/title "retractable task"})
            {claim :e} (put-entity! worker
                                    {:claim/task task,
                                     :claim/by "worker-fickle"})]
        (is (not (contains? (into #{}
                                  (map first)
                                  (available (source)
                                             (System/currentTimeMillis)))
                            task)))
        (testing
          "an explicit retraction datom releases the claim
                  (current-state resolution through the stream write path)"
          ;; the transactor owns t, so the retraction carries nil t and an
          ;; explicit retract m; it lands as its own atomic transaction
          (transactor/transact! (:log worker)
                                [[claim :claim/task task nil
                                  (:db/retract datom/reserved)]])
          (is (contains? (into #{}
                               (map first)
                               (available (source) (System/currentTimeMillis)))
                         task)))))))


(deftest publication-republication
  (let [agent (open-agent "indexer")
        {before :e} (put-entity! agent {:marker/id "pre-index"})
        addr-a (first (publish-and-materialize! [agent]))]
    (testing "a published manifest is an immutable snapshot of its stream"
      (is (= #{[before "pre-index"]}
             (query/q '[:find ?e ?id :where [?e :marker/id ?id]]
                      (query/published-source *store* addr-a)))))
    (let [{after :e} (put-entity! agent {:marker/id "post-index"})
          addr-b (first (publish-and-materialize! [agent]))]
      (testing "a fresh manifest after more appends folds old and new data"
        (is (not= addr-a addr-b))
        (is (= #{[before "pre-index"] [after "post-index"]}
               (query/q '[:find ?e ?id :where [?e :marker/id ?id]]
                        (query/published-source *store* addr-b)))))
      (testing "the earlier snapshot is untouched"
        (is (= #{[before "pre-index"]}
               (query/q '[:find ?e ?id :where [?e :marker/id ?id]]
                        (query/published-source *store* addr-a))))))))


(deftest transport-transparency
  (with-remote
    (fn [remote]
      (let [agent (open-agent "transparency-probe")]
        (put-entity! agent {:probe/id "wire"})
        (let [address (first (publish-and-materialize! [agent]))]
          (testing
            "the same published manifest address reads identically from the
                    server-side file content handle and the remote content
                    client — the rpc is invisible, and the datoms are durable
                    in the file store"
            (is (= (query/q '[:find ?e ?a ?v :where [?e ?a ?v]]
                            (query/published-source *store* address))
                   (query/q '[:find ?e ?a ?v :where [?e ?a ?v]]
                            (query/published-source remote address))))
            (is (contains? (query/q '[:find ?id :where [_ :probe/id ?id]]
                                    (query/published-source remote address))
                           ["wire"]))))))))
