;; A durable-directory owner for the J4b multi-process gates, run on the JVM
;; (clojure -M) or under jolt (-Srepro -M:jolt) by probes/run.clj:
;;
;;   owner.clj <dir> <signal-dir>
;;
;; Opens <dir> through dao.space.store/open (directory lock, content log,
;; HEAD), writes <signal-dir>/pid and then <signal-dir>/acquired (or
;; <signal-dir>/refused and exits 3), and serves commands until "stop": the
;; harness writes <signal-dir>/cmd as "<seq> <command> [args]" (by rename) and
;; waits for <signal-dir>/ack-<seq>:
;;   write <n>   publish n datoms and move HEAD; ack: the manifest address
;;   spawn <script> <args...>   (jolt only) run a fresh jolt child with this
;;               process's lock and content descriptors open; ack: its output
;;   stop        close the store (release the lock); ack, then exit 0
(ns owner
  (:require [clojure.string :as str]))


(load-file "native/jolt-file/probes/j4b/common.clj")


(def dir (first *command-line-args*))
(def sig (second *command-line-args*))


(defn- put!
  [nm text]
  (let [tmp (str sig "/." nm ".tmp")]
    (spit tmp text)
    (.renameTo (java.io.File. tmp) (java.io.File. (str sig "/" nm)))))


(put! "pid" (str (common/pid)))

(def opened (common/open-or-refusal dir))


(when (= :refused (first opened))
  (put! "refused" (second opened))
  (System/exit 3))


(def st (second opened))
(put! "acquired" "acquired")


(defn- spawn
  "A fresh jolt child (sh -c '. jolt-env && exec jolt ...'), started from the
   repository root while this process holds the lock and the content log."
  [args]
  (let [pb (ProcessBuilder.
             ^java.util.List
             (into ["sh" "-c"
                    ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt \"$@\""
                    "jolt"]
                   args))
        _ (.directory pb (java.io.File. (System/getProperty "user.dir")))
        _ (.redirectErrorStream pb true)
        p (.start pb)
        out (slurp (.getInputStream p))]
    (str "exit " (.waitFor p) "\n" out)))


(loop [last-seq 0]
  (let [f (java.io.File. (str sig "/cmd"))
        [s cmd & args] (when (.exists f) (str/split (str/trim (slurp f)) #" "))
        n (when s (parse-long s))]
    (if (and n (> n last-seq))
      (let [result (case cmd
                     "write" (str (common/publish! st (parse-long (first args))))
                     "spawn" (spawn args)
                     "stop" (do (dao.space.store/close! st) "stopped"))]
        (put! (str "ack-" n) result)
        (when (= cmd "stop") (System/exit 0))
        (recur n))
      (do (Thread/sleep 20) (recur last-seq)))))
