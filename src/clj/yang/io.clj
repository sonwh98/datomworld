(ns yang.io
  "Platform-specific I/O for Yang compiler on JVM.

  Provides functions to compile Clojure source files to Universal AST."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [yang.clojure :as yang]))


(defn read-source
  "Read Clojure source code from a file.
  Returns a sequence of forms."
  [file-path]
  (with-open [r (java.io.PushbackReader. (io/reader file-path))]
    (loop [forms []]
      (let [form (try (edn/read {:eof ::eof} r)
                      (catch Exception e
                        (throw (ex-info "Failed to read source file"
                                        {:file file-path,
                                         :error (.getMessage e)}
                                        e))))]
        (if (= form ::eof) forms (recur (conj forms form)))))))


(defn compile-file
  "Compile a Clojure source file to Universal AST.
  Returns a vector of compiled AST nodes, one for each top-level form."
  [file-path]
  (let [forms (read-source file-path)] (mapv yang/compile forms)))


(defn compile-string
  "Compile a string containing Clojure code to Universal AST.
  Returns a vector of compiled AST nodes, one for each form."
  [source-str]
  (let [forms (edn/read-string (str "[" source-str "]"))]
    (mapv yang/compile forms)))


(defn write-ast
  "Write compiled AST to a file as EDN.
  The AST is a map-based structure that can be serialized to EDN."
  [ast-nodes file-path]
  (spit file-path (pr-str ast-nodes)))


(defn compile-and-save
  "Compile a source file and save the AST to an output file.
  Returns the compiled AST."
  [source-path output-path]
  (let [ast (compile-file source-path)]
    (write-ast ast output-path)
    ast))
