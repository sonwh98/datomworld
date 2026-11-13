(ns yang.php.antlr.siteset-test
  (:require
    [clojure.test :refer [deftest is]]
    [dao.jing :as jing]
    [dao.jing.mem :as mem]
    [yang.core.publication :as publication]
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


(deftest verification-requires-the-bound-module-and-set
  (let [store (mem/create-content-mem)
        ast (u/lit 1)
        root (:root (siteset/site-set-of ast))
        manifest (jing/materialize! store {:yin.module/tree root})
        other (jing/materialize! store {:yin.module/tree :wrong})
        pub (siteset/publish! store manifest ast)]
    (is (= [] (:set (siteset/verify store manifest (:record-address pub)))))
    (is (nil? (siteset/verify store other (:record-address pub))))
    (is (nil? (siteset/verify store manifest :missing)))))


(deftest cyclic-aliases-publish-empty-sets
  (let [store (mem/create-content-mem)
        ast (u/lit 1)
        bundle {:name 'bundle, :ast ast}
        cfg {:imported-names :imports,
             :module-spec (fn [& _] (throw (ex-info "expected cycle" {}))),
             :scc-module-specs (fn [& _]
                                 [bundle {:name 'a, :ast ast}
                                  {:name 'b, :ast ast}]),
             :site-set siteset/publish!, :alias-site-set true,
             :refusal-ns "test",
             :publish-fn (fn [h spec]
                           {:status :ok, :address (jing/materialize!
                                                    h {:yin.module/tree (:root (siteset/site-set-of (:ast spec))),
                                                       :name (:name spec)})})}
        result (publication/publish-units! cfg store {} nil
                                           [{:name "a", :imports ["b"]}
                                            {:name "b", :imports ["a"]}] {})]
    (is (= :ok (:status result)))
    (is (= #{'bundle "a" "b"} (set (keys (:sites result)))))
    (doseq [[name address] (:addresses result)]
      (is (= [] (:set (siteset/verify store address
                                      (get-in result [:sites name :record-address]))))))))
