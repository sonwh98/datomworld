(ns yin.vm.ucf.stage-e-peer
  "M-next stage E's peer: one life of a crash/partition scenario
   (yin.vm.ucf.stage-e-world) run as its own operating-system process,
   the process transport of linker-dht 14.1.3 for the JVM and Node.

   The parent (yin.vm.ucf.stage-e-process-test) writes the life's spec
   as EDN to a file and starts this program with that path.  Every event
   the life emits is printed at once as one line, `E|` followed by the
   event as EDN; the life's report arrives as {:event :done}.  When the
   life's cut fires the peer prints {:event :cut} and blocks forever:
   the parent destroys the process there (SIGKILL), so nothing the life
   did after its cut ever happens and nothing it did before is undone --
   the durable media hold exactly what a killed process leaves.

   JVM: `java -cp <classpath> clojure.main -m yin.vm.ucf.stage-e-peer
   spec.edn`.  Node: the shadow-cljs :node-script build of `main`, which
   the parent compiles before its first Node life."
  (:require #?@(:cljd []
                :clj [[clojure.edn :as edn]]
                :cljs [[cljs.reader :as reader]])
            [yin.vm.ucf.stage-e-world :as world]))


(defn- read-spec
  [text]
  #?(:cljd nil
     :clj (edn/read-string text)
     :cljs (reader/read-string text)))


(defn- slurp-file
  [path]
  #?(:cljd nil
     :clj (slurp path)
     :cljs (.toString (.readFileSync (js/require "fs") path) "utf8")))


(defn- write-line!
  [line]
  #?(:cljd nil
     :clj (locking *out*
            (.write *out* (str line "\n"))
            (.flush *out*))
     ;; synchronous: a pipe write on Node may otherwise still be queued
     ;; when the peer blocks at its cut
     :cljs (.writeSync (js/require "fs") 1 (str line "\n"))))


(defn- block-forever!
  "Never return: the parent kills the process here."
  []
  #?(:cljd nil
     :clj @(promise)
     :cljs (loop []
             (recur))))


(defn- emit!
  [event]
  (write-line! (str "E|" (binding [*print-namespace-maps* false
                                   *print-length* nil
                                   *print-level* nil]
                           (pr-str (world/->data event))))))


(defn- host
  []
  #?(:cljd :dart :clj :jvm :cljs :node))


(defn- pid
  []
  #?(:cljd nil
     :clj (.pid (java.lang.ProcessHandle/current))
     :cljs (.-pid js/process)))


(defn run-spec!
  "Run the life whose spec is EDN `text`, printing its events."
  [text]
  (let [spec (read-spec text)
        ctx (world/context {:cut (:cut spec)
                            :fail (:fail spec)
                            :emit! emit!
                            :at-cut block-forever!})]
    (emit! {:event :started :host (host) :pid (pid) :cut (:cut spec)})
    (try
      (let [report (if (:inspect spec)
                     (world/inspect spec)
                     (world/run-life ctx spec))]
        (emit! {:event :done :report report}))
      (catch #?(:cljd Object :clj Throwable :cljs :default) e
        (if (world/killed? e)
          (block-forever!)
          (emit! {:event :error
                  :message #?(:cljd (str e) :clj (ex-message e) :cljs (ex-message e))
                  :data (world/->data (ex-data e))}))))))


#?(:cljd nil
   :clj
   (defn -main
     [path]
     (run-spec! (slurp-file path))
     (shutdown-agents)
     (System/exit 0)))


#?(:cljd nil
   :cljs
   (defn main
     [path]
     (run-spec! (slurp-file path))))
