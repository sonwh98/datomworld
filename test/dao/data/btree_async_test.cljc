(ns dao.data.btree-async-test
  "hydrate-async and store-tree-async (docs/design/dao.data.btree.md §5.4)
   over dao.jing.remote.async, the async content backend over the stepped
   client.

   Every test runs on every host, synchronously: the backend's reschedule
   hook is a manual queue, and `drain!` turns a hand-turned server (ring
   buffers + dao.stream.apply over dao.jing.remote/default-handlers, as in
   dao.jing.remote.step-test) before each queued pump.  The callback
   arities are the portable surface under test; the host deferred wrapper
   is checked on the JVM only."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.data.btree :as bt]
            [dao.data.btree.storage :as bts]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.jing.remote :as remote]
            [dao.jing.remote.async :as remote.async]
            [dao.jing.remote.step :as step]
            [dao.stream :as stream]
            [dao.stream.apply :as apply]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]))


(defn- ex-msg
  [f]
  (try (f)
       nil
       (catch #?(:cljd cljd.core/ExceptionInfo
                 :clj Exception
                 :cljs js/Error)
              e
         (ex-message e))))


(defn- ring-handle
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- ring-cursor
  [handle]
  (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest)))


(defn- make-server
  "A hand-turned server: `serve!` dispatches `handlers` over every request
   not yet served, one response per request."
  [handlers]
  (let [request-handle (ring-handle 256)
        response-handle (ring-handle 256)
        cursor-atom (atom (ring-cursor request-handle))]
    {:request request-handle
     :response response-handle
     :serve! (fn []
               (loop []
                 (let [read (stream/next request-handle @cursor-atom)]
                   (when (= :dao.stream/ok (:dao.stream/outcome read))
                     (reset! cursor-atom (:dao.stream/cursor read))
                     (stream/append! response-handle
                                     (apply/dispatch-request
                                       handlers
                                       (:dao.stream/value read)))
                     (recur)))))}))


(defn- rig
  "An async content handle over a hand-turned server for `handlers`, with a
   manual scheduler (or, given `schedule`, a host timer that turns the
   server before each pump). `:gets` counts get requests issued through it."
  ([handlers] (rig handlers nil))
  ([handlers schedule]
   (let [server (make-server handlers)
         queue (atom [])
         gets (atom 0)
         backend (remote.async/async-content
                   (step/client-state
                     (rpc/client-state (:request server)
                                       (:response server)
                                       (ring-cursor (:response server))))
                   {:schedule (if schedule
                                (fn [f] (schedule #(do ((:serve! server)) (f))))
                                (fn [f] (swap! queue conj f)))})]
     {:server server
      :queue queue
      :gets gets
      :source (update backend :get-content-async-fn
                      (fn [g] (fn [a cb] (swap! gets inc) (g a cb))))})))


(defn- drain!
  "Turn the server, then run the next queued pump, until nothing is
   queued. Returns the number of pumps run."
  [{:keys [server queue]}]
  (loop [n 0]
    (if-let [f (first @queue)]
      (do (swap! queue #(vec (rest %)))
          ((:serve! server))
          (f)
          (if (< n 10000) (recur (inc n)) (throw (ex-info "no quiescence" {}))))
      n)))


(defn- outcome
  "Callbacks recording the one outcome of an async call."
  []
  (let [r (atom nil)]
    {:result r
     :on-ok #(reset! r [:ok %])
     :on-err #(reset! r [:err (ex-message %)])}))


(defn- seeded-store
  "A memory store holding a tree of `n` elements at branching factor 32,
   written through the sync kv-storage. Returns {:store :address}."
  [n]
  (let [store (mem/create-content-mem)
        storage (bts/kv-storage store {:branching-factor 32})
        s (into (bt/restore-tree compare nil storage 0) (range n))]
    {:store store :address (bt/store-tree s storage)}))


(deftest hydrate-async-fills-the-cache-then-reads-succeed
  (let [{:keys [store address]} (seeded-store 600)
        r (rig (remote/default-handlers store))
        hs (bts/hydration-storage (:source r) (mem/create-content-mem)
                                  {:branching-factor 32})
        s (bt/restore-tree compare address hs 600)
        {:keys [result on-ok on-err]} (outcome)]
    (is (= "unhydrated segment" (ex-msg #(doall (seq s)))))
    (is (nil? (bts/hydrate-async s on-ok on-err)))
    (is (nil? @result) "nothing resolves before the pump runs")
    (is (pos? (drain! r)))
    (is (= [:ok s] @result) "resolves to the same set")
    (is (= (range 600) (seq s)))
    (is (some? (conj s 1000)) "the write path is resident too")
    (let [fetched @(:gets r)]
      (is (< 15 fetched) "every segment was fetched once")
      (testing "idempotent: a resident graph issues no requests"
        (let [{:keys [result on-ok on-err]} (outcome)]
          (bts/hydrate-async s on-ok on-err)
          (drain! r)
          (is (= [:ok s] @result))
          (is (= fetched @(:gets r))))))))


(deftest hydrate-async-rejects-a-source-miss
  (let [store (mem/create-content-mem)
        r (rig (remote/default-handlers store))
        hs (bts/hydration-storage (:source r) (mem/create-content-mem))
        s (bt/restore-tree compare (jing/segment-key {:not "stored"}) hs 1)
        {:keys [result on-ok on-err]} (outcome)]
    (bts/hydrate-async s on-ok on-err)
    (drain! r)
    (is (= [:err "missing index segment"] @result))))


(deftest hydrate-async-rejects-a-failed-fetch
  (let [{:keys [address]} (seeded-store 100)
        r (rig {:jing/get-content (fn [_] (throw (ex-info "down" {})))})
        hs (bts/hydration-storage (:source r) (mem/create-content-mem)
                                  {:branching-factor 32})
        s (bt/restore-tree compare address hs 100)
        {:keys [result on-ok on-err]} (outcome)]
    (bts/hydrate-async s on-ok on-err)
    (drain! r)
    (is (= [:err "hydration fetch failed"] @result))))


(deftest store-tree-async-resolves-after-every-segment-acknowledges
  (let [store (mem/create-content-mem)
        r (rig (remote/default-handlers store))
        hs (bts/hydration-storage (:source r) (mem/create-content-mem)
                                  {:branching-factor 32})
        s (into (bt/restore-tree compare nil hs 0) (range 600))]
    (is (= "sync store-tree against an async backend"
           (ex-msg #(bt/store-tree s hs))))
    (let [{:keys [result on-ok on-err]} (outcome)]
      (is (nil? (bts/store-tree-async s hs on-ok on-err)))
      (is (nil? @result) "no root before the writes acknowledge")
      (drain! r)
      (let [[tag root] @result
            addresses (atom [])]
        (is (= :ok tag))
        (is (= root (bt/set-address s)))
        (bt/walk-addresses hs root #(do (swap! addresses conj %) true))
        (is (< 15 (count @addresses)))
        (is (every? #(some? (jing/get store % nil)) @addresses)
            "the root's whole closure is durable at the source")
        (testing "round trip through a fresh cache"
          (let [hs2 (bts/hydration-storage (:source r) (mem/create-content-mem)
                                           {:branching-factor 32})
                s2 (bt/restore-tree compare root hs2 600)
                {:keys [result on-ok on-err]} (outcome)]
            (bts/hydrate-async s2 on-ok on-err)
            (drain! r)
            (is (= [:ok s2] @result))
            (is (= (range 600) (seq s2)))))))))


(deftest store-tree-async-retries-unacknowledged-segments
  (let [store (mem/create-content-mem)
        failing? (atom true)
        handlers (remote/default-handlers store)
        put (:jing/put-content handlers)
        r (rig (assoc handlers
                      :jing/put-content
                      (fn [address payload]
                        (if @failing?
                          (throw (ex-info "refused" {}))
                          (put address payload)))))
        hs (bts/hydration-storage (:source r) (mem/create-content-mem)
                                  {:branching-factor 32})
        s (into (bt/restore-tree compare nil hs 0) (range 300))]
    (let [{:keys [result on-ok on-err]} (outcome)]
      (bts/store-tree-async s hs on-ok on-err)
      (drain! r)
      (is (= [:err "store-tree-async: segment write failed"] @result)))
    (reset! failing? false)
    (testing "the retry on the now-addressed set re-pushes the closure"
      (let [{:keys [result on-ok on-err]} (outcome)]
        (bts/store-tree-async s hs on-ok on-err)
        (drain! r)
        (is (= [:ok (bt/set-address s)] @result))
        (is (some? (jing/get store (bt/set-address s) nil)))))))


(deftest sync-storages-resolve-at-once
  (let [store (mem/create-content-mem)
        storage (bts/kv-storage store {:branching-factor 32})
        s (into (bt/restore-tree compare nil storage 0) (range 100))
        stored (outcome)
        hydrated (outcome)]
    (bts/store-tree-async s storage (:on-ok stored) (:on-err stored))
    (is (= [:ok (bt/set-address s)] @(:result stored)))
    (bts/hydrate-async s (:on-ok hydrated) (:on-err hydrated))
    (is (= [:ok s] @(:result hydrated)))))


(deftest hydrate-async-preserves-sha256-algorithm-test
  (let [store (mem/create-content-mem)
        storage (bts/kv-storage store
                                {:branching-factor 32, :algorithm :sha256})
        s (into (bt/restore-tree compare nil storage 0) (range 300))
        root (bt/store-tree s storage)
        r (rig (remote/default-handlers store))
        cache (mem/create-content-mem)
        hs (bts/hydration-storage (:source r) cache {:branching-factor 32})
        s-restored (bt/restore-tree compare root hs 300)
        {:keys [result on-ok on-err]} (outcome)]
    (is (= :sha256 (jing/segment-algorithm root)))
    (is (= "unhydrated segment" (ex-msg #(doall (seq s-restored)))))
    (bts/hydrate-async s-restored on-ok on-err)
    (drain! r)
    (is (= [:ok s-restored] @result))
    (is (= (range 300) (seq s-restored)))
    (let [cached-addrs (keys (mem/entry-bytes cache))]
      (is (pos? (count cached-addrs)))
      (is (every? #(= :sha256 (jing/segment-algorithm %)) cached-addrs)
          "all cached segment addresses preserve the sha256 algorithm"))))


(deftest store-tree-async-flushes-preserve-sha256-algorithm-test
  (let [store (mem/create-content-mem)
        r (rig (remote/default-handlers store))
        raw-cache (mem/create-content-mem)
        hs (bts/hydration-storage (:source r)
                                  raw-cache
                                  {:branching-factor 32, :algorithm :sha256})
        s (into (bt/restore-tree compare nil hs 0) (range 300))
        {:keys [result on-ok on-err]} (outcome)]
    (bts/store-tree-async s hs on-ok on-err)
    (drain! r)
    (let [[tag root] @result]
      (is (= :ok tag))
      (is (= :sha256 (jing/segment-algorithm root)))
      (let [source-content (mem/entry-bytes store)]
        (is (pos? (count source-content)))
        (is (every? #(= :sha256 (jing/segment-algorithm %))
                    (keys source-content))
            "all flushed segments to source preserve sha256 addresses")))))


(deftest store-tree-async-materialize-fallback-preserves-sha256-test
  (let [store (mem/create-content-mem)
        r (rig (remote/default-handlers store))
        source-without-put (dissoc (:source r) :put-content-async-fn)
        raw-cache (mem/create-content-mem)
        hs (bts/hydration-storage source-without-put
                                  raw-cache
                                  {:branching-factor 32, :algorithm :sha256})
        s (into (bt/restore-tree compare nil hs 0) (range 100))
        {:keys [result on-ok on-err]} (outcome)]
    (bts/store-tree-async s hs on-ok on-err)
    (drain! r)
    (let [[tag root] @result]
      (is (= :ok tag))
      (is (= :sha256 (jing/segment-algorithm root)))
      (let [source-content (mem/entry-bytes store)]
        (is (pos? (count source-content)))
        (is (every? #(= :sha256 (jing/segment-algorithm %))
                    (keys source-content))
            "materialize-async fallback preserves sha256 addresses")))))


(deftest host-deferred-resolves
  #?(:cljd nil
     ;; the real reschedule path: pumps run on the JVM delayed executor
     :clj (let [{:keys [store address]} (seeded-store 200)
                r (rig (remote/default-handlers store)
                       (remote.async/default-schedule 1))
                hs (bts/hydration-storage (:source r) (mem/create-content-mem)
                                          {:branching-factor 32})
                s (bt/restore-tree compare address hs 200)
                f (bts/hydrate-async s)]
            (is (instance? java.util.concurrent.CompletableFuture f))
            (is (identical? s (.get ^java.util.concurrent.CompletableFuture f
                                    10 java.util.concurrent.TimeUnit/SECONDS)))
            (is (= (range 200) (seq s)))
            (let [s' (conj s 1000)
                  g (bts/store-tree-async s' hs)
                  root (.get ^java.util.concurrent.CompletableFuture g
                             10 java.util.concurrent.TimeUnit/SECONDS)]
              (is (= root (bt/set-address s')))
              (is (some? (jing/get store root nil)))))
     :cljs nil))
