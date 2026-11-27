(ns yang.php.antlr.contract
  "The PHP grammar's hook ABI and the two versioned entry contracts.")


(def legacy-id :yang.php/php-v1)
(def clojure-id :yang.php/clojure-core-v1)


;; [exact role, operand count, frozen PHP export, persistent export]
(def ^:private shared
  '[[:op/add 2 add add]
    [:op/sub 2 sub sub]
    [:op/mul 2 mul mul]
    [:op/div 2 div div]
    [:op/mod 2 mod mod]
    [:op/pow 2 pow pow]
    [:op/neg 1 neg neg]
    [:op/pos 1 plus pos]
    [:op/concat 2 concat concat]
    [:op/eq 2 loose-eq eq]
    [:op/ne 2 loose-ne ne]
    [:op/identical 2 identical eq]
    [:op/not-identical 2 not-identical ne]
    [:op/lt 2 lt lt]
    [:op/le 2 le le]
    [:op/gt 2 gt gt]
    [:op/ge 2 ge ge]
    [:op/truthy 1 to-bool truthy]
    [:op/str 1 to-string string]
    [:op/nan 0 nan-const nan-const]
    [:op/inf 0 inf-const inf-const]
    [:coll/vconj 2 vconj vconj]
    [:coll/get 2 dim-get get-item]
    [:fn/call 2 call call]
    [:fn/make 4 make-function make-fn]
    [:ctrl/call-ec 1 call-ec call-ec]
    [:ctrl/finally 2 try-finally try-finally]
    [:ctrl/raise 1 throw-value raise]
    [:io/echo 1 echo nil]
    [:literal/int-radix 2 int-lit-radix int-lit-radix]
    [:unit/init 0 init! nil]])


;; Native operations with no persistent-collection or Clojure-family equivalent.
(def ^:private native
  '[[addref 1]
    [args-spread 2]
    [array-append 2]
    [array-append-ref 2]
    [array-put 3]
    [array-put-ref 3]
    [array-spread 2]
    [array? 1]
    [bi-compact 2]
    [bi-extract 2]
    [bind-class-map! 2]
    [bind-marker 2]
    [bind-path-map! 1]
    [bit-and 2]
    [bit-not 1]
    [bit-or 2]
    [bit-xor 2]
    [call-named 3]
    [call-with-binding-profile 4]
    [callable-in 2]
    [check-param-cell! 5]
    [class-by-name 1]
    [class-const-in 3]
    [clone-obj 2]
    [closure-env 4]
    [closure-static 4]
    [coerce-arg 5]
    [coerce-return 3]
    [coerce-return-s 4]
    [const-declare! 2]
    [const-get 2]
    [const-get-fq 1]
    [copy-capture 1]
    [ctx-has-this? 1]
    [ctx-this 1]
    [ctx-this-or-null 1]
    [dec 1]
    [declare-class! 1]
    [declare-early-function! 2]
    [declare-function! 2]
    [declare-late! 2]
    [delref 1]
    [dict-bind! 3]
    [dict-capture 2]
    [dict-get 2]
    [dict-isset? 2]
    [dict-make-ref! 2]
    [dict-peek 2]
    [dict-put! 3]
    [dict-set! 3]
    [dict-unset! 2]
    [dim-peek 2]
    [empty-array 0]
    [eval-run 2]
    [exit 1]
    [fe-has-next 2]
    [fe-key 2]
    [fe-next 4]
    [foreach-array 1]
    [foreach-current 1]
    [foreach-key 1]
    [foreach-next 1]
    [foreach-prepare 2]
    [foreach-valid 1]
    [gather-variadic 2]
    [global-bind! 2]
    [global-capture 1]
    [global-get 1]
    [global-isset? 1]
    [global-make-ref! 1]
    [global-peek 1]
    [global-put! 2]
    [global-set! 2]
    [global-unset! 1]
    [globals-put! 1]
    [globals-read 0]
    [has-this? 1]
    [hoist-class! 1]
    [inc 1]
    [include-computed 4]
    [include-run 5]
    [instance-of 2]
    [lc 1]
    [list-get 2]
    [local-capture 1]
    [local-get 2]
    [local-isset? 1]
    [local-peek 1]
    [local-put! 2]
    [local-set! 2]
    [local-unset! 1]
    [make-closure 1]
    [make-generator 3]
    [make-ref-cv 1]
    [marker-of-cv 1]
    [marker-of-global 1]
    [method-call 4]
    [never-returned 1]
    [new-begin 2]
    [new-finish 3]
    [not-passed 6]
    [param-default 5]
    [param-ref 2]
    [param-ref-typed 6]
    [parent-of 1]
    [path-assign 3]
    [path-bind 3]
    [path-ref 2]
    [path-unset 2]
    [prop-key 2]
    [prop-write! 4]
    [raise 1]
    [ref-value 1]
    [release-cvs 3]
    [release-tmps 1]
    [resolve-fn 2]
    [resolve-fn2 3]
    [return-none 2]
    [scope-adopt! 3]
    [scope-new 0]
    [scope-of 2]
    [scope-release! 1]
    [set-strict! 1]
    [shl 2]
    [shr 2]
    [silence 1]
    [spaceship 2]
    [sprop-cell 3]
    [sprop-ref 3]
    [static-bind! 4]
    [static-call 6]
    [static-of 1]
    [static-or-nil 1]
    [this-of 1]
    [this-or-nil 1]
    [this-or-null 1]
    [throw-error 2]
    [tmp! 1]
    [tmp-mark 0]
    [to-float 1]
    [to-int 1]
    [try 3]
    [typed-inc 1]
    [unhandled-match 1]
    [warn 2]
    [yield 3]
    [yield-from 2]])


;; State and collections use separate roles where their operand conventions
;; differ from PHP's native task-local runtime.
(def ^:private persistent
  '[[:env/get 2 global-get]
    [:env/set 3 global-set]
    [:env/declare-fn 3 declare-fn]
    [:env/resolve-fn 3 resolve-fn]
    [:fn/arg 2 arg]
    [:fn/rest 2 rest-args]
    [:coll/empty 0 empty-array]
    [:coll/append 2 array-append]
    [:coll/put 3 array-put]
    [:io/write 2 echo]
    [:io/attach 3 attach-output]])


(def ^:private shared-requirements
  {:io/echo [:io/native-output]
   :unit/init [:runtime/native]
   :fn/make [:calls/positional]
   :fn/call [:calls/positional]
   :ctrl/finally [:control/exceptions]
   :ctrl/raise [:control/exceptions]})


(def ^:private native-requirements
  {:php/call-named [:calls/kwargs]
   :php/args-spread [:calls/kwstar]
   :php/path-assign [:mutation/subscript]
   :php/local-unset! [:mutation/delete]
   :php/instance-of [:oo/classes]
   :php/yield [:control/generators]
   :php/yield-from [:control/generators]
   :php/make-generator [:control/generators]
   :php/include-run [:modules/import]
   :php/include-computed [:modules/import]})


(def hook-abi
  (into {:unit/run-main {:arity 2 :required-by []}}
        (concat (map (fn [[role arity]]
                       [role {:arity arity :required-by (get shared-requirements role [])}])
                     shared)
                (map (fn [[export arity]]
                       (let [role (keyword "php" (name export))]
                         [role {:arity arity :required-by (get native-requirements role [:runtime/native])}]))
                     native)
                (map (fn [[role arity]]
                       [role {:arity arity :required-by [:runtime/persistent]}])
                     persistent))))


(def entry-lowering
  {legacy-id {:hook :unit/run-main :arity 4
              :params '[body request response-stream diag-stream]
              :call-shapes #{2 4}
              :operands {2 [:body-fn :request-stream]
                         4 [:body-fn :request-stream :response-stream :diagnostics-stream]}}
   clojure-id {:hook :unit/run-main :arity 2
               :params '[body-fn ambient-resolver]
               :call-shapes #{2}
               :operands {2 [:body-fn :ambient-resolver]}}})


(defn entry-operands
  "The slots the selected version can emit at this operand count."
  [id argc]
  (let [{:keys [call-shapes operands]} (get entry-lowering id)
        slots (get operands argc)]
    (when-not (and (contains? call-shapes argc) (= argc (count slots)))
      (throw (ex-info "PHP entry shape refused"
                      {:yang.php/diagnostic :yang.semantics/invalid-descriptor
                       :status :refused :reason :yang.semantics/invalid-descriptor
                       :defect :arity :call-shape argc})))
    slots))


(def legacy-hooks
  (into {:unit/run-main 'php/run-main}
        (concat (map (fn [[role _ export]] [role (symbol "php" (name export))]) shared)
                (map (fn [[export]]
                       [(keyword "php" (name export)) (symbol "php" (name export))])
                     native))))


(def clojure-hooks
  (into {:unit/run-main 'clj.php/run-main}
        (concat (keep (fn [[role _ _ export]]
                        (when export [role (symbol "clj.php" (name export))]))
                      shared)
                (map (fn [[role _ export]] [role (symbol "clj.php" (name export))])
                     persistent))))


(def clojure-capabilities
  {:mutation/locals :cell
   :mutation/subscript :refused
   :mutation/attribute :refused
   :mutation/delete :refused
   :scope/global :refused
   :calls/positional :supported
   :calls/varargs :supported
   :calls/defaults :refused
   :calls/kwargs :refused
   :calls/kwstar :refused
   :calls/references :refused
   :calls/types :refused
   :destructuring/unpack :refused
   :collections/arrays :supported
   :collections/spread :refused
   :control/exceptions :supported
   :control/except-typed :refused
   :control/generators :refused
   :control/foreach :refused
   :oo/classes :refused
   :modules/import :refused
   :modules/eval :refused
   :strings/binary :refused
   :io/output :supported
   :io/native-output :refused
   :io/request :refused
   :runtime/native :refused
   :runtime/persistent :supported})


(def legacy-capabilities
  (assoc (zipmap (keys clojure-capabilities) (repeat :supported))
         :mutation/locals :cell :runtime/persistent :refused))


(def ^:private literals
  {:nil {:kind :raw}
   :unbound {:kind :raw :value nil}
   :bool {:kind :raw}
   :str {:kind :raw}
   :float {:kind :float64-raw}
   :int {:kind :raw-or-call :safe-max-bits 53 :overflow-op 'clj.php/int-lit}})


(def legacy-literals
  (-> literals
      (assoc-in [:nil :value] :php/null)
      (assoc-in [:unbound :value] :php/undef)
      (assoc-in [:int :overflow-op] 'php/int-lit)))


(def clojure-projections
  (into {}
        (map (fn [[alias export]]
               [alias {:module 'clojure.core :export export :kind :cell-accessor}]))
        '[["identity" identity] ["inc" inc] ["dec" dec] ["str" str] ["count" count]
          ["first" first] ["rest" rest] ["second" second] ["cons" cons] ["conj" conj]
          ["assoc" assoc] ["assoc_in" assoc-in] ["get" get] ["get_in" get-in]
          ["update" update] ["update_in" update-in] ["map" map] ["filter" filter]
          ["reduce" reduce] ["into" into] ["keys" keys] ["vals" vals] ["merge" merge]
          ["is_empty" empty?] ["is_null" nil?] ["is_map" map?] ["is_vector" vector?]
          ["is_set" set?] ["is_int" int?] ["is_string" string?] ["keyword" keyword]]))


(defn descriptor
  [id pins]
  (let [legacy? (= id legacy-id)]
    {:yang.semantics/schema 1
     :yang.semantics/id id
     :yang.semantics/syntax :php
     :yang.semantics/prelude-module (if legacy? 'php 'clj.php)
     :yang.semantics/ambient-modules (mapv (fn [[module address]] {:module module :address address}) pins)
     :yang.semantics/capabilities (if legacy? legacy-capabilities clojure-capabilities)
     :yang.semantics/literals (if legacy? legacy-literals literals)
     :yang.semantics/hooks (if legacy? legacy-hooks clojure-hooks)
     :yang.semantics/ambient-projections (if legacy? {} clojure-projections)}))


(defn frontend-contract
  "Grant each role to the independently pinned export that implements it."
  [legacy-pins clojure-pins]
  (let [grants (fn [pins descriptor]
                 (let [literal (get-in descriptor [:yang.semantics/literals :int :overflow-op])]
                   (into #{[(pins (symbol (namespace literal))) literal [:literal :int]]}
                         (concat (map (fn [[role target]] [(pins (symbol (namespace target))) target role])
                                      (:yang.semantics/hooks descriptor))
                                 (map (fn [[alias {:keys [module export kind]}]]
                                        [(pins module) (symbol (str module) (name export))
                                         [:projection alias kind]])
                                      (:yang.semantics/ambient-projections descriptor))))))]
    {:syntax :php
     :hooks hook-abi
     :required-hooks (into #{} (comp (map first) (remove #{:io/echo :unit/init})) shared)
     :literals {:int {:arity 1}}
     :required-literals #{:nil :unbound :bool :str :float :int}
     :entry-lowering entry-lowering
     :roles (into (grants legacy-pins (descriptor legacy-id []))
                  (grants clojure-pins (descriptor clojure-id [])))}))
