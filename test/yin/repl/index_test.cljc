(ns yin.repl.index-test
  "The shell's code indexer (docs/design/yin.repl.dao.space-index.md): a
   dao.space.index observer on program-out, one transaction per forwarded
   program, covered indexes published into dao.jing each round."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.file :as jing.file]
            [dao.jing.mem :as mem]
            [dao.space.index :as index]
            [dao.space.query :as query]
            [dao.space.store :as durable]
            [dao.space.transactor :as transactor]
            [dao.stream :as stream]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.observer :as observer]
            [dao.stream.ringbuffer :as ring]
            [yin.repl :as repl]
            [yin.repl.fixture :as fixture]
            [yin.repl.frontends :as repl.frontends]
            [yin.repl.index :as repl.index]
            [yin.repl.store :as store]
            [yin.vm :as vm]
            [yin.vm.linker.publish :as publish]
            [yin.vm.linker.sign :as sign]
            [yin.vm.macro :as macro]))


(defrecord CountingLocal
  [inner reads]

  stream/IDaoStreamReader

  (cursor
    [_ anchor]
    (swap! reads conj [:cursor anchor])
    (stream/cursor inner anchor))


  (next
    [_ cursor]
    (swap! reads conj [:next cursor])
    (stream/next inner cursor))


  stream/IDaoStreamWriter

  (append! [_ value] (stream/append! inner value)))


(defn- stream-values
  "Every value currently on a stream, read from its oldest cursor."
  [s]
  (loop [cursor (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))
         acc []]
    (let [r (stream/next s cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- transactions
  "The atomic transaction records the session's indexer committed."
  [state]
  (mapv :dao.space/transaction
        (stream-values (get-in state [:indexer :local]))))


(defn- evaluate
  [state lines]
  (reduce (fn [[state texts] line]
            (let [[state' text] (repl/eval-input state line)]
              [state' (conj texts text)]))
          [state []]
          lines))


(def ^:private ref-attrs
  (into #{}
        (keep (fn [[a spec]] (when (= :db.type/ref (:db/valueType spec)) a)))
        vm/schema))


(defn- modulo-ids
  "`[e a v]` of `datoms` with entity ids relabelled by first appearance, so
   two projections of one tree compare equal whatever ids they minted."
  [datoms]
  (let [ids (into {}
                  (map-indexed (fn [i e] [e i]))
                  (distinct (map first datoms)))
        relabel (fn [a v]
                  (cond (not (contains? ref-attrs a)) v
                        (vector? v) (mapv ids v)
                        :else (ids v)))]
    (mapv (fn [[e a v]] [(ids e) a (relabel a v)]) datoms)))


(deftest one-program-is-one-transaction-of-its-expanded-projection
  (let [state (repl.frontends/create-state)
        [state' result] (repl/eval-input state "(+ 1 2)")
        [packet] (stream-values (:row-stream state'))
        root (first packet)
        rows (:rows (macro/packet->row-set packet))
        txs (transactions state')
        {:keys [t datoms]} (first txs)
        meta-e (some (fn [[e a]] (when (= :db/op a) e)) datoms)
        meta-facts (filterv #(= meta-e (first %)) datoms)
        addresses (filterv #(= :yin/address (second %)) datoms)
        ast-facts (filterv #(and (not= meta-e (first %))
                                 (not= :yin/address (second %)))
                           datoms)]
    (is (= "3" result) "the evaluator's answer is unchanged")
    (is (= 1 (count txs)) "exactly one transaction for the program")
    (testing "its datoms are ast->datoms of the expanded tree, modulo ids"
      (is (= (modulo-ids (vm/ast->datoms (vm/semantic-bytecode->ast
                                           (macro/packet->row-set packet))))
             (modulo-ids ast-facts))))
    (testing "the transactor allocated t; every datom carries it"
      (is (integer? t))
      (is (every? #(= t (nth % 3)) datoms)))
    (testing "e is transactor-local, with :yin/address back to the row"
      (is (every? #(and (integer? (first %)) (<= 16 (first %))) datoms))
      (is (= (set (map first ast-facts)) (set (map first addresses)))
          "every node occurrence carries exactly one address fact")
      (is (= (count addresses) (count (set (map first addresses)))))
      (is (every? #(contains? rows (nth % 2)) addresses))
      (let [root-e (some (fn [[e a]] (when (= :yin/root a) e)) ast-facts)]
        (is (some #(= [root-e :yin/address root] (subvec % 0 3)) addresses)
            "the root occurrence names the program's content address")))
    (testing "m is a local metadata entity carrying the session provenance"
      (is (integer? meta-e))
      (is (every? #(= meta-e (nth % 4)) (concat ast-facts addresses)))
      (is (= {:db/op :db/assert
              :yin.repl/session (:shell-token state)
              :yin.repl/root root
              :yin.repl/round 1}
             (into {} (map (fn [[_ a v]] [a v])) meta-facts))))
    (testing "only code is indexed: no result or printed output"
      (is (not-any? #(= 3 (nth % 2))
                    (filter #(= :yin/value (second %)) ast-facts))
          "the literal operands are 1 and 2; the result 3 appears nowhere")
      (is (= #{1 2}
             (set (keep (fn [[_ a v]] (when (= :yin/value a) v)) ast-facts)))))))


(deftest the-round-publishes-covered-indexes-readable-from-dao-jing
  ;; Gate B: the same publication proof on each non-DHT backend, through
  ;; an explicitly injected handle -- never the default construction.
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-state
        kind
        (fn [state _backend]
          (let [[state _] (evaluate state
                                    ["(def inc2 (fn [x] (+ x 2)))" "(inc2 40)"])
                {:keys [manifest-address content-store]} (:indexer state)
                committed (into #{} (mapcat :datoms) (transactions state))]
            (is (some? manifest-address))
            (is (identical? content-store (:handle _backend))
                "publication lands in the injected non-DHT dao.jing store")
            (testing "the manifest's EAVT index holds every committed datom"
              (is (= 2 (count (transactions state))))
              (is (= committed (set (index/read-datoms content-store manifest-address)))))
            (testing "each restored covered index holds the committed facts"
              (let [manifest (index/read-manifest content-store manifest-address)
                    restored (index/restored-indexes content-store manifest)]
                (doseq [k [:eavt :aevt :avet :vaet]]
                  (is (= committed (set (seq (get restored k)))) (str k)))))
            (testing "the datoms read back from the store answer queries"
              (let [published (query/relation
                                (index/read-datoms content-store manifest-address))
                    names (query/collect
                            (query/q '[:find ?name
                                       :where [$ ?e :yin/type :variable]
                                       [$ ?e :yin/name ?name]]
                                     (query/current published)))
                    sessions (query/collect
                               ;; the history view exposes m; current binds [e a v]
                               (query/q '[:find ?session ?round
                                          :where [?e :yin/root true ?t ?m]
                                          [?m :yin.repl/session ?session ?t1 ?m1]
                                          [?m :yin.repl/round ?round ?t2 ?m2]]
                                        (query/history published)))]
                (is (every? (set (map first names)) '[yin/def x + inc2]))
                (is (= #{[(:shell-token state) 1] [(:shell-token state) 2]}
                       (set sessions))
                    "each program's root reaches its provenance through m")))))))))


(deftest failed-expansions-add-nothing-raising-and-parking-programs-are-indexed
  (testing "a failed expansion forwards nothing, so nothing is committed"
    (let [[state [defined failed]]
          (evaluate (repl.frontends/create-state)
                    ["(defmacro unless [c a b] (yin/if c b a))"
                     "(unless 1 2)"])]
      (is (not (str/starts-with? defined "Error")))
      (is (str/starts-with? failed "Error: Macro expansion failed"))
      (is (= 1 (count (transactions state)))
          "only the defmacro program was committed")))
  (testing "a program whose VM raises is still indexed"
    (let [[state [raised]] (evaluate (repl.frontends/create-state) ["(nope 1)"])]
      (is (str/starts-with? raised "Error: "))
      (is (= 1 (count (transactions state))))
      (is (some? (get-in state [:indexer :manifest-address])))))
  (testing "a program whose VM parks is still indexed"
    (let [[state [text]] (evaluate (repl.frontends/create-state {:vm-type :stack})
                                   ["(require (quote mod))"])]
      (is (str/includes? text "pending"))
      (is (some? (:pending-run state)))
      (is (= 1 (count (transactions state)))))))


(deftest every-evaluator-is-indexed-the-same-way
  ;; Gate B: every evaluator, on every non-DHT backend, through the
  ;; fixture's explicitly injected handle.
  (doseq [kind fixture/backend-kinds
          vm-type (keys repl/vm-constructors)]
    (testing (str kind " " vm-type)
      (fixture/call-with-state
        kind {:vm-type vm-type}
        (fn [state _backend]
          (let [[state [text]] (evaluate state ["(+ 1 2)"])]
            (is (= "3" text))
            (is (= 1 (count (transactions state))))))))))


(defn- small-medium
  "A ring medium of `capacity` and an observer attached at its oldest
   position, as make-session composes program-out."
  [capacity]
  (let [writer (:dao.stream/handle
                 (ring/create! {:dao.stream/type ring/transport-type
                                ring/capacity-key capacity}))
        descriptor (:dao.stream/descriptor (stream/descriptor writer))
        attach! (ring/make-attacher {(:dao.stream/identity descriptor) writer})]
    {:writer writer
     :observer (observer/attach attach! descriptor)}))


(defn- committed-values
  "The literal values of every program the indexer committed."
  [indexer]
  (into #{}
        (comp (map :dao.space/transaction)
              (mapcat :datoms)
              (keep (fn [[_ a v]] (when (= :yin/value a) v))))
        (stream-values (:local indexer))))


(defn- literal-packet
  [v]
  (macro/ast->packet {:type :literal, :value v}))


(deftest a-gap-on-the-index-reader-loses-the-indexer-until-rebuilt
  (let [{:keys [writer observer]} (small-medium 2)
        indexer (repl.index/make-indexer
                  {:observer observer
                   :session-token "token"
                   :content-store (mem/create-content-mem)})
        _ (stream/append! writer (literal-packet 1))
        indexed (repl.index/step indexer 1)
        _ (doseq [v [2 3 4]] (stream/append! writer (literal-packet v)))
        lost (repl.index/step indexed 2)
        _ (stream/append! writer (literal-packet 5))
        still-lost (repl.index/step lost 3)]
    (is (= {:transactions 1 :published? true :lost? false :gaps 0 :failure nil}
           (repl.index/status indexed)))
    (testing "the gap is counted and marks the indexer lost"
      (is (= 1 (:gaps (repl.index/status lost))))
      (is (true? (:lost? (repl.index/status lost))))
      (is (= #{1} (committed-values lost))
          "neither the evicted packet nor the retained ones after it are called indexed"))
    (testing "nothing further is indexed while lost"
      (is (= 1 (:transactions (repl.index/status still-lost))))
      (is (= #{1} (committed-values still-lost)))
      (is (some? (repl.index/notice lost still-lost))
          "every round while lost says its code was not indexed"))))


(deftest only-derived-publication-state-is-retained-between-rounds
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-state
        kind
        (fn [initial _backend]
          (let [held-keys #{:observer :local :content-store :session-token
                            :publish-opts :publication-media :publication-seq
                            :pending-publications :next-e :transactions
                            :published :published-payloads :manifest-address
                            :lost? :failure :allocator :materialization}
                rounds (reductions
                         (fn [[state _] line] (repl/eval-input state line))
                         [initial nil]
                         ["(+ 1 2)" "(def f (fn [x] (* x x)))" "(f 7)" "(+ 40 2)"])]
            (doseq [[i [state _]] (map-indexed vector (rest rounds))
                    :let [ix (:indexer state)
                          records (stream-values (:local ix))]]
              (testing (str "after round " (inc i))
                (is (= held-keys (set (keys ix)))
                    "only the allocator and verified coverage outlive the round")
                (is (nil? (:intake-pool (:allocator ix))))
                (is (= (:published ix) (get-in ix [:materialization :transactions])))
                (is (= (:dao.stream/cursor (stream/cursor (:local ix) :dao.stream/newest))
                       (get-in ix [:materialization :cursor])))
                (is (= (select-keys (:materialization ix)
                                    [:cursor :transactions])
                       (:index-publication (repl/repl-state state))))
                (is (every? #(contains? % :dao.space/transaction) records)
                    "the local log holds transaction records only, no publication payload")
                (is (= (inc i) (count records)))
                (is (true? (:published? (repl.index/status ix))))
                (is (pos? (:published-payloads ix))
                    "the round did publish; its payloads went to dao.jing, not the indexer")))
            (testing "transaction time continues through the retained allocator"
              (let [ix (:indexer (first (last rounds)))]
                (is (= [0 1 2 3]
                       (mapv (comp :t :dao.space/transaction)
                             (stream-values (:local ix)))))))))))))


(defn- full-publication
  "Today's full-history publisher, used as an independent reference."
  [ix]
  (let [intake (:dao.stream/handle
                 (memory-log/create! {:dao.stream/type memory-log/transport-type}))
        tx (transactor/create! {:local-stream (:local ix) :intake-pool [intake]})
        result (transactor/publish! tx (:publish-opts ix))]
    (doseq [payload (stream-values intake)]
      (jing/materialize! (:content-store ix) payload))
    result))


(deftest incremental-publication-covers-the-same-history-as-full-reconstruction
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-backend
        kind
        (fn [backend]
          (let [{:keys [writer observer]} (small-medium 16)
                initial (repl.index/make-indexer
                          {:observer observer :session-token "equivalence"
                           :content-store (:handle backend)
                           :publish-opts {:branching-factor 4}})]
            (reduce
              (fn [ix round]
                ;; More than one transaction in a round, including repeated content.
                (doseq [v [round round (+ 100 round)]]
                  (stream/append! writer (literal-packet v)))
                (let [next-ix (repl.index/step ix round)
                      store (:content-store next-ix)
                      incremental (index/read-manifest store (:manifest-address next-ix))
                      full (:manifest (full-publication next-ix))]
                  (is (true? (:published? (repl.index/status next-ix))))
                  (is (= (:count full) (:count incremental)))
                  (doseq [order [:eavt :aevt :avet :vaet]]
                    (is (= (vec (index/walk-index-datoms store (get-in full [:indexes order])))
                           (vec (index/walk-index-datoms store (get-in incremental [:indexes order]))))
                        (str "identical covered facts at round " round " in " order)))
                  (is (= (:dao.stream/cursor (stream/cursor (:local next-ix) :dao.stream/newest))
                         (get-in next-ix [:materialization :cursor])))
                  next-ix))
              initial
              (range 1 7))))))))


(deftest a-fresh-round-does-not-reopen-the-retained-history
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-state
        kind
        (fn [shell _backend]
          (let [reads (atom [])
                initial (update-in shell [:indexer :local]
                                   #(->CountingLocal % reads))
                [state _] (evaluate initial ["1" "2" "3"])
                before (:indexer state)
                _ (reset! reads [])
                [after result] (repl/eval-input state "4")]
            (is (= "4" result))
            (is (not-any? #(= [:cursor :dao.stream/oldest] %) @reads)
                "neither allocation nor publication reopens the prefix")
            (is (= 2 (count (filter #(= :next (first %)) @reads)))
                "one suffix record and one blocked read, independent of history")
            (is (identical? (:next-t (:allocator before))
                            (get-in after [:indexer :allocator :next-t])))
            (is (= [0 1 2 3] (mapv :t (transactions after))))))))))


(deftest incremental-roots-match-a-full-insertion-replay
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-backend
        kind
        (fn [backend]
          (let [{:keys [writer observer]} (small-medium 16)
                store (:handle backend)
                initial (repl.index/make-indexer
                          {:observer observer :session-token "roots" :content-store store
                           :publish-opts {:branching-factor 16}})
                ix (reduce (fn [ix round]
                             (stream/append! writer (literal-packet round))
                             (repl.index/step ix round))
                           initial (range 1 13))
                intake (:dao.stream/handle
                         (memory-log/create! {:dao.stream/type memory-log/transport-type}))
                replay (reduce index/fold-batch
                               (index/session {:mode :resolved :schema {} :intake intake
                                               :branching-factor 16})
                               (stream-values (:local ix)))
                result (index/publish! replay)]
            (is (= :ok (:status result)))
            (is (= (get-in ix [:materialization :manifest])
                   (get-in result [:state :publish :manifest]))
                "all four root addresses equal a full insertion replay, including splits")))))))


(deftest a-stale-or-foreign-live-cursor-falls-back-to-full-reconstruction
  ;; Frontier gate, cursor ownership: a stale, ahead, or foreign cursor
  ;; is never trusted, on either non-DHT backend (spec section 8).
  (doseq [kind fixture/backend-kinds
          cursor-kind [:stale :ahead :foreign]]
    (testing (str kind " " cursor-kind)
      (fixture/call-with-backend
        kind
        (fn [backend]
          (let [{:keys [writer observer]} (small-medium 16)
                reads (atom [])
                initial (update (repl.index/make-indexer
                                  {:observer observer :session-token "cursor-validation"
                                   :content-store (:handle backend)
                                   :publish-opts {:branching-factor 4}})
                                :local #(->CountingLocal % reads))
                _ (stream/append! writer (literal-packet 10))
                first-ix (repl.index/step initial 1)
                _ (stream/append! writer (literal-packet 20))
                second-ix (repl.index/step first-ix 2)
                foreign (:dao.stream/handle
                          (memory-log/create! {:dao.stream/type memory-log/transport-type}))
                _ (stream/append! writer (literal-packet 30))
                ;; Stage one authoritative transaction beyond the published frontier.
                pending (repl.index/commit-names
                          (assoc second-ix
                                 :publication-media
                                 (fixture/engine-media
                                   (fixture/refusing-media)))
                          [] 3)
                cursor (case cursor-kind
                         :stale (get-in first-ix [:materialization :cursor])
                         :ahead (:dao.stream/cursor (stream/cursor (:local pending) :dao.stream/newest))
                         :foreign (:dao.stream/cursor (stream/cursor foreign :dao.stream/oldest)))
                bad (-> pending
                        (assoc :publication-media nil)
                        (assoc-in [:materialization :cursor] cursor))
                _ (reset! reads [])
                fixed (repl.index/step bad 4)
                f (fixture/frontier fixed)]
            (is (some #(= [:cursor :dao.stream/oldest] %) @reads)
                (str cursor-kind " cursor triggers full reconstruction"))
            (is (true? (:published? (repl.index/status fixed))))
            (is (= (:manifest (full-publication fixed))
                   (index/read-manifest (:content-store fixed) (:manifest-address fixed))))
            (testing "the reconstructed frontier agrees on every gate field"
              (is (= (:manifest-address f) (:manifest-identity f)))
              (is (= (:published f) (:transactions f) (:materialized-transactions f)))
              (is (= 4 (:branching-factor f)) "the declared count cap, not a default")
              (is (= (:cursor f) (:newest-cursor f))))))))))


(deftest unreadable-dirty-content-and-head-failure-never-promote-the-cursor
  ;; Frontier gate: missing content or a failed HEAD write must not
  ;; advance the frontier, on either backend (spec section 8).
  (doseq [kind fixture/backend-kinds
          stage [:content :head]]
    (testing (str kind " " stage)
      (fixture/call-with-backend
        kind
        (fn [backend]
          (let [{:keys [writer observer]} (small-medium 16)
                inner (:handle backend)
                armed? (atom false)
                hidden (atom nil)
                heads (atom [])
                content-store (assoc inner
                                     :put-bytes-fn
                                     (fn [address bs]
                                       (let [result ((:put-bytes-fn inner) address bs)
                                             blob (jing/get inner address nil)]
                                         (when (and @armed? (= stage :content)
                                                    (map? blob) (contains? blob :keys))
                                           (reset! hidden address))
                                         result))
                                     :get-bytes-fn
                                     (fn [address missing]
                                       (if (= address @hidden)
                                         missing
                                         ((:get-bytes-fn inner) address missing))))
                write-head! (fn [address]
                              (when (and @armed? (= stage :head))
                                (throw (ex-info "HEAD refused" {})))
                              (swap! heads conj address))
                media (fixture/head-media write-head!)
                initial (repl.index/make-indexer
                          {:observer observer :session-token "failure"
                           :content-store content-store
                           :publication-media media
                           :publish-opts {:branching-factor 4}})
                _ (stream/append! writer (literal-packet 1))
                before (fixture/step media initial 1)
                _ (reset! armed? true)
                _ (stream/append! writer (literal-packet 2))
                failed (fixture/step media before 2)]
            (is (false? (:published? (repl.index/status failed))))
            (is (= :publish (get-in failed [:failure :stage])))
            (is (= (:materialization before) (:materialization failed))
                "failed HEAD persistence must not advance the frontier")
            (is (= (:manifest-address before) (:manifest-address failed)))
            (is (= [(:manifest-address before)] @heads))
            (reset! armed? false)
            (reset! hidden nil)
            (stream/append! writer (literal-packet 3))
            (let [retried (fixture/step media failed 3)]
              (is (true? (:published? (repl.index/status retried))))
              (is (nil? (:failure retried)))
              (is (= 3 (:published retried)))
              (is (= #{1 2 3} (committed-values retried)))
              (is (= 2 (count @heads))))))))))


(deftest recovery-discards-live-cursors-and-derives-authority-from-history
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-backend
        kind
        (fn [backend]
          (let [{:keys [writer observer]} (small-medium 16)
                initial (repl.index/make-indexer
                          {:observer observer :session-token "recovery"
                           :content-store (:handle backend)})
                _ (stream/append! writer (literal-packet 10))
                first-ix (repl.index/step initial 1)
                _ (stream/append! writer (literal-packet 20))
                second-ix (repl.index/step first-ix 2)
                recovery {:manifest (:manifest-address second-ix)
                          :datoms (index/snapshot-datoms (:local second-ix))
                          ;; No stale process cursor or allocator is recovery authority.
                          :materialization (:materialization first-ix)
                          :allocator (:allocator first-ix)}
                reads (atom [])
                recovered (update (repl.index/rehydrate second-ix recovery)
                                  :local #(->CountingLocal % reads))
                _ (stream/append! writer (literal-packet 30))
                resumed (repl.index/step recovered 3)]
            (is (nil? (:allocator recovered)))
            (is (nil? (:materialization recovered)))
            (is (some #(= [:cursor :dao.stream/oldest] %) @reads)
                "restart takes the authoritative full reconstruction path")
            (is (= [0 1 2]
                   (mapv (comp :t :dao.space/transaction) (stream-values (:local resumed)))))
            (is (= #{10 20 30} (committed-values resumed)))
            (is (= (:manifest (full-publication resumed))
                   (index/read-manifest (:content-store resumed) (:manifest-address resumed)))
                "the first recovered publication is exactly today's full reconstruction")))))))


(deftest recovery-derives-the-allocator-from-transaction-times-not-record-count
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-backend
        kind
        (fn [backend]
          (let [{:keys [writer observer]} (small-medium 16)
                initial (repl.index/make-indexer
                          {:observer observer :session-token "watermark"
                           :content-store (:handle backend)})
                datoms [[100 :test/value :earlier 7 1] [101 :test/value :later 43 1]]
                _ (doseq [d datoms]
                    (stream/append! (:local initial)
                                    {:dao.space/transaction {:t (nth d 3) :datoms [d]}}))
                published (full-publication initial)
                recovered (repl.index/rehydrate initial {:manifest (:manifest-address published)
                                                         :datoms datoms})
                _ (stream/append! writer (literal-packet 99))
                resumed (repl.index/step recovered 1)
                txs (mapv :dao.space/transaction (stream-values (:local resumed)))]
            (is (= [7 43 44] (mapv :t txs)))
            (is (= 45 (transactor/watermark (:allocator resumed))))
            (is (every? #(<= 102 (first %)) (:datoms (last txs))))
            (is (true? (:published? (repl.index/status resumed))))))))))


(deftest a-publication-beyond-the-old-intake-bound-completes
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-backend
        kind
        (fn [backend]
          (let [{:keys [writer observer]} (small-medium 16)
                store (:handle backend)
                indexer (repl.index/make-indexer
                          {:observer observer
                           :session-token "token"
                           :content-store store
                           ;; a small branching factor makes many node blobs
                           :publish-opts {:branching-factor 4}})
                program (fn [base]
                          (macro/ast->packet
                            {:type :application
                             :operator {:type :variable, :name '+}
                             :operands (mapv (fn [i] {:type :literal, :value (+ base i)})
                                             (range 300))}))
                _ (doseq [base [0 1000 2000 3000]]
                    (stream/append! writer (program base)))
                ix (repl.index/step indexer 1)
                committed (into #{}
                                (mapcat (comp :datoms :dao.space/transaction))
                                (stream-values (:local ix)))
                address (:manifest-address ix)]
            (is (< 4096 (:published-payloads ix))
                "one publication exceeds the old 4096-payload ring intake")
            (is (= {:transactions 4 :published? true :lost? false :gaps 0 :failure nil}
                   (repl.index/status ix)))
            (is (= (count committed) (:count (index/read-manifest store address))))
            (is (= committed (set (index/read-datoms store address)))
                "the manifest and every node blob are read back from the store")))))))


(deftest a-retention-only-failure-is-reported-though-indexing-succeeds
  ;; cc3 r8 F1/F4: the store refuses only provenance writes, so the
  ;; indexer publishes and has nothing to report; the round must still
  ;; warn — through the retention diagnostic, not the indexer.
  (doseq [[label fail-key]
          [["relation write" :yin.vm.provenance/schema]
           ["record write" :yin.repl.call-provenance/schema]]]
    (testing label
      (let [inner (mem/create-content-mem)
            store (assoc inner
                         :put-bytes-fn
                         (fn [address bs]
                           (let [v (try (cbor/decode bs)
                                        (catch #?(:cljd Object :clj Exception
                                                  :cljs :default) _ nil))]
                             (when (and (map? v) (contains? v fail-key))
                               (throw (ex-info "store refused the provenance write"
                                               {})))
                             ((:put-bytes-fn inner) address bs))))
            [state [text]] (evaluate
                             (repl.frontends/create-state {:index-store store})
                             ["(+ 1 2)"])
            status (get-in (repl/repl-state state) [:index])]
        (is (str/starts-with? text "3\nWarning: "))
        (is (str/includes? text "call provenance was not retained"))
        (is (str/includes? text (str label " failed")))
        (is (true? (:published? status)) "indexing itself succeeded")
        (is (nil? (:failure status)) "the indexer has no failure to report")
        (is (empty? (repl/call-provenance-handles state))
            "no handle is advertised for the failed retention")
        (is (= 1 (count (repl/call-provenance-failures state))))))))


(deftest a-publication-failure-is-reported-in-the-round-and-repl-state
  (let [broken {:put-bytes-fn (fn [_address _bytes]
                                (throw (ex-info "store refused the write" {})))
                :get-bytes-fn (fn [_address not-found] not-found)}
        [state [text again]] (evaluate (repl.frontends/create-state {:index-store broken})
                                       ["(+ 1 2)" "(+ 2 3)"])
        status (get-in (repl/repl-state state) [:index])]
    (is (str/starts-with? text "3\nWarning: "))
    (is (str/includes? text "materialize failed: store refused the write")
        "the program's rows are the round's first write (yin.vm.linker.dht.md 5.1)")
    (is (str/starts-with? again "5\nWarning: ")
        "each round whose code is not published says so")
    (is (= 2 (:transactions status)) "the transactions were committed")
    (is (false? (:published? status)))
    (is (= :materialize (get-in status [:failure :stage])))
    (is (false? (:lost? status)))))


;; =============================================================================
;; L4: rows every round, and the name transaction
;; (docs/design/yin.vm.linker.dht.md 5.1, 6.1)
;; =============================================================================

(deftest every-row-of-each-evaluated-program-is-in-the-index-store
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-state
        kind
        (fn [shell _backend]
          (let [[state _] (evaluate shell
                                    ["(def inc2 (fn [x] (+ x 2)))" "(inc2 40)"])
                store (get-in state [:indexer :content-store])
                packets (stream-values (:row-stream state))
                roots (set (keep (fn [[_ a v]] (when (= :yin.repl/root a) v))
                                 (mapcat :datoms (transactions state))))]
            (is (= 2 (count packets)))
            (is (= roots (set (map first packets)))
                "each program's root address is its :yin.repl/root fact")
            (doseq [packet packets
                    [id row] (:rows (macro/packet->row-set packet))]
              (is (= (subvec row 1) (jing/get store id nil))
                  (str "row " id " is stored under its own address")))))))))


(deftest rows-a-round-could-not-write-are-written-before-any-later-head-moves
  (let [broken? (atom true)
        heads (atom [])
        inner (mem/create-content-mem)
        store (assoc inner
                     :put-bytes-fn (fn [address bs]
                                     (when @broken?
                                       (throw (ex-info "store refused" {})))
                                     ((:put-bytes-fn inner) address bs))
                     :head-fn (fn [m] (swap! heads conj m)))
        [state [first-text second-text]]
        (evaluate (repl.frontends/create-state {:index-store store})
                  ["(def a 4101)" "(def b 4102)"])
        packets (stream-values (:row-stream state))]
    (testing "while rows cannot be written, no round publishes and HEAD stays"
      (is (str/includes? first-text "materialize failed"))
      (is (str/includes? second-text "materialize failed"))
      (is (= [] @heads))
      (is (= 2 (:transactions (repl.index/status (:indexer state))))))
    (reset! broken? false)
    (let [[state [text]] (evaluate state ["(def c 4103)"])
          status (repl.index/status (:indexer state))
          rows (mapcat (comp vals :rows macro/packet->row-set)
                       (stream-values (:row-stream state)))]
      (testing "the next round writes the earlier rows first, then publishes"
        (is (= 3 (count (stream-values (:row-stream state)))))
        (is (every? (fn [[id & body]] (= (vec body) (jing/get store id nil))) rows)
            "every committed program's rows are held")
        (is (= 1 (count @heads)) "one HEAD move, covering all three")
        (is (= 3 (count (filter #(= :yin.repl/root (second %))
                                (index/read-datoms store (first @heads))))))
        (is (true? (:published? status)))
        (is (nil? (:failure status)) "the materialize failure is recovered")
        (is (str/starts-with? text "4103"))
        (is (str/includes? text "rows of earlier rounds are now written"))))
    (is (= 2 (count packets)))))


(deftest the-name-envelopes-are-one-transaction-under-the-session-s-metadata
  (let [[state _] (evaluate (repl.frontends/create-state) ["(def f (fn [] 1))"])
        key (sign/generate)
        signed [(publish/assertion key {:name 'lib :seq 1
                                        :manifest (jing/segment-key "m")})]
        indexer (repl.index/commit-names (:indexer state) signed 7)
        status (repl.index/status indexer)
        datoms (index/read-datoms (:content-store indexer)
                                  (:manifest-address indexer))
        [ev] (keep (fn [[e a]] (when (= :yin.module/envelope a) e)) datoms)
        of-ev (filterv #(= ev (first %)) datoms)
        m (nth (first of-ev) 4)]
    (is (= 2 (:transactions status)))
    (is (true? (:published? status)) "committed and published at once")
    (is (= #{[:yin.module/envelope (:envelope (first signed))]
             [:yin.module/proof (:proof (first signed))]}
           (set (map (fn [[_ a v]] [a v]) of-ev)))
        "one entity per envelope: its envelope and its proof")
    (is (= #{[:db/op :db/assert] [:yin.repl/session (:shell-token state)]
             [:yin.repl/round 7]}
           (set (keep (fn [[e a v]] (when (= m e) [a v])) datoms)))
        "m is the session's metadata entity, with no program root")
    (is (= [] (filterv #(= :yin/address (second %)) of-ev)))))


(deftest an-index-gap-is-reported-evaluation-continues-and-reset-recovers
  (let [{:keys [writer observer]} (small-medium 1)
        state (repl.frontends/create-state)
        _ (doseq [v [1 2]] (stream/append! writer (literal-packet v)))
        lossy (assoc-in state [:indexer :observer] observer)
        [lost text] (repl/eval-input lossy "(+ 1 2)")
        [later text'] (repl/eval-input lost "(+ 2 3)")]
    (testing "the gap is reported in the round result; evaluation continues"
      (is (str/starts-with? text "3\nWarning: "))
      (is (str/includes? text "(reset)"))
      (is (false? (:ingress-loss? lost)) "the evaluator is not refused")
      (is (= 0 (get-in (repl/repl-state lost) [:vm :in-stream :gaps]))
          "the evaluator's own readers lost nothing"))
    (testing "the indexer is lost, and says so, until reset"
      (is (= {:transactions 0 :published? false :lost? true :gaps 1 :failure nil}
             (get-in (repl/repl-state lost) [:index])))
      (is (str/starts-with? text' "5\nWarning: "))
      (is (= 0 (get-in (repl/repl-state later) [:index :transactions]))))
    (testing "reset rebuilds the observer with the session"
      (let [[fresh message] (repl/eval-input later "(reset)")
            [indexed result] (repl/eval-input fresh "(+ 1 2)")]
        (is (= "SemanticVM reset" message))
        (is (not (identical? (:indexer later) (:indexer fresh))))
        (is (= {:transactions 0 :published? false :lost? false :gaps 0
                :failure nil}
               (get-in (repl/repl-state fresh) [:index])))
        (is (identical? (:index-store later) (:index-store fresh))
            "the dao.jing store outlives the session")
        (is (= "3" result) "a healthy round carries no notice")
        (is (= 1 (count (transactions indexed))))
        (is (true? (get-in (repl/repl-state indexed)
                           [:index :published?])))))))


(deftest vm-selection-rebuilds-the-indexer
  (let [[state _] (evaluate (repl.frontends/create-state) ["(+ 1 2)"])
        [switched _] (repl/eval-input state "(vm :ast-walker)")]
    (is (= 1 (count (transactions state))))
    (is (empty? (transactions switched)))
    (is (not (identical? (get-in state [:indexer :observer :stream])
                         (get-in switched [:indexer :observer :stream]))))))


;; =============================================================================
;; The durable round's HEAD write — only after the manifest is read back
;; (the durable-store design, section 2). The memory store has no
;; after-publish hook and writes no HEAD.
;; =============================================================================

(defn- temp-store-dir
  []
  (str "target/test-index-head-" (random-uuid)))


(defn- cleanup-store-dir!
  [dir]
  #?(:cljd (try (.deleteSync (dart-io/Directory. dir) .recursive true)
                (catch Object _ nil))
     :clj (let [f (java.io.File. ^String dir)]
            (when (.isDirectory f)
              (doseq [child (.listFiles f)]
                (.delete ^java.io.File child))
              (.delete f)))
     :cljs (try (.rmSync (js/require "fs") dir #js {:recursive true :force true})
                (catch :default _ nil))))


(defn- head-text
  [dir]
  #?(:cljd (let [f (dart-io/File. (str dir "/HEAD"))]
             (when (.existsSync f) (.readAsStringSync f)))
     :clj (let [f (java.io.File. (str dir "/HEAD"))]
            (when (.exists f) (slurp f)))
     :cljs (let [fs (js/require "fs")
                 path (str dir "/HEAD")]
             (when (.existsSync fs path)
               (.readFileSync fs path "utf8")))))


(deftest a-durable-round-writes-head-only-after-the-manifest-read-back
  (testing "a healthy round: blobs, read-back, then HEAD names the manifest"
    (let [dir (temp-store-dir)
          opened (store/open {:type :file :dir dir})]
      (try
        (let [[state text] (repl/eval-input
                             (repl.frontends/create-state {:index-store opened})
                             "(+ 1 2)")
              manifest (get-in state [:indexer :manifest-address])]
          (is (= "3" text))
          (is (true? (get-in (repl/repl-state state) [:index :published?])))
          (is (str/includes? (str (head-text dir)) (str manifest))
              "HEAD names the manifest the round published"))
        (finally
          (store/close! opened)
          (cleanup-store-dir! dir)))))
  (testing "a round whose manifest cannot be read back writes no HEAD"
    (let [dir (temp-store-dir)
          opened (store/open {:type :file :dir dir})]
      (try
        (let [broken (assoc opened
                            :get-bytes-fn (fn [_address _not-found]
                                            (throw (ex-info
                                                     "store refused the read"
                                                     {}))))
              [state text] (repl/eval-input
                             (repl.frontends/create-state {:index-store broken})
                             "(+ 1 2)")]
          (is (str/starts-with? text "3\nWarning: ")
              "the round evaluates and says what its publication lost")
          (is (= :publish (get-in (repl/repl-state state)
                                  [:index :failure :stage])))
          (is (nil? (head-text dir))
              "no manifest read-back, no HEAD — never a HEAD naming an
               unreadable snapshot")
          (is (pos? (count (jing.file/records (durable/content-path dir))))
              "the blobs themselves did land"))
        (finally
          (store/close! opened)
          (cleanup-store-dir! dir)))))
  (testing "a round whose HEAD write fails reports the round unpublished"
    (let [dir (temp-store-dir)
          opened (store/open {:type :file :dir dir})]
      (try
        (let [[first-state _] (repl/eval-input
                                (repl.frontends/create-state {:index-store opened})
                                "(+ 1 2)")
              first-manifest (get-in first-state [:indexer :manifest-address])
              failing (assoc opened
                             :head-fn (fn [_manifest]
                                        (throw (ex-info "disk gone" {}))))
              [second-state text] (repl/eval-input
                                    (repl.frontends/create-state {:index-store failing})
                                    "(+ 2 3)")]
          (is (str/starts-with? text "5\nWarning: "))
          (is (false? (get-in (repl/repl-state second-state)
                              [:index :published?])))
          (is (nil? (get-in second-state [:indexer :manifest-address]))
              "the round whose HEAD did not move reports no manifest")
          (is (str/includes? (str (head-text dir)) (str first-manifest))
              "the previous published snapshot keeps the HEAD"))
        (finally
          (store/close! opened)
          (cleanup-store-dir! dir))))))


;; =============================================================================
;; Step 1 pins: explicit publication-result expectations (spec sections
;; 3, 4 and 8).  Today the engine publishes synchronously through the
;; handle's `:head-fn` (`yin.repl.index`'s `:after-publish`, after
;; materialize/read-back); Step 2 re-expresses that call as the section 4
;; request/result records on `dao.stream` media, settled through
;; `yin.repl/accept-publication-result`.  Two kinds of pin follow and
;; must not be confused.  The synchronous pins (this section, through
;; `:head-fn`) exercise only the cases that seam can deliver -- matching
;; success and refusal -- and pin the correlation that move must
;; preserve: a round's frontier -- manifest identity, published
;; transaction count, count-cap compatibility, cursor ownership, and
;; expected suffix length -- is promoted exactly once, only for the
;; round whose local durable publication succeeded, and never by a
;; failed HEAD write, a mismatched policy, or a repeated settlement.
;; The protocol oracle (`publication-result-protocol-oracle-for-step-2`)
;; specifies the remaining cases -- delayed completion, wrong
;; id/round/manifest, duplicate and stale delivery -- against the
;; fixture's test-local model; Step 2 connects those same records to
;; the real seam without changing their expected outcomes.
;; =============================================================================

(deftest frontier-gate-agrees-after-every-round-on-every-backend
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-state
        kind
        (fn [shell _backend]
          (let [rounds (reductions
                         (fn [[state _] line] (repl/eval-input state line))
                         [shell nil]
                         ["(def a 4101)" "(def b 4102)" "(+ a b)"])]
            (doseq [[i [state _]] (map-indexed vector (rest rounds))]
              (testing (str "after round " (inc i))
                (let [ix (:indexer state)
                      f (fixture/frontier ix)
                      committed (into #{} (mapcat :datoms) (transactions state))]
                  (is (= (:manifest-address f) (:manifest-identity f))
                      "manifest identity: the address is the manifest's content address")
                  (is (= #{:indexes :count :branching-factor}
                         (set (keys (:manifest f))))
                      "the manifest schema is exactly {:indexes :count :branching-factor}")
                  (is (= #{:eavt :aevt :avet :vaet}
                         (set (keys (:indexes (:manifest f))))))
                  (is (= (inc i) (:transactions f) (:published f)
                         (:materialized-transactions f))
                      "published transaction count: the frontier covers every committed transaction")
                  (is (= (:count f) (count committed))
                      "the manifest's count is the covered datom count")
                  (is (= (:branching-factor f) (:expected-branching-factor f) 512)
                      "count-cap compatibility: the manifest carries the declared policy's cap")
                  (is (= (:cursor f) (:newest-cursor f))
                      "cursor ownership: the retained cursor is this log's newest cursor")
                  (is (= committed
                         (set (index/read-datoms (:content-store ix)
                                                 (:manifest-address f))))
                      "the promoted manifest reads back the committed history")
                  (is (true? (:published? (:status f)))))))))))))


(deftest publication-requests-are-correlated-settle-once-and-in-order
  ;; Synchronous pin, matching-success case only: the composition-side
  ;; interpreter (fixture/observed-backend) records each durable
  ;; publication the engine emits as a section 4 result record.  Today
  ;; the fixture mints `:id` / `:round` in emission order because the
  ;; `:head-fn` seam carries only the manifest; Step 2's request record
  ;; carries the engine's own identifiers and this pin then checks them
  ;; instead.  A successful local result promotes exactly the manifest
  ;; its request named, once.  Delayed, mismatched, duplicate, and stale
  ;; delivery cannot occur through this seam; see the protocol oracle.
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-backend
        kind
        (fn [backend]
          (let [log (atom [])
                backend (fixture/observed-backend backend log)
                state (fixture/create-state backend)
                [s1 _] (repl/eval-input state "(def a 4101)")
                m1 (get-in s1 [:indexer :manifest-address])
                [s2 _] (repl/eval-input s1 "(def b 4102)")
                m2 (get-in s2 [:indexer :manifest-address])
                [s3 _] (repl/eval-input s2 "(def c 4103)")
                m3 (get-in s3 [:indexer :manifest-address])]
            (is (= [m1 m2 m3] (mapv :manifest @log))
                "one publication request per round, in round order, each naming the manifest its round promoted")
            (is (apply distinct? [m1 m2 m3])
                "each round's publication is a distinct manifest")
            (is (every? #(= (fixture/backend-durability kind) (:durability %)) @log)
                "each result claims only its backend's local durability; network acknowledgement is never claimed")
            (is (= [:publication-result :publication-result :publication-result]
                   (mapv :op @log))
                "each record is a section 4 publication result")
            (is (= [:published :published :published] (mapv :status @log))
                "each synchronous result is a matching success")
            (is (= [1 2 3] (mapv :id @log) (mapv :round @log))
                "correlation is explicit: distinct ids in round order, unique within the session")
            (is (= [{:op :publish :id 1 :round 1 :manifest m1}
                    {:op :publish :id 2 :round 2 :manifest m2}
                    {:op :publish :id 3 :round 3 :manifest m3}]
                   (fixture/publication-requests @log))
                "each result answers the request naming the manifest its round promoted")
            (testing "a step with nothing new settles nothing twice"
              (let [before (fixture/frontier (:indexer s3))
                    again (repl.index/step (:indexer s3) 99)
                    after (fixture/frontier again)]
                (is (= (:manifest-address before) (:manifest-address after)))
                (is (= (:materialization before) (:materialization after)))
                (is (= 3 (count @log)) "no second request for a settled frontier")))))))))


(deftest failed-head-persistence-never-advances-the-frontier
  ;; Synchronous pin, refusal case (spec sections 4 and 8): a failed
  ;; HEAD write is a refused local publication result.  The prepared
  ;; manifest may exist in the store, but the frontier -- manifest,
  ;; published count, cursor -- stays at the last successful result
  ;; until a later round succeeds.  The shell continuation past that
  ;; refusal is the known defect retained as a failing acceptance pin
  ;; in `shell-retry-after-refused-publication-promotes-the-whole-suffix`
  ;; below; the indexer continuation here stays the narrower green
  ;; oracle, as the unreadable-content pin already proves it.
  (testing "at the shell: the refused round keeps the previous frontier"
    (doseq [kind fixture/backend-kinds]
      (testing (str kind)
        (fixture/call-with-backend
          kind
          (fn [backend]
            (let [log (atom [])
                  fail? (atom false)
                  backend (fixture/failable-backend backend log fail?)
                  state (fixture/create-state backend)
                  [s1 _] (repl/eval-input state "(+ 1 2)")
                  f1 (fixture/frontier (:indexer s1))
                  _ (reset! fail? true)
                  [s2 text2] (repl/eval-input s1 "(+ 2 3)")
                  f2 (fixture/frontier (:indexer s2))
                  head-after-refusal (when (= :file kind)
                                       (head-text (:dir backend)))]
              (is (true? (:published? (:status f1))))
              (is (str/includes? text2 "Warning: "))
              (is (false? (:published? (:status f2))))
              (is (= :publish (get-in s2 [:indexer :failure :stage])))
              (is (= (:manifest-address f1) (:manifest-address f2)))
              (is (= (:published f1) (:published f2)))
              (is (= (:materialization f1) (:materialization f2))
                  "failed HEAD persistence must not advance the frontier")
              (is (= 2 (count @log)) "the refused round requested publication once")
              (is (= (:manifest-address f1) (:manifest (first @log))))
              (is (= [:published :refused] (mapv :status @log))
                  "the log distinguishes the success from the refusal; a refused attempt is never a success")
              (is (= [1 2] (mapv :id @log) (mapv :round @log))
                  "the refusal answers its own round's request")
              (is (= "HEAD refused" (:reason (second @log)))
                  "the refusal carries its local reason")
              (is (not= (:manifest (second @log)) (:manifest-address f2))
                  "the refused round's prepared manifest was never promoted")
              (when (= :file kind)
                (is (str/includes? (str head-after-refusal)
                                   (str (:manifest-address f1)))
                    "the durable HEAD still names the last successful manifest"))))))))
  (testing "at the indexer: the next successful result promotes the whole suffix"
    (doseq [kind fixture/backend-kinds]
      (testing (str kind)
        (fixture/call-with-backend
          kind
          (fn [backend]
            (let [{:keys [writer observer]} (small-medium 16)
                  log (atom [])
                  fail? (atom false)
                  write-head! (fn [address]
                                (swap! log conj address)
                                (when @fail?
                                  (throw (ex-info "HEAD refused" {}))))
                  media (fixture/head-media write-head!)
                  initial (repl.index/make-indexer
                            {:observer observer :session-token "head-failure"
                             :content-store (:handle backend)
                             :publication-media media
                             :publish-opts {:branching-factor 4}})
                  _ (stream/append! writer (literal-packet 1))
                  ix1 (fixture/step media initial 1)
                  f1 (fixture/frontier ix1)
                  _ (reset! fail? true)
                  _ (stream/append! writer (literal-packet 2))
                  failed (fixture/step media ix1 2)
                  ff (fixture/frontier failed)
                  _ (reset! fail? false)
                  _ (stream/append! writer (literal-packet 3))
                  retried (fixture/step media failed 3)
                  f3 (fixture/frontier retried)]
              (is (false? (:published? (:status ff))))
              (is (= (:manifest-address f1) (:manifest-address ff)))
              (is (= (:materialization f1) (:materialization ff))
                  "the refused result left the frontier exactly as it was")
              (is (true? (:published? (:status f3))))
              (is (= 3 (:published f3) (:transactions f3)
                     (:materialized-transactions f3))
                  "the retry promotes the refused round's suffix with its own")
              (is (= 3 (count @log)) "each round requested publication exactly once")
              (is (= (:manifest-address f1) (first @log)))
              (is (= (:manifest-address f3) (last @log)))
              (is (not= (second @log) (:manifest-address f3))
                  "the refused round's prepared manifest was never promoted"))))))))


(deftest publication-result-protocol-oracle-for-step-2
  ;; Step 2 oracle, not a synchronous pin: the full section 4 result
  ;; protocol, specified against the fixture's test-local model
  ;; (`fixture/accept-result`) because today's `:head-fn` seam cannot
  ;; delay, mismatch, duplicate, or stale a delivery.  Step 2 must make
  ;; `yin.repl/accept-publication-result` over the publication streams
  ;; produce exactly these frontier effects for these same records;
  ;; only then do these cases become engine pins.  The synchronous
  ;; cases above already prove matching success and refusal through the
  ;; real seam; nothing here claims they do.
  (let [f0 {:manifest :m0 :published 1 :round 1}
        request (fixture/publication-request 7 2 :m2)
        promoted {:manifest :m2 :published 2 :round 2}
        pending {:request request :promotes promoted}
        success (fixture/publication-result request :published :persistent)
        refusal (fixture/publication-result request :refused :persistent
                                            "HEAD refused")
        deliver (fn [result] (fixture/accept-result f0 pending result))]
    (testing "the specification covers the full protocol exactly once"
      (is (= #{:matching-success :refusal :delayed-completion :wrong-id
               :wrong-round :wrong-manifest :duplicate :stale}
             (set (map :case fixture/publication-result-expectations))))
      (is (= #{:matching-success :refusal}
             (set (map :case (filter #(= :synchronous (:pinned %))
                                     fixture/publication-result-expectations))))
          "only matching success and refusal are synchronous pins")
      (is (= #{:delayed-completion :wrong-id :wrong-round :wrong-manifest
               :duplicate :stale}
             (set (map :case (filter #(= :step-2-oracle (:pinned %))
                                     fixture/publication-result-expectations))))
          "the rest are Step 2 oracle cases, distinguished from the pins"))
    (testing "matching success promotes exactly the pending frontier"
      (let [out (deliver success)]
        (is (= :promoted (:effect out)))
        (is (= promoted (:frontier out))
            "manifest, published count, and round together, exactly once")
        (is (nil? (:pending out)) "the settled request leaves no pending")))
    (testing "refusal preserves the prior frontier and supersedes pending"
      (let [out (deliver refusal)]
        (is (= :preserved (:effect out)))
        (is (= f0 (:frontier out)) "no field of the frontier moves")
        (is (nil? (:pending out))
            "the refused frontier is superseded; retry is a new request")))
    (testing "delayed completion promotes on arrival, never before"
      (is (= f0 (:frontier {:frontier f0 :pending pending}))
          "while the result is outstanding the frontier stays at F")
      (let [out (deliver success)]
        (is (= :promoted (:effect out)))
        (is (= promoted (:frontier out))
            "the late matching result still promotes exactly its round")))
    (testing "wrong id, round, or manifest settles nothing"
      (doseq [[label result]
              {"wrong id" (assoc success :id 8)
               "wrong round" (assoc success :round 3)
               "wrong manifest" (assoc success :manifest :m-other)}]
        (testing label
          (let [out (deliver result)]
            (is (= :ignored (:effect out)))
            (is (= f0 (:frontier out))
                "neither the result's manifest nor the pending frontier promotes")
            (is (= pending (:pending out))
                "the pending request stays pending for its own result")))))
    (testing "duplicate and stale results cannot promote or regress"
      (let [settled (deliver success)
            duplicate (fixture/accept-result (:frontier settled) nil success)
            newer-request (fixture/publication-request 9 4 :m4)
            newer-pending {:request newer-request
                           :promotes {:manifest :m4 :published 4 :round 4}}
            stale (fixture/accept-result promoted newer-pending success)]
        (is (= :ignored (:effect duplicate)))
        (is (= promoted (:frontier duplicate))
            "a second delivery leaves counts and cursor identical")
        (is (= :ignored (:effect stale)))
        (is (= promoted (:frontier stale))
            "a superseded round's result cannot promote another round")
        (is (= newer-pending (:pending stale)))))
    (testing "durability claims local completion only"
      (is (= :memory (:durability
                       (fixture/publication-result request :published :memory))))
      (is (= :persistent (:durability success)))
      (is (= :publication-result (:op success) (:op refusal))))))


(deftest shell-retry-after-refused-publication-promotes-the-whole-suffix
  ;; KNOWN FAILING shell-level acceptance reproduction (spec sections
  ;; 8 and 11): do not weaken, skip, or delete this pin to green the
  ;; suite.  The narrower indexer oracle for the same continuation
  ;; (`failed-head-persistence-never-advances-the-frontier`, "at the
  ;; indexer") is green; only the shell path is defective.  Step 2
  ;; fixes the engine (`yin.repl.index/notice` renders `(name stage)`
  ;; of the cleared `:publish` failure, so the retry round errors and
  ;; rolls back); this test then passes without edit.
  ;;
  ;; Baseline (section 11 ledger): revision
  ;; b88bad9dd93d233f1d78e5a111444f6c8d86c9aa on branch
  ;; yin-repl-dht-composition, JVM, test identifier
  ;; yin.repl.index-test/shell-retry-after-refused-publication-promotes-the-whole-suffix,
  ;; command `clojure -M:test -n yin.repl.index-test -v
  ;; yin.repl.index-test/shell-retry-after-refused-publication-promotes-the-whole-suffix`.
  ;; Baseline signature: the retry round answers
  ;; "Error: Cannot invoke \"clojure.lang.Named.getName()\" because
  ;; \"x\" is null" instead of "7", `:published?` stays false, and the
  ;; frontier stays at the first round's manifest even though the
  ;; retry's HEAD write succeeded.  Any other failure here is a new
  ;; result, not this known failure.
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-backend
        kind
        (fn [backend]
          (let [log (atom [])
                fail? (atom false)
                backend (fixture/failable-backend backend log fail?)
                state (fixture/create-state backend)
                [s1 _] (repl/eval-input state "(+ 1 2)")
                _ (reset! fail? true)
                [s2 text2] (repl/eval-input s1 "(+ 2 3)")
                _ (reset! fail? false)
                [s3 text3] (repl/eval-input s2 "(+ 3 4)")
                f3 (fixture/frontier (:indexer s3))]
            (is (str/includes? text2 "Warning: ")
                "the refused round warns and keeps the prior frontier")
            (is (= "7" text3)
                "the retry round answers normally once HEAD succeeds again")
            (is (not (str/includes? text3 "Error:"))
                "the retry is not rolled back by rendering its notice")
            (is (true? (:published? (:status f3)))
                "the retry promotes its round")
            (is (= 3 (:published f3) (:transactions f3)
                   (:materialized-transactions f3))
                "the retry promotes the refused round's suffix with its own")
            (is (= [:published :refused :published] (mapv :status @log))
                "one result per round: success, refusal, retry success")
            (is (= (:manifest (last @log)) (:manifest-address f3))
                "the promoted manifest is the retry result's manifest")
            (when (= :file kind)
              (is (str/includes? (str (head-text (:dir backend)))
                                 (str (:manifest-address f3)))
                  "the durable HEAD names the retry's promoted manifest"))))))))


(deftest count-cap-mismatch-rebuilds-instead-of-folding-the-suffix
  ;; Count-cap compatibility (spec sections 5 and 8): suffix reuse is
  ;; permitted only under the same publication policy.  A changed
  ;; `:branching-factor` invalidates the retained frontier, so the next
  ;; publication reconstructs in full and the new manifest carries the
  ;; new cap; the manifest schema itself never changes.
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-backend
        kind
        (fn [backend]
          (let [{:keys [writer observer]} (small-medium 16)
                reads (atom [])
                initial (update (repl.index/make-indexer
                                  {:observer observer :session-token "cap"
                                   :content-store (:handle backend)
                                   :publish-opts {:branching-factor 4}})
                                :local #(->CountingLocal % reads))
                _ (stream/append! writer (literal-packet 1))
                ix1 (repl.index/step initial 1)
                _ (stream/append! writer (literal-packet 2))
                ix2 (repl.index/step ix1 2)
                _ (reset! reads [])
                _ (stream/append! writer (literal-packet 3))
                ix3 (repl.index/step ix2 3)
                same-policy-reads (vec @reads)
                changed (assoc ix3 :publish-opts {:branching-factor 8})
                _ (reset! reads [])
                _ (stream/append! writer (literal-packet 4))
                ix4 (repl.index/step changed 4)
                f4 (fixture/frontier ix4)]
            (testing "under the same policy the suffix folds without reopening"
              (is (not-any? #(= [:cursor :dao.stream/oldest] %) same-policy-reads)
                  "the same-cap round folded only its suffix")
              (is (= 4 (:branching-factor (fixture/frontier ix3)))))
            (testing "under a changed cap the frontier is rebuilt, not folded"
              (is (some #(= [:cursor :dao.stream/oldest] %) @reads)
                  "a count-cap mismatch forces full reconstruction")
              (is (= 8 (:branching-factor f4))
                  "the new manifest carries the new declared cap")
              (is (= #{:indexes :count :branching-factor}
                     (set (keys (:manifest f4))))
                  "the manifest schema is unchanged by the policy change")
              (is (= 4 (:published f4) (:transactions f4)
                     (:materialized-transactions f4)))
              (is (= (:count f4)
                     (count (index/read-datoms (:content-store ix4)
                                               (:manifest-address f4))))))))))))


(deftest the-suffix-folded-is-exactly-the-unpublished-suffix
  ;; Expected suffix length (spec section 8): with a valid frontier, a
  ;; round folds exactly the records past the retained cursor -- here
  ;; two new transactions, read as two hits and one blocked read, with
  ;; no reopening of the covered prefix.
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-backend
        kind
        (fn [backend]
          (let [{:keys [writer observer]} (small-medium 16)
                reads (atom [])
                initial (update (repl.index/make-indexer
                                  {:observer observer :session-token "suffix"
                                   :content-store (:handle backend)
                                   :publish-opts {:branching-factor 4}})
                                :local #(->CountingLocal % reads))
                _ (stream/append! writer (literal-packet 1))
                ix1 (repl.index/step initial 1)
                _ (stream/append! writer (literal-packet 2))
                ix2 (repl.index/step ix1 2)
                before (fixture/frontier ix2)
                _ (reset! reads [])
                _ (stream/append! writer (literal-packet 3))
                _ (stream/append! writer (literal-packet 4))
                ix3 (repl.index/step ix2 3)
                after (fixture/frontier ix3)]
            (is (= 2 (:materialized-transactions before)))
            (is (= 4 (:materialized-transactions after) (:published after)))
            (is (not-any? #(= [:cursor :dao.stream/oldest] %) @reads)
                "the covered prefix is never reopened")
            (is (= 3 (count (filter #(= :next (first %)) @reads)))
                "exactly the two-record suffix and one blocked read")))))))


(deftest a-settled-frontier-is-not-settled-twice
  ;; Duplicate or stale settlement (spec section 4): only the round that
  ;; materialized and read back its manifest promotes the frontier, and
  ;; only once.  Re-stepping with nothing new requests no second
  ;; publication; a refused name publication commits its transaction
  ;; without promoting the frontier past it.
  (doseq [kind fixture/backend-kinds]
    (testing (str kind)
      (fixture/call-with-backend
        kind
        (fn [backend]
          (let [{:keys [writer observer]} (small-medium 16)
                heads (atom [])
                media (fixture/head-media (fn [m] (swap! heads conj m)))
                initial (repl.index/make-indexer
                          {:observer observer :session-token "settle-once"
                           :content-store (:handle backend)
                           :publication-media media
                           :publish-opts {:branching-factor 4}})
                _ (stream/append! writer (literal-packet 1))
                ix1 (fixture/step media initial 1)
                f1 (fixture/frontier ix1)
                again (fixture/step media ix1 2)]
            (is (= [(:manifest-address f1)] @heads)
                "the successful round requested durable publication once")
            (testing "re-stepping a settled frontier settles nothing again"
              (is (= (:materialization f1)
                     (:materialization (fixture/frontier again))))
              (is (= 1 (count @heads))))
            (testing "a refused publication result cannot settle its round"
              (let [refusing (fixture/refusing-media)
                    refused (fixture/commit-names
                              refusing
                              (assoc ix1
                                     :publication-media
                                     (fixture/engine-media refusing))
                              [] 3)
                    fr (fixture/frontier refused)]
                (is (= :publish (get-in refused [:failure :stage])))
                (is (= (:manifest-address f1) (:manifest-address fr)))
                (is (= 1 (:published fr)))
                (is (= 2 (:transactions fr))
                    "the transaction committed; only its promotion was refused")
                (is (false? (:published? (:status fr))))))))))))


;; =============================================================================
;; Cross-host parity (spec section 8): identical supported inputs,
;; publication path, and policy publish identical bytes and addresses
;; on JVM, Node, and Dart.  This is the narrow pre-relocation oracle:
;; one fixed input, one fixed policy, pinned below as exact expected
;; values.  Any host whose canonical encoding, hashing, or publication
;; diverges fails this var; no host-specific value is consulted.
;;
;; Fixed inputs: session token "cross-host-parity", memory backend,
;; publication policy {:branching-factor 4}, and the three literal
;; programs 10, "cross-host", and 30 (int and text payloads).  The
;; shell is bypassed on purpose: its random session token would make
;; every run publish different bytes.  Bulk publishes all three
;; programs in one round (round 1); incremental publishes one program
;; per round (rounds 1, 2, 3).  The two paths must agree logically --
;; the same datoms apart from the :yin.repl/round provenance fact --
;; but need not, and here do not, produce identical tree shapes, so
;; each path pins its own manifest.  Byte identity is pinned by
;; digest: the manifest address (BLAKE3 of its canonical bytes), its
;; SHA-256, each index root's stored-byte SHA-256, and a digest over
;; every stored [address Base64-bytes] pair in address order.
;; =============================================================================

(deftest cross-host-parity-identical-input-publishes-identical-bytes
  (let [bulk-manifest
        {:count 24
         :indexes {:aevt :segment/blake3-52561f3fe45cdc05c97f819e689bc440747f0621549195c54b88155f89558409
                   :avet :segment/blake3-4459c7538f6a3e2d7097af4360f571fd9405bd59b49b8fb661e70afc352f9ebd
                   :eavt :segment/blake3-c846b75553785160e29d25894ba57e67cf18493f36093b599525a931799130fd
                   :vaet :segment/blake3-d9d15e5b074805fe4acb4da8720b71f5b24c7907dd89a5fe0cdcb36c69a0101c}
         :branching-factor 4}
        bulk-datoms
        #{[16 :db/op :db/assert 0 1]
          [16 :yin.repl/root :segment/blake3-fbf65215980851aa6540eed999d772e9f9261012538121d6ee6f47bee6cb2449 0 1]
          [16 :yin.repl/round 1 0 1]
          [16 :yin.repl/session "cross-host-parity" 0 1]
          [17 :yin/address :segment/blake3-fbf65215980851aa6540eed999d772e9f9261012538121d6ee6f47bee6cb2449 0 16]
          [17 :yin/root true 0 16]
          [17 :yin/type :literal 0 16]
          [17 :yin/value 10 0 16]
          [18 :db/op :db/assert 1 1]
          [18 :yin.repl/root :segment/blake3-e12cccd01d218f304d2520052bc57f3ccb90ff5c3fb2a78d310f81bf774e55ab 1 1]
          [18 :yin.repl/round 1 1 1]
          [18 :yin.repl/session "cross-host-parity" 1 1]
          [19 :yin/address :segment/blake3-e12cccd01d218f304d2520052bc57f3ccb90ff5c3fb2a78d310f81bf774e55ab 1 18]
          [19 :yin/root true 1 18]
          [19 :yin/type :literal 1 18]
          [19 :yin/value "cross-host" 1 18]
          [20 :db/op :db/assert 2 1]
          [20 :yin.repl/root :segment/blake3-6e87215003180aa7383304e1a5596ac12db40ba9865b3d9e204e251347e30db4 2 1]
          [20 :yin.repl/round 1 2 1]
          [20 :yin.repl/session "cross-host-parity" 2 1]
          [21 :yin/address :segment/blake3-6e87215003180aa7383304e1a5596ac12db40ba9865b3d9e204e251347e30db4 2 20]
          [21 :yin/root true 2 20]
          [21 :yin/type :literal 2 20]
          [21 :yin/value 30 2 20]}
        incr-manifest
        {:count 24
         :indexes {:aevt :segment/blake3-c48531f6134603737b88e1c6cc60e5be10fc2c7799f5aa21fdb5307a50579be0
                   :avet :segment/blake3-86bf19d5c1e97d25f3df71af65a93e47a0cff51ac11d123bded24387f589779b
                   :eavt :segment/blake3-fe62f3224a73184ba3fd6af2fc02e3f4b2e0459e3d4598162af09f8d549defa8
                   :vaet :segment/blake3-f6da0a46daa15cee059f6a2fc8e882306f45dbd48af61643743274568e3d76a9}
         :branching-factor 4}
        incr-datoms
        #{[16 :db/op :db/assert 0 1]
          [16 :yin.repl/root :segment/blake3-fbf65215980851aa6540eed999d772e9f9261012538121d6ee6f47bee6cb2449 0 1]
          [16 :yin.repl/round 1 0 1]
          [16 :yin.repl/session "cross-host-parity" 0 1]
          [17 :yin/address :segment/blake3-fbf65215980851aa6540eed999d772e9f9261012538121d6ee6f47bee6cb2449 0 16]
          [17 :yin/root true 0 16]
          [17 :yin/type :literal 0 16]
          [17 :yin/value 10 0 16]
          [18 :db/op :db/assert 1 1]
          [18 :yin.repl/root :segment/blake3-e12cccd01d218f304d2520052bc57f3ccb90ff5c3fb2a78d310f81bf774e55ab 1 1]
          [18 :yin.repl/round 2 1 1]
          [18 :yin.repl/session "cross-host-parity" 1 1]
          [19 :yin/address :segment/blake3-e12cccd01d218f304d2520052bc57f3ccb90ff5c3fb2a78d310f81bf774e55ab 1 18]
          [19 :yin/root true 1 18]
          [19 :yin/type :literal 1 18]
          [19 :yin/value "cross-host" 1 18]
          [20 :db/op :db/assert 2 1]
          [20 :yin.repl/root :segment/blake3-6e87215003180aa7383304e1a5596ac12db40ba9865b3d9e204e251347e30db4 2 1]
          [20 :yin.repl/round 3 2 1]
          [20 :yin.repl/session "cross-host-parity" 2 1]
          [21 :yin/address :segment/blake3-6e87215003180aa7383304e1a5596ac12db40ba9865b3d9e204e251347e30db4 2 20]
          [21 :yin/root true 2 20]
          [21 :yin/type :literal 2 20]
          [21 :yin/value 30 2 20]}
        without-round (fn [datoms]
                        (into #{}
                              (remove (fn [[_ a]] (= :yin.repl/round a)))
                              datoms))]
    (testing "bulk: all three programs in one round"
      (let [{:keys [writer observer]} (small-medium 16)
            store (mem/create-content-mem)
            initial (repl.index/make-indexer
                      {:observer observer :session-token "cross-host-parity"
                       :content-store store
                       :publish-opts {:branching-factor 4}})
            _ (doseq [v [10 "cross-host" 30]]
                (stream/append! writer (literal-packet v)))
            ix (repl.index/step initial 1)
            manifest (index/read-manifest store (:manifest-address ix))
            entries ((:entries-fn store))
            all-bytes (mapv (fn [[address bs]]
                              [address (jing/bytes->base64 bs)])
                            (sort-by (comp str key) entries))]
        (is (= {:transactions 3 :published? true :lost? false
                :gaps 0 :failure nil}
               (repl.index/status ix)))
        (is (= :segment/blake3-359408a61ef8080125e16c473ae7e0b7417e7d653f727ccf0663345244620314
               (:manifest-address ix))
            "the manifest content address is host-identical")
        (is (= bulk-manifest manifest)
            "the manifest value, all four roots included, is host-identical")
        (is (= (:manifest-address ix) (jing/segment-key manifest))
            "the address is the BLAKE3 digest of the manifest bytes")
        (is (= "b28c776d1ce50a2a37506b709d66ac5db08eb52f9fcbca59552ef487f89f4f03"
               (jing/content-hash manifest {:algorithm :sha256}))
            "the manifest canonical bytes are host-identical (SHA-256)")
        (is (= bulk-datoms (set (index/read-datoms store (:manifest-address ix))))
            "the published datoms are host-identical")
        (doseq [order [:eavt :aevt :avet :vaet]]
          (is (= bulk-datoms
                 (set (index/walk-index-datoms
                        store (get-in manifest [:indexes order]))))
              (str "the " order " index holds the same facts on every host")))
        (is (= {:eavt "c560da466af133937811a611f4b72d90a0d2073a335964df7e4a1c2eeca3c587"
                :aevt "baf6781eca373fc26fa8ed25c9bdbab4363de30b0a8ab238b996d5be903b2f00"
                :avet "61dfa95e2ae8a5e9a178f73fc90d1dbd17a497669a0a07db598ee43d149af76f"
                :vaet "cd68d81fd0c71345d2097fd76efa67735355fed6e2afdfe7cf0f523c286074cf"}
               (into {}
                     (map (fn [order]
                            [order (jing/sha256-bytes
                                     ((:get-bytes-fn store)
                                      (get-in manifest [:indexes order])
                                      nil))]))
                     [:eavt :aevt :avet :vaet]))
            "every index root's stored bytes are host-identical (SHA-256)")
        (is (= 41 (count entries)) "every published blob is present")
        (is (= :segment/blake3-02c588bf965d70b740874005f4c24b0837316e9bb8891ae4a8257405d488cacf
               (jing/segment-key all-bytes))
            "all stored bytes together are host-identical (BLAKE3)")
        (is (= "74dc4ed6aba2e03d317f56c2b8da5667712942a617e73bc943741b977b51f357"
               (jing/sha256-bytes (jing/canonical-bytes all-bytes)))
            "all stored bytes together are host-identical (SHA-256)")))
    (testing "incremental: the same programs, one round each"
      (let [{:keys [writer observer]} (small-medium 16)
            store (mem/create-content-mem)
            initial (repl.index/make-indexer
                      {:observer observer :session-token "cross-host-parity"
                       :content-store store
                       :publish-opts {:branching-factor 4}})
            ix (reduce (fn [ix [i v]]
                         (stream/append! writer (literal-packet v))
                         (repl.index/step ix (inc i)))
                       initial
                       (map-indexed vector [10 "cross-host" 30]))
            manifest (index/read-manifest store (:manifest-address ix))]
        (is (= {:transactions 3 :published? true :lost? false
                :gaps 0 :failure nil}
               (repl.index/status ix)))
        (is (= :segment/blake3-3fd0b79aab57973364afa1eb24c7f279cc4be90866060ba0621b62f33461b143
               (:manifest-address ix))
            "the incremental manifest address is host-identical")
        (is (= incr-manifest manifest))
        (is (= (:manifest-address ix) (jing/segment-key manifest)))
        (is (= incr-datoms (set (index/read-datoms store (:manifest-address ix)))))
        (is (= (without-round bulk-datoms) (without-round incr-datoms))
            "bulk and incremental agree logically, apart from round provenance")
        (is (not= :segment/blake3-359408a61ef8080125e16c473ae7e0b7417e7d653f727ccf0663345244620314
                  (:manifest-address ix))
            "the two paths need not, and here do not, share a tree shape")))))
