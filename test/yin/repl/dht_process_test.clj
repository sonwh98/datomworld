(ns yin.repl.dht-process-test
  "DHT epic S5 across real processes and real loopback sockets
   (docs/design/dao.jing.dht.md section 10): separate `yin.repl.main`
   JVMs, each over its own locked `dht:<dir>`, exchange content through
   their UDP sockets.

   Two fetch-only listeners and one publisher: the publisher's round
   prints its result at once and its publication is later reported
   acknowledged, sent to 2 peers.  Then a reader handed the manifest
   address the publisher printed hydrates the publisher's index over the
   network before it evaluates the lines already typed at it, and its `q`
   answers the publisher's facts under the publisher's session token.

   No port is reserved ahead of time.  The first node is an anchor in
   this JVM — a `dao.jing.dht` core node over the JVM datagram seam,
   bound to port 0 — and every REPL process binds an ephemeral port and
   reports it (`listening on host:port`); each later process is handed
   the addresses already reported.

   Each directory is locked by its owner: a plain `dao.space.dht/join`
   and a second REPL process on the publisher's directory are both
   refused, naming it.  The Node reader is required: build it with
   `bb build:yin-repl-node`; a missing build fails this test.

   Real processes wait on sockets, so this test polls their output with
   bounded deadlines; the DHT core itself still sees only ticks."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing.dht :as jing.dht]
            [dao.jing.mem :as mem]
            [dao.space.dht :as dht]
            [dao.stream :as stream]
            [dao.stream.datagram :as datagram]
            [dao.stream.datagram.jvm :as datagram.jvm]
            [dao.stream.ringbuffer :as ringbuffer])
  (:import (java.util.concurrent TimeUnit)))


(def ^:private startup-ms
  "Bound on one JVM REPL's startup: it loads the whole shell from source."
  240000)


(def ^:private exchange-ms
  "Bound on one network outcome once every process is up."
  60000)


(defn- ring
  []
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key 4096})))


(def ^:private bind-ms
  "Bound on the anchor's socket reporting its bind."
  10000)


(defn- await-bound
  "The anchor socket's `:bound` event from `traffic`, polled until
   `bind-ms` pass, or `{:failure text}`: a `:bind-failed` event, or no
   lifecycle event in time.  On failure the seam is closed."
  [seam traffic]
  (let [deadline (+ (System/currentTimeMillis) bind-ms)
        fail (fn [text]
               ((:close! seam))
               {:failure (str "the anchor socket did not bind: " text)})]
    (loop [cursor (:dao.stream/cursor (stream/cursor traffic :dao.stream/oldest))]
      (let [r (stream/next traffic cursor)
            v (:dao.stream/value r)]
        (cond
          (not= :dao.stream/ok (:dao.stream/outcome r))
          (if (< (System/currentTimeMillis) deadline)
            (do (Thread/sleep 5) (recur cursor))
            (fail (str "no bound event within " bind-ms " ms")))

          (= :dao.stream.datagram/bound (:dao.stream.datagram/event v))
          {:bound v}

          (= :dao.stream.datagram/bind-failed (:dao.stream.datagram/event v))
          (fail (str "bind-failed: " (:dao.stream.datagram/reason v)))

          :else (recur (:dao.stream/cursor r)))))))


(defn- start-bound-anchor!
  [seam traffic bound]
  (let [port (get-in bound [:dao.stream.datagram/local :dao.stream.datagram/port])
        ticks (ring)
        state (jing.dht/state
                {:local (mem/create-content-mem)
                 :requests (ring) :answers (ring) :facts (ring) :ticks ticks
                 :traffic traffic
                 :datagrams (datagram/writer
                              seam
                              {:dao.stream/type datagram/transport-type
                               :dao.stream/identity "anchor"
                               :dao.stream.datagram/bind-host "127.0.0.1"
                               :dao.stream.datagram/bind-port port}
                              1200)
                 ::jing.dht/id (jing.dht/bytes->hex (dht/secure-random-bytes 32))
                 ::jing.dht/secret (dht/secure-random-bytes 32)
                 ::jing.dht/max-inbound-bytes 0})
        running (atom true)
        origin (System/nanoTime)
        thread (doto (Thread.
                       ^Runnable
                       (fn []
                         (loop [state state]
                           (when @running
                             (stream/append! ticks
                                             {:dao.lease/event :dao.lease/tick
                                              :dao.lease/reading
                                              (quot (- (System/nanoTime) origin)
                                                    1000000)})
                             (let [state (jing.dht/step state 64)]
                               (Thread/sleep 5)
                               (recur state)))))
                       "dht-process-anchor")
                 (.setDaemon true)
                 (.start))]
    {:port port
     :stop! (fn []
              (reset! running false)
              (.join thread 1000)
              ((:close! seam)))}))


(defn- start-anchor!
  "The first node: a `dao.jing.dht` core node on an ephemeral loopback
   port, which needs no bootstrap contact to hold a socket, stepped by
   one owner thread with its monotonic clock as ticks.  Answers `{:port p
   :stop! f}`, or `{:failure text}` when its socket did not bind (the
   seam already closed)."
  []
  (let [traffic (ring)
        seam (datagram.jvm/bind! {:identity "anchor" :deposit traffic
                                  :bind-host "127.0.0.1" :bind-port 0
                                  :max-bytes 1200})
        {:keys [bound failure]} (await-bound seam traffic)]
    (if failure
      {:failure failure}
      (start-bound-anchor! seam traffic bound))))


(def ^:private listening
  #"dht: node [0-9a-f]+ listening on 127\.0\.0\.1:(\d+)")


(defn- temp-dir
  []
  (str "target/test-dht-process-" (random-uuid)))


(defn- delete-dir!
  [dir]
  (let [f (io/file dir)]
    (when (.isDirectory f)
      (doseq [child (.listFiles f)] (.delete child))
      (.delete f))))


(defn- jvm-command
  "`yin.repl.main` on a JVM over this test's classpath."
  []
  [(str (System/getProperty "java.home") "/bin/java")
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-m" "yin.repl.main"])


(def ^:private node-script
  "The Node build of `yin.repl.main` (shadow-cljs build `:yin-repl`),
   built by `bb build:yin-repl-node`."
  "target/yin-repl.js")


(defn- spawn-command!
  "Start the process `command`, collecting its merged output lines."
  [label command]
  (let [pb (doto (ProcessBuilder. ^java.util.List command)
             (.redirectErrorStream true))
        process (.start pb)
        lines (atom [])
        reader (doto (Thread.
                       ^Runnable
                       (fn []
                         (with-open [r (io/reader (.getInputStream process))]
                           (doseq [line (line-seq r)]
                             (swap! lines conj line))))
                       (str "dht-process-" label))
                 (.setDaemon true)
                 (.start))]
    {:label label
     :process process
     :lines lines
     :reader reader
     :stdin (io/writer (.getOutputStream process))}))


(defn- spawn!
  "Start one REPL process — `command`, a `yin.repl.main` JVM by default,
   then `args`."
  ([label args] (spawn! label (jvm-command) args))
  ([label command args]
   (spawn-command! label (into (vec command) args))))


(defn- type!
  [{:keys [stdin]} line]
  (.write ^java.io.Writer stdin (str line "\n"))
  (.flush ^java.io.Writer stdin))


(defn- await-line
  "The first output line matching `re`, polling until `ms` pass."
  [{:keys [lines]} re ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (some #(when (re-find re %) %) @lines)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))


(defn- index-of-match
  [{:keys [lines]} re]
  (first (keep-indexed (fn [i l] (when (re-find re l) i)) @lines)))


(defn- stop!
  [{:keys [process] :as p}]
  (try (type! p "(quit)") (catch Exception _ nil))
  (when-not (.waitFor ^Process process 20 TimeUnit/SECONDS)
    (.destroyForcibly ^Process process)
    (.waitFor ^Process process 10 TimeUnit/SECONDS)))


(defn- transcript
  [{:keys [label lines]}]
  (str label ":\n" (str/join "\n" @lines)))


(def ^:private token-query
  (str "(dao.space.query/q (quote [:find ?s :where [?e :yin/value 4242 ?t ?m]"
       " [?m :yin.repl/session ?s ?t1 ?m1]]) {:view :history})"))


(def ^:private remote-query
  '[:find ?v :where [?e :yin/value ?v]])


(defn- bound-port
  "The port a REPL process reported it bound, waiting for the report."
  [p]
  (some->> (await-line p listening startup-ms)
           (re-find listening)
           second
           Long/parseLong))


(deftest separate-jvm-processes-exchange-content-over-loopback
  (let [anchor (start-anchor!)
        dirs (vec (repeatedly 6 temp-dir))
        peer #(str "127.0.0.1:" %)
        procs (atom [])]
    (is (nil? (:failure anchor)) (:failure anchor))
    (when-not (:failure anchor)
      (try
        (let [b (spawn! "listener-b" ["--index-store" (str "dht:" (dirs 1))
                                      "--dht-peer" (peer (:port anchor))])
              c (spawn! "listener-c" ["--index-store" (str "dht:" (dirs 2))
                                      "--dht-peer" (peer (:port anchor))])
              _ (swap! procs into [b c])
              [pb pc] [(bound-port b) (bound-port c)]
              _ (testing "each listener binds an ephemeral port and reports it"
                  (is (some? pb) (transcript b))
                  (is (some? pc) (transcript c)))
              a (spawn! "publisher-a" ["--index-store" (str "dht:" (dirs 0))
                                       "--dht-peer" (peer pb) "--dht-peer" (peer pc)
                                       "--dht-publish"])
              _ (swap! procs conj a)
              pa (bound-port a)]
          (testing "the publisher binds and reports its address too"
            (is (some? pa) (transcript a)))
          (testing "the publisher's directory is locked against every other owner"
            (let [e (try (dht/close! (dht/join {:dir (dirs 0)})) nil
                         (catch Exception e e))]
              (is (some? e) "a plain join in this process is refused")
              (is (str/includes? (str (ex-message e)) (dirs 0))))
            (let [rival (spawn! "rival-a" ["--index-store" (str "dht:" (dirs 0))])]
              (swap! procs conj rival)
              (is (.waitFor ^Process (:process rival) 240 TimeUnit/SECONDS)
                  (transcript rival))
              (is (= 1 (.exitValue ^Process (:process rival))) (transcript rival))
              (is (some #(and (str/includes? % (dirs 0)) (str/includes? % "locked"))
                        @(:lines rival))
                  (transcript rival))))
          (testing "the publisher stated what it would share before sharing"
            (is (await-line a #"publishing is ON\. Everything in .*content\.jing will be shared"
                            1000)
                (transcript a))
            (is (await-line b #"fetch-only" 1000) (transcript b)))
          (type! a "(def answer 4242)")
          (let [published (await-line a #"dht: published :segment/\S+ .*acknowledged"
                                      exchange-ms)
                manifest (second (re-find #"dht: published :(segment/\S+)"
                                          (str published)))]
            (testing "the publication is acknowledged: sent to 2 peers"
              (is (some? published) (transcript a))
              (is (re-find #"— acknowledged: sent to 2 peers" (str published))
                  (transcript a)))
            (testing "the round printed its result before the network answered"
              (is (< (index-of-match a #"^(yin> )*4242$")
                     (index-of-match a #"dht: published"))
                  (transcript a)))
            (testing "the publisher's HEAD names what it announced"
              (is (str/includes? (slurp (str (dirs 0) "/HEAD")) (str ":" manifest))))
            (type! a "(require (quote dao.space.query))")
            (type! a token-query)
            (let [own (await-line a #"#\{\[\"[0-9a-f-]+\"\]\}" exchange-ms)
                  token (second (re-find #"\"([0-9a-f-]+)\"" (str own)))
                  d (spawn! "reader-d" ["--index-store" (str "dht:" (dirs 3))
                                        "--dht-peer" (peer pa)
                                        "--dht-manifest" (str ":" manifest)])]
              (swap! procs conj d)
              (is (some? token) (transcript a))
              ;; Typed before the reader has hydrated: these lines wait in its
              ;; input medium until the remote index is installed.
              (type! d "(require (quote dao.space.query))")
              (type! d token-query)
              (testing "the reader hydrates the remote index over the network"
                (is (await-line d (re-pattern (str "dht: hydrated :" manifest))
                                startup-ms)
                    (transcript d)))
              (testing "and q answers the publisher's fact under its token"
                (let [answer (await-line d #"#\{\[\"[0-9a-f-]+\"\]\}" exchange-ms)]
                  (is (some? answer) (transcript d))
                  (is (str/includes? (str answer) (str token)) (transcript d))
                  (is (< (index-of-match d #"dht: hydrated")
                         (index-of-match d #"#\{\[\""))
                      "the typed lines ran only once the index was installed")))
              (testing "the reader's own rounds continue that index, unshared"
                ;; Its require and q rounds each published over the hydrated
                ;; index, so its HEAD has moved past the manifest it fetched,
                ;; and publishing is off, so none of it left the process.
                (is (await-line d #"dht: published :segment/\S+ \(\d+ blobs\) — NOT acknowledged: publication is off.*; \d+ of \d+ blobs not sent; not retrying"
                                exchange-ms)
                    (transcript d))
                (is (re-find #"\{:version 1, :manifest :segment/"
                             (slurp (str (dirs 3) "/HEAD")))))
              (testing "plain Clojure in this JVM loads the same index over UDP"
                (let [m (keyword manifest)
                      node (dht/load-index
                             (dht/join {:dir (dirs 5)
                                        :peers [{:host "127.0.0.1" :port pa}]})
                             m)
                      deadline (+ (System/currentTimeMillis) exchange-ms)
                      node (loop [node node]
                             (let [[node _] (dht/step node (System/currentTimeMillis))]
                               (if (or (not= :loading (:status (dht/load-status node m)))
                                       (> (System/currentTimeMillis) deadline))
                                 node
                                 (do (Thread/sleep 10) (recur node)))))
                      expected (try (dht/q node m remote-query)
                                    (finally (dht/close! node)))]
                  (is (= :loaded (:status (dht/load-status node m))))
                  (is (contains? (set expected) [4242]))
                  (testing "and the reader's host functions answer the same"
                    (type! d "(require (quote dao.space.dht))")
                    (type! d (str "(dao.space.dht/load-index :" manifest ")"))
                    ;; D hydrated this very manifest at startup through the
                    ;; same load, so load-index answers :loaded at once.
                    (is (await-line d #"^(yin> )*:loaded$" exchange-ms)
                        (transcript d))
                    (type! d (str "(dao.space.dht/q :" manifest " (quote "
                                  (pr-str remote-query) "))"))
                    (is (await-line d (re-pattern
                                        (str "^(yin> )*"
                                             (java.util.regex.Pattern/quote
                                               (pr-str expected))
                                             "$"))
                                    exchange-ms)
                        (str (pr-str expected) "\n" (transcript d))))))
              (testing "each directory is its own, and each is locked"
                (is (= 4 (count (set (take 4 dirs)))))
                (doseq [dir (take 4 dirs)]
                  (is (.exists (io/file dir "lock")) dir)))
              (is (.exists (io/file node-script))
                  (str "the JVM-to-Node leg is required: " node-script
                       " is absent; build it with `bb build:yin-repl-node`"))
              (when (.exists (io/file node-script))
                (let [e (spawn! "node-reader-e" ["node" node-script]
                                ["--index-store" (str "dht:" (dirs 4))
                                 "--dht-peer" (peer pa)
                                 "--dht-manifest" (str ":" manifest)])]
                  (swap! procs conj e)
                  (type! e "(require (quote dao.space.query))")
                  (type! e token-query)
                  (testing "a Node reader hydrates the JVM publisher's index"
                    (is (await-line e (re-pattern (str "dht: hydrated :" manifest))
                                    startup-ms)
                        (transcript e))
                    (let [answer (await-line e #"#\{\[\"[0-9a-f-]+\"\]\}"
                                             exchange-ms)]
                      (is (str/includes? (str answer) (str token))
                          (transcript e)))))))))
        (finally
          (doseq [p @procs] (stop! p))
          ((:stop! anchor))
          (run! delete-dir! dirs))))))
