(ns dao.stream.remote-route-test
  (:require [clojure.test :refer [deftest is]]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.remote-pair :as pair]
            [dao.stream.remote-route :as route]
            [dao.stream.remote-channel :as channel]
            [dao.stream.ringbuffer :as ringbuffer]
            [dao.stream.transit :as transit]
            #?(:cljd ["dart:convert" :as convert])))


(defn- encoded-size
  [value]
  (let [text (transit/encode value)]
    #?(:cljd (.-length (convert/utf8.encode text))
       :clj (alength (.getBytes ^String text "UTF-8"))
       :cljs (.-length (.encode (js/TextEncoder.) text)))))


(defn- ring
  []
  (:dao.stream/handle (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                                           :dao.stream.ringbuffer/capacity 256})))


(defn- cd
  [id]
  {:dao.stream/type :test/channel :dao.stream/identity id})


(defn- rd
  [id ch]
  {:dao.stream/type :dao.stream/remote :dao.stream/identity id :dao.stream/channel ch})


(defn- pc
  [id in out]
  {:dao.stream/type :dao.stream/pair :dao.stream/identity id
   :dao.stream.remote/in in :dao.stream.remote/out out})


(defn- entry
  [h]
  {:handle h :surface #{:reader :writer}})


(defn- cursor
  [h]
  (:dao.stream/cursor (stream/cursor h stream/anchor-oldest)))


(defn- mirror
  [table names reader writer]
  (let [c (atom (cursor reader))]
    (fn []
      (reset! c (remote/mirror-step table names reader @c writer
                                    {:dao.stream.remote/mirror-budget 64
                                     :dao.stream.remote/chase-budget 8})))))


(deftest route-preflight-before-allocation-test
  (let [base (cd "base")
        nested (pc "p" (rd "x" base) (rd "y" base))]
    (is (= :ok (:status (route/preflight nested {:max-depth 1 :max-nodes 5} (constantly 16)))))
    (is (= ::route/nodes (:reason (route/preflight nested {:max-nodes 4} (constantly 16)))))
    (is (= ::route/depth (:reason (route/preflight (pc "q" (rd "a" nested) (rd "b" base))
                                                   {:max-depth 1} (constantly 16)))))
    (is (= ::route/bytes (:reason (route/preflight nested {:max-descriptor-bytes 15} (constantly 16)))))
    (is (= :ok (:status (route/preflight nested {:max-descriptor-bytes 16} (constantly 16)))))
    (is (= ::route/invalid-profile (:reason (route/preflight nested {} nil))))))


(deftest board-candidate-provenance-and-deadlines-test
  (let [s (route/discovery {:max-candidates 2 :freshness-ms 30})
        s (route/observe s :board-a {:dao.stream/outcome :dao.stream/ok
                                     :dao.stream/value {:meet/seen "hash" :meet/reflexive :a :meet/incarnation 1}} 0)
        s (route/observe s :board-b {:dao.stream/outcome :dao.stream/ok
                                     :dao.stream/value {:meet/seen "hash" :meet/reflexive :b :meet/incarnation 2}} 1)]
    (is (= [:board-b :board-a] (mapv :board (route/candidates s [:board-b :board-a] {"bob" "hash"} "bob" 2))))
    (is (empty? (route/candidates s [] {} "hash" 2)))
    (is (empty? (route/candidates s [:board-a :board-b] {} "hash" 31)))
    (is (= [] (:candidates (route/observe s :board-a {:dao.stream/outcome :dao.stream/gap} 2))))
    (let [fact {:meet/ready :lease :meet/request :request :meet/incarnation :epoch :meet/side :in}
          one (route/observe s :board-a {:dao.stream/outcome :dao.stream/ok :dao.stream/value fact} 2)
          both (route/observe one :board-a {:dao.stream/outcome :dao.stream/ok :dao.stream/value (assoc fact :meet/side :out)} 3)]
      (is (not (route/ready? one :board-a :lease :request :epoch)))
      (is (route/ready? both :board-a :lease :request :epoch))
      (is (not (route/ready? both :board-a :lease :other :epoch))))))


(deftest two-relays-resolve-at-destination-test
  (let [base (cd "M1")
        ar (ring) aw (ring) mr (ring) mw (ring)
        x1 (ring) y1 (ring) x2 (ring) y2 (ring)
        first-pair (pc "M1-pair" (rd "x1" base) (rd "y1" base))
        opposite (pc "M1-pair-reversed" (rd "y1" base) (rd "x1" base))
        final-pair (pc "M2-pair" (rd "x2" first-pair) (rd "y2" first-pair))
        m1-table {"x1" (entry x1) "y1" (entry y1)}
        m1-a (mirror m1-table nil aw ar)
        m1-m2 (mirror m1-table nil mw mr)
        m2-base (remote/links {:dao.stream.remote/channels {base {:reader mr :writer mw}}})
        m2-pairs (pair/links {:dao.stream.remote.pair/attach! (:attach m2-base)})
        _ ((:resolve m2-pairs) opposite "initialize")
        end ((:channel-end m2-pairs) opposite)
        m2 (mirror {"x2" (entry x2) "y2" (entry y2)} {"target" "relay-wrong"}
                   (:reader end) (:writer end))
        a-source (ring)
        a-id (:dao.stream/identity (stream/descriptor a-source))
        _ (stream/append! a-source :from-a)
        b-channel (cd "B-to-A")
        b-links (remote/links {:dao.stream.remote/channels {b-channel {:reader y2 :writer x2}}})
        source (ring)
        source-id (:dao.stream/identity (stream/descriptor source))
        _ (stream/append! source :hello)
        b (mirror {source-id (entry source)} {"target" source-id} y2 x2)
        d (atom (channel/dial {:route final-pair :channels {base {:reader ar :writer aw}}
                               :name "target" :encoded-size (constantly 1) :now 0
                               :table {a-id (entry a-source)} :names {"a-name" a-id}
                               :ready? true
                               :dao.stream.remote/drain-budget 16
                               :dao.stream.remote/max-outstanding 64
                               :dao.stream.remote/max-filed 128
                               :dao.stream.remote/give-up-after 30000}))
        tick! (fn [now]
                (m1-a) (m1-m2)
                ((:step m2-base) base now)
                ((:step m2-pairs) opposite now)
                (m2) (b)
                (swap! d channel/dial-step now))]
    (is (false? (:opened? @d)) "Deferred attach cannot confirm the route")
    (dotimes [n 40] (tick! n))
    (is (= :attached (:status @d)))
    (is (= source-id (:identity @d)) "The name belongs to B, not M2")
    (is (:opened? @d))
    (let [back (:dao.stream/handle ((:attach b-links) (rd a-id b-channel)))]
      (dotimes [n 12] (tick! (+ 40 n)))
      (is (= (:dao.stream/descriptor (stream/descriptor a-source))
             (:source-descriptor (do (stream/descriptor back) (remote/confirmation back)))))
      (stream/cursor back stream/anchor-oldest)
      (dotimes [n 12] (tick! (+ 52 n)))
      (let [c (:dao.stream/cursor (stream/cursor back stream/anchor-oldest))]
        (stream/next back c)
        (dotimes [n 12] (tick! (+ 64 n)))
        (is (= :from-a (:dao.stream/value (stream/next back c)))))
      (stream/close! back))
    (let [h (channel/handle @d)]
      (is (= source-id (:dao.stream/identity (stream/descriptor h))))
      (stream/cursor h stream/anchor-oldest)
      (dotimes [n 20] (tick! (+ 40 n)))
      (let [c (:dao.stream/cursor (stream/cursor h stream/anchor-oldest))]
        (is (map? c))
        (stream/next h c)
        (dotimes [n 20] (tick! (+ 60 n)))
        (is (= :hello (:dao.stream/value (stream/next h c)))))
      (is (<= (:work-used @d) 4096))
      (swap! d channel/close!)
      (is (= 0 ((:entries (:pairs @d)))))
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! source :still-owned)))))))


(deftest direct-and-one-relay-identity-parity-test
  (let [source (ring)
        source-id (:dao.stream/identity (stream/descriptor source))]
    (stream/append! source :shared-value)
    (doseq [relayed? [false true]]
      (let [base (cd (if relayed? "relay" "direct"))
            requests (ring) answers (ring)
            x (ring) y (ring)
            path (if relayed? (pc "pair" (rd "x" base) (rd "y" base)) base)
            serve (mirror (if relayed? {"x" (entry x) "y" (entry y)} {source-id (entry source)})
                          (when-not relayed? {"target" source-id}) requests answers)
            end (when relayed? (mirror {source-id (entry source)} {"target" source-id} y x))
            d (atom (channel/dial {:route path :channels {base {:reader answers :writer requests}}
                                   :name "target" :encoded-size (constantly 1) :now 0 :ready? true}))
            tick! (fn [n] (serve) (when end (end)) (swap! d channel/dial-step n))]
        (dotimes [n 20] (tick! n))
        (is (= :attached (:status @d)))
        (is (= source-id (:identity @d)))
        (is (= (:dao.stream/descriptor (stream/descriptor source))
               (:source-descriptor (remote/confirmation (:handle @d)))))
        (stream/cursor (:handle @d) stream/anchor-oldest)
        (dotimes [n 8] (tick! (+ 20 n)))
        (let [c (:dao.stream/cursor (stream/cursor (:handle @d) stream/anchor-oldest))]
          (stream/next (:handle @d) c)
          (dotimes [n 8] (tick! (+ 28 n)))
          (is (= :shared-value (:dao.stream/value (stream/next (:handle @d) c)))))
        (swap! d channel/close!)))))


(deftest explicit-bootstrap-and-sequential-attempts-test
  (is (= :no-route (:reason (route/resolution {:target "hash" :boards [] :routes [] :now 0}))))
  (let [a {:peer "hash" :channel (cd "a") :board :one}
        b {:peer "hash" :channel (cd "b") :board :two}
        s (route/resolution {:target "bob" :aliases {"bob" "hash"} :routes [a b]
                             :boards [] :now 0 :identity :exact :peer :self
                             :bounds {:max-attempts 2}})
        s (route/resolution-step s [] nil 1)]
    (is (= a (:current s)))
    (is (= :exact (:identity (last (:actions s)))))
    (let [s (route/resolution-step s [] {:status :failed} 2)]
      (is (= b (:current s)))
      (is (= 2 (:attempts s)))
      (is (= :lost (:status (route/resolution-step s [] {:status :failed} 3)))))
    (is (= :deadline (:reason (route/resolution-step s [] nil 60000))))))


(deftest punch-source-id-and-deadline-test
  (let [s {:status :punching :source :expected :identity :probe :request :fresh :deadline 30}
        answer {:source :expected :value {:dao.stream/identity :probe
                                          :dao.stream.remote/id :fresh
                                          :dao.stream.remote/error :dao.stream.remote/not-found}}]
    (is (= :confirmed (:status (route/punch s answer 1))))
    (is (= :punching (:status (route/punch s (assoc answer :source :wrong) 1))))
    (is (= :punching (:status (route/punch s (assoc-in answer [:value :dao.stream.remote/id] :wrong) 1))))
    (is (= :relay (:status (route/punch s answer 30))))))


(deftest driver-fairness-and-isolated-projection-failure-test
  (let [flood-medium (ring) flood-out (ring)
        broken? (atom false)
        flood-in (reify stream/IDaoStreamDescriptor
                   (descriptor [_] (stream/descriptor flood-medium))


                   stream/IDaoStreamReader

                   (cursor [_ anchor] (stream/cursor flood-medium anchor))

                   (next
                     [_ c]
                     (if @broken? (throw (ex-info "isolated fault" {:fixture true}))
                         (stream/next flood-medium c)))


                   stream/IDaoStreamWriter

                   (append! [_ v] (stream/append! flood-medium v)))
        good-in (ring) good-out (ring) source (ring)
        id (:dao.stream/identity (stream/descriptor source))
        flood (cd "flood") good (cd "good")
        serve (mirror {id (entry source)} {"target" id} good-out good-in)
        plans [{:route flood :channels {flood {:reader flood-in :writer flood-out}}
                :name "missing" :now 0 :ready? true :encoded-size (constantly 1)}
               {:route good :channels {good {:reader good-in :writer good-out}}
                :name "target" :now 0 :ready? true :encoded-size (constantly 1)}]
        state (atom (channel/route-driver plans {:work-budget 32}))]
    (dotimes [n 16]
      (dotimes [_ 100] (stream/append! flood-in :malformed))
      (serve)
      (swap! state channel/route-driver-step (inc n))
      (is (<= (:work-used @state) 32)))
    (is (= :attached (get-in @state [:routes 1 :status])))
    (is (= id (get-in @state [:routes 1 :identity])))
    (let [bad (get-in @state [:routes 0])]
      (reset! broken? true)
      (swap! state channel/route-driver-step 17)
      (swap! state channel/route-driver-step 18)
      (is (= :lost (get-in @state [:routes 0 :status])))
      (is (= :attached (get-in @state [:routes 1 :status])))
      (is (= :running (:status @state)))
      (is (= :establishing (:status bad))))
    (doseq [d (:routes @state)] (channel/close! d))
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! source :owned))))))


(deftest no-tick-work-and-large-time-jump-test
  (let [in (ring) out (ring) base (cd "base")
        d (channel/dial {:route base :channels {base {:reader in :writer out}}
                         :name "target" :now 1 :ready? false :encoded-size (constantly 1)})
        c (cursor out)]
    (is (= :dao.stream/blocked (:dao.stream/outcome (stream/next out c))))
    (is (= :establishing (:status d)))
    (let [ended (channel/dial-step d 60001)]
      (is (= :lost (:status ended)))
      (is (= :expired (channel/cause ended)))
      (is (false? (channel/opened? ended)))
      (is (= :dao.stream/blocked (:dao.stream/outcome (stream/next out c)))))))


(deftest actual-codec-bytes-and-nested-boundaries-test
  (let [base (cd "界界界")
        route (pc "nested" (rd "in" base) (rd "out" base))
        bytes (encoded-size route)]
    (is (> bytes (count (transit/encode route))))
    (is (= ::route/bytes (:reason (route/preflight route {:max-descriptor-bytes (dec bytes)} encoded-size))))
    (is (= :ok (:status (route/preflight route {:max-descriptor-bytes bytes} encoded-size))))
    (is (= :ok (:status (route/preflight route {:max-descriptor-bytes (inc bytes)} encoded-size)))))
  (let [in (ring) out (ring)
        base (cd "relay")
        cd (pc "bounded" (rd "in" base) (rd "out" base))
        value "界界"
        bytes (encoded-size value)
        pairs (pair/links {:dao.stream.remote.pair/attach!
                           (fn [d]
                             {:dao.stream/outcome :dao.stream/ok
                              :dao.stream/handle (if (= "in" (:dao.stream/identity d)) in out)})
                           :encoded-size encoded-size :dao.stream.remote.pair/value-bytes bytes})]
    ((:open! pairs) cd 1)
    (let [writer (:writer ((:channel-end pairs) cd))]
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! writer "界"))))
      (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! writer value))))
      (is (= :dao.stream/invalid-value (:dao.stream/outcome (stream/append! writer "界界界")))))
    ((:release! pairs) cd)))


(deftest holder-needs-source-confirmation-and-current-bound-test
  (let [ticks (ring) events (ring) renewal (ring)
        grant (lease/grant :lease [:in :out] :a {:ms 20})
        holder (lease/observe-grant
                 (lease/initial-holder {:self :a :grantor :grantor :units {:ms 1}
                                        :renewal-interval {:ms 3}
                                        :resolver (fn [source _fact] source)})
                 :grantor grant {:ms 1})
        state (route/holder {:holder holder :tick {:handle ticks :cursor (cursor ticks)}
                             :writer {:handle renewal}}
                            {:handle events :cursor (cursor events)} :renewal)
        allowance (atom 64)]
    (stream/append! ticks (lease/tick {:ms 4}))
    (let [sent (route/holder-step state allowance)]
      (is (= {:ms 4} (:pending sent)))
      (is (nil? (get-in sent [:composition :holder :last-renewal-at])))
      (stream/append! events {:dao.stream/identity :renewal :dao.stream/outcome :dao.stream/ok
                              :dao.stream.remote/id 1})
      (stream/append! ticks (lease/tick {:ms 5}))
      (reset! allowance 64)
      (let [confirmed (route/holder-step sent allowance)]
        (is (= {:ms 4} (get-in confirmed [:composition :holder :last-renewal-at])))
        (is (nil? (:pending confirmed))))
      (stream/append! ticks (lease/tick {:ms 100}))
      (reset! allowance 64)
      (let [expired (route/holder-step sent allowance)]
        (is (= :lost (:status expired)))
        (is (get-in expired [:composition :holder :bound-reached?]))
        (is (nil? (get-in expired [:composition :holder :last-renewal-at])))))))


(deftest shared-subtrees-are-stepped-before-all-parents-test
  (let [base (cd "base")
        shared (pc "shared" (rd "s-in" base) (rd "s-out" base))
        a (pc "a" (rd "a-in" shared) (rd "a-out" shared))
        b (pc "b" (rd "b-in" shared) (rd "b-out" shared))
        root (pc "root" (rd "r-in" a) (rd "r-out" b))
        dependencies (:dependencies (route/preflight root {} encoded-size))]
    (is (= shared (first dependencies)))
    (is (= root (last dependencies)))
    (is (= #{shared a b root} (set dependencies))))
  (let [base (cd "base") in (ring) out (ring)
        plan {:route base :channels {base {:reader in :writer out}}
              :identity :source :now 1 :encoded-size encoded-size}]
    (is (= ::route/shared-base-owner (:reason (channel/route-driver [plan plan] {}))))))
