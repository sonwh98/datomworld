;; A contender for the J4b gates (JVM or jolt): contend.clj <dir>. Prints
;; "RESULT refused <message>" when dao.space.store/open refuses the directory
;; as locked, or "RESULT acquired" (then closes); any other failure throws.
(ns contend)


(load-file "native/jolt-file/probes/j4b/common.clj")


(let [[verdict x] (common/open-or-refusal (first *command-line-args*))]
  (if (= :refused verdict)
    (println "RESULT refused" x)
    (do (println "RESULT acquired")
        (dao.space.store/close! x))))


(System/exit 0)
