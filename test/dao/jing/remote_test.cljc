(ns dao.jing.remote-test
  "Tests for dao.jing.remote — exposing a local IKVStore over the network via dao.stream.apply.

   Architecture:
   - Server: exposes a local dao.jing store (file or memory) over WebSocket
   - Client: implements IKVStore by sending put!/cas!/get/delete! requests over the network"
  (:require [clojure.test :as t :refer [deftest is testing use-fixtures]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.jing.file :as jing.file]
            [dao.jing.remote.server :as remote-server]
            [dao.jing.remote.client :as remote-client]
            [dao.stream :as ds]))


;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *server* nil)
(def ^:dynamic *client* nil)
(def ^:dynamic *store* nil)


#?(:clj (defn- random-port
          []
          (+ 10000 (rand-int 50000))))


#?(:clj (defn- with-remote-server
          [store-type f]
          (let [port (random-port)
                ;; Create backing store (memory for speed, file for
                ;; persistence
                ;; tests)
                backing-store (case store-type
                                :memory (mem/create-kv-mem)
                                :file (jing.file/create-kv-file
                                        (str "/tmp/dao-jing-remote-test-"
                                             (rand-int 1000000))))
                ;; Start WebSocket server exposing the store
                server (remote-server/start! backing-store port)
                ;; Give server time to start
                _ (Thread/sleep 100)]
            (try (binding [*server* server *store* backing-store] (f))
                 (finally (remote-server/stop! server)
                          (jing/close! backing-store))))))


;; =============================================================================
;; Basic Connectivity Tests
;; =============================================================================

(deftest client-can-connect-to-server-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote-client/connect! url)]
                (is (some? client) "Client should connect successfully")
                (is (satisfies? jing/IKVStore client)
                    "Client should implement IKVStore")
                (jing/close! client))))))


(deftest client-rejects-invalid-url-test
  #?(:clj (testing "Client throws on connection failure"
            (is (thrown? Exception
                  (remote-client/connect! "ws://localhost:99999"))))))


;; =============================================================================
;; put! and get Operations
;; =============================================================================

(deftest remote-put-and-get-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote-client/connect! url)]
                (try
                  ;; Put a value
                  (is (true? (jing/put! client
                                        :test-key
                                        {:value "hello", :bytes [1 2 3]}))
                      "put! should return true on success")
                  ;; Get it back
                  (let [result (jing/get client :test-key nil)]
                    (is (= "hello" (:value result))
                        "get should return the stored value")
                    (is (= [1 2 3] (:bytes result))
                        "get should preserve all fields")
                    (is (= 0 (:rev result)) "Initial revision should be 0"))
                  ;; Get non-existent key
                  (is (= :not-found (jing/get client :missing-key :not-found))
                      "get should return not-found sentinel for missing keys")
                  (finally (jing/close! client))))))))


(deftest remote-put-overwrites-test
  #?(:clj (with-remote-server :memory
            (fn []
              (let [url (str "ws://localhost:"
                             (:port *server*))
                    client (remote-client/connect! url)]
                (try
                  ;; First put
                  (jing/put! client :key {:v 1})
                  (is (= 1 (:v (jing/get client :key nil))))
                  ;; Second put (overwrites with rev 0)
                  (jing/put! client :key {:v 2})
                  (is (= 2 (:v (jing/get client :key nil))))
                  (is (= 0 (:rev (jing/get client :key nil)))
                      "put! resets revision to 0")
                  (finally (jing/close! client))))))))


;; =============================================================================
;; cas! (Compare-And-Swap) Operations
;; =============================================================================

(deftest remote-cas-success-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote-client/connect! url)]
                (try
                  ;; Initial put
                  (jing/put! client :counter {:n 0})
                  (is (= 0 (:rev (jing/get client :counter nil))))
                  ;; Successful CAS
                  (is (true? (jing/cas! client :counter 0 {:n 1}))
                      "cas! should return true when revision matches")
                  ;; Verify update
                  (let [result (jing/get client :counter nil)]
                    (is (= 1 (:n result)))
                    (is (= 1 (:rev result))
                        "Revision should increment after CAS"))
                  (finally (jing/close! client))))))))


(deftest remote-cas-failure-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote-client/connect! url)]
                (try
                  ;; Initial put
                  (jing/put! client :counter {:n 0})
                  ;; First CAS succeeds
                  (jing/cas! client :counter 0 {:n 1})
                  ;; Second CAS with stale revision fails
                  (is (false? (jing/cas! client :counter 0 {:n 2}))
                      "cas! should return false when revision doesn't match")
                  ;; Value unchanged
                  (is (= 1 (:n (jing/get client :counter nil))))
                  (finally (jing/close! client))))))))


(deftest remote-cas-on-fresh-key-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote-client/connect! url)]
                (try
                  ;; CAS on non-existent key with old-rev 0 should succeed
                  (is (true? (jing/cas! client :fresh-key 0 {:data "value"})))
                  (is (= "value" (:data (jing/get client :fresh-key nil))))
                  (finally (jing/close! client))))))))


;; =============================================================================
;; delete! Operations
;; =============================================================================

(deftest remote-delete-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote-client/connect! url)]
                (try
                  ;; Put then delete
                  (jing/put! client :temp {:data "to delete"})
                  (is (true? (jing/delete! client :temp))
                      "delete! should return true")
                  ;; Key should be gone
                  (is (= :gone (jing/get client :temp :gone))
                      "get should return not-found after delete")
                  (finally (jing/close! client))))))))


(deftest remote-delete-missing-key-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote-client/connect! url)]
                (try
                  ;; Deleting non-existent key should succeed (be
                  ;; idempotent)
                  (is (true? (jing/delete! client :never-existed)))
                  (finally (jing/close! client))))))))


;; =============================================================================
;; Multiple Client Tests
;; =============================================================================

(deftest multiple-clients-share-store-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client-a (remote-client/connect! url)
                    client-b (remote-client/connect! url)]
                (try
                  ;; Client A writes
                  (jing/put! client-a :shared {:from "a"})
                  ;; Client B should see it
                  (is (= "a" (:from (jing/get client-b :shared nil)))
                      "Client B should see writes from Client A")
                  ;; Client B updates via CAS
                  (let [current (jing/get client-b :shared nil)]
                    (jing/cas! client-b :shared (:rev current) {:from "b"}))
                  ;; Client A should see the update
                  (is (= "b" (:from (jing/get client-a :shared nil)))
                      "Client A should see CAS from Client B")
                  (finally (jing/close! client-a) (jing/close! client-b))))))))


;; =============================================================================
;; File-backed Store Tests
;; =============================================================================

(deftest remote-file-store-persists-test
  #?(:clj (testing "File-backed store persists across client reconnections"
            (let [port (random-port)
                  path (str "/tmp/dao-jing-remote-persist-test-"
                            (rand-int 1000000))
                  backing-store (jing.file/create-kv-file path)]
              (try
                ;; Start server with file store
                (let [server (remote-server/start! backing-store port)]
                  (Thread/sleep 100)
                  ;; Client 1 connects and writes
                  (let [client1 (remote-client/connect! (str "ws://localhost:"
                                                             port))]
                    (jing/put! client1 :persistent {:data "survives"})
                    (jing/close! client1))
                  ;; Stop server
                  (remote-server/stop! server)
                  (jing/close! backing-store))
                ;; Reopen the same file
                (let [backing-store2 (jing.file/create-kv-file path)
                      server2 (remote-server/start! backing-store2 port)]
                  (Thread/sleep 100)
                  ;; Client 2 connects and reads
                  (let [client2 (remote-client/connect! (str "ws://localhost:"
                                                             port))]
                    (is (= "survives"
                           (:data (jing/get client2 :persistent nil)))
                        "Data should persist across server restarts")
                    (jing/close! client2))
                  (remote-server/stop! server2)
                  (jing/close! backing-store2))
                (finally
                  ;; Cleanup
                  (try #?(:clj (java.nio.file.Files/deleteIfExists
                                 (java.nio.file.Path/of path
                                                        (make-array String 0))))
                       (catch #?(:clj Exception
                                 :cljs :default)
                              _))))))))


(deftest client-operations-fail-when-server-down-test
  #?(:clj (testing
            "Client throws on connection timeout when server is unreachable"
            (is (thrown? Exception
                  (remote-client/connect! "ws://localhost:59999"))))))


;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest remote-empty-value-map-test
  #?(:clj (with-remote-server :memory
            (fn []
              (let [url (str "ws://localhost:"
                             (:port *server*))
                    client (remote-client/connect! url)]
                (try
                  ;; Empty value map
                  (jing/put! client :empty {})
                  (let [result (jing/get client :empty nil)]
                    (is (= {} (dissoc result :rev))
                        "Empty map should round-trip"))
                  (finally (jing/close! client))))))))


(deftest remote-large-value-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote-client/connect! url)
                    large-data (vec (range 1000))]
                (try (jing/put! client :large {:data large-data})
                     (is (= large-data (:data (jing/get client :large nil)))
                         "Large values should round-trip correctly")
                     (finally (jing/close! client))))))))


(deftest remote-special-characters-in-keys-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote-client/connect! url)]
                (try
                  ;; Various key types that dao.jing supports
                  (jing/put! client :keyword-key {:type "keyword"})
                  (jing/put! client "string-key" {:type "string"})
                  (jing/put! client 42 {:type "number"})
                  (is (= "keyword" (:type (jing/get client :keyword-key nil))))
                  (is (= "string" (:type (jing/get client "string-key" nil))))
                  (is (= "number" (:type (jing/get client 42 nil))))
                  (finally (jing/close! client))))))))


(deftest client-close-is-idempotent-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote-client/connect! url)]
                ;; Multiple closes should not error
                (jing/close! client)
                (jing/close! client)
                (is (true? true) "Multiple close! calls should not throw"))))))
