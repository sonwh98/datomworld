(ns dao.star-cart.grid-program
  "The grid (catalog editor) session program.  Its continuation holds only UI
   state: the selected item and the last outcome.  Every mutation is emitted as
   data, `{:edit {:item slug :changes {...}}}`, for the host's effects
   interpreter to validate and transact; the program never claims success.  It
   renders `saving` until a `{:saved slug}` or `{:rejected reason}` input
   arrives, which the effects interpreter appends after the transact.

   Inputs are `{:signals {...}}` maps: `:act` names the action, `:arg` carries
   an item key, image URL or tagory name, and the typed fields arrive as the
   `name`, `price`, `desc`, `img` and `tag` signals."
  (:require [dao.await :as await]))


(def grid-forms
  '[(let [ic (await/cursor in)]
      (do
        (def emit (fn [sel status msg edit]
                    (await/>! out (assoc (assoc (assoc (assoc {} :sel sel) :status status) :msg msg) :edit edit))))
        (def change (fn [sel ch]
                      (assoc (assoc {} :item sel) :changes ch)))
        (def lp (fn [sel]
                  (let [x (await/<! ic)
                        s (get x :signals)
                        a (get s :act)
                        arg (get s :arg)]
                    (if (get s :saved)
                      (do (emit sel "saved" "" nil) (lp sel))
                      (if (get s :rejected)
                        (do (emit sel "rejected" (get s :rejected) nil) (lp sel))
                        (if (= a "select")
                          (do (emit arg "" "" nil) (lp arg))
                          (if (= a "cancel")
                            (do (emit nil "" "" nil) (lp nil))
                            (if (= a "save")
                              (do (emit sel "saving" ""
                                        (change sel (assoc (assoc (assoc {} :name (get s :name))
                                                                  :price (get s :price))
                                                           :description (get s :desc))))
                                  (lp sel))
                              (if (= a "addimg")
                                (do (emit sel "saving" "" (change sel (assoc {} :add-image (get s :img))))
                                    (lp sel))
                                (if (= a "rmimg")
                                  (do (emit sel "saving" "" (change sel (assoc {} :remove-image arg)))
                                      (lp sel))
                                  (if (= a "addtag")
                                    (do (emit sel "saving" "" (change sel (assoc {} :add-tagory (get s :tag))))
                                        (lp sel))
                                    (if (= a "rmtag")
                                      (do (emit sel "saving" "" (change sel (assoc {} :remove-tagory arg)))
                                          (lp sel))
                                      (do (emit sel "" "" nil) (lp sel))))))))))))))
        (emit nil "" "" nil)
        (lp nil)))])


(def grid-program
  (await/go* grid-forms {}))
