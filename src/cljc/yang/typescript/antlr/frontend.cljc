(ns yang.typescript.antlr.frontend
  "Install TypeScript's parser and two immutable lowering revisions."
  (:require [clojure.string :as str]
            [yang.clojure.prelude :as clj]
            [yang.frontend :as frontend]
            [yang.typescript.antlr.contract :as contract]
            [yang.typescript.antlr.lower :as lower]
            [yang.typescript.antlr.profiles :as profiles]
            [yang.typescript.antlr.render :as render]))


(def id :yang/typescript)


(defn- manifest [revision grammar {:keys [descriptor-address runtime-address]}]
  {:yang.frontend/id id
   :yang.frontend/spi 1
   :yang.frontend/revision (name revision)
   :yang.frontend/language :typescript
   :yang.frontend/grammar {:yang.grammar/package grammar
                           :yang.grammar/entries {:module "program"}
                           :yang.grammar/export-profile :yang.cst/v1
                           :yang.grammar/targets #{:java}}
   :yang.frontend/options-schema :segment/none
   :yang.frontend/lowering-profile descriptor-address
   :yang.frontend/runtime-profile runtime-address
   :yang.frontend/support-profile descriptor-address})


(defn- render-js [value]
  (let [{:js/keys [out uncaught]} (render/output value)]
    (str/join "\n" (cond-> out uncaught (conj (str "Uncaught " uncaught))))))


(defn- install-revision [catalog revision grammar parse-source]
  (let [profile (profiles/builtin revision)
        manifest (manifest revision grammar profile)]
    (frontend/install
      catalog manifest
      (cond-> {:parse (fn [source] (profiles/verify-builtin! revision) (parse-source source))
               :lower (fn [packet]
                        (let [{:keys [descriptor]} (profiles/verify-builtin! revision)]
                          (lower/lower-packet packet {:descriptor descriptor})))
               :derive (fn [packet ast] (profiles/derivation manifest profile packet ast))}
        (= revision contract/legacy-id) (assoc :render render-js)
        (= revision contract/clojure-id) (assoc :admit clj/admit)))))


(defn install
  "Add both revisions and select the persistent profile for ID-only
   requests. The composition supplies the parser that produces CST packets."
  [catalog grammar parse-source]
  (-> catalog
      (install-revision contract/legacy-id grammar parse-source)
      (install-revision contract/clojure-id grammar parse-source)
      (update :yang.frontend/preferred
              (fn [preference]
                (assoc (cond
                         (map? preference) preference
                         (vector? preference) (apply hash-map preference)
                         :else {})
                       id (name contract/clojure-id))))))
