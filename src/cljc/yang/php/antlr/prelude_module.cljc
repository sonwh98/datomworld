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
     (fn [path delivered scope ctx]
       (let [prev (cell/get php.rt/unit)]
         (do (cell/set! php.rt/included (assoc (cell/get php.rt/included) path true))
             (cell/set! php.rt/unit path)
             (php/try-finally
               (fn []
                 (let [v ((get delivered :body) scope ctx)]
                   (if (= v :php/no-return) 1 v)))
               (fn [x] (cell/set! php.rt/unit prev))))))]
    [php/include-run
     ;; `thunk` answers {:status :ok :body b} or the structured refusal
     (fn [path thunk kind scope ctx]
       (if (if (php/include-once? kind)
             (not (nil? (get (cell/get php.rt/included) path)))
             false)
         true
         (let [r (thunk)]
           (if (= (get r :status) :ok)
             (php/include-body path r scope ctx)
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
     (fn [pathv kind scope ctx]
       (let [path (php/normalize-path (php/to-string pathv))
             thunk (get (cell/get php.rt/path-map) path)]
         (php/include-run
           path
           (if (nil? thunk)
             (fn []
               (assoc (assoc {} :status :refused)
                      :reason :yang.php/computed-path-unmapped))
             thunk)
           kind
           scope
           ctx)))]
    ;; ---------------------------------------------------------------- scopes
    ;; An including scope is `:php/globals` (the entry unit's top level) or a
    ;; scope cell: name to the variable's own cell, in insertion order. A
    ;; dynamic-profile function adopts the cells its body already holds, so an
    ;; included body and the function see one variable.
    [php/scope-new
     (fn [] (cell/new (assoc (assoc {} :cells {}) :order [])))]
    [php/scope-adopt!
     (fn [d name c]
       (let [s (cell/get d)]
         (do (cell/set! d (assoc (assoc s :cells (assoc (get s :cells) name c))
                                 :order (php/vconj (get s :order) name)))
             c)))]
    [php/scope-cell
     (fn [d name]
       (let [c (get (get (cell/get d) :cells) name)]
         (if (nil? c) (php/scope-adopt! d name (cell/new :php/undef)) c)))]
    [php/scope-release-loop
     (fn [cells order i]
       (if (< i (data/count order))
         (do (php/unset-cv (get cells (get order i)))
             (php/scope-release-loop cells order (+ i 1)))
         (php/flush-pending!)))]
    [php/scope-release!
     (fn [d] (let [s (cell/get d)] (php/scope-release-loop (get s :cells) (get s :order) 0)))]
    [php/dict-get
     (fn [d name]
       (if (= d :php/globals) (php/global-get name) (php/local-get (php/scope-cell d name) name)))]
    [php/dict-peek
     (fn [d name]
       (if (= d :php/globals) (php/global-peek name) (php/local-peek (php/scope-cell d name))))]
    [php/dict-set!
     (fn [d name v]
       (if (= d :php/globals) (php/global-set! name v) (php/local-set! (php/scope-cell d name) v)))]
    [php/dict-put!
     (fn [d name v]
       (if (= d :php/globals) (php/global-put! name v) (php/local-put! (php/scope-cell d name) v)))]
    [php/dict-make-ref!
     (fn [d name]
       (if (= d :php/globals) (php/global-make-ref! name) (php/make-ref-cv (php/scope-cell d name))))]
    [php/dict-bind!
     (fn [d name m]
       (if (= d :php/globals) (php/global-bind! name m) (php/bind-marker (php/scope-cell d name) m)))]
    [php/dict-isset?
     (fn [d name]
       (if (= d :php/globals) (php/global-isset? name) (php/local-isset? (php/scope-cell d name))))]
    [php/dict-unset!
     (fn [d name]
       (if (= d :php/globals) (php/global-unset! name) (php/local-unset! (php/scope-cell d name))))]
    [php/dict-capture
     (fn [d name]
       (if (= d :php/globals) (php/global-capture name) (php/local-capture (php/scope-cell d name))))]
    [php/ctx-this
     (fn [f]
       (if (= f :php/null)
         (php/throw-error "Error" "Using $this when not in object context")
         (php/this-of f)))]
    [php/ctx-this-or-null
     (fn [f] (if (= f :php/null) :php/null (php/this-or-null f)))]
    [php/ctx-has-this?
     (fn [f] (if (= f :php/null) false (php/has-this? f)))]
    [php/bind-path-map!
     (fn [m] (cell/set! php.rt/path-map m))]
    [php/bind-class-map!
     ;; the composition's `phpm.$classmap` exports: lower-cased class name to
     ;; path, and the literal paths a computed include may name
     (fn [classes paths]
       (do (cell/set! php.rt/class-map classes)
           (cell/set! php.rt/path-map paths)))]
    ;; ---------------------------------------------------------------- namespaces
    [php/resolve-fn2
     ;; the two-key lookup of an unqualified call inside a namespace
     (fn [lc1 lc2 written]
       (let [f (get (cell/get php.rt/functions) lc1)]
         (if (nil? f)
           (let [g (get (cell/get php.rt/functions) lc2)]
             (if (nil? g)
               (php/throw-error "Error" (data/str-concat "Call to undefined function " written "()"))
               g))
           f)))]
    [php/strip-lead
     (fn [s]
       (if (if (> (data/str-length s) 0) (= (data/substring s 0 1) "\\") false)
         (data/substring s 1)
         s))]
    [php/const-key-at
     ;; the namespace part of a constant name is case-insensitive, the name is not
     (fn [s i]
       (if (< i 0)
         s
         (if (= (data/substring s i (+ i 1)) "\\")
           (data/str-concat (php/lc (data/substring s 0 i)) (data/substring s i (data/str-length s)))
           (php/const-key-at s (- i 1)))))]
    [php/const-key
     (fn [s] (php/const-key-at s (- (data/str-length s) 1)))]
    [php/const-lookup
     (fn [k] (get (cell/get php.rt/constants) (php/const-key k)))]
    [php/const-get
     ;; `name` as written, namespaced candidate first, then the global one
     (fn [k1 k2]
       (let [v (php/const-lookup k1)]
         (if (nil? v)
           (let [w (php/const-lookup k2)]
             (if (nil? w)
               (php/throw-error "Error" (data/str-concat "Undefined constant \"" k1 "\""))
               w))
           v)))]
    [php/const-get-fq
     (fn [k]
       (let [v (php/const-lookup k)]
         (if (nil? v)
           (php/throw-error "Error" (data/str-concat "Undefined constant \"" k "\""))
           v)))]
    [php/const-declare!
     ;; `const X = v;` and the guest of `define`: false on a redeclaration
     (fn [k v]
       (if (nil? (php/const-lookup k))
         (do (cell/set! php.rt/constants (assoc (cell/get php.rt/constants) (php/const-key k) v))
             true)
         (do (php/warn 2 (data/str-concat "Constant " k " already defined"))
             false)))]
    [php/bi-define
     (fn [args]
       (do (if (> (data/count args) 2)
             (if (php/to-bool (get args 2))
               (php/warn 2 "define(): Argument #3 ($case_insensitive) is ignored since declaration of case-insensitive constants is no longer supported")
               :php/null)
             :php/null)
           (php/const-declare! (php/strip-lead (php/to-string (get args 0))) (get args 1))))]
    [php/bi-defined
     (fn [args]
       (not (nil? (php/const-lookup (php/strip-lead (php/to-string (get args 0)))))))]
    [php/bi-constant
     (fn [args] (php/const-get-fq (php/strip-lead (php/to-string (get args 0)))))]
    [php/bi-function-exists
     (fn [args]
       (not (nil? (get (cell/get php.rt/functions) (php/lc (php/strip-lead (php/to-string (get args 0))))))))]
    ;; ---------------------------------------------------------------- autoload
    [php/run-loaders
     (fn [ls i name]
       (if (< i (data/count ls))
         (do (php/call-value (get ls i) (php/vconj [] name))
             (let [c (php/class-lookup name)]
               (if (nil? c) (php/run-loaders ls (+ i 1) name) c)))
         nil))]
    [php/autoload-class
     ;; each registered loader in order with the class name, re-checking the
     ;; table after each; a loader's own miss of the same name does not recurse
     (fn [name]
       (let [lc (php/lc name)]
         (if (= (get (cell/get php.rt/autoloading) lc) true)
           nil
           (do (cell/set! php.rt/autoloading (assoc (cell/get php.rt/autoloading) lc true))
               (php/try-finally
                 (fn [] (php/run-loaders (cell/get php.rt/autoloaders) 0 name))
                 (fn [x] (cell/set! php.rt/autoloading (assoc (cell/get php.rt/autoloading) lc false))))))))]
    [php/bi-spl-autoload-register
     (fn [args]
       (if (if (= (data/count args) 0) true (= (get args 0) :php/null))
         (php/throw-error "Error" "spl_autoload_register(): the default autoloader (G-default-autoload) is not supported")
         (do (cell/set! php.rt/autoloaders
                        (if (if (> (data/count args) 2) (php/to-bool (get args 2)) false)
                          (php/vconj-front (cell/get php.rt/autoloaders) (get args 0))
                          (php/vconj (cell/get php.rt/autoloaders) (get args 0))))
             true)))]
    [php/vconj-front
     (fn [v x] (data/into (php/vconj [] x) v))]
    [php/remove-callable
     (fn [ls i x acc]
       (if (< i (data/count ls))
         (if (php/identical (get ls i) x)
           (data/into acc (data/subvec ls (+ i 1) (data/count ls)))
           (php/remove-callable ls (+ i 1) x (php/vconj acc (get ls i))))
         acc))]
    [php/bi-spl-autoload-unregister
     (fn [args]
       (let [ls (cell/get php.rt/autoloaders)
             out (php/remove-callable ls 0 (get args 0) [])]
         (do (cell/set! php.rt/autoloaders out)
             (< (data/count out) (data/count ls)))))]
    [php/bi-spl-autoload-functions
     (fn [args]
       (let [ls (cell/get php.rt/autoloaders)]
         (php/vec->array ls 0 (data/count ls) (php/empty-array))))]
    [php/bi-spl-autoload-call
     (fn [args]
       (do (php/autoload-class (php/strip-lead (php/to-string (get args 0)))) :php/null))]
    [php/bi-class-exists
     (fn [args]
       (let [name (php/strip-lead (php/to-string (get args 0)))
             c0 (php/class-lookup name)
             c (if (nil? c0)
                 (if (if (> (data/count args) 1) (php/to-bool (get args 1)) true)
                   (php/autoload-class name)
                   nil)
                 c0)]
         (if (nil? c) false (if (= (get c :kind) :interface) false (not (= (get c :kind) :trait))))))]])


(def definitions
  "The include definitions: `[store-key form]` in definition order, the
   include-path literal first."
  (into [['php/include-path include-path-text]] module-forms))
