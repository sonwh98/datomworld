(ns dao.jing.remote-test
  "Tests for dao.jing.remote — the WebSocket-remote IKVStore adapter.

   Architecture:
   - Server side uses dao.stream.rpc.server with dao.jing.remote/default-handlers
   - Client side uses dao.jing.remote/connect-kv!, which wraps a
     dao.stream.rpc.client connection as a RemoteKVStore implementing IKVStore"
  (:require [clojure.test :as t :refer [deftest is testing use-fixtures]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.jing.file :as jing.file]
            [dao.jing.remote :as remote]
            [dao.stream.rpc.ws :as rpc-ws]))


;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *server* nil)
(def ^:dynamic *client* nil)
(def ^:dynamic *store* nil)


#?(:clj (defn- random-port
          []
          (+ 10000 (rand-int 50000))))


#?(:clj (defn- start-kv!
          [store port]
          (rpc-ws/start! (remote/default-handlers store) port)))


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
                server (start-kv! backing-store port)
                ;; Give server time to start
                _ (Thread/sleep 100)]
            (try (binding [*server* server *store* backing-store] (f))
                 (finally (rpc-ws/stop! server) (jing/close! backing-store))))))


;; =============================================================================
;; Basic Connectivity Tests
;; =============================================================================

(deftest client-can-connect-to-server-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote/connect-kv! url)]
                (is (some? client) "Client should connect successfully")
                (is (satisfies? jing/IKVStore client)
                    "Client should implement IKVStore")
                (jing/close! client))))))


(deftest client-rejects-invalid-url-test
  #?(:clj (testing "Client throws on connection failure"
            (is (thrown? Exception
                  (remote/connect-kv! "ws://localhost:99999"))))))


;; =============================================================================
;; cas! and get Operations
;; =============================================================================

(deftest remote-opaque-values-survive-transit
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote/connect-kv! url)]
                (try (testing "any value round-trips across the transit codec"
                       (doseq [v [42 "s" :kw [1 2 3] #{:x} {:m 1} {}]]
                         (jing/delete! client :opaque)
                         (is (true? (jing/cas! client :opaque jing/absent v)))
                         (is (= v (jing/get client :opaque ::miss))
                             (str (pr-str v) " must survive the wire"))))
                     (testing "a stored nil is present, not absent"
                       (is (true? (jing/cas! client :nil-v jing/absent nil)))
                       (is (nil? (jing/get client :nil-v ::miss))
                           "nil must not be reported as not-found")
                       (is (= ::miss (jing/get client :never-written ::miss))
                           "an absent key is still distinguishable"))
                     (testing "and nil is quotable as cas!'s expected value"
                       (is (true? (jing/cas! client :nil-v nil {:now "set"})))
                       (is (= {:now "set"} (jing/get client :nil-v nil))))
                     (finally (jing/close! client))))))))


(deftest remote-put-and-get-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote/connect-kv! url)]
                (try
                  ;; Put a value via cas!
                  (is (true? (jing/cas! client
                                        :test-key
                                        jing/absent
                                        {:value "hello", :bytes [1 2 3]}))
                      "cas! should return true on success")
                  ;; Get it back
                  (let [result (jing/get client :test-key nil)]
                    (is (= "hello" (:value result))
                        "get should return the stored value")
                    (is (= [1 2 3] (:bytes result))
                        "get should preserve all fields"))
                  ;; Get non-existent key
                  (is (= :not-found (jing/get client :missing-key :not-found))
                      "get should return not-found sentinel for missing keys")
                  (finally (jing/close! client))))))))


(deftest remote-put-overwrites-test
  #?(:clj (with-remote-server :memory
            (fn []
              (let [url (str "ws://localhost:"
                             (:port *server*))
                    client (remote/connect-kv! url)]
                (try
                  ;; First write
                  (jing/cas! client :key jing/absent {:v 1})
                  (is (= 1 (:v (jing/get client :key nil))))
                  ;; Second write via cas! replaces with
                  ;; expected
                  (jing/cas! client :key {:v 1} {:v 2})
                  (is (= {:v 2} (jing/get client :key nil))
                      "put! replaces the value wholesale")
                  (finally (jing/close! client))))))))


;; =============================================================================
;; cas! (Compare-And-Swap) Operations
;; =============================================================================

(deftest remote-cas-success-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote/connect-kv! url)]
                (try
                  ;; Initial write
                  (jing/cas! client :counter jing/absent {:n 0})
                  (is (= {:n 0} (jing/get client :counter nil)))
                  ;; Successful CAS
                  (is (true? (jing/cas! client :counter {:n 0} {:n 1}))
                      "cas! should return true when the expected value matches")
                  ;; Verify update
                  (is (= {:n 1} (jing/get client :counter nil)))
                  (finally (jing/close! client))))))))


(deftest remote-cas-failure-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote/connect-kv! url)]
                (try
                  ;; Initial write
                  (jing/cas! client :counter jing/absent {:n 0})
                  ;; First CAS succeeds
                  (jing/cas! client :counter {:n 0} {:n 1})
                  ;; Second CAS quoting the now-stale value fails
                  (is
                    (false? (jing/cas! client :counter {:n 0} {:n 2}))
                    "cas! should return false when the expected value is stale")
                  ;; Value unchanged
                  (is (= 1 (:n (jing/get client :counter nil))))
                  (finally (jing/close! client))))))))


(deftest remote-cas-on-fresh-key-test
  #?(:clj
     (with-remote-server
       :memory
       (fn []
         (let [url (str "ws://localhost:" (:port *server*))
               client (remote/connect-kv! url)]
           (try
             ;; CAS against jing/absent creates a non-existent key
             (is (true?
                   (jing/cas! client :fresh-key jing/absent {:data "value"})))
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
                    client (remote/connect-kv! url)]
                (try
                  ;; Put then delete
                  (jing/cas! client :temp jing/absent {:data "to delete"})
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
                    client (remote/connect-kv! url)]
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
                    client-a (remote/connect-kv! url)
                    client-b (remote/connect-kv! url)]
                (try
                  ;; Client A writes
                  (jing/cas! client-a :shared jing/absent {:from "a"})
                  ;; Client B should see it
                  (is (= "a" (:from (jing/get client-b :shared nil)))
                      "Client B should see writes from Client A")
                  ;; Client B updates via CAS
                  (let [current (jing/get client-b :shared nil)]
                    (jing/cas! client-b :shared current {:from "b"}))
                  ;; Client A should see the update
                  (is (= "b" (:from (jing/get client-a :shared nil)))
                      "Client A should see CAS from Client B")
                  (finally (jing/close! client-a) (jing/close! client-b))))))))


;; =============================================================================
;; File-backed Store Tests
;; =============================================================================
(deftest remote-file-store-persists-test
  #?(:clj
     (testing "File-backed store persists across client reconnections"
       (let [port (random-port)
             path (str "/tmp/dao-jing-remote-persist-test-"
                       (rand-int 1000000))
             backing-store (jing.file/create-kv-file path)]
         (try
           ;; Start server with file store
           (let [server (start-kv! backing-store port)]
             (Thread/sleep 100)
             ;; Client 1 connects and writes
             (let [client1 (remote/connect-kv! (str "ws://localhost:" port))]
               (jing/cas! client1 :persistent jing/absent {:data "survives"})
               (jing/close! client1))
             ;; Stop server
             (rpc-ws/stop! server)
             (jing/close! backing-store))
           ;; Reopen the same file
           (let [backing-store2 (jing.file/create-kv-file path)
                 server2 (start-kv! backing-store2 port)]
             (Thread/sleep 100)
             ;; Client 2 connects and reads
             (let [client2 (remote/connect-kv! (str "ws://localhost:" port))]
               (is (= "survives" (:data (jing/get client2 :persistent nil)))
                   "Data should persist across server restarts")
               (jing/close! client2))
             (rpc-ws/stop! server2)
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
  #?(:clj
     (testing "Client throws on connection timeout when server is unreachable"
       (is (thrown? Exception (remote/connect-kv! "ws://localhost:59999"))))))


;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest remote-empty-value-map-test
  #?(:clj (with-remote-server :memory
            (fn []
              (let [url (str "ws://localhost:"
                             (:port *server*))
                    client (remote/connect-kv! url)]
                (try
                  ;; Empty value map
                  (jing/cas! client :empty jing/absent {})
                  (is (= {} (jing/get client :empty nil))
                      "Empty map should round-trip")
                  (finally (jing/close! client))))))))


(deftest remote-large-value-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote/connect-kv! url)
                    large-data (vec (range 1000))]
                (try (jing/cas! client :large jing/absent {:data large-data})
                     (is (= large-data (:data (jing/get client :large nil)))
                         "Large values should round-trip correctly")
                     (finally (jing/close! client))))))))


(deftest remote-special-characters-in-keys-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote/connect-kv! url)]
                (try
                  ;; Various key types that dao.jing supports
                  (jing/cas! client :keyword-key jing/absent {:type "keyword"})
                  (jing/cas! client "string-key" jing/absent {:type "string"})
                  (jing/cas! client 42 jing/absent {:type "number"})
                  (is (= "keyword" (:type (jing/get client :keyword-key nil))))
                  (is (= "string" (:type (jing/get client "string-key" nil))))
                  (is (= "number" (:type (jing/get client 42 nil))))
                  (finally (jing/close! client))))))))


(deftest client-close-is-idempotent-test
  #?(:clj (with-remote-server
            :memory
            (fn []
              (let [url (str "ws://localhost:" (:port *server*))
                    client (remote/connect-kv! url)]
                ;; Multiple closes should not error
                (jing/close! client)
                (jing/close! client)
                (is (true? true) "Multiple close! calls should not throw"))))))
