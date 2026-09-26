(ns yin.vm.linker-step-test
  "M3 (docs/design/yin.vm.linker.md section 9): the stepped core and the
   link runtime. Every test here drives `link-state`, `request-link`,
   `step`, and `abandon` directly over a ring-buffer content pair whose
   server the test itself advances; `fetch` appears only to show it is
   the same traffic. The fixtures are `yin.vm.linker-test`'s, so the
   same corpus runs on the JVM, Node, and Dart."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.jing.remote :as remote]
            [dao.stream :as stream]
            [dao.stream.rpc :as rpc]
            [yin.vm :as vm]
            [yin.vm.content :as content]
            [yin.vm.linker :as linker]
            [yin.vm.linker-test :as lt]
            [yin.vm.semantic :as semantic]))


;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:private all-formats
  "The four format records, keyed by format (section 6.2)."
  (into {}
        (map (fn [r] [(:format r) r]))
        [linker/stack-format linker/register-format linker/ast-format
         linker/semantic-format]))


(defn- runtime
  "A local runtime serving `store` whose state holds all four records,
   `indexes`, and any further `link-state` options in `opts`."
  ([store indexes] (runtime store indexes {}))
  ([store indexes opts]
   (lt/local-runtime (remote/default-handlers store)
                     (merge {:formats all-formats, :indexes indexes} opts)
                     1024)))


(defn- request-for
  "A by-identity link request for `format`'s own contract, under the id
   `[:t0 1]` unless `extra` says otherwise."
  ([format identity] (request-for format identity {}))
  ([format identity extra]
   (merge {:yin.link/id [:t0 1],
           :yin.link/format (:format format),
           :yin.link/contract (:contract format),
           :yin.link/identity identity}
          extra)))


(defn- link
  "The one completion `request` receives when the runtime's drive serves
   the pair before every `step`, or `::stalled`."
  [{:keys [state drive]} request]
  (let [[state id] (linker/request-link state request)]
    (loop [state state, n 0]
      (let [r (linker/step (drive state) 8)
            c (some (fn [c] (when (= id (:yin.link/id c)) c))
                    (:completions r))]
        (cond
          c c
          (< n 1000) (recur (:state r) (inc n))
          :else ::stalled)))))


(defn- oldest
  [handle]
  (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest)))


(defn- elements
  "Every value on medium `handle`, oldest first."
  [handle]
  (loop [cursor (oldest handle), acc []]
    (let [r (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- malformed
  "A hashable image each format's validator refuses."
  [label]
  (case label
    :H [[:load-bound 5 0] [:halt]]
    :R (lt/invalid-register-image)
    :SEM [[:jump 9]]
    :AST (lt/row-tree [:literal 1 :extra])))


;; =============================================================================
;; The refusal matrix through step (S11.5)
;; =============================================================================

(deftest the-refusal-matrix-holds-through-step
  (doseq [[label format mint] lt/formats]
    (testing label
      (let [store (mem/create-content-mem)
            kw (:format format)
            image (mint lt/worked-example)
            {:keys [identity index]} (lt/publish store format image)
            indexes {kw index}
            request (request-for format identity)
            reason (fn [rt req] (:reason (link rt req)))]
        (testing "step 6: the verified image with its obligations"
          (let [c (link (runtime store indexes) request)]
            (is (= :ok (:status c)))
            (is (= [:t0 1] (:yin.link/id c)))
            (is (= image (:value (:image c))))
            (is (= identity (:identity (:image c))))
            (is (contains? (set (map :name (:obligations c))) '+))))
        (testing "step 0"
          (is (= {:status :refused, :reason :invalid-request,
                  :missing :contract, :yin.link/id [:t0 1]}
                 (link (runtime store indexes)
                       (dissoc request :yin.link/contract))))
          (is (= {:status :refused, :reason :unsupported-format,
                  :format kw, :yin.link/id [:t0 1]}
                 (link (runtime store indexes {:formats {}}) request)))
          (is (= {:status :refused, :reason :contract-mismatch,
                  :expected "other", :actual (:contract format),
                  :yin.link/id [:t0 1]}
                 (link (runtime store indexes)
                       (assoc request :yin.link/contract "other")))))
        (testing "steps 1 and 2: :absent"
          (is (= {:status :refused, :reason :absent, :identity identity,
                  :yin.link/id [:t0 1]}
                 (link (runtime store {}) request)))
          (is (= :absent
                 (reason (runtime store
                                  {kw {identity (jing/segment-key [:none])}})
                         request))))
        (testing "step 2: :address-mismatch and :parts-limit"
          (is (= :address-mismatch
                 (reason (runtime (lt/corrupt-store store) indexes) request)))
          (is (= {:reason :parts-limit, :bound :max-bytes}
                 (select-keys (link (runtime store indexes
                                             {:bounds {:max-bytes 1}})
                                    request)
                              [:reason :bound]))))
        (testing "step 3: :hash-mismatch"
          (let [other (lt/publish store format (mint lt/other-program))]
            (is (= :hash-mismatch
                   (reason (runtime store {kw {identity (:address other)}})
                           request)))))
        (testing "step 4: :descriptor-defect"
          (let [bad (lt/raw-publish store format (malformed label))]
            (is (= :descriptor-defect
                   (reason (runtime store {kw (:index bad)})
                           (request-for format (:identity bad)))))))
        (testing "step 5a: :use-before-definition"
          (let [ubd (lt/publish store format (mint lt/use-then-def))]
            (is (= :use-before-definition
                   (reason (runtime store {kw (:index ubd)})
                           (request-for format (:identity ubd)))))))
        (testing "step 5b is the receiver's, over the completion"
          (let [c (link (runtime store indexes) request)]
            (is (nil? (linker/discharge lt/receiver (:obligations c))))
            (is (= :unresolved-free
                   (:reason (linker/discharge {} (:obligations c)))))
            (is (= :shadowed-free
                   (:reason (linker/discharge
                              (assoc lt/receiver :free-env {'+ 0})
                              (:obligations c)))))))
        (jing/close! store)))))


;; =============================================================================
;; :pending: one part per step (section 6.3)
;; =============================================================================

(deftest a-link-stays-pending-while-the-server-answers-one-part-per-step
  (let [store (mem/create-content-mem)
        tree (vm/ast->semantic-bytecode lt/worked-example)
        root (content/materialize-tree! store tree)
        n (count (:rows tree))
        requests (lt/ring-handle 256)
        responses (lt/ring-handle 256)
        handlers (remote/default-handlers store)
        state (linker/link-state
                {:rpc (rpc/client-state requests responses
                                        (oldest responses)),
                 :formats all-formats,
                 :indexes {:yin.ast/code {root root}}})
        [state id] (linker/request-link state
                                        (request-for linker/ast-format root))]
    (is (< 1 n) "the tree has several parts")
    (loop [state state
           server (rpc/server-state (oldest requests))
           k 1]
      (let [r (linker/step state 8)
            mine (filter (fn [c] (= id (:yin.link/id c))) (:completions r))]
        (if (<= k n)
          (do (is (empty? mine) "the link is pending: no completion yet")
              (is (= k (count (elements requests)))
                  "exactly one further part is requested per step")
              (recur (:state r)
                     (:dao.stream.apply/state
                       (rpc/serve-once! handlers requests responses server))
                     (inc k)))
          (let [c (first mine)]
            (is (= :ok (:status c)) "the last answer completes the link")
            (is (= tree (:value (:image c))))
            (is (= n (count (:parts (:image c)))))))))
    (jing/close! store)))


;; =============================================================================
;; Admission: plain data only (section 6.3)
;; =============================================================================

(deftest a-request-carrying-a-function-or-a-handle-is-invalid-request
  (let [store (mem/create-content-mem)
        {:keys [identity index]}
        (lt/publish store linker/stack-format
                    (lt/stack-image lt/worked-example))
        rt (runtime store {:yin.debruijn.code index})
        base (request-for linker/stack-format identity)]
    (doseq [[label request]
            [[:function-identity
              (assoc base :yin.link/identity (fn [] identity))]
             [:handle-identity (assoc base :yin.link/identity store)]
             [:function-contract
              (assoc base :yin.link/contract (constantly "b2"))]
             [:index-key (assoc base :yin.link/index index)]
             [:receiver-key (assoc base :yin.link/receiver lt/receiver)]]]
      (testing label
        (let [c (link rt request)]
          (is (= :invalid-request (:reason c)))
          (is (= [:t0 1] (:yin.link/id c))
              "refused under the request's well-formed id")
          (is (not-any? fn? (tree-seq coll? seq c))
              "the refusal carries no function")
          (is (not-any? (fn [x] (and (map? x) (contains? x :get-bytes-fn)))
                        (tree-seq coll? seq c))
              "the refusal carries no handle"))))
    (testing "malformed shapes"
      (doseq [[label request]
              [[:both (assoc base :yin.link/name 'my.lib)]
               [:neither (dissoc base :yin.link/identity)]
               [:no-format (dissoc base :yin.link/format)]]]
        (testing label
          (is (= {:status :refused, :reason :invalid-request,
                  :yin.link/id [:t0 1]}
                 (select-keys (link rt request)
                              [:status :reason :yin.link/id])))))
      (doseq [[label request]
              [[:not-a-map [:yin.link/id [:t0 1]]]
               [:bad-id (assoc base :yin.link/id [:t0])]
               [:function-id (assoc base :yin.link/id [:t0 (fn [] 1)])]]]
        (testing label
          (let [[state id] (linker/request-link (:state rt) request)
                [c] (:completions (linker/step state 8))]
            (is (nil? id))
            (is (= {:status :refused, :reason :invalid-request,
                    :yin.link/id nil}
                   (select-keys c [:status :reason :yin.link/id]))
                "a refusal with no well-formed id completes under nil")))))
    (is (empty? (elements (:requests rt)))
        "no refused request ever reaches the content pair")
    (testing "an id whose completion is still owed"
      (let [[state a] (linker/request-link (:state rt) base)
            [state b] (linker/request-link state base)
            cs (:completions (linker/step state 8))]
        (is (= [:t0 1] a))
        (is (nil? b))
        (is (= [{:status :refused, :reason :invalid-request,
                 :defect :duplicate-id, :yin.link/id nil}]
               cs)
            "the duplicate never touches the owed link")
        (is (= 1 (count (elements (:requests rt))))
            "only the admitted link reads")))
    (jing/close! store)))


(deftest a-request-by-name-has-no-name-environment-yet
  (let [store (mem/create-content-mem)
        rt (runtime store {})]
    (is (= {:status :refused, :reason :absent, :name 'my.lib,
            :yin.link/id [:t0 1]}
           (link rt (-> (request-for linker/stack-format nil)
                        (dissoc :yin.link/identity)
                        (assoc :yin.link/name 'my.lib))))
        "section 8's name environment is M4's; with none observed the
         name is :absent now")
    (jing/close! store)))


(deftest the-format-record-is-admitted-before-the-contract
  (let [store (mem/create-content-mem)
        rt (runtime store {})
        request (request-for linker/stack-format "H")
        unknown (assoc request :yin.link/format :no.such/format)]
    (is (= {:status :refused, :reason :unsupported-format,
            :format :no.such/format, :yin.link/id [:t0 1]}
           (link rt (dissoc unknown :yin.link/contract)))
        "a contract-less request for an unknown format names the format")
    (is (= :unsupported-format
           (:reason (link rt (assoc unknown :yin.link/contract "other")))))
    (is (= {:status :refused, :reason :invalid-request, :missing :contract,
            :yin.link/id [:t0 1]}
           (link rt (dissoc request :yin.link/contract)))
        "a known format still requires the contract")
    (is (= {:status :refused, :reason :invalid-request,
            :defect :exactly-one-of, :yin.link/id [:t0 1]}
           (link rt (dissoc unknown :yin.link/identity)))
        "the shape checks still precede the format record")
    (jing/close! store)))


;; =============================================================================
;; Section 6.4: fetch is host policy over the same traffic
;; =============================================================================

(deftest a-local-fetch-is-exactly-the-stepped-traffic-over-a-handle-free-state
  (doseq [[label format mint] lt/formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (lt/publish store format
                                                 (mint lt/worked-example))
            opts {:formats all-formats, :indexes {(:format format) index}}
            fetch-counts (atom {})
            step-counts (atom {})
            by-fetch (lt/local-runtime
                       (remote/default-handlers
                         (lt/counting-store store fetch-counts))
                       opts 1024)
            by-step (lt/local-runtime
                      (remote/default-handlers
                        (lt/counting-store store step-counts))
                      opts 1024)
            res (linker/fetch by-fetch (:format format) identity lt/receiver
                              {:contract (:contract format)})
            c (link by-step (request-for format identity))]
        (is (linker/ok? res))
        (is (= :ok (:status c)))
        (is (= (:value res) (:value (:image c))))
        (is (seq (elements (:requests by-fetch))))
        (is (= (elements (:requests by-step)) (elements (:requests by-fetch)))
            "the same content requests, in the same order")
        (is (= (elements (:responses by-step))
               (elements (:responses by-fetch)))
            "the same content responses")
        (is (= @step-counts @fetch-counts))
        (is (every? #{1} (vals @fetch-counts))
            "each part is read once, by the server")
        (is (not-any? (fn [x] (and (map? x) (contains? x :get-bytes-fn)))
                      (tree-seq coll? seq (:state by-fetch)))
            "the linker state holds no content handle")
        (jing/close! store)))))


;; =============================================================================
;; The DHT behind the served boundary (section 6.1)
;; =============================================================================

(deftest the-dht-handle-behind-the-served-boundary-answers-a-link
  (doseq [[label format mint] lt/formats]
    (testing label
      (let [image (mint lt/worked-example)
            identity (lt/identity-for format image)
            kw (:format format)
            index (if (lt/storage-derived? format)
                    {identity identity}
                    {identity (jing/segment-key image)})
            payload (lt/stored-payload format image)
            honest-value (if (= :yin.ast/code kw)
                           (into {}
                                 (map (fn [[id row]] [id (subvec row 1)]))
                                 (:rows image))
                           {lt/any-address payload})
            forged (lt/grid-handle {2 {lt/any-address (lt/tamper payload)}})
            honest (lt/grid-handle {2 {lt/any-address (lt/tamper payload)},
                                    3 honest-value})
            request (request-for format identity)]
        (is (= :absent (:reason (link (runtime forged {kw index}) request)))
            "the DHT filters the forged payload behind the server")
        (let [c (link (runtime honest {kw index}) request)]
          (is (= :ok (:status c)) "a later honest peer answers the link")
          (is (= image (:value (:image c)))))
        (jing/close! forged)
        (jing/close! honest)))))


;; =============================================================================
;; abandon, and correlation on one pair (section 6.3)
;; =============================================================================

(deftest abandon-completes-a-link-lost-exactly-once
  (let [store (mem/create-content-mem)
        {:keys [identity index]}
        (lt/publish store linker/stack-format
                    (lt/stack-image lt/worked-example))
        rt (runtime store {:yin.debruijn.code index})
        [state id] (linker/request-link
                     (:state rt) (request-for linker/stack-format identity))
        r1 (linker/step state 8)
        abandoned (linker/abandon (:state r1) id :gave-up)
        r2 (linker/step ((:drive rt) abandoned) 8)
        r3 (linker/step ((:drive rt) (:state r2)) 8)]
    (is (empty? (:completions r1)) "the link was in flight")
    (is (= 1 (count (elements (:requests rt)))))
    (is (= [{:yin.link/id id, :status :lost, :reason :gave-up}]
           (:completions r2)))
    (is (empty? (:completions r3)) "the late answer completes nothing")
    (is (= (:state r3) (linker/abandon (:state r3) id :again))
        "an id with no link in flight leaves the state unchanged")
    (jing/close! store)))


(deftest two-links-on-one-pair-each-complete-under-their-own-id
  (let [store (mem/create-content-mem)
        h (lt/publish store linker/stack-format
                      (lt/stack-image lt/worked-example))
        tree (vm/ast->semantic-bytecode lt/closed-program)
        root (content/materialize-tree! store tree)
        rt (runtime store {:yin.debruijn.code (:index h),
                           :yin.ast/code {root root}})
        [state a] (linker/request-link
                    (:state rt)
                    (request-for linker/stack-format (:identity h)
                                 {:yin.link/id [:t0 7]}))
        [state b] (linker/request-link
                    state
                    (request-for linker/ast-format root
                                 {:yin.link/id [:t1 7]}))
        done (loop [state state, acc {}, n 0]
               (if (or (= 2 (count acc)) (= n 100))
                 acc
                 (let [r (linker/step ((:drive rt) state) 8)]
                   (recur (:state r)
                          (into acc (map (fn [c] [(:yin.link/id c) c]))
                                (:completions r))
                          (inc n)))))]
    (is (= #{a b} (set (keys done))))
    (is (= (lt/stack-image lt/worked-example)
           (:value (:image (get done a)))))
    (is (= tree (:value (:image (get done b)))))
    (jing/close! store)))


;; =============================================================================
;; Code identity: the addressed stream runs under its stamp or is refused
;; =============================================================================

(deftest an-addressed-instruction-stream-runs-under-its-stamp-or-is-refused
  (let [store (mem/create-content-mem)
        v (lt/semantic-vector lt/worked-example)
        address (content/materialize-vector! store v)
        indexes {:yin.semantic/code {address address}}
        c (link (runtime store indexes)
                (request-for linker/semantic-format address))]
    (is (= :ok (:status c)))
    (is (= 11 (vm/value
                (vm/run (semantic/load-vector
                          (semantic/create-vm) (:value (:image c))
                          (:contract linker/semantic-format)))))
        "the linked stream runs under the record's contract")
    (doseq [stamp ["v2" "b2" "r2"]]
      (let [rt (runtime store indexes)]
        (is (= :contract-mismatch
               (:reason (link rt (request-for linker/semantic-format address
                                              {:yin.link/contract stamp})))))
        (is (empty? (elements (:requests rt)))
            "refused before any content request, so nothing can load")))
    (jing/close! store)))


;; =============================================================================
;; Step 2's byte cap over the reply text (section 4.2)
;; =============================================================================

(defn- replying
  "A local runtime whose server answers every read with the found
   envelope carrying `text`, whatever it is."
  [text opts]
  (lt/local-runtime {:jing/get-content (fn [_] {:found? true, :value text})}
                    (merge {:formats all-formats} opts)
                    1024))


(defn- decoded-length
  "The byte length strict padded Base64 `text` decodes to."
  [text]
  (- (* 3 (quot (count text) 4))
     (count (filter (fn [c] (= "=" (str c))) text))))


(deftest an-oversize-base64-reply-is-refused-before-it-is-decoded
  (let [body [:literal 1]
        address (jing/segment-key body)
        text (jing/bytes->base64 (jing/canonical-bytes body))
        limit (decoded-length text)
        indexes {:yin.ast/code {address address}}
        request (request-for linker/ast-format address)
        ;; not Base64 at all: decoding it would fail closed as :absent,
        ;; so a :parts-limit refusal proves it was never decoded
        undecodable (apply str (repeat 400 "!"))]
    (is (= {:status :refused, :reason :parts-limit, :bound :max-bytes,
            :address address, :yin.link/id [:t0 1]}
           (link (replying undecodable {:indexes indexes,
                                        :bounds {:max-bytes 64}})
                 request))
        "text whose every decoding exceeds the budget is refused undecoded")
    (is (= {:status :refused, :reason :absent, :address address,
            :yin.link/id [:t0 1]}
           (link (replying undecodable {:indexes indexes}) request))
        "within the budget the same text is decoded and fails closed")
    (is (= :ok (:status (link (replying text {:indexes indexes,
                                              :bounds {:max-bytes limit}})
                              request)))
        "a reply exactly at the limit decodes and checks as before")
    (is (= {:reason :parts-limit, :bound :max-bytes, :address address}
           (select-keys (link (replying text
                                        {:indexes indexes,
                                         :bounds {:max-bytes (dec limit)}})
                              request)
                        [:reason :bound :address]))
        "one byte under the limit is refused with the same evidence")))


;; =============================================================================
;; The fail-closed paths of issue-request (section 6.3)
;; =============================================================================

(deftest a-terminal-client-fails-every-owed-read-closed
  (let [store (mem/create-content-mem)
        {:keys [identity index]}
        (lt/publish store linker/stack-format
                    (lt/stack-image lt/worked-example))
        address (index identity)
        fresh (fn []
                (let [requests (lt/ring-handle 16)
                      responses (lt/ring-handle 16)]
                  {:requests requests,
                   :responses responses,
                   :state (linker/link-state
                            {:rpc (rpc/client-state requests responses
                                                    (oldest responses)),
                             :formats all-formats,
                             :indexes {:yin.debruijn.code index}})}))]
    (testing "a read not yet issued"
      (let [{:keys [requests responses state]} (fresh)
            [state id] (linker/request-link
                         state (request-for linker/stack-format identity))
            _ (stream/append! responses :dao.stream.apply/ended)
            r (linker/step state 8)]
        (is (some? (:terminal (:rpc (:state r))))
            "the medium ended the attachment")
        (is (= [{:status :refused, :reason :absent, :address address,
                 :yin.link/id id}]
               (:completions r)))
        (is (empty? (elements requests)) "a terminal client sends nothing")))
    (testing "a read already on the wire"
      (let [{:keys [requests responses state]} (fresh)
            [state id] (linker/request-link
                         state (request-for linker/stack-format identity))
            r1 (linker/step state 8)
            _ (stream/append! responses :dao.stream.apply/ended)
            r2 (linker/step (:state r1) 8)]
        (is (empty? (:completions r1)))
        (is (= 1 (count (elements requests))))
        (is (= [{:status :refused, :reason :absent, :address address,
                 :yin.link/id id}]
               (:completions r2))
            "the medium's loss of the outstanding read is :absent")))
    (jing/close! store)))


(deftest a-full-writer-retries-the-one-envelope-it-owes
  (let [store (mem/create-content-mem)
        {:keys [identity index]}
        (lt/publish store linker/stack-format
                    (lt/stack-image lt/worked-example))
        requests (lt/ring-handle 16)
        responses (lt/ring-handle 16)
        fulls (atom 2)
        writer (reify
                 stream/IDaoStreamWriter

                 (append!
                   [_ v]
                   (if (pos? @fulls)
                     (do (swap! fulls dec)
                         {:dao.stream/outcome :dao.stream/full})
                     (stream/append! requests v))))
        handlers (remote/default-handlers store)
        state (linker/link-state
                {:rpc (rpc/client-state writer responses (oldest responses)),
                 :formats all-formats,
                 :indexes {:yin.debruijn.code index}})
        [state a] (linker/request-link
                    state (request-for linker/stack-format identity))
        [state b] (linker/request-link
                    state (request-for linker/stack-format identity
                                       {:yin.link/id [:t1 1]}))
        r1 (linker/step state 8)
        seen1 (elements requests)
        r2 (linker/step (:state r1) 8)
        seen2 (elements requests)
        r3 (linker/step (:state r2) 8)
        _ (lt/serve-all handlers requests responses
                        (rpc/server-state (oldest requests)))
        r4 (linker/step (:state r3) 8)]
    (is (rpc/unsent? (:rpc (:state r1)))
        "the writer answered full: the envelope is retained unsent")
    (is (empty? (:completions r1)))
    (is (empty? seen1) "and nothing reached the medium")
    (is (rpc/unsent? (:rpc (:state r2))) "full again: still retained")
    (is (empty? (:completions r2)))
    (is (empty? seen2)
        "no second link is issued while one envelope is owed")
    (is (not (rpc/unsent? (:rpc (:state r3)))))
    (is (= [0 1] (mapv :dao.stream.apply/id (elements requests)))
        "the retried envelope keeps its id, and the second link follows")
    (is (= #{a b} (set (map :yin.link/id (:completions r4)))))
    (is (every? (fn [c] (= :ok (:status c))) (:completions r4)))
    (jing/close! store)))
