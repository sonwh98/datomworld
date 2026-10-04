(ns yang.frontend-test
  "The frontend catalog on every host (docs/design/yang.antlr.md section
   8.5.6, slice F1): revisions coexist in an immutable catalog, and a
   manifest carrying a function or handle is refused as data."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.frontend :as frontend]))


(defn- manifest
  [revision]
  {:yang.frontend/id :example.toy/frontend,
   :yang.frontend/spi 1,
   :yang.frontend/revision revision,
   :yang.frontend/language :example.toy/language,
   :yang.frontend/grammar {:yang.grammar/package "grammar-address",
                           :yang.grammar/entries {:module "program"},
                           :yang.grammar/export-profile :yang.cst/v1},
   :yang.frontend/options-schema "options-address",
   :yang.frontend/lowering-profile "lowering-address",
   :yang.frontend/runtime-profile "runtime-address",
   :yang.frontend/support-profile (str "support-" revision)})


(defn- binding-for
  [revision]
  {:parse (fn [x] [revision x]), :lower identity})


(deftest two-revisions-coexist-test
  (let [b1 (binding-for "r1")
        b2 (binding-for "r2")
        c1 (frontend/install frontend/empty-catalog (manifest "r1") b1)
        c2 (frontend/install c1 (manifest "r2") b2)]
    (testing "installing returns a new catalog; the argument is unchanged"
      (is (= {:yang.frontend/entries {}} frontend/empty-catalog))
      (is (= ["r1"] (frontend/revisions c1 :example.toy/frontend)))
      (is (= ["r1" "r2"] (frontend/revisions c2 :example.toy/frontend))))
    (testing "each revision is selectable by id and revision"
      (let [s1 (frontend/select c2 {:yang.frontend/id :example.toy/frontend,
                                    :yang.frontend/revision "r1"})
            s2 (frontend/select c2 {:yang.frontend/id :example.toy/frontend,
                                    :yang.frontend/revision "r2"})]
        (is (frontend/ok? s1))
        (is (= (manifest "r1") (:manifest s1)))
        (is (identical? b1 (:binding s1)))
        (is (= ["r1" :x] ((:parse (:binding s1)) :x)))
        (is (= (manifest "r2") (:manifest s2)))
        (is (identical? b2 (:binding s2)))))
    (testing "the older snapshot does not see the newer revision"
      (is (= :yang.frontend/unknown
             (:reason (frontend/select
                        c1
                        {:yang.frontend/id :example.toy/frontend,
                         :yang.frontend/revision "r2"})))))
    (testing "selection without a revision never silently chooses"
      (is (frontend/ok? (frontend/select
                          c1
                          {:yang.frontend/id :example.toy/frontend})))
      (let [res (frontend/select c2 {:yang.frontend/id :example.toy/frontend})]
        (is (= :yang.frontend/ambiguous (:reason res)))
        (is (= ["r1" "r2"] (:revisions res)))))
    (testing "a pinned profile narrows or refuses the selection"
      (is (= (manifest "r2")
             (:manifest (frontend/select
                          c2
                          {:yang.frontend/id :example.toy/frontend,
                           :yang.frontend/support-profile "support-r2"}))))
      (let [res (frontend/select
                  c2
                  {:yang.frontend/id :example.toy/frontend,
                   :yang.frontend/revision "r1",
                   :yang.frontend/support-profile "support-r2"})]
        (is (frontend/refused? res))
        (is (= :yang.frontend/incompatible (:reason res)))))
    (testing "an unknown id is refused"
      (is (= {:status :refused, :reason :yang.frontend/unknown,
              :id :example.other/frontend, :revision nil}
             (frontend/select c2 {:yang.frontend/id :example.other/frontend}))))
    (testing "a revision is immutable once installed"
      (is (identical? c2 (frontend/install c2 (manifest "r1") b1)))
      (is (= {:status :refused, :reason :yang.frontend/revision-conflict,
              :id :example.toy/frontend, :revision "r1"}
             (frontend/install c2
                               (assoc (manifest "r1")
                                      :yang.frontend/runtime-profile "other")
                               b1)))
      (is (= :yang.frontend/revision-conflict
             (:reason (frontend/install c2 (manifest "r1") {:other :binding})))
          "the same manifest under a different binding is a conflict"))))


(deftest manifest-holding-a-function-is-refused-test
  (testing "a function anywhere in the manifest is refused with its path"
    (is (= {:status :refused, :reason :yang.frontend/non-portable,
            :path [:yang.frontend/grammar :yang.grammar/entries :module]}
           (frontend/install frontend/empty-catalog
                             (assoc-in (manifest "r1")
                                       [:yang.frontend/grammar
                                        :yang.grammar/entries :module]
                                       (fn [] "program"))
                             (binding-for "r1"))))
    (is (= {:status :refused, :reason :yang.frontend/non-portable,
            :path [:yang.frontend/support-profile]}
           (frontend/validate (assoc (manifest "r1")
                                     :yang.frontend/support-profile
                                     identity)))))
  (testing "a host handle is refused"
    (is (= {:status :refused, :reason :yang.frontend/non-portable,
            :path [:extra 1]}
           (frontend/validate (assoc (manifest "r1")
                                     :extra
                                     ["ok" (atom 0)]))))))


(deftest manifest-validation-test
  (testing "a portable, versioned manifest is admitted unchanged"
    (is (= {:status :ok, :manifest (manifest "r1")}
           (frontend/validate (manifest "r1")))))
  (testing "a present lexer or parser name must be a non-empty string"
    (is (= {:status :refused, :reason :yang.frontend/malformed,
            :path [:yang.frontend/grammar :yang.grammar/lexer],
            :defect :invalid}
           (frontend/validate (assoc-in (manifest "r1")
                                        [:yang.frontend/grammar
                                         :yang.grammar/lexer]
                                        42)))))
  (testing "a missing or malformed pinned key is refused by path"
    (is (= {:status :refused, :reason :yang.frontend/malformed,
            :path [:yang.frontend/revision], :defect :missing}
           (frontend/validate (dissoc (manifest "r1")
                                      :yang.frontend/revision))))
    (is (= {:status :refused, :reason :yang.frontend/malformed,
            :path [:yang.frontend/grammar :yang.grammar/entries],
            :defect :invalid}
           (frontend/validate (assoc-in (manifest "r1")
                                        [:yang.frontend/grammar
                                         :yang.grammar/entries]
                                        {}))))
    (is (= :yang.frontend/malformed
           (:reason (frontend/validate [:not :a :map])))))
  (testing "an SPI revision the catalog does not speak is refused"
    (is (= {:status :refused, :reason :yang.frontend/incompatible-spi,
            :spi 2, :supported #{1}}
           (frontend/validate (assoc (manifest "r1")
                                     :yang.frontend/spi 2))))))
