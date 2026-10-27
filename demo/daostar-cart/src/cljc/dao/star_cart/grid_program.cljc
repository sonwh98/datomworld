(ns dao.star-cart.grid-program
  "The grid (catalog editor) session program.  Its continuation holds only UI
   state: the last outcome.  Every mutation is emitted as data for the host's
   effects interpreter to validate and transact; the program never claims
   success.  It renders `saving` until a `{:saved slug}` or `{:rejected
   reason}` input arrives, which the interpreter appends after the transact.

   The spreadsheet's cells post the `cell` act: `:arg` is
   \"<slug>|<field>\" and the `val` signal carries the cell's new value.
   The program relays it as a `:cell-edit` payload — built with `assoc`,
   because a map literal containing variables is a constant in the VM (the
   phase-B report's rule) and would emit the unevaluated forms.

   Inputs are `{:signals {...}}` maps: `:act` names the action, `:arg`
   carries a slug, and the typed fields arrive as the `name`, `price`,
   `desc` signals (the create form)."
  (:require [dao.await :as await]))


(def grid-forms
  '[(let [ic (await/cursor in)]
      (do
        (def emit
          (fn [status msg edit]
            (await/>! out (assoc (assoc (assoc {} :status status) :msg msg) :edit edit))))
        (def lp
          (fn [status]
            (let [x (await/<! ic)
                  s (get x :signals)
                  a (get s :act)
                  arg (get s :arg)]
              (if (get s :saved)
                (do (emit "saved" "" nil) (lp status))
                (if (get s :rejected)
                  (do (emit "rejected" (get s :rejected) nil) (lp status))
                  (if (= a "cell")
                    (let [ce (assoc (assoc {} :arg arg) :val (get s :val))]
                      (do (emit "saving" "" (assoc {} :cell-edit ce)) (lp status)))
                    (if (= a "create")
                      (let [nw (assoc (assoc (assoc {} :name (get s :name))
                                             :price (get s :price))
                                      :description (get s :desc))]
                        (do (emit "saving" "" (assoc {} :new nw)) (lp status)))
                      (do (emit "" "" "" nil) (lp status)))))))))
        (emit "" "" "" nil)
        (lp "")))])


(def grid-program
  (await/go* grid-forms {}))
