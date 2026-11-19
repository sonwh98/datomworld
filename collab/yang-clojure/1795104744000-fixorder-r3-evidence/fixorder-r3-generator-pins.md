;; r3 B3 evidence -- the pinned shuffle vectors (JVM-recorded after the
;; seed reduction; corpus-generator-pins-test asserts these on every host).
;;
;; recorded-permutations n seed returns [forward reverse rot1 rot2 rot3
;; shuffle(seed) shuffle(seed+1)]; the two shuffles are pinned here:
;;
;;   n=8  map(seed 1795017757000): [3 0 1 2 5 7 6 4] / [6 7 2 4 1 5 0 3]
;;        set (seed 1795017757001): [6 7 2 4 1 5 0 3] / [6 7 4 5 3 0 1 2]
;;   n=9  map: [2 3 5 8 6 4 1 0 7] / [6 1 4 7 8 5 0 3 2]
;;        set: [6 1 4 7 8 5 0 3 2] / [1 2 4 5 3 0 7 8 6]
;;   n=16 map: [3 5 12 2 11 15 6 0 1 13 7 14 9 8 10 4] /
;;             [6 1 2 15 4 13 0 11 8 12 10 14 9 7 5 3]
;;        set: [6 1 2 15 4 13 0 11 8 12 10 14 9 7 5 3] /
;;             [8 7 4 5 3 11 1 10 12 13 15 14 9 6 0 2]
;;   n=32 map: [2 3 5 0 1 19 9 12 30 6 11 10 20 26 15 24 8 28 7 21 13 18
;;              23 25 17 16 27 22 29 31 14 4] /
;;             [4 3 20 12 6 10 18 22 9 17 8 11 25 7 2 15 13 23 0 31 26 27
;;              16 5 24 1 14 28 21 29 30 19]
;;        set: [4 3 20 12 6 10 18 22 9 17 8 11 25 7 2 15 13 23 0 31 26 27
;;              16 5 24 1 14 28 21 29 30 19] /
;;             [22 3 28 21 5 12 1 23 27 4 13 14 7 17 19 10 25 24 11 29 26
;;              31 16 18 6 8 30 20 9 0 15 2]
;;
;; The n=8 map vector [3 0 1 2 5 7 6 4] equals the sign-off's JVM column
;; (exact long arithmetic is unchanged by the reduction); the reduction
;; brings the double-arithmetic hosts onto it instead of the divergent
;; [1 7 3 6 2 0 5 4].
