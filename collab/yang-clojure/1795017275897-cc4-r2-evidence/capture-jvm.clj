(require '[yang.clojure-callsite-test :as cohort]
         '[dao.jing :as jing]
         '[clojure.java.io :as io])


(let [artifacts (cohort/replay-artifacts)
      data (mapv (fn [a]
                   (assoc (select-keys a [:id :tree :rows :addresses :provenance
                                          :expansion-log :published-payloads])
                          :sorted-keys
                          (vec (sort jing/canonical-compare
                                     (set (map #(vec (take 3 %)) (:rows a)))))))
                 artifacts)]
  (with-open [out (io/output-stream "test/resources/yang/clojure/cc4-replay-jvm.cbor")]
    (.write out (jing/canonical-bytes data)))
  (spit "test/resources/yang/clojure/cc4-replay-jvm-affected.edn"
        (pr-str (filterv #(contains? #{:argument-vector :lower-unit} (:id %)) data)))
  (prn :cc4/jvm-snapshots (mapv cohort/replay-snapshot artifacts)))
