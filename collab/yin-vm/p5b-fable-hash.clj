(require '[yin.vm.debruijn-register-code :as rcode])
(require '[yin.vm.semantic-register.derive :as derive])
(require '[yin.vm.semantic-register.linearize :as linearize])
(require '[yin.vm.semantic-register.corpus :as corpus :refer [lit v lam app tail if-node resume]])


;; Independent recompute of pinned register-hash strings from the pinned literal images.
(println "row2 " (rcode/register-hash '{:bodies [{:locals 0 :registers 4 :start 0 :end 6}] :instructions [[:load-free 0 f] [:load-free 1 g] [:load-free 2 x] [:call 3 1 [2] false [0]] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]}))
(println "row8 " (rcode/register-hash '{:bodies [{:locals 0 :registers 4 :start 0 :end 5}] :instructions [[:load-free 0 f] [:load-free 1 x] [:resume :p 1] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]}))
(println "row6 " (rcode/register-hash '{:bodies [{:locals 0 :registers 3 :start 0 :end 3} {:locals 1 :registers 5 :start 4 :end 8}] :instructions [[:closure 0 1 4] [:const 1 10] [:call 2 0 [1] false []] [:halt 2] [:load-free 1 +] [:load-bound 2 0 0] [:const 3 1] [:call 4 1 [2 3] true []] [:return 4]]}))


;; Probe: the widened-start rule where it IS observable (else-arm temporaries).
(def probe (if-node (v 'c) (resume :p (lit 1)) (app (v 'g) (lit 2))))
(println "probe A " (:vector (linearize/project probe)))
(println "probe R " (derive/register-image (:vector (linearize/project probe))))
