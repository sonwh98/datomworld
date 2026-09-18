(ns dao.stream
  "DaoStream v2: passive substrate for IO in datom.world.

   Seven operations:
     Five handle operations (protocols implemented per declared surface):
       descriptor (universal reachability and identity projection)
       cursor     (reader surface)
       next       (reader surface)
       append!    (writer surface)
       close!     (closable surface)
     Two transport entry functions (per-transport, host-composed):
       create!    (creates a new logical stream from a creation specification)
       attach!    (attaches to an existing stream via portable descriptor)

   Result Convention:
     Every operation returns an open outcome map keyed by :dao.stream/outcome.
     All qualified keywords live under :dao.stream/…
     Each operation has an exhaustive, closed outcome set."
  (:refer-clojure :exclude [next]))


;; =============================================================================
;; The Seven Operations and Standard Keywords
;; =============================================================================

(def operations
  "The seven DaoStream v2 operations."
  #{:create! :attach! :descriptor :cursor :next :append! :close!})


(def surfaces
  "The public operational surfaces a handle may declare."
  #{:reader :writer :closable})


;; Anchors
(def anchor-oldest
  "Earliest retained position on the stream."
  :dao.stream/oldest)


(def anchor-newest
  "Positioned after the newest appended value (observes next arrival)."
  :dao.stream/newest)


(def standard-anchors
  "Contract-defined anchor keywords."
  #{anchor-oldest anchor-newest})


;; Closed outcome sets per operation (contract exhaustive tables)
(def outcomes-create
  #{:dao.stream/ok
    :dao.stream/invalid-spec
    :dao.stream/not-found
    :dao.stream/transport-error})


(def outcomes-attach
  #{:dao.stream/ok
    :dao.stream/invalid-descriptor
    :dao.stream/not-found
    :dao.stream/transport-error})


(def outcomes-descriptor
  #{:dao.stream/ok})


(def outcomes-cursor
  #{:dao.stream/ok
    :dao.stream/invalid-anchor
    :dao.stream/closed
    :dao.stream/transport-error})


(def outcomes-next
  #{:dao.stream/ok
    :dao.stream/blocked
    :dao.stream/end
    :dao.stream/gap
    :dao.stream/cursor-mismatch
    :dao.stream/invalid-cursor
    :dao.stream/transport-error})


(def outcomes-append
  #{:dao.stream/ok
    :dao.stream/full
    :dao.stream/invalid-value
    :dao.stream/closed
    :dao.stream/transport-error})


(def outcomes-close
  #{:dao.stream/ok})


(def operation-outcomes
  "Map of operation keyword to its closed outcome set."
  {:create! outcomes-create
   :attach! outcomes-attach
   :descriptor outcomes-descriptor
   :cursor outcomes-cursor
   :next outcomes-next
   :append! outcomes-append
   :close! outcomes-close})


(def outcome-required-keys
  "Map from [operation outcome] to required keys in the result map."
  {[:create! :dao.stream/ok] #{:dao.stream/handle}
   [:attach! :dao.stream/ok] #{:dao.stream/handle}
   [:descriptor :dao.stream/ok] #{:dao.stream/descriptor :dao.stream/identity}
   [:cursor :dao.stream/ok] #{:dao.stream/cursor}
   [:next :dao.stream/ok] #{:dao.stream/value :dao.stream/cursor}
   [:next :dao.stream/gap] #{:dao.stream/cursor}})


;; =============================================================================
;; Canonical Operation Resolution Helper
;; =============================================================================

(defn canonical-op
  "Normalizes op to canonical keyword (:create!, :attach!, :descriptor,
   :cursor, :next, :append!, :close!). Supports aliases without '!'."
  [op]
  (case op
    (:create :create!) :create!
    (:attach :attach!) :attach!
    (:descriptor) :descriptor
    (:cursor) :cursor
    (:next) :next
    (:append :append!) :append!
    (:close :close!) :close!
    nil))


;; =============================================================================
;; Handle Protocols (Five Handle Operations)
;; =============================================================================

(defprotocol IDaoStreamDescriptor
  "Universal reachability and identity projection. Belongs to no surface:
   implemented by every handle regardless of declared reader/writer/closable surfaces."

  (descriptor
    [handle]
    "Returns descriptor outcome map:
     {:dao.stream/outcome :dao.stream/ok
      :dao.stream/descriptor <portable-descriptor>
      :dao.stream/identity <logical-stream-identity>}"))


(defprotocol IDaoStreamReader
  "Reader surface: positioned, non-destructive observation via immutable cursors."

  (cursor
    [handle anchor]
    "Mint an immutable cursor from anchor (:dao.stream/oldest, :dao.stream/newest,
     or transport-specific anchor).
     Returns outcome map with :dao.stream/cursor on :dao.stream/ok.")

  (next
    [handle cursor]
    "Read the value at cursor position. Total and non-blocking.
     Returns read outcome map."))


(defprotocol IDaoStreamWriter
  "Writer surface: append values to the sequence this handle's writer surface is on."

  (append!
    [handle val]
    "Append val to the sequence. Total and non-blocking.
     Returns write outcome map."))


(defprotocol IDaoStreamClosable
  "Closable surface: close what this handle is on (logical stream or attachment).
   Idempotent."

  (close!
    [handle]
    "Closes what this handle is on. Irrevocable.
     Returns outcome map {:dao.stream/outcome :dao.stream/ok}."))


;; =============================================================================
;; Surface Inspection & Gating
;; =============================================================================

(defn reader?
  "True if handle implements the reader surface (IDaoStreamReader)."
  [handle]
  (satisfies? IDaoStreamReader handle))


(defn writer?
  "True if handle implements the writer surface (IDaoStreamWriter)."
  [handle]
  (satisfies? IDaoStreamWriter handle))


(defn closable?
  "True if handle implements the closable surface (IDaoStreamClosable)."
  [handle]
  (satisfies? IDaoStreamClosable handle))


(defn descriptor?
  "True if handle implements IDaoStreamDescriptor."
  [handle]
  (satisfies? IDaoStreamDescriptor handle))


(defn declared-surfaces
  "Returns set of public surfaces implemented by handle:
   subset of #{:reader :writer :closable}."
  [handle]
  (cond-> #{}
    (reader? handle) (conj :reader)
    (writer? handle) (conj :writer)
    (closable? handle) (conj :closable)))


;; =============================================================================
;; Result Convention & Envelope Validation
;; =============================================================================

(defn qualified-keyword?*
  "True if x is a qualified keyword. Portable across CLJ, CLJS, CLJD."
  [x]
  (boolean (and (keyword? x) (namespace x))))


(defn outcome-map?
  "True if x is an open outcome map with a qualified keyword under :dao.stream/outcome."
  [x]
  (and (map? x)
       (qualified-keyword?* (:dao.stream/outcome x))))


(defn validate-outcome
  "Validates an operation outcome against contract requirements.
   Returns nil if valid, or a failure map describing the defect if invalid."
  [op result]
  (if-let [canon-op (canonical-op op)]
    (cond
      (not (outcome-map? result))
      {:valid? false :error :invalid-outcome-map :operation canon-op :result result}

      :else
      (let [outcome (:dao.stream/outcome result)
            allowed (get operation-outcomes canon-op)]
        (cond
          (not (contains? allowed outcome))
          {:valid? false
           :error :unauthorized-outcome
           :operation canon-op
           :outcome outcome
           :allowed allowed}

          :else
          (let [req-keys (get outcome-required-keys [canon-op outcome] #{})
                missing (into #{} (remove #(contains? result %) req-keys))]
            (if (seq missing)
              {:valid? false
               :error :missing-required-keys
               :operation canon-op
               :outcome outcome
               :missing missing}
              nil)))))
    {:valid? false :error :unknown-operation :operation op}))


(defn valid-outcome?
  "True if result is a valid outcome map for op according to the contract."
  [op result]
  (nil? (validate-outcome op result)))


(defn valid-envelope?
  "Generic envelope validation: envelope must be a map with a qualified keyword
   under :dao.stream/type. Everything else is transport-owned."
  [envelope]
  (and (map? envelope)
       (qualified-keyword?* (:dao.stream/type envelope))))


(defn valid-creation-spec?
  "True if spec is a generically valid creation specification envelope:
   must be a map with a qualified keyword under :dao.stream/type."
  [spec]
  (valid-envelope? spec))


(defn valid-descriptor?
  "True if descriptor is a generically valid portable descriptor envelope:
   must be a valid envelope (:dao.stream/type present and qualified)
   and carry :dao.stream/identity."
  [descriptor]
  (and (valid-envelope? descriptor)
       (contains? descriptor :dao.stream/identity)))


(defn descriptor-identity-consistent?
  "True if a descriptor operation result is {:dao.stream/outcome :dao.stream/ok ...}
   where both :dao.stream/descriptor and :dao.stream/identity are present,
   descriptor is a valid descriptor envelope, and the sibling :dao.stream/identity
   projection structurally equals the identity inside the descriptor envelope."
  [result]
  (and (map? result)
       (= :dao.stream/ok (:dao.stream/outcome result))
       (contains? result :dao.stream/descriptor)
       (contains? result :dao.stream/identity)
       (let [sibling-id (:dao.stream/identity result)
             desc (:dao.stream/descriptor result)]
         (and (some? sibling-id)
              (valid-descriptor? desc)
              (= sibling-id (:dao.stream/identity desc))))))


;; =============================================================================
;; Open Result Map Constructors
;; =============================================================================

(defn ok-result
  "Convenience constructor for an open :dao.stream/ok outcome map."
  ([]
   {:dao.stream/outcome :dao.stream/ok})
  ([extra-map]
   (assoc extra-map :dao.stream/outcome :dao.stream/ok)))


(defn outcome-result
  "Convenience constructor for an open outcome map."
  ([outcome]
   {:dao.stream/outcome outcome})
  ([outcome extra-map]
   (assoc extra-map :dao.stream/outcome outcome)))


;; =============================================================================
;; Transport Entry Functions & Host Dispatch
;; =============================================================================
;;
;; Two transport entry functions: create! and attach!
;; These take no handle because none exists yet. These are per-transport functions,
;; host-composed, taking whatever composition state the transport needs.
;;
;; Putting create! or attach! on a protocol is how the retired registry grows back;
;; DaoStream v2 ships no dispatch machinery (no multimethod, no registry,
;; no load-time side effect).
;;
;; Dynamic dispatch on :dao.stream/type is performed by the host using an ordinary
;; host-owned lookup table:
;;
;;   {:dao.stream/ringbuffer {:dao.stream/create ringbuffer/create!
;;                            :dao.stream/attach ringbuffer/attach!}
;;    :dao.stream/ws         {:dao.stream/attach ws/attach!}}
;;
;; The helper functions below provide transparent, purely functional host dispatch.

(defn host-dispatch-create!
  "Host dispatch helper: looks up :dao.stream/create in dispatch-table
   for (:dao.stream/type creation-spec).
   Returns :dao.stream/invalid-spec if spec is not a valid envelope.
   Returns :dao.stream/not-found if transport or :dao.stream/create is absent.
   Otherwise invokes the transport's create function."
  [dispatch-table creation-spec]
  (if-not (valid-creation-spec? creation-spec)
    {:dao.stream/outcome :dao.stream/invalid-spec}
    (if-let [create-fn (get-in dispatch-table [(:dao.stream/type creation-spec) :dao.stream/create])]
      (create-fn creation-spec)
      {:dao.stream/outcome :dao.stream/not-found})))


(defn host-dispatch-attach!
  "Host dispatch helper: looks up :dao.stream/attach in dispatch-table
   for (:dao.stream/type descriptor).
   Returns :dao.stream/invalid-descriptor if descriptor is not a valid descriptor envelope.
   Returns :dao.stream/not-found if transport or :dao.stream/attach is absent.
   Otherwise invokes the transport's attach function."
  [dispatch-table descriptor]
  (if-not (valid-descriptor? descriptor)
    {:dao.stream/outcome :dao.stream/invalid-descriptor}
    (if-let [attach-fn (get-in dispatch-table [(:dao.stream/type descriptor) :dao.stream/attach])]
      (attach-fn descriptor)
      {:dao.stream/outcome :dao.stream/not-found})))
