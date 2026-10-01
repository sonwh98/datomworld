(ns yin.vm.linker.dht-test
  "Linker over DHT slice L2 (docs/design/yin.vm.linker.dht.md 4.1, 4.3,
   7.4, 10): the module closure loaded by address onto a `dao.space.dht`
   node, then linked over the node's own store with no request to any
   peer; failures as data; and the dependency-binding check over the
   reader's declared principals and loaded index snapshots.  The sockets
   are `dao.stream.datagram` host seams over the `dao.jing.dht` test
   mesh; time advances only by the readings each step is handed."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.dht :as jing.dht]
            [dao.jing.dht.mesh :as mesh]
            [dao.jing.mem :as mem]
            [dao.space.dht :as dht]
            [dao.space.dht-test :as dht-test]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.linker :as linker]
            [yin.vm.linker.closure-test :as ct]
            [yin.vm.linker.dht :as ld]
            [yin.vm.linker.publish :as publish]
            [yin.vm.linker.sign :as sign]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Running a linked image (the linker-manifest-test pattern)
;; =============================================================================

(def receiver
  {:primitives vm/primitives})


(defn- loaded-vm
  "A VM of `format-kw`'s own backend holding the linked image `res`, its
   lexical environment `env`."
  [format-kw res env]
  (let [contract (get-in res [:manifest :yin.module/contracts format-kw])]
    (case format-kw
      :yin.ast/code (ast-walker/vm-load-rows (tu/create-vm {:env env})
                                             (:value res) contract)
      :yin.semantic/code (semantic/load-vector (semantic/create-vm {:env env})
                                               (:value res) contract)
      :yin.debruijn.code (dvm/create-vm (:value res)
                                        (assoc receiver :contract contract
                                               :env env))
      :yin.debruijn.register (rvm/create-vm (:value res)
                                            (assoc receiver :contract contract
                                                   :env env)))))


(defn- run-image
  "The B0-normalized value and store of the linked image `res`."
  ([format-kw res] (run-image format-kw res {}))
  ([format-kw res env]
   (let [machine (vm/run (loaded-vm format-kw res env))]
     {:value (b0/normalize (vm/value machine))
      :store (b0/normalize (vm/store machine))})))


(def formats
  (mapv :format publish/code-formats))


;; =============================================================================
;; A mesh world: A publishes, two plain peers acknowledge, B reads
;; =============================================================================

(defn- peer!
  [net port]
  (let [c (mesh/join! net port {::jing.dht/publish? true
                                ::jing.dht/max-inbound-bytes (* 64 1024 1024)})]
    {:c c :s (jing.dht/state c)}))


(defn- node-at
  [net port peers opts]
  (dht/join (merge {:local (mem/create-content-mem)
                    :peers (mapv (fn [p] {:host mesh/host :port p}) peers)
                    :bind! (mesh/seam net port)}
                   opts)))


(defn- world
  "A at port 1 (`a-opts`), plain publishing peers at 2 and 3, and B at 4
   (`b-opts`), every node knowing every other."
  [a-opts b-opts]
  (let [net (mesh/mesh)]
    {:net net
     :a (node-at net 1 [2 3 4] (merge {:publish? true} a-opts))
     :b (node-at net 4 [1 2 3] b-opts)
     :peers (into (sorted-map) (map (fn [p] [p (peer! net p)])) [2 3])
     :now 0
     :events {:a [] :b []}}))


(defn- step-world
  [{:keys [a b peers now] :as w}]
  (let [[a ea] (dht/step a now)
        [b eb] (dht/step b now)
        peers (into (sorted-map)
                    (map (fn [[p {:keys [c s]}]]
                           (mesh/tick! c now)
                           [p {:c c :s (jing.dht/step s 256)}]))
                    peers)]
    (-> w
        (assoc :a a :b b :peers peers :now (+ now 10))
        (update-in [:events :a] into ea)
        (update-in [:events :b] into eb))))


(defn- run-world
  "Step `w` until `(done? w)` or the reading passes `limit`, calling
   `(check w)` after every step."
  ([w limit done?] (run-world w limit done? (fn [_] nil)))
  ([w limit done? check]
   (loop [w w]
     (if (or (done? w) (> (:now w) limit))
       w
       (let [w (step-world w)]
         (check w)
         (recur w))))))


(defn- event
  [events kind manifest]
  (some #(when (and (= kind (::dht/event %)) (= manifest (:manifest %))) %)
        events))


(defn- terminal?
  [node address]
  (contains? #{:loaded :failed} (:status (ld/module-status node address))))


(defn- gets
  "The `:jing/get` requests `node`'s load client has appended."
  [node]
  (count (filter :jing/get (mesh/values (get-in node [:composition :requests])))))


(defn- close-world!
  [w]
  (dht/close! (:a w))
  (dht/close! (:b w)))


;; =============================================================================
;; Load by address, then link with no fetch
;; =============================================================================

(deftest a-closure-loaded-by-address-links-on-all-four-formats-with-no-fetch
  (let [w (world {} {})
        published (ld/publish! (:a w) {:name 'base :ast ct/base-ast
                                       :exports #{'f} :requires {}
                                       :primitives ct/plus})
        m (:address published)
        _ (dht/announce! (:a w) m)
        w (run-world w 20000 #(event (get-in % [:events :a]) :published m))
        report (event (get-in w [:events :a]) :published m)]
    (testing "A's publication is acknowledged"
      (is (= :acknowledged (:result report)) (pr-str report)))
    (let [w (update w :b ld/load-module m)
          w (run-world w 60000 #(terminal? (:b %) m))
          b (:b w)
          loaded (event (get-in w [:events :b]) :loaded m)]
      (testing "B loads the whole closure over the network"
        (is (= :loaded (:status (ld/module-status b m))) (pr-str (ld/module-status b m)))
        (is (= ld/module-kind (:kind loaded)))
        (is (pos? (:fetched loaded)) "the blobs came from peers")
        (is (= (count (ct/entries (dht/local (:a w))))
               (:blobs (:value (ld/module-status b m))))))
      (let [before (gets b)]
        (doseq [f formats]
          (testing (name f)
            (let [mine (ld/link b m f)
                  theirs (publish/link-local (dht/local (:a w)) m
                                             (some #(when (= f (:format %)) %)
                                                   publish/code-formats))]
              (is (linker/ok? mine) (pr-str mine))
              (is (= (b0/normalize theirs) (b0/normalize mine))
                  "the link outcome is A's own")
              (is (= (run-image f theirs) (run-image f mine))
                  "and runs B0-equal to A's local run"))))
        (is (= before (gets b))
            "linking issued no :jing/get: the request ring is unchanged")))
    (close-world! w)))


(deftest linking-one-format-never-makes-a-load-loaded
  (testing "a missing image no peer holds fails the load with the miss cause"
    (let [source (mem/create-content-mem)
          res (ct/publish-base! source)
          m (:address res)
          h (get-in res [:identities :yin.debruijn.code])
          h-address (get-in res [:manifest :yin.module/index h])
          w (world {} {:local (ct/without source h-address)})
          w (update w :b ld/load-module m)
          seen (atom #{})
          w (run-world w 60000 #(terminal? (:b %) m)
                       (fn [w]
                         (swap! seen conj (:status (ld/module-status (:b w) m)))
                         (is (= :ok (:status (publish/link-local
                                               (dht/local (:b w)) m
                                               linker/semantic-format)))
                             "the semantic format links from what is local")
                         (is (= :yin.link.dht/not-loaded
                                (:reason (ld/link (:b w) m :yin.semantic/code))))))]
      (is (not (contains? @seen :loaded)))
      (is (= {:status :failed :kind ld/module-kind :fetched 0
              :reason {::dht/failure :miss :address h-address
                       :cause ::jing.dht/exhausted}}
             (ld/module-status (:b w) m)))
      (is (= :yin.link.dht/not-loaded (:reason (ld/link (:b w) m :yin.semantic/code))))
      (close-world! w))))


(deftest a-closure-only-a-non-publishing-node-holds-is-exhausted
  (let [w (world {:publish? false} {})
        res (ct/publish-base! (dht/local (:a w)))
        m (:address res)
        w (update w :b ld/load-module m)
        w (run-world w 60000 #(terminal? (:b %) m))]
    (is (= {::dht/failure :miss :address m :cause ::jing.dht/exhausted}
           (:reason (ld/module-status (:b w) m))))
    (is (= {::dht/event :load-failed :manifest m :kind ld/module-kind
            :reason {::dht/failure :miss :address m :cause ::jing.dht/exhausted}}
           (event (get-in w [:events :b]) :load-failed m)))
    (close-world! w)))


(deftest link-before-loaded-is-not-loaded
  (let [store (mem/create-content-mem)
        m (:address (ct/publish-base! store))
        node (dht/join {:local store})]
    (testing "with no load at all"
      (is (= {:status :refused :reason :yin.link.dht/not-loaded :address m :load nil}
             (ld/link node m :yin.semantic/code)))
      (is (= :yin.link.dht/not-loaded
             (:reason (ld/dependency-bindings node {} m)))))
    (testing "while loading"
      (let [node (ld/load-module node m)]
        (is (= {:status :refused :reason :yin.link.dht/not-loaded :address m
                :load {:status :loading :kind ld/module-kind :fetched 0 :fetching nil}}
               (ld/link node m :yin.semantic/code)))
        (testing "and once loaded, the same call links"
          (let [[node _] (dht/step node 0)]
            (is (= :loaded (:status (ld/module-status node m))))
            (is (= 0 (gets node)) "everything was already local")
            (is (linker/ok? (ld/link node m :yin.semantic/code)))))))
    (dht/close! node)))


(defn- hiding
  "`store` whose reads of any address `@hidden` holds answer not-found: a
   local store that lost a blob after the load walked it."
  [store hidden]
  (assoc store :get-bytes-fn
         (fn [address not-found]
           (if (contains? @hidden address)
             not-found
             ((:get-bytes-fn store) address not-found)))))


(deftest a-blob-lost-after-loaded-is-closure-incomplete
  (let [store (mem/create-content-mem)
        res (ct/publish-base! store)
        m (:address res)
        image (get-in res [:identities :yin.semantic/code])
        hidden (atom #{})
        [node _] (dht/step (ld/load-module (dht/join {:local (hiding store hidden)}) m)
                           0)]
    (is (= :loaded (:status (ld/module-status node m))))
    (is (linker/ok? (ld/link node m :yin.semantic/code)))
    (swap! hidden conj image)
    (is (= {:status :refused :reason :yin.link.dht/closure-incomplete
            :address image}
           (ld/link node m :yin.semantic/code))
        "the load still says :loaded; the link's :absent is a walker defect")
    (is (= :loaded (:status (ld/module-status node m))) "and is not retried")
    (dht/close! node)))


;; =============================================================================
;; Each closed code fails the load without any fetch or retry
;; =============================================================================

(deftest each-closed-code-fails-the-load-without-retry
  (doseq [[code store m] (conj (vec (remove #(= :parts-limit (first %))
                                            (ct/invalid-fixtures)))
                               (ct/deep-fixture))]
    (testing (name code)
      (let [node (ld/load-module (dht/join {:local store}) m)
            [node events] (dht/step node 0)
            status (ld/module-status node m)]
        (is (= :failed (:status status)) (pr-str status))
        (is (= :invalid (get-in status [:reason ::dht/failure])))
        (is (= code (get-in status [:reason :defect :code])) (pr-str status))
        (is (= 1 (count (filter #(= :load-failed (::dht/event %)) events))))
        (let [[node more] (reduce (fn [[n es] t]
                                    (let [[n e] (dht/step n t)] [n (into es e)]))
                                  [node []]
                                  [10 20 30])]
          (is (= status (ld/module-status node m)) "a failed load stays failed")
          (is (empty? more) "and reports nothing again")
          (is (= 0 (gets node)) "no fetch was ever asked"))))))


;; =============================================================================
;; Dependency bindings over two principals and two snapshots (7.4)
;; =============================================================================

(defn- assertion
  [key n address s]
  (let [env {:yin.module/op :assert
             :yin.module/name n
             :yin.module/manifest address
             :yin.module/asserted-by (sign/principal (:public key))
             :yin.module/seq s}]
    {:env env :proof (sign/sign-envelope (:seed key) env)}))


(defn- retraction
  [key of s]
  (let [env {:yin.module/op :retract
             :yin.module/of (jing/segment-key (:env of))
             :yin.module/asserted-by (sign/principal (:public key))
             :yin.module/seq s}]
    {:env env :proof (sign/sign-envelope (:seed key) env)}))


(defn- declare-keys
  [& keys]
  {:principals (into {}
                     (map (fn [k]
                            [(sign/principal (:public k))
                             {:proof :yin.module/signature
                              :key (:public k)
                              :verify sign/verify-envelope}]))
                     keys)})


(def p1 (sign/generate))
(def p2 (sign/generate))


(defn- reader
  "A solo reader holding base, base' (another address) and app, each
   index of `snapshots` (each a vector of signed envelopes) published and
   loaded, and app's closure loaded.  Answers `{:node n :app a :base b
   :base2 b2}`."
  [snapshots]
  (let [node (dht/join {:local (mem/create-content-mem)})
        store (dht/local node)
        base (:address (ct/publish-base! store))
        base2 (:address (ct/publish-base! store (ct/def! 'f (ct/lit 7))))
        app (:address (ct/publish-app! store base))
        indexes (mapv (fn [envs]
                        (dht-test/publish-datoms!
                          node
                          (vec (map-indexed
                                 (fn [i {:keys [env proof]}]
                                   {:db/id (+ datom/first-user-id i)
                                    :yin.module/envelope env
                                    :yin.module/proof proof})
                                 envs))))
                      (snapshots {:base base :base2 base2 :app app}))
        node (reduce dht/load-index node indexes)
        node (ld/load-module node app)
        [node _] (dht/step node 0)]
    (is (every? #(= :loaded (:status (dht/load-status node %))) (conj indexes app)))
    {:node node :app app :base base :base2 base2 :indexes indexes}))


(defn- only-binding
  [{:keys [node app]} authority]
  (let [bindings (ld/dependency-bindings node authority app)]
    (is (= 1 (count bindings)) (pr-str bindings))
    (first bindings)))


(deftest dependency-bindings-over-two-principals-and-two-snapshots
  (let [p1-base (fn [{:keys [base]}] (assertion p1 'base base 1))
        p2-app (fn [{:keys [app]}] (assertion p2 'app app 1))]
    (testing "matching: both snapshots loaded, both principals declared"
      (let [r (reader (fn [a] [[(p1-base a)] [(p2-app a)]]))
            b (only-binding r (declare-keys p1 p2))]
        (is (= {:module (:app r) :name 'base :pinned (:base r) :binding :ok} b))
        (testing "and app links and runs"
          (doseq [f [:yin.semantic/code :yin.debruijn.code :yin.debruijn.register
                     :yin.ast/code]]
            (is (linker/ok? (ld/link (:node r) (:app r) f)) (name f)))
          (let [base-run (vm/run (loaded-vm :yin.ast/code
                                            (publish/link-local
                                              (dht/local (:node r)) (:base r)
                                              linker/ast-format)
                                            {}))
                f (get (vm/store base-run) 'f)
                app-run (vm/run (loaded-vm :yin.ast/code
                                           (ld/link (:node r) (:app r) :yin.ast/code)
                                           {'base/f f}))]
            (is (= 43 (get (vm/store app-run) 'g)))))
        (dht/close! (:node r))))
    (testing "missing: P1's snapshot is not loaded"
      (let [r (reader (fn [a] [[(p2-app a)]]))
            b (only-binding r (declare-keys p1 p2))]
        (is (= {:module (:app r) :name 'base :pinned (:base r) :binding :absent
                :diagnostics []}
               b))
        (dht/close! (:node r))))
    (testing "missing: P1 is not declared"
      (let [r (reader (fn [a] [[(p1-base a)] [(p2-app a)]]))
            b (only-binding r (declare-keys p2))]
        (is (= :absent (:binding b)))
        (is (= [:undeclared-principal] (mapv :reason (:diagnostics b))))
        (is (= (sign/principal (:public p1))
               (:principal (first (:diagnostics b)))))
        (dht/close! (:node r))))
    (testing "conflicting: P1 has republished base at another address"
      (let [r (reader (fn [{:keys [base2] :as a}]
                        (let [old (p1-base a)]
                          [[old (retraction p1 old 2) (assertion p1 'base base2 3)]
                           [(p2-app a)]])))
            b (only-binding r (declare-keys p1 p2))]
        (is (= {:module (:app r) :name 'base :pinned (:base r) :binding :mismatch
                :resolved (:base2 r) :asserters [(sign/principal (:public p1))]}
               b))
        (dht/close! (:node r))))
    (testing "conflicting: P2 also asserts base at another address"
      (let [r (reader (fn [{:keys [base2] :as a}]
                        [[(p1-base a)]
                         [(p2-app a) (assertion p2 'base base2 2)]]))
            b (only-binding r (declare-keys p1 p2))]
        (is (= :ambiguous (:binding b)))
        (is (= (set [(:base r) (:base2 r)]) (set (:addresses b))))
        (is (= #{(sign/principal (:public p1)) (sign/principal (:public p2))}
               (set (:asserters b))))
        (dht/close! (:node r))))
    (testing "P1 and P2 both asserting the pinned address"
      (let [r (reader (fn [{:keys [base] :as a}]
                        [[(p1-base a)]
                         [(p2-app a) (assertion p2 'base base 2)]]))
            authority (declare-keys p1 p2)
            b (only-binding r authority)]
        (is (= :ok (:binding b)))
        (is (= #{(sign/principal (:public p1)) (sign/principal (:public p2))}
               (set (get-in (ld/names (:node r) authority)
                            [:names 'base :yin.link/provenance
                             :yin.module/asserted-by]))))
        (dht/close! (:node r))))
    (testing "a direct entry at the pinned address needs no signature"
      (let [r (reader (fn [_] []))
            b (only-binding r {:name-env {'base (:base r)}})]
        (is (= :ok (:binding b)))
        (is (= :composition (get-in (ld/names (:node r) {:name-env {'base (:base r)}})
                                    [:names 'base :yin.link/provenance])))
        (dht/close! (:node r))))))


(deftest an-envelope-in-two-snapshots-is-one-event
  (let [r (reader (fn [a]
                    (let [x (assertion p1 'base (:base a) 1)]
                      [[x] [x (assertion p1 'other (:base2 a) 2)]])))
        env (ld/names (:node r) (declare-keys p1))]
    (is (= 2 (count (ld/snapshots (:node r)))))
    (is (= (:base r) (get-in env [:names 'base :address])))
    (is (empty? (:diagnostics env)) "no equivocation, no replay")
    (is (= 2 (count (filter #(= :undeclared-principal (:reason %))
                            (:diagnostics (ld/names (:node r) {})))))
        "undeclared: the shared envelope and the other, one diagnostic each")
    (dht/close! (:node r))))
