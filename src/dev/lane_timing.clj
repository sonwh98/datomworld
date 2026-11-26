(ns lane-timing
  "Shared lane timing records and report. Run: bb src/dev/lane_timing.clj runs-ndjson.txt"
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn now []
  {:wall (str (java.time.Instant/now)) :mono-ns (System/nanoTime)})

(defn record [fields start end rc]
  (merge fields {:start-wall (:wall start) :start-mono-ns (:mono-ns start)
                 :end-wall (:wall end) :end-mono-ns (:mono-ns end)
                 :rc rc :wall-seconds (/ (double (- (:mono-ns end) (:mono-ns start))) 1e9)}))

(defn append! [lock path row]
  (locking lock
    (spit path (str (json/generate-string row) "\n") :append true)))

(defn report [path]
  (let [rows (mapv #(json/parse-string % true) (remove str/blank? (str/split-lines (slurp path))))
        ordered (sort-by (juxt #(java.time.Instant/parse (:start-wall %)) :job-id) rows)
        origin (when (seq rows) (apply min (map #(inst-ms (java.util.Date/from (java.time.Instant/parse (:start-wall %)))) rows)))]
    (println "Gantt (seconds from first start; overlapping intervals retain their positions)")
    (doseq [r ordered]
      (let [start (/ (- (inst-ms (java.util.Date/from (java.time.Instant/parse (:start-wall r)))) origin) 1000.0)]
        (printf "%9.3f - %9.3f  %-20s %-30s rc=%s %s%n" start (+ start (:wall-seconds r)) (:worker-id r) (:job-id r) (:rc r) (or (:timing-scope r) ""))))
    (println "\nWall ranking (seconds; process-scope rows are not per-file measurements)")
    (doseq [r (sort-by (juxt (comp - :wall-seconds) :job-id) rows)]
      (printf "%9.3f  %-30s %s%n" (double (:wall-seconds r)) (:job-id r) (or (:timing-scope r) "")))))

(when (= *file* (System/getProperty "babashka.file"))
  (if (= 1 (count *command-line-args*))
    (report (first *command-line-args*))
    (do (binding [*out* *err*] (println "Usage: bb src/dev/lane_timing.clj runs-ndjson.txt"))
        (System/exit 2))))
