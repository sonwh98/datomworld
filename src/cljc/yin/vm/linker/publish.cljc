(ns yin.vm.linker.publish
  "Publish a complete four-format module closure into a DaoJing handle,
   derive a module from indexed code (docs/design/yin.vm.linker.dht.md
   5.3), and sign the name envelopes that bind it (6.1, 6.5, 6.6)."
  (:require [dao.jing :as jing]
            [dao.space.query :as query]
            [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.ledger :as ledger]
            [yin.vm.linearize :as linearize]
            [yin.vm.linker :as linker]
            [yin.vm.linker.closure :as closure]
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


(defn link-local
  "Link `format`'s image through the manifest `address` over `handle`'s
   own content, `:verifying` with discharge deferred, as a serving
   composition does (yin.vm.linker.dht.md 4.1): a fresh local runtime per
   call, so no request reaches any peer.  `opts` overrides the link
   options."
  ([handle address format] (link-local handle address format nil))
  ([handle address format opts]
   (linker/link-manifest
     (linker/local-runtime
       handle {:formats (into {} (map (fn [f] [(:format f) f]))
                              (conj code-formats linker/manifest-format
                                    linker/record-format))})
     address (:format format) {:primitives vm/primitives}
     (merge {:contract (:contract format)
             :derivation :verifying
             :defer-discharge true}
            opts))))


(defn publish-module!
  "Mint all four code formats, derivation records, and one schema-1 manifest."
  [handle {:keys [name ast exports requires primitives] :as spec}]
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
                  (let [w (closure/walk handle address)]
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
             (footprint handle (assoc spec :tree tree)))]
    (cond
      undefined
      (refusal :yin.link.publish/undefined-export {:export undefined})

      (= :refused (:status tree)) tree

      defect
      (refusal :yin.link.publish/malformed-tree {:defect defect})

      requirement requirement

      (= :refused (:status fp)) fp

      :else
      (let [tree-address (vm/materialize-tree! handle tree)
            sem-vector (:vector (linearize/lower-rows tree))
            sem (code/materialize-vector! handle sem-vector)
            h-image (:image (dl/adapt (vm/ast->datoms ast)))
            r-image (:image (rc/adapt (second (vm/ast->datoms-with-root ast))))
            h (dcode/image-hash h-image)
            r (rcode/register-hash r-image)
            h-address (jing/materialize! handle h-image)
            r-address (jing/materialize! handle r-image)
            mint (fn [output profile]
                   (jing/materialize!
                     handle
                     (assoc (ledger/derive-record tree-address output)
                            :yin.ledger/profile profile)))
            manifest {:yin.module/name name
                      :yin.module/schema linker/manifest-schema
                      :yin.module/contracts
                      {:yin.ast/code vm/ast-contract
                       :yin.semantic/code vm/semantic-contract
                       :yin.debruijn.code vm/stack-contract
                       :yin.debruijn.register vm/register-contract}
                      :yin.module/tree tree-address
                      :yin.module/derivations
                      {:yin.semantic/code (mint sem ledger/lowering-profile)
                       :yin.debruijn.code
                       (mint h linker/stack-lowering-profile)
                       :yin.debruijn.register
                       (mint r linker/register-lowering-profile)}
                      :yin.module/index {h h-address r r-address}
                      :yin.module/exports (set exports)
                      :yin.module/requires (or requires {})
                      :yin.module/primitives
                      (into {} (map (fn [[n p]] [n (:yin.k/profile p)])
                                    primitives))
                      :yin.module/footprint fp}
            address (jing/materialize! handle manifest)
            walked (closure/walk handle address)]
        (if (not= :complete (:yin.link.closure/outcome walked))
          (refusal :yin.link.publish/incomplete-closure
                   {:address address :walk walked})
          (let [links (into {}
                            (map (fn [f]
                                   [(:format f) (link-local handle address f)]))
                            code-formats)]
            {:address address :manifest manifest
             :identities {:yin.semantic/code sem
                          :yin.debruijn.code h
                          :yin.debruijn.register r}
             :links links}))))))


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


(defn- requirements
  "The modules `ast` requires at module level: every `(require 'm)`
   application -- operator the variable `require`, one literal symbol
   operand -- outside any lambda body but that of an immediately applied
   lambda, as `definitions` scans."
  [ast]
  (letfn [(scan
            [node]
            (when (map? node)
              (case (:type node)
                :lambda #{}
                :application
                (let [xs (:operands node)
                      own (when (and (= 'require (get-in node [:operator :name]))
                                     (= :variable (get-in node [:operator :type]))
                                     (= 1 (count xs))
                                     (= :literal (:type (first xs)))
                                     (symbol? (:value (first xs))))
                            #{(:value (first xs))})]
                  (into (or own #{}) (mapcat scan)
                        (concat (when (= :lambda (:type (:operator node)))
                                  [(:body (:operator node))])
                                xs)))
                #{})))]
    (scan ast)))


(defn- free-of
  "The free names of the map AST `ast` (`yin.vm/free-names`)."
  [ast]
  (let [tree (vm/ast->semantic-bytecode ast)]
    (vm/free-names (query/relation (vals (:rows tree)))
                   (query/relation (vm/occurrences tree))
                   (:root tree))))


(defn- sequenced
  "One tree running `asts` in order: `((fn [_] second) first)`, nested.
   A program root in operand position is not a tail call."
  [asts]
  (reduce (fn [body ast]
            {:type :application
             :tail? true
             :operator {:type :lambda :params '[_] :body body}
             :operands [(cond-> ast
                          (= :application (:type ast)) (assoc :tail? false))]})
          (peek asts)
          (rseq (pop asts))))


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
