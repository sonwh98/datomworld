(ns yang.python.antlr.int-ops-fixtures
  "CPython 3.9.6 operator rows. Reading this corpus never writes files."
  (:require
    [clojure.string :as str]
    [dao.jing.cbor :as cbor]
    [dao.jing.cbor-fixtures :as cfx]
    [yin.vm.integer :as integer]))


(def path "test/resources/yang/python/int-ops-v1.txt")


(def ^:private exact
  (integer/integer-module
    {::integer/max-bits 100000 ::integer/max-digits 4300}))


(defn value
  "A token as bool, tagged float, exception or integer/tuple descriptor.
   Integers use hex reconstruction so no big carrier enters an AST row."
  [s]
  (cond
    (= s "_") nil
    (= s "True") true
    (= s "False") false
    (str/starts-with? s "!") s
    (str/starts-with? s "t:")
    {:tuple (mapv value (str/split (subs s 2) #","))}
    (str/starts-with? s "f:")
    {:py/float (cbor/float64-from-bits (subs s 2))}
    :else {:int ((get exact 'format) ((get exact 'parse) s) 16)}))


(defn parse
  "Parse rows, ignoring all blank lines (including Dart's trailing one)."
  [text]
  (let [[magic version & rows] (remove str/blank? (str/split-lines text))]
    (when-not (and (contains? #{"int-ops-v1" "int-ops-v2"} magic)
                   (= "CPython 3.9.6" version))
      (throw (ex-info "wrong integer operator corpus" {:magic magic})))
    (mapv (fn [line]
            (let [[op a b expected :as fields] (str/split line #"\t")]
              (when-not (= 4 (count fields))
                (throw (ex-info "bad operator row" {:line line})))
              [op (value a) (value b) (value expected)])) rows)))


(defn read-file
  ([] (read-file path))
  ([path] (parse (cfx/read-path path))))
