(ns dao.space.query-test
  "Contract tests for dao.space.query: the pure, stateless match/q/pull
   library specified in docs/design/dao.space.md and docs/datomic-pull.md.
   Exercises the source shapes the design doc's Source Polymorphism section
   requires — an explicit published source (content store + manifest
   address), a collection of published sources (federated query, justified
   by ADR 0001's monoid homomorphism), a raw vector of datoms, and a raw
   vector of entity maps — plus mixes of them, and pull's schema-free
   entity-projection contract. Published fixtures are built with ringbuffer
   local+intake streams and publish-index!, then a DaoJing observer
   materializes the intake into a small test content handle before
   querying."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.stream :as ds]
            [dao.stream.ringbuffer]))


;; ---------------------------------------------------------------------------
;; Published-source fixtures
;; ---------------------------------------------------------------------------

(defn- content-handle
  "In-memory content store for tests: a map keyed by content address, the
   shape a DaoJing observer materializes into."
  []
  (let [store (atom {})]
    {:store store,
     :put-content-fn (fn [address payload]
                       (if (contains? @store address)
                         :present
                         (do (swap! store assoc address payload) :inserted))),
     :get-content-fn (fn [address not-found] (get @store address not-found))}))


(defn- recording-handle
  "A content handle that records every :get-content-fn read, for asserting
   lazy vs eager traversal."
  []
  (let [store (atom {})
        reads (atom [])]
    {:store store,
     :reads reads,
     :put-content-fn (fn [address payload]
                       (if (contains? @store address)
                         :present
                         (do (swap! store assoc address payload) :inserted))),
     :get-content-fn (fn [address not-found]
                       (swap! reads conj address)
                       (get @store address not-found))}))


(defn- open-local
  "Open a ringbuffer local (agent) stream pre-loaded with datoms."
  [datoms]
  (let [s (ds/open! {:type :ringbuffer})]
    (doseq [d datoms] (ds/append! s d))
    s))


(defn- open-intake
  "Open a ringbuffer intake stream with capacity large enough for the
   multi-node tests."
  []
  (ds/open! {:type :ringbuffer, :capacity 4096}))


(defn- materialize!
  "Drain intake streams through a dao.jing observer into a content store;
   publication only acknowledges intake append, so fixtures must run the
   observer before querying. Returns the store."
  ([intakes] (materialize! intakes (content-handle)))
  ([intakes handle]
   (loop [st (jing/observer-state intakes)]
     (let [r (jing/observe-step! handle st)]
       (case (:signal r)
         :ok (recur (:state r))
         :blocked handle
         :end handle
         :daostream/gap (throw (ex-info "test observer hit a gap"
                                        {:result r})))))))


(defn- publish!
  "Publish datoms into a fresh content store and materialize them through
   the DaoJing observer; returns {:content-store handle
   :manifest-address addr}."
  ([datoms] (publish! datoms nil))
  ([datoms opts]
   (let [local (open-local datoms)
         intake (open-intake)
         {:keys [manifest-address]} (index/publish-index! local [intake] opts)
         handle (materialize! [intake])]
     {:content-store handle, :manifest-address manifest-address})))


(defn- publish-into!
  "Publish datoms and materialize them into an existing content store;
   returns the manifest address."
  [handle datoms]
  (let [local (open-local datoms)
        intake (open-intake)
        {:keys [manifest-address]} (index/publish-index! local [intake])]
    (materialize! [intake] handle)
    manifest-address))


(defn- source
  "A published query source over datoms."
  ([datoms] (source datoms nil))
  ([datoms opts]
   (let [{:keys [content-store manifest-address]} (publish! datoms opts)]
     (query/published-source content-store manifest-address))))


;; ---------------------------------------------------------------------------
;; match / q over a raw vector of datoms
;; ---------------------------------------------------------------------------

(def sample-datoms
  [[1 :work/status :todo 0 1] [1 :work/task "write tests" 0 1]
   [2 :work/status :done 0 1] [2 :work/task "ship it" 0 1]])


(deftest match-over-raw-datoms
  (testing "a positional template with _ wildcards returns matching datoms"
    (is (= #{[1 :work/status :todo 0 1] [2 :work/status :done 0 1]}
           (set (query/match sample-datoms ['_ :work/status '_]))))
    (is (= #{[1 :work/status :todo 0 1]}
           (set (query/match sample-datoms [1 :work/status '_]))))
    (is (= [] (query/match sample-datoms [99 '_ '_])))))


(deftest q-over-raw-datoms
  (testing "find/where Datalog over a plain datom vector"
    (is (= #{[1 "write tests"] [2 "ship it"]}
           (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                    sample-datoms)))
    (is (= #{[1]}
           (query/q '[:find ?id :where [?id :work/status :todo]
                      [?id :work/task ?task]]
                    sample-datoms)))))


(deftest q-single-clause-is-match
  (testing "a single-clause q agrees with match, per dao.space.md's match ⊂ q"
    (is (= #{[1] [2]}
           (query/q '[:find ?id :where [?id :work/status _]] sample-datoms)))))


;; ---------------------------------------------------------------------------
;; match / q over a raw vector of entity maps
;; ---------------------------------------------------------------------------

(deftest entity-maps-normalize-to-datoms
  (testing "each map becomes one datom per k/v pair, using its explicit :db/id"
    (let [source [{:db/id 1, :work/status :todo, :work/task "a"}
                  {:db/id 2, :work/status :done, :work/task "b"}]]
      (is (= #{["a"] ["b"]}
             (query/q '[:find ?task :where [_ :work/task ?task]] source)))))
  (testing "a numeric :db/id is used verbatim"
    (let [source [{:db/id 42, :work/status :todo}]]
      (is (= [[42 :work/status :todo 0 1]] (query/source->datoms source)))))
  (testing "a symbolic :db/id is used verbatim and joins across maps"
    (let [source [{:db/id :e1, :work/status :todo}
                  {:db/id :e1, :work/task "a"}]]
      (is (= #{[:e1 "a"]}
             (query/q '[:find ?id ?task :where [?id :work/status :todo]
                        [?id :work/task ?task]]
                      source)))))
  (testing "empty input" (is (empty? (query/source->datoms []))))
  (testing "explicit-ID ordering is preserved"
    (let [source [{:db/id :a, :work/task "a"} {:db/id :b, :work/task "b"}]
          datoms (query/source->datoms source)]
      (is (= :a (nth (first datoms) 0)))
      (is (= :b (nth (second datoms) 0))))))


(deftest entity-map-sources-require-explicit-db-id
  (testing "a top-level entity map without :db/id throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"explicit :db/id"
          (query/source->datoms {:work/status :todo})))
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"explicit :db/id"
          (query/q '[:find ?task :where [_ :work/task ?task]]
                   {:work/task "a"}))))
  (testing "any :db/id-less map in a top-level collection throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"explicit :db/id"
          (query/q '[:find ?task :where [_ :work/task ?task]]
                   [{:db/id 1, :work/task "a"}
                    {:work/task "b"}]))))
  (testing "a :db/id-less map in a mixed collection throws"
    (let [source-a (source [[1 :work/status :todo 0 1]])]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"explicit :db/id"
            (query/q '[:find ?id :where
                       [?id :work/status :todo]]
                     [source-a {:work/status :todo}])))))
  (testing "a :db/id-less map nested in a sub-collection throws"
    (let [source-a (source [[1 :work/status :todo 0 1]])]
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"explicit :db/id"
            (query/q '[:find ?id :where
                       [?id :work/status :todo]]
                     [source-a [{:work/status :todo}]])))))
  (testing "a purely nested entity-map collection without :db/id throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"explicit :db/id"
          (query/source->datoms [[{:work/status :todo}]])))))


(deftest raw-datom-classification-is-arity-based
  (testing "nested d5 datom collections flatten in order"
    (is (= [[1 :work/status :todo 0 1] [2 :work/status :done 0 1]]
           (query/source->datoms [[[1 :work/status :todo 0 1]]
                                  [[2 :work/status :done 0 1]]]))))
  (testing "nested d6 datom collections flatten in order"
    (is (= [[1 :work/status :todo 0 1 :ns/a] [2 :work/status :done 0 1 :ns/b]]
           (query/source->datoms [[[1 :work/status :todo 0 1 :ns/a]]
                                  [[2 :work/status :done 0 1 :ns/b]]]))))
  (testing "a flat d5 datom vector is passed through unchanged"
    (is (= sample-datoms (query/source->datoms sample-datoms))))
  (testing "a nested collection of exactly five maps reads as one d5 datom"
    ;; Precedence, not full recognition: arity 5 is a datom tuple, so five
    ;; maps in a row are the e/a/v/t/m slots of a single datom, not an
    ;; entity-map collection. Pinned here so the classifier's precedence is
    ;; explicit rather than silently elided.
    (is (= [[{:a 1} {:b 2} {:c 3} {:d 4} {:e 5}]]
           (query/source->datoms [[{:a 1} {:b 2} {:c 3} {:d 4} {:e 5}]])))))


(deftest match-over-entity-maps
  (let [source [{:db/id 1, :work/status :todo, :work/task "a"}]]
    (is (= 2 (count (query/match source ['_ '_ '_]))))
    (is (= 1 (count (query/match source ['_ :work/status :todo]))))))


;; ---------------------------------------------------------------------------
;; Source descriptor validation
;; ---------------------------------------------------------------------------

(deftest published-source-validates-its-coordinates
  (testing "content-store must be a map with a function :get-content-fn"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"content-store handle"
          (query/published-source :not-a-store
                                  (jing/segment-key {:a 1}))))
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"content-store handle"
          (query/published-source {:get-content-fn :not-fn}
                                  (jing/segment-key {:a 1}))))
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"content-store handle"
          (query/published-source {}
                                  (jing/segment-key {:a 1})))))
  (testing "manifest-address must be a :segment/sha256-... content address"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"manifest address"
          (query/published-source (content-handle)
                                  :root/not-an-address)))
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"manifest address"
          (query/published-source (content-handle)
                                  "not-a-keyword"))))
  (testing "a valid coordinate returns a plain descriptor with private keys"
    (let [h (content-handle)
          address (jing/segment-key {:a 1})]
      (is (= {:dao.space.query/content-store h,
              :dao.space.query/manifest-address address}
             (query/published-source h address))))))


;; ---------------------------------------------------------------------------
;; match / q / pull over a published source
;; ---------------------------------------------------------------------------

(deftest q-over-a-published-source
  (testing
    "a published source names the content store and manifest address explicitly"
    (let [{:keys [content-store manifest-address]} (publish! sample-datoms)
          source (query/published-source content-store manifest-address)]
      (is (= #{[1 "write tests"] [2 "ship it"]}
             (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                      source))))))


(deftest bare-content-store-handle-has-no-implicit-source
  (testing
    "a bare content handle cannot imply which published manifest belongs to a query"
    (let [{:keys [content-store manifest-address]} (publish! sample-datoms)]
      (is (= #{[1 "write tests"] [2 "ship it"]}
             (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                      (query/published-source content-store manifest-address))))
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"explicit published source"
            (query/q '[:find ?id :where [?id _ _]]
                     content-store)))
      (is (thrown-with-msg? #?(:cljs js/Error
                               :cljd Object
                               :default Exception)
                            #"explicit published source"
            (query/match content-store ['_ '_ '_]))))))


(deftest match-over-a-published-source
  (let [source (source sample-datoms)]
    (is (= 1 (count (query/match source [1 :work/status '_]))))
    (is (= [[1 :work/status :todo 0 1]] (query/match source ['_ '_ :todo]))
        "v-only match exercises the restored VAET of the published index")))


;; A v-only match against a raw vector only exercises the in-memory VAET
;; that index-datoms builds. Publishing first makes the v-only branch reach
;; the lazily-restored VAET (fold's published-manifest guard) — the only way
;; to prove the persisted-VAET path works.
(deftest v-only-match-reaches-the-restored-vaet
  (let [source (source sample-datoms)]
    (is (= #{[1 :work/status :todo 0 1]}
           (set (query/match source ['_ '_ :todo]))))
    (is (= 1
           (count (query/q '[:find ?e :where [?e :work/status :todo]]
                           source))))))


(deftest empty-published-source-yields-no-datoms
  (testing
    "an empty published manifest (nil roots) contributes nothing, not an error"
    (let [source (source [])]
      (is (= [] (query/match source ['_ '_ '_])))
      (is (= #{} (query/q '[:find ?e :where [?e _ _]] source)))
      (is (= [] (query/source->datoms source))))))


(deftest pull-over-a-published-source
  (let [source (source [[1 :name "Alice" 1 1] [1 :age 30 1 1]
                        [2 :name "Bob" 1 1]])]
    (is (= {:db/id 1, :name "Alice", :age 30}
           (query/pull source 1 [:name :age])))
    (is (= [{:db/id 1, :name "Alice"} {:db/id 2, :name "Bob"} {:db/id 999}]
           (query/pull-many source [1 2 999] [:name])))))


;; ---------------------------------------------------------------------------
;; match / q over a pool of published sources (federated)
;; ---------------------------------------------------------------------------

(deftest q-over-multiple-published-sources
  (testing
    "a collection of stores folds and merges, per ADR 0001's monoid
           homomorphism: index(S1 ⊎ S2) = merge(index(S1), index(S2))"
    (let [source-a (source [[1 :work/status :todo 0 1] [1 :work/task "a" 0 1]])
          source-b (source [[2 :work/status :done 0 1] [2 :work/task "b" 0 1]])]
      (is (= #{[1 "a"] [2 "b"]}
             (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                      [source-a source-b])))
      (testing "querying each source alone yields a strict subset"
        (is (= #{[1 "a"]}
               (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                        source-a)))))))


(deftest match-over-multiple-published-sources
  (let [source-a (source [[1 :work/status :todo 0 1]])
        source-b (source [[2 :work/status :todo 0 1]])]
    (is (= #{[1 :work/status :todo 0 1] [2 :work/status :todo 0 1]}
           (set (query/match [source-a source-b] ['_ :work/status :todo]))))))


(deftest q-over-multiple-addresses-of-one-store
  (testing
    "one materialized store may hold several manifests; each address is its
           own source, because source identity is the store+address coordinate"
    (let [h (content-handle)
          addr-a (publish-into! h
                                [[1 :work/status :todo 0 1]
                                 [1 :work/task "a" 0 1]])
          addr-b (publish-into! h
                                [[2 :work/status :done 0 1]
                                 [2 :work/task "b" 0 1]])
          source-a (query/published-source h addr-a)
          source-b (query/published-source h addr-b)]
      (is (not= addr-a addr-b))
      (is (= #{[1 "a"]}
             (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                      source-a)))
      (is (= #{[1 "a"] [2 "b"]}
             (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                      [source-a source-b]))))))


;; ---------------------------------------------------------------------------
;; Namespace slot (d6): [e a v t m ns]
;; ---------------------------------------------------------------------------
;; ns goes last so [e a v t m] stays a literal prefix: existing accessors,
;; comparators and datom literals are untouched, and eliding ns is plain
;; truncation rather than an encoding convention.
;;
;; Both streams below independently assign entity 1025 — the collision the
;; slot exists to prevent. Stream beta also knows Alice, under its own local
;; id 1026.

(def ^:private ns-a :ns/alpha)


(def ^:private ns-b :ns/beta)


(def cross-stream-datoms
  [[1025 :person/email "alice@example.com" 0 1 ns-a]
   [1025 :person/name "Alice" 0 1 ns-a]
   [1025 :person/email "bob@example.com" 0 1 ns-b]
   [1025 :person/name "Bob" 0 1 ns-b]
   [1026 :person/email "alice@example.com" 0 1 ns-b]
   [1026 :person/nick "Ali" 0 1 ns-b]])


(deftest ns-slot-scopes-joins-within-one-stream
  (testing "a repeated ?ns keeps the join inside a single stream"
    ;; _ skips t and m to reach ns — the shape Datomic users already write
    ;; to reach `added`: [?e :attr ?v _ ?op].
    (is (= #{[ns-a 1025 "Alice"]}
           (query/q '[:find ?ns ?e ?name :where
                      [?e :person/email "alice@example.com" _ _ ?ns]
                      [?e :person/name ?name _ _ ?ns]]
                    cross-stream-datoms)))))


(deftest ns-slot-permits-deliberate-cross-stream-joins
  (testing "distinct ?ns vars join on a shared value, across streams"
    (is (= #{[ns-a 1025 ns-b 1026] [ns-b 1026 ns-a 1025]}
           (query/q '[:find ?ns1 ?e1 ?ns2 ?e2 :where
                      [?e1 :person/email ?email _ _ ?ns1]
                      [?e2 :person/email ?email _ _ ?ns2] [(not= ?ns1 ?ns2)]]
                    cross-stream-datoms)))))


(deftest unscoped-clauses-conflate-streams
  (testing
    "without ?ns, entity 1025 unifies across streams — a fabricated
            person whose email came from one stream and name from another.
            This asserts the WRONG result deliberately, pinning the hazard;
            invert it once the multi-source validation rule lands."
    (is (contains? (query/q '[:find ?e ?email ?name :where
                              [?e :person/email ?email] [?e :person/name ?name]]
                            cross-stream-datoms)
                   [1025 "alice@example.com" "Bob"]))))


(deftest ns-in-negation-assumes-arity-homogeneous-sources
  (testing "a free ?ns inside not is caught by the existing bound-vars guard"
    (is (thrown-with-msg?
          #?(:cljs js/Error
             :cljd Object
             :default Exception)
          #"inside not must be bound"
          (query/q '[:find ?e :where [?e :name _] (not [?e :claim _ _ _ ?ns])]
                   [[1 :name "Alice" 0 1] [1 :claim "c" 0 1]]))))
  (testing
    "a bound ?ns inside not silently ignores datoms that carry no ns —
            entity 1 HAS a claim, but the claim datom is a 5-tuple, so the
            slot-5 template exceeds its arity and cannot match. Correct only
            when a source is arity-homogeneous, which nothing enforces: this
            pins the assumption rather than endorsing the mixed case."
    (let [mixed [[1 :name "Alice" 0 1 :ns/a] [2 :name "Bob" 0 1 :ns/a]
                 [1 :claim "c" 0 1]] ; 5-tuple: no ns slot
          homogeneous [[1 :name "Alice" 0 1 :ns/a] [2 :name "Bob" 0 1 :ns/a]
                       [1 :claim "c" 0 1 :ns/a]]
          q '[:find ?e :where [?e :name _ _ _ ?ns] (not [?e :claim _ _ _ ?ns])]]
      (is (= #{[1] [2]} (query/q q mixed)) "false negative on mixed arity")
      (is (= #{[2]} (query/q q homogeneous)) "correct when arity is uniform"))))


(deftest local-queries-ignore-the-ns-slot
  (testing
    "5-tuple datoms and 3-element clauses are unaffected: [e a v t m]
            is a literal prefix of [e a v t m ns]"
    (is (= #{[1 "write tests"] [2 "ship it"]}
           (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                    sample-datoms)))))


;; ---------------------------------------------------------------------------
;; Mixed sources
;; ---------------------------------------------------------------------------

(deftest mixed-published-and-raw-collection
  (testing "a published source and a raw datom vector fold together"
    (let [source-a (source [[1 :work/status :todo 0 1]])]
      (is (= #{[1] [2]}
             (query/q '[:find ?id :where [?id :work/status :todo]]
                      [source-a [[2 :work/status :todo 0 1]]])))))
  (testing "published sources and raw entity maps remain distinct map shapes"
    (let [source-a (source [[1 :work/status :todo 0 1]])]
      (is (= #{[1] [2] [3]}
             (query/q '[:find ?id :where [?id :work/status :todo]]
                      [source-a {:db/id 2, :work/status :todo}
                       {:db/id 3, :work/status :todo}]))))))


;; ---------------------------------------------------------------------------
;; as-of
;; ---------------------------------------------------------------------------

(deftest as-of-bounds-visible-datoms
  (testing "as-of excludes datoms with t greater than the bound"
    (let [datoms [[1 :work/status :todo 0 1] [1 :work/status :done 5 1]]]
      (is (=
            #{[:todo]}
            (query/q '[:find ?v :where [1 :work/status ?v]] datoms {:as-of 0})))
      (is (= #{[:todo] [:done]}
             (query/q '[:find ?v :where [1 :work/status ?v]]
                      datoms
                      {:as-of 5}))))))


(deftest match-respects-as-of
  (let [datoms [[1 :work/status :todo 0 1] [1 :work/status :done 5 1]]]
    (is (= 1 (count (query/match datoms [1 :work/status '_] {:as-of 0}))))
    (is (= 2 (count (query/match datoms [1 :work/status '_] {:as-of 5}))))))


(deftest published-source-as-of-takes-the-eager-path
  (testing
    "as-of over a published source falls back to the eager walk and
          stays correct, bounded to t <= as-of"
    (let [source (source [[1 :work/status :todo 0 1]
                          [1 :work/status :done 5 1]])]
      (is (=
            #{[:todo]}
            (query/q '[:find ?v :where [1 :work/status ?v]] source {:as-of 0})))
      (is (=
            #{[:todo] [:done]}
            (query/q '[:find ?v :where [1 :work/status ?v]] source {:as-of 5})))
      (is (= 1 (count (query/match source [1 :work/status '_] {:as-of 0}))))
      (is (= 2 (count (query/match source [1 :work/status '_] {:as-of 5})))))))


(deftest lazy-point-lookup-faults-only-the-seek-path
  (testing
    "a single published source with no as-of restores lazily: a point
          lookup reads only the seek path, never the whole graph; the same
          lookup under an as-of bound takes the eager path and reads more"
    (let [datoms (mapv (fn [i] [i :work/status :todo 0 1]) (range 300))
          local (open-local datoms)
          intake (open-intake)
          handle (recording-handle)
          {:keys [manifest-address]}
          (index/publish-index! local [intake] {:branching-factor 16})]
      (materialize! [intake] handle)
      (reset! (:reads handle) [])
      (let [source (query/published-source handle manifest-address)
            lazy (query/match source [42 :work/status '_])]
        (is (= 1 (count lazy)))
        (is (= [42 :work/status :todo 0 1] (first lazy)))
        (is (pos? (count @(:reads handle)))
            "a lazy restore reads at least the seek path")
        (is (< (count @(:reads handle)) (count @(:store handle)))
            "a lazy restore never faults every stored segment")
        (let [lazy-reads (count @(:reads handle))]
          (reset! (:reads handle) [])
          (let [eager (query/match source [42 :work/status '_] {:as-of 1})]
            (is (= 1 (count eager)))
            (is (= [42 :work/status :todo 0 1] (first eager)))
            (is (> (count @(:reads handle)) lazy-reads)
                "as-of bounds read the EAVT graph eagerly")))))))


;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(deftest unrecognized-source-throws
  (is (thrown? #?(:cljs js/Error
                  :cljd Object
                  :default Exception)
        (query/q '[:find ?e :where [?e _ _]] 42))))


;; ---------------------------------------------------------------------------
;; :in bindings
;; ---------------------------------------------------------------------------

(deftest in-bindings-test
  (testing "scalar binding"
    (is (= #{[1]}
           (query/q '[:find ?e :in $ ?name :where [?e :name ?name]]
                    [[1 :name "Alice" 0 1] [2 :name "Bob" 0 1]]
                    "Alice"))))
  (testing "collection binding"
    (is (= #{["Alice"] ["Bob"]}
           (query/q '[:find ?name :in $ [?id ...] :where [?id :name ?name]]
                    [[1 :name "Alice" 0 1] [2 :name "Bob" 0 1]
                     [3 :name "Charlie" 0 1]]
                    [1 2]))))
  (testing "tuple binding"
    (is (= #{[1]}
           (query/q '[:find ?e :in $ [?name ?age] :where [?e :name ?name]
                      [?e :age ?age]]
                    [[1 :name "Alice" 0 1] [1 :age 30 0 1] [2 :name "Bob" 0 1]
                     [2 :age 30 0 1]]
                    ["Alice" 30]))))
  (testing "relation binding"
    (is (= #{[1] [2]}
           (query/q '[:find ?e :in $ [[?name ?age]] :where [?e :name ?name]
                      [?e :age ?age]]
                    [[1 :name "Alice" 0 1] [1 :age 30 0 1] [2 :name "Bob" 0 1]
                     [2 :age 40 0 1] [3 :name "Charlie" 0 1] [3 :age 50 0 1]]
                    [["Alice" 30] ["Bob" 40]]))))
  (testing "multiple db sources"
    (is (= #{[1 "Alice" 30] [2 "Bob" 40]}
           (query/q '[:find ?e ?name ?age :in $a $b :where [$a ?e :name ?name]
                      [$b ?e :age ?age]]
                    [[1 :name "Alice" 0 1] [2 :name "Bob" 0 1]]
                    [[1 :age 30 0 1] [2 :age 40 0 1]])))))


;; ---------------------------------------------------------------------------
;; current-state resolution
;; ---------------------------------------------------------------------------

(deftest current-state-resolution-test
  (testing "latest t supersedes older t, retracted facts are dropped"
    (let [datoms [[1 :color "red" 1 1]     ; asserted at t=1
                  [1 :color "red" 2 0]     ; retracted at t=2
                  [1 :color "blue" 2 1]    ; asserted at t=2
                  [2 :status "active" 1 1] ; asserted at t=1
                  [2 :status "active" 3 0]]]
      ;; For entity 1, "red" is retracted and "blue" is asserted
      (is (= #{["blue"]} (query/q '[:find ?c :where [1 :color ?c]] datoms)))
      ;; For entity 2, "active" is retracted entirely
      (is (= #{} (query/q '[:find ?s :where [2 :status ?s]] datoms)))
      ;; With as-of 1, "red" is still asserted and "active" is still
      ;; asserted
      (is (= #{["red"]}
             (query/q '[:find ?c :where [1 :color ?c]] datoms {:as-of 1})))
      (is (= #{["active"]}
             (query/q '[:find ?s :where [2 :status ?s]] datoms {:as-of 1}))))))


;; ---------------------------------------------------------------------------
;; entity-attrs
;; ---------------------------------------------------------------------------

(deftest entity-attrs-test
  (testing "returns a map of attributes for the entity"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1] [1 :hobby "reading" 1 1]
                  [1 :hobby "coding" 2 1]]]
      (is (= {:name "Alice", :age 30, :hobby ["coding" "reading"]}
             (query/entity-attrs datoms 1)))))
  (testing
    "entity with no datoms returns {} — unlike pull, entity-attrs
            never includes :db/id, matching Datomic's entity/touch
            convention rather than pull's convention"
    (is (= {} (query/entity-attrs [[1 :name "Alice" 1 1]] 999)))))


;; ---------------------------------------------------------------------------
;; Negation (not / not-join)
;; ---------------------------------------------------------------------------

(deftest negation-test
  (testing "not clause filters out bindings that satisfy the inner clauses"
    (let [datoms [[1 :work/posted true 1 1] [1 :work/task "Clean room" 1 1]
                  [2 :work/posted true 1 1] [2 :work/task "Buy groceries" 1 1]
                  [2 :work/claims "user1" 1 1]]]
      (is (= #{[1 "Clean room"]}
             (query/q '[:find ?w ?task :where [?w :work/posted true]
                        [?w :work/task ?task] (not [?w :work/claims _])]
                      datoms)))
      (testing
        "a retracted claim makes the item reappear (current-state interaction)"
        (let [datoms-retracted (conj datoms [2 :work/claims "user1" 2 0])]
          (is (= #{[1 "Clean room"] [2 "Buy groceries"]}
                 (query/q '[:find ?w ?task :where [?w :work/posted true]
                            [?w :work/task ?task] (not [?w :work/claims _])]
                          datoms-retracted)))))
      (testing
        "claims modeled as [claim-entity :work/claims ?w] (wildcard e inside not)"
        (let [datoms [[1 :work/posted true 1 1] [1 :work/task "Clean room" 1 1]
                      [2 :work/posted true 1 1]
                      [2 :work/task "Buy groceries" 1 1]
                      [100 :work/claims 2 1 1]]]
          (is (= #{[1 "Clean room"]}
                 (query/q '[:find ?w ?task :where [?w :work/posted true]
                            [?w :work/task ?task] (not [_ :work/claims ?w])]
                          datoms)))))))
  (testing
    "not-join specifies exactly which variables unify with the outer scope"
    (let [datoms [[1 :user/name "Alice" 1 1] [2 :user/name "Bob" 1 1]
                  ;; 3 is a document Alice authored
                  [3 :doc/author 1 1 1]
                  ;; 4 is a document Bob authored
                  [4 :doc/author 2 1 1]
                  ;; 3 is published
                  [3 :doc/published true 1 1]]]
      ;; Find users who have NO published documents. The not-join [?u]
      ;; means ?doc is free and local to the not clause.
      (is
        (= #{["Bob"]}
           (query/q
             '[:find ?name :where [?u :user/name ?name]
               (not-join [?u] [?doc :doc/author ?u] [?doc :doc/published true])]
             datoms)))))
  (testing "not-join treats non-joined vars as fresh even when names collide"
    (let [datoms [[1 :user/name "Alice" 1 1] [2 :user/name "Bob" 1 1]
                  ;; doc 5 is favorited by both; Alice authored doc 6
                  [5 :doc/fav 1 1 1] [5 :doc/fav 2 1 1] [6 :doc/author 1 1 1]]]
      ;; ?d is bound to 5 outside, but is NOT in the join vars, so inside
      ;; the not-join it must be fresh: "?u authored ANY doc", not "?u
      ;; authored doc 5".
      (is (= #{["Bob"]}
             (query/q '[:find ?name :where [?u :user/name ?name]
                        [?d :doc/fav ?u] (not-join [?u] [?d :doc/author ?u])]
                      datoms)))))
  (testing "special-form clauses are recognized as seqs, not only literal lists"
    (let [datoms [[1 :work/posted true 1 1] [2 :work/posted true 1 1]
                  [2 :work/claims "user1" 1 1]]
          not-clause (cons 'not '([?w :work/claims _]))]
      (is (= #{[1]}
             (query/q [:find '?w :where '[?w :work/posted true] not-clause]
                      datoms)))))
  (testing "planner barrier: var bound before use"
    (let [datoms [[1 :work/posted true 1 1] [1 :work/task "Clean room" 1 1]]]
      (is (= #{[1 "Clean room"]}
             (query/q '[:find ?w ?task :where [?w :work/posted true]
                        (not [?w :work/claims _]) [?w :work/task ?task]]
                      datoms))))))


;; ---------------------------------------------------------------------------
;; Aggregation (:find aggregates + :with)
;; ---------------------------------------------------------------------------

(deftest aggregation-test
  (testing "grouping: :find ?status (count ?e) groups by ?status"
    (let [datoms [[1 :task/status :open 1 1] [2 :task/status :open 1 1]
                  [3 :task/status :done 1 1]]]
      (is (= #{[:open 2] [:done 1]}
             (query/q '[:find ?status (count ?e) :where
                        [?e :task/status ?status]]
                      datoms)))))
  (testing "sum/min/max/avg over numerics"
    (let [datoms [[1 :item/price 10 1 1] [2 :item/price 20 1 1]
                  [3 :item/price 30 1 1]]]
      (is (= #{[60]}
             (query/q '[:find (sum ?p) :where [?e :item/price ?p]] datoms)))
      (is (= #{[10]}
             (query/q '[:find (min ?p) :where [?e :item/price ?p]] datoms)))
      (is (= #{[30]}
             (query/q '[:find (max ?p) :where [?e :item/price ?p]] datoms)))
      (is (= #{[20.0]}
             (query/q '[:find (avg ?p) :where [?e :item/price ?p]] datoms)))))
  (testing "count-distinct"
    (let [datoms [[1 :item/color :red 1 1] [2 :item/color :red 1 1]
                  [3 :item/color :blue 1 1]]]
      (is (= #{[2]}
             (query/q '[:find (count-distinct ?c) :with ?e :where
                        [?e :item/color ?c]]
                      datoms)))))
  (testing ":with keeps intended duplicates without appearing in the result"
    ;; Two entities share the same price: projected tuples collapse to one
    ;; without :with, so the sum dedupes; :with ?e keeps them distinct.
    (let [datoms [[1 :item/price 10 1 1] [2 :item/price 10 1 1]]]
      (is (= #{[10]}
             (query/q '[:find (sum ?p) :where [?e :item/price ?p]] datoms)))
      (is (= #{[20]}
             (query/q '[:find (sum ?p) :with ?e :where [?e :item/price ?p]]
                      datoms)))))
  (testing
    "dedupe-before-aggregate: an extra joined var must not inflate the sum"
    ;; Entity 1 has one price but two tags; the ?t join produces two raw
    ;; bindings. Projection to find ∪ :with vars must dedupe them.
    (let [datoms [[1 :item/price 10 1 1] [1 :item/tag :a 1 1]
                  [1 :item/tag :b 1 1]]]
      (is (= #{[10]}
             (query/q '[:find (sum ?p) :with ?e :where [?e :item/price ?p]
                        [?e :item/tag ?t]]
                      datoms)))
      (testing "putting the extra var in :with restores the duplicates"
        (is (= #{[20]}
               (query/q '[:find (sum ?p) :with ?e ?t :where [?e :item/price ?p]
                          [?e :item/tag ?t]]
                        datoms))))))
  (testing "scalar aggregate with no grouping vars yields a single row"
    (let [datoms [[1 :task/status :open 1 1] [2 :task/status :open 1 1]]]
      (is (= #{[2]}
             (query/q '[:find (count ?e) :where [?e :task/status ?s]] datoms)))
      (testing "and an empty result set stays empty"
        (is (= #{}
               (query/q '[:find (count ?e) :where [?e :task/status :missing]]
                        datoms))))))
  (testing "unknown aggregate throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unknown aggregate"
          (query/q '[:find (median ?p) :where
                     [?e :item/price ?p]]
                   [[1 :item/price 10 1 1]]))))
  (testing "queries without aggregates are unchanged"
    (is (= #{[1 "write tests"] [2 "ship it"]}
           (query/q '[:find ?id ?task :where [?id :work/task ?task]]
                    sample-datoms)))))


;; ---------------------------------------------------------------------------
;; Predicates & function clauses (caller-supplied :fns registry)
;; ---------------------------------------------------------------------------

(deftest fn-clause-test
  (testing "predicate clause filters bindings"
    (let [datoms [[1 :item/qty 5 1 1] [2 :item/qty 15 1 1]]]
      (is (= #{[1]}
             (query/q '[:find ?e :where [?e :item/qty ?n] [(< ?n 10)]]
                      datoms
                      {:fns {'< <}})))))
  (testing "function clause binds its result var"
    (let [datoms [[1 :item/price 10 1 1] [1 :item/qty 3 1 1]]]
      (is (= #{[1 30]}
             (query/q '[:find ?e ?total :where [?e :item/price ?price]
                        [?e :item/qty ?qty] [(* ?price ?qty) ?total]]
                      datoms
                      {:fns {'* *}})))))
  (testing "function clause result unifies (filters) an already-bound var"
    (let [datoms [[1 :item/price 10 1 1] [1 :item/total 30 1 1]
                  [1 :item/qty 3 1 1] [2 :item/price 10 1 1]
                  [2 :item/total 99 1 1] [2 :item/qty 3 1 1]]]
      (is (= #{[1]}
             (query/q '[:find ?e :where [?e :item/price ?price]
                        [?e :item/qty ?qty] [?e :item/total ?total]
                        [(* ?price ?qty) ?total]]
                      datoms
                      {:fns {'* *}})))))
  (testing "multi-return vector destructuring"
    (let [datoms [[1 :item/n 7 1 1]]]
      (is (= #{[1 2 1]}
             (query/q '[:find ?e ?q ?r :where [?e :item/n ?n]
                        [(div-mod ?n 3) [?q ?r]]]
                      datoms
                      {:fns {'div-mod (fn [n d] [(quot n d) (rem n d)])}})))))
  (testing "unknown fn throws when not in the registry or builtins"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unknown query fn"
          (query/q '[:find ?e :where [?e :item/qty ?n]
                     [(unknown-fn ?n 10)]]
                   [[1 :item/qty 5 1 1]]))))
  (testing "unbound arg throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unbound variable in fn clause"
          (query/q '[:find ?e :where [(< ?unbound 10)]
                     [?e :item/qty ?n]]
                   [[1 :item/qty 5 1 1]]
                   {:fns {'< <}}))))
  (testing
    "multiple bare result vars throw (Datomic wants a tuple binding [?a ?b])"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"one binding form"
          (query/q '[:find ?e ?q ?r :where [?e :item/n ?n]
                     [(div-mod ?n 3) ?q ?r]]
                   [[1 :item/n 7 1 1]]
                   {:fns {'div-mod (fn [n d]
                                     [(quot n d)
                                      (rem n d)])}}))))
  (testing "tuple binding arity mismatch throws instead of silently truncating"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"tuple binding arity"
          (query/q '[:find ?e ?q ?r ?s :where [?e :item/n ?n]
                     [(div-mod ?n 3) [?q ?r ?s]]]
                   [[1 :item/n 7 1 1]]
                   {:fns {'div-mod (fn [n d]
                                     [(quot n d)
                                      (rem n d)])}}))))
  (testing "unsupported binding forms (collection/relation) throw"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unsupported binding form"
          (query/q '[:find ?e ?y :where [?e :item/n ?n]
                     [(seq-fn ?n) [?y ...]]]
                   [[1 :item/n 7 1 1]]
                   {:fns {'seq-fn (fn [n] [n n])}})))))


;; ---------------------------------------------------------------------------
;; Negation error paths (all vars inside not must be bound; not-join join
;; vars must be bound)
;; ---------------------------------------------------------------------------

(deftest negation-unbound-var-test
  (testing
    "not with a var no prior clause bound throws instead of wildcard-scanning"
    ;; Without the check, FREE ?w acts as a wildcard: any claim anywhere
    ;; makes the negation fail for every candidate — silently wrong #{}.
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"inside not must be bound"
          (query/q '[:find ?w :where (not [?w :work/claims _])]
                   [[1 :work/claims 2 1 1]]))))
  (testing "not with a var found only inside it throws (Datomic: use not-join)"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"inside not must be bound"
          (query/q '[:find ?w :where [?w :work/posted true]
                     (not [?c :work/claims ?w])]
                   [[1 :work/posted true 1 1]]))))
  (testing "not-join with an unbound join var throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"not-join variables must be bound"
          (query/q
            '[:find ?name :where
              (not-join [?unbound] [?doc :doc/author ?unbound])]
            [[1 :doc/author 2 1 1]])))))


;; ---------------------------------------------------------------------------
;; Bug-exposing tests (RED) — EXPECTED TO FAIL until the bug is fixed.
;; Per TDD red phase: these document real defects found in code review.
;; ---------------------------------------------------------------------------

(deftest nested-not-join-inside-not-bug
  ;; Review finding: eval-not's var-bound check used to flatten EVERY ?-var
  ;; under the `not` via tree-seq, including vars legitimately scoped to a
  ;; nested not-join, throwing spuriously on a legal query. not-join is the
  ;; explicit mechanism for local vars, so the outer `not` must not demand
  ;; them bound. Note the semantics: not-join is itself negation, so
  ;; (not (not-join ...)) is DOUBLE negation — "exists".
  (testing "a not-join nested inside a not scopes its own local var (?r)"
    (let [datoms [[1 :user/name "Alice" 1 1] [2 :user/name "Bob" 1 1]
                  [10 :review/of 1 1 1]]] ; Alice is reviewed, Bob is not
      (testing "single negation: users with no reviews => Bob"
        (is (= #{["Bob"]}
               (query/q '[:find ?name :where [?u :user/name ?name]
                          (not-join [?u] [?r :review/of ?u])]
                        datoms))))
      (testing "double negation: not(no review of ?u) = reviewed users => Alice"
        (is (= #{["Alice"]}
               (query/q '[:find ?name :where [?u :user/name ?name]
                          (not (not-join [?u] [?r :review/of ?u]))]
                        datoms)))))))


(deftest nested-not-join-local-in-entity-slot-bug
  (testing "same over-reach with the not-join local in the entity slot"
    (let [datoms [[1 :user/name "Alice" 1 1] [2 :user/name "Bob" 1 1]
                  ;; review 10 is BY Alice (reviewer); none by Bob.
                  [10 :review/reviewer 1 1 1]]]
      ;; not(no review by ?u) = users who reviewed something => Alice.
      (is (= #{["Alice"]}
             (query/q '[:find ?name :where [?u :user/name ?name]
                        (not (not-join [?u] [?r :review/reviewer ?u]))]
                      datoms))))))


;; ---------------------------------------------------------------------------
;; Rules (recursion), Datomic-style: rules bound to % via :in
;; ---------------------------------------------------------------------------

(def family-datoms
  [["adam" :parent "beth" 1 1] ["beth" :parent "cara" 1 1]
   ["cara" :parent "dave" 1 1] ["xeno" :parent "yara" 1 1]])


(def ancestor-rules
  '[[(ancestor ?a ?d) [?a :parent ?d]]
    [(ancestor ?a ?d) [?a :parent ?c] (ancestor ?c ?d)]])


(deftest rules-test
  (testing "a non-recursive rule is a named sub-query"
    (let [datoms [[1 :user/friend 2 1 1] [3 :user/follows 1 1 1]
                  [4 :user/name "loner" 1 1]]
          rules '[[(social ?e) [?e :user/friend _]]
                  [(social ?e) [?e :user/follows _]]]]
      (testing "multiple bodies for one head are a disjunction (OR)"
        (is (=
              #{[1] [3]}
              (query/q '[:find ?e :in $ % :where (social ?e)] datoms rules))))))
  (testing "recursive rule computes the transitive closure"
    (is (= #{["beth"] ["cara"] ["dave"]}
           (query/q '[:find ?d :in $ % :where (ancestor "adam" ?d)]
                    family-datoms
                    ancestor-rules))))
  (testing "recursion with the value side bound works too"
    (is (= #{["adam"] ["beth"] ["cara"]}
           (query/q '[:find ?a :in $ % :where (ancestor ?a "dave")]
                    family-datoms
                    ancestor-rules))))
  (testing "rule invocations join with ordinary clauses"
    ;; every ancestor of the one :active person
    (let [datoms (conj family-datoms ["dave" :status :active 1 1])]
      (is (= #{["adam" "dave"] ["beth" "dave"] ["cara" "dave"]}
             (query/q '[:find ?a ?d :in $ % :where (ancestor ?a ?d)
                        [?d :status :active]]
                      datoms
                      ancestor-rules)))))
  (testing "recursion terminates on cyclic data"
    (let [cycle [["a" :edge "b" 1 1] ["b" :edge "c" 1 1] ["c" :edge "a" 1 1]]
          rules '[[(reach ?x ?y) [?x :edge ?y]]
                  [(reach ?x ?y) [?x :edge ?z] (reach ?z ?y)]]]
      (is (= #{["a"] ["b"] ["c"]}
             (query/q '[:find ?y :in $ % :where (reach "a" ?y)] cycle rules)))))
  (testing "rule body vars are locally scoped, even under colliding names"
    ;; ?c is bound to :blue outside AND is ancestor's internal chain var.
    ;; If the rule saw the outer ?c, its recursive body [?a :parent ?c]
    ;; would unify against :blue and recursion would die after "beth".
    (let [datoms (conj family-datoms ["adam" :color :blue 1 1])]
      (is (= #{[:blue "beth"] [:blue "cara"] [:blue "dave"]}
             (query/q '[:find ?c ?d :in $ % :where ["adam" :color ?c]
                        (ancestor "adam" ?d)]
                      datoms
                      ancestor-rules)))))
  (testing
    "rule head with repeated var yields no solutions for contradictory args"
    (let [datoms [[1 :val 1 1 1] [2 :val 2 1 1]]
          rules '[[(same ?x ?x)]]]
      (is (= #{}
             (query/q '[:find ?e :in $ % :where [?e :val ?v] (same ?v 99)]
                      datoms
                      rules))))))


(deftest rules-error-paths
  (testing "invoking a rule with no % bound throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"[Nn]o rules"
          (query/q '[:find ?d :where (ancestor "adam" ?d)]
                   family-datoms))))
  (testing "invoking an undefined rule throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unknown rule"
          (query/q '[:find ?d :in $ % :where
                     (descendant "adam" ?d)]
                   family-datoms
                   ancestor-rules)))))


;; ---------------------------------------------------------------------------
;; Bug-exposing tests (RED) — EXPECTED TO FAIL until the bug is fixed.
;; Per TDD red phase: these document real defects found in code review.
;; ---------------------------------------------------------------------------



(def side-effect (atom []))


(defn do-something
  [x]
  (swap! side-effect conj x) [x])


(deftest rule-body-execution-bug
  (testing "contradictory rule head args short-circuit before the body runs"
    (let [datoms [[1 :val 1 1 1]]
          rules '[[(same ?x ?x) [(dao.space.query_test/do-something ?x) [?y]]]]]
      (reset! side-effect [])
      (query/q '[:find ?e :in $ % :where [?e :val ?v] (same ?v 99)]
               datoms
               rules
               {:fns {'dao.space.query_test/do-something do-something}})
      (is (= [] @side-effect)))))


;; ---------------------------------------------------------------------------
;; or / or-join / and (Phase 1)
;; ---------------------------------------------------------------------------

(deftest or-test
  (testing "or unifies bindings from any branch (set semantics across branches)"
    (let [datoms [[1 :type :dog 1 1] [2 :type :cat 1 1] [3 :type :fish 1 1]]]
      (is (= #{[1] [2]}
             (query/q '[:find ?e :where (or [?e :type :dog] [?e :type :cat])]
                      datoms)))))
  (testing "an or branch with extra free vars must be detected (same-var rule)"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"[Ss]ame.*variable|branches"
          (query/q '[:find ?e :where (or [?e :a ?x] [?e :b ?y])]
                   [[1 :a 1 1 1] [1 :b 2 1 1]]))))
  (testing "set semantics: identical bindings from two branches appear once"
    (let [datoms [[1 :kind :cat 1 1] [1 :pet true 1 1]]]
      (is (= #{[1]}
             (query/q '[:find ?e :where (or [?e :kind :cat] [?e :pet true])]
                      datoms)))))
  (testing "or with no branch satisfied yields no bindings"
    (is (= #{}
           (query/q '[:find ?e :where (or [?e :a 1] [?e :a 2])]
                    [[1 :a 3 1 1]])))))


(deftest or-join-test
  (testing "or-join declares which vars unify with the outer scope"
    (let [datoms [[1 :x 10 1 1] [1 :flag true 1 1] [2 :x 20 1 1]
                  [2 :flag false 1 1]]]
      ;; ?x is declared in the join vars so it is allowed to flow out
      ;; of either branch (the engine strips branch-only vars; ?x is
      ;; not branch-only here).
      (is
        (=
          #{[1 10] [2 20] [1 1]}
          (query/q '[:find ?e ?x :where
                     (or-join
                       [?e ?x]
                       [?e :x ?x]
                       (and [?e :flag true] [(identity ?e) ?x]))]
                   datoms
                   {:fns {'identity identity}})))))
  (testing "branch-only vars do not escape the join declaration"
    ;; ?x is bound inside the branch but not declared in the join;
    ;; it must not appear in the outer result.
    (let [datoms [[1 :x 10 1 1] [2 :x 20 1 1]]]
      (is (= #{[1] [2]}
             (query/q '[:find ?e :where (or-join [?e] [?e :x ?x])] datoms)))))
  (testing "or-join can introduce its own join var when unbound"
    (is (= #{[1] [2]}
           (query/q '[:find ?e :where (or-join [?e] [?e :a 1] [?e :a 2])]
                    [[1 :a 1 1 1] [2 :a 2 1 1] [3 :a 3 1 1]]))))
  (testing "or-join preserves outer-scope vars (merge back into outer binding)"
    ;; ?n is bound by the top-level pattern; the or-join then adds
    ;; a join var (or introduces a new one). The merged binding must
    ;; carry ?n through — the engine should augment, not replace.
    (is (= #{["a" 1]}
           (query/q '[:find ?n ?e :where [?e :name ?n]
                      (or-join [?e] [?e :flag true])]
                    [[1 :name "a" 1 1] [1 :flag true 1 1]]))))
  (testing "branches do not see non-join outer vars (isolation)"
    ;; ?v is bound to 99 in the outer scope but NOT declared as a join var.
    ;; Inside the branch, ?v should be a fresh local var that ranges
    ;; freely, NOT filtered by the outer binding's ?v = 99. If isolation is
    ;; broken, the branch would filter on ?v = 99 (no match). With correct
    ;; isolation, ?v matches the :tag value (1) and succeeds.
    (let [datoms [[1 :name "a" 1 1] [1 :val 99 1 1] [1 :tag 1 1 1]]]
      (is (= #{["a" 1]}
             (query/q '[:find ?n ?e :where [?e :name ?n] [?e :val ?v]
                        (or-join [?e] [?e :tag ?v])]
                      datoms))))))


(deftest and-test
  (testing "and groups clauses inside an or branch"
    (let [datoms [[1 :x 5 1 1] [1 :y 5 1 1] [2 :x 5 1 1] [2 :y 4 1 1]
                  [3 :flag true 1 1]]]
      (is
        (=
          #{[1] [3]}
          (query/q '[:find ?e :where
                     (or-join
                       [?e]
                       (and [?e :x ?x] [?e :y ?y] [(= ?x ?y)])
                       [?e :flag true])]
                   datoms
                   {:fns {'= =}}))))))


;; ---------------------------------------------------------------------------
;; Find specs and return maps (Phase 2)
;; ---------------------------------------------------------------------------

(deftest find-scalar-test
  (testing "[:find ?x .] returns a single value, or nil for no results"
    (let [datoms [[1 :name "Alice" 1 1] [2 :name "Bob" 1 1]]]
      (is (= "Alice" (query/q '[:find ?n . :where [1 :name ?n]] datoms)))
      (is (nil? (query/q '[:find ?n . :where [99 :name ?n]] datoms)))))
  (testing "scalar find spec composes with aggregates"
    (is (= 3
           (query/q '[:find (count ?e) . :where [?e :task/status :open]]
                    [[1 :task/status :open 1 1] [2 :task/status :open 1 1]
                     [3 :task/status :open 1 1]])))))


(deftest find-coll-test
  (testing "single var coll returns flat vector of values"
    (let [datoms [[1 :name "Alice" 1 1] [2 :name "Bob" 1 1]
                  [3 :name "Charlie" 1 1]]]
      (is (= #{"Alice" "Bob" "Charlie"}
             (set (query/q '[:find [?n ...] :where [_ :name ?n]] datoms))))))
  (testing "collection find on empty results is an empty vector"
    (is (= [] (query/q '[:find [?n ...] :where [_ :name ?n]] []))))
  (testing "multi-var coll returns vector of tuples"
    (let [datoms [[1 :name "Alice" 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{[1 "Alice"] [2 "Bob"]}
             (set (query/q '[:find [?e ?n ...] :where [?e :name ?n]]
                           datoms)))))))


(deftest find-tuple-test
  (testing "[:find [?x ?y]] returns a single tuple (or nil)"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1]]]
      (is (= ["Alice" 30]
             (query/q '[:find [?n ?a] :where [1 :name ?n] [1 :age ?a]]
                      datoms))))
    (testing "no results yields nil"
      (is (nil? (query/q '[:find [?n ?a] :where [99 :name ?n]] []))))))


(deftest return-maps-keys-test
  (testing ":keys returns a seq of maps with the named keys"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1] [2 :name "Bob" 1 1]
                  [2 :age 40 1 1]]]
      (is (= #{{:e 1, :n "Alice", :a 30} {:e 2, :n "Bob", :a 40}}
             (set (query/q '[:find ?e ?n ?a :keys e n a :where [?e :name ?n]
                             [?e :age ?a]]
                           datoms))))))
  (testing ":keys arity must match the find vars (throws otherwise)"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"[Rr]eturn map arity|arity must match"
          (query/q '[:find ?e ?n :keys e :where [?e :name ?n]]
                   [[1 :name "Alice" 1 1]]))))
  (testing ":keys is relation-only (no scalar/coll/tuple find specs)"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"[Rr]eturn map form.*relation"
          (query/q '[:find ?e . :keys e :where [?e :name ?n]]
                   [[1 :name "Alice" 1 1]])))))


(deftest return-maps-syms-strs-test
  (testing ":syms returns a seq of maps with symbol keys"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1] [2 :name "Bob" 1 1]
                  [2 :age 40 1 1]]
          result (query/q '[:find ?e ?n :syms e n :where [?e :name ?n]
                            [?e :age ?a]]
                          datoms)]
      (is (every? map? result))
      (is (= #{[1 "Alice"] [2 "Bob"]} (set (map (juxt 'e 'n) result))))))
  (testing ":syms keys are symbols (e n), not keywords (:e :n)"
    (let [datoms [[1 :name "Alice" 1 1]]
          result (query/q '[:find ?e :syms e :where [?e :name _]] datoms)]
      (is (every? #(contains? % 'e) result))
      (is (not-any? #(contains? % :e) result))))
  (testing ":strs returns a seq of maps with string keys"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1] [2 :name "Bob" 1 1]
                  [2 :age 40 1 1]]
          result (query/q '[:find ?e ?n :strs e n :where [?e :name ?n]
                            [?e :age ?a]]
                          datoms)]
      (is (every? map? result))
      (is (= #{[1 "Alice"] [2 "Bob"]}
             (set (map (juxt #(get % "e") #(get % "n")) result)))))))


;; ---------------------------------------------------------------------------
;; VAET read path (Phase 4): v-only patterns answer from the VAET index
;; ---------------------------------------------------------------------------

(deftest vaet-vbound-test
  (testing "v-only match over a raw datom vector exercises in-memory VAET"
    (let [datoms [[1 :item/category :fruit 1 1] [2 :item/category :fruit 1 1]
                  [3 :item/category :vegetable 1 1]]]
      (is (= #{[1 :item/category :fruit 1 1] [2 :item/category :fruit 1 1]}
             (set (query/match datoms ['_ '_ :fruit]))))))
  (testing "v-only q (who points at X?) over a raw datom vector"
    (let [datoms [[1 :doc/author 2 1 1] [3 :doc/author 2 1 1]
                  [4 :doc/author 5 1 1]]]
      (is (= #{[1] [3]}
             (query/q '[:find ?e :where [?e :doc/author 2]] datoms)))))
  (testing "v-only with as-of bounds the index slice"
    (let [datoms [[1 :doc/author 2 1 1] [1 :doc/author 2 5 1]
                  [3 :doc/author 2 3 1]]]
      ;; as-of 1 excludes the t=3 and t=5 datoms
      (is (= #{[1]}
             (query/q '[:find ?e :where [?e :doc/author 2]] datoms {:as-of 1})))
      ;; as-of 4 includes t=1 and t=3
      (is (= #{[1] [3]}
             (query/q '[:find ?e :where [?e :doc/author 2]]
                      datoms
                      {:as-of 4}))))))


;; ---------------------------------------------------------------------------

(deftest builtin-fns-test
  (testing "comparators are in the default registry (no :fns option needed)"
    (let [datoms [[1 :item/qty 5 1 1] [2 :item/qty 15 1 1]]]
      (is (= #{[1]}
             (query/q '[:find ?e :where [?e :item/qty ?n] [(< ?n 10)]]
                      datoms)))))
  (testing "arithmetic fns are in the default registry"
    (let [datoms [[1 :item/price 10 1 1] [1 :item/qty 3 1 1]]]
      (is (= #{[1 30]}
             (query/q '[:find ?e ?total :where [?e :item/price ?p]
                        [?e :item/qty ?q] [(* ?p ?q) ?total]]
                      datoms)))))
  (testing "str / count / first / last / get / nth are builtins"
    (is (= #{["Alice"]}
           (query/q '[:find ?u :where [1 :name ?n] [(str ?n) ?u]]
                    [[1 :name "Alice" 1 1]])))
    (let [datoms [[1 :name "Alice" 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{["Alice!"] ["Bob!"]}
             (query/q '[:find ?c :where [?e :name ?n] [(str ?n "!") ?c]]
                      datoms))))
    (is (= #{[5]}
           (query/q '[:find ?l :where [_ :name "Alice"] [(count "Alice") ?l]]
                    [[1 :name "Alice" 1 1]]))))
  (testing "ground binds a literal to a variable"
    (is (= #{[42]} (query/q '[:find ?x :where [(ground 42) ?x]] [])))))


(deftest get-else-test
  (testing "returns the attr value or the default when the entity lacks it"
    (is (= #{["Alice"]}
           (query/q '[:find ?v :where [(get-else $ 1 :name "anon") ?v]]
                    [[1 :name "Alice" 1 1]])))
    (is (= #{["anon"]}
           (query/q '[:find ?v :where [(get-else $ 2 :name "anon") ?v]]
                    [[1 :name "Alice" 1 1]])))))


(deftest missing?-test
  (testing "predicate is true when the entity lacks the attribute"
    (is (= #{[1]}
           (query/q '[:find ?e :where [?e :name "Alice"] [(missing? $ 1 :age)]]
                    [[1 :name "Alice" 1 1]])))
    (is (= #{}
           (query/q '[:find ?e :where [?e :name "Alice"] [(missing? $ 1 :name)]]
                    [[1 :name "Alice" 1 1]])))))


(deftest builtins-disable-test
  (testing "the explicit :builtins false opt removes the default registry"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"Unknown query fn"
          (query/q '[:find ?e :where [?e :item/qty ?n]
                     [(< ?n 10)]]
                   [[1 :item/qty 5 1 1]]
                   {:builtins false})))))


;; ---------------------------------------------------------------------------
;; Bug-exposing tests
;; ---------------------------------------------------------------------------

(deftest get-else-with-query-var-test
  (testing "get-else resolves query variables in entity position"
    (let [datoms [[1 :name "Alice" 1 1] [2 :age 30 1 1]]]
      ;; ?e is bound by the pattern clause, then used in get-else
      (is (= #{[1 "Alice"]}
             (query/q '[:find ?e ?v :where [?e :name _]
                        [(get-else $ ?e :name "anon") ?v]]
                      datoms)))
      ;; entity 2 lacks :name, should return default
      (is (= #{[2 "anon"]}
             (query/q '[:find ?e ?v :where [?e :age _]
                        [(get-else $ ?e :name "anon") ?v]]
                      datoms))))))


(deftest missing?-with-query-var-test
  (testing "missing? resolves query variables in entity position"
    (let [datoms [[1 :name "Alice" 1 1] [2 :age 30 1 1]]]
      ;; entity 2 is missing :name -> predicate is true -> binding survives
      (is (= #{[2]}
             (query/q '[:find ?e :where [?e :age _] [(missing? $ ?e :name)]]
                      datoms)))
      ;; entity 1 has :name -> predicate is false -> binding filtered out
      (is (= #{}
             (query/q '[:find ?e :where [?e :name _] [(missing? $ ?e :name)]]
                      datoms))))))


(deftest or-with-not-local-vars-test
  (testing "or same-var rule ignores vars local to not/not-join"
    (let [datoms [[1 :a 1 1 1] [1 :b 1 1 1] [2 :a 2 1 1] [2 :c 2 1 1]]]
      ;; Branch 1 has ?x in a positive clause (output). Branch 2 has ?x in
      ;; a positive clause AND ?y inside a not-join
      ;; (a free, existentially-scoped var that must NOT count toward
      ;; the branch's output set for the same-var rule).
      ;; The same-var rule should see both branches as {?e, ?x} and
      ;; pass. Branch 1 yields {?e 1, ?x 1} and {?e 2, ?x 2}.
      ;; Branch 2 yields {?e 1, ?x 1} only (entity 2 lacks :b).
      ;; Union: {[1 1] [2 2]}.
      (is (= #{[1 1] [2 2]}
             (query/q
               '[:find ?e ?x :where
                 (or [?e :a ?x] (and [?e :b ?x] (not-join [?e ?x] [?e :c ?y])))]
               datoms))))))


(deftest rules-inside-not-or-join-test
  (testing "rules can be called inside not-join and or-join"
    (let [datoms [[1 :type :dog 1 1] [2 :type :cat 1 1] [3 :type :bird 1 1]]
          rules '[[(is-cat ?e) [?e :type :cat]]]]
      ;; test not-join with rule
      (is (= #{[1] [3]}
             (query/q '[:find ?e :in $ % :where [?e :type _]
                        (not-join [?e] (is-cat ?e))]
                      datoms
                      rules)))
      ;; test or-join with rule
      (is (= #{[2]}
             (query/q '[:find ?e :in $ % :where (or-join [?e] (is-cat ?e))]
                      datoms
                      rules))))))


(deftest or-join-branch-must-bind-join-vars-test
  (testing
    "a branch that fails to bind a declared join var throws
            instead of leaking the engine's FREE sentinel into results"
    ;; (or-join [?e ?x] [?e :a 1]) — the single branch binds ?e but
    ;; never ?x. DataScript rejects this via the same-var rule; without
    ;; validation the engine binds ?x to the internal ::free sentinel
    ;; and it escapes into the :find output.
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"join var"
          (query/q '[:find ?e ?x :where
                     (or-join [?e ?x] [?e :a 1])]
                   [[1 :a 1 1 1]]))))
  (testing
    "the check is per branch: one conforming branch does not
            excuse another that misses a join var"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"join var"
          (query/q '[:find ?e ?x :where
                     (or-join [?e ?x] [?e :a ?x] [?e :b 1])]
                   [[1 :a 1 1 1] [1 :b 1 1 1]])))))


;; ---------------------------------------------------------------------------
;; Pull: declarative entity projection (formerly dao.space.pull, merged
;; 2026-07-13 — see docs/datomic-pull.md and the Pull section banner in
;; query.cljc for why)
;; ---------------------------------------------------------------------------

;; Increment 1: Parser

(deftest parse-pattern-attrs-test
  (testing "simple attr list"
    (is (= {:attrs [:name :age], :wildcard? false, :nested {}}
           (query/parse-pattern [:name :age]))))
  (testing "empty pattern"
    (is (= {:attrs [], :wildcard? false, :nested {}}
           (query/parse-pattern [])))))


(deftest parse-pattern-wildcard-test
  (testing "wildcard ' [*] "
    (is (= {:attrs [], :wildcard? true, :nested {}}
           (query/parse-pattern '[*])))))


(deftest parse-pattern-nested-test
  (testing "nested map spec"
    (is (= {:attrs [],
            :wildcard? false,
            :nested {:friend {:attrs [:name], :wildcard? false, :nested {}}}}
           (query/parse-pattern [{:friend [:name]}])))))


(deftest parse-pattern-reverse-test
  (testing "reverse ref :_attr"
    (is (= {:attrs [:_friend], :wildcard? false, :nested {}}
           (query/parse-pattern [:_friend]))))
  (testing "reverse ref with nested"
    (is (= {:attrs [],
            :wildcard? false,
            :nested {:_friend {:attrs [:name], :wildcard? false, :nested {}}}}
           (query/parse-pattern [{:_friend [:name]}])))))


(deftest parse-pattern-options-test
  (testing "attr with :default"
    (is (= {:attrs [{:attr :age, :default 0}], :wildcard? false, :nested {}}
           (query/parse-pattern [[:age :default 0]]))))
  (testing "attr with :limit"
    (is (= {:attrs [{:attr :tags, :limit 5}], :wildcard? false, :nested {}}
           (query/parse-pattern [[:tags :limit 5]]))))
  (testing "attr with :as"
    (is (= {:attrs [{:attr :name, :as :label}], :wildcard? false, :nested {}}
           (query/parse-pattern [[:name :as :label]])))))


(deftest parse-pattern-malformed-test
  (testing "non-keyword/symbol/map/vector throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"malformed"
          (query/parse-pattern [123]))))
  (testing "vector with non-keyword first element throws"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"malformed"
          (query/parse-pattern [[123 :default 0]])))))


;; Increment 2: Flat pull

(def pull-sample-datoms
  [[1 :name "Alice" 1 1] [1 :age 30 1 1] [1 :tag "dev" 1 1] [1 :tag "admin" 2 1]
   [2 :name "Bob" 1 1]])


(deftest pull-flat-attrs-test
  (testing "simple attr list returns scalar for single-valued, vector for multi"
    (let [result (query/pull pull-sample-datoms 1 [:name :age :tag])]
      (is (= 1 (:db/id result)))
      (is (= "Alice" (:name result)))
      (is (= 30 (:age result)))
      (is (= #{"dev" "admin"} (set (:tag result))))))
  (testing "missing attr is omitted"
    (is (= {:db/id 2, :name "Bob"}
           (query/pull pull-sample-datoms 2 [:name :age])))))


(deftest pull-flat-wildcard-test
  (testing "wildcard includes all attrs"
    (let [result (query/pull pull-sample-datoms 1 '[*])]
      (is (= 1 (:db/id result)))
      (is (= "Alice" (:name result)))
      (is (= 30 (:age result)))
      (is (= #{"dev" "admin"} (set (:tag result)))))))


(deftest pull-flat-options-test
  (testing ":default for missing attr"
    (is (= {:db/id 2, :name "Bob", :age 0}
           (query/pull pull-sample-datoms 2 [:name [:age :default 0]]))))
  (testing ":limit bounds multi-valued results"
    (let [result (query/pull pull-sample-datoms 1 [[:tag :limit 1]])]
      (is (= 1 (:db/id result)))
      (is (= 1 (count (:tag result))))
      (is (contains? #{"dev" "admin"} (first (:tag result))))))
  (testing ":limit on single-valued attribute does not force vector wrapping"
    (is (= {:db/id 1, :name "Alice"}
           (query/pull pull-sample-datoms 1 [[:name :limit 5]]))))
  (testing ":as renames output key"
    (is (= {:db/id 1, :label "Alice"}
           (query/pull pull-sample-datoms 1 [[:name :as :label]])))))


(deftest pull-flat-db-id-only-for-absent-test
  (testing
    "entity with no datoms returns {:db/id eid}, matching Datomic —
            pull never returns nil at the top level, since entity ids
            are not existence-checked; it always echoes back :db/id"
    (is (= {:db/id 999} (query/pull pull-sample-datoms 999 [:name])))))


;; Increment 3: Nested maps (forward navigation)

(def pull-nested-datoms
  [[1 :name "Alice" 1 1] [1 :friend 2 1 1] [1 :friend 3 2 1] [2 :name "Bob" 1 1]
   [2 :friend 3 1 1] [3 :name "Charlie" 1 1]])


(deftest pull-many-test
  (testing "pull-many folds once, maps over eids"
    (is (= [{:db/id 1, :name "Alice"} {:db/id 2, :name "Bob"} {:db/id 999}]
           (query/pull-many pull-sample-datoms [1 2 999] [:name]))))
  (testing "pull-many supports nested and reverse specs"
    (let [res
          (query/pull-many pull-nested-datoms [1] [:name {:friend [:name]}])]
      (is (= [{:db/id 1,
               :name "Alice",
               :friend [{:db/id 2, :name "Bob"} {:db/id 3, :name "Charlie"}]}]
             (mapv (fn [item] (update item :friend #(sort-by :db/id %))) res))))
    (let [res
          (query/pull-many pull-nested-datoms [3] [:name {:_friend [:name]}])]
      (is (= [{:db/id 3,
               :name "Charlie",
               :_friend [{:db/id 1, :name "Alice"} {:db/id 2, :name "Bob"}]}]
             (mapv (fn [item] (update item :_friend #(sort-by :db/id %)))
                   res))))))


(deftest pull-nested-test
  (testing "nested map spec navigates forward refs"
    (is (= {:db/id 1,
            :name "Alice",
            :friend [{:db/id 2, :name "Bob"} {:db/id 3, :name "Charlie"}]}
           (let [result
                 (query/pull pull-nested-datoms 1 [:name {:friend [:name]}])]
             (update result :friend #(sort-by :db/id %))))))
  (testing "value addressing no datoms is omitted"
    (is (= {:db/id 3, :name "Charlie"}
           (query/pull pull-nested-datoms 3 [:name {:friend [:name]}]))))
  (testing "nesting depth > 2"
    (let [result (query/pull pull-nested-datoms
                             1
                             [:name {:friend [:name {:friend [:name]}]}])
          friends (sort-by :db/id (:friend result))]
      (is (= 2 (count friends)))
      (is (= "Bob" (:name (first friends))))
      ;; Bob has one friend (Charlie), so scalar per entity-attrs
      ;; convention
      (is (= {:db/id 3, :name "Charlie"} (:friend (first friends)))))))


;; Increment 4: Reverse refs

(deftest pull-reverse-test
  (testing "reverse ref :_attr returns vector of entities pointing here"
    (let [result (query/pull pull-nested-datoms 2 [:name :_friend])]
      (is (= "Bob" (:name result)))
      ;; Entity 1 has friend 2, so :_friend should include entity 1
      (is (= [{:db/id 1}] (mapv #(select-keys % [:db/id]) (:_friend result))))))
  (testing "reverse ref with nested"
    (let [result (query/pull pull-nested-datoms 3 [:name {:_friend [:name]}])
          friends (sort-by :db/id (:_friend result))]
      ;; Entities 1 and 2 both have friend 3
      (is (= 2 (count friends)))
      (is (= "Alice" (:name (first friends))))
      (is (= "Bob" (:name (second friends))))))
  (testing "reverse ref supports :default option"
    (is (= {:db/id 1, :name "Alice", :_friend []}
           (query/pull pull-nested-datoms 1 [:name [:_friend :default []]])))))


;; A reverse-ref pull against a raw datom vector only exercises the
;; in-memory index that `fold` builds fresh each call. Publishing first
;; makes the reverse probe reach the lazily-restored AVET instead — the only
;; way to prove the persisted-index path answers `:_attr` correctly, not
;; just the eager in-memory one.
(deftest pull-reverse-reaches-the-published-index
  (let [source (source pull-nested-datoms)]
    (let [result (query/pull source 3 [:name {:_friend [:name]}])
          friends (sort-by :db/id (:_friend result))]
      (is (= "Charlie" (:name result)))
      (is (= 2 (count friends)))
      (is (= "Alice" (:name (first friends))))
      (is (= "Bob" (:name (second friends)))))
    (let [flat (query/pull source 2 [:name :_friend])]
      (is (= "Bob" (:name flat)))
      (is (= [{:db/id 1}] (mapv #(select-keys % [:db/id]) (:_friend flat)))))))


;; ---------------------------------------------------------------------------
;; Increment 6: (pull ?e pattern) as a q find element
;; ---------------------------------------------------------------------------
;;
;; Pull and q now live in the same namespace (merged 2026-07-13), so a
;; pull find element resolves the `$` source's already-folded index
;; directly via `resolve-db` — no DI, no `:pull-fn` option, no per-row
;; re-fold. Pull find elements still bind to `$` only in this pass.


(deftest pull-find-element-basic-test
  (testing "(pull ?e pattern) as a find element projects each row through pull"
    (let [datoms [[1 :name "Alice" 1 1] [1 :age 30 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{[{:db/id 1, :name "Alice", :age 30}] [{:db/id 2, :name "Bob"}]}
             (set (query/q '[:find (pull ?e [:name :age]) :where [?e :name _]]
                           datoms))))))
  (testing "pull find element composes with a plain var in the same relation"
    (let [datoms [[1 :name "Alice" 1 1]]]
      (is (= #{[1 {:db/id 1, :name "Alice"}]}
             (set (query/q '[:find ?e (pull ?e [:name]) :where [?e :name _]]
                           datoms)))))))


(deftest pull-find-element-nested-and-reverse-test
  (testing
    "pull find element pattern supports nested/reverse specs, same as direct pull"
    (let [datoms [[1 :name "Alice" 1 1] [1 :friend 2 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{[{:db/id 1, :name "Alice", :friend {:db/id 2, :name "Bob"}}]}
             (set (query/q '[:find (pull ?e [:name {:friend [:name]}]) :where
                             [?e :name "Alice"]]
                           datoms)))))))


(deftest pull-find-element-scalar-spec-test
  (testing "pull composes with the scalar find spec"
    (is (= {:db/id 1, :name "Alice"}
           (query/q '[:find (pull ?e [:name]) . :where [?e :name "Alice"]]
                    [[1 :name "Alice" 1 1]])))))


(deftest pull-find-element-coll-spec-test
  (testing "pull composes with the collection find spec"
    (let [datoms [[1 :name "Alice" 1 1] [2 :name "Bob" 1 1]]]
      (is (= #{{:db/id 1, :name "Alice"} {:db/id 2, :name "Bob"}}
             (set (query/q '[:find [(pull ?e [:name]) ...] :where [?e :name _]]
                           datoms)))))))


(deftest pull-find-element-tuple-spec-test
  (testing "pull composes with the tuple find spec, alongside a plain var"
    (is (= [1 {:db/id 1, :name "Alice"}]
           (query/q '[:find [?e (pull ?e [:name])] :where [?e :name "Alice"]]
                    [[1 :name "Alice" 1 1]])))))


(deftest pull-find-element-rejects-aggregate-test
  (testing "an aggregate cannot wrap a pull find element"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"[Pp]ull"
          (query/q '[:find (count (pull ?e [:name])) :where
                     [?e :name _]]
                   [[1 :name "Alice" 1 1]])))))


(deftest pull-find-element-no-dollar-source-test
  (testing
    "pull find element throws when $ was never bound (an :in shape
            that binds ?e directly, bypassing any $-pattern clause)"
    (is (thrown-with-msg? #?(:cljs js/Error
                             :cljd Object
                             :default Exception)
                          #"\$ source"
          (query/q '[:find (pull ?e [:name]) :in ?e] 1)))))


(deftest pull-find-element-with-aggregate-test
  (testing "pull find element composes with aggregates (acts as grouping var)"
    (let [datoms [[1 :name "Alice" 1 1] [1 :friend 2 1 1] [1 :friend 3 1 1]
                  [2 :name "Bob" 1 1] [2 :friend 3 1 1]]]
      (is (= #{[{:db/id 1, :name "Alice"} 2] [{:db/id 2, :name "Bob"} 1]}
             (set (query/q '[:find (pull ?e [:name]) (count ?friend) :where
                             [?e :name _] [?e :friend ?friend]]
                           datoms)))))))
