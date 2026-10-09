(ns yin.vm.ucf.stage-e-process-test
  "M-next stage E across real processes: every scenario of
   yin.vm.ucf.stage-e-scenarios over the ordered JVM/Node host pairs of
   linker-dht 14.1.1 -- JVM to JVM and Node to Node as the same-host
   controls, JVM to Node and Node to JVM across hosts -- each life its
   own operating-system process (yin.vm.ucf.stage-e-peer) over the
   scenario's durable file media, each cut a real kill: the peer prints
   the cut and blocks, and this harness destroys it forcibly (SIGKILL)
   there.  Nothing crosses between lives but the bytes on disk: the
   authority's ledger, the journals, the content store's canonical
   bodies and the side log.

   The Node peer is required: it is compiled here, before the first
   Node life (shadow-cljs's :vm-bench node-script build with the peer as
   its main, to target/stage-e-peer.js), so this gate cannot skip; a
   failed build fails the test.  The JVM peer runs from classes this
   harness compiles ahead of time into a fresh directory, so each JVM
   life starts in seconds.

   No port is used and none is reserved.  Real processes are polled
   with bounded deadlines; every process is destroyed in a finally.  No
   wall-clock sleep is taken: time inside the protocol advances only by
   the clock readings a life's spec appends as ticks.

   Each scenario run appends one line to the durability record
   (target/stage-e-durability.edn): the scenario, the host pair, and for
   every life its host, pid, cut and whether it was killed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [yin.vm.ucf.stage-e-scenarios :as sc])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))


(def ^:private life-ms
  "Bound on one life, from process start to its done or cut event."
  180000)


(def pairs
  "The ordered source/receiver host pairs this harness runs: the two
   same-host controls and both cross-host orders."
  [[:jvm :jvm] [:jvm :node] [:node :jvm] [:node :node]])


;; =============================================================================
;; The peers
;; =============================================================================


(def ^:private node-peer "target/stage-e-peer.js")


(def ^:private classes-dir
  (str "target/stage-e-classes-" (random-uuid)))


(defn- run-build!
  [what command]
  (let [{:keys [exit out err]} (apply shell/sh command)]
    (when-not (zero? exit)
      (throw (ex-info (str "stage E: the " what " build failed")
                      {:command command :exit exit :out out :err err})))))


(def ^:private node-built
  (delay
    (run-build! "Node peer"
                ["clj" "-M:cljs" "-m" "shadow.cljs.devtools.cli" "compile" "vm-bench"
                 "--config-merge"
                 (str "{:main yin.vm.ucf.stage-e-peer/main :output-to \"" node-peer "\"}")])
    (when-not (.exists (io/file node-peer))
      (throw (ex-info "stage E: the Node peer build wrote nothing" {:path node-peer})))
    true))


(defn- java-bin
  []
  (str (System/getProperty "java.home") "/bin/java"))


(def ^:private jvm-built
  (delay
    (.mkdirs (io/file classes-dir))
    (run-build! "JVM peer classes"
                [(java-bin) "-cp" (str classes-dir java.io.File/pathSeparator
                                       (System/getProperty "java.class.path"))
                 "clojure.main" "-e"
                 (str "(binding [*compile-path* \"" classes-dir "\"]"
                      " (compile 'yin.vm.ucf.stage-e-peer))")])
    true))


(defn- command
  [host spec-path]
  (case host
    :jvm (do @jvm-built
             [(java-bin) "-cp" (str classes-dir java.io.File/pathSeparator
                                    (System/getProperty "java.class.path"))
              "clojure.main" "-m" "yin.vm.ucf.stage-e-peer" spec-path])
    :node (do @node-built
              ["node" node-peer spec-path])))


;; =============================================================================
;; One life, one process
;; =============================================================================


(defn- line-pump
  "A daemon thread that puts every stdout line of `process` on a queue,
   then ::eof."
  [process]
  (let [q (LinkedBlockingQueue.)
        t (Thread. ^Runnable
           (fn []
             (try
               (with-open [r (io/reader (.getInputStream process))]
                 (doseq [line (line-seq r)]
                   (.put q line)))
               (catch Throwable _ nil)
               (finally (.put q ::eof)))))]
    (.setDaemon t true)
    (.start t)
    q))


(defn- parse-event
  [line]
  (when (str/starts-with? line "E|")
    (edn/read-string (subs line 2))))


(defn- destroy!
  [process]
  (.destroyForcibly process)
  (.waitFor process 30 TimeUnit/SECONDS))


(defn run-life-in-process!
  "Run one life of `spec` as a peer process on `host`: answer the same
   result shape as the in-process runner, with the host and pid beside
   it.  A cut event is a kill: the process is destroyed forcibly the
   moment it reports its cut."
  [host spec]
  (let [spec-file (io/file (:root spec) (str "life-" (random-uuid) ".edn"))
        _ (io/make-parents spec-file)
        _ (spit spec-file (binding [*print-namespace-maps* false] (pr-str spec)))
        err-file (io/file (str (.getPath spec-file) ".err"))
        process (-> (ProcessBuilder. ^java.util.List (command host (.getPath spec-file)))
                    (.redirectError err-file)
                    (.start))]
    (try
      (.close (.getOutputStream process))
      (let [q (line-pump process)
            deadline (+ (System/currentTimeMillis) life-ms)]
        (loop [events []]
          (let [left (- deadline (System/currentTimeMillis))
                line (when (pos? left) (.poll q left TimeUnit/MILLISECONDS))]
            (cond
              (nil? line)
              (throw (ex-info "stage E: a life exceeded its deadline"
                              {:host host :spec spec :events events
                               :stderr (slurp err-file)}))

              (= ::eof line)
              (throw (ex-info "stage E: a peer ended without a report"
                              {:host host :spec spec :events events
                               :exit (.waitFor process)
                               :stderr (slurp err-file)}))

              :else
              (let [e (parse-event line)
                    events (if e (conj events e) events)]
                (case (:event e)
                  :cut (do (destroy! process)
                           {:spec spec :events events :killed? true
                            :host host :pid (:pid (first events))
                            :exit (.exitValue process)})
                  :done (do (.waitFor process 60 TimeUnit/SECONDS)
                            {:spec spec :events events :killed? false
                             :report (:report e)
                             :host host :pid (:pid (first events))})
                  :error (throw (ex-info (str "stage E: a peer failed: " (:message e))
                                         {:host host :spec spec :event e
                                          :stderr (slurp err-file)}))
                  (recur events)))))))
      (finally
        (when (.isAlive process) (destroy! process))))))


;; =============================================================================
;; The durability record
;; =============================================================================


(def ^:private record-file "target/stage-e-durability.edn")


(defn- record!
  [scenario [src rcv] results]
  (let [line {:scenario (:id scenario)
              :transport :separate-processes
              :pair [src rcv]
              :lives (mapv (fn [r]
                             {:host (:host r)
                              :role (get-in r [:spec :on])
                              :pid (:pid r)
                              :cut (get-in r [:spec :cut])
                              :killed? (:killed? r)
                              :exit (:exit r)})
                           results)}]
    (io/make-parents record-file)
    (spit record-file (str (pr-str line) "\n") :append true)
    line))


(defn run-pair!
  "Run `scenario` over ordered host pair [src rcv]: each life on the host
   its :on names, the inspection and the check here."
  [scenario [src rcv :as pair]]
  (let [results (sc/run-scenario scenario
                                 (fn [spec]
                                   (run-life-in-process! (case (:on spec) :src src :rcv rcv)
                                                         spec))
                                 (str pair " (separate processes)"))]
    (testing (str (:id scenario) " " pair " separate processes")
      (is (apply distinct? (keep :pid results))
          "every life ran in its own operating-system process")
      (is (every? #(= (case (get-in % [:spec :on]) :src src :rcv rcv) (:host %)) results)
          "every life ran on the host its role names")
      (is (every? #(if (:killed? %) (not= 0 (:exit %)) (or (nil? (:exit %)) (zero? (:exit %)))) results)
          "killed lives terminated with a fatal exit code"))
    (record! scenario pair results)))


;; =============================================================================
;; The rows
;; =============================================================================


(defn- run-pairs!
  "Run every scenario over every pair, the pairs concurrently (each
   scenario owns its root, and no port is used, so pairs never share a
   medium); the scenarios of one pair in order."
  [scenarios pairs]
  (let [runs (mapv (fn [pair]
                     (future
                       (doseq [s scenarios]
                         (run-pair! s pair))))
                   pairs)]
    (doseq [r runs] @r)))


(deftest ^:slow fork-per-host-across-processes-test
  (run-pairs! [sc/fork-row] [[:jvm :jvm] [:node :node]]))


(deftest the-cross-host-handoff-across-processes-test
  (testing "the fast-lane gate: a whole exclusive handoff and its successor
            run, JVM to Node and Node to JVM, over durable bytes alone"
    (run-pairs! [(sc/row7 nil)] [[:jvm :node] [:node :jvm]])))


(deftest ^:slow row-7-kills-around-completion-across-processes-test
  (run-pairs! (map sc/row7 sc/row7-cuts) pairs))


(deftest ^:slow row-7-failure-lower-and-lost-authority-across-processes-test
  (run-pairs! [sc/row7-failure-lower sc/row7-lost-authority] pairs))


(deftest ^:slow row-4-kills-around-result-delivery-across-processes-test
  (run-pairs! (map sc/row4 sc/row4-cuts) pairs))


(deftest ^:slow row-3-reclaim-across-processes-test
  (run-pairs! (map sc/row3 sc/row3-variants) pairs))


(deftest ^:slow row-5-replay-divergence-across-processes-test
  (run-pairs! (map sc/row5 sc/row5-variants) pairs))


(deftest ^:slow row-2-export-phases-across-processes-test
  (run-pairs! (concat (map sc/row2 (concat sc/row2-pre-fence-cuts sc/row2-post-fence-cuts))
                      (map sc/row2-carrier sc/row2-carriers))
              pairs))


(deftest ^:slow row-1-candidates-and-encodings-across-processes-test
  (run-pairs! [sc/row1] pairs))


(deftest ^:slow row-6-partition-across-processes-test
  (run-pairs! [sc/row6-request-partition sc/row6-consumer-partition sc/row6-lost-reply] pairs))


(deftest ^:slow clause-8-admission-order-across-processes-test
  (run-pairs! (concat (map sc/clause8 sc/clause8-variants) [sc/clause8-closed]) pairs))


(deftest ^:slow clause-3-sequence-and-regrant-across-processes-test
  (run-pairs! [sc/clause3-regrant sc/clause3-carried] pairs))


(deftest ^:slow inbox-and-journal-cuts-across-processes-test
  (run-pairs! (concat (map #(sc/journal-row {:cut %}) sc/journal-cuts)
                      (map #(sc/journal-row {:fail %}) sc/uncertain-retentions))
              pairs))
