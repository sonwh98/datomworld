(ns yang.io
  "Platform-specific I/O for Yang compiler.

  Provides functions to compile Clojure source files to Universal AST."
  (:require
    #?@(:clj [[clojure.java.io :as io]]
        :cljs [["fs" :as fs]])
    [yang.clojure :as yang]))


(defn- read-forms
  [source file-name]
  (yang/read-forms source (when file-name {:file file-name})))


(defn read-source
  "Read Clojure source code from a file.
  Returns a sequence of forms."
  [file-path]
  #?(:clj (try (read-forms (slurp (io/file file-path)) file-path)
                (catch Exception e
                  (throw (ex-info "Failed to read source file"
                                  {:file file-path, :error (.getMessage e)}
                                  e))))
     :cljs (try (let [content (.readFileSync fs file-path "utf8")
                      forms (read-forms content file-path)]
                  forms)
                (catch js/Error e
                  (throw (ex-info "Failed to read source file"
                                  {:file file-path, :error (.-message e)}
                                  e))))))


(defn compile-file
  "Compile a Clojure source file to Universal AST.
  Returns a vector of compiled AST nodes, one for each top-level form."
  [file-path]
  (let [forms (read-source file-path)] (mapv yang/compile forms)))


(defn compile-string
  "Compile a string containing Clojure code to Universal AST.
  Returns a vector of compiled AST nodes, one for each form."
  [source-str]
  (let [forms (read-forms source-str "<string>")]
    (mapv yang/compile forms)))


(defn write-ast
  "Write compiled AST to a file as EDN.
  The AST is a map-based structure that can be serialized to EDN."
  [ast-nodes file-path]
  #?(:clj (spit file-path (pr-str ast-nodes))
     :cljs (.writeFileSync fs file-path (pr-str ast-nodes) "utf8")))


(defn compile-and-save
  "Compile a source file and save the AST to an output file.
  Returns the compiled AST."
  [source-path output-path]
  (let [ast (compile-file source-path)]
    (write-ast ast output-path)
    ast))
