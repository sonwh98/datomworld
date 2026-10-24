;; Run under jolt by probes/run.clj in a fake root whose jolt-file wrapper is
;; missing or wrong: loading dao.jing.file must refuse before this script's
;; guest work (a print and a file) happens.
(ns guest)


(require '[dao.jing :as jing] '[dao.jing.file :as jf])

(println "GUEST-WORK")


(let [h (jf/create-content-file "build/j4a-probe/guest.log")]
  (jing/materialize! h "guest")
  (jing/close! h))
