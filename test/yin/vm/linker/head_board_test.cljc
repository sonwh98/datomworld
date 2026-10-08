(ns yin.vm.linker.head-board-test
  "The head board over `dao.stream.remote-channel`, on loopback
   (docs/design/yin.vm.linker.dht.head.md 5.1, section 6, slices H2 and
   S3a-2).

   The portable cases run over `dao.stream.loopback-net`, an in-process
   stand-in for the host listener and connect seams.  One JVM case
   crosses a real loopback socket through `yin.repl.host`."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.dht.mesh :as mesh]
            [dao.jing.mem :as mem]
            [dao.space.dht :as dht]
            [dao.space.index :as index]
            [dao.space.transactor :as transactor]
            [dao.stream :as stream]
            [dao.stream.loopback-net :refer [blackhole! connect-on listen-on loopback-net pump! unbind-on unlisten!]]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.remote :as remote]
            [dao.stream.remote-channel :as rc]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.transit :as transit]
            [dao.stream.ws-project :as ws-project]
            [yin.vm.linker.dht :as ld]
            [yin.vm.linker.head :as head]
            [yin.vm.linker.head.board :as head.board]
            [yin.vm.linker.sign :as sign]
            #?@(:cljd []
                :clj [[yin.repl.host :as host]]
                :default [])))


;; =============================================================================
;; Indexes at a chosen sequence (as in head-follow-test)
;; =============================================================================

(def p1 (sign/generate))


(defn- principal
  [key]
  (sign/principal (:public key)))


(defn- memory-log
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type memory-log/transport-type})))


(defn- index!
  [store history]
  (let [intake (memory-log)
        tx (transactor/create! {:local-stream (memory-log)
                                :intake-pool [intake]})]
    (doseq [[i entities] (map-indexed vector history)]
      (transactor/transact! tx (vec (map-indexed
                                      (fn [j e]
                                        (assoc e :db/id (+ datom/first-user-id
                                                           (* 16 i) j)))
                                      entities))))
    (let [{:keys [manifest-address]} (transactor/publish! tx {})]
      (loop [pool (jing/observer-state
                    [{:stream intake
                      :cursor (:dao.stream/cursor
                                (stream/cursor intake :dao.stream/oldest))}])]
        (let [{:keys [signal state]} (jing/observe-step! store pool)]
          (when (= :dao.stream/ok signal) (recur state))))
      manifest-address)))


(defn- assertion
  [key n address s]
  (let [env {:yin.module/op :assert
             :yin.module/name n
             :yin.module/manifest address
             :yin.module/asserted-by (principal key)
             :yin.module/seq s}]
    {:yin.module/envelope env
     :yin.module/proof (sign/sign-envelope (:seed key) env)}))


(defn- retraction
  [key of s]
  (let [env {:yin.module/op :retract
             :yin.module/of (jing/segment-key (:yin.module/envelope of))
             :yin.module/asserted-by (principal key)
             :yin.module/seq s}]
    {:yin.module/envelope env
     :yin.module/proof (sign/sign-envelope (:seed key) env)}))


(def v1 (jing/segment-key "alib, first"))
(def v2 (jing/segment-key "alib, second"))


(defn- alib-history
  [key n]
  (into [[(assertion key 'alib v1 1)]]
        (mapv (fn [i] [{:test/filler i}]) (range n))))


(defn- moved-history
  [key history address]
  (conj history [(retraction key (assertion key 'alib v1 1) 2)
                 (assertion key 'alib address 3)]))


(defn- node-at
  [peers opts bind!]
  (dht/join (merge {:local (mem/create-content-mem)
                    :peers (mapv (fn [p] {:host mesh/host :port p}) peers)
                    :bind! bind!}
                   opts)))


(defn- ring
  [n]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key n})))


(defn- identity-of
  [h]
  (:dao.stream/identity (stream/descriptor h)))


(defn- events-of
  [events kind]
  (filterv #(= kind (:yin.head/event %)) events))


;; =============================================================================
;; A world: publisher A serving its board at ws port 7001, reader B dialing it
;; =============================================================================

(def ^:private ws-port 7001)


(defn- host-of
  [net]
  {:connect! (connect-on net)
   :bind! (listen-on net)
   :unbind! (unbind-on net)})


(defn- serve-board
  [net board port]
  (head.board/serve {:board board
                     :principal (principal p1)
                     :spec {:host "127.0.0.1" :port port}
                     :host (host-of net)}))


(defn- dial-board
  ([net port] (dial-board net port {}))
  ([net port bounds]
   (head.board/dial {:principal (principal p1)
                     :spec {:host "127.0.0.1" :port port}
                     :host (host-of net)
                     :bounds bounds})))


(defn- world
  ([] (world {}))
  ([bounds]
   (let [net (loopback-net)
         dht-net (mesh/mesh)
         board (head/board)
         a (node-at [2] {:publish? true} (mesh/seam dht-net 1))
         b (node-at [1] {} (mesh/seam dht-net 2))
         [f b] (head/follow b {:heads :volatile :poll-ticks 10
                               :repair-ticks 100 :repair-max-ticks 400
                               :follow [(principal p1)]})]
     {:net net :board board :a a :b b :follower f :now 0 :events []
      :server (serve-board net board ws-port)
      :dial (dial-board net ws-port bounds)
      :attached nil})))


(defn- install-confirmed
  [f b events]
  (reduce (fn [[f b more] e]
            (if (= :confirmed (:yin.head/event e))
              (let [[f b es] (head/install f b (:principal e) (:trace e))]
                [f b (into more es)])
              [f b more]))
          [f b []]
          events))


(defn- tick
  "One cadence turn: the net, the board's endpoint, the dial (whose
   reflection is handed to the follower once, when it attaches), both
   nodes, and the follower, installing every confirmed head."
  [w]
  (pump! (:net w))
  (let [now (:now w)
        server (head.board/serve-step (:server w) now)
        d (head.board/dial-step (:dial w) now)
        _ (pump! (:net w))
        fresh (when (and (head.board/handle d)
                         (not (identical? (head.board/handle d) (:attached w))))
                (head.board/handle d))
        f (cond-> (:follower w)
            fresh (head/attach (principal p1) fresh))
        [a _] (dht/step (:a w) now)
        [b _] (dht/step (:b w) now)
        [f b events] (head/step f b now)
        [f b more] (install-confirmed f b events)
        events (into events more)]
    (-> w
        (assoc :server server :dial d :a a :b b :follower f
               :now (+ now 10)
               :attached (or fresh (:attached w)))
        (update :events into events))))


(defn- run
  [w n done?]
  (loop [w w left n]
    (if (or (done? w) (zero? left)) w (recur (tick w) (dec left)))))


(defn- status
  [w]
  (get (head/heads (:follower w)) (principal p1)))


(defn- installed?
  [m]
  (fn [w] (= m (:manifest (status w)))))


(defn- resolved
  [w n]
  (ld/resolve-name (:b w) (ld/authority {:principals [(:public p1)]}) n))


(defn- close!
  [w]
  (dht/close! (:a w))
  (dht/close! (:b w)))


;; =============================================================================
;; H1's first case, over a reflection
;; =============================================================================

(deftest a-first-contact-reader-follows-over-a-reflection
  (let [w (world)
        a-store (dht/local (:a w))
        m0 (index! a-store (alib-history p1 0))
        dep0 (head/deposit! (:board w) p1 m0 (index/read-datoms a-store m0))
        w (run w 300 (installed? m0))]
    (testing "the reader's handle is a reflection of the ring, by its own
              identity"
      (is (= :attached (:status (:dial w))) (pr-str (:outcome (:dial w))))
      (is (not (identical? (:board w) (:attached w)))
          "a reflection, not the ring")
      (is (= (identity-of (:board w)) (identity-of (:attached w)))
          "the reflection reports the ring's own identity")
      (is (= (identity-of (:board w)) (:identity (:server w))))
      (is (not= (head.board/board-name (principal p1))
                (identity-of (:attached w)))
          "never the name"))
    (testing "the first index deposits sequence 0, and it is installed"
      (is (= 0 (get-in dep0 [:trace :yin.head/envelope :yin.head/seq])))
      (is (= m0 (:manifest (status w))) (pr-str (:events w)))
      (is (= 0 (:floor (status w))))
      (is (= v1 (:address (resolved w 'alib)))))
    (let [m1 (index! a-store (moved-history p1 (alib-history p1 0) v2))
          dep1 (head/deposit! (:board w) p1 m1 (index/read-datoms a-store m1))
          w (run w 300 (installed? m1))]
      (testing "A republishes: sequence 1 replaces it"
        (is (= m1 (:manifest (status w))))
        (is (= 1 (:floor (status w))))
        (is (= v2 (:address (resolved w 'alib))))
        (is (= [(:trace dep0) (:trace dep1)]
               (mapv :trace (events-of (:events w) :installed)))))
      (testing "the reader's own HEAD is never written"
        (is (nil? (ld/head (:b w)))))
      (close! w))))


;; =============================================================================
;; Identity on the wire
;; =============================================================================

(defn- wire-maps
  "Every map inside the decoded frames, at any depth."
  [frames]
  (->> frames
       (keep #(:ws/value (transit/decode %)))
       (mapcat #(tree-seq coll? seq %))
       (filter map?)))


(deftest no-wire-value-carries-the-name-as-an-identity
  (let [net (loopback-net)
        frames (atom [])
        recording (fn [connect!]
                    (fn [descriptor client]
                      (let [client' (update client :message!
                                            (fn [f] (fn [p] (swap! frames conj p) (f p))))
                            socket (connect! descriptor client')]
                        (update socket :send!
                                (fn [f] (fn [p] (swap! frames conj p) (f p)))))))
        board (head/board)
        _ (stream/append! board {:a :head})
        server (atom (serve-board net board ws-port))
        d (atom (head.board/dial {:principal (principal p1)
                                  :spec {:host "127.0.0.1" :port ws-port}
                                  :host {:connect! (recording (connect-on net))}}))
        n (head.board/board-name (principal p1))]
    (dotimes [i 20]
      (pump! net)
      (swap! server head.board/serve-step i)
      (swap! d head.board/dial-step i)
      (pump! net))
    (is (= :attached (:status @d)))
    (let [h (head.board/handle @d)]
      (dotimes [_ 10]
        (stream/next h (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))
        (pump! net)
        (swap! server head.board/serve-step 20)
        (swap! d head.board/dial-step 20)
        (pump! net)))
    (let [maps (wire-maps @frames)]
      (is (some #(= n (:dao.stream.remote/name %)) maps)
          "the name crossed, as lookup data")
      (is (some #(= (identity-of board) (:dao.stream/identity %)) maps)
          "the ring's own identity crossed")
      (is (not-any? #(= n (:dao.stream/identity %)) maps)
          "never the name under :dao.stream/identity"))
    (is (= (identity-of board) (:identity @d)))))


;; =============================================================================
;; Restart
;; =============================================================================

(deftest a-restarted-publisher-is-a-lost-source-then-a-new-identity
  (let [w (world)
        a-store (dht/local (:a w))
        m0 (index! a-store (alib-history p1 0))
        datoms (index/read-datoms a-store m0)
        _ (head/deposit! (:board w) p1 m0 datoms)
        w (run w 300 (installed? m0))
        old-identity (identity-of (:attached w))
        old-cursor (:dao.stream/cursor
                     (stream/cursor (:board w) :dao.stream/oldest))]
    (is (= m0 (:manifest (status w))))
    (testing "the endpoint stops: the reader reports :source-lost"
      (unlisten! (:net w) ws-port)
      (let [w (run (assoc w :events []) 50
                   #(seq (events-of (:events %) :source-lost)))]
        (is (= 1 (count (events-of (:events w) :source-lost)))
            (pr-str (:events w)))
        (testing "the publisher starts again with a new ring holding the
                  same head; the reader dials and resolves again"
          (let [board2 (head/board)
                _ (head/deposit! board2 p1 m0 datoms)
                w (assoc w
                         :board board2
                         :server (serve-board (:net w) board2 ws-port)
                         :dial (dial-board (:net w) ws-port)
                         :events [])
                w (run w 100 #(= :duplicate
                                 (get-in (status %) [:observed :verdict])))]
            (is (= :attached (:status (:dial w))))
            (is (= (identity-of board2) (identity-of (:attached w))))
            (is (not= old-identity (identity-of (:attached w)))
                "a different identity")
            (is (= :duplicate (get-in (status w) [:observed :verdict]))
                "the head, judged a duplicate")
            (is (= m0 (:manifest (status w))))
            (testing "a cursor kept from the old ring is cursor-mismatch
                      on the new one"
              (let [h (:attached w)
                    seen (atom nil)
                    w (run w 20 (fn [_]
                                  (let [r (stream/next h old-cursor)]
                                    (when-not (= :dao.stream/blocked
                                                 (:dao.stream/outcome r))
                                      (reset! seen r)))))]
                (is (= :dao.stream/cursor-mismatch
                       (:dao.stream/outcome @seen)))
                (close! w)))))))))


;; =============================================================================
;; A shared key, and what the table holds
;; =============================================================================

(defn- drive!
  [net servers dials n]
  (dotimes [i n]
    (pump! net)
    (doseq [s servers] (swap! s head.board/serve-step i))
    (doseq [d dials] (swap! d head.board/dial-step i))
    (pump! net)))


(defn- settle
  [net servers dials op]
  (loop [left 50]
    (let [r (op)]
      (if (and (pos? left)
               (contains? #{:dao.stream/blocked :dao.stream/transport-error}
                          (:dao.stream/outcome r))
               (not (contains? r :dao.stream.remote/reason)))
        (do (drive! net servers dials 1) (recur (dec left)))
        r))))


(defn- read-first
  [net servers dials h]
  (let [c (settle net servers dials #(stream/cursor h :dao.stream/oldest))]
    (settle net servers dials #(stream/next h (:dao.stream/cursor c)))))


(deftest two-nodes-with-one-key-serve-two-identities-for-one-name
  (let [net (loopback-net)
        b1 (head/board)
        b2 (head/board)
        _ (stream/append! b1 :first-node)
        _ (stream/append! b2 :second-node)
        s1 (atom (serve-board net b1 7001))
        s2 (atom (serve-board net b2 7002))
        d1 (atom (dial-board net 7001))
        d2 (atom (dial-board net 7002))
        all [[s1 s2] [d1 d2]]]
    (drive! net [s1 s2] [d1 d2] 20)
    (is (= (:name @s1) (:name @s2)) "one name")
    (is (= [(identity-of b1) (identity-of b2)] [(:identity @d1) (:identity @d2)])
        "two identities, each its own ring's")
    (is (not= (:identity @d1) (:identity @d2)))
    (is (= :first-node
           (:dao.stream/value (apply read-first net (conj all (head.board/handle @d1))))))
    (is (= :second-node
           (:dao.stream/value (apply read-first net (conj all (head.board/handle @d2))))))))


(deftest the-table-holds-the-board-and-nothing-else
  (let [net (loopback-net)
        board (head/board)
        _ (stream/append! board :head)
        server (atom (serve-board net board ws-port))
        acceptor @(:acceptor @server)
        id (identity-of board)
        n (head.board/board-name (principal p1))]
    (is (= {id {:handle board :surface #{:reader}}} (:table acceptor)))
    (is (= {n id} (:names acceptor)))
    (testing "over the served table and name map: a writer op is
              no-surface, another identity not-found"
      (let [in (ring 16)
            out (ring 16)]
        (doseq [req [{:dao.stream/identity id
                      :dao.stream.remote/op :dao.stream/append!
                      :dao.stream.remote/args [:forged]
                      :dao.stream.remote/id 1}
                     {:dao.stream/identity "another"
                      :dao.stream.remote/op :dao.stream/cursor
                      :dao.stream.remote/args [:dao.stream/oldest]
                      :dao.stream.remote/id 2}]]
          (stream/append! in req))
        (remote/mirror-step (:table acceptor) (:names acceptor) in
                            (:dao.stream/cursor (stream/cursor in :dao.stream/oldest))
                            out)
        (let [c (:dao.stream/cursor (stream/cursor out :dao.stream/oldest))
              a1 (stream/next out c)
              a2 (stream/next out (:dao.stream/cursor a1))]
          (is (= :dao.stream.remote/no-surface
                 (:dao.stream.remote/error (:dao.stream/value a1))))
          (is (= :dao.stream.remote/not-found
                 (:dao.stream.remote/error (:dao.stream/value a2)))))))
    (testing "and through a reflection over the endpoint"
      (let [d (atom (dial-board net ws-port))
            _ (drive! net [server] [d] 20)
            h (head.board/handle @d)
            other (:dao.stream/handle
                    (ws-project/dial-reflect!
                      (:dial @d)
                      {:dao.stream/type :dao.stream/remote
                       :dao.stream/identity "another"
                       :dao.stream/channel (:descriptor @d)}))]
        (is (= :head (:dao.stream/value (read-first net [server] [d] h))))
        (is (= :dao.stream.remote/no-surface
               (:dao.stream.remote/reason (stream/append! h :forged))))
        (is (= :dao.stream.remote/not-found
               (:dao.stream.remote/reason
                 (settle net [server] [d]
                         #(stream/cursor other :dao.stream/oldest)))))
        (is (= [:head] (let [c (:dao.stream/cursor
                                 (stream/cursor board :dao.stream/oldest))]
                         [(:dao.stream/value (stream/next board c))]))
            "the board is unchanged")))))


(deftest an-unmapped-name-loses-the-dial
  (let [net (loopback-net)
        board (head/board)
        server (atom (serve-board net board ws-port))
        d (atom (head.board/dial {:principal "ed25519:another"
                                  :spec {:host "127.0.0.1" :port ws-port}
                                  :host (host-of net)}))]
    (drive! net [server] [d] 20)
    (is (= :lost (:status @d)))
    (is (= :dao.stream.remote/not-found
           (:dao.stream.remote/reason (:outcome @d))))
    (is (nil? (head.board/handle @d)))))


;; =============================================================================
;; Loopback only, and a port in use
;; =============================================================================

(deftest a-bind-host-that-is-not-a-loopback-literal-composes-no-endpoint
  (let [calls (atom 0)
        host {:bind! (fn [_] (swap! calls inc) {:dao.stream/outcome :dao.stream/ok})}]
    (doseq [h ["0.0.0.0" "192.168.1.5" "10.0.0.1" "128.0.0.1" "127.0.0"
               "127.0.0.256" "127.0.0.1.1" "127.0.0.1." "127.0.0.1.."
               ".127.0.0.1" "localhost" "::" "[::]" "fe80::1"
               "::ffff:127.0.0.1" "" nil]]
      (testing (pr-str h)
        (is (= {:status :refused :reason :yin.head/not-loopback
                :spec {:host h :port ws-port}}
               (head.board/serve {:board (head/board) :principal (principal p1)
                                  :spec {:host h :port ws-port}
                                  :host host})))))
    (is (= 0 @calls) "nothing listened")
    (doseq [h ["127.0.0.1" "127.1.2.3" "::1" "[::1]"]]
      (testing (pr-str h)
        (is (= :starting
               (:status (head.board/serve {:board (head/board)
                                           :principal (principal p1)
                                           :spec {:host h :port ws-port}
                                           :host host}))))))
    (is (= 4 @calls))))


(deftest a-bind-port-that-is-not-positive-composes-no-endpoint
  (let [calls (atom 0)
        host {:bind! (fn [_] (swap! calls inc) {:dao.stream/outcome :dao.stream/ok})}]
    (doseq [p [nil -1 "7001"]]
      (testing (pr-str p)
        (let [r (head.board/serve {:board (head/board) :principal (principal p1)
                                   :spec {:host "127.0.0.1" :port p}
                                   :host host})]
          (is (= [:refused ::rc/no-port] [(:status r) (:reason r)])))))
    (is (= 0 @calls) "nothing listened")
    (testing "port 0 is an ephemeral bind, finalized by the channel composition"
      (is (= :starting
             (:status (head.board/serve {:board (head/board) :principal (principal p1)
                                         :spec {:host "127.0.0.1" :port 0}
                                         :host host}))))
      (is (= 1 @calls)))))


(deftest a-host-without-a-listener-or-a-dialer-is-a-refusal
  (is (= [:refused ::rc/no-transport]
         ((juxt :status :reason)
          (head.board/serve {:board (head/board) :principal (principal p1)
                             :spec {:host "127.0.0.1" :port ws-port}
                             :host {}}))))
  (is (= [:refused ::rc/no-transport]
         ((juxt :status :reason)
          (head.board/dial {:principal (principal p1)
                            :spec {:host "127.0.0.1" :port ws-port}
                            :host {}})))))


(deftest the-port-is-named-from-the-start
  (let [net (loopback-net)
        board (head/board)
        server (atom (serve-board net board 7005))
        expected (rc/descriptor-of {:host "127.0.0.1" :port 7005
                                    :path head.board/path})]
    (is (= "ws://127.0.0.1:7005/head" (:dao.stream/identity expected)))
    (is (= expected (:descriptor @server)) "before the bind is reported")
    (let [d (atom (dial-board net 7005))]
      (drive! net [server] [d] 20)
      (is (= :attached (:status @d)))
      (is (= expected (:descriptor @server)) "after it")
      (let [sessions (rc/sessions @server)]
        (is (= 1 (count sessions)))
        (doseq [[_ s] sessions]
          (is (= expected
                 (:dao.stream/descriptor (stream/descriptor (:handle s))))
              "every accepted handle reports the port and its identity"))))))


(deftest a-port-in-use-is-a-refusal-and-the-node-keeps-running
  (let [net (loopback-net)
        _ (serve-board net (head/board) ws-port)
        refused (serve-board net (head/board) ws-port)
        a (node-at [] {:publish? true} (mesh/seam (mesh/mesh) 1))]
    (is (= :refused (:status refused)))
    (is (= ::rc/bind-failed (:reason refused)))
    (is (= refused (head.board/serve-step refused 0))
        "a refused server steps as itself")
    (testing "a bind that fails after it started is the same refusal"
      (let [late (head.board/serve
                   {:board (head/board) :principal (principal p1)
                    :spec {:host "127.0.0.1" :port 7009}
                    :host {:bind! (fn [{:keys [deposit!]}]
                                    (deposit! :bind-failed {:code :in-use})
                                    {:dao.stream/outcome :dao.stream/ok})}})]
        (is (= :starting (:status late)))
        (is (= [:refused ::rc/bind-failed {:code :in-use}]
               ((juxt :status :reason :detail)
                (head.board/serve-step late 0))))))
    (testing "the node keeps running without a board"
      (let [[a' _] (dht/step a 0)]
        (is (some? a')))
      (dht/close! a))))


;; =============================================================================
;; Explicit stop and stream-side liveness (S3a-2)
;; =============================================================================

(deftest a-stopped-board-is-a-lost-source-then-reattachable
  (let [w (world)
        a-store (dht/local (:a w))
        m0 (index! a-store (alib-history p1 0))
        _ (head/deposit! (:board w) p1 m0 (index/read-datoms a-store m0))
        w (run w 300 (installed? m0))
        id (identity-of (:attached w))]
    (is (= m0 (:manifest (status w))))
    (let [w (update w :server head.board/stop!)]
      (testing "stop! performs no I/O"
        (is (= :stopping (:status (:server w))))
        (is (= 1 (count (rc/sessions (:server w))))))
      (let [w (run (assoc w :events []) 50
                   #(seq (events-of (:events %) :source-lost)))
            w (run w 5 (constantly false))]
        (testing "serve-step completes the stop; the reader reports
                  :source-lost once and keeps its installed head"
          (is (= :stopped (:status (:server w))))
          (is (= :confirmed (get-in w [:server :stop :outcome])))
          (is (= 1 (count (events-of (:events w) :source-lost)))
              (pr-str (:events w)))
          (is (= :lost (:status (:dial w))))
          (is (= m0 (:manifest (status w)))))
        (testing "a new serve on the same port and a fresh dial reattach
                  and read the same identity"
          (let [w (assoc w
                         :server (serve-board (:net w) (:board w) ws-port)
                         :dial (dial-board (:net w) ws-port)
                         :events [])
                w (run w 100 #(= :duplicate
                                 (get-in (status %) [:observed :verdict])))]
            (is (= :attached (:status (:dial w))))
            (is (= id (identity-of (:attached w))))
            (is (= :duplicate (get-in (status w) [:observed :verdict])))
            (close! w)))))))


(deftest a-blackholed-board-is-source-lost-after-give-up-after
  (let [w (world {:dao.stream.remote/give-up-after 150})
        a-store (dht/local (:a w))
        m0 (index! a-store (alib-history p1 0))
        _ (head/deposit! (:board w) p1 m0 (index/read-datoms a-store m0))
        w (run w 300 (installed? m0))
        answered (:answered (status w))]
    (is (= m0 (:manifest (status w))))
    (is (some? answered))
    (let [w (run (assoc w :events []) 40 (constantly false))]
      (testing "an idle healthy board: :polled advances, :answered does not,
                and nothing is lost"
        (is (= :attached (:status (:dial w))))
        (is (empty? (events-of (:events w) :source-lost)))
        (is (< answered (:polled (status w))))
        (is (= answered (:answered (status w)))))
      (testing "blackholed toward the reader: lost once give-up-after passes,
                never before"
        (blackhole! (:net w) :client)
        (let [since (:now w)
              w (run (assoc w :events []) 100
                     #(seq (events-of (:events %) :source-lost)))
              lost (first (events-of (:events w) :source-lost))]
          (is (some? lost) (pr-str (:events w)))
          (is (= :dao.stream/transport-error (:outcome lost)))
          (is (<= (+ since 150) (:now w)))
          (is (< (:now w) (+ since 150 50)) "within a few ticks of it")
          (is (= :lost (:status (:dial w))))
          (is (= :dao.stream.remote/channel-gone
                 (get-in w [:dial :outcome :dao.stream.remote/reason])))
          (is (= answered (:answered (status w)))
              "blocked polls answered nothing")
          (is (= m0 (:manifest (status w))) "the installed head is kept")
          (close! w))))))


;; =============================================================================
;; A real loopback socket (JVM)
;; =============================================================================

#?(:cljd nil
   :clj
   (deftest the-board-crosses-a-real-loopback-socket
     (let [seam (host/websocket)
           board (head/board)
           _ (stream/append! board {:yin.head/test :real})
           free-port (with-open [s (java.net.ServerSocket. 0)]
                       (.getLocalPort s))
           spec {:host "127.0.0.1" :port free-port}
           server (atom (head.board/serve {:board board :principal (principal p1)
                                           :spec spec :host seam}))
           deadline (+ (System/currentTimeMillis) 5000)
           step! (fn []
                   (swap! server head.board/serve-step
                          (System/currentTimeMillis)))]
       (try
         (loop [] (step!)
               (when (and (not= :serving (:status @server))
                          (< (System/currentTimeMillis) deadline))
                 (Thread/sleep 10) (recur)))
         (is (= :serving (:status @server)))
         (is (= (rc/descriptor-of (assoc spec :path head.board/path))
                (:descriptor @server)))
         (let [d (atom (head.board/dial {:principal (principal p1)
                                         :spec spec :host seam}))
               dial! #(swap! d head.board/dial-step (System/currentTimeMillis))]
           (loop [] (step!) (dial!)
                 (when (and (= :resolving (:status @d))
                            (< (System/currentTimeMillis) deadline))
                   (Thread/sleep 10) (recur)))
           (is (= :attached (:status @d)) (pr-str (:outcome @d)))
           (is (= (identity-of board) (identity-of (head.board/handle @d))))
           (let [h (head.board/handle @d)
                 settle (fn [op]
                          (loop [r (op)]
                            (if (and (contains? #{:dao.stream/blocked
                                                  :dao.stream/transport-error}
                                                (:dao.stream/outcome r))
                                     (< (System/currentTimeMillis) deadline))
                              (do (step!) (dial!)
                                  (Thread/sleep 10) (recur (op)))
                              r)))
                 c (settle #(stream/cursor h :dao.stream/oldest))]
             (is (= {:yin.head/test :real}
                    (:dao.stream/value
                      (settle #(stream/next h (:dao.stream/cursor c)))))))
           (testing "a TCP port already in use is a refusal as data"
             (let [again (head.board/serve {:board (head/board)
                                            :principal (principal p1)
                                            :spec spec :host seam})]
               (is (= :refused (:status again)))
               (is (= ::rc/bind-failed (:reason again)))))
           (testing "stop: confirmed by the host's close completion within
                     the stop grace, and the dial is lost"
             (swap! server head.board/stop!)
             (let [since (System/currentTimeMillis)
                   grace (get-in @server [:bounds :stop-grace-ms])]
               (loop [] (step!) (dial!)
                     (when (and (= :stopping (:status @server))
                                (< (System/currentTimeMillis) deadline))
                       (Thread/sleep 10) (recur)))
               (is (= :stopped (:status @server)))
               (is (= :confirmed (get-in @server [:stop :outcome])))
               (is (< (- (System/currentTimeMillis) since) grace)))
             (loop [] (dial!)
                   (when (and (= :attached (:status @d))
                              (< (System/currentTimeMillis) deadline))
                     (Thread/sleep 10) (recur)))
             (is (= :lost (:status @d)))
             (is (= :dao.stream.remote/channel-gone
                    (get-in @d [:outcome :dao.stream.remote/reason]))))
           (head.board/close! @d))
         (finally
           (when (contains? #{:starting :serving} (:status @server))
             ((:unbind! seam) (:listener @server) (fn [& _] nil))))))))
