(require '[clojure.pprint :refer [pprint]]
         '[yin.vm :as vm]
         '[yin.vm.semantic-register.corpus :refer [lit v lam app]]
         '[yin.vm.semantic-register.parity-test :as parity]
         '[yin.vm.semantic-register.effects-test :as e])


;; ((fn [c] (list c (next! c))) (cursor (stream/make 4)))
(def program
  (app (lam ['c] (app (v 'list) (v 'c) (e/next-node (v 'c))))
       (e/cursor-node {:type :stream/make :buffer 4})))


(def m (parity/register-machine program {:env {'list list}}))
(def before (e/until-inst m :stream-next))
(def seg (get-in before [:control :segment]))
(def pc  (get-in before [:control :pc]))
(println "== the closure body, as register instructions (A); pc" pc "is the blocking read")
(pprint (map-indexed vector (get-in before [:code seg :vector])))
(def blocked (vm/step before))
(def entry (first (:wait-set blocked)))
(println "\n== the suspended state: the wait entry")
(pprint (select-keys entry [:segment :pc :env :window :deliver :reason :cursor-ref :stream-id]))
(println "\n== the resources: the cursor cell the cursor-ref names")
(pprint (into {} (filter (fn [[k r]] (and (map? r) (contains? r :stream-id))) (:resources blocked))))
