;; Reviewer's Node slow C4 scanner row (phase 5c review): bb collab/5c-review-node-c4.bb
;; Runs node in the foreground with DATOM_SLOW_TESTS=1, as bb test:slow:cljs does.
(require '[babashka.process :as p])
(println "start" (str (java.time.Instant/now)))
(let [r (p/shell {:continue true :extra-env {"DATOM_SLOW_TESTS" "1"}}
                 "node" "target/5c-review-node-scanners.js"
                 "--test=yin.vm.semantic-register.scan-test/scanners-agree-on-the-c4-modules")]
  (println "node exit" (:exit r)))
(println "end" (str (java.time.Instant/now)))
