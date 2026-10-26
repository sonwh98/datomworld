;; Reopen a durable directory (JVM or jolt) and print what it recovers:
;; "RECOVERY <manifest-address> <datom-count>". verify.clj <dir>
(ns verify)


(load-file "native/jolt-file/probes/j4b/common.clj")


(let [[verdict st] (common/open-or-refusal (first *command-line-args*))]
  (if (= :refused verdict)
    (println "RECOVERY refused" st)
    (let [{:keys [manifest datoms]} (:recovery st)]
      (println "RECOVERY" manifest (count datoms))
      (dao.space.store/close! st))))


(System/exit 0)
