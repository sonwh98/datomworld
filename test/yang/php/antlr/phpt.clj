(ns yang.php.antlr.phpt
  "php-src `.phpt` handling for the differential selection (docs/design/
   yang.php.md section 4.6 source 1): parse a test file, classify its source
   against the supported surface, and match `--EXPECTF--` patterns. The
   selection script `src/dev/yang/php/phpt.clj` writes
   `test/yang/php/antlr/phpt_selection.edn` from a pinned php-src checkout;
   `e2e_test` runs that list."
  (:require
    [clojure.string :as str]
    [yang.php.antlr.builtins :as builtins]))


(def selection-patterns
  "Path predicates (regexes over the path below the php-src root) of the
   directories and name families section 4.6 lists."
  [#"tests/lang/foreachLoop\."
   #"tests/lang/passByReference_"
   #"tests/lang/engine_assignExecutionOrder_"
   #"Zend/tests/bug[^/]*\.phpt$"
   #"Zend/tests/list/"
   #"Zend/tests/foreach_"
   #"Zend/tests/unset_"
   #"Zend/tests/temporary_cleaning_"
   #"Zend/tests/named_params/"
   #"Zend/tests/(new_|constructor_|destructor_|objects_0)"
   #"tests/classes/(ctor_|constructor_|destructor_)"
   #"Zend/tests/type_declarations/typed_properties_"
   #"Zend/tests/readonly_props/"
   #"Zend/tests/gc_"
   #"ext/standard/tests/array/(current|next|reset|end)[^/]*\.phpt$"])


(defn selected-path?
  [path]
  (boolean (some #(re-find % path) selection-patterns)))


(defn parse-phpt
  "The sections of `.phpt` text as `{\"TEST\" \"..\" \"FILE\" \"..\" ...}`."
  [text]
  (let [lines (str/split-lines text)]
    (loop [ls lines, current nil, acc {}]
      (if-let [l (first ls)]
        (if-let [[_ name] (re-matches #"--([A-Z_]+)--\s*" l)]
          (recur (rest ls) name (assoc acc name []))
          (recur (rest ls) current (if current (update acc current conj l) acc)))
        (into {} (map (fn [[k v]] [k (str/join "\n" v)])) acc)))))


(def ^:private always-skip
  [#"\bpreg_" #"\bgoto\b" #"\bextension_loaded\b" #"\bdl\(" #"\beval\b" #"\binclude(_once)?\b"
   #"\brequire(_once)?\b" #"\bdeclare\s*\(" #"\bnamespace\b" #"\buse\s+\\" #"\byield\b"
   #"\bfunction\s*&" #"\$\$" #"\bextract\(" #"\bcompact\(" #"\bget_defined_vars\b"])


(def ^:private p4-markers
  [#"\bclass\b" #"\binterface\b" #"\btrait\b" #"\benum\b" #"\bnew\b" #"->" #"::" #"\bclone\b"
   #"\bstatic::" #"\binstanceof\b"])


(def ^:private language-words
  #{"if" "while" "for" "foreach" "switch" "array" "list" "isset" "unset" "empty" "echo" "print"
    "function" "fn" "match" "catch" "return" "exit" "die" "use" "elseif" "and" "or"})


(defn called-names
  "The lower-cased identifiers followed by `(` in `source`, minus language words."
  [source]
  (->> (re-seq #"(?<![\w$>:])([A-Za-z_][A-Za-z0-9_]*)\s*\(" source)
       (map (comp str/lower-case second))
       (remove language-words)
       set))


(defn declared-names
  [source]
  (set (map (comp str/lower-case second)
            (re-seq #"function\s+&?\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(" source))))


(defn classify
  "`{:status :select|:skip, :needs :p4|nil, :reason r}` for `sections`: the
   source must use only the supported surface (unit functions, the prelude's
   builtins and the catalogue that the lowering warns about)."
  [sections]
  (let [src (get sections "FILE" "")
        expected (or (get sections "EXPECT") (get sections "EXPECTF"))]
    (cond
      (nil? expected) {:status :skip, :reason :no-expectation}
      (contains? sections "SKIPIF") {:status :skip, :reason :skipif}
      (some #(re-find % src) always-skip) {:status :skip, :reason :unsupported-surface}
      (some #(re-find % src) p4-markers) {:status :select, :needs :p4}
      :else
      (let [unknown (remove (into (declared-names src)
                                  (concat (keys builtins/supported) builtins/catalogue))
                            (called-names src))]
        (if (seq unknown)
          {:status :skip, :reason :unknown-functions, :functions (vec (sort unknown))}
          {:status :select})))))


(defn expectf->regex
  "A regex for an `--EXPECTF--` body: `%d` `%i` `%s` `%f` `%e` `%w` `%c` `%a`
   and `%%`; everything else is literal."
  [expectf]
  (let [quote-lit #(java.util.regex.Pattern/quote %)
        parts (str/split expectf #"(?=%[disfewca%])|(?<=%[disfewca%])")
        piece (fn [p]
                (case p
                  "%d" "[+-]?\\d+"
                  "%i" "[+-]?\\d+"
                  "%s" "[^\\r\\n]+"
                  "%f" "[+-]?\\.?\\d+\\.?\\d*(?:[Ee][+-]?\\d+)?"
                  "%e" "[\\\\/]"
                  "%w" "\\s*"
                  "%c" "."
                  "%a" ".+"
                  "%%" "%"
                  (quote-lit p)))]
    (re-pattern (str "(?s)" (apply str (map piece parts))))))


(defn matches-expect?
  "True when `actual` equals the `--EXPECT--` body, or matches the
   `--EXPECTF--` one, ignoring trailing whitespace."
  [sections actual]
  (let [a (str/trimr actual)]
    (if-let [e (get sections "EXPECT")]
      (= (str/trimr e) a)
      (boolean (when-let [f (get sections "EXPECTF")]
                 (re-matches (expectf->regex (str/trimr f)) a))))))
