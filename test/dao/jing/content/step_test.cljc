(ns dao.jing.content.step-test
  "Tests for dao.jing.content.step, the stepped (non-blocking) content
   client over dao.jing.content's request and response vocabulary.

   The state-machine tests run on every host over two ring buffers and
   a hand-turned server step: dao.jing.content/serve-step over a
   dao.jing handle, or a scripted answering server where the test is
   about a hostile answer."

  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.content :as content]
            [dao.jing.content.step :as step]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            #?@(:cljd [["dart:typed_data" :as typed]])))


(defn- ring-handle
  "A fresh ring-buffer stream handle for the in-process media below."
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   :dao.stream.ringbuffer/capacity capacity})))


(defn- ring-cursor
  [handle]
  (:dao.stream/cursor (stream/cursor handle :dao.stream/oldest)))


(defn- elements
  "Every value on medium `handle`, oldest first."
  [handle]
  (loop [cursor (ring-cursor handle), acc []]
    (let [r (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- make-server
  "A hand-turned server over one request/answer ring pair: each `serve!`
   call answers every request carried and not yet served from the
   dao.jing handle.  The cursor atom is the server's read position."
  [handle]
  (let [request-handle (ring-handle 16)
        answer-handle (ring-handle 16)
        cursor-atom (atom (ring-cursor request-handle))]
    {:request request-handle
     :response answer-handle
     :serve! (fn []
               (reset! cursor-atom
                       (content/serve-step handle request-handle @cursor-atom
                                           answer-handle 32)))}))


(defn- scripted-server
  "A hand-turned server answering each request through `answer-fn`,
   which receives the request map and returns the answer value to
   append; nil appends nothing (the server's silence)."
  [answer-fn]
  (let [request-handle (ring-handle 16)
        answer-handle (ring-handle 16)
        cursor-atom (atom (ring-cursor request-handle))]
    {:request request-handle
     :response answer-handle
     :serve! (fn []
               (loop []
                 (let [read (stream/next request-handle @cursor-atom)]
                   (when (= :dao.stream/ok (:dao.stream/outcome read))
                     (reset! cursor-atom (:dao.stream/cursor read))
                     (when-some [answer (answer-fn (:dao.stream/value read))]
                       (stream/append! answer-handle answer))
                     (recur)))))}))


(defn- stepped-over
  "Stepped-client state over a hand-turned server's two ring buffers."
  [server]
  (step/client-state (:request server) (:response server)
                     (ring-cursor (:response server))))


(defn- store-and-server
  "A hand-turned server over a fresh memory store."
  []
  (let [store (mem/create-content-mem)]
    {:store store
     :server (make-server store)}))


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
   `answer` (::absent reports absence) -- the hostile server the verify
   hop exists for."
  [answer]
  {:put-bytes-fn (fn [_address _bytes] :present)
   :get-bytes-fn (fn [_address not-found]
                   (if (= ::absent answer)
                     not-found
                     (jing/canonical-bytes answer)))})


(defn- first-error
  "The decoded error map of the first completion of `stepped`."
  [stepped]
  (-> stepped :completions first :error))


(deftest request-get-round-trips-and-decodes-totally
  ;; The ok path: the answer arrives as the found?/bytes half.
  (let [{:keys [store server]} (store-and-server)
        payload {:hello "world"}
        address (jing/segment-key payload)]
    (try
      (jing/materialize! store payload)
      (let [r (step/request-get (stepped-over server) address)]
        (is (= :requested (:outcome r)))
        (is (string? (:id r)) "the id is the minted request id")
        ((:serve! server))
        (let [stepped (step/step (:state r) 4)]
          (is (= [{:id (:id r), :found? true, :value payload}]
                 (:completions stepped)))
          (is (= [] (:diagnostics stepped)))
          (is (= {} (get-in stepped [:state :materializations]))
              "a plain get registers no record")
          (is (= {} (get-in stepped [:state :routes])))))
      (finally (jing/close! store))))
  ;; An absent address answers the found? false half.
  (let [store (mem/create-content-mem)
        server (make-server store)
        address (jing/segment-key {:absent true})
        r (step/request-get (stepped-over server) address)]
    ((:serve! server))
    (is (= [{:id (:id r), :found? false, :value nil}]
           (:completions (step/step (:state r) 4)))))
  ;; An answer that does not conform to the get vocabulary is a
  ;; correlated malformed-response error, never a crash and never
  ;; silence.
  (let [server (scripted-server
                 (fn [request]
                   {:jing/request (:jing/request request)
                    :jing/found? "yes"
                    :jing/bytes nil}))
        address (jing/segment-key {:x 1})
        r (step/request-get (stepped-over server) address)]
    ((:serve! server))
    (is (= {:code step/malformed-response-code
            :message (str "the answer does not conform to the "
                          "request's vocabulary")}
           (first-error (step/step (:state r) 4)))))


  ;; A non-segment address throws before the wire.
  (let [server (scripted-server (fn [_] nil))]
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (step/request-get (stepped-over server) :not/an-address)))
    ((:serve! server))
    (is (empty? (elements (:request server)))
        "nothing was submitted")))


(deftest request-put-publishes-without-verify
  ;; A plain put publishes its verdict with the address and never issues
  ;; a read-back: the verify hop is materialization's obligation.
  (let [{:keys [store server]} (store-and-server)
        payload {:a 1}
        address (jing/segment-key payload)]
    (try
      (let [r (step/request-put (stepped-over server) address payload)]
        (is (= :requested (:outcome r)))
        ((:serve! server))
        (let [stepped (step/step (:state r) 4)]
          (is (= [{:id (:id r), :address address, :result :inserted}]
                 (:completions stepped))
              "no payload appears in the published entry")
          (is (= {} (get-in stepped [:state :materializations])))
          (is (= {} (get-in stepped [:state :routes])))
          ;; The duplicate put answers :present, still with no verify,
          ;; on the same threaded state.
          (let [dup (step/request-put (:state stepped) address payload)]
            ((:serve! server))
            (is (= [{:id (:id dup), :address address, :result :present}]
                   (:completions (step/step (:state dup) 4)))))))
      ;; Exactly two requests ever crossed the writer: a third serve
      ;; finds nothing outstanding, so no verify read was issued for
      ;; either put.
      ((:serve! server))
      (is (= 2 (count (elements (:request server)))))
      (finally (jing/close! store))))
  ;; A non-segment address throws before the wire.
  (let [server (make-server {:put-bytes-fn (fn [_ _] :inserted)
                             :get-bytes-fn (fn [_ m] m)})]
    (is (thrown? #?(:clj Exception
                    :cljd Object
                    :cljs js/Error)
          (step/request-put (stepped-over server) :not/an-address {:x 1})))
    ((:serve! server))
    (is (empty? (elements (:request server))))))


(deftest a-mismatched-put-is-the-servers-silence
  ;; Address-against-payload validation is the server's at its own
  ;; door: the ingress check refuses the bytes and no answer ever
  ;; comes back, so the request stays outstanding.
  (let [{:keys [store server]} (store-and-server)
        payload {:lying "put"}
        address (jing/segment-key {:other "payload"})]
    (try
      (let [r (step/request-put (stepped-over server) address payload)]
        (is (= :requested (:outcome r)))
        ((:serve! server))
        (let [stepped (step/step (:state r) 4)]
          (is (= [] (:completions stepped))
              "the refused put published nothing")
          (is (contains? (get-in stepped [:state :outstanding]) (:id r))
              "the request is still outstanding, awaiting an answer that
               never comes"))
        (is (= 1 (count (elements (:request server)))))
        (is (= 0 (count (elements (:response server))))
            "no answer crossed back"))
      (finally (jing/close! store)))))


(deftest materialize-inserted-completes-with-the-address
  (let [{:keys [store server]} (store-and-server)
        payload {:tree {:nodes [1 2 3]}}
        address (jing/segment-key payload)]
    (try
      (let [r (step/request-materialize (stepped-over server) payload)]
        (is (= :requested (:outcome r)))
        (is (string? (:id r)) "the returned id is the put id")
        ((:serve! server))
        (let [stepped (step/step (:state r) 4)]
          (is (= [{:id (:id r), :materialized? true, :address address,
                   :result :inserted}]
                 (:completions stepped))
              ":inserted completes at once with the derived address")
          (is (= {} (get-in stepped [:state :materializations]))
              "the record's removal is the exactly-once guard")
          (is (= {} (get-in stepped [:state :routes])))
          ;; The content is really there, through the ordinary remote
          ;; read on the same threaded state.
          (let [g (step/request-get (:state stepped) address)]
            ((:serve! server))
            (is (= [{:id (:id g), :found? true, :value payload}]
                   (:completions (step/step (:state g) 4)))))))
      (finally (jing/close! store)))))


(deftest materialize-present-verifies-before-completing
  ;; :present publishes nothing until a read-back hashes to the
  ;; address. The fixed order costs three steps: one routes the put,
  ;; one issues the verify (order 4 runs after order 5), one routes its
  ;; answer.
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
          (is (= {(:id r) {:phase :verify-unissued
                           :address address
                           :put-id (:id r)
                           :seq 0}}
                 (get-in first-step [:state :materializations]))
              "the record holds the address and ids, never the payload")
          (is (= {} (get-in first-step [:state :routes]))
              "the put id's route died with its answer")
          (let [second-step (step/step (:state first-step) 4)]
            (is (= [] (:completions second-step))
                "the verify read was issued but not yet answered")
            (is (= :verify-issued
                   (get-in second-step
                           [:state :materializations (:id r) :phase])))
            (is (= 1 (count (get-in second-step [:state :routes])))
                "the get id routes to the record's put id")
            ((:serve! server))
            (let [third-step (step/step (:state second-step) 4)]
              (is (= [{:id (:id r), :materialized? true, :address address,
                       :result :present}]
                     (:completions third-step))
                  "an equal read-back completes :present, carrying the
                   put id")
              (is (= {} (get-in third-step [:state :materializations])))
              (is (= {} (get-in third-step [:state :routes])))
              ;; And no published entry anywhere carried the payload.
              (is (every? #(not (contains? % :jing/bytes))
                          (mapcat :completions
                                  [first-step second-step third-step])))))))
      (finally (jing/close! store)))))


(deftest a-stored-nil-round-trips-as-a-found-nil
  ;; The ingress sentinel must distinguish a refused reply from a
  ;; stored nil, which decodes to the value nil.
  (let [{:keys [store server]} (store-and-server)
        address (jing/segment-key nil)]
    (try
      (jing/materialize! store nil)
      (let [r (step/request-get (stepped-over server) address)]
        ((:serve! server))
        (is (= [{:id (:id r), :found? true, :value nil}]
               (:completions (step/step (:state r) 4)))))
      (finally (jing/close! store)))))


(deftest a-hash-valid-noncanonical-payload-is-an-integrity-failure
  ;; The hostile pair: an address minted over bytes that decode as one
  ;; payload but are not its canonical encoding, served by a server
  ;; holding exactly those bytes. The digest matches the minted
  ;; address, so only the ingress canonicality check can refuse the
  ;; reply.
  (let [bs (host-bytes [0x18 0x01])
        algo jing/default-hash-algorithm
        reg (get jing/registry algo)
        address (keyword "segment"
                         (str (:address-id reg)
                              "-" (jing/digest-bytes algo bs)))
        server (make-server
                 {:put-bytes-fn (fn [_address _bytes] :present)
                  :get-bytes-fn (fn [_address _not-found] bs)})
        r (step/request-get (stepped-over server) address)]
    (is (= :requested (:outcome r)))
    ((:serve! server))
    (let [stepped (step/step (:state r) 4)]
      (is (= [{:id (:id r)
               :error {:code step/integrity-failure-code
                       :message (str "the remote content does not "
                                     "hash to its content address")}}]
             (:completions stepped))))))


(deftest a-verify-mismatch-is-an-integrity-failure
  ;; The hostile server: :present, then different content at the
  ;; address.
  (let [server (make-server (lying-store {:tampered "content"}))
        payload {:real "payload"}
        address (jing/segment-key payload)
        r (step/request-materialize (stepped-over server) payload)]
    ((:serve! server))
    (let [stepped-1 (step/step (:state r) 4)]
      (is (= [] (:completions stepped-1)))
      (let [stepped-2 (step/step (:state stepped-1) 4)]
        ((:serve! server))
        (let [stepped-3 (step/step (:state stepped-2) 4)]
          (is (= [{:id (:id r)
                   :materialized? true
                   :address address
                   :error {:code step/integrity-failure-code
                           :message (str "the remote content does not "
                                         "hash to its content address")}}]
                 (:completions stepped-3)))
          (is (= {} (get-in stepped-3 [:state :materializations]))))))))


(deftest a-present-but-absent-verify-is-present-but-absent
  (let [server (make-server (lying-store ::absent))
        payload {:ghost "payload"}
        address (jing/segment-key payload)
        r (step/request-materialize (stepped-over server) payload)]
    ((:serve! server))
    (let [stepped-1 (step/step (:state r) 4)
          stepped-2 (step/step (:state stepped-1) 4)]
      ((:serve! server))
      (is (= [{:id (:id r)
               :materialized? true
               :address address
               :error {:code step/present-but-absent-code
                       :message (str "the remote reported :present "
                                     "but the content address is "
                                     "absent")}}]
             (:completions (step/step (:state stepped-2) 4)))))))


(deftest a-nonconforming-put-verdict-routes-to-the-record
  ;; A put answering anything but :inserted or :present is a
  ;; malformed-response error, and the materialization completes with
  ;; it carrying the put id.
  (let [server (scripted-server
                 (fn [request]
                   (if (contains? request :jing/put)
                     {:jing/request (:jing/request request)
                      :jing/result :exploded}
                     {:jing/request (:jing/request request)
                      :jing/found? false
                      :jing/bytes nil})))
        payload {:will "fail"}
        address (jing/segment-key payload)
        r (step/request-materialize (stepped-over server) payload)]
    ((:serve! server))
    (let [stepped (step/step (:state r) 4)]
      (is (= [{:id (:id r)
               :materialized? true
               :address address
               :error {:code step/malformed-response-code
                       :message (str "the answer does not conform to "
                                     "the request's vocabulary")}}]
             (:completions stepped)))
      (is (= {} (get-in stepped [:state :materializations]))))))


(deftest busy-clears-only-through-step
  ;; A writer answering full once leaves the request unsent; further
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
        s0 (step/client-state once-full response-handle
                              (ring-cursor response-handle))
        address (jing/segment-key {:x 1})
        first (step/request-get s0 address)]
    (is (= :pending-request (:outcome first)))
    (is (some? (get (:state first) :unsent)))
    (let [second (step/request-put (:state first) address {:x 1})]
      (is (= :busy (:outcome second)))
      (is (= (:state first) (:state second))
          "busy changes nothing"))
    (let [retried (step/step (:state first) 4)]
      (is (= :dao.stream/ok (:attempt retried))
          "the retry's append outcome is folded under :attempt")
      (is (= [] (:completions retried)))
      ;; The request is delivered; a new request is accepted again.
      (let [third (step/request-get (:state retried) address)]
        (is (= :requested (:outcome third)))
        (stream/append! response-handle
                        {:jing/request (:id first)
                         :jing/found? true
                         :jing/bytes (b64 {:x 1})})
        (stream/append! response-handle
                        {:jing/request (:id third)
                         :jing/found? false
                         :jing/bytes nil})
        (is (= [{:id (:id first), :found? true, :value {:x 1}}
                {:id (:id third), :found? false, :value nil}]
               (:completions (step/step (:state third) 4))))))))


(deftest unsent-at-detach-loses-through-order-3
  ;; The answers reader ends with a request unsent: the client loses it
  ;; with the terminal reason and the loss surfaces on the ordinary
  ;; completion path, routed by the record.
  (let [response-handle (ring-handle 16)
        full-writer (reify stream/IDaoStreamWriter
                      (append! [_ _] {:dao.stream/outcome :dao.stream/full}))
        s0 (step/client-state full-writer response-handle
                              (ring-cursor response-handle))
        payload {:never "sent"}
        r (step/request-materialize s0 payload)]
    (is (= :pending-request (:outcome r)))
    (stream/close! response-handle)
    (let [stepped (step/step (:state r) 4)]
      (is (= [{:id (:id r)
               :materialized? true
               :address (jing/segment-key payload)
               :lost :dao.stream/end}]
             (:completions stepped))
          "the abandoned request's loss is routed and published")
      (is (= :dao.stream/end
             (get-in stepped [:state :terminal])))
      (is (= {} (get-in stepped [:state :materializations])))
      (is (= {} (get-in stepped [:state :routes])))
      ;; A further request answers :terminal, submitting nothing.
      (is (= :terminal
             (:outcome (step/request-get (:state stepped)
                                         (jing/segment-key {:x 1}))))))))


(deftest a-non-terminal-gap-loses-the-outstanding-put-and-service-continues
  (let [{:keys [store server]} (store-and-server)
        payload {:gapped true}
        address (jing/segment-key payload)
        r (step/request-materialize (stepped-over server) payload)]
    (try
      (is (= :requested (:outcome r)))
      ;; A reader that answers one gap -- resyncing to the same
      ;; position -- then delegates back to the ring.
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
            gapped (step/step (assoc (:state r) :answers gapping) 4)]
        (is (= [{:id (:id r)
                 :materialized? true
                 :address address
                 :lost :dao.stream/gap}]
               (:completions gapped))
            "the gap loses the outstanding put with its reason")
        (is (nil? (get-in gapped [:state :terminal]))
            "a gap is not terminal")
        (is (= {} (get-in gapped [:state :materializations])))
        ;; The client keeps serving: a new read on the same state works.
        (let [back (assoc (:state gapped) :answers (:response server))
              g (step/request-get back address)]
          (is (= :requested (:outcome g)))
          (stream/append! (:response server)
                          {:jing/request (:id g)
                           :jing/found? true
                           :jing/bytes (b64 payload)})
          (is (= [{:id (:id g), :found? true, :value payload}]
                 (:completions (step/step (:state g) 4))))))
      (finally (jing/close! store)))))


(deftest abandon-retires-the-unsent-request-at-the-next-drain
  (let [request-handle (ring-handle 16)
        response-handle (ring-handle 16)
        once-full (let [full? (atom true)]
                    (reify stream/IDaoStreamWriter
                      (append!
                        [_ value]
                        (if (compare-and-set! full? true false)
                          {:dao.stream/outcome :dao.stream/full}
                          (stream/append! request-handle value)))))
        s0 (step/client-state once-full response-handle
                              (ring-cursor response-handle))
        payload {:abandoned 1}
        address (jing/segment-key payload)
        pending (step/request-materialize s0 payload)]
    (is (= :pending-request (:outcome pending)))
    (let [abandoned (step/abandon (:state pending) :operator/disconnect)]
      (is (nil? (get-in abandoned [:unsent]))
          "the request is retired eagerly; what waits for the drain is
           its completion")
      (is (= 1 (count (get-in abandoned [:completed]))))
      (let [stepped (step/step abandoned 4)]
        (is (= [{:id (:id pending)
                 :materialized? true
                 :address address
                 :lost :operator/disconnect}]
               (:completions stepped))
            "the reason passes through by declaration")
        (is (= {} (get-in stepped [:state :materializations])))
        ;; And the writer, unblocked by the test's once-full, still
        ;; works.
        (let [next (step/request-get (:state stepped) address)]
          (is (= :requested (:outcome next)))))))
  ;; abandon touches nothing when no request is retained: a
  ;; verify-phase record holds nothing this binding owes.
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
              "no unsent request means abandon is the identity here")
          ((:serve! server))
          (is (= [{:id (:id r), :materialized? true, :address address,
                   :result :present}]
                 (:completions (step/step abandoned 4)))
              "the outstanding verify read was untouched")))
      (finally (jing/close! store)))))


(deftest diagnostics-are-taken-once-per-step
  (let [server (scripted-server (fn [_] nil))
        address (jing/segment-key {:x 1})
        r (step/request-get (stepped-over server) address)]
    ;; A foreign-id answer is consumed as an unsolicited diagnostic; the
    ;; awaited read's answer follows it in the same medium.
    (stream/append! (:response server)
                    {:jing/request "foreign-id", :jing/found? false,
                     :jing/bytes nil})
    (stream/append! (:response server)
                    {:jing/request (:id r), :jing/found? true,
                     :jing/bytes (b64 {:x 1})})
    (let [stepped (step/step (:state r) 4)]
      (is (= [{:code :dao.jing.content/unsolicited-answer}]
             (mapv #(dissoc % :value) (:diagnostics stepped))))
      (is (= [{:id (:id r), :found? true, :value {:x 1}}]
             (:completions stepped)))
      (let [again (step/step (:state stepped) 4)]
        (is (= [] (:diagnostics again)) "taken exactly once")
        (is (= [] (:completions again)))))))


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
          ;; full, so order 4 issues the first and stops.
          (let [always-full (reify stream/IDaoStreamWriter
                              (append!
                                [_ _]
                                {:dao.stream/outcome :dao.stream/full}))
                blocked (step/step (assoc (:state stepped-1)
                                          :requests always-full)
                                   4)]
            (is (= {(:id r-a) {:phase :verify-issued
                               :address address-a
                               :put-id (:id r-a)}
                    (:id r-b) {:phase :verify-unissued
                               :address address-b
                               :put-id (:id r-b)}}
                   (into {}
                         (map (fn [[k v]]
                                [k (select-keys v [:phase :address :put-id])]))
                         (get-in blocked [:state :materializations])))
                "put-id order: only the first verify was issued")
            (is (= 1 (count (get-in blocked [:state :routes]))))
            ;; Unblock: the next step's order 1 delivers the retained
            ;; request, the step after issues the second verify.
            (let [delivered (step/step (assoc (:state blocked)
                                              :requests (:request server))
                                       4)]
              (is (= :dao.stream/ok (:attempt delivered)))
              (let [stepped-3 (step/step (:state delivered) 4)]
                (is (= :verify-issued
                       (get-in stepped-3
                               [:state :materializations (:id r-b) :phase])))
                ((:serve! server))
                (let [final (step/step (:state stepped-3) 32)]
                  (is (= #{(:id r-a) (:id r-b)}
                         (set (map :id (:completions final)))))
                  (is (every? #(= :present (:result %))
                              (:completions final)))
                  (is (= {} (get-in final [:state :materializations])))
                  (is (= {} (get-in final [:state :routes])))))))))
      (finally (jing/close! store)))))


(deftest records-and-published-entries-never-carry-payloads
  ;; The discipline, held by construction: whatever the sequence of
  ;; requests, refusals and losses, the retained state and the
  ;; published entries carry addresses and ids only, and every outbox
  ;; drains.
  (let [response-handle (ring-handle 16)
        closed-writer (reify stream/IDaoStreamWriter
                        (append!
                          [_ _]
                          {:dao.stream/outcome :dao.stream/closed}))
        payload {:secret "payload-bytes"}
        address (jing/segment-key payload)
        s0 (step/client-state closed-writer response-handle
                              (ring-cursor response-handle))
        r (step/request-materialize s0 payload)
        stepped (step/step (:state r) 4)]
    (is (= :request-undeliverable (:outcome r)))
    (is (= [{:id (:id r), :materialized? true, :address address
             :lost :dao.stream/closed}]
           (:completions stepped))
        "an undeliverable materialization publishes its loss")
    (is (= {} (get-in stepped [:state :materializations])))
    (is (= {} (get-in stepped [:state :routes])))
    (is (= {} (get-in stepped [:state :outstanding])))
    (is (= [] (get-in stepped [:state :completed])))
    (is (= [] (get-in stepped [:state :diagnostics])))))


(deftest an-unacknowledged-put-completes-with-its-reason
  ;; docs/design/dao.jing.dht.md section 3: {:jing/request r
  ;; :jing/unacknowledged reason} completes as {:id id :error {:code
  ;; :dao.jing.content/unacknowledged :reason reason}}, for a plain put
  ;; and for a materialization's put alike.
  (let [server (scripted-server
                 (fn [request]
                   {:jing/request (:jing/request request)
                    :jing/unacknowledged :dao.jing.dht/too-few-peers}))
        payload {:not "acknowledged"}
        address (jing/segment-key payload)
        {s :state, put-id :id} (step/request-put (stepped-over server)
                                                 address payload)
        {s :state, mat-id :id} (step/request-materialize s payload)
        _ ((:serve! server))
        {:keys [completions]} (step/step s 16)]
    (is (= [{:id put-id
             :error {:code :dao.jing.content/unacknowledged
                     :reason :dao.jing.dht/too-few-peers}}
            {:id mat-id, :materialized? true, :address address
             :error {:code :dao.jing.content/unacknowledged
                     :reason :dao.jing.dht/too-few-peers}}]
           completions)))
  (testing "an unacknowledged answer with an extra key is malformed"
    (let [server (scripted-server
                   (fn [request]
                     {:jing/request (:jing/request request)
                      :jing/unacknowledged :dao.jing.dht/solo
                      :extra true}))
          payload {:not "acknowledged"}
          {s :state} (step/request-put (stepped-over server)
                                       (jing/segment-key payload) payload)
          _ ((:serve! server))]
      (is (= :dao.jing.content/malformed-response
             (:code (first-error (step/step s 16))))))))
