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

   Linker over DHT slice L5 (docs/design/yin.vm.linker.dht.md section 12)
   is the end-to-end gate: a JVM publisher holding a key file publishes a
   module by name; JVM and Node readers handed its index manifest and
   principal require it by name and evaluate the export on each of the
   four VMs; a reader that does not declare the principal is refused; and
   plain Clojure in this JVM takes the same path through the functions
   the REPL's host functions call, failure results included.  Its
   in-process form, over the mesh seam on all three hosts, is
   yin.vm.linker.dht-end-to-end-test.

   Real processes wait on sockets, so this test polls their output with
   bounded deadlines; the DHT core itself still sees only ticks."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.dht :as jing.dht]
            [dao.jing.mem :as mem]
            [dao.space.dht :as dht]
            [dao.space.dht-test :as dht-test]
            [dao.stream :as stream]
            [dao.stream.datagram :as datagram]
            [dao.stream.datagram.jvm :as datagram.jvm]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.repl.main :as main]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.linker :as linker]
            [yin.vm.linker.closure-test :as ct]
            [yin.vm.linker.dht :as ld]
            [yin.vm.linker.publish :as publish]
            [yin.vm.linker.sign :as sign]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu])
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
  [seam traffic bound opts]
  (let [port (get-in bound [:dao.stream.datagram/local :dao.stream.datagram/port])
        ticks (ring)
        state (jing.dht/state
                (merge
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
                   ::jing.dht/max-inbound-bytes 0}
                  opts))
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
   seam already closed).  `opts` merge into the core state: a storing
   peer is `{::jing.dht/publish? true ::jing.dht/max-inbound-bytes n}`."
  ([] (start-anchor! {}))
  ([opts]
   (let [traffic (ring)
         seam (datagram.jvm/bind! {:identity "anchor" :deposit traffic
                                   :bind-host "127.0.0.1" :bind-port 0
                                   :max-bytes 1200})
         {:keys [bound failure]} (await-bound seam traffic)]
     (if failure
       {:failure failure}
       (start-bound-anchor! seam traffic bound opts)))))


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


;; =============================================================================
;; Linker over DHT slice L5: a module by name, end to end
;; =============================================================================

(defn- await-count
  "The output lines matching `re` once there are at least `n`, polling
   until `ms` pass; nil if there never were."
  [{:keys [lines]} re n ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [found (filterv #(re-find re %) @lines)]
        (cond
          (<= n (count found)) found
          (< (System/currentTimeMillis) deadline) (do (Thread/sleep 50) (recur))
          :else nil)))))


(def ^:private publisher-lines
  "A defines a function and a module-level value read by a second
   function, and publishes each as a module by name."
  ["(def f (fn [x] (+ x 4200)))"
   "(def base 7)"
   "(def g (fn [] (+ base 1)))"
   "(require (quote yin.link))"
   "(yin.link/publish (quote my.lib) (quote [f]))"
   "(yin.link/publish (quote my.store) (quote [g]))"])


(def ^:private vm-types
  [:ast-walker :semantic :stack :register])


(def ^:private vm-names
  {:ast-walker "ASTWalkerVM" :semantic "SemanticVM"
   :stack "DebruijnStackVM" :register "DebruijnRegisterVM"})


(defn- require-on-each-vm
  "At reader `p`, on each VM in turn: switch to it, require `my.lib` by
   name and evaluate its export, then require the store corpus `my.store`
   and evaluate its export.  Each step waits for the line it answers
   (`(vm ...)` is answered at once even while a require is pending, so
   nothing is typed ahead).  Answers the failures, each naming the VM and
   the line it did not see."
  [p]
  (reduce
    (fn [failures [i vm-type]]
      (let [n (inc i)
            step (fn [failures line re k]
                   (if (seq failures)
                     failures
                     (do (type! p line)
                         (if (await-count p re k exchange-ms)
                           failures
                           (conj failures (str vm-type ": " line " never answered "
                                               re " (" k ")"))))))
            walker? (= :ast-walker vm-type)]
        (-> failures
            (step (str "(vm " vm-type ")")
                  (re-pattern (str "Switched to " (vm-names vm-type))) 1)
            (step "(require (quote my.lib))" #"^(yin> )*'my\.lib$" n)
            (step "(my.lib/f 1)" #"^(yin> )*4201$" n)
            (step "(require (quote my.store))"
                  (if walker?
                    #"Module link refused: undeclared-free"
                    #"^(yin> )*'my\.store$")
                  (if walker? 1 (dec n)))
            (cond-> (not walker?)
              (step "(my.store/g)" #"^(yin> )*8$" (dec n))))))
    []
    (map-indexed vector vm-types)))


(def ^:private published-line
  #"dht: published :(segment/\S+) .*— acknowledged")


(defn- head-of-dir
  [dir]
  (some->> (io/file dir "HEAD") (#(when (.exists ^java.io.File %) (slurp %)))
           (re-find #":manifest :(segment/\S+?)\}")
           second))


(defn- await-head-published
  "The index manifest A's output reports acknowledged last, once it is
   the one A's HEAD names: what A announced after its last round."
  [a dir ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [last-published (some->> @(:lines a)
                                    (keep #(second (re-find published-line %)))
                                    last)]
        (cond
          (and last-published (= last-published (head-of-dir dir))) last-published
          (< (System/currentTimeMillis) deadline) (do (Thread/sleep 100) (recur))
          :else nil)))))


(defn- step-until
  "Step the plain node with the wall clock until `(done? node)`: `[node
   nil]`, or `[node cause]` when `ms` pass first."
  [node ms cause done?]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [node node]
      (cond
        (done? node) [node nil]
        (> (System/currentTimeMillis) deadline) [node (str cause " within " ms " ms")]
        :else (let [[node _] (dht/step node (System/currentTimeMillis))]
                (Thread/sleep 10)
                (recur node))))))


(defn- settled?
  [address]
  (fn [node]
    (contains? #{:loaded :failed} (:status (dht/load-status node address)))))


(defn- hiding
  "`store` whose reads of any address `@hidden` holds answer not-found."
  [store hidden]
  (assoc store :get-bytes-fn
         (fn [address not-found]
           (if (contains? @hidden address)
             not-found
             ((:get-bytes-fn store) address not-found)))))


(defn- loaded-vm
  "A VM of `format-kw`'s own backend holding the linked image `res`."
  [format-kw res]
  (let [contract (get-in res [:manifest :yin.module/contracts format-kw])
        receiver {:primitives vm/primitives :contract contract}]
    (case format-kw
      :yin.ast/code (ast-walker/vm-load-rows (tu/create-vm) (:value res) contract)
      :yin.semantic/code (semantic/load-vector (semantic/create-vm) (:value res) contract)
      :yin.debruijn.code (dvm/create-vm (:value res) receiver)
      :yin.debruijn.register (rvm/create-vm (:value res) receiver))))


(def ^:private call-f
  {:type :application
   :operator {:type :variable :name 'f}
   :operands [{:type :literal :value 1}]})


(defn- undeliverable
  "A request writer that refuses every append as closed."
  []
  (reify
    stream/IDaoStreamWriter

    (append! [_ _] {:dao.stream/outcome :dao.stream/closed})))


(defn- requiring-app!
  "Publish `app` into `store`: `(require 'my.lib)`, then `(yin/def g
   (my.lib/f 1))`, pinned to the manifest `lib`."
  [store lib]
  (let [variable (fn [n] {:type :variable :name n})
        call (fn [f & xs] {:type :application :operator f :operands (vec xs)})]
    (publish/publish-module!
      store {:name 'app
             :ast (call {:type :lambda :params '[_]
                         :body (ct/def! 'g (call (variable 'my.lib/f) (ct/lit 1)))}
                        (call (variable 'require) (ct/lit 'my.lib)))
             :exports #{'g}
             :requires {'my.lib lib}
             :primitives {'require (vm/profile-of vm/primitives 'require)}})))


(defn- plain-clojure-leg
  "join -> load-index -> names -> load-module -> link over real UDP, the
   functions the REPL's host functions call, then each failure result of
   section 9 as the plain functions answer it."
  [peers manifest public]
  (let [hidden (atom #{})
        node (dht/join {:local (hiding (mem/create-content-mem) hidden)
                        :peers (mapv (fn [p] {:host "127.0.0.1" :port p}) peers)})
        m (keyword manifest)
        authority (ld/authority {:principals [public]})
        principal (sign/principal public)]
    (try
      (let [[node cause] (step-until (dht/load-index node m) exchange-ms
                                     "the index did not load" (settled? m))
            _ (is (nil? cause) cause)
            _ (is (= :loaded (:status (dht/load-status node m))))
            names (ld/names node authority)
            lib (get-in names [:names 'my.lib :address])
            store-module (get-in names [:names 'my.store :address])]
        (testing "names: the publisher's signed names resolve under its principal"
          (is (= [m] (ld/snapshots node)))
          (is (jing/segment-address? lib) (pr-str names))
          (is (= [principal] (get-in names [:names 'my.lib :yin.link/provenance
                                            :yin.module/asserted-by])))
          (is (= (get-in names [:names 'my.lib]) (ld/resolve-name node authority 'my.lib))))
        (testing "link before the load is :yin.link.dht/not-loaded"
          (is (= {:status :refused :reason :yin.link.dht/not-loaded :address lib :load nil}
                 (ld/link node lib :yin.semantic/code))))
        (let [[node cause] (step-until (ld/load-module node lib) exchange-ms
                                       "my.lib did not load" (settled? lib))
              [node cause'] (step-until (ld/load-module node store-module) exchange-ms
                                        "my.store did not load" (settled? store-module))]
          (is (nil? cause) cause)
          (is (nil? cause') cause')
          (testing "load-module fetched the closure from peers"
            (is (= :loaded (:status (ld/module-status node lib)))
                (pr-str (ld/module-status node lib)))
            (is (pos? (:fetched (ld/module-status node lib)))))
          (testing "link on all four formats, and run each image"
            (doseq [f [:yin.ast/code :yin.semantic/code :yin.debruijn.code
                       :yin.debruijn.register]]
              (let [res (ld/link node lib f)]
                (is (linker/ok? res) (str f " " (pr-str res)))
                (when (linker/ok? res)
                  (is (some? (get (vm/store (vm/run (loaded-vm f res))) 'f))
                      (str f ": the image defines the export"))))))
          (testing "and the export evaluates"
            (let [image (vm/run (loaded-vm :yin.ast/code (ld/link node lib :yin.ast/code)))
                  answer (vm/eval (tu/create-vm {:env {'f (get (vm/store image) 'f)}})
                                  call-f)]
              (is (= 4201 (vm/value answer)))))
          (testing "the store corpus links on semantic, stack and register"
            (doseq [f [:yin.semantic/code :yin.debruijn.code :yin.debruijn.register]]
              (is (linker/ok? (ld/link node store-module f)) (str f))))
          (testing "failure results of section 9, as data"
            (testing ":absent (name): the principal is not declared"
              (let [r (ld/resolve-name node (ld/authority {}) 'my.lib)]
                (is (= {:status :refused :reason :absent :name 'my.lib}
                       (dissoc r :diagnostics)))
                (is (= [[:undeclared-principal principal]]
                       (mapv (juxt :reason :principal) (:diagnostics r))))))
            (testing ":ambiguous-name: a second declared principal names another address"
              (let [other (sign/generate)
                    own (dht-test/publish-datoms!
                          node
                          (mapv (fn [{:keys [envelope proof]}]
                                  {:db/id datom/first-user-id
                                   :yin.module/envelope envelope
                                   :yin.module/proof proof})
                                [(publish/assertion other {:name 'my.lib
                                                           :manifest store-module
                                                           :seq 1})]))
                    [node' _] (dht/step (dht/load-index node own) (System/currentTimeMillis))
                    r (ld/resolve-name node' (ld/authority {:principals [public (:public other)]})
                                       'my.lib)]
                (is (= {:status :refused :reason :ambiguous-name :name 'my.lib}
                       (select-keys r [:status :reason :name])))
                (is (= #{lib store-module} (set (:addresses r))))
                (is (= #{principal (sign/principal (:public other))} (set (:asserters r))))))
            (testing ":absent (content): a manifest no peer holds misses, exhausted"
              (let [nowhere (jing/segment-key "a module no node holds")
                    [node cause] (step-until (ld/load-module node nowhere) exchange-ms
                                             "the miss was not reported" (settled? nowhere))]
                (is (nil? cause) cause)
                (is (= {:status :refused :reason :absent :address nowhere
                        :cause ::jing.dht/exhausted}
                       (ld/load-refusal (ld/module-status node nowhere))))))
            (testing ":descriptor-defect: a defective manifest fails :invalid with its code"
              (let [bad (jing/materialize! (dht/store node)
                                           (assoc (jing/get (dht/local node) lib nil)
                                                  :yin.module/schema 2))
                    [node _] (dht/step (ld/load-module node bad) (System/currentTimeMillis))
                    row (ld/load-refusal (ld/module-status node bad))]
                (is (= {:status :refused :reason :descriptor-defect :address bad
                        :code :manifest-defect}
                       (dissoc row :detail :text))
                    (pr-str row))))
            (testing ":yin.link.dht/dependency-binding: app pins my.lib"
              (let [app (:address (requiring-app! (dht/store node) lib))
                    [node _] (dht/step (ld/load-module node app) (System/currentTimeMillis))]
                (is (= :loaded (:status (ld/module-status node app))))
                (is (= [{:module app :name 'my.lib :pinned lib :binding :absent
                         :diagnostics [{:reason :undeclared-principal}]}]
                       (mapv #(update % :diagnostics (partial mapv (fn [d] (select-keys d [:reason]))))
                             (ld/dependency-bindings node (ld/authority {}) app))))
                (is (= [{:module app :name 'my.lib :pinned lib :binding :ok}]
                       (ld/dependency-bindings node authority app)))))
            (testing "the linker's own refusal: the store corpus on the walker"
              (is (= {:status :refused :reason :undeclared-free :name 'base}
                     (select-keys (ld/link node store-module :yin.ast/code)
                                  [:status :reason :name]))))
            (testing ":yin.link.dht/closure-incomplete: a blob lost after :loaded"
              (let [image (get-in (jing/get (dht/local node) lib nil)
                                  [:yin.module/derivations :yin.semantic/code])]
                (swap! hidden conj image)
                (is (= {:status :refused :reason :yin.link.dht/closure-incomplete
                        :address image}
                       (ld/link node lib :yin.semantic/code)))
                (reset! hidden #{})))
            (testing ":yin.link.dht/unaskable: a client that cannot submit"
              (let [elsewhere (jing/segment-key "another module no node holds")
                    [node _] (dht/step (-> node
                                           (update :client assoc :requests (undeliverable))
                                           (ld/load-module elsewhere))
                                       (System/currentTimeMillis))]
                (is (= {:status :refused :reason :yin.link.dht/unaskable
                        :address elsewhere :outcome :request-undeliverable}
                       (ld/load-refusal (ld/module-status node elsewhere))))))))
        (dht/close! node))
      (catch Throwable t
        (dht/close! node)
        (throw t)))))


(deftest a-module-published-by-name-is-required-by-name-across-processes
  (let [storing {::jing.dht/publish? true
                 ::jing.dht/max-inbound-bytes (* 64 1024 1024)}
        anchors [(start-anchor! storing) (start-anchor! storing)]
        dirs (vec (repeatedly 4 temp-dir))
        key-file (str (temp-dir) ".key")
        peer #(str "127.0.0.1:" %)
        procs (atom [])]
    (doseq [x anchors] (is (nil? (:failure x)) (:failure x)))
    (when-not (some :failure anchors)
      (try
        (main/keygen! key-file)
        (let [[x y] (map :port anchors)
              a (spawn! "publisher-a" ["--index-store" (str "dht:" (dirs 0))
                                       "--dht-peer" (peer x) "--dht-peer" (peer y)
                                       "--dht-publish" "--dht-key" key-file])
              _ (swap! procs conj a)
              pa (bound-port a)
              _ (is (some? pa) (transcript a))
              public (some->> (await-line a #"signed by principal ed25519:([0-9a-f]{64})"
                                          1000)
                              (re-find #"ed25519:([0-9a-f]{64})")
                              second)
              _ (testing "A states the principal it signs names with"
                  (is (some? public) (transcript a)))
              _ (doseq [line publisher-lines] (type! a line))
              published (await-line a #":module 'my\.store" exchange-ms)
              _ (testing "A publishes both modules by name"
                  (is (some? published) (transcript a))
                  (is (await-line a #":module 'my\.lib" 1000) (transcript a)))
              manifest (await-head-published a (dirs 0) exchange-ms)
              _ (testing "A's output reports the index naming them acknowledged"
                  (is (some? manifest) (transcript a)))
              reader (fn [label command dir principals]
                       (let [p (spawn! label command
                                       (into ["--index-store" (str "dht:" dir)
                                              "--dht-peer" (peer x) "--dht-peer" (peer pa)
                                              "--dht-manifest" (str ":" manifest)]
                                             (mapcat (fn [k] ["--dht-principal" k]))
                                             principals))]
                         (swap! procs conj p)
                         p))]
          (when (and public manifest)
            (is (.exists (io/file node-script))
                (str "the Node reader is required: " node-script
                     " is absent; build it with `bb build:yin-repl-node`"))
            (let [b (reader "reader-b-jvm" (jvm-command) (dirs 1) [public])
                  e (when (.exists (io/file node-script))
                      (reader "reader-b-node" ["node" node-script] (dirs 2) [public]))
                  c (reader "reader-c-undeclared" (jvm-command) (dirs 3) [])]
              (doseq [p (remove nil? [b e])]
                (testing (str (:label p) " hydrates A's index")
                  (is (await-line p (re-pattern (str "dht: hydrated :" manifest))
                                  startup-ms)
                      (transcript p)))
                (testing (str (:label p) " requires each module by name on all four"
                              " VMs: my.lib evaluates; the store corpus evaluates on"
                              " semantic, stack and register and the walker refuses it")
                  (let [failures (require-on-each-vm p)]
                    (is (empty? failures) (str failures "\n" (transcript p))))
                  (is (await-line p #";; require pending: my\.lib" 1000)
                      "the first require waited through pending")
                  (is (= 1 (count (filter #(re-find #"Module link refused" %) @(:lines p))))
                      (transcript p))))
              (testing "without --dht-principal, the require is :absent, undeclared"
                (is (await-line c (re-pattern (str "dht: hydrated :" manifest)) startup-ms)
                    (transcript c))
                (type! c "(require (quote my.lib))")
                (is (await-line c #"Module link refused: absent" exchange-ms) (transcript c))
                (type! c "(require (quote yin.link))")
                (type! c "(yin.link/names)")
                (is (await-line c (re-pattern (str ":reason :undeclared-principal|"
                                                   ":undeclared-principal"))
                                exchange-ms)
                    (transcript c))
                (is (await-line c (re-pattern (str "ed25519:" public)) 1000)
                    (transcript c)))
              (testing "plain Clojure takes the same path, and answers each failure as data"
                (plain-clojure-leg [x pa] manifest public)))))
        (finally
          (doseq [p @procs] (stop! p))
          (doseq [x anchors] ((:stop! x)))
          (io/delete-file key-file true)
          (run! delete-dir! dirs))))))
