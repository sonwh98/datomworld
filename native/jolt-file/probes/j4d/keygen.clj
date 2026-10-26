;; Run under jolt by probes/run.clj: one --dht-keygen through yin.repl.main's
;; startup, the path -main takes. Prints
;;   "RESULT <exit> <refusal> <principal-printed?> <seed-printed?>"
;; for run.clj, which runs it twice on one path and checks the file's mode
;; and bytes in between.
(ns keygen)


(require '[clojure.string :as str] '[yin.repl.main :as m] '[yin.vm.linker.sign :as sign])


(let [path (first *command-line-args*)
      r (m/startup ["--dht-keygen" path])
      text (try (slurp path) (catch Throwable _ nil))
      key (when text (sign/key-from-text text))]
  (println "RESULT" (:exit r) (pr-str (:refusal r))
           (boolean (and (:public key)
                         (some #(str/includes? % (sign/principal (:public key))) (:lines r))))
           (boolean (and (:seed key) (some #(str/includes? % (:seed key)) (:lines r))))))


(System/exit 0)
