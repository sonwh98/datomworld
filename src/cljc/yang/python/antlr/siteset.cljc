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
   address. The record is a datom map, so an index carries it beside the
   name envelopes, and the module manifest keeps its schema: a manifest
   address names code, and a record names a manifest.

   A consumer under a profile requiring hooks `admit`s an import: it finds
   the record for the manifest, verifies every pin against the published
   code root, and refuses a missing or mismatched set, naming the profile
   and the set. An explicitly noninterruptible profile imports with none."
  (:require [dao.jing :as jing]
            [yang.safepoint :as safepoint]
            [yin.vm :as vm]
            [yin.vm.encoder :as encoder]))


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
  "`{:root address :set [[path mark] ...]}` for the module map AST `ast`:
   the address of its canonical tree and its sorted site set."
  [ast]
  (let [env (encoder/project (encoder/source-envelope
                               (medium) [:site-set 0] [ast]))
        tree (nth (:yin/batch env) (:yin/root env))
        origin (vm/source-origin (:yin/source-medium env)
                                 (:yin/batch-token env) (:yin/root env))
        sites (safepoint/sites (:yin/frontend-metadata env) origin (:root tree))]
    {:root (:root tree), :tree tree, :set (safepoint/site-set sites)}))


(defn record
  "The publication record (a datom map) associating manifest `manifest` with
   site set `set-address` over code root `root`."
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


(defn- refusal
  [reason requirement data]
  (merge {:status :refused,
          :reason reason,
          :profile (:profile requirement),
          :message (str "profile " (pr-str (:profile requirement))
                        " requires hooks: "
                        (case reason
                          :yang.python.antlr/missing-site-set
                          "no published site set for the module"
                          :yang.python.antlr/site-set-mismatch
                          "the published site set does not verify"))}
         data))


(defn verify
  "Nil when `record` verifies as the site set of the module published at
   manifest `manifest` in `handle`, else the first defect
   `{:defect k ...}`: the manifest absent, the record naming another
   manifest, the code root, the frontend revision or the profile not the
   published ones, the set absent or not its address, or a mark kind
   outside the profile's."
  [handle manifest record]
  (let [m (fetch handle manifest)
        set (when (map? record) (fetch handle (:yang.site/set record)))]
    (cond
      (nil? m) {:defect :manifest-absent}
      (not= manifest (:yang.site/manifest record)) {:defect :manifest}
      (not= (:yin.module/tree m) (:yang.site/root record)) {:defect :root}
      (not= frontend-revision (:yang.site/revision record)) {:defect :revision}
      (not= profile-address (:yang.site/profile record))
      {:defect :profile}
      (not (vector? set)) {:defect :set-absent}
      (not (every? (fn [[_ mark]]
                     (some #{(if (vector? mark) (first mark) mark)}
                           (:yang.site/kinds lowering-profile)))
                   set))
      {:defect :kind}
      :else nil)))


(defn admit
  "A consumer's decision for the import of the module at `manifest` in
   `handle`, `records` the publication records it can discover, under
   `requirement`: `{:profile name :hooks true}` or `{:profile name
   :noninterruptible true}`. Answers `{:status :ok :set [...]}` when a
   verified set is bound to the code, `{:status :ok :instrumented? false
   :note ...}` for a noninterruptible profile, else a refusal naming the
   profile and the missing or mismatched set."
  [handle manifest records requirement]
  (if (:noninterruptible requirement)
    {:status :ok,
     :instrumented? false,
     :note (str "profile " (pr-str (:profile requirement))
                " is noninterruptible: imported without a site set")}
    (let [r (some #(when (= manifest (:yang.site/manifest %)) %) records)]
      (if-not r
        (refusal :yang.python.antlr/missing-site-set requirement
                 {:manifest manifest})
        (if-let [defect (verify handle manifest r)]
          (refusal :yang.python.antlr/site-set-mismatch requirement
                   (assoc defect :manifest manifest))
          {:status :ok,
           :instrumented? true,
           :set (fetch handle (:yang.site/set r))})))))


(defn instrumented-ast
  "Module map AST `ast` with the hooks of `profile` inserted at the sites of
   the verified `set` (`admit`'s): `A'`, ready to publish as the module the
   hooks profile imports."
  [ast set profile]
  (let [{:keys [tree]} (site-set-of ast)
        sites (into {} (map (fn [[path mark]] [path mark])) set)]
    (vm/semantic-bytecode->ast (:tree (safepoint/insert tree sites profile)))))
