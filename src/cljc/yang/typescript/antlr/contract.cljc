(ns yang.typescript.antlr.contract
  "TypeScript hook roles and emitted call shapes for the two revisions.")


(def legacy-id :yang.typescript/ts-v1)
(def clojure-id :yang.typescript/clojure-core-v1)


;; [exact role, lambda arity, frozen js export, clj.ts export, required capability]
(def ^:private hooks
  '[[:coll/get 2 get-value get-item]
    [:coll/map-put 3 nil map-put]
    [:coll/property-key 1 to-property-key property-key]
    [:coll/spread 2 args-spread spread :calls/spread]
    [:coll/vconj 2 vconj vconj]
    [:coll/vector 1 new-array vector]
    [:ctrl/call-ec 1 call-ec call-ec]
    [:ctrl/for-in-next 1 for-in-next for-in-next :control/for-in]
    [:ctrl/for-in-start 1 for-in-start for-in-start :control/for-in]
    [:ctrl/raise 1 throw raise :control/exceptions]
    [:ctrl/try 2 try try :control/exceptions]
    [:ctrl/try-finally 2 try-finally try-finally :control/exceptions]
    [:env/const-assign 0 const-assign! const-assign :mutation/locals]
    [:env/global-get 2 nil global-get]
    [:legacy/global-get 1 global-get nil]
    [:env/local-get 2 lexical-get local-get :mutation/locals]
    [:fn/arg 2 arg arg :calls/positional]
    [:fn/call 3 call call :calls/positional]
    [:fn/make 5 make-function make-fn :calls/positional]
    [:legacy/add-field! 3 add-field! nil :oo/classes]
    [:legacy/add-priv-method! 4 add-priv-method! nil :oo/classes]
    [:legacy/args-object 1 args-object nil :calls/arguments]
    [:legacy/bit-and 2 bit-and nil :op/bitwise]
    [:legacy/bit-not 1 bit-not nil :op/bitwise]
    [:legacy/bit-or 2 bit-or nil :op/bitwise]
    [:legacy/bit-xor 2 bit-xor nil :op/bitwise]
    [:legacy/class-heritage 1 class-heritage nil :oo/classes]
    [:legacy/construct 2 construct nil :oo/classes]
    [:legacy/create-data-prop! 3 create-data-prop! nil]
    [:legacy/def-class-accessor! 4 def-class-accessor! nil :oo/classes]
    [:legacy/def-class-method! 3 def-class-method! nil :oo/classes]
    [:legacy/def-static-private! 4 def-static-private! nil :oo/classes]
    [:legacy/define-accessor-half! 5 define-accessor-half! nil]
    [:legacy/define-field! 3 define-field! nil :oo/classes]
    [:legacy/delete-value 2 delete-value nil :mutation/delete]
    [:legacy/derived-result 2 derived-result nil :oo/classes]
    [:legacy/global-set 2 global-set nil :scope/global]
    [:legacy/global-typeof 1 global-typeof nil :op/typeof]
    [:legacy/in-op 2 in-op nil :op/in]
    [:legacy/instanceof 2 instanceof nil :oo/classes]
    [:legacy/literal-proto! 2 literal-proto! nil]
    [:legacy/make-class 6 make-class nil :oo/classes]
    [:legacy/make-function-nt 6 make-function-nt nil :oo/classes]
    [:legacy/make-object 1 make-object nil]
    [:legacy/new-object 0 new-object nil]
    [:legacy/priv-get 2 priv-get nil :oo/classes]
    [:legacy/priv-has 2 priv-has nil :oo/classes]
    [:legacy/priv-set 3 priv-set nil :oo/classes]
    [:legacy/private-name 1 private-name nil :oo/classes]
    [:legacy/put-value 3 put-value nil]
    [:legacy/sar 2 sar nil :op/bitwise]
    [:legacy/shl 2 shl nil :op/bitwise]
    [:legacy/shr 2 shr nil :op/bitwise]
    [:legacy/static-init! 2 static-init! nil :oo/classes]
    [:legacy/super-call 4 super-call nil :oo/classes]
    [:legacy/super-get 3 super-get nil :oo/classes]
    [:legacy/super-set 4 super-set nil :oo/classes]
    [:legacy/this-after-super 1 this-after-super nil :oo/classes]
    [:legacy/throw-reference-error 1 throw-reference-error nil]
    [:legacy/typeof 1 typeof nil :op/typeof]
    [:op/add 2 add add]
    [:op/div 2 div div]
    [:op/ge 2 ge ge]
    [:op/gt 2 gt gt]
    [:op/kind 1 kind-of num-kind]
    [:op/le 2 le le]
    [:op/loose-eq 2 loose-eq equiv]
    [:op/lt 2 lt lt]
    [:op/mod 2 mod mod]
    [:op/mul 2 mul mul]
    [:op/neg 1 neg neg]
    [:op/pos 1 pos to-numeric]
    [:op/pow 2 pow pow]
    [:op/str-concat 2 str-concat str-concat]
    [:op/strict-eq 2 strict-eq equiv]
    [:op/sub 2 sub sub]
    [:op/to-numeric 1 to-numeric to-numeric]
    [:op/to-string 1 to-string str]
    [:op/truthy 1 to-boolean truthy]
    [:unit/init 0 init! nil]])


(def entry-lowering
  {legacy-id {:hook :unit/run-main :arity 1 :params '[body]
              :call-shapes #{1} :operands [:body-fn]}
   clojure-id {:hook :unit/run-main :arity 2 :params '[body-fn ambient-resolver]
               :call-shapes #{2} :operands [:body-fn :ambient-resolver]}})


(def hook-abi
  (into {:unit/run-main {:arity 2 :required-by []}}
        (map (fn [[role arity _ _ capability]]
               [role (cond-> {:arity arity :required-by (if capability [capability] [])}
                       (= role :legacy/make-object) (assoc :call-shapes #{0 1}))]))
        hooks))


(def ^:private legacy-hooks
  (into {:unit/run-main 'js/run-main}
        (keep (fn [[role _ export]]
                (when export [role (symbol "js" (name export))])))
        hooks))


(def ^:private clojure-hooks
  (into {:unit/run-main 'clj.ts/run-main}
        (keep (fn [[role _ _ export]]
                (when export [role (symbol "clj.ts" (name export))])))
        hooks))


(def ^:private clojure-capabilities
  {:mutation/locals :cell
   :mutation/subscript :refused
   :mutation/attribute :refused
   :mutation/delete :refused
   :scope/global :refused
   :scope/nonlocal :supported
   :calls/positional :supported
   :calls/spread :supported
   :calls/varargs :refused
   :calls/defaults :refused
   :calls/arguments :refused
   :calls/receiver :refused
   :destructuring/unpack :refused
   :expr/array-holes :refused
   :expr/comprehensions :refused
   :expr/object-accessors :refused
   :expr/object-spread :refused
   :control/exceptions :supported
   :control/except-typed :refused
   :control/for-in :supported
   :control/for-of :refused
   :control/generators :refused
   :control/with :refused
   :oo/classes :refused
   :op/bitwise :refused
   :op/typeof :refused
   :op/in :refused
   :typescript/enums :refused
   :modules/export :refused
   :modules/import :ambient-only})


(def ^:private legacy-capabilities
  (merge clojure-capabilities
         (zipmap [:mutation/subscript :mutation/attribute :mutation/delete :scope/global
                  :calls/arguments :calls/receiver :expr/array-holes :expr/object-accessors
                  :oo/classes :op/bitwise :op/typeof :op/in :typescript/enums]
                 (repeat :supported))
         {:modules/import :refused}))


(def ^:private clojure-literals
  {:nil {:kind :raw :value nil}
   :undefined {:kind :raw :value nil}
   :bool {:kind :raw}
   :str {:kind :raw}
   :float {:kind :float64-raw}
   :int {:kind :raw-or-call :safe-max-bits 53 :overflow-op 'clj.ts/int-lit}
   :unbound {:kind :raw :value :clj/unbound}
   :done {:kind :raw :value :clj.ts/done}})


(def ^:private legacy-literals
  (merge clojure-literals
         {:nil {:kind :raw :value :js/null}
          :undefined {:kind :raw :value :js/undefined}
          :str {:kind :tagged-map :tag :js/str}
          :str-units {:kind :tagged-map :tag :js/units}
          :int {:kind :raw-or-call :safe-max-bits 53 :overflow-op 'js/string->bigint}
          :unbound {:kind :raw :value :js/tdz}
          :hole {:kind :raw :value :js/hole}
          :none {:kind :raw :value :js/none}
          :done {:kind :raw :value :js/done}}))


(def ^:private clojure-projections
  (into {"exInfo" {:module 'clj.ts :export 'ex-info :kind :cell-accessor}}
        (map (fn [[alias export]]
               [alias {:module 'clojure.core :export export :kind :cell-accessor}]))
        '[["identity" identity] ["inc" inc] ["dec" dec] ["str" str] ["count" count]
          ["first" first] ["rest" rest] ["second" second] ["cons" cons] ["conj" conj]
          ["assoc" assoc] ["assocIn" assoc-in] ["get" get] ["getIn" get-in]
          ["update" update] ["updateIn" update-in] ["map" map] ["filter" filter]
          ["reduce" reduce] ["into" into] ["keys" keys] ["vals" vals] ["merge" merge]
          ["isEmpty" empty?] ["isNil" nil?] ["isMap" map?] ["isVector" vector?]
          ["isSet" set?]]))


(defn descriptor [id pins]
  (let [legacy? (= id legacy-id)]
    {:yang.semantics/schema 1
     :yang.semantics/id id
     :yang.semantics/syntax :typescript
     :yang.semantics/prelude-module (if legacy? 'js 'clj.ts)
     :yang.semantics/ambient-modules (mapv (fn [[module address]] {:module module :address address}) pins)
     :yang.semantics/capabilities (if legacy? legacy-capabilities clojure-capabilities)
     :yang.semantics/literals (if legacy? legacy-literals clojure-literals)
     :yang.semantics/hooks (if legacy? legacy-hooks clojure-hooks)
     :yang.semantics/ambient-projections (if legacy? {} clojure-projections)}))


(defn frontend-contract [legacy-pins clojure-pins]
  (let [grants (fn [pins descriptor]
                 (let [literal (get-in descriptor [:yang.semantics/literals :int :overflow-op])
                       grant (fn [target role]
                               [(get pins (symbol (namespace target))) target role])]
                   (into #{(grant literal [:literal :int])}
                         (concat (map (fn [[role target]] (grant target role))
                                      (:yang.semantics/hooks descriptor))
                                 (map (fn [[alias {:keys [module export kind]}]]
                                        (grant (symbol (str module) (name export))
                                               [:projection alias kind]))
                                      (:yang.semantics/ambient-projections descriptor))))))]
    {:syntax :typescript
     :hooks hook-abi
     :required-hooks (into #{} (comp (filter (fn [[role _ legacy clojure cap]]
                                                (and legacy clojure (nil? cap))))
                                     (map first))
                           hooks)
     :literals {:int {:arity 1}}
     :required-literals (set (keys clojure-literals))
     :entry-lowering entry-lowering
     :roles (into (grants legacy-pins (descriptor legacy-id []))
                  (grants clojure-pins (descriptor clojure-id [])))}))
