(ns yin.vm.ucf.handoff
  "Stage 1 of the post-M5 hardening (docs/design/yin.vm.linker.dht.md
   section 14.1): the kept-cursor handoff -- one blocked or halted task
   exported as canonical bytes, resumed as a fresh task on another host.

   `export-task` is the lift of 14.1.2: it validates the safepoint and
   the empty ready queue, captures the ordered waits without polling
   them, walks the reachable graph with ONE source-resource-to-cell
   map (`yin.vm.completion`'s fixed point over the whole frame set, so
   two waiters on one cell lift to one UCF cell and a cell a closure or
   the store also reaches shares the same id), derives every dynamic
   reason and pending variant from the OBSERVED wait entry -- never
   from the static `:yin.safepoint/kinds` at the pc, which only gates
   eligibility (`yin.vm.ucf-revisions.md` section 8) -- closes the
   requirements, validates the whole body, and answers it as canonical
   CBOR bytes under their content address.

   `resume-task` is the lower: decode the bytes, check stamp, version,
   grammar, references and code hashes before anything is installed;
   attach each stream identity once through its carried descriptor;
   allocate one fresh private resource per logical cell and stream;
   re-seal every reference under the receiving task; restore the store
   slice, the module stores, the counter and the parked records, then
   the ordered waits; and answer the runnable machine only after all
   restoration passes.  A failed lower installs nothing partially
   runnable: refusals are total.

   The transfer seam is the same composition pair `yin.vm.ucf.remote`
   defined: `serve!` (the exporter's remote-table entry, so the source
   keeps serving the stream) and `attach!` (the receiver's dispatch).
   Nothing here moves custody: a resumed task is a fork until stage 2
   publishes the fenced UCF amendment (section 14.3).

   Halt is a result, never a frame: the halted body carries the result
   and no wait at all.  An explicit park is the no-wait shape: the
   parked record travels in the scheduler slice under its own id and
   no pending is minted for it.  An install waiter travels beside its
   whole child -- the child's own handoff body, phase and response --
   or the export refuses; an install name alone reconstructs nothing."
  (:require [clojure.set :as set]
            [dao.jing :as jing]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.stream.cbor :as cbor]
            [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.completion :as completion]
            [yin.vm.engine :as engine]
            [yin.vm.module :as module]
            [yin.vm.ucf :as ucf]
            [yin.vm.ucf.remote :as ucf.remote]
            [yin.vm.values :as values]))


(def handoff-tag
  "The S7.5.1 tag of one handoff body."
  :yin.k/handoff)


(def handoff-version
  "The wire version this namespace speaks; stage 2's amendment raises
   it to 1 (section 14.3), and a body of any other version is refused
   with :yin.k/profile-mismatch before restoration."
  0)


;; =============================================================================
;; Refusal vocabulary (S7.9): every failure is a data outcome
;; =============================================================================

(defn- refuse!
  [status data]
  (throw (ex-info (str "Handoff refused: " (name status))
                  (merge {:yin.k/status status} data))))


(defn- non-portable!
  [kind data]
  (refuse! :yin.k/non-portable (assoc data :yin.k/kind kind)))


(defn- unsatisfied!
  ([] (unsatisfied! nil))
  ([identity]
   (refuse! :yin.k/unsatisfied
            (cond-> {} (some? identity)
                    (assoc :dao.stream/identity identity)))))


(defn- undecodable!
  [data]
  (refuse! :yin.k/undecodable data))


(defn- not-at-safepoint!
  [pc]
  (refuse! :yin.k/not-at-safepoint {:yin.k/pc pc}))


(defn- outcome
  "The S7.9 data outcome exception `e` carries, or nil."
  [e]
  (let [d (ex-data e)]
    (when (and (map? d) (contains? d :yin.k/status)) d)))


(defn- call!
  "Run `f`, answering its value, or the data outcome it refused with.
   A throw with no outcome vocabulary is a defect, not a refusal: it
   propagates."
  [f]
  (try (f)
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (if-let [d (outcome e)] d (throw e)))))


;; =============================================================================
;; The one cell map (14.1.2 lift step 2): minted across the WHOLE set
;; =============================================================================

(defn- scalar?
  [x]
  (or (nil? x) (boolean? x) (number? x) (string? x)
      (keyword? x) (symbol? x)))


(defn- local-identity
  "The identity `h` itself declares, when its descriptor answers ok;
   nil otherwise -- the best name a refusal can give before `serve!`."
  [h]
  (let [d (when (stream/descriptor? h) (stream/descriptor h))]
    (when (= :dao.stream/ok (:dao.stream/outcome d))
      (:dao.stream/identity d))))


(defn- marker-for!
  "The UCF stream marker for the stream resource `id` of `vm`, minted
   through the exporter's own table (`serve!`) once per resource: the
   descriptor serve! answers, never a handle.  A resource id the
   private table does not hold is a forged reference; a stream the
   table cannot serve is unsatisfied, naming the identity where one is
   known.  Two resources of one identity mint two markers that name
   the one identity -- the lower attaches per identity, once."
  [vm serve! found id]
  (let [handle (get (:resources vm) id)]
    (if (nil? handle)
      (non-portable! :forged-resource-reference {:yin.k/hint id})
      (or (get-in @found [:markers id])
          (if-let [marker (ucf.remote/lift-marker serve! handle)]
            (do (swap! found assoc-in [:markers id] marker) marker)
            (unsatisfied! (local-identity handle)))))))


(defn- portable-cursor
  "The kept cursor when it survives a round trip through the body's
   own canonical codec -- encoded, decoded back, equal -- which is what
   the :dao.stream.remote/v1 claim means for this body.  nil when it
   does not, so no cell is ever published over an unpublishable
   cursor."
  [kept]
  (try (let [b (cbor/encode kept)]
         (when (= kept (cbor/decode b)) kept))
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn- cell-at!
  "The logical cursor cell for an explicit kept position on the stream
   resource `stream-id` (UCF 7.5.3), minted once per `key` across the
   WHOLE export: two references through one key share one cell id.
   Cell ids are lift-local identities, never content or position
   hashes."
  [vm serve! found key stream-id kept]
  (or (get-in @found [:cell-of key])
      (let [marker (marker-for! vm serve! found stream-id)]
        (if (some? (portable-cursor kept))
          (let [cid (keyword "yin.k" (str "c-" (count (:cells @found))))]
            (swap! found
                   (fn [acc]
                     (-> acc
                         (assoc-in [:cell-of key] cid)
                         (assoc-in [:cells cid]
                                   {:yin.k/stream marker
                                    :yin.k/position kept}))))
            cid)
          (unsatisfied! (:dao.stream/identity marker))))))


(defn- cell-for!
  "The cell the cursor-cell resource `id` lifts to: the entry's kept
   position on its own stream.  A key naming no resource is a forged
   reference."
  [vm serve! found id]
  (let [cell (get (:resources vm) id)]
    (if (nil? cell)
      (non-portable! :forged-resource-reference {:yin.k/hint id})
      (cell-at! vm serve! found id (:stream-id cell) (:cursor cell)))))


;; =============================================================================
;; The value encoder: UCF 7.5.1 over one task's whole reachable graph
;; =============================================================================

(defn- encode-primitive
  "A host function as UCF 7.5.2's primitive marker: its one name in
   `vm`'s primitives, with that name's profile.  Unnamed, ambiguous
   and host-state functions are not portable."
  [vm f]
  (let [n (vm/name-of (:primitives vm)
                      (or (:primitive-canonical-names vm) {}) f)
        profile (when (symbol? n)
                  (vm/profile-of (:primitives vm) n))]
    (cond
      (nil? n) (non-portable! :unnamed-function {})
      (not (symbol? n)) (non-portable! :ambiguous-primitive {})
      (= :host (:yin.k/class profile))
      (non-portable! :host-state-primitive {:yin.k/name n})
      :else {:yin.k/tag :yin.k/primitive
             :yin.k/name n
             :yin.k/profile (:yin.k/profile profile)})))


(defn- value-encoder
  "The encode half, over `vm`'s coordinates with the export's ONE
   found map: closures lift through the kernel (recording their origin
   segment and module store), a stream or cursor reference is
   authenticated under the source's own secret before it is encoded --
   a forged reference refuses with its kind -- and every literal map is
   wrapped, so a program's data can never forge a marker.  A host
   object, a reified continuation and a heap cell refuse (S7.5.4)."
  [vm serve! found]
  (letfn [(encode
            [x]
            (cond
              (scalar? x) x
              (values/host-typed? x)
              (cond
                (not (values/owned-by? x (:owner vm)))
                (non-portable! :foreign-value {:yin.k/hint (values/kind-of x)})
                (values/closure? x)
                (let [marker (module/lift-closure vm (values/payload x)
                                                  encode)]
                  (swap! found
                         (fn [acc]
                           (let [acc (update acc :segments
                                             conj (:yin.k/segment marker))]
                             (if-some [m (:yin.k/store-of marker)]
                               (update acc :stores conj m)
                               acc))))
                  marker)
                :else
                (non-portable! :non-canonicalizable
                               {:yin.k/hint :reified-continuation}))
              (map? x)
              (case (:type x)
                :stream-ref
                (if (engine/authentic-ref? vm :stream-ref x)
                  (marker-for! vm serve! found (:id x))
                  (non-portable! :forged-resource-reference
                                 {:yin.k/hint :stream-ref}))
                :cursor-ref
                (if (engine/authentic-ref? vm :cursor-ref x)
                  {:yin.k/tag :yin.k/cursor-ref
                   :yin.k/cell (cell-for! vm serve! found (:id x))}
                  (non-portable! :forged-resource-reference
                                 {:yin.k/hint :cursor-ref}))
                :parked-continuation
                (non-portable! :non-canonicalizable
                               {:yin.k/hint (:type x)})
                :cell-ref
                (non-portable! :cell {:yin.k/hint (:id x)})
                {:yin.k/tag :yin.k/literal
                 :yin.k/entries
                 (into [] (mapcat (fn [[k v]] [(encode k) (encode v)])) x)})
              (vector? x) (mapv encode x)
              (set? x) (into #{} (map encode) x)
              (seq? x) (apply list (map encode x))
              (fn? x) (encode-primitive vm x)
              :else (non-portable! :host-object
                                   {:yin.k/hint
                                    #?(:cljd (str (.-runtimeType x))
                                       :default (str (type x)))})))]
    encode))


;; =============================================================================
;; Registers: the frame slice, structurally
;; =============================================================================

(defn- seg-address!
  "The content address of local segment id `seg`, recorded as a
   requirement of the body.  A segment with no address names nothing
   portable."
  [found vm seg]
  (if-some [a (get-in vm [:code seg :address])]
    (do (swap! found update :segments conj a) a)
    (non-portable! :unaddressed-segment {:yin.k/hint seg})))


(defn- encode-env
  "One environment: keys as carried (names), values encoded, and the
   active module store it names -- when it names one -- recorded as a
   store the body must snapshot."
  [found encode env]
  (when-some [m (get env engine/store-of-key)]
    (swap! found update :stores conj m))
  (into {} (map (fn [[k v]] [k (encode v)])) env))


(defn- encode-frame
  "One K frame: its own keys, the segment as its address, the
   environment encoded as one."
  [found encode vm frame]
  (into {}
        (map (fn [[k v]]
               [k (cond
                    (= :segment k) (seg-address! found vm v)
                    (= :env k) (encode-env found encode v)
                    :else (encode v))]))
        frame))


(defn- encode-registers
  "One register map -- a wait entry's or a parked record's frame slice
   -- without its transport fields: they travel as the pending."
  [found encode vm {:keys [segment pc env stack k]}]
  (cond-> {:yin.k/pc pc
           :yin.k/env (encode-env found encode (or env {}))
           :yin.k/stack (mapv encode (or stack []))
           :yin.k/k (mapv (partial encode-frame found encode vm)
                          (or k []))}
    (some? segment)
    (assoc :yin.k/segment (seg-address! found vm segment))))


(defn- decode-env
  [decode env]
  (into {} (map (fn [[k v]] [k (decode v)])) env))


(defn- decode-frame
  [decode aliases frame]
  (into {}
        (map (fn [[k v]]
               [k (cond
                    (= :segment k) (get aliases v)
                    (= :env k) (decode-env decode v)
                    :else (decode v))]))
        frame))


(defn- decode-registers
  "The register map back in the receiver's coordinates: segments
   through the alias column of the attached code, values decoded and
   re-sealed under the receiving task."
  [decode aliases r]
  (cond-> {:pc (:yin.k/pc r)
           :env (decode-env decode (:yin.k/env r))
           :stack (mapv decode (:yin.k/stack r))
           :k (mapv (partial decode-frame decode aliases)
                    (or (:yin.k/k r) []))}
    (some? (:yin.k/segment r))
    (assoc :segment (get aliases (:yin.k/segment r)))))


;; =============================================================================
;; Dynamic reasons: observed evidence against the static kinds
;; =============================================================================

(def ^:private wire-of-kind
  "The wire variants each static safepoint kind admits.  The static map
   says where an instruction may park; the observed entry says why this
   activation did.  An explicit park admits no waiter at all, and an
   `:effectful-call` admits whatever effect the activation actually
   produced -- a read, a write, an FFI state or a link state -- because
   the instruction's kind selects none of them."
  {:explicit-park #{}
   :blocked-read #{:next}
   :blocked-write #{:put}
   :ffi-sent #{:ffi}
   :ffi-retained #{:ffi-request}
   :effectful-call #{:next :put :ffi :ffi-request
                     :link-request :link-response :install}})


(defn- observed-wire
  "The wire variant the observed entry itself carries, from its shape
   alone: a `:next` wait with a call id is a sent FFI response reader;
   a `:put` wait holding its request is a retained call.  nil when the
   entry is no variant at all."
  [entry]
  (case (:reason entry)
    :next (if (:call-id entry) :ffi :next)
    :put (if (:request-sent entry) :ffi-request :put)
    :link-request :link-request
    :link-response :link-response
    :install :install
    nil))


(defn- safepoint-kinds-at
  "The static `:yin.safepoint/kinds` at the resume pc of `seg`, from
   the loaded image's own canonical vector; an undeclared pc refuses
   with the existing :yin.k/not-at-safepoint outcome."
  [vm seg pc]
  (let [image (get (:code vm) seg)
        v (when image (completion/image->vector image))]
    (cond
      (nil? image) (non-portable! :unaddressed-segment {:yin.k/hint seg})
      (nil? v) (non-portable! :address-mismatch {:yin.k/hint seg})
      :else
      (let [at (ucf/safepoint-at v pc)]
        (if (contains? at :yin.k/status)
          (not-at-safepoint! pc)
          (:yin.safepoint/kinds at))))))


;; =============================================================================
;; Lift: one pending variant each, from the observed entry
;; =============================================================================

(defn- call-out-cell
  "The emitter's call-out cursor cell -- the response route a retained
   call held.  nil when the composition holds no call pair, which no
   real retained request does."
  [resources]
  (let [cursor (get resources vm/call-out-cursor-key)
        handle (get resources vm/call-out-stream-key)]
    (when (and (map? cursor) (contains? cursor :stream-id) handle)
      {:id vm/call-out-cursor-key
       :stream-id vm/call-out-stream-key
       :cursor (:cursor cursor)})))


(defn- lift-pending!
  "The S7.4.3 pending of one observed entry, in `yin.vm.ucf.remote`'s
   wire shapes, minted through THIS export's one cell and marker maps:
   the cell a waiter names is the same cell a closure or the store
   reaches, and the response route a retained call held is a cell like
   any other, at its kept position."
  [vm serve! found encode entry]
  (case (observed-wire entry)
    :next
    {:yin.k/reason :next
     :yin.k/cell (cell-for! vm serve! found
                            (get-in entry [:cursor-ref :id]))}

    :ffi
    (cond-> {:yin.k/reason :ffi
             :yin.k/call-id (:call-id entry)
             :yin.k/cell (cell-for! vm serve! found
                                    (get-in entry [:cursor-ref :id]))}
      (contains? entry :op) (assoc :yin.k/op (:op entry)))

    :put
    (do (when-not (contains? entry :datom)
          (non-portable! :incomplete-write
                         {:yin.k/pc (:pc entry)}))
        {:yin.k/reason :put
         :yin.k/stream (marker-for! vm serve! found (:stream-id entry))
         :yin.k/value (encode (:datom entry))})

    :ffi-request
    (let [route (call-out-cell (:resources vm))]
      (if (nil? route)
        (unsatisfied!)
        (let [envelope (:datom entry)]
          {:yin.k/reason :ffi-request
           :yin.k/call-id (:call-id entry)
           :yin.k/request-envelope (encode envelope)
           :yin.k/request-op (or (apply2/request-op envelope)
                                 (:op entry))
           :yin.k/request-args (apply2/request-args envelope)
           :yin.k/request (marker-for! vm serve! found
                                       (:stream-id entry))
           :yin.k/response (marker-for! vm serve! found
                                        (:stream-id route))
           :yin.k/response-cell (cell-for! vm serve! found
                                           (:id route))})))

    (:link-request :link-response)
    (let [reason (:reason entry)
          kept (:cursor entry)]
      (cond-> {:yin.k/reason reason
               :yin.k/link-id (:link-id entry)
               :yin.k/name (:name entry)
               :yin.k/request (marker-for! vm serve! found
                                           (:request entry))
               :yin.k/response (marker-for! vm serve! found
                                            (:response entry))
               :yin.k/cell (cell-at! vm serve! found
                                     [(:response entry) kept]
                                     (:response entry) kept)}
        (= :link-request reason)
        (assoc :yin.k/envelope (encode (:envelope entry)))))

    :install
    (do (when-not (contains? (:installs vm) (:name entry))
          (non-portable! :incomplete-install {:yin.k/name (:name entry)}))
        {:yin.k/reason :install
         :yin.k/name (:name entry)})

    (undecodable! {:yin.k/reason (:reason entry)
                   :yin.k/path [:yin.k/wait (:pc entry)]})))


(defn- lift-frame!
  "One wait entry lifted: its registers and its pending, the dynamic
   reason checked against the static kinds at its pc -- the kinds gate
   eligibility only, and a variant they do not admit is evidence the
   frame is not exportable here, never a wire reason to invent."
  [vm serve! found encode entry]
  (let [kinds (safepoint-kinds-at vm (:segment entry) (:pc entry))
        wire (observed-wire entry)]
    (when (nil? wire)
      (undecodable! {:yin.k/reason (:reason entry)
                     :yin.k/path [:yin.k/wait (:pc entry)]}))
    (when-not (some #(contains? (get wire-of-kind %) wire) kinds)
      (non-portable! :reason-mismatch
                     {:yin.k/pc (:pc entry)
                      :yin.k/reason wire
                      :yin.safepoint/kinds (vec kinds)}))
    {:yin.k/registers (encode-registers found encode vm entry)
     :yin.k/pending (lift-pending! vm serve! found encode entry)}))


;; =============================================================================
;; The install children: a whole child travels, or nothing does
;; =============================================================================

(declare export-task validate-body resume-task)


(defn- export-installs
  "Each live install child of `vm` as its own handoff body beside the
   response that spawned it, preserving the child's phase and waits
   (14.1.1's :install row).  A child that cannot export itself refuses
   the parent's export -- a refusal is thrown, never embedded -- and a
   response outside the canonical bytes domain refuses it too."
  [vm serve!]
  (into {}
        (map (fn [[m {:keys [vm phase parent response]}]]
               (when-not (cbor/portable-value? response)
                 (non-portable! :install-response {:yin.k/name m}))
               (let [r (export-task vm serve!)]
                 (when (not= :ok (:status r))
                   (refuse! (:yin.k/status r) (dissoc r :status)))
                 [m {:yin.k/phase phase
                     :yin.k/parent parent
                     :yin.k/response response
                     :yin.k/child (:body r)}])))
        (:installs vm)))


;; =============================================================================
;; Export (14.1.2 lift): the whole blocked task as canonical bytes
;; =============================================================================

(defn- module-decls
  "The module declarations the completion walk asks for: each linked
   module of the registry under its manifest address."
  [vm]
  (into {}
        (map (fn [[m entry]]
               [m {:yin.k/manifest (:address entry)}]))
        (module/module-entries (:modules vm))))


(defn- export-refusals
  "The completion walk's refusals as one data outcome."
  [refusals]
  (when-some [r (first refusals)]
    (refuse! (if (= :yin.k/not-quiescent (:kind r))
               :yin.k/not-quiescent
               :yin.k/non-portable)
             (assoc (dissoc r :kind) :yin.k/kind (:kind r)))))


(defn- snapshot-module-stores
  "The encoded store snapshot of every module the encoding reached,
   exactly as it stands in the task at export, mutations included."
  [vm encode found]
  (when-some [m (first (remove #(contains? (:module-stores vm) %)
                               (sort-by str (:stores @found))))]
    (non-portable! :missing-module-store {engine/store-of-key m}))
  (into {}
        (map (fn [m]
               [m (into {}
                        (map (fn [[k v]] [(encode k) (encode v)]))
                        (get (:module-stores vm) m))]))
        (sort-by str (:stores @found))))


(defn export-task
  "The lift of 14.1.2: `vm`, a blocked or halted task at a safepoint,
   as canonical bytes under their content address.  Answers
   `{:status :ok :kind k :body b :bytes encoded :address a}` with `k`
   `:blocked`, `:parked` or `:halted`, or the S7.9 data outcome of the
   first refusal -- `:yin.k/not-quiescent` for queued work,
   `:yin.k/not-at-safepoint` for an undeclared pc, `:yin.k/non-portable`
   for a value or reference that cannot cross, `:yin.k/unsatisfied`
   for a stream that cannot be served, a cursor that cannot survive
   the canonical codec, or -- when `opts` names cursor profiles that
   do not cover the cells -- a claim the body cannot make.
   `serve!` is the exporter's remote-table entry, exactly
   `yin.vm.ucf.remote`'s."
  ([vm serve!]
   (export-task vm serve! nil))
  ([vm serve! opts]
   (call!
     (fn []
       (when (seq (:ready-queue vm))
         (refuse! :yin.k/not-quiescent
                  {:yin.k/ready (count (:ready-queue vm))}))
       (let [walked (completion/complete
                      {:vm vm
                       :cursor-profile (constantly :dao.stream.remote/v1)
                       :modules (module-decls vm)})
             _ (export-refusals (:yin.k/refusals walked))
             ;; the pending-child gap the walk cannot close itself: an
             ;; obligation a live install child will bind when it
             ;; completes is discharged by the child this body carries
             ;; beside the waiter, not by a store the emitter holds
             installing (into #{}
                              (mapcat (fn [[m inst]]
                                        (map (fn [x]
                                               (symbol (str (name m) "/"
                                                            (name x))))
                                             (get-in inst
                                                     [:response :manifest
                                                      :yin.module/exports])))
                                      (:installs vm)))
             missing (update (:yin.k/missing walked) :obligations
                             #(set/difference (set %) installing))
             discovery (cond
                         (seq (:segments missing)) :blocked
                         (some seq (vals (dissoc missing :segments)))
                         :incomplete
                         :else :complete)]
         (when-not (= :complete discovery)
           (refuse! :yin.k/unsatisfied
                    {:yin.k/discovery discovery
                     :yin.k/missing missing}))
         (let [value (:value vm)
               active (and (map? value)
                           (= :parked-continuation (:type value))
                           (contains? (:parked vm) (:id value)))
               active-rec (when active (get (:parked vm) (:id value)))
               kind (cond
                      active :parked
                      (engine/halted-with-empty-queue? vm) :halted
                      (seq (:wait-set vm)) :blocked
                      :else (not-at-safepoint!
                              (get-in vm [:control :pc])))
               _ (when (and (= :halted kind) (seq (:wait-set vm)))
                   (non-portable! :inconsistent-halt {}))
               _ (when (and active
                            (not (some #(= :explicit-park %)
                                       (safepoint-kinds-at
                                         vm (:segment active-rec)
                                         (:pc active-rec)))))
                   (non-portable! :reason-mismatch
                                  {:yin.k/pc (:pc active-rec)}))
               found (atom {:cells {} :cell-of {} :markers {}
                            :stores #{} :segments #{}})
               encode (value-encoder vm serve! found)
               frames (mapv (partial lift-frame! vm serve! found encode)
                            (:wait-set vm))
               parked (into {}
                            (map (fn [[pid rec]]
                                   (safepoint-kinds-at vm (:segment rec)
                                                       (:pc rec))
                                   [pid (encode-registers found encode
                                                          vm rec)]))
                            (:yin.k/parked (:yin.k/scheduler walked)))
               store (into {}
                           (map (fn [[k v]] [(encode k) (encode v)]))
                           (:yin.k/store walked))
               module-stores (snapshot-module-stores vm encode found)
               installs (export-installs vm serve!)
               cells (:cells @found)
               profiles (or (:cursor-profiles opts)
                            (if (seq cells)
                              ucf.remote/cursor-profiles
                              #{}))
               _ (when (and (seq cells)
                            (not (contains? profiles
                                            :dao.stream.remote/v1)))
                   (unsatisfied!))
               segments (:segments @found)
               fetch (completion/vm-fetch vm nil)
               code (into {}
                          (map (fn [a]
                                 (if-some [v (fetch a)]
                                   [a v]
                                   (refuse! :yin.k/unsatisfied
                                            {:yin.k/segment a}))))
                          (sort-by str segments))
               body (cond-> {handoff-tag true
                             :yin.k/version handoff-version
                             :yin.k/kind kind
                             :yin.k/contract ucf/contract-stamp
                             :yin.k/id-counter (or (:id-counter vm) 0)
                             :yin.k/store store
                             :yin.k/module-stores module-stores
                             :yin.k/parked parked
                             :yin.k/cells cells
                             :yin.k/requires
                             {:yin.k/cursor-profiles profiles
                              :yin.k/segments (set segments)}
                             :yin.k/code code}
                      (seq installs)
                      (assoc :yin.k/installs installs)
                      (seq frames)
                      (assoc :yin.k/frames frames)
                      (= :parked kind)
                      (assoc :yin.k/parked-id (:id value))
                      (= :halted kind)
                      (assoc :yin.k/result (encode (:value vm))))
               bytes (cbor/encode body)
               _ (validate-body (cbor/decode bytes))]
           {:status :ok
            :kind kind
            :body body
            :bytes bytes
            :address (jing/content-hash body)}))))))


;; =============================================================================
;; Grammar (S7.5.4): total, before anything is attached or restored
;; =============================================================================

(defn- marker?
  [x]
  (and (map? x) (= :yin.k/stream (:yin.k/tag x))
       (string? (:dao.stream/identity x))
       (map? (:dao.stream/descriptor x))))


(defn- tagged-of
  "Every marker tagged `tag` inside `x`, map keys and values both: a
   sealed reference may sit anywhere an encoded value can, including
   a store key or a literal entry, so the walk covers both halves of
   every map it meets."
  [tag x]
  (into []
        (comp (filter #(and (map? %) (= tag (:yin.k/tag %))))
              (distinct))
        (tree-seq coll? (fn [n]
                          (if (map? n)
                            (concat (keys n) (vals n))
                            (seq n)))
                  x)))


(defn- cell-ids-of
  "Every cell id the value's cursor-ref markers name."
  [x]
  (into #{}
        (map :yin.k/cell)
        (tagged-of :yin.k/cursor-ref x)))


(defn- check-cell
  [cells cid path]
  (when-not (map? (get cells cid))
    (undecodable! {:yin.k/cell cid :yin.k/path path})))


(defn- require-keys
  [pending keys path]
  (doseq [k keys]
    (when-not (contains? pending k)
      (undecodable! {:yin.k/path (conj path k)}))))


(defn- validate-pending
  [cells pending path]
  (let [reason (:yin.k/reason pending)]
    (case reason
      (:next :ffi)
      (do (require-keys pending [:yin.k/cell] path)
          (check-cell cells (:yin.k/cell pending)
                      (conj path :yin.k/cell)))

      :put
      (do (require-keys pending [:yin.k/value] path)
          (when-not (marker? (:yin.k/stream pending))
            (undecodable! {:yin.k/path (conj path :yin.k/stream)})))

      :ffi-request
      (do (require-keys pending [:yin.k/call-id :yin.k/request-envelope
                                 :yin.k/request :yin.k/response
                                 :yin.k/response-cell]
                        path)
          (check-cell cells (:yin.k/response-cell pending)
                      (conj path :yin.k/response-cell))
          (when-not (marker? (:yin.k/request pending))
            (undecodable! {:yin.k/path (conj path :yin.k/request)}))
          (when-not (marker? (:yin.k/response pending))
            (undecodable! {:yin.k/path (conj path :yin.k/response)})))

      (:link-request :link-response)
      (do (require-keys pending [:yin.k/link-id :yin.k/name
                                 :yin.k/request :yin.k/response
                                 :yin.k/cell]
                        path)
          (check-cell cells (:yin.k/cell pending)
                      (conj path :yin.k/cell))
          (when-not (marker? (:yin.k/request pending))
            (undecodable! {:yin.k/path (conj path :yin.k/request)}))
          (when-not (marker? (:yin.k/response pending))
            (undecodable! {:yin.k/path (conj path :yin.k/response)}))
          (when (= :link-request reason)
            (require-keys pending [:yin.k/envelope] path)))

      :install
      (require-keys pending [:yin.k/name] path)

      (undecodable! {:yin.k/reason reason :yin.k/path path}))))


(defn- validate-registers
  "Registers are meaningless without their code: the segment is
   REQUIRED, must name code the body carries, and the pc bound is
   checked unconditionally against it -- a segmentless register set
   never validates, whatever its pc."
  [code r path]
  (doseq [k [:yin.k/segment :yin.k/pc :yin.k/env :yin.k/stack :yin.k/k]]
    (when-not (contains? r k)
      (undecodable! {:yin.k/path (conj path k)})))
  (when-not (and (nat-int? (:yin.k/pc r))
                 (map? (:yin.k/env r))
                 (vector? (:yin.k/stack r))
                 (vector? (:yin.k/k r)))
    (undecodable! {:yin.k/path path :yin.k/kind :registers}))
  (let [a (:yin.k/segment r)]
    (when-not (contains? code a)
      (undecodable! {:yin.k/segment a :yin.k/path path}))
    (when-not (< (:yin.k/pc r) (count (get code a)))
      (undecodable! {:yin.k/pc (:yin.k/pc r)
                     :yin.k/segment a
                     :yin.k/path path}))))


(defn- check-frame-pc
  "The resume pc must sit at a static safepoint whose kinds admit the
   pending's wire reason: the same eligibility the lift proved on the
   live entry, re-proved on the bytes before anything is restored, so
   a tampered frame cannot lower at an arbitrary pc.  Runs after
   `validate-registers`, so the segment names carried code and the pc
   is inside it."
  [code pending registers path]
  (let [reason (:yin.k/reason pending)
        a (:yin.k/segment registers)
        pc (:yin.k/pc registers)
        at (ucf/safepoint-at (get code a) pc)]
    (if (contains? at :yin.k/status)
      (undecodable! {:yin.k/pc pc :yin.k/path path})
      (when-not (some #(contains? (get wire-of-kind %) reason)
                      (:yin.safepoint/kinds at))
        (undecodable! {:yin.k/kind :reason-mismatch
                       :yin.k/pc pc
                       :yin.k/reason reason
                       :yin.k/path path})))))


(defn- validate-frames
  [body code cells]
  (let [frames (:yin.k/frames body)]
    (when (some? frames)
      (when-not (vector? frames)
        (undecodable! {:yin.k/path [:yin.k/frames]}))
      (mapv (fn [i frame]
              (let [path [:yin.k/frames i]]
                (when-not (and (map? frame)
                               (map? (:yin.k/registers frame)))
                  (undecodable! {:yin.k/path path}))
                (validate-registers code (:yin.k/registers frame)
                                    (conj path :yin.k/registers))
                (when-not (map? (:yin.k/pending frame))
                  (undecodable! {:yin.k/path
                                 (conj path :yin.k/pending)}))
                (validate-pending cells (:yin.k/pending frame)
                                  (conj path :yin.k/pending))
                (check-frame-pc code (:yin.k/pending frame)
                                (:yin.k/registers frame)
                                (conj path :yin.k/registers))))
            (range)
            frames))))


(defn- reachable-values
  "Every encoded root of `body` whose markers name cells and streams:
   the cell table itself, each frame's registers AND pending (the
   retained values and envelopes are values inside the pending), the
   store and the module stores, the parked records, and the halt
   result.  Install children are whole bodies of their own, resumed
   separately, so they are not roots here."
  [body]
  (let [frames (or (:yin.k/frames body) [])]
    (concat [(:yin.k/cells body)]
            (mapcat (fn [frame]
                      [(:yin.k/registers frame)
                       (:yin.k/pending frame)])
                    frames)
            [(:yin.k/store body)
             (:yin.k/module-stores body)
             (:yin.k/parked body)]
            (when (contains? body :yin.k/result)
              [(:yin.k/result body)]))))


(defn- referenced-cells
  "Every cell id the body names: through the pendings' own cell
   fields, and through the cursor-ref markers of every encoded root
   it carries -- a cell the encoder minted inside wait-frame
   registers, a store key, or a retained envelope is as referenced as
   one a pending names.  Exactly these, and the body's cells, must
   agree."
  [body]
  (let [direct (mapcat (fn [p]
                         (remove nil? [(:yin.k/cell p)
                                       (:yin.k/response-cell p)]))
                       (map :yin.k/pending
                            (or (:yin.k/frames body) [])))]
    (into (set direct)
          (mapcat #(cell-ids-of %))
          (reachable-values body))))


(defn validate-body
  "The body grammar both ends run (S7.5.4): the tag, the version, the
   kind's own shape, every cell reference resolved to exactly the
   cells the body carries -- none missing, none extra -- every pending
   a known variant with the keys that variant requires, every frame's
   segment among the code the body names, and each code vector still
   hashing to its own address and admissible as a vector.  A malformed
   body is refused whole, before any attachment or restoration."
  [body]
  (when-not (and (map? body) (true? (get body handoff-tag)))
    (undecodable! {:yin.k/kind :body}))
  (when-not (= handoff-version (:yin.k/version body))
    (refuse! :yin.k/profile-mismatch
             {:yin.k/version (:yin.k/version body)}))
  (let [kind (:yin.k/kind body)
        cells (or (:yin.k/cells body) {})
        code (or (:yin.k/code body) {})]
    (when-not (contains? #{:blocked :parked :halted} kind)
      (undecodable! {:yin.k/kind kind :yin.k/path [:yin.k/kind]}))
    (when-not (and (map? cells) (map? code))
      (undecodable! {:yin.k/path [:yin.k/cells]}))
    (doseq [[cid cell] cells]
      (when-not (and (map? cell) (marker? (:yin.k/stream cell))
                     (contains? cell :yin.k/position))
        (undecodable! {:yin.k/cell cid :yin.k/path [:yin.k/cells cid]})))
    (doseq [[a v] code]
      (when-not (= a (ucf/code-address v))
        (refuse! :yin.k/hash-mismatch {:yin.k/segment a}))
      (when-some [defect (code/well-formed-vector? v)]
        (undecodable! {:yin.k/segment a :yin.k/pc (:pc defect)
                       :yin.k/defect defect})))
    (validate-frames body code cells)
    (when (contains? body :yin.k/frames)
      (when (= :halted kind)
        (undecodable! {:yin.k/path [:yin.k/frames]})))
    (when (and (= :blocked kind)
               (not (seq (or (:yin.k/frames body) []))))
      (undecodable! {:yin.k/path [:yin.k/frames]
                     :yin.k/kind :blocked-without-frames}))
    (when (contains? body :yin.k/result)
      (when-not (= :halted kind)
        (undecodable! {:yin.k/path [:yin.k/result]})))
    (when (= :halted kind)
      (when-not (contains? body :yin.k/result)
        (undecodable! {:yin.k/path [:yin.k/result]})))
    (when (= :parked kind)
      (when-not (contains? (:yin.k/parked body)
                           (:yin.k/parked-id body))
        (undecodable! {:yin.k/parked-id (:yin.k/parked-id body)
                       :yin.k/path [:yin.k/parked-id]})))
    (doseq [[pid r] (:yin.k/parked body)]
      (let [path [:yin.k/parked pid]]
        (validate-registers code r path)
        ;; a parked record IS an explicit park: its resume pc must sit
        ;; at the one safepoint kind whose meaning is the park itself,
        ;; so a record steered onto an ordinary instruction pc never
        ;; restores as a continuation
        (let [at (ucf/safepoint-at (get code (:yin.k/segment r))
                                   (:yin.k/pc r))]
          (when (or (contains? at :yin.k/status)
                    (not (contains? (set (:yin.safepoint/kinds at))
                                    :explicit-park)))
            (undecodable! {:yin.k/parked-id pid
                           :yin.k/pc (:yin.k/pc r)
                           :yin.k/path path})))))
    (doseq [[m inst] (:yin.k/installs body)]
      (when-not (and (contains? inst :yin.k/response)
                     (map? (:yin.k/child inst))
                     (true? (get (:yin.k/child inst) handoff-tag)))
        (undecodable! {:yin.k/name m
                       :yin.k/path [:yin.k/installs m]}))
      ;; a child is a whole body: validated with the same grammar
      ;; before anything of the parent is restored.  Decoded bytes
      ;; are trees, so the recursion terminates.
      (validate-body (:yin.k/child inst)))
    (let [referenced (referenced-cells body)
          missing (remove #(contains? cells %) referenced)
          extra (remove #(contains? referenced %) (keys cells))]
      (when-some [cid (first missing)]
        (undecodable! {:yin.k/cell cid :yin.k/path [:yin.k/cells]}))
      (when-some [cid (first (sort-by str extra))]
        (undecodable! {:yin.k/cell cid :yin.k/kind :extra-cell
                       :yin.k/path [:yin.k/cells cid]})))
    (when (and (seq cells)
               (not (contains?
                      (get-in (or (:yin.k/requires body) {})
                              [:yin.k/cursor-profiles])
                      :dao.stream.remote/v1)))
      (unsatisfied! nil))
    body))


;; =============================================================================
;; Lower (14.1.2): a fresh task over the receiver's own coordinates
;; =============================================================================

(def ^:private fixed-resource-keys
  "The VM's fixed private resource keys, never allocated by the lower
   (`yin.vm.ucf.remote`'s discipline)."
  #{vm/call-in-stream-key vm/call-out-stream-key
    vm/call-out-cursor-key module/link-request-resource
    module/link-response-resource})


(defn- fresh-key
  [used]
  (loop [n (inc (count @used))]
    (let [k (keyword "yin.k" (str "k-" n))]
      (if (contains? @used k)
        (recur (inc n))
        (do (swap! used conj k) k)))))


(defn- body-markers
  "Every stream marker the body's encoded roots name: the cells' own
   streams, the pendings' endpoint markers, and every marker a store,
   a register, a parked record, a closure or the halt result carries.
   A stream referenced only there is attached exactly like one a
   pending names -- collecting less would have the decoder refuse a
   reference the encoder minted.  `attach-all!` deduplicates by
   stream identity."
  [body]
  (mapcat #(tagged-of :yin.k/stream %)
          (reachable-values body)))


(defn- attach-all!
  "One attach per distinct stream identity, through `attach!`; the
   first refusal -- a reflection marked gone included -- is
   :yin.k/unsatisfied naming that identity."
  [attach! markers]
  (reduce (fn [acc mkr]
            (let [identity (:dao.stream/identity mkr)]
              (if (contains? acc identity)
                acc
                (if-some [h (ucf.remote/lower-marker attach! mkr)]
                  (assoc acc identity h)
                  (unsatisfied! identity)))))
          {}
          markers))


(defn- value-decoder
  "The decode half, over the receiver's coordinates: literals back to
   maps, closures through the kernel against the attached code,
   primitives from the receiver's own registry under an equal profile,
   and stream and cursor markers as fresh references sealed under the
   receiving task's own secret."
  [recv streams cells]
  (letfn [(decode
            [x]
            (cond
              (scalar? x) x
              (map? x)
              (case (:yin.k/tag x)
                :yin.k/literal
                (let [e (:yin.k/entries x)]
                  (if (even? (count e))
                    (into {}
                          (map (fn [[k v]] [(decode k) (decode v)]))
                          (partition 2 e))
                    (undecodable! {:yin.k/tag :yin.k/literal})))
                :yin.k/closure (module/lower-closure recv x decode)
                :yin.k/primitive
                (let [n (:yin.k/name x)
                      entry (get (:primitives recv) n)]
                  (if (and (some? entry)
                           (= (:yin.k/profile x)
                              (:yin.k/profile
                                (vm/profile-of (:primitives recv) n))))
                    (vm/primitive-function entry)
                    (refuse! :yin.k/unsatisfied {:yin.k/name n})))
                :yin.k/stream
                (if-some [k (get streams (:dao.stream/identity x))]
                  (engine/issue-ref recv :stream-ref k)
                  (undecodable! {:dao.stream/identity
                                 (:dao.stream/identity x)}))
                :yin.k/cursor-ref
                (if-some [k (get cells (:yin.k/cell x))]
                  (engine/issue-ref recv :cursor-ref k)
                  (undecodable! {:yin.k/cell (:yin.k/cell x)}))
                (undecodable! {:yin.k/tag (:yin.k/tag x)}))
              (vector? x) (mapv decode x)
              (set? x) (into #{} (map decode) x)
              (seq? x) (apply list (map decode x))
              :else (undecodable! {:yin.k/kind :host-object})))]
    decode))


(defn- spawn-child-template
  "A fresh child VM of the receiver's composition for one install
   response -- the engine's own spawn discipline (yin.vm.linker.md
   7.3): nothing of the receiver's state but its composition values,
   a fresh origin tag, and the secret the composition's source mints
   for it."
  [recv response module-name]
  (let [n (or (:origins recv) 0)
        origin (keyword (str (name (or (:origin recv) :t0)) "." n))
        registry (reduce (fn [r [m e]]
                           (module/assoc-module r m (dissoc e :bindings)))
                         (:modules recv)
                         (module/module-entries (:modules recv)))
        source (:secret-source recv)
        child (module/spawn-module
                recv
                (:value (:image response))
                {:modules registry
                 :origin origin
                 :ancestry (conj (vec (:ancestry recv)) module-name)
                 :capability-secret (when (fn? source) (source origin))})]
    (assoc child :origins (inc n))))


(defn- resume-installs
  "Each install child resumed into a fresh child of the receiver's
   composition, its phase and response carried: the receiver's own
   scheduler steps it exactly as the source's did.  A child that
   refuses -- its bytes undecodable, a stream it names unattachable
   -- aborts the parent's restoration: the refusal is thrown here,
   before any machine value is assembled, so the parent never answers
   :ok over a child that did not lower."
  [recv body attach! opts]
  (into {}
        (map (fn [[m inst]]
               (let [response (:yin.k/response inst)
                     child-body (:yin.k/child inst)
                     resumed (resume-task
                               (if-some [spawn (:child-of opts)]
                                 (spawn recv response m)
                                 (spawn-child-template recv response m))
                               (cbor/encode child-body)
                               attach!
                               opts)]
                 (when (not= :ok (:status resumed))
                   (refuse! (:yin.k/status resumed)
                            (dissoc resumed :yin.k/status)))
                 [m {:phase (or (:yin.k/phase inst) :running)
                     :parent (:yin.k/parent inst)
                     :response response
                     :vm (:vm resumed)}])))
        (:yin.k/installs body)))


(defn- link-pair-resources
  "The engine's fixed link keys mapped onto the reflections a lowered
   link wait uses, so a migrated link wait parks where the engine's
   own sweep polls it."
  [streams resources pendings]
  (reduce (fn [acc pending]
            (if (contains? #{:link-request :link-response}
                           (:yin.k/reason pending))
              (let [req (get resources
                             (streams (get-in pending
                                              [:yin.k/request
                                               :dao.stream/identity])))
                    resp (get resources
                              (streams (get-in pending
                                               [:yin.k/response
                                                :dao.stream/identity])))]
                (if (and req resp)
                  (assoc acc
                         module/link-request-resource req
                         module/link-response-resource resp)
                  acc))
              acc))
          {}
          pendings))


(defn- lower-frame!
  "One pending lowered back into the engine's own wait-set entry
   shape, the receiver's register context riding verbatim under the
   fields the variant rebuilds (`yin.vm.ucf.remote`'s shapes, through
   this lower's own fresh keys)."
  [decode streams cells cell-keys pending ctx]
  (let [stream-of (fn [marker]
                    (streams (:dao.stream/identity marker)))
        cell-stream (fn [cid]
                      (stream-of (get-in cells [cid :yin.k/stream])))
        cell-key (fn [cid] (get cell-keys cid))]
    (case (:yin.k/reason pending)
      :next
      (merge ctx
             {:reason :next
              :stream-id (cell-stream (:yin.k/cell pending))
              :cursor-ref {:type :cursor-ref
                           :id (cell-key (:yin.k/cell pending))}})

      :ffi
      (merge ctx
             (cond-> {:reason :next
                      :call-id (:yin.k/call-id pending)
                      :stream-id (cell-stream (:yin.k/cell pending))
                      :cursor-ref {:type :cursor-ref
                                   :id (cell-key (:yin.k/cell pending))}}
               (contains? pending :yin.k/op)
               (assoc :op (:yin.k/op pending))))

      :put
      (merge ctx
             {:reason :put
              :stream-id (stream-of (:yin.k/stream pending))
              :datom (decode (:yin.k/value pending))})

      :ffi-request
      (merge ctx
             {:reason :put
              :request-sent true
              :call-id (:yin.k/call-id pending)
              :op (:yin.k/request-op pending)
              :stream-id (stream-of (:yin.k/request pending))
              :datom (decode (:yin.k/request-envelope pending))
              :response-cursor (cell-key (:yin.k/response-cell pending))
              :response-stream (stream-of (:yin.k/response pending))})

      (:link-request :link-response)
      (let [cid (:yin.k/cell pending)]
        (merge ctx
               (cond-> {:reason (:yin.k/reason pending)
                        :link-id (:yin.k/link-id pending)
                        :name (:yin.k/name pending)
                        :request module/link-request-resource
                        :response module/link-response-resource
                        :cursor (:yin.k/position (get cells cid))}
                 (= :link-request (:yin.k/reason pending))
                 (assoc :envelope (decode (:yin.k/envelope pending))))))

      :install
      (merge ctx {:reason :install :name (:yin.k/name pending)})

      (undecodable! {:yin.k/reason (:yin.k/reason pending)}))))


(defn resume-task
  "The lower of 14.1.2: `bytes`, a handoff body this namespace minted,
   resumed into a fresh task over `recv` -- a machine of the receiver's
   own composition.  Answers `{:status :ok :kind k :vm resumed}` or the
   first data refusal: `:yin.k/undecodable` bytes or grammar,
   `:yin.k/profile-mismatch` stamp or version, `:yin.k/hash-mismatch`
   code, `:yin.k/unsatisfied` a stream that cannot be attached or a
   primitive the receiver cannot answer.  Nothing partially runnable
   is ever exposed: the machine value is assembled only after every
   restoration step passes.  `attach!` is the receiver's own
   `:dao.stream/attach` dispatch, exactly `yin.vm.ucf.remote`'s; the
   resumed task is a fork until stage 2."
  ([recv bytes attach!]
   (resume-task recv bytes attach! nil))
  ([recv bytes attach! opts]
   (call!
     (fn []
       (let [body (try (cbor/decode bytes)
                       (catch #?(:cljd Object :clj Throwable
                                 :cljs :default)
                              _
                         (undecodable! {:yin.k/kind :bytes})))
             _ (validate-body body)
             _ (when-not (= ucf/contract-stamp
                            (:yin.k/contract body))
                 (refuse! :yin.k/profile-mismatch
                          {:yin.k/contract (:yin.k/contract body)}))
             kind (:yin.k/kind body)
             cells (:yin.k/cells body)
             pendings (mapv :yin.k/pending
                            (or (:yin.k/frames body) []))
             recv' (reduce (fn [r [_a v]] (module/attach-module r v))
                           recv
                           (:yin.k/code body))
             handles (attach-all! attach! (body-markers body))
             used (atom (into fixed-resource-keys
                              (keys (:resources recv))))
             streams (into {}
                           (map (fn [identity]
                                  [identity (fresh-key used)]))
                           (sort-by str (keys handles)))
             resources (into {}
                             (map (fn [[identity k]]
                                    [k (get handles identity)]))
                             streams)
             cell-keys (into {}
                             (map (fn [cid] [cid (fresh-key used)]))
                             (sort-by str (keys cells)))
             cell-resources (into {}
                                  (map (fn [[cid k]]
                                         [k
                                          {:stream-id
                                           (streams
                                             (get-in cells
                                                     [cid :yin.k/stream
                                                      :dao.stream/identity]))
                                           :cursor
                                           (get-in cells
                                                   [cid
                                                    :yin.k/position])}]))
                                  cell-keys)
             decode (value-decoder recv' streams cell-keys)
             aliases (:code-aliases recv')
             ;; the isolated slice installs through `store-put`, so a
             ;; reserved key inside a tampered body refuses here exactly
             ;; as a program write would (Rule R), before the checked
             ;; store lands on the receiver in one write
             store (reduce (fn [s [k v]]
                             (engine/store-put s (decode k) (decode v)))
                           (or (:store recv') {})
                           (:yin.k/store body))
             module-stores (into {}
                                 (map (fn [[m s]]
                                        [m (into {}
                                                 (map (fn [[k v]]
                                                        [(decode k)
                                                         (decode v)]))
                                                 s)]))
                                 (:yin.k/module-stores body))
             parked (into {}
                          (map (fn [[pid r]]
                                 [pid (assoc
                                        (decode-registers decode aliases r)
                                        :type :parked-continuation
                                        :id pid)]))
                          (:yin.k/parked body))
             installs (resume-installs recv' body attach! opts)
             registers (mapv (fn [frame]
                               (decode-registers decode aliases
                                                 (:yin.k/registers frame)))
                             (or (:yin.k/frames body) []))
             entries (mapv (fn [pending ctx]
                             (lower-frame! decode streams cells cell-keys
                                           pending ctx))
                           pendings registers)
             machine (-> recv'
                         (assoc :store store
                                :module-stores (merge (:module-stores recv')
                                                      module-stores)
                                :parked (merge (:parked recv') parked)
                                :installs (merge (:installs recv') installs)
                                :resources (merge (:resources recv')
                                                  resources
                                                  cell-resources
                                                  (link-pair-resources
                                                    streams resources
                                                    pendings))
                                :id-counter (max (or (:id-counter recv') 0)
                                                 (or (:yin.k/id-counter body)
                                                     0))
                                :ready-queue []
                                :control nil
                                :k nil))]
         {:status :ok
          :kind kind
          :vm (case kind
                :halted
                (assoc machine
                       :halted? true :blocked? false
                       :value (decode (:yin.k/result body))
                       :wait-set [])

                :parked
                (assoc machine
                       :halted? true :blocked? false
                       :value (get parked (:yin.k/parked-id body))
                       :wait-set [])

                (assoc machine
                       :halted? false :blocked? true
                       :value :yin/blocked
                       :wait-set entries))})))))
