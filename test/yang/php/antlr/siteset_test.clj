(ns yang.php.antlr.siteset-test
  (:require
    [clojure.test :refer [deftest is]]
    [dao.jing.mem :as mem]
    [yang.core.uast :as u]
    [yang.php.antlr.siteset :as siteset]))


(deftest publication-binds-occurrences-to-root
  (let [mark [:line 42 {:unit "lib.php", :ticks 2}]
        ast (vary-meta (u/lit 1) assoc :yang/site mark)
        store (mem/create-content-mem)
        pub (siteset/publish! store :manifest ast)
        empty-pub (siteset/publish! store :other (u/lit 1))]
    (is (= [[[] mark]] (:set pub)))
    (is (= [] (:set empty-pub)))
    (is (= (:yang.site/root (:record pub)) (:yang.site/root (:record empty-pub))))
    (is (= :manifest (:yang.site/manifest (:record pub))))))
