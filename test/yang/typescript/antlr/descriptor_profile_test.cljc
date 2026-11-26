(ns yang.typescript.antlr.descriptor-profile-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [dao.host.integer :as host-int]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [yang.core.semantics :as semantics]
            [yang.core.uast :as u]
            [yang.frontend :as frontend]
            [yang.typescript.antlr.clj-runtime :as runtime]
            [yang.typescript.antlr.contract :as contract]
            [yang.typescript.antlr.frontend :as ts-frontend]
            [yang.typescript.antlr.linked-harness :as harness]
            [yang.typescript.antlr.lower :as lower]
            [yang.typescript.antlr.lower-portable-test :as portable]
            [yang.typescript.antlr.profiles :as profiles]
            [yin.vm :as vm]
            [yin.vm.linker.interpreter :as link]
            #?@(:cljd [["dart:io" :as dart-io]]
                :jolt []
                :clj [[yang.typescript.antlr.cst :as cst]
                      [yin.repl :as repl]
                      [yin.repl.frontends :as frontends]])))


(def ^:private fixtures
  (delay (let [path "test/resources/yang/typescript/clojure_core_packets.edn"]
           (edn/read-string
             #?(:cljd (.readAsStringSync (dart-io/File. path))
                :cljs (.readFileSync (js/require "fs") path "utf8")
                :clj (slurp path))))))


(defn- packet [id]
  (portable/packet (get-in @fixtures [id :form])))


(def ^:private composition
  (delay
    (let [store (mem/create-content-mem)
          {:keys [pins] :as composition} (profiles/prepare! store [contract/clojure-id])]
      (assoc composition :link-source
             (link/composition {:content-store store :name-env pins :derivation :trusted})))))


(defn- descriptor []
  (get-in @composition [:profiles contract/clojure-id :descriptor]))


(defn- refusal [f]
  (try (f) nil
       (catch #?(:cljd Object :clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (ex-data e))))


(defn- run-program [backend ast]
  (let [{:keys [registry link-source]} @composition]
    (vm/value (harness/run-linked backend ast {:modules registry :link-source link-source}))))


#?(:cljd nil
   :jolt nil
   :clj
   (deftest packets-agree-with-the-parser-test
     (doseq [[id {:keys [source form]}] @fixtures
             :let [parsed (cst/parse-source source)
                   helper (portable/packet form)
                   nodes #(mapv (fn [n] (select-keys n [:kind :rule :type :text :children]))
                                (:yang.cst/nodes %))]]
       (is (= :yang.cst/ok (:yang.cst/outcome parsed)) (str id))
       (is (= (nodes parsed) (nodes helper)) (str id)))))


(deftest entry-shapes-and-role-validation-test
  (let [{:keys [artifacts registry contract pins]} @composition
        d (descriptor)
        validate #(semantics/validate-descriptor % contract artifacts registry)
        replace-add #(assoc-in d [:yang.semantics/hooks :op/add] %)
        root (pins 'clj.ts)
        manifest (artifacts root)
        changed-tree (update runtime/module-uast :operands
                             (fn [operands]
                               (mapv (fn [node]
                                       (if (= 'add (get-in node [:operands 0 :value]))
                                         (assoc-in node [:operands 1] (u/lit 42))
                                         node))
                                     operands)))
        tree-address (:root (vm/ast->semantic-bytecode changed-tree))
        manifest (assoc manifest :yin.module/tree tree-address)
        changed-address (jing/segment-key manifest)
        changed-contract (update contract :roles
                                 (fn [roles]
                                   (into roles
                                         (keep (fn [[address target role]]
                                                 (when (= root address)
                                                   [changed-address target role])))
                                         roles)))
        changed (update d :yang.semantics/ambient-modules
                        #(mapv (fn [pin]
                                 (if (= 'clj.ts (:module pin))
                                   (assoc pin :address changed-address)
                                   pin))
                               %))]
    (is (= d (validate d)))
    (is (= #{1} (get-in contract/entry-lowering [contract/legacy-id :call-shapes])))
    (is (= #{2} (get-in contract/entry-lowering [contract/clojure-id :call-shapes])))
    (doseq [[target defect] [['clj.ts/neg :arity]
                             ['clj.ts/sub :export-role]
                             ['js/add :export]
                             ['cell/dsg__provenance :reserved-door]
                             ['clj/dsg__invoke :reserved-door]
                             ['yin/def :reserved-door]
                             ['clj.ts.rt/exports :reserved-door]
                             ['js.b/builtins :reserved-door]
                             ['yang.safepoint/poll :reserved-door]
                             ['clojure.core.impl/impl-inc :reserved-door]]]
      (is (= defect (:defect (refusal #(validate (replace-add target))))) (str target)))
    (is (= :yang.semantics/unqualified-hook
           (:reason (refusal #(validate (replace-add 'add))))))
    (is (= :non-lambda
           (:defect (refusal #(semantics/validate-descriptor
                               changed changed-contract
                               (assoc artifacts changed-address manifest tree-address changed-tree)
                               registry)))))
    (is (= :runtime-profile-pins
           (:defect (refusal #(lower/lower-packet nil {:descriptor changed})))))
    (is (= :export-role
           (:defect (refusal #(lower/lower-packet nil {:descriptor (replace-add 'clj.ts/sub)})))))
    (doseq [[revision symbol arity packet-id]
            [[contract/legacy-id 'js/run-main 1 :legacy]
             [contract/clojure-id 'clj.ts/run-main 2 :catalog]]
            :let [d (:descriptor (profiles/verify-builtin! revision))
                  program (lower/lower-packet (packet packet-id) {:prelude :linked :descriptor d})
                  entries (filter #(= symbol (get-in % [:operator :name]))
                                  (tree-seq map? #(concat (:operands %)
                                                          (keep % [:operator :body :test :consequent :alternate]))
                                            program))]]
      (is (= [arity] (mapv (comp count :operands) entries)) (str revision)))))


(def ^:private expected-refusals
  {:array-hole :expr/array-holes
   :attribute :mutation/attribute
   :bitwise :op/bitwise
   :classes :oo/classes
   :constructor :oo/classes
   :defaults :calls/defaults
   :delete :mutation/delete
   :destructuring :destructuring/unpack
   :generator :control/generators
   :global :scope/global
   :import-export :modules/import
   :import-module :modules/import
   :import-namespace :modules/import
   :rest :calls/varargs
   :subscript :mutation/subscript
   :surrogate :literals/utf16-units
   :typed-catch :control/except-typed
   :with :control/with})


(deftest capabilities-fail-before-emission-test
  (doseq [[id cap] expected-refusals prelude [:bundled :linked]
          :let [pk (packet id)
                refused (refusal #(lower/lower-packet pk {:descriptor (descriptor) :prelude prelude}))]]
    (is (= {:status :refused :reason :yang.semantics/unsupported-capability
            :capability cap :semantics-id contract/clojure-id}
           (select-keys refused [:status :reason :capability :semantics-id]))
        (str id " " prelude))
    (is (seq (:construct refused)) (str id " " prelude))
    (is (get (:yang.cst/nodes pk) (:node refused)) (str id " " prelude)))
  (let [[_ [[port diagnostic] :as outputs]]
        (lower/lower-transform {:descriptor (descriptor)} (packet :subscript))]
    (is (= 1 (count outputs)))
    (is (= :diagnostics port))
    (is (= :yang.semantics/unsupported-capability (:reason diagnostic)))
    (is (= :mutation/subscript (:capability diagnostic)))))


#?(:cljd nil
   :jolt nil
   :clj
   (deftest ambient-import-function-collision-test
     (let [catalog (ts-frontend/install frontend/empty-catalog "fixture" cst/parse-source)
           {:keys [binding]} (frontend/select catalog {:yang.frontend/id ts-frontend/id})
           pk ((:parse binding) (str "import {inc} from 'clojure.core';\n"
                                    "function inc(x) { return x + 100n; }\n"
                                    "inc(1n);"))]
       (is (= :yang.cst/ok (:yang.cst/outcome pk)))
       (is (= {:yang.ts/diagnostic :yang.ts/early-error
               :rule "importAliasName"
               :name "inc"}
              (select-keys (refusal #((:lower binding) pk))
                           [:yang.ts/diagnostic :rule :name]))))))


(def ^:private profile-goldens
  {:numeric {:value [6 (host-int/shift-left-big (host-int/promote 1) 70)
                     (host-int/shift-left-big (host-int/promote 1) 53) 1
                     (cbor/float64 4.5) (cbor/float64 3.5) (cbor/float64 -0.0)
                     true 7 (cbor/float64 0.25)]
             :roots [:segment/blake3-327e90f35f469b441557b3648a78d1b438c1e7a60ae885f07d83a84cac1191cb
                     :segment/blake3-32e4526b36a399ce8b3898a6fb3ba05f88d2f2e06514bf2f950a5ed9d94a68f1]}
   :collections {:value [{"a" [4] "z" 1} 4 "az" "{\"a\" [4], \"z\" 1}" "(\"a\" \"z\")" "([4] 1)"
                         true false false 11 "v4" true]
                 :roots [:segment/blake3-3162e54c44bc60449c3d1903074d5fd9c01800dccf7e2c71193ec5f823633d6f
                         :segment/blake3-14378f18370e69ad161126291e9e9b98e95ff33bdf4309680d8d2aa13ecace61]}
   :policy {:value [nil "{:clj.ts/error :unsupported-key, :key #<fn>}"
                    "{:clj.ts/error :unsupported-key, :key 1.0}"
                    "{:clj.ts/error :zero-division, :detail {:a 1, :b 0}, :policy :math/div-zero}"]
            :roots [:segment/blake3-e8da55d3c2694da72b50503075fc937232e9a9b95c63e4bc87850d92a153b9f3
                    :segment/blake3-7c8ceec29fb8419f012aaa06ac1e2ba03b90672707b8cda8084ec3b1c6c98442]}
   :unwind {:value ["caught-finally" {:yin.k/message "oops" :yin.k/data {"value" 1}}]
            :roots [:segment/blake3-ca355b592caf27417a0a3eacc098f965ce8bb3fa7d47a62919d1ea43edc4707b
                    :segment/blake3-127a5c6bf9cc36db4763693755f3363c18dd505b89a96556e2c9888f33bfbba5]}})


(deftest persistent-profile-goldens-test
  (doseq [[id {:keys [value roots]}] profile-goldens
          [prelude root] (map vector [:bundled :linked] roots)
          :let [ast (lower/lower-packet (packet id) {:descriptor (descriptor) :prelude prelude})]]
    (testing (str id " " prelude)
      (is (= root (:root (vm/ast->semantic-bytecode ast))))
      (doseq [backend [:semantic :stack :register]]
        (is (= (jing/segment-key value) (jing/segment-key (run-program backend ast)))
            (name backend))))))


(deftest default-selection-and-in-flight-snapshot-test
  (let [catalog (ts-frontend/install frontend/empty-catalog "fixture" packet)
        request {:yang.frontend/id ts-frontend/id}
        selected (frontend/select catalog request)
        {:keys [manifest binding]} selected
        pk ((:parse binding) :catalog)
        next-catalog (assoc catalog :yang.frontend/preferred [ts-frontend/id "ts-v1"])
        legacy (frontend/select next-catalog request)
        explicit (frontend/select catalog (assoc request :yang.frontend/revision "ts-v1"))
        ast ((:lower binding) pk)
        native ((get-in legacy [:binding :lower]) (packet :legacy))
        derived ((:derive binding) pk ast)]
    (is (= ["clojure-core-v1" "ts-v1"] (frontend/revisions catalog ts-frontend/id)))
    (is (= "clojure-core-v1" (:yang.frontend/revision manifest)))
    (is (= "ts-v1" (get-in legacy [:manifest :yang.frontend/revision])))
    (is (= (:manifest legacy) (:manifest explicit)))
    (is (false? (run-program :register ast)))
    (is (= "true" ((get-in explicit [:binding :render]) (run-program :register native))))
    (is (= (:yang.frontend/lowering-profile manifest) (:yang.frontend/lowering-profile derived)))
    (is (= (:yang.frontend/runtime-profile manifest) (:yang.frontend/runtime-profile derived)))
    (is (= (:closure (profiles/builtin contract/clojure-id)) (:yang.frontend/runtime-closure derived)))
    (is (= (jing/segment-key pk) (:yang.frontend/input derived)))
    (is (= (:root (vm/ast->semantic-bytecode ast)) (:yang.frontend/output derived)))
    (is (jing/segment-address? (:yang.frontend/ambient-resolver derived)))))


#?(:cljd nil
   :jolt nil
   :clj
   (deftest standard-request-selects-clojure-test
     (let [state (frontends/create-state {:lang :yang/typescript})
           [advanced displayed] (repl/eval-input state "1n == 1;")
           legacy (frontends/create-state {:lang [:yang/typescript "ts-v1"]})]
       (is (= "false" displayed))
       (is (= "true" (second (repl/eval-input legacy "console.log(1n == 1);"))))
       (is (= "clojure-core-v1"
              (:yang.frontend/revision (val (first (:frontend-derivations advanced)))))))))
