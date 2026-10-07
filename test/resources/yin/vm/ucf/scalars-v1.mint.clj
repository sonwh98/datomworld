;; Mint once on the JVM from the repository root:
;; clojure -Sdeps '{:aliases {:mint {:extra-paths ["test"]}}}' -M:mint \
;;   test/resources/yin/vm/ucf/scalars-v1.mint.clj < /dev/null
(require '[yin.vm.ucf.scalar-round-trip-test :as s]
         '[dao.jing.cbor-fixtures :as fx]
         '[clojure.string :as str])


(let [r (s/minted)]
  (assert (= :ok (:status r)))
  (spit s/path
        (str "scalars-v1\n" (:address r) "\n"
             (str/join "\n"
                       (map #(apply str %)
                            (partition-all
                              64 (fx/bytes->hex (:bytes r)))))
             "\n")))
