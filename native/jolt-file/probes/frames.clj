;; Run on the JVM (clojure -M) and under jolt (-Srepro -M:jolt) by
;; probes/run.clj, with an output path: the same fixed puts through
;; dao.jing.file on both hosts, under both registered hash algorithms, so the
;; two files can be compared byte for byte (the JVM is canonical). Payloads
;; include embedded NUL, non-ASCII text and a frame over 64 KiB.
(ns frames)


(require '[dao.jing :as jing] '[dao.jing.file :as jf])


(def payloads
  [["blake3" :blake3 "plain text"]
   ["sha256" :sha256 "plain text"]
   ["nul" :blake3 "a\u0000b\u0000"]
   ["nul-sha" :sha256 "a\u0000b\u0000"]
   ["unicode" :blake3 "é中😀"]
   ["map" :sha256 {:k [1 2 3], "s" #{:a :b}, :n -42}]
   ["large" :blake3 (apply str (repeat 70000 "z"))]
   ["large-sha" :sha256 (apply str (repeat 70000 "y"))]])


(let [path (first *command-line-args*)
      _ (.delete (java.io.File. ^String path))
      h (jf/create-content-file path)]
  (doseq [[label algorithm v] payloads]
    (println "ADDRESS" label (jing/materialize! h v {:algorithm algorithm})))
  (jing/close! h)
  (println "RECORDS" (count (jf/records path))))
