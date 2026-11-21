(ns yang.cc4-corpus
  "The cc4 budget common corpus: a faithful copy of the pinned cohort
   builder test/yang/clojure_callsite_test.cljc/replay-artifacts at
   origin/master 2175252d (sha256 of that file at that pin is recorded in
   the budget findings), factored with one knob: an EXCLUDED id set, so
   members the PRE-port pin refuses (the lower-unit member: pre-port
   check-top-level-discard! rejects a may-fail non-final form, which the
   port-era corpus relies on) can be omitted from BOTH revisions of the
   measurement, keeping the pre/post corpus identical. Nothing else
   differs from the cohort definition; the equality gate itself continues
   to use the cohort in the test namespace."
  (:require [clojure.walk :as walk]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [yang.clojure :as yang]
            [yang.clojure.prelude :as clj-prelude]
            [yang.core.call :as call]
            [yang.rt.effect :as effect]
            [yin.vm :as vm]
            [yin.vm.encoder :as encoder]
            [yin.vm.macro :as macro]
            [yin.vm.module :as module]
            [yin.vm.data :as data]
            [yin.vm.linker.publish :as publish]))


(def ^:private publish-registry
  (-> (module/default-registry)
      module/register-cell-module
      (data/register-data-module {::data/max-items 1048576})
      clj-prelude/admit))


(defn- expand-like-the-repl
  [ast]
  (let [store (macro/seed-store
                (macro/make-ctx {:token :callsite-test/standard-forms
                                 :source-medium :callsite-test/standard-forms})
                [macro/stdlib-forms])
        ctx (macro/make-ctx {:token "callsite-test"
                             :source-medium :program-in
                             :store store})]
    {:ctx ctx
     :result (macro/expand-batch (encoder/program-batch ast) ctx)}))


(defn replay-corpus
  "The pinned eight-member corpus. `annotated?` selects the S shape
   (annotations retained, prepare = identity) or the B shape (C1 markers
   stripped before projection, exactly the cohort's knob); `excluded` is
   a set of member ids to omit (recorded in the findings with the exact
   reason)."
  ([] (replay-corpus true #{}))
  ([annotated? excluded]
   (let [prepare (if annotated? identity
                     (fn [ast]
                       (walk/postwalk (fn [n]
                                        (if (map? n)
                                          (dissoc n :yang/call-site) n))
                                      ast)))
         forms (quote [(defn replay
                         [x]
                         (let [[a b] [x 1.5]]
                           (+ (identity ^:replay a)
                              (identity ^:replay a) b)))
                       (replay 2)])
         projected (fn [id ast token]
                     (let [ast (prepare ast)
                           p (encoder/project
                               (encoder/source-envelope :cc4/replay token [ast]))]
                       {:id id :compiled ast
                        :tree (first (:yin/batch p))
                        :rows (:yin/frontend-metadata p)}))
         ast (yang/compile-program forms)
         macro-ast (yang/compile-program
                     (quote [(defmacro twin
                               [x]
                               (yin/if (yin/literal true) x
                                       (yin/application
                                         (yin/variable (quote f))
                                         [(yin/literal 1)])))
                             (defn f
                               [x]
                               x)
                             (twin (f 1))]))
         expansion (:result (expand-like-the-repl (prepare macro-ast)))
         store (mem/create-content-mem)
         call-spec (update (call/module-spec publish-registry) :ast prepare)
         call-result (publish/publish-module! store call-spec)
         effect-result (publish/publish-module! store (effect/module-spec publish-registry))
         clj-spec (update (clj-prelude/module-spec
                            publish-registry (:address call-result)
                            (:address effect-result))
                          :ast prepare)
         clj-result (publish/publish-module! store clj-spec)
         published (fn [id result spec]
                     (let [table (publish/published-call-site-table store result)]
                       {:id id :compiled (:ast spec)
                        :tree (or (:tree table)
                                  (vm/ast->semantic-bytecode (:ast spec)))
                        :rows (or (:rows table) [])
                        :addresses (assoc (dissoc (:call-sites result) :origin)
                                          :manifest (:address result))
                        :published-payloads
                        (when (:call-sites result)
                          (mapv #(jing/get store % nil)
                                (map (:call-sites result)
                                     [:table :provenance :record])))
                        :provenance (publish/published-instruction-provenance
                                      store result)}))
         members (cond-> []
                   (not (contains? excluded :native))
                   (conj (projected :native ast "admission-a"))
                   (not (contains? excluded :argument-vector))
                   (conj (projected :argument-vector
                                    (yang/compile-program forms true yang/unit-opts)
                                    "admission-a"))
                   (not (contains? excluded :lower-unit))
                   (conj (projected :lower-unit (yang/lower-unit forms)
                                    "admission-a"))
                   (not (contains? excluded :prelude))
                   (conj (projected :prelude clj-prelude/module-uast
                                    "admission-a"))
                   (not (contains? excluded :macro))
                   (conj {:id :macro :compiled (prepare macro-ast)
                          :status (:status expansion)
                          :tree (macro/packet->row-set (:tree expansion))
                          :rows (:frontend-metadata expansion)
                          :expansion-log (:log expansion)})
                   (not (contains? excluded :negative))
                   (conj (projected :negative ast "admission-b"))
                   (not (contains? excluded :yang.call))
                   (conj (published :yang.call call-result call-spec))
                   (not (contains? excluded :clj))
                   (conj (published :clj clj-result clj-spec)))]
     members)))
