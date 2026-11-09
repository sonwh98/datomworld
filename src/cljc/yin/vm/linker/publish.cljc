(ns yin.vm.linker.publish
  "Publish a complete four-format module closure into a DaoJing handle,
   derive a module from indexed code (docs/design/yin.vm.linker.dht.md
   5.3), and sign the name envelopes that bind it (6.1, 6.5, 6.6)."
  (:require [dao.jing :as jing]
            [dao.space.query :as query]
            [yin.vm :as vm]
            [yin.vm.semantic.code :as code]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.semantic.derive :as derive]
            [yin.vm.ledger :as ledger]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.linker :as linker]
            [yin.vm.linker.closure :as closure]
            [yin.vm.linker.requirements :as requirements]
            [yin.vm.linker.sign :as sign]))


(defn- refusal
  [reason data]
  (merge {:status :refused :reason reason} data))


(defn- definitions
  [ast]
  (letfn [(scan
            [node]
            (when (map? node)
              (case (:type node)
                :lambda #{}
                :application
                (let [xs (:operands node)
                      own (when (and (vm/definition-operator? (:operator node))
                                     (= 2 (count xs))
                                     (= :literal (:type (first xs)))
                                     (symbol? (:value (first xs))))
                            #{(:value (first xs))})]
                  (into (or own #{}) (mapcat scan)
                        (concat (when (= :lambda (:type (:operator node)))
                                  [(:body (:operator node))])
                                xs)))
                #{})))]
    (scan ast)))


(defn footprint
  "Derive the manifest footprint from tree rows, declared profiles and dependencies."
  [handle {:keys [ast tree primitives requires]}]
  (let [tree (or tree (vm/ast->semantic-bytecode ast))
        own (vm/ast-requirements (query/relation (vals (:rows tree))))
        bad-primitive (some (fn [[name profile]]
                              (when-not (and (map? profile)
                                             (keyword? (:yin.k/profile profile))
                                             (set? (:yin.k/effects profile)))
                                name))
                            primitives)
        missing (some (fn [[name address]]
                        (let [manifest (jing/get handle address ::absent)]
                          (when (= ::absent manifest) [name address])))
                      requires)]
    (cond
      (seq (:ffi-ops own))
      (refusal :yin.link.publish/ffi-op {:ffi-ops (:ffi-ops own)})

      (seq (:parked-ids own))
      (refusal :yin.link.publish/parked-id {:parked-ids (:parked-ids own)})

      bad-primitive
      (refusal :yin.link.publish/unprofiled-primitive
               {:primitive bad-primitive})

      missing
      (refusal :yin.link.publish/missing-requirement
               {:name (first missing) :manifest (second missing)})

      :else
      {:store-keys (:store-keys own)
       :effects (into (or (:effects own) #{})
                      (concat (mapcat :yin.k/effects (vals primitives))
                              (mapcat (fn [address]
                                        (get-in (jing/get handle address nil)
                                                [:yin.module/footprint :effects]))
                                      (vals requires))))})))


(def code-formats
  "The four code format records a module manifest links through."
  [linker/ast-format linker/semantic-format
   linker/stack-format linker/register-format])


;; =============================================================================
;; Call-site occurrence contract C1 (Addendum A, design §2)
;; =============================================================================
;;
;; A module whose frontend finalized its tree (`:yang/call-site 1` on
;; every final `:application` occurrence) publishes its frontend-metadata
;; side table beside its manifest: the manifest keeps the closed
;; schema-1 key set (a manifest address names code, and nothing else),
;; so the side table never enters the hashed manifest. The manifest is
;; materialized first, and the publication record then names it
;; (`:yang.call-site/manifest`), beside the tree root, the publication's
;; recorded admission origin, and the table's address — a record names
;; a manifest, never the reverse (the site-set precedent,
;; `yang.python.antlr.siteset`). Custody of the record and table runs
;; through the `publish-module!` result's `:call-sites`, not through
;; the module closure: execution never consults C1 tables, so the
;; closure walker does not follow them. The publication's origin is an ADMISSION identity
;; (design §2.1): a token the composition pins explicitly, draws from
;; a composition-owned scope object, or — by default — has derived
;; from the publication specification's own durable identity; no
;; counter or other mutable state lives in this namespace. The token
;; is persisted in the record and retrieved from the record, never
;; reconstructed from the tree's content. The publisher
;; extracts the markers from the map AST, refuses the
;; publication when they do not exactly cover the canonical tree's
;; application occurrences — or when a marker's value is not version 1 —
;; and otherwise materializes the table and its record into the same
;; handle. A module with no markers makes no claim and publishes exactly
;; as before.

(defn admission-scope
  "An admission scope for `publish-module!`'s `:yin.link.publish/scope`
   option. `id` names this publisher — a durable, caller-chosen
   identity (a string or keyword) that distinguishes this publisher's
   admissions from every other publisher's; a publisher that must not
   collide with its own past runs takes a fresh `id` per run. The
   returned scope object OWNS the publication ordinal counter, so no
   counter lives in this namespace (no hidden global state): two
   publications through one scope draw consecutive ordinals, and two
   independent scopes publishing identical specifications still yield
   distinct admission identities, because their `id`s differ."
  [id]
  {:yin.link.publish/scope-id id
   :yin.link.publish/ordinal (atom 0)})

(defn- scope-admission-token
  "The admission token a scope object draws for one publication: the
   scope's own `id` plus the ordinal the scope's counter assigns. The
   counter lives in the caller-owned scope object, never here."
  [{:yin.link.publish/keys [scope-id ordinal]}]
  ;; pr-str keeps the value's full identity — a keyword's namespace, a
  ;; string's quoting, a symbol's name — so distinct caller-chosen ids
  ;; (:a/publisher, :b/publisher, "publisher") never mint the same token
  (keyword "yin.vm.linker.publish.scope"
           (str (pr-str scope-id) "#" (swap! ordinal inc))))

(defn- spec-admission-token
  "The default admission token: derived deterministically from the
   publication specification's own durable identity — the module name
   and the content address of its compiled tree — never from hidden
   mutable state. Two publications of the same specification therefore
   answer the same admission identity (an idempotent re-publication is
   the same recorded admission), and distinct specifications never
   share one, in this or any other publisher process. A composition
   that admits the same specification more than once as separate
   admissions passes an `:yin.link.publish/scope` (or an explicit
   `:yin.link.publish/admission`) so the admissions stay distinct."
  [{:keys [name]} tree]
  (keyword "yin.vm.linker.publish.publication"
           (str name "::" (:root tree))))

(defn- publication-origin
  "The occurrence origin of the publication admission `token` names:
   the publisher's own medium, the explicit admission token, member 0.
   `token` is the publication's admission identity — the
   `:yin.link.publish/admission` option when the composition pins one,
   the `:yin.link.publish/scope` option's next drawn token when the
   composition scopes the publisher, otherwise the token derived from
   the publication specification's own durable identity. The origin is
   an admission event's identity, not a function of the tree's content
   alone: it is persisted in the side record at publication time and
   retrieved from that record; nothing ever reconstructs it from the
   root."
  [token]
  [:source :yin.vm.linker.publish/publish token 0])


(defn- claims-call-sites?
  "True when map AST `ast` carries the C1 key anywhere. A hit inside a
   literal payload is data, not a claim: extraction then finds no
   markers and the module publishes unannotated, as it should."
  [ast]
  (boolean (some #(= :yang/call-site %) (tree-seq coll? seq ast))))


(defn- call-site-claim
  "The C1 claim `ast` makes over its canonical `tree`: nil when it
   carries no markers; `{:origin o :rows [...]}` in canonical-byte
   order (`jing/canonical-compare`) when the extracted markers exactly cover the tree's eligible
   application occurrences; `{:missing [...] :extra [...]}` when they
   do not; `{:unsupported-version v :path p}` when a marker's value is
   not version 1 — a claim rejection, never a silent acceptance of a
   table a retriever would read as version 1."
  [ast tree admission]
  (when (claims-call-sites? ast)
    (let [origin (publication-origin admission)
          extracted (filter #(= :yang/call-site (nth % 3))
                            (:frontend-metadata
                              (vm/ast-side-tables ast tree origin)))
          eligible (into #{}
                         (map (fn [[r p]] [origin r p]))
                         (vm/application-occurrences tree))
          marked (into #{}
                       (map (fn [[o r p _ _]] [o r p]))
                       extracted)]
      (or (when-let [[v p] (some (fn [row]
                                   (let [v (nth row 4)]
                                     (when (not (and (integer? v) (= 1 v)))
                                       [v (subvec row 0 3)])))
                                 extracted)]
            {:unsupported-version v, :path p})
          (if (= eligible marked)
            {:origin origin, :rows (vec (sort jing/canonical-compare extracted))}
            {:missing (vec (sort jing/canonical-compare (remove marked eligible))),
             :extra (vec (sort jing/canonical-compare (remove eligible marked)))})))))


(defn link-local
  "Link `format`'s image through the manifest `address` over `handle`'s
   own content, `:verifying` with discharge deferred, as a serving
   composition does (yin.vm.linker.dht.md 4.1): a fresh local runtime per
   call, so no request reaches any peer.  `opts` overrides the link
   options; its `:bounds` are the runtime's composition bounds (section
   4.2 step 2), every one left out taking `linker/default-bounds`."
  ([handle address format] (link-local handle address format nil))
  ([handle address format opts]
   (linker/link-manifest
     (linker/local-runtime
       handle {:formats (into {} (map (fn [f] [(:format f) f]))
                              (conj code-formats linker/manifest-format
                                    linker/record-format))
               :bounds (:bounds opts)})
     address (:format format) {:primitives vm/primitives}
     (merge {:contract (:contract format)
             :derivation :verifying
             :defer-discharge true}
            (dissoc opts :bounds)))))


(defn- publish-closure!
  "`publish-module!` under the composition bounds of `opts`."
  [handle {:keys [name ast exports requires primitives] :as spec} opts]
  (let [undefined (first (remove (definitions ast) exports))
        tree (when-not undefined
               (try (vm/ast->semantic-bytecode ast)
                    (catch #?(:cljd Object :clj Exception :cljs :default) _
                      (refusal :yin.link.publish/malformed-tree {}))))
        defect (when (and tree (not= :refused (:status tree)))
                 (vm/validate-rows tree))
        ;; each pinned closure is walked before anything is written; an
        ;; absent pinned manifest is the footprint's missing-requirement
        requirement
        (when (and tree (not defect) (not= :refused (:status tree)))
          (some (fn [[n address]]
                  (let [w (closure/walk handle address opts)]
                    (case (:yin.link.closure/outcome w)
                      :complete nil
                      :invalid (refusal :yin.link.publish/invalid-requirement
                                        {:name n})
                      :missing (when (not= address (:address w))
                                 (refusal :yin.link.publish/incomplete-requirement
                                          {:name n :walk w})))))
                (sort-by (fn [[n _]] (str n)) requires)))
        fp (when (and tree (not defect) (not requirement)
                      (not= :refused (:status tree)))
             (footprint handle (assoc spec :tree tree)))
        ;; a C1 claim is validated before anything is written: a module
        ;; whose markers do not cover its tree is never published as
        ;; though it did. One publication is one admission event
        ;; (design §2.1): the admission identity is pinned explicitly by
        ;; the composition when it supplies `:yin.link.publish/admission`,
        ;; drawn from the composition's own scope object when it supplies
        ;; `:yin.link.publish/scope`, and otherwise derived from the
        ;; specification's own durable identity — no counter lives in
        ;; this namespace. Either way it is persisted in the side record
        ;; and retrieved from there
        admission (or (:yin.link.publish/admission opts)
                      (when-let [scope (:yin.link.publish/scope opts)]
                        (scope-admission-token scope))
                      (when tree (spec-admission-token spec tree)))
        claim (when (and tree (not defect) (not requirement)
                         (not= :refused (:status tree))
                         (not= :refused (:status fp)))
                (call-site-claim ast tree admission))]
    (cond
      undefined
      (refusal :yin.link.publish/undefined-export {:export undefined})

      (= :refused (:status tree)) tree

      defect
      (refusal :yin.link.publish/malformed-tree {:defect defect})

      requirement requirement

      (= :refused (:status fp)) fp

      ;; dispatch on the key's PRESENCE, not its truthiness: a marker
      ;; whose value is present but not the integer 1 — nil or false
      ;; included — carries :unsupported-version nil/false, and its
      ;; reason is the unsupported version, never incomplete coverage
      (and claim (contains? claim :unsupported-version))
      (refusal :yin.link.publish/unsupported-call-site-version claim)

      (and claim (not (:rows claim)))
      (refusal :yin.link.publish/incomplete-call-site-coverage claim)

      :else
      (let [tree-address (vm/materialize-tree! handle tree)
            sem-vector (:vector (linearize/project-rows tree))
            sem (code/materialize-vector! handle sem-vector)
            h-image (:image (derive/stack-image sem-vector))
            r-image (derive/register-image sem-vector)
            h (dcode/image-hash h-image)
            r (rcode/register-hash r-image)
            h-address (jing/materialize! handle h-image)
            r-address (jing/materialize! handle r-image)
            mint (fn [input output profile]
                   (jing/materialize!
                     handle
                     (assoc (ledger/derive-record input output)
                            :yin.ledger/profile profile)))
            ;; the manifest is materialized FIRST, with the closed
            ;; schema-1 key set only: the C1 side table never enters
            ;; the hashed manifest, so identical specifications under
            ;; distinct admissions share one manifest address
            manifest {:yin.module/name name
                      :yin.module/schema linker/manifest-schema
                      :yin.module/contracts
                      {:yin.ast/code vm/ast-contract
                       :yin.semantic/code vm/semantic-contract
                       :yin.debruijn.code vm/stack-contract
                       :yin.debruijn.register vm/register-contract}
                      :yin.module/tree tree-address
                      :yin.module/derivations
                      {:yin.semantic/code (mint tree-address sem ledger/lowering-profile)
                       :yin.debruijn.code
                       (mint sem h linker/stack-lowering-profile)
                       :yin.debruijn.register
                       (mint sem r linker/register-lowering-profile)}
                      :yin.module/index {:yin.debruijn.code h-address
                                         :yin.debruijn.register r-address}
                      :yin.module/exports (set exports)
                      :yin.module/requires (or requires {})
                      :yin.module/primitives
                      (into {} (map (fn [[n p]] [n (:yin.k/profile p)])
                                    primitives))
                      :yin.module/footprint fp}
            address (jing/materialize! handle manifest)
            ;; the C1 side table and its publication record, beside
            ;; the manifest: the record names the manifest (a record
            ;; names a manifest, never the reverse), so each admission
            ;; of one specification keeps its own record and table
            call-sites (when (:rows claim)
                         (let [table (jing/materialize! handle (:rows claim))
                               record {:yang.call-site/schema 1
                                       :yang.call-site/manifest address
                                       :yang.call-site/root (:root tree)
                                       :yang.call-site/origin (:origin claim)
                                       :yang.call-site/table table}]
                           {:record (jing/materialize! handle record)
                            :table table
                            :origin (:origin claim)
                            :root (:root tree)
                            :manifest address}))
            walked (closure/walk handle address opts)]
        (if (not= :complete (:yin.link.closure/outcome walked))
          (refusal :yin.link.publish/incomplete-closure
                   {:address address :walk walked})
          (let [links (into {}
                            (map (fn [f]
                                   [(:format f)
                                    (link-local handle address f
                                                (select-keys opts
                                                             [:bounds]))]))
                            code-formats)]
            (cond-> {:address address :manifest manifest
                     :identities {:yin.semantic/code sem
                                  :yin.debruijn.code sem
                                  :yin.debruijn.register sem}
                     :checksums {:yin.debruijn.code h
                                 :yin.debruijn.register r}
                     :links links}
              call-sites (assoc :call-sites call-sites))))))))


(defn publish-module!
  "Mint all four code formats, derivation records, and one schema-1 manifest.
   `opts` `{:bounds {...}}` are the composition bounds (section 4.2 step
   2) of every closure walk and local link the publication runs, every
   bound left out taking `linker/default-bounds`. `opts` may also pin
   the publication's admission identity explicitly
   (`:yin.link.publish/admission`) or scope it — a scope object from
   `admission-scope` under `:yin.link.publish/scope`, whose counter the
   caller owns; without either, the admission identity is derived from
   the specification's own durable identity, so re-publishing the same
   specification is the same recorded admission."
  ([handle spec] (publish-module! handle spec nil))
  ([handle spec opts] (publish-closure! handle spec opts)))


(defn- stored-tree
  "The canonical row tree stored under `root` in `handle`, rebuilt
   from its individually materialized rows (`vm/materialize-tree!`
   stores each row body under its own address), or nil when a reached
   row is absent."
  [handle root]
  (loop [queue [root]
         rows {}]
    (if (empty? queue)
      {:root root, :rows rows}
      (let [address (first queue)
            body (jing/get handle address nil)]
        (if-not (vector? body)
          nil
          (let [row (into [address] body)
                kids (into []
                           (mapcat (fn [[j [_ kind]]]
                                     (let [pos (+ j 2)]
                                       (case kind
                                         :node [(nth row pos)]
                                         :nodes (nth row pos)
                                         nil))))
                           (map-indexed vector
                                        (get vm/semantic-bytecode-grammar
                                             (nth row 1))))]
            (recur (into (subvec queue 1)
                         (remove #(contains? rows %) kids))
                   (assoc rows address row))))))))


(defn published-call-site-table
  "The C1 side table a publication record claims: `{:origin o :root r
   :rows [...] :tree tree}` with the rows exactly as stored and the
   module's canonical tree, or nil when no record verifies. The record
   names the manifest (`:yang.call-site/manifest`); the manifest never
   names the record, so a bare manifest address alone cannot discover
   a side table. Accepts the publication record's address, or the
   `publish-module!` result map (whose `:call-sites :record` and
   `:address` it reads); the three-argument form additionally holds
   the record to the manifest it is asked about. A record verifies
   only when its schema is 1, its `:yang.call-site/manifest` is the
   manifest in question, its `:yang.call-site/root` is that manifest's
   own tree, and its ORIGIN is the one RECORDED at publication
   (`:yang.call-site/origin`, held to be a well-formed occurrence
   origin). Nothing reconstructs an origin from content: a replay of
   this retrieval answers the recorded admission identity, and no
   other module's admission can be confused with it."
  ([handle record-or-result]
   (if (map? record-or-result)
     (published-call-site-table handle
                                (:address record-or-result)
                                (get-in record-or-result
                                        [:call-sites :record]))
     (published-call-site-table handle nil record-or-result)))
  ([handle manifest-address record-address]
   (when (jing/segment-address? record-address)
     (let [record (jing/get handle record-address nil)]
       (when (and (map? record)
                  (= 1 (:yang.call-site/schema record))
                  (jing/segment-address? (:yang.call-site/manifest record))
                  (or (nil? manifest-address)
                      (= manifest-address (:yang.call-site/manifest record)))
                  (jing/segment-address? (:yang.call-site/table record))
                  (vm/occurrence-origin? (:yang.call-site/origin record)))
         (let [manifest (jing/get handle
                                  (:yang.call-site/manifest record) nil)]
           (when (and (map? manifest)
                      (= (:yang.call-site/root record)
                         (:yin.module/tree manifest)))
             (when-let [tree (stored-tree handle (:yin.module/tree manifest))]
               (let [stored-rows (jing/get handle
                                           (:yang.call-site/table record)
                                           nil)]
                 (when (vector? stored-rows)
                   {:origin (:yang.call-site/origin record),
                    :root (:root tree), :rows stored-rows,
                    :tree tree}))))))))))


;; =============================================================================
;; Deriving a module from indexed code (5.3)
;; =============================================================================

(defn- indexed-programs
  "Every program the index `db` holds -- a relation of `[e a v t m]` index
   datoms, as `yin.repl.index` publishes them -- in ascending `t`, each
   `{:m m :t t :ast ast}`: its tree rebuilt from its own facts, those
   whose `m` is the program's metadata entity."
  [db]
  (let [by-m (group-by #(nth % 4)
                       (query/collect
                         (query/q '[:find ?e ?a ?v ?t ?m :where [?e ?a ?v ?t ?m]]
                                  db)))]
    (mapv (fn [[m t]] {:m m :t t :ast (vm/datoms->ast (vec (get by-m m)))})
          (sort-by (juxt second first)
                   (query/collect
                     (query/q '[:find ?m ?t :where [?m :yin.repl/root _ ?t _]]
                              db))))))


(defn requirements
  "The modules `ast` unconditionally requires at module level. Scan literal
   `(require 'm)` applications outside lambda bodies, except immediately
   applied lambdas. Conditional branches and try-require remain deferred."
  [ast]
  (requirements/eager ast))


(defn- free-of
  "The free names of the map AST `ast` (`yin.vm/free-names`)."
  [ast]
  (let [tree (vm/ast->semantic-bytecode ast)]
    (vm/free-names (query/relation (vals (:rows tree)))
                   (query/relation (vm/occurrences tree))
                   (:root tree))))


(def no-op-tree
  "The tree of a module that collects no program, which is the module of
   an empty export list: one `nil` literal, defining nothing."
  {:type :literal :value nil})


(defn- sequenced
  "One tree running `asts` in order: `((fn [_] second) first)`, nested.
   A program root in operand position is not a tail call.  No programs
   is `no-op-tree`."
  [asts]
  (if (empty? asts)
    no-op-tree
    (reduce (fn [body ast]
              {:type :application
               :tail? true
               :operator {:type :lambda :params '[_] :body body}
               :operands [(cond-> ast
                            (= :application (:type ast)) (assoc :tail? false))]})
            (peek asts)
            (rseq (pop asts)))))


(defn- free-declaration
  "How the module declares the free name `n`: `[:requires module
   address]`, `[:primitives n profile]`, or a refusal."
  [n {:keys [primitives modules host-modules]}]
  (let [module (some-> (namespace n) symbol)]
    (cond
      (and module (contains? modules module))
      [:requires module (get modules module)]

      (and module (contains? (set host-modules) module))
      (refusal :yin.link.publish/host-module {:name n :module module})

      (and (nil? module) (contains? primitives n))
      [:primitives n (vm/profile-of primitives n)]

      :else (refusal :yin.link.publish/undeclared-free {:name n}))))


(defn module-from-index
  "`publish-module!`'s spec for the module `name` exporting `exports`,
   derived from the indexed code of `db` (section 5.3), or a refusal.
   Persists nothing.

   The defining program of a symbol is the indexed program of greatest
   `t` defining it at module level.  The exports' defining programs are
   collected, then the defining program of every free name they hold
   that some indexed program defines, and, for each linked module a free
   qualified name declares, the latest program requiring that module at
   module level (refused `:yin.link.publish/missing-require-program`
   naming the module when none does), to a fixed point; the tree is the
   collected programs, each once, in ascending `t`.  Each remaining free name is a
   primitive of `primitives` (declared with its profile), or qualified by
   a module of `modules` -- `{name manifest-address}`, the modules the
   session linked -- (declared under `:requires`).  A name qualified by
   one of `host-modules` refuses `:yin.link.publish/host-module`, any
   other `:yin.link.publish/undeclared-free`."
  [db {:keys [name exports modules] :as opts}]
  (let [programs (mapv (fn [p]
                         (assoc p
                                :defines (definitions (:ast p))
                                :requires (requirements (:ast p))
                                :free (free-of (:ast p))))
                       (indexed-programs db))
        defining (fn [sym]
                   (last (filter #(contains? (:defines %) sym) programs)))
        requiring (fn [module]
                    (last (filter #(contains? (:requires %) module) programs)))
        ;; the linked modules the collected programs' free qualified names
        ;; declare as requirements (closure by linked requirements)
        linked (fn [ps]
                 (sort-by str
                          (distinct
                            (keep (fn [n]
                                    (let [m (some-> (namespace n) symbol)]
                                      (when (and m (not (defining n))
                                                 (contains? modules m))
                                        m)))
                                  (mapcat :free ps)))))
        undefined (first (remove defining exports))]
    (if undefined
      (refusal :yin.link.publish/undefined-export {:export undefined})
      (let [collected (loop [ms (set (map (comp :m defining) exports))]
                        (let [ps (filterv #(contains? ms (:m %)) programs)
                              unrequired (first (remove requiring (linked ps)))
                              more (-> ms
                                       (into (keep (comp :m defining))
                                             (mapcat :free ps))
                                       (into (keep (comp :m requiring))
                                             (linked ps)))]
                          (cond
                            unrequired
                            (refusal :yin.link.publish/missing-require-program
                                     {:module unrequired})

                            (= more ms) ps
                            :else (recur more))))
            declared (when (vector? collected)
                       (map #(free-declaration % opts)
                            (sort-by str
                                     (remove defining
                                             (distinct (mapcat :free collected))))))]
        (or (when (map? collected) collected)
            (some #(when (map? %) %) declared)
            {:name name
             :ast (sequenced (mapv :ast collected))
             :exports (set exports)
             :requires (into {}
                             (keep (fn [[k n a]] (when (= :requires k) [n a])))
                             declared)
             :primitives (into {}
                               (keep (fn [[k n p]] (when (= :primitives k) [n p])))
                               declared)})))))


;; =============================================================================
;; Name envelopes (6.1, 6.5, 6.6)
;; =============================================================================

(defn- signed
  [key env e]
  (let [proof (sign/sign-envelope (:seed key) env)
        e (if (some? e) e -1)]
    {:envelope env
     :proof proof
     :datoms [[e :yin.module/envelope env] [e :yin.module/proof proof]]}))


(defn assertion
  "The assertion envelope binding `name` to the module manifest
   `manifest` at sequence `seq`, signed by `key` (`{:seed :public}`):
   `{:envelope e :proof p :datoms [...]}`, the datoms of entity `e`
   (default -1) as section 6.1 indexes them."
  [key {:keys [name manifest seq e]}]
  (signed key
          {:yin.module/op :assert
           :yin.module/name name
           :yin.module/manifest manifest
           :yin.module/asserted-by (sign/principal (:public key))
           :yin.module/seq seq}
          e))


(defn retraction
  "The retraction of the assertion whose envelope id is `of`, at
   sequence `seq`, signed by `key`; shaped as `assertion`'s answer."
  [key {:keys [of seq e]}]
  (signed key
          {:yin.module/op :retract
           :yin.module/of of
           :yin.module/asserted-by (sign/principal (:public key))
           :yin.module/seq seq}
          e))


(defn- own-envelopes
  "Every envelope of `principal` in the index `db`."
  [db principal]
  (filterv #(= principal (:yin.module/asserted-by %))
           (map first
                (query/collect
                  (query/q '[:find ?env :where [_ :yin.module/envelope ?env _ _]]
                           db)))))


(defn next-seq
  "The publisher's next sequence (6.5): one more than the greatest
   sequence among `key`'s own envelopes in its own index `db`, 1 when
   there are none.  Derived, never stored."
  [db key]
  (inc (reduce max 0 (map :yin.module/seq
                          (own-envelopes db (sign/principal (:public key)))))))


(defn name-envelopes
  "What publishing `name` at `manifest` under `key` writes, given the
   publisher's own index `db` (6.6): nothing when its standing assertion
   already names `manifest`; otherwise a retraction of each standing
   assertion naming another manifest, then the assertion, at consecutive
   sequences from `next-seq`.  Each is `assertion`'s answer."
  [db key name manifest]
  (let [own (own-envelopes db (sign/principal (:public key)))
        retracted (set (keep #(when (= :retract (:yin.module/op %))
                                (:yin.module/of %))
                             own))
        standing (sort-by :yin.module/seq
                          (filter #(and (= :assert (:yin.module/op %))
                                        (= name (:yin.module/name %))
                                        (not (contains? retracted
                                                        (jing/segment-key %))))
                                  own))
        stale (remove #(= manifest (:yin.module/manifest %)) standing)
        bound? (some #(= manifest (:yin.module/manifest %)) standing)
        start (next-seq db key)]
    (cond-> (vec (map-indexed (fn [i env]
                                (retraction key {:of (jing/segment-key env)
                                                 :seq (+ start i)}))
                              stale))
      (not bound?) (conj (assertion key {:name name :manifest manifest
                                         :seq (+ start (count stale))})))))
