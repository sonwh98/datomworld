(ns yang.python.antlr.contract
  "The Python grammar's hook ABI and the two versioned entry call shapes.")


(def legacy-id :yang.python/py-v1)
(def clojure-id :yang.python/clojure-core-v1)


;; [exact role, operand count, frozen py export, Clojure-profile export]
(def ^:private shared-hooks
  '[[:op/add 2 add add]
    [:op/sub 2 sub sub]
    [:op/mul 2 mul mul]
    [:op/truediv 2 truediv truediv]
    [:op/floordiv 2 floordiv floordiv]
    [:op/mod 2 mod mod]
    [:op/pow 2 pow pow]
    [:op/neg 1 neg neg]
    [:op/pos 1 pos pos]
    [:op/eq 2 eq eq]
    [:op/ne 2 ne ne]
    [:op/lt 2 lt lt]
    [:op/le 2 le le]
    [:op/gt 2 gt gt]
    [:op/ge 2 ge ge]
    [:op/lnot 1 lnot lnot]
    [:op/truthy 1 truthy truthy]
    [:coll/vconj 2 vconj vconj]
    [:coll/list 1 list list]
    [:coll/tuple 1 tuple tuple]
    [:coll/set 1 set-from set-from]
    [:coll/dict 1 dict-from map-from]
    [:coll/getitem 2 getitem get-item]
    [:comp/list-new 1 list comp-list-new]
    [:comp/list-append 2 list-append comp-list-append]
    [:comp/set-new 0 set-new comp-set-new]
    [:comp/set-add 2 set-add comp-set-add]
    [:comp/dict-new 0 dict-new comp-dict-new]
    [:comp/dict-set 3 dict-set comp-dict-set]
    [:comp/finish 1 nil comp-finish]
    [:env/local-get 2 local-get local-get]
    [:env/global-get 2 global-get global-get]
    [:env/global-set 3 global-set global-set]
    [:fn/arg 2 arg arg]
    [:fn/make 5 make-function make-fn]
    [:fn/call 2 call call]
    [:ctrl/for-each 2 for-each for-each]
    [:ctrl/try 3 try try]
    [:ctrl/try-finally 2 try-finally try-finally]
    [:ctrl/raise 1 raise raise]
    [:ctrl/call-ec 1 call-ec call-ec]])


(def ^:private legacy-only
  ;; The extensions expose the existing shapes of the native runtime. The
  ;; persistent profile can use its shared hooks or refuse these constructs.
  '[[as-exception 1]
    [ascii-conv 1]
    [assert 2]
    [bitand 2]
    [bitor 2]
    [bitxor 2]
    [call-kw 3 :calls/kwargs]
    [class-ns-del 2 :mutation/delete]
    [class-ns-get 3 :oo/classes]
    [delattr 2 :mutation/delete]
    [delattr-quiet 2]
    [exc-matches 2 :control/except-typed]
    [extend 2]
    [format 2]
    [getattr 2]
    [global-del 2 :mutation/delete]
    [global-del-quiet 2]
    [global-or 3]
    [iadd 2]
    [import 2 :modules/import]
    [import-fail 1 :modules/import]
    [import-from 2 :modules/import]
    [import-module? 1 :modules/import]
    [import-star 2 :modules/import]
    [import-sub 4 :modules/import]
    [imul 2]
    [in 2]
    [invert 1]
    [is 2]
    [is-not 2]
    [iter 1 :control/generators]
    [iter-at 2]
    [iterable 1]
    [kw-extend 2 :calls/kwstar]
    [lshift 2]
    [make-class 2 :oo/classes]
    [make-exc 2]
    [make-generator 2 :control/generators]
    [not-in 2]
    [object-class 0 :oo/classes]
    [result! 1]
    [rshift 2]
    [setattr 3 :mutation/attribute]
    [setitem 3 :mutation/subscript]
    [slice 3]
    [str 1]
    [str-conv 2]
    [unpack 2 :destructuring/unpack]
    [unpack-star 3 :destructuring/unpack]
    [with 2 :control/with]
    [yield 2 :control/generators]
    [yield-from 2 :control/generators]])


(def ^:private shared-requirements
  {:env/local-get [:mutation/locals]
   :fn/arg [:calls/positional]
   :fn/make [:calls/positional]
   :fn/call [:calls/positional]
   :comp/list-new [:expr/comprehensions]
   :comp/list-append [:expr/comprehensions]
   :comp/set-new [:expr/comprehensions]
   :comp/set-add [:expr/comprehensions]
   :comp/dict-new [:expr/comprehensions]
   :comp/dict-set [:expr/comprehensions]
   :ctrl/try [:control/exceptions]
   :ctrl/try-finally [:control/exceptions]
   :ctrl/raise [:control/exceptions]})


(def hook-abi
  (into {:unit/run-main {:arity 2 :required-by []}}
        (concat (map (fn [[role arity]]
                       [role {:arity arity :required-by (get shared-requirements role [])}])
                     shared-hooks)
                (map (fn [[export arity capability]]
                       [(keyword "legacy" (name export))
                        {:arity arity :required-by (if capability [capability] [])}])
                     legacy-only))))


(def entry-lowering
  {legacy-id {:hook :unit/run-main :arity 1 :params '[body]
              :call-shapes #{1} :operands [:body-fn]}
   clojure-id {:hook :unit/run-main :arity 2 :params '[body-fn ambient-resolver]
               :call-shapes #{2} :operands [:body-fn :ambient-resolver]}})


(def legacy-hooks
  (into {:unit/run-main 'py/run-main :comp/finish nil}
        (concat (keep (fn [[role _ export]]
                        (when export [role (symbol "py" (name export))])) shared-hooks)
                (map (fn [[export]]
                       [(keyword "legacy" (name export)) (symbol "py" (name export))])
                     legacy-only))))


(def clojure-hooks
  (into {:unit/run-main 'clj.py/run-main}
        (map (fn [[role _ _ export]] [role (symbol "clj.py" (name export))]))
        shared-hooks))


(def clojure-capabilities
  {:mutation/locals :cell
   :mutation/subscript :refused
   :mutation/attribute :refused
   :mutation/delete :refused
   :scope/global :supported
   :scope/nonlocal :supported
   :calls/positional :supported
   :calls/varargs :supported
   :calls/defaults :refused
   :calls/kwonly :refused
   :calls/kwstar :refused
   :calls/kwargs :refused
   :destructuring/unpack :refused
   :expr/comprehensions :supported
   :control/exceptions :supported
   :control/except-typed :refused
   :control/generators :refused
   :control/with :refused
   :oo/classes :refused
   :modules/import :ambient-only})


(def legacy-capabilities
  (assoc (zipmap (keys clojure-capabilities) (repeat :supported)) :mutation/locals :cell))


(def legacy-literals
  {:nil {:kind :raw :value :py/None}
   :bool {:kind :raw}
   :str {:kind :tagged-map :tag :py/str}
   :float {:kind :tagged-map :tag :py/float}
   :int {:kind :raw-or-call :safe-max-bits 53 :overflow-op 'py/int-lit}
   :unbound {:kind :raw :value :py/unbound}})


(def clojure-literals
  {:nil {:kind :raw :value nil}
   :bool {:kind :raw}
   :str {:kind :raw}
   :float {:kind :float64-raw}
   :int {:kind :raw-or-call :safe-max-bits 53 :overflow-op 'clj.py/int-lit}
   :unbound {:kind :raw :value :clj/unbound}})


(def clojure-projections
  (into {"ex_info" {:module 'clj.py :export 'ex-info :kind :cell-accessor}}
        (map (fn [[alias export]]
               [alias {:module 'clojure.core :export export :kind :cell-accessor}]))
        '[["identity" identity] ["inc" inc] ["dec" dec] ["str" str] ["count" count]
          ["first" first] ["rest" rest] ["second" second] ["cons" cons] ["conj" conj]
          ["assoc" assoc] ["assoc_in" assoc-in] ["get" get] ["get_in" get-in]
          ["update" update] ["update_in" update-in] ["map" map] ["filter" filter]
          ["reduce" reduce] ["into" into] ["keys" keys] ["vals" vals] ["merge" merge]
          ["is_empty" empty?] ["is_nil" nil?] ["is_map" map?] ["is_vector" vector?]
          ["is_set" set?]]))


(defn descriptor [id pins]
  (let [legacy? (= id legacy-id)]
    {:yang.semantics/schema 1
     :yang.semantics/id id
     :yang.semantics/syntax :python
     :yang.semantics/prelude-module (if legacy? 'py 'clj.py)
     :yang.semantics/ambient-modules (mapv (fn [[module address]] {:module module :address address}) pins)
     :yang.semantics/capabilities (if legacy? legacy-capabilities clojure-capabilities)
     :yang.semantics/literals (if legacy? legacy-literals clojure-literals)
     :yang.semantics/hooks (if legacy? legacy-hooks clojure-hooks)
     :yang.semantics/ambient-projections (if legacy? {} clojure-projections)}))


(defn frontend-contract
  "Grant exact roles to the independently pinned exports of the two profiles."
  [legacy-pins clojure-pins]
  (let [grants (fn [pins hooks literal projections]
                 (into #{[(get pins (symbol (namespace literal))) literal [:literal :int]]}
                       (concat (keep (fn [[role export]]
                                       (when export
                                         [(get pins (symbol (namespace export))) export role]))
                                     hooks)
                               (map (fn [[alias {:keys [module export kind]}]]
                                      [(get pins module) (symbol (str module) (name export))
                                       [:projection alias kind]])
                                    projections))))]
    {:syntax :python
     :hooks hook-abi
     :required-hooks (into #{} (comp (map first) (remove #{:comp/finish})
                                    (remove shared-requirements))
                           shared-hooks)
     :literals {:int {:arity 1}}
     :required-literals #{:nil :bool :str :float :int :unbound}
     :entry-lowering entry-lowering
     :roles (into (grants legacy-pins legacy-hooks 'py/int-lit {})
                  (grants clojure-pins clojure-hooks 'clj.py/int-lit clojure-projections))}))
