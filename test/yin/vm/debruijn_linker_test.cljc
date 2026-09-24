(ns yin.vm.debruijn-linker-test
  "B6 (docs/design/yin.vm.debruijn.linker.md S11): completion tests for
   `yin.vm.debruijn-linker`. Every refusal and success is exercised for
   both the stack format (H) and the register format (R) through the one
   `fetch` function. Jing addresses are always computed, never pinned;
   only H and R are pinned as goldens (S8). The corpus holds only
   print-stable scalars (symbols, longs), so the same test passes on the
   JVM, Node, and Dart."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.dht :as dht]
            [dao.jing.mem :as mem]
            [dao.jing.remote :as remote]
            [dao.stream :as stream]
            [dao.stream.apply :as apply]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [yin.vm :as vm]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-linker :as linker]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- lit
  [v]
  {:type :literal, :value v})


(defn- v
  [s]
  {:type :variable, :name s})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- tail
  [node]
  (assoc node :tail? true))


(def ^:private worked-example
  "`((fn [x] (+ x 1)) 10)`: one free name, `+`."
  (app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10)))


(def ^:private closed-program
  "`((fn [x] x) 42)`: no free names at all."
  (app (lam '[x] (tail (v 'x))) (lit 42)))


(def ^:private other-program
  "`(* 6 7)`: a different image, for swapped index entries and pairings."
  (app (v '*) (lit 6) (lit 7)))


(def ^:private unknown-free
  "`(nope 1)`: a free name no receiver resolves."
  (app (v 'nope) (lit 1)))


(defn- ast-datoms
  [ast]
  (second (vm/ast->datoms-with-root ast)))


(defn- stack-image
  [ast]
  (:image (dl/adapt (ast-datoms ast))))


(defn- register-image
  [ast]
  (:image (rc/adapt (ast-datoms ast))))


(def ^:private formats
  "Each format record beside the lowering that mints its images."
  [[:H linker/stack-format stack-image]
   [:R linker/register-format register-image]])


(def ^:private receiver
  "The standard receiver: the full primitive registry, nothing shadowing."
  {:primitives vm/primitives})


(defn- publish
  "Store `image` in `handle`; return `{:identity id :index idx}` where
   `idx` is the index read back from the published datom."
  [handle format image]
  (let [datom (linker/publish! handle format image)]
    {:identity (first datom),
     :index (linker/index-from-datoms format [datom])}))


(defn- tamper
  [value]
  [:tampered value])


(defn- corrupt-store
  "A local store whose reads return corrupted bytes for every address."
  [store]
  (assoc store
         :get-content-fn
         (fn [address not-found]
           (let [x ((:get-content-fn store) address not-found)]
             (if (identical? x not-found) x (tamper x))))))


;; =============================================================================
;; Goldens: H and R only, never a Jing address (S8)
;; =============================================================================

(deftest pinned-identities-are-host-independent
  (testing "the receiver computes the same H and R on every host"
    (is (= (str "784ec567b17d91a9bde4f23bd180d09b"
                "ebf69e8da0e57503659c95f1ef2a7b50")
           (dcode/image-hash (stack-image worked-example))))
    (is (= (str "c85f9adbb70bc0297abcb2b4b0362d74"
                "0b57ed3010a29cb5a98d509900cca3b7")
           (rcode/register-hash (register-image worked-example))))))


;; =============================================================================
;; Free-name scanners and format records
;; =============================================================================

(deftest free-name-scanners-read-every-load-free
  (is (= '[+] (linker/stack-free-names (stack-image worked-example))))
  (is (= '[+] (linker/register-free-names (register-image worked-example))))
  (is (= [] (linker/stack-free-names (stack-image closed-program))))
  (is (= [] (linker/register-free-names (register-image closed-program))))
  (is (= '[nope] (linker/stack-free-names (stack-image unknown-free)))))


(deftest format-records-name-their-contract
  (is (= :yin.debruijn.code (:format linker/stack-format)))
  (is (= :yin.debruijn.register (:format linker/register-format)))
  (is (= :yin.debruijn.code/address
         (linker/address-attribute linker/stack-format)))
  (is (= :yin.debruijn.register/address
         (linker/address-attribute linker/register-format))))


;; =============================================================================
;; Step 6: the verified image, one fetch for both formats (S11.14)
;; =============================================================================

(deftest fetch-returns-the-verified-image
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            image (mint worked-example)
            {:keys [identity index]} (publish store format image)
            res (linker/fetch store index format identity receiver)]
        (is (linker/ok? res))
        (is (not (linker/refused? res)))
        (is (= (:format format) (:format res)))
        (is (= identity (:identity res)))
        (is (= image (:value res)))
        (is (jing/segment-matches? (:address res) image))
        (is (= res (linker/fetch store index format identity receiver))
            "fetch is a pure read: no cache, no registry changes the answer")
        (jing/close! store)))))


(deftest index-may-be-a-map-or-a-function
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint closed-program))]
        (is (linker/ok? (linker/fetch store index format identity)))
        (is (linker/ok? (linker/fetch store #(get index %) format identity)))
        (jing/close! store)))))


;; =============================================================================
;; Step 1 and 2: :absent (S11.4)
;; =============================================================================

(deftest absent-identity-or-payload-is-refused
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            image (mint worked-example)
            identity ((:hash-fn format) image)
            unstored (jing/segment-key image)]
        (is (= {:status :refused, :reason :absent, :identity identity}
               (linker/fetch store {} format identity receiver))
            "step 1: the index has no entry")
        (is (= {:status :refused, :reason :absent, :address unstored}
               (linker/fetch store {identity unstored} format identity
                             receiver))
            "step 2: the address has no payload in the store")
        (is (= :absent
               (:reason (linker/fetch store {identity :segment/garbage}
                                      format identity receiver)))
            "an index entry that is no Jing address has no payload")
        (jing/close! store)))))


;; =============================================================================
;; Step 2: :address-mismatch (S11.5)
;; =============================================================================

(deftest local-storage-corruption-is-an-address-mismatch
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            image (mint worked-example)
            {:keys [identity index]} (publish store format image)
            res (linker/fetch (corrupt-store store) index format identity
                              receiver)]
        (is (= :address-mismatch (:reason res)))
        (is (= (tamper image) (:value res)))
        (is (= (index identity) (:address res)))
        (jing/close! store)))))


(deftest corrupt-rpc-response-is-an-address-mismatch
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            image (mint worked-example)
            {:keys [identity index]} (publish store format image)
            handlers (remote/default-handlers store)
            client (remote/content-client
                     ::corrupt
                     (fn [_ op args]
                       (let [resp (apply (get handlers op) args)]
                         (update resp :value tamper)))
                     (fn [_] nil))]
        (is (= :address-mismatch
               (:reason (linker/fetch client index format identity
                                      receiver))))
        (jing/close! client)
        (jing/close! store)))))


;; =============================================================================
;; DHT: a peer serving mismatched content is rejected before load (S11.6)
;; =============================================================================

(defrecord ^:private GridNet
  [self peers served]

  dht/IDhtNet

  (self-peer [_] self)


  (known-peers [_ _target n] (vec (take n peers)))


  (find-closer [_ _peer _target] peers)


  (store-content! [_ _peer _address _payload] false)


  (fetch-content
    [_ peer _address]
    (if-let [pair (find served (:id peer))]
      {:found? true, :value (val pair)}
      {:found? false, :value nil}))


  (close-net! [_] nil))


(defn- peer
  [port]
  {:id (dht/node-id "127.0.0.1" port), :host "127.0.0.1", :port port})


(defn- grid-handle
  "A DHT handle over an empty local store whose peers serve `served`
   (peer port -> the value that peer answers every fetch with)."
  [served]
  (let [self (peer 1)
        peers (mapv peer (keys served))]
    (dht/create-content-dht
      {:net (->GridNet self peers
                       (into {} (map (fn [[p x]] [(:id (peer p)) x])) served)),
       :local (mem/create-content-mem)})))


(deftest dht-peer-with-mismatched-content-is-absent
  (doseq [[label format mint] formats]
    (testing label
      (let [image (mint worked-example)
            identity ((:hash-fn format) image)
            index {identity (jing/segment-key image)}
            forged (grid-handle {2 (tamper image)})
            honest (grid-handle {2 (tamper image), 3 image})]
        (is (= :absent
               (:reason (linker/fetch forged index format identity
                                      receiver)))
            "make-get filters the forged payload; no peer has valid data")
        (is (linker/ok? (linker/fetch honest index format identity
                                      receiver))
            "a later honest peer is accepted")
        (is (= image (jing/get (:local honest) (index identity) nil))
            "the verified payload is cached by the DHT, not by the linker")
        (jing/close! forged)
        (jing/close! honest)))))


;; =============================================================================
;; Step 3: :hash-mismatch (S11.7)
;; =============================================================================

(defn- foreign-descriptor-identity
  "The identity `image` would have under a different descriptor or
   contract version: same canonical bytes, another descriptor hash."
  [format image]
  (jing/sha256
    (str "a-different-descriptor-hash"
         (if (= linker/stack-format format)
           (dcode/encode-image image)
           (rcode/encode-register-image image)))))


(deftest wrong-program-at-the-indexed-address-is-a-hash-mismatch
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            wanted ((:hash-fn format) (mint worked-example))
            other (mint other-program)
            {:keys [identity]} (publish store format other)
            swapped {wanted (jing/segment-key other)}]
        (is (= {:status :refused, :reason :hash-mismatch,
                :expected wanted, :actual identity}
               (linker/fetch store swapped format wanted receiver))
            "a stale or swapped index entry")
        (let [foreign (foreign-descriptor-identity format other)]
          (is (= {:status :refused, :reason :hash-mismatch,
                  :expected foreign, :actual identity}
                 (linker/fetch store {foreign (jing/segment-key other)}
                               format foreign receiver))
              "a descriptor or contract version disagreement"))
        (jing/close! store)))))


;; =============================================================================
;; Step 4: :descriptor-defect (S11.8, S11.9)
;; =============================================================================

(defn- invalid-image
  "A hashable image the format's validator rejects."
  [format]
  (if (= linker/stack-format format)
    [[:load-bound 5 0] [:halt]]
    (let [image (register-image worked-example)
          end (:end (first (:bodies image)))]
      (update-in image [:instructions end] (fn [t] [:return (nth t 1)])))))


(deftest structural-defect-is-refused-before-load
  (doseq [[label format _mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            image (invalid-image format)
            {:keys [identity index]} (publish store format image)
            res (linker/fetch store index format identity receiver)]
        (is (= :descriptor-defect (:reason res)))
        (is (= identity (:identity res)))
        (is (= ((:validate-fn format) image) (:defect res)))
        (is (not (contains? res :value)) "the image is never handed over")
        (jing/close! store)))))


(defn- bad-live-image
  "The worked example's register image with one boundary live set
   rewritten to a well-formed but wrong answer."
  []
  (let [image (register-image worked-example)
        pc (first (keep-indexed (fn [pc t]
                                  (when (rcode/boundary-opcodes (nth t 0)) pc))
                                (:instructions image)))
        slot (rcode/live-slot-index
               (nth (nth (:instructions image) pc) 0))
        live (nth (nth (:instructions image) pc) slot)]
    (assoc-in image [:instructions pc slot] (if (seq live) [] [0]))))


(deftest register-live-set-defect-is-refused-before-load
  (let [store (mem/create-content-mem)
        image (bad-live-image)
        {:keys [identity index]} (publish store linker/register-format image)
        res (linker/fetch store index linker/register-format identity
                          receiver)]
    (is (= :descriptor-defect (:reason res)))
    (is (contains? #{:live-exact :live-bounds} (:rule (:defect res))))
    (jing/close! store)))


;; =============================================================================
;; Step 5: free-name closure (S11.10, S11.11)
;; =============================================================================

(deftest unresolved-free-name-is-refused
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint unknown-free))]
        (is (= {:status :refused, :reason :unresolved-free, :name 'nope}
               (linker/fetch store index format identity receiver)))
        (jing/close! store))
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint worked-example))]
        (is (= {:status :refused, :reason :unresolved-free, :name '+}
               (linker/fetch store index format identity))
            "the empty receiver accepts only closed images")
        (jing/close! store)))))


(deftest shadowed-free-name-is-refused
  (doseq [[label format mint] formats]
    (testing label
      (let [store (mem/create-content-mem)
            {:keys [identity index]} (publish store format
                                              (mint worked-example))]
        (doseq [shadowing [(assoc receiver :free-env {'+ 0})
                           (assoc receiver :store {'+ 0})]]
          (is (= {:status :refused, :reason :shadowed-free, :name '+}
                 (linker/fetch store index format identity shadowing))))
        (jing/close! store)))))


(deftest module-names-resolve-through-the-registry
  (let [receiver' {:modules {:modules {'io {'print :host-print}}}}]
    (is (nil? (linker/free-name-defect receiver' '[io/print])))
    (is (= :unresolved-free
           (:reason (linker/free-name-defect receiver' '[io/nope]))))))


;; =============================================================================
;; Same-root pairing (S11.12, S11.13)
;; =============================================================================

(defn- mint-root
  "Publish both images of `ast` and return what a composition holds."
  [store ast]
  (let [h (publish store linker/stack-format (stack-image ast))
        r (publish store linker/register-format (register-image ast))]
    {:H (:identity h), :h-index (:index h), :R (:identity r)}))


(deftest trusted-fallback-names-its-trust
  (let [store (mem/create-content-mem)
        {:keys [H R h-index]} (mint-root store worked-example)
        pairing (linker/pairing-datoms 'root H R)
        res (linker/trusted-fallback store h-index 'root pairing receiver)]
    (is (linker/ok? res))
    (is (= :composition (:trust res)))
    (is (= {:root 'root, :from R} (:fallback res)))
    (is (= H (:identity res)))
    (is (= :yin.debruijn.code (:format res)))
    (is (= {:status :refused, :reason :absent, :root 'elsewhere}
           (linker/trusted-fallback store h-index 'elsewhere pairing
                                    receiver)))
    (jing/close! store)))


(deftest verifying-fallback-re-lowers-the-named-root
  (let [store (mem/create-content-mem)
        {:keys [H R h-index]} (mint-root store worked-example)
        other (mint-root store other-program)
        source (ast-datoms worked-example)]
    (testing "an authentic pairing is accepted"
      (is (= {:status :ok, :root 'root, :H H, :R R}
             (linker/verify-same-root-pairing 'root H R source)))
      (let [res (linker/verifying-fallback
                  store h-index 'root (linker/pairing-datoms 'root H R)
                  source receiver)]
        (is (linker/ok? res))
        (is (= :verified (:trust res)))
        (is (= (stack-image worked-example) (:value res)))))
    (testing "a swapped pairing is refused"
      (is (= {:status :refused, :reason :pairing-mismatch, :root 'root,
              :expected {:H (:H other), :R R}, :actual {:H H, :R R}}
             (linker/verify-same-root-pairing 'root (:H other) R source)))
      (is (= :pairing-mismatch
             (:reason (linker/verifying-fallback
                        store (merge h-index (:h-index other)) 'root
                        (linker/pairing-datoms 'root (:H other) R)
                        source receiver)))))
    (jing/close! store)))


;; =============================================================================
;; Execution parity under the B0 normalizer (S11.2)
;; =============================================================================

(defn- named-value
  [ast]
  (b0/normalize (vm/value (vm/eval (tu/create-vm) ast))))


(defn- lifted-value
  [named-vector]
  (b0/normalize
    (vm/value (vm/run (semantic/load-vector (semantic/create-vm)
                                            named-vector)))))


(deftest fetched-images-execute-like-local-code
  (doseq [ast [worked-example closed-program other-program]]
    (let [store (mem/create-content-mem)
          local (named-value ast)
          h (publish store linker/stack-format (stack-image ast))
          r (publish store linker/register-format (register-image ast))
          fh (linker/fetch store (:index h) linker/stack-format
                           (:identity h) receiver)
          fr (linker/fetch store (:index r) linker/register-format
                           (:identity r) receiver)]
      (is (= local (lifted-value (dl/lift (:value fh))))
          "H: lifted to :yin.code/* and run on the semantic VM")
      (is (= local (lifted-value (rc/lift (:value fr))))
          "R: lifted to :yin.code/* and run on the semantic VM")
      (is (= local
             (b0/normalize
               (vm/value (vm/run (dvm/create-vm (:value fh) receiver)))))
          "H: run directly on the de Bruijn stack kernel")
      (jing/close! store))))


;; =============================================================================
;; Transfer over dao.stream (S11.1, S11.3)
;; =============================================================================
;; The receiver knows only the identity and an index. Its content handle is
;; `dao.jing.remote/content-client`; every request and response crosses a
;; pair of `dao.stream` ring buffers as RPC envelopes, served by
;; `default-handlers` over the publisher's store. This path is portable, so
;; every host runs it; the JVM additionally runs the WebSocket transport.

(defn- ring-handle
  []
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key 64})))


(defn- stream-client
  "A content handle whose calls travel over dao.stream to a publisher
   serving `handlers`."
  [handlers]
  (let [requests (ring-handle)
        responses (ring-handle)
        oldest #(:dao.stream/cursor (stream/cursor % :dao.stream/oldest))
        server-cursor (atom (oldest requests))
        client-state (atom (rpc/client-state requests responses
                                             (oldest responses)))
        serve! (fn []
                 (let [r (stream/next requests @server-cursor)]
                   (reset! server-cursor (:dao.stream/cursor r))
                   (stream/append! responses
                                   (apply/dispatch-request
                                     handlers (:dao.stream/value r)))))
        call (fn [_ op args]
               (let [requested (rpc/request! @client-state op args)
                     id (:dao.stream.rpc/id requested)]
                 (serve!)
                 (let [done (remote/call-step (:dao.stream.rpc/state requested)
                                              id 8)]
                   (reset! client-state (:state done))
                   (remote/completion-value (:completion done)))))]
    (remote/content-client ::stream call (fn [_] nil))))


(deftest images-transfer-over-dao-stream
  (doseq [[label format mint] formats]
    (testing label
      (let [publisher (mem/create-content-mem)
            image (mint worked-example)
            {:keys [identity index]} (publish publisher format image)
            client (stream-client (remote/default-handlers publisher))
            res (linker/fetch client index format identity receiver)]
        (is (linker/ok? res))
        (is (= image (:value res)))
        (is (= (named-value worked-example)
               (lifted-value (if (= linker/stack-format format)
                               (dl/lift (:value res))
                               (rc/lift (:value res))))))
        (is (= :absent
               (:reason (linker/fetch client {identity
                                              (jing/segment-key [:none])}
                                      format identity receiver)))
            "an absent address over the stream is :absent")
        (jing/close! client)
        (jing/close! publisher)))))


(deftest images-transfer-over-the-websocket-transport
  #?(:cljd (is true "the WebSocket constructors are JVM-only")
     :clj
     (let [port (+ 20000 (rand-int 30000))
           publisher (mem/create-content-mem)
           server (remote/serve-content! (remote/default-handlers publisher)
                                         port)]
       (try
         (let [client (remote/connect-content! (str "ws://127.0.0.1:" port))]
           (try
             (doseq [[label format mint] formats]
               (testing label
                 (let [image (mint worked-example)
                       {:keys [identity index]} (publish publisher format
                                                         image)
                       res (linker/fetch client index format identity
                                         receiver)]
                   (is (linker/ok? res))
                   (is (= image (:value res))))))
             (finally (jing/close! client))))
         (finally ((:stop! server)) (jing/close! publisher))))
     :cljs (is true "the WebSocket constructors are JVM-only")))
