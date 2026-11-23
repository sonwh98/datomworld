(ns yang.clojure.core-module
  "Cell-free publication and task-local initialization of the Clojure library."
  (:require [yang.clojure.core-impl :as impl]
            [yang.clojure.prelude :as clj]
            [yang.core.prelude :as core]
            [yang.core.prelude-admission :as admission]
            [yang.core.uast :as u]))


(def module-name 'clojure.core)

(def ^:private table-key 'clojure.core.rt/exports)
(def ^:private uninitialized :clojure.core/uninitialized)


(defn accessor-name [name]
  (symbol (str module-name) (str name)))


(defn entry-key [name]
  (symbol "clojure.core.rt" (str name)))


(def source-names
  ;; The Clojure lowerer already emits these predicates as VM primitives.
  ;; The facade also offers cell accessors for descriptor projections.
  (disj (set impl/public-names) 'empty? 'nil?))


(defn referenced-exports [free-names]
  (into #{}
        (keep (fn [sym]
                (when (= "clojure.core" (namespace sym))
                  (let [name (symbol (name sym))]
                    (when (contains? source-names name) name)))))
        free-names))


(defn- facade-definitions [names]
  (let [populate
        (reduce (fn [table name]
                  (list 'clj/prim-assoc table (list 'quote name)
                        (list 'clj/make-source-callable (list 'quote (entry-key name))
                              {:clj/type :function} (impl/callable-entry name))))
                {} names)]
    (into [[table-key uninitialized]
           ['clojure.core/init!
            (list 'fn []
                  (list 'if (list 'data/content= table-key uninitialized)
                        (core/define table-key populate)
                        nil))]
           ['clojure.core/export '(fn [name] (clj/prim-get clojure.core.rt/exports name))]]
          (map (fn [name]
                 [(accessor-name name) (list 'fn [] (list 'clojure.core/export (list 'quote name)))]))
          names)))


(def ^:private definitions (facade-definitions impl/public-names))


(def module-exports (core/exports (str module-name) definitions))


(defn bundled-uast
  "Install the same facade over the subset of exports the bundled unit reads."
  [names]
  (let [names (filterv names impl/public-names)]
    (u/seq-nodes [(impl/bundled-uast names)
                  (core/balanced-definitions->uast (facade-definitions names))
                  (u/app (u/v 'clojure.core/init!))])))


(def module-uast
  (let [nodes (mapv (fn [[name body]] (u/def! name (u/sexp->uast body)))
                    (core/module-definitions (str module-name) uninitialized [] definitions))
        [facade accessors] (split-at 3 nodes)
        requires (mapv u/sexp->uast '[(require 'clj) (require 'clojure.core.impl)])]
    ;; The table and export reader dominate the accessor bodies. Load the
    ;; dependencies before installing accessors named after VM primitives.
    (u/annotate-call-sites (core/wide-app (concat facade requires accessors)))))


(def ^:private free-names (core/free-name-vec module-uast))


(defn module-spec [registry clj-address impl-address]
  (let [requires {clj/module-name clj-address impl/module-name impl-address}]
    (admission/module-spec
      {:module-name module-name :ast module-uast :exports module-exports
       :requires requires :free-names free-names
       :skip? #(contains? requires (some-> % namespace symbol))
       :registry registry :message "clojure.core reads an undeclared name"
       :refusal-ns "yang.clojure"})))
