(ns yin.vm.ffi.remote-serve.responder-test
  "Slice 3c, remote FFI end to end: a real VM on peer A whose FFI call
   pair is two reflections of streams peer B serves through its export
   binding (`yin.vm.ffi.remote-serve`), answered by B's apply responder
   (`yin.vm.ffi.remote-serve.responder`) -- no local bridge anywhere.

   The VM's own apply request crosses unchanged; B's responder reads it
   whole and answers on call-out; the VM resumes, or raises the
   portable apply error, through `yin.vm.ffi/call-result`. Then the
   failure rows of the design: a request append refused full and
   retried (one append, one handler run); a response append refused
   full (retained, no second handler run); a remote answer refused full
   (the remote append! outcome stays unknown until close/loss says
   append-unknown, while the VM resumes from its own apply response);
   a call-in gap reported as loss that ends the pair, and a pair
   channel gap that ends the link; detach and rebind under the same
   served identities; a reclaim answered not-found; and a retained UCF
   call lifted at B, lowered at A and retried over reflections."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.stream.remote :as remote]
            [dao.stream.remote-pair :as pair]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.ffi.remote-serve :as rs]
            [yin.vm.ffi.remote-serve.caller :as caller]
            [yin.vm.ffi.remote-serve.responder :as resp]
            [yin.vm.linearize :as linearize]
            [yin.vm.semantic :as semantic]
            [yin.vm.ucf.remote :as ucf.remote]))


;; =============================================================================
;; Streams
;; =============================================================================

(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity capacity})))


(defn- oldest
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(defn- values
  [h]
  (loop [c (oldest h)
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- gated
  "`inner`, answering full to every append while `refuse?` holds -- the
   append outcome a ring buffer never gives. Every other operation is
   `inner`'s own, so it serves, reads and closes as `inner` does."
  [inner refuse?]
  (reify
    stream/IDaoStreamDescriptor

    (descriptor [_] (stream/descriptor inner))


    stream/IDaoStreamReader

    (cursor [_ anchor] (stream/cursor inner anchor))

    (next [_ c] (stream/next inner c))


    stream/IDaoStreamWriter

    (append!
      [_ v]
      (if @refuse?
        {:dao.stream/outcome :dao.stream/full}
        (stream/append! inner v)))


    stream/IDaoStreamClosable

    (close! [_] (stream/close! inner))))


(defn- recording
  "A writer view of `inner` noting every value appended through it: the
   value exactly as the VM handed it to its call-in."
  [inner seen]
  (reify
    stream/IDaoStreamDescriptor

    (descriptor [_] (stream/descriptor inner))


    stream/IDaoStreamReader

    (cursor [_ anchor] (stream/cursor inner anchor))

    (next [_ c] (stream/next inner c))


    stream/IDaoStreamWriter

    (append!
      [_ v]
      (swap! seen conj v)
      (stream/append! inner v))))


;; =============================================================================
;; Peer B: the export binding and the responder
;; =============================================================================

(def ^:private chan
  {:dao.stream/type :dao.stream.test/channel
   :dao.stream/identity "responder-toy"})


(def ^:private per-author-medium
  {:retention :evict-oldest
   :capacity 64
   :value-domain :portable-values
   :attribution :per-author-media})


(defn- channel
  "Two ring buffers as the channel; `ba`, B's answers toward A, behind
   a gate."
  []
  (let [ab (ring 1024)
        ba (ring 1024)
        ba-full? (atom false)
        ba' (gated ba ba-full?)]
    {:ab ab, :ba ba, :ba-full? ba-full?,
     :a-end {:reader ba, :writer ab},
     :b-end {:reader ab, :writer ba'}}))


(defn- binding-opts
  [t ticks grants call-in step-budget channel-descriptor]
  {::rs/channel (:b-end t)
   ::rs/channel-exclusive? true
   ::rs/channel-descriptor channel-descriptor
   ;; the call pair: call-in written remotely, everything else read
   ::rs/surface (fn [h] (if (identical? h call-in) #{:writer} #{:reader}))
   ::rs/admit? (constantly true)
   ::rs/capacity 8
   ::rs/step-budget step-budget
   ::rs/lease-duration {:ms 10}
   ::rs/lease-tolerance {:ms 2}
   ::rs/lease-cadence {:ms 1}
   ::rs/lease-ticks [{:handle ticks :cursor (oldest ticks)}]
   ::rs/lease-media [(let [p (ring 64)]
                       {:handle p :cursor (oldest p)
                        :source :lease-proposals
                        :medium per-author-medium})]
   ::rs/lease-writer grants
   ::rs/lease-grants grants
   ::rs/lease-renewal-capacity 16})


(defn- counting
  "The handler map, each run counted in `runs`."
  [runs]
  {:op/add (fn [a b] (swap! runs inc) (+ a b))
   :op/nothing (fn [] (swap! runs inc) nil)
   :op/boom (fn [] (swap! runs inc) (throw (ex-info "host failure" {})))})


(defn- peer-b
  "Peer B over channel `t`: its call pair (call-in behind a gate when
   `call-in-capacity` is given small), the binding and the responder.
   `opts` may name :call-in-capacity, :step-budget and the
   :channel-descriptor B stamps on its served descriptors."
  ([t] (peer-b t {}))
  ([t {:keys [call-in-capacity step-budget channel-descriptor]
       :or {call-in-capacity 16, step-budget 64, channel-descriptor chan}}]
   (let [in-full? (atom false)
         out-full? (atom false)
         call-in (gated (ring call-in-capacity) in-full?)
         call-out (gated (ring 16) out-full?)
         ticks (ring 256)
         grants (ring 256)
         runs (atom 0)
         b (rs/open! (binding-opts t ticks grants call-in step-budget
                                   channel-descriptor))
         r (resp/open! b {::resp/call-in call-in
                          ::resp/call-out call-out
                          ::resp/handlers (counting runs)})]
     (assert (rs/binding? b) (pr-str b))
     (assert (not (contains? r ::resp/status)) (pr-str r))
     {:binding b, :responder (atom r), :results (atom []),
      :call-in call-in, :call-out call-out,
      :in-full? in-full?, :out-full? out-full?,
      :ticks ticks, :grants grants, :runs runs})))


(defn- respond!
  "One responder step, threading its value; returns the step's result."
  [pb]
  (let [result (resp/step @(:responder pb))]
    (reset! (:responder pb) (::resp/responder result))
    (swap! (:results pb) conj (:dao.stream.apply/outcome result))
    result))


(defn- drive!
  "B's one drive owner: a binding pass, a responder step, a binding
   pass."
  [pb]
  (rs/step (:binding pb))
  (respond! pb)
  (rs/step (:binding pb)))


(defn- tick-step!
  [pb ms]
  (stream/append! (:ticks pb) (lease/tick {:ms ms}))
  (rs/step (:binding pb)))


(defn- endpoint
  [pb]
  (::resp/endpoint @(:responder pb)))


;; =============================================================================
;; Peer A: reflections and a real VM over them
;; =============================================================================

(defn- peer-a
  [a-end]
  (let [events (ring 1024)]
    {:events events
     :attach! (remote/attacher {:dao.stream.remote/channels {chan a-end}
                                :dao.stream.remote/events events})}))


(defn- reflect
  [pa descriptor]
  (:dao.stream/handle ((:attach! pa) descriptor)))


(defn- poll!
  "Ask `f` until it answers something other than blocked or retry,
   driving B between asks."
  [pb f]
  (loop [n 0]
    (let [r (f)]
      (if (and (< n 12)
               (or (= :dao.stream/blocked (:dao.stream/outcome r))
                   (:dao.stream/retry? r)))
        (do (drive! pb) (recur (inc n)))
        r))))


(def ^:private secret "yin.vm.ffi.remote-serve.responder-test/secret")


(def ^:private load-ast
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(defn- make-ring-stream
  [capacity]
  (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                       :dao.stream.ringbuffer/capacity capacity}))


(def ^:private caller-seq (atom 0))


(defn- fresh-caller-id
  "A caller token unique to one VM caller tenure in this suite."
  []
  (str "caller-" (swap! caller-seq inc)))


(defn- ready-caller
  "The production readiness step (`yin.vm.ffi.remote-serve.caller`)
   over B's served endpoint, B driven between asks until it settles."
  ([pa pb] (ready-caller pa pb (fresh-caller-id)))
  ([pa pb caller-id]
   (loop [c (caller/open {::caller/endpoint (endpoint pb)
                          ::caller/attach! (:attach! pa)
                          ::caller/caller-id caller-id})]
     (if (= ::caller/pending (::caller/status c))
       (let [c' (caller/step c)]
         (when (= ::caller/pending (::caller/status c')) (drive! pb))
         (recur c'))
       c))))


(defn- remote-vm
  "A real semantic VM on peer A whose FFI call pair is two reflections
   of B's served endpoint, and NO bridge: every call it makes is
   answered at B or not at all. call-in is seen through `seen`, which
   notes each value the VM appends. The pair, its `:newest` call-out
   cursor and the caller token come from the production readiness step."
  ([pa pb seen] (remote-vm pa pb seen (fresh-caller-id)))
  ([pa pb seen caller-id]
   (let [c (ready-caller pa pb caller-id)
         opts (caller/vm-opts c)]
     (assert (caller/ready? c) (pr-str c))
     {:in (:call-in opts), :out (:call-out opts), :caller c,
      :vm (semantic/create-vm (merge {:make-stream make-ring-stream
                                      :capability-secret secret}
                                     (update opts :call-in recording seen)))})))


(defn- lit
  [v]
  {:type :literal, :value v})


(defn- call-node
  [op & args]
  {:type :dao.stream.apply/call, :op op, :operands (mapv lit args)})


(defn- load-call
  [machine ast]
  (load-ast machine (vm/ast->datoms ast)))


(defn- settle
  "Run `machine` until it halts or 12 drive rounds pass."
  [pb machine]
  (loop [v (vm/run machine)
         n 0]
    (if (and (vm/blocked? v) (< n 12))
      (do (drive! pb) (recur (vm/run v) (inc n)))
      v)))


(defn- raised
  "The exception `thunk` raised, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e e)))


(defn- apply-error
  "The portable apply error `call-result` raised, found on `e` or its
   causes."
  [e]
  (loop [e e]
    (when e
      (or (:error (ex-data e))
          (recur (ex-cause e))))))


(defn- messages
  [e]
  (loop [e e
         acc []]
    (if e (recur (ex-cause e) (conj acc (ex-message e))) acc)))


;; =============================================================================
;; 1. Real VM end to end
;; =============================================================================

(deftest a-real-vm-call-is-answered-remotely
  (let [t (channel)
        pb (peer-b t)
        pa (peer-a (:a-end t))
        seen (atom [])
        {machine :vm} (remote-vm pa pb seen)
        parked (vm/run (load-call machine (call-node :op/add 1 2)))
        call-id (:call-id (first (:wait-set parked)))
        done (settle pb parked)
        request (first (values (:call-in pb)))
        response (first (values (:call-out pb)))]
    (testing "the VM parked on its own call, with no bridge"
      (is (vm/blocked? parked))
      (is (nil? (:bridge machine)))
      (is (some? call-id)))
    (testing "the call-in value crossed unchanged"
      (is (= [(apply2/request call-id :op/add [1 2])] @seen)
          "the VM appended its own apply request")
      (is (= @seen (values (:call-in pb)))
          "B's call-in holds exactly that map: whole, not rebuilt")
      (is (= call-id (apply2/request-id request))))
    (testing "B's responder answered with the request's id"
      (is (= (apply2/success-response call-id 3) response))
      (is (= 1 @(:runs pb))))
    (testing "the VM halted with the value through call-result"
      (is (vm/halted? done))
      (is (= 3 (vm/value done)))
      (is (empty? (:parked done)) "the parked call left :parked"))))


(deftest nil-is-a-value-and-errors-raise
  (testing "a nil success is a value"
    (let [t (channel)
          pb (peer-b t)
          pa (peer-a (:a-end t))
          {machine :vm} (remote-vm pa pb (atom []))
          done (settle pb (load-call machine (call-node :op/nothing)))]
      (is (vm/halted? done))
      (is (nil? (vm/value done)))
      (is (= 1 @(:runs pb)))))
  (doseq [[op code] [[:op/boom :dao.stream.apply/handler-error]
                     [:op/missing :dao.stream.apply/unknown-operation]]]
    (testing (str op " answers the portable error, which the VM raises")
      (let [t (channel)
            pb (peer-b t)
            pa (peer-a (:a-end t))
            {machine :vm} (remote-vm pa pb (atom []))
            parked (vm/run (load-call machine (call-node op)))
            call-id (:call-id (first (:wait-set parked)))
            e (raised #(settle pb parked))]
        (is (= (apply2/error-response call-id code
                                      (:dao.stream.apply/message
                                        (apply2/response-error
                                          (first (values (:call-out pb))))))
               (first (values (:call-out pb))))
            "an apply error response carrying the request's id")
        (is (some? e) "raised, not parked")
        (is (= code (:dao.stream.apply/code (apply-error e)))
            "call-result raised the portable apply error")))))


(deftest a-request-carries-its-extra-keys-in-transit
  (let [t (channel)
        pb (peer-b t)
        pa (peer-a (:a-end t))
        in (reflect pa (apply2/endpoint-request (endpoint pb)))
        out (reflect pa (apply2/endpoint-response (endpoint pb)))
        c (:dao.stream/cursor (poll! pb #(stream/cursor out :dao.stream/oldest)))
        request (assoc (apply2/request "ext-1" :op/add [2 3])
                       :producer/nonce "n-1")]
    (stream/descriptor in)
    (is (= :dao.stream/ok (:dao.stream/outcome (stream/append! in request))))
    (let [answer (poll! pb #(stream/next out c))]
      (is (= [request] (values (:call-in pb)))
          "the whole original map at B, the extra key included")
      (is (= (apply2/success-response "ext-1" 5) (:dao.stream/value answer))
          "answered under the identical id, read back over the reflection"))))


;; =============================================================================
;; 2. Pending request append
;; =============================================================================

(deftest a-refused-request-append-is-retained-and-retried
  (let [t (channel)
        ab-full? (atom false)
        a-end (update (:a-end t) :writer gated ab-full?)
        pb (peer-b t)
        pa (peer-a a-end)
        seen (atom [])
        {machine :vm} (remote-vm pa pb seen)
        _ (reset! ab-full? true)
        parked (vm/run (load-call machine (call-node :op/add 20 22)))
        entry (first (:wait-set parked))]
    (testing "the append is refused full: the VM retains the request"
      (is (true? (:request-sent entry)))
      (is (= (apply2/request (:call-id entry) :op/add [20 22]) (:datom entry))
          "the exact request, under its id"))
    (testing "no capacity: nothing reaches B, no handler runs"
      (let [still (settle pb parked)]
        (is (vm/blocked? still))
        (is (empty? (values (:call-in pb))))
        (is (zero? @(:runs pb)))))
    (testing "capacity returns: the retry lands once and is answered once"
      (reset! ab-full? false)
      (let [done (settle pb parked)]
        (is (vm/halted? done))
        (is (= 42 (vm/value done)))
        (is (= [(:datom entry)] (values (:call-in pb)))
            "exactly one accepted append, the retained map itself")
        (is (= 1 @(:runs pb)) "one handler invocation")
        (is (= [(:call-id entry)]
               (map apply2/response-id (values (:call-out pb)))))))))


;; =============================================================================
;; 3. Response full
;; =============================================================================

(deftest a-refused-response-is-retained-without-a-second-run
  (let [t (channel)
        pb (peer-b t)
        pa (peer-a (:a-end t))
        {machine :vm} (remote-vm pa pb (atom []))
        parked (vm/run (load-call machine (call-node :op/add 1 1)))
        call-id (:call-id (first (:wait-set parked)))]
    (rs/step (:binding pb))
    (is (= [call-id] (map apply2/request-id (values (:call-in pb))))
        "the request landed at B")
    (reset! (:out-full? pb) true)
    (let [first-step (respond! pb)]
      (is (= :dao.stream.apply/pending-response
             (:dao.stream.apply/outcome first-step))
          "the handler ran; its response was refused full"))
    (dotimes [_ 3] (respond! pb))
    (testing "retained: response and successor, no second run"
      (let [s (::resp/state @(:responder pb))]
        (is (= (apply2/success-response call-id 2) (:pending-response s)))
        (is (= call-id (:pending-request-id s)))
        (is (some? (:pending-successor s))))
      (is (= 1 @(:runs pb)))
      (is (empty? (values (:call-out pb)))))
    (reset! (:out-full? pb) false)
    (is (= :dao.stream.apply/responded
           (:dao.stream.apply/outcome (respond! pb))))
    (is (= [(apply2/success-response call-id 2)] (values (:call-out pb)))
        "one response append")
    (let [done (settle pb parked)]
      (is (= 2 (vm/value done)))
      (is (= 1 @(:runs pb)) "still one handler invocation"))))


;; =============================================================================
;; 4. A remote answer refused full
;; =============================================================================

(deftest a-refused-remote-answer-leaves-the-append-unknown
  (let [t (channel)
        pb (peer-b t {:step-budget 1})
        pa (peer-a (:a-end t))
        {machine :vm in :in} (remote-vm pa pb (atom []))
        in-id (:dao.stream/identity (apply2/endpoint-request (endpoint pb)))
        append-events (fn []
                        (filter #(and (= in-id (:dao.stream/identity %))
                                      (not (contains? % :dao.stream/descriptor)))
                                (values (:events pa))))
        parked (vm/run (load-call machine (call-node :op/add 4 5)))]
    ;; one read: the VM's append! request, answered into a full writer
    (reset! (:ba-full? t) true)
    (rs/step (:binding pb))
    (reset! (:ba-full? t) false)
    (is (= 1 (count (values (:call-in pb))))
        "the request was applied at B")
    (let [done (settle pb parked)]
      (testing "the VM resumes from its own apply response"
        (is (vm/halted? done))
        (is (= 9 (vm/value done)))
        (is (= 1 @(:runs pb))))
      (testing "the remote append! outcome is unknown, not retried"
        (is (empty? (append-events)) "no source outcome was ever filed")
        (is (= 1 (count (values (:call-in pb)))) "nothing re-sent"))
      (testing "close surfaces it as append-unknown"
        (stream/close! in)
        (is (= [:dao.stream.remote/append-unknown]
               (keep :dao.stream.remote/event (values (:events pa)))))))))


;; =============================================================================
;; 5. Gap
;; =============================================================================

(deftest a-call-in-gap-is-reported-as-loss
  (let [t (channel)
        pb (peer-b t {:call-in-capacity 2})
        pa (peer-a (:a-end t))
        {machine :vm} (remote-vm pa pb (atom []))
        parked (vm/run (load-call machine (call-node :op/add 1 2)))]
    (rs/step (:binding pb))
    (is (= 1 (count (values (:call-in pb)))) "the VM's request landed")
    ;; two other producers' requests evict it before the responder reads
    (stream/append! (:call-in pb) (apply2/request "o-1" :op/add [0 0]))
    (stream/append! (:call-in pb) (apply2/request "o-2" :op/add [0 0]))
    (is (= ::resp/request-lost
           (:dao.stream.apply/outcome (respond! pb)))
        "the responder reports the loss")
    (is (= :dao.stream/closed
           (:dao.stream/outcome (stream/append! (:call-out pb) :late)))
        "and ends the pair: call-out is closed")
    (is (= :dao.stream.apply/terminal
           (:dao.stream.apply/outcome (respond! pb))))
    (let [call-id (:call-id (first (:wait-set parked)))
          e (raised #(settle pb parked))]
      (is (some? e) "the parked VM raises instead of waiting forever")
      (is (= {:call-id call-id,
              :error {:dao.stream.apply/code ::ffi/response-lost,
                      :dao.stream.apply/message
                      "FFI response stream ended before this call was answered",
                      ::ffi/loss :dao.stream/end}}
             (ex-data e))
          "it read the end of call-out: the portable loss, with its id")
      (is (not-any? #{"FFI response envelope is malformed"} (messages e))))
    (is (zero? @(:runs pb)) "no handler ran after the loss")))


(defn- lower-attach!
  [handles]
  (fn [descriptor]
    (if-some [h (get handles (:dao.stream/identity descriptor))]
      {:dao.stream/outcome :dao.stream/ok :dao.stream/handle h}
      {:dao.stream/outcome :dao.stream/not-found})))


(deftest a-pair-channel-gap-ends-the-link
  (let [ab (ring 64)
        ba (ring 8)
        pcd {:dao.stream/type :dao.stream/pair
             :dao.stream/identity "pair-1"
             :dao.stream.remote/in {:dao.stream/type :dao.stream/remote
                                    :dao.stream/identity "ba"
                                    :dao.stream/channel chan}
             :dao.stream.remote/out {:dao.stream/type :dao.stream/remote
                                     :dao.stream/identity "ab"
                                     :dao.stream/channel chan}}
        t {:b-end {:reader ab, :writer ba}}
        pb (peer-b t {:channel-descriptor pcd})
        events (ring 64)
        pa {:events events
            :attach! (pair/attacher
                       {:dao.stream.remote.pair/attach!
                        (lower-attach! {"ba" ba "ab" ab})
                        :dao.stream.remote/events events})}
        {machine :vm out :out} (remote-vm pa pb (atom []))
        parked (vm/run (load-call machine (call-node :op/add 1 2)))]
    (is (vm/blocked? parked))
    ;; B's answers outrun the pair's `in` before A reads them
    (dotimes [_ 12] (stream/append! ba :noise))
    (let [e (raised #(settle pb parked))]
      (is (some? e) "the VM raises: its link ended")
      (is (some #{"Stream read failed"} (messages e))))
    (is (= :dao.stream.remote/channel-gone
           (:dao.stream.remote/reason
             (stream/cursor out :dao.stream/oldest)))
        "the link is gone: its reflection answers channel-gone to any
         op no filed answer covers")))


;; =============================================================================
;; 6. Detach and rebind
;; =============================================================================

(deftest detach-and-rebind-under-the-same-identity
  (let [t (channel)
        pb (peer-b t)
        b (:binding pb)
        ep (endpoint pb)
        ids (mapv :dao.stream/identity [(apply2/endpoint-request ep)
                                        (apply2/endpoint-response ep)])
        pa (peer-a (:a-end t))
        {machine :vm} (remote-vm pa pb (atom []))
        first-done (settle pb (load-call machine (call-node :op/add 1 2)))]
    (is (= 3 (vm/value first-done)))
    (stream/close! (:ab t))
    (testing "the channel ends: detached, entry and lease live"
      (is (true? (::rs/detached? (rs/step b))))
      (is (every? #(rs/served? b %) ids))
      (is (every? #(some? (rs/lease-of b %)) ids)))
    (let [t2 (channel)
          _ (is (= {:dao.stream/outcome :dao.stream/ok}
                   (rs/reattach! b (:b-end t2))))
          pa2 (peer-a (:a-end t2))
          ;; the same VM rebinds its pair: fresh reflections of the SAME
          ;; served identities through the new channel, its call-out
          ;; cursor cell kept past the response it already read
          rebound (merge (:resources first-done)
                         {vm/call-in-stream-key
                          (reflect pa2 (apply2/endpoint-request ep))
                          vm/call-out-stream-key
                          (reflect pa2 (apply2/endpoint-response ep))})
          done (settle pb (-> first-done
                              (assoc :resources rebound)
                              (load-call (call-node :op/add 10 20))))]
      (testing "a subsequent call completes under the same identities"
        (is (= ep (endpoint pb)) "the one endpoint")
        (is (= 30 (vm/value done)))
        (is (= 2 @(:runs pb)))
        (is (every? #(rs/served? b %) ids))))))


;; =============================================================================
;; 7. Terminal not-found
;; =============================================================================

(deftest a-reclaimed-pair-answers-not-found
  (let [t (channel)
        pb (peer-b t)
        b (:binding pb)
        ep (endpoint pb)
        in-id (:dao.stream/identity (apply2/endpoint-request ep))
        out-id (:dao.stream/identity (apply2/endpoint-response ep))
        pa (peer-a (:a-end t))
        {machine :vm} (remote-vm pa pb (atom []))
        lapsed (fn []
                 (filter #(= :dao.lease/lapsed (:dao.lease/status %))
                         (values (:grants pb))))]
    (tick-step! pb 1)
    (is (empty? (lapsed)))
    (tick-step! pb 14)
    (testing "reclaimed with no holder renewing: removed, then recorded"
      (is (= 2 (count (lapsed))) "call-in's lease and call-out's")
      (is (not (rs/served? b in-id)))
      (is (not (rs/served? b out-id)))
      (is (not (contains? (rs/table b) in-id))))
    (testing "the responder invokes nothing for a reclaimed pair"
      (is (= ::resp/retired (:dao.stream.apply/outcome (respond! pb))))
      (is (= :dao.stream.apply/terminal
             (:dao.stream.apply/outcome (respond! pb)))))
    (testing "a fresh call answers not-found and raises"
      (let [e (raised #(settle pb (load-call machine (call-node :op/add 1 2))))]
        (is (some? e))
        (is (some #{"Stream read failed"} (messages e))))
      (is (empty? (values (:call-in pb))) "nothing reached call-in")
      (is (zero? @(:runs pb))))
    (testing "the identity cannot rebind"
      (let [again (reflect pa (apply2/endpoint-request ep))]
        (is (= :dao.stream.remote/not-found
               (:dao.stream.remote/reason
                 (poll! pb #(stream/cursor again :dao.stream/oldest))))))
      (let [re (rs/serve! b (:call-in pb))]
        (is (not= in-id (:dao.stream/identity re))
            "re-serving mints a new identity")))))


;; =============================================================================
;; 8. A retained UCF call
;; =============================================================================

(deftest a-retained-call-lifts-at-b-and-resumes-at-a
  (let [t (channel)
        pb (peer-b t)
        b (:binding pb)
        ;; the emitter: a real VM at B over B's own call pair, no bridge
        emitter (semantic/create-vm {:make-stream make-ring-stream
                                     :capability-secret secret
                                     :call-in (:call-in pb)
                                     :call-out (:call-out pb)
                                     :call-out-cursor (oldest (:call-out pb))})
        ast (call-node :op/add 7 8)
        datoms (vm/ast->datoms ast)
        _ (reset! (:in-full? pb) true)
        parked (vm/run (load-ast emitter datoms))
        entry (-> (first (:wait-set parked))
                  ;; a producer's extra envelope key
                  (assoc-in [:datom :producer/nonce] "n-1"))
        kept (get-in parked [:resources vm/call-out-cursor-key :cursor])
        lifted (rs/lift-frame b (:resources parked) [entry])
        pending (first (:yin.k/pending lifted))
        pa (peer-a (:a-end t))
        registers (select-keys entry [:segment :pc :env :stack :k])
        receiver-base (semantic/create-vm {:make-stream make-ring-stream
                                           :capability-secret secret})
        lowered (ucf.remote/lower-pending (:attach! pa) pending
                                          (:yin.k/cells lifted)
                                          registers
                                          (:resources receiver-base))
        receiver (assoc (load-ast receiver-base datoms)
                        :control nil, :k nil, :value :yin/blocked,
                        :blocked? true, :halted? false,
                        :wait-set [(:entry lowered)],
                        :resources (merge (:resources receiver-base)
                                          (:resources lowered)))]
    (testing "the call-in append refused full: the request is retained"
      (is (true? (:request-sent entry)))
      (is (empty? (values (:call-in pb)))))
    (testing "lifted at B through the binding's serve!"
      (is (= :ffi-request (:yin.k/reason pending)))
      (is (= (:datom entry) (:yin.k/request-envelope pending))
          "the envelope verbatim, extra key included")
      (is (= (:dao.stream/identity (apply2/endpoint-request (endpoint pb)))
             (get-in pending [:yin.k/request :dao.stream/identity]))
          "the request marker is the responder's own call-in identity")
      (is (= kept (get-in lifted [:yin.k/cells (:yin.k/response-cell pending)
                                  :yin.k/position]))
          "the kept response cursor"))
    (testing "lowered at A and retried over reflections"
      (reset! (:in-full? pb) false)
      (let [done (settle pb receiver)]
        (is (vm/halted? done))
        (is (= 15 (vm/value done)))
        (is (= [(:datom entry)] (values (:call-in pb)))
            "the retained map landed once, verbatim")
        (is (= 1 @(:runs pb)))
        (is (= [(apply2/success-response (:call-id entry) 15)]
               (values (:call-out pb))))))))


;; =============================================================================
;; Slice 3d: caller-scoped correlation over one shared pair
;; =============================================================================

(defn- pump!
  "A binding pass alone: B's mirror moves the channel, no responder runs
   -- the test answers by hand, in the order it chooses."
  [pb]
  (rs/step (:binding pb)))


(defn- pumped
  "Run `machine` until it halts or 12 pump rounds pass."
  [pb machine]
  (loop [v (vm/run machine)
         n 0]
    (if (and (vm/blocked? v) (< n 12))
      (do (pump! pb) (recur (vm/run v) (inc n)))
      v)))


(defn- two-callers
  "Two VMs on peer A over ONE exported pair, each through its own
   readiness step and caller token, each parked on one call whose
   request has landed at B."
  [pb pa]
  (let [{vm1 :vm} (remote-vm pa pb (atom []) "caller-1")
        {vm2 :vm} (remote-vm pa pb (atom []) "caller-2")
        p1 (vm/run (load-call vm1 (call-node :op/add 1 2)))
        p2 (vm/run (load-call vm2 (call-node :op/add 10 20)))]
    (dotimes [_ 3] (pump! pb))
    {:p1 p1, :p2 p2,
     :id1 (:call-id (first (:wait-set p1))),
     :id2 (:call-id (first (:wait-set p2)))}))


(deftest two-callers-on-one-pair-each-receive-their-own-result
  (let [t (channel)
        pb (peer-b t)
        pa (peer-a (:a-end t))
        {:keys [p1 p2 id1 id2]} (two-callers pb pa)]
    (testing "both VMs minted the same local park id; the tokens differ"
      (is (= ["caller-1" :parked-0] id1))
      (is (= ["caller-2" :parked-0] id2))
      (is (= [id1 id2] (map apply2/request-id (values (:call-in pb))))
          "requests landed in the order 1, 2"))
    ;; answered in the OPPOSITE order to the requests
    (apply2/put-response! (:call-out pb) (apply2/success-response id2 30))
    (apply2/put-response! (:call-out pb) (apply2/success-response id1 3))
    (let [d1 (pumped pb p1)
          d2 (pumped pb p2)]
      (is (vm/halted? d1))
      (is (= 3 (vm/value d1)) "caller 1 received its own result")
      (is (vm/halted? d2))
      (is (= 30 (vm/value d2)) "caller 2 received its own result")
      (is (= [{:kind :unmatched, :response-id id2,
               :cell vm/call-out-cursor-key}]
             (first (engine/take-ffi-diagnostics d1)))
          "caller 1 skipped caller 2's response, never consumed it as its own"))))


(deftest an-unrelated-response-leaves-the-waiter-parked
  (let [t (channel)
        pb (peer-b t)
        pa (peer-a (:a-end t))
        {:keys [p1 p2 id1 id2]} (two-callers pb pa)
        stray ["caller-9" :parked-0]]
    (apply2/put-response! (:call-out pb) (apply2/success-response stray :x))
    (apply2/put-response! (:call-out pb) (apply2/success-response id2 30))
    (let [still (pumped pb p1)]
      (testing "caller 1 read both, woke on neither"
        (is (vm/blocked? still))
        (is (= [id1] (map :call-id (:wait-set still))))
        (is (= [stray id2]
               (map :response-id (first (engine/take-ffi-diagnostics still))))
            "a response with no live waiter is diagnosed, never delivered"))
      (testing "caller 2 was woken by its own, past the stray"
        (let [d2 (pumped pb p2)]
          (is (= 30 (vm/value d2)))
          (is (= [stray] (map :response-id
                              (first (engine/take-ffi-diagnostics d2)))))))
      (testing "caller 1 is woken later by its own"
        (apply2/put-response! (:call-out pb) (apply2/success-response id1 3))
        (let [d1 (pumped pb still)]
          (is (vm/halted? d1))
          (is (= 3 (vm/value d1)))
          (is (empty? (:parked d1))))))))


(deftest the-readiness-step-yields-then-returns-a-real-cursor
  (let [t (channel)
        pb (peer-b t)
        pa (peer-a (:a-end t))
        opts {::caller/endpoint (endpoint pb)
              ::caller/attach! (:attach! pa)
              ::caller/caller-id "caller-r"}
        ;; history on the shared call-out before this tenure: even one
        ;; under the very id this caller will mint
        _ (apply2/put-response! (:call-out pb)
                                (apply2/success-response ["caller-r" :parked-0]
                                                         :stale))
        opened (caller/open opts)
        first-ask (caller/step opened)]
    (testing "the first ask is retry: the step yields, nothing is ready"
      (is (= ::caller/pending (::caller/status opened)))
      (is (= ::caller/pending (::caller/status first-ask)))
      (is (= 1 (::caller/attempts first-ask)))
      (is (nil? (caller/vm-opts first-ask))))
    (let [ready (loop [c first-ask]
                  (if (= ::caller/pending (::caller/status c))
                    (do (drive! pb) (recur (caller/step c)))
                    c))
          cursor (caller/call-out-cursor ready)]
      (testing "once filed, the step returns the minted cursor"
        (is (caller/ready? ready))
        (is (some? cursor))
        (is (= {:call-in (::caller/call-in ready)
                :call-out (::caller/call-out ready)
                :call-out-cursor cursor
                :ffi-caller-id "caller-r"}
               (caller/vm-opts ready))))
      (testing "a VM built with it completes a remote call, past the history"
        (let [machine (semantic/create-vm (merge {:make-stream make-ring-stream
                                                  :capability-secret secret}
                                                 (caller/vm-opts ready)))
              done (settle pb (load-call machine (call-node :op/add 2 5)))]
          (is (vm/halted? done))
          (is (= 7 (vm/value done)) "never the :stale value before :newest")))
      (testing "a supplied pair without the cursor is refused at construction"
        (is (some? (raised #(semantic/create-vm
                              {:make-stream make-ring-stream
                               :capability-secret secret
                               :call-in (::caller/call-in ready)
                               :call-out (::caller/call-out ready)})))))))
  (testing "the step is bounded, and its options are checked first"
    (let [t (channel)
          pb (peer-b t)
          pa (peer-a (:a-end t))
          opts {::caller/endpoint (endpoint pb)
                ::caller/attach! (:attach! pa)
                ::caller/caller-id "caller-x"}]
      (is (= ::caller/exhausted
             (::caller/status (caller/step (caller/open
                                             (assoc opts
                                                    ::caller/max-attempts 1)))))
          "no drive between asks: one retry exhausts a bound of one")
      (is (= [{::caller/option ::caller/caller-id,
               ::caller/reason ::caller/malformed}]
             (::caller/refusals (caller/open (assoc opts
                                                    ::caller/caller-id "")))))
      (is (= [{::caller/option ::caller/caller-id,
               ::caller/reason ::caller/unportable}]
             (::caller/refusals
               (caller/open (assoc opts
                                   ::caller/codec {:encode pr-str
                                                   :decode (constantly nil)})))))
      (is (= [{::caller/option ::caller/call-in,
               ::caller/reason ::caller/unattached}]
             (::caller/refusals
               (caller/open (assoc opts
                                   ::caller/attach!
                                   (constantly {:dao.stream/outcome
                                                :dao.stream/not-found})))))))))


(defn- tracking
  "`attach!`, noting every reflection it hands out in `acquired`."
  [attach! acquired]
  (fn [descriptor]
    (let [r (attach! descriptor)]
      (when-some [h (:dao.stream/handle r)] (swap! acquired conj h))
      r)))


(defn- closed?
  "A closed reflection answers closed to cursor and append!, and nothing
   crosses the wire."
  [h]
  (and (= :dao.stream/closed
          (:dao.stream/outcome (stream/cursor h :dao.stream/oldest)))
       (= :dao.stream/closed (:dao.stream/outcome (stream/append! h :probe)))))


(defn- settle-caller
  "Step `c`, driving B between asks, until it is no longer pending."
  [pb c]
  (loop [c (caller/step c)]
    (if (= ::caller/pending (::caller/status c))
      (do (drive! pb) (recur (caller/step c)))
      c)))


(deftest a-readiness-attempt-without-a-vm-leaves-no-open-reflection
  (let [t (channel)
        pb (peer-b t)
        pa (peer-a (:a-end t))
        ep (endpoint pb)
        opts (fn [acquired]
               {::caller/endpoint ep
                ::caller/attach! (tracking (:attach! pa) acquired)
                ::caller/caller-id "caller-t"})]
    (testing "call-out does not attach: the attached call-in is closed"
      (let [acquired (atom [])
            out-id (:dao.stream/identity (apply2/endpoint-response ep))
            attach! (tracking (fn [d]
                                (if (= out-id (:dao.stream/identity d))
                                  {:dao.stream/outcome :dao.stream/not-found}
                                  ((:attach! pa) d)))
                              acquired)
            r (caller/open (assoc (opts acquired) ::caller/attach! attach!))]
        (is (= [{::caller/option ::caller/call-out,
                 ::caller/reason ::caller/unattached}]
               (::caller/refusals r)))
        (is (= 1 (count @acquired)))
        (is (every? closed? @acquired))))
    (testing "exhausted: both reflections are closed"
      (let [acquired (atom [])
            c (caller/step (caller/open (assoc (opts acquired)
                                               ::caller/max-attempts 1)))]
        (is (= ::caller/exhausted (::caller/status c)))
        (is (true? (::caller/closed? c)))
        (is (nil? (caller/vm-opts c)))
        (is (= 2 (count @acquired)))
        (is (every? closed? @acquired))))
    (testing "refused: an unserved call-out identity; both closed"
      (let [acquired (atom [])
            bogus (apply2/endpoint (apply2/endpoint-request ep)
                                   (assoc (apply2/endpoint-response ep)
                                          :dao.stream/identity "no-such"))
            c (settle-caller pb (caller/open (assoc (opts acquired)
                                                    ::caller/endpoint bogus)))]
        (is (= ::caller/refused (::caller/status c)))
        (is (some? (::caller/outcome c)))
        (is (true? (::caller/closed? c)))
        (is (= 2 (count @acquired)))
        (is (every? closed? @acquired))))
    (testing "a ready caller that builds no VM is torn down, idempotently"
      (let [acquired (atom [])
            ready (settle-caller pb (caller/open (opts acquired)))
            closed (caller/close! ready)]
        (is (caller/ready? ready))
        (is (nil? (::caller/closed? ready)) "readiness alone closes nothing")
        (is (not (caller/ready? closed)))
        (is (nil? (caller/vm-opts closed)))
        (is (every? closed? @acquired))
        (is (= closed (caller/close! closed)) "a second close! is a no-op")
        (is (= closed (caller/close! (caller/close! closed))))))
    (testing "a caller refused at open holds nothing to close"
      (let [r (caller/open (assoc (opts (atom [])) ::caller/caller-id ""))]
        (is (= r (caller/close! r)))))
    (testing "success hands the pair on open: a VM completes a call over it"
      (let [acquired (atom [])
            ready (settle-caller pb (caller/open (opts acquired)))
            machine (semantic/create-vm (merge {:make-stream make-ring-stream
                                                :capability-secret secret}
                                               (caller/vm-opts ready)))
            done (settle pb (load-call machine (call-node :op/add 4 4)))]
        (is (= 8 (vm/value done)))
        (is (= [(:call-in (caller/vm-opts ready))
                (:call-out (caller/vm-opts ready))]
               @acquired)
            "the very reflections it acquired, unchanged")))))


(deftest a-migrated-composite-call-is-routed-on-its-carried-cell
  (let [t (channel)
        pb (peer-b t)
        b (:binding pb)
        emitter (semantic/create-vm {:make-stream make-ring-stream
                                     :capability-secret secret
                                     :ffi-caller-id "emitter-b"
                                     :call-in (:call-in pb)
                                     :call-out (:call-out pb)
                                     :call-out-cursor (oldest (:call-out pb))})
        ast (call-node :op/add 7 8)
        datoms (vm/ast->datoms ast)
        _ (reset! (:in-full? pb) true)
        parked (vm/run (load-ast emitter datoms))
        entry (first (:wait-set parked))
        lifted (rs/lift-frame b (:resources parked) [entry])
        pending (first (:yin.k/pending lifted))
        pa (peer-a (:a-end t))
        receiver-base (semantic/create-vm {:make-stream make-ring-stream
                                           :capability-secret secret
                                           :ffi-caller-id "receiver-a"})
        lowered (ucf.remote/lower-pending (:attach! pa) pending
                                          (:yin.k/cells lifted)
                                          (select-keys entry
                                                       [:segment :pc :env
                                                        :stack :k])
                                          (:resources receiver-base))
        receiver (assoc (load-ast receiver-base datoms)
                        :control nil, :k nil, :value :yin/blocked,
                        :blocked? true, :halted? false,
                        :wait-set [(:entry lowered)],
                        :resources (merge (:resources receiver-base)
                                          (:resources lowered)))]
    (testing "the retained call carries its composite id across UCF"
      (is (= ["emitter-b" :parked-0] (:call-id entry)))
      (is (= (:call-id entry) (:yin.k/call-id pending)))
      (is (= (:call-id entry) (:call-id (:entry lowered))))
      (let [cell (:response-cursor (:entry lowered))]
        (is (some? cell))
        (is (not= vm/call-out-cursor-key cell)
            "the resumer waits on a carried cell, not its own call-out")))
    ;; another caller's response lands first on the carried cell's stream
    (apply2/put-response! (:call-out pb)
                          (apply2/success-response ["other" :parked-0] 99))
    (reset! (:in-full? pb) false)
    (let [done (settle pb receiver)]
      (is (vm/halted? done))
      (is (= 15 (vm/value done)) "its own response, past the foreign one")
      (is (= [["other" :parked-0]]
             (map :response-id (first (engine/take-ffi-diagnostics done))))
          "the carried cell was routed: the foreign response was skipped")
      (is (= [(:datom entry)] (values (:call-in pb)))))))


;; =============================================================================
;; 10. Refusal
;; =============================================================================

(deftest an-unservable-endpoint-refuses-the-responder
  (let [t (channel)
        call-in (ring 4)
        call-out (ring 4)
        ticks (ring 8)
        grants (ring 8)
        b (rs/open! (assoc (binding-opts t ticks grants call-in 64 chan)
                           ::rs/admit? #(not (identical? call-out %))))
        exports #(dissoc (rs/table b)
                         (:dao.stream/identity (rs/lease-grants b)))]
    (is (= [{::resp/option ::resp/call-out, ::resp/reason ::resp/unservable}]
           (::resp/refusals
             (resp/open! b {::resp/call-in call-in
                            ::resp/call-out call-out
                            ::resp/handlers {}}))))
    (is (empty? (exports)) "call-in, served first, was retired")
    (is (empty? (rs/entries b)))
    (testing "the handler map has no default"
      (is (= [{::resp/option ::resp/handlers, ::resp/reason ::resp/missing}]
             (::resp/refusals (resp/open! b {::resp/call-in call-in
                                             ::resp/call-out (ring 4)})))))
    (testing "an endpoint served without the surface it needs"
      (let [b' (rs/open! (assoc (binding-opts t ticks grants call-in 64 chan)
                                ::rs/surface (constantly #{:reader})))]
        (is (= [{::resp/option ::resp/call-in, ::resp/reason ::resp/surface}]
               (::resp/refusals
                 (resp/open! b' {::resp/call-in call-in
                                 ::resp/call-out call-out
                                 ::resp/handlers {}}))))
        (is (empty? (rs/entries b')))))))
