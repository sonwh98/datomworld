(ns yin.repl.frontends
  "The REPL's standard frontend catalog (docs/design/yang.antlr.md section
   8.5.6, slice F2): the Clojure and available ANTLR frontends installed
   into a `yang.frontend` catalog. This is
   the only place the REPL composition names a language's namespace;
   `yin.repl` core steps whatever binding the catalog selects.

   A binding is `{:parse (fn [input]) :lower (fn [parsed])}`, with
   `:input :forms` when the frontend consumes the forms the shell reader
   already read, else the source text."
  (:require
    [yang.clojure :as yang.clojure]
    [yang.frontend :as frontend]
    #?@(:cljd []
        :jolt []
        :default [[yang.php.antlr.cst :as php.antlr.cst]
                  [yang.php.antlr.lower :as php.antlr.lower]
                  [yang.php.antlr.contract :as php.contract]
                  [yang.php.antlr.profiles :as php.profiles]])
    [yang.clojure.prelude :as clj-runtime]
    [yang.python.antlr.contract :as python.contract]
    [yang.python.antlr.profiles :as python.profiles]
    [yang.python.antlr.repl :as python.repl]
    [yang.python.antlr.parser :as python.parser]
    [yang.python.antlr.repl-probe :as python.probe]
    #?@(:cljd []
        :jolt []
        :clj [[yang.typescript.antlr.cst :as ts.cst]
              [yang.typescript.antlr.frontend :as ts.frontend]])
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
   a support claim, so the revision and the support profile name it.
   Revision c1-2 declared constructed-collections canonical operand ordering
   (:yang.clojure/constructed-collections-v1).
   Revision c1-3 (architect ruling 1795693415000) retains the
   constructed-collections ordering contract and adds frontend-owned
   token-aware source reading, carrier-literal admission, and carrier-aware
   floating-point execution (`:yang.clojure/constructed-collections-float-kind-v1`,
   support profile `:yang.clojure/c1-float-kind`), while exposing accurate
   host capability (`:source-text` on JVM/Node, `:forms-only` on Dart/Jolt).
   The C1 marker value and execution grammar versions are unchanged."
  []
  (assoc (manifest :yang/clojure :clojure)
         :yang.frontend/revision "c1-3"
         :yang.frontend/grammar {:yang.grammar/package "yang.clojure.reader",
                                 :yang.grammar/entries {:module "program"},
                                 :yang.grammar/export-profile :yang.cst/v1,
                                 :yang.grammar/capability #?(:jolt :forms-only
                                                             :cljd :forms-only
                                                             :clj :source-text
                                                             :cljs :source-text
                                                             :default :forms-only)}
         :yang.frontend/lowering-profile :yang.clojure/constructed-collections-float-kind-v1
         :yang.frontend/support-profile :yang.clojure/c1-float-kind))


(defn- clojure-lower
  [forms]
  (if (= 1 (count forms))
    (yang.clojure/compile (first forms))
    (yang.clojure/compile-program forms)))


(defn- clojure-binding
  "The Clojure frontend binding for this host: original source text through
   `yang.clojure/read-forms` on JVM/Node (`:source-text`), forms-only on
   Dart/Jolt (`:forms-only`), and carrier-aware Clojure numeric primitives
   scoped to this frontend."
  []
  #?(:jolt {:input :forms
            :parse identity
            :lower clojure-lower
            :primitives yang.clojure/primitives}
     :cljd {:input :forms
            :parse identity
            :lower clojure-lower
            :primitives yang.clojure/primitives}
     :clj {:parse yang.clojure/read-forms
           :lower clojure-lower
           :lower-forms clojure-lower
           :primitives yang.clojure/primitives}
     :cljs {:parse yang.clojure/read-forms
            :lower clojure-lower
            :lower-forms clojure-lower
            :primitives yang.clojure/primitives}
     :default {:input :forms
               :parse identity
               :lower clojure-lower
               :primitives yang.clojure/primitives}))


(defn- antlr-manifest
  [id {:keys [descriptor-address runtime-address]}]
  (assoc (manifest :yang/python :python)
         :yang.frontend/revision (name id)
         :yang.frontend/lowering-profile descriptor-address
         :yang.frontend/runtime-profile runtime-address
         :yang.frontend/support-profile descriptor-address
         :yang.frontend/grammar
         {:yang.grammar/package "python3",
          :yang.grammar/entries {:module "file_input"},
          :yang.grammar/export-profile :yang.cst/v1,
          :yang.grammar/targets #{:java :javascript :dart}}))


(defn php-manifest
  [id {:keys [descriptor-address runtime-address]}]
  (assoc (manifest :yang/php :php)
         :yang.frontend/revision (name id)
         :yang.frontend/grammar
         {:yang.grammar/package "php",
          :yang.grammar/entries {:module "htmlDocument"},
          :yang.grammar/export-profile :yang.cst/v1,
          :yang.grammar/targets #{:java :javascript}}
         :yang.frontend/lowering-profile descriptor-address
         :yang.frontend/runtime-profile runtime-address
         :yang.frontend/support-profile descriptor-address))


#?(:cljd nil
   :jolt nil
   :default
   (defn- install-php
     [catalog id]
     (let [{:keys [descriptor] :as profile} (php.profiles/builtin id)
           manifest (php-manifest id profile)
           legacy? (= id php.contract/legacy-id)]
       (frontend/install catalog manifest
                         (cond-> {:parse (fn [source]
                                           (php.profiles/verify-builtin! id)
                                           (php.antlr.cst/parse-source source))
                                  :lower (fn [packet]
                                           (php.profiles/verify-builtin! id)
                                           (php.antlr.lower/lower-packet packet {:descriptor descriptor}))
                                  :derive (fn [packet ast] (php.profiles/derivation manifest profile packet ast))
                                  :render (if legacy?
                                            :php/out
                                            (fn [{:php/keys [out uncaught]}]
                                              (if uncaught (str out "Error: " (pr-str uncaught)) out)))}
                           (not legacy?) (assoc :admit clj-runtime/admit))))))


(defn- parse-python
  [source]
  #?(:jolt
     (try (python.parser/parse-source (str source "\n"))
          (catch Exception e
            (if (#{:parser-budget :unavailable-parser} (:diagnostic (ex-data e)))
              (throw (ex-info (ex-message e) (assoc (ex-data e) :lang :yang/python) e))
              (throw e))))
     :default (python.parser/parse-source (str source "\n"))))


(defn- install-python
  [catalog id]
  (let [profile (python.profiles/builtin id)
        manifest (antlr-manifest id profile)
        legacy? (= id python.contract/legacy-id)
        lower (if legacy? python.repl/lower-session python.repl/lower-clojure-session)
        lower-session (fn [packet session]
                        (python.profiles/verify-builtin! id)
                        (lower packet session (:descriptor profile)))]
    (frontend/install catalog manifest
                      (cond-> {:parse (fn [source]
                                        (python.profiles/verify-builtin! id)
                                        (parse-python source))
                               :lower (fn [packet] (first (lower-session packet nil)))
                               :lower-session lower-session
                               :derive (fn [packet ast] (python.profiles/derivation manifest profile packet ast))
                               :probe (fn [source]
                                        (python.profiles/verify-builtin! id)
                                        (python.probe/probe source))
                               :render (if legacy? python.repl/display-value python.repl/display-clojure-value)}
                        legacy? (assoc :primitives python.repl/primitives)
                        (not legacy?) (assoc :admit clj-runtime/admit)))))


(defn standard
  "The standard immutable frontend catalog and its preferred revisions."
  []
  (let [catalog (cond-> frontend/empty-catalog
                  true (frontend/install (clojure-manifest) (clojure-binding))
                  #?@(:cljd []
                      :jolt []
                      :default [true (install-php php.contract/legacy-id)
                                true (install-php php.contract/clojure-id)]))]
    (cond-> catalog
      #?(:jolt (python.parser/available?) :default true)
      (-> (install-python python.contract/legacy-id)
          (install-python python.contract/clojure-id)
          (assoc :yang.frontend/preferred [:yang/python "clojure-core-v1"]))
      #?@(:cljd []
          :jolt []
          :clj [true (ts.frontend/install ts.cst/grammar-id ts.cst/parse-source)])
      #?@(:cljd []
          :jolt []
          :default [true (update :yang.frontend/preferred
                                 (fn [preference]
                                   (assoc (cond
                                            (map? preference) preference
                                            (vector? preference) (apply hash-map preference)
                                            :else {})
                                          :yang/php (name php.contract/clojure-id))))]))))


(defn create-state
  "`yin.repl/create-state` composed with the standard catalog unless the
   host supplies its own `:frontends`.  Each binding registers its own
   `:primitives`; none is merged ambiently."
  ([] (create-state {}))
  ([opts]
   (repl/create-state (update opts :frontends #(or % (standard))))))
