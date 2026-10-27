(ns yang.php.phpt
  "Selects the php-src `.phpt` tests the differential run uses.

   clj -M:test -m yang.php.phpt <php-src-root> <tag>

   walks the pinned php-src checkout, keeps the files named by the checked-in
   predicates (`yang.php.antlr.phpt/selection-patterns`) whose source uses only
   the supported surface, and writes test/yang/php/antlr/phpt_selection.edn:
   `{:tag t :entries [{:id :path :source :expect :expectf :needs}] :skipped
   {reason count}}`. Files exercising classes or typed properties carry
   `:needs :p4` and are skipped by the P3 runner but listed. The counts are
   printed; report them, do not estimate them."
  (:require
    [clojure.java.io :as io]
    [clojure.pprint :as pp]
    [clojure.string :as str]
    [yang.php.antlr.phpt :as phpt]))


(defn- rel
  [root f]
  (str/replace (.getPath ^java.io.File f) (str (.getPath (io/file root)) "/") ""))


(defn select
  "`{:entries [...] :skipped {reason n}}` for the php-src checkout at `root`."
  [root]
  (let [files (->> (file-seq (io/file root))
                   (filter #(.isFile ^java.io.File %))
                   (filter #(str/ends-with? (.getName ^java.io.File %) ".phpt"))
                   (filter #(phpt/selected-path? (rel root %)))
                   (sort-by #(.getPath ^java.io.File %)))]
    (reduce
      (fn [acc f]
        (let [path (rel root f)
              sections (phpt/parse-phpt (slurp f))
              {:keys [status needs reason]} (phpt/classify sections)]
          (if (= :select status)
            (update acc :entries conj
                    (cond-> {:id path,
                             :path path,
                             :source (get sections "FILE"),
                             :title (str/trim (get sections "TEST" ""))}
                      (get sections "EXPECT") (assoc :expect (get sections "EXPECT"))
                      (get sections "EXPECTF") (assoc :expectf (get sections "EXPECTF"))
                      needs (assoc :needs needs)))
            (update-in acc [:skipped reason] (fnil inc 0)))))
      {:entries [], :skipped {}}
      files)))


(defn -main
  [& [root tag]]
  (if-not (and root tag)
    (println "usage: yang.php.phpt <php-src-root> <tag>")
    (let [{:keys [entries skipped]} (select root)
          out "test/yang/php/antlr/phpt_selection.edn"]
      (spit out (with-out-str (pp/pprint {:tag tag, :entries entries, :skipped skipped})))
      (println "selected:" (count entries)
               "needs p4:" (count (filter :needs entries))
               "skipped:" skipped)))
  (shutdown-agents))
