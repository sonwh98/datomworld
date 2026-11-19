(ns yang.core.siteset-publication
  "Shared site-set projection, publication and signing operations."
  (:require [dao.jing :as jing]
            [yang.safepoint :as safepoint]
            [yin.vm :as vm]
            [yin.vm.encoder :as encoder]
            [yin.vm.linker.sign :as sign]))


(defn site-set-of
  "Project the canonical tree and occurrence set of `ast` under `medium`."
  [medium ast]
  (let [env (encoder/project (encoder/source-envelope medium [:site-set 0] [ast]))
        tree (nth (:yin/batch env) (:yin/root env))
        origin (vm/source-origin (:yin/source-medium env)
                                 (:yin/batch-token env) (:yin/root env))
        sites (safepoint/sites (:yin/frontend-metadata env) origin (:root tree))]
    {:root (:root tree), :tree tree, :set (safepoint/site-set sites)}))


(defn publish!
  "Materialize the occurrence set, then its manifest/root binding."
  [handle medium frontend-revision profile-address manifest ast]
  (let [{:keys [root set]} (site-set-of medium ast)
        set-address (jing/materialize! handle (vec set))
        record {:yang.site/manifest manifest, :yang.site/root root,
                :yang.site/revision frontend-revision,
                :yang.site/profile profile-address, :yang.site/set set-address}]
    {:record record, :record-address (jing/materialize! handle record),
     :set-address set-address, :set set}))


(defn signed-entry
  "Sign the association between a published manifest and its site record."
  [key manifest record-address]
  (let [env {:yang.site/op :publish,
             :yang.site/manifest manifest,
             :yang.site/record record-address,
             :yin.module/asserted-by (sign/principal (:public key))}]
    {:envelope env, :proof (sign/sign-envelope (:seed key) env)}))


(defn entry-datoms
  "Index signed site publications alongside name assertions."
  [signed]
  (vec (mapcat (fn [e {:keys [envelope proof]}]
                 [[e :yang.site/envelope envelope]
                  [e :yang.site/proof proof]])
               (range) signed)))


(defn signed-entries
  "Sign all site publications returned by unit publication, including aliases."
  [publisher sites addresses bundles]
  (mapv (fn [[k s]]
          (signed-entry publisher (or (get addresses k) (get bundles k))
                        (:record-address s)))
        (sort-by (comp str key) sites)))
