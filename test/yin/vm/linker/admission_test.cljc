(ns yin.vm.linker.admission-test
  "The admission seam, slice AD-1 (yin.vm.linker.dht.md section 7.5;
   collab design r3): the trust question a DHT link source asks before
   it loads a name, the interpreter that answers it, and the shell
   composition around both.

   Every world here is one solo `dao.space.dht` node whose local store
   holds the modules and an index of their signed names, loaded: own
   principal A asserts most names, B asserts `their.m`.  The seam tests
   drive `yin.vm.linker.interpreter/round` over link requests; the
   shell tests drive a REPL whose link source is that node.  Nothing
   waits on a clock: a node step is handed its reading."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.dht.mesh :as mesh]
            [dao.jing.mem :as mem]
            [dao.space.dht :as dht]
            [dao.space.dht-test :as dht-test]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [yin.repl :as repl]
            [yin.repl.frontends :as repl.frontends]
            [yin.repl.main :as main]
            [yin.vm :as vm]
            #?(:cljd nil :clj [yin.vm.ffi :as ffi])
            [yin.vm.linker.admission :as admission]
            [yin.vm.linker.closure-test :as ct]
            [yin.vm.linker.dht :as ld]
            [yin.vm.linker.interpreter :as link]
            [yin.vm.linker.publish :as publish]
            [yin.vm.linker.sign :as sign]
            [yin.vm.module :as module]))


;; =============================================================================
;; The world: one solo node, signed names, an index loaded
;; =============================================================================

(def ^:private a (sign/generate))
(def ^:private b (sign/generate))
(def ^:private pa (sign/principal (:public a)))
(def ^:private pb (sign/principal (:public b)))


(defn- variable
  [n]
  {:type :variable :name n})


(defn- call
  [f & xs]
  {:type :application :operator f :operands (vec xs)})


(def ^:private forty-two
  (call (variable '+) (ct/lit 40) (ct/lit 2)))


(defn- publish!
  "Publish module `n` into `store`, `(yin/def f body)`, after `(require
   'dep)` when `dep` is given, pinned to `dep-address`: its manifest
   address."
  ([store n body] (publish! store n body nil nil))
  ([store n body dep dep-address]
   (:address
     (publish/publish-module!
       store
       (if dep
         {:name n
          :ast (call {:type :lambda :params '[_] :body (ct/def! 'f body)}
                     (call (variable 'require) (ct/lit dep)))
          :exports #{'f}
          :requires {dep dep-address}
          :primitives (assoc ct/plus 'require
                             (vm/profile-of vm/primitives 'require))}
         {:name n :ast (ct/def! 'f body) :exports #{'f} :requires {}
          :primitives ct/plus})))))


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


(defn- index!
  "Publish the signed envelopes `signed` as one index through `node`,
   load it, and step the node until it is loaded: the node."
  [node signed]
  (let [m (dht-test/publish-datoms!
            node
            (vec (map-indexed (fn [i {:keys [env proof]}]
                                {:db/id (+ datom/first-user-id i)
                                 :yin.module/envelope env
                                 :yin.module/proof proof})
                              signed)))
        [node _] (dht/step (dht/load-index node m) 0)]
    (is (= :loaded (:status (dht/load-status node m))))
    node))


(defn- world
  "The solo node and its modules: `own.m`, `own2.m`, `own3.m`, `app.m`
   (requires `own.m`), `bad.m` (its body raises), `mid.m` (requires
   `inner.m`) and `inner.m` (requires `their.m`) asserted by A,
   `their.m` asserted by B, and `direct.m` asserted by nobody."
  []
  (let [node (dht/join {:local (mem/create-content-mem)})
        store (dht/local node)
        own (publish! store 'own.m forty-two)
        their (publish! store 'their.m forty-two)
        inner (publish! store 'inner.m
                        (call (variable '+) (variable 'their.m/f) (ct/lit 1))
                        'their.m their)
        addresses {'own.m own
                   'own2.m (publish! store 'own2.m forty-two)
                   'own3.m (publish! store 'own3.m forty-two)
                   'app.m (publish! store 'app.m
                                    (call (variable '+) (variable 'own.m/f)
                                          (ct/lit 1))
                                    'own.m own)
                   'bad.m (publish! store 'bad.m
                                    (call (variable '+) (ct/lit 1)
                                          (call (ct/lit 1))))
                   'inner.m inner
                   'mid.m (publish! store 'mid.m
                                    (call (variable '+) (variable 'inner.m/f)
                                          (ct/lit 1))
                                    'inner.m inner)
                   'their.m their
                   'direct.m (publish! store 'direct.m forty-two)}
        by-a (map-indexed (fn [i n] (assertion a n (get addresses n) (inc i)))
                          '[own.m own2.m own3.m app.m bad.m inner.m mid.m])]
    {:node (index! node (conj (vec by-a)
                              (assertion b 'their.m their 1)))
     :store store
     :addresses addresses
     :by-a (vec by-a)}))


(defn- address
  [w n]
  (get-in w [:addresses n]))


(defn- status
  "The node's module record of `n`'s manifest, or nil: nil means no load
   was ever started."
  [node w n]
  (ld/module-status node (address w n)))


;; =============================================================================
;; The seam: link/serve and admission/serve over hand-written requests
;; =============================================================================

(defn- request
  [id n]
  {:yin.link/id id
   :yin.link/name n
   :yin.link/format :yin.semantic/code
   :yin.link/contract vm/semantic-contract})


(def ^:private ctx
  (admission/context {:key a}))


(defn- seam
  "A DHT link source over `w`'s node, declaring B, holding A's key, its
   in-process interpreter composed from `opts` -- none when `opts` is
   nil -- and a fresh link pair and admission state."
  ([w] (seam w {}))
  ([w opts] (seam w opts (admission/make-state link/pair-capacity)))
  ([w opts adm]
   (let [src (link/composition {:dht? true
                                :principals [(:public b)]
                                :key a
                                :admission opts})]
     {:src src
      :node (:node w)
      :pair (link/make-pair)
      :adm adm
      :interp (when (:admission src)
                (admission/interpreter adm (:admission src)))
      :now 0})))


(defn- ask!
  [s id n]
  (stream/append! (get-in s [:pair :requests]) (request id n))
  s)


(defn- answer!
  "Append `answer` to the answer medium, as an external interpreter."
  [s answer]
  (stream/append! (get-in s [:adm :pair :answers]) answer)
  s)


(defn- round
  "One link serve round, then one interpreter round, then one node step."
  [s]
  (let [value (cond-> {:pair (:pair s) :source (:src s)
                       :admission (:adm s) :context ctx}
                (:interp s) (assoc :interpreter (:interp s)))
        result (link/round value (:node s) link/serve-budget)
        successor (:linker result)
        served (assoc result :pair (:pair successor)
                      :admission (:admission successor))
        [node _] (dht/step (:dht result) (:now s))]
    (assoc s
           :pair (:pair successor)
           :adm (:admission successor)
           :interp (:interpreter successor)
           :node node
           :now (+ 10 (:now s))
           :served served
           :admitted {:answered (:answered result)
                      :progress? (pos? (:answered result))})))


(defn- responses
  [s]
  (mesh/values (get-in s [:pair :responses])))


(defn- until-response
  "Rounds until a response lands, at most 20."
  [s]
  (loop [s s
         i 0]
    (if (or (seq (responses s)) (>= i 20))
      s
      (recur (round s) (inc i)))))


(defn- questions-of
  [adm]
  (mesh/values (get-in adm [:pair :questions])))


(defn- allow
  [id]
  {:yin.admit/id id :yin.admit/decision :allow :yin.admit/conditions {}})


(defn- capacity-1
  []
  (:dao.stream/handle (ring/create! {:dao.stream/type ring/transport-type
                                     ring/capacity-key 1})))


(defn- position
  [cursor]
  (:dao.stream.ringbuffer/position cursor))


(deftest a-trusted-asserter-is-allowed-over-the-question-it-was-asked-test
  (let [w (world)
        s (round (ask! (seam w) [:t0 0] 'own.m))]
    (testing "the first round asks: the question is appended, reported as
              progress, and the link parks on it with nothing loaded"
      (is (true? (get-in s [:served :progress?])))
      (is (= [{:name 'own.m :yin.link/id [:t0 0] :admission [[:t0 0] 0]}]
             (get-in s [:served :pending])))
      (is (= [{:yin.admit/id [[:t0 0] 0]
               :yin.link/id [:t0 0]
               :yin.admit/name 'own.m
               :yin.admit/address (address w 'own.m)
               :yin.admit/asserters [pa]
               :yin.admit/provenance (:yin.link/provenance
                                       (ld/resolve-name
                                         (:node s)
                                         (link/authority (:src s))
                                         'own.m))
               :yin.link/format :yin.semantic/code
               :yin.link/contract vm/semantic-contract}]
             (questions-of (:adm s))))
      (is (nil? (status (:node s) w 'own.m))))
    (testing "the interpreter allows it, and the link loads and responds"
      (is (= 1 (get-in s [:admitted :answered])))
      (let [s (until-response s)
            [res] (responses s)]
        (is (= :ok (:status res)) (pr-str (dissoc res :image)))
        (is (= {:yin.admit/id [[:t0 0] 0]
                :yin.admit/decision :allow
                :yin.admit/asserters [pa]
                :yin.admit/address (address w 'own.m)}
               (select-keys res [:yin.admit/id :yin.admit/decision
                                 :yin.admit/asserters :yin.admit/address])))
        (testing "and the question and its answer are dropped once it lands"
          (is (= {} (:asked (:adm s)) (:answers (:adm s))))
          (is (= 1 (:serial (:adm s)))))))))


(deftest an-untrusted-principal-is-refused-with-no-load-started-test
  (let [w (world)
        s (until-response (ask! (seam w) [:t0 0] 'their.m))]
    (is (= [{:status :refused
             :reason :yin.admit/untrusted-principal
             :name 'their.m
             :yin.admit/id [[:t0 0] 0]
             :yin.admit/decision :refuse
             :yin.admit/asserters [pb]
             :yin.link/id [:t0 0]}]
           (responses s)))
    (is (nil? (status (:node s) w 'their.m)) "no load was started")
    (testing "trusting B allows it"
      (let [s (until-response (ask! (seam w {:trusted #{pb}}) [:t0 0]
                                    'their.m))]
        (is (= :ok (:status (first (responses s)))))))))


(deftest an-unanswered-question-survives-sixty-four-rounds-test
  (let [w (world)
        s (round (ask! (seam w {:decision-medium (capacity-1)}) [:t0 0]
                       'own.m))
        pending (get-in s [:served :pending])
        asked (:asked (:adm s))
        later (nth (iterate round s) 64)]
    (testing "an absent snapshot answers nothing and reports no progress"
      (is (= 0 (get-in s [:admitted :answered])))
      (is (false? (get-in s [:admitted :progress?]))))
    (testing "sixty-four rounds later: the same pending entry, one question,
              no answer, no response, no progress, nothing loaded"
      (is (= pending (get-in later [:served :pending])))
      (is (= asked (:asked (:adm later))))
      (is (= 1 (count (questions-of (:adm later)))))
      (is (empty? (mesh/values (get-in later [:adm :pair :answers]))))
      (is (empty? (responses later)))
      (is (false? (get-in later [:served :progress?])))
      (is (nil? (status (:node later) w 'own.m))))
    (testing "only a retirement ends it"
      (let [{:keys [admission retired]}
            (link/retire {:pair (:pair later) :admission (:adm later)}
                         {:origin :t0 :ids [0 1] :children [0 0]})]
        (is (= [[:t0 0]] retired))
        (is (= {} (:asked admission)))))))


(deftest answers-correlate-by-exact-admission-id-and-the-first-stands-test
  (let [w (world)
        s (round (ask! (seam w nil) [:t0 0] 'own.m))
        id [[:t0 0] 0]]
    (testing "with no interpreter in process nothing answers"
      (is (nil? (:interp s)))
      (is (= id (:admission (first (get-in s [:served :pending]))))))
    (testing "an unknown id and a stale serial of the same link id are
              consumed and dropped; the question stays pending"
      (let [s (-> s
                  (answer! (allow [[:t9 9] 0]))
                  (answer! (allow [[:t0 0] 7]))
                  (answer! (allow :not-an-id))
                  round)]
        (is (= {} (:answers (:adm s))))
        (is (= 3 (position (get-in s [:adm :pair :cursor]))))
        (is (= [id] (keep :admission (get-in s [:served :pending]))))
        (testing "the first answer for the id stands, a duplicate is dropped"
          (let [s (-> s
                      (answer! (allow id))
                      (answer! {:yin.admit/id id
                                :yin.admit/decision :refuse
                                :yin.admit/reason :test/second})
                      until-response)]
            (is (= :ok (:status (first (responses s)))))
            (is (= 1 (count (responses s))))))))))


(deftest an-answer-the-composition-cannot-honor-refuses-test
  (let [w (world)
        id [[:t0 0] 0]
        refused (fn [answer]
                  (let [s (-> (ask! (seam w nil) [:t0 0] 'own.m)
                              round
                              (answer! answer)
                              until-response)]
                    (is (nil? (status (:node s) w 'own.m))
                        "refused before any load")
                    (select-keys (first (responses s))
                                 [:status :reason :yin.admit/conditions])))]
    (testing "non-empty conditions: unsupported, final for the link"
      (is (= {:status :refused
              :reason :yin.admit/unsupported-condition
              :yin.admit/conditions {:names ['f]}}
             (refused (assoc (allow id) :yin.admit/conditions {:names ['f]})))))
    (testing "malformed: an unknown decision, a refusal with no reason,
              conditions that are not a map, an allow with no conditions,
              an allow with a key beyond its closed set"
      (doseq [answer [{:yin.admit/id id :yin.admit/decision :maybe}
                      {:yin.admit/id id :yin.admit/decision :refuse}
                      (assoc (allow id) :yin.admit/conditions [:x])
                      {:yin.admit/id id :yin.admit/decision :allow}
                      (assoc (allow id) :unexpected true)]]
        (is (= {:status :refused :reason :yin.admit/malformed-answer}
               (refused answer))
            (pr-str answer))))
    (testing "a refusal keeps its reason and open keys"
      (is (= {:status :refused :reason :test/policy}
             (refused {:yin.admit/id id
                       :yin.admit/decision :refuse
                       :yin.admit/reason :test/policy}))))))


(deftest a-dht-source-with-no-admission-state-refuses-unconfigured-test
  (let [w (world)
        s (seam w)
        served (link/serve {:pair (:pair (ask! s [:t0 0] 'own.m))
                            :source (:src s)
                            :dht (:node s)})]
    (is (= [{:status :refused :reason :yin.admit/unconfigured :name 'own.m
             :yin.link/id [:t0 0]}]
           (responses s)))
    (is (nil? (:admission served)))
    (is (nil? (status (:dht served) w 'own.m))))
  (testing "a direct name-env entry is the composition's own: not asked"
    (let [w (world)
          s (seam w)
          s (until-response
              (ask! (assoc-in s [:src :name-env]
                              {'direct.m (address w 'direct.m)})
                    [:t0 0] 'direct.m))]
      (is (= :ok (:status (first (responses s)))))
      (is (nil? (:yin.admit/id (first (responses s)))))
      (is (empty? (questions-of (:adm s)))))))


(deftest the-admission-pair-is-the-only-policy-surface-test
  (let [w (world)
        calls (atom 0)
        hook (fn [n _]
               (swap! calls inc)
               {:status :refused :reason :test/hook :name n})
        src (link/composition {:dht? true
                               :principals [(:public b)]
                               :key a
                               :admission {}
                               :admit hook})
        s (until-response (ask! (assoc (seam w) :src src) [:t0 0] 'their.m))]
    (is (not (contains? src :admit)) "a composition keeps no :admit")
    (is (zero? @calls) "an :admit function is never called")
    (is (= 1 (count (questions-of (:adm s)))) "the name is asked")
    (is (= :yin.admit/untrusted-principal (:reason (first (responses s)))))))


(deftest an-untrusted-principal-is-refused-by-the-default-interpreter-test
  (let [p (admission/policy {:own #{pa}})
        verify (:verify p)
        q (fn [& asserters]
            {:yin.admit/name 'their.m :yin.admit/asserters (vec asserters)})]
    (testing "the decision is the own principals with no trusted set"
      (is (= #{pa} (:decision p))))
    (testing "the importer's own principal is allowed, alone or beside
              another asserting the same address (R8)"
      (is (nil? (verify (:decision p) ctx (q pa))))
      (is (nil? (verify (:decision p) ctx (q pb pa)))))
    (testing "only another principal: refused untrusted, naming it"
      (is (= {:yin.admit/reason :yin.admit/untrusted-principal
              :yin.admit/asserters [pb]}
             (verify (:decision p) ctx (q pb)))))
    (testing "an explicit trusted set joins the decision"
      (let [p (admission/policy {:own #{pa} :trusted #{pb}})]
        (is (= #{pa pb} (:decision p)))
        (is (nil? ((:verify p) (:decision p) ctx (q pb))))))))


(deftest verify-receives-the-snapshot-the-context-and-the-question-test
  (let [w (world)
        seen (atom [])
        snapshot {:test/rule :allow-all}
        verify (fn [d c q] (swap! seen conj [d c q]) nil)
        s (round (ask! (seam w {:verify verify :decision snapshot}) [:t0 0]
                       'own.m))
        [[d c q]] @seen]
    (is (= 1 (count @seen)))
    (is (identical? snapshot d))
    (is (= ctx c))
    (is (= {:yin.admit/subject pa} c))
    (is (= (first (questions-of (:adm s))) q))
    (testing "policies compose as verify functions, the first reason wins"
      (let [s (until-response
                (ask! (seam w {:verify [(constantly nil)
                                        (fn [_ _ _] {:yin.admit/reason :t/one})
                                        (fn [_ _ _] {:yin.admit/reason :t/two})]})
                      [:t0 0] 'own.m))]
        (is (= :t/one (:reason (first (responses s)))))))
    (testing "no authorization word travels in a question or a request"
      (let [forbidden? (fn [k]
                         (or (contains? #{:yin.admit/subject}
                                        k)
                             (str/starts-with? (str (namespace k)) "dao.shibi")))]
        (is (not-any? forbidden? (keys q)))
        (is (not-any? forbidden?
                      (keys (first (mesh/values
                                     (get-in s [:pair :requests]))))))))))


(deftest an-ended-decision-medium-refuses-with-no-load-started-test
  (let [w (world)
        m (capacity-1)
        _ (stream/close! m)
        s (until-response (ask! (seam w {:decision-medium m}) [:t0 0] 'own.m))]
    (is (= {:status :refused :reason :yin.admit/decision-ended}
           (select-keys (first (responses s)) [:status :reason])))
    (is (nil? (status (:node s) w 'own.m))))
  (testing "a snapshot on the medium is read once per round, the newest"
    (let [w (world)
          m (capacity-1)
          _ (stream/append! m #{pb})
          _ (stream/append! m #{pa})
          s (until-response (ask! (seam w {:decision-medium m}) [:t0 0]
                                  'own.m))]
      (is (= :ok (:status (first (responses s))))))))


;; -----------------------------------------------------------------------------
;; Retirement (P1-1) and the drain (P1-2) at the seam
;; -----------------------------------------------------------------------------

(defn- pair-of
  "A link pair holding requests with the ids `ids`, oldest first."
  [ids]
  (let [pair (link/make-pair)]
    (doseq [id ids] (stream/append! (:requests pair) (request id 'x.m)))
    pair))


(deftest retire-matches-the-run-s-interval-and-delimited-descendants-test
  (testing "the half-open root interval: base 0 retires [:t0 0], not the
            next id"
    (is (= [[:t0 0]]
           (:retired (link/retire {:pair (pair-of [[:t0 0] [:t0 1]])}
                                  {:origin :t0 :ids [0 1] :children [0 0]})))))
  (testing "child origins match exactly or on origin + \".\", never a raw
            prefix: :t0.10 is not a descendant of :t0.1"
    (let [{:keys [pair retired]}
          (link/retire {:pair (pair-of [[:t0.1 0] [:t0.1.0 2] [:t0.10 0]])}
                       {:origin :t0 :ids [3 3] :children [1 2]})]
      (is (= [[:t0.1 0] [:t0.1.0 2]] retired))
      (is (= [:t0.10 0]
             (:yin.link/id (:dao.stream/value
                             (stream/next (:requests pair) (:cursor pair))))))))
  (testing "the first request the run does not own stops the walk"
    (is (= [[:t0 0]]
           (:retired (link/retire {:pair (pair-of [[:t0 0] [:t1 0] [:t0 1]])}
                                  {:origin :t0 :ids [0 2] :children [0 0]}))))))


(deftest retire-drops-the-run-s-questions-and-answers-test
  (let [w (world)
        s (round (ask! (seam w nil) [:t0.1.0 2] 'own.m))
        id [[:t0.1.0 2] 0]
        adm (admission/drain (:adm (answer! s (allow id))) 64)
        _ (is (= [id] (keys (:answers adm))))
        {:keys [admission retired]}
        (link/retire {:pair (:pair s) :admission adm}
                     {:origin :t0 :ids [0 0] :children [1 2]})]
    (is (= [[:t0.1.0 2]] retired))
    (is (= {} (:asked admission) (:answers admission)))
    (testing "a later answer for the retired id correlates to nothing"
      (stream/append! (get-in s [:adm :pair :answers]) (allow id))
      (is (= {} (:answers (admission/drain admission 64)))))))


(deftest the-drain-recovers-from-a-gap-at-the-earliest-retained-answer-test
  (let [w (world)
        s (round (ask! (seam w nil (admission/make-state 4)) [:t0 0] 'own.m))
        id [[:t0 0] 0]
        s (reduce (fn [s i] (answer! s (allow [[:t9 i] 0]))) s (range 6))
        s (answer! s (allow id))
        adm (admission/drain (:adm s) link/serve-budget)]
    (testing "six unknown answers then the allow on a capacity-4 medium: the
              drain adopts the recovery cursor and keeps the allow"
      (is (= [id] (keys (:answers adm))))
      (is (= 7 (position (get-in adm [:pair :cursor])))))
    (testing "and the link proceeds"
      (is (= :ok (:status (first (responses (until-response
                                              (assoc s :adm adm)))))))))
  (testing "the drain is bounded by its budget and persists its cursor"
    (let [w (world)
          s (round (ask! (seam w nil) [:t0 0] 'own.m))
          s (reduce (fn [s i] (answer! s (allow [[:t9 i] 0]))) s (range 5))
          adm (admission/drain (:adm s) 3)]
      (is (= 3 (position (get-in adm [:pair :cursor]))))
      (is (= 5 (position (get-in (admission/drain adm 3) [:pair :cursor])))))))


;; =============================================================================
;; The shell: a REPL whose link source is the node
;; =============================================================================

(defn- shell
  "A REPL of `vm-type` over `w`'s node, holding A's key and declaring B,
   composed with the creation options `opts`."
  ([w vm-type] (shell w vm-type {}))
  ([w vm-type opts]
   (-> (repl.frontends/create-state (merge {:vm-type vm-type
                                            :dht-key a
                                            :principals [(:public b)]}
                                           opts))
       (assoc :dht (:node w))
       (assoc-in [:link-source :content] {:kind :dht})
       (#(assoc % :linker
                (assoc (link/make (:link-source %)
                                  {:key a})
                       :pair (get-in % [:linker :pair])))))))


(defn- type!
  [state line]
  (repl/eval-input state line))


(defn- require!
  [state n]
  (type! state (str "(require (quote " n "))")))


(defn- pending-admission
  [state]
  (some :admission (get-in state [:pending-run :links])))


(defn- settle
  "Step the node and re-check the pending run until `done?` holds or 40
   re-checks pass: `[state text]`."
  ([state] (settle state #(nil? (:pending-run %))))
  ([state done?]
   (loop [state state
          text ""
          i 0]
     (if (or (done? state) (>= i 40))
       [state text]
       (let [[node _] (dht/step (:dht state) (* 10 i))
             [state t] (repl/recheck-pending (assoc state :dht node))]
         (recur state (str text t) (inc i)))))))


(defn- answer-at!
  "Append `answer` to the shell's answer medium, as an external
   interpreter."
  [state answer]
  (stream/append! (get-in state [:linker :admission :pair :answers]) answer)
  state)


(defn- shell-responses
  [state]
  (mesh/values (get-in state [:linker :pair :responses])))


(defn- entry
  [state n]
  (module/module-entry (:modules (:modules (:vm state))) n))


(defn- preload
  "The shell with `n`'s closure loaded on its node, so an allow links at
   once."
  [state w n]
  (loop [node (ld/load-module (:dht state) (address w n))
         i 0]
    (if (or (= :loaded (:status (ld/module-status node (address w n))))
            (> i 40))
      (assoc state :dht node)
      (recur (first (dht/step node (* 10 i))) (inc i)))))


(deftest a-trusted-import-records-its-admission-on-every-vm-test
  (doseq [vm-type [:ast-walker :semantic :stack :register]]
    (testing (str vm-type)
      (let [w (world)
            [state text] (require! (shell w vm-type) 'app.m)
            [state text'] (settle state)
            id-of (fn [n] (get-in (entry state n) [:admission :yin.admit/id]))]
        (is (nil? (:pending-run state)) (str text text'))
        (is (= 'app.m (:last-value state)) (str text text'))
        (testing "the installed entry records the admission and the address
                  it was bound to"
          (is (= {:yin.admit/decision :allow
                  :yin.admit/asserters [pa]
                  :yin.admit/address (address w 'app.m)}
                 (dissoc (:admission (entry state 'app.m)) :yin.admit/id))))
        (testing "the dependency the install child imported is transferred
                  with its own admission"
          (is (= {:yin.admit/decision :allow
                  :yin.admit/asserters [pa]
                  :yin.admit/address (address w 'own.m)}
                 (dissoc (:admission (entry state 'own.m)) :yin.admit/id)))
          (is (not= (id-of 'app.m) (id-of 'own.m))))
        (is (= "43" (second (type! state "app.m/f"))))))))


(deftest an-install-child-inherits-the-admission-of-a-preinstalled-entry-test
  (doseq [vm-type [:ast-walker :semantic :stack :register]]
    (testing (str vm-type)
      (let [w (world)
            allow! (fn [state]
                     (let [id (pending-admission state)]
                       (first (settle (first (repl/recheck-on-tick
                                               (answer-at! state (allow id))
                                               []))
                                      #(or (nil? (:pending-run %))
                                           (not= id (pending-admission %)))))))
            [state _] (require! (shell w vm-type {:admission :external})
                                'own.m)
            state (allow! state)
            recorded (:admission (entry state 'own.m))
            [state _] (require! state 'mid.m)
            state (allow! state)
            child (get-in state [:vm :installs 'mid.m :vm])]
        (is (= (address w 'own.m) (:yin.admit/address recorded)))
        (is (some? (:yin.admit/id recorded)))
        (is (some? child) "mid.m's install child is parked on inner.m")
        (is (some? (pending-admission state)))
        (testing "the child's inherited entry carries the original admission"
          (is (= recorded
                 (:admission (module/module-entry (:modules (:modules child))
                                                  'own.m)))))))))


(deftest an-untrusted-import-is-refused-at-the-shell-with-no-load-test
  (let [w (world)
        [state text] (require! (shell w :semantic) 'their.m)]
    (is (nil? (:pending-run state)) text)
    (is (str/includes? text "untrusted-principal") text)
    (is (nil? (status (:dht state) w 'their.m)))
    (testing "trusted in the session's composition, it imports"
      (let [[state text] (require! (shell w :semantic {:admission
                                                       {:trusted #{pb}}})
                                   'their.m)
            [state text'] (settle state)]
        (is (= 'their.m (:last-value state)) (str text text'))))))


(defn- booted
  "The shell `yin.repl.main/boot` composes from `opts`, holding A's key
   and declaring B, over `w`'s node."
  [w opts]
  (-> (main/boot (merge {:dht-key a :principals [(:public b)]} opts))
      :repl
      (assoc :dht (:node w))
      (assoc-in [:link-source :content] {:kind :dht})
      (#(assoc % :linker
               (assoc (link/make (:link-source %)
                                 {:key a})
                      :pair (get-in % [:linker :pair]))))))


(deftest boot-keeps-declared-authority-apart-from-admission-trust-test
  (let [w (world)]
    (testing "declaring B alone refuses B's module before any load"
      (let [state (booted w {})
            [state text] (require! state 'their.m)]
        (is (= #{pa} (get-in state [:link-source :admission :decision])))
        (is (nil? (:pending-run state)) text)
        (is (str/includes? text "untrusted-principal") text)
        (is (nil? (status (:dht state) w 'their.m)))))
    (testing "an explicit admission passed to boot is honored: trusting B
              imports B's module"
      (let [state (booted w {:admission {:trusted #{pb}}})
            [state text] (require! state 'their.m)
            [state text'] (settle state)]
        (is (= #{pa pb} (get-in state [:link-source :admission :decision])))
        (is (= 'their.m (:last-value state)) (str text text'))))
    (testing "and an external admission reaches the session"
      (is (nil? (get-in (booted w {:admission :external})
                        [:link-source :admission]))))))


(deftest create-state-composes-the-admission-policy-test
  (let [w (world)]
    (is (= #{pa} (get-in (shell w :semantic) [:link-source :admission
                                              :decision])))
    (is (= #{pa pb} (get-in (shell w :semantic {:admission {:trusted #{pb}}})
                            [:link-source :admission :decision])))
    (let [external (shell w :semantic {:admission :external})]
      (is (nil? (get-in external [:link-source :admission])))
      (is (nil? (get-in external [:linker :interpreter])))
      (is (some? (get-in external [:linker :admission]))))
    (testing "(reset) mints a fresh admission state and keeps the policy"
      (let [state (shell w :semantic)
            [state' _] (type! state "(reset)")]
        (is (not= (get-in state [:linker :admission :pair :questions])
                  (get-in state' [:linker :admission :pair :questions])))
        (is (= #{pa} (get-in state' [:link-source :admission :decision])))
        (is (some? (get-in state' [:linker :interpreter])))))))


(deftest abandon-retires-the-run-and-the-pair-serves-on-test
  (let [w (world)
        [state text] (require! (shell w :semantic {:admission :external})
                               'their.m)
        id (pending-admission state)]
    (testing "zero counters: the run's base has minted nothing, and the
              require parks on its question, serial 0"
      (is (zero? (or (get-in state [:pending-run :base :id-counter]) 0)))
      (is (str/includes? text "awaiting admission") text)
      (is (= 0 (second id))))
    (let [[state _] (settle state (constantly false))
          _ (is (= id (pending-admission state)) "re-checks change nothing")
          [state text] (type! state "(abandon)")
          _ (is (str/includes? text "abandoned") text)
          _ (is (= {} (get-in state [:linker :admission :asked])))
          _ (is (not (contains? (:linker state) :retired)))
          [state _] (require! (preload state w 'own.m) 'own.m)
          id2 (pending-admission state)
          _ (is (= 1 (second id2)))
          [state text] (repl/recheck-on-tick (answer-at! state (allow id2)) [])]
      (testing "an unrelated trusted require completes on the same pair"
        (is (nil? (:pending-run state)) text)
        (is (= 'own.m (:last-value state)))
        (is (= [(first id2)] (mapv :yin.link/id (shell-responses state)))
            "no response for the retired request"))
      (testing "a late allow for the retired id loads, prints and parks
                nothing"
        (let [[state' text] (repl/recheck-on-tick
                              (answer-at! state (allow id)) [])]
          (is (nil? text))
          (is (nil? (:pending-run state')))
          (is (nil? (status (:dht state') w 'their.m)))
          (is (= 1 (count (shell-responses state'))))
          (is (= {} (get-in state' [:linker :admission :answers]))))))))


(deftest abandon-retires-nested-install-requests-from-a-nonzero-base-test
  (let [w (world)
        allow! (fn [state]
                 (let [id (pending-admission state)]
                   (is (some? id) "parked on a question")
                   [(first (repl/recheck-on-tick
                             (answer-at! state (allow id))
                             []))
                    id]))
        past (fn [id]
               #(or (nil? (:pending-run %))
                    (not= id (pending-admission %))))
        step (fn [state]
               (let [[state id] (allow! state)]
                 (first (settle state (past id)))))
        [state _] (require! (shell w :semantic {:admission :external}) 'own.m)
        state (step state)
        [state _] (require! state 'own2.m)
        state (step state)
        _ (is (= 'own2.m (:last-value state)))
        base (:vm state)
        _ (is (pos? (:id-counter base)))
        _ (is (= 2 (:origins base)) "two install children so far")
        [state _] (require! state 'mid.m)
        state (step state)
        _ (is (= :t0.2 (first (first (pending-admission state))))
              "the run's child asks about inner.m")
        state (step state)
        child-q (pending-admission state)
        _ (is (= :t0.2.0 (first (first child-q)))
              "the run's grandchild asks about their.m")
        before (get-in state [:linker :pair :cursor])
        [state text] (type! state "(abandon)")
        passed (loop [c before
                      acc []]
                 (let [r (stream/next
                           (get-in state [:linker :pair :requests]) c)]
                   (if (= :dao.stream/ok (:dao.stream/outcome r))
                     (recur (:dao.stream/cursor r)
                            (conj acc (:yin.link/id (:dao.stream/value r))))
                     acc)))]
    (is (str/includes? text "abandoned") text)
    (testing "retired: exactly the run-owned requests still unread, the root
              and the child having been answered"
      (is (= [(first child-q)] passed))
      (is (= :dao.stream/blocked
             (:dao.stream/outcome
               (stream/next (get-in state [:linker :pair :requests])
                            (get-in state [:linker :pair :cursor]))))))
    (is (= {} (get-in state [:linker :admission :asked])
           (get-in state [:linker :admission :answers])))
    (testing "an unrelated trusted require completes"
      (let [[state _] (require! state 'own3.m)
            state (step state)]
        (is (= 'own3.m (:last-value state)))
        (is (not-any? #{(first child-q)}
                      (map :yin.link/id (shell-responses state))))))))


(deftest the-host-hook-drains-irrelevant-answers-without-a-re-check-test
  (let [w (world)
        unknown (fn [state n]
                  (dotimes [i n] (answer-at! state (allow [[:t9 i] 0])))
                  state)
        parked (fn []
                 (let [[state text] (require! (preload (shell w :semantic
                                                              {:admission
                                                               :external})
                                                       w 'own.m)
                                              'own.m)]
                   (is (= 0 (second (pending-admission state))) text)
                   state))
        allow-pending #(answer-at! % (allow (pending-admission %)))]
    (testing "serve-budget unknown answers, then the allow: two host calls
              complete the require, the first re-checks nothing"
      (let [state (-> (parked)
                      (unknown link/serve-budget)
                      allow-pending)
            [state text] (repl/recheck-on-tick state [])]
        (is (nil? text))
        (is (= 0 (get-in state [:pending-run :checks])))
        (let [[state text] (repl/recheck-on-tick state [])]
          (is (some? text))
          (is (nil? (:pending-run state)))
          (is (= 'own.m (:last-value state))))))
    (testing "serve-budget unknown answers and no allow: three calls, no
              :checks, the cursor past all of them"
      (let [state (unknown (parked) link/serve-budget)
            state (nth (iterate #(first (repl/recheck-on-tick % [])) state) 3)]
        (is (= 0 (get-in state [:pending-run :checks])))
        (is (some? (:pending-run state)))
        (is (= link/serve-budget
               (position (get-in state [:linker :admission :pair :cursor]))))))
    (testing "a gap: a capacity-4 answer medium, six unknown answers then
              the allow: the hook adopts the recovery cursor"
      (let [state (-> (shell w :semantic {:admission :external})
                      (assoc-in [:linker :admission] (admission/make-state 4))
                      (preload w 'own.m))
            [state _] (require! state 'own.m)
            state (-> state (unknown 6) allow-pending)
            [state _] (repl/recheck-on-tick state [])
            [state _] (repl/recheck-on-tick state [])]
        (is (nil? (:pending-run state)))
        (is (= 'own.m (:last-value state)))))
    (testing "with nothing pending the hook still drains"
      (let [state (unknown (shell w :semantic {:admission :external}) 2)
            [state' text] (repl/recheck-on-tick state [])]
        (is (nil? text))
        (is (= 2 (position
                   (get-in state' [:linker :admission :pair :cursor]))))))))


(deftest a-late-answer-completes-the-require-with-no-typed-line-test
  (let [w (world)
        [state text] (require! (preload (shell w :semantic
                                               {:admission :external})
                                        w 'own.m)
                               'own.m)
        id (pending-admission state)]
    (is (str/includes? text "pending") text)
    (is (= 0 (second id)))
    (testing "an unrelated load event re-checks nothing"
      (let [[state' text'] (repl/recheck-on-tick
                             state
                             [{:dao.space.dht/event :loaded
                               :manifest (address w 'own2.m)}])]
        (is (identical? state state'))
        (is (nil? text'))))
    (let [[state text] (repl/recheck-on-tick (answer-at! state (allow id)) [])]
      (is (some? text))
      (is (nil? (:pending-run state)))
      (is (= 'own.m (:last-value state))))))


(deftest an-answer-alone-is-progress-and-the-drive-continues-test
  (let [w (world)
        m (capacity-1)
        [state text] (require! (preload (shell w :semantic
                                               {:admission
                                                {:decision-medium m}})
                                        w 'own.m)
                               'own.m)]
    (testing "an absent snapshot: the question is asked, not answered"
      (is (some? (pending-admission state)) text)
      (is (empty? (mesh/values
                    (get-in state [:linker :admission :pair :answers])))))
    (stream/append! m #{pa})
    (testing "the snapshot arrives: one re-check answers, drains and links,
              the interpreter's answer alone continuing the drive"
      (let [[state text] (repl/recheck-on-tick state [])]
        (is (nil? (:pending-run state)) text)
        (is (= 'own.m (:last-value state)))))))


(deftest an-install-error-keeps-the-admission-it-settled-test
  (let [w (world)
        [state text] (require! (shell w :semantic) 'bad.m)
        [state text'] (settle state)
        adm (get-in state [:linker :admission])]
    (is (nil? (:pending-run state)))
    (is (str/includes? (str text text') "Error") (str text text'))
    (testing "the rollback keeps the settled admission: nothing asked or
              answered for the failed link, the serial past it"
      (is (= {} (:asked adm) (:answers adm)))
      (is (= 1 (:serial adm))))
    (let [[state _] (require! state 'own.m)
          [state _] (settle state)
          adm (get-in state [:linker :admission])]
      (is (= 'own.m (:last-value state)))
      (testing "the next require's question has a greater serial, and
                nothing of the failed one is asked or answered again"
        (is (= [0 1] (mapv (comp second :yin.admit/id) (questions-of adm))))
        (is (= 2 (count (mesh/values (get-in adm [:pair :answers])))))
        (is (= 2 (:serial adm)))))))


;; =============================================================================
;; The head moves between the question and the answer (F1)
;; =============================================================================

(deftest an-allow-binds-the-address-the-question-was-asked-about-test
  (let [w (world)
        store (:store w)
        m1 (address w 'own.m)
        m2 (publish! store 'own.m (call (variable '+) (ct/lit 1) (ct/lit 2)))
        [state _] (require! (shell w :semantic {:admission :external}) 'own.m)
        id (pending-admission state)
        old (first (:by-a w))
        node (index! (:dht state) [old (retraction a old 8)
                                   (assertion a 'own.m m2 9)])
        state (assoc state :dht node)]
    (is (not= m1 m2))
    (is (= m2 (:address (ld/resolve-name node (link/authority
                                                (:link-source state))
                                         'own.m)))
        "the name now resolves to M2")
    (let [[state _] (settle state (constantly false))
          _ (is (= id (pending-admission state))
                "re-checks neither re-resolve nor re-ask")
          _ (is (= 1 (count (questions-of
                              (get-in state [:linker :admission])))))
          [state text] (repl/recheck-on-tick (answer-at! state (allow id)) [])
          [state text'] (settle state)
          [res] (shell-responses state)]
      (is (nil? (:pending-run state)) (str text text'))
      (is (= m1 (:yin.admit/address res)))
      (is (= (jing/get store m1 nil) (:manifest res)))
      (is (some? (ld/module-status (:dht state) m1)))
      (is (nil? (ld/module-status (:dht state) m2)))
      (is (= {:yin.admit/id id
              :yin.admit/decision :allow
              :yin.admit/asserters [pa]
              :yin.admit/address m1}
             (:admission (entry state 'own.m))))
      (is (= "42" (second (type! state "own.m/f")))))))


;; =============================================================================
;; AL-2: one round, one handoff, one carried successor
;; =============================================================================

(defn- traced-tick
  "JVM-only call-order evidence; other hosts observe state and streams."
  [state]
  #?(:cljd {:result (repl/recheck-on-tick state [])}
     :cljs {:result (repl/recheck-on-tick state [])}
     :clj
     (let [trace (atom [])
           successors (atom [])
           round-fn link/round
           drain-fn admission/drain
           run-fn ffi/maybe-run
           origin (:origin (:vm (:pending-run state)))
           result (with-redefs
                    [link/round
                     (fn [value node]
                       (swap! trace conj :round)
                       (let [r (round-fn value node link/serve-budget)]
                         (swap! successors conj (:linker r))
                         r))
                     admission/drain
                     (fn [value budget]
                       (swap! trace conj :drain)
                       (drain-fn value budget))
                     ffi/maybe-run
                     (fn [v f]
                       ;; Output tokenization also runs the already halted VM.
                       (when (and (= origin (:origin v)) (not (vm/halted? v)))
                         (swap! trace conj :run))
                       (run-fn v f))]
                    (repl/recheck-on-tick state []))]
       {:result result :trace @trace :successors @successors})))


(defn- assert-handoff
  [_trace]
  #?(:cljd nil
     :clj
     (do
       (is (= [:round :drain :run] (vec (take 3 _trace))))
       (is (= 1 (count (filter #{:run}
                               (take-while #(not= :round %)
                                           (drop 2 _trace))))))
       (is (= (count (filter #{:round} _trace))
              (count (filter #{:drain} _trace))))
       (is (every? #(= :drain (second %))
                   (filter #(= :round (first %))
                           (partition 2 1 _trace)))))))


(defn- newest-cursor
  [medium]
  (:dao.stream/cursor (stream/cursor medium stream/anchor-newest)))


(defn- settled-successor
  "Expected whole successor from parked state and emitted stream ends.
   No interpreter or VM is run to compute this portable expectation."
  [state medium decision]
  (let [value (:linker state)
        expected (-> value
                     (assoc :source (:link-source state))
                     (assoc-in [:pair :cursor]
                               (newest-cursor (get-in value
                                                      [:pair :requests])))
                     (assoc-in [:admission :pair :cursor]
                               (newest-cursor
                                 (get-in value [:admission :pair :answers])))
                     (assoc-in [:admission :asked] {})
                     (assoc-in [:admission :answers] {}))]
    (if (:interpreter value)
      (-> expected
          (assoc-in [:interpreter :cursor]
                    (newest-cursor (get-in value [:interpreter :questions])))
          (assoc-in [:interpreter :decision-cursor] (newest-cursor medium))
          (assoc-in [:interpreter :snapshot] decision)
          (assoc-in [:interpreter :snapshot?] true))
      expected)))


(deftest one-tick-hands-external-and-snapshot-progress-to-the-vm-test
  (doseq [external? [true false]]
    (testing (if external? "external allow" "late decision snapshot")
      (let [w (world)
            medium (capacity-1)
            opts (if external? :external {:decision-medium medium})
            [state text] (require! (preload (shell w :semantic
                                                   {:admission opts})
                                            w 'own.m)
                                   'own.m)
            id (pending-admission state)
            _ (is (some? id) text)
            _ (is (some? (:pending-run state)))
            _ (is (empty? (shell-responses state)))
            _ (is (= 1 (count (questions-of
                                (get-in state [:linker :admission])))))
            _ (if external?
                (answer-at! state (allow id))
                (stream/append! medium #{pa}))
            {:keys [result trace]} (traced-tick state)
            [done text] result]
        (assert-handoff trace)
        (is (some? text))
        (is (nil? (:pending-run done)))
        (is (= 'own.m (:last-value done)))
        (is (= 1 (count (questions-of
                          (get-in done [:linker :admission])))))
        (is (= 1 (count (shell-responses done))))
        (is (= 1 (count (mesh/values
                          (get-in done [:linker :admission :pair :answers])))))
        (is (= (settled-successor state medium #{pa}) (:linker done)))))))


(deftest tick-rollbacks-carry-the-entire-linker-successor-test
  (doseq [mode [:external-refuse :snapshot-refuse :install-error
                :snapshot-install-error]]
    (testing (str mode)
      (let [w (world)
            medium (capacity-1)
            n (if (contains? #{:install-error :snapshot-install-error} mode)
                'bad.m 'own.m)
            opts (if (contains? #{:snapshot-refuse :snapshot-install-error}
                                mode)
                   {:decision-medium medium}
                   :external)
            [state _] (require! (preload (shell w :semantic {:admission opts})
                                         w n)
                                n)
            id (pending-admission state)
            _ (is (some? id))
            _ (case mode
                :snapshot-refuse (stream/append! medium #{})
                :snapshot-install-error (stream/append! medium #{pa})
                :install-error (answer-at! state (allow id))
                (answer-at! state {:yin.admit/id id
                                   :yin.admit/decision :refuse
                                   :yin.admit/reason
                                   :yin.admit/untrusted-principal}))
            {result :result trace :trace _successors :successors}
            (traced-tick state)
            [rolled text] result
            decision (if (= mode :snapshot-refuse) #{} #{pa})
            successor (settled-successor state medium decision)
            value (:linker rolled)]
        (assert-handoff trace)
        #?(:cljd nil :clj (is (= successor (peek _successors))))
        (is (str/includes? text "Error") text)
        (is (nil? (:pending-run rolled)))
        (is (nil? (entry rolled n)))
        (when (= n 'own.m)
          (is (= :yin.admit/untrusted-principal
                 (:reason (first (shell-responses rolled)))))
          (is (str/includes? text "Module link refused") text))
        (is (= successor value))
        (is (= (get-in successor [:pair :cursor])
               (get-in value [:pair :cursor])))
        (is (= (get-in successor [:admission :pair :cursor])
               (get-in value [:admission :pair :cursor])))
        (is (= (get-in successor [:interpreter :cursor])
               (get-in value [:interpreter :cursor])))
        (is (= 1 (get-in value [:admission :serial])))
        (is (= {} (get-in value [:admission :asked])
               (get-in value [:admission :answers])))))))


(deftest idle-tick-does-not-run-or-increment-checks-test
  (let [w (world)
        [state _] (require! (shell w :semantic {:admission :external}) 'own.m)
        {result :result _trace :trace} (traced-tick state)
        [successor text] result]
    #?(:cljd nil :clj (is (= [:round :drain] _trace)))
    (is (= (:vm (:pending-run state))
           (:vm (:pending-run successor))))
    (is (nil? text))
    (is (= (:pending-run state) (:pending-run successor)))
    (is (= 0 (get-in successor [:pending-run :checks])))))


(deftest continuing-tick-progress-is-bounded-test
  (let [w (world)
        [state _] (require! (shell w :semantic {:admission :external}) 'own.m)
        n (* link/serve-budget (inc repl/link-round-budget))
        pair (link/make-pair (inc n))
        requests (:requests pair)
        ;; Real invalid requests emit responses without settling the VM's
        ;; parked require. More than the round bound remain answerable.
        _ (reduce (fn [_ i]
                    (stream/append! requests {:yin.link/id [:noise i]}))
                  nil (range n))
        parked (first (mesh/values (get-in state [:linker :pair :requests])))
        _ (stream/append! requests parked)
        pair (assoc pair :responses (get-in state [:linker :pair :responses]))
        state (assoc-in state [:linker :pair] pair)
        [successor text] (repl/recheck-on-tick state [])
        cursor (get-in successor [:linker :pair :cursor])
        ;; The tick plus its initial VM handoff count as the first round.
        answered (* link/serve-budget repl/link-round-budget)
        expected (reduce (fn [c _]
                           (:dao.stream/cursor (stream/next requests c)))
                         (:cursor pair) (range answered))]
    (is (= expected cursor))
    (is (= [:noise answered]
           (:yin.link/id (:dao.stream/value (stream/next requests cursor)))))
    (is (some? (:pending-run successor)))
    (is (= (get-in state [:linker :admission])
           (get-in successor [:linker :admission])))
    (is (str/includes? text "require still pending"))
    (is (= 0 (get-in successor [:pending-run :checks]))))


  ;; Exact VM run counts use the original JVM-only spy. The stream
  ;; fixture above proves the production bound on every host.
  #?(:cljd nil
     :clj
     (let [w (world)
           [state _] (require! (shell w :semantic
                                      {:admission :external})
                               'own.m)
           rounds (atom 0)
           runs (atom 0)
           round-fn link/round
           [successor _]
           (with-redefs [link/round
                         (fn [value node]
                           (swap! rounds inc)
                           (assoc (round-fn value node link/serve-budget)
                                  :progress? true))
                         ffi/maybe-run
                         (fn [v _f] (swap! runs inc) v)]
             (repl/recheck-on-tick state []))]
       (is (= repl/link-round-budget @rounds @runs))
       (is (some? (:pending-run successor)))
       (is (= 0 (get-in successor [:pending-run :checks]))))))


(deftest only-signed-name-sources-mint-admission-state-test
  (let [store (mem/create-content-mem)
        client {:requests (capacity-1) :answers (capacity-1)}]
    (doseq [opts [{} {:content-store store} {:content-client client}]]
      (let [value (link/make (link/composition (assoc opts :admission {})))
            successor (:linker (link/round value nil))]
        (is (some? (:pair value)))
        (is (not (contains? value :admission)))
        (is (not (contains? value :interpreter)))
        (is (= value successor))))
    (let [external (link/make (link/composition {:dht? true}))
          attached (link/make (link/composition {:dht? true :admission {}}))]
      (is (some? (:admission external)))
      (is (nil? (:interpreter external)))
      (is (some? (:admission attached)))
      (is (some? (:interpreter attached))))))


(deftest a-matched-drain-is-progress-before-a-load-responds-test
  (let [w (world)
        s (round (ask! (seam w nil) [:t0 0] 'own.m))
        id (:yin.admit/id (first (questions-of (:adm s))))
        s (answer! s (allow id))
        value {:pair (:pair s) :source (:src s) :admission (:adm s)}
        result (link/round value (:node s))
        successor (:linker result)]
    (is (true? (:progress? result)))
    (is (empty? (mesh/values (get-in successor [:pair :responses]))))
    (is (= (allow id) (get-in successor [:admission :answers id])))
    (is (= 1 (count (questions-of (:admission successor)))))
    (is (= (address w 'own.m) (:manifest (first (:pending result)))))
    (let [next-round (link/round successor (:dht result))]
      (is (false? (:progress? next-round)))
      (is (= successor (:linker next-round))))))


(deftest holder-context-is-composition-owned-test
  (doseq [opts [{} {:holder pb} {:holder {:capability :t/read}}]]
    (let [w (world)
          seen (atom [])
          verify (fn [d c q] (swap! seen conj [d c q]) nil)
          source (link/composition
                   (merge {:dht? true :key a
                           :admission {:verify verify :decision :t/snapshot}}
                          opts))
          value (link/make source)
          subject (get opts :holder pa)]
      (stream/append! (get-in value [:pair :requests])
                      (request [:t0 0] 'own.m))
      (link/round value (:node w))
      (is (= 1 (count @seen)))
      (is (= :t/snapshot (ffirst @seen)))
      (is (= {:yin.admit/subject subject} (second (first @seen))))
      (is (= {:yin.admit/subject subject} (:context value)))
      (is (= {:yin.admit/subject :t/override}
             (:context (link/make source {:holder :t/override})))))))


(deftest reconfigure-preserves-the-parked-protocol-test
  (let [w (world)
        decisions (capacity-1)
        source (link/composition
                 {:dht? true :key a :principals [(:public b)]
                  :admission {:decision-medium decisions
                              :verify (constantly
                                        {:yin.admit/reason :t/old})}})
        value (link/make source)
        _ (stream/append! (get-in value [:pair :requests])
                          (request [:t0 0] 'their.m))
        parked (:linker (link/round value (:node w)))
        changed (link/reconfigure parked {:trusted #{pb}})
        answered (link/round changed (:node w))
        id (get-in parked [:admission :asked [:t0 0] :yin.admit/id])]
    (is (some? id))
    (is (= (:pair parked) (:pair changed)))
    (is (= (:admission parked) (:admission changed)))
    (is (= (get-in parked [:interpreter :cursor])
           (get-in changed [:interpreter :cursor])))
    (is (= (:context parked) (:context changed)))
    (is (= #{pa pb} (get-in changed [:source :admission :decision])))
    (is (= [(allow id)]
           (mesh/values (get-in changed [:admission :pair :answers]))))
    (is (= 1 (:answered answered)))
    (testing "untrust cannot revoke an answer already appended"
      (let [untrusted (link/reconfigure (:linker answered) {})
            drained (:linker (link/round untrusted (:node w)))]
        (is (= (allow id) (get-in drained [:admission :answers id])))))
    (testing "verify and explicit decision are replaced together"
      (let [seen (atom [])
            verify (fn [d c q] (swap! seen conj [d c q]) nil)
            changed (link/reconfigure parked
                                      {:decision :t/new :verify verify})]
        (is (= 1 (:answered (link/round changed (:node w)))))
        (is (= [[:t/new (:context parked)
                 (get-in parked [:admission :asked [:t0 0]])]] @seen))))
    (testing "a new medium supplies a new snapshot, never the old one"
      (let [next-medium (capacity-1)
            changed (link/reconfigure parked
                                      {:decision-medium next-medium})]
        (is (= 0 (:answered (link/round changed (:node w)))))
        (stream/append! next-medium #{pb})
        (is (= 1 (:answered (link/round changed (:node w)))))))))


(deftest reconfigure-preserves-external-and-non-dht-attachment-test
  (let [external (link/make (link/composition {:dht? true :key a}))
        local (link/make (link/composition {}))]
    (is (= external (link/reconfigure external {:trusted #{pb}})))
    (is (nil? (:interpreter (link/reconfigure external {}))))
    (is (= local (link/reconfigure local {:trusted #{pb}})))))


(deftest trust-commands-configure-the-live-linker-test
  (doseq [vm-type [:ast-walker :semantic :stack :register]]
    (let [w (world)
          state (shell w vm-type)
          [state text] (type! state (str "(trust " (pr-str pb) ")"))
          _ (is (str/includes? text pb))
          _ (is (= #{pb} (get-in state [:admission :trusted])))
          [state _] (require! state 'their.m)
          [state _] (settle state)
          recorded (:admission (entry state 'their.m))
          serial (get-in state [:linker :admission :serial])
          [state text] (type! state (str "(untrust " (pr-str pb) ")"))]
      (is (str/includes? text pb))
      (is (nil? (:pending-run state)))
      (is (= #{pa} (get-in state [:link-source :admission :decision])))
      (is (= recorded (:admission (entry state 'their.m))))
      (let [[state _] (require! state 'their.m)]
        (is (= 'their.m (:last-value state)))
        (is (= serial (get-in state [:linker :admission :serial]))))
      (testing "a fresh question from B is refused without a load"
        (let [other (publish! (:store w) 'their2.m forty-two)
              node (index! (:dht state) [(assertion b 'their2.m other 2)])
              [state text] (require! (assoc state :dht node) 'their2.m)]
          (is (str/includes? text "untrusted-principal") text)
          (is (nil? (entry state 'their2.m)))
          (is (nil? (ld/module-status (:dht state) other))))))))


(deftest untrust-keeps-an-already-answered-allow-test
  (let [w (world)
        decisions (capacity-1)
        [state _] (require! (shell w :semantic
                                   {:admission {:decision-medium decisions}})
                            'their.m)
        id (pending-admission state)
        [state _] (type! state (str "(trust " (pr-str pb) ")"))
        ;; Switch from an absent snapshot to the default trust policy.
        state (assoc state :linker
                     (link/reconfigure (:linker state) {:trusted #{pb}}))
        value (:linker (link/round (:linker state) (:dht state)))
        state (assoc state :linker value)
        [state _] (type! state (str "(untrust " (pr-str pb) ")"))
        [state _] (settle state)]
    (is (= 'their.m (:last-value state)))
    (is (nil? (:pending-run state)))
    (is (= id (get-in (entry state 'their.m)
                      [:admission :yin.admit/id])))))


(deftest abandon-an-open-question-then-import-a-trusted-module-test
  (let [node (dht/join {:local (mem/create-content-mem)})
        store (dht/local node)
        e (publish! store 'pym.e forty-two)
        m (publish! store 'pym.m forty-two)
        w {:node (index! node [(assertion b 'pym.e e 1)
                               (assertion a 'pym.m m 1)])
           :store store :addresses {'pym.e e 'pym.m m}}
        decisions (capacity-1)
        [state text] (require! (shell w :semantic
                                      {:admission {:decision-medium decisions}})
                               'pym.e)
        id (pending-admission state)
        before (get-in state [:linker :pair])
        [state notice] (type! state "(abandon)")
        _ (stream/append! decisions #{pa})
        [state _] (type! state (str "(trust " (pr-str pa) ")"))
        [state _] (require! state 'pym.m)
        [state text2] (settle state)]
    (is (some? id) text)
    (is (str/includes? notice "abandoned"))
    (is (= (:requests before) (get-in state [:linker :pair :requests])))
    (is (= 'pym.m (:last-value state)) text2)
    (is (nil? (:pending-run state)))
    (is (nil? (entry state 'pym.e)))
    (is (not-any? #{(first id)} (map :yin.link/id (shell-responses state))))
    (let [responses (shell-responses state)
          vm (:vm state)
          [later text] (repl/recheck-on-tick
                         (answer-at! state (allow id)) [])]
      (is (nil? text))
      (is (= vm (:vm later)))
      (is (nil? (:pending-run later)))
      (is (= {} (get-in later [:linker :admission :asked])
             (get-in later [:linker :admission :answers])))
      (is (= responses (shell-responses later)))
      (is (nil? (status (:dht later) w 'pym.e))))))


(deftest trust-commands-preserve-external-attachment-test
  (let [w (world)
        state (shell w :semantic {:admission :external})
        value (:linker state)
        [state text] (type! state (str "(trust " (pr-str pb) ")"))]
    (is (str/includes? text pb))
    (is (= #{pb} (get-in state [:admission :trusted])))
    (is (= value (:linker state)))
    (is (nil? (get-in state [:linker :interpreter])))
    (let [[reset _] (type! state "(reset)")]
      (is (= :external (:admission reset)))
      (is (nil? (get-in reset [:linker :interpreter]))))))
