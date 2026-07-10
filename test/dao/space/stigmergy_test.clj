(ns dao.space.stigmergy-test
  "End-to-end test of the agent-collaboration prototype
  (docs/dao.space.stigmergy.md): a poster and two workers coordinate purely
  through deposits and associative reads over the wire — the full
  stigmergy loop with the LLM step replaced by the test body, including
  gap 10's claim leases. JVM-only: the coordinator and the rpc client are
  :clj."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.space.stigmergy :as server]
            [dao.stream.rpc.client :as rpc-client]
            [dao.space.query :as query]))


(defn- with-coordinator
  [opts f]
  (let [srv (server/start! (+ 10000 (rand-int 50000)) opts)
        url (str "ws://127.0.0.1:" (:port srv))]
    (try (f url) (finally ((:stop! srv))))))


(defn- call!
  [url op args]
  (let [client (rpc-client/connect! url)]
    (try (rpc-client/call! client op args)
         (finally (rpc-client/close! client)))))


(defn- board-task
  [url task-id]
  (first (filter #(= task-id (:task/id %))
                 (:tasks (call! url :space/board [])))))


(deftest full-stigmergy-loop
  (with-coordinator
    {}
    (fn [url]
      (testing "the vocabulary is discoverable before anything is deposited"
        (let [voc (call! url :space/vocabulary [])]
          (is (contains? (:attributes voc) :task/id))
          (is (pos? (:lease-ms voc)))))
      (let [task-id (str (random-uuid))]
        (testing "a poster deposits work without naming a recipient"
          (let [r (call! url
                         :space/deposit
                         ["poster"
                          [{:task/id task-id,
                            :task/title "write a haiku about tuple spaces",
                            :task/posted true}]])]
            (is (pos? (:deposited r)))
            (is (number? (:t r)) "the coordinator stamps wall-clock t")))
        (testing "a worker discovers the task associatively"
          (is (= :unclaimed (:status (board-task url task-id)))))
        (testing "a worker claims by depositing to its own log"
          (call! url
                 :space/deposit
                 ["worker-glm" [{:claim/task task-id, :claim/by "worker-glm"}]])
          (let [task (board-task url task-id)]
            (is (= :claimed (:status task)))
            (is (= "worker-glm" (:winner task)))
            (is (number? (:expires (first (:claims task))))
                "the coordinator stamps the lease expiry")))
        (testing
          "a racing claim is recorded as a fact, then resolved on the
                 read side — never rejected by the medium"
          (Thread/sleep 5) ; distinct wall-clock t, so the tie-break is on
          ;; t
          (call! url
                 :space/deposit
                 ["worker-deepseek"
                  [{:claim/task task-id, :claim/by "worker-deepseek"}]])
          (let [task (board-task url task-id)]
            (is (= 2 (count (:claims task))) "both claims are durable facts")
            (is (= "worker-glm" (:winner task))
                "smallest [t agent] among live claims wins")))
        (testing "the winner deposits the result; the task settles"
          (call! url
                 :space/deposit
                 ["worker-glm"
                  [{:result/task task-id,
                    :result/by "worker-glm",
                    :result/output "tuples drift like leaves"}]])
          (let [task (board-task url task-id)]
            (is (= :done (:status task)))
            (is (= "worker-glm" (:winner task))))
          (is (= #{["tuples drift like leaves"]}
                 (call! url
                        :space/query
                        [[:find '?out :where ['?r :result/task task-id]
                          ['?r :result/output '?out]]]))))
        (testing "provenance: every entity carries the coordinator's stamp"
          (is (= #{["poster"] ["worker-glm"] ["worker-deepseek"]}
                 (call! url
                        :space/query
                        ['[:find ?a :where [?e :dao/agent ?a]]]))))))))


(deftest claim-leases
  (with-coordinator
    {:lease-ms 150}
    (fn [url]
      (let [task-id (str (random-uuid))]
        (call! url
               :space/deposit
               ["poster"
                [{:task/id task-id,
                  :task/title "short-lease task",
                  :task/posted true}]])
        (testing "a claim is stamped with expires = t + lease-ms"
          (let [{:keys [t]} (call! url
                                   :space/deposit
                                   ["worker-slow"
                                    [{:claim/task task-id,
                                      :claim/by "worker-slow"}]])
                claim (first (:claims (board-task url task-id)))]
            (is (= (+ t 150) (:expires claim)))
            (is (:live? claim))))
        (testing
          "an expired claim without a result counts for nothing:
                 the task returns to the unclaimed pool"
          (Thread/sleep 250)
          (let [task (board-task url task-id)]
            (is (= :unclaimed (:status task)))
            (is (nil? (:winner task)))
            (is
              (= 1 (count (:claims task)))
              "the dead claim is still a durable fact — only the
                interpretation changed")))
        (testing
          "anyone may re-claim after expiry, and the re-claimer wins
                 despite the earlier (expired) claim's smaller t"
          (call! url
                 :space/deposit
                 ["worker-fresh"
                  [{:claim/task task-id, :claim/by "worker-fresh"}]])
          (let [task (board-task url task-id)]
            (is (= :claimed (:status task)))
            (is (= "worker-fresh" (:winner task)))))
        (testing
          "a delivered result settles the task permanently, even
                 after its claim's lease has lapsed"
          (call! url
                 :space/deposit
                 ["worker-fresh"
                  [{:result/task task-id,
                    :result/by "worker-fresh",
                    :result/output "done"}]])
          (Thread/sleep 250) ; well past worker-fresh's lease
          (let [task (board-task url task-id)]
            (is (= :done (:status task)))
            (is (= "worker-fresh" (:winner task)))))))))


(deftest prototype-issues
  (with-coordinator
    {}
    (fn [url]
      (testing "Issue 2: deposit! rejects a single map instead of a vector"
        ;; Ensures single maps are properly rejected since stigmergy
        ;; convention requires a vector of entities
        (is (thrown-with-msg? Exception
                              #"deposit requires a vector"
              (call! url
                     :space/deposit
                     ["poster"
                      {:task/id "t-map",
                       :task/posted true,
                       :task/title "single map"}]))))
      (testing "Issue 4: tasks without titles disappear from the board"
        (call! url
               :space/deposit
               ["poster" [{:task/id "t-notitle", :task/posted true}]])
        (let [task (board-task url "t-notitle")]
          (is (some? task)
              "Task without title should still appear on the board")))
      (testing "Issue 3: Agent impersonation / lease forgery"
        (let [task-id "t-forge"]
          (call! url
                 :space/deposit
                 ["poster"
                  [{:task/id task-id, :task/title "task", :task/posted true}]])
          ;; Worker tries to spoof its lease and provenance
          (call! url
                 :space/deposit
                 ["malicious"
                  [{:claim/task task-id,
                    :claim/by "malicious",
                    :dao/agent "admin",
                    :claim/expires 9999999999999}]])
          (let [task (board-task url task-id)
                claim (first (:claims task))]
            (is (not= 9999999999999 (:expires claim))
                "Lease forgery should be prevented")
            ;; the real hole behind the finding: the forged :dao/agent
            ;; lands as a SECOND provenance datom on the entity — only
            ;; coordinator-stamped agent ids may ever appear
            (is (= #{["poster"] ["malicious"]}
                   (call! url
                          :space/query
                          ['[:find ?a :where [?e :dao/agent ?a]]]))
                "provenance must show only coordinator-stamped agent ids"))
          ;; the deeper spoof the review missed: :claim/by is
          ;; agent-supplied and never validated — worker-x claims as
          ;; worker-y outright
          (call! url
                 :space/deposit
                 ["worker-x" [{:claim/task task-id, :claim/by "worker-y"}]])
          (let [claim-bys (set (map :by (:claims (board-task url task-id))))]
            (is (contains? claim-bys "worker-x")
                "authorship is stamped to the depositing agent")
            (is (not (contains? claim-bys "worker-y"))
                "an agent cannot claim in another agent's name")))))))


(deftest data-loss-on-indexed-store
  (testing "Issue 1: deposit! preserves data when publish-index! is used"
    (let [stores (atom {})
          agent-id "worker-1"
          deposit! #'server/deposit!]
      (deposit! stores agent-id [{:task/id "t1"}] 5000)
      (let [store (get @stores agent-id)]
        (query/publish-index! store)
        (deposit! stores agent-id [{:task/id "t2"}] 5000)
        (is (= #{"t1" "t2"}
               (set (map first
                         (query/q '[:find ?id :where [_ :task/id ?id]] [store]))))
            "Data from before the index should not be lost")))))
