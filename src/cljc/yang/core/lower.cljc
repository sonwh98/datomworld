(ns yang.core.lower
  "Lowering helpers shared by every language's CST-to-Universal-AST lowering
   (docs/design/yang.typescript.md §17, lift L2): the CST accessors over a
   lowering context `{:pk packet ...}`, the generated-name supply, safepoint
   site marks, and the diagnostic builders. A language supplies its
   diagnostic namespace (a string such as \"yang.python.antlr\"), its name
   for messages and its table of unsupported rules."
  (:require
    [yang.antlr.packet :as p]
    [yang.core.uast :as u]))


;; =============================================================================
;; Diagnostics
;; =============================================================================

(defn unsupported!
  "Throw the `<diagnostic-ns>/unsupported` diagnostic for rule node `n`
   naming `construct`; `language` is the name the message uses."
  [diagnostic-ns language n construct]
  (throw (ex-info (str "Unsupported " language " construct: " construct)
                  {(keyword diagnostic-ns "diagnostic")
                   (keyword diagnostic-ns "unsupported"),
                   :rule (:rule n),
                   :construct construct,
                   :span (:span n)})))


(defn syntax!
  "Throw the `<diagnostic-ns>/syntax` diagnostic for rule node `n`."
  [diagnostic-ns n message]
  (throw (ex-info message
                  {(keyword diagnostic-ns "diagnostic")
                   (keyword diagnostic-ns "syntax"),
                   :rule (:rule n),
                   :span (:span n)})))


(defn default-arm
  "The arm of a rule with no lowering: `unsupported!` when `refusals` (rule
   name to construct) names it, otherwise `<diagnostic-ns>/unhandled-rule`."
  [diagnostic-ns language refusals n]
  (if-let [construct (get refusals (:rule n))]
    (unsupported! diagnostic-ns language n construct)
    (throw (ex-info (str "Unhandled grammar rule: " (:rule n))
                    {(keyword diagnostic-ns "diagnostic")
                     (keyword diagnostic-ns "unhandled-rule"),
                     :rule (:rule n),
                     :span (:span n)}))))


;; =============================================================================
;; Small helpers
;; =============================================================================

(defn gen
  [prefix n]
  (symbol (str "%" prefix (:id n))))


(defn kids
  [ctx n]
  (p/children (:pk ctx) n))


(defn rules
  [ctx n rule-name]
  (p/child-rules (:pk ctx) n rule-name))


(defn rule-kids
  [ctx n]
  (filterv p/rule? (kids ctx n)))


(defn site
  "`node` marked as a safepoint site of `kind` (`:loop`, `:call`, `:line`),
   a keyword or a vector `[kind datum ...]`. The mark is map metadata, so it never enters a canonical row: projection
   puts it in the frontend-metadata side table, where `yang.safepoint`
   finds it."
  [kind node]
  (vary-meta node assoc :yang/site kind))


(defn app*
  [op-sym & args]
  (apply u/app (u/v op-sym) args))


(defn contains-rule?
  "True when `rule-name` occurs below `n` without crossing any `stop` rule."
  [ctx n rule-name stop]
  (boolean
    (some (fn [k]
            (and (p/rule? k)
                 (or (= rule-name (:rule k))
                     (and (not (contains? stop (:rule k)))
                          (contains-rule? ctx k rule-name stop)))))
          (kids ctx n))))
