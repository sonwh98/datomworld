(ns yang.safepoint-test
  "The language-neutral safepoint stage over a small hand-built tree, on
   every host: insertion at marked lambdas, tail marks recomputed, the
   identity on an empty profile, refusals, and golden addresses that every
   host must reproduce (insertion determinism across CLJ, CLJS and CLJD)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.jing :as jing]
    [yang.safepoint :as safepoint]
    [yang.tails :as tails]
    [yin.vm :as vm]
    [yin.vm.encoder :as encoder]))


(defn- v
  [s]
  {:type :variable, :name s})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- site
  [kind node]
  (vary-meta node assoc :yang/site kind))


(def ^:private ast
  "`((fn [w] (w w)) (fn [w] (f w)))`, both lambdas marked: the first a
   `:call` site, the second a `:loop` site; tails marked as a frontend
   would."
  (tails/mark-tails
    (app (site :call (lam '[w] (app (v 'w) (v 'w))))
         (site :loop (lam '[w] (app (v 'f) (v 'w)))))))


(def ^:private profile {:loop 'h/loop, :call 'h/call})


(defn- projected
  []
  (encoder/project (encoder/source-envelope :m [:t 0] [ast])))


(defn- derived
  [p]
  (safepoint/derive-envelope (projected) p :m'))


(defn- tree
  [e]
  (nth (:yin/batch e) (:yin/root e)))


(deftest sites-from-the-side-table-test
  (let [e (projected)]
    (is (= {[2] :call, [[3 0]] :loop}
           (safepoint/sites (:yin/frontend-metadata e)
                            (vm/source-origin :m [:t 0] 0)
                            (:root (tree e)))))))


(deftest insertion-test
  (let [out (vm/semantic-bytecode->ast (tree (:envelope (derived profile))))
        hooked (fn [hook body]
                 {:type :application,
                  :operator {:type :lambda, :params ['yang.safepoint/_], :body body},
                  :operands [{:type :application,
                              :operator {:type :variable, :name hook},
                              :operands [],
                              :tail? false}],
                  :tail? true})]
    (testing "each marked lambda's body runs after its hook; the hook
              application is an operand, never a tail call, and the old body
              is a tail call of the sequencing lambda"
      (is (= {:type :application,
              :operator {:type :lambda, :params '[w],
                         :body (hooked 'h/call
                                       {:type :application, :operator {:type :variable, :name 'w},
                                        :operands [{:type :variable, :name 'w}], :tail? true})},
              :operands [{:type :lambda, :params '[w],
                          :body (hooked 'h/loop
                                        {:type :application,
                                         :operator {:type :variable, :name 'f},
                                         :operands [{:type :variable, :name 'w}],
                                         :tail? true})}],
              :tail? false}
             out)))))


(deftest exit-hook-wraps-the-body-test
  (let [{:keys [envelope record]} (derived {:call 'h/call, :return 'h/return})
        out (vm/semantic-bytecode->ast (tree envelope))]
    (testing "at a :call site the :return hook takes the body's value, after
              the entry hook; the old body is no longer a tail call, the
              exit hook is"
      (is (= {:type :lambda, :params '[w],
              :body {:type :application,
                     :operator {:type :lambda, :params ['yang.safepoint/_],
                                :body {:type :application,
                                       :operator {:type :variable,
                                                  :name 'h/return},
                                       :operands [{:type :application,
                                                   :operator {:type :variable,
                                                              :name 'w},
                                                   :operands [{:type :variable,
                                                               :name 'w}],
                                                   :tail? false}],
                                       :tail? true}},
                     :operands [{:type :application,
                                 :operator {:type :variable, :name 'h/call},
                                 :operands [],
                                 :tail? false}],
                     :tail? true}}
             (:operator out))))
    (testing "the :loop site, whose kind the profile omits, is untouched"
      (is (= (tails/strip-tails (first (:operands ast)))
             (tails/strip-tails (first (:operands out))))))
    (is (= (jing/segment-key [[[2] :call]])
           (get-in record [:yin.ledger/profile :yang.safepoint/sites]))))
  (testing "an exit kind alone selects its entry kind's sites"
    (let [out (vm/semantic-bytecode->ast
                (tree (:envelope (derived {:return 'h/return}))))]
      (is (= {:type :application,
              :operator {:type :variable, :name 'h/return},
              :operands [{:type :application,
                          :operator {:type :variable, :name 'w},
                          :operands [{:type :variable, :name 'w}],
                          :tail? false}],
              :tail? true}
             (:body (:operator out)))))))


(deftest profile-selects-kinds-test
  (let [{:keys [envelope record]} (derived {:loop 'h/loop})
        out (vm/semantic-bytecode->ast (tree envelope))]
    (testing "a kind the profile omits is not inserted"
      (is (= (tails/strip-tails (:operator ast))
             (tails/strip-tails (:operator out)))))
    (is (= {:yang.safepoint/hooks {:loop 'h/loop},
            :yang.safepoint/sites (jing/segment-key [[[[3 0]] :loop]])}
           (:yin.ledger/profile record)))))


(deftest empty-profile-is-the-identity-test
  (let [{:keys [envelope record]} (derived {})]
    (is (= (tree (projected)) (tree envelope)))
    (is (= (:yin.ledger/input record) (:yin.ledger/output record)))))


(deftest remark-reproduces-a-frontends-marks-test
  (is (= ast (tails/remark-tails ast))))


(deftest refusals-test
  (let [t (tree (projected))]
    (testing "a site that names a node that is not a lambda"
      (is (= :site-not-lambda
             (try (safepoint/insert t {[] :loop} profile) nil
                  (catch #?(:cljd Object :clj Exception :cljs :default) e
                    (:rule (ex-data e)))))))
    (testing "a site that names no node"
      (is (= :site-missing
             (try (safepoint/insert t {[9] :loop} profile) nil
                  (catch #?(:cljd Object :clj Exception :cljs :default) e
                    (:rule (ex-data e)))))))))


(deftest every-host-derives-the-same-addresses-test
  (let [{:keys [envelope record-address]} (derived profile)]
    (is (nil? (vm/validate-rows (tree envelope))))
    ;; computed on the JVM; Node and Dart must agree
    (is (= :segment/blake3-c749fd829c4e3cd6c300ca4cf4b3735c32c87f9c968327bd195f2a3568e01959
           (:root (tree envelope))))
    (is (= :segment/blake3-dc131db3cac4bbcf77eb4ddb74036cd7611df5336af4ee0d1db952fe91c436c9
           record-address))
    (is (= record-address (:yin/batch-token envelope)))))
