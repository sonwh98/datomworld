;; T1 step S7: write the Node oracle and the pinned CST packets for the T1
;; corpus (docs/design/yang.typescript.md section 9).
;;
;;   clojure -M:dev -m yang-ts-oracle
;;
;; Reads test/resources/yang/typescript/t1_corpus.edn. For each entry the
;; source runs under Node (the mise-pinned version, recorded in the oracle
;; header) with src/dev/yang_ts_oracle_preload.cjs, which prints an uncaught
;; throw as an `Uncaught <name>: <message>` line; the entry's output lines
;; go to t1_oracle.edn. The JVM parser's packet for each source goes to
;; t1_packets.edn, so Node and Dart can run the corpus without a parser.
;; Neither file is regenerated on another host or another Node.
(ns yang-ts-oracle
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [yang.typescript.antlr.cst :as cst]))


(def dir "test/resources/yang/typescript/")
(def corpus-path (str dir "t1_corpus.edn"))
(def oracle-path (str dir "t1_oracle.edn"))
(def packets-path (str dir "t1_packets.edn"))
(def preload-path "src/dev/yang_ts_oracle_preload.cjs")


(defn node-version
  []
  (str/trim (:out (shell/sh "node" "--version"))))


(defn- run-node
  [source]
  (let [file (java.io.File/createTempFile "yang-ts-oracle" ".mjs")]
    (try
      (spit file source)
      (let [{:keys [out err exit]}
            (shell/sh "node" "-r" (.getAbsolutePath (io/file preload-path))
                      (.getAbsolutePath file))]
        {:out (if (str/blank? out) [] (str/split-lines (str/trim-newline out))),
         :exit exit,
         :err err})
      (finally (.delete file)))))


(defn- write-entries!
  "`header` then one entry per line, printed with `pr-str` (no whitespace
   before a closer)."
  [path header entries]
  (spit path
        (str "{:header " (pr-str header) "\n :entries [\n"
             (str/join "\n" (map pr-str entries))
             "]}\n")))


(defn -main
  [& _]
  (let [corpus (edn/read-string (slurp corpus-path))
        header {:node (node-version),
                :generated (str (java.time.LocalDate/now)),
                :generator "src/dev/yang_ts_oracle.clj"}
        worker (cst/make-worker :ts)
        results (mapv (fn [{:keys [id source]}]
                        (assoc (run-node source) :id id))
                      corpus)]
    (write-entries! oracle-path header
                    (mapv (fn [{:keys [id out exit]}]
                            {:id id, :out out, :exit exit})
                          results))
    (write-entries! packets-path (assoc header :grammar cst/grammar-id)
                    (mapv (fn [{:keys [id source]}]
                            {:id id,
                             :packet (cst/parse-source worker [:unit 0] source)})
                          corpus))
    (doseq [{:keys [id err exit]} results
            :when (or (not (zero? exit)) (not (str/blank? err)))]
      (println "NODE" id "exit" exit (str/trim err)))
    (println "wrote" (count corpus) "entries")
    (shutdown-agents)))
