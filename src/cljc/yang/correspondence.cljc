(ns yang.correspondence
  "Structural checks for D1 records. These checks do not establish reliance:
   all referenced content and relationships still require reconstruction."
  (:require [dao.jing :as jing]
            [yin.vm :as vm]))


(def ^:private record-keys
  #{:yang.correspondence/schema :yang.correspondence/evidence
    :yang.correspondence/recipe :yang.correspondence/inputs
    :yang.correspondence/roots :yang.correspondence/origin-bindings
    :yang.correspondence/artifacts :yang.correspondence/metadata
    :yang.correspondence/instruction-provenance})


(def ^:private recipe-keys
  #{:yang.correspondence.recipe/schema :yang.correspondence.recipe/function
    :yang.correspondence.recipe/profile :yang.correspondence.recipe/input-roles
    :yang.correspondence.recipe/evidence-contract :yang.correspondence.recipe/coverage})


(defn- distinct-vector?
  [v pred]
  (and (vector? v) (every? pred v) (= (count v) (count (set v)))))


(defn- role?
  [entry]
  (and (vector? entry) (= 2 (count entry)) (keyword? (first entry))
       (int? (second entry)) (<= 0 (second entry))))


(defn recipe?
  "Check the closed schema and basic shapes, without resolving the profile."
  [x]
  (and (map? x) (= recipe-keys (set (keys x)))
       (= 1 (:yang.correspondence.recipe/schema x))
       (qualified-symbol? (:yang.correspondence.recipe/function x))
       (jing/segment-address? (:yang.correspondence.recipe/profile x))
       (distinct-vector? (:yang.correspondence.recipe/input-roles x) role?)
       (some? (:yang.correspondence.recipe/evidence-contract x))
       (jing/segment-address? (:yang.correspondence.recipe/coverage x))))


(defn- path-step?
  [x]
  (or (and (int? x) (<= 0 x))
      (and (vector? x) (= 2 (count x))
           (every? #(and (int? %) (<= 0 %)) x))))


(defn- binding?
  [entry]
  (and (vector? entry) (= 2 (count entry))
       (some? (first entry))
       (let [k (second entry)]
         (and (vector? k) (= 3 (count k))
              (vm/occurrence-origin? (nth k 0))
              (jing/segment-address? (nth k 1))
              (vector? (nth k 2)) (every? path-step? (nth k 2))))))


(defn- artifact?
  [x]
  (and (map? x) (= #{:format :identity :storage :derivation} (set (keys x)))
       (keyword? (:format x)) (some? (:identity x))
       (jing/segment-address? (:storage x))
       (jing/segment-address? (:derivation x))))


(defn correspondence?
  "Check the closed schema and basic shapes, not addresses or endpoints.
   Compilation-scoped site uniqueness is checked during reconstruction."
  [x]
  (and (map? x) (= record-keys (set (keys x)))
       (= 1 (:yang.correspondence/schema x))
       (every? jing/segment-address?
               [(:yang.correspondence/evidence x) (:yang.correspondence/recipe x)
                (:yang.correspondence/metadata x)])
       (distinct-vector? (:yang.correspondence/inputs x) jing/segment-address?)
       (distinct-vector? (:yang.correspondence/roots x) jing/segment-address?)
       (distinct-vector? (:yang.correspondence/artifacts x) artifact?)
       (distinct-vector? (:yang.correspondence/instruction-provenance x) jing/segment-address?)
       (distinct-vector? (:yang.correspondence/origin-bindings x) binding?)))


(defn store-evidence!
  "Store the complete version-1 site-table value without projecting its rows.
   This checks only the outer version; reconstruction must validate the data."
  [handle evidence]
  (when-not (and (map? evidence) (= 1 (:version evidence))
                 (map? (:fixtures evidence)))
    (throw (ex-info "Unsupported oracle evidence" {:rule :correspondence/evidence-schema})))
  (jing/materialize! handle evidence))


(defn- require-content!
  [handle address]
  (let [missing ::missing
        content (jing/get handle address missing)]
    (when (= missing content)
      (throw (ex-info "Correspondence input is missing"
                      {:rule :correspondence/missing-content :address address})))
    content))


(defn store-recipe!
  "Store a structurally valid recipe after its profile and coverage are present.
   This is a publication precondition, not interpretation of either object."
  [handle recipe]
  (when-not (recipe? recipe)
    (throw (ex-info "Malformed correspondence recipe" {:rule :correspondence/recipe})))
  (doseq [address [(:yang.correspondence.recipe/profile recipe)
                   (:yang.correspondence.recipe/coverage recipe)]]
    (require-content! handle address))
  (jing/materialize! handle recipe))


(defn store-record!
  "Store a structurally valid correspondence with its direct references present.
   Checks declared roots and markers, but not paths, transitive closures,
   derivations, or attribution."
  [handle record]
  (when-not (correspondence? record)
    (throw (ex-info "Malformed correspondence" {:rule :correspondence/record})))
  (let [recipe (require-content! handle (:yang.correspondence/recipe record))
        inputs (:yang.correspondence/inputs record)]
    (when-not (recipe? recipe)
      (throw (ex-info "Malformed correspondence recipe" {:rule :correspondence/recipe})))
    (let [evidence (require-content! handle (:yang.correspondence/evidence record))
          coverage (require-content! handle (:yang.correspondence.recipe/coverage recipe))]
      (when-not (and (= [:slice9-site-tables 1] (:yang.correspondence.recipe/evidence-contract recipe))
                     (map? evidence) (= 1 (:version evidence)) (map? (:fixtures evidence)))
        (throw (ex-info "Unsupported oracle evidence contract"
                        {:rule :correspondence/evidence-schema})))
      (when (and (or (empty? (:yang.correspondence/artifacts record))
                     (empty? (:yang.correspondence/instruction-provenance record)))
                 (not= :tree-only (:scope coverage)))
        (throw (ex-info "Empty image claims require tree-only coverage"
                        {:rule :correspondence/coverage}))))
    (doseq [[_ index] (:yang.correspondence.recipe/input-roles recipe)]
      (when-not (< index (count inputs))
        (throw (ex-info "Recipe input is absent" {:rule :correspondence/input-role :index index}))))
    (doseq [address (concat [(:yang.correspondence/evidence record)
                             (:yang.correspondence.recipe/profile recipe)
                             (:yang.correspondence.recipe/coverage recipe)
                             (:yang.correspondence/metadata record)]
                            inputs
                            (:yang.correspondence/roots record)
                            (:yang.correspondence/instruction-provenance record)
                            (mapcat (juxt :storage :derivation)
                                    (:yang.correspondence/artifacts record)))]
      (require-content! handle address))
    (let [metadata (require-content! handle (:yang.correspondence/metadata record))
          roots (set (:yang.correspondence/roots record))
          marker-counts (frequencies metadata)]
      (when-not (vector? metadata)
        (throw (ex-info "Malformed frontend metadata" {:rule :correspondence/metadata})))
      (doseq [[_ [origin root path]] (:yang.correspondence/origin-bindings record)]
        (when-not (and (contains? roots root)
                       (= 1 (get marker-counts [origin root path :yang/call-site 1])))
          (throw (ex-info "Binding has no declared root and unique marker"
                          {:rule :correspondence/binding-endpoint})))))
    (jing/materialize! handle record)))


(defn binding-coverage?
  "Check one compilation's site and marker relations against its row tree.
   This does not reconstruct the binding or independently derive site classes."
  [site-table origin tree metadata bindings]
  (and (map? site-table) (vm/occurrence-origin? origin)
       (map? tree) (vector? metadata) (vector? bindings)
       (every? #(and (vector? %) (= 5 (count %))) metadata)
       (distinct-vector? bindings binding?)
       (let [markers (filterv #(= :yang/call-site (nth % 3)) metadata)
             keys-of (mapv #(subvec % 0 3) markers)
             eligible (into #{} (map (fn [[root path]] [origin root path]))
                            (vm/application-occurrences tree))
             ids (mapv first bindings)
             targets (mapv second bindings)]
         (and (every? #(= 1 (nth % 4)) markers)
              (= (count keys-of) (count (set keys-of)))
              (= eligible (set keys-of))
              (= (count ids) (count (set ids)))
              (= (count targets) (count (set targets)))
              (= (set ids) (set (keys site-table)))
              (every? eligible targets)))))
