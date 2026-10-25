;; Run under jolt by probes/run.clj in a fake root whose jolt-file wrapper is
;; missing or wrong: loading dao.space.store.fs must refuse before this
;; script's guest work (a print, a directory and its lock file) happens.
(ns guest-fs)


(require '[dao.space.store.fs :as fs])

(println "GUEST-WORK")
(.mkdirs (java.io.File. "build/j4a-probe/guest-dir"))
(fs/unlock! (fs/lock! "build/j4a-probe/guest-dir"))
