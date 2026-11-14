(ns yang.php.antlr.eval-transport
  "Validation of the portable, sealed eval compiler request.")


(def request-fields
  #{:yang.compile/request :yang.compile/unit :yang.compile/frontend-revision
    :yang.compile/compiler-revision :yang.compile/goal :yang.compile/provenance
    :yang.compile/options :yang.php/source})


(defn portable-data?
  "True for the data forms accepted by this request protocol."
  [x]
  (cond
    (or (nil? x) (boolean? x) (string? x) (number? x)
        (keyword? x) (symbol? x)) true
    (map? x) (every? (fn [[k v]] (and (portable-data? k) (portable-data? v))) x)
    (or (vector? x) (set? x) (list? x)) (every? portable-data? x)
    :else false))


(defn request-defect
  "Return a protocol defect or nil. Unknown fields are refused so that
   caller runtime state cannot accidentally be included at the top level."
  [request]
  (cond
    (not (map? request)) :request-shape
    (some #(not (contains? request %)) request-fields) :missing-field
    (some #(not (contains? request-fields %)) (keys request)) :unknown-field
    (not= :eval (:yang.compile/goal request)) :wrong-goal
    (not (string? (:yang.php/source request))) :source-shape
    (not (map? (:yang.compile/options request))) :options-shape
    (not (portable-data? request)) :non-portable-data
    :else nil))
