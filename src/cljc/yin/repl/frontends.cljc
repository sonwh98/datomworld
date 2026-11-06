(ns yin.repl.frontends
  "The REPL's standard frontend catalog (docs/design/yang.antlr.md section
   8.5.6, slice F2): the hand-written Clojure frontend and the ANTLR PHP and
   Python frontends installed into a `yang.frontend` catalog. This is
   the only place the REPL composition names a language's namespace;
   `yin.repl` core steps whatever binding the catalog selects.

   A binding is `{:parse (fn [input]) :lower (fn [parsed])}`, with
   `:input :forms` when the frontend consumes the forms the shell reader
   already read, else the source text."
  (:require
    [yang.clojure :as yang.clojure]
    [yang.frontend :as frontend]
    #?@(:cljd []
        :clj [[yang.php.antlr.cst :as php.antlr.cst]
              [yang.php.antlr.lower :as php.antlr.lower]])
    #?@(:cljd [[yang.python.antlr.repl :as python.antlr.repl]
               [yang.python.antlr.parser :as python.antlr.parser]
               [yang.python.antlr.repl-probe :as python.antlr.probe]]
        :default [[yang.python.antlr.repl :as python.antlr.repl]
                  [yang.python.antlr.parser :as python.antlr.parser]
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


(defn clojure-manifest
  "The Clojure frontend's manifest. Its coverage promise changed under
   the call-site occurrence contract C1 — every final `:application`
   occurrence carries `:yang/call-site` in the frontend-metadata side
   table, finalized over the post-expansion tree that executes and
   retained beside a published module's manifest — and that promise is
   a support claim, so the revision and the support profile name it;
   the shared legacy profiles no longer describe this frontend."
  []
  (assoc (manifest :yang/clojure :clojure)
         :yang.frontend/revision "c1-1"
         :yang.frontend/support-profile :yang.clojure/c1))


(defn- clojure-lower
  [forms]
  (if (= 1 (count forms))
    (yang.clojure/compile (first forms))
    (yang.clojure/compile-program forms)))


(defn- antlr-manifest
  []
  (assoc (manifest :yang/python :python)
         :yang.frontend/revision "f4"
         :yang.frontend/grammar
         {:yang.grammar/package "python3",
          :yang.grammar/entries {:module "file_input"},
          :yang.grammar/export-profile :yang.cst/v1,
          :yang.grammar/targets #{:java :javascript :dart}}))


(defn php-manifest
  []
  (assoc (manifest :yang/php :php)
         :yang.frontend/revision "antlr-p8"
         :yang.frontend/grammar
         {:yang.grammar/package "php",
          :yang.grammar/entries {:module "htmlDocument"},
          :yang.grammar/export-profile :yang.cst/v1,
          :yang.grammar/targets #{:java :javascript}}
         :yang.frontend/lowering-profile :yang.php/antlr
         :yang.frontend/runtime-profile :yang.php/antlr
         :yang.frontend/support-profile :yang.php/antlr))


(defn standard
  "The standard catalog. The Clojure frontend consumes the forms the
   shell's own reader read, so its parse stage is the identity. The ANTLR
   frontends show what a submission printed and the value their `:render`
   returns."
  []
  (cond-> frontend/empty-catalog
    true (frontend/install (clojure-manifest)
                           {:input :forms,
                            :parse identity,
                            :lower clojure-lower})
    #?@(:clj [true (frontend/install
                     (php-manifest)
                     {:parse php.antlr.cst/parse-source,
                      :lower php.antlr.lower/lower-packet,
                      :render :php/out})])
    #?@(:cljd [true (frontend/install
                      (antlr-manifest)
                      {:parse (fn [source]
                                (python.antlr.parser/parse-source (str source "\n"))),
                       :lower python.antlr.repl/lower,
                       :primitives python.antlr.repl/primitives,
                       :lower-session python.antlr.repl/lower-session,
                       :render python.antlr.repl/display-value,
                       :probe python.antlr.probe/probe})]
        ;; jolt (J5): installed over the C++ parser when native/jolt-parser
        ;; is available; otherwise left out, so (lang :yang/python) answers
        ;; unavailable-parser, never another parser
        :jolt [(python.antlr.parser/available?)
               (frontend/install
                 (antlr-manifest)
                 {;; a :parser-budget or :unavailable-parser refusal names
                  ;; the language it refused
                  :parse (fn [source]
                           (try (python.antlr.parser/parse-source (str source "\n"))
                                (catch Exception e
                                  (if (#{:parser-budget :unavailable-parser}
                                       (:diagnostic (ex-data e)))
                                    (throw (ex-info (ex-message e)
                                                    (assoc (ex-data e) :lang :yang/python)
                                                    e))
                                    (throw e))))),
                  :lower python.antlr.repl/lower,
                  :primitives python.antlr.repl/primitives,
                  :lower-session python.antlr.repl/lower-session,
                  :render python.antlr.repl/display-value,
                  :probe python.antlr.probe/probe})]
        :default [true (frontend/install
                         (antlr-manifest)
                         {:parse (fn [source]
                                   (python.antlr.parser/parse-source (str source "\n"))),
                          :lower python.antlr.repl/lower,
                          :primitives python.antlr.repl/primitives,
                          :lower-session python.antlr.repl/lower-session,
                          :render python.antlr.repl/display-value,
                          :probe python.antlr.probe/probe})])))


(defn create-state
  "`yin.repl/create-state` composed with the standard catalog unless the
   host supplies its own `:frontends`.  Each binding registers its own
   `:primitives`; none is merged ambiently."
  ([] (create-state {}))
  ([opts]
   (repl/create-state (update opts :frontends #(or % (standard))))))
