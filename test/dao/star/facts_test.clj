(ns dao.star.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.star.facts :as facts]))


(deftest constructors-are-valid-facts
  (is (= :root (facts/fact-kind (facts/root "s"))))
  (is (= :consumption (facts/fact-kind (facts/consumption "s" "o2" "o1"))))
  (is (= :ended (facts/fact-kind (facts/ended "s" :halted)))))


(deftest foreign-values-are-noise
  (doseq [x [nil 42 "s" {} [] {:dao.star/lineage "s"}
             {:dao.star/lineage "s" :dao.star/occurrence "t"}
             {:dao.star/lineage "s" :dao.star/occurrence "s" :extra 1}
             {:dao.star/lineage "s" :dao.star/occurrence "o" :dao.star/predecessor "o"}
             {:dao.lease/event :dao.lease/renewal :dao.lease/lease "L" :dao.star/lineage "s"}]]
    (is (not (facts/valid-fact? x)) (pr-str x))
    (is (= facts/empty-index (facts/index-step facts/empty-index x)) "noise changes nothing")))


(deftest index-folds-lineage
  (let [index (reduce facts/index-step facts/empty-index
                      [(facts/root "s")
                       (facts/consumption "s" "o2" "s")
                       (facts/consumption "s" "o3" "o2")])]
    (is (= {:kind :current :lineage "s"} (facts/resolve-token index "o3")))
    (is (= {:kind :consumed :lineage "s"} (facts/resolve-token index "o2")))
    (is (= {:kind :consumed :lineage "s"} (facts/resolve-token index "s")))
    (is (= {:kind :unknown} (facts/resolve-token index "nope")))
    (testing "a consumption whose predecessor is not current is ignored"
      (is (= index (facts/index-step index (facts/consumption "s" "o9" "o2")))))
    (testing "a consumption of an unknown lineage is ignored"
      (is (= index (facts/index-step index (facts/consumption "z" "z2" "z")))))))


(deftest ended-and-lapsed-end-the-lineage
  (let [base (reduce facts/index-step facts/empty-index
                     [(facts/root "s") (facts/root "t")])
        halted (facts/index-step base (facts/ended "s" :halted))
        granted (facts/index-step
                  base {:dao.lease/status :dao.lease/accepted :dao.lease/lease "L"
                        :dao.lease/subject {:dao.star/lineage "t"}
                        :dao.lease/holder {:dao.star/lineage "t"}
                        :dao.lease/duration {:ms 1}})
        lapsed (facts/index-step granted {:dao.lease/status :dao.lease/lapsed
                                          :dao.lease/lease "L" :dao.lease/cause :silence})]
    (is (= :ended (:kind (facts/resolve-token halted "s"))))
    (is (= :current (:kind (facts/resolve-token halted "t"))))
    (is (= :current (:kind (facts/resolve-token granted "t"))))
    (is (= :ended (:kind (facts/resolve-token lapsed "t"))))
    (testing "a lapse of a lease the index never saw granted changes nothing"
      (is (= base (facts/index-step base {:dao.lease/status :dao.lease/lapsed
                                          :dao.lease/lease "X" :dao.lease/cause :silence}))))))
