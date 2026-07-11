(ns dao.space.stigmergy-test
  "Agents collaborating by stigmergy over dao.space: every write is ds/put!
  on the agent's own :dao-stream, every read is query/q or query/match.
  There is no coordinator and no stigmergy API — the conventions (self-stamped
  provenance, wall-clock t, claim leases, the [t agent] winner rule) are
  expressed by the datoms agents build and the query forms below
  (docs/dao.space.stigmergy.md).

  The shared medium is a network-accessible dao.jing.file store served over
  dao.stream.rpc — the rpc is an implementation detail below IKVStore, so
  :dao-stream and q/match work over it unmodified. JVM-only (blocking rpc,
  file store, wall-clock).

  The space persists after the run for inspection at target/stigmergy-space.db:
    (query/q '[:find ?e ?a ?v :where [?e ?a ?v]]
             (file/create-kv-file \"target/stigmergy-space.db\"))"
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.file :as file]
            [dao.jing.remote.client :as remote-client]
            [dao.jing.remote.server :as remote-server]
            [dao.space.query :as query]
            [dao.space.stream] ; loaded for defopen :dao-stream side effect
            [dao.stream :as ds]
            [dao.stream.rpc.server :as rpc-server])
  (:import (java.io File)))


(def space-path "target/stigmergy-space.db")


;; ---------------------------------------------------------------------------
;; The medium: a dao.jing.file store served over dao.stream.rpc.
;; Exposing a store is just registering its ops as handlers; consuming one is
;; a reify over rpc-client/call!. No namespaces needed — dao.stream.rpc makes
;; any function remotely callable.
;; ---------------------------------------------------------------------------



(def ^:dynamic *store* nil) ; the server-side file store
(def ^:dynamic *url* nil)


(defn- space-fixture
  "Serve a fresh file-backed store for the whole run. The file from a prior
  run is deleted at START; it is never deleted at the end, so the finished
  simulation stays on disk for inspection with dao.space.query."
  [f]
  (let [fl (.getAbsoluteFile (File. space-path))]
    (.mkdirs (.getParentFile fl))
    (when (.exists fl) (.delete fl)))
  (let [store (file/create-kv-file space-path)
        srv (rpc-server/start! (remote-server/default-handlers store)
                               (+ 10000 (rand-int 50000)))]
    (try (binding [*store* store *url* (str "ws://127.0.0.1:" (:port srv))] (f))
         (finally ((:stop! srv)) (jing/close! store)))))


(use-fixtures :once space-fixture)


(defn- with-remote
  "Run f with a remote dao.jing handle; closes the rpc client after."
  [f]
  (let [client (remote-client/connect! *url*)]
    (try (f client) (finally (jing/close! client)))))


;; ---------------------------------------------------------------------------
;; Agent-side write convention (test code, not API): fresh UUID entity id,
;; :dao/agent self-stamp, wall-clock t in the datom t slot, and
;; :claim/expires = t + lease-ms on claims. The write itself is only ds/put!.
;; ---------------------------------------------------------------------------

(defn- entity-datoms
  [agent-id entity {:keys [lease-ms], :or {lease-ms 300000}}]
  (let [t (System/currentTimeMillis)
        e (str (random-uuid))
        entity (cond-> (assoc entity :dao/agent agent-id)
                 (:claim/task entity) (assoc :claim/expires (+ t lease-ms)))]
    {:e e,
     :t t,
     :datoms (vec (for [[a v] entity] [e a v t datom/default-op]))}))


(defn- put-entity!
  "Deposit an entity as stamped datoms on the agent's own stream. Returns
  {:e <entity id> :t <wall-clock t>}."
  ([log agent-id entity] (put-entity! log agent-id entity {}))
  ([log agent-id entity opts]
   (let [{:keys [datoms], :as stamped} (entity-datoms agent-id entity opts)]
     (run! #(ds/put! log %) datoms)
     (select-keys stamped [:e :t]))))


(defn- open-agent
  [store agent-id]
  (ds/open! {:type :dao-stream, :store store, :name agent-id}))


;; ---------------------------------------------------------------------------
;; The read conventions, as plain query forms.
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
  "Unexpired claims on a task, with the datom t for the winner tie-break."
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
  among live claims."
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
      (let [producer (open-agent remote "producer")
            glm (open-agent remote "worker-glm")
            deepseek (open-agent remote "worker-deepseek")
            {t1 :e} (put-entity! producer
                                 "producer"
                                 {:task/posted true, :task/title "haiku"})
            {t2 :e} (put-entity! producer
                                 "producer"
                                 {:task/posted true, :task/title "limerick"})
            now (System/currentTimeMillis)]
        (testing "workers discover posted work associatively"
          (is (= #{[t1 "haiku"] [t2 "limerick"]} (available remote now))))
        (testing "a claim is a deposit on the claimant's own stream"
          (put-entity! glm
                       "worker-glm"
                       {:claim/task t1, :claim/by "worker-glm"})
          (is (= #{[t2 "limerick"]}
                 (available remote (System/currentTimeMillis)))))
        (testing
          "a racing claim is recorded as a fact, never rejected;
                  the [t agent] rule picks one winner for every reader"
          (Thread/sleep 5) ; distinct wall-clock t
          (put-entity! deepseek
                       "worker-deepseek"
                       {:claim/task t1, :claim/by "worker-deepseek"})
          (is (= 2 (count (query/q claims-q remote t1)))
              "both claims are durable facts")
          (is (= "worker-glm" (winner remote t1 (System/currentTimeMillis)))))
        (testing "the winner deposits the result; the task settles"
          (put-entity! glm
                       "worker-glm"
                       {:result/task t1,
                        :result/by "worker-glm",
                        :result/output "tuples drift like leaves"})
          (is (= #{[t2 "limerick"]}
                 (available remote (System/currentTimeMillis))))
          (is (= #{["tuples drift like leaves"]}
                 (query/q results-q remote t1))))
        (testing "provenance: every entity carries its writer's stamp"
          (is (set/subset? #{["producer"] ["worker-glm"] ["worker-deepseek"]}
                           (query/q '[:find ?a :where [?e :dao/agent ?a]]
                                    remote))))))))


(deftest claim-leases
  (with-remote
    (fn [remote]
      (let [poster (open-agent remote "lease-poster")
            slow (open-agent remote "worker-slow")
            fresh (open-agent remote "worker-fresh")
            {task :e} (put-entity! poster
                                   "lease-poster"
                                   {:task/posted true,
                                    :task/title "short-lease task"})
            {claim-t :t} (put-entity! slow
                                      "worker-slow"
                                      {:claim/task task,
                                       :claim/by "worker-slow"}
                                      {:lease-ms 150})]
        (testing "while the lease is live the task is claimed"
          (is (= "worker-slow" (winner remote task (+ claim-t 100))))
          (is (not (contains?
                     (into #{} (map first) (available remote (+ claim-t 100)))
                     task))))
        (testing "past the lease, an unfulfilled claim counts for nothing"
          (let [later (+ claim-t 151)]
            (is (nil? (winner remote task later)))
            (is (contains? (into #{} (map first) (available remote later))
                           task))
            (is
              (= 1 (count (query/q claims-q remote task)))
              "the dead claim is still a durable fact — only the
                 interpretation changed")))
        (testing
          "anyone may re-claim after expiry and wins despite the
                  earlier claim's smaller t"
          (Thread/sleep 5)
          (let [{re-t :t} (put-entity! fresh
                                       "worker-fresh"
                                       {:claim/task task,
                                        :claim/by "worker-fresh"})]
            (is (= "worker-fresh" (winner remote task (+ re-t 100))))))
        (testing
          "a delivered result settles the task permanently, even after
                  every lease has lapsed"
          (put-entity! fresh
                       "worker-fresh"
                       {:result/task task,
                        :result/by "worker-fresh",
                        :result/output "done"})
          (let [far-future (+ claim-t (* 1000 60 60))]
            (is (not (contains?
                       (into #{} (map first) (available remote far-future))
                       task)))))))))


(deftest retracting-a-claim
  (with-remote
    (fn [remote]
      (let [poster (open-agent remote "retract-poster")
            worker (open-agent remote "worker-fickle")
            {task :e} (put-entity! poster
                                   "retract-poster"
                                   {:task/posted true,
                                    :task/title "retractable task"})
            {claim :e, claim-t :t} (put-entity! worker
                                                "worker-fickle"
                                                {:claim/task task,
                                                 :claim/by "worker-fickle"})
            now (System/currentTimeMillis)]
        (is (not (contains? (into #{} (map first) (available remote now))
                            task)))
        (testing
          "an explicit retraction datom releases the claim
                  (current-state resolution through the stream write path)"
          ;; the retraction carries a wall-clock t after the claim's, per
          ;; the convention — current-state resolution orders by t
          (ds/put! worker
                   [claim :claim/task task (inc claim-t)
                    (:db/retract datom/reserved)])
          (is (contains? (into #{} (map first) (available remote now))
                         task)))))))


(deftest indexed-root-survival
  ;; Local store, not the shared remote medium: publish-index! roots hold
  ;; segment keywords (:segment/<sha>) whose names start with a digit —
  ;; unreadable EDN, so an indexed root cannot cross the rpc transport
  ;; today. The subject here is the stream write path's fold, which is
  ;; transport-independent.
  (let [store (jing/create-kv-mem)
        agent (open-agent store "indexer")
        {before :e} (put-entity! agent "indexer" {:marker/id "pre-index"})]
    (query/publish-index! store)
    (let [{after :e} (put-entity! agent "indexer" {:marker/id "post-index"})]
      (is
        (= #{[before "pre-index"] [after "post-index"]}
           (query/q '[:find ?e ?id :where [?e :marker/id ?id]] store))
        "a stream append onto a publish-index!-ed root folds the indexed
           datoms back rather than dropping them"))))


(deftest transport-transparency
  (with-remote
    (fn [remote]
      (put-entity! (open-agent remote "transparency-probe")
                   "transparency-probe"
                   {:probe/id "wire"})
      (testing
        "q over the server-side store sees exactly what agents put!
                through remote handles — the rpc is invisible above IKVStore,
                and the datoms are durable in the file store"
        (is (= (query/q '[:find ?e ?a ?v :where [?e ?a ?v]] *store*)
               (query/q '[:find ?e ?a ?v :where [?e ?a ?v]] remote)))
        (is (contains? (query/q '[:find ?id :where [_ :probe/id ?id]] *store*)
                       ["wire"]))))))
