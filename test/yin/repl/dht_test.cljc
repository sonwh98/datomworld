(ns yin.repl.dht-test
  "The REPL's DHT index store, DHT epic slice S5 (docs/design/
   dao.jing.dht.md section 10; docs/design/yin.repl.dao.space-index.md,
   \"DHT store\").  `dht:<dir>` is an explicit `--index-store` choice over
   the durable directory store; with no peers it is solo and opens no
   socket; publishing is its own flag, stated before anything is shared;
   every publication is reported acknowledged (sent to N peers) or not
   and why, while the round itself completes against `:local`; and a
   reader handed a manifest address hydrates the remote index before it
   admits evaluation, then queries it.

   The peers here are in-process: the shell's socket is a
   dao.stream.datagram host seam over the dao.jing.dht test mesh, and its
   peers are plain DHT states on the same mesh.  Time is the `now` each
   step is handed.  Real processes over real loopback sockets are
   yin.repl.dht-process-test."
  (:require [dao.test-slow :as slow] #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.dht :as dht]
            [dao.jing.dht.mesh :as mesh]
            [dao.jing.mem :as mem]
            [dao.space.dht :as space.dht]
            [dao.space.index :as space.index]
            [dao.stream :as stream]
            [dao.stream.datagram :as datagram]
            [yin.repl :as repl]
            [yin.repl.dht :as repl.dht]
            [yin.repl.driver :as driver]
            [yin.repl.main :as main]
            [yin.repl.store :as store]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.linker :as linker]
            [yin.vm.linker.closure-test :as ct]
            [yin.vm.linker.dht :as ld]
            [yin.vm.linker.head :as head]
            [yin.vm.linker.publish :as publish]
            [yin.vm.linker.publish-test :as publish-test]
            [yin.vm.linker.sign :as sign]))


;; =============================================================================
;; Host helpers
;; =============================================================================

(defn- temp-dir
  []
  (str "target/test-dht-store-" (random-uuid)))


(defn- cleanup-dir!
  [dir]
  #?(:cljd (try (.deleteSync (dart-io/Directory. dir) .recursive true)
                (catch Object _ nil))
     :clj (let [f (java.io.File. dir)]
            (when (.isDirectory f)
              (doseq [child (.listFiles f)]
                (.delete child))
              (.delete f)))
     :cljs (try (.rmSync (js/require "fs") dir #js {:recursive true :force
                                                    true})
                (catch :default _ nil))))


(defn- dir-names
  "The file names directly inside `dir`."
  [dir]
  #?(:cljd (set (map #(last (str/split (.-path %) #"/"))
                     (.listSync (dart-io/Directory. dir))))
     :clj (set (map #(.getName ^java.io.File %)
                    (.listFiles (java.io.File. ^String dir))))
     :cljs (set (js->clj (.readdirSync (js/require "fs") dir)))))


(defn- read-text!
  [path]
  #?(:cljd (let [f (dart-io/File. path)]
             (when (.existsSync f) (.readAsStringSync f)))
     :clj (let [f (java.io.File. ^String path)]
            (when (.exists f) (slurp f)))
     :cljs (let [fs (js/require "fs")]
             (when (.existsSync fs path)
               (.readFileSync fs path "utf8")))))


(defn- head-manifest
  "The manifest address `<dir>/HEAD` names, or nil."
  [dir]
  (some->> (read-text! (str dir "/HEAD"))
           (re-find #":manifest :(\S+?)\}")
           second
           keyword))


(defn- index-of
  [xs x]
  (first (keep-indexed (fn [i y] (when (= x y) i)) xs)))


(defn- refusal-of
  "The error `thunk` throws, or nil.  The error itself, not its message:
   see yin.repl.store-test's helper of the same name."
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object
                 :clj Exception
                 :cljs :default)
              e
         e)))


;; =============================================================================
;; A dao.stream.datagram host seam over the test mesh
;; =============================================================================

(defn- mesh-bind
  "The shared mesh seam (dao.jing.dht.mesh/seam) at `port`."
  [net port binds]
  (mesh/seam net port binds))


(defn- dht-spec
  [dir opts]
  (merge {:type :dht
          :dir dir
          :peers []
          :publish? false
          :bind-host "127.0.0.1"
          :bind-port 0
          :max-inbound-bytes repl.dht/default-max-inbound-bytes
          :manifest nil}
         opts))


(defn- peer-node
  "A plain DHT node on the mesh at `port`, publishing, as a map
   {:composition c :state s}."
  [net port]
  (let [c (mesh/join! net port {::dht/publish? true})]
    {:composition c :state (dht/state c)}))


(defn- step-peers
  [peers now]
  (into {}
        (map (fn [[port {:keys [composition state]}]]
               (mesh/tick! composition now)
               [port {:composition composition
                      :state (dht/step state 64)}]))
        peers))


(defn- run-until
  "Step every shell's DHT runner and every peer at readings `now`, `now` +
   10, ... until `done?` holds over the collected lines or `limit` readings
   pass.  Answers `[shells peers lines now]`."
  [shells peers now limit done?]
  (loop [shells shells
         peers peers
         lines []
         now now]
    (if (or (done? lines) (> now limit))
      [shells peers lines now]
      (let [stepped (map (fn [s] (repl.dht/step s now)) shells)]
        (recur (mapv first stepped)
               (step-peers peers now)
               (into lines (mapcat second) stepped)
               (+ now 10))))))


(defn- line-with
  [lines text]
  (some #(when (str/includes? % text) %) lines))


(defn- close!
  [shell]
  (store/close! (:index-store shell)))


;; =============================================================================
;; Bullet 1: the store is picked explicitly; mem stays the default
;; =============================================================================

(deftest mem-stays-the-default-and-dht-is-an-explicit-choice
  (is (= :mem (:index-store-spec (main/parse-args []))))
  (is (= (dht-spec "idx" {})
         (:index-store-spec (main/parse-args ["--index-store" "dht:idx"])))
      "dht:<dir> is the DHT store over that directory, solo by default")
  (is (= {:type :dht :dir "idx"} (store/parse-arg "dht:idx")))
  (testing "a bare dht: names no directory and is refused"
    (is (str/includes? (ex-message (refusal-of #(store/parse-arg "dht:")))
                       "needs a directory")))
  (testing "the unknown-scheme refusal names every supported form"
    (is (str/includes? (ex-message (refusal-of #(store/parse-arg "bogus")))
                       "dht:<dir>"))))


(deftest peers-publication-and-binding-are-separate-explicit-flags
  (let [spec #(:index-store-spec (main/parse-args (into ["--index-store"
                                                         "dht:d"] %)))]
    (testing "peers declare nothing: publication stays off"
      (is (= {:peers [{:host "127.0.0.1" :port 7001}
                      {:host "::1" :port 7002}]
              :publish? false}
             (select-keys (spec ["--dht-peer" "127.0.0.1:7001"
                                 "--dht-peer" "[::1]:7002"])
                          [:peers :publish?]))))
    (testing "--dht-publish is its own declaration"
      (is (true? (:publish? (spec ["--dht-peer" "127.0.0.1:7001"
                                   "--dht-publish"]))))
      (is (true? (:publish? (spec ["--dht-publish"])))
          "publishing in solo is legal: nothing leaves the process"))
    (testing "the bind host defaults to loopback; any other is explicit"
      (is (= "127.0.0.1" (:bind-host (spec ["--dht-peer" "127.0.0.1:7001"]))))
      (is (= "0.0.0.0" (:bind-host (spec ["--dht-peer" "127.0.0.1:7001"
                                          "--dht-bind" "0.0.0.0"])))))
    (testing "the inbound storage bound has a CLI default and a flag"
      (is (= repl.dht/default-max-inbound-bytes
             (:max-inbound-bytes (spec []))))
      (is (= 1024 (:max-inbound-bytes (spec ["--dht-max-inbound-bytes"
                                             "1024"]))))))
  (testing "every malformed or meaningless combination is refused, not ignored"
    (doseq [[args text] [[["--dht-peer" "127.0.0.1:1"] "dht:<dir>"]
                         [["--dht-publish"] "dht:<dir>"]
                         [["--index-store" "file:d" "--dht-peer" "127.0.0.1:1"]
                          "dht:<dir>"]
                         [["--index-store" "dht:d" "--dht-peer" "localhost:1"]
                          "IP literal"]
                         [["--index-store" "dht:d" "--dht-peer" "127.0.0.1"]
                          "host:port"]
                         [["--index-store" "dht:d" "--dht-peer" "127.0.0.1:0"]
                          "host:port"]
                         [["--index-store" "dht:d" "--dht-port" "7000"]
                          "solo"]
                         [["--index-store" "dht:d" "--dht-bind" "0.0.0.0"]
                          "solo"]
                         [["--index-store" "dht:d" "--dht-bind" "localhost"
                           "--dht-peer" "127.0.0.1:1"]
                          "IP literal"]
                         [["--index-store" "dht:d" "--dht-manifest"
                           "segment/blake3-00"]
                          "solo"]
                         [["--index-store" "dht:d" "--dht-peer" "127.0.0.1:1"
                           "--dht-manifest" "not-an-address"]
                          "manifest"]
                         [["--index-store" "dht:d" "--dht-max-inbound-bytes"
                           "-1"]
                          "max-inbound-bytes"]]]
      (let [e (refusal-of #(main/parse-args args))]
        (is (some? e) (pr-str args))
        (is (str/includes? (str (ex-message e)) text) (pr-str args))))))


(deftest startup-refuses-a-bad-dht-flag-before-composing
  (let [started (main/startup ["--dht-publish"])]
    (is (string? (:refusal started)))
    (is (nil? (:state started)))))


;; =============================================================================
;; Bullet 1: solo opens no socket; the secret is minted, in memory only
;; =============================================================================

(deftest a-dht-store-with-no-peers-is-solo-and-opens-no-socket
  (let [dir (temp-dir)
        net (mesh/mesh)
        binds (atom 0)]
    (try
      (let [shell (repl/create-state
                    {:index-store-spec (dht-spec dir {:bind! (mesh-bind net 9
                                                                        binds)
                                                      :publish? true})})
            runner (:dht shell)]
        (is (zero? @binds) "no socket was bound")
        (is (nil? (get-in runner [:composition :traffic])))
        (is (nil? (get-in runner [:composition :datagrams])))
        (is (nil? (get-in runner [:composition ::dht/secret]))
            "solo composes no secret: there is nothing to cookie")
        (is (store/durable? (:index-store shell))
            "the directory is the durable store: lock, HEAD, recovery")
        (let [[shell text] (repl/eval-input shell "(+ 1 2)")
              [shell lines] (repl.dht/step shell 0)]
          (is (= "3" text))
          (is (line-with lines "NOT acknowledged"))
          (is (line-with lines "solo"))
          (is (= (get-in shell [:indexer :manifest-address])
                 (head-manifest dir))
              "the publication is durable locally, HEAD moved")
          (close! shell)))
      (finally
        (cleanup-dir! dir)))))


(deftest the-root-secret-is-minted-per-open-in-memory-and-never-persisted
  (let [dirs [(temp-dir) (temp-dir)]
        net (mesh/mesh)
        binds (atom 0)]
    (try
      (let [shells (mapv (fn [dir port]
                           (repl/create-state
                             {:index-store-spec
                              (dht-spec dir {:bind! (mesh-bind net port binds)
                                             :peers [{:host "127.0.0.1" :port
                                                      1}]
                                             :publish? true})}))
                         dirs [11 12])
            secrets (mapv #(get-in % [:dht :composition ::dht/secret]) shells)
            hexes (mapv dht/bytes->hex secrets)]
        (is (= 2 @binds) "a peer configured composes the socket")
        (is (every? #(<= 64 (count %)) hexes) "at least 32 bytes each")
        (is (apply not= hexes) "one per open, from the CSPRNG")
        (is (not= (apply str (repeat 64 "0")) (first hexes)))
        (let [[shell _] (repl/eval-input (first shells) "(def s 1)")
              [shell _] (repl.dht/step shell 0)]
          (is (= #{"content.jing" "HEAD" "lock"}
                 ;; Node's lock is a claim entry, lock.<pid>.<nonce>
                 (set (map #(if (str/starts-with? % "lock") "lock" %)
                           (dir-names (first dirs)))))
              "the directory holds the store and nothing else")
          (is (not (str/includes? (str (read-text! (str (first dirs) "/HEAD")))
                                  (first hexes))))
          (is (not (str/includes? (pr-str (repl/repl-state shell))
                                  (first hexes)))
              "the shell's reported state does not carry it")
          (close! shell))
        (close! (second shells)))
      (finally
        (run! cleanup-dir! dirs)))))


;; =============================================================================
;; Bullet 2: publication is its own flag, and the REPL states what it shares
;; =============================================================================

(deftest the-banner-states-what-will-be-shared-before-anything-is
  (let [banner #(str/join "\n" (main/banner (main/parse-args
                                              (into ["--index-store" "dht:idx"]
                                                    %))))]
    (testing "publishing states the whole store is public"
      (let [text (banner ["--dht-peer" "127.0.0.1:7001" "--dht-publish"])]
        (is (str/includes? text "will be shared"))
        (is (str/includes? text "idx/content.jing"))
        (is (str/includes? text "127.0.0.1:7001"))))
    (testing "without the flag the REPL says it shares nothing"
      (let [text (banner ["--dht-peer" "127.0.0.1:7001"])]
        (is (str/includes? text "fetch-only"))
        (is (not (str/includes? text "will be shared")))))
    (testing "solo says no socket is opened"
      (is (str/includes? (banner []) "solo")))
    (testing "the memory store prints no DHT banner"
      (is (empty? (main/banner (main/parse-args [])))))))


;; =============================================================================
;; Bullet 3: per-publication acknowledgement, and no round waits
;; =============================================================================

(defn- publisher
  "A shell over a dht store at mesh `port`, with `peer-ports` as its
   bootstrap contacts."
  [net dir port peer-ports opts]
  (repl/create-state
    {:index-store-spec
     (dht-spec dir (merge {:bind! (mesh-bind net port (atom 0))
                           :peers (mapv (fn [p] {:host "127.0.0.1" :port p})
                                        peer-ports)}
                          opts))}))


(deftest a-publication-sent-to-two-peers-is-reported-acknowledged
  (let [dir (temp-dir)
        net (mesh/mesh)
        peers {21 (peer-node net 21) 22 (peer-node net 22)}]
    (try
      (let [shell (publisher net dir 20 [21 22] {:publish? true})
            [shell text] (repl/eval-input shell "(def answer 4242)")
            manifest (get-in shell [:indexer :manifest-address])]
        (testing "the round completed against :local, before any DHT step"
          (is (= "4242" text))
          (is (true? (:published? (:index (repl/repl-state shell)))))
          (is (some? (jing/get (:index-store shell) manifest nil))
              "the manifest reads back through the store at once"))
        (let [[shells _ lines] (run-until [shell] peers 0 4000
                                          #(line-with % "acknowledged"))]
          (is (line-with lines (str "dht: published " manifest)))
          (is (line-with lines "acknowledged: sent to 2 peers"))
          (is (not (line-with lines "NOT acknowledged")))
          (close! (first shells))))
      (finally
        (cleanup-dir! dir)))))


(defn- refusing-bind
  "The mesh seam at `port`, whose socket refuses every `:store` datagram
   for an address `(@refuse? a)` holds (dao.space.dht-test's seam).  A
   store too large for one datagram travels as `:chunk` frames, which
   name no address: they are refused when `(@refuse? nil)` holds."
  [net port refuse?]
  (let [bind! (mesh/seam net port)]
    (fn [opts]
      (let [seam (bind! opts)]
        (assoc seam
               :send! (fn [host to-port bs]
                        (let [m (dht/decode-message bs)
                              refused? (case (:op m)
                                         :store (@refuse? (:address m))
                                         :chunk (and (= :request (:dir m))
                                                     (@refuse? nil))
                                         false)]
                          (if refused?
                            {:dao.stream/outcome :dao.stream/transport-error}
                            ((:send! seam) host to-port bs)))))))))


(defn- rows-refuser
  "A refusal of every store but `@manifest`'s."
  [manifest]
  (fn [a] (not= a @manifest)))


(deftest
  a-partial-publication-is-reported-and-repaired-through-the-host-module
  (let
    [dir (temp-dir)
     net (mesh/mesh)
     peers {121 (peer-node net 121) 122 (peer-node net 122)}
     manifest (atom nil)
     refuse (atom (rows-refuser manifest))]
    (try
      (let
        [shell (repl/create-state
                 {:index-store-spec
                  (dht-spec dir {:bind! (refusing-bind net 120 refuse)
                                 :peers [{:host "127.0.0.1" :port 121}
                                         {:host "127.0.0.1" :port 122}]
                                 :publish? true})})
         [shell _] (repl/eval-input shell "(def partly 7)")
         m (get-in shell [:indexer :manifest-address])
         _ (reset! manifest m)
         [[shell] peers lines now] (run-until
                                     [shell] peers 0 20000
                                     #(line-with
                                        % (str
                                            "published " m)))
         first-line (line-with lines (str "published " m))]
        (testing (str
                   "the first report names the result, the failed"
                   " count and the retry")
          (is (str/includes? first-line "PARTIAL") first-line)
          (is (re-find #"\d+ of \d+ blobs not sent" first-line) first-line)
          (is (str/includes? first-line "too few peers") first-line)
          (is (str/includes? first-line "retrying while the node is open")
              first-line)
          (is (= m (head-manifest dir)) "HEAD moved"))
        (reset! refuse (constantly false))
        (let
          [[shell required] (repl/eval-input
                              shell
                              "(require (quote dao.space.dht))")
           [shell retried] (repl/eval-input
                             shell (str
                                     "(dao.space.dht/retry " m ")"))
           [[shell] _ lines] (run-until [shell] peers now (+ now 20000)
                                        #(line-with % (str "republished "
                                                           m)))
           line (line-with lines (str "republished " m))]
          (is (= "'dao.space.dht" required))
          (is (= ":retrying" retried) "retry brings the repair forward")
          (is (str/includes? (str line) "acknowledged: sent to 2 peers")
              (pr-str lines))
          (testing
            "retry and cancel are refused for what is not live, as data"
            (let
              [[shell again] (repl/eval-input
                               shell (str
                                       "(dao.space.dht/retry " m ")"))
               [shell cancelled] (repl/eval-input
                                   shell (str
                                           "(dao.space.dht/cancel " m ")"))]
              (is (str/includes? again "not-repairing") again)
              (is (str/includes? cancelled "not-live") cancelled)
              (close! shell)))))
      (finally
        (cleanup-dir! dir)))))


(deftest
  a-refused-manifest-is-reported-unacknowledged-and-cancel-ends-it
  (let
    [dir (temp-dir)
     net (mesh/mesh)
     peers {124 (peer-node net 124) 125 (peer-node net 125)}
     refuse (atom (constantly false))]
    (try
      (let
        [shell (repl/create-state
                 {:index-store-spec
                  (dht-spec dir {:bind! (refusing-bind net 123 refuse)
                                 :peers [{:host "127.0.0.1" :port 124}
                                         {:host "127.0.0.1" :port 125}]
                                 :publish? true})})
         [shell _] (repl/eval-input shell "(def unsent 8)")
         m (get-in shell [:indexer :manifest-address])
         _ (reset! refuse #{m})
         [[shell] peers lines now] (run-until
                                     [shell] peers 0 20000
                                     #(line-with
                                        % (str
                                            "published " m)))
         first-line (line-with lines (str "published " m))
         _ (is (= m (head-manifest dir)) "HEAD moved")
         [shell _] (repl/eval-input shell "(require (quote dao.space.dht))")
         [shell cancelled] (repl/eval-input
                             shell (str
                                     "(dao.space.dht/cancel " m ")"))
         [[shell] _ lines] (run-until [shell] peers now (+ now 1000)
                                      #(line-with % (str "republished " m)))]
        (is (str/includes? first-line "NOT acknowledged") first-line)
        (is (re-find #"1 of \d+ blobs not sent" first-line) first-line)
        (is (str/includes? first-line "retrying") first-line)
        (is (= ":cancelled" cancelled))
        (is (str/includes? (str (line-with lines (str "republished " m)))
                           "not retrying (cancelled)")
            (pr-str lines))
        (close! shell))
      (finally
        (cleanup-dir! dir)))))


(deftest a-publication-with-publishing-off-is-reported-and-sends-nothing
  (let [dir (temp-dir)
        net (mesh/mesh)
        peers {31 (peer-node net 31) 32 (peer-node net 32)}]
    (try
      (let [shell (publisher net dir 30 [31 32] {})
            [shell _] (repl/eval-input shell "(def quiet 1)")
            [shells _ lines] (run-until [shell] peers 0 400
                                        #(line-with % "acknowledged"))]
        (is (line-with lines "NOT acknowledged"))
        (is (line-with lines "publication is off"))
        (is (empty? (filter #(= :store (get-in % [:message :op]))
                            (mesh/sent-from net 30)))
            "no store was sent")
        (close! (first shells)))
      (finally
        (cleanup-dir! dir)))))


(deftest a-publication-short-of-peers-is-reported-with-why-at-the-deadline
  (let [dir (temp-dir)
        net (mesh/mesh)
        peers {41 (peer-node net 41)}]
    (try
      (let [shell (publisher net dir 40 [41] {:publish? true})
            [shell _] (repl/eval-input shell "(def lonely 1)")
            [shells _ lines now] (run-until [shell] peers 0 20000
                                            #(line-with % "acknowledged"))]
        (is (line-with lines "NOT acknowledged"))
        (is (line-with lines "too few peers"))
        (is (line-with lines "sent to 1 of 2"))
        (is (<= 5000 now) "reported at the ack deadline, not before")
        (close! (first shells)))
      (finally
        (cleanup-dir! dir)))))


;; =============================================================================
;; Bullet 5: a reader given a manifest address hydrates and queries
;; =============================================================================

(def ^:private require-line "(require (quote dao.space.query))")


(def ^:private query-line
  "The session token of every run that defined the literal 4242: the
   history view, as the restart tests ask it (yin.repl.store-test)."
  (str "(dao.space.query/q (quote [:find ?s :where [?e :yin/value 4242 ?t ?m]"
       " [?m :yin.repl/session ?s ?t1 ?m1]]) {:view :history})"))


(deftest a-reader-given-a-manifest-hydrates-then-queries-the-remote-index
  (let [[pdir rdir] [(temp-dir) (temp-dir)]
        net (mesh/mesh)
        peers {51 (peer-node net 51)}]
    (try
      (let [pub (publisher net pdir 50 [51] {:publish? true})
            [pub _] (repl/eval-input pub "(def answer 4242)")
            manifest (get-in pub [:indexer :manifest-address])
            reader (publisher net rdir 52 [50] {:manifest manifest})]
        (is (not (repl.dht/admitting? reader))
            "evaluation waits for the hydration, as it waits for recovery")
        (let [[[pub reader] _ lines]
              (run-until [pub reader] peers 0 20000
                         #(line-with % "hydrated"))]
          (is (line-with lines (str "hydrated " manifest)))
          (is (repl.dht/admitting? reader))
          (is (nil? (repl.dht/refusal reader)))
          (is (= manifest (get-in reader [:indexer :manifest-address])))
          (is (= manifest (head-manifest rdir))
              "the hydrated index is the reader directory's durable HEAD")
          (let [[reader _] (repl/eval-input reader require-line)
                [reader answer] (repl/eval-input reader query-line)]
            (is (str/includes? (str answer) (:shell-token pub))
                (str "q answers the publisher's fact, under its token: "
                     answer))
            (is (not (str/includes? (str answer) (:shell-token reader)))
                "the reader itself never evaluated it")
            (close! reader))
          (close! pub)))
      (finally
        (run! cleanup-dir! [pdir rdir])))))


(defn- recorded-dht-store
  "`repl.dht/open` of `spec`, every HEAD write recorded in `heads` after
   it succeeds: a round's, through the handle's `:head-fn`, and the
   hydration's, through the node's local store (yin.repl.dht/hydrated)."
  [spec heads]
  (let [record (fn [head-fn]
                 (fn [m] (head-fn m) (swap! heads conj m)))]
    (-> (repl.dht/open spec)
        (update :head-fn record)
        (update-in [:dht :composition :local :head-fn] record))))


(deftest hydration-is-a-directory-s-first-head-write-at-its-own-sequence
  ;; docs/design/yin.vm.linker.dht.head.md 5.2: the HEAD writes of one
  ;; directory carry strictly increasing sequences; hydration is only ever
  ;; the first, installing the index at its own greatest t.
  (let [[pdir rdir] [(temp-dir) (temp-dir)]
        net (mesh/mesh)
        peers {71 (peer-node net 71)}
        heads (atom [])
        spec (fn [port opts]
               (dht-spec rdir (merge {:bind! (mesh-bind net port (atom 0))
                                      :peers [{:host "127.0.0.1" :port 70}]}
                                     opts)))
        seq-at (fn [shell m]
                 (head/seq-of (space.index/read-datoms (:index-store shell) m)))]
    (try
      (let [pub (publisher net pdir 70 [71] {:publish? true})
            [pub _] (repl/eval-input pub "(def answer 4242)")
            manifest (get-in pub [:indexer :manifest-address])
            published (seq-at pub manifest)
            reader (repl/create-state
                     {:index-store
                      (recorded-dht-store
                        (spec 72 {:manifest manifest})
                        heads)})
            [[pub reader]] (run-until [pub reader] peers 0 20000
                                      #(line-with % "hydrated"))]
        (close! pub)
        (testing "no HEAD: hydration is the one HEAD write, at its own sequence"
          (is (repl.dht/admitting? reader))
          (is (= [manifest] @heads))
          (is (= published
                 (head/seq-of (get-in reader [:index-recovery :datoms])))))
        (close! reader)
        (testing "the same manifest: no load, no HEAD write, admitted at once"
          (let [reader (repl/create-state
                         {:index-store (recorded-dht-store
                                         (spec 73 {:manifest manifest})
                                         heads)})]
            (is (repl.dht/admitting? reader))
            (is (nil? (space.dht/load-status (:dht reader) manifest)))
            (is (= [manifest] @heads))
            (testing "one round writes a second HEAD, one sequence above"
              (let [[reader _] (repl/eval-input reader "(def later 1)")
                    next-manifest (get-in reader
                                          [:indexer :manifest-address])
                    key (sign/generate)
                    p (sign/principal (:public key))]
                (is (= [manifest next-manifest] @heads))
                (is (= (inc published) (seq-at reader next-manifest)))
                (is (= :duplicate
                       (head/judge p published manifest
                                   (head/trace key manifest published))))
                (is (= :candidate
                       (head/judge p published manifest
                                   (head/trace key next-manifest
                                               (inc published)))))))
            (close! reader)))
        (testing "a different manifest: refused, HEAD unchanged, lock released"
          (let [held (head-manifest rdir)
                other (jing/segment-key "another index")
                e (refusal-of #(recorded-dht-store
                                 (spec 74 {:manifest other})
                                 heads))]
            (is (some? e))
            (is (str/includes? (ex-message e) (str held)))
            (is (= held (head-manifest rdir)))
            (is (= 2 (count @heads)))
            (store/close! (repl.dht/open (spec 75 {}))))))
      (finally
        (run! cleanup-dir! [pdir rdir])))))


(deftest a-manifest-no-peer-holds-refuses-the-reader
  (let [dir (temp-dir)
        net (mesh/mesh)
        peers {61 (peer-node net 61)}
        absent (jing/segment-key "no peer holds this manifest")]
    (try
      (let [reader (publisher net dir 60 [61] {:manifest absent})
            [[reader] _ lines] (run-until [reader] peers 0 30000
                                          #(line-with % "refused"))]
        (is (some? (repl.dht/refusal reader)))
        (is (str/includes? (repl.dht/refusal reader) (str absent)))
        (is (not (repl.dht/admitting? reader))
            "a refused hydration never admits evaluation over an empty index")
        (is (line-with lines (str absent)))
        (is (str/includes? (repl.dht/refusal reader) "no peer produced")
            "the reason, data, is rendered as text by the REPL")
        (is (str/includes? (repl.dht/refusal reader) "exhausted"))
        (close! reader))
      (finally
        (cleanup-dir! dir)))))


(deftest the-step-owner-admits-no-line-while-hydrating
  (let [[pdir rdir] [(temp-dir) (temp-dir)]
        net (mesh/mesh)
        peers {71 (peer-node net 71)}]
    (try
      (let [pub (publisher net pdir 70 [71] {:publish? true})
            [pub _] (repl/eval-input pub "(def answer 4242)")
            manifest (get-in pub [:indexer :manifest-address])
            state (main/boot {:index-store-spec
                              (dht-spec rdir {:bind! (mesh-bind net 72 (atom 0))
                                              :peers [{:host "127.0.0.1" :port
                                                       70}]
                                              :manifest manifest})})
            _ (driver/submit-line! (:input state) "(+ 1 2)")
            [state _ lines] (main/step-all state nil 0)]
        (is (not (some #{"3"} lines)) "the line waits in the input medium")
        (is (main/moved? state nil lines)
            "a hydrating composition keeps the base cadence")
        (loop [state state
               pub pub
               peers peers
               now 10
               seen []]
          (let [[state _ lines] (main/step-all state nil now)
                [pub _] (repl.dht/step pub now)
                peers (step-peers peers now)
                seen (into seen lines)]
            (if (or (some #{"3"} seen) (> now 20000))
              (do (is (some #{"3"} seen) "evaluated once hydrated")
                  (is (< (index-of seen (line-with seen "hydrated"))
                         (index-of seen "3"))
                      "the hydration is reported before the first result")
                  (main/close-index-store! state)
                  (close! pub))
              (recur state pub peers (+ now 10) seen)))))
      (finally
        (run! cleanup-dir! [pdir rdir])))))


;; =============================================================================
;; A socket that cannot bind refuses the shell; it never runs solo instead
;; =============================================================================

(defn- failing-bind
  [{:keys [identity deposit]}]
  (stream/append! deposit (datagram/bind-failed-event identity
                                                      "address in use"))
  {:send! (fn [_ _ _] {:dao.stream/outcome :dao.stream/transport-error})
   :close! (fn [] nil)})


(deftest a-socket-that-cannot-bind-refuses-and-stops-the-shell
  (let [dir (temp-dir)]
    (try
      (let [state (main/boot {:index-store-spec
                              (dht-spec dir {:bind! failing-bind
                                             :peers [{:host "127.0.0.1" :port
                                                      1}]})})
            _ (driver/submit-line! (:input state) "(+ 1 2)")
            [state _ lines] (main/step-all state nil 0)]
        (is (line-with lines "could not bind"))
        (is (line-with lines "address in use"))
        (is (not (some #{"3"} lines))
            "nothing is evaluated over a node that never bound")
        (is (not (repl.dht/admitting? (:repl state))))
        (is (false? (:running? state)) "the shell stops")
        (is (= 1 (main/exit-status state)) "and the host exits failing")
        (main/close-index-store! state))
      (finally
        (cleanup-dir! dir)))))


;; =============================================================================
;; The same path from the prompt: the dao.space.dht host module
;; =============================================================================

(def ^:private remote-query
  '[:find ?v :where [?e :yin/value ?v]])


(deftest the-repl-queries-a-remote-index-through-the-same-plain-path
  (let [[pdir rdir] [(temp-dir) (temp-dir)]
        net (mesh/mesh)]
    (try
      (let [pub (publisher net pdir 110 [111] {:publish? true})
            [pub _] (repl/eval-input pub "(def answer 4242)")
            manifest (get-in pub [:indexer :manifest-address])
            plain (space.dht/load-index
                    (space.dht/join {:local (mem/create-content-mem)
                                     :peers [{:host "127.0.0.1" :port 110}]
                                     :bind! (mesh/seam net 111)})
                    manifest)
            reader (publisher net rdir 112 [110] {})
            [reader required] (repl/eval-input
                                reader "(require (quote dao.space.dht))")
            [reader early] (repl/eval-input
                             reader (str "(dao.space.dht/q " manifest
                                         " (quote " (pr-str remote-query) "))"))
            [reader asked] (repl/eval-input
                             reader (str "(dao.space.dht/load-index " manifest
                                         ")"))]
        (is (= "'dao.space.dht" required))
        (is (str/includes? early "is not loaded")
            "q refuses an index that is not loaded, rather than wait")
        (is (= ":loading" asked) "load-index answers at once")
        (loop [pub pub
               plain plain
               reader reader
               now 0
               lines []
               events []]
          (if (or (> now 20000)
                  (and (line-with lines (str "dht: loaded " manifest))
                       (some #(= :loaded (::space.dht/event %)) events)))
            (let [expected (space.dht/q plain manifest remote-query)
                  [reader status] (repl/eval-input
                                    reader (str "(dao.space.dht/load-status "
                                                manifest ")"))
                  [reader answer] (repl/eval-input
                                    reader (str "(dao.space.dht/q " manifest
                                                " (quote " (pr-str remote-query)
                                                "))"))]
              (is (line-with lines (str "dht: loaded " manifest)) (pr-str
                                                                    lines))
              (is (str/includes? status ":status :loaded"))
              (is (str/includes? status ":kind :dao.space.dht/index"))
              (is (re-find #":datoms \d+" status) status)
              (is (not (str/includes? status ":value"))
                  "the host answers the status, not the datoms")
              (is (contains? (set expected) [4242])
                  "the plain path answers the publisher's fact")
              (is (= (pr-str expected) answer)
                  "the host function answers exactly what the plain path does")
              (space.dht/close! plain)
              (close! reader)
              (close! pub))
            (let [[pub _] (repl.dht/step pub now)
                  [plain more] (space.dht/step plain now)
                  [reader more-lines] (repl.dht/step reader now)]
              (recur pub plain reader (+ now 10)
                     (into lines more-lines) (into events more))))))
      (finally
        (run! cleanup-dir! [pdir rdir])))))


;; =============================================================================
;; The REPL's dht:<dir> and a plain join share one directory lock
;; =============================================================================

(deftest a-plain-join-and-the-repl-store-exclude-each-other
  (let [dir (temp-dir)]
    (try
      (let [shell (repl/create-state {:index-store-spec (dht-spec dir {})})
            e (refusal-of #(space.dht/join {:dir dir}))]
        (is (some? e) "the plain join cannot open a directory the REPL holds")
        (is (str/includes? (str (ex-message e)) dir))
        (close! shell))
      (let [node (space.dht/join {:dir dir})
            e (refusal-of #(repl/create-state
                             {:index-store-spec (dht-spec dir {})}))]
        (is (some? e) "nor the REPL one a plain node holds")
        (is (str/includes? (str (ex-message e)) dir))
        (space.dht/close! node))
      (finally
        (cleanup-dir! dir)))))


;; =============================================================================
;; L3: the REPL's DHT link source and the relevant re-check
;; (docs/design/yin.vm.linker.dht.md 4.1, 7.4, 8.2, 8.4, 9 and 12, slice L3)
;; =============================================================================

(def ^:private closed-ast
  "`(yin/def f (fn [] (+ 40 2)))`: the closed corpus."
  (ct/def! 'f {:type :lambda
               :params []
               :body {:type :application
                      :operator {:type :variable :name '+}
                      :operands [(ct/lit 40) (ct/lit 2)]}}))


(defn- publish-mod!
  "Publish the closed corpus as the module `mod` into `store`; answers its
   manifest address."
  [store]
  (:address (publish/publish-module! store {:name 'mod :ast closed-ast
                                            :exports #{'f} :requires {}
                                            :primitives ct/plus})))


(def ^:private nowhere
  "A manifest address no node holds."
  (jing/segment-key "a module no node holds"))


(defn- holder-world
  "A plain `dao.space.dht` node at mesh port 1 holding `mod`, and plain
   publishing peers at 2 and 3, stepped until the holder's publication is
   reported.  Answers `{:holder node :peers peers :now t :manifest m}`."
  [net]
  (let [node (space.dht/join {:local (mem/create-content-mem)
                              :peers [{:host "127.0.0.1" :port 2}
                                      {:host "127.0.0.1" :port 3}]
                              :publish? true
                              :bind! (mesh/seam net 1)})
        m (publish-mod! (space.dht/local node))
        _ (space.dht/announce! node m)]
    (loop [node node
           peers {2 (peer-node net 2) 3 (peer-node net 3)}
           now 0]
      (let [[node events] (space.dht/step node now)
            peers (step-peers peers now)]
        (if (or (some #(= :published (::space.dht/event %)) events) (> now
                                                                       20000))
          {:holder node :peers peers :now (+ now 10) :manifest m}
          (recur node peers (+ now 10)))))))


(defn- reader-state
  "The step owner's state for a shell over a dht store at mesh `port`
   with bootstrap contacts `peer-ports` (none: solo), its name
   environment `names` -- direct addresses, as in slice L3."
  [net dir port peer-ports names]
  (-> (main/boot {:index-store-spec
                  (dht-spec dir {:bind! (mesh-bind net port (atom 0))
                                 :peers (mapv (fn [p]
                                                {:host "127.0.0.1" :port
                                                 p})
                                              peer-ports)})})
      (assoc-in [:repl :link-source :name-env] names)))


(defn- type!
  "Evaluate `line` at the shell's prompt: `[state text]`."
  [state line]
  (let [[repl text] (repl/eval-input (:repl state) line)]
    [(assoc state :repl repl) text]))


(defn- tick
  "One reading for the whole world: the shell's step owner
   (`main/step-all`), then the holder and the plain peers.  The shell's
   printed lines accumulate under `:lines`."
  [{:keys [state holder peers now] :as w}]
  (let [[state _ lines] (main/step-all state nil now)
        holder (when holder (first (space.dht/step holder now)))]
    (-> w
        (assoc :state state :holder holder :peers (step-peers peers now)
               :now (+ now 10))
        (update :lines (fnil into []) lines))))


(defn- run-ticks
  "Tick `w` until `(done? w)` or the reading passes `limit`, calling
   `(check w)` after every tick."
  ([w limit done?] (run-ticks w limit done? (fn [_] nil)))
  ([w limit done? check]
   (loop [w w]
     (if (or (done? w) (> (:now w) limit))
       w
       (let [w (tick w)]
         (check w)
         (recur w))))))


(defn- node-of
  [state]
  (get-in state [:repl :dht]))


(defn- parked
  [state]
  (get-in state [:repl :pending-run]))


(defn- responses
  "Every link response on the shell's link pair, oldest first, each
   without its id."
  [state]
  (mapv #(dissoc % :yin.link/id)
        (mesh/values (get-in state [:repl :link-pair :responses]))))


(defn- requests
  "Every link request on the shell's link pair, oldest first."
  [state]
  (mesh/values (get-in state [:repl :link-pair :requests])))


(defn- close-world!
  [w]
  (when-let [h (:holder w)] (space.dht/close! h))
  (when-let [s (:state w)] (main/close-index-store! s))
  nil)


(deftest
  a-require-a-peer-holds-parks-and-completes-on-a-later-tick-with-no-typed-line
  (let [dir (temp-dir)
        net (mesh/mesh)
        w (holder-world net)
        m (:manifest w)]
    (try
      (let [state (reader-state net dir 4 [1 2 3] {'mod m 'gone nowhere})
            [state text] (type! state "(require (quote mod))")]
        (testing "the require parks on the closure load it started"
          (is (str/includes? text "pending"))
          (is (= [m] (mapv :manifest (:links (parked state)))))
          (is (= :loading (:status (ld/module-status (node-of state) m)))))
        (let [w (run-ticks (assoc w :state state) 60000
                           #(nil? (parked (:state %)))
                           (fn [w]
                             (when-let [p (parked (:state w))]
                               (is (= 0 (:checks p))
                                   "no re-check ran before the load ended"))
                             nil))
              repl (:repl (:state w))]
          (testing "a later tick completed it, with no line typed"
            (is (nil? (:pending-run repl)))
            (is (= 'mod (:last-value repl)))
            (is (line-with (:lines w) (str "dht: loaded " m)) (pr-str (:lines
                                                                        w))))
          (testing "the export then answers"
            (is (= "42" (second (type! (:state w) "(mod/f)")))))
          (testing (str
                     "a repeated or late event for the answered lin"
                     "k changes nothing")
            (let [late {::space.dht/event :loaded :manifest m
                        :kind ld/module-kind :fetched 1}
                  [repl' text'] (repl/recheck-on-load-events repl [late late])
                  [waiting _] (repl/eval-input repl "(require (quote gone))")
                  [waiting' text''] (repl/recheck-on-load-events waiting
                                                                 [late])]
              (is (identical? repl repl'))
              (is (nil? text'))
              (testing "nor while another require waits on its own load"
                (is (= [nowhere] (mapv :manifest (:links (:pending-run
                                                           waiting)))))
                (is (identical? waiting waiting'))
                (is (nil? text'')))))
          (close-world! w)))
      (finally
        (cleanup-dir! dir)))))


(deftest an-unrelated-node-event-causes-no-re-check
  (let [dir (temp-dir)
        net (mesh/mesh)
        _silent (mesh/join! net 9)]
    (try
      (let [state (reader-state net dir 4 [9] {'mod nowhere})
            [state _] (type! state "(def z 1)")
            index (get-in state [:repl :indexer :manifest-address])
            other (publish-mod! (space.dht/local (node-of state)))
            state (update-in state [:repl :dht]
                             #(-> % (ld/load-module other)
                                  (space.dht/load-index index)))
            [state text] (type! state "(require (quote mod))")
            before (parked state)
            w (reduce (fn [w _] (tick w)) {:state state :peers {} :now 0}
                      (range 5))
            after (parked (:state w))]
        (is (str/includes? text "pending"))
        (testing (str
                   "the node reported a publication, an index loa"
                   "d and another module's load")
          (is (line-with (:lines w) "dht: published"))
          (is (line-with (:lines w) (str "dht: loaded " other)))
          (is (line-with (:lines w) (str "dht: loaded " index))))
        (testing "none of them re-checked the run"
          (is (= :loading (:status (ld/module-status (node-of (:state w))
                                                     nowhere))))
          (is (= 0 (:checks before) (:checks after)))
          (is (identical? (:vm before) (:vm after))))
        (close-world! w))
      (finally
        (cleanup-dir! dir)))))


(deftest a-function-policy-counts-only-re-checks-of-this-run
  (let [dir (temp-dir)
        net (mesh/mesh)
        _silent (mesh/join! net 9)]
    (try
      (let [views (atom [])
            state (-> (reader-state net dir 4 [9] {'gone nowhere})
                      (assoc-in [:repl :link-policy]
                                (fn [v]
                                  (swap! views conj (:checks v))
                                  (if (>= (:checks v) 2) :abandon :keep))))
            [state _] (type! state "(require (quote gone))")
            [repl _] (repl/recheck-pending (:repl state))
            _ (is (= 1 (:checks (:pending-run repl))))
            [repl _] (repl/eval-input repl "(abandon)")
            [repl _] (repl/eval-input repl "(require (quote gone))")
            _ (is (= 0 (:checks (:pending-run repl))) "a new run starts at 0")
            w (reduce (fn [w _] (tick w))
                      {:state (assoc state :repl repl) :peers {} :now 0}
                      (range 5))
            repl (:repl (:state w))
            _ (is (= 0 (:checks (:pending-run repl)))
                  "the node's steps are not re-checks")
            [repl _] (repl/recheck-pending repl)
            _ (is (some? (:pending-run repl)) "one re-check of this run: kept")
            [repl text] (repl/recheck-pending repl)]
        (is (nil? (:pending-run repl)))
        (is (str/includes? text "the session link policy ended the require"))
        (is (= [0 1 0 1 2] @views))
        (close-world! (assoc w :state (assoc (:state w) :repl repl))))
      (finally
        (cleanup-dir! dir)))))


(deftest a-keep-policy-never-ends-a-dht-run
  (let [dir (temp-dir)
        net (mesh/mesh)
        _silent (mesh/join! net 9)]
    (try
      (let [state (-> (reader-state net dir 4 [9] {'gone nowhere})
                      (assoc-in [:repl :link-policy] (constantly :keep)))
            [state _] (type! state "(require (quote gone))")
            repl (nth (iterate #(first (repl/recheck-pending %)) (:repl state))
                      6)
            _ (is (= 6 (:checks (:pending-run repl))))
            w (run-ticks {:state (assoc state :repl repl) :peers {} :now 0}
                         20000
                         #(nil? (parked (:state %))))]
        (testing "only the load's own failure ended it, as a refusal"
          (is (nil? (parked (:state w))))
          (is (line-with (:lines w) "Module link refused: absent"))
          (is (not (line-with (:lines w) "session link policy"))))
        (close-world! w))
      (finally
        (cleanup-dir! dir)))))


(deftest
  abandon-during-a-load-then-a-new-require-finds-it-loaded
  (let
    [dir (temp-dir)
     net (mesh/mesh)
     w (holder-world net)
     m (:manifest w)]
    (try
      (let
        [state (reader-state net dir 4 [1 2 3] {'mod m})
         [state _] (type! state "(require (quote mod))")
         [state text] (type! state "(abandon)")
         _ (is (str/includes? text "abandoned"))
         _ (is (nil? (parked state)))
         w (run-ticks
             (assoc w :state state) 60000
             #(=
                :loaded (:status
                          (ld/module-status
                            (node-of
                              (:state %)) m))))
         state (:state w)]
        (testing "the load completed and settled nothing"
          (is (= :loaded (:status (ld/module-status (node-of state) m))))
          (is (nil? (parked state)))
          (is (nil? (get-in state [:repl :last-value])))
          (is (= [] (responses state))
              "no answer was appended for the abandoned run"))
        (let [fetched (:fetched (ld/module-status (node-of state) m))
              [state _] (type! state "(require (quote mod))")
              repl (:repl state)]
          (testing (str
                     "a new require of the name finds the closure l"
                     "oaded and links")
            (is (nil? (:pending-run repl)))
            (is (= 'mod (:last-value repl)))
            (is (= fetched (:fetched (ld/module-status (node-of state) m)))
                "no new load"))
          (testing "the abandoned request's late answer was skipped"
            (let [ids (mapv :yin.link/id (requests state))]
              (is (= [{:kind :unknown :id (first ids) :entry (second ids)}]
                     (first (engine/take-link-diagnostics (:vm repl)))))))
          (is (= "42" (second (type! state "(mod/f)"))))
          (close-world! (assoc w :state state))))
      (finally
        (cleanup-dir! dir)))))


;; -----------------------------------------------------------------------------
;; Failures, each its one response shape (section 9)
;; -----------------------------------------------------------------------------

(defn- solo-state
  [dir names]
  (reader-state (mesh/mesh) dir 4 [] names))


(defn- fail-require
  "Require `name` on the solo shell `state`, step the node until the run
   ends, and answer `{:state s :text t :lines l}`."
  [state name]
  (let [[state text] (type! state (str "(require (quote " name "))"))
        w (run-ticks {:state state :peers {} :now 0} 1000
                     #(nil? (parked (:state %))))]
    (is (str/includes? text "pending"))
    {:state (:state w) :text text :lines (:lines w)}))


^:cljstyle/ignore
(deftest
 a-solo-node-s-require-fails-with-cause-solo-and-a-new-require-starts-a-new-load
  (let [dir (temp-dir)]
    (try
      (let [{:keys [state lines]} (fail-require (solo-state dir {'mod nowhere})
                                                'mod)]
        (testing "the node's next step failed it with /solo"
          (is (nil? (parked state)))
          (is (line-with lines "Module link refused: absent"))
          (is (= [{:status :refused :reason :absent :address nowhere
                   :cause ::dht/solo}]
                 (responses state))))
        (testing (str
                   "the failed record was forgotten; a new requir"
                   "e starts a new load")
          (is (nil? (ld/module-status (node-of state) nowhere)))
          (let [[state text] (type! state "(require (quote mod))")]
            (is (str/includes? text "pending"))
            (is (= :loading (:status (ld/module-status (node-of state)
                                                       nowhere))))
            (main/close-index-store! state))))
      (finally
        (cleanup-dir! dir)))))


(deftest a-defective-closure-raises-descriptor-defect-with-its-code
  (let [dir (temp-dir)]
    (try
      (let [state (solo-state dir {})
            store (space.dht/local (node-of state))
            res (ct/publish-base! store)
            bad (jing/materialize! store (assoc (:manifest res)
                                                :yin.module/schema 2))
            {:keys [state lines]} (fail-require
                                    (assoc-in state [:repl :link-source
                                                     :name-env]
                                              {'base bad})
                                    'base)
            [body] (responses state)]
        (is (line-with lines "Module link refused: descriptor-defect"))
        (is (= {:status :refused :reason :descriptor-defect :address bad
                :code :manifest-defect}
               (dissoc body :detail :text)))
        (main/close-index-store! state))
      (finally
        (cleanup-dir! dir)))))


(defn- undeliverable
  "A request writer that refuses every append as closed."
  []
  (reify
    stream/IDaoStreamWriter

    (append! [_ _] {:dao.stream/outcome :dao.stream/closed})))


(deftest a-load-that-cannot-ask-raises-unaskable-with-the-outcome
  (let [dir (temp-dir)]
    (try
      (let [state (-> (solo-state dir {'mod nowhere})
                      (update-in [:repl :dht :client] assoc :requests
                                 (undeliverable)))
            {:keys [state lines]} (fail-require state 'mod)]
        (is (line-with lines "Module link refused: unaskable"))
        (is (= [{:status :refused :reason :yin.link.dht/unaskable :address
                 nowhere
                 :outcome :request-undeliverable}]
               (responses state)))
        (main/close-index-store! state))
      (finally
        (cleanup-dir! dir)))))


(defn- publish-requiring-app!
  "Publish `app` into `store`: `(require 'base)`, then
   `(yin/def g (+ (base/f) 1))`, pinned to the manifest `base`."
  [store base]
  (let [variable (fn [n] {:type :variable :name n})
        call (fn [f & xs] {:type :application :operator f :operands (vec xs)})]
    (publish/publish-module!
      store {:name 'app
             :ast (call {:type :lambda :params '[_]
                         :body (ct/def! 'g (call (variable '+)
                                                 (call (variable 'base/f))
                                                 (ct/lit 1)))}
                        (call (variable 'require) (ct/lit 'base)))
             :exports #{'g}
             :requires {'base base}
             :primitives (assoc ct/plus 'require
                                (vm/profile-of vm/primitives 'require))})))


(deftest a-dependency-binding-that-is-not-ok-raises-before-any-install
  (let [dir (temp-dir)]
    (try
      (let [state (solo-state dir {})
            store (space.dht/local (node-of state))
            base (:address (ct/publish-base! store))
            app (:address (publish-requiring-app! store base))
            {:keys [state lines]} (fail-require
                                    (assoc-in state [:repl :link-source
                                                     :name-env]
                                              {'app app})
                                    'app)]
        (is (line-with lines "Module link refused: dependency-binding"))
        (is (= [{:status :refused :reason :yin.link.dht/dependency-binding
                 :module app :name 'base :pinned base :binding :absent
                 :diagnostics []}]
               (responses state)))
        (testing "no install child started: none asked for base"
          (is (= ['app] (mapv :yin.link/name (requests state))))
          (is (zero? (or (get-in state [:repl :vm :origins]) 0))))
        (testing "with the dependency named, app links through two loads"
          (let [state (assoc-in state [:repl :link-source :name-env]
                                {'app app 'base base})
                [state _] (type! state "(require (quote app))")
                w (run-ticks {:state state :peers {} :now 0} 1000
                             #(nil? (parked (:state %))))
                state (:state w)]
            (is (= 'app (get-in state [:repl :last-value]))
                (pr-str (:lines w)))
            (is (= :loaded (:status (ld/module-status (node-of state) base))))
            (is (= "43" (second (type! state "app/g"))))
            (main/close-index-store! state))))
      (finally
        (cleanup-dir! dir)))))


;; -----------------------------------------------------------------------------
;; A record of another kind at the resolved address (section 9; head trace
;; ruling 3, yin.vm.linker.dht.head.md 5.5 "Kinds do not mix")
;; -----------------------------------------------------------------------------

(defn- conflict-require
  "Require `mod`, bound to `address`, on the shell `state`, whose node
   records `address` under the kind `kind`: the require is refused at
   once with the kind conflict and the record is as it was.  Answers the
   state."
  [state address kind]
  (let [state (assoc-in state [:repl :link-source :name-env] {'mod address})
        record (get-in (node-of state) [:loads address])
        asked (count (requests state))
        [state text] (type! state "(require (quote mod))")]
    (is (some? record))
    (is (str/includes? text "Module link refused: kind-conflict") text)
    (is (nil? (parked state)) "not waited on")
    (is (= (inc asked) (count (requests state))))
    (is (= {:status :refused :reason ::space.dht/kind-conflict
            :address address :recorded kind}
           (peek (responses state))))
    (is (= record (get-in (node-of state) [:loads address]))
        "the record is neither forgotten nor restarted")
    (is (not= 'mod (get-in state [:repl :last-value])) "not linked")
    state))


(defn- follow-tick
  "One reading for a shell with a head follower beside it: the shell's
   step owner, then the follower on the shell's node."
  [{:keys [state follower now] :as w}]
  (let [[state _ _] (main/step-all state nil now)
        [follower node _] (head/step follower (node-of state) now)]
    (assoc w
           :state (assoc-in state [:repl :dht] node)
           :follower follower
           :now (+ now 10))))


(deftest a-require-of-a-failed-candidate-s-address-leaves-it-to-the-follower
  (let [dir (temp-dir)]
    (try
      (let [key (sign/generate)
            p (sign/principal (:public key))
            state (solo-state dir {'mod nowhere})
            board (head/board)
            [follower node] (head/follow (node-of state)
                                         {:follow [p] :heads :volatile
                                          :poll-ticks 10 :repair-ticks 100
                                          :repair-max-ticks 400})
            _ (stream/append! board (head/trace key nowhere 0))
            candidate #(get-in % [:follower :principals p :candidate])
            status #(space.dht/load-status (node-of (:state %)) nowhere)
            w (loop [w {:state (assoc-in state [:repl :dht] node)
                        :follower (head/attach follower p board)
                        :now 0}]
                (if (or (= :failed (:state (candidate w))) (> (:now w) 1000))
                  w
                  (recur (follow-tick w))))
            failed (candidate w)]
        (testing "the candidate's load failed and its retry is due later"
          (is (= :failed (:state failed)))
          (is (= {:status :failed :kind head/candidate-kind}
                 (select-keys (status w) [:status :kind])))
          (is (< (:now w) (:due failed))))
        (let [w (update w :state conflict-require nowhere head/candidate-kind)]
          (testing "the retry delay is intact: no restart before it is due"
            (let [w (loop [w w]
                      (if (< (:now w) (:due failed))
                        (do (is (= failed (candidate w)))
                            (is (= :failed (:status (status w))))
                            (recur (follow-tick w)))
                        w))]
              (testing "and the follower restarts it once it is"
                (let [w (follow-tick w)]
                  (is (= {:status :loading :kind head/candidate-kind}
                         (select-keys (status w) [:status :kind])))
                  (is (= {:state :loading :delay (* 2 (:delay failed))}
                         (select-keys (candidate w) [:state :delay])))
                  (main/close-index-store! (:state w))))))))
      (finally
        (cleanup-dir! dir)))))


(deftest a-require-of-a-failed-index-load-made-by-hand-leaves-it-in-place
  (let [dir (temp-dir)]
    (try
      (let [state (-> (solo-state dir {'mod nowhere})
                      (update-in [:repl :dht] space.dht/load-index nowhere))
            w (run-ticks {:state state :peers {} :now 0} 1000
                         #(= :failed (:status (space.dht/load-status
                                                (node-of (:state %))
                                                nowhere))))
            state (conflict-require (:state w) nowhere space.dht/index-kind)]
        (is (= {:status :failed :kind space.dht/index-kind}
               (select-keys (space.dht/load-status (node-of state) nowhere)
                            [:status :kind])))
        (testing "a second require is refused the same way: nothing was forgotten"
          (main/close-index-store!
            (conflict-require state nowhere space.dht/index-kind))))
      (finally
        (cleanup-dir! dir)))))


(deftest a-require-of-a-loading-or-loaded-record-of-another-kind-is-refused
  (let [dir (temp-dir)]
    (try
      (let [[state _] (type! (solo-state dir {}) "(def z 1)")
            index (get-in state [:repl :indexer :manifest-address])
            other (publish-mod! (space.dht/local (node-of state)))
            loading-index (jing/segment-key "an index still loading")
            loading-head (jing/segment-key "a head still loading")
            status #(:status (space.dht/load-status (node-of %1) %2))
            ;; loaded: an index load by hand, and a load of a kind nobody
            ;; here knows, at the address of a module that would link
            state (update-in state [:repl :dht]
                             #(-> %
                                  (space.dht/load-index index)
                                  (space.dht/load other
                                                  {:kind :test/another
                                                   :walk (ld/closure-walk
                                                           other)})))
            w (run-ticks {:state state :peers {} :now 0} 1000
                         #(= [:loaded :loaded]
                             [(status (:state %) index)
                              (status (:state %) other)]))
            ;; loading: started after the last step, so never advanced
            state (update-in (:state w) [:repl :dht]
                             #(-> %
                                  (space.dht/load-index loading-index)
                                  (space.dht/load loading-head
                                                  {:kind head/candidate-kind
                                                   :walk (space.dht/index-walk
                                                           loading-head)})))]
        (is (= [:loaded :loaded :loading :loading]
               (mapv #(status state %)
                     [index other loading-index loading-head])))
        (-> state
            (conflict-require index space.dht/index-kind)
            (conflict-require other :test/another)
            (conflict-require loading-index space.dht/index-kind)
            (conflict-require loading-head head/candidate-kind)
            main/close-index-store!))
      (finally
        (cleanup-dir! dir)))))


;; -----------------------------------------------------------------------------
;; All four VMs
;; -----------------------------------------------------------------------------

(def ^:private vm-types
  [:ast-walker :semantic :stack :register])


(deftest all-four-vms-link-the-closed-corpus-over-the-dht
  (let [net (mesh/mesh)
        w0 (holder-world net)
        m (:manifest w0)
        dirs (mapv (fn [_] (temp-dir)) vm-types)]
    (try
      (let [w (reduce
                (fn [w [vm-type dir port]]
                  (let [state (reader-state net dir port [1 2 3] {'mod m})
                        [state _] (type! state (str "(vm " vm-type ")"))
                        [state text] (type! state "(require (quote mod))")
                        w (run-ticks (assoc w :state state) (+ (:now w) 60000)
                                     #(nil? (parked (:state %))))
                        state (:state w)]
                    (testing (name vm-type)
                      (is (str/includes? text "pending"))
                      (is (= 'mod (get-in state [:repl :last-value])))
                      (is (= "42" (second (type! state "(mod/f)")))))
                    (when (= :register vm-type)
                      (testing (str
                                 "the register kernel asks for R and no fallbac"
                                 "k is composed")
                        (is (= [:yin.debruijn.register]
                               (mapv :yin.link/format (requests state))))
                        (is (= [:ok] (mapv :status (responses state))))
                        (is (not-any? :fallback (responses state)))))
                    (main/close-index-store! state)
                    (dissoc w :state :lines)))
                w0
                (map vector vm-types dirs (range 4 8)))]
        (space.dht/close! (:holder w)))
      (finally
        (run! cleanup-dir! dirs))))
  (testing "a failed load raises the same refusal on each"
    (let [bodies (mapv (fn [vm-type]
                         (let [dir (temp-dir)]
                           (try
                             (let [state (solo-state dir {'mod nowhere})
                                   [state _] (type! state (str "(vm " vm-type
                                                               ")"))
                                   {:keys [state]} (fail-require state 'mod)]
                               (main/close-index-store! state)
                               (responses state))
                             (finally
                               (cleanup-dir! dir)))))
                       vm-types)]
      (is (= (repeat 4 [{:status :refused :reason :absent :address nowhere
                         :cause ::dht/solo}])
             bodies)))))


;; -----------------------------------------------------------------------------
;; The host module, the composition, the banner and (help)
;; -----------------------------------------------------------------------------

(deftest load-module-and-module-status-answer-through-the-plain-functions
  (let [dir (temp-dir)]
    (try
      (let [state (solo-state dir {})
            m (:address (ct/publish-base! (space.dht/local (node-of state))))
            [state _] (type! state "(require (quote dao.space.dht))")
            [state _] (type! state (str "(dao.space.dht/module-status " m ")"))
            _ (is (nil? (get-in state [:repl :last-value])))
            [state _] (type! state (str "(dao.space.dht/load-module " m ")"))
            _ (is (= :loading (get-in state [:repl :last-value])))
            _ (is (= :loading (:status (ld/module-status (node-of state) m))))
            [state _ _] (main/step-all state nil 0)
            [state _] (type! state (str "(dao.space.dht/module-status " m ")"))]
        (is (= (dissoc (ld/module-status (node-of state) m) :value)
               (get-in state [:repl :last-value])))
        (is (= :loaded (get-in state [:repl :last-value :status])))
        (main/close-index-store! state))
      (finally
        (cleanup-dir! dir)))))


(deftest both-load-operations-answer-a-refusal-as-data
  ;; head trace slice H1 (yin.vm.linker.dht.head.md 5.5): a load of an
  ;; address recorded under another kind is refused, and the host answers
  ;; the refusal under its own code instead of throwing
  (let [dir (temp-dir)]
    (try
      (let [state (solo-state dir {})
            node (node-of state)
            m (:address (ct/publish-base! (space.dht/local node)))
            candidate (jing/segment-key "a head loading as a candidate")
            state (assoc-in state [:repl :dht]
                            (space.dht/load node candidate
                                            {:kind head/candidate-kind
                                             :walk (space.dht/index-walk
                                                     candidate)}))
            [state _] (type! state "(require (quote dao.space.dht))")
            [state by-hand] (type! state (str "(dao.space.dht/load-index "
                                              candidate ")"))
            [state indexed] (type! state (str "(dao.space.dht/load-index " m
                                              ")"))
            [state module] (type! state (str "(dao.space.dht/load-module " m
                                             ")"))
            [state status] (type! state (str "(dao.space.dht/load-status "
                                             candidate ")"))]
        (testing "load-index of a manifest loading as a candidate"
          (is (str/includes? by-hand ":dao.space.dht/kind-conflict") by-hand)
          (is (= head/candidate-kind
                 (:kind (space.dht/load-status (node-of state) candidate)))))
        (is (= ":loading" indexed))
        (testing "load-module of an address recorded as an index load"
          (is (str/includes? module ":dao.space.dht/kind-conflict") module)
          (is (= space.dht/index-kind
                 (:kind (space.dht/load-status (node-of state) m)))))
        (testing "the round continues and the next request is answered"
          (is (str/includes? status ":status :loading") status)
          (is (= :loading (get-in state [:repl :last-value :status]))))
        (main/close-index-store! state))
      (finally
        (cleanup-dir! dir)))))


(deftest a-dht-store-refuses-a-second-content-source
  (let [dir (temp-dir)]
    (try
      (is (some? (refusal-of #(repl/create-state
                                {:index-store-spec (dht-spec dir {})
                                 :content-store (mem/create-content-mem)}))))
      (testing "and the refusal left the directory unlocked"
        (close! (repl/create-state {:index-store-spec (dht-spec dir {})})))
      (finally
        (cleanup-dir! dir)))))


(deftest the-banner-and-help-say-a-require-may-fetch-from-peers
  (let [banner (str/join "\n" (main/banner (main/parse-args
                                             ["--index-store" "dht:idx"])))
        [_ help] (repl/eval-input (repl/create-state) "(help)")]
    (is (str/includes? banner "require"))
    (is (str/includes? banner "may fetch"))
    (is (str/includes? help "require"))
    (is (str/includes? help "may fetch"))))


;; =============================================================================
;; L4: rows every round, names in the index, publish at the prompt
;; (docs/design/yin.vm.linker.dht.md 5.1, 5.3, 5.5.6, 6, 7 and 12, slice L4)
;; =============================================================================

(defn- keyed-state
  "The step owner's state for a shell over a dht store at `dir`, at mesh
   `port` with bootstrap contacts `peer-ports` (none: solo), holding
   `key` (nil: none) and declaring the principals `principals`."
  ([net dir port peer-ports key principals]
   (keyed-state net dir port peer-ports key principals (mesh-bind net port
                                                                  (atom 0))))
  ([_net dir _port peer-ports key principals bind!]
   (main/boot {:index-store-spec
               (dht-spec dir {:bind! bind!
                              :publish? true
                              :peers (mapv (fn [p] {:host "127.0.0.1" :port p})
                                           peer-ports)})
               :dht-key key
               :principals principals})))


(defn- value-of
  "The value `line` answers at the shell's prompt: `[state value text]`."
  [state line]
  (let [[state text] (type! state line)]
    [state (get-in state [:repl :last-value]) text]))


(defn- tree-ast
  "The map AST of the tree whose root row `root` is in `store`."
  [store root]
  (vm/semantic-bytecode->ast
    {:root root
     :rows (into {} (map (fn [[a body]] [a (into [a] body)]))
                 (ct/tree-rows store root))}))


(defn- index-envelopes
  "Every name envelope in the shell's published index."
  [state]
  (let [ix (get-in state [:repl :indexer])]
    (vec (keep (fn [[_ a v]] (when (= :yin.module/envelope a) v))
               (space.index/read-datoms (:content-store ix)
                                        (:manifest-address ix))))))


(defn- link-base!
  "Publish base into the solo shell's own store, name it directly, and
   require it: answers the state with base linked, and base's address."
  [state]
  (let [base (:address (ct/publish-base! (space.dht/local (node-of state))))
        state (assoc-in state [:repl :link-source :name-env] {'base base})
        [state _] (type! state "(require (quote base))")
        w (run-ticks {:state state :peers {} :now 0} 1000
                     #(nil? (parked (:state %))))]
    (is (= 'base (get-in w [:state :repl :last-value]))
        (pr-str (:lines w)))
    [(:state w) base]))


(deftest
  publish-at-the-prompt-derives-one-tree-and-declares-what-it-reads
  (let
    [dir (temp-dir)
     key (sign/generate)]
    (try
      (let
        [state (keyed-state (mesh/mesh) dir 4 [] key [])
         [state base] (link-base! state)
         store (space.dht/local (node-of state))
         [state _] (type! state "(def h (fn [x] (+ x 1)))")
         [state _] (type! state "(def unused 5)")
         [state _] (type! state "(def f (fn [] (h (base/f))))")
         [state required] (type! state "(require (quote yin.link))")
         [state res text] (value-of
                            state
                            "(yin.link/publish (quote my.lib) (quote [f]))")
         manifest (jing/get store (:address res) nil)]
        (is (= "'yin.link" required))
        (testing "one tree, the defining programs in t order"
          (is (= '[h f] (publish-test/module-defs
                          (tree-ast store (:yin.module/tree manifest))))
              text))
        (testing "a manifest declaring the free primitive and the linked module"
          (is (= {'base base} (:yin.module/requires manifest)))
          (is (= #{'+ 'require} (set (keys (:yin.module/primitives manifest))))
              "require is free in the collected (require 'base) program")
          (is (= #{'f} (:yin.module/exports manifest))))
        (testing "the answer names the module, its address and where it links"
          (is (= 'my.lib (:module res)))
          (is (= #{:yin.ast/code :yin.semantic/code :yin.debruijn.code
                   :yin.debruijn.register}
                 (set (keys (:links res))))))
        (testing
          (str
            "the assertion is in the round's index, and th"
            "e node resolves it")
          (is (= [{:yin.module/op :assert :yin.module/name 'my.lib
                   :yin.module/manifest (:address res)
                   :yin.module/asserted-by (sign/principal (:public key))
                   :yin.module/seq 1}]
                 (index-envelopes state)))
          (is (= (get-in state [:repl :indexer :manifest-address])
                 (head-manifest dir))
              "HEAD names the index holding it")
          (let
            [[state names] (value-of state "(yin.link/names)")]
            (is (= (:address res) (get-in names [:names 'my.lib :address])))
            (testing
              "republishing with a new manifest retracts, then asserts"
              (let
                [[state _] (type! state "(def f (fn [] (h 2)))")
                 [state again] (value-of
                                 state
                                 (str
                                   "(yin.link/publish (quote "
                                   "my.lib) (quote [f]))"))
                 [state names] (value-of state "(yin.link/names)")
                 [state same] (value-of
                                state
                                (str
                                  "(yin.link/publish (quote "
                                  "my.lib) (quote [f]))"))]
                (is (not= (:address res) (:address again)))
                (is (= [[:assert 1] [:retract 2] [:assert 3]]
                       (mapv (juxt :yin.module/op :yin.module/seq)
                             (sort-by :yin.module/seq (index-envelopes
                                                        state)))))
                (is (= (:address again) (get-in names [:names 'my.lib
                                                       :address])))
                (is (= (:address again) (:address same)))
                (is (= 3 (count (index-envelopes state)))
                    "the same manifest again writes nothing")
                (main/close-index-store! state))))))
      (finally
        (cleanup-dir! dir)))))


(deftest
  publish-at-the-prompt-refuses-and-writes-nothing
  (let
    [dir (temp-dir)
     key (sign/generate)]
    (try
      (let
        [state (keyed-state (mesh/mesh) dir 4 [] key [])
         lines ["(def ok (fn [] 1))"
                "(def g (fn [] (mystery 1)))"
                "(require (quote dao.space.query))"
                "(def q2 (fn [] (dao.space.query/q 1)))"
                "(require (quote yin.link))"]
         state (reduce (fn [s l] (first (type! s l))) state lines)
         refused (fn [state exports reason]
                   (let
                     [before (get-in state [:repl :indexer :transactions])
                      [state text] (type!
                                     state (str
                                             "(yin.link/publish (quote "
                                             "my.lib) (quote "
                                             exports "))"))]
                     (is (str/includes? (str text) reason) (str text))
                     (is (empty? (index-envelopes state))
                         "no name was asserted")
                     (is (= (inc before) (get-in state [:repl :indexer
                                                        :transactions]))
                         "only the round's own program was committed")
                     state))
         state (refused state "[nope]" "undefined-export")
         state (refused state "[g]" "undeclared-free")
         state (refused state "[q2]" "host-module")
         state (refused (assoc-in state [:repl :dht-key] nil) "[ok]"
                        "no-key")]
        (main/close-index-store! state))
      (finally
        (cleanup-dir! dir)))))


(deftest
  the-next-sequence-is-derived-from-the-index-after-a-restart
  (let
    [dir (temp-dir)
     key (sign/generate)]
    (try
      (let
        [state (keyed-state (mesh/mesh) dir 4 [] key [])
         state (reduce (fn [s l] (first (type! s l)))
                       state
                       ["(def f (fn [] 1))" "(require (quote yin.link))"
                        "(yin.link/publish (quote my.lib) (quote [f]))"])
         _ (main/close-index-store! state)
         ;; a new process over the same directory and the same key file
         state (keyed-state (mesh/mesh) dir 4 [] key [])
         state (reduce (fn [s l] (first (type! s l)))
                       state
                       ["(def f (fn [] 2))" "(require (quote yin.link))"])
         [state res] (value-of
                       state
                       "(yin.link/publish (quote my.lib) (quote [f]))")
         [state names] (value-of state "(yin.link/names)")]
        (is (= [[:assert 1] [:retract 2] [:assert 3]]
               (mapv (juxt :yin.module/op :yin.module/seq)
                     (sort-by :yin.module/seq (index-envelopes state))))
            "no sequence restarts at 1: no equivocation")
        (is (= (:address res) (get-in names [:names 'my.lib :address])))
        (is (empty? (:diagnostics names)))
        (main/close-index-store! state))
      (finally
        (cleanup-dir! dir)))))


;; -----------------------------------------------------------------------------
;; A mesh world of shells, plain nodes and plain peers
;; -----------------------------------------------------------------------------

(defn- blocking-bind
  "The mesh seam at `port`, whose socket never reaches the ports
   `blocked`: a node whose lookups cannot reach the publisher."
  [net port blocked]
  (let [bind! (mesh/seam net port)]
    (fn [opts]
      (let [seam (bind! opts)]
        (assoc seam
               :send! (fn [host to-port bs]
                        (if (contains? blocked to-port)
                          {:dao.stream/outcome :dao.stream/transport-error}
                          ((:send! seam) host to-port bs))))))))


(defn- shell-at
  "A publishing shell over a dht store at `dir`, socket `bind!`, contacts
   `peer-ports`, holding `key` and declaring `principals`."
  [dir bind! peer-ports key principals]
  (repl/create-state
    {:index-store-spec (dht-spec dir {:bind! bind!
                                      :publish? true
                                      :peers (mapv (fn [p]
                                                     {:host "127.0.0.1"
                                                      :port p})
                                                   peer-ports)})
     :dht-key key
     :principals principals}))


(defn- plain-node
  "A fetch-only plain node: what it loads it never serves on."
  [bind! peer-ports]
  (space.dht/join {:local (mem/create-content-mem)
                   :peers (mapv (fn [p] {:host "127.0.0.1" :port p}) peer-ports)
                   :bind! bind!}))


(defn- world
  [net peer-ports]
  {:net net
   :peers (into {} (map (fn [p] [p (peer-node net p)])) peer-ports)
   :shells {}
   :nodes {}
   :now 0
   :lines []
   :events []})


(defn- step-world
  "One reading `dt` long: every shell's node (`yin.repl.dht/step`, then
   the re-check its load events call for, as the host ticker does), every
   plain node, every peer.  Lines and events accumulate, each event
   marked with its owner under `:who` and its reading under `:at`."
  [w dt]
  (let [now (:now w)
        mark (fn [who events] (map #(assoc % :who who :at now) events))
        shells (map (fn [[k s]]
                      (let [[s lines events] (repl.dht/step s now)
                            [s text] (repl/recheck-on-load-events s events)]
                        [k s (cond-> lines text (conj text)) (mark k events)]))
                    (:shells w))
        nodes (map (fn [[k n]]
                     (let [[n events] (space.dht/step n now)]
                       [k n (mark k events)]))
                   (:nodes w))]
    (-> w
        (assoc :shells (into {} (map (fn [[k s]] [k s])) shells)
               :nodes (into {} (map (fn [[k n]] [k n])) nodes)
               :peers (step-peers (:peers w) now)
               :now (+ now dt))
        (update :lines into (mapcat #(nth % 2)) shells)
        (update :events into (mapcat #(nth % 3)) shells)
        (update :events into (mapcat #(nth % 2)) nodes))))


(defn- run-world
  [w dt limit done?]
  (loop [w w]
    (if (or (done? w) (> (:now w) limit))
      w
      (recur (step-world w dt)))))


(defn- eval-at
  "Evaluate `line` at shell `k`'s prompt: `[w text value]`."
  [w k line]
  (let [[s text] (repl/eval-input (get-in w [:shells k]) line)]
    [(assoc-in w [:shells k] s) text (:last-value s)]))


(defn- event-of
  [w who kind manifest]
  (some #(when (and (= who (:who %)) (= kind (::space.dht/event %))
                    (= manifest (:manifest %)))
           %)
        (:events w)))


(defn- head-of
  "The index manifest shell `k` published last."
  [w k]
  (get-in w [:shells k :indexer :manifest-address]))


(defn- datoms-of
  [w k]
  (let [ix (get-in w [:shells k :indexer])]
    (vec (space.index/read-datoms (:content-store ix) (:manifest-address ix)))))


(defn- row-of-value
  "The row address of the literal `v` in shell `k`'s index."
  [w k v]
  (let [datoms (datoms-of w k)
        e (some (fn [[e a x]] (when (and (= :yin/value a) (= v x)) e)) datoms)]
    (some (fn [[e' a x]] (when (and (= e e') (= :yin/address a)) x)) datoms)))


(defn- root-of-value
  "The `:yin.repl/root` of the program in shell `k`'s index holding the
   literal `v`."
  [w k v]
  (let [datoms (datoms-of w k)
        e (some (fn [[e a x]] (when (and (= :yin/value a) (= v x)) e)) datoms)
        m (some (fn [[e' _ _ _ m]] (when (= e e') m)) datoms)]
    (some (fn [[e' a x]] (when (and (= m e') (= :yin.repl/root a)) x)) datoms)))


(defn- tree-walk
  "A `dao.space.dht/load` walk over the program tree at `root`: missing at
   the first row not local, complete once every row is."
  [root]
  (fn [handle]
    (loop [queue [root]
           seen #{}]
      (if-let [a (first queue)]
        (if (contains? seen a)
          (recur (rest queue) seen)
          (let [body (jing/get handle a ::absent)]
            (if (= ::absent body)
              {::space.dht/walk :missing :address a}
              (recur (concat (rest queue) ((:parts-fn linker/ast-format) body))
                     (conj seen a)))))
        {::space.dht/walk :complete :value (count seen)}))))


(defn- load-tree
  [node root]
  (space.dht/load node root {:kind ::tree :walk (tree-walk root)}))


(defn- close-all!
  [w]
  (doseq [[_ s] (:shells w)] (close! s))
  (doseq [[_ n] (:nodes w)] (space.dht/close! n))
  nil)


;; -----------------------------------------------------------------------------
;; Rows every round
;; -----------------------------------------------------------------------------

(deftest
  every-round-s-rows-are-published-and-a-second-node-loads-the-tree-by-its-root
  (let
    [dir (temp-dir)
     net (mesh/mesh)]
    (try
      (let
        [w (assoc-in (world net [2 3]) [:shells :p]
                     (shell-at dir (mesh-bind net 10 (atom 0)) [2 3] nil []))
         [w _] (eval-at w :p "(def answer (fn [x] (+ x 4242)))")
         m (head-of w :p)
         root (root-of-value w :p 4242)
         w (run-world w 10 20000 #(event-of % :p :published m))
         report (event-of w :p :published m)]
        (is (= :acknowledged (:result report)) (pr-str (dissoc report :failed)))
        (testing "the round's publication holds the program's rows"
          (is (< 8 (:blobs report)) "rows and the index blobs both")
          (is (some? (jing/get (space.dht/local (get-in w [:shells :p :dht]))
                               root nil))))
        (let
          [w (assoc-in
               w [:nodes :r] (load-tree
                               (plain-node
                                 (mesh/seam
                                   net
                                   11) [2 3 10])
                               root))
           w (run-world
               w 10 60000
               #(#{:loaded :failed} (:status
                                      (space.dht/load-status
                                        (get-in
                                          % [:nodes
                                             :r]) root))))
           status (space.dht/load-status (get-in w [:nodes :r]) root)]
          (is (= :loaded (:status status)) (pr-str status))
          (is (pos? (:fetched status)) "the rows came from the network")
          (close-all! w)))
      (finally
        (cleanup-dir! dir)))))


(defn- big-program
  "`(def big<r> (fn [] (+ r0 (+ r1 ... ))))`: well over the DHT's
   pending-write bound in rows."
  [r n]
  (str "(def big" r " (fn [] "
       (apply str (map (fn [i] (str "(+ " (+ (* r 1000) i) " ")) (range n)))
       "0"
       (apply str (repeat n ")"))
       "))"))


(deftest
  ^:slow rounds-larger-than-the-pending-write-bound-are-acknowledged-never-busy
  (slow/guard "rounds-larger-than-the-pending-write-bound-are-acknowledged-never-busy"
              (fn []
                (let [dir (temp-dir)
                      net (mesh/mesh)]
                  (try
                    (let [w (assoc-in (world net [2 3]) [:shells :p]
                                      (shell-at dir (mesh-bind net 10 (atom 0)) [2 3] nil []))
                          [w ms] (reduce (fn [[w ms] r]
                                           (let [[w _] (eval-at w :p (big-program r 80))]
                                             [w (conj ms (head-of w :p))]))
                                         [w []]
                                         (range 1 9))
                          w (run-world w 10 60000
                                       (fn [w] (every? #(event-of w :p :published %) ms)))
                          reports (mapv #(event-of w :p :published %) ms)]
                      (is (= 8 (count (distinct ms))))
                      (is (every? #(< space.dht/max-pending-writes (:blobs %)) reports)
                          (pr-str (map :blobs reports)))
                      (is (= (repeat 8 :acknowledged) (mapv :result reports)))
                      (is (not-any? #(= ::dht/busy (:reason %)) (mapcat :failed (:events w)))
                          "no write ever drew /busy")
                      (close-all! w))
                    (finally
                      (cleanup-dir! dir)))))))


;; -----------------------------------------------------------------------------
;; A failed row replication, repaired with no call from anyone
;; -----------------------------------------------------------------------------

(defn- refused-row-world
  "A publishing shell at mesh port 10 whose stores of the addresses
   `@refuse` holds are refused by every peer; `(def partly (fn [] 7007))`
   evaluated, the literal's row refused, and the round reported.
   Answers `[w m row dir]`."
  [net dir refuse]
  (let [w (assoc-in (world net [2 3]) [:shells :p]
                    (shell-at dir (refusing-bind net 10 refuse) [2 3] nil []))
        [w _] (eval-at w :p "(def partly (fn [] 7007))")
        m (head-of w :p)
        row (row-of-value w :p 7007)
        _ (reset! refuse #{row})
        w (run-world w 10 20000 #(event-of % :p :published m))]
    [w m row]))


(deftest
  a-failed-row-is-partial-unreachable-except-from-the-publisher-and-repaired
  (let
    [dir (temp-dir)
     net (mesh/mesh)
     refuse (atom (constantly false))]
    (try
      (let
        [[w m row] (refused-row-world net dir refuse)
         report (event-of w :p :published m)
         first-line (line-with (:lines w) (str "published " m))
         root (root-of-value w :p 7007)]
        (testing (str
                   "the round is indexed, HEAD moves, and the res"
                   "ult is partial naming the row")
          (is (= :partial (:result report)))
          (is (= [row] (mapv :address (:failed report))))
          (is (true? (:repairing? report)))
          (is (= m (head-manifest dir))))
        (testing (str
                   "the first line names the result, the failed c"
                   "ount and the retry")
          (is (str/includes? first-line "PARTIAL") first-line)
          (is (str/includes? first-line (str "1 of " (:blobs report)
                                             " blobs not sent")))
          (is (str/includes? first-line "retrying while the node is open")))
        (let
          [w (->
               w
               (assoc-in
                 [:nodes :far] (->
                                 (plain-node
                                   (blocking-bind
                                     net
                                     11 #{10}) [2 3])
                                 (space.dht/load-index m)))
               (assoc-in
                 [:nodes :near] (plain-node
                                  (mesh/seam net 12) [2
                                                      3 10])))
           w (run-world w 10 60000
                        #(= :loaded (:status (space.dht/load-status
                                               (get-in % [:nodes :far]) m))))
           _ (is
               (=
                 :loaded (:status
                           (space.dht/load-status
                             (get-in
                               w
                               [:nodes :far]) m)))
               "a reader loads the index from the peers")
           settled? (fn [w k]
                      (#{:loaded :failed}
                       (:status (space.dht/load-status (get-in w [:nodes k])
                                                       root))))
           w (run-world (update-in w [:nodes :far] load-tree root) 10 120000
                        #(settled? % :far))
           far (space.dht/load-status (get-in w [:nodes :far]) root)
           w (run-world
               (update-in w [:nodes :near] load-tree root) 10 (+
                                                                (:now w) 120000)
               #(settled? % :near))]
          (testing (str
                     "a lookup that does not reach the publisher mi"
                     "sses exactly that row")
            (is (= :failed (:status far)) (pr-str far))
            (is (= :miss (get-in far [:reason ::space.dht/failure])))
            (is (= row (get-in far [:reason :address])))
            (is (keyword? (get-in far [:reason :cause]))))
          (testing
            "a lookup that reaches the publisher loads it"
            (is
              (=
                :loaded (:status
                          (space.dht/load-status
                            (get-in
                              w [:nodes
                                 :near]) root)))))
          (reset! refuse #{})
          (let
            [first-at (:at report)
             w (run-world
                 w 100 (+ first-at 200000) #(event-of
                                              % :p
                                              :republished m))
             again (event-of w :p :republished m)]
            (testing "the node repairs on its own, after :repair-ticks"
              (is (= :acknowledged (:result again)) (pr-str again))
              (is (<= 30000 (- (:at again) first-at)))
              (is (str/includes? (str (line-with (:lines w) (str "republished "
                                                                 m)))
                                 "acknowledged: sent to 2 peers")))
            (testing
              "and a new load succeeds"
              (let
                [w (update-in w [:nodes :far] #(load-tree (space.dht/forget
                                                            % root) root))
                 w (run-world w 10 (+ (:now w) 60000) #(settled? % :far))]
                (is
                  (=
                    :loaded (:status
                              (space.dht/load-status
                                (get-in
                                  w
                                  [:nodes :far]) root))))
                (close-all! w))))))
      (finally
        (cleanup-dir! dir)))))


(deftest retry-at-the-prompt-brings-the-same-repair-forward
  (let [dir (temp-dir)
        net (mesh/mesh)
        refuse (atom (constantly false))]
    (try
      (let [[w m] (refused-row-world net dir refuse)
            first-at (:at (event-of w :p :published m))
            _ (reset! refuse #{})
            [w _] (eval-at w :p "(require (quote dao.space.dht))")
            [w retried] (eval-at w :p (str "(dao.space.dht/retry " m ")"))
            w (run-world w 10 (+ first-at 20000) #(event-of % :p :republished
                                                            m))
            again (event-of w :p :republished m)]
        (is (= ":retrying" retried))
        (is (= :acknowledged (:result again)) (pr-str again))
        (is (< (- (:at again) first-at) 30000) "well before :repair-ticks")
        (close-all! w))
      (finally
        (cleanup-dir! dir)))))


(deftest
  a-refused-manifest-is-unacknowledged-and-repaired-the-same-way
  (let
    [dir (temp-dir)
     net (mesh/mesh)
     refuse (atom (constantly false))]
    (try
      (let
        [w (assoc-in (world net [2 3]) [:shells :p]
                     (shell-at dir (refusing-bind net 10 refuse) [2 3] nil
                               []))
         [w _] (eval-at w :p "(def unsent 8)")
         m (head-of w :p)
         _ (reset! refuse #{m})
         w (run-world w 10 20000 #(event-of % :p :published m))
         report (event-of w :p :published m)
         _ (reset! refuse #{})
         w (run-world
             w 100 (+ (:at report) 200000) #(event-of
                                              % :p
                                              :republished m))
         again (event-of w :p :republished m)]
        (is (= :unacknowledged (:result report)))
        (is (= [m] (mapv :address (:failed report))))
        (is (= m (head-manifest dir)) "HEAD moved")
        (is (= :acknowledged (:result again)) (pr-str again))
        (is (<= 30000 (- (:at again) (:at report))))
        (close-all! w))
      (finally
        (cleanup-dir! dir)))))


;; -----------------------------------------------------------------------------
;; Peers that never accept: twenty rounds, the prompt and the ticker
;; -----------------------------------------------------------------------------

(deftest
  with-peers-that-never-accept-twenty-rounds-report-and-the-ticker-idles
  (let
    [dir (temp-dir)
     net (mesh/mesh)
     peers {2 (peer-node net 2) 3 (peer-node net 3)}]
    (try
      (let
        [state (keyed-state
                 net dir 10 [2 3] nil [] (refusing-bind
                                           net 10
                                           (atom (constantly true))))
         step (fn [{:keys [state peers now] :as w}]
                (let [[state _ lines] (main/step-all state nil now)]
                  (-> w
                      (assoc :state state :peers (step-peers peers now) :now
                             (+ now 250)
                             :moved? (main/moved? state nil lines))
                      (update :lines into lines)
                      (update :moved into [(main/moved? state nil lines)]))))
         w (reduce (fn [w i]
                     (let [[state text] (type! (:state w) (str "(def v" i
                                                               " " i ")"))
                           m (get-in state [:repl :indexer
                                            :manifest-address])]
                       (is (= (str i) text) "the prompt answers at once")
                       (loop [w (assoc w :state state)]
                         (if (or (line-with (:lines w) (str "published " m))
                                 (> (:now w) 2000000))
                           w
                           (recur (step w))))))
                   {:state state :peers peers :now 0 :lines [] :moved []}
                   (range 20))
         firsts (filter #(re-find #"dht: published " %) (:lines w))
         idle (reduce (fn [w _] (step w)) (assoc w :moved []) (range 400))]
        (is (= 20 (count firsts)) (pr-str (count firsts)))
        (is (every? #(str/includes? % "NOT acknowledged") firsts))
        (is (not-any? #(str/includes? % "acknowledged: sent") (:lines idle))
            "no event ever reports acknowledged")
        (testing "between repair cycles the ticker returns to its idle cadence"
          (is (some true? (:moved idle)) "repair cycles keep the base cadence")
          (is (some false? (drop-while false? (:moved idle)))
              "and the idle curve resumes after a cycle"))
        (main/close-index-store! (:state idle)))
      (finally
        (cleanup-dir! dir)))))


;; -----------------------------------------------------------------------------
;; A module published at the prompt in a partial round
;; -----------------------------------------------------------------------------

(deftest
  a-module-published-in-a-partial-round-is-absent-until-repaired
  (let
    [[pdir rdir] [(temp-dir) (temp-dir)]
     net (mesh/mesh)
     refuse (atom #{})
     kp (sign/generate)]
    (try
      (let
        [w (-> (world net [2 3])
               (assoc-in [:shells :p] (shell-at pdir (refusing-bind net 10
                                                                    refuse)
                                                [2 3] kp []))
               (assoc-in [:shells :r] (shell-at rdir (blocking-bind net 12
                                                                    #{10})
                                                [2 3] nil [(:public kp)])))
         [w _] (eval-at w :p "(def f (fn [] 4242))")
         [w _] (eval-at w :p "(require (quote yin.link))")
         [w _ res] (eval-at w :p
                            "(yin.link/publish (quote my.lib) (quote [f]))")
         a (:address res)
         m (head-of w :p)
         store (space.dht/local (get-in w [:shells :p :dht]))
         image (get-in (jing/get store a nil) [:yin.module/derivations
                                               :yin.semantic/code])
         _ (reset! refuse #{image})
         w (run-world w 10 20000 #(event-of % :p :published m))
         report (event-of w :p :published m)]
        (is (= :partial (:result report)) (pr-str (dissoc report :failed)))
        (is (= [image] (mapv :address (:failed report))))
        (let
          [[w _] (eval-at w :r "(require (quote dao.space.dht))")
           [w _] (eval-at w :r (str "(dao.space.dht/load-index " m ")"))
           w (run-world w 10 (+ (:now w) 60000) #(event-of % :r :loaded m))
           [w _] (eval-at w :r "(require (quote yin.link))")
           [w _ names] (eval-at w :r "(yin.link/names)")
           _ (is (= a (get-in names [:names 'my.lib :address]))
                 "the reader resolves the name")
           [w text] (eval-at w :r "(require (quote my.lib))")
           _ (is (str/includes? text "pending"))
           w (run-world w 10 (+ (:now w) 120000)
                        #(nil? (get-in % [:shells :r :pending-run])))
           refusal (peek (mesh/values (get-in w [:shells :r :link-pair
                                                 :responses])))]
          (testing (str
                     "until repair completes the require is refused"
                     " :absent with the miss cause")
            (is (= {:status :refused :reason :absent :address image}
                   (select-keys refusal [:status :reason :address])))
            (is (keyword? (:cause refusal)) (pr-str refusal)))
          (reset! refuse #{})
          (let
            [w (run-world
                 w 100 (+ (:at report) 200000) #(event-of
                                                  % :p
                                                  :republished m))
             _ (is (= :acknowledged (:result (event-of w :p :republished
                                                       m))))
             [w text] (eval-at w :r "(require (quote my.lib))")
             _ (is (str/includes? text "pending"))
             w (run-world w 10 (+ (:now w) 120000)
                          #(nil? (get-in % [:shells :r :pending-run])))
             _ (is
                 (= 'my.lib (get-in w [:shells :r :last-value]))
                 (pr-str
                   (:lines w)))
             [w answer] (eval-at w :r "(my.lib/f)")]
            (testing "a require after it evaluates"
              (is (= "4242" answer)))
            (close-all! w))))
      (finally
        (run! cleanup-dir! [pdir rdir])))))


;; -----------------------------------------------------------------------------
;; A two-principal dependency at the prompt
;; -----------------------------------------------------------------------------

(defn- until-settled
  "Run `w` until every shell's latest index publication is reported."
  [w]
  (run-world w 10 (+ (:now w) 60000)
             (fn [w]
               (every? (fn [[k _]]
                         (event-of w k :published (head-of w k)))
                       (:shells w)))))


(defn- require-at
  "Require `module` at shell `k` and run until the run ends: `[w text]`,
   the text of the require or of the re-check that ended it."
  [w k module]
  (let [[w text] (eval-at w k (str "(require (quote " module "))"))
        w (run-world w 10 (+ (:now w) 120000)
                     #(nil? (get-in % [:shells k :pending-run])))]
    [w (str text (str/join "\n" (:lines w)))]))


(defn- last-response
  [w k]
  (dissoc (peek (mesh/values (get-in w [:shells k :link-pair :responses])))
          :yin.link/id))


(deftest
  ^:slow a-two-principal-dependency-evaluates-or-raises-dependency-binding
  (slow/guard "a-two-principal-dependency-evaluates-or-raises-dependency-binding"
              (fn []
                (let
                  [dirs (vec (repeatedly 3 temp-dir))
                   net (mesh/mesh)
                   k1 (sign/generate)
                   k2 (sign/generate)]
                  (try
                    (let
                      [w (->
                           (world net [2 3])
                           (assoc-in
                             [:shells :p1] (shell-at
                                             (dirs 0) (mesh-bind
                                                        net 10
                                                        (atom 0))
                                             [2 3 11 12] k1 []))
                           (assoc-in
                             [:shells :p2] (shell-at
                                             (dirs 1) (mesh-bind
                                                        net 11
                                                        (atom 0))
                                             [2 3 10 12] k2 [(:public
                                                               k1)]))
                           (assoc-in [:shells :r] (shell-at (dirs 2) (mesh-bind net 12
                                                                                (atom 0))
                                                            [2 3 10 11] nil
                                                            [(:public k1) (:public
                                                                            k2)])))
                       ;; P1 publishes base
                       [w _] (eval-at w :p1 "(def f (fn [] 42))")
                       [w _] (eval-at w :p1 "(require (quote yin.link))")
                       [w _ base] (eval-at w :p1
                                           "(yin.link/publish (quote base) (quote [f]))")
                       m1 (head-of w :p1)
                       w (until-settled w)
                       ;; P2 loads P1's index, links base, and publishes app against it
                       [w _] (eval-at w :p2 "(require (quote dao.space.dht))")
                       [w _] (eval-at w :p2 (str "(dao.space.dht/load-index " m1 ")"))
                       w (run-world w 10 (+ (:now w) 60000) #(event-of % :p2 :loaded m1))
                       [w _] (require-at w :p2 'base)
                       ;; the require above is its own program: 5.3's closure by
                       ;; linked requirements collects it into app's tree
                       [w _] (eval-at w :p2 "(def g (fn [] (+ (base/f) 1)))")
                       [w _] (eval-at w :p2 "(require (quote yin.link))")
                       [w _ app] (eval-at w :p2
                                          "(yin.link/publish (quote app) (quote [g]))")
                       m2 (head-of w :p2)
                       w (until-settled w)
                       ;; a (reset) drops host modules: require the DHT one each time
                       load-at (fn [w m]
                                 (let
                                   [[w _] (eval-at w :r
                                                   "(require (quote dao.space.dht))")
                                    [w _] (eval-at
                                            w :r (str
                                                   "(dao.space.dht/load-index " m ")"))]
                                   (run-world
                                     w 10 (+ (:now w) 60000) #(event-of
                                                                % :r
                                                                :loaded m))))]
                      (is
                        (=
                          {'base (:address base)}
                          (:yin.module/requires
                            (jing/get
                              (space.dht/local
                                (get-in
                                  w
                                  [:shells :p2 :dht]))
                              (:address app) nil)))
                        "app pins the base P2 linked")
                      (testing
                        "absent: the reader has not loaded P1's index"
                        (let
                          [w (load-at w m2)
                           [w _] (require-at w :r 'app)]
                          (is (= {:status :refused :reason :yin.link.dht/dependency-binding
                                  :module (:address app) :name 'base :pinned (:address base)
                                  :binding :absent :diagnostics []}
                                 (last-response w :r)))
                          (testing
                            "matching: both indexes loaded, both principals declared"
                            (let
                              [w (load-at w m1)
                               [w _] (require-at w :r 'app)
                               _ (is
                                   (= 'app (get-in w [:shells :r :last-value]))
                                   (pr-str
                                     (last-response w :r)))
                               [w answer] (eval-at w :r "(app/g)")]
                              (is (= "43" answer))
                              (testing
                                "mismatch: P1 republished base at another address"
                                (let
                                  [[w _] (eval-at w :p1 "(def f (fn [] 7))")
                                   [w _ base2] (eval-at
                                                 w :p1
                                                 (str
                                                   "(yin.link/publish (quote "
                                                   "base) (quote [f]))"))
                                   w (until-settled w)
                                   w (load-at w (head-of w :p1))
                                   [w _] (eval-at w :r "(reset)")
                                   [w _] (require-at w :r 'app)]
                                  (is (= {:status :refused :reason
                                          :yin.link.dht/dependency-binding
                                          :module (:address app) :name 'base :pinned
                                          (:address base)
                                          :binding :mismatch :resolved (:address base2)
                                          :asserters [(sign/principal (:public k1))]}
                                         (last-response w :r)))
                                  (testing
                                    "ambiguous: P2 also asserts base at another address"
                                    (let
                                      [[w _] (eval-at w :p2 "(def f (fn [] 9))")
                                       [w _ own] (eval-at
                                                   w :p2
                                                   (str
                                                     "(yin.link/publish (quote "
                                                     "base) (quote [f]))"))
                                       w (until-settled w)
                                       w (load-at w (head-of w :p2))
                                       [w _] (eval-at w :r "(reset)")
                                       [w _] (require-at w :r 'app)
                                       body (last-response w :r)]
                                      (is (= [:yin.link.dht/dependency-binding :ambiguous]
                                             [(:reason body) (:binding body)]))
                                      (is
                                        (=
                                          (set [(:address base2) (:address own)])
                                          (set
                                            (:addresses body))))
                                      (is (= #{(sign/principal (:public k1)) (sign/principal
                                                                               (:public k2))}
                                             (set (:asserters body))))
                                      (close-all! w))))))))))
                    (finally
                      (run! cleanup-dir! dirs)))))))
