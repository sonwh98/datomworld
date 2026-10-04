(ns yin.vm.ucf.checkpoint
  "The pure checkpoint inspector of M-next C slice C4: the custody
   grammar of a version-1 handoff body (UCF 7.2.1, 7.4.3 and 7.7.8),
   checked on canonical bytes, and the operation baseline the body
   carries.

   `inspect` takes a body's content address and its canonical bytes;
   `inspect-body` takes a body `dao.jing.cbor/decode` already answered.
   The checks run in this order and the first to fail decides: the
   address, canonical decoding, the tag, the version gate, the kind,
   the custody header in the root or the child role, and the operation
   ids on `:put`, `:ffi-request` and `:link-request`, recursing into
   every install child with the root's occurrence, origin and counter.
   An integer is checked on the canonical codec's integer kind, never
   on numeric equality, so an integral float never passes.

   Every `:install` pending must name an entry of `:yin.k/installs`
   (7.4.3), because the baseline includes the children's ids.  Not
   checked here: code hashes, cells, registers, safepoints, install
   phases and responses.  Those are restoration validity (stage D),
   whose version-1 `validate-body` runs this namespace first.

   Answers the operation baseline

     {:yin.k/kind        k
      :yin.k/occurrence  O          ; absent on a :halted root
      :yin.k/origin      {...}      ; absent on a first park
      :yin.k/arbitration {...}      ; absent on a :halted root
      :yin.k/next-op-seq n          ; absent on a :halted root
      :yin.k/ops         {op-id encoded-intent}}

   where `encoded-intent` is the BLAKE3 hex digest of the canonical
   bytes of the intent `[:yin.k/append target-identity payload]`
   (7.7.8), with the payload exactly as the body carries it (7.5
   encoded), so two baselines compare with host `=` on every host.
   That carried intent serves variant comparison (7.7.8, *Snapshot
   variants*) and id membership only.  It is never a dedup comparand:
   admission compares the intent a consumer computes from an
   envelope's decoded `:yin.k/value`, and only against another such
   intent.  Every refusal is a data outcome carrying `:yin.k/status`;
   nothing here throws on any input."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]))


(def supported-versions
  "The body versions this inspector speaks: version 1 alone, because
   version 0 is fork only and names no custody (7.2.1)."
  #{1})


(def ^:private known-versions
  "Every body version the UCF publishes, for the mixed-tree rule: a
   child whose version is published but differs from its holder's is
   undecodable, not a profile mismatch."
  #{0 1})


(def max-exact
  "2^52-1, the largest sequence, counter or epoch (7.7.8)."
  4503599627370495)


(def ^:private header-keys
  [:yin.k/policy :yin.k/occurrence :yin.k/arbitration :yin.k/origin
   :yin.k/next-op-seq])


(def ^:private reasons
  #{:next :ffi :put :ffi-request :link-request :link-response :install})


;; =============================================================================
;; Refusals: thrown inside, answered as data at the two entry points
;; =============================================================================

(defn- refuse!
  [status data]
  (throw (ex-info (str "Checkpoint refused: " (name status))
                  (assoc data :yin.k/status status))))


(defn- undecodable!
  [path data]
  (refuse! :yin.k/undecodable (assoc data :yin.k/path path)))


(defn- answer
  "Run `f`, answering its value or the refusal it threw.  A throw
   without the refusal vocabulary is a defect and propagates."
  [f]
  (try (f)
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (let [d (ex-data e)]
           (if (and (map? d) (contains? d :yin.k/status)) d (throw e))))))


;; =============================================================================
;; Exact integers and canonical identity
;; =============================================================================

(defn- integer-kind?
  [x]
  (and (cbor/numeric? x) (= :integer (cbor/numeric-kind x))))


(defn- exact?
  "A nonnegative exact integer no greater than 2^52-1, by integer kind."
  [x]
  (and (integer-kind? x)
       (not (neg? (cbor/num-compare x 0)))
       (not (pos? (cbor/num-compare x max-exact)))))


(defn- below?
  [a b]
  (neg? (cbor/num-compare a b)))


;; =============================================================================
;; Version gate (7.2.1): before every other rule
;; =============================================================================

(defn- check-version!
  "The root's gate: an absent version, a version of another kind, or a
   version this inspector does not speak is a profile mismatch."
  [body]
  (let [v (get body :yin.k/version)]
    (when-not (and (integer-kind? v) (contains? supported-versions v))
      (refuse! :yin.k/profile-mismatch
               {:yin.k/version v :yin.k/supported supported-versions}))
    v))


(defn- check-child-version!
  "A child's gate: it carries the version of the body that holds it.
   A published version that differs is a mixed tree, undecodable; any
   other version fails the gate itself."
  [body version path]
  (let [v (get body :yin.k/version)
        integral (integer-kind? v)]
    (cond
      (and integral (= version v)) nil
      (and integral (contains? known-versions v))
      (undecodable! (conj path :yin.k/version)
                    {:yin.k/kind :mixed-version :yin.k/version v})
      :else
      (refuse! :yin.k/profile-mismatch
               {:yin.k/version v :yin.k/supported supported-versions
                :yin.k/path (conj path :yin.k/version)}))))


;; =============================================================================
;; Custody header (7.2.1): context-sensitive, root or child
;; =============================================================================

(defn- check-origin!
  [body path]
  (let [origin (get body :yin.k/origin)
        p (conj path :yin.k/origin)]
    (when-not (and (map? origin)
                   (some? (get origin :yin.k/occurrence))
                   (some? (get origin :dao.lease/lease))
                   (some? (get origin :yin.k/emitter)))
      (undecodable! p {:yin.k/kind :malformed-origin}))
    (when (cbor/content= (get origin :yin.k/occurrence)
                         (get body :yin.k/occurrence))
      (undecodable! (conj p :yin.k/occurrence)
                    {:yin.k/kind :origin-is-self}))))


(defn- check-root-header!
  "The header of a root body: required on `:blocked` and `:parked`,
   only the origin on `:halted`."
  [body kind path]
  (if (= :halted kind)
    (do (doseq [k header-keys
                :when (and (not= :yin.k/origin k) (contains? body k))]
          (undecodable! (conj path k) {:yin.k/kind :halted-header}))
        (when-not (contains? body :yin.k/origin)
          (undecodable! (conj path :yin.k/origin)
                        {:yin.k/kind :missing-header}))
        (check-origin! body path))
    (do (doseq [k [:yin.k/policy :yin.k/occurrence :yin.k/arbitration
                   :yin.k/next-op-seq]
                :when (not (contains? body k))]
          (undecodable! (conj path k) {:yin.k/kind :missing-header}))
        (when-not (= :yin.k/exclusive (get body :yin.k/policy))
          (undecodable! (conj path :yin.k/policy)
                        {:yin.k/policy (get body :yin.k/policy)}))
        (when (nil? (get body :yin.k/occurrence))
          (undecodable! (conj path :yin.k/occurrence)
                        {:yin.k/kind :nil-occurrence}))
        (let [arb (get body :yin.k/arbitration)]
          (when-not (and (map? arb)
                         (some? (get arb :dao.stream/identity))
                         (map? (get arb :dao.stream/descriptor)))
            (undecodable! (conj path :yin.k/arbitration)
                          {:yin.k/kind :malformed-arbitration})))
        (let [n (get body :yin.k/next-op-seq)]
          (when-not (exact? n)
            (undecodable! (conj path :yin.k/next-op-seq)
                          {:yin.k/kind :inexact}))
          (when (and (not (contains? body :yin.k/origin))
                     (not (cbor/num= 0 n)))
            (undecodable! (conj path :yin.k/next-op-seq)
                          {:yin.k/kind :first-export-counter})))
        (when (contains? body :yin.k/origin)
          (check-origin! body path)))))


(defn- check-child-header!
  "An install child of any kind, halted included, carries no header
   key: it is part of its root's task, not a custody subject."
  [body path]
  (doseq [k header-keys
          :when (contains? body k)]
    (undecodable! (conj path k) {:yin.k/kind :child-header})))


;; =============================================================================
;; Operation ids (7.4.3, 7.7.8): checked in the root's context
;; =============================================================================

(defn- marker-identity
  [pending k path]
  (let [m (get pending k)]
    (when-not (and (map? m) (= :yin.k/stream (get m :yin.k/tag))
                   (some? (get m :dao.stream/identity)))
      (undecodable! (conj path k) {:yin.k/kind :malformed-marker}))
    (get m :dao.stream/identity)))


(defn- intent
  "The canonical intent the retained write of `pending` will carry:
   the target's identity and the payload exactly as carried."
  [pending path]
  (let [[target payload]
        (case (get pending :yin.k/reason)
          :put [:yin.k/stream :yin.k/value]
          :ffi-request [:yin.k/request :yin.k/request-envelope]
          :link-request [:yin.k/request :yin.k/envelope])]
    (when-not (contains? pending payload)
      (undecodable! (conj path payload) {:yin.k/kind :missing-payload}))
    [:yin.k/append (marker-identity pending target path)
     (get pending payload)]))


(defn- check-op-id!
  "One carried id against the root context `ctx`: its shape, an origin
   present, never the body's own occurrence, below the root counter."
  [ctx op-id path]
  (let [p (conj path :yin.k/op-id)]
    (when-not (and (map? op-id) (= 2 (count op-id))
                   (some? (get op-id :yin.k/occurrence))
                   (contains? op-id :yin.k/seq))
      (undecodable! p {:yin.k/kind :malformed-op-id}))
    (when-not (exact? (get op-id :yin.k/seq))
      (undecodable! (conj p :yin.k/seq) {:yin.k/kind :inexact}))
    (when-not (contains? ctx :yin.k/origin)
      (undecodable! p {:yin.k/kind :op-id-without-origin}))
    (when (cbor/content= (get op-id :yin.k/occurrence)
                         (get ctx :yin.k/occurrence))
      (undecodable! p {:yin.k/kind :op-id-own-occurrence}))
    ;; a :halted root has no counter, so no id can be below it
    (when-not (and (contains? ctx :yin.k/next-op-seq)
                   (below? (get op-id :yin.k/seq)
                           (get ctx :yin.k/next-op-seq)))
      (undecodable! (conj p :yin.k/seq) {:yin.k/kind :op-seq-range}))))


(defn- pending-ops
  "The `[path op-id intent]` of every id the frames of `body` carry."
  [ctx body path]
  (let [frames (get body :yin.k/frames [])]
    (when-not (vector? frames)
      (undecodable! (conj path :yin.k/frames) {:yin.k/kind :frames}))
    (into []
          (keep-indexed
            (fn [i frame]
              (let [p (conj path :yin.k/frames i :yin.k/pending)
                    pending (when (map? frame) (get frame :yin.k/pending))
                    reason (when (map? pending) (get pending :yin.k/reason))]
                (when-not (contains? reasons reason)
                  (undecodable! p {:yin.k/reason reason}))
                ;; a missing entry would silently drop a child's ids
                (when (and (= :install reason)
                           (not (contains? (get body :yin.k/installs {})
                                           (get pending :yin.k/name))))
                  (undecodable! p {:yin.k/kind :incomplete-install
                                   :yin.k/name (get pending :yin.k/name)}))
                (when (contains? pending :yin.k/op-id)
                  (when-not (contains? #{:put :ffi-request :link-request}
                                       reason)
                    (undecodable! (conj p :yin.k/op-id)
                                  {:yin.k/kind :op-id-on-variant
                                   :yin.k/reason reason}))
                  (let [op-id (get pending :yin.k/op-id)]
                    (check-op-id! ctx op-id p)
                    [p op-id (intent pending p)])))))
          frames)))


;; =============================================================================
;; The tree: the root, then every install child in the root's context
;; =============================================================================

(defn- check-kind!
  [body path]
  (let [kind (get body :yin.k/kind)]
    (when-not (contains? #{:blocked :parked :halted} kind)
      (undecodable! (conj path :yin.k/kind) {:yin.k/kind kind}))
    kind))


(defn- tree-ops
  "Every carried id of the task rooted at `body`, children included,
   depth first in install-name order so the first duplicate found is
   the same on every host."
  [ctx version body path]
  (let [installs (get body :yin.k/installs {})]
    (when-not (map? installs)
      (undecodable! (conj path :yin.k/installs) {:yin.k/kind :installs}))
    (into (pending-ops ctx body path)
          (mapcat
            (fn [m]
              (let [p (conj path :yin.k/installs m :yin.k/child)
                    child (get-in installs [m :yin.k/child])]
                (when-not (and (map? child)
                               (true? (get child :yin.k/handoff)))
                  (undecodable! p {:yin.k/kind :body}))
                (check-child-version! child version p)
                (check-kind! child p)
                (check-child-header! child p)
                (tree-ops ctx version child p))))
          (sort cbor/encoded-compare (keys installs)))))


(defn- baseline
  [body kind ops]
  (let [ops (reduce (fn [acc [path op-id in]]
                      (let [k (cbor/content-key op-id)]
                        (when (contains? (:seen acc) k)
                          (undecodable! (conj path :yin.k/op-id)
                                        {:yin.k/kind :duplicate-op-id}))
                        (-> acc
                            (update :seen conj k)
                            (assoc-in [:ops op-id]
                                      (jing/digest-bytes :blake3
                                                         (cbor/encode in))))))
                    {:seen #{} :ops {}}
                    ops)]
    (merge (select-keys body [:yin.k/occurrence :yin.k/origin
                              :yin.k/arbitration :yin.k/next-op-seq])
           {:yin.k/kind kind :yin.k/ops (:ops ops)})))


(defn inspect-body
  "The operation baseline of a decoded version-1 root `body`, or the
   first refusal: `:yin.k/undecodable` naming its `:yin.k/path`, or
   `:yin.k/profile-mismatch` carrying the version as found and the
   supported set.  `body` must come from `dao.jing.cbor/decode`, which
   keeps the integer kind the version gate checks."
  [body]
  (answer
    (fn []
      (when-not (and (map? body) (true? (get body :yin.k/handoff)))
        (undecodable! [] {:yin.k/kind :body}))
      (let [version (check-version! body)
            kind (check-kind! body [])]
        (check-root-header! body kind [])
        (baseline body kind
                  (tree-ops (select-keys body [:yin.k/occurrence
                                               :yin.k/origin
                                               :yin.k/next-op-seq])
                            version body []))))))


(defn- computed-address
  "The segment address of `bytes` under the algorithm `address` names,
   or BLAKE3 when it names none; nil when `bytes` are not bytes."
  [address bytes]
  (when (cbor/byte-payload? bytes)
    (let [algo (or (:algorithm (jing/parse-segment-address address))
                   :blake3)]
      (keyword "segment" (str (get-in jing/registry [algo :address-id]) "-"
                              (jing/digest-bytes algo bytes))))))


(defn inspect
  "The operation baseline of the root body whose canonical bytes are
   `bytes` under the segment address `address` (`:segment/<algo>-<hex>`,
   verified under the algorithm it names), or the first refusal:
   `:yin.k/hash-mismatch` carrying the claimed `:yin.k/address` and the
   `:yin.k/computed` one when the bytes do not match it or it is no
   segment address, `:yin.k/undecodable` naming the codec's refusal
   when the bytes are not one canonical payload, then every refusal of
   `inspect-body`."
  [address bytes]
  (answer
    (fn []
      (when-not (and (cbor/byte-payload? bytes)
                     (jing/segment-bytes-match? address bytes))
        (refuse! :yin.k/hash-mismatch
                 {:yin.k/address address
                  :yin.k/computed (computed-address address bytes)}))
      (let [body (try (cbor/decode bytes)
                      (catch #?(:cljd Object :clj Throwable :cljs :default) e
                        (undecodable! [] {:yin.k/kind :bytes
                                          :yin.k/refusal (cbor/refusal e)})))]
        (inspect-body body)))))
