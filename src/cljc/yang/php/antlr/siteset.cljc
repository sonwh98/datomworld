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


(defn- read-at
  [handle address]
  (when (jing/segment-address? address)
    (let [bytes ((:get-bytes-fn handle) address nil)]
      (when (and bytes (jing/segment-bytes-match? address bytes))
        (jing/segment-value address bytes)))))


(defn verify
  "Read and verify a published set, or return nil for missing or mismatched
   metadata. An empty set is a valid publication."
  [handle manifest record-address]
  (let [module (read-at handle manifest)
        record (read-at handle record-address)
        sites (read-at handle (:yang.site/set record))]
    (when (and (map? module) (map? record) (vector? sites)
               (= manifest (:yang.site/manifest record))
               (= (:yin.module/tree module) (:yang.site/root record))
               (= frontend-revision (:yang.site/revision record))
               (= profile-address (:yang.site/profile record))
               (every? (fn [entry]
                         (and (vector? entry) (= 2 (count entry))
                              (vector? (first entry))
                              (let [mark (second entry)
                                    kind (if (vector? mark) (first mark) mark)]
                                (contains? #{:call :line :loop} kind))))
                       sites)
               (= sites (safepoint/site-set (into {} sites))))
      {:record record, :set sites})))
