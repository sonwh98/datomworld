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

   `resume-task` is the lower, and its reader is version-aware (M-next
   D7): the version-1 decode is tried first -- `dao.jing.cbor` canonical
   bytes, whose decoder refuses the stream codec's tag-39 identifiers --
   then the stage-1 `dao.stream.cbor` decode; the codec that accepted
   the bytes decides which body version they may carry (version 1 in
   jing bytes, version 0 in stream bytes, enforced as
   `:yin.k/profile-mismatch` before the address check), a version-1
   body then runs the custody inspector (`yin.vm.ucf.checkpoint`) over
   its claimed address, and a failure at any step is never
   reinterpreted through the other codec.  It checks stamp, grammar,
   references and code hashes before anything is installed;
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
   Version 0 resumes as a fork. Version 1 restores a running root only
   with composition-supplied grant evidence and protection declarations;
   halted results restore ended, and install children carry the gate only.

   Halt is a result, never a frame: the halted body carries the result
   and no wait at all.  An explicit park is the no-wait shape: the
   parked record travels in the scheduler slice under its own id and
   no pending is minted for it.  An install waiter travels beside its
   whole child -- the child's own handoff body, phase and response --
   or the export refuses; an install name alone reconstructs nothing."
  (:require [clojure.set :as set]
            [dao.jing :as jing]
            [dao.jing.cbor :as jing.cbor]
            [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [dao.stream.cbor :as cbor]
            [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.ast-walker :as walker]
            [yin.vm.completion :as completion]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-effects :as reffects]
            [yin.vm.debruijn.register :as register]
            [yin.vm.debruijn.stack :as stack]
            [yin.vm.engine :as engine]
            [yin.vm.integer.host :as integer-host]
            [yin.vm.module :as module]
            [yin.vm.ucf :as ucf]
            [yin.vm.ucf.checkpoint :as checkpoint]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.remote :as ucf.remote]
            [yin.vm.values :as values]))


(def handoff-tag
  "The S7.5.1 tag of one handoff body."
  :yin.k/handoff)


(def handoff-version
  "The body versions this namespace's reader speaks: version 0, the
   frozen stage-1 wire the lower still emits, version 1, the custody
   amendment (section 14.3) whose canonical bytes are dao.jing.cbor's,
   and version 2, the four-profile amendment
   (docs/design/yin.vm.universal-continuation-format.v2-amendment.md),
   also in dao.jing.cbor bytes.  Which a body may carry is decided by
   the codec that accepted its bytes -- the version-aware reader's gate,
   before the tag grammar and before any address or restoration check --
   and a body of any other version, absent or not of the integer kind,
   is refused with :yin.k/profile-mismatch.  Version 2 is dispatched
   by exact integer kind and structural role, never by `>= 1`: it is
   selected at export by the composition's `:version 2` option."
  #{0 1 2})


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
  (or (nil? x) (boolean? x)
      (and (number? x) (not (integer-host/big-carrier? x)))
      (string? x) (keyword? x) (symbol? x)))


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
   private table does not hold is a forged reference; `serve!` here is
   the keyed form `(serve! id handle)` of `export-task`, so the answer
   is retained under the resource id, never under the handle.  A stream the
   table cannot serve is unsatisfied, naming the identity where one is
   known.  Two resources of one identity mint two markers that name
   the one identity -- the lower attaches per identity, once.  Under
   the ordering scratch the marker is seeded by the resource id and
   nothing is asked: a sort comparison serves no stream and mints no
   identity, exactly as it mints no cell number."
  [vm serve! found id]
  (if (:ordering @found)
    (or (get-in @found [:markers id])
        (let [m {:yin.k/tag :yin.k/stream
                 :dao.stream/identity id
                 :dao.stream/descriptor {:yin.k/hint :ordering}}]
          (swap! found assoc-in [:markers id] m)
          m))
    (let [handle (get (:resources vm) id)]
      (if (nil? handle)
        (non-portable! :forged-resource-reference {:yin.k/hint id})
        (or (get-in @found [:markers id])
            (if-let [marker (ucf.remote/lift-marker (partial serve! id)
                                                    handle)]
              (do (swap! found assoc-in [:markers id] marker) marker)
              (unsatisfied! (local-identity handle))))))))


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
   hashes.  Under the ordering scratch (`:ordering`) the id IS the key
   -- the resource id, or the position pair, the very identity the cell
   table itself keys by -- so a sort comparison never mints a number
   and no traversal order can change which distinct cursor sorts
   first."
  [vm serve! found key stream-id kept]
  (or (get-in @found [:cell-of key])
      (let [marker (marker-for! vm serve! found stream-id)]
        (if (some? (portable-cursor kept))
          (let [cid (if (:ordering @found)
                      key
                      (or (get-in @found [:recovery-cell-ids key])
                          (keyword "yin.k" (str "c-" (count (:cells @found))))))]
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
;; The found map and the canonical order of a version-1 export
;; =============================================================================

(defn- new-found
  []
  {:cells {} :cell-of {} :markers {} :stores #{} :segments #{}})


(defn- ordered
  "`coll` in the order the export walks it: by the canonical bytes of
   `(keyfn x)`'s encoding under a version-1 export, so cell numbering
   never depends on a host's map or set iteration; as given under
   version 0, whose wire is frozen."
  [found keyfn coll]
  (if-some [order (:order @found)]
    (order keyfn coll)
    coll))


(declare value-encoder registers-v2)


(defn- canonical-order
  "The ordering function of a version-1 export: `(order keyfn coll)`
   sorts `coll` by the canonical bytes of the scratch encoding of each
   `(keyfn x)`.  The scratch encoder owns a found map of its own, so it
   mints nothing the export keeps; it runs under `:ordering`, so every
   cell its keys meet is seeded by the identity the cell table keys by,
   never by a number a comparison could shift, and it orders its own
   nested maps the same way.  Ties keep the walk order given: wait
   order and alias identity are never reordered."
  [vm serve! v2]
  (let [self (volatile! nil)
        order (fn [keyfn coll]
                (sort-by (comp @self keyfn) jing.cbor/encoded-compare coll))
        encode (value-encoder vm serve!
                              (atom (cond-> (assoc (new-found)
                                                   :order order
                                                   :v1 true
                                                   :ordering true)
                                      v2 (assoc :v2 v2))))]
    (vreset! self encode)
    order))


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
              ;; the canonical codec's numeric carriers (Float64, Decimal,
              ;; Rational and the like, none of them `number?` on every
              ;; host) are version-1 content: the jing body carries them
              ;; as-is, and the stream codec's frozen version-0 domain --
              ;; `portable-value?` -- does not admit them
              (jing.cbor/numeric? x)
              (if (:v1 @found)
                x
                (non-portable! :host-object
                               {:yin.k/hint
                                #?(:cljd (str (.-runtimeType x))
                                   :default (str (type x)))}))
              (values/host-typed? x)
              (cond
                (not (values/owned-by? x (:owner vm)))
                (non-portable! :foreign-value {:yin.k/hint (values/kind-of x)})
                (values/closure? x)
                (let [marker (module/lift-closure vm (values/payload x)
                                                  encode)]
                  (swap! found
                         (fn [acc]
                           (let [acc (-> acc
                                         (update :bound (fnil into #{})
                                                 (keys (:yin.k/env marker)))
                                         (update :segments
                                                 conj (:yin.k/segment marker)))]
                             (if-some [m (:yin.k/store-of marker)]
                               (update acc :stores conj m)
                               acc))))
                  marker)
                (and (:v2 @found) (values/continuation? x))
                {:yin.k/tag :yin.k/frame
                 :yin.k/registers (registers-v2 (:engine (:v2 @found))
                                                found encode vm
                                                (values/payload x))}
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
                (if (:v2 @found)
                  (do (swap! found update :parked-refs (fnil conj #{}) (:id x))
                      {:yin.k/tag :yin.k/frame
                       :yin.k/parked-id (:id x)})
                  (non-portable! :non-canonicalizable
                                 {:yin.k/hint (:type x)}))
                :cell-ref
                (non-portable! :cell {:yin.k/hint (:id x)})
                {:yin.k/tag :yin.k/literal
                 :yin.k/entries
                 (into []
                       (mapcat (fn [[k v]] [(encode k) (encode v)]))
                       (ordered found key x))})
              (vector? x) (mapv encode x)
              (set? x) (into #{} (map encode) (ordered found identity x))
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
  (into {} (map (fn [[k v]] [k (encode v)])) (ordered found key env)))


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
        (ordered found key frame)))


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


(declare observed-wire* row-children)


(defn- walker-ffi-frame
  "The FFI wrapper frame the walker entry `entry` rides on top of, or
   nil: a response reader's `eval-call` carrying its call id, or a
   retained request's `request-sent`."
  [entry]
  (let [k (:k entry)]
    (case (:type k)
      :dao.stream.apply/eval-call (when (contains? k :call-id) k)
      :dao.stream.apply/request-sent k
      nil)))


(defn- entry-call-id
  "The FFI call id of `entry`: its own under the positional and
   semantic profiles, the wrapper frame's under the walker."
  [engine entry]
  (if (= :walker engine)
    (let [k (walker-ffi-frame entry)]
      (or (:call-id k) (:parked-id k)))
    (:call-id entry)))


(defn- observed-wire
  "The wire variant the observed entry itself carries, from its shape
   alone: a `:next` wait with a call id is a sent FFI response reader;
   a `:put` wait holding its request is a retained call.  The walker
   carries that identity in its wrapper frame.  nil when the entry is no
   variant at all."
  ([entry] (observed-wire entry nil))
  ([entry engine]
   (if (= :walker engine)
     (case (:reason entry)
       :next (if (= :dao.stream.apply/eval-call (:type (walker-ffi-frame entry)))
               :ffi :next)
       :put (if (= :dao.stream.apply/request-sent
                   (:type (walker-ffi-frame entry)))
              :ffi-request :put)
       (observed-wire entry nil))
     (observed-wire* entry))))


(defn- observed-wire*
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
;; What cannot lift at all (UCF 7.4.1, 7.4.3): holds with no wire form
;; =============================================================================

(defn- held?
  [x]
  (and (map? x) (contains? x :yin.k/held)))


(defn- unliftable-hold
  "The first thing `vm` holds that has no wire pending variant at any
   version, as the data the refusal carries, or nil: a held observation
   -- an `:observe` entry, or an entry or cursor cell carrying
   `:yin.k/held` --, a reachable unminted cursor cell, a pending close,
   or a `:link-request` entry whose response cursor is not installed.
   Entering exporting refuses the same holds over the whole task (D8);
   the lift refuses them here, before `lift-pending!`, so its
   undecodable fall-through is never reached for these, in the root and
   in every install child through the recursive export."
  [vm]
  (let [waits (:wait-set vm)
        cells (filter map? (vals (:resources vm)))]
    (cond
      (some #(= :observe (:reason %)) waits)
      {:yin.k/hold :observe}

      (or (some held? waits) (some held? cells))
      {:yin.k/hold :held}

      (some :yin.k/unminted cells)
      {:yin.k/hold :unminted-cursor}

      (seq (:yin.k/closes vm))
      {:yin.k/hold :pending-close}

      (some #(and (= :link-request (:reason %)) (not (contains? % :cursor)))
            waits)
      {:yin.k/hold :link-cursor-not-installed})))


;; =============================================================================
;; Protection (UCF 7.4.3, 7.7.8): the operation id against the enrolled set
;; =============================================================================

(defn- protected!
  "`pending` under a version-1 export's protection rules.  A retained
   `:put`, `:ffi-request` or `:link-request` entry whose target is in
   the header's enrolled set must carry an operation id -- one attempted
   without it cannot be made exactly-once afterwards, so the lift
   refuses `:unprotected-pending` -- and an entry carrying an id whose
   target is not enrolled is unsatisfied, naming the stream: protection
   is neither added to an attempted write nor dropped from one.  The id
   is the entry's `:op-id`, copied to `:yin.k/op-id`.  Version 0 names
   no custody and returns `pending` as it was."
  [found entry pending]
  (let [reason (:yin.k/reason pending)]
    (if-not (and (:v1 @found)
                 (contains? #{:put :ffi-request :link-request} reason))
      pending
      (let [target (:dao.stream/identity
                     (get pending (if (= :put reason)
                                    :yin.k/stream
                                    :yin.k/request)))
            enrolled? (contains? (:enrolled @found) target)
            carried? (some? (:op-id entry))]
        (cond
          (and enrolled? (not carried?))
          (non-portable! :unprotected-pending {:dao.stream/identity target})

          (and carried? (not enrolled?))
          (unsatisfied! target)

          carried?
          (assoc pending :yin.k/op-id (:op-id entry))

          :else pending)))))


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
  (case (observed-wire entry (:engine (:v2 @found)))
    :next
    {:yin.k/reason :next
     :yin.k/cell (cell-for! vm serve! found
                            (get-in entry [:cursor-ref :id]))}

    :ffi
    (cond-> {:yin.k/reason :ffi
             :yin.k/call-id (entry-call-id (:engine (:v2 @found)) entry)
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
    (let [route (if (and (:response-cursor entry)
                         (:response-stream entry)
                         (:yin.k/recovery-cell-ids vm))
                  {:id (:response-cursor entry)
                   :stream-id (:response-stream entry)}
                  (call-out-cell (:resources vm)))]
      (if (nil? route)
        (unsatisfied!)
        (let [envelope (:datom entry)]
          {:yin.k/reason :ffi-request
           :yin.k/call-id (entry-call-id (:engine (:v2 @found)) entry)
           :yin.k/request-envelope (encode envelope)
           :yin.k/request-op (or (apply2/request-op envelope)
                                 (:op entry)
                                 (:op (:k entry)))
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


(declare pc-profile? site-op site-reasons module-decls)


(defn- lift-frame!
  "One wait entry lifted: its registers and its pending, the dynamic
   reason checked against the static kinds at its pc -- the kinds gate
   eligibility only, and a variant they do not admit is evidence the
   frame is not exportable here, never a wire reason to invent."
  [vm serve! found encode entry]
  (let [engine (:engine (:v2 @found))
        pc? (pc-profile? engine)
        walker? (= :walker engine)
        kinds (when-not (or pc? walker?)
                (safepoint-kinds-at vm (:segment entry) (:pc entry)))
        wire (observed-wire entry engine)]
    (when (nil? wire)
      (undecodable! {:yin.k/reason (:reason entry)
                     :yin.k/path [:yin.k/wait (:pc entry)]}))
    (when-not (cond
                walker? true
                pc? (contains? (get site-reasons (site-op engine entry)) wire)
                :else (some #(contains? (get wire-of-kind %) wire) kinds))
      (non-portable! :reason-mismatch
                     {:yin.k/pc (:pc entry)
                      :yin.k/reason wire
                      :yin.safepoint/kinds (vec kinds)}))
    {:yin.k/registers (if-some [v2 (:v2 @found)]
                        (registers-v2 (:engine v2) found encode vm entry)
                        (encode-registers found encode vm entry))
     :yin.k/pending (protected! found entry
                                (lift-pending! vm serve! found encode
                                               entry))}))


;; =============================================================================
;; The install children: a whole child travels, or nothing does
;; =============================================================================

(declare export-task validate-body validate-body-v1 validate-body-v2
         v2-body? resume-task resume-task*)


(defn- portable-response?
  "True when install `response` survives the body's own codec."
  [v1? response]
  (if v1?
    (try (jing.cbor/encode response) true
         (catch #?(:cljd Object :clj Throwable :cljs :default) _ false))
    (cbor/portable-value? response)))


(defn- export-installs
  "Each live install child of `vm` as its own handoff body beside the
   response that spawned it, preserving the child's phase and waits
   (14.1.1's :install row).  A child that cannot export itself refuses
   the parent's export -- a refusal is thrown, never embedded -- and a
   response outside the canonical bytes domain refuses it too.  Under
   version 1 the child is a part of its root's task: it carries the
   root's version and enrolled set, no header, and its phase, a wire
   phase (`:running` or `:parked`), and parent always travel."
  [vm serve! opts]
  (let [v1? (::v1 opts)]
    (into {}
          (map (fn [[m {:keys [vm phase parent response]}]]
                 (when-not (portable-response? v1? response)
                   (non-portable! :install-response {:yin.k/name m}))
                 (when (and v1? (not (contains? #{:running :parked} phase)))
                   (non-portable! :incomplete-install
                                  {:yin.k/name m :yin.k/phase phase}))
                 (let [r (export-task vm serve!
                                      (assoc opts
                                             :path (conj (:path opts) m)
                                             ::child true))]
                   (when (not= :ok (:status r))
                     (refuse! (:yin.k/status r) (dissoc r :status)))
                   [m {:yin.k/phase phase
                       :yin.k/parent parent
                       :yin.k/response response
                       :yin.k/child (:body r)}])))
          (:installs vm))))


;; =============================================================================
;; Version 2 lift: one adapter per execution profile (UCF v2 amendment)
;; =============================================================================

(defn- engine-of
  "The version-2 engine of `vm`, from the kernel's own link format: the
   profile is the kernel's, never inferred from an image's shape."
  [vm]
  (case (:format (module/link-format vm))
    :yin.semantic/code :semantic
    :yin.ast/code :walker
    :yin.debruijn.code :stack
    :yin.debruijn.register :register
    (non-portable! :unsupported-kernel {})))


(defn- encode-env-v2
  "One version-2 environment E: the bindings by name, values encoded,
   and the module store it names when it names one, recorded as a store
   the body must snapshot."
  [found encode env]
  (let [m (get env engine/store-of-key)]
    (when (some? m) (swap! found update :stores conj m))
    (swap! found update :bound (fnil into #{})
           (keys (engine/without-store-of (or env {}))))
    (cond-> {:yin.k/bindings
             (into {}
                   (map (fn [[k v]] [k (encode v)]))
                   (ordered found key (engine/without-store-of (or env {}))))}
      (some? m) (assoc :yin.k/store-of m))))


(defn- semantic-frame-v2
  [found encode vm frame]
  (when-not (and (= :return (:type frame))
                 (= #{:type :segment :pc :env :stack-base}
                    (set (keys frame))))
    (non-portable! :inconsistent-state {:yin.k/hint :frame}))
  {:yin.k/type :return
   :yin.k/segment (seg-address! found vm (:segment frame))
   :yin.k/pc (:pc frame)
   :yin.k/env (encode-env-v2 found encode (:env frame))
   :yin.k/stack-base (:stack-base frame)})


(defn- stack-payload-rows
  "The prefix of `vm`'s offset table a stack `payload` was captured
   under: the shortest one whose lengths sum to the payload's code
   space, which must be reproduced by that prefix and by its hash."
  [vm {:keys [segment] :as payload}]
  (let [rows (vec (:images vm))
        n (count segment)
        prefix (some (fn [i]
                       (let [[_ off len] (nth rows (dec i))]
                         (when (= n (+ off len)) (subvec rows 0 i))))
                     (range 1 (inc (count rows))))]
    (when (or (nil? prefix)
              (nil? (stack/layout-images segment prefix))
              (not= (:hash payload) (dcode/image-hash segment)))
      (non-portable! :inconsistent-state {:yin.k/hint :layout}))
    prefix))


(defn- stack-registers-v2
  [found encode vm payload]
  (let [layout (mapv first (stack-payload-rows vm payload))
        store! (fn [m]
                 (when (some? m) (swap! found update :stores conj m))
                 m)]
    (swap! found update :segments into layout)
    (cond-> {:yin.k/layout layout
             :yin.k/image (:image payload)
             :yin.k/pc (:pc payload)
             :yin.k/frames (mapv #(mapv encode %) (:frames payload))
             :yin.k/stack (mapv encode (:stack payload))
             :yin.k/continuation
             (mapv (fn [f]
                     (when-not (every? #{:return-pc :frames :stack-base
                                         :store-of}
                                       (keys f))
                       (non-portable! :inconsistent-state
                                      {:yin.k/hint :frame}))
                     (cond-> {:yin.k/return-pc (:return-pc f)
                              :yin.k/frames (mapv #(mapv encode %)
                                                  (:frames f))
                              :yin.k/stack-base (:stack-base f)}
                       (some? (:store-of f))
                       (assoc :yin.k/store-of (store! (:store-of f)))))
                   (:continuation payload))}
      (some? (:store-of payload))
      (assoc :yin.k/store-of (store! (:store-of payload))))))


(defn- register-payload-rows
  "The prefix of `vm`'s offset table a register `payload` was captured
   under: the shortest one whose lengths sum to the payload's code
   space, reproduced by that prefix and by its hash."
  [vm {:keys [segment] :as payload}]
  (let [rows (vec (:images vm))
        n (count (:instructions segment))
        prefix (some (fn [i]
                       (let [[_ off len] (nth rows (dec i))]
                         (when (= n (+ off len)) (subvec rows 0 i))))
                     (range 1 (inc (count rows))))]
    (when (or (nil? prefix)
              (nil? (register/layout-images segment prefix))
              (not= (:hash payload) (rcode/register-hash segment)))
      (non-portable! :inconsistent-state {:yin.k/hint :layout}))
    prefix))


(defn- register-registers-v2
  [found encode vm payload]
  (let [layout (mapv first (register-payload-rows vm payload))
        store! (fn [m]
                 (when (some? m) (swap! found update :stores conj m))
                 m)
        pairs (fn [regs] (mapv (fn [[r x]] [r (encode x)]) regs))
        frames (fn [fs] (mapv #(mapv encode %) fs))]
    (swap! found update :segments into layout)
    (cond-> {:yin.k/layout layout
             :yin.k/image (:image payload)
             :yin.k/site-pc (:site-pc payload)
             :yin.k/pc (:pc payload)
             :yin.k/frames (frames (:frames payload))
             :yin.k/regs (pairs (:regs payload))
             :yin.k/live (:live payload)
             :yin.k/continuation
             (mapv (fn [f]
                     (when-not (every? #{:site-pc :return-pc :frames :regs
                                         :live :dest :store-of}
                                       (keys f))
                       (non-portable! :inconsistent-state
                                      {:yin.k/hint :frame}))
                     (cond-> {:yin.k/site-pc (:site-pc f)
                              :yin.k/return-pc (:return-pc f)
                              :yin.k/frames (frames (:frames f))
                              :yin.k/regs (pairs (:regs f))
                              :yin.k/live (:live f)
                              :yin.k/dest (:dest f)}
                       (some? (:store-of f))
                       (assoc :yin.k/store-of (store! (:store-of f)))))
                   (:continuation payload))
             :yin.k/dest (:dest payload)
             :yin.k/resume-mode (:resume-mode payload)}
      (some? (:store-of payload))
      (assoc :yin.k/store-of (store! (:store-of payload))))))


(defn- node-row!
  "The root row id of AST `node` through the v3 row codec, its rows
   joining the export's own.  Extra AST metadata never travels; an AST
   the codec cannot project is not portable."
  [found node]
  (let [{:keys [root rows]}
        (try (vm/ast->semantic-bytecode node)
             (catch #?(:cljd Object :clj Throwable :cljs :default) _
               (non-portable! :non-canonicalizable {:yin.k/hint :ast})))]
    (swap! found (fn [acc]
                   (-> acc
                       (update :rows merge rows)
                       (update :roots (fnil conj #{}) root))))
    root))


(defn- walker-frame-v2
  "One native walker continuation frame (`nil` ends the chain) as its
   closed wire map: the type and next, the saved environment when the
   frame has one, and the arm's own fields.  An addressed node is its
   row; every dynamic field is a value."
  [found encode k]
  (when (some? k)
    (let [t (:type k)
          f (:frame k)
          next-k (walker-frame-v2 found encode (:next k))
          base (cond-> {:yin.k/type t :yin.k/next next-k}
                 (contains? k :env)
                 (assoc :yin.k/env (encode-env-v2 found encode (:env k))))
          node #(assoc base :yin.k/node (node-row! found %))]
      (case t
        (:eval-operator :eval-test :eval-stream-put-target
                        :eval-stream-cursor-source :eval-stream-next-cursor
                        :eval-stream-close-source)
        (node f)

        :eval-operand
        (do (when-not (true? (:operator-evaluated? f))
              (non-portable! :inconsistent-state {:yin.k/hint :operator}))
            (assoc (node (dissoc f :operator-evaluated? :fn :evaluated))
                   :yin.k/function (encode (:fn f))
                   :yin.k/evaluated (mapv encode (or (:evaluated f) []))))

        :dao.stream.apply/eval-operand
        (assoc (node {:type :dao.stream.apply/call
                      :op (:op f)
                      :operands (:operands f)})
               :yin.k/evaluated (mapv encode (or (:evaluated f) [])))

        :eval-stream-put-val
        (assoc (node f) :yin.k/stream-ref (encode (:stream-ref k)))

        :dao.stream.apply/request-sent
        (assoc base :yin.k/parked-id (:parked-id k) :yin.k/op (:op k))

        :dao.stream.apply/eval-call
        (cond-> base
          (contains? k :call-id) (assoc :yin.k/call-id (:call-id k)))

        :eval-define
        (assoc base :yin.k/name (:name k))

        :eval-resume-val
        (assoc base :yin.k/parked-id (:parked-id k))

        (non-portable! :inconsistent-state {:yin.k/hint :frame})))))


(defn- walker-registers-v2
  [found encode {:keys [env k]}]
  {:yin.k/env (encode-env-v2 found encode env)
   :yin.k/k (walker-frame-v2 found encode k)})


(defn- walker-code-v2
  "The walker's code map: the rows the encoded frames projected, and
   the dependency closure, through the source's own held rows, of every
   closure's lambda row.  A name the source holds no row for is not
   addressed."
  [vm found]
  (let [held (:rows vm)
        closure
        (loop [work (vec (:segments @found)), seen #{}]
          (if-some [id (peek work)]
            (let [work (pop work)]
              (if (contains? seen id)
                (recur work seen)
                (let [row (get held id)]
                  (when (nil? row)
                    (non-portable! :unaddressed-segment {:yin.k/hint id}))
                  (recur (into work (row-children row)) (conj seen id)))))
            seen))]
    (merge (into {} (map (fn [id] [id (get held id)])) closure)
           (:rows @found))))


(defn- registers-v2
  "One register map of the body's profile: a wait entry's, a parked
   record's or a reified continuation's frame slice, without its
   transport fields, which travel as the pending."
  [engine found encode vm {:keys [segment pc env stack k] :as rec}]
  (case engine
    :semantic
    {:yin.k/segment (seg-address! found vm segment)
     :yin.k/pc pc
     :yin.k/env (encode-env-v2 found encode env)
     :yin.k/stack (mapv encode (or stack []))
     :yin.k/k (mapv (partial semantic-frame-v2 found encode vm) (or k []))}
    :stack (stack-registers-v2 found encode vm rec)
    :register (register-registers-v2 found encode vm rec)
    :walker (walker-registers-v2 found encode rec)
    (non-portable! :unsupported-kernel {:yin.k/hint engine})))


(defn- row-children
  "The row ids a v3 row `[id tag & slots]` references, by its slots'
   `:node` and `:nodes` kinds."
  [row]
  (mapcat (fn [[_ kind] v]
            (case kind :node [v] :nodes v nil))
          (get vm/semantic-bytecode-grammar (nth row 1))
          (drop 2 row)))


(defn- ffi-bookkeeping?
  "A walker parked record that is FFI correlation bookkeeping, not an
   explicit park: its continuation is the response wrapper."
  [rec]
  (= :dao.stream.apply/eval-call (:type (:k rec))))


;; -----------------------------------------------------------------------------
;; The positional profiles' sites, census and layouts
;; -----------------------------------------------------------------------------

(defn- pc-profile?
  [engine]
  (contains? #{:stack :register} engine))


(defn- site-op
  "The mnemonic of the instruction a positional register map resumes
   after: the one before the resume pc (stack), or at the carried site
   (register).  nil when it names none."
  [engine {:keys [segment pc site-pc]}]
  (case engine
    :stack (when (and (vector? segment) (pos? pc) (<= pc (count segment)))
             (first (nth segment (dec pc))))
    :register (when-some [ins (:instructions segment)]
                (when (and (nat-int? site-pc) (< site-pc (count ins)))
                  (first (nth ins site-pc))))
    nil))


(def ^:private site-reasons
  "The wire reasons each resume-site mnemonic admits (UCF v2 7.2): a
   park and a captured continuation admit none, only their explicit
   record."
  {:stream-next #{:next}
   :stream-put #{:put}
   :ffi-call #{:ffi :ffi-request}
   :call #{:next :put :ffi :ffi-request :link-request :link-response
           :install}
   :park #{}
   :current-continuation #{}})


(defn- explicit-park?
  [engine vm rec]
  (case engine
    :walker (not (ffi-bookkeeping? rec))
    (:stack :register) (= :park (site-op engine rec))
    (boolean (some #(= :explicit-park %)
                   (safepoint-kinds-at vm (:segment rec) (:pc rec))))))


(defn- census-v2
  "The positional profiles' stand-in for the semantic completion walk:
   every carried reference is discovered by the encoder itself, so the
   census starts from the guest store whole and the explicit parked
   records.  FFI bookkeeping records (a parked record whose id is a
   retained call's id) are transport, not explicit parks."
  [engine vm]
  (let [justified (into #{}
                        (keep #(when (walker-ffi-frame %)
                                 (entry-call-id :walker %)))
                        (:wait-set vm))]
    {:yin.k/refusals []
     :yin.k/missing {}
     :yin.k/store (:store vm)
     :yin.k/scheduler
     {:yin.k/parked (into {}
                          (filter (fn [[pid rec]]
                                    (or (explicit-park? engine vm rec)
                                        (and (= :walker engine)
                                             (contains? justified pid)))))
                          (:parked vm))}}))


(defn reachable-parked
  "The ids of the parked records the lift of `vm` would carry, by the
   same census the lift runs: the semantic completion walk, or the
   positional and walker census.  Answers `{:ids #{...}}`, or
   `{:refusal data}` for a walk refusal.  The holder's `enter` moves
   exactly these records into its export record."
  [vm]
  (let [engine (try (engine-of vm)
                    (catch #?(:cljd Object :clj Throwable :cljs :default) _
                      :semantic))]
    (if (= :semantic engine)
      (let [walked (completion/complete
                     {:vm vm
                      :cursor-profile (constantly :dao.stream.remote/v1)
                      :modules (module-decls vm)})]
        (if-some [r (first (:yin.k/refusals walked))]
          {:refusal (if (= :yin.k/not-quiescent (:kind r))
                      {:yin.k/status :yin.k/not-quiescent}
                      (assoc (dissoc r :kind)
                             :yin.k/status :yin.k/non-portable
                             :yin.k/kind (:kind r)))}
          {:ids (set (keys (:yin.k/parked (:yin.k/scheduler walked))))}))
      {:ids (set (keys (:yin.k/parked
                         (:yin.k/scheduler (census-v2 engine vm)))))})))


(defn- walker-free-names
  "The names the walker rows reachable from `roots` read from outside
   every enclosing lambda: a `:variable` not bound by a `:lambda` on its
   path, the reserved definition operator excepted."
  [rows roots]
  (loop [work (mapv (fn [id] [id #{}]) roots), seen #{}, names #{}]
    (if-some [[id bound :as item] (peek work)]
      (let [work (pop work)]
        (if (contains? seen item)
          (recur work seen names)
          (let [row (get rows id)
                tag (nth row 1)]
            (case tag
              :variable
              (let [n (nth row 2)]
                (recur work (conj seen item)
                       (if (or (contains? bound n) (vm/reserved-name? n))
                         names
                         (conj names n))))
              :lambda
              (recur (conj work [(nth row 3) (into bound (nth row 2))])
                     (conj seen item) names)
              (recur (into work (map (fn [c] [c bound])) (row-children row))
                     (conj seen item) names)))))
      names)))


(defn- free-names-v2
  "Every name the carried code reads free, by profile: the `:load-free`
   names of the layout's images, or the walker rows' free variables.
   Over-approximation is admissible: a name that would never be read
   still has to be dischargeable."
  [engine layout code roots]
  (case engine
    :stack (into #{}
                 (comp (mapcat (fn [[_ image]] image))
                       (filter #(= :load-free (first %)))
                       (map #(nth % 1)))
                 layout)
    :register (into #{}
                    (comp (mapcat (fn [[_ image]] (:instructions image)))
                          (filter #(= :load-free (first %)))
                          (map #(nth % 2)))
                    layout)
    :walker (walker-free-names code roots)
    #{}))


(defn- discharge-names!
  "Rule every free name in `resolve-var`'s order -- the free environment
   and every environment carried, the store, the primitives, the module
   registry -- or refuse `:yin.k/unsatisfied`, discovery incomplete,
   naming what nothing answers.  A host-state primitive is not portable,
   and a primitive with no published profile is unsatisfied."
  [vm names bound installing]
  (let [primitives (:primitives vm)
        missing (volatile! #{})
        profiles (volatile! #{})]
    (doseq [sym (remove vm/reserved-name? names)]
      (let [profile (vm/profile-of primitives sym)]
        (cond
          (or (contains? (:free-env vm) sym) (contains? bound sym)
              (contains? (:store vm) sym) (contains? installing sym))
          nil

          (some? profile)
          (when (= :host (:yin.k/class profile))
            (non-portable! :host-state-primitive {:yin.k/name sym}))

          (contains? primitives sym) (vswap! profiles conj sym)

          (and (namespace sym)
               (some? (module/resolve-module
                        (:modules vm)
                        (symbol (str (namespace sym) "." (name sym))))))
          nil

          :else (vswap! missing conj sym))))
    (when (or (seq @missing) (seq @profiles))
      (refuse! :yin.k/unsatisfied
               {:yin.k/discovery :incomplete
                :yin.k/missing {:obligations @missing
                                :profiles @profiles}}))))


(defn- compose-v2
  "The code space a layout of `images` builds, under `engine`'s kernel."
  [engine images]
  (case engine
    :stack (stack/compose images)
    :register (register/compose images)))


(defn- layout-images-v2
  "The task layout of positional `vm` as `[[identity image] ...]`, every
   image its unrelocated component; a table that does not reproduce its
   identities refuses."
  [engine vm]
  (let [v (case engine
            :stack (stack/layout-images (:segment vm) (:images vm))
            :register (register/layout-images (:segment vm) (:images vm))
            nil)]
    (when (nil? v)
      (non-portable! :inconsistent-state {:yin.k/hint :layout}))
    (mapv (fn [[ident _ _] image] [ident image]) (:images vm) v)))


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
   exactly as it stands in the task at export, mutations included,
   walked in the same canonical order the task store is. Version 1's
   fixed point corrects omitted transitive stores; canonical-order's
   scratch census never populated this export's found map. Published
   version 0 retains its one-pass bytes; only recovery closes its graph."
  [vm encode found]
  (when-some [m (first (remove #(contains? (:module-stores vm) %)
                               (sort-by str (:stores @found))))]
    (non-portable! :missing-module-store {engine/store-of-key m}))
  (let [snapshot (fn [module-id]
                   (when-not (contains? (:module-stores vm) module-id)
                     (non-portable! :missing-module-store {engine/store-of-key module-id}))
                   (into {}
                         (map (fn [[key value]] [(encode key) (encode value)]))
                         (ordered found key (get (:module-stores vm) module-id))))]
    (if (or (:v1 @found) (:full-census @found))
      (loop [stores {}]
        (if-some [module-id (first (sort-by str (set/difference (:stores @found)
                                                                (set (keys stores)))))]
          (recur (assoc stores module-id (snapshot module-id)))
          stores))
      (into {} (map (fn [module-id] [module-id (snapshot module-id)]))
            (sort-by str (:stores @found))))))


(defn- bytes-address
  "The jing segment address of the exact emitted `bytes`, the scheme a
   receiver verifies fetched bytes against (`jing/segment-bytes-match?`)."
  [bytes]
  (let [algo jing/default-hash-algorithm]
    (keyword "segment"
             (str (get-in jing/registry [algo :address-id]) "-"
                  (jing/digest-bytes algo bytes)))))


(defn- header-of
  "The custody header keys the root body of `kind` carries, from the
   caller's `header`: a blocked or parked root takes the occurrence,
   arbitration, counter and the exclusive policy, and the origin when
   the header names one; a halted root takes the origin alone, its other
   values ignored.  A nil value is an absent key."
  [header kind]
  (into {}
        (filter (comp some? val))
        (if (= :halted kind)
          (select-keys header [:yin.k/origin])
          (assoc (select-keys header [:yin.k/occurrence :yin.k/arbitration
                                      :yin.k/next-op-seq :yin.k/origin])
                 :yin.k/policy :yin.k/exclusive))))


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
   `yin.vm.ucf.remote`'s.  A task that holds an `:observe` entry, an
   entry or cell carrying `:yin.k/held`, an unminted cursor cell, a
   pending close, or a cursorless `:link-request` entry -- itself or any
   install child -- refuses `:yin.k/non-portable` of kind
   `:reason-mismatch` before anything is lifted, the same holds
   `yin.vm.ucf.holder.export/enter` refuses (UCF 7.4.1, 7.4.3).

   `opts` may name `:header`, the version-1 custody header
   (`yin.vm.ucf.holder.export/prepare` validates its shape): occurrence,
   arbitration, next-op-seq, origin when the task has a predecessor, and
   `:yin.k/enrolled`, the set of enrolled stream identities, which is a
   lift input and never travels.  No header is the version-0 fork lift,
   byte for byte as before; a header is the version-1 exclusive lift in
   `dao.jing.cbor`, with `:yin.k/policy :yin.k/exclusive` added here.
   The root carries the header by its kind (blocked or parked: all of
   it; halted: the origin alone; an install child: none), refuses
   `:op-seq-exhausted` at 2^52-1, `:unprotected-pending` and a stream
   `:yin.k/unsatisfied` by the enrolled set, orders every map and set
   by canonical key bytes, and before answering runs the version-1
   inspector over its own bytes and address and then `validate-body`:
   whatever either refuses is the lift's refusal, and nothing is
   answered.  `:serve-keyed`, `(f [path resource-id] handle)`, replaces
   `serve!` and retains each answer under the task path and the
   resource id.

   `:version 2` selects the version-2 body of the four execution
   profiles (semantic, stack, register, walker), the profile being the
   kernel's own: no header is the fork, a header the exclusive lift, and
   the roles are structural (the v2 amendment, section 3).  The
   composition chooses it; it is never inferred, and without it the lift
   is exactly the version-0 or version-1 lift as before."
  ([vm serve!]
   (export-task vm serve! nil))
  ([vm serve! opts]
   (call!
     (fn []
       (when (seq (:ready-queue vm))
         (refuse! :yin.k/not-quiescent
                  {:yin.k/ready (count (:ready-queue vm))}))
       (when-some [h (unliftable-hold vm)]
         (non-portable! :reason-mismatch h))
       (let [header (:header opts)
             child? (::child opts)
             v2? (= 2 (:version opts))
             engine (when v2? (engine-of vm))
             v1? (boolean (or (some? header) (::v1 opts) v2?))
             keyed (or (:serve-keyed opts) (fn [_ h] (serve! h)))
             ;; grounded: ClojureDart compiles a many-key assoc onto nil
             ;; as a conj, whose answer is a list no dissoc accepts
             opts (assoc (or opts {})
                         :path (or (:path opts) [])
                         ::v1 v1?
                         ::enrolled (or (:yin.k/enrolled header)
                                        (::enrolled opts)
                                        #{})
                         :serve-keyed keyed)
             serve! (fn [id h] (keyed [(:path opts) id] h))
             {:keys [encode-bytes decode-bytes]}
             (if v1?
               {:encode-bytes jing.cbor/encode :decode-bytes jing.cbor/decode}
               {:encode-bytes cbor/encode :decode-bytes cbor/decode})
             walked (if (and v2? (not= :semantic engine))
                      (census-v2 engine vm)
                      (completion/complete
                        {:vm vm
                         :cursor-profile (constantly :dao.stream.remote/v1)
                         :modules (module-decls vm)}))
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
               _ (when (and v1? (not child?) (not= :halted kind)
                            (let [n (:yin.k/next-op-seq header)]
                              (and (jing.cbor/numeric? n)
                                   (jing.cbor/num= n checkpoint/max-exact))))
                   (non-portable! :op-seq-exhausted
                                  {:yin.k/next-op-seq
                                   (:yin.k/next-op-seq header)}))
               _ (when (and active
                            (not (explicit-park? engine vm active-rec)))
                   (non-portable! :reason-mismatch
                                  {:yin.k/pc (:pc active-rec)}))
               found (atom (cond-> (assoc (new-found)
                                          :v1 v1?
                                          :full-census (::recovery opts)
                                          :recovery-cell-ids (:yin.k/recovery-cell-ids vm)
                                          :enrolled (::enrolled opts))
                             v2? (assoc :v2 {:engine engine})
                             v1? (assoc :order
                                        (canonical-order
                                          vm serve!
                                          (when v2? {:engine engine})))))
               encode (value-encoder vm serve! found)
               frames (mapv (partial lift-frame! vm serve! found encode)
                            (:wait-set vm))
               parked (into {}
                            (map (fn [[pid rec]]
                                   (when-not (and v2? (not= :semantic engine))
                                     (safepoint-kinds-at vm (:segment rec)
                                                         (:pc rec)))
                                   [pid (if v2?
                                          (registers-v2 engine found encode
                                                        vm rec)
                                          (encode-registers found encode
                                                            vm rec))]))
                            (ordered found key
                                     (:yin.k/parked
                                       (:yin.k/scheduler walked))))
               store (into {}
                           (map (fn [[k v]] [(encode k) (encode v)]))
                           (ordered found key (:yin.k/store walked)))
               ;; the halted result is encoded before any dependency is
               ;; finalized: it can reach cells, module closures and code
               ;; the halted result is encoded before any dependency is
               ;; finalized: it can reach cells, module closures and code
               result (when (= :halted kind) (encode (:value vm)))
               module-stores (snapshot-module-stores vm encode found)
               installs (export-installs vm nil (dissoc opts :header))
               cells (:cells @found)
               profiles (or (:cursor-profiles opts)
                            (if (seq cells)
                              ucf.remote/cursor-profiles
                              #{}))
               _ (when (and (seq cells)
                            (not (contains? profiles
                                            :dao.stream.remote/v1)))
                   (unsatisfied!))
               layout (when (pc-profile? engine)
                        (layout-images-v2 engine vm))
               free-env (when (pc-profile? engine)
                          (encode-env-v2 found encode (:free-env vm)))
               walker-code (when (= :walker engine)
                             (walker-code-v2 vm found))
               _ (when (and v2? (not= :semantic engine))
                   (discharge-names!
                     vm
                     (free-names-v2 engine layout (or walker-code {})
                                    (into (or (:roots @found) #{})
                                          (:segments @found)))
                     (or (:bound @found) #{})
                     installing))
               segments (if walker-code
                          (set (keys walker-code))
                          (into (:segments @found) (map first) layout))
               images (into {} layout)
               fetch (if (pc-profile? engine)
                       #(get images %)
                       (completion/vm-fetch vm nil))
               code (or walker-code
                        (into {}
                              (map (fn [a]
                                     (if-some [v (fetch a)]
                                       [a v]
                                       (refuse! :yin.k/unsatisfied
                                                {:yin.k/segment a}))))
                              (sort-by str segments)))
               _ (when-some [p (first (remove #(contains? parked %)
                                              (:parked-refs @found)))]
                   (non-portable! :inconsistent-state
                                  {:yin.k/hint :parked-reference
                                   :yin.k/parked-id p}))
               body (cond-> {handoff-tag true
                             :yin.k/version (cond v2? 2 v1? 1 :else 0)
                             :yin.k/kind kind
                             :yin.k/contract (if v2?
                                               (get ucf/profiles engine)
                                               ucf/contract-stamp)
                             :yin.k/id-counter (or (:id-counter vm) 0)
                             :yin.k/store store
                             :yin.k/module-stores module-stores
                             :yin.k/parked parked
                             :yin.k/cells cells
                             :yin.k/requires
                             {:yin.k/cursor-profiles profiles
                              :yin.k/segments (set segments)}
                             :yin.k/code code}
                      (pc-profile? engine)
                      (assoc :yin.k/layout (mapv first layout)
                             :yin.k/free-env free-env)
                      (or v2? (seq installs))
                      (assoc :yin.k/installs installs)
                      (or (seq frames) (and v2? (= :parked kind)))
                      (assoc :yin.k/frames frames)
                      (= :parked kind)
                      (assoc :yin.k/parked-id (:id value))
                      (= :halted kind)
                      (assoc :yin.k/result result)
                      (and v1? (not child?) header)
                      (merge (header-of header kind)))
               bytes (if v1?
                       (try (encode-bytes body)
                            (catch #?(:cljd Object
                                      :clj Throwable
                                      :cljs :default) e
                              (if (outcome e)
                                (throw e)
                                (non-portable! :non-canonicalizable
                                               {:yin.k/hint
                                                (jing.cbor/refusal e)}))))
                       (encode-bytes body))
               address (bytes-address bytes)
               _ (when (and v1? (not child?))
                   (let [r (checkpoint/inspect address bytes)]
                     (when (contains? r :yin.k/status)
                       (refuse! (:yin.k/status r) (dissoc r :yin.k/status)))))
               _ (validate-body (decode-bytes bytes))]
           {:status :ok
            :kind kind
            :body body
            :bytes bytes
            :address address}))))))


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
            (when (contains? body :yin.k/free-env)
              [(:yin.k/free-env body)])
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
  "The body grammar both ends and both versions run (S7.5.4 over 0 and
   1): the tag, the version among those this namespace speaks, the
   kind's own shape, every cell reference resolved to exactly the
   cells the body carries -- none missing, none extra -- every pending
   a known variant with the keys that variant requires, every frame's
   segment among the code the body names, each code vector still
   hashing to its own address and admissible as a vector, and each
   install entry carrying a phase the wire admits, with phase and
   parent required outright at version 1.  A malformed body is refused
   whole, before any attachment or restoration."
  [body]
  (when-not (and (map? body) (true? (get body handoff-tag)))
    (undecodable! {:yin.k/kind :body}))
  (if (v2-body? body)
    (validate-body-v2 body)
    (validate-body-v1 body)))


(defn- validate-body-v1
  [body]
  (let [version (:yin.k/version body)]
    (when-not (contains? #{0 1} version)
      (refuse! :yin.k/profile-mismatch
               {:yin.k/version version
                :yin.k/supported handoff-version})))
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
      ;; the phase travels beside the child on both versions' grammar:
      ;; the wire carries a running or a parked child, never one of the
      ;; engine's transient phases; version 1 requires phase and parent
      ;; outright, the child being part of a custody subject's task
      (when (contains? inst :yin.k/phase)
        (when-not (contains? #{:running :parked} (:yin.k/phase inst))
          (undecodable! {:yin.k/phase (:yin.k/phase inst)
                         :yin.k/path [:yin.k/installs m :yin.k/phase]})))
      (when (= 1 (:yin.k/version body))
        (doseq [k [:yin.k/phase :yin.k/parent]]
          (when-not (contains? inst k)
            (undecodable! {:yin.k/name m
                           :yin.k/kind :install-header
                           :yin.k/path [:yin.k/installs m k]}))))
      ;; a child is a whole body: validated with the same grammar
      ;; before anything of the parent is restored.  Decoded bytes
      ;; are trees, so the recursion terminates.
      (validate-body (:yin.k/child inst)))
    ;; every install waiter travels beside its whole child: the lift's
    ;; check, re-proved on the bytes, so a foreign body naming an
    ;; install it does not carry never restores
    (doseq [[i frame] (map-indexed vector (or (:yin.k/frames body) []))]
      (let [pending (:yin.k/pending frame)]
        (when (and (= :install (:yin.k/reason pending))
                   (not (contains? (:yin.k/installs body)
                                   (:yin.k/name pending))))
          (undecodable! {:yin.k/name (:yin.k/name pending)
                         :yin.k/kind :incomplete-install
                         :yin.k/path [:yin.k/frames i :yin.k/pending]}))))
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
;; Version 2 grammar (UCF v2 amendment, sections 2 to 7)
;; =============================================================================

(defn- v2-body?
  "True when `body` declares version 2 on the canonical integer kind."
  [body]
  (let [v (when (map? body) (:yin.k/version body))]
    (and (jing.cbor/numeric? v)
         (= :integer (jing.cbor/numeric-kind v))
         (jing.cbor/num= v 2))))


(defn- exact-int?
  "An exact CBOR integer in [0, 2^52-1], by integer kind: never a float64
   carrier that happens to be integral."
  [x]
  (and (jing.cbor/numeric? x)
       (= :integer (jing.cbor/numeric-kind x))
       (not (neg? (jing.cbor/num-compare x 0)))
       (not (pos? (jing.cbor/num-compare x checkpoint/max-exact)))))


(defn- closed!
  "`m` is a map whose keys are exactly the `required` ones and any of the
   `optional` ones: a missing key, or an unlisted one, refuses naming its
   path."
  [m required optional path]
  (when-not (map? m)
    (undecodable! {:yin.k/path path}))
  (doseq [k required
          :when (not (contains? m k))]
    (undecodable! {:yin.k/path (conj path k)}))
  (doseq [k (sort-by str (keys m))
          :when (not (or (contains? required k) (contains? optional k)))]
    (undecodable! {:yin.k/path (conj path k) :yin.k/kind :unlisted-key})))


(def ^:private pending-keys
  "The closed key set of each pending variant: `[required optional]`."
  {:next [#{:yin.k/reason :yin.k/cell} #{}]
   :ffi [#{:yin.k/reason :yin.k/call-id :yin.k/cell} #{:yin.k/op}]
   :put [#{:yin.k/reason :yin.k/stream :yin.k/value} #{:yin.k/op-id}]
   :ffi-request [#{:yin.k/reason :yin.k/call-id :yin.k/request-envelope
                   :yin.k/request-op :yin.k/request-args :yin.k/request
                   :yin.k/response :yin.k/response-cell}
                 #{:yin.k/op-id}]
   :link-request [#{:yin.k/reason :yin.k/link-id :yin.k/name :yin.k/request
                    :yin.k/response :yin.k/cell :yin.k/envelope}
                  #{:yin.k/op-id}]
   :link-response [#{:yin.k/reason :yin.k/link-id :yin.k/name :yin.k/request
                     :yin.k/response :yin.k/cell}
                   #{}]
   :install [#{:yin.k/reason :yin.k/name} #{}]})


(defn- validate-env-v2
  [e path]
  (closed! e #{:yin.k/bindings} #{:yin.k/store-of} path)
  (when-not (map? (:yin.k/bindings e))
    (undecodable! {:yin.k/path (conj path :yin.k/bindings)}))
  (doseq [k (keys (:yin.k/bindings e))]
    (when-not (symbol? k)
      (undecodable! {:yin.k/path (conj path :yin.k/bindings)
                     :yin.k/kind :binding-name}))))


(defn- instruction-at
  [v pc]
  (when (and (exact-int? pc) (< -1 pc (count v)))
    (nth v pc)))


(def ^:private context-sites
  "The resume-site mnemonics each register context admits: a wait one
   of the four effect sites, an explicit park its `:park`, a captured
   continuation its `:current-continuation`."
  {:wait #{:call :stream-put :stream-next :ffi-call}
   :parked #{:park}
   :captured #{:current-continuation}})


(defn- layout-prefix!
  "`l` is a nonempty prefix of the task layout, every image carried."
  [{:keys [code layout]} l path]
  (when-not (and (vector? l) (seq l)
                 (<= (count l) (count layout))
                 (= l (subvec layout 0 (count l)))
                 (every? #(contains? code %) l))
    (undecodable! {:yin.k/path (conj path :yin.k/layout)
                   :yin.k/kind :layout-not-prefix})))


(defn- stack-payload-v2
  "The native-shaped skeleton of one stack register map, its code space
   rebuilt from its own layout; the wire values stay as they are, since
   only counts and shapes are judged."
  [{:keys [code]} r path]
  (let [composed (try (stack/compose (mapv code (:yin.k/layout r)))
                      (catch #?(:cljd Object :clj Throwable :cljs :default) e
                        (if (= :layout-overflow (:rule (ex-data e)))
                          (undecodable! {:yin.k/path (conj path :yin.k/layout)
                                         :yin.k/kind :layout-overflow})
                          (throw e))))]
    {:segment (:segment composed)
     :images (:images composed)
     :hash (:hash composed)
     :format stack/format-tag
     :image (:yin.k/image r)
     :pc (:yin.k/pc r)
     :stack (:yin.k/stack r)
     :frames (:yin.k/frames r)
     :continuation (mapv (fn [f]
                           {:return-pc (:yin.k/return-pc f)
                            :frames (:yin.k/frames f)
                            :stack-base (:yin.k/stack-base f)})
                         (:yin.k/continuation r))}))


(defn- register-payload-v2
  "The native-shaped skeleton of one register register map, its code
   space rebuilt from its own layout; the wire values stay as they are."
  [{:keys [code]} r path]
  (let [composed (try (register/compose (mapv code (:yin.k/layout r)))
                      (catch #?(:cljd Object :clj Throwable :cljs :default) e
                        (if (= :layout-overflow (:rule (ex-data e)))
                          (undecodable! {:yin.k/path (conj path :yin.k/layout)
                                         :yin.k/kind :layout-overflow})
                          (throw e))))
        rows (:images composed)]
    (cond-> {:segment (:segment composed)
             :hash (:hash composed)
             :format reffects/format-tag
             :image (:yin.k/image r)
             :site-pc (:yin.k/site-pc r)
             :pc (:yin.k/pc r)
             :frames (:yin.k/frames r)
             :regs (:yin.k/regs r)
             :live (:yin.k/live r)
             :continuation (mapv (fn [f]
                                   {:site-pc (:yin.k/site-pc f)
                                    :return-pc (:yin.k/return-pc f)
                                    :frames (:yin.k/frames f)
                                    :regs (:yin.k/regs f)
                                    :live (:yin.k/live f)
                                    :dest (:yin.k/dest f)})
                                 (:yin.k/continuation r))
             :dest (:yin.k/dest r)
             :resume-mode (:yin.k/resume-mode r)}
      (< 1 (count rows)) (assoc :images rows))))


(def ^:private kw-node-tags
  "The row tag each node-bearing walker frame type addresses."
  {:eval-operator :application
   :eval-operand :application
   :eval-test :if
   :dao.stream.apply/eval-operand :dao.stream.apply/call
   :eval-stream-put-target :stream/put
   :eval-stream-put-val :stream/put
   :eval-stream-cursor-source :stream/cursor
   :eval-stream-next-cursor :stream/next
   :eval-stream-close-source :stream/close})


(def ^:private kw-fields
  "The closed arm fields of each walker frame type beyond `:yin.k/type`,
   `:yin.k/next` and the optional `:yin.k/env`."
  {:eval-operator #{:yin.k/node}
   :eval-operand #{:yin.k/node :yin.k/function :yin.k/evaluated}
   :eval-test #{:yin.k/node}
   :dao.stream.apply/eval-operand #{:yin.k/node :yin.k/evaluated}
   :dao.stream.apply/request-sent #{:yin.k/parked-id :yin.k/op}
   :dao.stream.apply/eval-call #{}
   :eval-stream-put-target #{:yin.k/node}
   :eval-stream-put-val #{:yin.k/node :yin.k/stream-ref}
   :eval-stream-cursor-source #{:yin.k/node}
   :eval-stream-next-cursor #{:yin.k/node}
   :eval-stream-close-source #{:yin.k/node}
   :eval-define #{:yin.k/name}
   :eval-resume-val #{:yin.k/parked-id}})


(defn- row-definition?
  "True when application row `row` is a Rule R definition."
  [code row]
  (let [op (get code (nth row 2))]
    (boolean (and op (= :variable (nth op 1))
                  (= vm/definition-operator (nth op 2))))))


(defn- validate-kw!
  "The closed walker frame chain `kw` (nil ends it), outermost last:
   every frame's type and fields, each addressed node a carried row of
   the right tag, applications that are not Rule R definitions, and
   partial evaluations shorter than their operand lists."
  [{:keys [code parked]} kw path]
  (loop [k kw, p path]
    (when (some? k)
      (let [t (:yin.k/type k)]
        (when-not (contains? kw-fields t)
          (undecodable! {:yin.k/path (conj p :yin.k/type)
                         :yin.k/kind :frame-type}))
        (closed! k (into #{:yin.k/type :yin.k/next} (get kw-fields t))
                 (cond-> #{:yin.k/env}
                   (= :dao.stream.apply/eval-call t) (conj :yin.k/call-id))
                 p)
        (when (contains? k :yin.k/env)
          (validate-env-v2 (:yin.k/env k) (conj p :yin.k/env)))
        (when-some [tag (get kw-node-tags t)]
          (let [row (get code (:yin.k/node k))]
            (when-not (and (vector? row) (= tag (nth row 1)))
              (undecodable! {:yin.k/path (conj p :yin.k/node)
                             :yin.k/kind :frame-node}))
            (when (and (= :application tag) (row-definition? code row))
              (undecodable! {:yin.k/path (conj p :yin.k/node)
                             :yin.k/kind :definition-frame}))
            (when (contains? #{:eval-operand
                               :dao.stream.apply/eval-operand} t)
              (let [ev (:yin.k/evaluated k)]
                (when-not (and (vector? ev) (< (count ev) (count (nth row 3))))
                  (undecodable! {:yin.k/path (conj p :yin.k/evaluated)
                                 :yin.k/kind :evaluated}))))))
        (case t
          :eval-stream-put-val
          (when-not (marker? (:yin.k/stream-ref k))
            (undecodable! {:yin.k/path (conj p :yin.k/stream-ref)}))
          :eval-define
          (when-not (and (symbol? (:yin.k/name k))
                         (not (vm/reserved-name? (:yin.k/name k))))
            (undecodable! {:yin.k/path (conj p :yin.k/name)}))
          :eval-resume-val
          (when-not (contains? parked (:yin.k/parked-id k))
            (undecodable! {:yin.k/path (conj p :yin.k/parked-id)}))
          :dao.stream.apply/request-sent
          (when-not (keyword? (:yin.k/op k))
            (undecodable! {:yin.k/path (conj p :yin.k/op)}))
          nil)
        (recur (:yin.k/next k) (conj p :yin.k/next))))))


(defn- validate-registers-v2
  "One register map under the body's profile, `context` `:wait`,
   `:parked` or `:captured`, `ctx` the body's `{:code :layout}`.  The
   semantic grammar: the segment and pc, the environment E, the operand
   stack, and the return frames bottom to top, each resuming just after
   a non-tail call, with nondecreasing stack bases no deeper than the
   stack.  The stack grammar: its own layout (a prefix of the task's),
   the image of the resume pc, the pc, positional frames, the operand
   stack and the return frames, judged by the kernel."
  [engine {:keys [code] :as ctx} r path context]
  (case engine
    :semantic
    (do
      (closed! r #{:yin.k/segment :yin.k/pc :yin.k/env :yin.k/stack :yin.k/k}
               #{} path)
      (validate-env-v2 (:yin.k/env r) (conj path :yin.k/env))
      (when-not (and (vector? (:yin.k/stack r)) (vector? (:yin.k/k r)))
        (undecodable! {:yin.k/path path :yin.k/kind :registers}))
      (let [a (:yin.k/segment r)
            pc (:yin.k/pc r)]
        (when-not (contains? code a)
          (undecodable! {:yin.k/segment a :yin.k/path path}))
        (when-not (and (exact-int? pc) (< pc (count (get code a))))
          (undecodable! {:yin.k/pc pc :yin.k/segment a :yin.k/path path}))
        (when (= :captured context)
          (when-not (= :current-continuation
                       (first (instruction-at (get code a) (dec pc))))
            (undecodable! {:yin.k/pc pc :yin.k/path path
                           :yin.k/kind :captured-site}))))
      (reduce
        (fn [base [i f]]
          (let [fp (conj path :yin.k/k i)
                a (:yin.k/segment f)
                pc (:yin.k/pc f)
                b (:yin.k/stack-base f)]
            (closed! f #{:yin.k/type :yin.k/segment :yin.k/pc :yin.k/env
                         :yin.k/stack-base}
                     #{} fp)
            (when-not (= :return (:yin.k/type f))
              (undecodable! {:yin.k/path (conj fp :yin.k/type)}))
            (validate-env-v2 (:yin.k/env f) (conj fp :yin.k/env))
            (when-not (contains? code a)
              (undecodable! {:yin.k/segment a :yin.k/path fp}))
            (when-not (and (exact-int? pc) (< 0 pc (count (get code a))))
              (undecodable! {:yin.k/pc pc :yin.k/segment a :yin.k/path fp}))
            (let [site (instruction-at (get code a) (dec pc))]
              (when-not (and (= :call (first site)) (false? (nth site 2)))
                (undecodable! {:yin.k/pc pc :yin.k/path fp
                               :yin.k/kind :return-site})))
            (when-not (and (exact-int? b)
                           (<= b (count (:yin.k/stack r)))
                           (<= base b))
              (undecodable! {:yin.k/path (conj fp :yin.k/stack-base)}))
            b))
        0
        (map-indexed vector (:yin.k/k r))))

    :stack
    (do
      (closed! r #{:yin.k/layout :yin.k/image :yin.k/pc :yin.k/frames
                   :yin.k/stack :yin.k/continuation}
               #{:yin.k/store-of} path)
      (layout-prefix! ctx (:yin.k/layout r) path)
      (when-not (and (exact-int? (:yin.k/pc r))
                     (vector? (:yin.k/frames r))
                     (vector? (:yin.k/stack r))
                     (vector? (:yin.k/continuation r)))
        (undecodable! {:yin.k/path path :yin.k/kind :registers}))
      (doseq [[i f] (map-indexed vector (:yin.k/continuation r))]
        (let [fp (conj path :yin.k/continuation i)]
          (closed! f #{:yin.k/return-pc :yin.k/frames :yin.k/stack-base}
                   #{:yin.k/store-of} fp)
          (when-not (and (exact-int? (:yin.k/return-pc f))
                         (exact-int? (:yin.k/stack-base f)))
            (undecodable! {:yin.k/path fp :yin.k/kind :return-frame}))))
      (when-some [defect (stack/continuation-defect
                           (stack-payload-v2 ctx r path)
                           (get context-sites context))]
        (undecodable! {:yin.k/path path :yin.k/defect defect})))

    :register
    (do
      (closed! r #{:yin.k/layout :yin.k/image :yin.k/site-pc :yin.k/pc
                   :yin.k/frames :yin.k/regs :yin.k/live :yin.k/continuation
                   :yin.k/dest :yin.k/resume-mode}
               #{:yin.k/store-of} path)
      (layout-prefix! ctx (:yin.k/layout r) path)
      (when-not (and (exact-int? (:yin.k/site-pc r))
                     (exact-int? (:yin.k/pc r))
                     (vector? (:yin.k/frames r))
                     (vector? (:yin.k/regs r))
                     (every? (fn [p]
                               (and (vector? p) (= 2 (count p))
                                    (exact-int? (nth p 0))))
                             (:yin.k/regs r))
                     (vector? (:yin.k/live r))
                     (every? exact-int? (:yin.k/live r))
                     (vector? (:yin.k/continuation r))
                     (or (nil? (:yin.k/dest r)) (exact-int? (:yin.k/dest r)))
                     (contains? #{:write-result :return-result}
                                (:yin.k/resume-mode r)))
        (undecodable! {:yin.k/path path :yin.k/kind :registers}))
      (doseq [[i f] (map-indexed vector (:yin.k/continuation r))]
        (let [fp (conj path :yin.k/continuation i)]
          (closed! f #{:yin.k/site-pc :yin.k/return-pc :yin.k/frames
                       :yin.k/regs :yin.k/live :yin.k/dest}
                   #{:yin.k/store-of} fp)
          (when-not (and (exact-int? (:yin.k/site-pc f))
                         (exact-int? (:yin.k/return-pc f))
                         (exact-int? (:yin.k/dest f))
                         (vector? (:yin.k/frames f))
                         (vector? (:yin.k/regs f))
                         (vector? (:yin.k/live f))
                         (every? exact-int? (:yin.k/live f))
                         (every? (fn [p]
                                   (and (vector? p) (= 2 (count p))
                                        (exact-int? (nth p 0))))
                                 (:yin.k/regs f)))
            (undecodable! {:yin.k/path fp :yin.k/kind :return-frame}))))
      (let [payload (register-payload-v2 ctx r path)
            composed (register/compose (mapv code (:yin.k/layout r)))]
        (when-some [defect (reffects/continuation-defect payload)]
          (undecodable! {:yin.k/path path :yin.k/defect defect}))
        (when-not (contains? (get context-sites context)
                             (site-op :register payload))
          (undecodable! {:yin.k/path path :yin.k/kind :site}))
        (when-not (= (:yin.k/image r)
                     (nth (reffects/image-row (:images composed)
                                              (inc (:yin.k/site-pc r)))
                          0 nil))
          (undecodable! {:yin.k/path (conj path :yin.k/image)}))))

    :walker
    (do (closed! r #{:yin.k/env :yin.k/k} #{} path)
        (validate-env-v2 (:yin.k/env r) (conj path :yin.k/env))
        (validate-kw! ctx (:yin.k/k r) (conj path :yin.k/k)))

    (undecodable! {:yin.k/kind :unsupported-profile :yin.k/path path})))


(defn- check-frame-pc-v2
  "The wire reasons the resume site admits, per profile."
  [engine {:keys [code]} pending registers path]
  (case engine
    :semantic (check-frame-pc code pending registers path)
    :walker
    (let [top (:yin.k/k registers)
          t (:yin.k/type top)
          reason (:yin.k/reason pending)
          bad! (fn []
                 (undecodable! {:yin.k/kind :ffi-correlation
                                :yin.k/reason reason
                                :yin.k/path path}))]
      (case reason
        :ffi (when-not (and (= :dao.stream.apply/eval-call t)
                            (= (:yin.k/call-id pending) (:yin.k/call-id top)))
               (bad!))
        :ffi-request (when-not (and (= :dao.stream.apply/request-sent t)
                                    (= (:yin.k/call-id pending)
                                       (:yin.k/parked-id top))
                                    (= (:yin.k/request-op pending)
                                       (:yin.k/op top)))
                       (bad!))
        (when (contains? #{:dao.stream.apply/eval-call
                           :dao.stream.apply/request-sent}
                         t)
          (bad!))))
    (:stack :register)
    (let [composed (compose-v2 engine (mapv code (:yin.k/layout registers)))
          op (site-op engine {:segment (:segment composed)
                              :pc (:yin.k/pc registers)
                              :site-pc (:yin.k/site-pc registers)})]
      (when-not (contains? (get site-reasons op) (:yin.k/reason pending))
        (undecodable! {:yin.k/kind :reason-mismatch
                       :yin.k/pc (:yin.k/pc registers)
                       :yin.k/reason (:yin.k/reason pending)
                       :yin.k/path path})))
    (undecodable! {:yin.k/kind :unsupported-profile :yin.k/path path})))


(defn- guarded-hash
  "`(f image)`, or nil when the image is so malformed that hashing it
   throws: a code entry that cannot be hashed is not an image."
  [f image]
  (try (f image)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn- validate-code-v2
  "Every code entry hashes to its own key and is admissible under the
   profile's own validator."
  [engine code]
  (doseq [[a v] code]
    (when (and (contains? #{:stack :register} engine)
               (nil? (guarded-hash (if (= :stack engine)
                                     dcode/image-hash
                                     rcode/register-hash)
                                   v)))
      (undecodable! {:yin.k/segment a :yin.k/kind :code}))
    (case engine
      :semantic
      (do (when-not (= a (ucf/code-address v))
            (refuse! :yin.k/hash-mismatch {:yin.k/segment a}))
          (when-some [defect (code/well-formed-vector? v)]
            (undecodable! {:yin.k/segment a :yin.k/pc (:pc defect)
                           :yin.k/defect defect})))
      :stack
      (do (when-not (and (vector? v) (= a (dcode/image-hash v)))
            (refuse! :yin.k/hash-mismatch {:yin.k/segment a}))
          (when-some [defect (when (seq v) (dcode/image-defect v))]
            (undecodable! {:yin.k/segment a :yin.k/defect defect})))
      :register
      (do (when-not (and (map? v) (= a (rcode/register-hash v)))
            (refuse! :yin.k/hash-mismatch {:yin.k/segment a}))
          (when-some [defect (when (seq (:instructions v))
                               (rcode/register-image-defect v))]
            (undecodable! {:yin.k/segment a :yin.k/defect defect})))
      :walker
      (when-not (and (vector? v) (< 1 (count v)) (= a (first v))
                     (jing/segment-matches? a (subvec v 1)))
        (refuse! :yin.k/hash-mismatch {:yin.k/segment a}))
      (undecodable! {:yin.k/kind :unsupported-profile}))))


(defn- empty-image?
  [engine image]
  (if (= :register engine) (empty? (:instructions image)) (empty? image)))


(defn- validate-layout-v2
  "The task layout of a positional body: nonempty, distinct, every
   image carried, an empty image only as the base at index zero."
  [engine code body]
  (let [layout (:yin.k/layout body)]
    (when-not (and (vector? layout) (seq layout)
                   (= (count layout) (count (set layout)))
                   (every? #(contains? code %) layout))
      (undecodable! {:yin.k/path [:yin.k/layout]}))
    (doseq [[i a] (map-indexed vector layout)
            :when (and (pos? i) (empty-image? engine (get code a)))]
      (undecodable! {:yin.k/path [:yin.k/layout i]
                     :yin.k/kind :empty-image}))
    (when-some [defect (try (compose-v2 engine (mapv code layout)) nil
                            (catch #?(:cljd Object :clj Throwable :cljs :default) e
                              (when (= :layout-overflow (:rule (ex-data e)))
                                {:rule :layout-overflow})))]
      (undecodable! {:yin.k/path [:yin.k/layout] :yin.k/defect defect}))
    (validate-env-v2 (:yin.k/free-env body) [:yin.k/free-env])))


(defn- referenced-segments-v2
  "Every code address the body's registers and values name."
  [body]
  (let [rs (concat (map :yin.k/registers (:yin.k/frames body))
                   (vals (:yin.k/parked body)))
        roots (reachable-values body)
        captured (keep :yin.k/registers
                       (mapcat #(tagged-of :yin.k/frame %) roots))
        all (concat rs captured)]
    (into #{}
          (concat
            (keep :yin.k/segment all)
            (mapcat (fn [r] (keep :yin.k/segment (:yin.k/k r))) all)
            (mapcat :yin.k/layout all)
            (map :yin.k/segment (mapcat #(tagged-of :yin.k/closure %) roots))))))


(defn- validate-closures-v2
  "Each closure marker names carried code, agreeing with it."
  [engine code roots]
  (doseq [root roots
          m (tagged-of :yin.k/closure root)]
    (let [a (:yin.k/segment m)
          v (get code a)
          entry (:yin.k/entry m)]
      (case engine
        :semantic
        (do
          (when-not (and (= :named (:yin.k/binding m))
                         (= :yin.semantic/code (:yin.k/format m)))
            (undecodable! {:yin.k/kind :closure-profile}))
          (when-not (and (some? v) (exact-int? entry) (< entry (count v))
                         (some (fn [inst]
                                 (and (= :closure (first inst))
                                      (= entry (nth inst 2))
                                      (= (:yin.k/params m) (nth inst 1))))
                               v))
            (undecodable! {:yin.k/segment a :yin.k/kind :closure-code})))
        :stack
        (do
          (when-not (and (= :positional (:yin.k/binding m))
                         (= stack/format-tag (:yin.k/format m)))
            (undecodable! {:yin.k/kind :closure-profile}))
          (when-not (and (some? v) (exact-int? entry) (< entry (count v))
                         (some (fn [inst]
                                 (and (= :closure (first inst))
                                      (= entry (nth inst 2))
                                      (= (:yin.k/arity m) (nth inst 1))))
                               v))
            (undecodable! {:yin.k/segment a :yin.k/kind :closure-code})))
        :register
        (do
          (when-not (and (= :positional (:yin.k/binding m))
                         (= reffects/format-tag (:yin.k/format m)))
            (undecodable! {:yin.k/kind :closure-profile}))
          (when-not (and (some? v) (exact-int? entry)
                         (< entry (count (:instructions v)))
                         (some (fn [inst]
                                 (and (= :closure (nth inst 0))
                                      (= (:yin.k/arity m) (nth inst 2))
                                      (= entry (nth inst 3))))
                               (:instructions v)))
            (undecodable! {:yin.k/segment a :yin.k/kind :closure-code})))
        :walker
        (let [row v]
          (when-not (and (= :named (:yin.k/binding m))
                         (= :yin.ast/code (:yin.k/format m))
                         (nil? (:yin.k/entry m))
                         (vector? row) (= :lambda (nth row 1))
                         (= (:yin.k/params m) (nth row 2)))
            (undecodable! {:yin.k/segment a :yin.k/kind :closure-code})))
        (undecodable! {:yin.k/kind :unsupported-profile})))))


(defn- parked-site!
  "A parked record names an explicit park: the semantic safepoint kind,
   or the positional `:park` site."
  [engine {:keys [code]} pid r path]
  (let [ok? (case engine
              :walker true
              (:stack :register)
              (= :park (site-op engine
                                {:segment (:segment
                                            (compose-v2
                                              engine
                                              (mapv code (:yin.k/layout r))))
                                 :pc (:yin.k/pc r)
                                 :site-pc (:yin.k/site-pc r)}))
              (let [at (ucf/safepoint-at (get code (:yin.k/segment r))
                                         (:yin.k/pc r))]
                (and (not (contains? at :yin.k/status))
                     (contains? (set (:yin.safepoint/kinds at))
                                :explicit-park))))]
    (when-not ok?
      (undecodable! {:yin.k/parked-id pid
                     :yin.k/pc (:yin.k/pc r)
                     :yin.k/path path}))))


(defn- validate-install-v2
  [m inst]
  (closed! inst #{:yin.k/phase :yin.k/parent :yin.k/response :yin.k/child}
           #{} [:yin.k/installs m])
  (when-not (contains? #{:running :parked} (:yin.k/phase inst))
    (undecodable! {:yin.k/phase (:yin.k/phase inst)
                   :yin.k/path [:yin.k/installs m :yin.k/phase]}))
  (when-not (and (map? (:yin.k/child inst))
                 (true? (get (:yin.k/child inst) handoff-tag)))
    (undecodable! {:yin.k/name m :yin.k/path [:yin.k/installs m]})))


(defn- kw-chain
  [k]
  (take-while some? (iterate :yin.k/next k)))


(defn- body-registers
  "Every register map a body carries: its waits', its parked records'
   and those of the continuation values reachable in its roots."
  [body]
  (concat (map :yin.k/registers (:yin.k/frames body))
          (vals (:yin.k/parked body))
          (keep :yin.k/registers
                (mapcat #(tagged-of :yin.k/frame %) (reachable-values body)))))


(defn- walker-roots
  "Every row id the walker body names as a root: its frames' nodes and
   its closures' lambda rows."
  [body]
  (into #{}
        (concat
          (keep :yin.k/node (mapcat #(kw-chain (:yin.k/k %))
                                    (body-registers body)))
          (map :yin.k/segment
               (mapcat #(tagged-of :yin.k/closure %)
                       (reachable-values body))))))


(defn- walker-closure!
  "The set of row ids reachable from `roots` through the carried `code`;
   a reference no carried row answers is undecodable."
  [code roots]
  (loop [work (vec roots), seen #{}]
    (if-some [id (peek work)]
      (let [work (pop work)]
        (if (contains? seen id)
          (recur work seen)
          (let [row (get code id)]
            (when-not (vector? row)
              (undecodable! {:yin.k/segment id :yin.k/path [:yin.k/code]
                             :yin.k/kind :missing-row}))
            (recur (into work (row-children row)) (conj seen id)))))
      seen)))


(defn- validate-walker-rows!
  "Every row of the walker body validates under the v3 grammar from each
   of its roots: arity, slot kinds, saturation, resolution, acyclicity
   and Rule R."
  [code roots]
  (doseq [root (sort-by str roots)]
    (let [closure (walker-closure! code [root])]
      (when-some [defect (vm/validate-rows
                           {:root root
                            :rows (select-keys code closure)})]
        (undecodable! {:yin.k/segment root :yin.k/defect defect
                       :yin.k/path [:yin.k/code root]})))))


(defn- walker-bookkeeping!
  "A parked record whose continuation is the FFI response wrapper is
   correlation bookkeeping: allowed only when a live FFI pending of this
   task names its id, sharing its next and environment, and never the
   active parked record."
  [body]
  (let [justified
        (into {}
              (keep (fn [frame]
                      (let [pending (:yin.k/pending frame)]
                        (when (contains? #{:ffi :ffi-request}
                                         (:yin.k/reason pending))
                          [(:yin.k/call-id pending)
                           (:yin.k/registers frame)]))))
              (:yin.k/frames body))]
    (doseq [[pid r] (:yin.k/parked body)
            :let [k (:yin.k/k r)]
            :when (= :dao.stream.apply/eval-call (:yin.k/type k))]
      (let [frame-r (get justified pid)]
        (when (or (nil? frame-r)
                  (not= (:yin.k/next k) (:yin.k/next (:yin.k/k frame-r)))
                  (not= (:yin.k/env r) (:yin.k/env frame-r)))
          (undecodable! {:yin.k/parked-id pid :yin.k/kind :ffi-bookkeeping
                         :yin.k/path [:yin.k/parked pid]}))
        (when (and (= :parked (:yin.k/kind body))
                   (= pid (:yin.k/parked-id body)))
          (undecodable! {:yin.k/parked-id pid :yin.k/kind :ffi-bookkeeping
                         :yin.k/path [:yin.k/parked-id]}))))))


(defn- validate-body-v2
  "The version-2 body grammar both ends run: the closed body, the
   profile's own code validator and address, closed registers and
   pendings, the dependency closure (carried code is exactly what the
   body names), nested install roles and the cell census.  A malformed
   body is refused whole, before any attachment or restoration."
  [body]
  (when-not (and (map? body) (true? (get body handoff-tag)))
    (undecodable! {:yin.k/kind :body}))
  (let [engine (ucf/profile-engine (:yin.k/contract body))
        kind (:yin.k/kind body)]
    (when-not (v2-body? body)
      (refuse! :yin.k/profile-mismatch
               {:yin.k/version (:yin.k/version body)
                :yin.k/supported handoff-version}))
    (when (nil? engine)
      (refuse! :yin.k/profile-mismatch
               {:yin.k/contract (:yin.k/contract body)
                :yin.k/supported ucf/supported-profiles}))
    (when-not (contains? #{:blocked :parked :halted} kind)
      (undecodable! {:yin.k/kind kind :yin.k/path [:yin.k/kind]}))
    (closed! body
             (cond-> #{handoff-tag :yin.k/version :yin.k/contract :yin.k/kind
                       :yin.k/id-counter :yin.k/store :yin.k/module-stores
                       :yin.k/parked :yin.k/cells :yin.k/requires
                       :yin.k/code :yin.k/installs}
               (pc-profile? engine)
               (into [:yin.k/layout :yin.k/free-env])
               (= :blocked kind) (conj :yin.k/frames)
               (= :parked kind) (into [:yin.k/frames :yin.k/parked-id])
               (= :halted kind) (conj :yin.k/result))
             #{:yin.k/policy :yin.k/occurrence :yin.k/arbitration
               :yin.k/origin :yin.k/next-op-seq}
             [])
    (let [cells (:yin.k/cells body)
          code (:yin.k/code body)
          frames (:yin.k/frames body)]
      (when-not (and (map? cells) (map? code) (map? (:yin.k/store body))
                     (map? (:yin.k/module-stores body))
                     (map? (:yin.k/parked body))
                     (map? (:yin.k/installs body)))
        (undecodable! {:yin.k/path [:yin.k/cells]}))
      (closed! (:yin.k/requires body)
               #{:yin.k/cursor-profiles :yin.k/segments} #{}
               [:yin.k/requires])
      (doseq [[cid cell] cells]
        (closed! cell #{:yin.k/stream :yin.k/position} #{}
                 [:yin.k/cells cid])
        (when-not (marker? (:yin.k/stream cell))
          (undecodable! {:yin.k/cell cid :yin.k/path [:yin.k/cells cid]})))
      (validate-code-v2 engine code)
      (when (pc-profile? engine)
        (validate-layout-v2 engine code body))
      (when-not (exact-int? (:yin.k/id-counter body))
        (undecodable! {:yin.k/path [:yin.k/id-counter]}))
      (when (contains? body :yin.k/frames)
        (when-not (vector? frames)
          (undecodable! {:yin.k/path [:yin.k/frames]})))
      (when (and (= :blocked kind) (empty? frames))
        (undecodable! {:yin.k/path [:yin.k/frames]
                       :yin.k/kind :blocked-without-frames}))
      (let [ctx {:code code
                 :layout (:yin.k/layout body)
                 :parked (:yin.k/parked body)}]
        (doseq [[i frame] (map-indexed vector frames)]
          (let [path [:yin.k/frames i]
                pending (:yin.k/pending frame)
                reason (:yin.k/reason pending)]
            (closed! frame #{:yin.k/registers :yin.k/pending} #{} path)
            (when-not (and (map? pending) (contains? pending-keys reason))
              (undecodable! {:yin.k/path (conj path :yin.k/pending)
                             :yin.k/reason reason}))
            (let [[required optional] (get pending-keys reason)]
              (closed! pending required optional (conj path :yin.k/pending)))
            (validate-registers-v2 engine ctx (:yin.k/registers frame)
                                   (conj path :yin.k/registers) :wait)
            (validate-pending cells pending (conj path :yin.k/pending))
            (check-frame-pc-v2 engine ctx pending (:yin.k/registers frame)
                               (conj path :yin.k/registers))))
        (when (= :parked kind)
          (when-not (contains? (:yin.k/parked body) (:yin.k/parked-id body))
            (undecodable! {:yin.k/parked-id (:yin.k/parked-id body)
                           :yin.k/path [:yin.k/parked-id]})))
        (doseq [[pid r] (:yin.k/parked body)]
          (let [path [:yin.k/parked pid]]
            (validate-registers-v2 engine ctx r path :parked)
            (parked-site! engine ctx pid r path)))
        (when (and (= :halted kind) (seq frames))
          (undecodable! {:yin.k/path [:yin.k/frames]}))
        (let [roots (reachable-values body)]
          (validate-closures-v2 engine code roots)
          (doseq [root roots
                  f (tagged-of :yin.k/frame root)]
            (let [path [:yin.k/frame]]
              (if (contains? f :yin.k/parked-id)
                (do (closed! f #{:yin.k/tag :yin.k/parked-id} #{} path)
                    (when-not (contains? (:yin.k/parked body)
                                         (:yin.k/parked-id f))
                      (undecodable! {:yin.k/parked-id (:yin.k/parked-id f)
                                     :yin.k/path path})))
                (do (closed! f #{:yin.k/tag :yin.k/registers} #{} path)
                    (validate-registers-v2 engine ctx (:yin.k/registers f)
                                           (conj path :yin.k/registers)
                                           :captured)))))))
      (doseq [[m inst] (:yin.k/installs body)]
        (validate-install-v2 m inst)
        (validate-body-v2 (:yin.k/child inst))
        (when-not (= (:yin.k/contract body)
                     (:yin.k/contract (:yin.k/child inst)))
          (undecodable! {:yin.k/name m :yin.k/kind :mixed-profile
                         :yin.k/path [:yin.k/installs m :yin.k/child
                                      :yin.k/contract]})))
      (doseq [[i frame] (map-indexed vector frames)]
        (let [pending (:yin.k/pending frame)]
          (when (and (= :install (:yin.k/reason pending))
                     (not (contains? (:yin.k/installs body)
                                     (:yin.k/name pending))))
            (undecodable! {:yin.k/name (:yin.k/name pending)
                           :yin.k/kind :incomplete-install
                           :yin.k/path [:yin.k/frames i :yin.k/pending]}))))
      (when (= :walker engine)
        (walker-bookkeeping! body))
      (let [named (if (= :walker engine)
                    (let [roots (walker-roots body)
                          closure (walker-closure! code roots)]
                      (validate-walker-rows! code roots)
                      closure)
                    (into (referenced-segments-v2 body)
                          (:yin.k/layout body)))
            carried (set (keys code))]
        (when-some [a (first (sort-by str (set/difference named carried)))]
          (undecodable! {:yin.k/segment a :yin.k/path [:yin.k/code]}))
        (when-some [a (first (sort-by str (set/difference carried named)))]
          (undecodable! {:yin.k/segment a :yin.k/kind :extra-code
                         :yin.k/path [:yin.k/code a]}))
        (when-not (= carried (:yin.k/segments (:yin.k/requires body)))
          (undecodable! {:yin.k/path [:yin.k/requires :yin.k/segments]})))
      (let [stores (set (keys (:yin.k/module-stores body)))
            roots (reachable-values body)
            wanted (into #{}
                         (comp (mapcat #(tree-seq coll?
                                                  (fn [n]
                                                    (if (map? n)
                                                      (concat (keys n) (vals n))
                                                      (seq n)))
                                                  %))
                               (filter map?)
                               (keep :yin.k/store-of))
                         (conj roots (:yin.k/free-env body)))]
        (when-some [m (first (sort-by str (set/difference wanted stores)))]
          (non-portable! :missing-module-store {engine/store-of-key m})))
      (let [referenced (referenced-cells body)
            missing (remove #(contains? cells %) referenced)
            extra (remove #(contains? referenced %) (keys cells))]
        (when-some [cid (first missing)]
          (undecodable! {:yin.k/cell cid :yin.k/path [:yin.k/cells]}))
        (when-some [cid (first (sort-by str extra))]
          (undecodable! {:yin.k/cell cid :yin.k/kind :extra-cell
                         :yin.k/path [:yin.k/cells cid]})))
      (when (and (seq cells)
                 (not (contains? (get-in body [:yin.k/requires
                                               :yin.k/cursor-profiles])
                                 :dao.stream.remote/v1)))
        (unsatisfied! nil))
      body)))


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


(defn- decode-env-v2
  [decode e]
  (cond-> (into {}
                (map (fn [[k v]] [k (decode v)]))
                (:yin.k/bindings e))
    (some? (:yin.k/store-of e))
    (assoc engine/store-of-key (:yin.k/store-of e))))


(defn- decode-kw
  "The native walker continuation chain a wire frame chain denotes:
   addressed nodes are the receiver's decoded rows, runtime fields come
   back as values, and an absent environment stays absent."
  [node-of decode kw]
  (when (some? kw)
    (let [t (:yin.k/type kw)
          base (cond-> {:type t
                        :next (decode-kw node-of decode (:yin.k/next kw))}
                 (contains? kw :yin.k/env)
                 (assoc :env (decode-env-v2 decode (:yin.k/env kw))))
          node (node-of (:yin.k/node kw))]
      (case t
        (:eval-operator :eval-test :eval-stream-put-target
                        :eval-stream-cursor-source :eval-stream-next-cursor
                        :eval-stream-close-source)
        (assoc base :frame node)

        :eval-operand
        (assoc base :frame (assoc node
                                  :operator-evaluated? true
                                  :fn (decode (:yin.k/function kw))
                                  :evaluated (mapv decode
                                                   (:yin.k/evaluated kw))))

        :dao.stream.apply/eval-operand
        (assoc base :frame {:op (:op node)
                            :operands (:operands node)
                            :evaluated (mapv decode (:yin.k/evaluated kw))})

        :eval-stream-put-val
        (assoc base
               :frame node
               :stream-ref (decode (:yin.k/stream-ref kw)))

        :dao.stream.apply/request-sent
        (assoc base :parked-id (:yin.k/parked-id kw) :op (:yin.k/op kw))

        :dao.stream.apply/eval-call
        (cond-> base
          (contains? kw :yin.k/call-id) (assoc :call-id (:yin.k/call-id kw)))

        :eval-define (assoc base :name (:yin.k/name kw))
        :eval-resume-val (assoc base :parked-id (:yin.k/parked-id kw))))))


(defn- decode-registers-v2
  "One version-2 register map back in the receiver's native coordinates:
   segments through the alias column of the attached code (`ctx`'s
   `:aliases`), or the layout rebuilt from the carried `:code`, values
   decoded and re-sealed under the receiving task."
  [engine decode {:keys [aliases code node-of]} r]
  (case engine
    :walker
    {:env (decode-env-v2 decode (:yin.k/env r))
     :k (decode-kw node-of decode (:yin.k/k r))}
    :stack
    (let [c (stack/compose (mapv code (:yin.k/layout r)))
          frames (fn [fs] (mapv #(mapv decode %) fs))]
      (cond-> {:segment (:segment c)
               :hash (:hash c)
               :format stack/format-tag
               :image (:yin.k/image r)
               :pc (:yin.k/pc r)
               :frames (frames (:yin.k/frames r))
               :stack (mapv decode (:yin.k/stack r))
               :continuation
               (mapv (fn [f]
                       (cond-> {:return-pc (:yin.k/return-pc f)
                                :frames (frames (:yin.k/frames f))
                                :stack-base (:yin.k/stack-base f)}
                         (some? (:yin.k/store-of f))
                         (assoc :store-of (:yin.k/store-of f))))
                     (:yin.k/continuation r))}
        (some? (:yin.k/store-of r))
        (assoc :store-of (:yin.k/store-of r))))
    :register
    (let [c (register/compose (mapv code (:yin.k/layout r)))
          frames (fn [fs] (mapv #(mapv decode %) fs))
          pairs (fn [regs] (mapv (fn [[i x]] [i (decode x)]) regs))]
      (cond-> {:segment (:segment c)
               :hash (:hash c)
               :format reffects/format-tag
               :image (:yin.k/image r)
               :site-pc (:yin.k/site-pc r)
               :pc (:yin.k/pc r)
               :frames (frames (:yin.k/frames r))
               :regs (pairs (:yin.k/regs r))
               :live (:yin.k/live r)
               :continuation
               (mapv (fn [f]
                       (cond-> {:site-pc (:yin.k/site-pc f)
                                :return-pc (:yin.k/return-pc f)
                                :frames (frames (:yin.k/frames f))
                                :regs (pairs (:yin.k/regs f))
                                :live (:yin.k/live f)
                                :dest (:yin.k/dest f)}
                         (some? (:yin.k/store-of f))
                         (assoc :store-of (:yin.k/store-of f))))
                     (:yin.k/continuation r))
               :dest (:yin.k/dest r)
               :resume-mode (:yin.k/resume-mode r)}
        (< 1 (count (:images c))) (assoc :images (:images c))
        (some? (:yin.k/store-of r))
        (assoc :store-of (:yin.k/store-of r))))
    :semantic
    {:segment (get aliases (:yin.k/segment r))
     :pc (:yin.k/pc r)
     :env (decode-env-v2 decode (:yin.k/env r))
     :stack (mapv decode (:yin.k/stack r))
     :k (mapv (fn [f]
                {:type :return
                 :segment (get aliases (:yin.k/segment f))
                 :pc (:yin.k/pc f)
                 :env (decode-env-v2 decode (:yin.k/env f))
                 :stack-base (:yin.k/stack-base f)})
              (:yin.k/k r))}
    (undecodable! {:yin.k/kind :unsupported-profile})))


(defn- value-decoder
  "The decode half, over the receiver's coordinates: literals back to
   maps, closures through the kernel against the attached code,
   primitives from the receiver's own registry under an equal profile,
   and stream and cursor markers as fresh references sealed under the
   receiving task's own secret."
  ([recv streams cells]
   (value-decoder recv streams cells nil))
  ([recv streams cells frames]
   (letfn [(decode
             [x]
             (cond
               (scalar? x) x
               (jing.cbor/numeric? x) x
               (map? x)
               (case (:yin.k/tag x)
                 :yin.k/frame
                 (if (and frames (contains? x :yin.k/parked-id))
                   ((:parked @frames) (:yin.k/parked-id x))
                   (if frames
                     (values/continuation
                       (:owner recv)
                       (assoc ((:reified @frames) (:yin.k/registers x))
                              :type :reified-continuation))
                     (undecodable! {:yin.k/tag :yin.k/frame})))
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
     decode)))


(defn- spawn-child-template
  "A fresh child VM of the receiver's composition for one install
   response -- the engine's own spawn discipline (yin.vm.linker.md
   7.3): nothing of the receiver's state but its composition values,
   a fresh origin tag, and the secret the composition's source mints
   for it."
  [recv response module-name fenced?]
  (let [n (or (:origins recv) 0)
        origin (keyword (str (name (or (:origin recv) :t0)) "." n))
        registry (reduce (fn [r [m e]]
                           (module/assoc-module r m (dissoc e :bindings)))
                         (:modules recv)
                         (module/module-entries (:modules recv)))
        source (:secret-source recv)
        child (module/spawn-module
                (cond-> recv fenced? (assoc :make-stream nil :resources {}))
                (:value (:image response))
                {:modules registry
                 :origin origin
                 :ancestry (conj (vec (:ancestry recv)) module-name)
                 :capability-secret (when (fn? source) (source origin))})]
    (assoc child :origins (inc n) :make-stream (:make-stream recv))))


(defn- child-bytes
  "A child body re-encoded for its own recursive resume under the codec
   its version rides: version 1 in jing canonical bytes, version 0 in
   the stream codec, exactly as it arrived inside its parent."
  [child-body]
  (if (= 0 (:yin.k/version child-body))
    (cbor/encode child-body)
    (jing.cbor/encode child-body)))


(defn- resume-installs
  "Each install child resumed into a fresh child of the receiver's
   composition, its phase and response carried: the receiver's own
   scheduler steps it exactly as the source's did.  A child that
   refuses -- its bytes undecodable, a stream it names unattachable
   -- aborts the parent's restoration: the refusal is thrown here,
   before any machine value is assembled, so the parent never answers
   :ok over a child that did not lower.  The child is resumed as an
   install child, not a custody root: its own reader pass skips the
   custody step the root already ran over it."
  [recv body attach! opts fenced?]
  (into {}
        (map-indexed (fn [index [m inst]]
                       (let [response (:yin.k/response inst)
                             child-body (:yin.k/child inst)
                             resumed (resume-task*
                                       (if-some [spawn (:child-of opts)]
                                         (spawn recv response m)
                                         (spawn-child-template
                                           (cond-> recv
                                             fenced? (assoc :origins (+ (or (:origins recv) 0) index)))
                                           response m fenced?))
                                       (child-bytes child-body)
                                       attach!
                                       opts
                                       true
                                       fenced?)]
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
  [engine decode streams cells cell-keys pending ctx]
  (let [walker? (= :walker engine)
        stream-of (fn [marker]
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
                      :stream-id (cell-stream (:yin.k/cell pending))
                      :cursor-ref {:type :cursor-ref
                                   :id (cell-key (:yin.k/cell pending))}}
               (not walker?)
               (assoc :call-id (:yin.k/call-id pending))
               (and (not walker?) (contains? pending :yin.k/op))
               (assoc :op (:yin.k/op pending))))

      :put
      (merge ctx
             {:reason :put
              :stream-id (stream-of (:yin.k/stream pending))
              :datom (decode (:yin.k/value pending))})

      :ffi-request
      (merge ctx
             (cond-> {:reason :put
                      :stream-id (stream-of (:yin.k/request pending))
                      :datom (decode (:yin.k/request-envelope pending))}
               (not walker?)
               (assoc :request-sent true
                      :call-id (:yin.k/call-id pending)
                      :op (:yin.k/request-op pending)
                      :response-cursor (cell-key (:yin.k/response-cell pending))
                      :response-stream (stream-of (:yin.k/response pending)))))

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


;; =============================================================================
;; The version-aware reader (M-next D7): two codecs, one grammar each
;; =============================================================================

(def ^:private codec-versions
  "The body-version contract of each accepting codec, the two-codec
   split of the D plan's residual 3: stage-1 `dao.stream.cbor` bytes are
   version 0 only -- that wire is frozen -- and `dao.jing.cbor` canonical
   bytes are version 1 only, the stream codec's tag-39 identifiers being
   refused by jing's decoder.  Scalar overlap between the codecs
   establishes no body compatibility; which codec accepted decides."
  {:stream #{0} :jing #{1 2}})


(defn- decode-two
  "Reader step 1: the version-1 decode first (jing), then the version-0
   decode (the stream codec).  Answers `{:codec c :body b}` for the
   first codec that accepted the bytes; bytes neither decodes are
   `:yin.k/undecodable`."
  [bytes]
  (letfn [(attempt
            [decode]
            (try {:body (decode bytes)}
                 (catch #?(:cljd Object :clj Throwable :cljs :default) _
                   nil)))]
    (or (when-some [r (attempt jing.cbor/decode)]
          (assoc r :codec :jing))
        (when-some [r (attempt cbor/decode)]
          (assoc r :codec :stream))
        (undecodable! {:yin.k/kind :bytes}))))


(defn- require-tag!
  "Reader step 2: the S7.5.1 tag, before any version or address rule."
  [body]
  (when-not (and (map? body) (true? (get body handoff-tag)))
    (undecodable! {:yin.k/kind :body})))


(defn- version-gate!
  "Reader step 3, before the address check (UCF 7.2.1): the version
   contract of the codec that accepted the bytes.  A version the codec
   does not speak, an absent version, or one not of the integer kind is
   `:yin.k/profile-mismatch`, carrying the version as found and the
   supported set.  Under `opts`' `:exclusive` a version-0 body is the
   same refusal: a reader that requires exclusive custody never
   downgrades a fork into it."
  [codec body opts install-child?]
  (let [supported (get codec-versions codec)
        v (get body :yin.k/version)]
    (when-not (and (jing.cbor/numeric? v)
                   (= :integer (jing.cbor/numeric-kind v))
                   (contains? supported v))
      (refuse! :yin.k/profile-mismatch
               {:yin.k/version v :yin.k/supported supported}))
    (when (and (:exclusive opts)
               (if (v2-body? body)
                 ;; version 2 is a fork exactly when it carries no custody
                 ;; header key, and only a root is a custody subject
                 (and (not install-child?)
                      (not-any? #(contains? body %)
                                [:yin.k/policy :yin.k/occurrence
                                 :yin.k/arbitration :yin.k/origin
                                 :yin.k/next-op-seq]))
                 (not= 1 v)))
      (refuse! :yin.k/profile-mismatch
               {:yin.k/version v
                :yin.k/supported #{1 2}
                :yin.k/policy :yin.k/exclusive}))))


(defn- custody-inspect!
  "Reader step 4, version 1 only: `checkpoint/inspect` over the claimed
   address and the exact bytes -- the address (`:yin.k/hash-mismatch`)
   first, then the custody grammar, including an entry for every install
   pending.  The address is the one `opts` names, the address the bytes
   were fetched under; unnamed, it is the address of the bytes
   themselves.  A structural failure here is never reinterpreted as
   version 0: the accepting codec is already fixed.  Answers the
   operation baseline D10's lower will consume."
  [bytes opts]
  (let [address (or (:address opts) (bytes-address bytes))
        r (checkpoint/inspect address bytes)]
    (when (contains? r :yin.k/status)
      (refuse! (:yin.k/status r) (dissoc r :yin.k/status)))
    r))


(defn- task-bodies
  [body]
  (tree-seq #(seq (:yin.k/installs %))
            #(map :yin.k/child (vals (:yin.k/installs %))) body))


(defn- accept-grant!
  [body opts]
  (let [grant (:grant opts)
        evidence (:evidence grant)]
    (when-not (and (or (nil? grant) (map? grant))
                   (or (nil? evidence) (map? evidence)))
      (throw (ex-info "Malformed lower grant or evidence" {})))
    (when-not evidence
      (refuse! :yin.k/awaiting-grant {}))
    (when-not (= :yin.k/ready (:yin.k/status evidence))
      (refuse! (if (= :yin.k/awaiting-grant (:yin.k/status evidence))
                 :yin.k/awaiting-grant :yin.k/unsatisfied)
               (dissoc evidence :yin.k/status)))
    (let [binding (:yin.k/binding evidence)
          prefix (:yin.k/prefix evidence)
          tenure (:tenure grant)
          occurrence (:yin.k/occurrence body)
          lease (:dao.lease/lease grant)
          holder (:dao.lease/holder grant)
          frontier (:yin.k/frontier prefix)
          records (:yin.k/inputs prefix)]
      (when-not (and (map? binding) (map? tenure))
        (throw (ex-info "Malformed lower binding or tenure" {})))
      (when (or (nil? prefix) (:yin.k/status prefix))
        (refuse! :yin.k/unsatisfied
                 {:yin.k/reason (or (:yin.k/reason prefix) :unavailable)}))
      (when-not (and (some? (:address opts))
                     (= (:checkpoint grant) (:address opts))
                     (= occurrence (:yin.k/occurrence binding))
                     (some? lease) (= lease (:dao.lease/lease binding))
                     (some? holder) (= holder (:dao.lease/holder binding))
                     (= (get-in body [:yin.k/arbitration :dao.stream/identity])
                        (get-in binding [:yin.k/transaction :yin.k/arbitration]))
                     (custody/exact? (:yin.k/epoch binding))
                     (true? (:live tenure))
                     (number? (:now tenure)) (number? (:bound tenure))
                     (< (:now tenure) (:bound tenure))
                     (= occurrence (:yin.k/occurrence prefix))
                     (= lease (:dao.lease/lease prefix))
                     (custody/exact? frontier) (vector? records)
                     (= frontier (count records))
                     (every? true? (map-indexed
                                     (fn [index record]
                                       (and (custody/exact? (:yin.k/input-seq record))
                                            (= index (:yin.k/input-seq record))))
                                     records)))
        (refuse! :yin.k/not-holder {}))
      (let [classes (:protection opts)
            enrolled (:yin.k/enrolled evidence)]
        (when-not (and (map? classes)
                       (every? #{:enrolled :at-least-once :fail-stop} (vals classes))
                       (set? enrolled))
          (throw (ex-info "Malformed lower protection or enrollment" {})))
        (doseq [task (task-bodies body)
                marker (body-markers task)]
          (let [identity (:dao.stream/identity marker)
                class (get classes identity)]
            (when-not (and class (= (= class :enrolled)
                                    (contains? enrolled identity)))
              (unsatisfied! identity))))
        (doseq [task (task-bodies body)
                frame (:yin.k/frames task)
                :let [pending (:yin.k/pending frame)
                      reason (:yin.k/reason pending)]
                :when (contains? #{:put :ffi-request :link-request} reason)]
          (let [marker (if (= :put reason) (:yin.k/stream pending)
                           (:yin.k/request pending))
                identity (:dao.stream/identity marker)]
            (when-not (= (= :enrolled (get classes identity))
                         (contains? pending :yin.k/op-id))
              (unsatisfied! identity))))
        {:yin.k/occurrence occurrence
         :dao.lease/lease lease :dao.lease/holder holder
         :yin.k/epoch (:yin.k/epoch binding)
         :yin.k/arbitration (:yin.k/arbitration body)
         :yin.k/next-op-seq (:yin.k/next-op-seq body)
         :protection classes :input {:next 0 :prefix prefix}
         :tenure {:bound (:bound tenure)}}))))


(defn- isolated-receiver
  [recv]
  (reduce (fn [machine [module-name entry]]
            (update machine :modules module/assoc-module module-name
                    entry))
          (-> recv
              (assoc :store {} :module-stores {} :parked {} :installs {})
              (assoc-in [:modules :modules] {})
              (dissoc :yin.k/custody :yin.k/gate :yin.k/closes :yin.k/issued
                      :yin.k/recovery-cell-ids :yin.k/recovery-waits))
          (filter (fn [[_ entry]] (nil? (:address entry)))
                  (module/module-entries (:modules recv)))))


(defn- primitives-satisfied!
  "Every primitive any task of the tree names exists in `recv`'s own
   registry under an equal profile, or the lower is `:yin.k/unsatisfied`
   naming it: judged on the bytes' markers before one attachment is made,
   never found missing at decode time."
  [recv body]
  (doseq [task (task-bodies body)
          root (reachable-values task)
          marker (tagged-of :yin.k/primitive root)]
    (let [n (:yin.k/name marker)
          entry (get (:primitives recv) n)]
      (when-not (and (some? entry)
                     (= (:yin.k/profile marker)
                        (:yin.k/profile (vm/profile-of (:primitives recv) n))))
        (refuse! :yin.k/unsatisfied {:yin.k/name n})))))


(defn- resume-task*
  "The lower of 14.1.2 behind the version-aware reader of M-next D7:
   `bytes`, a handoff body this namespace minted, resumed into a fresh
   task over `recv` -- a machine of the receiver's own composition.
   Before any attachment or restoration, in this order: the two-codec
   decode (jing first, then the stream codec; neither accepting is
   `:yin.k/undecodable`), the tag, the version gate of the accepting
   codec (`:yin.k/profile-mismatch`, before the address check), and --
   version 1 only -- `checkpoint/inspect` over the address `opts` names
   (`:yin.k/hash-mismatch`, then the custody grammar); then the full
   recursive grammar of `validate-body`, the contract stamp and the
   restoration itself.  `opts` may name `:exclusive` (a version-0 body
   is then refused; without it such a body still lowers as a fork) and
   `:address`. A blocked or parked version-1 root additionally requires
   `:grant` (checkpoint, lease, holder, evidence and current tenure) and
   `:protection` keyed by portable stream identity. Grant consistency and
   recursive protection checks precede all attachments. Missing evidence
   answers `:yin.k/awaiting-grant`, invalid binding `:yin.k/not-holder`,
   and unavailable evidence or protection mismatch `:yin.k/unsatisfied`.
   Accepted custody restores the body's operation counter exactly and
   starts input replay at zero. Halted version-1 results need no grant
   and are gated ended. Install children are resumed as children, their pass
   skipping the custody step their root already ran.  Answers
   `{:status :ok :kind k :vm resumed}` or the first data refusal:
   `:yin.k/undecodable` bytes or grammar, `:yin.k/profile-mismatch`
   stamp or version, `:yin.k/hash-mismatch` address or code,
   `:yin.k/unsatisfied` a stream that cannot be attached or a primitive
   the receiver cannot answer.  Nothing partially runnable is ever
   exposed: the machine value is assembled only after every restoration
   step passes.  `attach!` is the receiver's own `:dao.stream/attach`
   dispatch, exactly `yin.vm.ucf.remote`'s; a version-0 resume is a
   fork."
  ([recv bytes attach!]
   (resume-task recv bytes attach! nil))
  ([recv bytes attach! opts install-child?]
   (resume-task* recv bytes attach! opts install-child? false))
  ([recv bytes attach! opts install-child? fenced?]
   (call!
     (fn []
       (let [{:keys [codec body]} (decode-two bytes)
             _ (require-tag! body)
             _ (version-gate! codec body opts install-child?)
             v2? (v2-body? body)
             _ (when (and (or v2? (= 1 (:yin.k/version body)))
                          (not install-child?))
                 (custody-inspect! bytes opts))
             _ (validate-body body)
             engine (when v2? (ucf/profile-engine (:yin.k/contract body)))
             _ (if v2?
                 (when-not (= engine (engine-of recv))
                   (refuse! :yin.k/profile-mismatch
                            {:yin.k/contract (:yin.k/contract body)
                             :yin.k/supported ucf/supported-profiles}))
                 (when-not (= ucf/contract-stamp
                              (:yin.k/contract body))
                   (refuse! :yin.k/profile-mismatch
                            {:yin.k/contract (:yin.k/contract body)})))
             _ (when v2?
                 (primitives-satisfied! recv body))
             kind (:yin.k/kind body)
             ;; a version-2 tree is a fork exactly when its root carries no
             ;; custody header key; its children inherit the root's role
             tree-fork? (if install-child?
                          (boolean (::fork opts))
                          (and v2? (not-any? #(contains? body %)
                                             [:yin.k/policy :yin.k/occurrence
                                              :yin.k/arbitration :yin.k/origin
                                              :yin.k/next-op-seq])))
             opts (assoc opts ::fork tree-fork?)
             version-one? (or (= 1 (:yin.k/version body))
                              (and v2? (not tree-fork?)))
             custody-state (when (and version-one? (not install-child?) (not fenced?)
                                      (not= :halted kind))
                             (accept-grant! body opts))
             cells (:yin.k/cells body)
             pendings (mapv :yin.k/pending
                            (or (:yin.k/frames body) []))
             recv' (cond
                     (= :walker engine)
                     (walker/attach-rows
                       (walker/clear-code (isolated-receiver recv))
                       (:yin.k/code body)
                       vm/ast-contract)
                     (pc-profile? engine)
                     ((if (= :stack engine) stack/rebuild register/rebuild)
                      (isolated-receiver recv)
                      (mapv (:yin.k/code body) (:yin.k/layout body)))
                     :else
                     (reduce (fn [r [_a v]] (module/attach-module r v))
                             (cond
                               (= :semantic engine)
                               ;; the receiver's own code layout is cleared
                               ;; before the body's is rebuilt (v2, 9)
                               (assoc (isolated-receiver recv)
                                      :code {} :code-aliases {} :program nil)
                               (or version-one? v2? fenced?)
                               (isolated-receiver recv)
                               :else recv)
                             (:yin.k/code body)))
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
             frame-hooks (atom nil)
             decode (value-decoder recv' streams cell-keys frame-hooks)
             aliases (:code-aliases recv')
             decode-regs (if v2?
                           (partial decode-registers-v2 engine decode
                                    {:aliases aliases
                                     :code (:yin.k/code body)
                                     :node-of (partial walker/row-node recv')})
                           (partial decode-registers decode aliases))
             parked-cache (atom {})
             resolving (atom #{})
             resolve-parked
             (fn [pid]
               (or (get @parked-cache pid)
                   (do (when (contains? @resolving pid)
                         (non-portable! :non-canonicalizable
                                        {:yin.k/hint :parked-cycle}))
                       (swap! resolving conj pid)
                       (let [rec (assoc (decode-regs
                                          (get-in body [:yin.k/parked pid]))
                                        :type :parked-continuation
                                        :id pid)]
                         (swap! parked-cache assoc pid rec)
                         (swap! resolving disj pid)
                         rec))))
             _ (when v2?
                 (reset! frame-hooks {:parked resolve-parked
                                      :reified decode-regs}))
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
                                 [pid (if v2?
                                        (resolve-parked pid)
                                        (assoc
                                          (decode-registers decode aliases r)
                                          :type :parked-continuation
                                          :id pid))]))
                          (:yin.k/parked body))
             installs (resume-installs recv' body attach! opts fenced?)
             registers (mapv (fn [frame]
                               (decode-regs (:yin.k/registers frame)))
                             (or (:yin.k/frames body) []))
             raw-entries (mapv (fn [pending ctx]
                                 (cond-> (lower-frame! engine decode streams cells cell-keys
                                                       pending ctx)
                                   (and version-one? (contains? pending :yin.k/op-id))
                                   (assoc :op-id (:yin.k/op-id pending))))
                               pendings registers)
             [entries issued] (if version-one?
                                (reduce (fn [[acc issue] entry]
                                          (if (= :put (:reason entry))
                                            [(conj acc (assoc entry :yin.k/issue issue)) (inc issue)]
                                            [(conj acc entry) issue]))
                                        [[] 0]
                                        raw-entries)
                                [raw-entries nil])
             machine (cond-> (-> recv'
                                 (assoc :store store
                                        :module-stores (merge (:module-stores recv')
                                                              module-stores)
                                        :parked (merge (:parked recv') parked)
                                        :installs (merge (:installs recv') installs)
                                        :resources (merge (:resources recv')
                                                          resources
                                                          cell-resources
                                                          (when (or fenced? (= :walker engine))
                                                            (reduce (fn [acc pending]
                                                                      (if (= :ffi-request (:yin.k/reason pending))
                                                                        (assoc acc
                                                                               vm/call-in-stream-key
                                                                               (get resources (streams (get-in pending [:yin.k/request :dao.stream/identity])))
                                                                               vm/call-out-stream-key
                                                                               (get resources (streams (get-in pending [:yin.k/response :dao.stream/identity])))
                                                                               vm/call-out-cursor-key
                                                                               (get cell-resources (cell-keys (:yin.k/response-cell pending))))
                                                                        acc))
                                                                    {} pendings))
                                                          (link-pair-resources
                                                            streams resources
                                                            pendings))
                                        :id-counter (max (or (:id-counter recv') 0)
                                                         (or (:yin.k/id-counter body)
                                                             0))
                                        :ready-queue []
                                        :control nil
                                        :k nil))
                       (pc-profile? engine)
                       (assoc :free-env (decode-env-v2 decode (:yin.k/free-env body)))
                       version-one?
                       (assoc :yin.k/issued issued
                              :yin.k/gate (if (and (not install-child?)
                                                   (= :halted kind))
                                            :ended :running))
                       fenced? (assoc :yin.k/gate :exporting
                                      :yin.k/recovery-cell-ids
                                      (into (into {} (map (fn [[cell-id resource-id]]
                                                            [resource-id cell-id])) cell-keys)
                                            (keep (fn [pending]
                                                    (when (contains? #{:link-request :link-response}
                                                                     (:yin.k/reason pending))
                                                      [[module/link-response-resource
                                                        (get-in cells [(:yin.k/cell pending) :yin.k/position])]
                                                       (:yin.k/cell pending)])))
                                            pendings)
                                      :id-counter (or (:yin.k/id-counter body) 0))
                       custody-state (assoc :yin.k/custody custody-state))]
         (cond-> {:status :ok
                  :kind kind
                  :vm (case kind
                        :halted
                        (assoc machine
                               :halted? true :blocked? false
                               :value (decode (:yin.k/result body))
                               :wait-set [])

                        ;; the parked activation waits on nothing; every other
                        ;; carried frame is still an ordered wait (UCF 7.4.3)
                        :parked
                        (assoc machine
                               :halted? true :blocked? false
                               :value (get parked (:yin.k/parked-id body))
                               :wait-set entries)

                        (assoc machine
                               :halted? false :blocked? true
                               :value :yin/blocked
                               :wait-set entries))}
           fenced? (update :vm (fn [restored]
                                 (assoc restored
                                        :yin.k/recovery-waits (:wait-set restored)
                                        :wait-set [])))))))))


(defn rehydrate-fenced-body
  "Administrative reconstruction of a validated handoff snapshot.
   Unlike resume-task, never admits execution or restores custody. Root
   and install children remain exporting with waits outside the scheduler."
  [receiver bytes attach! address]
  (resume-task* receiver bytes attach! {:address address} false true))


(defn inspect-recovery-body
  "Validate an embedded export snapshot before administrative attachment."
  ([bytes address header]
   (inspect-recovery-body bytes address header true))
  ([bytes address header complete?]
   (call!
     (fn []
       (let [{:keys [codec body]} (decode-two bytes)]
         (require-tag! body)
         (version-gate! codec body nil false)
         (when (or (v2-body? body) (= 1 (:yin.k/version body)))
           (custody-inspect! bytes {:address address}))
         (when-not (jing/segment-bytes-match? address bytes)
           (refuse! :yin.k/hash-mismatch {}))
         (validate-body body)
         (doseq [task (task-bodies body)
                 root (reachable-values task)
                 closure (tagged-of :yin.k/closure root)
                 :let [store-of (:yin.k/store-of closure)]
                 :when (and complete? (some? store-of))]
           (when-not (contains? (:yin.k/module-stores task) store-of)
             (non-portable! :missing-module-store {engine/store-of-key store-of})))
         (when-not (and (if (v2-body? body)
                          (some? (ucf/profile-engine (:yin.k/contract body)))
                          (= ucf/contract-stamp (:yin.k/contract body)))
                        (= (some? header)
                           (if (v2-body? body)
                             (boolean (some #(contains? body %)
                                            [:yin.k/policy :yin.k/occurrence
                                             :yin.k/arbitration :yin.k/origin
                                             :yin.k/next-op-seq]))
                             (= 1 (:yin.k/version body))))
                        (or (nil? header)
                            (and (map? header) (set? (:yin.k/enrolled header))
                                 (every? (fn [[key value]] (= value (get body key)))
                                         (header-of header (:yin.k/kind body))))))
           (undecodable! {:yin.k/reason :recovery-header}))
         {:status :ok :body body
          :descriptors (set (map :dao.stream/descriptor
                                 (mapcat body-markers (task-bodies body))))})))))


(defn resume-task
  "Public entry for 14.1.2 lower."
  ([recv bytes attach!]
   (resume-task recv bytes attach! nil))
  ([recv bytes attach! opts]
   (resume-task* recv bytes attach! (dissoc opts ::install-child) false)))
