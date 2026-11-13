(ns yang.php.antlr.siteset
  "Publication of PHP occurrence marks beside canonical module rows."
  (:require
    [dao.jing :as jing]
    [yang.safepoint :as safepoint]
    [yin.vm :as vm]
    [yin.vm.encoder :as encoder]))


(def frontend-revision "yang.php.antlr.lower/sites-1")


(def lowering-profile
  {:yang.site/unit :module, :yang.site/prelude :linked,
   :yang.site/kinds [:call :line :loop]})


(def profile-address (jing/segment-key lowering-profile))


(defn site-set-of
  "The canonical root and occurrence marks of module AST `ast`."
  [ast]
  (let [env (encoder/project (encoder/source-envelope
                               :yang.php.antlr/site-set [:site-set 0] [ast]))
        tree (nth (:yin/batch env) (:yin/root env))
        origin (vm/source-origin (:yin/source-medium env)
                                 (:yin/batch-token env) (:yin/root env))
        sites (safepoint/sites (:yin/frontend-metadata env) origin (:root tree))]
    {:root (:root tree), :set (safepoint/site-set sites)}))


(defn publish!
  "Publish the set, including an empty set, and its manifest/root binding."
  [handle manifest ast]
  (let [{:keys [root set]} (site-set-of ast)
        set-address (jing/materialize! handle (vec set))
        record {:yang.site/manifest manifest, :yang.site/root root,
                :yang.site/revision frontend-revision,
                :yang.site/profile profile-address, :yang.site/set set-address}]
    {:record record, :record-address (jing/materialize! handle record),
     :set-address set-address, :set set}))
