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
            [yin.repl.store :as store]))


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
