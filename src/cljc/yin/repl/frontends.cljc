(ns yin.repl.frontends
  "The REPL's standard frontend catalog (docs/design/yang.antlr.md section
   8.5.6, slice F2): the three legacy hand-written frontends installed into
   a `yang.frontend` catalog. This is the only place the REPL composition
   names a language's namespace; `yin.repl` core steps whatever binding the
   catalog selects.

   A binding is `{:parse (fn [input]) :lower (fn [parsed])}`, with
   `:input :forms` when the frontend consumes the forms the shell reader
   already read, else the source text."
  (:require
    [yang.clojure :as yang.clojure]
    [yang.frontend :as frontend]
    [yang.php :as yang.php]
    [yang.python :as yang.python]
    [yang.python.antlr.repl :as python.antlr.repl]
    #?@(:cljd [] :clj [[yang.python.antlr.parser :as python.antlr.parser]
                       [yang.python.antlr.repl-probe :as python.antlr.probe]])
    [yin.repl :as repl]))


(defn- manifest
  [id language]
  {:yang.frontend/id id,
   :yang.frontend/spi 1,
   :yang.frontend/revision "legacy-1",
   :yang.frontend/language language,
   :yang.frontend/grammar {:yang.grammar/package "none",
                           :yang.grammar/entries {:module "program"},
                           :yang.grammar/export-profile :yang.cst/v1},
   :yang.frontend/options-schema :segment/none,
   :yang.frontend/lowering-profile :segment/legacy,
   :yang.frontend/runtime-profile :segment/legacy,
   :yang.frontend/support-profile :segment/legacy})


(defn- clojure-lower
  [forms]
  (if (= 1 (count forms))
    (yang.clojure/compile (first forms))
    (yang.clojure/compile-program forms)))


#?(:cljd nil
   :clj
   (defn- antlr-manifest
     []
     (assoc (manifest :yang.python/antlr :python.antlr)
            :yang.frontend/revision "f3"
            :yang.frontend/grammar
            {:yang.grammar/package "python3",
             :yang.grammar/entries {:module "file_input"},
             :yang.grammar/export-profile :yang.cst/v1})))


(defn standard
  "The standard catalog. The Clojure frontend consumes the forms the
   shell's own reader read, so its parse stage is the identity.  The Python
   ANTLR frontend is installed where its parser runs (the JVM); elsewhere
   selecting it answers `unavailable-parser`.  It shows what a submission
   printed and not the program's value (`:display :output`)."
  []
  (cond-> frontend/empty-catalog
    true (frontend/install (manifest :yang.clojure/legacy :clojure)
                           {:input :forms,
                            :parse identity,
                            :lower clojure-lower})
    true (frontend/install (manifest :yang.python/legacy :python)
                           {:parse identity, :lower yang.python/compile})
    true (frontend/install (manifest :yang.php/legacy :php)
                           {:parse identity, :lower yang.php/compile})
    #?@(:cljd [] :clj [true (frontend/install
                              (antlr-manifest)
                              {:parse (fn [source]
                                        (python.antlr.parser/parse-source
                                          (str source "\n"))),
                               :lower python.antlr.repl/lower,
                               :lower-session python.antlr.repl/lower-session,
                               :display :output,
                               :probe python.antlr.probe/probe})])))


(defn create-state
  "`yin.repl/create-state` composed with the standard catalog unless the
   host supplies its own `:frontends`."
  ([] (create-state {}))
  ([opts]
   (repl/create-state
     (-> opts
         (update :frontends #(or % (standard)))
         (update :primitives #(merge python.antlr.repl/primitives %))))))
