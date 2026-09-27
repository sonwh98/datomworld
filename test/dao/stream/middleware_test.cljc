(ns dao.stream.middleware-test
  "The middleware slice: the position rule under a value cipher over a
   ring buffer; the allow-list gate refusing with :dao.stream/refused; a
   gate reading the latest decision through a capacity-1 medium after
   eviction; a filter that cannot be expressed, a violating out
   raising; and the operation rules of apply-request and wrap, the
   short-circuit ordering, the failure markers, the gate's wrap-minted
   decision-read lifecycle, metering and present."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.middleware :as mw]
            [dao.stream.ringbuffer :as ringbuffer]))


;; =============================================================================
;; Test support
;; =============================================================================

(defn- ring
  "An open owner ring-buffer handle with the given capacity."
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity capacity})))


(defn- drain-values
  "Every retained value of `h`, oldest first, by a fresh cursor walk."
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h :dao.stream/oldest))
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- encipher
  "The test cipher's encipher half: a tagged map, total and invertible."
  [v]
  {:cipher/of v})


(defn- decipher
  "The test cipher's decipher half: it throws on anything that is not
   its own ciphertext, as a library cipher fails on a corrupt value."
  [v]
  (if (and (map? v) (contains? v :cipher/of))
    (:cipher/of v)
    (throw (ex-info "undecipherable" {:value v}))))


(defn- encryption
  "The spec's value-encryption exemplar over the test cipher."
  []
  (mw/encryption {:dao.stream.middleware/encipher encipher
                  :dao.stream.middleware/decipher decipher}))


(defn- recording-handle
  "A reader, writer and closable handle recording every protocol call in
   `calls` and answering fixed contract-valid outcomes."
  [calls]
  (reify
    stream/IDaoStreamDescriptor
    (descriptor
      [_]
      (swap! calls conj :descriptor)
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/descriptor {:dao.stream/type :test/only
                               :dao.stream/identity :inner}
       :dao.stream/identity :inner})


    stream/IDaoStreamReader

    (cursor
      [_ anchor]
      (swap! calls conj [:cursor anchor])
      {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor :minted})

    (next
      [_ c]
      (swap! calls conj [:next c])
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/value :inner-value
       :dao.stream/cursor :successor})


    stream/IDaoStreamWriter

    (append!
      [_ v]
      (swap! calls conj [:append! v])
      {:dao.stream/outcome :dao.stream/ok})


    stream/IDaoStreamClosable

    (close!
      [_]
      (swap! calls conj :close!)
      {:dao.stream/outcome :dao.stream/ok})))


(defn- spy
  "A middleware recording [:in name] / [:out name] in `log` around the
   given transform behavior."
  [name log in-fn out-fn]
  {:dao.stream.middleware/in
   (fn [ctx req] (swap! log conj [:in name]) (in-fn ctx req))
   :dao.stream.middleware/out
   (fn [ctx req outcome]
     (swap! log conj [:out name])
     (out-fn ctx req outcome))})


(defn- pass-in
  [_ctx req]
  req)


(defn- pass-out
  [_ctx _req outcome]
  outcome)


(defn- refuse-in
  "An `in` that short-circuits with a refused outcome naming `reason`."
  [reason]
  (fn [_ctx _req]
    {:dao.stream/outcome :dao.stream/refused
     :dao.stream.middleware/reason reason}))


(defn- scripted-medium
  "A reader-only decision medium: each `cursor` and `next` call is
   recorded in `calls` and answered by popping the head of the matching
   queue atom."
  [calls cursors reads]
  (reify
    stream/IDaoStreamReader
    (cursor
      [_ anchor]
      (swap! calls conj [:cursor anchor])
      (let [[r & more] @cursors]
        (reset! cursors more)
        r))

    (next
      [_ c]
      (swap! calls conj [:next c])
      (let [[r & more] @reads]
        (reset! reads more)
        r))))


(defn- ok-cursor
  "A scripted :ok cursor answer for `c`."
  [c]
  {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor c})


(defn- ok-read
  "A scripted :ok read answer of `v` with successor cursor `c`."
  [v c]
  {:dao.stream/outcome :dao.stream/ok
   :dao.stream/value v :dao.stream/cursor c})


(defn- gap-read
  "A scripted :gap read answer with recovery cursor `c`."
  [c]
  {:dao.stream/outcome :dao.stream/gap :dao.stream/cursor c})


;; =============================================================================
;; apply-request
;; =============================================================================

(deftest apply-request-dispatches-each-operation-once
  (let [h (ring 4)
        c (:dao.stream/cursor
            (mw/apply-request h {}
                              {:dao.stream.remote/op :dao.stream/cursor
                               :dao.stream.remote/args
                               [:dao.stream/oldest]}))
        a (mw/apply-request h {}
                            {:dao.stream.remote/op :dao.stream/append!
                             :dao.stream.remote/args [:v]
                             :dao.stream.test/open :kept})
        n (mw/apply-request h {}
                            {:dao.stream.remote/op :dao.stream/next
                             :dao.stream.remote/args [c]})]
    (is (= :dao.stream/ok (:dao.stream/outcome a)))
    (is (= :dao.stream/ok (:dao.stream/outcome n)))
    (is (= :v (:dao.stream/value n)))
    (is (= (stream/next h c) n) "the protocol call itself, verbatim")
    (testing "an op outside the middleware vocabulary is a host assembly
              defect and throws"
      (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
            (mw/apply-request h {}
                              {:dao.stream.remote/op
                               :dao.stream/close!
                               :dao.stream.remote/args []}))))))


;; =============================================================================
;; wrap: order, short-circuit, delegation, surfaces
;; =============================================================================

(deftest wrap-runs-ins-outermost-first-and-outs-innermost-outward
  (let [calls (atom [])
        log (atom [])
        wrapped (mw/wrap (recording-handle calls)
                         [(spy :a log pass-in pass-out)
                          (spy :b log pass-in pass-out)])]
    (is (= :dao.stream/ok
           (:dao.stream/outcome (stream/append! wrapped :v))))
    (is (= [[:in :a] [:in :b] [:out :b] [:out :a]] @log))
    (is (= [[:append! :v]] @calls))))


(deftest an-in-outcome-short-circuits-past-the-inner-handle
  (let [calls (atom [])
        log (atom [])
        wrapped (mw/wrap (recording-handle calls)
                         [(spy :a log pass-in pass-out)
                          (spy :b log (refuse-in :b) pass-out)
                          (spy :c log pass-in pass-out)])
        outcome (stream/cursor wrapped :dao.stream/oldest)]
    (is (= :dao.stream/refused (:dao.stream/outcome outcome)))
    (is (= :b (:dao.stream.middleware/reason outcome)))
    (is (= [] @calls) "the inner handle is not consulted")
    (is (= [[:in :a] [:in :b] [:out :a]] @log)
        "only the middlewares outside b run their out transforms")))


(deftest descriptor-and-close-delegate-unchanged
  (let [calls (atom [])
        log (atom [])
        h (recording-handle calls)
        wrapped (mw/wrap h [(spy :a log pass-in pass-out)])]
    (is (= (stream/descriptor h) (stream/descriptor wrapped)))
    (is (= :dao.stream/ok
           (:dao.stream/outcome (stream/close! wrapped))))
    (is (= [:descriptor :descriptor :close!] @calls))
    (is (empty? @log) "no middleware sees descriptor or close!")))


(deftest the-wrapped-handle-implements-exactly-the-inner-surfaces
  (let [reader-only
        (reify stream/IDaoStreamReader
          (cursor
            [_ _]
            {:dao.stream/outcome :dao.stream/ok
             :dao.stream/cursor :c})

          (next [_ _] {:dao.stream/outcome :dao.stream/blocked}))]
    (let [w (mw/wrap reader-only
                     [(spy :a (atom []) pass-in pass-out)])]
      (is (stream/descriptor? w))
      (is (stream/reader? w))
      (is (not (stream/writer? w)))
      (is (not (stream/closable? w))))
    (let [w (mw/wrap (ring 2)
                     [(spy :a (atom []) pass-in pass-out)])]
      (is (stream/reader? w))
      (is (stream/writer? w))
      (is (stream/closable? w)))))


;; =============================================================================
;; The position rule under a value cipher over a ring buffer
;; =============================================================================

(deftest a-value-cipher-preserves-positions-cursors-and-outcomes
  (let [inner (ring 4)
        wrapped (mw/wrap inner [(encryption)])]
    (is (= :dao.stream/ok
           (:dao.stream/outcome (stream/append! wrapped :v1))))
    (is (= :dao.stream/ok
           (:dao.stream/outcome (stream/append! wrapped :v2))))
    (is (= [{:cipher/of :v1} {:cipher/of :v2}] (drain-values inner))
        "the inner handle stores only ciphertext")
    (let [cw (:dao.stream/cursor
               (stream/cursor wrapped :dao.stream/oldest))
          ci (:dao.stream/cursor (stream/cursor inner :dao.stream/oldest))]
      (is (= ci cw) "the wrapped handle mints the inner handle's cursors")
      (let [rw (stream/next wrapped cw)
            ri (stream/next inner ci)]
        (is (= :v1 (:dao.stream/value rw))
            "the wrapped handle presents the plaintext")
        (is (= {:cipher/of :v1} (:dao.stream/value ri)))
        (is (= (:dao.stream/cursor ri) (:dao.stream/cursor rw))
            "the successor cursor is the inner handle's own")
        (is (= (:dao.stream/outcome ri) (:dao.stream/outcome rw))
            "the outcome kind is untouched"))))
  (testing "a gap crosses with the source's own recovery cursor"
    (let [inner (ring 2)
          wrapped (mw/wrap inner [(encryption)])
          ci (:dao.stream/cursor (stream/cursor inner :dao.stream/oldest))
          cw (:dao.stream/cursor
               (stream/cursor wrapped :dao.stream/oldest))]
      (is (= ci cw))
      (doseq [v [:v1 :v2 :v3]]
        (is (= :dao.stream/ok
               (:dao.stream/outcome (stream/append! wrapped v)))))
      (let [gi (stream/next inner ci)
            gw (stream/next wrapped cw)]
        (is (= :dao.stream/gap (:dao.stream/outcome gw)))
        (is (= (:dao.stream/cursor gi) (:dao.stream/cursor gw))
            "the recovery cursor is the source's, unparsed and unrewritten")
        (let [rw (stream/next wrapped (:dao.stream/cursor gw))]
          (is (= :dao.stream/ok (:dao.stream/outcome rw)))
          (is (= :v2 (:dao.stream/value rw))
              "the recovery read presents the retained plaintext")
          (is (= :v3 (:dao.stream/value
                       (stream/next wrapped (:dao.stream/cursor rw))))
              "and the walk continues to the newest plaintext"))))))


(deftest an-undecodable-value-is-presented-as-the-marker
  (let [inner (ring 4)
        wrapped (mw/wrap inner [(encryption)])]
    (is (= :dao.stream/ok
           (:dao.stream/outcome (stream/append! inner :raw))))
    (let [c (:dao.stream/cursor
              (stream/cursor wrapped :dao.stream/oldest))
          plain (stream/next inner c)
          r (stream/next wrapped c)]
      (is (= :dao.stream/ok (:dao.stream/outcome r)))
      (is (= {:dao.stream.middleware/undecodable true
              :dao.stream.middleware/raw :raw}
             (:dao.stream/value r)))
      (is (= (:dao.stream/cursor plain) (:dao.stream/cursor r))
          "the cursor is untouched: the position is real")
      (is (= (:dao.stream/outcome plain) (:dao.stream/outcome r))
          "the outcome is untouched: the reader decides"))))


(deftest an-untransformable-value-answers-invalid-value-on-the-way-in
  (let [inner (ring 4)
        encipher (fn [v]
                   (if (= v :poison)
                     (throw (ex-info "cannot encode" {:value v}))
                     {:cipher/of v}))
        wrapped (mw/wrap inner
                         [(mw/encryption
                            {:dao.stream.middleware/encipher encipher
                             :dao.stream.middleware/decipher decipher})])]
    (is (= :dao.stream/invalid-value
           (:dao.stream/outcome (stream/append! wrapped :poison))))
    (is (empty? (drain-values inner))
        "nothing reached the inner handle and nothing was appended")))


;; =============================================================================
;; A filter cannot be expressed
;; =============================================================================

(deftest a-filter-cannot-be-expressed
  (testing "an out may replace a value, never remove the position"
    (let [inner (ring 4)
          tombstone
          (spy :filter (atom []) pass-in
               (fn [_ctx _req outcome]
                 (if (and (= :dao.stream/ok (:dao.stream/outcome outcome))
                          (= :drop (:dao.stream/value outcome)))
                   (assoc outcome :dao.stream/value :dropped)
                   outcome)))
          wrapped (mw/wrap inner [tombstone])]
      (doseq [v [:a :drop :c]]
        (is (= :dao.stream/ok
               (:dao.stream/outcome (stream/append! wrapped v)))))
      (loop [cw (:dao.stream/cursor
                  (stream/cursor wrapped :dao.stream/oldest))
             ci (:dao.stream/cursor
                  (stream/cursor inner :dao.stream/oldest))
             seen []]
        (let [rw (stream/next wrapped cw)
              ri (stream/next inner ci)]
          (if (= :dao.stream/ok (:dao.stream/outcome rw))
            (do (is (= (:dao.stream/cursor ri) (:dao.stream/cursor rw))
                    "the chain keeps the inner handle's positions")
                (recur (:dao.stream/cursor rw) (:dao.stream/cursor ri)
                       (conj seen (:dao.stream/value rw))))
            (is (= [:a :dropped :c] seen)
                "every element is still presented: dropping is not a
                 transform's to decide"))))))
  (testing "declining one element declines the whole operation"
    (let [inner (ring 4)
          wrapped (mw/wrap inner
                           [(spy :gate (atom []) (refuse-in :filtered)
                                 pass-out)])]
      (is (= :dao.stream/refused
             (:dao.stream/outcome (stream/append! wrapped :drop))))
      (is (empty? (drain-values inner))
          "the stream holds nothing: a refusal is data, not a silent
           skip"))))


(deftest an-out-that-violates-the-position-rule-raises
  (testing "an out that turns an ok from next into blocked raises"
    (let [inner (ring 4)
          wrapped
          (mw/wrap inner
                   [(spy :filter (atom []) pass-in
                         (fn [_ctx _req outcome]
                           (if (= :drop (:dao.stream/value outcome))
                             (assoc outcome :dao.stream/outcome
                                    :dao.stream/blocked)
                             outcome)))])]
      (is (= :dao.stream/ok
             (:dao.stream/outcome (stream/append! wrapped :drop))))
      (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
            (stream/next
              wrapped
              (:dao.stream/cursor
                (stream/cursor wrapped :dao.stream/oldest)))))
      (is (= [:drop] (drain-values inner))
          "the element is still there: the chain raised and the stream
           kept its sequence")))
  (testing "an out that changes the outcome kind raises"
    (let [inner (ring 4)
          wrapped
          (mw/wrap inner
                   [(spy :filter (atom []) pass-in
                         (fn [_ctx _req _outcome]
                           {:dao.stream/outcome :dao.stream/refused}))])]
      (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
            (stream/append! wrapped :v)))))
  (testing "an out that rewrites a cursor raises"
    (let [inner (ring 4)
          wrapped
          (mw/wrap inner
                   [(spy :filter (atom []) pass-in
                         (fn [_ctx req outcome]
                           (if (= :dao.stream/next
                                  (:dao.stream.remote/op req))
                             (assoc outcome :dao.stream/cursor :forged)
                             outcome)))])]
      (is (= :dao.stream/ok
             (:dao.stream/outcome (stream/append! wrapped :v))))
      (is (thrown? #?(:clj Exception :cljs js/Error :cljd Object)
            (stream/next
              wrapped
              (:dao.stream/cursor
                (stream/cursor wrapped :dao.stream/oldest))))))))


(deftest an-out-adding-a-nil-protected-key-raises
  ;; a nil value under a protected key is an ADDITION, not a no-op:
  ;; position-preserving? compares presence as well as values
  (let [raise-with (fn [out-fn]
                     (let [inner (ring 4)
                           wrapped
                           (mw/wrap inner
                                    [(spy :filter (atom []) pass-in
                                          out-fn)])]
                       (try
                         (do (stream/append! wrapped :v)
                             (stream/next
                               wrapped
                               (:dao.stream/cursor
                                 (stream/cursor wrapped
                                                :dao.stream/oldest))))
                         (catch #?(:clj Exception :cljs js/Error
                                   :cljd Object) e
                           (ex-message e)))))]
    (testing "adding :dao.stream/cursor nil raises"
      (is (re-find #"position rule"
                   (raise-with
                     (fn [_ _ outcome]
                       (assoc outcome :dao.stream/cursor nil))))))
    (testing "adding :dao.stream/identity nil raises"
      (is (re-find #"position rule"
                   (raise-with
                     (fn [_ _ outcome]
                       (assoc outcome :dao.stream/identity nil))))))
    (testing "adding :dao.stream/value nil on a non-ok raises"
      (is (re-find #"position rule"
                   (raise-with
                     (fn [_ _ outcome]
                       (assoc outcome :dao.stream/value nil))))))))


;; =============================================================================
;; gate
;; =============================================================================

(deftest a-gate-with-the-channel-allow-list-refuses
  (let [inner (ring 4)
        wrapped (mw/wrap inner
                         [(mw/gate
                            {:dao.stream.middleware/verify
                             (mw/channel-allow-list #{"a"})
                             :dao.stream.middleware/decision (ring 1)})])]
    (testing "a channel on the list passes and the operation lands"
      (is (= :dao.stream/ok
             (:dao.stream/outcome
               (mw/apply-request wrapped
                                 {:dao.stream.remote/channel "a"}
                                 {:dao.stream.remote/op :dao.stream/append!
                                  :dao.stream.remote/args [:v]}))))
      (is (= [:v] (drain-values inner))))
    (testing "a channel off the list is refused with its reason"
      (let [r (mw/apply-request wrapped
                                {:dao.stream.remote/channel "z"}
                                {:dao.stream.remote/op :dao.stream/append!
                                 :dao.stream.remote/args [:w]})]
        (is (= :dao.stream/refused (:dao.stream/outcome r)))
        (is (= :dao.stream.middleware/channel-not-allowed
               (:dao.stream.middleware/reason r))))
      (is (= [:v] (drain-values inner)) "nothing was appended"))
    (testing "a local caller's {} context names no channel and refuses"
      (is (= :dao.stream/refused
             (:dao.stream/outcome (stream/append! wrapped :x)))))))


(deftest a-gate-reads-the-latest-decision-through-a-capacity-1-medium
  (let [medium (ring 1)
        seen (atom [])
        gate-mw (mw/gate
                  {:dao.stream.middleware/verify
                   (fn [d _ctx _req] (swap! seen conj d) nil)
                   :dao.stream.middleware/decision medium})
        target (ring 4)
        wrapped (mw/wrap target [gate-mw])
        ;; One operation per gate pass: the cursor is the target's own,
        ;; minted once, and every read! is one next through the gate.
        c0 (:dao.stream/cursor (stream/cursor target :dao.stream/oldest))
        read! (fn []
                (mw/apply-request wrapped {}
                                  {:dao.stream.remote/op :dao.stream/next
                                   :dao.stream.remote/args [c0]}))]
    (is (= :dao.stream/blocked (:dao.stream/outcome (read!))))
    (is (= [{:dao.stream.middleware/none true}] @seen)
        "the medium has published nothing yet")
    (stream/append! medium :d1)
    (read!)
    (is (= [{:dao.stream.middleware/none true} :d1] @seen))
    ;; Two further decisions evict :d1; the gate's cursor spans the
    ;; eviction of :d2 as well.
    (stream/append! medium :d2)
    (stream/append! medium :d3)
    (read!)
    (is (= [{:dao.stream.middleware/none true} :d1 :d3] @seen)
        "the latest decision, recovered through the eviction")))


(deftest the-gate-decision-read-lifecycle
  (testing "the definition mints nothing; each wrap mints its own"
    (let [calls (atom [])
          gate-mw
          (mw/gate
            {:dao.stream.middleware/verify (fn [_ _ _] nil)
             :dao.stream.middleware/decision
             (scripted-medium calls
                              (atom [(ok-cursor :c0) (ok-cursor :c1)])
                              (atom [{:dao.stream/outcome
                                      :dao.stream/blocked}
                                     {:dao.stream/outcome
                                      :dao.stream/blocked}]))})]
      (is (= [] @calls) "a definition is inert: no state, no mint")
      (let [wrapped-a (mw/wrap (recording-handle (atom [])) [gate-mw])]
        (is (= [[:cursor :dao.stream/oldest]] @calls)
            "the first wrap mints once, at wrap")
        (let [wrapped-b (mw/wrap (recording-handle (atom [])) [gate-mw])]
          (is (= [[:cursor :dao.stream/oldest]
                  [:cursor :dao.stream/oldest]] @calls)
              "the same definition mints again for the second wrap")
          (is (= :dao.stream/ok
                 (:dao.stream/outcome (stream/next wrapped-a :c))))
          (is (= :dao.stream/ok
                 (:dao.stream/outcome (stream/next wrapped-b :c))))
          (is (= [[:cursor :dao.stream/oldest]
                  [:cursor :dao.stream/oldest]
                  [:next :c0] [:next :c1]] @calls)
              "each wrapped handle reads the shared medium through its
               own minted cursor")))))
  (testing "a mint that never answers ok leaves verify the none marker"
    (let [calls (atom [])
          medium (scripted-medium
                   calls
                   (atom [{:dao.stream/outcome :dao.stream/transport-error}
                          {:dao.stream/outcome :dao.stream/transport-error}])
                   (atom []))
          seen (atom [])
          gate-mw (mw/gate
                    {:dao.stream.middleware/verify
                     (fn [d _ctx _req] (swap! seen conj d) nil)
                     :dao.stream.middleware/decision medium})
          inner-calls (atom [])
          wrapped (mw/wrap (recording-handle inner-calls) [gate-mw])]
      (is (= :dao.stream/ok
             (:dao.stream/outcome (stream/next wrapped :my-c))))
      (is (= [{:dao.stream.middleware/none true}] @seen))
      (is (= [[:cursor :dao.stream/oldest] [:cursor :dao.stream/oldest]] @calls)
          "the wrap mint, then one retry this operation; next is skipped")
      (is (= [[:next :my-c]] @inner-calls)
          "the request itself passes through unchanged")))
  (testing "blocked keeps the cursor and the last value"
    (let [calls (atom [])
          medium (scripted-medium calls
                                  (atom [(ok-cursor :c0)])
                                  (atom [(ok-read :d1 :c1)
                                         {:dao.stream/outcome
                                          :dao.stream/blocked}]))
          seen (atom [])
          wrapped (mw/wrap (ring 4)
                           [(mw/gate
                              {:dao.stream.middleware/verify
                               (fn [d _ctx _req] (swap! seen conj d) nil)
                               :dao.stream.middleware/decision medium})])]
      (stream/next wrapped :c)
      (stream/next wrapped :c)
      (is (= [:d1 :d1] @seen) "the last value is kept across blocked")
      (is (= [[:cursor :dao.stream/oldest] [:next :c0] [:next :c1]] @calls)
          "next is called once per operation")))
  (testing "end ends the gate: no later operation reads the medium"
    (let [calls (atom [])
          medium (scripted-medium calls
                                  (atom [(ok-cursor :c0)])
                                  (atom [{:dao.stream/outcome
                                          :dao.stream/end}]))
          seen (atom [])
          wrapped (mw/wrap (ring 4)
                           [(mw/gate
                              {:dao.stream.middleware/verify
                               (fn [d _ctx _req] (swap! seen conj d) nil)
                               :dao.stream.middleware/decision medium})])]
      (stream/next wrapped :c)
      (stream/next wrapped :c)
      (is (= [{:dao.stream.middleware/ended true}
              {:dao.stream.middleware/ended true}] @seen))
      (is (= [[:cursor :dao.stream/oldest] [:next :c0]] @calls)
          "the ended gate reads no more")))
  (testing "one recovery read after a gap, then the latest value"
    (let [calls (atom [])
          medium (scripted-medium calls
                                  (atom [(ok-cursor :c0)])
                                  (atom [(gap-read :c9)
                                         (ok-read :d2 :c10)
                                         {:dao.stream/outcome
                                          :dao.stream/blocked}]))
          seen (atom [])
          wrapped (mw/wrap (ring 4)
                           [(mw/gate
                              {:dao.stream.middleware/verify
                               (fn [d _ctx _req] (swap! seen conj d) nil)
                               :dao.stream.middleware/decision medium})])]
      (stream/next wrapped :c)
      (stream/next wrapped :c)
      (is (= [:d2 :d2] @seen))
      (is (= [[:cursor :dao.stream/oldest] [:next :c0] [:next :c9] [:next :c10]]
             @calls)
          "the gap adopts the recovery cursor and reads it once")))
  (testing "a gap recovered by a gap stays without a value, no third read"
    (let [calls (atom [])
          medium (scripted-medium calls
                                  (atom [(ok-cursor :c0)])
                                  (atom [(gap-read :c9)
                                         (gap-read :c11)
                                         {:dao.stream/outcome
                                          :dao.stream/blocked}]))
          seen (atom [])
          wrapped (mw/wrap (ring 4)
                           [(mw/gate
                              {:dao.stream.middleware/verify
                               (fn [d _ctx _req] (swap! seen conj d) nil)
                               :dao.stream.middleware/decision medium})])]
      (stream/next wrapped :c)
      (stream/next wrapped :c)
      (is (= [{:dao.stream.middleware/none true}
              {:dao.stream.middleware/none true}] @seen))
      (is (= [[:cursor :dao.stream/oldest] [:next :c0] [:next :c9] [:next :c11]]
             @calls)
          "the second recovery cursor is adopted, never read twice")))
  (testing "a non-ok read clears cursor and value; the mint retries afresh"
    (let [calls (atom [])
          medium (scripted-medium
                   calls
                   (atom [(ok-cursor :c0) (ok-cursor :c1)])
                   (atom [{:dao.stream/outcome :dao.stream/cursor-mismatch}
                          (ok-read :d3 :c2)]))
          seen (atom [])
          wrapped (mw/wrap (ring 4)
                           [(mw/gate
                              {:dao.stream.middleware/verify
                               (fn [d _ctx _req] (swap! seen conj d) nil)
                               :dao.stream.middleware/decision medium})])]
      (stream/next wrapped :c)
      (stream/next wrapped :c)
      (is (= [{:dao.stream.middleware/none true} :d3] @seen))
      (is (= [[:cursor :dao.stream/oldest] [:next :c0]
              [:cursor :dao.stream/oldest] [:next :c1]]
             @calls)
          "the next operation mints afresh")))
  (testing "nil passes the request through; a reason short-circuits"
    (let [calls (atom [])
          inner-calls (atom [])
          medium (scripted-medium
                   calls
                   (atom [(ok-cursor :c0)])
                   (atom [(ok-read :good :c1) (ok-read :bad :c2)]))
          wrapped (mw/wrap
                    (recording-handle inner-calls)
                    [(mw/gate
                       {:dao.stream.middleware/verify
                        (fn [d _ctx _req] (when (= :bad d) :no))
                        :dao.stream.middleware/decision medium})])
          op (fn [] (stream/next wrapped :my-c))]
      (is (= :dao.stream/ok (:dao.stream/outcome (op))))
      (is (= [[:next :my-c]] @inner-calls)
          "a nil answer passes the request through unchanged")
      (is (= :dao.stream/refused (:dao.stream/outcome (op))))
      (is (= [[:next :my-c]] @inner-calls)
          "a refusal is a short-circuit: the inner handle is not
           consulted")
      (is (= [[:cursor :dao.stream/oldest] [:next :c0] [:next :c1]]
             @calls)
          "one medium read per operation, the refusal consuming the last"))))


;; =============================================================================
;; The exemplars
;; =============================================================================

(deftest metering-emits-per-operation-and-keeps-the-outcome
  (let [target (ring 4)
        meter (ring 8)
        wrapped (mw/wrap target
                         [(mw/metering {:meter/stream meter
                                        :meter/identity "peer-a"})])
        c (:dao.stream/cursor (stream/cursor wrapped :dao.stream/oldest))]
    (is (= :dao.stream/blocked (:dao.stream/outcome (stream/next wrapped c))))
    (is (= :dao.stream/ok
           (:dao.stream/outcome (stream/append! wrapped :v))))
    (is (= [{:meter/identity "peer-a"
             :meter/op :dao.stream/cursor
             :meter/outcome :dao.stream/ok}
            {:meter/identity "peer-a"
             :meter/op :dao.stream/next
             :meter/outcome :dao.stream/blocked}
            {:meter/identity "peer-a"
             :meter/op :dao.stream/append!
             :meter/outcome :dao.stream/ok}]
           (drain-values meter))
        "one emission per operation, outcome returned unchanged")))


(deftest present-associates-the-credential-inward
  (let [inner-calls (atom [])
        log (atom [])
        seen-reqs (atom [])
        wrapped (mw/wrap
                  (recording-handle inner-calls)
                  [(mw/present
                     {:dao.stream.middleware/present
                      (fn [ctx _req]
                        {:token (:dao.stream.remote/channel ctx)})})
                   (spy :inside log
                        (fn [_ctx req] (swap! seen-reqs conj req) req)
                        pass-out)])
        outcome (mw/apply-request wrapped
                                  {:dao.stream.remote/channel "a"}
                                  {:dao.stream.remote/op :dao.stream/append!
                                   :dao.stream.remote/args [:v]})]
    (is (= :dao.stream/ok (:dao.stream/outcome outcome)))
    (is (= [{:token "a"}]
           (mapv :dao.stream.remote/credential @seen-reqs))
        "the credential rides the operation map inward")
    (is (= [[:append! :v]] @inner-calls)
        "the inner handle sees only the operation's own args")
    (is (= [[:in :inside] [:out :inside]] @log)
        "the out transform is identity")))


;; =============================================================================
;; The index-interpreter exemplar publishing decisions
;; =============================================================================

(defn- index-fold
  "The slice's index-interpreter exemplar: folds the retained facts of
   `facts` from `cursor`, deriving the running set of allowed channels
   and appending each new state to `medium` as a decision. An
   interpreter folds streams; only transforms are bound by the four
   prohibitions. Returns the fold's final cursor."
  [facts medium cursor]
  (loop [cursor cursor, allowed #{}]
    (let [r (stream/next facts cursor)]
      (case (:dao.stream/outcome r)
        :dao.stream/ok
        (let [fact (:dao.stream/value r)
              allowed (cond
                        (contains? fact :middleware.test/allow)
                        (conj allowed (:middleware.test/allow fact))
                        (contains? fact :middleware.test/deny)
                        (disj allowed (:middleware.test/deny fact))
                        :else allowed)
              appended (stream/append! medium allowed)]
          (is (= :dao.stream/ok (:dao.stream/outcome appended)))
          (recur (:dao.stream/cursor r) allowed))
        cursor))))


(deftest the-index-interpreter-exemplar-publishes-decisions
  (let [facts (ring 16)
        medium (ring 1)
        wrapped (mw/wrap
                  (ring 4)
                  [(mw/gate
                     {:dao.stream.middleware/verify
                      (fn [decision ctx _req]
                        (if (contains? decision
                                       (:dao.stream.remote/channel ctx))
                          nil
                          :middleware.test/not-allowed))
                      :dao.stream.middleware/decision medium})])
        ask (fn [channel]
              (mw/apply-request
                wrapped
                {:dao.stream.remote/channel channel}
                {:dao.stream.remote/op :dao.stream/append!
                 :dao.stream.remote/args [:v]}))]
    (stream/append! facts {:middleware.test/allow "a"})
    (let [c1 (index-fold facts medium
                         (:dao.stream/cursor
                           (stream/cursor facts :dao.stream/oldest)))]
      (testing "the gate consumes the index's published decision"
        (is (= :dao.stream/ok (:dao.stream/outcome (ask "a"))))
        (is (= :dao.stream/refused (:dao.stream/outcome (ask "b")))))
      ;; The index folds further facts and publishes afresh; the
      ;; capacity-1 medium evicts the earlier decision, and the gate's
      ;; cursor crosses the eviction to the latest state.
      (stream/append! facts {:middleware.test/allow "b"})
      (stream/append! facts {:middleware.test/deny "a"})
      (index-fold facts medium c1)
      (testing "the latest published state governs, after eviction"
        (is (= :dao.stream/ok (:dao.stream/outcome (ask "b"))))
        (is (= :dao.stream/refused (:dao.stream/outcome (ask "a"))))))))
