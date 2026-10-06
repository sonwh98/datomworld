(ns yang.python.antlr.int-ops-fixtures
  "CPython 3.9.6 operator rows, and the float text rows of integer
   module version 4 and the renderer. Reading these corpora never writes
   files."
  (:require
    [clojure.string :as str]
    [dao.jing.cbor :as cbor]
    [dao.jing.cbor-fixtures :as cfx]
    [yin.vm.integer :as integer]))


(def path "test/resources/yang/python/int-ops-v1.txt")


(def float-text-path
  "CPython 3.9.6 `repr(float)` and `float(str)` rows."
  "test/resources/yang/python/float-text-v1.txt")


(def ^:private exact
  (integer/integer-module
    {::integer/max-bits 100000 ::integer/max-digits 4300}))


(declare code-points->text)


(defn value
  "A token as bool, tagged float, exception or integer/tuple descriptor.
   Integers use hex reconstruction so no big carrier enters an AST row."
  [s]
  (cond
    (= s "_") nil
    (= s "True") true
    (= s "False") false
    (str/starts-with? s "!") s
    (str/starts-with? s "s:")
    {:py/str (code-points->text (subs s 2))}
    (str/starts-with? s "t:")
    {:tuple (mapv value (str/split (subs s 2) #","))}
    (str/starts-with? s "f:")
    {:py/float (cbor/float64-from-bits (subs s 2))}
    :else {:int ((get exact 'format) ((get exact 'parse) s) 16)}))


(defn code-points->text
  "Comma-separated hex code points, astral ones included, as a string."
  [s]
  (let [units (mapv (fn [h]
                      (let [u ((get exact 'parse) h 16)]
                        (when-not (<= u 0x10ffff)
                          (throw (ex-info "invalid code point" {:cp h})))
                        u))
                    (remove str/blank? (str/split s #",")))]
    #?(:cljd (.toString (reduce (fn [sb u] (.writeCharCode sb u) sb)
                                (StringBuffer)
                                units))
       :clj (apply str (map #(String. (Character/toChars (int %))) units))
       :cljs (apply str (map #(.fromCodePoint js/String %) units)))))


(defn- float-text-row
  "`[op operand nil expected]`: `repr_float` maps a tagged float to its
   text; `float_str` maps a string to a tagged float, or to
   `{:error class, :message text}`."
  [line]
  (let [[op a _ expected message :as fields] (str/split line #"\t")]
    (when-not (or (= 4 (count fields))
                  (and (= 5 (count fields)) (str/starts-with? expected "!")))
      (throw (ex-info "bad float text row" {:line line})))
    (case op
      "repr_float" [op (value a) nil expected]
      "float_str" [op (code-points->text a) nil
                   (if message
                     {:error (subs expected 1), :message message}
                     (value expected))]
      (throw (ex-info "bad float text op" {:line line})))))


(defn- conversion-row
  [line]
  (let [[op a b expected message :as fields] (str/split line #"\t")]
    (when-not (or (= 4 (count fields))
                  (and (= 5 (count fields))
                       (str/starts-with? expected "!")))
      (throw (ex-info "bad conversion row" {:line line})))
    [op (value a) (value b)
     (if message {:error (subs expected 1), :message message}
         (value expected))]))


(defn parse
  "Parse rows, ignoring all blank lines (including Dart's trailing one)."
  [text]
  (let [[magic version & rows] (remove str/blank? (str/split-lines text))]
    (when-not (and (contains? #{"int-ops-v1" "int-ops-v2" "int-ops-v3"
                                "float-text-v1" "int-conv-v1"}
                              magic)
                   (= "CPython 3.9.6" version))
      (throw (ex-info "wrong integer operator corpus" {:magic magic})))
    (cond
      (= "float-text-v1" magic) (mapv float-text-row rows)
      (= "int-conv-v1" magic) (mapv conversion-row rows)
      :else (mapv (fn [line]
                    (let [[op a b expected :as fields] (str/split line #"\t")]
                      (when-not (= 4 (count fields))
                        (throw (ex-info "bad operator row" {:line line})))
                      [op (value a) (value b) (value expected)])) rows))))


(defn read-file
  ([] (read-file path))
  ([path] (parse (cfx/read-path path))))
