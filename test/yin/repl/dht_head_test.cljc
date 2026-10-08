(ns yin.repl.dht-head-test
  "The published head trace in the REPL (docs/design/
   yin.vm.linker.dht.head.md, slice H3), in process: a publisher shell
   deposits its HEAD at every move and serves its board, printing the
   join token once the board is bound; a reader shell follows it, parks
   a first-contact require until the first head is installed, writes
   `heads.edn` before it installs, prints the moved line, and restarts
   from `heads.edn` before any connection.

   The DHT is the dao.jing.dht test mesh and the WebSocket is an
   in-process loopback net standing in for the host's `listen!` and
   `connect!` seams (as in yin.vm.linker.head-board-test), pumped by the
   test, so every host runs the same composition.  Real processes over
   real sockets are yin.repl.dht-process-test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            #?@(:cljd [["dart:io" :as dart-io]])
            [dao.jing.dht.mesh :as mesh]
            [dao.space.store :as durable]
            [dao.space.store.fs :as fs]
            [dao.stream :as stream]
            [dao.stream.loopback-net :as net]
            [yin.repl :as repl]
            [yin.repl.dht :as repl.dht]
            [yin.repl.main :as main]
            [yin.vm.linker.dht :as ld]
            [yin.vm.linker.head :as head]
            [yin.vm.linker.sign :as sign])
  #?@(:cljd [(:import ["dart:typed_data" Uint8List])]))


;; =============================================================================
;; The in-process loopback net (dao.stream.loopback-net)
;; =============================================================================

(defn- ws-host
  "The host WebSocket seam over `net`, as yin.repl.host answers it: an
   unbind closes what the listener accepted and deposits `:stopped`."
  [lnet]
  {:bind! (net/listen-on lnet)
   :connect! (net/connect-on lnet)
   :unbind! (fn [listener _]
              (net/unlisten! lnet (:port listener)))})


;; =============================================================================
;; Host helpers
;; =============================================================================

(defn- temp-dir
  []
  (str "target/test-dht-head-" (random-uuid)))


(defn- refusal-of
  [thunk]
  (try (thunk) nil
       (catch #?(:cljd Object :clj Exception :cljs :default) e e)))


;; =============================================================================
;; Shells and a world
;; =============================================================================

(def ^:private pub-port 11)
(def ^:private reader-port 12)


(defn- quick
  "The follower's delays shortened to the test's cadence."
  [state]
  (if (get-in state [:repl :dht ::repl.dht/follower])
    (update-in state [:repl :dht ::repl.dht/follower]
               assoc :poll-ticks 50 :repair-ticks 200 :repair-max-ticks 800)
    state))


(defn- shell
  "The step owner's state of a shell over `dir` at mesh `port`."
  [{:keys [mesh ws dir port peers key principals follow write! bind-host
           bind!]}]
  (quick
    (main/boot (cond-> {:index-store-spec
                        (cond-> {:type :dht
                                 :dir dir
                                 :bind! (or bind! (mesh/seam mesh port (atom 0)))
                                 :publish? (some? key)
                                 :peers (mapv (fn [p] {:host "127.0.0.1" :port p})
                                              peers)}
                          bind-host (assoc :bind-host bind-host)
                          (seq follow) (assoc :follow follow))
                        :dht-key key
                        :principals principals
                        :ws-host (when ws (ws-host ws))}
                 write! (assoc :write-heads! write!)))))


(defn- follow-of
  [key]
  [{:principal (:public key) :host "127.0.0.1" :port pub-port}])


(defn- publisher
  [w key dir]
  (shell {:mesh (:mesh w) :ws (:ws w) :dir dir :port pub-port
          :peers [reader-port] :key key :principals []}))


(defn- reader
  ([w key dir] (reader w key dir {}))
  ([w key dir opts]
   (shell (merge {:mesh (:mesh w) :ws (:ws w) :dir dir :port reader-port
                  :peers [pub-port] :principals [(:public key)]
                  :follow (follow-of key)}
                 opts))))


(defn- world
  "A world whose clock advances `:dt` ms per tick (10 by default)."
  ([] (world {}))
  ([{:keys [dt] :or {dt 10}}]
   {:mesh (mesh/mesh) :ws (net/loopback-net) :now 0 :dt dt
    :lines {:a [] :b [] :c []}}))


(defn- tick
  [w]
  (net/pump! (:ws w))
  (let [now (:now w)
        step (fn [w k]
               (if-some [s (get w k)]
                 (let [[s _ lines] (main/step-all s nil now)]
                   (net/pump! (:ws w))
                   (-> w (assoc k s) (update-in [:lines k] into lines)))
                 w))]
    (-> w (step :a) (step :b) (step :c) (assoc :now (+ now (:dt w))))))


(defn- run
  [w limit done?]
  (loop [w w left limit]
    (if (or (done? w) (zero? left)) w (recur (tick w) (dec left)))))


(defn- type!
  "Evaluate `line` at shell `k`: the world, its text appended to `k`'s
   lines."
  [w k line]
  (let [[repl text] (repl/eval-input (get-in w [k :repl]) line)]
    (-> w (assoc-in [k :repl] repl) (update-in [:lines k] conj text))))


(defn- value
  [w k]
  (get-in w [k :repl :last-value]))


(defn- heads-of
  [w k key]
  (get (head/heads (get-in w [k :repl :dht ::repl.dht/follower]))
       (sign/principal (:public key))))


(defn- installed?
  [k key]
  (fn [w] (some? (:manifest (heads-of w k key)))))


(defn- lines-with
  [w k text]
  (filterv #(str/includes? (str %) text) (get-in w [:lines k])))


(defn- pending?
  [w k]
  (some? (get-in w [k :repl :pending-run])))


(defn- close!
  [w]
  (doseq [k [:a :b :c]]
    (when-some [s (get w k)] (main/close-index-store! s))))


(defn- publish!
  "At A: define f as `n` and publish it as alib."
  [w n]
  (-> w
      (type! :a (str "(def f (fn [] " n "))"))
      (type! :a "(require (quote yin.link))")
      (type! :a "(yin.link/publish (quote alib) (quote [f]))")))


;; =============================================================================
;; The token, the board and the deposit
;; =============================================================================

(deftest the-token-prints-once-when-the-board-is-bound
  (let [key (sign/generate)
        w (world)
        w (assoc w :a (publisher w key (temp-dir)))
        w (run w 20 (fn [_] false))
        tokens (lines-with w :a "dht: join token: ")]
    (is (= [(str "dht: join token: yin:127.0.0.1:" pub-port "/" (:public key))]
           tokens)
        (pr-str (get-in w [:lines :a])))
    (testing "the token parses back to the peer and the principal"
      (is (= [(str "127.0.0.1:" pub-port) (:public key)]
             (main/parse-token (subs (first tokens)
                                     (count "dht: join token: "))))))
    (testing "every HEAD move deposits its trace on the board"
      (let [w (publish! w 1)
            board (get-in w [:a :repl :dht ::repl.dht/publisher :board])
            c (stream/cursor board :dao.stream/oldest)
            trace (:dao.stream/value (stream/next board (:dao.stream/cursor c)))]
        (is (head/verify trace))
        (is (= (get-in w [:a :repl :indexer :manifest-address])
               (get-in trace [:yin.head/envelope :yin.head/manifest]))
            "the board names HEAD")))
    (close! w)))


(deftest no-board-means-no-token
  (testing "a TCP port already in use: no board, no token, the port named"
    (let [key (sign/generate)
          w (world)
          _ ((net/listen-on (:ws w)) {:bind-host "127.0.0.1" :bind-port pub-port
                                      :deposit! (fn [& _] nil)})
          w (assoc w :a (publisher w key (temp-dir)))
          w (run w 20 (fn [_] false))]
      (is (empty? (lines-with w :a "dht: join token:")))
      (is (seq (lines-with w :a (str "could not bind TCP 127.0.0.1:" pub-port)))
          (pr-str (get-in w [:lines :a])))
      (is (seq (lines-with w :a "dht: node ")) "the node keeps running")
      (close! w)))
  (testing "a bind host that is not a loopback literal serves no board"
    (let [key (sign/generate)
          w (world)
          w (assoc w :a (shell {:mesh (:mesh w) :ws (:ws w) :dir (temp-dir)
                                :port pub-port :peers [reader-port] :key key
                                :principals [] :bind-host "10.0.0.5"}))
          w (run w 20 (fn [_] false))]
      (is (empty? (lines-with w :a "join token")))
      (is (seq (lines-with w :a "served on loopback only"))
          (pr-str (get-in w [:lines :a])))
      (is (empty? (:listeners @(:ws w))) "nothing listened")
      (close! w)))
  (testing "a node without a key deposits nothing and serves no board"
    (let [w (world)
          w (assoc w :a (shell {:mesh (:mesh w) :ws (:ws w) :dir (temp-dir)
                                :port pub-port :peers [reader-port]
                                :principals []}))
          w (run w 20 (fn [_] false))]
      (is (nil? (get-in w [:a :repl :dht ::repl.dht/publisher])))
      (is (empty? (lines-with w :a "join token")))
      (close! w))))


;; =============================================================================
;; Following: first contact, the moved line, (reset), and a bare restart
;; =============================================================================

(deftest a-reader-follows-requires-moves-and-restarts-from-heads-edn
  (let [key (sign/generate)
        principal (sign/principal (:public key))
        [dir-a dir-b] [(temp-dir) (temp-dir)]
        w (world)
        w (assoc w :a (publisher w key dir-a) :b (reader w key dir-b))
        w (type! w :b "(require (quote alib))")]
    (testing "a require typed before the first head parks pending on it"
      (is (pending? w :b) (pr-str (get-in w [:lines :b])))
      (is (= [principal]
             (mapcat :principals (get-in w [:b :repl :pending-run :links]))))
      (is (seq (lines-with w :b ";; require pending: alib"))))
    (let [w (publish! w 42)
          w (run w 3000 #(and (not (pending? % :b)) ((installed? :b key) %)))
          m1 (:manifest (heads-of w :b key))]
      (testing "the install completes it with no typed line"
        (is (some? m1) (pr-str (get-in w [:lines :b])))
        (is (not (pending? w :b)))
        (is (= 'alib (value w :b)) (pr-str (get-in w [:lines :b])))
        (is (seq (lines-with w :b (str "dht: installed the head of "
                                       principal))))
        (is (= [["127.0.0.1" pub-port]] (distinct (:dialed @(:ws w))))
            "only the address the principal was given is dialed"))
      (testing "the head is durable before it is used"
        (is (= m1 (get-in (repl.dht/read-heads dir-b)
                          [:records :heads principal :yin.head/envelope
                           :yin.head/manifest]))))
      (let [w (type! w :b "(alib/f)")
            _ (is (= 42 (value w :b)))
            w (type! w :b "(require (quote yin.head))")
            w (type! w :b "(yin.head/heads)")]
        (testing "(yin.head/heads) answers what is installed"
          (is (= m1 (get-in (value w :b) [principal :manifest]))
              (pr-str (value w :b))))
        (let [w (publish! w 43)
              w (run w 3000 #(not= m1 (:manifest (heads-of % :b key))))
              m2 (:manifest (heads-of w :b key))
              moved (lines-with w :b "dht: alib moved: linked ")]
          (testing "an installed head that moves a linked name prints one line"
            (is (and m2 (not= m1 m2)) (pr-str (get-in w [:lines :b])))
            (is (= 1 (count moved)) (pr-str (get-in w [:lines :b])))
            (is (str/includes? (str (first moved)) principal))
            (is (str/includes? (str (first moved))
                               "(reset) then (require 'alib) links it")))
          (let [w (type! w :b "(yin.head/moved)")
                [moved-entry] (value w :b)
                w (type! w :b "(require (quote alib))")
                w (type! w :b "(alib/f)")]
            (testing "(yin.head/moved) names the moved name"
              (is (= 'alib (:name moved-entry)) (pr-str (value w :b)))
              (is (not= (:linked moved-entry) (:resolved moved-entry))))
            (testing "the repeat require is unchanged"
              (is (= 42 (value w :b))))
            (let [w (type! w :b "(reset)")
                  w (type! w :b "(require (quote alib))")
                  w (run w 3000 #(not (pending? % :b)))
                  w (type! w :b "(alib/f)")]
              (testing "(reset) then require links the new module"
                (is (= 43 (value w :b)) (pr-str (get-in w [:lines :b])))))
            (testing "a bare restart resolves from heads.edn before any
                      connection, with the publisher stopped"
              (close! w)
              (let [w2 (world)
                    b (shell {:mesh (:mesh w2) :ws nil :dir dir-b
                              :port reader-port :peers [pub-port]
                              :principals [(:public key)]
                              :follow (follow-of key)})
                    node (get-in b [:repl :dht])]
                (is (= m2 (get (ld/installed-heads node) principal))
                    "installed at open, before any step")
                (is (= :ok (:status (ld/resolve-name
                                      node
                                      (ld/authority {:principals [(:public key)]})
                                      'alib))))
                (let [w2 (-> (assoc w2 :b b)
                             (type! :b "(require (quote alib))")
                             (run 3000 #(not (pending? % :b)))
                             (type! :b "(alib/f)"))]
                  (is (= 43 (value w2 :b)) (pr-str (get-in w2 [:lines :b])))
                  (close! w2))))))))))


(deftest abandon-ends-a-first-contact-require
  (let [key (sign/generate)
        w (world)
        w (assoc w :b (reader w key (temp-dir)))
        w (type! w :b "(require (quote alib))")
        w (run w 50 (fn [_] false))]
    (is (pending? w :b) "no publisher: the require waits on the first head")
    (let [w (type! w :b "(abandon)")]
      (is (not (pending? w :b)))
      (is (nil? (:pending (repl/repl-state (get-in w [:b :repl])))))
      (close! w))))


;; =============================================================================
;; The write precedes the install (5.7)
;; =============================================================================

(defn- failing-write
  "A `heads.edn` write seam: `(mode)` answers :fail (throw, nothing
   written), :write-then-fail (written, then a throw: a crash between the
   write and the install), or :ok."
  [mode]
  (fn [dir name text]
    (case (mode)
      :fail (throw (ex-info "disk full" {}))
      :write-then-fail (do (fs/atomic-replace! dir name text)
                           (throw (ex-info "killed after the write" {})))
      (fs/atomic-replace! dir name text))))


(deftest the-write-precedes-the-install
  (let [key (sign/generate)
        [dir-a dir-b] [(temp-dir) (temp-dir)]
        mode (atom :fail)
        w (world)
        w (assoc w
                 :a (publisher w key dir-a)
                 :b (reader w key dir-b {:write! (failing-write #(deref mode))}))
        w (publish! w 1)
        w (run w 3000 #(seq (lines-with % :b ":yin.head/unpersisted")))]
    (testing "a write that fails prints :yin.head/unpersisted and installs
              nothing"
      (is (seq (lines-with w :b ":yin.head/unpersisted (disk full)"))
          (pr-str (get-in w [:lines :b])))
      (is (seq (lines-with w :b "again in 200 ticks")) "the delay is in ticks")
      (is (nil? (:manifest (heads-of w :b key))))
      (is (= :confirmed (get-in (heads-of w :b key) [:candidate :state])))
      (is (empty? (ld/installed-heads (get-in w [:b :repl :dht])))
          "nothing reads a confirmed head before the write"))
    (reset! mode :ok)
    (let [w (run w 3000 (installed? :b key))]
      (testing "and installs after the retry"
        (is (some? (:manifest (heads-of w :b key)))
            (pr-str (get-in w [:lines :b]))))
      (close! w))))


(defn- crash-before-rename
  "The real `heads.edn` write, interrupted in the one window a crash can
   hit: after the temp file is synced, before the rename.  It leaves a
   stale temp file beside `heads.edn`."
  [dir name text]
  (fs/atomic-replace! dir name text
                      {:before-rename (fn [_] (throw (ex-info "crashed before the rename" {})))}))


(defn- dir-names
  [dir]
  #?(:cljd (set (map #(last (str/split (.-path %) #"/"))
                     (.listSync (dart-io/Directory. dir))))
     :clj (set (map #(.getName ^java.io.File %)
                    (.listFiles (java.io.File. ^String dir))))
     :cljs (set (js->clj (.readdirSync (js/require "fs") dir)))))


(deftest a-simulated-crash-restarts-at-the-head-heads-edn-holds
  ;; SIMULATED, by the Architect's ruling for H3: no OS process is killed.
  ;; The crash is a write seam that fails, then the shell is closed and
  ;; opened again from the same directory.  What a real kill adds is the
  ;; atomicity of `dao.space.store.fs/atomic-replace!`, which is that
  ;; function's own contract.
  (let [key (sign/generate)
        principal (sign/principal (:public key))
        [dir-a dir-b] [(temp-dir) (temp-dir)]
        mode (atom :ok)
        written #(get-in (repl.dht/read-heads dir-b)
                         [:records :heads principal :yin.head/envelope
                          :yin.head/manifest])
        w (world)
        w (assoc w
                 :a (publisher w key dir-a)
                 :b (reader w key dir-b {:write! (failing-write #(deref mode))}))
        w (run (publish! w 1) 3000 (installed? :b key))
        m1 (:manifest (heads-of w :b key))
        heads-text #(fs/read-file-text dir-b repl.dht/heads-file)]
    (is (= m1 (written)))
    (testing "simulated crash before the write: the restart is at the old
              head"
      (reset! mode :fail)
      (let [w (publish! w 2)
            w (run w 3000 #(seq (lines-with % :b ":yin.head/unpersisted")))
            confirmed (get-in (heads-of w :b key) [:candidate :manifest])
            _ (close! w)
            b (reader w key dir-b)]
        (is (and confirmed (not= m1 confirmed)))
        (is (= m1 (get (ld/installed-heads (get-in b [:repl :dht])) principal)))
        (main/close-index-store! b)))
    (testing "a real write interrupted before its rename leaves the old
              heads.edn bytes untouched and a stale temp file, which startup
              ignores and the next write replaces past"
      (let [before (heads-text)
            w (world)
            w (assoc w
                     :a (publisher w key dir-a)
                     :b (reader w key dir-b {:write! crash-before-rename}))
            w (publish! w 3)
            w (run w 3000 #(seq (lines-with % :b "crashed before the rename")))
            stale (filterv #(str/starts-with? % (str repl.dht/heads-file ".tmp-"))
                           (dir-names dir-b))]
        (is (seq (lines-with w :b "crashed before the rename"))
            (pr-str (get-in w [:lines :b])))
        (is (= before (heads-text)) "the old bytes, exactly")
        (is (seq stale) "the interrupted write left its temp file")
        (close! w)
        (let [w (world)
              w (assoc w
                       :a (publisher w key dir-a)
                       :b (reader w key dir-b))]
          (is (= m1 (get (ld/installed-heads (get-in w [:b :repl :dht]))
                         principal))
              "startup reads heads.edn and ignores the temp file")
          (let [newest (get-in w [:a :repl :indexer :manifest-address])
                w (run w 3000 #(= newest (:manifest (heads-of % :b key))))]
            (is (= newest (written)) "the next write replaces heads.edn")
            (close! w)))))
    (testing "simulated crash between the write and the install: the restart
              is at the new head"
      (reset! mode :write-then-fail)
      (let [w (world)
            w (assoc w
                     :a (publisher w key dir-a)
                     :b (reader w key dir-b {:write! (failing-write #(deref mode))}))
            w (publish! w 4)
            newest (get-in w [:a :repl :indexer :manifest-address])
            w (run w 3000 (fn [_] (= newest (written))))
            _ (is (= newest (written)) (pr-str (get-in w [:lines :b])))
            _ (is (seq (lines-with w :b "killed after the write")))
            _ (is (not= newest (:manifest (heads-of w :b key)))
                  "not installed in this process")
            _ (close! w)
            b (reader w key dir-b)]
        (is (= newest (get (ld/installed-heads (get-in b [:repl :dht])) principal))
            "the restart is at the head the file holds")
        (main/close-index-store! b)))))


;; =============================================================================
;; heads.edn (5.7): absent, malformed, a record that fails verification, a
;; record for an unfollowed principal
;; =============================================================================

(defn- close-failure
  []
  #?(:cljd (Exception. "close failed")
     :clj (java.io.IOException. "close failed")
     :cljs (js/Error. "close failed")))


(defn- recording-bind
  "A mesh socket seam that counts its binds and its closes in `counts`;
   with `throw?` each close releases the socket and then throws."
  ([counts] (recording-bind counts false))
  ([counts throw?]
   (let [seam (mesh/seam (mesh/mesh) reader-port (atom 0))]
     (fn [opts]
       (swap! counts update :bound (fnil inc 0))
       (update (seam opts) :close!
               (fn [close!]
                 (fn []
                   (swap! counts update :closed (fnil inc 0))
                   (close!)
                   (when throw? (throw (close-failure))))))))))


(defn- recording-store
  "The `:open-store` seam: the durable directory store, counting its
   closes in `counts`; with `throw?` each close releases the store and
   then throws."
  [counts throw?]
  (fn [dir]
    (let [s (durable/open dir)]
      (assoc s :close-fn (fn []
                           (swap! counts update :store-closed (fnil inc 0))
                           ((:close-fn s))
                           (when throw? (throw (close-failure))))))))


(defn- open-reader
  ([dir key] (open-reader dir key (atom {})))
  ([dir key counts]
   (shell {:mesh (mesh/mesh) :ws nil :dir dir :port reader-port
           :peers [pub-port] :principals [(:public key)]
           :follow (follow-of key) :bind! (recording-bind counts)})))


(defn- write-heads!
  [dir text]
  (fs/atomic-replace! dir repl.dht/heads-file text))


(defn- mkdirs!
  [dir]
  #?(:cljd (.createSync (dart-io/Directory. dir) .recursive true)
     :clj (.mkdirs (java.io.File. ^String dir))
     :cljs (.mkdirSync (js/require "fs") dir #js {:recursive true})))


(deftest heads-edn-cases-are-refusals-as-data
  (let [key (sign/generate)
        other (sign/generate)
        principal (sign/principal (:public key))
        manifest (keyword (str "segment/blake3-" (apply str (repeat 64 "a"))))
        good (head/trace key manifest 7)
        record (fn [p t] (repl.dht/render-heads {:version 1 :heads {p t}}))]
    (testing "absent: no head, and the shell opens"
      (let [dir (temp-dir)
            b (open-reader dir key)]
        (is (= {:records {:version 1 :heads {}}} (repl.dht/read-heads dir)))
        (is (nil? (:manifest (get (head/heads (get-in b [:repl :dht
                                                         ::repl.dht/follower]))
                                  principal))))
        (main/close-index-store! b)))
    (doseq [[label text why]
            [["malformed EDN" "{:version 1 :heads" "not readable EDN"]
             ["a trailing form" "{:version 1 :heads {}} garbage"
              "not readable EDN"]
             ["a truncated second form" "{:version 1 :heads {}} {:broken"
              "not readable EDN"]
             ["an empty file" "" "not readable EDN"]
             ["another version" "{:version 2 :heads {}}" "not a version-1"]
             ["no heads map" "{:version 1 :heads []}" "not a version-1"]
             ["a record that fails verification"
              (record principal (assoc-in good [:yin.head/proof
                                                :yin.head/signature]
                                          (apply str (repeat 128 "0"))))
              "does not verify"]
             ["a record for an unfollowed principal"
              (record (sign/principal (:public other))
                      (head/trace other manifest 7))
              "is recorded but not followed"]
             ["a record whose index is not local" (record principal good)
              "is not in the local store"]]]
      (testing label
        (let [dir (temp-dir)]
          (mkdirs! dir)
          (write-heads! dir text)
          (is (= (contains? #{"malformed EDN" "another version" "no heads map"
                              "a trailing form" "a truncated second form"
                              "an empty file"}
                            label)
                 (contains? (repl.dht/read-heads dir) :refusal))
              "the file is read as data, never a throw; its records are
               verified by the follower")
          (let [counts (atom {})
                e (refusal-of #(open-reader dir key counts))]
            (is (some? (ex-data e)) "a designed refusal, never a raw error")
            (is (str/includes? (str (ex-message e)) why) (ex-message e))
            (is (str/includes? (str (ex-message e)) repl.dht/heads-file))
            (is (= (:bound @counts 0) (:closed @counts 0))
                (str "no socket outlives the refusal: " (pr-str @counts))))
          (testing "the refusal releases the directory"
            (write-heads! dir (repl.dht/render-heads {:version 1 :heads {}}))
            (let [b (open-reader dir key)]
              (is (some? b))
              (main/close-index-store! b))))))))


(deftest heads-edn-renders-with-no-space-before-a-closer
  (let [key (sign/generate)
        manifest (keyword (str "segment/blake3-" (apply str (repeat 64 "c"))))
        records {:version 1
                 :heads {(sign/principal (:public key))
                         (head/trace key manifest 4294967297)}}
        text (repl.dht/render-heads records)]
    (is (not (re-find #"\s[\]\}\)]" text)))
    (is (= {:records records} (repl.dht/parse-heads "heads.edn" text)))))


;; =============================================================================
;; The yin.head host module holds no rule
;; =============================================================================

(deftest the-host-module-answers-with-no-follower
  (let [w (world)
        w (assoc w :b (shell {:mesh (:mesh w) :ws nil :dir (temp-dir)
                              :port reader-port :peers [] :principals []}))
        w (-> w
              (type! :b "(require (quote yin.head))")
              (type! :b "(yin.head/heads)"))]
    (is (= {} (value w :b)) "a node that follows nothing has no heads")
    (let [w (type! w :b "(yin.head/moved)")]
      (is (= [] (value w :b))))
    (close! w)))


#?(:cljd nil
   :clj
   (deftest the-host-module-holds-no-fold-verification-or-file-write
     (let [text (slurp "src/cljc/yin/repl/query.cljc")]
       (doseq [word ["dao.space.store.fs" "yin.vm.linker.sign"
                     "yin.vm.linker.authority" "head/verify" "head/judge"
                     "head/install" "head/step" "atomic-replace!"
                     "name-environment"]]
         (is (not (str/includes? text word)) word)))))


;; =============================================================================
;; The channel owns the dial's liveness (H2 sign-off notes 1 to 3, S3a-2)
;; =============================================================================

(deftest a-lost-or-silent-dial-is-closed-and-composed-again
  (testing "nothing listens: each dial is lost and the next follows the
            doubling delay, at the given address only"
    (let [key (sign/generate)
          w (world)
          w (assoc w :b (reader w key (temp-dir)))
          w (run w 70 (fn [_] false))
          dialed (:dialed @(:ws w))]
      (is (<= 2 (count dialed) 3) (pr-str dialed))
      (is (= [["127.0.0.1" pub-port]] (distinct dialed)))
      (close! w)))
  (testing "a board that accepts and never answers: the resolve expires on
            the link after give-up-after as channel-gone, the dial is
            closed, then a fresh one is composed"
    (let [key (sign/generate)
          w (world {:dt 1000})
          _ (swap! (:ws w) assoc-in [:listeners pub-port]
                   {:accept! (fn [& _] {}) :deposit! (fn [& _] nil)})
          w (assoc w :b (reader w key (temp-dir)))
          w (run w 70 (fn [_] false))
          conns (:conns @(:ws w))
          failures (lines-with w :b "dht: cannot follow ")]
      (is (<= 2 (count conns)) (pr-str (:dialed @(:ws w))))
      (is (every? #(deref (:closed? %)) (butlast conns))
          "every dial but the newest was closed before the next")
      (is (= 1 (count failures)) (pr-str (get-in w [:lines :b])))
      (is (str/includes? (str (first failures))
                         "the connection was refused, closed, or stopped answering"))
      (close! w))))


(deftest close-leaves-no-listener-and-no-dial
  (let [key (sign/generate)
        w (world)
        w (assoc w
                 :a (publisher w key (temp-dir))
                 :b (reader w key (temp-dir)))
        w (run w 100 #(= :attached (get-in % [:b :repl :dht
                                              ::repl.dht/follow :links
                                              (sign/principal (:public key))
                                              :dial :status])))]
    (is (seq (:listeners @(:ws w))))
    (is (some #(not @(:closed? %)) (:conns @(:ws w))) "a live connection")
    (close! w)
    (net/pump! (:ws w))
    (is (empty? (:listeners @(:ws w))) "no listener")
    (is (every? #(deref (:closed? %)) (:conns @(:ws w)))
        "every connection closed")))


(defn- attached-pair
  "A publisher at :a with its board bound and a reader at :b attached to
   it."
  [key]
  (let [w (world)
        w (assoc w
                 :a (publisher w key (temp-dir))
                 :b (reader w key (temp-dir)))]
    (run w 100 #(= :attached (get-in % [:b :repl :dht ::repl.dht/follow :links
                                        (sign/principal (:public key))
                                        :dial :status])))))


(deftest the-board-exit-is-driver-paced
  (let [key (sign/generate)
        principal (sign/principal (:public key))
        w (attached-pair key)
        _ (is (seq (:listeners @(:ws w))) "the board is bound")
        w (update-in w [:a :repl] repl.dht/stop!)
        _ (is (false? (repl.dht/stopped? (:repl (:a w))))
              "stop! initiates; the board still owes its release")
        ;; the reader reports the loss once, by its dial or its follower
        losses (fn [w]
                 (+ (count (lines-with w :b (str "lost the head board of " principal)))
                    (count (lines-with w :b (str "cannot follow " principal)))))
        lost-before (losses w)
        w (run w 5 #(repl.dht/stopped? (:repl (:a %))))]
    (is (true? (repl.dht/stopped? (:repl (:a w)))) (pr-str (get-in w [:lines :a])))
    (is (empty? (:listeners @(:ws w))) "no listener")
    (is (= ["dht: the head board stopped"]
           (lines-with w :a "dht: the head board stopped"))
        (pr-str (get-in w [:lines :a])))
    (let [w (tick w)]
      (is (< lost-before (losses w)) (pr-str (get-in w [:lines :b])))
      (is (empty? (get-in w [:a :repl :dht ::repl.dht/follow :links]))
          "the stopping node composes no dial")
      (close! w))))


(deftest close-is-the-last-resort-and-idempotent
  (testing "a shell never stopped: close! closes the dials and the board"
    (let [w (attached-pair (sign/generate))]
      (is (nil? (repl.dht/close! (:repl (:a w)))))
      (is (nil? (repl.dht/close! (:repl (:b w)))))
      (net/pump! (:ws w))
      (is (empty? (:listeners @(:ws w))) "nothing listens")
      (is (every? #(deref (:closed? %)) (:conns @(:ws w))) "every connection closed")
      (is (nil? (repl.dht/close! (:repl (:a w)))) "close! again does not throw")
      (close! w)))
  (testing "a shell already stopped: close! runs no stopping step"
    (let [w (attached-pair (sign/generate))
          w (update-in w [:a :repl] repl.dht/stop!)
          w (run w 5 #(repl.dht/stopped? (:repl (:a %))))
          _ (is (true? (repl.dht/stopped? (:repl (:a w)))))
          unbinds (atom 0)
          w (assoc-in w [:a :repl :dht ::repl.dht/publisher :server :host :unbind!]
                      (fn [& _] (swap! unbinds inc) nil))]
      (is (nil? (repl.dht/close! (:repl (:a w)))))
      (is (zero? @unbinds) "a stopped board is not unbound again")
      (close! w))))


;; =============================================================================
;; heads.edn is read bounded and strictly UTF-8
;; =============================================================================

(defn- ascii-codes
  [text]
  (mapv #?(:cljd #(.codeUnitAt text %)
           :clj #(int (.charAt ^String text %))
           :cljs #(.charCodeAt text %))
        (range (count text))))


(defn- write-bytes!
  [path codes]
  #?(:cljd (.writeAsBytesSync (dart-io/File. path) (Uint8List.fromList codes))
     :clj (java.nio.file.Files/write
            (java.nio.file.Paths/get path (make-array String 0))
            ^bytes (byte-array (map unchecked-byte codes))
            ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))
     :cljs (.writeFileSync (js/require "fs") path (js/Buffer.from (clj->js codes)))))


(deftest heads-edn-is-bounded-and-strict-utf-8
  (let [key (sign/generate)
        empty-text (repl.dht/render-heads {:version 1 :heads {}})]
    (testing "an invalid byte, even inside a comment, is refused"
      (let [dir (temp-dir)
            counts (atom {})]
        (mkdirs! dir)
        (write-bytes! (str dir "/" repl.dht/heads-file)
                      (-> (ascii-codes empty-text)
                          (into (ascii-codes "; "))
                          (conj 0xff 10)))
        (is (re-find #"not valid UTF-8"
                     (str (:refusal (repl.dht/read-heads dir)))))
        (let [e (refusal-of #(open-reader dir key counts))]
          (is (re-find #"not valid UTF-8" (str (ex-message e))))
          (is (= (:bound @counts 0) (:closed @counts 0))))))
    (testing "a file larger than the bound is refused, by size"
      (let [dir (temp-dir)]
        (mkdirs! dir)
        (write-heads! dir (str empty-text ";"
                               (apply str (repeat repl.dht/heads-max-bytes "x"))
                               "\n"))
        (is (re-find #"holds more than"
                     (str (:refusal (repl.dht/read-heads dir)))))))
    (testing "a leading UTF-8 byte order mark is ignored, on every host"
      (let [dir (temp-dir)]
        (mkdirs! dir)
        (write-bytes! (str dir "/" repl.dht/heads-file)
                      (into [0xef 0xbb 0xbf] (ascii-codes empty-text)))
        (is (= {:records {:version 1 :heads {}}} (repl.dht/read-heads dir)))))
    (testing "every leading byte order mark is ignored, on every host"
      (doseq [n [2 3]]
        (let [dir (temp-dir)]
          (mkdirs! dir)
          (write-bytes! (str dir "/" repl.dht/heads-file)
                        (into (vec (mapcat identity (repeat n [0xef 0xbb 0xbf])))
                              (ascii-codes empty-text)))
          (is (= {:records {:version 1 :heads {}}} (repl.dht/read-heads dir))
              (str n " marks")))))
    (testing "a byte order mark past the leading run is refused, on every
              host: between tokens, before a closer, inside a string"
      (let [bom [0xef 0xbb 0xbf]]
        (doseq [codes [(-> (ascii-codes "{:version 1 ") (into bom)
                           (into (ascii-codes ":heads {}}")))
                       (-> (ascii-codes "{:version 1 :heads {}") (into bom)
                           (into (ascii-codes "}")))
                       (-> (ascii-codes "{:version 1 :heads {} :x \"a")
                           (into bom) (into (ascii-codes "\"}")))
                       (-> bom (into (ascii-codes empty-text)) (into bom))]]
          (let [dir (temp-dir)]
            (mkdirs! dir)
            (write-bytes! (str dir "/" repl.dht/heads-file) codes)
            (is (re-find #"not valid: a byte order mark \(U\+FEFF\) follows its start"
                         (str (:refusal (repl.dht/read-heads dir))))
                (pr-str codes))))))
    (testing "a file of marks is scanned once, in bounded time"
      (let [now #?(:cljd #(.-millisecondsSinceEpoch (DateTime/now))
                   :clj #(System/currentTimeMillis)
                   :cljs #(.getTime (js/Date.)))
            marks (fn [n] (into [] (comp (take n) cat) (repeat [0xef 0xbb 0xbf])))]
        (doseq [[label codes ok?]
                [["only marks, up to the bound" (marks 349525) false]
                 ["marks, then the record" (into (marks 349000)
                                                 (ascii-codes empty-text))
                  true]]]
          (let [dir (temp-dir)]
            (mkdirs! dir)
            (write-bytes! (str dir "/" repl.dht/heads-file) codes)
            (let [start (now)
                  r (repl.dht/read-heads dir)
                  took (- (now) start)]
              (is (= ok? (= {:records {:version 1 :heads {}}} r))
                  (str label ": " (pr-str (:refusal r))))
              (is (< took 3000) (str label ": " took " ms")))))))
    (testing "an overlong encoding and an encoded surrogate stay refused"
      (doseq [bad [[0xc0 0xaf] [0xed 0xa0 0x80]]]
        (let [dir (temp-dir)]
          (mkdirs! dir)
          (write-bytes! (str dir "/" repl.dht/heads-file)
                        (-> (ascii-codes empty-text)
                            (into (ascii-codes "; "))
                            (into bad)
                            (conj 10)))
          (is (re-find #"not valid UTF-8"
                       (str (:refusal (repl.dht/read-heads dir))))
              (pr-str bad)))))
    (testing "a record with comments and whitespace around it still reads"
      (let [dir (temp-dir)]
        (mkdirs! dir)
        (write-heads! dir (str "; the heads\n" empty-text "  ; trailing\n\n"))
        (is (= {:records {:version 1 :heads {}}} (repl.dht/read-heads dir)))))))


;; =============================================================================
;; A refusal ring that overflows says so and stays visible
;; =============================================================================

(deftest deposit-refusals-past-the-ring-are-reported-lost-then-stay-visible
  (let [key (sign/generate)
        w (world)
        w (run (assoc w :a (publisher w key (temp-dir))) 2 (fn [_] false))
        notes (get-in w [:a :repl :dht ::repl.dht/publisher :notes])
        note! (fn [i]
                (stream/append! notes {:status :refused
                                       :reason :yin.head/test
                                       :manifest (str "m" i)}))
        step! (fn [w now]
                (let [[repl lines] (repl.dht/step (get-in w [:a :repl]) now)]
                  [(assoc-in w [:a :repl] repl) lines]))]
    (run! note! (range 65))
    (let [[w lines] (step! w 100)
          reported (filterv #(str/includes? % "was not deposited") lines)]
      (is (some #(str/includes? % "the oldest were not reported") lines)
          (pr-str lines))
      (is (= 64 (count reported)) "every retained refusal is drained")
      (is (str/includes? (str (last reported)) "m64"))
      (note! 65)
      (let [[_ lines] (step! w 110)]
        (is (= ["dht: the head trace of m65 was not deposited: :yin.head/test"]
               lines)
            "a later refusal is reported")))
    (close! w)))


(defn- board-trace
  "The trace on the publisher's board now, or nil."
  [w]
  (let [board (get-in w [:a :repl :dht ::repl.dht/publisher :board])
        c (stream/cursor board :dao.stream/oldest)]
    (when (= :dao.stream/ok (:dao.stream/outcome c))
      (:dao.stream/value (stream/next board (:dao.stream/cursor c))))))


(deftest a-refused-deposit-is-reported-once-and-fails-nothing
  ;; The refusal is made real by the board itself: a trace this process
  ;; did not sign stands on it at a sequence above the next HEAD's, so
  ;; `yin.vm.linker.head/deposit!` refuses that HEAD :yin.head/seq-regression.
  (let [key (sign/generate)
        dir (temp-dir)
        w (world)
        w (run (assoc w :a (publisher w key dir)) 2 (fn [_] false))
        w (publish! w 1)
        t1 (board-trace w)
        s1 (get-in t1 [:yin.head/envelope :yin.head/seq])
        board (get-in w [:a :repl :dht ::repl.dht/publisher :board])
        forged (keyword (str "segment/blake3-" (apply str (repeat 64 "f"))))
        _ (stream/append! board (head/trace key forged (+ s1 1000)))
        ;; one evaluation, one HEAD move
        w (type! w :a "(def g (fn [] 2))")
        m2 (get-in w [:a :repl :indexer :manifest-address])
        answer (last (get-in w [:lines :a]))
        w (run w 20 (fn [_] false))
        refused (lines-with w :a "was not deposited")]
    (testing "the refusal is reported once, with its manifest and reason"
      (is (= [(str "dht: the head trace of " m2 " was not deposited: "
                   ":yin.head/seq-regression")]
             refused)
          (pr-str (get-in w [:lines :a]))))
    (testing "the HEAD write did not fail"
      (is (= "{:type :closure}" answer) "the evaluation answered")
      (is (str/includes? (str (fs/read-file-text dir "HEAD")) (str m2)))
      (is (= forged (get-in (board-trace w) [:yin.head/envelope
                                             :yin.head/manifest]))
          "a refusal appends nothing"))
    (testing "a later deposit, once the board admits it, is made"
      (stream/append! board t1)
      (let [w (run (publish! w 3) 20 (fn [_] false))
            m3 (get-in w [:a :repl :indexer :manifest-address])]
        (is (not= m2 m3))
        (is (= m3 (get-in (board-trace w) [:yin.head/envelope
                                           :yin.head/manifest])))
        (is (= 1 (count (lines-with w :a "was not deposited")))
            "nothing more is reported")
        (close! w)))))


;; =============================================================================
;; The moved line names only new moves; a dial failure prints once
;; =============================================================================

(def ^:private second-pub-port 13)


(defn- moved-by-installs
  "A reader of two publishers, A (`key`, alib from `a-code`) and C
   (`other`, the module `c-name` from `c-code`), that linked both names;
   then both republish (`a-code'`, `c-code'`) and the reader installs
   both heads.  Answers the moved lines `moved-lines` derives for those
   two installs taken as one step: the reader's node before them, its
   node after, and both `:installed` events."
  [key other c-name [a-code a-code'] [c-code c-code']]
  (let [p1 (sign/principal (:public key))
        p2 (sign/principal (:public other))
        w (world)
        w (assoc w
                 :a (publisher w key (temp-dir))
                 :c (shell {:mesh (:mesh w) :ws (:ws w) :dir (temp-dir)
                            :port second-pub-port :peers [reader-port]
                            :key other :principals []})
                 :b (reader w key (temp-dir)
                            {:peers [pub-port second-pub-port]
                             :principals [(:public key) (:public other)]
                             :follow [{:principal (:public key)
                                       :host "127.0.0.1" :port pub-port}
                                      {:principal (:public other)
                                       :host "127.0.0.1"
                                       :port second-pub-port}]}))
        publish (fn [w k n code]
                  (-> w
                      (type! k (str "(def f (fn [] " code "))"))
                      (type! k "(require (quote yin.link))")
                      (type! k (str "(yin.link/publish (quote " n
                                    ") (quote [f]))"))))
        newest #(get-in %1 [%2 :repl :indexer :manifest-address])
        caught-up #(and (= (newest % :a) (:manifest (heads-of % :b key)))
                        (= (newest % :c) (:manifest (heads-of % :b other))))
        w (-> w
              (publish :a "alib" a-code)
              (publish :c c-name c-code)
              (run 3000 caught-up)
              (type! :b "(require (quote alib))")
              (type! :b (str "(require (quote " c-name "))"))
              (run 3000 #(not (pending? % :b))))
        _ (is (= (symbol c-name) (value w :b)) (pr-str (get-in w [:lines :b])))
        before (get-in w [:b :repl :dht])
        w (-> w
              (publish :a "alib" a-code')
              (publish :c c-name c-code')
              (run 3000 caught-up))
        _ (is (caught-up w) (pr-str (get-in w [:lines :b])))
        installed [{:yin.head/event :installed :principal p1
                    :trace (:installed (heads-of w :b key))}
                   {:yin.head/event :installed :principal p2
                    :trace (:installed (heads-of w :b other))}]
        lines (repl.dht/moved-lines (get-in w [:b :repl]) before
                                    (get-in w [:b :repl :dht]) installed)]
    (close! w)
    lines))


(deftest the-moved-line-names-the-principal-that-moved-each-name
  (let [key (sign/generate)
        other (sign/generate)
        p1 (sign/principal (:public key))
        p2 (sign/principal (:public other))
        head-of (fn [line] (re-find #"\(head of [^)]*\)" (str line)))]
    (testing "two principals installed in one step, each moving its own name"
      (let [lines (moved-by-installs key other "blib" [1 2] [10 20])
            line-of (fn [n]
                      (first (filter #(str/starts-with?
                                        % (str "dht: " n " moved: "))
                                     lines)))]
        (is (= 2 (count lines)) (pr-str lines))
        (is (str/includes? (str (head-of (line-of "alib"))) p1) (pr-str lines))
        (is (not (str/includes? (str (head-of (line-of "alib"))) p2))
            (pr-str lines))
        (is (str/includes? (str (head-of (line-of "blib"))) p2) (pr-str lines))
        (is (not (str/includes? (str (head-of (line-of "blib"))) p1))
            (pr-str lines))))
    (testing "two principals installed in one step, moving the same name
              together: both are named"
      (let [lines (moved-by-installs key other "alib" [1 2] [1 2])]
        (is (= 1 (count lines)) (pr-str lines))
        (is (str/includes? (str (head-of (first lines))) p1) (pr-str lines))
        (is (str/includes? (str (head-of (first lines))) p2)
            (pr-str lines))))))


(deftest a-move-is-reported-once-and-each-name-its-own
  (let [key (sign/generate)
        w (world)
        w (assoc w :a (publisher w key (temp-dir)) :b (reader w key (temp-dir)))
        w (-> w
              (type! :a "(def f (fn [] 1))")
              (type! :a "(def g (fn [] 1))")
              (type! :a "(require (quote yin.link))")
              (type! :a "(yin.link/publish (quote alib) (quote [f]))")
              (type! :a "(yin.link/publish (quote blib) (quote [g]))"))
        newest #(get-in % [:a :repl :indexer :manifest-address])
        caught-up #(= (newest %) (:manifest (heads-of % :b key)))
        w (run w 3000 caught-up)
        w (-> w
              (type! :b "(require (quote alib))")
              (type! :b "(require (quote blib))")
              (run 3000 #(not (pending? % :b))))
        moved (fn [w n] (lines-with w :b (str "dht: " n " moved: ")))]
    (is (= 'blib (value w :b)) (pr-str (get-in w [:lines :b])))
    (let [w (-> w
                (type! :a "(def f (fn [] 2))")
                (type! :a "(yin.link/publish (quote alib) (quote [f]))")
                (run 3000 caught-up))
          _ (is (= 1 (count (moved w "alib"))) (pr-str (get-in w [:lines :b])))
          w (-> w (type! :a "(def unrelated 3)") (run 3000 caught-up))
          _ (is (= 1 (count (moved w "alib")))
                "a later install that leaves alib where it moved prints nothing")
          w (-> w
                (type! :a "(def g (fn [] 2))")
                (type! :a "(yin.link/publish (quote blib) (quote [g]))")
                (run 3000 caught-up))]
      (is (= 1 (count (moved w "blib"))) (pr-str (get-in w [:lines :b])))
      (is (= 1 (count (moved w "alib"))) "alib is not printed again")
      (close! w))))


(deftest a-dial-failure-prints-once-per-change
  (testing "nothing listens: many dials, one line"
    (let [key (sign/generate)
          w (world)
          w (run (assoc w :b (reader w key (temp-dir))) 200 (fn [_] false))
          failures (lines-with w :b "dht: cannot follow ")]
      (is (<= 3 (count (:dialed @(:ws w))))
          (pr-str (get-in w [:b :repl :dht ::repl.dht/follow :links])))
      (is (= 1 (count failures)) (pr-str (get-in w [:lines :b])))
      (is (str/includes? (str (first failures)) (str "127.0.0.1:" pub-port)))
      (close! w)))
  (testing "a board of another principal answers not-found: said once"
    (let [key (sign/generate)
          other (sign/generate)
          w (world)
          w (assoc w :a (publisher w other (temp-dir)) :b (reader w key (temp-dir)))
          w (run w 200 (fn [_] false))
          failures (lines-with w :b "dht: cannot follow ")]
      (is (<= 2 (count (:dialed @(:ws w)))))
      (is (= 1 (count failures)) (pr-str (get-in w [:lines :b])))
      (is (str/includes? (str (first failures))
                         "serves no head board for this principal"))
      (close! w))))


;; =============================================================================
;; heads.edn must be a regular file; a failed cleanup never masks a refusal
;; =============================================================================

(defn- symlink!
  "A symlink at `path` to `target`, relative to the link's directory."
  [path target]
  #?(:cljd (.createSync (dart-io/Link. path) target)
     :clj (java.nio.file.Files/createSymbolicLink
            (java.nio.file.Paths/get path (make-array String 0))
            (java.nio.file.Paths/get target (make-array String 0))
            (make-array java.nio.file.attribute.FileAttribute 0))
     :cljs (.symlinkSync (js/require "fs") target path)))


(deftest heads-edn-must-be-a-regular-file
  (let [empty-text (repl.dht/render-heads {:version 1 :heads {}})
        path #(str % "/" repl.dht/heads-file)]
    (testing "a directory is refused, by name"
      (let [dir (temp-dir)]
        (mkdirs! (path dir))
        (is (re-find #"not a regular file"
                     (str (:refusal (repl.dht/read-heads dir)))))))
    (testing "a symlink to a regular file is followed"
      (let [dir (temp-dir)]
        (mkdirs! dir)
        (fs/atomic-replace! dir "real.edn" empty-text)
        (symlink! (path dir) "real.edn")
        (is (= {:records {:version 1 :heads {}}} (repl.dht/read-heads dir)))))
    (testing "a dangling symlink is refused, never read as absence"
      (let [dir (temp-dir)]
        (mkdirs! dir)
        (symlink! (path dir) "missing.edn")
        (is (re-find #"not a regular file"
                     (str (:refusal (repl.dht/read-heads dir)))))))))


(deftest a-failed-cleanup-never-masks-the-refusal
  (let [key (sign/generate)
        other (sign/generate)
        manifest (keyword (str "segment/blake3-" (apply str (repeat 64 "a"))))
        unfollowed (repl.dht/render-heads
                     {:version 1
                      :heads {(sign/principal (:public other))
                              (head/trace other manifest 7)}})
        open! (fn [dir counts socket-throws? store-throws?]
                (repl.dht/open {:type :dht :dir dir
                                :bind! (recording-bind counts socket-throws?)
                                :peers [{:host "127.0.0.1" :port pub-port}]
                                :follow (follow-of key)}
                               {:open-store (recording-store counts
                                                             store-throws?)}))]
    (doseq [[label text why socket? store? bound]
            [["after the join, the socket's close throws" unfollowed
              "is recorded but not followed" true false 1]
             ["after the join, the store's close throws" unfollowed
              "is recorded but not followed" false true 1]
             ["after the join, both closes throw" unfollowed
              "is recorded but not followed" true true 1]
             ["before the join, the store's close throws" "{:version 1"
              "not readable EDN" false true 0]]]
      (testing label
        (let [dir (temp-dir)
              counts (atom {})]
          (mkdirs! dir)
          (write-heads! dir text)
          (let [e (refusal-of #(open! dir counts socket? store?))]
            (is (some? (ex-data e)) (str "the refusal itself, not " e))
            (is (str/includes? (str (ex-message e)) why) (ex-message e)))
          (is (= {:bound bound :closed bound :store-closed 1}
                 (merge {:bound 0 :closed 0} @counts))
              "each resource closed exactly once")
          (testing "nothing leaks: the directory opens again"
            (write-heads! dir (repl.dht/render-heads {:version 1 :heads {}}))
            (let [handle (open! dir (atom {}) false false)]
              (is (some? (:dht handle)))
              (durable/close! handle))))))))


#?(:cljd nil
   :clj
   (deftest ^:slow a-fifo-at-heads-edn-is-refused-not-waited-on
     ;; In a child JVM, bounded: a regression blocks the child, not this
     ;; test, and fails at the deadline.
     (let [dir (temp-dir)
           fifo (str dir "/" repl.dht/heads-file)
           _ (mkdirs! dir)
           made (.waitFor (.start (ProcessBuilder. ^java.util.List ["mkfifo" fifo])))
           child (.start (doto (ProcessBuilder.
                                 ^java.util.List
                                 [(str (System/getProperty "java.home") "/bin/java")
                                  "-cp" (System/getProperty "java.class.path")
                                  "clojure.main" "-e"
                                  (str "(require 'yin.repl.dht)"
                                       "(prn (yin.repl.dht/read-heads " (pr-str dir) "))"
                                       "(shutdown-agents)")])
                           (.redirectErrorStream true)))
           done? (.waitFor child 240 java.util.concurrent.TimeUnit/SECONDS)
           out (if done?
                 (slurp (.getInputStream child))
                 (do (.destroyForcibly child) "the child blocked"))]
       (is (zero? made) "mkfifo made the FIFO")
       (is done? "the read did not block on the FIFO")
       (is (str/includes? out "it is not a regular file") out))))
