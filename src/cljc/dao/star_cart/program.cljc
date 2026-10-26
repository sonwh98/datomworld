(ns dao.star-cart.program
  "The shop's session program: pure sequential logic on yin.vm.  It reads
   inputs from `in` with `<!` (each `{:signals {:act .. :arg ..}}`), holds the
   page, item and cart as locals of its loop, and emits a model on `out` with
   `>!`.  It never queries the catalog and never touches egress: the driver
   projects, so the cart lives exactly as long as the lineage.

   The cart is a map slug -> quantity.  The VM's vocabulary has no `dissoc`,
   so a removal decrements and the projection ignores quantities <= 0."
  (:require [dao.await :as await]))


(def shop-forms
  '[(let [ic (await/cursor in)]
      (do
        (def emit (fn [page item cart]
                    (await/>! out (assoc (assoc (assoc {} :page page) :item item) :cart cart))))
        (def lp (fn [page item cart]
                  (let [x (await/<! ic)
                        s (get x :signals)
                        a (get s :act)
                        arg (get s :arg)]
                    (if (= a "nav")
                      (do (emit arg nil cart) (lp arg nil cart))
                      (if (= a "item")
                        (do (emit "item" arg cart) (lp "item" arg cart))
                        (if (= a "add")
                          (let [c (assoc cart arg (+ 1 (get cart arg 0)))]
                            (do (emit page item c) (lp page item c)))
                          (if (= a "remove")
                            (let [c (assoc cart arg (- (get cart arg 0) 1))]
                              (do (emit page item c) (lp page item c)))
                            (if (= a "checkout")
                              (do (emit "checkout" nil {}) (lp "checkout" nil {}))
                              (do (emit page item cart) (lp page item cart))))))))))
        (emit "catalog" nil {})
        (lp "catalog" nil {})))])


(def shop-program
  (await/go* shop-forms {}))
