(ns yin.vm.linker.layering-test
  "The linker's layering rule (collab design admission-in-linker r2,
   section 1): linker semantics is what a plain Clojure consumer needs,
   and the REPL is a consumer of it, never the reverse.  No namespace
   under src/cljc/yin/vm, src/cljc/yang or src/clj/dao requires a
   `yin.repl` namespace.  This gate reads the `ns` form of every source
   file there (tools.reader, reader conditionals preserved, so every
   branch is read, whether inside a clause or around whole clauses) and
   fails on any `yin.repl` or `yin.repl.*` symbol in a `:require`,
   `:require-macros` or `:use` clause, on any file it cannot read, on
   any file whose first form is not `ns` (empty files included), and on
   a root that is missing or holds no source file.  Docstrings and datum
   vocabulary such as `:yin.repl/root` are not requires and do not
   count.  JVM only: it reads source files."
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


(defn- clauses
  "The `ns` body elements with every reader conditional opened: each
   branch of a `#?` is one element, each branch of a `#?@` a sequence
   of elements, recursively, whatever the host."
  [body]
  (mapcat (fn [x]
            (if (reader-conditional? x)
              (let [branches (take-nth 2 (rest (:form x)))]
                (clauses (if (:splicing? x) (apply concat branches) branches)))
              [x]))
          body))


(defn repl-requires
  "The `yin.repl` namespaces the `ns` form of `text` requires, as a
   sorted vector; `[:unreadable message]` when its first form cannot be
   read; `[:no-ns-form]` when its first form is not an `ns` form, which
   includes an empty file (every source file under `roots` is a
   namespace)."
  [text]
  (try
    (binding [reader/*default-data-reader-fn* (fn [_ v] v)
              *ns* (create-ns 'yin.vm.linker.layering-reader)]
      (let [eof (Object.)
            form (reader/read {:read-cond :preserve, :eof eof}
                              (rt/indexing-push-back-reader text))]
        (if (and (seq? form) (= 'ns (first form)))
          (->> (clauses (rest form))
               (filter #(and (seq? %) (contains? require-clauses (first %))))
               (mapcat symbols-in)
               (filter repl-ns?)
               distinct
               sort
               vec)
          [:no-ns-form])))
    (catch Exception e
      [:unreadable (ex-message e)])))


(defn- source-files
  ([] (mapcat source-files roots))
  ([root]
   (->> (file-seq (io/file root))
        (filter #(.isFile ^java.io.File %))
        (map #(.getPath ^java.io.File %))
        (filter #(re-find #"\.clj[csd]?$" %))
        sort)))


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
  (testing "#? wrapping a whole clause is read"
    (is (= '[yin.repl.store]
           (repl-requires
             "(ns a.b #?(:clj (:require [yin.repl.store :as store])))"))))
  (testing "#?@ wrapping whole clauses is read"
    (is (= '[yin.repl yin.repl.link]
           (repl-requires
             (str "(ns a.b #?@(:clj [(:require [dao.stream :as s])"
                  " (:use [yin.repl.link])]"
                  " :cljs [(:require-macros [yin.repl :as r])]))")))))
  (testing "a violation only in an inactive host branch is found"
    (is (= '[yin.repl.store]
           (repl-requires
             (str "(ns a.b #?(:clj (:require [dao.stream :as s])"
                  " :cljd (:require [yin.repl.store :as store])))"))))
    (is (= '[yin.repl]
           (repl-requires
             (str "(ns a.b #?(:cljs (:require [dao.stream :as s])"
                  " :default #?@(:clj [] :cljd [(:require [yin.repl])])))")))))
  (testing "a clean namespace with whole-clause conditionals stays clean"
    (is (= []
           (repl-requires
             (str "(ns a.b #?(:clj (:require [dao.stream :as s])"
                  " :cljd (:require [yin.vm.linker :as l]))"
                  " #?@(:clj [(:import (java.io File))]"
                  " :cljs [(:require [yin.repl-like :as x])]))")))))
  (testing "a file whose first form is not ns is an inspection failure"
    (is (= :no-ns-form (first (repl-requires "(def x 1) (ns a.b)"))))
    (is (= :no-ns-form (first (repl-requires ""))))
    (is (= :no-ns-form (first (repl-requires ";; only a comment\n")))))
  (testing "datum vocabulary and docstrings are not requires"
    (is (= []
           (repl-requires
             (str "(ns a.b \"yin.repl.link\" (:require [dao.stream :as s]))"
                  " (def k :yin.repl/root)"))))))


(deftest every-root-is-inspected-test
  (doseq [root roots]
    (testing (str root " exists and holds source files")
      (is (.isDirectory (io/file root)))
      (is (seq (source-files root))))))


(deftest linker-requires-no-repl-test
  (testing (str "no namespace under " roots " requires yin.repl")
    (is (= {} (violations)))))


(deftest portable-evaluator-and-lower-have-no-fetch-or-lease-dependencies
  (doseq [path (filter #(or (= % "src/cljc/yin/vm/engine.cljc")
                            (= % "src/cljc/yin/vm/semantic.cljc")
                            (.startsWith % "src/cljc/yin/vm/semantic/")
                            (= % "src/cljc/yin/vm/ucf/rows.cljc"))
                       (source-files "src/cljc/yin/vm"))]
    (let [form (reader/read {:read-cond :preserve}
                            (rt/indexing-push-back-reader (slurp path)))
          dependencies (->> (clauses (rest form))
                            (filter #(and (seq? %) (require-clauses (first %))))
                            (mapcat symbols-in))]
      (is (not-any? #{'yin.vm.linker.interpreter 'yin.vm.linker.dht 'dao.lease} dependencies)
          path))))
