(ns yang.core.module
  "Module emitter machinery shared by every language frontend
   (docs/design/yang.typescript.md §17, lift L3): the linker module naming
   rule, the `require` and wide-application tree nodes, free-name
   declaration over a host registry, and the `module-spec` and SCC bundle
   shapes `yin.vm.linker.publish/publish-module!` takes. A language supplies
   its prefix, its runtime module and its module AST; nothing here knows a
   source language."
  (:require
    [clojure.string :as str]
    [yang.core.prelude :as prelude]
    [yang.core.uast :as u]
    [yin.vm :as vm]
    [yin.vm.module :as module]))


(defn module-symbol
  "The linker module name of source module `name` under language `prefix`
   (\"py\", \"php\", \"js\"): `<prefix>m.` then the name with each dot a `$`,
   so no linker module name is a dotted prefix of another."
  [prefix name]
  (symbol (str prefix "m." (str/replace name "." "$"))))


(defn require-node
  "(require 'm) as a tree node."
  [m]
  (u/sexp->uast (list 'require (list 'quote m))))


(defn module-tree
  "A wide application: all definitions remain at module level."
  [operands]
  (prelude/wide-app operands))


(defn declare-free-name
  "The `:primitives` entry free name `n` declares over `registry`, or nil
   when `covered` (the requirement-pinned module names) covers it. A name
   no `vm/primitives` profile or host export supplies throws, with
   `refusal` under `refusal-key` in its data."
  [refusal-key refusal registry covered n]
  (if (and (symbol? n) (some? (namespace n))
           (contains? covered (symbol (namespace n))))
    nil
    (if-let [profile (vm/profile-of vm/primitives n)]
      [n profile]
      (if-let [address (module/host-export-profile registry n)]
        (let [entry (module/module-entry (:modules registry)
                                         (symbol (namespace n)))
              f (get-in entry [:slice (symbol (name n))])]
          [n {:yin.k/profile address,
              :yin.k/effects
              (get (:callable-effects registry) f #{})}])
        (throw (ex-info "the module reads an undeclared name"
                        {refusal-key refusal, :name n}))))))


(defn free-primitives
  "The `:primitives` map declaring every free name of `ast` over `registry`."
  [refusal-key refusal registry covered ast]
  (let [free (remove (conj (prelude/defined-keys ast) 'yin/def)
                     (prelude/free-names ast))]
    (into {}
          (keep (partial declare-free-name refusal-key refusal registry covered))
          (sort-by str free))))


(defn module-spec
  "`publish-module!`'s spec for module `name`: `ast` with `exports`, requiring
   the runtime module `runtime-name` at `runtime-address` and each of `deps`
   ({module-symbol address}). A free name under a required module is covered
   by it; any other is declared as `declare-free-name` does."
  [refusal-key refusal registry
   {:keys [name ast exports runtime-name runtime-address deps]}]
  (let [requires (into {runtime-name runtime-address} deps)]
    {:name name,
     :ast ast,
     :exports exports,
     :requires requires,
     :primitives (free-primitives refusal-key refusal registry
                                  (set (keys requires)) ast)}))


(defn alias-spec
  "A thin member alias of the SCC bundle `bundle-name`: it requires the
   bundle and defines `spec` and `body` as the bundle's `spec-key` and
   `body-key` exports."
  [name exports require-profile bundle-name spec-key body-key]
  {:name name,
   :exports exports,
   :primitives {'require require-profile},
   :ast (module-tree
          [(require-node bundle-name)
           (u/def! 'spec (u/v (symbol (str bundle-name) (str spec-key))))
           (u/def! 'body (u/v (symbol (str bundle-name) (str body-key))))])})


(defn scc-module-specs
  "The SCC publication shape: the `bundle` spec first, then the member alias
   specs (each `alias-spec`'s output) in member order."
  [bundle aliases]
  (into [bundle] aliases))
