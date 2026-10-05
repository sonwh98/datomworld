(ns yin.vm.linker.head-follow-test
  "The head follower over any reader (docs/design/yin.vm.linker.dht.head.md
   sections 5.3 to 5.7, slice H1).  No transport: the reader is handed the
   publisher's board ring directly as its reader handle, and blobs travel
   over the dao.jing.dht test mesh.  Time is the reading each step is
   handed."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.dht :as jing.dht]
            [dao.jing.dht.mesh :as mesh]
            [dao.jing.mem :as mem]
            [dao.space.dht :as dht]
            [dao.space.index :as index]
            [dao.space.transactor :as transactor]
            [dao.stream :as stream]
            [dao.stream.memory-log :as memory-log]
            [yin.vm.linker.dht :as ld]
            [yin.vm.linker.head :as head]
            [yin.vm.linker.sign :as sign]))


;; =============================================================================
;; Indexes at a chosen sequence
;; =============================================================================

(def p1 (sign/generate))
(def p2 (sign/generate))


(defn- principal
  [key]
  (sign/principal (:public key)))


(defn- memory-log
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type memory-log/transport-type})))


(defn- index!
  "Publish the covered index of `history`, a vector of transactions each
   a vector of attribute maps, into the `dao.jing` store `store`: one
   transaction per element, so the index's sequence is `(dec (count
   history))`.  Answers its manifest address."
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
(def vx (jing/segment-key "alib, only through a wrong trace"))


(defn- filler
  [n]
  (mapv (fn [i] [{:test/filler i}]) (range n)))


(defn- alib-history
  "alib asserted at `v1` in the first transaction, then `n` fillers: a
   history whose index is at sequence `n`."
  [key n]
  (into [[(assertion key 'alib v1 1)]] (filler n)))


(defn- moved-history
  "`history` with one more transaction: alib retracted from `v1` and
   asserted at `address`."
  [key history address]
  (conj history [(retraction key (assertion key 'alib v1 1) 2)
                 (assertion key 'alib address 3)]))


;; =============================================================================
;; A world: publisher A at mesh port 1, reader B at 2 following A
;; =============================================================================

(defn- withholding-bind
  "The mesh seam at `port`, whose socket drops every `:fetch` for an
   address `(@withheld? address)` holds: that fetch is never answered."
  [net port withheld?]
  (let [bind! (mesh/seam net port)]
    (fn [opts]
      (let [seam (bind! opts)]
        (assoc seam
               :send! (fn [host to-port bs]
                        (let [m (jing.dht/decode-message bs)]
                          (if (and (= :fetch (:op m)) (@withheld? (:address m)))
                            {:dao.stream/outcome :dao.stream/ok}
                            ((:send! seam) host to-port bs)))))))))


(defn- node-at
  "A node bound through `bind!` with the mesh ports `peers` as contacts."
  [peers opts bind!]
  (dht/join (merge {:local (mem/create-content-mem)
                    :peers (mapv (fn [p] {:host mesh/host :port p}) peers)
                    :bind! bind!}
                   opts)))


(def follow-opts
  {:heads :volatile :poll-ticks 10 :repair-ticks 100 :repair-max-ticks 400})


(defn- world
  "A, publishing, at port 1 with one board per key of `keys`; B at port 2
   following those principals over A's boards, `:volatile`, installing
   every confirmed head at once unless `:install? false`."
  ([] (world {}))
  ([{:keys [keys install?] :or {keys [p1] install? true} :as opts}]
   (let [net (mesh/mesh)
         withheld (atom (constantly false))
         boards (into {} (map (fn [k] [(principal k) (head/board)])) keys)
         a (node-at [2] {:publish? true} (mesh/seam net 1))
         b (node-at [1] (select-keys opts [:local])
                    (withholding-bind net 2 withheld))
         [f b] (head/follow b (merge follow-opts
                                     {:follow (mapv principal keys)}
                                     (:follow opts)))
         f (reduce (fn [f [p board]] (head/attach f p board)) f boards)]
     {:withheld withheld :boards boards :a a :b b :follower f :now 0
      :events [] :last [] :install? install?})))


(defn- board-of
  ([w] (board-of w p1))
  ([w key] (get-in w [:boards (principal key)])))


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
  [w]
  (let [now (:now w)
        [a _] (dht/step (:a w) now)
        [b _] (dht/step (:b w) now)
        [f b events] (head/step (:follower w) b now)
        [f b more] (if (:install? w) (install-confirmed f b events) [f b []])
        events (into events more)]
    (-> w
        (assoc :a a :b b :follower f :now (+ now 10) :last events)
        (update :events into events))))


(defn- run
  "Tick `w` until `(done? w)` or `n` ticks pass, calling `(check w)`
   after every tick."
  ([w n done?] (run w n done? (fn [_] nil)))
  ([w n done? check]
   (loop [w w
          left n]
     (if (or (done? w) (zero? left))
       w
       (let [w (tick w)]
         (check w)
         (recur w (dec left)))))))


(defn- status
  ([w] (status w p1))
  ([w key] (get (head/heads (:follower w)) (principal key))))


(defn- installed-manifest
  ([w] (installed-manifest w p1))
  ([w key] (:manifest (status w key))))


(defn- installed?
  [m]
  (fn [w] (= m (installed-manifest w))))


(defn- settled?
  "Nothing is a candidate and the last observation is `trace`."
  ([trace] (settled? trace p1))
  ([trace key]
   (fn [w]
     (let [s (status w key)]
       (and (nil? (:candidate s)) (= trace (get-in s [:observed :trace])))))))


(defn- resolved
  [w n]
  (ld/resolve-name (:b w) (ld/authority {:principals [(:public p1)]}) n))


(defn- gets
  "The `:jing/get` requests `node`'s load client has appended."
  [node]
  (count (filter :jing/get (mesh/values (get-in node [:composition
                                                      :requests])))))


(defn- head-of!
  "Publish `history` into A's store; answers `{:manifest m :seq n :trace
   t}`, `t` signed under `key`."
  ([w history] (head-of! w history p1))
  ([w history key]
   (let [m (index! (dht/local (:a w)) history)
         n (dec (count history))]
     {:manifest m :seq n :trace (head/trace key m n)})))


(defn- show!
  "Append `trace` to `key`'s board, as its publisher's source shows it."
  ([w trace] (show! w trace p1))
  ([w trace key]
   (stream/append! (board-of w key) trace)
   w))


(defn- events-of
  [events kind]
  (filterv #(= kind (:yin.head/event %)) events))


(defn- loading-records
  [node]
  (filterv #(= :loading (:status (dht/load-status node %)))
           (keys (:loads node))))


(defn- close!
  [w]
  (dht/close! (:a w))
  (dht/close! (:b w)))


;; =============================================================================
;; The board and deposit!
;; =============================================================================

(defn- board-values
  [board]
  (mesh/values board))


(deftest deposit-refuses-and-appends-nothing
  (let [store (mem/create-content-mem)
        m2 (index! store (alib-history p1 2))
        other (index! store (moved-history p1 (alib-history p1 1) v2))
        m1 (index! store (alib-history p1 1))
        datoms #(index/read-datoms store %)
        board (head/board)
        ok (head/deposit! board p1 m2 (datoms m2))]
    (is (= :ok (:status ok)))
    (is (= 2 (head/seq-of (datoms m2))))
    (is (= [(:trace ok)] (board-values board)))
    (is (head/verify (:trace ok)))
    (doseq [[reason res] [[:yin.head/seq-collision
                           (head/deposit! board p1 other (datoms other))]
                          [:yin.head/seq-regression
                           (head/deposit! board p1 m1 (datoms m1))]
                          [:yin.head/empty (head/deposit! board p1 m2 [])]
                          [:yin.head/no-key
                           (head/deposit! board nil m2 (datoms m2))]]]
      (testing (str reason)
        (is (= :refused (:status res)) (pr-str res))
        (is (= reason (:reason res)))
        (is (= [(:trace ok)] (board-values board)) "nothing was appended")))
    (testing "the same head again appends nothing"
      (is (= ok (head/deposit! board p1 m2 (datoms m2))))
      (is (= 1 (count (board-values board)))))
    (testing "a higher sequence is appended, and the ring keeps one"
      (let [m3 (index! store (alib-history p1 3))
            res (head/deposit! board p1 m3 (datoms m3))]
        (is (= 3 (get-in res [:trace :yin.head/envelope :yin.head/seq])))
        (is (= [(:trace res)] (board-values board)))))))


;; =============================================================================
;; A reader given the board and the principal, and no manifest
;; =============================================================================

(deftest a-first-contact-reader-installs-sequence-zero-then-follows
  (let [w (world)
        a-store (dht/local (:a w))
        m0 (index! a-store (alib-history p1 0))
        dep0 (head/deposit! (board-of w) p1 m0 (index/read-datoms a-store m0))
        w (run w 300 (installed? m0))]
    (testing "the first index deposits sequence 0, and it is installed"
      (is (= 0 (get-in dep0 [:trace :yin.head/envelope :yin.head/seq])))
      (is (= m0 (installed-manifest w)) (pr-str (:events w)))
      (is (= 0 (:floor (status w))))
      (is (= v1 (:address (resolved w 'alib))))
      (is (contains? (set (ld/snapshots (:b w))) m0)))
    (testing "B fetched it over the mesh"
      (is (pos? (gets (:b w)))))
    (let [linked {'alib v1}
          names-before (ld/names (:b w) (ld/authority {:principals
                                                       [(:public p1)]}))
          m1 (index! a-store (moved-history p1 (alib-history p1 0) v2))
          dep1 (head/deposit! (board-of w) p1 m1 (index/read-datoms a-store m1))
          w (run w 300 (installed? m1))]
      (testing "A republishes: sequence 1 replaces it"
        (is (= 1 (get-in dep1 [:trace :yin.head/envelope :yin.head/seq])))
        (is (= m1 (installed-manifest w)))
        (is (= 1 (:floor (status w))))
        (is (= v2 (:address (resolved w 'alib))))
        (is (not (contains? (set (ld/snapshots (:b w))) m0)))
        (is (= [(:trace dep0) (:trace dep1)]
               (mapv :trace (events-of (:events w) :installed)))))
      (testing "moved names exactly the linked names whose address changed"
        (let [names (ld/names (:b w) (ld/authority {:principals
                                                    [(:public p1)]}))]
          (is (= [] (head/moved names-before linked)))
          (is (= [{:name 'alib :linked v1 :resolved v2}]
                 (head/moved names (assoc linked 'other v1 'nowhere v2))))))
      (testing "the reader's own HEAD is never written"
        (is (nil? (ld/head (:b w)))))
      (close! w))))


(deftest ten-deposits-between-two-polls-install-one-head-the-last
  (let [w (world {:follow {:poll-ticks 1000}})
        a-store (dht/local (:a w))
        deposit! (fn [n]
                   (let [m (index! a-store (alib-history p1 n))]
                     (head/deposit! (board-of w) p1 m
                                    (index/read-datoms a-store m))
                     m))
        first-head (deposit! 0)
        w (run w 300 (installed? first-head))
        _ (is (= first-head (installed-manifest w)))
        heads (mapv deposit! (range 1 11))
        before (count (events-of (:events w) :installed))
        w (run w 300 (installed? (peek heads)))]
    (is (= (peek heads) (installed-manifest w)))
    (is (= 10 (:floor (status w))))
    (is (= 1 (- (count (events-of (:events w) :installed)) before))
        "one head installed, the last")
    (close! w)))


;; =============================================================================
;; A candidate is not resolvable
;; =============================================================================

(deftest a-candidate-is-in-no-snapshot-set
  (let [w (world {:install? false})
        {:keys [manifest trace]} (head-of! w (alib-history p1 0))
        w (show! w trace)
        not-resolvable (fn [w]
                         (is (not (contains? (set (ld/snapshots (:b w)))
                                             manifest)))
                         (is (= :absent (:reason (resolved w 'alib)))))]
    (testing "while the candidate is loaded and not yet judged"
      (let [w (loop [w w left 300]
                (let [now (:now w)
                      [a _] (dht/step (:a w) now)
                      [b _] (dht/step (:b w) now)
                      w (assoc w :a a :b b)]
                  (if (or (zero? left)
                          (= :loaded (:status (dht/load-status b manifest))))
                    w
                    (let [[f b _] (head/step (:follower w) b now)]
                      (recur (assoc w :follower f :b b :now (+ now 10))
                             (dec left))))))]
        (is (= head/candidate-kind (:kind (dht/load-status (:b w) manifest))))
        (is (= :loaded (:status (dht/load-status (:b w) manifest))))
        (not-resolvable w)
        (is (= [] (dht/loaded-indexes (:b w))))
        (let [[f b events] (head/step (:follower w) (:b w) (:now w))
              w (assoc w :follower f :b b)]
          (testing "between :confirmed and install"
            (is (= [:confirmed] (mapv :yin.head/event events)))
            (not-resolvable w)
            (is (nil? (:installed (status w)))))
          (testing "install makes it resolvable"
            (let [[f b installed] (head/install f b (principal p1) trace)
                  w (assoc w :follower f :b b)]
              (is (= [:installed] (mapv :yin.head/event installed)))
              (is (= v1 (:address (resolved w 'alib))))
              (is (nil? (dht/load-status b manifest))
                  "installation released the candidate record")
              (close! w))))))))


;; =============================================================================
;; A wrong-sequence trace
;; =============================================================================

(defn- wrong-world
  "A at heads 10, 11 and 12 and the wrong trace X (sequence 1000000 over
   an index at 10 holding a newer assertion of alib); B installed at 10
   when `floor?`."
  [floor?]
  (let [w (world)
        h10 (head-of! w (alib-history p1 10))
        h11 (head-of! w (alib-history p1 11))
        h12 (head-of! w (alib-history p1 12))
        x-manifest (index! (dht/local (:a w))
                           (moved-history p1 (alib-history p1 9) vx))
        x (head/trace p1 x-manifest 1000000)
        w (if floor?
            (run (show! w (:trace h10)) 300 (installed? (:manifest h10)))
            w)]
    (when floor? (is (= 10 (:floor (status w)))))
    {:w w :h11 h11 :h12 h12 :x x :x-manifest x-manifest}))


(defn- never-through-x
  "alib never resolves to the address only a wrong trace's index asserts."
  [w]
  (is (not= vx (:address (resolved w 'alib)))))


(defn- show-and-settle
  [w trace]
  (run (show! w trace) 300 (settled? trace) never-through-x))


(deftest a-wrong-sequence-trace-is-rejected-in-every-order
  (doseq [floor? [true false]
          order [[:x 11 12] [11 :x 12] [11 12 :x] [:x-while-11-loads]]]
    (testing (str (if floor? "floor 10, " "no floor, ") order)
      (let [{:keys [w h11 h12 x x-manifest]} (wrong-world floor?)
            by {:x x 11 (:trace h11) 12 (:trace h12)}
            w (if (= [:x-while-11-loads] order)
                (let [w (tick (show! w (:trace h11)))
                      _ (is (= :loading
                               (get-in (status w) [:candidate :state])))
                      w (show-and-settle w x)]
                  (show-and-settle w (:trace h12)))
                (reduce (fn [w k] (show-and-settle w (by k))) w order))
            rejections #(count (filter (fn [e]
                                         (= :yin.head/seq-mismatch (:reason e)))
                                       (:events %)))]
        (is (= (:manifest h12) (installed-manifest w)) (pr-str (status w)))
        (is (= 12 (:floor (status w))))
        (is (= 1 (rejections w)) "X was loaded and rejected once")
        (is (= :yin.head/seq-mismatch (get-in (status w) [:refusal :reason]))
            "the last refusal was X's")
        (testing "X shown again is not loaded a second time"
          (let [gets-before (gets (:b w))
                w (run (show! w x) 20 (fn [_] false))]
            (is (= 1 (rejections w)))
            (is (nil? (dht/load-status (:b w) x-manifest)))
            (is (= gets-before (gets (:b w))))
            (is (= 12 (:floor (status w))))
            (close! w)))))))


;; =============================================================================
;; An unfinished load does not block
;; =============================================================================

(deftest an-unfinished-load-does-not-block-a-newer-head
  (let [w (world)
        h10 (head-of! w (alib-history p1 10))
        w (run (show! w (:trace h10)) 300 (installed? (:manifest h10)))
        h11 (head-of! w (alib-history p1 11))
        h12 (head-of! w (alib-history p1 12))
        ;; the mesh never answers for the manifest blob of head 11
        _ (reset! (:withheld w) #{(:manifest h11)})
        w (run (show! w (:trace h11)) 5 (fn [_] false))
        _ (is (= :loading (:status (dht/load-status (:b w) (:manifest h11)))))
        w (tick (show! w (:trace h12)))]
    (testing "11's record is gone in the step 12 was observed"
      (is (nil? (dht/load-status (:b w) (:manifest h11))))
      (is (= (:manifest h12) (get-in (status w) [:candidate :manifest]))))
    (let [w (run w 300 (installed? (:manifest h12)))]
      (is (= (:manifest h12) (installed-manifest w)))
      (is (= 12 (:floor (status w))))
      (close! w))))


;; =============================================================================
;; Alternating replay: the stated limit
;; =============================================================================

(deftest alternating-replay-keeps-safety-without-progress
  (let [w (world {:follow {:poll-ticks 30}})
        h10 (head-of! w (alib-history p1 10))
        w (run (show! w (:trace h10)) 300 (installed? (:manifest h10)))
        h11 (head-of! w (alib-history p1 11))
        h13 (head-of! w (alib-history p1 13))
        observed? (fn [trace]
                    #(= trace (get-in (status %) [:observed :trace])))
        turn (fn [w trace]
               ;; one turn: the source shows `trace` until the poll that
               ;; observes it, which releases the previous candidate's load
               ;; and starts this one's; the next poll is three steps on
               (let [w (run (show! w trace) 10 (observed? trace)
                            #(is (<= (count (loading-records (:b %))) 1)))]
                 (testing "the client holds nothing between turns"
                   (is (empty? (get-in w [:b :client :outstanding])))
                   (is (nil? (get-in w [:b :client :unsent]))))
                 w))]
    (testing "no fetch is answered: nothing installs, the floor stays 10"
      (reset! (:withheld w) (constantly true))
      (let [w (reduce turn w (take 20 (cycle [(:trace h11) (:trace h13)])))]
        (is (= 10 (:floor (status w))))
        (is (= (:manifest h10) (installed-manifest w)))
        (is (= 1 (count (events-of (:events w) :installed))))
        (testing "fetches answered: 11 and 13 install, and 11 is stale after"
          (reset! (:withheld w) (constantly false))
          (let [w (loop [w w
                         [t & more] (cycle [(:trace h11) (:trace h13)])
                         left 200]
                    (if (or (zero? left) (= 13 (:floor (status w))))
                      w
                      (recur (turn w t) more (dec left))))
                installs (mapv #(get-in % [:trace :yin.head/envelope
                                           :yin.head/seq])
                               (events-of (:events w) :installed))]
            (is (= 13 (:floor (status w))))
            (is (= [10 11 13] installs) "11 installs, then 13")
            (let [w (run (show! w (:trace h11)) 5 (fn [_] false))]
              (is (= :yin.head/stale (get-in (status w) [:observed :verdict])))
              (close! w))))))))


(deftest two-wrong-traces-alternating-are-walked-locally-and-never-resolve
  (let [w (world {:follow {:poll-ticks 30}})
        a-store (dht/local (:a w))
        x1 (head/trace p1 (index! a-store (moved-history p1 (alib-history p1 9)
                                                         vx))
                       500)
        x2 (head/trace p1 (index! a-store (moved-history p1 (alib-history p1 7)
                                                         vx))
                       600)
        mismatches #(count (filter (fn [e]
                                     (= :yin.head/seq-mismatch (:reason e)))
                                   (:events %)))
        turn (fn [w trace]
               (run (show! w trace) 10 (settled? trace) never-through-x))
        w (run (show! w x1) 300 (settled? x1) never-through-x)
        w (run (show! w x2) 300 (settled? x2) never-through-x)
        _ (is (= 2 (mismatches w)))
        gets-after-first (gets (:b w))
        w (reduce turn w (take 10 (cycle [x1 x2])))]
    (is (= 12 (mismatches w)) "each walked each turn")
    (is (= gets-after-first (gets (:b w))) "zero :jing/get after the first")
    (is (nil? (:installed (status w))))
    (close! w)))


;; =============================================================================
;; Unloadable candidates
;; =============================================================================

(deftest an-unloadable-candidate-keeps-the-head-and-retries-after-the-delay
  (let [w (world)
        h10 (head-of! w (alib-history p1 10))
        w (run (show! w (:trace h10)) 300 (installed? (:manifest h10)))
        nobody (jing/segment-key "a manifest nobody holds")
        bad (head/trace p1 nobody 11)
        w (run (show! w bad) 300 #(seq (events-of (:events %) :unloadable)))
        failed-at (:now w)
        unloadable (first (events-of (:events w) :unloadable))]
    (is (= bad (:trace unloadable)))
    (is (= :miss (get-in unloadable [:failure ::dht/failure])))
    (is (= (:manifest h10) (installed-manifest w)) "the previous head stays")
    (is (= bad (get-in (status w) [:candidate :trace])))
    (testing "started again only after the delay"
      (let [w (run w 300 #(= :loading (:status (dht/load-status (:b %)
                                                                nobody))))]
        (is (>= (- (:now w) failed-at) 100) (pr-str (:now w)))
        (is (= head/candidate-kind (:kind (dht/load-status (:b w) nobody))))
        (testing "and replaced at once by the next trace"
          (let [h12 (head-of! w (alib-history p1 12))
                w (tick (show! w (:trace h12)))]
            (is (nil? (dht/load-status (:b w) nobody)))
            (is (= (:manifest h12) (get-in (status w) [:candidate :manifest])))
            (let [w (run w 300 (installed? (:manifest h12)))]
              (is (= 12 (:floor (status w))))
              (close! w))))))))


(deftest a-failed-load-made-by-hand-is-restarted-as-an-index-load
  (let [w (world)
        staging (mem/create-content-mem)
        m (index! staging (alib-history p1 4))
        ;; B loads it by hand before any peer holds it: it fails
        w (update w :b dht/load-index m)
        w (run w 300 #(= :failed (:status (dht/load-status (:b %) m))))
        _ (is (= :failed (:status (dht/load-status (:b w) m))))
        ;; the blobs appear at A, and A's head names it
        _ (doseq [[address bs] ((:entries-fn staging))]
            ((:put-bytes-fn (dht/local (:a w))) address bs))
        trace (head/trace p1 m 4)
        w (run (show! w trace) 300 (installed? m))]
    (is (= m (installed-manifest w)))
    (is (seq (events-of (:events w) :unloadable)) "the failed record was read")
    (is (= dht/index-kind (:kind (dht/load-status (:b w) m)))
        "the record stays of the index kind")
    (is (= :loaded (:status (dht/load-status (:b w) m))))
    (is (= [m] (dht/loaded-indexes (:b w))))
    (close! w)))


(deftest a-load-index-by-hand-of-a-loading-candidate-is-a-kind-conflict
  (let [w (world)
        {:keys [manifest trace]} (head-of! w (alib-history p1 3))
        _ (reset! (:withheld w) (constantly true))
        w (tick (show! w trace))
        b (:b w)]
    (is (= head/candidate-kind (:kind (dht/load-status b manifest))))
    (is (= ::dht/kind-conflict
           (try (dht/load-index b manifest)
                nil
                (catch #?(:cljd Object :clj Exception :cljs :default) e
                  (::dht/refused (ex-data e))))))
    (reset! (:withheld w) (constantly false))
    (let [w (run w 300 (installed? manifest))
          b (dht/load-index (:b w) manifest)]
      (is (= :loading (:status (dht/load-status b manifest)))
          "it succeeds once the head is installed")
      (close! w))))


;; =============================================================================
;; Shared candidates: one record, two owners
;; =============================================================================

(defn- shared-world
  [opts]
  (let [w (world (merge {:keys [p1 p2]} opts))
        m (index! (dht/local (:a w)) (alib-history p1 5))]
    (assoc w :m m)))


(deftest shared-candidates-are-one-record-with-two-owners
  (testing "one record, both owners; both install; then it is gone"
    (let [w (shared-world {})
          m (:m w)
          w (-> w
                (show! (head/trace p1 m 5) p1)
                (show! (head/trace p2 m 5) p2)
                tick)]
      (is (= #{(principal p1) (principal p2)} (get-in w [:follower :owners m])))
      (is (= 1 (count (filter #(= m %) (keys (get-in w [:b :loads]))))))
      (let [w (run w 300 #(and (= m (installed-manifest % p1))
                               (= m (installed-manifest % p2))))
            _ (is (= m (installed-manifest w p2)))
            _ (is (nil? (dht/load-status (:b w) m)) "the record is gone")
            before (gets (:b w))
            [b _] (dht/step (dht/load-index (:b w) m) (:now w))]
        (is (= :loaded (:status (dht/load-status b m))))
        (is (= before (gets b)) "loaded by hand with zero :jing/get")
        (close! (assoc w :b b)))))
  (testing "P's candidate is replaced while it loads; Q installs from it"
    (let [w (shared-world {})
          m (:m w)
          _ (reset! (:withheld w) (constantly true))
          w (-> w
                (show! (head/trace p1 m 5) p1)
                (show! (head/trace p2 m 5) p2)
                tick)
          other (index! (dht/local (:a w)) (alib-history p1 6))
          w (tick (show! w (head/trace p1 other 6) p1))]
      (is (= :loading (:status (dht/load-status (:b w) m))) "still loading")
      (is (= #{(principal p2)} (get-in w [:follower :owners m])))
      (reset! (:withheld w) (constantly false))
      (let [w (run w 300 #(= m (installed-manifest % p2)))]
        (is (= m (installed-manifest w p2)))
        (close! w))))
  (testing "P is rejected :seq-mismatch, Q is right: removed at the 2nd release"
    (let [w (shared-world {:install? false})
          m (:m w)
          w (-> w
                (show! (head/trace p1 m 9) p1)
                (show! (head/trace p2 m 5) p2))
          w (run w 300 #(seq (events-of (:events %) :confirmed)))
          w (run w 5 #(= 1 (count (events-of (:events %) :refused))))]
      (is (= [:yin.head/seq-mismatch]
             (mapv :reason (events-of (:events w) :refused))))
      (is (= (principal p2) (:principal (first (events-of (:events w)
                                                          :confirmed)))))
      (is (= :loaded (:status (dht/load-status (:b w) m)))
          "the first release kept the record")
      (let [[f b _] (head/install (:follower w) (:b w) (principal p2)
                                  (head/trace p2 m 5))]
        (is (= m (:manifest (get (head/heads f) (principal p2)))))
        (is (nil? (dht/load-status b m)) "the second release removed it")
        (close! (assoc w :b b))))))


;; =============================================================================
;; Durable before used
;; =============================================================================

(deftest a-confirmed-head-is-used-only-after-install
  (let [w (world {:install? false :follow {:heads {:version 1 :heads {}}}})
        p (principal p1)
        h0 (head-of! w (alib-history p1 0))
        w (run (show! w (:trace h0)) 300 #(seq (events-of (:events %)
                                                          :confirmed)))
        [f b _] (head/install (:follower w) (:b w) p (:trace h0))
        old-records (head/records f p (:trace h0))
        w (assoc w :follower f :b b)
        h1 (head-of! w (moved-history p1 (alib-history p1 0) v2))
        w (run (show! w (:trace h1)) 300 #(= 2 (count (events-of (:events %)
                                                                 :confirmed))))
        new-records (head/records (:follower w) p (:trace h1))]
    (testing "after :confirmed and before install, nothing moved"
      (is (= 0 (:floor (status w))))
      (is (= (:trace h0) (:installed (status w))))
      (is (= [(:manifest h0)] (ld/snapshots (:b w))))
      (is (= v1 (:address (resolved w 'alib)))))
    (is (= {:version 1 :heads {p (:trace h1)}} new-records))
    (let [copy #(dht/join {:local (mem/create-content-mem
                                    ((:entries-fn (dht/local (:b w)))))})]
      (testing "composed again from the old records: at the old head"
        (let [[f node] (head/follow (copy) {:follow [p] :heads old-records})]
          (is (= 0 (:floor (get (head/heads f) p))))
          (is (= [(:manifest h0)] (ld/snapshots node)))
          (dht/close! node)))
      (testing "composed again from the new records: at the new head"
        (let [[f node] (head/follow (copy) (merge follow-opts
                                                  {:follow [p]
                                                   :heads new-records}))
              old-board (head/board)
              _ (stream/append! old-board (:trace h0))
              [f node events] (head/step (head/attach f p old-board) node 0)]
          (is (= 1 (:floor (get (head/heads f) p))))
          (is (= [(:manifest h1)] (ld/snapshots node)))
          (is (= v2 (:address (ld/resolve-name
                                node (ld/authority {:principals [(:public p1)]})
                                'alib))))
          (is (= 0 (gets node)) "zero :jing/get")
          (is (= [:yin.head/stale] (mapv :reason events))
              "the older trace is stale")
          (dht/close! node))))
    (testing "install of any other trace is refused"
      (let [[f b events] (head/install (:follower w) (:b w) p (:trace h0))]
        (is (= [[:refused :yin.head/not-confirmed]]
               (mapv (juxt :yin.head/event :reason) events)))
        (is (= f (:follower w)))
        (is (= b (:b w)))))
    (testing "install of the confirmed trace moves the floor"
      (let [[f _ events] (head/install (:follower w) (:b w) p (:trace h1))]
        (is (= [:installed] (mapv :yin.head/event events)))
        (is (= 1 (:floor (get (head/heads f) p))))))
    (close! w)))


(deftest follow-refuses-a-record-that-fails
  (let [node (dht/join {:local (mem/create-content-mem)})
        m (index! (dht/local node) (alib-history p1 2))
        good (head/trace p1 m 2)
        refused (fn [opts]
                  (try (head/follow node opts)
                       nil
                       (catch #?(:cljd Object :clj Exception :cljs :default) e
                         (:yin.head/refused (ex-data e)))))]
    (is (= :yin.head/unfollowed
           (refused {:follow [(principal p2)]
                     :heads {:version 1 :heads {(principal p1) good}}})))
    (is (= :yin.head/bad-record
           (refused {:follow [(principal p1)]
                     :heads {:version 1
                             :heads {(principal p1)
                                     (assoc-in good [:yin.head/envelope
                                                     :yin.head/seq]
                                               3)}}})))
    (is (= :yin.head/unloadable
           (refused {:follow [(principal p1)]
                     :heads {:version 1
                             :heads {(principal p1)
                                     (head/trace p1 (jing/segment-key "nowhere")
                                                 2)}}})))
    (is (= :yin.head/seq-mismatch
           (refused {:follow [(principal p1)]
                     :heads {:version 1 :heads {(principal p1)
                                                (head/trace p1 m 1)}}})))
    (is (= :yin.head/too-many
           (refused {:follow (mapv #(sign/principal (jing/sha256 (str %)))
                                   (range 65))
                     :heads :volatile})))
    (dht/close! node)))


;; =============================================================================
;; A malformed index is refused as data (review finding P1)
;; =============================================================================

(defn- malformed-index!
  "A hash-valid covered index in `store` whose rows have a `t` that is not
   an integer: the covered-index walk counts rows, so it completes."
  [store]
  (let [leaf (jing/materialize! store {:keys [[100 :x/y 1 0 0]
                                              [101 :x/y 1 "bad-t" 0]]})]
    (jing/materialize! store {:indexes {:eavt leaf :aevt leaf :avet leaf
                                        :vaet leaf}
                              :count 2
                              :branching-factor 32})))


(deftest a-malformed-index-is-refused-at-confirmation
  (let [w (world)
        h10 (head-of! w (alib-history p1 10))
        w (run (show! w (:trace h10)) 300 (installed? (:manifest h10)))
        bad (head/trace p1 (malformed-index! (dht/local (:a w))) 11)
        w (run (show! w bad) 300 #(seq (events-of (:events %) :refused)))]
    (is (= [{:yin.head/event :refused :principal (principal p1)
             :reason :yin.head/index-invalid :trace bad}]
           (events-of (:events w) :refused)))
    (is (= 10 (:floor (status w))) "the floor is unchanged")
    (is (= (:manifest h10) (installed-manifest w)) "nothing installed")
    (is (= 1 (count (events-of (:events w) :installed))))
    (is (nil? (get-in (status w) [:candidate])))
    (is (= :yin.head/index-invalid (get-in (status w) [:refusal :reason])))
    (testing "shown again it is not loaded again"
      (let [w (run (show! w bad) 10 (fn [_] false))]
        (is (= 1 (count (events-of (:events w) :refused))))
        (is (= 10 (:floor (status w))))
        (close! w)))))


(deftest a-malformed-index-refuses-follow-naming-the-principal
  (let [node (dht/join {:local (mem/create-content-mem)})
        bad (head/trace p1 (malformed-index! (dht/local node)) 0)
        data (try (head/follow node {:follow [(principal p1)]
                                     :heads {:version 1
                                             :heads {(principal p1) bad}}})
                  nil
                  (catch #?(:cljd Object :clj Exception :cljs :default) e
                    (ex-data e)))]
    (is (= {:yin.head/refused :yin.head/index-invalid
            :principal (principal p1)}
           data))
    (is (= {} (ld/installed-heads node)) "nothing installed")
    (dht/close! node)))


(defn- rows-index!
  "A hash-valid covered index in `store` holding exactly `rows`: the
   covered-index walk counts rows, so it completes whatever they are."
  [store rows]
  (let [leaf (jing/materialize! store {:keys rows})]
    (jing/materialize! store {:indexes {:eavt leaf :aevt leaf :avet leaf
                                        :vaet leaf}
                              :count (count rows)
                              :branching-factor 32})))


(def ^:private non-canonical-rows
  "Each case's one bad row, beside a good one: not a persisted local
   datom (`dao.datom/local-datom?`)."
  [[:four-slots [100 :x/y 1 0]]
   [:six-slots [100 :x/y 1 0 0 :extra]]
   [:negative-e [-1 :x/y 1 0 0]]
   [:non-integer-e ["e" :x/y 1 0 0]]
   [:unnamespaced-attribute [100 :y 1 0 0]]
   [:non-integer-m [100 :x/y 1 0 "m"]]])


(deftest non-canonical-rows-are-refused-at-confirmation
  (let [w (world)
        h10 (head-of! w (alib-history p1 10))
        w (run (show! w (:trace h10)) 300 (installed? (:manifest h10)))
        a-store (dht/local (:a w))
        w (reduce (fn [w [i [case-key row]]]
                    (let [bad (head/trace p1 (rows-index! a-store
                                                          [[99 :x/y 1 0 0]
                                                           row])
                                          (+ 11 i))
                          w (run (show! w bad) 300 (settled? bad))]
                      (testing (str case-key)
                        (is (= {:yin.head/event :refused
                                :principal (principal p1)
                                :reason :yin.head/index-invalid :trace bad}
                               (peek (events-of (:events w) :refused))))
                        (is (= 10 (:floor (status w))) "the floor is unchanged")
                        (is (= (:manifest h10) (installed-manifest w))
                            "nothing installed"))
                      w))
                  w
                  (map-indexed vector non-canonical-rows))]
    (is (= (count non-canonical-rows) (count (events-of (:events w) :refused))))
    (is (= [(:trace h10)] (mapv :trace (events-of (:events w) :confirmed)))
        "only head 10 was ever confirmed")
    (is (= 1 (count (events-of (:events w) :installed))))
    (close! w)))


(deftest non-canonical-rows-refuse-follow-naming-the-principal
  (doseq [[case-key row] non-canonical-rows]
    (testing (str case-key)
      (let [node (dht/join {:local (mem/create-content-mem)})
            bad (head/trace p1 (rows-index! (dht/local node)
                                            [[99 :x/y 1 0 0] row])
                            0)
            data (try (head/follow node {:follow [(principal p1)]
                                         :heads {:version 1
                                                 :heads {(principal p1) bad}}})
                      nil
                      (catch #?(:cljd Object :clj Exception :cljs :default) e
                        (ex-data e)))]
        (is (= {:yin.head/refused :yin.head/index-invalid
                :principal (principal p1)}
               data))
        (is (= {} (ld/installed-heads node)) "nothing installed")
        (dht/close! node)))))


;; =============================================================================
;; A failed record of another kind stays its owner's (Architect ruling 1)
;; =============================================================================

(deftest a-failed-module-record-is-left-until-its-owner-forgets-it
  (let [w (world)
        {:keys [manifest trace]} (head-of! w (alib-history p1 3))
        ;; a module load of that address, made by someone else, fails
        [b _] (dht/step (dht/load (:b w) manifest
                                  {:kind ld/module-kind
                                   :walk (fn [_]
                                           {::dht/walk :invalid :address nil
                                            :defect {:code :not-a-module}})})
                        (:now w))
        failure (:reason (dht/load-status b manifest))
        w (assoc w :b b :now (+ 10 (:now w)))
        _ (is (= :failed (:status (dht/load-status b manifest))))
        ;; well past the retry delay (100)
        w (run (show! w trace) 60 (fn [_] false))]
    (testing "the record is still there, of its kind, failed as it was"
      (is (= {:status :failed :kind ld/module-kind :fetched 0
              :reason failure}
             (dht/load-status (:b w) manifest))))
    (testing "the candidate was reported unloadable once, with its failure"
      (is (= [{:yin.head/event :unloadable :principal (principal p1)
               :trace trace :failure failure}]
             (events-of (:events w) :unloadable))))
    (is (nil? (installed-manifest w)))
    (testing "once its owner forgets it, the candidate loads and installs"
      (let [w (run (update w :b dht/forget manifest) 300 (installed? manifest))]
        (is (= manifest (installed-manifest w)))
        (is (= 3 (:floor (status w))))
        (close! w)))))


(defn- counting-store
  "A memory store counting every read of an address `@watched?` holds."
  [watched? reads]
  (let [inner (mem/create-content-mem)]
    (assoc inner :get-bytes-fn (fn [a not-found]
                                 (when (@watched? a) (swap! reads inc))
                                 ((:get-bytes-fn inner) a not-found)))))


(deftest a-loaded-record-of-another-kind-is-never-read
  (let [watched (atom #{})
        reads (atom 0)
        b-store (counting-store watched reads)
        w (world {:local b-store})
        {:keys [manifest trace]} (head-of! w (alib-history p1 3))
        ;; every blob of the index is already local to B, so a read of the
        ;; foreign record's address could confirm it
        _ (doseq [[address bs] ((:entries-fn (dht/local (:a w))))]
            ((:put-bytes-fn b-store) address bs))
        _ (reset! watched #{manifest})
        ;; someone else's module load at that address completes
        [b _] (dht/step (dht/load (:b w) manifest
                                  {:kind ld/module-kind
                                   :walk (fn [_]
                                           {::dht/walk :complete
                                            :value ::someone-elses})})
                        (:now w))
        record (dht/load-status b manifest)
        _ (is (= :loaded (:status record)))
        w (run (show! (assoc w :b b :now (+ 10 (:now w))) trace)
               60 (fn [_] false))]
    (testing "reported unloadable once, the record untouched and unread"
      (is (= [{:yin.head/event :unloadable :principal (principal p1)
               :trace trace
               :failure {:yin.head/foreign-kind ld/module-kind
                         :status :loaded}}]
             (events-of (:events w) :unloadable)))
      (is (empty? (events-of (:events w) :confirmed)))
      (is (= record (dht/load-status (:b w) manifest)))
      (is (= 0 @reads) "the follower never read the foreign record's datoms")
      (is (nil? (get-in w [:follower :owners manifest])) "no owner entry")
      (is (nil? (installed-manifest w))))
    (testing "once its owner forgets it, the candidate loads and installs"
      (let [w (run (update w :b dht/forget manifest) 300 (installed? manifest))]
        (is (= manifest (installed-manifest w)))
        (is (pos? @reads) "now walked, as a candidate")
        (close! w)))))


;; =============================================================================
;; A lost source
;; =============================================================================

(deftype ^:private SplitReader
  [cursor-ring next-ring]

  stream/IDaoStreamReader

  (cursor
    [_ anchor]
    (stream/cursor cursor-ring anchor))


  (next
    [_ c]
    (stream/next next-ring c)))


(deftest a-lost-source-is-reported-once-and-a-fresh-handle-reads-again
  (let [w (world)
        p (principal p1)
        h0 (head-of! w (alib-history p1 0))
        w (run (show! w (:trace h0)) 300 (installed? (:manifest h0)))]
    (doseq [[outcome lost-handle]
            [[:dao.stream/cursor-mismatch
              ;; its cursors are another ring's
              (->SplitReader (head/board) (board-of w))]
             [:dao.stream/end
              (let [ended (head/board)]
                (stream/append! ended (:trace h0))
                (stream/close! ended)
                ended)]]]
      (testing (str outcome)
        (let [w (assoc w :events [])
              w (update w :follower head/attach p lost-handle)
              w (run w 10 (fn [_] false))
              lost (events-of (:events w) :source-lost)]
          (is (= [{:yin.head/event :source-lost :principal p :outcome outcome}]
                 lost))
          (is (false? (:source? (status w))))
          (let [w (update w :follower head/attach p (board-of w))
                w (tick (assoc w :events []))]
            (is (= :duplicate (get-in (status w) [:observed :verdict])))
            (is (= (:trace h0) (get-in (status w) [:observed :trace])))
            (is (= [] (:events w)))))))
    (close! w)))


;; =============================================================================
;; Two nodes following each other
;; =============================================================================

(deftest two-nodes-following-each-other-reach-quiescence
  (let [net (mesh/mesh)
        a (node-at [2] {:publish? true} (mesh/seam net 1))
        b (node-at [1] {:publish? true} (mesh/seam net 2))
        [board-a board-b] [(head/board) (head/board)]
        ma (index! (dht/local a) (alib-history p1 3))
        mb (index! (dht/local b) (alib-history p2 2))
        _ (head/deposit! board-a p1 ma (index/read-datoms (dht/local a) ma))
        _ (head/deposit! board-b p2 mb (index/read-datoms (dht/local b) mb))
        [fa a] (head/follow a (assoc follow-opts :follow [(principal p2)]))
        [fb b] (head/follow b (assoc follow-opts :follow [(principal p1)]))
        fa (head/attach fa (principal p2) board-b)
        fb (head/attach fb (principal p1) board-a)
        step (fn [[node f] now]
               (let [[node _] (dht/step node now)
                     [f node events] (head/step f node now)
                     [f node more] (install-confirmed f node events)]
                 [[node f] (into events more)]))
        [sides events] (loop [sides [[a fa] [b fb]]
                              events []
                              now 0]
                         (if (> now 4000)
                           [sides events]
                           (let [stepped (mapv #(step % now) sides)]
                             (recur (mapv first stepped)
                                    (into events (mapcat second) stepped)
                                    (+ now 10)))))
        [[a fa] [b fb]] sides]
    (is (= mb (:manifest (get (head/heads fa) (principal p2)))))
    (is (= ma (:manifest (get (head/heads fb) (principal p1)))))
    (is (= 2 (count (events-of events :installed))) "one install each")
    (testing "quiescent: the last steps produce nothing, and no HEAD moved"
      (let [[[a _] e1] (step [a fa] 5000)
            [[b _] e2] (step [b fb] 5000)]
        (is (= [] e1))
        (is (= [] e2))
        (is (= 1 (count (board-values board-a))))
        (is (= 1 (count (board-values board-b))))
        (is (nil? (ld/head a)))
        (is (nil? (ld/head b)))
        (is (empty? (loading-records a)))
        (is (empty? (loading-records b)))
        (dht/close! a)
        (dht/close! b)))))
