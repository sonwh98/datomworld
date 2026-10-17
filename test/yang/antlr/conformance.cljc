(ns yang.antlr.conformance
  "The per-target, language-free exporter oracle (docs/design/yang.antlr.md
   §5.5): a language's fixtures are run against the exporter of the running
   host, JVM, Node or Dart, and every one must come out the same. A fixture is
   a source string and either its expected `shape` or the error it must
   produce. The goldens are the JVM's; they are the cross-target oracle."
  (:require
    [clojure.test :refer [is testing]]
    [yang.antlr.packet :as packet]))


(defn shape
  "A packet's nodes as [rule span] and [type text span] entries in preorder."
  [pk]
  (mapv (fn [n]
          (if (= :rule (:kind n))
            [(:rule n) (:span n)]
            [(:type n) (:text n) (:span n)]))
        (:yang.cst/nodes pk)))


(defn check-ok
  "`source` parses to an ok packet that validates and conforms (invariants
   1-5); with `expected` the packet's shape equals it. Returns the packet."
  ([parse source] (check-ok parse source nil))
  ([parse source expected]
   (let [pk (parse source)]
     (is (= :yang.cst/ok (:yang.cst/outcome pk)) source)
     (is (some? (packet/validate! pk)) source)
     (is (= [] (packet/conform pk source)) source)
     (when expected
       (is (= expected (shape pk)) source))
     pk)))


(defn check-error
  "`source` yields a syntax-error packet with no nodes whose first error has
   the given `line` and `span` (invariant 6). Returns the packet."
  [parse source {:keys [line span]}]
  (let [pk (parse source)
        e (first (:yang.cst/errors pk))]
    (testing source
      (is (= :yang.cst/syntax-error (:yang.cst/outcome pk)))
      (is (not (contains? pk :yang.cst/nodes)) "never a partial program")
      (is (not (contains? pk :yang.cst/root)))
      (is (true? (:yang.cst/complete pk)))
      (is (= :syntax-error (:kind e)))
      (is (string? (:message e)))
      (when line (is (= line (:line e))))
      (when span (is (= span (:span e)))))
    pk))


(defn check-fixtures
  "Run each fixture {:source s :shape [...]} or {:source s :error {...}}."
  [parse fixtures]
  (doseq [{:keys [source shape error]} fixtures]
    (if error
      (check-error parse source error)
      (check-ok parse source shape))))
