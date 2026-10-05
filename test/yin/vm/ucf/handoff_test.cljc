(ns yin.vm.ucf.handoff-test
  "Stage 1 of the post-M5 hardening (docs/design/yin.vm.linker.dht.md
   14.1.3): the kept-cursor rows over ACTUAL canonical bytes.

   Every proof here exports a real machine parked through the engine,
   moves the body as CBOR bytes, and lowers into a fresh machine of
   the same composition: the three same-host pairs of 14.1.1's matrix
   are these proofs run on their own lanes (JVM here, Node and Dart on
   theirs), with the toy channel of `yin.vm.ucf.remote-test` as the
   in-process transport seam and a served table as the remote table.
   The six cross-host pairs of the nine-pair matrix are NOT covered
   here: they need a handoff peer program per host and process
   transport beside this namespace, which stage 1 records as its
   outstanding coverage gap rather than inferring from same-host
   bytes (14.1.3: process transport is recorded separately).

   Row 1 aliases one shared cell in two ordered waiters, a closure and
   the store, beside two independent cells at one position.  Row 2
   runs every safepoint kind of 14.1.1's table -- a blocked read at
   two activation depths, a blocked write, a sent and a retained FFI
   call, a retained and a polling link wait, an install waiter beside
   its whole child, an explicit park and a halt -- each with parity
   against the source's own resumed run.  Row 3 interleaves unrelated,
   duplicate and late responses around the target.  Row 4 evicts, ends
   a route, and omits the cursor profile.  Row 5 is the refusal
   battery."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.stream.cbor :as cbor]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.ucf.handoff :as handoff]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; The composition: machines, the toy channel, the served table
;; =============================================================================

(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key capacity})))


(defn- make-ring-stream
  [capacity]
  (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                       ringbuffer/capacity-key capacity}))


(defn- new-machine
  []
  (semantic/create-vm {:make-stream make-ring-stream
                       :capability-secret tu/secret
                       :modules (module/default-registry)
                       :secret-source (fn [origin]
                                        (str tu/secret "/"
                                             (name origin)))}))


(def ^:private semantic-vector
  "The direct path's canonical vector of `ast` -- the loader that
   addresses what it loads (`load-vector` records the vector's own
   address in the alias column, where the AST batch path records
   none), so every segment a handoff names is portable."
  (comp :vector linearize/lower-rows vm/ast->semantic-bytecode))


(defn- load-ast
  [machine ast]
  (semantic/load-vector machine (semantic-vector ast)
                        vm/semantic-contract))


(defn- toy
  []
  (let [ab (ring 64)
        ba (ring 64)
        cd {:dao.stream/type :dao.stream.test/channel
            :dao.stream/identity "ucf-handoff-toy"}]
    {:channel cd, :ab ab, :ba ba,
     :a-end {:reader ba, :writer ab},
     :b-end {:reader ab, :writer ba}}))


(defn- served-peer
  [t]
  {:table (atom {})
   :b-reader (:reader (:b-end t))
   :b-writer (:writer (:b-end t))
   :mirror (atom (:dao.stream/cursor
                   (stream/cursor (:reader (:b-end t))
                                  :dao.stream/oldest)))})


(defn- serve-mirror!
  [peer]
  (swap! (:mirror peer)
         #(remote/mirror-step @(:table peer) (:b-reader peer) %
                              (:b-writer peer))))


(defn- server
  "The exporter's `serve!`: a stable identity per handle, entering it
   in the peer's table under the toy channel."
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


(defn- one-slot-stream
  "A stream that answers `full` while one value sits in it; reading the
   value frees the slot."
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


;; =============================================================================
;; AST helpers and the programs that park each way
;; =============================================================================

(defn- lit
  [x]
  {:type :literal, :value x})


(defn- v
  [n]
  {:type :variable, :name n})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [f & args]
  {:type :application, :operator f, :operands (vec args)})


(defn- def!
  [k val]
  (app (v 'yin/def) (lit k) val))


(defn- then
  [first-ast second-ast]
  (app (lam ['_] second-ast) first-ast))


(defn- cursor-of
  [source]
  {:type :stream/cursor, :source source})


(defn- next-of
  [source]
  {:type :stream/next, :source source})


(defn- let1
  [param init body]
  (app (lam [param] body) init))


(defn- nested-reads
  "`depth` nested ordinary calls around two reads of the cursor bound
   to `name`: the first answers A, the second parks."
  [depth name]
  (letfn [(go
            [d]
            (if (zero? d)
              (then (next-of (v name)) (next-of (v name)))
              (app (lam ['x] (go (dec d))) (lit 1))))]
    (go depth)))


(defn- reader-at-depth
  "A program that parks on a blocked read of the stream it makes:
   the cursor is passed as a closure parameter (so the parked frame's
   captured environment holds the cell -- the alias row's closure),
   inside `depth` nested ordinary calls.  With `held`, the store also
   holds the cursor under 'held."
  [depth held]
  (let1 's {:type :stream/make, :buffer 4}
        (let [read (app (lam ['c] (nested-reads depth 'c))
                        (if held (v 'held) (cursor-of (v 's))))]
          (if held
            (then (def! 'held (cursor-of (v 's))) read)
            read))))


(defn- parked-machine
  [program-ast]
  (vm/run (load-ast (new-machine) program-ast)))


(defn- halted-machine
  [ast]
  (vm/run (load-ast (new-machine) ast)))


(defn- stream-of
  "The stream handle a parked reader's wait entry polls."
  [parked]
  (let [entry (first (:wait-set parked))]
    (get (:resources parked) (:stream-id entry))))


(defn- parked-reader
  "A real machine parked on a blocked read with A consumed: park on the
   empty stream, append A, run on -- the read answers, the program
   parks again on the second read, its cursor past A."
  [depth held]
  (let [m0 (parked-machine (reader-at-depth depth held))
        src (stream-of m0)]
    (stream/append! src "A")
    [(vm/run m0) src]))


(defn- parked-writer
  "A real machine parked on a blocked write: the warm one-slot stream
   keeps the value retained."
  []
  (let [w (one-slot-stream "w")]
    (stream/append! w :warmed)
    (let [m0 (load-ast (new-machine)
                       {:type :stream/put, :target (v 'w),
                        :val (lit "v")})
          [ref m0'] (engine/attach-resource m0 w)]
      [(vm/run (assoc m0' :store {'w ref})) w])))


(defn- calling-machine
  ([call-in call-out]
   (calling-machine call-in call-out nil))
  ([call-in call-out bridge]
   (semantic/create-vm
     {:make-stream make-ring-stream,
      :capability-secret tu/secret,
      :modules (module/default-registry),
      :secret-source (fn [origin] (str tu/secret "/" (name origin))),
      :call-in call-in,
      :call-out call-out,
      :call-out-cursor (vm/mint-oldest call-out :test),
      :bridge bridge})))


(defn- call-node
  [op arg]
  {:type :dao.stream.apply/call, :op op, :operands [(lit arg)]})


(defn- parked-sent-caller
  []
  (let [call-in (ring 8)
        call-out (ring 8)
        program (then (call-node :op/echo "hello")
                      (call-node :op/echo "world"))
        parked (vm/run (load-ast (calling-machine call-in call-out)
                                 program))]
    [parked call-in call-out]))


(defn- parked-retained-caller
  []
  (let [call-in (one-slot-stream "call-in")
        call-out (ring 8)
        _ (stream/append! call-in :warmed)
        program (then (call-node :op/echo "hello")
                      (call-node :op/echo "world"))
        parked (vm/run (load-ast (calling-machine call-in call-out)
                                 program))]
    [parked call-in call-out]))


(defn- linking-machine
  [request response]
  (semantic/create-vm
    {:make-stream make-ring-stream,
     :capability-secret tu/secret,
     :modules (module/default-registry),
     :secret-source (fn [origin] (str tu/secret "/" (name origin))),
     :link-request request,
     :link-response response}))


(defn- require-program
  [name]
  (app (v 'require) (lit name)))


(defn- parked-link-request
  []
  (let [request (one-slot-stream "link-request")
        response (ring 64)
        _ (stream/append! request :warmed)
        parked (vm/run (load-ast (linking-machine request response)
                                 (require-program
                                   'host.the-module)))]
    [parked]))


(defn- parked-link-response
  []
  (let [request (ring 64)
        response (ring 64)
        parked (vm/run (load-ast (linking-machine request response)
                                 (require-program
                                   'host.the-module)))]
    [parked]))


(defn- module-response
  []
  {:status :ok,
   :image {:value (semantic-vector
                    (let1 's {:type :stream/make, :buffer 4}
                          (then (next-of (cursor-of (v 's)))
                                (def! 'f (lam [] (lit 42))))))},
   :manifest {:yin.module/name 'host.mod,
              :yin.module/exports #{'f}},
   :obligations []})


(defn- parked-installer
  "A real machine parked at :install with a live child: the response is
   appended after the first park, and the spawned child blocks on its
   own read.  With `plain`, the program reads nothing of the module
   after the require.  Answers [parked child-stream]."
  ([] (parked-installer false))
  ([plain]
   (let [request (ring 64)
         response (ring 64)
         m0 (vm/run (load-ast (linking-machine request response)
                              (then (require-program 'host.mod)
                                    (if plain
                                      (lit 7)
                                      (app (v 'host.mod/f))))))
         entry (first (:wait-set m0))
         _ (stream/append! response
                           (assoc (module-response)
                                  :yin.link/id (:link-id entry)))
         parked (vm/run m0)
         child (get-in parked [:installs 'host.mod :vm])
         child-stream (get (:resources child)
                           (:stream-id (first (:wait-set child))))]
     [parked child-stream])))


(defn- parked-explicit
  "A real machine at an explicit park, its record's environment holding
   a cursor on a blocked stream, so reachable cells keep positions."
  []
  (vm/run (load-ast (new-machine)
                    (let1 's {:type :stream/make, :buffer 4}
                          (app (lam ['c] {:type :vm/park})
                               (cursor-of (v 's)))))))


;; =============================================================================
;; The transfer and the drivers
;; =============================================================================

(defn- drive-local
  "Run a blocked machine against its own local streams until it stops
   blocking or rounds run out: the reference run."
  ([m] (drive-local m 64))
  ([m rounds]
   (loop [m (vm/run m), n rounds]
     (if (or (not (vm/blocked? m)) (zero? n))
       m
       (recur (vm/run m) (dec n))))))


(defn- drive
  "Run a resumed machine through its reflections, stepping the source's
   mirror between rounds, until it stops blocking."
  ([m peer] (drive m peer 64))
  ([m peer rounds]
   (loop [m (vm/run m), n rounds]
     (if (or (not (vm/blocked? m)) (zero? n))
       m
       (do (serve-mirror! peer)
           (recur (vm/run m) (dec n)))))))


(defn- keywords-of
  [x]
  (into #{}
        (filter keyword?)
        (tree-seq coll? (fn [n]
                          (if (map? n)
                            (concat (keys n) (vals n))
                            (seq n))) x)))


(defn- encoded-cell-ids
  "Every cell id the encoded value's cursor-ref markers name."
  [x]
  (into #{}
        (comp (filter #(and (map? %)
                            (= :yin.k/cursor-ref (:yin.k/tag %))))
              (map :yin.k/cell))
        (tree-seq coll? (fn [n] (if (map? n) (vals n) (seq n))) x)))


(defn- source-resource-keys
  [vm-m]
  (into #{} (filter keyword?)
        (concat (keys (:resources vm-m))
                (mapcat (fn [r]
                          (when (map? r) [(:stream-id r)]))
                        (vals (:resources vm-m))))))


(defn- parked-frame
  [export]
  (get-in export [:body :yin.k/frames 0 :yin.k/pending]))


;; =============================================================================
;; Row 1: aliasing across the transfer
;; =============================================================================

(deftest shared-and-independent-cells-cross-as-bytes
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t))
        [parked src] (parked-reader 1 true)
        entry (first (:wait-set parked))
        cid (get-in entry [:cursor-ref :id])
        sid (:stream-id entry)
        kept (get-in parked [:resources cid :cursor])
        held (engine/issue-ref parked :cursor-ref cid)
        parked (-> parked
                   (assoc-in [:store 'held] held)
                   (update :resources assoc
                           :yin/i1 {:stream-id sid, :cursor kept}
                           :yin/i2 {:stream-id sid, :cursor kept}))
        base (first (:wait-set parked))
        indep (fn [id]
                (-> base
                    (assoc :cursor-ref {:type :cursor-ref, :id id})))
        waiter-2 base
        parked (assoc parked
                      :wait-set [base waiter-2
                                 (indep :yin/i1)
                                 (indep :yin/i2)])
        export (handoff/export-task parked serve!)
        body (:body export)
        resume (handoff/resume-task (new-machine) (:bytes export)
                                    (attacher t))
        recv (:vm resume)]
    (testing "the export is canonical bytes under their address"
      (is (= :ok (:status export)) (pr-str export))
      (is (cbor/byte-payload? (:bytes export)))
      (is (= body (cbor/decode (:bytes export)))
          "the bytes are the canonical encoding of the body")
      (let [address (:address export)
            algo (:algorithm (jing/parse-segment-address address))]
        (is (jing/segment-address? address)
            "the address is a jing segment address")
        (is (= (:digest (jing/parse-segment-address address))
               (jing/digest-bytes algo (:bytes export)))
            "the digest is recomputed from the emitted bytes")
        (is (jing/segment-bytes-match? address (:bytes export))
            "a receiver verifying the fetched bytes accepts the address")
        (is (not (jing/segment-bytes-match?
                   address
                   (cbor/encode (assoc body :yin.k/id-counter -1))))
            "other bytes do not verify under the address")))
    (testing "one cell for the waiters, the closure and the store;
              two more for the independents"
      (is (= 3 (count (:yin.k/cells body)))
          (pr-str (:yin.k/cells body)))
      (let [pendings (mapv :yin.k/pending (:yin.k/frames body))
            shared (:yin.k/cell (first pendings))
            env-cells (into #{}
                            (mapcat encoded-cell-ids)
                            (map :yin.k/env
                                 (map :yin.k/registers
                                      (:yin.k/frames body))))
            store-cell (get-in body [:yin.k/store 'held :yin.k/cell])]
        (is (= shared (:yin.k/cell (second pendings)))
            "both shared waiters name the one cell")
        (is (contains? env-cells shared)
            "the closure's captured environment names the same cell")
        (is (= shared store-cell)
            "the store's held cursor is the same cell")
        (is (= kept (get-in body [:yin.k/cells shared :yin.k/position]))
            "the cell sits at the source-minted kept position")
        (is (= 3 (count (into #{} (map :yin.k/cell) pendings)))
            "the two independents are their own cells beside it")))
    (testing "no source handle, id or seal crosses"
      (is (empty? (set/intersection
                    (keywords-of body)
                    (source-resource-keys parked)))))
    (testing "the lower shares one fresh cursor entry across the
              waiters, the closure and the store"
      (is (= :ok (:status resume)) (pr-str resume))
      (let [[e1 e2 e3 e4] (:wait-set recv)
            k1 (get-in e1 [:cursor-ref :id])
            held' (get-in recv [:store 'held])]
        (is (= k1 (get-in e2 [:cursor-ref :id])))
        (is (not= k1 (get-in e3 [:cursor-ref :id])))
        (is (not= (get-in e3 [:cursor-ref :id])
                  (get-in e4 [:cursor-ref :id])))
        (is (= k1 (:id held'))
            "the store's re-sealed cursor-ref names the shared entry")
        (is (= kept (:cursor (get (:resources recv) k1))))
        (is (= 3 (count (into #{} (map (fn [e]
                                         (get-in e [:cursor-ref :id])))
                              (:wait-set recv))))
            "three cells lower to three fresh cursor entries")))
    (testing "shared waiters see B then C; independents see B each"
      (stream/append! src "B")
      (stream/append! src "C")
      (let [swept (loop [w (engine/check-wait-set recv) n 12]
                    (if (or (zero? n) (empty? (:wait-set w)))
                      w
                      (do (serve-mirror! peer)
                          (recur (engine/check-wait-set w)
                                 (dec n)))))]
        (is (= ["B" "C" "B" "B"] (mapv :value (:ready-queue swept)))
            "the shared pair advance in order on the one cell; each
             independent reads B at its own position")
        (is (empty? (:wait-set swept))
            "no waiter sees A, and nothing is left waiting")))))


;; =============================================================================
;; Row 2: every declared park, with parity against the reference run
;; =============================================================================

(deftest a-blocked-read-crosses-at-two-depths-and-resumes
  (doseq [[depth frames] [[1 4] [3 6]]]
    (let [t (toy)
          peer (served-peer t)
          serve! (server peer (:channel t))
          [parked src] (parked-reader depth false)
          export (handoff/export-task parked serve!)
          reference (do (stream/append! src "R")
                        (vm/value (drive-local parked)))
          recv (:vm (handoff/resume-task (new-machine)
                                         (:bytes export)
                                         (attacher t)))]
      (testing (str "depth " depth)
        (is (= :ok (:status export)) (pr-str export))
        (is (= frames
               (count (get-in export [:body :yin.k/frames 0
                                      :yin.k/registers :yin.k/k])))
            "the carried K is the depth of ordinary calls")
        (is (= :next (:yin.k/reason (parked-frame export))))
        (is (contains? (get-in export [:body :yin.k/requires
                                       :yin.k/cursor-profiles])
                       :dao.stream.remote/v1))
        (is (= reference (vm/value (drive recv peer)))
            "the resumed task answers what the source's own run did")))))


(deftest a-blocked-write-retries-its-retained-value
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t) #{:reader :writer})
        [parked w] (parked-writer)
        export (handoff/export-task parked serve!)
        reference (do (stream/next w 0)
                      (vm/value (drive-local parked)))
        recv (:vm (handoff/resume-task (new-machine)
                                       (:bytes export)
                                       (attacher t)))
        done (drive recv peer)]
    (is (= :ok (:status export)) (pr-str export))
    (is (= :put (:yin.k/reason (parked-frame export))))
    (is (= "v" (:yin.k/value (parked-frame export))))
    (is (= reference (vm/value done))
        "the retry appends the retained value through the reflection")
    (is (= "v" (:dao.stream/value (stream/next w 0))))))


(deftest a-sent-ffi-call-keeps-its-cell-and-correlation
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t))
        [parked call-in call-out] (parked-sent-caller)
        entry (first (:wait-set parked))
        export (handoff/export-task parked serve!)
        reference (do (stream/append! call-out
                                      (apply2/success-response
                                        (:call-id entry) "echo!"))
                      (vm/value (drive-local parked)))
        after-reference (count (tu/drain call-in))
        ;; the receiver's own pair serves the program's NEXT call
        own-in (ring 8)
        own-out (ring 8)
        recv (:vm (handoff/resume-task (calling-machine own-in own-out)
                                       (:bytes export)
                                       (attacher t)))
        done (drive recv peer)]
    (is (= :ok (:status export)) (pr-str export))
    (is (= :ffi (:yin.k/reason (parked-frame export))))
    (is (= (:call-id entry) (:yin.k/call-id (parked-frame export))))
    (is (= #{:dao.stream.remote/v1}
           (get-in export [:body :yin.k/requires
                           :yin.k/cursor-profiles])))
    (is (= reference (vm/value done)))
    (is (= after-reference (count (tu/drain call-in)))
        "no sent request is ever reissued: the source's stream saw only
         what the source's own runs appended")
    (is (= 1 (count (tu/drain own-in)))
        "the program's next call used the receiver's own pair")))


(deftest a-retained-ffi-request-retries-its-envelope-verbatim
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t) #{:reader :writer})
        [parked call-in _call-out] (parked-retained-caller)
        entry (first (:wait-set parked))
        export (handoff/export-task parked serve!)
        _ (do (stream/next call-in 0)
              (drive-local parked))
        recv (:vm (handoff/resume-task (new-machine)
                                       (:bytes export)
                                       (attacher t)))
        done (drive recv peer)]
    (is (= :ok (:status export)) (pr-str export))
    (is (= :ffi-request (:yin.k/reason (parked-frame export))))
    (is (= (:datom entry)
           (apply hash-map
                  (:yin.k/entries
                    (:yin.k/request-envelope (parked-frame export)))))
        "the retained envelope rides verbatim, wrapped as the literal
         it is")
    (is (vm/blocked? done)
        "the retry stays parked: the emitter's bridge never answered")
    (let [landed (stream/next call-in 0)]
      (is (= :dao.stream/ok (:dao.stream/outcome landed)))
      (is (= (:datom entry) (:dao.stream/value landed))
          "the identical envelope, appended through the reflection"))))


(deftest link-waits-carry-their-envelope-and-kept-cursor
  (let [t (toy)
        serve! (server (served-peer t) (:channel t) #{:reader :writer})
        [parked] (parked-link-request)
        entry (first (:wait-set parked))
        export (handoff/export-task parked serve!)
        recv (:vm (handoff/resume-task (new-machine)
                                       (:bytes export)
                                       (attacher t)))
        lowered (first (:wait-set recv))
        [parked2] (parked-link-response)
        entry2 (first (:wait-set parked2))
        export2 (handoff/export-task parked2 serve!)]
    (is (= :ok (:status export)) (pr-str export))
    (is (= :link-request (:yin.k/reason (parked-frame export))))
    (is (= (:envelope entry)
           (:envelope lowered))
        "the retained envelope rides verbatim, wrapped as the literal
         it is, and the lower unwraps it")
    (is (= module/link-request-resource (:request lowered))
        "the lowered wait parks where the engine retries it")
    (is (= module/link-response-resource (:response lowered))
        "and polls where the engine reads it")
    (is (= :link-response (:yin.k/reason (parked-frame export2))))
    (is (= (:cursor entry2)
           (get-in export2
                   [:body :yin.k/cells
                    (:yin.k/cell (parked-frame export2))
                    :yin.k/position]))
        (str "the polling wait's kept cursor is the cell's position: "
             (pr-str {:cursor (:cursor entry2)
                      :cells (get-in export2 [:body :yin.k/cells])
                      :frame (parked-frame export2)})))))


(deftest an-install-waiter-crosses-beside-its-whole-child
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t))
        [parked child-stream] (parked-installer)
        export (handoff/export-task parked serve!)
        ;; the receiving task composes an attach seam, which the
        ;; install's own slice lowering resolves the child's cells
        ;; through, exactly as the engine's receive-module does
        recv (:vm (handoff/resume-task (assoc (new-machine)
                                              :attach-stream
                                              (attacher t))
                                       (:bytes export)
                                       (attacher t)))]
    (is (= :ok (:status export)) (pr-str export))
    (is (= :install (:yin.k/reason (parked-frame export))))
    (testing "the child's phase and waits travel"
      (let [child (get-in export [:body :yin.k/installs 'host.mod])]
        (is (true? (get (:yin.k/child child) handoff/handoff-tag)))
        (is (= :blocked (:yin.k/kind (:yin.k/child child)))
            "the child is itself parked on its read")))
    (testing "the receiver steps the child and wakes the waiter"
      (is (contains? (:installs recv) 'host.mod))
      (stream/append! child-stream "go")
      (let [done (drive recv peer)]
        (is (not (vm/blocked? done)) (pr-str (:wait-set done)))
        (is (= 42 (vm/value done))
            "the install completed, the waiter woke with the module,
             and the program called its export")
        (is (empty? (:installs done)))))))


(deftest an-explicit-park-is-the-no-wait-shape
  (let [t (toy)
        serve! (server (served-peer t) (:channel t))
        parked (parked-explicit)
        pid (:id (:value parked))
        export (handoff/export-task parked serve!)
        body (:body export)
        recv (:vm (handoff/resume-task (new-machine)
                                       (:bytes export)
                                       (attacher t)))
        resume-prog {:type :vm/resume, :parked-id pid,
                     :val (lit "back")}
        reference (vm/value
                    (vm/run (load-ast parked resume-prog)))]
    (is (= :ok (:status export)) (pr-str export))
    (is (= :parked (:yin.k/kind body)))
    (is (not (contains? body :yin.k/frames))
        "no pending wait is minted for an explicit park")
    (is (contains? (:yin.k/parked body) pid))
    (is (= 1 (count (:yin.k/cells body)))
        "the record's captured cursor keeps its position")
    (is (= (:value recv) (get (:parked recv) pid)))
    (is (= reference
           (vm/value (vm/run (load-ast recv resume-prog))))
        "the parked continuation resumes on the receiver too")))


(deftest a-halt-crosses-as-a-result-with-no-frame
  (let [t (toy)
        serve! (server (served-peer t) (:channel t))
        halted (halted-machine (lit 42))
        export (handoff/export-task halted serve!)
        body (:body export)
        recv (:vm (handoff/resume-task (new-machine)
                                       (:bytes export)
                                       (attacher t)))]
    (is (= :ok (:status export)) (pr-str export))
    (is (= :halted (:yin.k/kind body)))
    (is (not (contains? body :yin.k/frames)))
    (is (= 42 (:yin.k/result body)))
    (is (vm/halted? recv))
    (is (= 42 (vm/value recv)))
    (is (empty? (:wait-set recv)))
    (is (empty? (:parked recv)))))


;; =============================================================================
;; Row 3: interleaved responses around the target
;; =============================================================================

(deftest interleaved-responses-find-the-correlated-one-once
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t))
        [parked call-in call-out] (parked-sent-caller)
        entry (first (:wait-set parked))
        id (:call-id entry)
        export (handoff/export-task parked serve!)
        own-in (ring 8)
        own-out (ring 8)
        recv (:vm (handoff/resume-task
                    (calling-machine own-in own-out
                                     {:op/echo (constantly "local!")})
                    (:bytes export)
                    (attacher t)))
        ;; unrelated, duplicate and late responses around the target,
        ;; appended while the task is in transit
        _ (stream/append! call-out
                          (apply2/success-response :other "noise"))
        _ (stream/append! call-out
                          (apply2/success-response id "echo!"))
        done (drive recv peer)]
    (is (= :ok (:status export)))
    (is (vm/halted? done))
    (is (= "local!" (vm/value done))
        "the kept position found the correlated response once, past
         the unrelated one before it, and the program's next call was
         answered by the receiver's own pair")
    (is (= ["world"]
           (apply2/request-args (first (tu/drain own-in))))
        "the next call's request is on the receiver's own call-in")
    (is (= 1 (count (tu/drain call-in)))
        "the sent request was not reissued")))


;; =============================================================================
;; Row 4: eviction, route loss, profile omission
;; =============================================================================

(deftest eviction-yields-the-sources-gap-and-successor
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t))
        [parked src] (parked-reader 1 false)
        kept (get-in parked [:resources
                             (get-in (first (:wait-set parked))
                                     [:cursor-ref :id])
                             :cursor])
        export (handoff/export-task parked serve!)
        _ (doseq [x ["B" "C" "D" "E" "F"]]
            (stream/append! src x))
        expected (stream/next src kept)
        recv (:vm (handoff/resume-task (new-machine)
                                       (:bytes export)
                                       (attacher t)))
        done (drive recv peer)]
    (is (= :ok (:status export)))
    (is (= :dao.stream/gap (vm/value done))
        "the receiver reads the source's own gap, never a replay")
    (is (= (:dao.stream/cursor expected)
           (get-in done [:resources
                         (get-in (first (:wait-set recv))
                                 [:cursor-ref :id])
                         :cursor]))
        "with the source's exact successor cursor in the cell")))


(deftest a-lost-route-refuses-unsatisfied-naming-the-stream
  (let [t (toy)
        serve! (server (served-peer t) (:channel t))
        [parked _src] (parked-reader 1 false)
        export (handoff/export-task parked serve!)
        identity (get-in export [:body :yin.k/cells :yin.k/c-0
                                 :yin.k/stream :dao.stream/identity])
        attach-gone! (fn [_descriptor]
                       {:dao.stream/outcome :dao.stream/transport-error,
                        :dao.stream.remote/reason
                        :dao.stream.remote/not-found})
        resumed (handoff/resume-task (new-machine)
                                     (:bytes export)
                                     attach-gone!)]
    (is (= :ok (:status export)))
    (is (= :yin.k/unsatisfied (:yin.k/status resumed)))
    (is (= identity (:dao.stream/identity resumed))
        "naming the stream identity, with no anchor fallback")
    (is (not= :ok (:status resumed)))))


(deftest an-omitted-cursor-profile-refuses-before-publication
  (let [t (toy)
        serve! (server (served-peer t) (:channel t))
        [parked _src] (parked-reader 1 false)
        refused (handoff/export-task parked serve!
                                     {:cursor-profiles #{}})]
    (is (= :yin.k/unsatisfied (:yin.k/status refused))
        "a body with cells cannot drop the profile claim")
    (is (not (contains? refused :bytes))
        "nothing was published")))


;; =============================================================================
;; Row 5: the refusal battery
;; =============================================================================

(deftest queued-work-undeclared-pcs-and-mismatched-evidence-refuse
  (let [t (toy)
        serve! (server (served-peer t) (:channel t))
        [parked _src] (parked-reader 1 false)
        as-put (fn [m]
                 (update m :wait-set
                         (fn [ws]
                           (mapv #(assoc % :reason :put :datom "v")
                                 ws))))]
    (is (= :yin.k/not-quiescent
           (:yin.k/status
             (handoff/export-task
               (update parked :ready-queue (fnil conj [])
                       {:reason :next, :pc 0})
               serve!))))
    (is (= :yin.k/not-at-safepoint
           (:yin.k/status
             (handoff/export-task
               (update parked :wait-set
                       (fn [ws] (mapv #(assoc % :pc 0) ws)))
               serve!))))
    (is (= :yin.k/non-portable
           (:yin.k/status (handoff/export-task (as-put parked)
                                               serve!)))
        "a blocked-read pc admits no write variant: the kinds gate
         eligibility, the observed entry is the evidence")
    (is (= :reason-mismatch
           (:yin.k/kind (handoff/export-task (as-put parked)
                                             serve!))))))


(deftest a-forged-resource-reference-refuses-with-its-kind
  (let [t (toy)
        serve! (server (served-peer t) (:channel t))
        [parked _src] (parked-reader 1 false)
        m0 (load-ast (new-machine) (lit nil))
        forged (engine/issue-ref m0 :cursor-ref :yin/no-such-cell)]
    (is (= :forged-resource-reference
           (:yin.k/kind
             (handoff/export-task
               (update parked :wait-set
                       (fn [ws]
                         (mapv #(assoc-in % [:env 'ghost] forged)
                               ws)))
               serve!)))
        "a reference the source never issued names nothing portable")))


(defn- tamper
  "Decode the export's bytes, apply `f` to the body, re-encode."
  [export f]
  (cbor/encode (f (cbor/decode (:bytes export)))))


(deftest malformed-bodies-refuse-undecodable-whole
  (let [t (toy)
        serve! (server (served-peer t) (:channel t))
        [parked _src] (parked-reader 1 true)
        parked (assoc-in parked [:store 'held]
                         (engine/issue-ref
                           parked :cursor-ref
                           (get-in (first (:wait-set parked))
                                   [:cursor-ref :id])))
        export (handoff/export-task parked serve!)
        attach! (attacher t)
        resume (fn [bytes]
                 (handoff/resume-task (new-machine) bytes attach!))]
    (is (= :ok (:status export)))
    (testing "a missing cell reference"
      (is (= :yin.k/undecodable
             (:yin.k/status
               (resume (tamper export
                               #(update % :yin.k/cells
                                        dissoc :yin.k/c-0)))))))
    (testing "an extra cell nobody references"
      (is (= :extra-cell
             (:yin.k/kind
               (resume (tamper
                         export
                         #(assoc-in %
                                    [:yin.k/cells :yin.k/c-9]
                                    {:yin.k/stream
                                     (get-in %
                                             [:yin.k/cells :yin.k/c-0
                                              :yin.k/stream]),
                                     :yin.k/position 0})))))))
    (testing "a malformed cell"
      (is (= :yin.k/undecodable
             (:yin.k/status
               (resume (tamper
                         export
                         #(assoc-in % [:yin.k/cells :yin.k/c-0
                                       :yin.k/stream]
                                    :not-a-marker)))))))
    (testing "frames that are not a vector"
      (is (= :yin.k/undecodable
             (:yin.k/status
               (resume (tamper export
                               #(assoc % :yin.k/frames
                                       {:not :a-vector})))))))
    (testing "code that no longer hashes to its address"
      (is (= :yin.k/hash-mismatch
             (:yin.k/status
               (resume (tamper
                         export
                         #(update % :yin.k/code
                                  (fn [code]
                                    (into {}
                                          (map (fn [[a _v]]
                                                 [a [[:halt]]]))
                                          code)))))))))
    (testing "a wrong wire version"
      (is (= :yin.k/profile-mismatch
             (:yin.k/status
               (resume (tamper export
                               #(assoc % :yin.k/version 1)))))))
    (testing "bytes that are no body at all"
      (is (= :yin.k/undecodable
             (:yin.k/status
               (resume (cbor/encode "not a body"))))))))


(deftest an-install-name-alone-refuses-export
  (let [t (toy)
        serve! (server (served-peer t) (:channel t))
        [parked _child] (parked-installer true)
        stripped (-> parked
                     (update :installs dissoc 'host.mod)
                     (update :wait-set
                             (fn [ws]
                               (mapv #(assoc % :reason :install
                                             :name 'host.mod)
                                     ws))))
        refused (handoff/export-task stripped serve!)]
    (is (= :incomplete-install (:yin.k/kind refused))
        (pr-str refused))
    (is (not (contains? refused :bytes))
        "an install name alone reconstructs nothing, so nothing is
         published")))


;; =============================================================================
;; Round 5: the graph the two traversals must cover, the child
;; refusal, and the frame grammar
;; =============================================================================

(deftest a-cell-reachable-only-through-registers-or-an-envelope-crosses
  (let [t (toy)
        serve! (server (served-peer t) (:channel t) #{:reader :writer})
        attach! (attacher t)
        [parked _src] (parked-reader 1 false)
        entry (first (:wait-set parked))
        sid (:stream-id entry)
        kept (get-in parked [:resources
                             (get-in entry [:cursor-ref :id]) :cursor])
        ;; a cell no pending, store or halt result names: only the
        ;; wait frame's captured environment reaches it
        env-only (-> parked
                     (update :resources assoc
                             :yin/lonely {:stream-id sid, :cursor kept})
                     (update-in [:wait-set 0 :env]
                                assoc 'lonely
                                (engine/issue-ref parked :cursor-ref
                                                  :yin/lonely)))
        export (handoff/export-task env-only serve!)
        resumed (when (= :ok (:status export))
                  (handoff/resume-task (new-machine) (:bytes export)
                                       attach!))
        ;; a cell only the retained request's own argument reaches
        call-in (one-slot-stream "call-in")
        call-out (ring 8)
        _ (stream/append! call-in :warmed)
        arg-program (let1 's {:type :stream/make, :buffer 4}
                          (let1 'c (cursor-of (v 's))
                                {:type :dao.stream.apply/call,
                                 :op :op/echo, :operands [(v 'c)]}))
        arg-parked (vm/run (load-ast (calling-machine call-in call-out)
                                     arg-program))
        arg-export (handoff/export-task arg-parked serve!)
        arg-resumed (when (= :ok (:status arg-export))
                      (handoff/resume-task (new-machine)
                                           (:bytes arg-export) attach!))]
    (testing "the register-reached cell is no extra cell"
      (is (= :ok (:status export)) (pr-str export))
      (is (= 2 (count (get-in export [:body :yin.k/cells])))
          "the waiter's cell beside the lonely one")
      (is (contains? (encoded-cell-ids
                       (get-in export [:body :yin.k/frames 0
                                       :yin.k/registers]))
                     (second (keys (get-in export
                                           [:body :yin.k/cells])))))
      (is (= :ok (:status resumed)) (pr-str resumed)))
    (testing "the envelope-reached cell crosses too"
      (is (= :ok (:status arg-export)) (pr-str arg-export))
      (is (= 2 (count (get-in arg-export [:body :yin.k/cells])))
          "the response route beside the argument's own cell")
      (is (contains? (encoded-cell-ids
                       (get-in arg-export [:body :yin.k/frames 0
                                           :yin.k/pending
                                           :yin.k/request-envelope]))
                     (first (keys (get-in arg-export
                                          [:body :yin.k/cells])))))
      (is (= :ok (:status arg-resumed)) (pr-str arg-resumed)))))


(deftest a-stream-carried-only-in-a-register-or-store-attaches
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t))
        attach! (attacher t)
        [parked _src] (parked-reader 1 false)
        ;; a SECOND stream, distinct from the one the reader's own
        ;; cell forces: no cell, pending or halt result names it, so
        ;; only the register and the store reach it -- a collector
        ;; that missed those would leave the decoder's reference
        ;; unresolvable
        other (ring 4)
        _ (stream/append! other "solo")
        carried (engine/issue-ref parked :stream-ref :yin/other)
        parked (-> parked
                   (assoc-in [:resources :yin/other] other)
                   (update-in [:wait-set 0 :env] assoc 'carried carried)
                   (assoc-in [:store 'held-stream] carried))
        export (handoff/export-task parked serve!)
        resumed (when (= :ok (:status export))
                  (handoff/resume-task (new-machine) (:bytes export)
                                       attach!))]
    (is (= :ok (:status export)) (pr-str export))
    (is (= 1 (count (get-in export [:body :yin.k/cells])))
        "no cell names the carried stream: nothing else forces it")
    (is (= :ok (:status resumed))
        "the lower attaches what only a register and the store name")
    (when (= :ok (:status resumed))
      (let [recv (:vm resumed)
            ref (get-in recv [:wait-set 0 :env 'carried])]
        (is (contains? (:resources recv) (:id ref))
            "the re-sealed stream reference resolves to an attached
             reflection")
        (let [h (get (:resources recv) (:id ref))
              read (loop [n 6]
                     (let [r (stream/next
                               h
                               (:dao.stream/cursor
                                 (stream/cursor h :dao.stream/oldest)))]
                       (if (or (= :dao.stream/ok (:dao.stream/outcome r))
                               (zero? n))
                         r
                         (do (serve-mirror! peer)
                             (recur (dec n))))))]
          (is (= :dao.stream/ok (:dao.stream/outcome read)))
          (is (= "solo" (:dao.stream/value read))
              "and the attachment is live: the distinct stream reads
               through its reflection"))))))


(deftest a-parked-record-at-no-explicit-park-refuses
  (let [t (toy)
        serve! (server (served-peer t) (:channel t))
        attach! (attacher t)
        parked (parked-explicit)
        pid (:id (:value parked))
        export (handoff/export-task parked serve!)
        resume (fn [f]
                 (handoff/resume-task (new-machine)
                                      (tamper export f) attach!))]
    (is (= :ok (:status export)))
    (is (= :parked (:yin.k/kind (:body export))))
    (testing "a record steered onto an ordinary instruction pc"
      (is (= :yin.k/undecodable
             (:yin.k/status
               (resume #(assoc-in % [:yin.k/parked pid :yin.k/pc] 0))))
          "pc 0 of this program creates the outer closure, a step:
            an in-bounds pc that is no park never restores"))
    (testing "a record whose pc leaves the segment"
      (is (= :yin.k/undecodable
             (:yin.k/status
               (resume #(assoc-in % [:yin.k/parked pid :yin.k/pc]
                                  9999)))))
      (is (= :yin.k/undecodable
             (:yin.k/status
               (resume #(update-in %
                                   [:yin.k/parked pid]
                                   dissoc :yin.k/segment))))))
    (testing "the untampered record still lowers and resumes"
      (is (= :ok (:status (handoff/resume-task (new-machine)
                                               (:bytes export)
                                               attach!)))))))


(deftest a-refused-install-child-aborts-the-parents-resume
  (let [t (toy)
        serve! (server (served-peer t) (:channel t))
        attach! (attacher t)
        [parked _child] (parked-installer)
        export (handoff/export-task parked serve!)
        grammar-broken (tamper export
                               #(assoc-in %
                                          [:yin.k/installs 'host.mod
                                           :yin.k/child :yin.k/code]
                                          {}))
        route-gone (tamper export
                           #(assoc-in %
                                      [:yin.k/installs 'host.mod
                                       :yin.k/child :yin.k/cells
                                       :yin.k/c-0 :yin.k/stream
                                       :dao.stream/descriptor
                                       :dao.stream/channel]
                                      {:dao.stream.type/x 1}))
        broken (handoff/resume-task (new-machine) grammar-broken
                                    attach!)
        gone (handoff/resume-task (new-machine) route-gone attach!)]
    (testing "a child the grammar refuses aborts before attachment"
      (is (= :yin.k/undecodable (:yin.k/status broken))
          (pr-str broken)))
    (testing "a child whose stream cannot attach propagates"
      (is (= :yin.k/unsatisfied (:yin.k/status gone))
          (pr-str gone))
      (is (not= :ok (:status gone))
          "the parent never answers :ok over a child that did not
           lower"))))


(deftest malformed-frames-and-registers-refuse-undecodable-whole
  (let [t (toy)
        serve! (server (served-peer t) (:channel t))
        [parked _src] (parked-reader 1 false)
        export (handoff/export-task parked serve!)
        attach! (attacher t)
        resume (fn [f]
                 (handoff/resume-task (new-machine)
                                      (tamper export f) attach!))
        pc-path [:yin.k/frames 0 :yin.k/registers :yin.k/pc]]
    (is (= :ok (:status export)))
    (testing "a blocked body without frames"
      (is (= :yin.k/undecodable
             (:yin.k/status (resume #(dissoc % :yin.k/frames)))))
      (is (= :yin.k/undecodable
             (:yin.k/status (resume #(assoc % :yin.k/frames []))))))
    (testing "a resume pc outside the segment or below zero"
      (is (= :yin.k/undecodable
             (:yin.k/status (resume #(assoc-in % pc-path 9999)))))
      (is (= :yin.k/undecodable
             (:yin.k/status (resume #(assoc-in % pc-path -1))))))
    (testing "registers without their segment never validate"
      (let [drop-segment #(update-in %
                                     [:yin.k/frames 0 :yin.k/registers]
                                     dissoc :yin.k/segment)]
        (is (= :yin.k/undecodable
               (:yin.k/status (resume drop-segment)))
            "a segment absent outright refuses")
        (is (= :yin.k/undecodable
               (:yin.k/status
                 (resume #(-> %
                              (update-in [:yin.k/frames 0 :yin.k/registers]
                                         dissoc :yin.k/segment)
                              (assoc-in pc-path 9999)))))
            "and a wild pc buys nothing once the segment is gone")))
    (testing "a segment naming no carried code"
      (let [unheard-of :segment/blake3-0]
        (is (= :yin.k/undecodable
               (:yin.k/status
                 (resume #(assoc-in %
                                    [:yin.k/frames 0 :yin.k/registers
                                     :yin.k/segment]
                                    unheard-of)))))))
    (testing "a resume pc at no safepoint"
      (is (= :yin.k/undecodable
             (:yin.k/status (resume #(assoc-in % pc-path 0))))
          "pc 0 of this program is the outer closure's creation, a
           step, not a parking instruction"))
    (testing "a reason the pc's kinds do not admit"
      (let [refused (resume
                      #(assoc-in %
                                 [:yin.k/frames 0 :yin.k/pending]
                                 {:yin.k/reason :put
                                  :yin.k/stream
                                  (get-in % [:yin.k/cells :yin.k/c-0
                                             :yin.k/stream])
                                  :yin.k/value "x"}))]
        (is (= :yin.k/undecodable (:yin.k/status refused))
            (pr-str refused))
        (is (= :reason-mismatch (:yin.k/kind refused)))))
    (testing "register fields of the wrong type"
      (is (= :yin.k/undecodable
             (:yin.k/status
               (resume #(assoc-in % [:yin.k/frames 0
                                     :yin.k/registers :yin.k/env]
                                  [])))))
      (is (= :yin.k/undecodable
             (:yin.k/status
               (resume #(assoc-in % [:yin.k/frames 0
                                     :yin.k/registers :yin.k/stack]
                                  {}))))))))


;; =============================================================================
;; Version-0 handoff defects (yin.vm.ucf-revisions.md section 6)
;; =============================================================================

(defn- parked-with-a-wait
  "A real machine blocked on a read, A consumed, that then reaches an
   explicit park: the parked activation waits on nothing, but the
   reader's ordered wait is still carried.  Answers [parked src]."
  []
  (let [[blocked src] (parked-reader 1 false)]
    [(vm/run (load-ast blocked {:type :vm/park})) src]))


(deftest a-park-beside-ordered-waits-keeps-the-waits
  (let [t (toy)
        peer (served-peer t)
        serve! (server peer (:channel t))
        [parked src] (parked-with-a-wait)
        pid (:id (:value parked))
        export (handoff/export-task parked serve!)
        resumed (handoff/resume-task (new-machine) (:bytes export)
                                     (attacher t))
        recv (:vm resumed)
        resume-prog {:type :vm/resume, :parked-id pid,
                     :val (lit "back")}]
    (is (= 1 (count (:wait-set parked))) (pr-str (:wait-set parked)))
    (is (= :ok (:status export)) (pr-str export))
    (is (= :parked (:yin.k/kind (:body export))))
    (is (= :next (:yin.k/reason (parked-frame export)))
        "the carried reader's wait is serialized beside the park")
    (is (= :ok (:status resumed)) (pr-str resumed))
    (testing "the lower restores the ordered waits, not only the park"
      (is (= (:value recv) (get (:parked recv) pid)))
      (is (= [:next] (mapv :reason (:wait-set recv)))
          (pr-str (:wait-set recv))))
    (testing "the restored waiter reads on, in parity with the source"
      (stream/append! src "B")
      (let [reference (drive-local (vm/run (load-ast parked resume-prog)))
            done (drive (vm/run (load-ast recv resume-prog)) peer)]
        (is (= (select-keys reference [:halted? :blocked? :value])
               (select-keys done [:halted? :blocked? :value]))
            (pr-str {:reference (select-keys reference
                                             [:halted? :blocked? :value])
                     :done (select-keys done
                                        [:halted? :blocked? :value])}))
        (is (= (count (:wait-set reference))
               (count (:wait-set done))))))))


(deftest an-install-pending-without-its-entry-refuses-before-restoration
  (let [t (toy)
        serve! (server (served-peer t) (:channel t))
        [parked _child] (parked-installer)
        export (handoff/export-task parked serve!)
        stripped (tamper export #(dissoc % :yin.k/installs))
        renamed (tamper export
                        #(update % :yin.k/installs
                                 (fn [insts]
                                   {'host.other (get insts 'host.mod)})))
        attached (atom 0)
        counting (let [a (attacher t)]
                   (fn [d] (swap! attached inc) (a d)))
        resume (fn [bytes]
                 (handoff/resume-task (new-machine) bytes counting))]
    (is (= :ok (:status export)) (pr-str export))
    (is (= :install (:yin.k/reason (parked-frame export))))
    (testing "a body whose install waiter has no install entry at all"
      (let [refused (resume stripped)]
        (is (= :yin.k/undecodable (:yin.k/status refused))
            (pr-str refused))
        (is (= 'host.mod (:yin.k/name refused)))
        (is (= :incomplete-install (:yin.k/kind refused)))))
    (testing "an entry under some other name is no entry for the waiter"
      (let [refused (resume renamed)]
        (is (= :yin.k/undecodable (:yin.k/status refused))
            (pr-str refused))
        (is (= 'host.mod (:yin.k/name refused)))
        (is (= :incomplete-install (:yin.k/kind refused)))))
    (is (zero? @attached)
        "refused by the grammar before any stream was attached")))
