(ns yang.python.antlr.siteset
  "C4 slice I7 (docs/design/yang.antlr.md 8.5.6, ruling 14): published site
   sets for imported modules.

   Site marks never enter canonical rows, so a published module tree
   carries none; they live in the frontend-metadata side table. A module's
   site set is that side table's `:yang/site` marks for the module tree,
   sorted (`yang.safepoint/site-set`) and content-addressed. A separate
   publication record, keyed by the module's manifest address, pins the
   association: the exact code root (the manifest's `:yin.module/tree`),
   the frontend revision, the transformation profile and the set's
   address. The module manifest keeps its schema: a manifest address names
   code, and a record names a manifest.

   Discovery. The publisher signs a publication entry per module,
   `{:yang.site/manifest m :yang.site/record record-address ...}`, and
   indexes it as the datoms `[e :yang.site/envelope env]` and `[e
   :yang.site/proof proof]` beside its name envelopes (`signed-entry`).
   A reader `discover`s a manifest's record addresses from the index
   datoms it loaded, under the principals it declares; only a declared
   principal's signature makes an entry count. The record and the set are
   fetched by address and verified against it.

   A consumer under a profile requiring hooks `admit`s an import: it
   verifies every pin against the published code root and refuses a
   missing, empty or mismatched set, naming the profile and the set.
   `stage` then applies a verified set at link time, as the linker
   composition's `:stage`: it derives the instrumented module `A'` from the
   published tree alone, bottom-up over the module's guest requirements,
   and publishes it beside its `:derive` record. An explicitly noninterruptible profile imports the module
   as published, with none."
  (:require [dao.jing :as jing]
            [yang.python.antlr.lower :as lower]
            [yang.safepoint :as safepoint]
            [yin.vm :as vm]
            [yin.vm.encoder :as encoder]
            [yin.vm.linker.publish :as publish]
            [yin.vm.linker.sign :as sign]))


(def frontend-revision
  "The frontend revision the marks are pinned to: it moves with any change
   to which nodes the Python lowering marks, or how."
  "yang.python.antlr.lower/sites-1")


(def lowering-profile
  "The transformation profile the marks were made under: the module
   emitter over the linked prelude, marking these kinds."
  {:yang.site/unit :module,
   :yang.site/prelude :linked,
   :yang.site/kinds [:call :line :loop]})


(def profile-address
  "The address the transformation profile is pinned by."
  (jing/segment-key lowering-profile))


(defn- medium
  []
  :yang.python.antlr/site-set)


(defn site-set-of
  "`{:root address :tree tree :set [[path mark] ...]}` for the module map
   AST `ast`: the address of its canonical tree, the tree's rows and its
   sorted site set."
  [ast]
  (let [env (encoder/project (encoder/source-envelope
                               (medium) [:site-set 0] [ast]))
        tree (nth (:yin/batch env) (:yin/root env))
        origin (vm/source-origin (:yin/source-medium env)
                                 (:yin/batch-token env) (:yin/root env))
        sites (safepoint/sites (:yin/frontend-metadata env) origin (:root tree))]
    {:root (:root tree), :tree tree, :set (safepoint/site-set sites)}))


(defn record
  "The publication record associating manifest `manifest` with site set
   `set-address` over code root `root`."
  [manifest root set-address]
  {:yang.site/manifest manifest,
   :yang.site/root root,
   :yang.site/revision frontend-revision,
   :yang.site/profile profile-address,
   :yang.site/set set-address})


(defn publish!
  "Publish the site set of module `ast` (published at manifest address
   `manifest`) into `handle`: the set as one blob, then its record. Answers
   `{:record r :record-address a :set-address s :set [...]}`."
  [handle manifest ast]
  (let [{:keys [root set]} (site-set-of ast)
        set-address (jing/materialize! handle (vec set))
        r (record manifest root set-address)]
    {:record r,
     :record-address (jing/materialize! handle r),
     :set-address set-address,
     :set set}))


(defn- read-at
  "The value at `address` in `handle`: `::absent` when the handle holds no
   bytes for a segment address (the handle is asked with a not-found
   sentinel, as a byte store is), nil when `address` is no segment address
   or its bytes do not hash to it, else the value."
  [handle address]
  (if-not (jing/segment-address? address)
    nil
    (let [bs ((:get-bytes-fn handle) address ::absent)]
      (cond
        (= ::absent bs) ::absent
        (nil? bs) ::absent
        (jing/segment-bytes-match? address bs) (jing/segment-value address bs)
        :else nil))))


(defn- absent?
  [x]
  (= ::absent x))


(defn- missing
  "The stage's report of the absent `address`: first one met, nothing
   written."
  [address]
  {:status :missing, :address address})


(defn- fetch
  "The value at `address` in `handle`, or nil when absent or not its
   address."
  [handle address]
  (let [v (read-at handle address)]
    (when-not (absent? v) v)))


;; =============================================================================
;; Discovery
;; =============================================================================

(defn signed-entry
  "The publisher `key`'s (`{:seed :public}`) signed publication entry
   `{:envelope e :proof p}` for the record at `record-address` of the
   module at `manifest`: what the index carries so a reader discovers the
   record from the published code."
  [key manifest record-address]
  (let [env {:yang.site/op :publish,
             :yang.site/manifest manifest,
             :yang.site/record record-address,
             :yin.module/asserted-by (sign/principal (:public key))}]
    {:envelope env, :proof (sign/sign-envelope (:seed key) env)}))


(defn entry-datoms
  "The index datoms of the signed entries `signed`, entity ids from 0."
  [signed]
  (vec (mapcat (fn [e {:keys [envelope proof]}]
                 [[e :yang.site/envelope envelope]
                  [e :yang.site/proof proof]])
               (range) signed)))


(defn discover
  "The record addresses `datoms` (`[e a v ...]`, a loaded index's) publish
   for the module at `manifest`, in datom order: the entries whose proof is
   a valid signature by one of the `publics` (64 hex keys) the reader
   declares, and no other."
  [datoms publics manifest]
  (let [proofs (reduce (fn [acc [e a v]]
                         (cond-> acc
                           (= a :yang.site/proof) (update e (fnil conj #{}) v)))
                       {} datoms)]
    (vec (distinct
           (keep (fn [[e a env]]
                   (when (and (= a :yang.site/envelope)
                              (map? env)
                              (= :publish (:yang.site/op env))
                              (= manifest (:yang.site/manifest env)))
                     (let [ps (get proofs e)
                           sig (when (= 1 (count ps))
                                 (:yin.module/signature (first ps)))]
                       (when (and (string? sig)
                                  (some #(sign/verify-envelope
                                           % (jing/canonical-bytes env) sig)
                                        publics))
                         (:yang.site/record env)))))
                 datoms)))))


;; =============================================================================
;; Verification and admission
;; =============================================================================

(defn- refusal
  [reason requirement message data]
  (merge {:status :refused,
          :reason reason,
          :profile (:profile requirement),
          :message (str "profile " (pr-str (:profile requirement))
                        " requires hooks: " message)}
         data))


(defn- entry-ok?
  "A set entry is `[path mark]`, a path vector and a mark of the profile's
   kinds (a kind, or `[kind datum ...]`)."
  [entry]
  (and (vector? entry)
       (= 2 (count entry))
       (vector? (first entry))
       (let [mark (second entry)]
         (boolean (some #{(if (vector? mark) (first mark) mark)}
                        (:yang.site/kinds lowering-profile))))))


(defn verify
  "Nil when the record at `record-address` verifies as the site set of the
   module published at `manifest` in `handle`; `{:status :missing :address
   a}` for the first address (manifest, record, set, in that order) the
   handle does not hold; else the first defect `{:defect k ...}` (with the
   record's `:set` address when it names one): the manifest or record not
   its address, the record naming another manifest, the code root, the
   frontend revision or the profile not the published ones, the set not its
   address or empty, or an entry not a path with a mark kind of the
   profile's."
  [handle manifest record-address]
  (let [m (read-at handle manifest)
        r (when-not (absent? m) (read-at handle record-address))
        set-address (when (map? r) (:yang.site/set r))
        set (when-not (or (absent? m) (absent? r))
              (read-at handle set-address))
        defect (cond
                 (not (map? m)) {:defect :manifest-absent}
                 (not (map? r)) {:defect :record-absent}
                 (not= manifest (:yang.site/manifest r)) {:defect :manifest}
                 (not= (:yin.module/tree m) (:yang.site/root r)) {:defect :root}
                 (not= frontend-revision (:yang.site/revision r))
                 {:defect :revision}
                 (not= profile-address (:yang.site/profile r)) {:defect :profile}
                 (not (vector? set)) {:defect :set-absent}
                 (empty? set) {:defect :set-empty}
                 (not (every? entry-ok? set)) {:defect :kind}
                 :else nil)]
    (cond
      (absent? m) (missing manifest)
      (absent? r) (missing record-address)
      (absent? set) (missing set-address)
      defect (cond-> defect set-address (assoc :set set-address)))))


(defn admit
  "A consumer's decision for the import of the module at `manifest` in
   `handle`, `record-addresses` the publication records it discovered for
   it, under `requirement`: `{:profile name :hooks true}` or `{:profile
   name :noninterruptible true}`. Answers `{:status :ok :instrumented? true
   :set [...] :set-address s :record a}` when a record verifies, `{:status
   :ok :instrumented? false :note ...}` for a noninterruptible profile,
   else a refusal naming the profile and the missing or mismatched set (its
   address when the record names one); or `{:status :missing :address a}`,
   the first address a record's verification reads that `handle` does not
   hold (`verify`)."
  [handle manifest record-addresses requirement]
  (cond
    (:noninterruptible requirement)
    {:status :ok,
     :instrumented? false,
     :note (str "profile " (pr-str (:profile requirement))
                " is noninterruptible: imported without a site set")}

    (:hooks requirement)
    (if (empty? record-addresses)
      (refusal :yang.python.antlr/missing-site-set requirement
               "no published site set for the module" {:manifest manifest})
      (loop [todo record-addresses
             first-defect nil]
        (if-let [a (first todo)]
          (let [v (verify handle manifest a)]
            (cond
              (nil? v)
              (let [set-address (:yang.site/set (fetch handle a))]
                {:status :ok,
                 :instrumented? true,
                 :record a,
                 :set-address set-address,
                 :set (fetch handle set-address)})

              (= :missing (:status v)) v

              :else (recur (rest todo) (or first-defect [a v]))))
          (let [[a defect] first-defect]
            (refusal :yang.python.antlr/site-set-mismatch requirement
                     (str "the published site set"
                          (when-let [s (:set defect)] (str " " s))
                          " does not verify (" (name (:defect defect)) ")")
                     (assoc defect :manifest manifest :record a))))))

    :else
    (refusal :yang.python.antlr/profile-undeclared requirement
             "the profile declares neither :hooks nor :noninterruptible"
             {:manifest manifest})))


;; =============================================================================
;; Link-time application
;; =============================================================================

(defn- load-tree
  "The canonical row set `{:root :rows}` of the tree at `root` in `handle`,
   every row fetched by its address, depth-first: `{:status :missing
   :address a}` for the first row the handle does not hold, nil when a row
   is not its address or no tree row."
  [handle root]
  (loop [todo [root]
         rows {}]
    (if-let [id (peek todo)]
      (if (contains? rows id)
        (recur (pop todo) rows)
        (let [body (read-at handle id)
              slots (when (vector? body)
                      (get vm/semantic-bytecode-grammar (first body)))]
          (cond
            (absent? body) (missing id)
            slots (recur (into (pop todo)
                               (mapcat (fn [[_ kind] v]
                                         (case kind
                                           :node [v]
                                           :nodes v
                                           nil))
                                       slots
                                       (rest body)))
                         (assoc rows id (into [id] body)))
            :else nil)))
      {:root root, :rows rows})))


(defn- derive-module
  "Publish into `handle` the instrumented module `A'` of the module `m` at
   `manifest` for the verified set of `adm`: the published tree with the
   hooks of `(:hooks-profile requirement)` at its sites, requiring
   `(:pysp requirement)` beside its own requirements, each guest
   requirement rebound through `derived` ({A A'}). The `:derive` record
   (`yang.safepoint/derive-record`) is materialized beside it and its
   address answered as `:derivation`; nothing transacts it into a ledger.
   Answers `{:status :ok :address a' :derivation d}`, a refusal, or
   `{:status :missing :address a}` for the first tree row the handle does
   not hold, before anything is written."
  [handle registry manifest m adm requirement derived]
  (let [fail (fn [message data]
               (refusal :yang.python.antlr/instrumentation-failed requirement
                        message (assoc data :manifest manifest)))]
    (if-not (jing/segment-address? (:pysp requirement))
      (fail "the hook module is not published" {})
      (try
        (let [tree (load-tree handle (:yin.module/tree m))
              absent (= :missing (:status tree))
              sites (into {} (:set adm))
              inserted (when (and tree (not absent))
                         (safepoint/insert tree sites
                                           (:hooks-profile requirement)))
              ast (when inserted
                    (vm/semantic-bytecode->ast (:tree inserted)))
              runtime (or (:runtime requirement) '#{py pysp})
              out (when ast
                    (publish/publish-module!
                      handle
                      {:name (:yin.module/name m),
                       :ast ast,
                       :exports (:yin.module/exports m),
                       :requires (assoc (into {}
                                              (map (fn [[n a]]
                                                     [n (if (contains? runtime n)
                                                          a
                                                          (get derived a a))]))
                                              (:yin.module/requires m))
                                        'pysp (:pysp requirement)),
                       :primitives (into {}
                                         (map (fn [[n _]]
                                                [n (lower/primitive-declaration
                                                     registry n)]))
                                         (:yin.module/primitives m))}))]
          (cond
            absent tree
            (nil? tree) (fail "the module's tree is not published" {})
            (= :refused (:status out))
            (fail "the instrumented module did not publish" {:cause out})
            :else
            {:status :ok,
             :address (:address out),
             :instrumented? true,
             :set-address (:set-address adm),
             :derivation (jing/materialize!
                           handle
                           (safepoint/derive-record
                             (:yin.module/tree m)
                             (:yin.module/tree (:manifest out))
                             (:hooks-profile requirement)
                             (:sites inserted)))}))
        (catch #?(:cljd Object :clj Exception :cljs :default) e
          (fail (str "the instrumented module did not derive: " (ex-message e))
                {}))))))


(defn- records-for
  "The record addresses for `manifest`: the local map `records`'s, then
   those `ctx`'s loaded index datoms publish under its principals."
  [ctx records manifest]
  (into (vec (get records manifest))
        (discover (:datoms ctx) (:principals ctx) manifest)))


(defn- derive-closure
  "The instrumented module of `manifest` under a hook profile, derived
   bottom-up: each guest requirement is admitted and derived first, so the
   derived module pins the derived requirements it will link (`derived`,
   {A A'}, derives a shared requirement once). Answers `{:status :ok
   :address a' :derivation d :derived derived}`, the first refusal, with
   `:name` the requirement that was refused and `:via` the module that
   required it, or `{:status :missing :address a}` for the first address
   (record, set, guest manifest, tree row) read in a fixed depth-first
   order that the handle does not hold. Nothing is written until every
   read has succeeded."
  [ctx registry records requirement manifest derived]
  (let [handle (:store ctx)
        runtime (or (:runtime requirement) '#{py pysp})
        adm (admit handle manifest (records-for ctx records manifest)
                   requirement)]
    (if (not= :ok (:status adm))
      adm
      (let [m (fetch handle manifest)
            deps (sort-by (comp str key)
                          (remove (comp runtime key)
                                  (:yin.module/requires m)))
            below (reduce (fn [acc [n a]]
                            (if (contains? (:derived acc) a)
                              acc
                              (let [r (derive-closure ctx registry records
                                                      requirement a
                                                      (:derived acc))]
                                (case (:status r)
                                  :ok (assoc acc :derived (:derived r))
                                  :missing (reduced r)
                                  (reduced (cond-> (assoc r :via manifest)
                                             (nil? (:name r)) (assoc :name n)))))))
                          {:derived derived}
                          deps)]
        (if (:status below)
          below
          (let [out (derive-module handle registry manifest m adm requirement
                                   (:derived below))]
            (if (not= :ok (:status out))
              out
              (assoc out :derived (assoc (:derived below) manifest
                                         (:address out))))))))))


(defn- overlay
  "A write-buffering handle over `base`: reads see the buffer first, then
   `base`; puts land in the buffer only, in put order. `flush!` writes the
   buffer to `base`; dropping the overlay writes nothing."
  [base]
  (let [buf (atom {:bytes {}, :order []})
        sentinel #?(:cljs (js-obj)
                    :default (Object.))]
    {:handle (assoc base
                    :get-bytes-fn (fn [address not-found]
                                    (let [bs (get-in @buf [:bytes address])]
                                      (if (nil? bs)
                                        ((:get-bytes-fn base) address not-found)
                                        bs)))
                    :put-bytes-fn (fn [address bs]
                                    (if (or (contains? (:bytes @buf) address)
                                            (let [existing ((:get-bytes-fn base)
                                                            address
                                                            sentinel)]
                                              (and (some? existing)
                                                   (not (identical? sentinel
                                                                    existing)))))
                                      :present
                                      (do (swap! buf (fn [b]
                                                       (-> b
                                                           (assoc-in [:bytes address]
                                                                     bs)
                                                           (update :order conj
                                                                   address))))
                                          :inserted))))
     :flush! (fn []
               (let [{:keys [bytes order]} @buf]
                 (doseq [a order] ((:put-bytes-fn base) a (get bytes a)))))}))


(def reasons
  "The refusal reasons the stage answers, the composition's own."
  #{:yang.python.antlr/missing-site-set
    :yang.python.antlr/site-set-mismatch
    :yang.python.antlr/profile-undeclared
    :yang.python.antlr/instrumentation-failed})


(defn stage
  "The link-time stage (`yin.vm.linker.interpreter/composition`'s `:stage`)
   of a Python import under `requirement`: `{:profile name :hooks true
   :hooks-profile p :pysp address}` or `{:profile name :noninterruptible
   true}`, with `:runtime` the names of the runtime modules (default `py`,
   `pysp`) it passes through. `records` ({manifest [record-address ...]})
   are the local publication's records; a DHT source supplies none and the
   stage discovers them from the index datoms of its context. The stage
   reads only the context's `:store` and never fetches: it answers
   `{:status :missing :address a}` for the first address that store does
   not hold, and is run again once it is there. Answers
   `(fn [ctx name address])`: `{:status :ok :address address}` as published
   when noninterruptible, else the address of the instrumented module
   derived bottom-up from the verified sets (`:derivation` its `:derive`
   record's address), or the refusal naming the profile, the set and the
   `:name` refused."
  [registry requirement records]
  (let [runtime (or (:runtime requirement) '#{py pysp})]
    (fn [ctx name address]
      (cond
        (contains? runtime name) {:status :ok, :address address}

        (:noninterruptible requirement)
        (assoc (admit (:store ctx) address [] requirement) :address address)

        :else
        (let [{:keys [handle flush!]} (overlay (:store ctx))
              r (derive-closure (assoc ctx :store handle) registry records
                                requirement address {})]
          (case (:status r)
            :ok (do (flush!)
                    (select-keys r [:status :address :derivation :instrumented?
                                    :set-address]))
            :missing (select-keys r [:status :address])
            (cond-> r (nil? (:name r)) (assoc :name name))))))))


(defn signed-entries
  "The `publisher` key's signed publication entries for the site sets
   `publish-units!` answered: `sites` (`:sites`), the manifest of each
   from `addresses` (a Python name) or `bundles` (a bundle symbol), in
   key order. The publisher that asserts these names indexes them in the
   same transaction (`entry-datoms`); a module with no entry is refused
   by every hook-profile reader."
  [publisher sites addresses bundles]
  (mapv (fn [[k s]]
          (signed-entry publisher (or (get addresses k) (get bundles k))
                        (:record-address s)))
        (sort-by (comp str key) sites)))
