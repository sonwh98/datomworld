(ns yang.clojure.prelude
  "The trusted Clojure runtime, emitted inline or as the linked clj module."
  (:require [yang.core.call :as call]
            [yang.core.prelude :as core]
            [yang.core.prelude-admission :as prelude-admission]
            [yang.core.uast :as u]
            [yang.rt.effect :as effect]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.module :as module]))


(def module-name 'clj)

(def runtime-keys '[clj.rt/state clj.rt/globals clj.rt/ctx])


(def definitions
  "The ordered guest definitions shared by bundled and linked clj."
  '[[clj/init!
     (fn []
       (if (= clj.rt/state :clj/uninit)
         (do (yin/def (quote clj.rt/globals) (cell/new {}))
             (yin/def (quote clj.rt/ctx) (cell/new (yrt.effect/ctx nil 0 0 nil)))
             (yin/def (quote clj.rt/state) :clj/ready))
         :clj/ready))]
    [clj/define-global!
     (fn [name value]
       (do (cell/set! clj.rt/globals
                      (assoc (cell/get clj.rt/globals) name value))
           value))]
    [clj/global-get
     (fn [name]
       (if (data/contains? (cell/get clj.rt/globals) name)
         (get (cell/get clj.rt/globals) name)
         :clj/unbound))]
    [clj/prim-get-default
     (fn [coll k default]
       (if (data/vector? coll)
         (if (data/contains? coll k) (data/nth coll k) default)
         (get coll k default)))]
    [clj/prim-get (fn [coll k] (clj/prim-get-default coll k nil))]
    [clj/prim-assoc (fn [coll k v] (assoc coll k v))]
    [clj/rt-ctx (fn [] clj.rt/ctx)]
    [clj/raise
     (fn [e] (yrt.effect/raise (clj/rt-ctx) e (fn [v] (:clj/no-handler v))))]
    [clj/throw (fn [e] (clj/raise e))]
    [clj/try
     (fn [body handler] (yrt.effect/try-value (clj/rt-ctx) body handler))]
    [clj/try-finally
     (fn [body fin] (yrt.effect/try-finally (clj/rt-ctx) body fin nil))]

    [clj/admitted-callable?
     (fn [x]
       (if (data/map? x)
         (if (= (get x :type) :cell-ref)
           (if (cell/authentic? x)
             (if (data/callable? (yang.call/entry x))
               (yang.call/eligible? x)
               false)
             false)
           false)
         false))]
    [clj/dsg__invoke
     (fn [callee positional named raise]
       (if (clj/admitted-callable? callee)
         (yang.call/invoke callee positional named raise)
         (yang.call/transfer
           raise
           (yang.call/condition :yang.call/not-callable
             (assoc {} :yang.call/callee callee)))))]
    [clj/dsg__run-body
     (fn [meta body invocation raise]
       (let [ctx (clj/rt-ctx)
             run (fn [tail-invoke]
                   (let [named (get invocation :yang.call/named)]
                     (if (= (data/count named) 0)
                       (body (get invocation :yang.call/positional) tail-invoke)
                       (clj/throw
                         (assoc {} :yin.k/message
                                   "Clojure functions do not accept named arguments"
                                   :yin.k/data (assoc {} :yang.call/named named))))))
             escape (fn [e]
                      (let [retained (cell/dsg__provenance e)]
                        (yang.call/transfer
                          raise
                          (if (= retained nil) (yang.call/thrown e) retained))))]
         (if (get meta :clj/tail-call?)
           ;; A source tail call escapes the current entry before entering
           ;; the next one. The thunk carries the caller's actual raise
           ;; exit, and the value path wraps and removes exactly one thunk.
           ((yrt.effect/call-ec ctx
              (fn [exit]
                (let [value (yrt.effect/try-value ctx
                              (fn []
                                (run (fn [callee positional named]
                                       (exit (fn []
                                               (clj/dsg__invoke callee positional named raise))))))
                              escape)]
                  (fn [] value)))
              nil))
           (yrt.effect/try-value ctx (fn [] (run nil)) escape))))]
    [clj/make-callable
     (fn [meta body]
       (let [owner (cell/new meta)
             entry
             (fn [invocation]
               (if (data/map? invocation)
                 (let [raise (get invocation :yang.call/raise)]
                   (if (data/callable? raise)
                     (if (if (yang.call/valid-invocation? invocation)
                           (= (get invocation :yang.call/callee) owner)
                           false)
                       (if (data/contains? invocation :yang.call/binding-profile)
                         (yang.call/transfer
                           raise
                           (yang.call/condition :yang.call/unsupported-binding
                             (assoc {} :yang.call/callee owner
                                       :yang.call/binding-profile
                                       (get invocation :yang.call/binding-profile))))
                         (clj/dsg__run-body meta body invocation raise))
                       (yang.call/transfer
                         raise
                         (yang.call/condition :yang.call/invalid-invocation
                           (assoc {} :yang.call/callee (get invocation :yang.call/callee)
                                     :yang.call/owner owner))))
                     (:clj/missing-raise-exit invocation)))
                 (:clj/invalid-invocation invocation)))]
         (do (cell/set! owner (assoc meta :yang.call/entry entry))
             owner)))]
    [clj/invoke-callable
     (fn [callee positional named]
       (clj/dsg__invoke callee positional named
         (fn [condition]
           (clj/raise
             (if (= (get condition :yang.call/reason) :yang.call/thrown)
               (get condition :yang.call/value)
               (cell/dsg__new-provenance condition condition))))))]
    ;; A caught carrier stays intact for storing or rethrowing. Source
    ;; readers observe its value, and the program boundary reports the
    ;; retained condition even if the carrier's public value changed.
    [clj/condition-view
     (fn [x]
       (if (= (cell/dsg__provenance x) nil)
         (if (if (data/map? x) (= (get x :type) :cell-ref) false)
           (if (cell/authentic? x)
             (let [descriptor (cell/get x)]
               (if (= (get descriptor :clj/type) :function) descriptor x))
             x)
           x)
         (cell/get x)))]
    [clj/run-unit
     (fn [body]
       (clj/try (fn [] (conj [] :yin.k/ok (body)))
                (fn [e]
                  (let [retained (cell/dsg__provenance e)]
                    (conj [] :yin.k/error (if (= retained nil) e retained))))))]
    [clj/unwrap-outcome
     (fn [value]
       (if (if (data/vector? value) (= (data/count value) 2) false)
         (if (= (data/nth value 0) :yin.k/ok)
           (data/nth value 1)
           value)
         value))]
    [clj/run-main
     (fn [body]
       (do (clj/init!)
           (clj/unwrap-outcome (body))))]])


(def bundled-primitive-forms
  "Guest closures can capture each other without capturing a host function."
  '[(fn [coll k default]
      (if (data/vector? coll)
        (if (data/contains? coll k) (data/nth coll k) default)
        (dsg__primitive/get coll k default)))
    (fn [a b] (data/content= a b))
    (fn [coll k v k2 v2 k3 v3 k4 v4]
      (if (data/content= k2 nil)
        (dsg__primitive/assoc coll k v)
        (if (data/content= k3 nil)
          (dsg__primitive/assoc coll k v k2 v2)
          (if (data/content= k4 nil)
            (dsg__primitive/assoc coll k v k2 v2 k3 v3)
            (dsg__primitive/assoc coll k v k2 v2 k3 v3 k4 v4)))))
    (fn [a b] (dsg__primitive/+ a b))
    (fn [a b] (dsg__primitive/<= a b))])


(def module-exports
  (disj (core/exports "clj" definitions) 'dsg__invoke 'dsg__run-body))


(def uast
  "Install clj from a closed, content-addressed bundle."
  (let [placeholders (mapv (fn [key] [key :clj/uninit]) runtime-keys)
        install (apply u/app
                       (u/lam '[get = assoc + <=]
                              (core/definitions->uast (into placeholders definitions)))
                       (map u/sexp->uast bundled-primitive-forms))]
    (u/mark-tails
      (u/app (u/lam [] (u/then (u/lit :clj/bundled-runtime) install))))))


(defn with-bundled-runtime
  "Install and initialize bundled clj before running body in the same
   lexical scope. Bundled UAST callers can use the private lookup closure
   to read an invocation record without capturing a host function."
  [& body]
  (let [shared (u/app (u/lam '[get = assoc + <=] (u/seq-nodes [call/uast effect/uast]))
                      (u/v 'dsg__get) (u/v 'dsg__equal) (u/v 'dsg__assoc)
                      (u/v 'dsg__add) (u/v 'dsg__lte))]
    (apply u/app
           (u/lam '[dsg__get dsg__equal dsg__assoc dsg__add dsg__lte]
                  (u/seq-nodes (into [shared uast (u/app (u/v 'clj/init!))] body)))
           (map u/sexp->uast bundled-primitive-forms))))


(def bundled-uast (with-bundled-runtime))


(def bundled-names
  "Bundled runtime store definitions accepted by admission scans."
  (into (set runtime-keys) (map first) (concat definitions effect/definitions)))


(def module-definitions
  (core/module-definitions "clj" :clj/uninit runtime-keys definitions))


(def module-uast
  (u/annotate-call-sites
    (core/module-tree module-definitions
                      (mapv u/sexp->uast '[(require 'yang.call) (require 'yrt.effect)]))))


(def ^:private module-free-names (core/free-name-vec module-uast))


(defn module-spec
  "Emit the clj module pinned to the installed calling and effect runtimes."
  [registry call-address effect-address]
  (let [requires {call/module-name call-address, effect/module-name effect-address}]
    (prelude-admission/module-spec
      {:module-name module-name
       :ast module-uast
       :exports module-exports
       :requires requires
       :free-names module-free-names
       :skip? #(contains? requires (some-> % namespace symbol))
       :registry registry
       :message "the clj module reads an undeclared name"
       :refusal-ns "yang.clojure"})))


(defn outcome-adapter
  "Wrap host function `f`, a primitive declaring domain failures, as an
   outcome-returning one: success is [:yin.k/ok value], and the module's own
   refusal (ex-info with message `refusal-message` and :yin.vm.data/op and
   :yin.vm.data/reason in its data) is [:yin.k/error data] with those keys.
   Any other host exception propagates: an unexpected VM or compiler failure
   is never a successful rejection."
  [refusal-message f]
  (fn [& args]
    (try
      [:yin.k/ok (apply f args)]
      (catch #?(:clj Throwable, :cljs :default, :cljd Object) e
        (let [data (ex-data e)]
          (if (and (= refusal-message (ex-message e))
                   (contains? data :yin.vm.data/op)
                   (contains? data :yin.vm.data/reason))
            [:yin.k/error data]
            (throw e)))))))


(def library-adapters
  "The retired `:adapters` map a program using `library` once compiled with:
   the data exports whose refusal the collection predicates read as data.
   The continuation port rewrote those predicates onto the `data/*` and
   `kind/*` host predicates, so no `portable/*` adapter is consulted; the
   map stays for the `library-opts`/`unit-opts` fixtures and the prelude's
   `admit` registration."
  {'data/count 'portable/count
   'data/peek 'portable/peek
   'data/dissoc 'portable/dissoc
   'data/disj 'portable/disj
   'data/str-length 'portable/str-length})


(def kind-module
  "The `kind` host module's exports, symbol to host function: the irreducible
   kind and name primitives (see the library notes). Every export is pure."
  (merge
    {'symbol? symbol?
     'keyword? keyword?
     'name name
     'namespace namespace
     'symbol symbol
     'keyword keyword
     'str str
     'int? int?
     'with-meta with-meta
     'meta meta
     'coll? coll?
     'set? set?}
    #?(:clj {'var? var?} :default {})))


(def kind-arities
  "The published arities of each `kind` export."
  {'symbol? [1], 'keyword? [1], 'name [1], 'namespace [1], 'symbol [1 2], 'keyword [1 2]
   'str [0 1 2 3 4 5 6 :variadic], 'int? [1], 'var? [1], 'with-meta [2], 'meta [1]
   'coll? [1], 'set? [1]})


(defn register-library-module
  "Return `registry` with host module `name` installed over `entries`
   (`[export function]` pairs) at `arities`, each export pure."
  [registry name entries arities]
  (module/register-host-module
    registry name
    (into {} entries)
    (into {} (map (fn [[op _]] [op (vm/primitive-profile op :pure (get arities op [1]) #{} :none)]))
          entries)))


(defn admit
  "Return `registry` with the guest library's host surface installed: the
   `kind` module and the `portable` adapters of the data exports the
   collection predicates read (yang.clojure's `library-adapters`). A
   linked clojure composition registers them beside the other runtimes'
   registry surfaces; a composition without a clojure program reads none
   of them."
  [registry]
  (let [portable (into {}
                       (map (fn [[source _]]
                              (let [op (symbol (name source))]
                                [op (outcome-adapter data/refusal-message
                                                          (get data/data-module op))])))
                       library-adapters)]
    (-> registry
        (register-library-module 'kind kind-module kind-arities)
        (register-library-module
          'portable portable
          (into {} (map (fn [op] [op (:yin.k/arities (get data/data-profiles op))]))
                (keys portable))))))
