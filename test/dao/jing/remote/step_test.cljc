(ns dao.jing.remote.step-test
  "Tests for dao.jing.remote.step, the stepped (non-blocking) remote
   content client.

   The state-machine tests run on every host over two ring buffers and a
   hand-turned server step (`dao.stream.apply/dispatch-request` over
   `dao.jing.remote/default-handlers`, or a scripted lying backend where
   the test is about a hostile answer); the one wire test runs its body on
   the JVM only, against a real `serve-content!` endpoint, exactly as the
   blocking client's `network-*` tests do."
  (:require [clojure.test :refer [deftest is]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.stream.apply :as apply]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.rpc :as rpc]
            [dao.jing.remote :as remote]
            [dao.jing.remote.step :as step]
            ;; :cljd first, as in remote_test: dao.stream.ws.jvm has no Dart
            ;; twin, so a :clj-first spelling would become a Dart import.
            ;; Used only inside the :clj wire-test branch.
            #?@(:cljd [["dart:typed_data" :as typed]]
                :clj [[dao.stream.rpc.ws :as rpc.ws]
                      [dao.stream.ws :as ws]
                      [dao.stream.ws.jvm :as jvm]])))


(defn- ring-handle
  "A fresh ring-buffer stream handle for the in-process media below."
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- ring-cursor
  [handle]
  (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest)))


(defn- make-server
  "A hand-turned server over one request/response ring pair: each `serve!`
   call dispatches `handlers` over every request carried and not yet
   served, appending one response per request.  The cursor atom is the
   server's read position."
  [handlers]
  (let [request-handle (ring-handle 16)
        response-handle (ring-handle 16)
        cursor-atom (atom (ring-cursor request-handle))]
    {:request request-handle
     :response response-handle
     :serve! (fn []
               (loop [served 0]
                 (let [read (stream/next request-handle @cursor-atom)]
                   (if (= :dao.stream/ok (:dao.stream/outcome read))
                     (do (reset! cursor-atom (:dao.stream/cursor read))
                         (stream/append!
                           response-handle
                           (apply/dispatch-request handlers
                                                   (:dao.stream/value read)))
                         (recur (inc served)))
                     served))))}))


(defn- stepped-over
  "Stepped-client state over a hand-turned server's two ring buffers, with
   no decoder: bare apply responses and bare lifecycle values are
   accepted as-is."
  [server]
  (step/client-state
    (rpc/client-state (:request server) (:response server)
                      (ring-cursor (:response server)))))


(defn- store-and-server
  "default-handlers over a fresh memory store, with a hand-turned server."
  []
  (let [store (mem/create-content-mem)]
    {:store store
     :server (make-server (remote/default-handlers store))}))


(defn- b64
  "The wire form of a value: Base64 of its canonical bytes."
  [v]
  (jing/bytes->base64 (jing/canonical-bytes v)))


(defn- host-bytes
  "A host byte object from a seq of ints 0-255."
  [ints]
  #?(:clj (byte-array (mapv unchecked-byte ints))
     :cljs (js/Buffer.from (clj->js (vec ints)))
     :cljd (typed/Uint8List.fromList ints)))


(defn- lying-store
  "A backend handle that answers every put :present and every get with
   `answer` (::absent reports absence) — the hostile server the verify
   hop exists for."
  [answer]
  {:put-bytes-fn (fn [_address _bytes] :present)
   :get-bytes-fn (fn [_address not-found]
                   (if (= ::absent answer)
                     not-found
                     (jing/canonical-bytes answer)))})


(deftest request-get-round-trips-and-decodes-totally
  ;; The ok path: the envelope arrives as :found?/:value.
  (let [{:keys [store server]} (store-and-server)
        payload {:hello "world"}
        address (jing/segment-key payload)]
    (try
      (jing/materialize! store payload)
      (is (zero? ((:serve! server))) "nothing is served before the call")
      (let [r (step/request-get (stepped-over server) address)]
        (is (= :dao.stream.rpc/requested (:outcome r)))
        (is (= 0 (:id r)))
        ((:serve! server))
        (let [stepped (step/step (:state r) 4)]
          (is (= [{:id 0 :op :jing/get-content :found? true :value payload}]
                 (:completions stepped)))
          (is (= [] (:diagnostics stepped)))
          (is (= {} (get-in stepped [:state :materializations]))
              "a plain get registers no record")
          (is (= {} (get-in stepped [:state :routes])))))
      (finally (jing/close! store))))
  ;; An absent address answers the envelope's false half.
  (let [store (mem/create-content-mem)
        server (make-server (remote/default-handlers store))
        address (jing/segment-key {:absent true})]
    (let [r (step/request-get (stepped-over server) address)]
      ((:serve! server))
      (is (= [{:id 0 :op :jing/get-content :found? false :value nil}]
             (:completions (step/step (:state r) 4))))))
  ;; An error response carries apply's portable error map.
  (let [server (make-server {:jing/get-content (fn [_] (throw (ex-info "boom" {})))})
        address (jing/segment-key {:x 1})]
    (let [r (step/request-get (stepped-over server) address)]
      ((:serve! server))
      (is (= [{:id 0
               :op :jing/get-content
               :error {:dao.stream.apply/code :dao.stream.apply/handler-error
                       :dao.stream.apply/message "Handler failed"}}]
             (:completions (step/step (:state r) 4))))))
  ;; An ok value that is not the presence envelope is a correlated
  ;; malformed-response error, never a crash and never silence.
  (let [server (make-server {:jing/get-content (fn [_] :garbage)})
        address (jing/segment-key {:x 1})]
    (let [r (step/request-get (stepped-over server) address)]
      ((:serve! server))
      (is (= [{:id 0
               :op :jing/get-content
               :error {:dao.stream.apply/code step/malformed-response-code
                       :dao.stream.apply/message
                       "the response does not conform to get-content's wire vocabulary"}}]
             (:completions (step/step (:state r) 4))))))
  ;; A non-segment address throws before the wire (C1).
  (let [server (make-server {:jing/get-content (fn [_] nil)})]
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (step/request-get (stepped-over server) :not/an-address)))
    (is (zero? ((:serve! server))) "nothing was submitted")))


(deftest request-put-publishes-without-verify
  ;; A plain put publishes its verdict with the address and never issues a
  ;; read-back: the verify hop is materialize!'s obligation, not put's.
  (let [{:keys [store server]} (store-and-server)
        payload {:a 1}
        address (jing/segment-key payload)]
    (try
      (let [r (step/request-put (stepped-over server) address payload)]
        (is (= :dao.stream.rpc/requested (:outcome r)))
        ((:serve! server))
        (let [stepped (step/step (:state r) 4)]
          (is (= [{:id 0 :op :jing/put-content :address address
                   :result :inserted}]
                 (:completions stepped))
              "no payload appears in the published entry")
          (is (= {} (get-in stepped [:state :materializations])))
          (is (= {} (get-in stepped [:state :routes])))
          ;; The duplicate put answers :present, still with no verify, on
          ;; the same threaded state.
          (let [dup (step/request-put (:state stepped) address payload)]
            ((:serve! server))
            (is (= [{:id 1 :op :jing/put-content :address address
                     :result :present}]
                   (:completions (step/step (:state dup) 4)))))))
      ;; Exactly two requests ever crossed the writer: a third serve finds
      ;; nothing outstanding, so no verify read was issued for either put.
      (is (zero? ((:serve! server))))
      (finally (jing/close! store))))
  ;; A non-segment address throws before the wire (C1).
  (let [server (make-server {:jing/put-content (fn [_ _] :inserted)})]
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (step/request-put (stepped-over server) :not/an-address {:x 1})))
    (is (zero? ((:serve! server))))))


(deftest materialize-inserted-completes-with-the-address
  (let [{:keys [store server]} (store-and-server)
        payload {:tree {:nodes [1 2 3]}}
        address (jing/segment-key payload)]
    (try
      (let [r (step/request-materialize (stepped-over server) payload)]
        (is (= :dao.stream.rpc/requested (:outcome r)))
        (is (= 0 (:id r)) "the returned id is the put id")
        ((:serve! server))
        (let [stepped (step/step (:state r) 4)]
          (is (= [{:id 0 :materialized? true :address address
                   :result :inserted}]
                 (:completions stepped))
              ":inserted completes at once with the derived address")
          (is (= {} (get-in stepped [:state :materializations]))
              "the record's removal is the exactly-once guard")
          (is (= {} (get-in stepped [:state :routes])))
          ;; The content is really there, through the ordinary remote read
          ;; on the same threaded state.
          (let [g (step/request-get (:state stepped) address)]
            ((:serve! server))
            (is (= [{:id 1 :op :jing/get-content :found? true :value payload}]
                   (:completions (step/step (:state g) 4)))))))
      (finally (jing/close! store)))))


(deftest materialize-present-verifies-before-completing
  ;; :present publishes nothing until a read-back hashes to the address.
  ;; The fixed order costs three steps: one routes the put, one issues the
  ;; verify (order 4 runs after order 5), one routes its answer.
  (let [{:keys [store server]} (store-and-server)
        payload {:already "there"}
        address (jing/segment-key payload)]
    (try
      (jing/materialize! store payload)
      (let [r (step/request-materialize (stepped-over server) payload)]
        ((:serve! server))
        (let [first-step (step/step (:state r) 4)]
          (is (= [] (:completions first-step))
              "the :present put published nothing")
          (is (= {0 {:phase :verify-unissued
                     :address address
                     :put-id 0}}
                 (get-in first-step [:state :materializations]))
              "the record holds the address and ids, never the payload")
          (is (= {} (get-in first-step [:state :routes]))
              "the put id's route died with its completion")
          (let [second-step (step/step (:state first-step) 4)]
            (is (= [] (:completions second-step))
                "the verify read was issued but not yet answered")
            (is (= :verify-issued
                   (get-in second-step [:state :materializations 0 :phase])))
            (is (= {1 0} (get-in second-step [:state :routes]))
                "the get id routes to the record's put id")
            ((:serve! server))
            (let [third-step (step/step (:state second-step) 4)]
              (is (= [{:id 0 :materialized? true :address address
                       :result :present}]
                     (:completions third-step))
                  "an equal read-back completes :present, carrying the put id")
              (is (= {} (get-in third-step [:state :materializations])))
              (is (= {} (get-in third-step [:state :routes])))
              ;; And no published entry anywhere carried the payload.
              (is (every? #(not (contains? % :args))
                          (mapcat :completions
                                  [first-step second-step third-step])))))))
      (finally (jing/close! store)))))


(deftest a-hash-valid-noncanonical-payload-is-an-integrity-failure
  ;; The hostile pair: an address minted over bytes that decode as one
  ;; payload but are not its canonical encoding, served by a server
  ;; holding exactly those bytes. The digest matches the minted address,
  ;; so only the ingress canonicality check can refuse the reply.
  (let [bs (host-bytes [0x18 0x01])
        algo jing/default-hash-algorithm
        reg (get jing/registry algo)
        address (keyword "segment"
                         (str (:address-id reg)
                              "-" (jing/digest-bytes algo bs)))
        server (make-server
                 (remote/default-handlers
                   {:put-bytes-fn (fn [_address _bytes] :present)
                    :get-bytes-fn (fn [_address _not-found] bs)}))]
    (let [r (step/request-get (stepped-over server) address)]
      (is (= :dao.stream.rpc/requested (:outcome r)))
      ((:serve! server))
      (let [stepped (step/step (:state r) 4)]
        (is (= [{:id 0 :op :jing/get-content
                 :error {:dao.stream.apply/code step/integrity-failure-code
                         :dao.stream.apply/message
                         (str "the remote content does not hash to "
                              "its content address")}}]
               (:completions stepped)))))))


(deftest a-verify-mismatch-is-an-integrity-failure
  ;; The hostile server: :present, then different content at the address.
  (let [server (make-server (remote/default-handlers
                              (lying-store {:tampered "content"})))
        payload {:real "payload"}
        address (jing/segment-key payload)]
    (let [r (step/request-materialize (stepped-over server) payload)]
      ((:serve! server))
      (let [stepped-1 (step/step (:state r) 4)]
        (is (= [] (:completions stepped-1)))
        (let [stepped-2 (step/step (:state stepped-1) 4)]
          ((:serve! server))
          (let [stepped-3 (step/step (:state stepped-2) 4)]
            (is (= [{:id 0
                     :materialized? true
                     :address address
                     :error {:dao.stream.apply/code step/integrity-failure-code
                             :dao.stream.apply/message
                             "the remote content does not hash to its content address"}}]
                   (:completions stepped-3)))
            (is (= {} (get-in stepped-3 [:state :materializations])))))))))


(deftest a-present-but-absent-verify-is-present-but-absent
  (let [server (make-server (remote/default-handlers (lying-store ::absent)))
        payload {:ghost "payload"}
        address (jing/segment-key payload)]
    (let [r (step/request-materialize (stepped-over server) payload)]
      ((:serve! server))
      (let [stepped-1 (step/step (:state r) 4)]
        (let [stepped-2 (step/step (:state stepped-1) 4)]
          ((:serve! server))
          (is (= [{:id 0
                   :materialized? true
                   :address address
                   :error {:dao.stream.apply/code step/present-but-absent-code
                           :dao.stream.apply/message
                           "the remote reported :present but the content address is absent"}}]
                 (:completions (step/step (:state stepped-2) 4)))))))))


(deftest an-error-response-routes-to-the-record
  ;; A throwing put handler becomes H5's error response, and the
  ;; materialization completes with it carrying the put id.
  (let [server (make-server {:jing/put-content
                             (fn [_ _] (throw (ex-info "put failed" {})))})
        payload {:will "fail"}
        address (jing/segment-key payload)]
    (let [r (step/request-materialize (stepped-over server) payload)]
      ((:serve! server))
      (let [stepped (step/step (:state r) 4)]
        (is (= [{:id 0
                 :materialized? true
                 :address address
                 :error {:dao.stream.apply/code :dao.stream.apply/handler-error
                         :dao.stream.apply/message "Handler failed"}}]
               (:completions stepped)))
        (is (= {} (get-in stepped [:state :materializations])))))))


(deftest a-nonconforming-put-verdict-is-malformed
  ;; B2 over the wire: a put answering anything but :inserted/:present.
  (let [server (make-server {:jing/put-content (fn [_ _] :ok)})
        payload {:b 2}
        address (jing/segment-key payload)]
    (let [r (step/request-materialize (stepped-over server) payload)]
      ((:serve! server))
      (is (= [{:id 0
               :materialized? true
               :address address
               :error {:dao.stream.apply/code step/malformed-response-code
                       :dao.stream.apply/message
                       "the response does not conform to put-content's wire vocabulary"}}]
             (:completions (step/step (:state r) 4)))))))


(deftest busy-clears-only-through-step
  ;; A writer answering full once leaves the envelope unsent; further
  ;; requests answer :busy until step's order 1 delivers it.
  (let [request-handle (ring-handle 16)
        response-handle (ring-handle 16)
        once-full (let [full? (atom true)]
                    (reify stream/IDaoStreamWriter
                      (append!
                        [_ value]
                        (if (compare-and-set! full? true false)
                          {:dao.stream/outcome :dao.stream/full}
                          (stream/append! request-handle value)))))
        s0 (step/client-state
             (rpc/client-state once-full response-handle
                               (ring-cursor response-handle)))
        address (jing/segment-key {:x 1})
        first (step/request-get s0 address)]
    (is (= :dao.stream.rpc/pending-request (:outcome first)))
    (is (true? (rpc/unsent? (get (:state first) :rpc))))
    (let [second (step/request-put (:state first) address {:x 1})]
      (is (= :busy (:outcome second)))
      (is (= (:state first) (:state second))
          "busy changes nothing"))
    (let [retried (step/step (:state first) 4)]
      (is (= :dao.stream.rpc/requested (:attempt retried))
          "the retry's outcome is folded under :attempt")
      (is (= [] (:completions retried)))
      ;; The envelope is delivered; a new request is accepted again.
      (let [third (step/request-get (:state retried) address)]
        (is (= :dao.stream.rpc/requested (:outcome third)))
        (is (= 1 (:id third)))
        (stream/append! response-handle
                        (apply/success-response 0 {:found? true
                                                   :value (b64 {:x 1})}))
        (stream/append! response-handle
                        (apply/success-response 1 {:found? false :value nil}))
        (is (= [{:id 0 :op :jing/get-content :found? true :value {:x 1}}
                {:id 1 :op :jing/get-content :found? false :value nil}]
               (:completions (step/step (:state third) 4))))))))


(deftest unsent-at-detach-loses-through-order-3
  ;; Terminal with an unsent envelope: order 3 abandons it with the
  ;; terminal reason and the loss surfaces on the ordinary completion
  ;; path, routed by the record.
  (let [response-handle (ring-handle 16)
        full-writer (reify stream/IDaoStreamWriter
                      (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
        s0 (step/client-state
             (rpc/client-state full-writer response-handle
                               (ring-cursor response-handle)))
        payload {:never "sent"}
        r (step/request-materialize s0 payload)]
    (is (= :dao.stream.rpc/pending-request (:outcome r)))
    (stream/append! response-handle :dao.stream.apply/detached)
    (let [stepped (step/step (:state r) 4)]
      (is (= [{:id 0
               :materialized? true
               :address (jing/segment-key payload)
               :lost :dao.stream.apply/detached}]
             (:completions stepped))
          "the abandoned envelope's loss is routed and published")
      (is (= :dao.stream.apply/detached
             (get-in stepped [:state :rpc :terminal])))
      (is (= {} (get-in stepped [:state :materializations])))
      (is (= {} (get-in stepped [:state :routes])))
      ;; A further request answers terminal, verbatim.
      (is (= :dao.stream.rpc/terminal
             (:outcome (step/request-get (:state stepped)
                                         (jing/segment-key {:x 1}))))))))


(deftest a-non-terminal-gap-loses-the-outstanding-put-and-service-continues
  (let [{:keys [store server]} (store-and-server)
        payload {:gapped true}
        address (jing/segment-key payload)
        r (step/request-materialize (stepped-over server) payload)]
    (try
      (is (= :dao.stream.rpc/requested (:outcome r)))
      ;; A reader that answers one gap — resyncing to the same position —
      ;; then delegates back to the ring.
      (let [gaps (atom true)
            gapping (reify stream/IDaoStreamReader
                      (cursor
                        [_ anchor]
                        (stream/cursor (:response server) anchor))

                      (next
                        [_ cursor]
                        (if (compare-and-set! gaps true false)
                          {:dao.stream/outcome :dao.stream/gap
                           :dao.stream/cursor cursor}
                          (stream/next (:response server) cursor))))
            gapped (step/step (assoc-in (:state r) [:rpc :reader] gapping) 4)]
        (is (= [{:id 0
                 :materialized? true
                 :address address
                 :lost :dao.stream/gap}]
               (:completions gapped))
            "the gap loses the outstanding put with its reason")
        (is (nil? (get-in gapped [:state :rpc :terminal]))
            "a gap is not terminal")
        (is (= {} (get-in gapped [:state :materializations])))
        ;; The client keeps serving: a new read on the same state works.
        (let [back (assoc-in (:state gapped) [:rpc :reader] (:response server))
              g (step/request-get back address)]
          (is (= :dao.stream.rpc/requested (:outcome g)))
          (stream/append! (:response server)
                          (apply/success-response 1
                                                  {:found? true
                                                   :value (b64 payload)}))
          (is (= [{:id 1 :op :jing/get-content :found? true :value payload}]
                 (:completions (step/step (:state g) 4))))))
      (finally (jing/close! store)))))


(deftest abandon-retires-the-unsent-envelope-at-the-next-drain
  (let [request-handle (ring-handle 16)
        response-handle (ring-handle 16)
        once-full (let [full? (atom true)]
                    (reify stream/IDaoStreamWriter
                      (append!
                        [_ value]
                        (if (compare-and-set! full? true false)
                          {:dao.stream/outcome :dao.stream/full}
                          (stream/append! request-handle value)))))
        s0 (step/client-state
             (rpc/client-state once-full response-handle
                               (ring-cursor response-handle)))
        payload {:abandoned 1}
        address (jing/segment-key payload)
        pending (step/request-materialize s0 payload)]
    (is (= :dao.stream.rpc/pending-request (:outcome pending)))
    (let [abandoned (step/abandon (:state pending) :operator/disconnect)]
      (is (nil? (get-in abandoned [:rpc :unsent]))
          "the envelope is retired eagerly; what waits for the drain is
           its completion")
      (is (= 1 (count (get-in abandoned [:rpc :completed]))))
      (let [stepped (step/step abandoned 4)]
        (is (= [{:id 0
                 :materialized? true
                 :address address
                 :lost :operator/disconnect}]
               (:completions stepped))
            "the reason passes through by declaration")
        (is (= {} (get-in stepped [:state :materializations])))
        ;; And the writer, unblocked by the test's once-full, still works.
        (let [next (step/request-get (:state stepped) address)]
          (is (= :dao.stream.rpc/requested (:outcome next)))))))
  ;; abandon touches nothing when no envelope is retained: a verify-phase
  ;; record holds nothing this binding owes.
  (let [{:keys [store server]} (store-and-server)
        payload {:verify "phase"}
        address (jing/segment-key payload)]
    (try
      (jing/materialize! store payload)
      (let [r (step/request-materialize (stepped-over server) payload)]
        ((:serve! server))
        (let [stepped-1 (step/step (:state r) 4)
              stepped-2 (step/step (:state stepped-1) 4)
              abandoned (step/abandon (:state stepped-2))]
          (is (= (:state stepped-2) abandoned)
              "no unsent envelope means abandon is the identity here")
          ((:serve! server))
          (is (= [{:id 0 :materialized? true :address address
                   :result :present}]
                 (:completions (step/step abandoned 4)))
              "the outstanding verify read was untouched")))
      (finally (jing/close! store)))))


(deftest diagnostics-are-taken-once-per-step
  (let [server (make-server {:jing/get-content (fn [_] nil)})
        address (jing/segment-key {:x 1})
        r (step/request-get (stepped-over server) address)]
    ;; A foreign-id response is consumed as an unsolicited diagnostic; the
    ;; awaited read's response follows it in the same medium.
    (stream/append! (:response server) (apply/success-response 7 :foreign))
    (stream/append! (:response server)
                    (apply/success-response 0 {:found? true
                                               :value (b64 {:x 1})}))
    (let [stepped (step/step (:state r) 4)]
      (is (= [{:dao.stream.rpc/code :dao.stream.rpc/unsolicited-response}]
             (mapv #(dissoc % :dao.stream.rpc/value) (:diagnostics stepped))))
      (is (= [{:id 0 :op :jing/get-content :found? true :value {:x 1}}]
             (:completions stepped)))
      (let [again (step/step (:state stepped) 4)]
        (is (= [] (:diagnostics again)) "taken exactly once")
        (is (= [] (:completions again)))))))


(deftest allocator-exhaustion-at-verify-issue-synthesizes-the-loss
  ;; The verify read cannot allocate an id; the rpc layer turns terminal
  ;; and loses every outstanding request, but this record holds no live
  ;; id — its loss is synthesized by order 4, carrying the put id.
  (let [{:keys [store server]} (store-and-server)
        payload {:exhaust "me"}
        address (jing/segment-key payload)]
    (try
      (jing/materialize! store payload)
      (let [r (step/request-materialize (stepped-over server) payload)]
        ((:serve! server))
        (let [stepped-1 (step/step (:state r) 4)
              exhausted (assoc-in stepped-1 [:state :rpc :next-id]
                                  (inc rpc/max-safe-id))
              stepped-2 (step/step (:state exhausted) 4)]
          (is (= [{:id 0
                   :materialized? true
                   :address address
                   :lost :dao.stream.rpc/allocator-error}]
                 (:completions stepped-2)))
          (is (= [{:dao.stream.rpc/code :dao.stream.rpc/id-exhausted}]
                 (mapv #(dissoc % :dao.stream.rpc/value)
                       (:diagnostics stepped-2)))
              "the allocator's own diagnostic is drained the same step")
          (is (= :dao.stream.rpc/allocator-error
                 (get-in stepped-2 [:state :rpc :terminal])))
          (is (= {} (get-in stepped-2 [:state :materializations])))))
      (finally (jing/close! store)))))


(deftest verify-reads-issue-in-put-id-order-until-full
  (let [{:keys [store server]} (store-and-server)
        payload-a {:order "first"}
        payload-b {:order "second"}
        address-a (jing/segment-key payload-a)
        address-b (jing/segment-key payload-b)]
    (try
      ;; Both payloads already stored, so both puts answer :present.
      (jing/materialize! store payload-a)
      (jing/materialize! store payload-b)
      (let [s0 (stepped-over server)
            r-a (step/request-materialize s0 payload-a)
            r-b (step/request-materialize (:state r-a) payload-b)]
        ((:serve! server))
        (let [stepped-1 (step/step (:state r-b) 4)]
          (is (= [] (:completions stepped-1)))
          ;; Both records verify-unissued; swap in a writer that stays
          ;; full, so order 4 issues the first (put-id 0) and stops.
          (let [always-full (reify stream/IDaoStreamWriter
                              (append!
                                [_ _]
                                {:dao.stream/outcome :dao.stream/full}))
                blocked (step/step (assoc-in (:state stepped-1)
                                             [:rpc :writer] always-full)
                                   4)]
            (is (= {0 {:phase :verify-issued
                       :address address-a
                       :put-id 0
                       :get-id 2}
                    1 {:phase :verify-unissued
                       :address address-b
                       :put-id 1}}
                   (get-in blocked [:state :materializations]))
                "put-id order: only the first verify was issued")
            (is (= {2 0} (get-in blocked [:state :routes])))
            ;; Unblock: the next step's order 1 delivers the retained
            ;; envelope, the step after issues the second verify.
            (let [delivered (step/step (assoc-in (:state blocked)
                                                 [:rpc :writer]
                                                 (:request server))
                                       4)]
              (is (= :dao.stream.rpc/requested (:attempt delivered)))
              (let [stepped-3 (step/step (:state delivered) 4)]
                (is (= :verify-issued
                       (get-in stepped-3 [:state :materializations 1 :phase])))
                ((:serve! server))
                (let [final (step/step (:state stepped-3) 32)]
                  (is (= [{:id 0 :materialized? true :address address-a
                           :result :present}
                          {:id 1 :materialized? true :address address-b
                           :result :present}]
                         (:completions final)))
                  (is (= {} (get-in final [:state :materializations])))
                  (is (= {} (get-in final [:state :routes])))))))))
      (finally (jing/close! store)))))


(deftest records-and-published-entries-never-carry-payloads
  ;; The N11 discipline, held by construction: whatever the sequence of
  ;; requests, refusals and losses, the retained state and the published
  ;; entries carry addresses and ids only, and every outbox drains.
  (let [response-handle (ring-handle 16)
        closed-writer (reify stream/IDaoStreamWriter
                        (append!
                          [_ _]
                          {:dao.stream/outcome :dao.stream/closed}))
        payload {:secret "payload-bytes"}
        address (jing/segment-key payload)
        s0 (step/client-state
             (rpc/client-state closed-writer response-handle
                               (ring-cursor response-handle)))
        r (step/request-materialize s0 payload)
        stepped (step/step (:state r) 4)]
    (is (= :dao.stream.rpc/request-undeliverable (:outcome r)))
    (is (= [{:id 0 :materialized? true :address address
             :lost :dao.stream/closed}]
           (:completions stepped))
        "an undeliverable materialization publishes its loss")
    (is (= {} (get-in stepped [:state :materializations])))
    (is (= {} (get-in stepped [:state :routes])))
    (is (= {} (get-in stepped [:state :rpc :outstanding])))
    (is (= [] (get-in stepped [:state :rpc :completed])))
    (is (= [] (get-in stepped [:state :rpc :diagnostics])))))


;; =============================================================================
;; The wire test — JVM only, as the blocking client's network-* tests are
;; =============================================================================


#?(:cljd nil
   :clj
   (defn- wire-client
     "A stepped client over a real WebSocket attachment, composed the way
      `connect-content!` composes its blocking twin — traffic ring buffer,
      cursor minted at :newest BEFORE attach! — but established by stepping
      `await-established-step`, not by parking a thread."
     [url]
     (let [traffic (:dao.stream/handle
                     (ring/create! {:dao.stream/type ring/transport-type
                                    ring/capacity-key remote/traffic-capacity}))
           cursor (:dao.stream/cursor (stream/cursor traffic :dao.stream/newest))
           attach! (ws/make-attacher
                     {:traffic {:dao.stream/handle traffic
                                :dao.stream/surface #{:writer}}
                      :admission remote/traffic-admission
                      :connect! jvm/connect!})
           result (attach! (remote/content-descriptor url))]
       (when-not (= :dao.stream/ok (:dao.stream/outcome result))
         (throw (ex-info "the endpoint refused the attachment" {:url url})))
       (let [handle (:dao.stream/handle result)]
         {:handle handle
          :establish! (fn [deadline]
                        (loop [state (rpc.ws/init-client result traffic cursor)]
                          (let [r (remote/await-established-step state)]
                            (cond
                              (= :established (:status r)) (:state r)

                              (= :terminal (:status r))
                              (throw (ex-info "terminal before establishment"
                                              {:reason (:reason r)}))

                              (< (System/currentTimeMillis) deadline)
                              (do (Thread/sleep 5) (recur (:state r)))

                              :else
                              (throw (ex-info "establishment timed out"
                                              {:url url}))))))}))))


(deftest stepped-calls-complete-over-a-real-endpoint
  #?(:clj
     (let [port (+ 20000 (rand-int 30000))
           backing (mem/create-content-mem)
           server (remote/serve-content! (remote/default-handlers backing) port)]
       (try
         (let [{:keys [handle establish!]}
               (wire-client (str "ws://127.0.0.1:" port))
               rpc-state (establish! (+ (System/currentTimeMillis) 5000))
               drive (fn drive
                       [state]
                       (loop [state state n 0]
                         (let [r (step/step state 32)]
                           (if (seq (:completions r))
                             r
                             (do (Thread/sleep 5)
                                 (when (< 4000 n)
                                   (throw (ex-info "no completion arrived"
                                                   {:steps n})))
                                 (recur (:state r) (inc n)))))))
               payload {:wire "stepped"}
               address (jing/segment-key payload)
               put (step/request-materialize (step/client-state rpc-state)
                                             payload)
               put-done (drive (:state put))
               g (step/request-get (:state put-done) address)
               g-done (drive (:state g))
               ;; Re-materializing over the wire verifies and answers
               ;; :present.
               m (step/request-materialize (:state g-done) payload)
               m-done (drive (:state m))]
           (is (= :dao.stream.rpc/requested (:outcome put)))
           (is (= [{:id 0 :materialized? true :address address
                    :result :inserted}]
                  (:completions put-done)))
           (is (= :dao.stream.rpc/requested (:outcome g)))
           (is (= [{:id 1 :op :jing/get-content :found? true :value payload}]
                  (:completions g-done)))
           (is (= [{:id 2 :materialized? true :address address
                    :result :present}]
                  (:completions m-done)))
           (is (= {} (get-in m-done [:state :materializations])))
           (stream/close! handle))
         (finally ((:stop! server)) (jing/close! backing))))
     :cljd (is true "network tests are JVM-only")
     :cljs (is true "network tests are JVM-only")))
