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
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.dht :as dht]
            [dao.jing.dht.mesh :as mesh]
            [dao.jing.mem :as mem]
            [dao.space.dht :as space.dht]
            [dao.stream :as stream]
            [dao.stream.datagram :as datagram]
            [yin.repl :as repl]
            [yin.repl.dht :as repl.dht]
            [yin.repl.driver :as driver]
            [yin.repl.main :as main]
            [yin.repl.store :as store]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.linker.closure-test :as ct]
            [yin.vm.linker.dht :as ld]
            [yin.vm.linker.publish :as publish]))


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
     :cljs (try (.rmSync (js/require "fs") dir #js {:recursive true :force true})
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
   10, … until `done?` holds over the collected lines or `limit` readings
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
  (let [spec #(:index-store-spec (main/parse-args (into ["--index-store" "dht:d"] %)))]
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
      (is (= 1024 (:max-inbound-bytes (spec ["--dht-max-inbound-bytes" "1024"]))))))
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
                         [["--index-store" "dht:d" "--dht-max-inbound-bytes" "-1"]
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
                    {:index-store-spec (dht-spec dir {:bind! (mesh-bind net 9 binds)
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
                                             :peers [{:host "127.0.0.1" :port 1}]
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
                                              (into ["--index-store" "dht:idx"] %))))]
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


(deftest a-partial-publication-is-reported-and-repaired-through-the-host-module
  (let [dir (temp-dir)
        net (mesh/mesh)
        peers {121 (peer-node net 121) 122 (peer-node net 122)}
        manifest (atom nil)
        refuse (atom (rows-refuser manifest))]
    (try
      (let [shell (repl/create-state
                    {:index-store-spec
                     (dht-spec dir {:bind! (refusing-bind net 120 refuse)
                                    :peers [{:host "127.0.0.1" :port 121}
                                            {:host "127.0.0.1" :port 122}]
                                    :publish? true})})
            [shell _] (repl/eval-input shell "(def partly 7)")
            m (get-in shell [:indexer :manifest-address])
            _ (reset! manifest m)
            [[shell] peers lines now] (run-until [shell] peers 0 20000
                                                 #(line-with % (str "published " m)))
            first-line (line-with lines (str "published " m))]
        (testing "the first report names the result, the failed count and the retry"
          (is (str/includes? first-line "PARTIAL") first-line)
          (is (re-find #"\d+ of \d+ blobs not sent" first-line) first-line)
          (is (str/includes? first-line "too few peers") first-line)
          (is (str/includes? first-line "retrying while the node is open") first-line)
          (is (= m (head-manifest dir)) "HEAD moved"))
        (reset! refuse (constantly false))
        (let [[shell required] (repl/eval-input shell "(require (quote dao.space.dht))")
              [shell retried] (repl/eval-input shell (str "(dao.space.dht/retry " m ")"))
              [[shell] _ lines] (run-until [shell] peers now (+ now 20000)
                                           #(line-with % (str "republished " m)))
              line (line-with lines (str "republished " m))]
          (is (= "'dao.space.dht" required))
          (is (= ":retrying" retried) "retry brings the repair forward")
          (is (str/includes? (str line) "acknowledged: sent to 2 peers") (pr-str lines))
          (testing "retry and cancel are refused for what is not live, as data"
            (let [[shell again] (repl/eval-input shell (str "(dao.space.dht/retry " m ")"))
                  [shell cancelled] (repl/eval-input shell (str "(dao.space.dht/cancel " m ")"))]
              (is (str/includes? again "not-repairing") again)
              (is (str/includes? cancelled "not-live") cancelled)
              (close! shell)))))
      (finally
        (cleanup-dir! dir)))))


(deftest a-refused-manifest-is-reported-unacknowledged-and-cancel-ends-it
  (let [dir (temp-dir)
        net (mesh/mesh)
        peers {124 (peer-node net 124) 125 (peer-node net 125)}
        refuse (atom (constantly false))]
    (try
      (let [shell (repl/create-state
                    {:index-store-spec
                     (dht-spec dir {:bind! (refusing-bind net 123 refuse)
                                    :peers [{:host "127.0.0.1" :port 124}
                                            {:host "127.0.0.1" :port 125}]
                                    :publish? true})})
            [shell _] (repl/eval-input shell "(def unsent 8)")
            m (get-in shell [:indexer :manifest-address])
            _ (reset! refuse #{m})
            [[shell] peers lines now] (run-until [shell] peers 0 20000
                                                 #(line-with % (str "published " m)))
            first-line (line-with lines (str "published " m))
            _ (is (= m (head-manifest dir)) "HEAD moved")
            [shell _] (repl/eval-input shell "(require (quote dao.space.dht))")
            [shell cancelled] (repl/eval-input shell (str "(dao.space.dht/cancel " m ")"))
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
                (str "q answers the publisher's fact, under its token: " answer))
            (is (not (str/includes? (str answer) (:shell-token reader)))
                "the reader itself never evaluated it")
            (close! reader))
          (close! pub)))
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
                                              :peers [{:host "127.0.0.1" :port 70}]
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
  (stream/append! deposit (datagram/bind-failed-event identity "address in use"))
  {:send! (fn [_ _ _] {:dao.stream/outcome :dao.stream/transport-error})
   :close! (fn [] nil)})


(deftest a-socket-that-cannot-bind-refuses-and-stops-the-shell
  (let [dir (temp-dir)]
    (try
      (let [state (main/boot {:index-store-spec
                              (dht-spec dir {:bind! failing-bind
                                             :peers [{:host "127.0.0.1" :port 1}]})})
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
                             reader (str "(dao.space.dht/load-index " manifest ")"))]
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
              (is (line-with lines (str "dht: loaded " manifest)) (pr-str lines))
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
        (if (or (some #(= :published (::space.dht/event %)) events) (> now 20000))
          {:holder node :peers peers :now (+ now 10) :manifest m}
          (recur node peers (+ now 10)))))))


(defn- reader-state
  "The step owner's state for a shell over a dht store at mesh `port`
   with bootstrap contacts `peer-ports` (none: solo), its name
   environment `names` -- direct addresses, as in slice L3."
  [net dir port peer-ports names]
  (-> (main/boot {:index-store-spec
                  (dht-spec dir {:bind! (mesh-bind net port (atom 0))
                                 :peers (mapv (fn [p] {:host "127.0.0.1" :port p})
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


(deftest a-require-a-peer-holds-parks-and-completes-on-a-later-tick-with-no-typed-line
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
            (is (line-with (:lines w) (str "dht: loaded " m)) (pr-str (:lines w))))
          (testing "the export then answers"
            (is (= "42" (second (type! (:state w) "(mod/f)")))))
          (testing "a repeated or late event for the answered link changes nothing"
            (let [late {::space.dht/event :loaded :manifest m
                        :kind ld/module-kind :fetched 1}
                  [repl' text'] (repl/recheck-on-load-events repl [late late])
                  [waiting _] (repl/eval-input repl "(require (quote gone))")
                  [waiting' text''] (repl/recheck-on-load-events waiting [late])]
              (is (identical? repl repl'))
              (is (nil? text'))
              (testing "nor while another require waits on its own load"
                (is (= [nowhere] (mapv :manifest (:links (:pending-run waiting)))))
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
                             #(-> % (ld/load-module other) (space.dht/load-index index)))
            [state text] (type! state "(require (quote mod))")
            before (parked state)
            w (reduce (fn [w _] (tick w)) {:state state :peers {} :now 0} (range 5))
            after (parked (:state w))]
        (is (str/includes? text "pending"))
        (testing "the node reported a publication, an index load and another module's load"
          (is (line-with (:lines w) "dht: published"))
          (is (line-with (:lines w) (str "dht: loaded " other)))
          (is (line-with (:lines w) (str "dht: loaded " index))))
        (testing "none of them re-checked the run"
          (is (= :loading (:status (ld/module-status (node-of (:state w)) nowhere))))
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
            repl (nth (iterate #(first (repl/recheck-pending %)) (:repl state)) 6)
            _ (is (= 6 (:checks (:pending-run repl))))
            w (run-ticks {:state (assoc state :repl repl) :peers {} :now 0} 20000
                         #(nil? (parked (:state %))))]
        (testing "only the load's own failure ended it, as a refusal"
          (is (nil? (parked (:state w))))
          (is (line-with (:lines w) "Module link refused: absent"))
          (is (not (line-with (:lines w) "session link policy"))))
        (close-world! w))
      (finally
        (cleanup-dir! dir)))))


(deftest abandon-during-a-load-then-a-new-require-finds-it-loaded
  (let [dir (temp-dir)
        net (mesh/mesh)
        w (holder-world net)
        m (:manifest w)]
    (try
      (let [state (reader-state net dir 4 [1 2 3] {'mod m})
            [state _] (type! state "(require (quote mod))")
            [state text] (type! state "(abandon)")
            _ (is (str/includes? text "abandoned"))
            _ (is (nil? (parked state)))
            w (run-ticks (assoc w :state state) 60000
                         #(= :loaded (:status (ld/module-status (node-of (:state %)) m))))
            state (:state w)]
        (testing "the load completed and settled nothing"
          (is (= :loaded (:status (ld/module-status (node-of state) m))))
          (is (nil? (parked state)))
          (is (nil? (get-in state [:repl :last-value])))
          (is (= [] (responses state)) "no answer was appended for the abandoned run"))
        (let [fetched (:fetched (ld/module-status (node-of state) m))
              [state _] (type! state "(require (quote mod))")
              repl (:repl state)]
          (testing "a new require of the name finds the closure loaded and links"
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


(deftest a-solo-node-s-require-fails-with-cause-solo-and-a-new-require-starts-a-new-load
  (let [dir (temp-dir)]
    (try
      (let [{:keys [state lines]} (fail-require (solo-state dir {'mod nowhere}) 'mod)]
        (testing "the node's next step failed it with /solo"
          (is (nil? (parked state)))
          (is (line-with lines "Module link refused: absent"))
          (is (= [{:status :refused :reason :absent :address nowhere
                   :cause ::dht/solo}]
                 (responses state))))
        (testing "the failed record was forgotten; a new require starts a new load"
          (is (nil? (ld/module-status (node-of state) nowhere)))
          (let [[state text] (type! state "(require (quote mod))")]
            (is (str/includes? text "pending"))
            (is (= :loading (:status (ld/module-status (node-of state) nowhere))))
            (main/close-index-store! state))))
      (finally
        (cleanup-dir! dir)))))


(deftest a-defective-closure-raises-descriptor-defect-with-its-code
  (let [dir (temp-dir)]
    (try
      (let [state (solo-state dir {})
            store (space.dht/local (node-of state))
            res (ct/publish-base! store)
            bad (jing/materialize! store (assoc (:manifest res) :yin.module/schema 2))
            {:keys [state lines]} (fail-require
                                    (assoc-in state [:repl :link-source :name-env]
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
                      (update-in [:repl :dht :client] assoc :requests (undeliverable)))
            {:keys [state lines]} (fail-require state 'mod)]
        (is (line-with lines "Module link refused: unaskable"))
        (is (= [{:status :refused :reason :yin.link.dht/unaskable :address nowhere
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
                                    (assoc-in state [:repl :link-source :name-env]
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
            (is (= 'app (get-in state [:repl :last-value])) (pr-str (:lines w)))
            (is (= :loaded (:status (ld/module-status (node-of state) base))))
            (is (= "43" (second (type! state "app/g"))))
            (main/close-index-store! state))))
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
                      (testing "the register kernel asks for R and no fallback is composed"
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
                                   [state _] (type! state (str "(vm " vm-type ")"))
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
