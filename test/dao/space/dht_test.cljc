(ns dao.space.dht-test
  "The plain Clojure path to code in the DHT (dao.space.dht; DHT epic
   S5): with no yin.repl anywhere, one node publishes a covered index, and
   another joins, loads it from its manifest address over the network,
   and queries it with dao.space.query/q.  yin.repl.dht-test drives the
   same functions through the REPL's host module and gets the same
   answer.  The sockets are dao.stream.datagram host seams over the
   dao.jing.dht test mesh; time is the reading each step is handed.

   Linker-over-DHT slice L1 (docs/design/yin.vm.linker.dht.md 4.3, 5.5):
   the staged load over any walk with reasons as data, the bounded
   replicate backlog, the publication ledger and its result, automatic
   repair, `retry!` and `cancel!`."
  (:require [dao.test-slow :as slow] #?@(:cljd [["dart:io" :as dart-io]])
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
   store (the transactor's publish, its intake drained into the store)
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
  "Step every node at readings 0, 10, ... until `done?` holds over the
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


(defn- events-of
  [events kind manifest]
  (filterv #(and (= kind (::dht/event %)) (= manifest (:manifest %))) events))


(defn- node-at
  [net port peers opts]
  (dht/join (merge {:local (mem/create-content-mem)
                    :peers (mapv (fn [p] {:host mesh/host :port p}) peers)
                    :bind! (mesh/seam net port)}
                   opts)))


(defn- refusal-of
  "The error `thunk` throws, or nil (see yin.repl.store-test)."
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         e)))


(defn- refused-code
  [thunk]
  (::dht/refused (ex-data (refusal-of thunk))))


;; =============================================================================
;; A world: one publishing node, acknowledging peers, and a store refusal
;; =============================================================================

(defn- peer!
  "A plain DHT peer on the mesh at `port`, publishing, with room for
   every test's writes."
  [net port]
  (let [c (mesh/join! net port {::jing.dht/publish? true
                                ::jing.dht/max-inbound-bytes (* 256 1024
                                                                1024)})]
    {:c c :s (jing.dht/state c)}))


(defn- refusing-bind
  "The mesh seam at `port`, whose socket refuses every `:store` datagram
   for an address `@refuse?` holds: the store is never handed to the
   socket, so the DHT counts no peer for it and gives up at its
   acknowledgement deadline (dao.jing.dht.md 4.3)."
  [net port refuse?]
  (let [bind! (mesh/seam net port)]
    (fn [opts]
      (let [seam (bind! opts)]
        (assoc seam
               :send! (fn [host to-port bs]
                        (let [m (jing.dht/decode-message bs)]
                          (if (and (= :store (:op m)) (@refuse? (:address m)))
                            {:dao.stream/outcome :dao.stream/transport-error}
                            ((:send! seam) host to-port bs)))))))))


(defn- world
  "A publishing node at port 1 with peers at `peer-ports`, its stores
   refused for every address `(@refuse a)` holds."
  ([peer-ports opts] (world peer-ports opts (mem/create-content-mem)))
  ([peer-ports opts local]
   (let [net (mesh/mesh)
         refuse (atom (constantly false))]
     {:net net
      :refuse refuse
      :node (dht/join (merge {:local local
                              :publish? true
                              :peers (mapv (fn [p] {:host mesh/host :port p})
                                           peer-ports)
                              :bind! (refusing-bind net 1 refuse)}
                             opts))
      :peers (into (sorted-map) (map (fn [p] [p (peer! net p)])) peer-ports)
      :now 0
      :events []
      :last []
      :busy-facts 0
      :facts-cursor nil})))


(defn- new-facts
  "The DHT facts the node appended since the world last read them."
  [w]
  (let
    [facts (get-in w [:node :composition :facts])]
    (loop
      [cursor (or (:facts-cursor w)
                  (:dao.stream/cursor (stream/cursor facts
                                                     :dao.stream/oldest)))
       acc []]
      (let
        [r (stream/next facts cursor)]
        (case
          (:dao.stream/outcome r)
          :dao.stream/ok (recur
                           (:dao.stream/cursor r) (conj
                                                    acc
                                                    (:dao.stream/value r)))
          :dao.stream/gap (recur (:dao.stream/cursor r) acc)
          [acc cursor])))))


(defn- step-world
  "Step the node at the world's reading, then every peer, and advance the
   reading by `dt`."
  [{:keys [node peers now] :as w} dt]
  (let [[node events] (dht/step node now)
        peers (into (sorted-map)
                    (map (fn [[p {:keys [c s]}]]
                           (mesh/tick! c now)
                           [p {:c c :s (jing.dht/step s 256)}]))
                    peers)
        w (assoc w :node node :peers peers :now (+ now dt))
        [fs cursor] (new-facts w)]
    (-> w
        (assoc :facts-cursor cursor :last events)
        (update :events into events)
        (update :busy-facts + (count (filter #(= ::jing.dht/busy
                                                 (::jing.dht/reason %))
                                             fs))))))


(defn- run-world
  "Step `w` by `dt` until `(done? w)` or the reading passes `limit`,
   calling `(check w)` after every step."
  ([w dt limit done?] (run-world w dt limit done? (fn [_] nil)))
  ([w dt limit done? check]
   (loop [w w]
     (if (or (done? w) (> (:now w) limit))
       w
       (let [w (step-world w dt)]
         (check w)
         (recur w))))))


(defn- round!
  "Put `n` distinct rows of round `r` and a manifest through the node's
   store, then announce the manifest: one publication of n + 1 blobs."
  [node r n]
  (let [h (dht/store node)
        rows (mapv #(jing/materialize! h {:round r :row %}) (range n))
        m (jing/materialize! h {:round r :rows n})]
    (dht/announce! node m)
    {:manifest m :rows rows}))


(defn- replicates
  "How many replicate requests the node issued for `address`."
  [node address]
  (count (filter #(= {::jing.dht/replicate address} %)
                 (mesh/values (get-in node [:composition :requests])))))


(defn- reports
  [w]
  (filterv #(#{:published :republished} (::dht/event %)) (:events w)))


(defn- invariant-holds?
  "Section 5.5.1: in every :published and :republished event, :sent plus
   the count of :failed is :blobs, and :blobs is the same in every event
   of one publication."
  [events]
  (and (every? #(= (:blobs %) (+ (:sent %) (count (:failed %))))
               (filter #(#{:published :republished} (::dht/event %)) events))
       (every? (fn [[_ es]] (apply = (map :blobs es)))
               (group-by :manifest
                         (filter #(#{:published :republished} (::dht/event %))
                                 events)))))


(defn- close-world!
  [w]
  (dht/close! (:node w))
  (is (invariant-holds? (:events w)) (pr-str (reports w))))


;; =============================================================================
;; S5: the plain path, now with reasons and results as data
;; =============================================================================

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
          _ (is (= {:status :loading :kind ::dht/index :fetched 0 :fetching nil}
                   (dht/load-status reader manifest)))
          [[publisher reader] events]
          (run-nodes [publisher reader] 20000 #(event % :loaded manifest))
          loaded (event events :loaded manifest)]
      (is (some? loaded) (pr-str events))
      (is (= ::dht/index (:kind loaded)))
      (is (pos? (:fetched loaded)) "the blobs came over the network")
      (is (= 4 (:datoms loaded)) "two entities, two facts each")
      (is (= [:fetched :kind :status :value]
             (sort (keys (dht/load-status reader manifest)))))
      (is (= 4 (count (:value (dht/load-status reader manifest)))))
      (is (= #{["alpha" 1] ["beta" 2]} (set (dht/q reader manifest
                                                   names-query))))
      (is (= #{["beta"]}
             (set (dht/q reader manifest
                         '[:find ?n :in $ ?a :where [?e :code/arity ?a]
                           [?e :code/name ?n]]
                         2)))
          "inputs follow the query")
      (is (some? (jing/get (dht/local reader) manifest nil))
          "the loaded blobs are the reader's own now")
      (is (= 1 (count (filter #(#{:loaded :load-failed} (::dht/event %))
                              events)))
          "exactly one terminal event")
      (dht/close! publisher)
      (dht/close! reader))))


(deftest a-publication-reports-its-acknowledgement-as-an-event
  (let [w (world [91 92] {})
        manifest (publish-datoms! (:node w) facts)
        w (run-world w 10 5000 #(event (:events %) :published manifest))
        published (event (:events w) :published manifest)]
    (is (= :acknowledged (:result published)) (pr-str published))
    (is (= (:blobs published) (:sent published)))
    (is (= [] (:failed published)))
    (is (false? (:repairing? published)))
    (is (= 2 (:peers published)))
    (is (not (contains? published :acknowledged?)) "removed, no compatibility")
    (close-world! w)))


(deftest a-solo-node-opens-no-socket-and-reports-why-nothing-was-sent
  (let [net (mesh/mesh)
        binds (atom 0)
        node (dht/join {:local (mem/create-content-mem)
                        :publish? true
                        :bind! (mesh/seam net 99 binds)})
        manifest (publish-datoms! node facts)
        [node events] (dht/step node 0)
        published (event events :published manifest)]
    (is (zero? @binds))
    (is (nil? (get-in node [:composition ::jing.dht/secret])))
    (testing "the step that reads the puts reports them, every blob /solo"
      (is (= {:result :unacknowledged :sent 0 :repairing? false
              :ended ::dht/terminal}
             (select-keys published [:result :sent :repairing? :ended])))
      (is (= (:blobs published) (count (:failed published))))
      (is (every? #(= ::jing.dht/solo (:reason %)) (:failed published))))
    (testing "and holds no backlog"
      (is (= 0 (:fresh (dht/backlog node))))
      (is (= 0 (:outstanding (dht/backlog node))))
      (is (= 0 (:live (dht/backlog node))))
      (is (false? (dht/busy? node))))
    (is (invariant-holds? events))
    (dht/close! node)))


(deftest a-non-publishing-node-holds-no-backlog-and-reports-unpublished
  (let [w (world [93] {:publish? false})
        w (step-world w 10)
        node (:node w)
        manifest (publish-datoms! node facts)
        [node events] (dht/step node 10)
        published (event events :published manifest)]
    (is (some? published) "reported in the step that reads the puts")
    (is (every? #(= ::jing.dht/unpublished (:reason %)) (:failed published)))
    (is (= ::dht/terminal (:ended published)))
    (is (= 0 (:fresh (dht/backlog node))))
    (is (= 0 (replicates node manifest)) "nothing was asked of the DHT")
    (dht/close! node)))


(deftest a-manifest-no-peer-holds-fails-its-load
  (let [net (mesh/mesh)
        other (node-at net 71 [70] {:publish? true})
        m (jing/segment-key "nobody published this")
        reader (dht/load-index (node-at net 70 [71] {}) m)
        [nodes events] (run-nodes [reader other] 30000
                                  #(event % :load-failed m))]
    (is (= {:status :failed :kind ::dht/index :fetched 0
            :reason {::dht/failure :miss :address m :cause
                     ::jing.dht/exhausted}}
           (dht/load-status (first nodes) m)))
    (is (= {::dht/event :load-failed :manifest m :kind ::dht/index
            :reason {::dht/failure :miss :address m :cause
                     ::jing.dht/exhausted}}
           (event events :load-failed m)))
    (run! dht/close! nodes)))


(deftest join-refuses-options-it-cannot-honour
  (doseq [opts [{}
                {:local (mem/create-content-mem) :peers [{:host "localhost"
                                                          :port 1}]
                 :bind! (fn [_])}
                {:local (mem/create-content-mem) :bind-host "::zz"}
                {:local (mem/create-content-mem) :publish? :yes}
                {:local (mem/create-content-mem) :max-inbound-bytes -1}
                {:local (mem/create-content-mem) :max-backlog 0}
                {:local (mem/create-content-mem) :repair-slots 64}
                {:local (mem/create-content-mem) :repair-ticks -1}]]
    (is (thrown? #?(:cljd Object :clj Exception :cljs :default) (dht/join opts))
        (pr-str (dissoc opts :local :bind!)))))


;; =============================================================================
;; 4.3: the staged load over any walk
;; =============================================================================

(defn- gets-asked
  "The `:jing/get` requests the node's load client appended."
  [node]
  (filterv :jing/get (mesh/values (get-in node [:composition :requests]))))


(defn- solo-node
  []
  (dht/join {:local (mem/create-content-mem)}))


(defn- settle
  "Step `node` at readings 0, 10, ... until `address`'s load is terminal."
  [node address]
  (loop [node node
         now 0
         events []]
    (if (or (> now 50000)
            (#{:loaded :failed} (:status (dht/load-status node address))))
      [node events]
      (let [[node more] (dht/step node now)]
        (recur node (+ now 10) (into events more))))))


(deftest a-walk-answering-complete-loads-without-any-fetch
  (let [a (jing/segment-key "complete")
        [node events] (settle (dht/load (solo-node) a
                                        {:kind ::test
                                         :walk (fn [_]
                                                 {::dht/walk :complete
                                                  :value 42})})
                              a)]
    (is (= {:status :loaded :kind ::test :fetched 0 :value 42}
           (dht/load-status node a)))
    (is (= [{::dht/event :loaded :manifest a :kind ::test :fetched 0}] events))
    (is (empty? (gets-asked node)))
    (dht/close! node)))


(deftest loaded-indexes-lists-only-loaded-index-manifests
  (let [node (solo-node)
        m1 (publish-datoms! node facts)
        m2 (publish-datoms! node [{:db/id datom/first-user-id :code/name "gamma"
                                   :code/arity 3}])
        other (jing/segment-key "another kind")
        failed (jing/segment-key "invalid index")
        node (-> node
                 (dht/load-index m1)
                 (dht/load-index m2)
                 (dht/load other {:kind ::test
                                  :walk (fn [_]
                                          {::dht/walk :complete :value
                                           1})})
                 (dht/load failed {:kind dht/index-kind
                                   :walk (fn [_]
                                           {::dht/walk :invalid :address nil
                                            :defect {:code :index-invalid}})}))]
    (is (= [] (dht/loaded-indexes node)) "nothing is loaded while loading")
    (let [[node _] (dht/step node 0)]
      (is (= :loaded (:status (dht/load-status node other))))
      (is (= :failed (:status (dht/load-status node failed))))
      (is (= (vec (sort-by str [m1 m2])) (dht/loaded-indexes node))
          (str
            "loaded index manifests only, sorted: not anot"
            "her kind, not a failure"))
      (is (= [m2] (dht/loaded-indexes (dht/forget node m1)))
          "a forgotten record is no longer listed")
      (dht/close! node))))


(deftest a-walk-answering-invalid-fails-without-any-fetch
  (let [a (jing/segment-key "invalid")
        [node events] (settle (dht/load (solo-node) a
                                        {:kind ::test
                                         :walk (fn [_]
                                                 {::dht/walk :invalid :address a
                                                  :defect {:code :row-defect
                                                           :detail {:x 1}}})})
                              a)]
    (is (= {::dht/failure :invalid :address a
            :defect {:code :row-defect :detail {:x 1}}}
           (:reason (dht/load-status node a))))
    (is (= 1 (count events)))
    (is (empty? (gets-asked node)) "fetching cannot repair invalid content")
    (dht/close! node)))


(deftest a-throwing-walk-and-a-misshapen-walk-fail-with-closed-codes
  (doseq [[walk code] [[(fn [_] (throw (ex-info "the walk broke" {})))
                        ::dht/walk-threw]
                       [(fn [_] {:outcome :done}) ::dht/walk-shape]
                       [(fn [_] nil) ::dht/walk-shape]
                       [(fn [_] {::dht/walk :missing :address "not-an-address"})
                        ::dht/walk-shape]
                       [(fn [_] {::dht/walk :invalid :defect {:code "x"}})
                        ::dht/walk-shape]
                       [(fn [_] {::dht/walk :complete}) ::dht/walk-shape]]]
    (let [a (jing/segment-key (str code))
          [node _] (settle (dht/load (solo-node) a {:kind ::test :walk walk}) a)
          reason (:reason (dht/load-status node a))]
      (is (= :invalid (::dht/failure reason)))
      (is (= code (get-in reason [:defect :code])) (pr-str reason))
      (is (empty? (gets-asked node)))
      (dht/close! node))))


(deftest a-walk-answering-missing-fetches-from-a-peer
  (let [net (mesh/mesh)
        publisher (node-at net 41 [42] {:publish? true})
        blob (jing/materialize! (dht/local publisher) {:held
                                                       "by the publisher"})
        walk (fn [handle]
               (if (= ::absent ((:get-bytes-fn handle) blob ::absent))
                 {::dht/walk :missing :address blob}
                 {::dht/walk :complete :value :got-it}))
        reader (dht/load (node-at net 42 [41] {}) blob {:kind ::test :walk
                                                        walk})
        [[_ reader] events] (run-nodes [publisher reader] 20000
                                       #(event % :loaded blob))]
    (is (= {:status :loaded :kind ::test :fetched 1 :value :got-it}
           (dht/load-status reader blob)))
    (is (= [blob] (mapv :jing/get (gets-asked reader))) "one fetch")
    (is (= 1 (count (filter #(#{:loaded :load-failed} (::dht/event %))
                            events))))
    (dht/close! publisher)
    (dht/close! reader)))


(deftype ^:private FlakyWriter
  [inner outcomes]

  stream/IDaoStreamWriter

  (append!
    [_ value]
    (let [[outcome & more] @outcomes]
      (if outcome
        (do (reset! outcomes more) {:dao.stream/outcome outcome})
        (stream/append! inner value)))))


(defn- with-flaky-client
  "`node` whose load client's request writer answers `outcomes` first,
   then appends to the node's own request ring."
  [node outcomes]
  (update node :client assoc :requests
          (->FlakyWriter (get-in node [:composition :requests]) (atom
                                                                  outcomes))))


(deftest a-busy-client-leaves-the-load-loading-and-asks-again
  (let [a (jing/segment-key "first")
        b (jing/segment-key "second")
        missing (fn [x] (fn [_] {::dht/walk :missing :address x}))
        node (-> (solo-node)
                 (dht/load a {:kind ::test :walk (missing a)})
                 (dht/load b {:kind ::test :walk (missing b)})
                 ;; the first request is not appended at once: the client owes
                 ;; it
                 (with-flaky-client [:dao.stream/full]))
        [node _] (dht/step node 0)
        asked (sort-by str [a b])]
    (testing "one load holds the owed request; the other was answered :busy"
      (is (= #{:loading} (set (map #(:status (dht/load-status node %)) asked))))
      (is (= [(first asked) nil]
             (mapv #(:fetching (dht/load-status node %)) asked))))
    (testing "it asks again on a later step and completes"
      (let [[node events] (settle node (second asked))]
        (is (= :failed (:status (dht/load-status node (second asked)))))
        (is (= ::jing.dht/solo
               (get-in (dht/load-status node (second asked)) [:reason :cause])))
        (is (not-any? #(= :unaskable (get-in % [:reason ::dht/failure]))
                      events))
        (dht/close! node)))))


(deftest a-client-that-cannot-submit-fails-the-load-unaskable
  (let [a (jing/segment-key "unaskable")
        node (-> (solo-node)
                 (dht/load a {:kind ::test
                              :walk (fn [_] {::dht/walk :missing :address a})})
                 (with-flaky-client [:dao.stream/closed]))
        [node events] (dht/step node 0)]
    (is (= {::dht/failure :unaskable :address a :outcome :request-undeliverable}
           (:reason (dht/load-status node a))))
    (is (= 1 (count events)))
    (dht/close! node)))


(deftest every-miss-cause-reaches-the-failed-reason
  (testing "/solo"
    (let [a (jing/segment-key "solo")
          [node _] (settle (dht/load-index (solo-node) a) a)]
      (is (= ::jing.dht/solo (get-in (dht/load-status node a) [:reason
                                                               :cause])))
      (dht/close! node)))
  (testing (str
             "/exhausted and /deadline: a peer that never a"
             "nswers outlives get-ticks")
    (let [net (mesh/mesh)
          _silent (mesh/join! net 2)          ; registered, never stepped
          a (jing/segment-key "deadline")
          node (dht/load-index (node-at net 3 [2] {}) a)
          [node _] (dht/step node 0)
          [node _] (dht/step node 10)
          [node _] (dht/step node 10000)
          [node _] (dht/step node 10010)]
      (is (= {::dht/failure :miss :address a :cause ::jing.dht/deadline}
             (:reason (dht/load-status node a))))
      (dht/close! node)))
  (testing "/busy: one load beyond the DHT's pending gets"
    (let [net (mesh/mesh)
          _silent (mesh/join! net 4)
          as (mapv #(jing/segment-key (str "busy " %)) (range 65))
          node (reduce dht/load-index (node-at net 5 [4] {}) as)
          node (loop [node node now 0]
                 (if (or (> now 200)
                         (some #(= :failed (:status (dht/load-status node %)))
                               as))
                   node
                   (recur (first (dht/step node now)) (+ now 10))))
          failed (keep #(:reason (dht/load-status node %)) as)]
      (is (= [::jing.dht/busy] (distinct (map :cause failed))) (pr-str failed))
      (dht/close! node))))


(defn- missing-walk
  "A load walk that is always missing `a`."
  [a]
  (fn [_] {::dht/walk :missing :address a}))


(defn- load-missing
  [node m a]
  (dht/load node m {:kind ::shared :walk (missing-walk a)}))


(defn- step-until-failed
  "Step `node` from `now` by 10 until every load of `ms` failed or
   `limit` passes: `[node now]`."
  [node ms now limit]
  (loop [node node now now]
    (if (or (> now limit)
            (every? #(= :failed (:status (dht/load-status node %))) ms))
      [node now]
      (recur (first (dht/step node now)) (+ now 10)))))


(deftest loads-sharing-a-missing-blob-each-keep-its-cause
  (let [a (jing/segment-key "the shared blob")
        [m1 m2 m3] (mapv #(jing/segment-key (str "load " %)) [1 2 3])
        reason (fn [node m] (:reason (dht/load-status node m)))]
    (testing "/solo: two loads asking in the same step"
      (let [[node _] (step-until-failed (-> (solo-node) (load-missing m1 a)
                                            (load-missing m2 a))
                                        [m1 m2] 0 1000)]
        (doseq [m [m1 m2]]
          (is (= {::dht/failure :miss :address a :cause ::jing.dht/solo}
                 (reason node m))
              (str m)))
        (dht/close! node)))
    (testing "/exhausted: later loads join the first one's request steps later"
      (let [net (mesh/mesh)
            _silent (mesh/join! net 6)
            node (load-missing (node-at net 7 [6] {}) m1 a)
            [node _] (dht/step node 0)
            [node _] (dht/step node 10)
            node (load-missing node m2 a)
            [node _] (dht/step node 20)
            [node _] (dht/step node 30)
            node (load-missing node m3 a)
            [node _] (step-until-failed node [m1 m2 m3] 40 30000)]
        (doseq [m [m1 m2 m3]]
          (is (= {::dht/failure :miss :address a :cause ::jing.dht/exhausted}
                 (reason node m))
              (str m)))
        (testing "and the cause is not kept once no load waits on it"
          (is (empty? (:misses node))))
        (dht/close! node)))
    (testing (str
               "a load started after the first one failed ask"
               "s again and keeps its own cause")
      (let [[node now] (step-until-failed (load-missing (solo-node) m1 a) [m1]
                                          0 1000)
            [node _] (step-until-failed (load-missing node m2 a) [m2] now 2000)]
        (is (= ::jing.dht/solo (:cause (reason node m1))))
        (is (= ::jing.dht/solo (:cause (reason node m2))))
        (dht/close! node)))))


(defn- lying-store
  "A content store that answers `address` with bytes that are not its."
  [address]
  (let [inner (mem/create-content-mem)]
    (assoc inner :get-bytes-fn (fn [a not-found]
                                 (if (= a address)
                                   (jing/canonical-bytes "not the content")
                                   ((:get-bytes-fn inner) a not-found))))))


(deftest a-peer-serving-bytes-that-do-not-hash-never-loads
  (let [net (mesh/mesh)
        a (jing/segment-key {:the "real content"})
        liar (let [c (mesh/join! net 6 {::jing.dht/publish? true
                                        :local (lying-store a)})]
               {:c c :s (jing.dht/state c)})
        reader (dht/load-index (node-at net 7 [6] {}) a)
        [reader events]
        (loop [reader reader liar liar now 0 events []]
          (if (or (> now 30000) (event events :load-failed a))
            [reader events]
            (let [[reader more] (dht/step reader now)]
              (mesh/tick! (:c liar) now)
              (recur reader (update liar :s jing.dht/step 64) (+ now 10)
                     (into events more)))))]
    (is (nil? (event events :loaded a)))
    (is (= :miss (get-in (event events :load-failed a) [:reason
                                                        ::dht/failure])))
    (is (= ::absent ((:get-bytes-fn (dht/local reader)) a ::absent))
        "the lie never entered :local")
    (dht/close! reader)))


(deftest forget-clears-a-terminal-record-and-is-refused-while-loading
  (let [a (jing/segment-key "forget")
        node (dht/load (solo-node) a {:kind ::test
                                      :walk (fn [_]
                                              {::dht/walk :missing
                                               :address a})})]
    (is (= ::dht/loading (refused-code #(dht/forget node a))))
    (let [[node _] (settle node a)
          _ (is (= :failed (:status (dht/load-status node a))))
          node (dht/forget node a)]
      (is (nil? (dht/load-status node a)))
      (testing "a new load starts over"
        (let [node (dht/load node a {:kind ::test
                                     :walk (fn [_]
                                             {::dht/walk :complete :value
                                              1})})
              [node events] (settle node a)]
          (is (= :loaded (:status (dht/load-status node a))))
          (is (= 1 (count events)))
          (dht/close! node))))))


;; =============================================================================
;; 5.5.2: pacing and the bounded backlog
;; =============================================================================

(defn- bounded-outstanding
  [w]
  (is (<= (:outstanding (dht/backlog (:node w))) 64))
  (is (<= (count (get-in w [:node :dht :writes])) 64)))


(deftest one-round-of-500-puts-is-paced-and-acknowledged
  (let [w (world [11 12] {})
        {:keys [manifest]} (round! (:node w) 1 499)
        w (run-world w 10 60000 #(event (:events %) :published manifest)
                     bounded-outstanding)
        published (event (:events w) :published manifest)]
    (is (= {:result :acknowledged :blobs 500 :sent 500 :failed []}
           (select-keys published [:result :blobs :sent :failed])))
    (is (zero? (:busy-facts w)) "no /busy fact was produced")
    (close-world! w)))


(deftest sustained-rounds-are-each-reported-once-in-order-and-drain
  (let [w (world [13 14] {})
        rounds (mapv (fn [r] (:manifest (round! (:node w) r 499))) (range 8))
        w (step-world w 10)
        _ (is (= 4000 (+ (:fresh (dht/backlog (:node w)))
                         (:outstanding (dht/backlog (:node w)))))
              "all admitted: within :max-backlog")
        w (run-world w 10 400000
                     #(every? (fn [m] (event (:events %) :published m)) rounds)
                     bounded-outstanding)
        published (filterv #(= :published (::dht/event %)) (:events w))]
    (is (= rounds (mapv :manifest published))
        "each once, in announcement order")
    (is (every? #(= :acknowledged (:result %)) published))
    (let [w (run-world w 10 (+ (:now w) 20000)
                       #(not (dht/busy? (:node %))))]
      (is (= 0 (:fresh (dht/backlog (:node w)))))
      (is (= 0 (:outstanding (dht/backlog (:node w)))))
      (is (false? (dht/busy? (:node w))))
      (is (zero? (:busy-facts w)))
      (close-world! w))))


(deftest ^:slow rounds-beyond-the-backlog-bound-fail-at-once-then-repair
  (slow/guard "rounds-beyond-the-backlog-bound-fail-at-once-then-repair"
              (fn []
                (let [w (world [15 16] {:repair-ticks 2000})
                      _rounds (mapv (fn [r] (:manifest (round! (:node w) r 499))) (range 8))
                      w (step-world w 10)
                      late (mapv (fn [r] (:manifest (round! (:node w) r 499))) [8 9])
                      w (run-world w 10 400000
                                   #(every? (fn [m] (event (:events %) :published m)) late))
                      firsts (mapv #(event (:events w) :published %) late)]
                  (doseq [published firsts]
                    (is (not= :acknowledged (:result published)))
                    (is (some #(= ::dht/backlog-full (:reason %)) (:failed published)))
                    (is (true? (:repairing? published))))
                  (let [w (run-world w 50 1000000
                                     #(every? (fn [m]
                                                (some (fn [e] (= :acknowledged (:result e)))
                                                      (events-of (:events %) :republished m)))
                                              late)
                                     bounded-outstanding)]
                    (doseq [m late
                            :let [results (mapv :result (events-of (:events w) :republished
                                                                   m))]]
                      (is (= :acknowledged (last results)) (pr-str results))
                      (is (= results (distinct results)) "each result change reported once")
                      (is (every? #{:partial :acknowledged} results)))
                    (is (zero? (:busy-facts w)))
                    (close-world! w))))))


;; =============================================================================
;; 5.5.3: the result
;; =============================================================================

(deftest a-refused-row-is-partial-and-a-refused-manifest-unacknowledged
  (testing "one non-manifest blob refused"
    (let [w (world [17 18] {})
          {:keys [manifest rows]} (round! (:node w) 1 5)
          refused (nth rows 2)
          _ (reset! (:refuse w) #{refused})
          w (run-world w 50 20000 #(event (:events %) :published manifest))
          published (event (:events w) :published manifest)]
      (is (= :partial (:result published)))
      (is (= [refused] (mapv :address (:failed published))))
      (is (= ::jing.dht/too-few-peers (:reason (first (:failed published)))))
      (is (true? (:repairing? published)))
      (close-world! w)))
  (testing "the manifest refused"
    (let [w (world [19 20] {})
          {:keys [manifest]} (round! (:node w) 1 5)
          _ (reset! (:refuse w) #{manifest})
          w (run-world w 50 20000 #(event (:events %) :published manifest))
          published (event (:events w) :published manifest)]
      (is (= :unacknowledged (:result published)))
      (is (= [manifest] (mapv :address (:failed published))))
      (is (= 5 (:sent published)))
      (close-world! w))))


;; =============================================================================
;; 5.5.4: repair
;; =============================================================================

(deftest repair-acknowledges-a-partial-publication-once-healed
  (let [w (world [21 22] {:repair-ticks 3000})
        {:keys [manifest rows]} (round! (:node w) 1 5)
        refused (nth rows 1)
        _ (reset! (:refuse w) #{refused})
        w (run-world w 50 20000 #(event (:events %) :published manifest))
        _ (is (= :partial (:result (event (:events w) :published manifest))))
        _ (reset! (:refuse w) #{})
        w (run-world w 50 60000 #(event (:events %) :republished manifest))
        w (run-world w 50 (+ (:now w) 40000) (constantly false))
        republished (events-of (:events w) :republished manifest)]
    (is (= 1 (count republished)) (pr-str republished))
    (is (= {:result :acknowledged :sent 6 :blobs 6 :repairing? false}
           (select-keys (first republished) [:result :sent :blobs
                                             :repairing?])))
    (is (= 1 (replicates (:node w) manifest)) "the manifest was asked once")
    (is (= 2 (replicates (:node w) refused)))
    (doseq [row (remove #{refused} rows)]
      (is (= 1 (replicates (:node w) row)) "a sent blob is never asked again"))
    (close-world! w)))


(deftest repair-takes-an-unacknowledged-publication-through-partial
  (let [w (world [23 24] {:repair-ticks 3000})
        {:keys [manifest rows]} (round! (:node w) 1 4)
        refused (set (cons manifest (take 2 rows)))
        _ (reset! (:refuse w) refused)
        w (run-world w 50 20000 #(event (:events %) :published manifest))
        _ (is (= :unacknowledged (:result (event (:events w) :published
                                                 manifest))))
        _ (reset! (:refuse w) (disj refused manifest))
        w (run-world w 50 80000 #(event (:events %) :republished manifest))
        _ (reset! (:refuse w) #{})
        w (run-world w 50 200000
                     #(= 2 (count (events-of (:events %) :republished
                                             manifest))))
        w (run-world w 50 (+ (:now w) 100000) (constantly false))]
    (is (= [:partial :acknowledged]
           (mapv :result (events-of (:events w) :republished manifest))))
    (is (= 2 (replicates (:node w) manifest)))
    (doseq [row (drop 2 rows)]
      (is (= 1 (replicates (:node w) row))))
    (close-world! w)))


(defn- check-dead-network
  "The bounds of 5.5.2 on every step of a dead network.  `blob-max` is
   the largest publication the test announces, so the ledger entries the
   node retains are bounded by the contract's ring-capacity form and by
   the tighter form the test's data allows."
  [limits blob-max]
  (fn [w]
    (let [{:keys [fresh repair outstanding repairing open live ledger-entries]}
          (dht/backlog (:node w))
          live-bound (+ (:max-open limits) (:max-repairing limits))]
      (is (<= fresh (:max-backlog limits)))
      (is (<= repair (* (:max-repairing limits) (:repair-batch limits))))
      (is (<= outstanding 64))
      (is (<= repairing (:max-repairing limits)))
      (is (<= open (:max-open limits)))
      (is (<= live live-bound))
      (is (<= ledger-entries (* live-bound dht/ring-capacity))
          "retained ledger entries stay within the ledger ring's bound")
      (is (<= ledger-entries (* live-bound blob-max))
          (str "retained ledger entries " ledger-entries " exceed "
               live-bound " ledgers of " blob-max)))))


(defn- round-verdicts!
  "Put round `r`'s `n` rows and its manifest through the node's store
   handle and announce it, answering `{:manifest m :verdicts [...]}`, the
   local verdict of every put."
  [node r n]
  (let [put (:put-bytes-fn (dht/store node))
        payloads (conj (mapv #(hash-map :round r :row %) (range n)) {:round r
                                                                     :rows n})
        verdicts (mapv #(put (jing/segment-key %) (jing/canonical-bytes %))
                       payloads)
        m (jing/segment-key (peek payloads))]
    (dht/announce! node m)
    {:manifest m :verdicts verdicts}))


(deftest
  a-peer-that-never-accepts-is-never-acknowledged-and-stays-bounded
  (let
    [limits {:max-backlog 64 :max-repairing 3 :repair-batch 4 :max-open 4
             :repair-ticks 1000 :repair-max-ticks 8000}
     w (world [25 26] limits)
     _ (reset! (:refuse w) (constantly true))
     rounds (mapv #(round-verdicts! (:node w) % 9) (range 6))
     manifests (atom (mapv :manifest rounds))
     _ (testing (str
                  "no put waits: every put and announcement comp"
                  "leted before any step")
         (is (nil? (:reading (:node w))) "the node has not stepped")
         (is (every? #(= :inserted %) (mapcat :verdicts rounds))
             "each put answered its local verdict at once")
         (is (= 60 (count (mapcat :verdicts rounds)))))
     idle (atom [])
     delays (atom {})
     w (run-world
         w 100 300000 (constantly false)
         (fn [w]
           ((check-dead-network limits 10) w)
           (doseq [p (dht/publications (:node w))]
             (swap! delays update (:manifest p) (fnil conj [])
                    (:delay p)))
           (let
             [b (dht/backlog (:node w))
              between? (and
                         (zero? (:open b)) (zero? (:fresh b))
                         (zero? (:repair b)) (zero?
                                               (:outstanding b))
                         (not-any? :cycle-open?
                                   (dht/publications (:node
                                                       w))))]
             (when between?
               (is (false? (dht/busy? (:node w))))
               (swap! idle conj (:now w))))))]
    (testing "every first report is unacknowledged and repairing"
      (doseq [m @manifests]
        (let [p (event (:events w) :published m)]
          (is (= :unacknowledged (:result p)) (pr-str p))
          (is (true? (:repairing? p))))))
    (is (not-any? #(= :acknowledged (:result %)) (:events w)))
    (testing (str
               "only displacement ends a publication, once ea"
               "ch, beyond :max-repairing")
      (let [republished (filterv #(= :republished (::dht/event %)) (:events w))]
        (is (every? #(= ::dht/displaced (:ended %)) republished))
        (is (= 3 (count republished)))
        (is (= (count republished) (count (distinct (map :manifest
                                                         republished)))))))
    (testing "the delay doubles to :repair-max-ticks and stays"
      (let [seen (distinct (val (last (sort-by #(count (val %)) @delays))))]
        (is (= [1000 2000 4000 8000] (vec (take 4 seen))) (pr-str seen))
        (is (= 8000 (last seen)))))
    (close-world! w)
    (testing "one repairing publication leaves the node idle between cycles"
      (let [w (world [25 26] limits)
            _ (reset! (:refuse w) (constantly true))
            _ (round! (:node w) 0 9)
            idle (atom 0)
            w (run-world w 100 60000 (constantly false)
                         (fn [w]
                           (when (and (= 1 (:repairing (dht/backlog (:node w))))
                                      (not (dht/busy? (:node w))))
                             (swap! idle inc))))]
        (is (pos? @idle))
        (close-world! w)))))


(deftest open-publications-are-bounded-and-the-overflow-reported-at-once
  (let [limits {:max-backlog 4096 :max-repairing 4 :repair-batch 64 :max-open 3
                :repair-ticks 30000 :repair-max-ticks 600000}
        w (world [27 28] limits)
        _ (reset! (:refuse w) (constantly true))
        open (mapv #(:manifest (round! (:node w) % 3)) (range 3))
        w (step-world w 50)
        {:keys [manifest]} (round! (:node w) 3 3)
        w (step-world w 50)
        published (event (:last w) :published manifest)]
    (is (some? published) "the overflow is reported in the step that reads it")
    (is (every? #(= ::dht/publications-full (:reason %)) (:failed published)))
    (is (= 4 (count (:failed published))))
    (is (true? (:repairing? published)))
    (is (not-any? #(event (:events w) :published %) open)
        "it displaced nothing open")
    (is (= 3 (:open (dht/backlog (:node w)))))
    (is (= 4 (:live (dht/backlog (:node w)))))
    (is (<= (:ledger-entries (dht/backlog (:node w)))
            (* (+ 3 4) dht/ring-capacity)))
    (close-world! w))
  (testing (str
             "publications sharing one address, on a dead n"
             "etwork: bounded every step")
    (let [limits {:max-backlog 64 :max-repairing 3 :repair-batch 4 :max-open 4
                  :repair-ticks 1000 :repair-max-ticks 4000}
          w (world [63 64] limits)
          _ (reset! (:refuse w) (constantly true))
          a (jing/materialize! (dht/store (:node w)) {:shared "dead"})
          check (fn [w]
                  ((check-dead-network limits 1) w)
                  (let [b (dht/backlog (:node w))]
                    (is (<= (+ (:fresh b) (:repair b) (:outstanding b)) 1)
                        "the shared address holds one queue entry or request")))
          w (reduce (fn [w _]
                      (dht/announce! (:node w) a)
                      (run-world w 50 (+ (:now w) 500) (constantly false)
                                 check))
                    w
                    (range 12))
          w (run-world w 100 (+ (:now w) 60000) (constantly false) check)]
      (is (= 12 (count (events-of (:events w) :published a))))
      (close-world! w)))
  (testing "many open publications sharing one queued address hold one entry"
    (let [w (world [29 30] {:max-open 16})
          a (jing/materialize! (dht/store (:node w)) {:shared true})
          node (reduce (fn [node _] (dht/announce! node a)) (:node w) (range
                                                                        16))
          [node _] (dht/step node 0)]
      (is (= 16 (:open (dht/backlog node))))
      (is (= 16 (:ledger-entries (dht/backlog node))))
      (is (= 1 (+ (:fresh (dht/backlog node)) (:outstanding (dht/backlog
                                                              node)))))
      (dht/close! node))))


(deftest
  fresh-and-repair-requests-share-the-bound-fairly
  (let [limits {:max-repairing 4 :repair-batch 8 :repair-slots 16
                :repair-ticks 500 :repair-max-ticks 500}
        w (world [31 32] limits)
        failing (atom #{})
        _ (reset! (:refuse w) #(contains? @failing %))
        old (mapv (fn [r] (round! (:node w) r 16)) (range 4))
        _ (reset! failing (set (mapcat :rows old)))
        w (run-world w 50 60000
                     #(= 32 (:repair (dht/backlog (:node %))))
                     bounded-outstanding)
        _ (is (= 32 (:repair (dht/backlog (:node w))))
              "the repair queue is full")
        {:keys [manifest]} (round! (:node w) 9 200)
        fresh-peak (atom 0)
        w (run-world w 10 (+ (:now w) 60000)
                     #(event (:events %) :published manifest)
                     (fn [w]
                       (let [b (dht/backlog (:node w))]
                         (is (<= (:outstanding-repair b) 16))
                         (swap! fresh-peak max (:outstanding-fresh b)))))]
    (is (<= 48 @fresh-peak) (str "fresh peak " @fresh-peak))
    (is (some? (event (:events w) :published manifest))
        "the new round is reported while repairs continue")
    (is (pos? (:repairing (dht/backlog (:node w)))))
    (close-world! w))
  (testing
    "with no repair queued a fresh round uses the whole bound"
    (let
      [w (world [33 34] {})
       {:keys [manifest]} (round! (:node w) 1 300)
       peak (atom 0)
       w (run-world
           w 10 60000 #(event (:events %) :published manifest)
           #(swap!
              peak max (:outstanding-fresh
                         (dht/backlog
                           (:node
                             %)))))]
      (is (= 64 @peak))
      (close-world! w))))


(deftest repair-is-admitted-while-fresh-writes-keep-the-queue-full
  (let [limits {:max-backlog 128 :max-open 256 :max-repairing 256
                :repair-ticks 2000}
        w (world [35 36] limits)
        {:keys [manifest rows]} (round! (:node w) 0 4)
        refused (first rows)
        _ (reset! (:refuse w) #{refused})
        w (run-world w 50 20000 #(event (:events %) :published manifest))
        _ (is (= :partial (:result (event (:events w) :published manifest))))
        _ (reset! (:refuse w) #{})
        r (atom 100)
        top-up (fn [w]
                 (let [b (dht/backlog (:node w))
                       n (+ 20 (- 128 (:fresh b)))]
                   (round! (:node w) (swap! r inc) n)
                   w))
        admitted (atom nil)
        w (loop [w (top-up w)]
            (let [w (step-world w 50)
                  p (dht/publication (:node w) manifest)]
              ;; the cycle opens and admits the row in one step, before
              ;; fresh admission refills the queue past its bound
              (when (and (nil? @admitted) (= 1 (:cycles p)))
                (reset! admitted {:fresh (:fresh (dht/backlog (:node w)))
                                  :asked (replicates (:node w) refused)}))
              (if (or (event (:events w) :republished manifest) (> (:now w)
                                                                   200000))
                w
                (recur (top-up w)))))]
    (is (some? @admitted) "the cycle opened while fresh writes continued")
    (is (<= (- 128 64) (:fresh @admitted))
        "the fresh queue was full at admission and barely drained in that step")
    (is (= :acknowledged (:result (event (:events w) :republished manifest))))
    (is (<= (- 128 64) (:fresh (dht/backlog (:node w))))
        "fresh writes continued")
    (is (some #(= ::dht/backlog-full (:reason %))
              (mapcat :failed (filter #(= :published (::dht/event %)) (:events
                                                                        w)))))
    (close-world! w)))


(deftest
  a-repair-batch-bounds-each-publication-and-all-progress
  (let
    [limits {:max-repairing 3 :repair-batch 4 :repair-ticks 1000
             :repair-max-ticks 1000}
     w (world [37 38] limits)
     _ (reset! (:refuse w) (constantly true))
     pubs (mapv (fn [r] (round! (:node w) r 9)) (range 3))
     cycles (atom {})
     w (run-world
         w 50 150000
         #(every?
            (fn [p]
              (<=
                3 (:cycles
                    (dht/publication
                      (:node %)
                      (:manifest p)) 0)))
            pubs)
         (fn [w]
           (doseq [{:keys [manifest]} pubs
                   :let [p (dht/publication (:node w) manifest)]]
             (is (some? p) "no publication is retired")
             (is (<= (:repair-queued p 0) 4))
             (swap! cycles assoc manifest (:cycles p)))))]
    (doseq [{:keys [manifest rows]} pubs]
      (is (<= 3 (get @cycles manifest)) "every publication cycles")
      (doseq [a (cons manifest rows)]
        (is (<= 3 (replicates (:node w) a))
            "every failed blob is asked in every cycle")))
    (close-world! w)))


;; =============================================================================
;; 5.5.1: a shared outstanding address, the overflow, and /sent
;; =============================================================================

(defn- silent-world
  "A world whose peers stay silent while it is stepped with
   `step-node-only`, so the node's requests stay outstanding."
  [peer-ports opts]
  (world peer-ports opts))


(defn- step-node-only
  [w dt]
  (let [[node events] (dht/step (:node w) (:now w))]
    (-> w
        (assoc :node node :now (+ (:now w) dt) :last events)
        (update :events into events))))


(deftest an-overflow-sharing-an-outstanding-address-takes-its-sent-fact
  (doseq [shape [:only-shared :with-own-row]]
    (let [w (silent-world [39 40] {:max-open 2 :repair-ticks 30000})
          node (:node w)
          a (jing/materialize! (dht/store node) {:shared "a"})
          m1 (jing/materialize! (dht/store node) {:m 1})
          _ (dht/announce! node m1)
          _ (round! node 2 1)
          w (step-node-only w 10)
          _ (is (= 2 (:open (dht/backlog (:node w)))))
          own (when (= shape :with-own-row)
                (jing/materialize! (dht/store (:node w)) {:own "row"}))
          _ (jing/materialize! (dht/store (:node w)) {:shared "a"})
          _ (dht/announce! (:node w) a)
          w (step-node-only w 10)
          first-report (event (:last w) :published a)]
      (is (= {:result :unacknowledged :repairing? true}
             (select-keys first-report [:result :repairing?])))
      (is (= (set (remove nil? [a own])) (set (map :address (:failed
                                                              first-report)))))
      (is (every? #(= ::dht/publications-full (:reason %)) (:failed
                                                             first-report)))
      (let [no-cycle (fn [w]
                       (when-let [p (and (= shape :only-shared)
                                         (dht/publication (:node w) a))]
                         (is (zero? (:cycles p)) "no repair cycle of it opens"))
                       ;; an explicit value: cljd types a nested when-of-is as
                       ;; Null
                       nil)
            _ (no-cycle w)
            w (run-world w 10 20000 #(event (:events %) :republished a)
                         no-cycle)
            republished (event (:events w) :republished a)]
        (when (= shape :only-shared)
          (is (nil? (dht/publication (:node w) a))
              "retired on acknowledgement"))
        (is (< (:now w) 30000) "well before :repair-ticks")
        (case shape
          :only-shared
          (do (is (= :acknowledged (:result republished)))
              (is (= (:blobs republished) (:sent republished))))
          :with-own-row
          (let [_ (is (= :partial (:result republished)))
                w (run-world w 100 200000
                             #(= 2 (count (events-of (:events %) :republished
                                                     a))))]
            (is (= [:partial :acknowledged]
                   (mapv :result (events-of (:events w) :republished a))))))
        (is (= 1 (replicates (:node w) a)) "no second request for a")
        (close-world! w)))))


(deftest
  a-shared-failure-changes-no-result-and-produces-no-event
  (let
    [w (silent-world [43 44] {:max-open 1 :repair-ticks 100000})
     node (:node w)
     a (jing/materialize! (dht/store node) {:shared "fails"})
     _ (dht/announce! node a)
     w (step-node-only w 10)
     _ (dht/announce! (:node w) a)
     w (step-node-only w 10)
     _ (is
         (=
           2 (count
               (filter
                 #(= a (:manifest %)) (dht/publications
                                        (:node
                                          w))))))
     _ (reset! (:refuse w) #{a})
     w (run-world w 50 30000 #(<= 2 (count (events-of (:events %) :published
                                                      a))))]
    (is (= 2 (count (events-of (:events w) :published a))))
    (is (empty? (events-of (:events w) :republished a))
        "the overflow's entry changed reason, not result")
    (close-world! w)))


;; =============================================================================
;; 5.5.4: terminal reasons, retry!, and time
;; =============================================================================

(defn- hiding-store
  "A content store that answers no bytes for an address `@hidden` holds."
  [hidden]
  (let [inner (mem/create-content-mem)]
    (assoc inner :get-bytes-fn (fn [a not-found]
                                 (if (contains? @hidden a)
                                   not-found
                                   ((:get-bytes-fn inner) a not-found))))))


(deftest terminal-reasons-end-repair
  (testing "a publication whose only failure is /absent"
    (let [hidden (atom #{})
          w (world [45 46] {:repair-ticks 1000} (hiding-store hidden))
          {:keys [manifest rows]} (round! (:node w) 1 3)
          _ (reset! hidden #{(first rows)})
          w (run-world w 50 20000 #(event (:events %) :published manifest))
          w (run-world w 50 (+ (:now w) 20000) (constantly false))
          published (event (:events w) :published manifest)]
      (is (= {:result :partial :repairing? false :ended ::dht/terminal}
             (select-keys published [:result :repairing? :ended])))
      (is (= [{:address (first rows) :reason ::jing.dht/absent :peers 0}]
             (:failed published)))
      (is (= 1 (count (reports w))) "reported once")
      (is (= 1 (replicates (:node w) (first rows))) "never asked again")
      (close-world! w)))
  (testing "a publication whose only failure is /oversize"
    (let [w (world [47 48] {})
          big (jing/materialize! (dht/local (:node w))
                                 {:big (apply str (repeat 70000 "x"))})
          _ (dht/announce! (:node w) big)
          w (run-world w 50 20000 #(event (:events %) :published big))
          published (event (:events w) :published big)]
      (is (= ::jing.dht/oversize (:reason (first (:failed published)))))
      (is (= ::dht/terminal (:ended published)))
      (close-world! w)))
  (testing (str
             "a terminal and a retryable failure: repair, t"
             "hen end :terminal :partial")
    (let [hidden (atom #{})
          w (world [49 50] {:repair-ticks 1000} (hiding-store hidden))
          {:keys [manifest rows]} (round! (:node w) 1 3)
          _ (reset! hidden #{(first rows)})
          _ (reset! (:refuse w) #{(second rows)})
          w (run-world w 50 20000 #(event (:events %) :published manifest))
          published (event (:events w) :published manifest)
          _ (is (true? (:repairing? published)))
          _ (reset! (:refuse w) #{})
          w (run-world w 50 60000 #(event (:events %) :republished manifest))
          w (run-world w 50 (+ (:now w) 20000) (constantly false))]
      (is (= [{:result :partial :repairing? false :ended ::dht/terminal :sent
               3}]
             (mapv #(select-keys % [:result :repairing? :ended :sent])
                   (events-of (:events w) :republished manifest))))
      (close-world! w))))


(deftest retry-brings-the-next-cycle-forward
  (let [w (world [51 52] {:repair-ticks 1000000 :repair-max-ticks 1000000})
        {:keys [manifest rows]} (round! (:node w) 1 2)
        _ (reset! (:refuse w) #{(first rows)})
        w (run-world w 50 20000 #(event (:events %) :published manifest))
        before (replicates (:node w) (first rows))
        w (update w :node dht/retry! manifest)
        w (step-world w 50)]
    (is (= (inc before) (replicates (:node w) (first rows)))
        "the cycle ran at the next step")
    (is (empty? (:last w)) "retry! reports nothing of its own")
    (is (= ::dht/not-repairing
           (refused-code #(dht/retry! (:node w) (jing/segment-key "unknown")))))
    (let [w (run-world w 50 (+ (:now w) 20000)
                       #(not (:cycle-open? (dht/publication (:node %)
                                                            manifest))))
          _ (is (empty? (events-of (:events w) :republished manifest))
                "a failed cycle changes no result")
          _ (reset! (:refuse w) #{})
          w (update w :node dht/retry! manifest)
          w (run-world w 50 (+ (:now w) 20000) #(event (:events %) :republished
                                                       manifest))]
      (is (= :acknowledged (:result (event (:events w) :republished manifest))))
      (is (= ::dht/not-repairing (refused-code #(dht/retry! (:node w)
                                                            manifest)))
          "refused once acknowledged")
      (close-world! w))))


(deftest repair-is-due-only-as-appended-ticks-advance
  (let [w (world [53 54] {:repair-ticks 1000})
        {:keys [manifest rows]} (round! (:node w) 1 2)
        _ (reset! (:refuse w) #{(first rows)})
        w (run-world w 50 20000 #(event (:events %) :published manifest))
        asked (replicates (:node w) (first rows))
        frozen (reduce (fn [w _] (step-world w 0)) w (range 300))]
    (is (= asked (replicates (:node frozen) (first rows)))
        "three hundred steps at one reading open no cycle")
    (let [w (run-world frozen 50 (+ (:now frozen) 2000) (constantly false))]
      (is (< asked (replicates (:node w) (first rows)))
          "the readings, not the steps, made it due")
      (close-world! w))))


;; =============================================================================
;; 5.5.5: cancel!
;; =============================================================================

(deftest
  cancel-settles-every-ledger-state
  (let
    [w (world [55 56] {})
     node (:node w)
     h (dht/store node)
     sent (mapv #(jing/materialize! h {:sent %}) (range 10))
     failing (mapv #(jing/materialize! h {:failing %}) (range 54))
     slow (mapv #(jing/materialize! h {:slow %}) (range 10))
     _queued (mapv #(jing/materialize! h {:queued %}) (range 100))
     manifest (jing/materialize! h {:cancel "me"})
     _ (dht/announce! node manifest)
     _ (reset! (:refuse w) (set (concat failing slow)))
     state-of (fn [w a]
                (get-in (dht/publication (:node w) manifest)
                        [:entries a :state]))
     w (run-world w 10 30000 #(every? (fn [a] (= :failed (state-of % a)))
                                      failing))]
    (testing "the four states hold before the call"
      (is (every? #(= :sent (state-of w %)) sent))
      (is (every? #(= :failed (state-of w %)) failing))
      (is (some #(= :waiting (state-of w %)) slow) "outstanding")
      (is (pos? (:fresh (dht/backlog (:node w)))) "and queued"))
    (let
      [w (update w :node dht/cancel! manifest)
       w (step-world w 10)
       reported (event (:last w) :published manifest)]
      (is (= {:ended ::dht/cancelled :repairing? false :sent 10 :blobs 175}
             (select-keys reported [:ended :repairing? :sent :blobs])))
      (is (every? #(= ::dht/cancelled (:reason %)) (:failed reported)))
      (is
        (=
          ::jing.dht/too-few-peers
          (:was
            (first
              (filter
                #(= (first failing) (:address %)) (:failed
                                                    reported))))))
      (is (= 0 (:fresh (dht/backlog (:node w)))) "its queue entries are gone")
      (testing "a late fact changes nothing reported"
        (let [w (run-world w 50 (+ (:now w) 20000)
                           #(zero? (:outstanding (dht/backlog (:node %)))))]
          (is (= 1 (count (reports w))))
          (is (= ::dht/not-live (refused-code #(dht/cancel! (:node w)
                                                            manifest))))
          (close-world! w))))))


(deftest cancel-after-the-first-report-is-republished
  (let [w (world [59 60] {:repair-ticks 100000})
        {:keys [manifest rows]} (round! (:node w) 1 2)
        _ (reset! (:refuse w) #{(first rows)})
        w (run-world w 50 20000 #(event (:events %) :published manifest))
        w (update w :node dht/cancel! manifest)
        w (step-world w 50)]
    (is (= [{:result :partial :ended ::dht/cancelled :repairing? false}]
           (mapv #(select-keys % [:result :ended :repairing?])
                 (events-of (:last w) :republished manifest))))
    (is (= [{:address (first rows) :reason ::dht/cancelled
             :was ::jing.dht/too-few-peers :peers 0}]
           (:failed (event (:last w) :republished manifest))))
    (is (= ::dht/not-repairing (refused-code #(dht/retry! (:node w) manifest))))
    (close-world! w)))


(deftest shared-addresses-issue-one-request-and-survive-a-cancel
  (let [w (silent-world [57 58] {})
        node (:node w)
        h (dht/store node)
        fillers (mapv #(jing/materialize! h {:filler %}) (range 70))
        x1 (first fillers)
        a (jing/materialize! h {:shared "queued"})
        m1 (jing/materialize! h {:m 1})
        _ (dht/announce! node m1)
        w (step-node-only w 10)
        _ (is (= 64 (:outstanding (dht/backlog (:node w)))) "x1 is outstanding")
        _ (is (= 8 (:fresh (dht/backlog (:node w)))) "a is queued")
        _ (jing/materialize! h {:filler 0})
        _ (jing/materialize! h {:shared "queued"})
        m2 (jing/materialize! h {:m 2})
        _ (dht/announce! node m2)
        w (step-node-only w 10)
        _ (is (= 9 (:fresh (dht/backlog (:node w))))
              "a joined, not queued twice")
        w (update w :node dht/cancel! m1)
        _ (is (= 2 (:fresh (dht/backlog (:node w))))
              "a stays queued for the second")
        w (run-world w 10 30000 #(event (:events %) :published m2))
        first-pub (event (:events w) :published m1)
        second-pub (event (:events w) :published m2)
        reason-of (fn [e x]
                    (:reason (first (filter #(= x (:address %))
                                            (:failed e)))))]
    (is (= ::dht/cancelled (:ended first-pub)))
    (is (= ::dht/cancelled (reason-of first-pub x1)))
    (is (= ::dht/cancelled (reason-of first-pub a)))
    (is (= {:result :acknowledged :sent 3 :blobs 3}
           (select-keys second-pub [:result :sent :blobs]))
        "the late fact of x1 and the queued a count for the second")
    (is (= 1 (replicates (:node w) a)))
    (is (= 1 (replicates (:node w) x1)))
    (is (= 1 (count (events-of (:events w) :published m1))))
    (is (empty? (events-of (:events w) :republished m1)))
    (close-world! w)))


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
     :cljs (try (.rmSync (js/require "fs") dir #js {:recursive true :force
                                                    true})
                (catch :default _ nil))))


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


(deftest
  close-reports-nothing-and-a-reopened-node-repairs-nothing
  (let
    [dir (temp-dir)
     net (mesh/mesh)
     refuse (atom (constantly true))
     open! #(dht/join {:dir dir :publish? true :repair-ticks 1000
                       :peers [{:host mesh/host :port 62}]
                       :bind! (refusing-bind net 61 refuse)})]
    (try
      (let
        [_peer (peer! net 62)
         node (open!)
         {:keys [manifest]} (round! node 1 3)
         [node events] (loop
                         [node node now 0 events []]
                         (if
                           (or
                             (event events :published manifest) (>
                                                                  now
                                                                  20000))
                           [node events]
                           (let [[node more] (dht/step node now)]
                             (recur node (+ now 50) (into events more)))))]
        (is (= :unacknowledged (:result (event events :published manifest))))
        (is (nil? (dht/close! node)) "close! reports nothing")
        (let [again (open!)
              [again events] (loop [node again now 0 events []]
                               (if (> now 20000)
                                 [node events]
                                 (let [[node more] (dht/step node now)]
                                   (recur node (+ now 50) (into events
                                                                more)))))]
          (is (some? (jing/get (dht/local again) manifest nil))
              "every blob is local")
          (is (zero? (count (filter ::jing.dht/replicate
                                    (mesh/values (get-in again [:composition
                                                                :requests])))))
              "no repair request")
          (is (empty? (reports {:events events})))
          (dht/close! again)))
      (finally
        (cleanup-dir! dir)))))
