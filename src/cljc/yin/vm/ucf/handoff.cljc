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
            [yin.vm.completion :as completion]
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
   frozen stage-1 wire the lower still emits, and version 1, the custody
   amendment (section 14.3) whose canonical bytes are dao.jing.cbor's.
   Which of the two a body may carry is decided by the codec that
   accepted its bytes -- the version-aware reader's gate, before the tag
   grammar and before any address or restoration check -- and a body of
   any other version, absent or not of the integer kind, is refused with
   :yin.k/profile-mismatch."
  #{0 1})


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


(declare value-encoder)


(defn- canonical-order
  "The ordering function of a version-1 export: `(order keyfn coll)`
   sorts `coll` by the canonical bytes of the scratch encoding of each
   `(keyfn x)`.  The scratch encoder owns a found map of its own, so it
   mints nothing the export keeps; it runs under `:ordering`, so every
   cell its keys meet is seeded by the identity the cell table keys by,
   never by a number a comparison could shift, and it orders its own
   nested maps the same way.  Ties keep the walk order given: wait
   order and alias identity are never reordered."
  [vm serve!]
  (let [self (volatile! nil)
        order (fn [keyfn coll]
                (sort-by (comp @self keyfn) jing.cbor/encoded-compare coll))
        encode (value-encoder vm serve!
                              (atom (assoc (new-found)
                                           :order order
                                           :v1 true
                                           :ordering true)))]
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
     :yin.k/pending (protected! found entry
                                (lift-pending! vm serve! found encode
                                               entry))}))


;; =============================================================================
;; The install children: a whole child travels, or nothing does
;; =============================================================================

(declare export-task validate-body resume-task resume-task*)


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
   resource id."
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
             v1? (boolean (or (some? header) (::v1 opts)))
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
             walked (completion/complete
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
               _ (when (and v1? (not child?) (not= :halted kind)
                            (let [n (:yin.k/next-op-seq header)]
                              (and (jing.cbor/numeric? n)
                                   (jing.cbor/num= n checkpoint/max-exact))))
                   (non-portable! :op-seq-exhausted
                                  {:yin.k/next-op-seq
                                   (:yin.k/next-op-seq header)}))
               _ (when (and active
                            (not (some #(= :explicit-park %)
                                       (safepoint-kinds-at
                                         vm (:segment active-rec)
                                         (:pc active-rec)))))
                   (non-portable! :reason-mismatch
                                  {:yin.k/pc (:pc active-rec)}))
               found (atom (cond-> (assoc (new-found)
                                          :v1 v1?
                                          :full-census (::recovery opts)
                                          :recovery-cell-ids (:yin.k/recovery-cell-ids vm)
                                          :enrolled (::enrolled opts))
                             v1? (assoc :order
                                        (canonical-order vm serve!))))
               encode (value-encoder vm serve! found)
               frames (mapv (partial lift-frame! vm serve! found encode)
                            (:wait-set vm))
               parked (into {}
                            (map (fn [[pid rec]]
                                   (safepoint-kinds-at vm (:segment rec)
                                                       (:pc rec))
                                   [pid (encode-registers found encode
                                                          vm rec)]))
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
                             :yin.k/version (if v1? 1 0)
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
  (let [version (:yin.k/version body)]
    (when-not (contains? handoff-version version)
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
              (jing.cbor/numeric? x) x
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
  (if (= 1 (:yin.k/version child-body))
    (jing.cbor/encode child-body)
    (cbor/encode child-body)))


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
  {:stream #{0} :jing #{1}})


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
  [codec body opts]
  (let [supported (get codec-versions codec)
        v (get body :yin.k/version)]
    (when-not (and (jing.cbor/numeric? v)
                   (= :integer (jing.cbor/numeric-kind v))
                   (contains? supported v))
      (refuse! :yin.k/profile-mismatch
               {:yin.k/version v :yin.k/supported supported}))
    (when (and (:exclusive opts) (not= 1 v))
      (refuse! :yin.k/profile-mismatch
               {:yin.k/version v
                :yin.k/supported #{1}
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
             _ (version-gate! codec body opts)
             _ (when (and (= 1 (:yin.k/version body))
                          (not install-child?))
                 (custody-inspect! bytes opts))
             _ (validate-body body)
             _ (when-not (= ucf/contract-stamp
                            (:yin.k/contract body))
                 (refuse! :yin.k/profile-mismatch
                          {:yin.k/contract (:yin.k/contract body)}))
             kind (:yin.k/kind body)
             version-one? (= 1 (:yin.k/version body))
             custody-state (when (and version-one? (not install-child?) (not fenced?)
                                      (not= :halted kind))
                             (accept-grant! body opts))
             cells (:yin.k/cells body)
             pendings (mapv :yin.k/pending
                            (or (:yin.k/frames body) []))
             recv' (reduce (fn [r [_a v]] (module/attach-module r v))
                           (if (or version-one? fenced?) (isolated-receiver recv) recv)
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
             installs (resume-installs recv' body attach! opts fenced?)
             registers (mapv (fn [frame]
                               (decode-registers decode aliases
                                                 (:yin.k/registers frame)))
                             (or (:yin.k/frames body) []))
             raw-entries (mapv (fn [pending ctx]
                                 (cond-> (lower-frame! decode streams cells cell-keys
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
                                                          (when fenced?
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
         (version-gate! codec body nil)
         (when (= 1 (:yin.k/version body))
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
         (when-not (and (= ucf/contract-stamp (:yin.k/contract body))
                        (= (some? header) (= 1 (:yin.k/version body)))
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
