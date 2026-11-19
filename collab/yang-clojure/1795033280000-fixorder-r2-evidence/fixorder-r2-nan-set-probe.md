;; r2 F1 probe -- JVM NaN set-construction retention (fresh JVM per run,
;; clojure -M:test -n fixorder-tmp-diag-test; scratch namespace deleted at
;; delivery). count = host set size, nan = ##NaN elements retained.
;;
;;   (count (into #{} [##NaN ##NaN]))                    2 nan=2  keeps
;;   (count (into #{} [##NaN ##NaN 1]))                  3 nan=2  keeps
;;   (count (into #{} [##NaN ##NaN "x"]))                3 nan=2  keeps
;;   (count (into #{} [##NaN ##NaN :k]))                 3 nan=2  keeps
;;   (count (into #{} [##NaN ##NaN [1]]))                3 nan=2  keeps
;;   (count (into #{} [##NaN 1 ##NaN]))                  3 nan=2  keeps
;;   (count (into #{} [##NaN ##NaN (list '+ 'n 0)))      2 nan=1  LOSES ONE
;;   (count (into #{} [##NaN app ##NaN]))                2 nan=1  LOSES ONE (any position)
;;   (count (into #{} [app ##NaN ##NaN]))                2 nan=1  LOSES ONE
;;   (count (hash-set ##NaN ##NaN))                      1 nan=1  collapses
;;   (count (apply hash-set [##NaN ##NaN 1]))            3 nan=2  keeps
;;   (count (set [##NaN ##NaN]))                         2 nan=2  keeps
;;   (count (conj #{} ##NaN ##NaN))                      1 nan=1  collapses (persistent chain)
;;   (count (conj (into #{} [##NaN ##NaN]) app))         3 nan=2  KEEPS (chosen)
;;   (count (into (into #{} [##NaN ##NaN]) [app]))       3 nan=2  KEEPS
;;   map analogues: (assoc {} ##NaN 1 ##NaN 2)           2 nan=2  keeps (r1 map tie)
;;                  (hash-map ##NaN :l ##NaN :r)         1 nan=1  collapses
;;                  (into {} [[##NaN v] [##NaN v]])      collapses (r1 observation)
;;
;; Hash collision ruled out: (hash ##NaN) = 2146959360,
;; (hash (list '+ 'n 0)) = -1050398832. The trigger for the single-session
;; loss is the seq element itself (also (list 1) loses), not its bucket.
;;
;; Conclusion: the whole-element tie IS constructable; retention is
;; per-constructor (JVM transient/persistent/check paths differ), not a
;; map-vs-set difference. The r1 "unconstructable" claim was a
;; misdiagnosis: the r1 block's count assertions were calibrated to a
;; collapsed set, and their failure (3 operands, NaN root seen twice)
;; actually demonstrated retention with reordering. Reviewer F1 sustained.
