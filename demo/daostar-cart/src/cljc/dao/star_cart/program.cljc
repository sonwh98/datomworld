(ns dao.star-cart.program
  "The shop's session program: pure sequential logic on yin.vm.  It reads
   inputs from `in` with `<!` (each `{:signals {:act .. :arg ..}}`), holds the
   page, item, cart, search and tagory as locals of its loop, and emits a model
   on `out` with `>!`.  It never queries the catalog and never touches egress:
   the driver projects, so the cart lives exactly as long as the lineage.

   The cart is a map slug -> quantity.  The VM's vocabulary has no `dissoc`,
   so a removal decrements and the projection ignores quantities <= 0.  The
   search text and the selected tagory are filtered in the projection, which
   has the string library the VM lacks."
  (:require [dao.await :as await]))


(def shop-forms
  '[(let [ic (await/cursor in)]
      (do
        (def emit
          (fn [page item cart search tagory]
            (await/>! out (assoc (assoc (assoc (assoc (assoc {} :page page) :item item)
                                               :cart cart)
                                        :search search)
                                 :tagory tagory))))
        (def lp
          (fn [page item cart search tagory]
            (let [x (await/<! ic)
                  s (get x :signals)
                  a (get s :act)
                  arg (get s :arg)]
              (if (= a "nav")
                (do (emit arg nil cart search tagory) (lp arg nil cart search tagory))
                (if (= a "item")
                  (do (emit "item" arg cart search tagory) (lp "item" arg cart search tagory))
                  (if (= a "add")
                    (let [c (assoc cart arg (+ 1 (get cart arg 0)))]
                      (do (emit page item c search tagory) (lp page item c search tagory)))
                    (if (= a "remove")
                      (let [c (assoc cart arg (- (get cart arg 0) 1))]
                        (do (emit page item c search tagory) (lp page item c search tagory)))
                      (if (= a "checkout")
                        (do (emit "checkout" nil {} search tagory) (lp "checkout" nil {} search tagory))
                        (if (= a "search")
                          (do (emit "catalog" nil cart arg tagory) (lp "catalog" nil cart arg tagory))
                          (if (= a "tagory")
                            (do (emit "catalog" nil cart search arg) (lp "catalog" nil cart search arg))
                            (do (emit page item cart search tagory) (lp page item cart search tagory))))))))))))
        (emit "catalog" nil {} "" "")
        (lp "catalog" nil {} "" "")))])


(def shop-program
  (await/go* shop-forms {}))
