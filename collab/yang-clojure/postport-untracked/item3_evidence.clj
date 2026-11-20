(ns yang.item3-evidence
  "Round 1794519755000 item-3 evidence extraction (temporary, not a
   contract test): the binding concrete numbers at this tree shape,
   printed from the latte-oracle test namespace own private machinery.
   Run: clojure -M:test -n yang.item3-evidence."
  (:require [clojure.test :refer [deftest is]]
            [yang.latte-oracle-test :as t]))


(deftest evidence
  (let [run ((var t/fresh-admission-run) :pp-item3-verify)
        fixture {:id :cc3-binding
                 :call (quote (latte-kernel.utils/vectorn? [1 2 3] 2))}
        compiled ((var t/kernel-compile-in-run) run fixture)
        b ((var t/cc3-oracle-binding) compiled)]
    (is (= :compiled (:status compiled)))
    (println :origin (:origin b))
    (println :site-count (count (:sites compiled)))
    (println :binding-count (count (:binding b)))
    (println :marked (count (:marked b)) :eligible (count (:eligible b)))
    (println :stamped-marked (count (:stamped-marked b))
             :stamped-eligible (count (:stamped-eligible b)))
    (let [stamped-entries
          (filter (fn [entry] (= :application (:type (second entry))))
                  ((var t/cc3-walk) (:ast compiled) [] []))
          direct-stamps
          (filter (fn [entry]
                    (some? ((var t/cc3-stamp-site-id)
                            (second (:operands (second entry))))))
                  stamped-entries)
          routed-stamps
          (filter (fn [entry]
                    (when-let [inner ((var t/kernel-routed-call?) (second entry))]
                      (some? ((var t/cc3-stamp-site-id)
                              (first (:operands inner))))))
                  stamped-entries)
          app-nodes (map second stamped-entries)
          intra-apps (filter (var t/kernel-intra-call?) app-nodes)
          routed-apps (filter (fn [node]
                                (some? ((var t/kernel-routed-call?) node)))
                              app-nodes)]
      (println :direct-stamps (count direct-stamps)
               :routed-stamps (count routed-stamps))
      (println :intra-unit-applications (count intra-apps)
               :routed-applications (count routed-apps))
      (println :sites (mapv (juxt :site :callee :arg-shapes) (:sites compiled)))
      (println :binding (into (sorted-map) (:binding b))))
    (let [lane-ks (fn [lane]
                    (let [row ((var t/kernel-c) compiled lane)
                          ks (into #{} (comp (map (fn [c] (nth c 2 nil)))
                                             (filter integer?))
                                   (:executed-calls row))]
                      (println :lane lane :status (:status row)
                               :executed-calls (count (:executed-calls row))
                               :distinct-sites (count ks)
                               :all-sites-bound
                               (every? (set (keys (:binding b))) ks))
                      (into #{} (map (fn [k] (get (:binding b) k))) ks)))
          kw (lane-ks :c-walker)
          ksem (lane-ks :c-semantic)]
      (is (= kw ksem) "per-lane K sets equal")
      (println :k-set-size (count kw)))))
