(ns yin.vm.linker.layering-test
  "The linker's layering rule (collab design admission-in-linker r2,
   section 1): linker semantics is what a plain Clojure consumer needs,
   and the REPL is a consumer of it, never the reverse.  No namespace
   under src/cljc/yin/vm, src/cljc/yang or src/clj/dao requires a
   `yin.repl` namespace.  This gate reads the `ns` form of every source
   file there (tools.reader, reader conditionals preserved, so every
   branch is read) and fails on any `yin.repl` or `yin.repl.*` symbol in
   a `:require`, `:require-macros` or `:use` clause, and on any file it
   cannot read.  Docstrings and datum vocabulary such as `:yin.repl/root`
   are not requires and do not count.  JVM only: it reads source files."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as rt]))


(def ^:private roots
  ["src/cljc/yin/vm" "src/cljc/yang" "src/clj/dao"])


(def ^:private require-clauses
  #{:require :require-macros :use})


(defn- repl-ns?
  [x]
  (and (symbol? x)
       (let [s (str x)]
         (or (= "yin.repl" s) (.startsWith s "yin.repl.")))))


(defn- symbols-in
  [form]
  (cond
    (reader-conditional? form) (symbols-in (:form form))
    (symbol? form) [form]
    (coll? form) (mapcat symbols-in form)
    :else nil))


(defn repl-requires
  "The `yin.repl` namespaces the `ns` form of `text` requires, as a
   sorted vector, or `[:unreadable message]` when its first form cannot
   be read."
  [text]
  (try
    (binding [reader/*default-data-reader-fn* (fn [_ v] v)
              *ns* (create-ns 'yin.vm.linker.layering-reader)]
      (let [form (reader/read {:read-cond :preserve, :eof nil}
                              (rt/indexing-push-back-reader text))]
        (->> (when (and (seq? form) (= 'ns (first form))) form)
             (filter #(and (seq? %) (contains? require-clauses (first %))))
             (mapcat symbols-in)
             (filter repl-ns?)
             distinct
             sort
             vec)))
    (catch Exception e
      [:unreadable (ex-message e)])))


(defn- source-files
  []
  (->> roots
       (mapcat #(file-seq (io/file %)))
       (filter #(.isFile ^java.io.File %))
       (map #(.getPath ^java.io.File %))
       (filter #(re-find #"\.clj[csd]?$" %))
       sort))


(defn violations
  "file -> the `yin.repl` namespaces it requires, for every file under
   `roots` that requires one."
  []
  (into (sorted-map)
        (keep (fn [path]
                (let [found (repl-requires (slurp path))]
                  (when (seq found) [path found]))))
        (source-files)))


(deftest detector-sees-every-branch-test
  (testing "a require in any reader-conditional branch is found"
    (is (= '[yin.repl yin.repl.link]
           (repl-requires
             (str "(ns a.b (:require [dao.stream :as s]"
                  " #?(:cljd [yin.repl.link :as l] :clj [x.y :as y])"
                  " #?@(:cljs [[yin.repl :as r]])))")))))
  (testing "datum vocabulary and docstrings are not requires"
    (is (= []
           (repl-requires
             (str "(ns a.b \"yin.repl.link\" (:require [dao.stream :as s]))"
                  " (def k :yin.repl/root)"))))))


(deftest linker-requires-no-repl-test
  (testing (str "no namespace under " roots " requires yin.repl")
    (is (seq (source-files)))
    (is (= {} (violations)))))
