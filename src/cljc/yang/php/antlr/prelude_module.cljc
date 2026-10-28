(ns ^{:clj-kondo/config '{:lint-as {yang.php.antlr.quoted/defquoted clojure.core/def}}}
 yang.php.antlr.prelude-module
  "The include/require runtime of the PHP profile (docs/design/yang.php.md
   section 11, plan P6 R3/R4): the four kinds, the `_once` check, the two
   missing stages. Guest code like the rest of the prelude; the lowering
   emits only applications of these names.

   Runtime state `php/init!` allocates for it (prelude.cljc owns the keys):
     php.rt/included   a cell, map of normalized path to true; written on
                       `:ok` delivery only, never on a refusal
     php.rt/unit       a cell, the executing unit's path or :php/null
     php.rt/path-map   a cell, map of normalized path to a delivery thunk
                       (the literal path map of the composition's `:paths`)"
  (:require
    [yang.php.antlr.quoted :refer [defquoted]]))


(def include-path-text
  "The `include_path` the missing-include texts print: the oracle image's
   default. PENDING the S0 oracle pin (php:8.3-cli reports
   `.:/usr/local/lib/php`)."
  ".:/usr/local/lib/php")


(def include-kinds
  "The four kinds the lowering passes to `php/include-run`."
  #{:include :include-once :require :require-once})


(defquoted ^:private module-forms
  "The include definitions, `[store-key form]`."
  '[[php/include-kind-name
     (fn [kind]
       (if (= kind :include)
         "include"
         (if (= kind :include-once)
           "include_once"
           (if (= kind :require) "require" "require_once"))))]
    [php/include-require?
     (fn [kind] (if (= kind :require) true (= kind :require-once)))]
    [php/include-once?
     (fn [kind] (if (= kind :include-once) true (= kind :require-once)))]
    [php/warn-refusal
     ;; php/warn with the structured refusal on the record (plan R4)
     (fn [errno msg refusal]
       (let [rec (assoc (php/warn-record errno msg) :yang.php/refusal refusal)
             h (cell/get php.rt/error-handler)]
         (if (= h :php/null)
           (php/warn-sink rec)
           (if (= (php/invoke-handler h errno msg) false)
             (php/warn-sink rec)
             :php/null))))]
    [php/include-missing!
     ;; the refusal map follows the base kind; the texts carry the kind as
     ;; written; a refused path is never marked included
     (fn [path kind refusal]
       (let [nm (php/include-kind-name kind)]
         (do (php/warn-refusal
               2
               (data/str-concat
                 (data/str-concat (data/str-concat nm "(") path)
                 "): Failed to open stream: No such file or directory")
               refusal)
             (if (php/include-require? kind)
               (php/throw-error
                 "Error"
                 (data/str-concat
                   (data/str-concat
                     (data/str-concat "Failed opening required '" path)
                     "' (include_path='")
                   (data/str-concat php/include-path "')")))
               (do (php/warn-refusal
                     2
                     (data/str-concat
                       (data/str-concat
                         (data/str-concat
                           (data/str-concat nm "(): Failed opening '")
                           path)
                         "' for inclusion (include_path='")
                       (data/str-concat php/include-path "')"))
                     refusal)
                   false)))))]
    [php/include-body
     ;; marks the path before the body runs, names the unit while it runs,
     ;; answers the body's `return` value, else 1
     (fn [path delivered]
       (let [prev (cell/get php.rt/unit)]
         (do (cell/set! php.rt/included (assoc (cell/get php.rt/included) path true))
             (cell/set! php.rt/unit path)
             (php/try-finally
               (fn []
                 (let [v ((get delivered :body) :php/null :php/null)]
                   (if (= v :php/no-return) 1 v)))
               (fn [x] (cell/set! php.rt/unit prev))))))]
    [php/include-run
     ;; `thunk` answers {:status :ok :body b} or the structured refusal
     (fn [path thunk kind]
       (if (if (php/include-once? kind)
             (not (nil? (get (cell/get php.rt/included) path)))
             false)
         true
         (let [r (thunk)]
           (if (= (get r :status) :ok)
             (php/include-body path r)
             (php/include-missing! path kind r)))))]
    [php/normalize-path
     ;; the computed arm's lookup key: leading "./" segments dropped
     (fn [s]
       (if (if (>= (data/str-length s) 2) (= (data/substring s 0 2) "./") false)
         (php/normalize-path (data/substring s 2))
         s))]
    [php/include-computed
     ;; a computed path is a run-time lookup in the published path map; a
     ;; miss is the run-time missing-include behaviour for the kind
     (fn [pathv kind]
       (let [path (php/normalize-path (php/to-string pathv))
             thunk (get (cell/get php.rt/path-map) path)]
         (php/include-run
           path
           (if (nil? thunk)
             (fn []
               (assoc (assoc {} :status :refused)
                      :reason :yang.php/computed-path-unmapped))
             thunk)
           kind)))]
    [php/bind-path-map!
     (fn [m] (cell/set! php.rt/path-map m))]])


(def definitions
  "The include definitions: `[store-key form]` in definition order, the
   include-path literal first."
  (into [['php/include-path include-path-text]] module-forms))
