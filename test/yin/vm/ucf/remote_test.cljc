(ns yin.vm.ucf.remote-test
  "The slice 8 acceptance row
   (`docs/design/dao.stream.remote.implementation-plan.md` slice 8;
   `yin.vm.universal-continuation-format.md` S7.4.3, S7.5.3, S7.11.1
   'Reflection acceptance'): a REAL machine parked through the engine
   on a `:stream-next` wait migrates with a kept cursor and resumes
   equivalently in a fresh engine state through a reflection; forced
   eviction yields `gap` with the source's own recovery cursor; a
   kept cursor that cannot survive the channel codec refuses the lift
   before any cell is published; a source `serve!` refuses is
   `:yin.k/unsatisfied`; an `attach!` that answers gone is
   `:yin.k/unsatisfied` naming the stream at lower -- either endpoint
   of a retained call. Cursor cells are
   minted and remapped across a whole frame set: two waiters on one
   engine cell lift to one UCF cell, lower back shared, and advance
   in order on the one cell -- the first waiter's value, then the
   second's, the shared cursor advancing between them -- while
   independent cells at one position stay independent (S7.5.3). The
   remaining S7.4.3 variants -- `:put`, `:ffi`, `:ffi-request`,
   `:link-request`, `:link-response`, `:install` -- round-trip
   through stub `serve!`/`attach!` callbacks over the engine's own
   wait-set shapes, since their proof is the shape of the bridge, not
   the transport. The lowered waits the engine resolves at its own
   fixed resource keys resume there for real: a retained
   `:link-request` migrates and the engine's own poll retries it and
   resumes on the response read at the fixed link keys; a retained
   FFI request migrates, its retry appends at the fixed call-in key,
   and the restored response wait -- `semantic-restore` parks it on
   the receiver's fixed local call-out keys -- receives the retried
   response the route cell carried."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.ucf.remote :as ucf.remote]))


;; =============================================================================
;; The toy: two in-process ring buffers as the channel (as
;; dao.stream.remote-test), with a served-table `serve!` on top
;; =============================================================================

(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity capacity})))


(defn- toy
  []
  (let [ab (ring 64)
        ba (ring 64)
        cd {:dao.stream/type :dao.stream.test/channel
            :dao.stream/identity "ucf-remote-toy"}]
    {:channel cd, :ab ab, :ba ba,
     :a-end {:reader ba, :writer ab},
     :b-end {:reader ab, :writer ba}}))


(defn- served-peer
  [t]
  {:table (atom {})
   :b-reader (:reader (:b-end t))
   :b-writer (:writer (:b-end t))
   :mirror (atom (:dao.stream/cursor
                   (stream/cursor (:reader (:b-end t)) :dao.stream/oldest)))})


(defn- serve-mirror!
  "One mirror step over everything pending on the toy's request
   buffer."
  [peer]
  (swap! (:mirror peer)
         #(remote/mirror-step @(:table peer) (:b-reader peer) %
                              (:b-writer peer))))


(defn- server
  "The exporter's `serve!` (dao.stream.remote.md S2.3): assigns a
   stable identity to `h`, entering it in the peer's table under
   `chan`, if it is not already served. The served surface defaults
   to reader only; a stream the remote writes through -- a link
   request stream the lowered retry appends to, a call-in stream the
   retried FFI request lands on -- is served with the writer surface
   too."
  ([peer chan] (server peer chan #{:reader}))
  ([peer chan surfaces]
   (fn [h]
     (if-let [existing (some (fn [[id e]] (when (= h (:handle e)) id))
                             @(:table peer))]
       {:dao.stream/identity existing, :dao.stream/channel chan}
       (let [id (str (gensym "seg"))]
         (swap! (:table peer) assoc id {:handle h, :surface surfaces})
         {:dao.stream/identity id, :dao.stream/channel chan})))))


(defn- attacher
  [t]
  (remote/attacher
    {:dao.stream.remote/channels {(:channel t) (:a-end t)}}))


;; =============================================================================
;; The emitter side: a real machine parked through the engine
;; =============================================================================

(def ^:private secret
  "The capability secret the test composition gives its machines."
  "yin.vm.ucf.remote-test/capability-secret")


(defn- make-ring-stream
  "The ring-buffer `:make-stream` this composition gives its
   machines -- `yin.vm.test-utils`'s choice, at test scale."
  [capacity]
  (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                       :dao.stream.ringbuffer/capacity capacity}))


(defn- new-machine
  "A fresh semantic machine over that composition."
  []
  (semantic/create-vm {:make-stream make-ring-stream
                       :capability-secret secret}))


(def ^:private load-ast
  "The trusted fresh path: the AST linearizer behind the code loader."
  (vm/fresh-code-loader (linearize/ast-loader semantic/vm-load-program)
                        vm/ast-contract))


(defn- var-ref
  [n]
  {:type :variable, :name n})


(defn- let1
  "`((fn [param] body) init)`."
  [param init body]
  {:type :application,
   :operator {:type :lambda, :params [param], :body body},
   :operands [init]})


(defn- reader-datoms
  "Make a stream of `capacity`, mint a cursor on it, read one value."
  [capacity]
  (vm/ast->datoms
    (let1 's {:type :stream/make, :buffer capacity}
          {:type :stream/next,
           :source {:type :stream/cursor, :source (var-ref 's)}})))


(defn- parked-reader
  "A real machine parked through the engine on a `:stream-next` wait
   over a fresh ring buffer: the code datoms, the parked VM, its
   wait-set entry, and the stream handle the entry polls."
  [capacity]
  (let [datoms (reader-datoms capacity)
        parked (vm/run (load-ast (new-machine) datoms))
        entry (first (:wait-set parked))]
    [datoms parked entry
     (get (:resources parked) (:stream-id entry))]))


(defn- lit
  [v]
  {:type :literal, :value v})


(defn- one-slot-stream
  "A stream that answers `full` while one value sits in it -- the one
   append outcome a ring buffer never gives (a ringbuffer evicts),
   without which no request stays retained. Reading the value frees
   the slot. The descriptor answers ok, so the reflection's attach
   probe is answerable."
  [identity]
  (let [slot (atom :empty)]
    (reify
      stream/IDaoStreamDescriptor
      (descriptor
        [_]
        {:dao.stream/outcome :dao.stream/ok,
         :dao.stream/identity identity,
         :dao.stream/descriptor
         {:dao.stream/type :dao.stream.test/channel,
          :dao.stream/identity identity}})


      stream/IDaoStreamReader

      (cursor
        [_ _anchor]
        {:dao.stream/outcome :dao.stream/ok, :dao.stream/cursor 0})

      (next
        [_ cursor]
        (if (and (= 0 cursor) (not= :empty @slot))
          (let [v @slot]
            (reset! slot :empty)
            {:dao.stream/outcome :dao.stream/ok,
             :dao.stream/value v, :dao.stream/cursor 1})
          {:dao.stream/outcome :dao.stream/blocked}))


      stream/IDaoStreamWriter

      (append!
        [_ v]
        (if (not= :empty @slot)
          {:dao.stream/outcome :dao.stream/full}
          (do (reset! slot v)
              {:dao.stream/outcome :dao.stream/ok}))))))


(defn- requirer-datoms
  "The datoms of `(require name)` -- the effectful primitive whose
   miss parks mid-link (`module/require-handler`)."
  [name]
  (vm/ast->datoms
    {:type :application,
     :operator {:type :variable, :name 'require},
     :operands [(lit name)]}))


(defn- linking-machine
  "A fresh semantic machine composed over a link pair, the module
   kernel's own composition keys (`yin.vm.linker.md` S6.1)."
  [request response]
  (semantic/create-vm
    {:make-stream make-ring-stream,
     :capability-secret secret,
     :modules (module/default-registry),
     :link-request request,
     :link-response response}))


(defn- linked-machine
  "A fresh semantic machine whose registry already holds `name` as a
   host module: `settle`'s own linked-meanwhile answer, no install
   child needed."
  [name]
  (semantic/create-vm
    {:make-stream make-ring-stream,
     :capability-secret secret,
     :modules (module/register-host-module
                (module/default-registry) name
                {'answer 42}
                {'answer (vm/primitive-profile 'answer :pure [0]
                                               #{} :none)})}))


(defn- parked-requirer
  "A real machine parked mid-link by a require: the request stream
   holds one warm-up value, so `require-handler`'s append refuses
   with full and the entry keeps its envelope -- a retained
   `:link-request`, the engine's own fields, registers and pair."
  [name]
  (let [request (one-slot-stream "link-request")
        response (ring 64)
        _ (stream/append! request :warmed)
        datoms (requirer-datoms name)
        parked (vm/run (load-ast (linking-machine request response)
                                 datoms))
        entry (first (:wait-set parked))]
    [datoms parked entry request response]))


(defn- call-node
  "A `:dao.stream.apply/call` of `op` with one literal argument."
  [op arg]
  {:type :dao.stream.apply/call, :op op,
   :operands [(lit arg)]})


(defn- then
  "`((fn [_] second) first)`: first for its effect, then second."
  [first-ast second-ast]
  {:type :application,
   :operator {:type :lambda, :params ['_], :body second-ast},
   :operands [first-ast]})


(defn- calling-machine
  "A fresh semantic machine composed over an FFI call pair, under the
   engine's own fixed pair keys (`vm.cljc`'s `:call-in`/`:call-out`
   composition keys). `bridge` is the machine's own handler map for
   calls that arrive on that pair."
  [call-in call-out bridge]
  (semantic/create-vm
    {:make-stream make-ring-stream,
     :capability-secret secret,
     :call-in call-in,
     :call-out call-out,
     ;; the composition mints a supplied call-out's cursor
     :call-out-cursor (vm/mint-oldest call-out :test),
     :bridge bridge}))


(defn- parked-caller
  "A real machine parked on a retained FFI request: the call-in
   stream holds one warm-up value, so the first call's put refuses
   with full and the entry keeps the request -- the engine's own
   `:request-sent` shape, the pair under its fixed resource keys.
   `ast` may call again after the first call, so a resume can watch
   where a future call routes."
  [ast]
  (let [call-in (one-slot-stream "call-in")
        call-out (ring 8)
        _ (stream/append! call-in :warmed)
        datoms (vm/ast->datoms ast)
        parked (vm/run (load-ast (calling-machine call-in call-out nil)
                                 datoms))
        entry (first (:wait-set parked))]
    [datoms parked entry call-in call-out]))


(defn- registers-of
  "The register payload a parked entry carries."
  [entry]
  (select-keys entry [:segment :pc :env :stack :k]))


(defn- quiesced-receiver
  "The fresh engine state a lower installs into: a real machine over
   the same code image, quiesced, holding the lowered resource table
   and the lowered wait-set entry -- nothing of the emitter's state
   but the code image and the registers the entry carries."
  ([datoms lowered] (quiesced-receiver datoms lowered (new-machine)))
  ([datoms lowered machine]
   (assoc (load-ast machine datoms)
          :control nil, :k nil,
          :value :yin/blocked, :blocked? true, :halted? false,
          :wait-set [(:entry lowered)],
          :resources (:resources lowered))))


(defn- cursor-entries
  "The cursor entries of a lowered resource table: maps keyed by the
   UCF cell ids, each carrying its stream id and kept cursor."
  [table]
  (into {} (filter (fn [[_ v]] (and (map? v) (contains? v :stream-id))))
        table))


;; =============================================================================
;; The acceptance row: a real parked machine migrates and resumes
;; =============================================================================

(deftest migrates-a-real-parked-machine-and-resumes
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t))
        attach! (attacher t)
        [datoms parked entry src] (parked-reader 4)
        cid (:id (:cursor-ref entry))
        sid (:stream-id entry)
        kept (get-in parked [:resources cid :cursor])
        lifted (ucf.remote/lift-pending serve! (:resources parked) entry)
        lowered (ucf.remote/lower-pending attach! (:yin.k/pending lifted)
                                          (:yin.k/cells lifted)
                                          (registers-of entry) {})
        receiver (quiesced-receiver datoms lowered)]
    (testing "the machine parked through the engine"
      (is (vm/blocked? parked))
      (is (= :next (:reason entry)))
      (is (= :cursor-ref (get-in entry [:cursor-ref :type]))))
    (testing "the lift reads the engine's own wait-set entry"
      (is (= {:yin.k/reason :next, :yin.k/cell :yin.k/c-0}
             (:yin.k/pending lifted))
          "the cursor CELL id, S7.5.3 -- not a bare position")
      (is (= kept (get-in lifted [:yin.k/cells :yin.k/c-0 :yin.k/position]))
          "the cell carries the engine's kept cursor")
      (is (= #{:dao.stream.remote/v1}
             (get-in lifted [:yin.k/requires :yin.k/cursor-profiles]))))
    (testing "the lower builds a fresh engine state"
      (let [e (:entry lowered)
            rs (:resources lowered)
            cid' (get-in e [:cursor-ref :id])
            sid' (:stream-id e)]
        (is (= (registers-of entry) (registers-of e))
            "the register context rides the lower verbatim")
        (is (not= cid cid'))
        (is (not= sid sid'))
        (is (not (contains? rs cid)))
        (is (not (contains? rs sid)))
        (is (= {:stream-id sid', :cursor kept} (get rs cid'))
            "one fresh cursor entry, at the kept position")
        (is (not= src (get rs sid'))
            "the stream entry is a reflection, not the emitter's handle")
        (is (= sid' (get-in rs [cid' :stream-id])))))
    (testing "the emitter's own wait resumes equivalently"
      (stream/append! src "hello")
      (is (= "hello" (vm/value (vm/run parked)))))
    (testing "the migrated wait resumes in the fresh engine state"
      (is (vm/blocked? (vm/run receiver))
          "the reflection answers blocked until the mirror serves")
      (serve-mirror! peer)
      (let [done (vm/run receiver)]
        (is (vm/halted? done))
        (is (= "hello" (vm/value done))
            "the source's own value, through the kept cursor")
        (is (empty? (:wait-set done)))))))


(deftest forced-eviction-yields-gap-with-the-sources-cursor
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t))
        attach! (attacher t)
        [datoms parked entry src] (parked-reader 2)
        cid (:id (:cursor-ref entry))
        kept (get-in parked [:resources cid :cursor])
        lifted (ucf.remote/lift-pending serve! (:resources parked) entry)
        lowered (ucf.remote/lower-pending attach! (:yin.k/pending lifted)
                                          (:yin.k/cells lifted)
                                          (registers-of entry) {})
        cid' (get-in (:entry lowered) [:cursor-ref :id])
        receiver (quiesced-receiver datoms lowered)]
    (stream/append! src "hello")
    (doseq [v ["v1" "v2" "v3"]]
      (stream/append! src v))
    (is (vm/blocked? (vm/run receiver)))
    (serve-mirror! peer)
    (let [expected (stream/next src kept)
          done (vm/run receiver)]
      (testing "the program reads the source's own gap, verbatim"
        (is (= :dao.stream/gap (vm/value done)))
        (is (= (:dao.stream/cursor expected)
               (get-in done [:resources cid' :cursor]))
            "with the source's own recovery cursor in the cell"))
      (testing "no replay and no silent skip: the walk continues
                from the recovery cursor"
        (let [r (get-in done [:resources (:stream-id (:entry lowered))])
              recovery (get-in done [:resources cid' :cursor])]
          (is (= :dao.stream/blocked
                 (:dao.stream/outcome (stream/next r recovery))))
          (serve-mirror! peer)
          (let [ok (stream/next r recovery)]
            (is (= :dao.stream/ok (:dao.stream/outcome ok)))
            (is (= "v2" (:dao.stream/value ok)))))))))


;; =============================================================================
;; The fixed keys: lowered link waits and retained FFI requests
;; resume through the engine's own poll and retry paths
;; =============================================================================

(deftest a-lowered-link-request-retries-and-resumes-through-the-engine
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t) #{:reader :writer})
        attach! (attacher t)
        [datoms parked entry req-src resp-src]
        (parked-requirer 'host.the-module)
        lifted (ucf.remote/lift-pending serve! (:resources parked) entry)
        pending (:yin.k/pending lifted)
        lowered (ucf.remote/lower-pending attach! pending
                                          (:yin.k/cells lifted)
                                          (registers-of entry) {})
        e (:entry lowered)
        rs (:resources lowered)
        receiver (quiesced-receiver datoms lowered
                                    (linked-machine 'host.the-module))]
    (testing "the machine parked through the engine, envelope retained"
      (is (vm/blocked? parked))
      (is (= :link-request (:reason entry)))
      (is (= module/link-request-resource (:request entry)))
      (is (= module/link-response-resource (:response entry)))
      (is (map? (:envelope entry)) "the request the retry appends"))
    (testing "the lower parks the wait where the engine polls"
      (is (= module/link-request-resource (:request e))
          "the retry appends at the engine's fixed request key")
      (is (= module/link-response-resource (:response e))
          "the poll reads at the engine's fixed response key")
      (is (contains? rs (:request e)))
      (is (contains? rs (:response e))
          "the pair's reflections stand under the fixed keys"))
    (testing "the engine's own poll retries and resumes the require"
      ;; the linker answered before the migration: the response sits
      ;; on the emitter's response stream, past the kept cursor
      (stream/append! resp-src
                      {:status :ok,
                       :yin.link/id (:link-id entry),
                       :manifest {:yin.module/name 'host.the-module}})
      (is (vm/blocked? (vm/run receiver))
          "first poll: the envelope is retried, the answer not yet read")
      (let [w0 (:dao.stream/cursor
                 (stream/cursor req-src :dao.stream/oldest))]
        (is (= :warmed
               (:dao.stream/value (stream/next req-src w0)))
            "reading the warm-up frees the slot the retry needs"))
      (serve-mirror! peer)
      (let [c0 (:dao.stream/cursor
                 (stream/cursor req-src :dao.stream/oldest))
            r (stream/next req-src c0)]
        (is (= :dao.stream/ok (:dao.stream/outcome r)))
        (is (= (:envelope entry) (:dao.stream/value r))
            "the envelope verbatim, appended via the fixed request key"))
      (let [done (vm/run receiver)]
        (is (vm/halted? done))
        (is (= 'host.the-module (vm/value done))
            "the require resumes with the module's own value")
        (is (empty? (:wait-set done)))))))


(deftest a-retained-ffi-request-migrates-and-the-retry-resumes
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t) #{:reader :writer})
        attach! (attacher t)
        program (then (call-node :op/echo "hello")
                      (call-node :op/echo "world"))
        [datoms parked entry call-in-src call-out-src]
        (parked-caller program)
        ;; a producer's own extra envelope key -- legal, the open
        ;; request protocol ignores unknown keys
        ;; (`dao.stream.apply`'s `request?`) -- must survive the
        ;; migration verbatim, which no op/args rebuild could give
        entry (assoc-in entry [:datom :producer/nonce] "n-1")
        kept (get-in parked [:resources vm/call-out-cursor-key :cursor])
        lifted (ucf.remote/lift-pending serve! (:resources parked) entry)
        pending (:yin.k/pending lifted)
        ;; the resumer's own pair and its own handler, under the fixed
        ;; keys: they serve the program's subsequent calls and can
        ;; never answer the migrated one
        own-call-in (ring 8)
        own-call-out (ring 8)
        own (calling-machine own-call-in own-call-out
                             {:op/echo (constantly "local!")})
        own-pair (select-keys (:resources own)
                              [vm/call-in-stream-key
                               vm/call-out-stream-key
                               vm/call-out-cursor-key])
        lowered (ucf.remote/lower-pending attach! pending
                                          (:yin.k/cells lifted)
                                          (registers-of entry)
                                          (keys own-pair))
        e (:entry lowered)
        rs (:resources lowered)
        receiver (-> (quiesced-receiver datoms lowered own)
                     (update :resources #(merge own-pair %)))
        oldest (fn [h]
                 (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))
        ;; the emitter's bridge answered the migrated call before the
        ;; migration: the response sits on the emitter's call-out,
        ;; past the kept cursor
        _ (stream/append! call-out-src
                          (apply2/success-response
                            (:call-id entry) "echo!"))
        ;; phase 1: the sweep retries the retained request through the
        ;; carried request reflection, and the restored response wait
        ;; polls the carried response route
        r1 (vm/run receiver)
        wait-a (first (:wait-set r1))
        warm-up (stream/next call-in-src (oldest call-in-src))
        _ (serve-mirror! peer)
        landed (stream/next call-in-src (oldest call-in-src))
        ;; phase 2: the carried route delivers the response; the
        ;; program's own next call parks on the receiver's local pair
        ;; and its own handler answers it there
        r2 (vm/run r1)]
    (testing "the machine parked through the engine, request retained"
      (is (vm/blocked? parked))
      (is (true? (:request-sent entry)))
      (is (= vm/call-in-stream-key (:stream-id entry)))
      (is (apply2/request? (:datom entry))))
    (testing "the lift carries both endpoints and the kept response cell"
      (is (= (:datom entry) (:yin.k/request-envelope pending))
          "the envelope rides VERBATIM -- the producer's extra key
           included, beside the op/args views")
      (is (contains? pending :yin.k/request))
      (is (contains? pending :yin.k/response)
          "both endpoint markers ride beside the retained envelope")
      (is (= :yin.k/c-0 (:yin.k/response-cell pending)))
      (is (= (get-in pending [:yin.k/response :dao.stream/identity])
             (get-in lifted [:yin.k/cells :yin.k/c-0 :yin.k/stream
                             :dao.stream/identity]))
          "the response endpoint's marker is the route cell's stream")
      (is (= kept
             (get-in lifted [:yin.k/cells :yin.k/c-0 :yin.k/position]))
          "the kept response position"))
    (testing "the lower allocates fresh keys and carries the route"
      (is (contains? rs (:stream-id e))
          "the retry writes through the carried request reflection")
      (is (not= vm/call-in-stream-key (:stream-id e))
          "never the receiver's own call-in key")
      (is (not (contains? rs vm/call-in-stream-key)))
      (is (not (contains? rs vm/call-out-stream-key)))
      (is (not (contains? rs vm/call-out-cursor-key))
          "no route is ever installed at a fixed key")
      (is (= (:response-cursor e) (get-in wait-a [:cursor-ref :id]))
          "the restored response wait polls the carried cell's key")
      (is (= (:response-stream e) (:stream-id wait-a))
          "reading the carried response reflection")
      (let [route (get rs (:response-cursor e))]
        (is (= kept (:cursor route))
            "at the kept response position")
        (is (= (:response-stream e) (:stream-id route))
            "naming the carried response reflection")
        (is (contains? rs (:response-stream e)))))
    (testing "the retried call is answered on the emitter's pair"
      (is (vm/blocked? r1)
          "first round: the request is retried, the response not yet
           read")
      (is (= :warmed (:dao.stream/value warm-up))
          "reading the warm-up freed the slot the retry needed")
      (is (= :dao.stream/ok (:dao.stream/outcome landed)))
      (is (= (:datom entry) (:dao.stream/value landed))
          "the retained request VERBATIM -- the producer's extra key
           included -- through the carried request reflection")
      (is (= "echo!"
             (apply2/response-ok
               (:dao.stream/value
                 (stream/next call-out-src (oldest call-out-src)))))
          "the emitter's bridge answered it on the emitter's pair"))
    (testing "the carried route delivers, and later calls are local"
      (is (vm/halted? r2))
      (is (= "local!" (vm/value r2))
          "the migrated result came from the emitter's bridge; the
           receiver's own handler answered only the program's own next
           call")
      (is (not= kept (:cursor (get-in r2 [:resources
                                          (:response-cursor e)])))
          "the carried route's cursor advanced: the response was read
           through it")
      (is (= ["world"]
             (apply2/request-args
               (:dao.stream/value
                 (stream/next own-call-in (oldest own-call-in)))))
          "the subsequent call's request is on the receiver's own
           call-in")
      (is (= "local!"
             (apply2/response-ok
               (:dao.stream/value
                 (stream/next own-call-out (oldest own-call-out)))))
          "and its answer is on the receiver's own call-out"))))


;; =============================================================================
;; Unsatisfied: an unpublishable cursor, a source that cannot be
;; served, a gone reflection
;; =============================================================================

(defn- host-cursor-stream
  "A handle whose cursors hold a host object -- the case
   dao.stream.remote.md S2.3 says cannot be served."
  []
  (reify stream/IDaoStreamDescriptor
    (descriptor
      [_]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/identity "host-cursor"
       :dao.stream/descriptor
       {:dao.stream/type :dao.stream.test/channel
        :dao.stream/identity "host-cursor"}})


    stream/IDaoStreamReader

    (cursor
      [_ _]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/cursor {:position 0, :owner (fn [x] x)}})

    (next
      [_ _]
      {:dao.stream/outcome :dao.stream/blocked})))


(deftest a-cursor-that-cannot-round-trip-is-refused
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t))
        s (host-cursor-stream)
        resources {:yin/host-stream s,
                   :yin/host-cursor
                   {:stream-id :yin/host-stream,
                    :cursor {:position 0, :owner (fn [x] x)}}}
        entry {:reason :next,
               :cursor-ref {:type :cursor-ref, :id :yin/host-cursor},
               :stream-id :yin/host-stream}
        refusal (ucf.remote/lift-pending serve! resources entry)]
    (is (= :yin.k/unsatisfied (:yin.k/status refusal))
        "the profile claim is checked before anything is published")
    (is (= "host-cursor" (:dao.stream/identity refusal))
        "naming the stream identity")
    (is (empty? @(:table peer))
        "refused before `serve!` even mints the descriptor")))


(deftest lift-refuses-unsatisfied-when-the-source-cannot-be-served
  (let [s (ring 4)]
    (stream/append! s "x")
    (let [c0 (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))
          resources {:yin/s s, :yin/c {:stream-id :yin/s, :cursor c0}}
          entry {:reason :next,
                 :cursor-ref {:type :cursor-ref, :id :yin/c},
                 :stream-id :yin/s}
          refusal (ucf.remote/lift-pending (constantly nil) resources entry)]
      (is (= :yin.k/unsatisfied (:yin.k/status refusal))
          "lift refuses before minting the value")
      (is (contains? refusal :dao.stream/identity)
          "naming the stream identity"))))


(deftest lower-refuses-unsatisfied-when-the-reflection-is-gone
  (let [attach! (constantly {:dao.stream/outcome :dao.stream/transport-error
                             :dao.stream.remote/reason
                             :dao.stream.remote/not-found})
        marker {:yin.k/tag :yin.k/stream,
                :dao.stream/identity "missing",
                :dao.stream/descriptor
                {:dao.stream/type :dao.stream/remote,
                 :dao.stream/identity "missing",
                 :dao.stream/channel
                 {:dao.stream/type :dao.stream.test/channel,
                  :dao.stream/identity "elsewhere"}}}
        pending {:yin.k/reason :next, :yin.k/cell :yin.k/c-0}
        cells {:yin.k/c-0 {:yin.k/stream marker, :yin.k/position 0}}
        lowered (ucf.remote/lower-pending attach! pending cells {} {})]
    (is (= :yin.k/unsatisfied (:yin.k/status lowered)))
    (is (= "missing" (:dao.stream/identity lowered))
        "naming the stream identity")))


;; =============================================================================
;; Frame-level cells (S7.5.3): shared and independent
;; =============================================================================

(defn- stub-serve!
  "A `serve!` that assigns every handle to one identity per handle
   under `chan`, with no wire underneath: these variants prove the
   bridge's shape, the transport already proven above."
  [chan]
  (let [ids (atom {})]
    (fn [h]
      (let [id (or (get @ids h)
                   (let [n (str (gensym "seg"))]
                     (swap! ids assoc h n)
                     n))]
        {:dao.stream/identity id, :dao.stream/channel chan}))))


(defn- stub-attach!
  "An `attach!` that answers ok at once with a reflection standing for
   the descriptor's identity -- opaque here, since these variants
   never poll it."
  []
  (fn [d]
    {:dao.stream/outcome :dao.stream/ok,
     :dao.stream/handle [:reflection-of (:dao.stream/identity d)]}))


(def ^:private chan
  {:dao.stream/type :dao.stream.test/channel, :dao.stream/identity "c"})


(deftest shared-cursor-cells-lift-and-lower-shared
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t))
        attach! (attacher t)
        [datoms parked entry src] (parked-reader 4)
        cid (:id (:cursor-ref entry))
        sid (:stream-id entry)
        kept (get-in parked [:resources cid :cursor])
        ;; a second waiter on the SAME engine cursor cell, as
        ;; check-wait-set's shared-cursor co-waiters hold (named
        ;; waiter-2: a `second` binding would shadow clojure.core/second
        ;; under the assertions below)
        waiter-2 (assoc (registers-of entry)
                        :reason :next,
                        :stream-id sid,
                        :cursor-ref {:type :cursor-ref, :id cid},
                        :pc 42)
        lifted (ucf.remote/lift-frame serve! (:resources parked)
                                      [entry waiter-2])
        lowered (ucf.remote/lower-frame attach! (:yin.k/pending lifted)
                                        (:yin.k/cells lifted)
                                        [(registers-of entry)
                                         (registers-of waiter-2)] {})
        [e1 e2] (:entries lowered)
        rs (:resources lowered)
        shared (get-in e1 [:cursor-ref :id])
        receiver (assoc (load-ast (new-machine) datoms)
                        :control nil, :k nil,
                        :value :yin/blocked, :blocked? true,
                        :halted? false,
                        :wait-set (:entries lowered),
                        :resources rs)]
    (testing "two waiters on one engine cell lift to one UCF cell"
      (is (= 1 (count (:yin.k/cells lifted))))
      (let [[p1 p2] (:yin.k/pending lifted)]
        (is (= (:yin.k/cell p1) (:yin.k/cell p2))
            "both waiters name the one cell")))
    (let [ctxs [(registers-of entry) (registers-of waiter-2)]]
      (testing "and lower back into one shared cursor entry"
        (is (= shared (get-in e2 [:cursor-ref :id]))
            "one cell id stands for the one entry")
        (is (= 1 (count (cursor-entries rs))))
        (is (= kept (:cursor (get rs shared)))
            "the one entry sits at the kept position")
        (is (= ctxs [(select-keys e1 [:segment :pc :env :stack :k])
                     (select-keys e2 [:segment :pc :env :stack :k])])
            "each entry keeps its own register context")))
    (testing "co-waiters advance in order on the one shared cell"
      (stream/append! src "hello")
      (stream/append! src "world")
      (let [w0 (engine/check-wait-set receiver)]
        (is (empty? (:ready-queue w0))
            "nothing filed yet: both polls went to the wire")
        (is (= 2 (count (:wait-set w0))))
        (serve-mirror! peer)
        (let [w1 (engine/check-wait-set w0)
              wake1 (first (:ready-queue w1))]
          (is (= ["hello"] (mapv :value (:ready-queue w1)))
              "the first waiter's value -- not both waiters reading
               the value at the shared pre-poll cursor")
          (is (= shared (get-in wake1 [:cursor-ref :id]))
              "the wake names the one shared cell")
          (is (= 1 (count (:wait-set w1)))
              "the second waiter stays parked on that cell")
          (is (= (:cursor wake1)
                 (:cursor (get (:resources w1) shared)))
              "the shared cursor advanced to the first waiter's
               successor before the second was polled")
          (serve-mirror! peer)
          (let [w2 (engine/check-wait-set w1)]
            (is (= ["hello" "world"] (mapv :value (:ready-queue w2)))
                "first waiter's value, then second's, in order")
            (is (empty? (:wait-set w2)))
            (is (every? #(= shared (get-in % [:cursor-ref :id]))
                        (:ready-queue w2))
                "both wakes are the two co-waiters on the one cell")
            (is (= (:cursor (second (:ready-queue w2)))
                   (:cursor (get (:resources w2) shared)))
                "the shared cursor advanced again, past the second's
                 read")))))))


(deftest independent-cells-at-one-position-stay-independent
  (let [serve! (stub-serve! chan)
        attach! (stub-attach!)
        s (ring 8)
        _ (stream/append! s "v")
        c0 (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))
        resources {:yin/s s,
                   :yin/c1 {:stream-id :yin/s, :cursor c0},
                   :yin/c2 {:stream-id :yin/s, :cursor c0}}
        entry1 {:reason :next,
                :cursor-ref {:type :cursor-ref, :id :yin/c1},
                :stream-id :yin/s}
        entry2 {:reason :next,
                :cursor-ref {:type :cursor-ref, :id :yin/c2},
                :stream-id :yin/s}
        lifted (ucf.remote/lift-frame serve! resources
                                      [entry1 entry2])]
    (testing "two engine cells at one position are two UCF cells"
      (is (= 2 (count (:yin.k/cells lifted)))
          "the aliasing a position-only encoding erases, kept")
      (let [[p1 p2] (:yin.k/pending lifted)]
        (is (not= (:yin.k/cell p1) (:yin.k/cell p2)))
        (is (= c0 (get-in lifted [:yin.k/cells (:yin.k/cell p1)
                                  :yin.k/position])))
        (is (= c0 (get-in lifted [:yin.k/cells (:yin.k/cell p2)
                                  :yin.k/position])))))
    (let [ctx {:segment 0, :pc 1, :env {}, :stack [], :k []}
          lowered (ucf.remote/lower-frame attach! (:yin.k/pending lifted)
                                          (:yin.k/cells lifted)
                                          [ctx ctx] {})
          [e1 e2] (:entries lowered)
          rs (:resources lowered)]
      (testing "and lower into two independently advanceable entries"
        (is (not= (get-in e1 [:cursor-ref :id])
                  (get-in e2 [:cursor-ref :id])))
        (is (= 2 (count (cursor-entries rs))))
        (is (= 1 (count (filter vector? (vals rs))))
            "one stream, so the two cells share one reflection")))))


(deftest a-refused-leaf-refuses-the-frame
  (let [serve! (stub-serve! chan)
        s (ring 4)
        c0 (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))
        resources {:yin/s s, :yin/c {:stream-id :yin/s, :cursor c0}}
        good {:reason :next,
              :cursor-ref {:type :cursor-ref, :id :yin/c},
              :stream-id :yin/s}]
    (is (= :yin.k/undecodable
           (:yin.k/status
             (ucf.remote/lift-frame serve! resources
                                    [good {:reason :bogus}])))
        "an unrecognized reason is total, S7.5.4")
    (is (= :yin.k/unsatisfied
           (:yin.k/status
             (ucf.remote/lift-frame (constantly nil) resources [good]))))))


;; =============================================================================
;; The remaining S7.4.3 variants round-trip, over the engine's shapes
;; =============================================================================

(deftest a-blocked-read-round-trips
  (let [serve! (stub-serve! chan)
        attach! (stub-attach!)
        resources {:yin/s [:handle :s],
                   :yin/c {:stream-id :yin/s, :cursor :kept-position}}
        entry {:reason :next,
               :cursor-ref {:type :cursor-ref, :id :yin/c},
               :stream-id :yin/s}
        lifted (ucf.remote/lift-pending serve! resources entry)]
    (is (= {:yin.k/reason :next, :yin.k/cell :yin.k/c-0}
           (:yin.k/pending lifted))
        "the cursor CELL id, S7.5.3 -- not a bare position")
    (is (= :kept-position
           (get-in lifted [:yin.k/cells :yin.k/c-0 :yin.k/position])))
    (is (= #{:dao.stream.remote/v1}
           (get-in lifted [:yin.k/requires :yin.k/cursor-profiles])))
    (let [ctx {:segment 0, :pc 7, :env {}, :stack [], :k []}
          lowered (ucf.remote/lower-pending attach! (:yin.k/pending lifted)
                                            (:yin.k/cells lifted) ctx {})
          e (:entry lowered)]
      (is (= :next (:reason e)))
      (is (= 7 (:pc e)) "the registers ride the lower")
      (is (contains? (:resources lowered) (get-in e [:cursor-ref :id]))
          "the wait polls its own fresh cursor entry")
      (is (contains? (:resources lowered) (:stream-id e)))
      (is (= :kept-position
             (get-in lowered [:resources (get-in e [:cursor-ref :id])
                              :cursor]))))))


(deftest put-round-trips
  (let [serve! (stub-serve! chan)
        attach! (stub-attach!)
        resources {:yin/w [:handle :w]}
        entry {:reason :put, :stream-id :yin/w, :datom "hello"}
        lifted (ucf.remote/lift-pending serve! resources entry)]
    (is (= :put (get-in lifted [:yin.k/pending :yin.k/reason])))
    (is (= "hello" (get-in lifted [:yin.k/pending :yin.k/value]))
        "the retained :datom -- a retry appends THIS")
    (is (empty? (:yin.k/cells lifted))
        "a put wait names a stream, never a cursor cell")
    (is (empty? (:yin.k/requires lifted)))
    (let [ctx {:segment 0, :pc 3, :env {}, :stack [], :k []}
          lowered (ucf.remote/lower-pending attach! (:yin.k/pending lifted)
                                            (:yin.k/cells lifted) ctx {})
          e (:entry lowered)]
      (is (= :put (:reason e)))
      (is (= "hello" (:datom e)))
      (is (contains? (:resources lowered) (:stream-id e))
          "the target is a fresh reflection resource"))))


(deftest ffi-sent-round-trips
  (let [serve! (stub-serve! chan)
        attach! (stub-attach!)
        resources {:yin/call-out [:handle :call-out],
                   :yin/call-out-cursor
                   {:stream-id :yin/call-out, :cursor 5}}
        entry {:reason :next, :call-id :call-7, :op :op/add,
               :cursor-ref {:type :cursor-ref, :id :yin/call-out-cursor},
               :stream-id :yin/call-out}
        lifted (ucf.remote/lift-pending serve! resources entry)
        pending (:yin.k/pending lifted)]
    (is (= :ffi (:yin.k/reason pending)))
    (is (= :call-7 (:yin.k/call-id pending)))
    (is (= :op/add (:yin.k/op pending)))
    (is (= :yin.k/c-0 (:yin.k/cell pending)))
    (is (= 5 (get-in lifted [:yin.k/cells :yin.k/c-0 :yin.k/position]))
        "the response cell at its KEPT position")
    (let [ctx {:segment 0, :pc 9, :env {}, :stack [], :k []}
          lowered (ucf.remote/lower-pending attach! pending
                                            (:yin.k/cells lifted) ctx {})
          e (:entry lowered)]
      (is (= :next (:reason e)) "the response wait is a :next waiter")
      (is (= :call-7 (:call-id e)))
      (is (= :op/add (:op e)))
      (is (contains? (:resources lowered) (get-in e [:cursor-ref :id]))
          "the response wait polls its own fresh cursor entry")
      (is (= 5 (get-in lowered [:resources (get-in e [:cursor-ref :id])
                                :cursor]))))))


(deftest ffi-retained-round-trips
  (let [serve! (stub-serve! chan)
        attach! (stub-attach!)
        ;; a producer's own extra envelope key -- legal, the open
        ;; request protocol ignores unknown keys
        ;; (`dao.stream.apply`'s `request?`) -- must survive the
        ;; round trip, which no rebuild from the op/args views gives
        envelope (assoc (apply2/request :call-7 :op/add [1 2])
                        :producer/nonce "n-1")
        resources {vm/call-in-stream-key [:handle :call-in],
                   vm/call-out-stream-key [:handle :call-out],
                   vm/call-out-cursor-key
                   {:stream-id vm/call-out-stream-key, :cursor 2}}
        entry {:reason :put, :request-sent true, :call-id :call-7,
               :op :op/add, :stream-id vm/call-in-stream-key,
               :datom envelope}
        lifted (ucf.remote/lift-pending serve! resources entry)
        pending (:yin.k/pending lifted)]
    (is (= :ffi-request (:yin.k/reason pending)))
    (is (= :call-7 (:yin.k/call-id pending)))
    (is (= :op/add (:yin.k/request-op pending)))
    (is (= [1 2] (:yin.k/request-args pending)))
    (is (= envelope (:yin.k/request-envelope pending))
        "the envelope rides VERBATIM -- op/args are derived views
         beside it, and the producer's extra key survives")
    (is (= "n-1" (:producer/nonce (:yin.k/request-envelope pending)))
        "a key a constructor-made op/args envelope would not have")
    (is (contains? pending :yin.k/request))
    (is (contains? pending :yin.k/response)
        "both endpoint markers ride beside the retained envelope")
    (is (= :yin.k/c-0 (:yin.k/response-cell pending))
        "the response route rides as a cell")
    (is (= (get-in pending [:yin.k/response :dao.stream/identity])
           (get-in lifted [:yin.k/cells :yin.k/c-0 :yin.k/stream
                           :dao.stream/identity]))
        "the response endpoint's marker is the route cell's stream")
    (is (= 2 (get-in lifted [:yin.k/cells :yin.k/c-0 :yin.k/position]))
        "the route cell at the emitter's kept call-out position")
    (is (= #{:dao.stream.remote/v1}
           (get-in lifted [:yin.k/requires :yin.k/cursor-profiles]))
        "the frame carries a cursor profile: the route is a cell")
    (let [ctx {:segment 0, :pc 5, :env {}, :stack [], :k []}
          lowered (ucf.remote/lower-pending attach! pending
                                            (:yin.k/cells lifted)
                                            ctx
                                            #{:yin.k/k-1 :yin.k/k-2})
          e (:entry lowered)
          rs (:resources lowered)]
      (is (= :put (:reason e)))
      (is (true? (:request-sent e)))
      (is (= :call-7 (:call-id e)))
      (is (= envelope (:datom e))
          "the CARRIED envelope, verbatim -- the producer's extra
           key intact, not an op/args rebuild")
      (is (contains? rs (:stream-id e))
          "the retry writes through the carried request reflection")
      (is (not= vm/call-in-stream-key (:stream-id e))
          "never the receiver's own call-in key")
      (is (not (contains? rs vm/call-in-stream-key)))
      (is (not (contains? rs vm/call-out-stream-key)))
      (is (not (contains? rs vm/call-out-cursor-key))
          "no route is ever installed at a fixed key")
      (is (not (contains? #{:yin.k/k-1 :yin.k/k-2}
                          (:response-cursor e)))
          "occupied keys were regenerated away")
      (let [route (get rs (:response-cursor e))]
        (is (some? route)
            "the response cell lowers under its own allocated key")
        (is (= 2 (:cursor route))
            "at the kept response position")
        (is (= (:response-stream e) (:stream-id route))
            "naming the carried response reflection")
        (is (contains? rs (:response-stream e))
            "which is attached")
        (is (not= vm/call-out-stream-key (:response-stream e))
            "never a fixed key")))))


(deftest link-request-round-trips
  (let [serve! (stub-serve! chan)
        attach! (stub-attach!)
        envelope {:yin.link/id [:t0 7], :yin.link/name 'foo,
                  :yin.link/format :yin.debruijn.code,
                  :yin.link/contract "b2"}
        resources {module/link-request-resource [:handle :req],
                   module/link-response-resource [:handle :resp]}
        entry {:reason :link-request, :link-id [:t0 7], :name 'foo,
               :envelope envelope,
               :request module/link-request-resource,
               :response module/link-response-resource,
               :cursor :dao.stream/newest}
        lifted (ucf.remote/lift-pending serve! resources entry)
        pending (:yin.k/pending lifted)]
    (is (= :link-request (:yin.k/reason pending)))
    (is (= [:t0 7] (:yin.k/link-id pending)))
    (is (= 'foo (:yin.k/name pending)))
    (is (= envelope (:yin.k/envelope pending)))
    (is (= :dao.stream/newest
           (get-in lifted [:yin.k/cells :yin.k/c-0 :yin.k/position]))
        "minted newest before the request was appended")
    (let [ctx {:segment 0, :pc 2, :env {}, :stack [], :k []}
          lowered (ucf.remote/lower-pending attach! pending
                                            (:yin.k/cells lifted) ctx {})
          e (:entry lowered)
          rs (:resources lowered)]
      (is (= :link-request (:reason e)))
      (is (= envelope (:envelope e)))
      (is (= :dao.stream/newest (:cursor e)))
      (is (= module/link-request-resource (:request e))
          "the retry appends at the engine's fixed request key")
      (is (= module/link-response-resource (:response e))
          "the poll reads at the engine's fixed response key")
      (is (contains? rs (:request e)))
      (is (contains? rs (:response e))
          "the pair's reflections stand under the fixed keys")
      (let [[_ route] (first (filter (fn [[_ v]]
                                       (and (map? v)
                                            (contains? v :stream-id)))
                                     rs))]
        (is (= :dao.stream/newest (:cursor route))
            "the response cell lowers as its own fresh cursor entry")
        (is (= (get rs (:response e)) (get rs (:stream-id route)))
            "onto the same reflection")))))


(deftest link-response-round-trips
  (let [serve! (stub-serve! chan)
        attach! (stub-attach!)
        resources {module/link-request-resource [:handle :req],
                   module/link-response-resource [:handle :resp]}
        entry {:reason :link-response, :link-id [:t0 7], :name 'foo,
               :request module/link-request-resource,
               :response module/link-response-resource,
               :cursor 3}
        lifted (ucf.remote/lift-pending serve! resources entry)
        pending (:yin.k/pending lifted)]
    (is (= :link-response (:yin.k/reason pending)))
    (is (= [:t0 7] (:yin.k/link-id pending)))
    (is (= 'foo (:yin.k/name pending)))
    (is (= 3 (get-in lifted [:yin.k/cells :yin.k/c-0 :yin.k/position])))
    (let [ctx {:segment 0, :pc 2, :env {}, :stack [], :k []}
          lowered (ucf.remote/lower-pending attach! pending
                                            (:yin.k/cells lifted) ctx {})
          e (:entry lowered)
          rs (:resources lowered)]
      (is (= :link-response (:reason e)))
      (is (= 3 (:cursor e)))
      (is (= module/link-request-resource (:request e))
          "the pair fields name the engine's fixed keys")
      (is (= module/link-response-resource (:response e)))
      (is (contains? rs (:response e))
          "the poll finds its reader at the fixed response key")
      (is (contains? e :request) "the pair fields the poll keeps")
      (is (contains? e :response))
      (is (not (contains? e :envelope))
          "an appended request carries its envelope no more"))))


(deftest install-passes-through-unchanged
  (let [entry {:reason :install, :name 'foo}
        lifted (ucf.remote/lift-pending (constantly nil) {} entry)]
    (is (= {:yin.k/reason :install, :yin.k/name 'foo}
           (:yin.k/pending lifted)))
    (is (empty? (:yin.k/cells lifted)))
    (let [lowered (ucf.remote/lower-pending
                    (constantly nil) (:yin.k/pending lifted) {}
                    {:segment 0, :pc 3} {})]
      (is (= {:reason :install, :name 'foo, :segment 0, :pc 3}
             (:entry lowered))))))


(deftest an-unrecognized-reason-is-undecodable
  (is (= :yin.k/undecodable
         (:yin.k/status (ucf.remote/lift-pending
                          (constantly nil) {} {:reason :bogus}))))
  (is (= :yin.k/undecodable
         (:yin.k/status (ucf.remote/lower-pending
                          (constantly nil) {:yin.k/reason :bogus} {}
                          {} {})))))


;; =============================================================================
;; The ruling's fresh-key rule and endpoint refusals
;; =============================================================================

(defn- refusing-serve!
  "A `serve!` that refuses `refused` and serves every other handle
   under `chan` with a stable identity."
  [refused]
  (let [ids (atom {})]
    (fn [h]
      (when (not= h refused)
        (let [id (or (get @ids h)
                     (let [n (str (gensym "seg"))]
                       (swap! ids assoc h n)
                       n))]
          {:dao.stream/identity id, :dao.stream/channel chan})))))


(defn- retained-resources
  "The emitter-side table of a retained call over `call-in` and
   `call-out`, the call-out cursor kept at `position`."
  [call-in call-out position]
  {vm/call-in-stream-key call-in,
   vm/call-out-stream-key call-out,
   vm/call-out-cursor-key {:stream-id vm/call-out-stream-key,
                           :cursor position}})


;; the retained envelope carries a producer key the op/args views do
;; not name -- legal per the open request protocol -- so the pins
;; below see one an op/args rebuild would drop
(def ^:private retained-entry
  {:reason :put, :request-sent true, :call-id :call-7,
   :op :op/add, :stream-id vm/call-in-stream-key,
   :datom (assoc (apply2/request :call-7 :op/add [1 2])
                 :producer/nonce "n-1")})


(deftest retained-lift-refuses-when-an-endpoint-cannot-be-served
  (let [call-in (one-slot-stream "call-in")
        call-out (one-slot-stream "call-out")
        resources (retained-resources call-in call-out 2)]
    (testing "an unservable request endpoint names it"
      (let [refusal (ucf.remote/lift-pending
                      (refusing-serve! call-in) resources retained-entry)]
        (is (= :yin.k/unsatisfied (:yin.k/status refusal)))
        (is (= "call-in" (:dao.stream/identity refusal))
            "naming that endpoint's identity")
        (is (empty? (:yin.k/cells refusal))
            "no cell is published")))
    (testing "an unservable response endpoint names it"
      (let [refusal (ucf.remote/lift-pending
                      (refusing-serve! call-out) resources retained-entry)]
        (is (= :yin.k/unsatisfied (:yin.k/status refusal)))
        (is (= "call-out" (:dao.stream/identity refusal))
            "naming that endpoint's identity")
        (is (empty? (:yin.k/cells refusal))
            "no cell is published")))))


(deftest retained-lower-refuses-when-an-endpoint-cannot-attach
  (let [serve! (stub-serve! chan)
        resources (retained-resources (one-slot-stream "call-in")
                                      (one-slot-stream "call-out") 2)
        lifted (ucf.remote/lift-pending serve! resources retained-entry)
        pending (:yin.k/pending lifted)
        req-id (get-in pending [:yin.k/request :dao.stream/identity])
        resp-id (get-in pending [:yin.k/response :dao.stream/identity])
        attach-but! (fn [refused]
                      (fn [d]
                        (when (not= refused (:dao.stream/identity d))
                          {:dao.stream/outcome :dao.stream/ok,
                           :dao.stream/handle
                           [:reflection-of (:dao.stream/identity d)]})))]
    (testing "a failed REQUEST attachment names the request identity"
      (let [lowered (ucf.remote/lower-pending (attach-but! req-id)
                                              pending
                                              (:yin.k/cells lifted)
                                              {:segment 0, :pc 5} {})]
        (is (= :yin.k/unsatisfied (:yin.k/status lowered))
            "the request reflection unservable refuses the lower")
        (is (= req-id (:dao.stream/identity lowered))
            "naming the REQUEST endpoint's identity")))
    (testing "a failed RESPONSE attachment names the response identity"
      (let [lowered (ucf.remote/lower-pending (attach-but! resp-id)
                                              pending
                                              (:yin.k/cells lifted)
                                              {:segment 0, :pc 5} {})]
        (is (= :yin.k/unsatisfied (:yin.k/status lowered))
            "the response reflection unservable refuses the lower")
        (is (= resp-id (:dao.stream/identity lowered))
            "naming the RESPONSE endpoint's identity")))
    (is (contains? pending :yin.k/request)
        "both route markers and the cell survive in the UCF entry")
    (is (contains? pending :yin.k/response))
    (is (contains? pending :yin.k/response-cell))))


(deftest retained-lower-refuses-a-pend-without-its-envelope
  (let [serve! (stub-serve! chan)
        resources (retained-resources (one-slot-stream "call-in")
                                      (one-slot-stream "call-out") 2)
        lifted (ucf.remote/lift-pending serve! resources retained-entry)
        ;; lift always sets the envelope, so a pend without one is
        ;; malformed -- it must refuse, not rebuild from the views
        pending (dissoc (:yin.k/pending lifted) :yin.k/request-envelope)
        req-id (get-in pending [:yin.k/request :dao.stream/identity])
        attached (atom 0)
        attach! (let [stub (stub-attach!)]
                  (fn [d] (swap! attached inc) (stub d)))
        lowered (ucf.remote/lower-pending attach! pending
                                          (:yin.k/cells lifted)
                                          {:segment 0, :pc 5} {})]
    (is (= :yin.k/unsatisfied (:yin.k/status lowered))
        "no silent rebuild from the op/args views: the carried
         envelope is the only `:datom` a retry appends")
    (is (= req-id (:dao.stream/identity lowered))
        "naming the request identity")
    (is (zero? @attached)
        "the refusal precedes any attachment: a malformed frame
         mints no reflection")))


(deftest two-separately-lowered-frames-get-distinct-fresh-keys
  (let [serve! (stub-serve! chan)
        attach! (stub-attach!)
        s (ring 8)
        _ (stream/append! s "v")
        c0 (:dao.stream/cursor (stream/cursor s :dao.stream/oldest))
        resources {:yin/s s, :yin/c {:stream-id :yin/s, :cursor c0}}
        entry {:reason :next,
               :cursor-ref {:type :cursor-ref, :id :yin/c},
               :stream-id :yin/s}
        ctx {:segment 0, :pc 1, :env {}, :stack [], :k []}
        lifted (ucf.remote/lift-pending serve! resources entry)
        pending (:yin.k/pending lifted)
        cells (:yin.k/cells lifted)
        one (ucf.remote/lower-frame attach! [pending] cells [ctx] {})
        one-keys (set (keys (:resources one)))
        two (ucf.remote/lower-frame attach! [pending] cells [ctx]
                                    one-keys)
        ;; a receiver that already holds the keys this batch would
        ;; propose first: the allocator regenerates past them
        three (ucf.remote/lower-frame attach! [pending] cells [ctx]
                                      (into one-keys
                                            #{:yin.k/k-10 :yin.k/k-11
                                              :yin.k/k-12}))
        cursor-key (fn [frame]
                     (get-in (first (:entries frame))
                             [:cursor-ref :id]))]
    (testing "equal cell ids in separate batches get different keys"
      (is (= :yin.k/c-0 (:yin.k/cell pending))
          "each lift restarts its cell ids")
      (is (not= (cursor-key one) (cursor-key two)))
      (is (every? #(not (contains? one-keys %)) (set (keys (:resources two))))
          "no key is shared between the two lowerings"))
    (testing "collisions against pre-existing resources regenerate"
      (is (every? #(not (contains? (set (keys (:resources three))) %))
                  #{:yin.k/k-10 :yin.k/k-11 :yin.k/k-12})
          "the pre-held keys were regenerated away")
      (is (not= (cursor-key three) (cursor-key one))))
    (testing "every lowered frame keeps a working cursor entry"
      (doseq [frame [one two three]]
        (let [cursor (get (:resources frame) (cursor-key frame))]
          (is (= c0 (:cursor cursor))
              "at the kept position, on its own reflection")
          (is (contains? (:resources frame) (:stream-id cursor))
              "naming an attached reflection"))))))
