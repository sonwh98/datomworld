(ns yang.core.semantics
  "Portable descriptor data and admission against content-addressed runtime exports."
  (:require [clojure.string :as str]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yang.core.uast :as u]
            [yang.frontend :as frontend]
            [yin.vm :as vm]
            [yin.vm.module :as module]))


(defn- refuse!
  [defect evidence]
  (throw (ex-info "Semantics descriptor refused"
                  (frontend/refused :yang.semantics/invalid-descriptor
                                    (assoc evidence :defect defect)))))


(defn- address-matches?
  [address value]
  (and (jing/segment-address? address)
       (jing/segment-bytes-match? address (jing/canonical-bytes value))))


(defn capability
  [descriptor key]
  (get-in descriptor [:yang.semantics/capabilities key] :refused))


(defn literal
  "Emit a literal for an already decoded value. For raw-or-call, supply
   the magnitude bit count and the portable operand for the overflow hook."
  ([descriptor key value]
   (literal descriptor key value nil nil))
  ([descriptor key value bits overflow-operand]
   (let [strategy (get-in descriptor [:yang.semantics/literals key])]
     (case (:kind strategy)
       :raw (u/lit (get strategy :value value))
       :float64-raw (u/lit (cbor/float64 value))
       :tagged-map (u/lit {(:tag strategy) value})
       :raw-or-call (if (<= bits (:safe-max-bits strategy))
                      (u/lit value)
                      (u/app (u/v (:overflow-op strategy)) (u/lit overflow-operand)))))))


(defn- literal-strategy?
  [[_ strategy]]
  (and (map? strategy)
       (case (:kind strategy)
         :raw (every? #{:kind :value} (keys strategy))
         :float64-raw (= #{:kind} (set (keys strategy)))
         :tagged-map (and (= #{:kind :tag} (set (keys strategy))) (keyword? (:tag strategy)))
         :raw-or-call (and (= #{:kind :safe-max-bits :overflow-op} (set (keys strategy)))
                           (int? (:safe-max-bits strategy))
                           (<= 0 (:safe-max-bits strategy) 53)
                           (some? (:overflow-op strategy)))
         false)))


(defn- module-symbol?
  [x]
  (and (symbol? x) (nil? (namespace x))))


(defn- validate-schema!
  [descriptor]
  (let [{:yang.semantics/keys [schema id syntax prelude-module ambient-modules capabilities
                               literals hooks ambient-projections]} descriptor]
    (when-not (and (= #{:yang.semantics/schema :yang.semantics/id :yang.semantics/syntax
                        :yang.semantics/prelude-module :yang.semantics/ambient-modules
                        :yang.semantics/capabilities :yang.semantics/literals
                        :yang.semantics/hooks :yang.semantics/ambient-projections}
                      (set (keys descriptor)))
                   (nil? (frontend/non-portable-path descriptor []))
                   (= 1 schema) (qualified-keyword? id) (keyword? syntax)
                   (module-symbol? prelude-module) (vector? ambient-modules)
                   (every? (fn [{:keys [module address] :as pin}]
                             (and (map? pin) (= #{:module :address} (set (keys pin)))
                                  (module-symbol? module) (jing/segment-address? address)))
                           ambient-modules)
                   (map? capabilities)
                   (every? qualified-keyword? (keys capabilities))
                   (every? #{:cell :single-assignment :supported :refused :ambient-only}
                           (vals capabilities))
                   (map? literals) (every? keyword? (keys literals))
                   (every? literal-strategy? literals)
                   (map? hooks) (every? qualified-keyword? (keys hooks))
                   (map? ambient-projections)
                   (every? (fn [[alias {:keys [module export kind] :as projection}]]
                             (and (map? projection) (string? alias) (seq alias)
                                  (module-symbol? module) (module-symbol? export)
                                  (every? #{:module :export :kind} (keys projection))
                                  (or (not (contains? projection :kind))
                                      (#{:cell-accessor :value} kind))))
                           ambient-projections))
      (refuse! :schema {}))))


(defn- qualified-hook!
  [target]
  (when-not (qualified-symbol? target)
    (throw (ex-info "Semantics hooks must be namespace-qualified"
                    (frontend/refused :yang.semantics/unqualified-hook {:target target})))))


(defn- reserved?
  [target]
  (let [ns-name (namespace target)]
    (or (= target 'yin/def)
        (str/starts-with? (name target) "dsg__")
        (= ns-name "yang.safepoint")
        (some #(str/ends-with? ns-name %) [".rt" ".b" ".impl"]))))


(defn- definitions
  [tree]
  (when-not (and (= :application (:type tree))
                 (= :lambda (get-in tree [:operator :type])))
    (refuse! :module-shape {}))
  (reduce (fn [defs {:keys [type operator operands]}]
            (if (and (= type :application) (= (:name operator) 'yin/def))
              (let [[binding expr] operands sym (:value binding)]
                (when-not (and (= 2 (count operands)) (= :literal (:type binding))
                               (symbol? sym) (not (contains? defs sym)))
                  (refuse! :module-definition {:symbol sym}))
                (assoc defs sym expr))
              defs))
          {} (:operands tree)))


(defn- verified-definitions
  [store address manifest]
  (let [root (:yin.module/tree manifest)
        tree (get store root)
        rows (try
               (if (:rows tree) tree (when (map? tree) (vm/ast->semantic-bytecode tree)))
               (catch #?(:cljd Object :clj Exception :cljs :default) _ nil))]
    (when-not (and rows (= (:root rows) root) (nil? (vm/validate-rows rows))
                   (every? (fn [[id row]]
                             (and (= id (first row)) (address-matches? id (subvec row 1))))
                           (:rows rows)))
      (refuse! :tree-address {:address address :tree root}))
    (definitions (vm/semantic-bytecode->ast rows))))


(defn- closure
  [descriptor store]
  (let [pins (:yang.semantics/ambient-modules descriptor)
        addresses (into {} (map (juxt :module :address)) pins)
        root (:yang.semantics/prelude-module descriptor)]
    (when (or (not= (count pins) (count addresses)) (not (contains? addresses root)))
      (refuse! :module-pins {}))
    (letfn [(visit
              [modules active sym address]
              (when (or (not= address (get addresses sym)) (contains? active sym))
                (refuse! :module-pins {:module sym :address address}))
              (if (contains? modules sym)
                modules
                (let [manifest (get store address)]
                  (when-not (and (map? manifest) (address-matches? address manifest)
                                 (= sym (:yin.module/name manifest))
                                 (set? (:yin.module/exports manifest))
                                 (map? (:yin.module/requires manifest)))
                    (refuse! :manifest-address {:module sym :address address}))
                  (let [deps (reduce (fn [seen [dep pin]]
                                       (visit seen (conj active sym) dep pin))
                                     modules
                                     (sort-by (comp str first) (:yin.module/requires manifest)))]
                    (assoc deps sym {:address address
                                     :exports (:yin.module/exports manifest)
                                     :definitions (verified-definitions store address manifest)})))))]
      (let [modules (visit {} #{} root (get addresses root))]
        (when-not (= (set (keys addresses)) (set (keys modules)))
          (refuse! :module-pins {}))
        modules))))


(defn- export!
  [modules target]
  (let [entry (get modules (symbol (namespace target)))
        exported (symbol (name target))]
    (when-not (and (contains? (:exports entry) exported)
                   (contains? (:definitions entry) exported))
      (refuse! :export {:target target}))
    [(:address entry) (get-in entry [:definitions exported])]))


(defn- authorize!
  [roles address target role]
  (when-not (contains? roles [address target role])
    (refuse! :export-role {:address address :target target :role role})))


(defn- host-arity?
  [arities n]
  (let [fixed (filter int? arities)]
    (or (some #{n} fixed)
        (and (some #{:variadic} arities)
             (seq fixed)
             (<= (apply max fixed) n)))))


(defn- callable!
  [modules store registry roles target role {:keys [arity params call-shapes operands]}]
  (qualified-hook! target)
  (when (reserved? target) (refuse! :reserved-door {:target target}))
  (let [host-address (when-not (contains? modules (symbol (namespace target)))
                       (module/host-export-profile registry target))
        [address node] (if host-address [host-address nil] (export! modules target))
        shapes (or call-shapes #{arity})]
    (cond
      (map? operands)
      (when-not (and (= shapes (set (keys operands)))
                     (every? (fn [[argc slots]] (and (vector? slots) (= argc (count slots))))
                             operands))
        (refuse! :arity {:target target :call-shapes shapes}))

      (and operands (not (contains? shapes (count operands))))
      (refuse! :arity {:target target :call-shape (count operands)}))
    (if host-address
      (let [profile (get store host-address)]
        (when-not (and (address-matches? host-address profile)
                       (every? #(host-arity? (:yin.k/arities profile) %) shapes))
          (refuse! :arity {:target target :call-shapes shapes})))
      (let [actual (:params node)]
        (when-not (= :lambda (:type node)) (refuse! :non-lambda {:target target}))
        (when-not (and (= arity (count actual))
                       (or (nil? params) (= params actual))
                       ;; A contract can record optional trailing operands.
                       (every? #(and (int? %) (<= 0 % arity)) shapes))
          (refuse! :arity {:target target :params actual :call-shapes shapes}))))
    (authorize! roles address target role)))


(defn validate-descriptor
  "Validate portable data, closure pins and each executable field. The trusted
   frontend contract supplies its hook ABI, the entry signature selected by
   descriptor id, and the set of [address qualified-export exact-role] grants.
   The artifact store is an immutable map of manifests, UAST trees or row
   packets, and any declared host primitive profiles."
  [descriptor {:keys [syntax hooks required-hooks literals required-literals entry-lowering roles]}
   store registry]
  (when-not (map? descriptor) (refuse! :schema {}))
  (validate-schema! descriptor)
  (when-not (= syntax (:yang.semantics/syntax descriptor))
    (refuse! :syntax {}))
  (let [active (:yang.semantics/hooks descriptor)
        strategies (:yang.semantics/literals descriptor)
        projections (:yang.semantics/ambient-projections descriptor)
        entry (get entry-lowering (:yang.semantics/id descriptor))
        modules (closure descriptor store)]
    (when-not entry (refuse! :entry-contract {}))
    (doseq [[role target] (sort-by key active)
            :when (some? target)]
      (qualified-hook! target)
      (when (reserved? target) (refuse! :reserved-door {:target target}))
      (let [spec (if (= role (:hook entry)) entry (get hooks role))]
        (when-not spec (refuse! :unknown-role {:role role}))
        (callable! modules store registry roles target role spec)))
    (doseq [[role spec] (sort-by key hooks)
            :when (and (not (some? (get active role)))
                       (or (= role (:hook entry))
                           (contains? required-hooks role)
                           (some #(#{:supported :cell :single-assignment} (capability descriptor %))
                                 (:required-by spec))))]
      (refuse! :missing-hook {:role role}))
    (doseq [key (sort required-literals) :when (not (contains? strategies key))]
      (refuse! :missing-literal {:literal key}))
    (doseq [[key {:keys [kind overflow-op]}] (sort-by key strategies)
            :when (= kind :raw-or-call)]
      (callable! modules store registry roles overflow-op [:literal key]
                 (or (get literals key) (refuse! :unknown-role {:literal key}))))
    (doseq [[alias {:keys [module export kind] :or {kind :cell-accessor}}]
            (sort-by key projections)]
      (let [target (symbol (str module) (name export))]
        (when (reserved? target) (refuse! :reserved-door {:target target}))
        (let [[address node] (export! modules target)]
          (case kind
            :cell-accessor
            (doseq [[target node arity]
                    (into [[target node 0]]
                          (map (fn [[name arity]]
                                 (let [sym (symbol (str module) name)]
                                   [sym (second (export! modules sym)) arity])))
                          [["init!" 0] ["export" 1]])]
              (when-not (and (= :lambda (:type node)) (= arity (count (:params node))))
                (refuse! :projection-signature {:target target :alias alias})))
            :value (when-not (= :literal (:type node))
                     (refuse! :projection-value {:alias alias :target target})))
          (authorize! roles address target [:projection alias kind]))))
    descriptor))
