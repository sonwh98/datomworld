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
   `import-address` then applies a verified set at link time: it derives
   the instrumented module `A'` from the published tree alone and
   publishes it, and `link-env` rebinds a name environment to those
   addresses. An explicitly noninterruptible profile imports the module
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


(defn- fetch
  "The value at `address` in `handle`, verified against its address, or nil."
  [handle address]
  (let [bs (when (jing/segment-address? address)
             ((:get-bytes-fn handle) address nil))]
    (when (and bs (jing/segment-bytes-match? address bs))
      (jing/segment-value address bs))))


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
   module published at `manifest` in `handle`, else the first defect
   `{:defect k ...}` (with the record's `:set` address when it names one):
   the manifest absent, the record absent or not its address, the record
   naming another manifest, the code root, the frontend revision or the
   profile not the published ones, the set absent, not its address or
   empty, or an entry not a path with a mark kind of the profile's."
  [handle manifest record-address]
  (let [m (fetch handle manifest)
        r (fetch handle record-address)
        set-address (when (map? r) (:yang.site/set r))
        set (fetch handle set-address)
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
    (when defect
      (cond-> defect set-address (assoc :set set-address)))))


(defn admit
  "A consumer's decision for the import of the module at `manifest` in
   `handle`, `record-addresses` the publication records it discovered for
   it, under `requirement`: `{:profile name :hooks true}` or `{:profile
   name :noninterruptible true}`. Answers `{:status :ok :instrumented? true
   :set [...] :set-address s :record a}` when a record verifies, `{:status
   :ok :instrumented? false :note ...}` for a noninterruptible profile,
   else a refusal naming the profile and the missing or mismatched set (its
   address when the record names one)."
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
      (let [checked (mapv (fn [a] [a (verify handle manifest a)])
                          record-addresses)]
        (if-let [[a _] (some #(when (nil? (second %)) %) checked)]
          (let [set-address (:yang.site/set (fetch handle a))]
            {:status :ok,
             :instrumented? true,
             :record a,
             :set-address set-address,
             :set (fetch handle set-address)})
          (let [[a defect] (first checked)]
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
   every row fetched by its address, or nil when one is absent."
  [handle root]
  (loop [todo [root]
         rows {}]
    (if-let [id (peek todo)]
      (if (contains? rows id)
        (recur (pop todo) rows)
        (let [body (fetch handle id)
              slots (when (vector? body)
                      (get vm/semantic-bytecode-grammar (first body)))]
          (when slots
            (recur (into (pop todo)
                         (mapcat (fn [[_ kind] v]
                                   (case kind
                                     :node [v]
                                     :nodes v
                                     nil))
                                 slots
                                 (rest body)))
                   (assoc rows id (into [id] body))))))
      {:root root, :rows rows})))


(defn- derive-module
  "Publish into `handle` the instrumented module `A'` of the module `m` at
   `manifest` for the verified set of `adm`: the published tree with the
   hooks of `(:hooks-profile requirement)` at its sites, requiring
   `(:pysp requirement)` beside its own requirements. Answers
   `{:status :ok :address a'}` or a refusal."
  [handle registry manifest m adm requirement]
  (let [fail (fn [message data]
               (refusal :yang.python.antlr/instrumentation-failed requirement
                        message (assoc data :manifest manifest)))]
    (if-not (jing/segment-address? (:pysp requirement))
      (fail "the hook module is not published" {})
      (try
        (let [tree (load-tree handle (:yin.module/tree m))
              ast (when tree
                    (vm/semantic-bytecode->ast
                      (:tree (safepoint/insert tree (into {} (:set adm))
                                               (:hooks-profile requirement)))))
              out (when ast
                    (publish/publish-module!
                      handle
                      {:name (:yin.module/name m),
                       :ast ast,
                       :exports (:yin.module/exports m),
                       :requires (assoc (:yin.module/requires m)
                                        'pysp (:pysp requirement)),
                       :primitives (into {}
                                         (map (fn [[n _]]
                                                [n (lower/primitive-declaration
                                                     registry n)]))
                                         (:yin.module/primitives m))}))]
          (cond
            (nil? tree) (fail "the module's tree is not published" {})
            (= :refused (:status out))
            (fail "the instrumented module did not publish" {:cause out})
            :else {:status :ok,
                   :address (:address out),
                   :instrumented? true,
                   :set-address (:set-address adm)}))
        (catch #?(:cljd Object :clj Exception :cljs :default) e
          (fail (str "the instrumented module did not derive: " (ex-message e))
                {}))))))


(defn import-address
  "The address the import of the module at `manifest` links at under
   `requirement` (`admit`'s, and for a hook profile also `:hooks-profile`,
   the `yang.safepoint` profile, and `:pysp`, the hook module's address):
   `{:status :ok :address a}`, the manifest itself when noninterruptible,
   else the address of the instrumented module derived from the verified
   set (`:instrumented? true`); or `admit`'s refusal. `record-addresses`
   are the records discovered for `manifest`."
  [handle registry manifest record-addresses requirement]
  (let [adm (admit handle manifest record-addresses requirement)]
    (cond
      (= :refused (:status adm)) adm
      (not (:instrumented? adm)) (assoc adm :address manifest)
      :else (derive-module handle registry manifest (fetch handle manifest)
                           adm requirement))))


(defn link-env
  "`name-env` ({name manifest}) rebound for the link source of an import
   under `requirement`: each name but the runtime modules in
   `(:runtime requirement)` (default `py`, `pysp`) goes through
   `import-address`, `records` ({manifest [record-address ...]}) its
   discovered records. Answers `{:status :ok :name-env env}` or the first
   refusal, naming the module in `:name`."
  [handle registry name-env records requirement]
  (let [runtime (or (:runtime requirement) '#{py pysp})]
    (reduce (fn [acc [n manifest]]
              (if (contains? runtime n)
                (assoc-in acc [:name-env n] manifest)
                (let [r (import-address handle registry manifest
                                        (get records manifest) requirement)]
                  (if (= :refused (:status r))
                    (reduced (assoc r :name n))
                    (assoc-in acc [:name-env n] (:address r))))))
            {:status :ok, :name-env {}}
            (sort-by (comp str key) name-env))))
