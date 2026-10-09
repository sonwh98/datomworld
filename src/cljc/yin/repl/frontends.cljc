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


(defn standard
  "The standard catalog. The Clojure frontend consumes the forms the
   shell's own reader read, so its parse stage is the identity."
  []
  (-> frontend/empty-catalog
      (frontend/install (manifest :yang.clojure/legacy :clojure)
                        {:input :forms,
                         :parse identity,
                         :lower clojure-lower})
      (frontend/install (manifest :yang.python/legacy :python)
                        {:parse identity, :lower yang.python/compile})
      (frontend/install (manifest :yang.php/legacy :php)
                        {:parse identity, :lower yang.php/compile})))


(defn create-state
  "`yin.repl/create-state` composed with the standard catalog unless the
   host supplies its own `:frontends`."
  ([] (create-state {}))
  ([opts]
   (repl/create-state (update opts :frontends #(or % (standard))))))
