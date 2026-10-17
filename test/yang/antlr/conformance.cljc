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
  "A packet's nodes in preorder: [rule span children] for a rule and
   [type text span synthetic?] for a token. The child edges and the synthetic
   flags are part of the shape, so a tree that differs only in them differs."
  [pk]
  (mapv (fn [n]
          (if (= :rule (:kind n))
            [(:rule n) (:span n) (:children n)]
            [(:type n) (:text n) (:span n) (boolean (:synthetic n))]))
        (:yang.cst/nodes pk)))


(defn check-ok
  "`source` parses to an ok packet that validates and conforms (invariants
   1-5); with `expected` the packet's shape equals it. Returns the packet."
  ([parse source] (check-ok parse source nil))
  ([parse source expected]
   (let [pk (parse source)]
     (is (= :yang.cst/ok (:yang.cst/outcome pk)) source)
     (is (some? (packet/validate! pk {:source source})) source)
     (is (= [] (packet/conform pk source)) source)
     (when expected
       (is (= expected (shape pk)) source))
     pk)))


(defn check-error
  "`source` yields a syntax-error packet with no nodes that validates and
   whose first error has the given `line`, `column` and `span` (invariant 6).
   ANTLR's line and column and the byte span are independent coordinates, so
   each is pinned on its own. Returns the packet."
  [parse source {:keys [line column span]}]
  (let [pk (parse source)
        e (first (:yang.cst/errors pk))]
    (testing source
      (is (= :yang.cst/syntax-error (:yang.cst/outcome pk)))
      (is (not (contains? pk :yang.cst/nodes)) "never a partial program")
      (is (not (contains? pk :yang.cst/root)))
      (is (true? (:yang.cst/complete pk)))
      (is (= :syntax-error (:kind e)))
      (is (string? (:message e)))
      (is (some? (packet/validate! pk {:source source})))
      (is (= [] (packet/conform pk source)))
      (when line (is (= line (:line e))))
      (when column (is (= column (:column e))))
      (when span (is (= span (:span e)))))
    pk))


(defn check-fixtures
  "Run each fixture {:source s :shape [...]} or {:source s :error {...}}."
  [parse fixtures]
  (doseq [{:keys [source shape error]} fixtures]
    (if error
      (check-error parse source error)
      (check-ok parse source shape))))


;; =============================================================================
;; The adversarial corpus: packets that must be rejected
;; =============================================================================

(defn- first-node
  [pk pred]
  (first (filter pred (:yang.cst/nodes pk))))


(defn- token-of
  [pk type]
  (first-node pk #(and (= :token (:kind %)) (= type (:type %)))))


(defn- edit
  [pk id f]
  (update-in pk [:yang.cst/nodes id] f))


(def mutations
  "Named mutations of a good packet for a source with a multi-byte string
   token; each yields a packet `validate!` and `conform` must refuse. Pair
   with `check-mutations`."
  [["duplicate child edge"
    (fn [pk]
      (let [r (first-node pk #(and (= :rule (:kind %)) (seq (:children %))))]
        (edit pk (:id r) #(update % :children (fn [k] (into [(first k)] k))))))]
   ["wrong token text"
    (fn [pk] (edit pk (:id (token-of pk "NAME")) #(assoc % :text "wrong")))]
   ["collapsed synthetic NAME"
    (fn [pk]
      (let [t (token-of pk "NAME")
            a (first (:span t))]
        (edit pk (:id t) #(assoc % :synthetic true, :text "", :span [a a]))))]
   ["span ending inside a multi-byte character"
    (fn [pk]
      (let [t (first-node pk #(and (= :token (:kind %))
                                   (= "STRING" (:type %))))
            a (first (:span t))]
        (edit pk (:id t) #(assoc % :span [a (+ a 2)]))))]
   ["children out of preorder"
    (fn [pk]
      (let [r (first-node pk #(and (= :rule (:kind %))
                                   (< 1 (count (:children %)))))]
        (edit pk (:id r) #(update % :children (comp vec reverse)))))]
   ["node unreachable from the root"
    (fn [pk]
      (let [end (count (:yang.cst/nodes pk))]
        (update pk :yang.cst/nodes conj
                {:id end, :kind :token, :type "NAME", :text "", :span [0 0]})))]
   ["span past the end of the source"
    (fn [pk] (edit pk (:id (token-of pk "NAME")) #(assoc % :span [0 99])))]
   ["blank grammar name" (fn [pk] (assoc pk :yang.cst/grammar ""))]
   ["rule name outside the grammar"
    (fn [pk] (edit pk 0 #(assoc % :rule "no_such_rule")))]
   ["real token overlapping the previous one"
    (fn [pk]
      (let [t (token-of pk "ASSIGN")]
        (edit pk (:id t) #(assoc % :span [0 (second (:span %))]))))]
   ["rule span not covering its children"
    (fn [pk] (edit pk 0 #(assoc % :span [0 1])))]
   ["error packet that still carries nodes"
    (fn [pk] (assoc pk :yang.cst/outcome :yang.cst/syntax-error))]
   ["EOF presented as real source"
    (fn [pk]
      (let [t (token-of pk "EOF")]
        (edit pk (:id t) #(dissoc % :synthetic))))]
   ["synthetic NEWLINE in the middle of the source"
    (fn [pk]
      (let [t (token-of pk "NEWLINE")
            a (first (:span t))]
        (edit pk (:id t) #(assoc % :synthetic true, :span [a a]))))]])


(defn- refused?
  [pk opts]
  (try (packet/validate! pk opts)
       false
       (catch #?(:cljd Object
                 :clj clojure.lang.ExceptionInfo
                 :cljs ExceptionInfo)
              _
         true)))


(defn check-mutations
  "`source` parses to a packet that conforms; every mutation of it is then
   refused by both `validate!` (with the source) and `conform`."
  [parse source]
  (let [pk (parse source)
        opts {:source source,
              :rule-names (set (keep :rule (:yang.cst/nodes pk)))}]
    (is (= [] (packet/violations pk opts)) "the unmutated packet conforms")
    (doseq [[label f] mutations
            :let [m (f pk)]]
      (testing label
        (is (not= pk m) "the mutation changed the packet")
        (is (seq (packet/violations m opts)))
        (is (seq (packet/conform m source (select-keys opts [:rule-names]))))
        (is (refused? m opts))))))
