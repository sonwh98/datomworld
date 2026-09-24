(ns yin.vm.debruijn-linker
  "B6 (docs/design/yin.vm.debruijn.linker.md): the closed-image code
   linker, used by both the stack VM and the register VM for linking code
   over `dao.stream`. The code itself is stored in `dao.jing`; the linker
   only fetches it through a DaoJing handle -- a local store, a
   `dao.jing.dht/create-content-dht` handle, or a
   `dao.jing.remote/content-client` over DaoStream -- and verifies it.

   `fetch` is one format-neutral six-step function (section 4.2). A format
   record (section 5) supplies the identity hash, the validator, and the
   free-name scanner; `stack-format` and `register-format` are the two
   records this phase provides. Storage addresses (`jing/segment-key`) and
   VM identities (H, R) are separate preimages, so step 2 checks the
   address and step 3 checks the identity; neither subsumes the other.

   The handle, the index, the format record, and the receiver environment
   are explicit arguments. There is no global loader, registry, callback,
   or cache here, and every outcome -- success or refusal -- is a returned
   plain data map (section 4.3)."
  (:require [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yin.vm.debruijn-code :as debruijn-code]
            [yin.vm.debruijn-linearize :as linearize]
            [yin.vm.debruijn-register-code :as debruijn-register-code]
            [yin.vm.debruijn-register-compile :as register-compile]
            [yin.vm.debruijn-resolve :as resolve]
            [yin.vm.module :as module]))


;; =============================================================================
;; Refusal vocabulary (section 4.3)
;; =============================================================================

(def refusal-reasons
  "Every reason a linker refusal may carry."
  #{:absent :address-mismatch :hash-mismatch :descriptor-defect
    :unresolved-free :shadowed-free :pairing-mismatch})


(defn refused
  "A qualified refusal: `{:status :refused, :reason reason}` merged with
   the step's own evidence `data`."
  ([reason] (refused reason {}))
  ([reason data]
   (merge data {:status :refused, :reason reason})))


(defn refused?
  "True when `res` is a linker refusal."
  [res]
  (and (map? res) (= :refused (:status res))))


(defn ok?
  "True when `res` is a verified linker outcome."
  [res]
  (and (map? res) (= :ok (:status res))))


;; =============================================================================
;; Free-name scanners (sections 5.1, 5.2)
;; =============================================================================

(defn- distinct-names
  [names]
  (vec (distinct names)))


(defn stack-free-names
  "Every `:load-free` name in a canonical stack instruction vector, in pc
   order of first appearance."
  [instruction-vector]
  (distinct-names
    (keep (fn [t] (when (= :load-free (nth t 0)) (nth t 1)))
          instruction-vector)))


(defn register-free-names
  "Every `:load-free` name across all bodies of a register image map, in
   pc order of first appearance. A register `:load-free` is
   `[:load-free rd name]`."
  [register-image-map]
  (distinct-names
    (keep (fn [t] (when (= :load-free (nth t 0)) (nth t 2)))
          (:instructions register-image-map))))


;; =============================================================================
;; Format records (section 5)
;; =============================================================================

(def stack-format
  "The `:yin.debruijn.code` format record: stack images identified by H."
  {:format :yin.debruijn.code,
   :hash-fn debruijn-code/image-hash,
   :validate-fn debruijn-code/image-defect,
   :free-names-fn stack-free-names})


(def register-format
  "The `:yin.debruijn.register` format record: register images identified
   by R."
  {:format :yin.debruijn.register,
   :hash-fn debruijn-register-code/register-hash,
   :validate-fn debruijn-register-code/register-image-defect,
   :free-names-fn register-free-names})


;; =============================================================================
;; The H and R indexes (section 6)
;; =============================================================================

(defn address-attribute
  "The index attribute a format's identities are published under:
   `:yin.debruijn.code/address` or `:yin.debruijn.register/address`."
  [format]
  (keyword (name (:format format)) "address"))


(defn index-from-datoms
  "A plain identity -> address map read from index datoms
   `[identity attribute address]`, keeping only the attribute `format`
   publishes under. The index is composition data; an entry is a claim,
   not a proof (step 3 checks it)."
  [format datoms]
  (let [attr (address-attribute format)]
    (into {}
          (keep (fn [[e a v]] (when (= attr a) [e v])))
          datoms)))


;; =============================================================================
;; The receiver environment (step 5)
;; =============================================================================
;; The receiver binds a `:load-free` name in the order
;; `yin.vm.engine/resolve-var` uses: free env -> store -> primitives ->
;; module registry. A name the image publisher meant as a primitive or a
;; module binding must resolve there; a receiver whose free env or store
;; also binds it would bind it differently, so that is refused as
;; shadowed rather than executed (D11).

(defn- shadowed?
  [{:keys [free-env store]} sym]
  (or (contains? free-env sym) (contains? store sym)))


(defn- resolvable?
  [{:keys [primitives modules]} sym]
  (or (contains? primitives sym)
      (and (symbol? sym)
           (some? (namespace sym))
           (some? (module/resolve-module
                    modules
                    (symbol (str (namespace sym) "." (name sym))))))))


(defn free-name-defect
  "The first step-5 refusal for `names` against `receiver`
   (`{:free-env :store :primitives :modules}`, every key optional), or nil
   when every name is bound identically by the receiver."
  [receiver names]
  (some (fn [sym]
          (cond (shadowed? receiver sym)
                (refused :shadowed-free {:name sym})
                (not (resolvable? receiver sym))
                (refused :unresolved-free {:name sym})))
        names))


;; =============================================================================
;; fetch (section 4)
;; =============================================================================

(def ^:private missing
  "Opaque per-host not-found sentinel for `jing/get`, never a keyword: a
   keyword sentinel could collide with a genuinely stored payload."
  #?(:cljd (Object.)
     :clj (Object.)
     :cljs (js-obj)))


(defn- read-address
  "Step 2's read: the stored canonical bytes, `missing` when absent, or
   `missing` when the handle fails (a closed client, a malformed RPC
   envelope, a transport timeout). A failing store has no payload for this
   caller, so it fails closed as `:absent`, never as execution. The bytes
   are read raw through the handle's byte store, not through `jing/get`,
   so that step 2's own address check can name the mismatch."
  [handle address]
  (try (let [get-fn (:get-bytes-fn handle)]
         (if (fn? get-fn) (get-fn address missing) missing))
       (catch #?(:cljd Object :clj Throwable :cljs :default) _
         missing)))


(defn- decoded
  "The value of canonical bytes bs, or nil when they do not decode."
  [bs]
  (try (cbor/decode bs)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _
         nil)))


(defn- identity-of
  "Step 3's hash, or nil when `value` cannot be hashed under the format
   at all (a malformed payload hashes to no identity)."
  [format value]
  (try ((:hash-fn format) value)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _
         nil)))


(defn- validation-defect
  [format value]
  (try ((:validate-fn format) value)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _
         {:rule :validator-refused})))


(defn fetch
  "Fetches and verifies an image by its identity using handle, index, and
   format record. Used by stack and register VMs alike. Returns the
   verified image `{:status :ok ...}` or a qualified refusal.

   `handle` is any DaoJing content handle; `index` is a map or function
   from identity to Jing address; `format` is a format record; `identity`
   is H or R. `receiver` is the receiving VM's free-name environment
   `{:free-env :store :primitives :modules}`; the 4-arity form uses the
   empty receiver, so only a closed image (no `:load-free`) is accepted.

   The six steps run in strict order (section 4.2), with no
   format-specific branching:
     1. address <- (index identity)               :absent
     2. value   <- (jing/get handle address)      :absent, :address-mismatch
     3. identity = ((:hash-fn format) value)      :hash-mismatch
     4. ((:validate-fn format) value) is nil      :descriptor-defect
     5. every free name binds in receiver         :unresolved-free,
                                                  :shadowed-free
     6. the verified image"
  ([handle index format identity]
   (fetch handle index format identity {}))
  ([handle index format identity receiver]
   (let [address (index identity)]
     (if (nil? address)
       (refused :absent {:identity identity})
       (let [bs (if (jing/segment-address? address)
                  (read-address handle address)
                  missing)
             value (when-not (or (identical? missing bs)
                                 (not (jing/segment-bytes-match? address bs)))
                     (decoded bs))]
         (cond
           (identical? missing bs)
           (refused :absent {:address address})

           (nil? value)
           (refused :address-mismatch {:address address,
                                       :value (decoded bs)})

           :else
           (let [actual (identity-of format value)]
             (if (not= identity actual)
               (refused :hash-mismatch {:expected identity, :actual actual})
               (if-let [defect (validation-defect format value)]
                 (refused :descriptor-defect
                          {:identity identity, :defect defect})
                 (or (free-name-defect receiver
                                       ((:free-names-fn format) value))
                     {:status :ok,
                      :format (:format format),
                      :identity identity,
                      :address address,
                      :value value}))))))))))


;; =============================================================================
;; Publishing (the mint-side counterpart the tests and compositions use)
;; =============================================================================

(defn publish!
  "Store `image` in `handle` and return its index datom
   `[identity attribute address]`. The address is the store's own
   `segment-key`; the identity is the format's hash. The caller owns the
   index: nothing is recorded here."
  [handle format image]
  (let [address (jing/materialize! handle image)]
    [((:hash-fn format) image) (address-attribute format) address]))


;; =============================================================================
;; Same-root pairing (section 7)
;; =============================================================================

(defn pairing-datoms
  "The mint-time pairing datoms recorded beside a named root."
  [root H R]
  [[root :yin.debruijn.code/hash H]
   [root :yin.debruijn.register/hash R]])


(defn root-pairing
  "`{:root root :H H :R R}` read from pairing datoms, or nil when either
   half is missing."
  [root datoms]
  (let [value-of (fn [attr]
                   (some (fn [[e a v]] (when (and (= root e) (= attr a)) v))
                         datoms))
        H (value-of :yin.debruijn.code/hash)
        R (value-of :yin.debruijn.register/hash)]
    (when (and H R) {:root root, :H H, :R R})))


(defn- relowered-hashes
  [source-datoms]
  (try
    (let [resolved (resolve/resolve source-datoms)]
      {:H (debruijn-code/image-hash
            (:image (linearize/lower-stack resolved))),
       :R (debruijn-register-code/register-hash
            (:image (register-compile/lower-register resolved)))})
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      {:H nil, :R nil})))


(defn verify-same-root-pairing
  "Re-lowers the named `source-datoms` locally (`resolve`, then
   `lower-stack` for H and `lower-register` for R) and confirms both
   recomputed identities equal the claimed pair. Returns `{:status :ok
   :root root :H H :R R}` or a `:pairing-mismatch` refusal carrying the
   expected and actual pairs."
  [root H R source-datoms]
  (let [actual (relowered-hashes source-datoms)]
    (if (and (= H (:H actual)) (= R (:R actual)))
      {:status :ok, :root root, :H H, :R R}
      (refused :pairing-mismatch
               {:root root, :expected {:H H, :R R}, :actual actual}))))


(defn- fallback-fetch
  [handle h-index pairing receiver trust]
  (let [res (fetch handle h-index stack-format (:H pairing) receiver)]
    (if (ok? res)
      (assoc res
             :trust trust
             :fallback {:root (:root pairing), :from (:R pairing)})
      res)))


(defn trusted-fallback
  "R -> H fallback on composition trust (section 7, path 1): reads the
   root's pairing and fetches its stack image by H. The outcome names its
   trust as `:trust :composition`. A root without a recorded pairing is
   `:absent`."
  [handle h-index root pairing-datoms receiver]
  (if-let [pairing (root-pairing root pairing-datoms)]
    (fallback-fetch handle h-index pairing receiver :composition)
    (refused :absent {:root root})))


(defn verifying-fallback
  "R -> H fallback that verifies the pairing first (section 7, path 2):
   re-lowers the root's named `source-datoms` and refuses with
   `:pairing-mismatch` unless both recomputed identities match, then
   fetches the stack image by H. The outcome names its trust as
   `:trust :verified`."
  [handle h-index root pairing-datoms source-datoms receiver]
  (if-let [{:keys [H R], :as pairing} (root-pairing root pairing-datoms)]
    (let [verdict (verify-same-root-pairing root H R source-datoms)]
      (if (refused? verdict)
        verdict
        (fallback-fetch handle h-index pairing receiver :verified)))
    (refused :absent {:root root})))
