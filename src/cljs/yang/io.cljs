(ns yang.io
  "Platform-specific I/O for Yang compiler on Node.js.

  Provides functions to compile Clojure source files to Universal AST."
  (:require [yang.core :as yang]
            [cljs.reader :as reader]))

(def fs (js/require "fs"))

(defn read-source
  "Read Clojure source code from a file.
  Returns a sequence of forms."
  [file-path]
  (try
    (let [content (.readFileSync fs file-path "utf8")
          forms (reader/read-string (str "[" content "]"))]
      forms)
    (catch js/Error e
      (throw (ex-info "Failed to read source file"
                      {:file file-path
                       :error (.-message e)}
                      e)))))

(defn compile-file
  "Compile a Clojure source file to Universal AST.
  Returns a vector of compiled AST nodes, one for each top-level form."
  [file-path]
  (let [forms (read-source file-path)]
    (mapv yang/compile forms)))

(defn compile-string
  "Compile a string containing Clojure code to Universal AST.
  Returns a vector of compiled AST nodes, one for each form."
  [source-str]
  (let [forms (reader/read-string (str "[" source-str "]"))]
    (mapv yang/compile forms)))

(defn write-ast
  "Write compiled AST to a file as EDN.
  The AST is a map-based structure that can be serialized to EDN."
  [ast-nodes file-path]
  (.writeFileSync fs file-path (pr-str ast-nodes) "utf8"))

(defn compile-and-save
  "Compile a source file and save the AST to an output file.
  Returns the compiled AST."
  [source-path output-path]
  (let [ast (compile-file source-path)]
    (write-ast ast output-path)
    ast))
