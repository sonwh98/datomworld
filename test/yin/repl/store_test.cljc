(ns yin.repl.store-test
  "The index store's startup selection and durable lifecycle
   (yin.repl.store): the default is the in-memory store today's shell
   uses, `file:<dir>` opens `<dir>/content.jing` under an exclusive
   directory lock, publishes each round's manifest address to a versioned
   `<dir>/HEAD` through an atomic temp/sync/rename replacement, and
   refuses every invalid spec or corrupt durable state — a missing value,
   an unknown scheme, an empty or unopenable directory, a host without
   file support, a handle and a spec together, a malformed HEAD, a
   missing manifest, an unreadable index node — with its reason, never
   answered with a silent memory fallback."
  (:require #?@(:cljd [["dart:core" :as dart-core] ["dart:io" :as dart-io]
                       ["dart:typed_data" :as typed]
                       [clojure.edn :as edn]])
            #?(:cljd nil
               :clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            [clojure.set :as cset]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.jing.file :as dao.jing.file]
            [dao.jing.mem :as jing.mem]
            [dao.space.index :as dao.index]
            [dao.space.transactor :as transactor]
            [dao.stream :as dao.stream]
            [dao.stream.memory-log :as memory-log]
            [dao.space.store :as durable]
            [yin.repl :as repl]
            [yin.repl.frontends :as repl.frontends]
            [yin.repl.store :as store]
            [dao.space.store.fs :as fs]))


;; =============================================================================
;; Host helpers — a scratch directory, a scratch file, and their cleanup.
;; These are the only host-aware lines in the suite.
;; =============================================================================

(defn- temp-dir
  []
  (str "target/test-index-store-" (random-uuid)))


(defn- temp-file
  []
  (str "target/test-index-blocker-" (random-uuid) ".tmp"))


(defn- write-file!
  [path]
  #?(:cljd (let [f (dart-io/File. path)]
             (when-not (.existsSync (.-parent f))
               (.createSync (.-parent f) .recursive true))
             (.writeAsStringSync f ""))
     :clj (do (.mkdirs (.getParentFile (java.io.File. path)))
              (spit path ""))
     :cljs (let [fs (js/require "fs")
                 path-module (js/require "path")]
             (.mkdirSync fs (.dirname path-module path) #js {:recursive true})
             (.writeFileSync fs path ""))))


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


(defn- cleanup-file!
  [path]
  #?(:cljd (try (let [f (dart-io/File. path)]
                  (when (.existsSync f) (.deleteSync f)))
                (catch Object _ nil))
     :clj (let [f (java.io.File. path)]
            (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") path)
                (catch :default _ nil))))


(defn- refusal-of
  "The error `thunk` throws, or nil when it does not.  The error itself,
   not its message: on the shadow-cljs test build, `(str (this-helper …))`
   at an assertion site is compile-time evaluated and its constant
   embedded, so a refusal's message would never be checked at runtime —
   `(ex-message (refusal-of …))`, with the helper one level down, is the
   shape that runs."
  [thunk]
  (try (thunk)
       nil
       (catch #?(:cljd Object
                 :clj Exception
                 :cljs :default)
              e
         e)))


(defn- read-text!
  "The file at `path` as text, or nil when absent."
  [path]
  #?(:cljd (let [f (dart-io/File. path)]
             (when (.existsSync f) (.readAsStringSync f)))
     :clj (let [f (java.io.File. path)]
            (when (.exists f) (slurp f)))
     :cljs (let [fs (js/require "fs")]
             (when (.existsSync fs path)
               (.readFileSync fs path "utf8")))))


(defn- read-edn
  [text]
  #?(:cljd (edn/read-string text)
     :clj (edn/read-string text)
     :cljs (reader/read-string text)))


(defn- head-path
  [dir]
  (str dir "/HEAD"))


(defn- head-record-of
  "The parsed HEAD record of `dir`, or nil when absent."
  [dir]
  (some-> (read-text! (head-path dir)) read-edn))


;; =============================================================================
;; Raw content-log surgery — the framed [4-byte length][frame] records of
;; content.jing, read and rewritten as plain bytes per host, so a corrupt
;; index can be fabricated the way a crash or a rotting disk makes it.
;; =============================================================================

(defn- read-bytes!
  [path]
  #?(:cljd (.readAsBytesSync (dart-io/File. path))
     :clj (java.nio.file.Files/readAllBytes
            (.toPath (java.io.File. path)))
     :cljs (.readFileSync (js/require "fs") path)))


(defn- write-bytes!
  [path bs]
  #?(:cljd (.writeAsBytesSync (dart-io/File. path) bs)
     :clj (with-open [out (java.io.FileOutputStream. ^String path)]
            (.write out ^bytes bs))
     :cljs (.writeFileSync (js/require "fs") path bs)))


(defn- byte-count
  [bs]
  #?(:cljd (.-length bs)
     :clj (alength ^bytes bs)
     :cljs (.-length bs)))


(defn- byte-at
  [bs i]
  #?(:cljd (nth bs i)
     :clj (bit-and 0xFF (aget ^bytes bs i))
     :cljs (aget bs i)))


(defn- int32-at
  "The big-endian frame length at `offset`."
  [bs offset]
  (loop [i 0 acc 0]
    (if (= i 4)
      acc
      (recur (inc i)
             (bit-or (bit-shift-left acc 8) (byte-at bs (+ offset i)))))))


(defn- frame-starts
  "Every frame's start offset of the content log's bytes."
  [bs]
  (loop [offset 0 starts []]
    (if (>= offset (byte-count bs))
      starts
      (recur (+ offset 4 (int32-at bs offset)) (conj starts offset)))))


(defn- splice-out!
  "Rewrite the log's bytes without the range `[cut from)`."
  [path bs cut from]
  (let [head-part #?(:cljd (typed/Uint8List.sublistView bs 0 cut)
                     :clj (java.util.Arrays/copyOfRange ^bytes bs 0 cut)
                     :cljs (.subarray ^js bs 0 cut))
        tail-part #?(:cljd (typed/Uint8List.sublistView bs from)
                     :clj (java.util.Arrays/copyOfRange
                            ^bytes bs from (alength ^bytes bs))
                     :cljs (.subarray ^js bs from))]
    (write-bytes!
      path
      #?(:cljd (typed/Uint8List.fromList
                 (concat (vec head-part) (vec tail-part)))
         :clj (let [out (byte-array (+ (alength ^bytes head-part)
                                       (alength ^bytes tail-part)))]
                (System/arraycopy ^bytes head-part 0 out 0
                                  (alength ^bytes head-part))
                (System/arraycopy ^bytes tail-part 0 out
                                  (alength ^bytes head-part)
                                  (alength ^bytes tail-part))
                out)
         :cljs (.concat js/Buffer #js [head-part tail-part])))))


(defn- drop-frame-with-address!
  "Remove the frame holding `address`, leaving every remaining frame
   valid: a manifest that still names a node the log no longer holds —
   exactly the unreadable-index-node corruption the open must refuse on."
  [path address]
  (let [entries (dao.jing.file/records path)
        index (first (keep-indexed (fn [i [a _]] (when (= a address) i))
                                   entries))
        _ (when (nil? index)
            (throw (ex-info "address is not in the content log"
                            {:address address})))
        bs (read-bytes! path)
        starts (frame-starts bs)
        cut (nth starts index)
        from (if (< (inc index) (count starts))
               (nth starts (inc index))
               (byte-count bs))]
    (splice-out! path bs cut from)))


(defn- corrupt-payload-byte!
  "Flip one payload byte inside the first frame — a byte-rotten content
   log whose digest no longer matches, which the frame validation at open
   must refuse."
  [path]
  (let [bs (read-bytes! path)
        flip #?(:cljd (fn [i] (. bs "[]=" i (bit-xor 0xFF (nth bs i))))
                :clj (fn [i]
                       (aset ^bytes bs i (unchecked-byte
                                           (bit-xor 0xFF (aget ^bytes bs i)))))
                :cljs (fn [i] (aset bs i (bit-xor 0xFF (aget bs i)))))]
    ;; byte 4 is the first payload byte of the first frame
    (flip 4)
    (write-bytes! path bs)))


(defn- write-text!
  [path text]
  #?(:cljd (let [f (dart-io/File. path)]
             (when-not (.existsSync (.-parent f))
               (.createSync (.-parent f) .recursive true))
             (.writeAsStringSync f text))
     :clj (do (.mkdirs (.getParentFile (java.io.File. path)))
              (spit path text))
     :cljs (let [fs (js/require "fs")
                 path-module (js/require "path")]
             (.mkdirSync fs (.dirname path-module path) #js {:recursive true})
             (.writeFileSync fs path text))))


(defn- absent-address
  "A well-formed content address no store holds: the blake3 shape with a
   digest of all zeros."
  []
  (keyword "segment" (str "blake3-" (apply str (repeat 64 "0")))))


(defn- hex-altered
  "A manifest address with one digest digit changed: still a well-formed
   content address, absent from any store."
  [address]
  (let [[algo digest] (str/split (name address) #"-" 2)
        swaps {"0" "1", "1" "0", "2" "3", "3" "2", "4" "5", "5" "4",
               "6" "7", "7" "6", "8" "9", "9" "8",
               "a" "b", "b" "a", "c" "d", "d" "c", "e" "f", "f" "e"}
        c (some #(when (str/includes? digest (str %)) (str %))
                "0123456789abcdef")
        digest' (str/replace-first digest (re-pattern c) (get swaps c))]
    (keyword (namespace address) (str algo "-" digest'))))


(defn- stream-values
  "Every value currently on a stream, read from its oldest cursor."
  [s]
  (loop [cursor (:dao.stream/cursor (dao.stream/cursor s :dao.stream/oldest))
         acc []]
    (let [r (dao.stream/next s cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- transactions
  "The transaction records the shell's indexer committed."
  [state]
  (mapv :dao.space/transaction (stream-values (get-in state [:indexer :local]))))


;; =============================================================================
;; The spec itself
;; =============================================================================

(deftest the-flag-value-parses-to-a-spec-or-refuses-its-reason
  (is (= :mem (store/parse-arg "mem")))
  (is (= {:type :file :dir "idx"} (store/parse-arg "file:idx")))
  (testing "a missing value, an unknown scheme, and an empty dir are refused"
    (is (str/includes? (ex-message (refusal-of #(store/parse-arg nil)))
                       "--index-store needs a value"))
    (is (str/includes? (ex-message (refusal-of #(store/parse-arg "redis://x")))
                       "Unknown --index-store"))
    (is (str/includes? (ex-message (refusal-of #(store/parse-arg "file:")))
                       "needs a directory"))))


(deftest an-embedder-spec-is-checked-the-same-way
  (is (= :mem (store/checked-spec nil)))
  (is (= :mem (store/checked-spec :mem)))
  (is (= {:type :file :dir "idx"}
         (store/checked-spec {:type :file :dir "idx"})))
  (is (str/includes? (ex-message (refusal-of #(store/checked-spec :redis)))
                     "Unknown :index-store-spec"))
  (is (str/includes? (ex-message (refusal-of
                                   #(store/checked-spec {:type :file :dir ""})))
                     "Unknown :index-store-spec")
      "an empty directory is not a file spec"))


;; =============================================================================
;; Opening
;; =============================================================================

(deftest the-default-is-the-in-memory-store-of-today
  (let [state (repl.frontends/create-state)]
    (is (= :mem (:index-store-spec state)))
    (is (fn? (:put-bytes-fn (:index-store state))))
    (is (fn? (:get-bytes-fn (:index-store state))))
    (is (fn? (:close-fn (:index-store state))))))


(deftest a-host-without-file-support-is-refused-not-fallen-back
  (is (durable/host-file-support)
      "the JVM, Node, and Dart lanes this suite runs on can all open files")
  (testing "the refusal names the host, whatever the directory"
    (is (str/includes? (str (durable/file-refusal false "/any/dir"))
                       "not supported on this host")))
  (let [dir (temp-dir)]
    (try
      (is (nil? (durable/file-refusal true dir))
          "a capable host is refused only by the directory itself")
      (finally
        (cleanup-dir! dir)))))


(deftest an-unopenable-directory-is-refused-with-its-reason
  (let [blocker (temp-file)]
    (try
      (write-file! blocker)
      (is (str/includes? (ex-message (refusal-of #(store/open
                                                    {:type :file :dir blocker})))
                         "not a directory"))
      (is (str/includes? (ex-message (refusal-of
                                       #(repl.frontends/create-state
                                          {:index-store-spec
                                           {:type :file :dir blocker}})))
                         "not a directory")
          "and the shell's construction refuses it too, before it composes")
      (finally
        (cleanup-file! blocker)))))


(deftest a-handle-and-a-spec-together-are-refused
  (is (str/includes? (ex-message (refusal-of #(repl.frontends/create-state
                                                {:index-store
                                                 (jing.mem/create-content-mem)
                                                 :index-store-spec :mem})))
                     "not both"))
  (testing "either alone is accepted"
    (let [injected (jing.mem/create-content-mem)]
      (is (identical? injected (:index-store (repl.frontends/create-state
                                               {:index-store injected}))))
      (is (nil? (:index-store-spec (repl.frontends/create-state
                                     {:index-store injected})))))))


;; =============================================================================
;; The durable store receives what the memory store receives
;; =============================================================================

(deftest a-file-store-opens-content-jing-and-receives-publications
  (let [dir (temp-dir)]
    (try
      (let [[state text] (repl/eval-input
                           (repl.frontends/create-state
                             {:index-store-spec {:type :file :dir dir}})
                           "(+ 1 2)")
            manifest (get-in state [:indexer :manifest-address])]
        (is (= "3" text) "the shell evaluates as it does on memory")
        (is (= {:type :file :dir dir} (:index-store-spec state)))
        (is (some? manifest) "the round published")
        (testing "the publication is on disk, at <dir>/content.jing"
          (let [on-disk (dao.jing.file/records (durable/content-path dir))]
            (is (pos? (count on-disk)))
            (is (contains? (into #{} (map first) on-disk) manifest)
                "the manifest blob is one of the file's records")))
        (testing "the file store answers the covered datoms back"
          (let [committed (into #{} (mapcat :datoms) (transactions state))]
            (is (= committed
                   (set (dao.index/read-datoms (:index-store state) manifest))))))
        (testing "the opened store outlives a session rebuild"
          (let [[fresh _] (repl/eval-input state "(reset)")]
            (is (identical? (:index-store state) (:index-store fresh)))
            (is (= (:index-store-spec state) (:index-store-spec fresh))))))
      (finally
        (cleanup-dir! dir)))))


;; =============================================================================
;; The durable lifecycle: lock, HEAD, and recovery — slice 2
;; =============================================================================

(defn- durable-shell!
  "A shell over a durable store freshly opened on a scratch directory."
  [dir]
  (repl.frontends/create-state {:index-store-spec {:type :file :dir dir}}))


(deftest a-durable-store-carries-its-lock-head-and-recovery
  (let [dir (temp-dir)]
    (try
      (let [store (store/open {:type :file :dir dir})]
        (is (fn? (:head-fn store)) "the HEAD writer travels with the handle")
        (is (= dir (:durable-dir store)))
        (is (= {:manifest nil :datoms nil} (:recovery store))
            "an absent HEAD is an empty index, not a refusal")
        (is (= {:manifest nil :datoms nil}
               (:index-recovery (repl.frontends/create-state {:index-store store})))
            "the shell exposes the recovery for the rehydration slice")
        (store/close! store))
      (testing "the memory store carries none of it and writes no HEAD"
        (let [mem-state (repl/eval-input
                          (repl.frontends/create-state
                            {:index-store (jing.mem/create-content-mem)})
                          "(+ 1 2)")]
          (is (nil? (:head-fn (:index-store (first mem-state)))))
          (is (= {:manifest nil :datoms nil}
                 (:index-recovery (first mem-state))))
          (is (nil? (read-text! (head-path dir))))))
      (finally
        (cleanup-dir! dir)))))


(deftest a-second-owner-of-the-directory-is-refused-and-the-first-is-unaffected
  (let [dir (temp-dir)]
    (try
      (let [owner (store/open {:type :file :dir dir})]
        (testing "a second open of the same directory refuses, naming it"
          (let [refusal (refusal-of #(store/open {:type :file :dir dir}))]
            (is (str/includes? (ex-message refusal) dir))
            (is (str/includes? (ex-message refusal) "locked"))))
        (testing "the first owner keeps working"
          (let [[state text] (repl/eval-input
                               (repl.frontends/create-state {:index-store owner})
                               "(+ 1 2)")]
            (is (= "3" text))
            (is (= {:version 1
                    :manifest (get-in state [:indexer :manifest-address])}
                   (head-record-of dir)))))
        (testing "the lock is released on close, and only then"
          (store/close! owner)
          (let [reopened (store/open {:type :file :dir dir})]
            (is (fn? (:head-fn reopened)))
            (store/close! reopened))))
      (finally
        (cleanup-dir! dir)))))


#?(:cljd nil
   :clj
   (deftest a-real-second-jvm-process-is-refused-and-its-death-releases
     (let [dir (temp-dir)]
       (try
         (let [command ["java" "-cp" (System/getProperty "java.class.path")
                        "clojure.main" "-e"
                        (str "(require '[yin.repl.store :as s])"
                             "(s/open {:type :file :dir " (pr-str dir) "})"
                             "(println :locked)"
                             "(Thread/sleep 120000)")]
               child (.start (ProcessBuilder. ^java.util.List command))
               lines (atom [])
               _pump (doto (Thread.
                             (fn []
                               (try
                                 (with-open [rdr (java.io.BufferedReader.
                                                   (java.io.InputStreamReader.
                                                     (.getInputStream ^Process child)))]
                                   (loop []
                                     (when-let [line (.readLine rdr)]
                                       (swap! lines conj line)
                                       (recur))))
                                 (catch Exception _ nil))))
                       (.setDaemon true)
                       (.start))
               deadline (+ (System/currentTimeMillis) 60000)]
           (while (and (not (some #(= ":locked" %) @lines))
                       (< (System/currentTimeMillis) deadline))
             (Thread/sleep 20))
           (is (contains? (set @lines) ":locked")
               "the child never acquired the durable directory")
           (let [refusal (refusal-of #(store/open {:type :file :dir dir}))]
             (is (str/includes? (ex-message refusal) dir))
             (is (str/includes? (ex-message refusal) "locked")))
           (.destroy ^Process child)
           (.waitFor ^Process child)
           (testing "the owner's death releases the lock: no stale refusal"
             (let [after (store/open {:type :file :dir dir})]
               (is (fn? (:head-fn after)))
               (store/close! after))))
         (finally
           (cleanup-dir! dir))))))


#?(:cljd nil
   :cljs
   (defn- claim-files
     "The Node claim entries in `dir`: `lock.<pid>.<nonce>`."
     [dir]
     (set (filter #(str/starts-with? % (str fs/lock-name "."))
                  (.readdirSync (js/require "fs") dir)))))


#?(:cljd nil
   :cljs
   (defn- dead-pid
     "The pid of a process that has already exited and been reaped."
     []
     (.-pid (.spawnSync (js/require "child_process") "true"))))


#?(:cljd nil
   :cljs
   (deftest a-live-foreign-owner-is-refused-and-a-dead-ones-lock-is-replaced
     (let [dir (temp-dir)
           node-fs (js/require "fs")]
       (try
         (store/close! (store/open {:type :file :dir dir}))
         (is (empty? (claim-files dir)) "close withdraws the claim")
         (testing "a claim by another live process refuses, naming the dir"
           (let [owner (str fs/lock-name "." (.-ppid js/process) ".0")]
             (.writeFileSync node-fs (str dir "/" owner) "")
             (let [refusal (refusal-of #(store/open {:type :file :dir dir}))]
               (is (str/includes? (str (ex-message refusal)) dir))
               (is (str/includes? (str (ex-message refusal)) "locked"))
               (is (str/includes? (str (ex-message refusal)) owner)
                   "the refusal names the claim an operator would remove
                    were its pid a reused one"))
             (is (= #{owner} (claim-files dir))
                 "the live owner's claim is left as it was; the refused
                  contender withdrew its own")
             (.unlinkSync node-fs (str dir "/" owner))))
         (testing "a crashed owner's claim is removed, never obeyed"
           (let [crashed (str fs/lock-name "." (dead-pid) ".0")]
             (.writeFileSync node-fs (str dir "/" crashed) "")
             (let [opened (store/open {:type :file :dir dir})
                   claims (claim-files dir)]
               (is (= 1 (count claims)))
               (is (str/starts-with? (first claims)
                                     (str fs/lock-name "." (.-pid js/process) "."))
                   "the one claim left is this process's own")
               (store/close! opened)
               (is (empty? (claim-files dir)) "close releases the lock"))))
         (finally
           (cleanup-dir! dir))))))


#?(:cljd nil
   :cljs
   (deftest a-concurrent-takeover-of-a-dead-owners-lock-leaves-one-owner
     (let [dir (temp-dir)
           node-fs (js/require "fs")
           crashed (str fs/lock-name "." (dead-pid) ".0")
           rival (str fs/lock-name "." (.-ppid js/process) ".1")]
       (try
         (store/close! (store/open {:type :file :dir dir}))
         (.writeFileSync node-fs (str dir "/" crashed) "")
         (let [outcome (try
                         {:lock (fs/lock!
                                  dir
                                  {:claimed
                                   ;; A rival contender, alive, claims the
                                   ;; directory while this one is between
                                   ;; its own claim and reading the others.
                                   (fn []
                                     (.writeFileSync node-fs (str dir "/" rival)
                                                     "" #js {:flag "wx"}))})}
                         (catch :default e
                           {:refusal e}))]
           (fs/unlock! (:lock outcome))
           (is (some? (:refusal outcome))
               "the contender that sees a live rival is refused, never a
                second owner")
           (is (str/includes? (str (ex-message (:refusal outcome))) dir))
           (is (= #{rival} (claim-files dir))
               "the rival's claim survives; the dead owner's is gone and
                the refused contender withdrew its own"))
         (finally
           (cleanup-dir! dir))))))


#?(:cljd nil
   :cljs
   (deftest a-takeover-that-crashed-midway-does-not-brick-the-directory
     (let [dir (temp-dir)
           node-fs (js/require "fs")
           dead-owner (str fs/lock-name "." (dead-pid) ".0")
           dead-contender (str fs/lock-name "." (dead-pid) ".1")]
       (try
         (store/close! (store/open {:type :file :dir dir}))
         ;; An owner crashed, and a contender taking the directory over
         ;; crashed too, after its claim and before it finished.
         (.writeFileSync node-fs (str dir "/" dead-owner) "")
         (.writeFileSync node-fs (str dir "/" dead-contender) "")
         (let [outcome (try {:store (store/open {:type :file :dir dir})}
                            (catch :default e
                              {:refusal e}))]
           (is (nil? (:refusal outcome))
               (str (ex-message (:refusal outcome))))
           (is (not-any? #{dead-owner dead-contender} (claim-files dir))
               "both crashed claims are reclaimed, with no operator")
           (some-> (:store outcome) store/close!))
         (finally
           (cleanup-dir! dir))))))


#?(:cljd nil
   :cljs
   (def ^:private worker-contender-js
     "One Node worker thread contending for the directory with this build's
      own `dao.space.store.fs/lock!`.  It loads the compiled test bundle's
      namespaces up to `dao.space.store.fs` — skipping the test namespaces
      and the runner's autorun — so the worker has its own `held`
      registry and shares the process's pid, exactly like a second worker
      in a real process.  Flags (an Int32Array over a SharedArrayBuffer):
      [1|2] loaded, [0] start, [3|4] outcome (1 owns, 2 refused), [5]
      release, [6|7] released.  A refused contender retries with a small
      random pause, as an operator restarting would, so the pair settles
      on exactly one owner."
     (str
       "const {workerData} = require('worker_threads');\n"
       "const nodeFs = require('fs'); const nodePath = require('path');\n"
       "const {sab, bundle, dir, id} = workerData;\n"
       "const flags = new Int32Array(sab);\n"
       "const pause = new Int32Array(new SharedArrayBuffer(4));\n"
       "const last = 'SHADOW_IMPORT(\"dao.space.store.fs.js\");';\n"
       "let text = nodeFs.readFileSync(bundle, 'utf8');\n"
       "text = text.slice(text.indexOf('\\n') + 1, text.indexOf(last) + last.length);\n"
       "text = text.split('\\n').filter(l => !(l.startsWith('SHADOW_IMPORT(') && l.includes('_test.js'))).join('\\n') + '\\n})();';\n"
       "new Function('require', 'module', '__filename', '__dirname', text)"
       "(require, module, bundle, nodePath.dirname(bundle));\n"
       "const fsns = global.dao.space.store.fs;\n"
       "Atomics.store(flags, id, 1); Atomics.notify(flags, id);\n"
       "while (Atomics.load(flags, 0) === 0) Atomics.wait(flags, 0, 0, 100);\n"
       "let held = null;\n"
       "for (let i = 0; i < 50 && held === null; i++) {\n"
       "  try { held = fsns.lock_BANG_(dir); }\n"
       "  catch (e) { Atomics.wait(pause, 0, 0, Math.floor(Math.random() * 20)); }\n"
       "}\n"
       "Atomics.store(flags, 2 + id, held === null ? 2 : 1); Atomics.notify(flags, 2 + id);\n"
       "if (held !== null) {\n"
       "  while (Atomics.load(flags, 5) === 0) Atomics.wait(flags, 5, 0, 100);\n"
       "  fsns.unlock_BANG_(held);\n"
       "  Atomics.store(flags, 5 + id, 1); Atomics.notify(flags, 5 + id);\n"
       "}\n")))


#?(:cljd nil
   :cljs
   (deftest two-worker-threads-of-one-process-never-both-own-the-directory
     (let [dir (temp-dir)
           Worker (.-Worker (js/require "worker_threads"))
           sab (js/SharedArrayBuffer. 32)
           flags (js/Int32Array. sab)
           await! (fn [i]
                    (loop [n 0]
                      (cond
                        (not= 0 (aget flags i)) (aget flags i)
                        (< n 600) (do (js/Atomics.wait flags i 0 100)
                                      (recur (inc n)))
                        :else nil)))
           spawn (fn [id]
                   (Worker. worker-contender-js
                            #js {:eval true
                                 :workerData #js {:sab sab
                                                  :bundle js/__filename
                                                  :dir dir
                                                  :id id}}))
           workers (atom [])]
       (try
         (store/close! (store/open {:type :file :dir dir}))
         (swap! workers conj (spawn 1) (spawn 2))
         (is (= [1 1] [(await! 1) (await! 2)])
             "both workers loaded the store namespace")
         (js/Atomics.store flags 0 1)
         (js/Atomics.notify flags 0)
         (let [outcomes [(await! 3) (await! 4)]]
           (is (= 1 (count (filter #{1} outcomes)))
               (str "exactly one worker owns the directory, never both: "
                    outcomes))
           (js/Atomics.store flags 5 1)
           (js/Atomics.notify flags 5)
           (doseq [[owner-flag outcome] [[6 (first outcomes)]
                                         [7 (second outcomes)]]]
             (when (= 1 outcome)
               (is (= 1 (await! owner-flag)) "the owner released"))))
         (finally
           (doseq [w @workers] (.terminate w))
           (cleanup-dir! dir))))))


#?(:cljd nil
   :cljs
   (deftest a-short-write-never-becomes-a-partial-head
     (let [dir (temp-dir)
           node-fs (js/require "fs")
           original (.-writeSync node-fs)
           record (str "{:version 1, :manifest :segment/blake3-"
                       (apply str (repeat 64 "a")) "}")]
       (try
         (write-text! (head-path dir) "{:version 1, :manifest :segment/a}")
         ;; The host stores at most three bytes per call, as a real
         ;; writeSync may: its byte count is the only word on how much.
         (set! (.-writeSync node-fs)
               (fn [fd data & more]
                 (let [buf (if (string? data) (js/Buffer.from data "utf8") data)
                       offset (if (string? data) 0 (or (first more) 0))
                       length (if (string? data)
                                (.-length buf)
                                (or (second more) (- (.-length buf) offset)))]
                   (.call original node-fs fd buf offset (min 3 length) nil))))
         (let [refusal (refusal-of #(fs/atomic-replace! dir "HEAD" record))]
           (set! (.-writeSync node-fs) original)
           (is (nil? refusal) (str (ex-message refusal)))
           (is (= record (read-text! (head-path dir)))
               "HEAD holds the whole record, never a short write's prefix"))
         (finally
           (set! (.-writeSync node-fs) original)
           (cleanup-dir! dir))))))


(defn- set-dir-readable!
  "Grant or take away read permission on the directory: without it the
   directory can still be written into and renamed within, but not
   opened — so its sync fails while the HEAD rename succeeds."
  [dir readable?]
  #?(:cljd (.runSync dart-io/Process "chmod" [(if readable? "755" "300") dir])
     :clj (.setReadable (java.io.File. ^String dir) (boolean readable?) false)
     :cljs (.chmodSync (js/require "fs") dir (if readable? 493 192))))


(deftest a-failed-directory-sync-fails-the-head-replacement
  (let [dir (temp-dir)]
    (try
      (write-text! (head-path dir) "{:version 1, :manifest :segment/a}")
      (set-dir-readable! dir false)
      (let [refusal (refusal-of #(fs/atomic-replace!
                                   dir "HEAD"
                                   "{:version 1, :manifest :segment/b}"))]
        (if (fs/directory-sync-supported?)
          (do (is (some? refusal)
                  "a rename whose directory entry cannot be synced is not
                   reported replaced")
              (is (str/includes? (str (ex-message refusal)) "sync")))
          (is (nil? refusal)
              "a host without directory sync is not a failed sync")))
      (finally
        (set-dir-readable! dir true)
        (cleanup-dir! dir)))))


(deftest a-round-whose-head-cannot-be-made-durable-is-not-published
  (let [dir (temp-dir)
        [state _] (repl/eval-input (durable-shell! dir) "(+ 1 2)")]
    (try
      (is (true? (get-in (repl/repl-state state) [:index :published?])))
      (set-dir-readable! dir false)
      (let [[again _] (repl/eval-input state "(+ 2 3)")]
        (is (= (fs/directory-sync-supported?)
               (false? (get-in (repl/repl-state again)
                               [:index :published?])))
            "where the host syncs directories, a failed sync is a failed
             publication; elsewhere the rename alone publishes"))
      (finally
        (set-dir-readable! dir true)
        (store/close! (:index-store state))
        (cleanup-dir! dir)))))


(deftest an-unreadable-node-in-any-index-refuses-startup
  (doseq [index [:aevt :avet :vaet]]
    (let [dir (temp-dir)]
      (try
        (let [[state _] (repl/eval-input (durable-shell! dir) "(+ 1 2)")
              manifest (get-in state [:indexer :manifest-address])
              root (get-in (dao.index/read-manifest (:index-store state)
                                                    manifest)
                           [:indexes index])]
          (store/close! (:index-store state))
          (drop-frame-with-address! (durable/content-path dir) root)
          (let [content (dao.jing.file/create-content-file
                          (durable/content-path dir))]
            (is (seq (dao.index/read-datoms content manifest))
                (str "only the " index " tree is damaged; EAVT still reads"))
            ((:close-fn content)))
          (let [refusal (refusal-of #(store/open {:type :file :dir dir}))]
            (is (str/includes? (str (ex-message refusal)) "corrupt")
                (str "a missing " index " node refuses startup"))))
        (finally
          (cleanup-dir! dir))))))


(deftest a-failing-content-close-still-releases-the-lock
  (let [dir (temp-dir)]
    (try
      (store/close! (store/open {:type :file :dir dir}))
      (let [close! (durable/lock-releasing-close
                     (fn [] (throw (ex-info "disk gone" {})))
                     (fs/lock! dir))]
        (is (some? (refusal-of close!)) "the close failure is still reported")
        (is (nil? (refusal-of #(store/close! (store/open {:type :file
                                                          :dir dir}))))
            "the directory lock was released regardless"))
      (finally
        (cleanup-dir! dir)))))


#?(:cljd
   (deftest a-real-second-dart-process-is-refused-and-its-death-releases
     (let [dir (temp-dir)
           holder (str dir "-holder.dart")
           pid-file (str dir "-holder.pid")
           pause! #(dart-io/sleep (dart-core/Duration .milliseconds 100))]
       (try
         (store/close! (store/open {:type :file :dir dir}))
         (write-text! holder
                      (str "import 'dart:io';\n"
                           "void main(List<String> a) {\n"
                           "  final r = File(a[0]).openSync(mode: FileMode.append);\n"
                           "  r.lockSync();\n"
                           "  File(a[1]).writeAsStringSync('$pid');\n"
                           "  sleep(Duration(seconds: 120));\n"
                           "}\n"))
         (.runSync dart-io/Process "sh"
                   ["-c" (str "dart run " holder " " dir "/" fs/lock-name
                              " " pid-file " >/dev/null 2>&1 &")])
         (let [pid (loop [n 0]
                     (let [text (read-text! pid-file)]
                       (cond
                         (not (str/blank? text)) (dart-core/int.parse
                                                   (str/trim text))
                         (< n 600) (do (pause!) (recur (inc n)))
                         :else nil)))]
           (is (some? pid) "the child never acquired the durable directory")
           (when pid
             (let [refusal (refusal-of #(store/open {:type :file :dir dir}))]
               (is (str/includes? (ex-message refusal) dir))
               (is (str/includes? (ex-message refusal) "locked")))
             (.killPid dart-io/Process pid)
             (testing "the owner's death releases the lock: no stale refusal"
               (let [after (loop [n 0]
                             (let [opened (try (store/open {:type :file :dir dir})
                                               (catch Object _ nil))]
                               (if (or opened (>= n 100))
                                 opened
                                 (do (pause!) (recur (inc n))))))]
                 (is (some? after))
                 (when after (store/close! after))))))
         (finally
           (cleanup-file! holder)
           (cleanup-file! pid-file)
           (cleanup-dir! dir))))))


(defn- make-read-only!
  "Take write permission away from the file at `path`: its bytes can no
   longer be overwritten in place, but its directory entry can still be
   renamed over."
  [path]
  #?(:cljd (.runSync dart-io/Process "chmod" ["444" path])
     :clj (.setWritable (java.io.File. ^String path) false false)
     :cljs (.chmodSync (js/require "fs") path 292)))


(deftest head-replacement-is-a-rename-of-a-synced-temp-never-a-partial-head
  (let [dir (temp-dir)]
    (try
      (write-text! (str dir "/HEAD.tmp-crashed") "{:version 1, :man")
      (fs/atomic-replace! dir "HEAD" "{:version 1, :manifest :segment/a}")
      (is (= "{:version 1, :manifest :segment/a}"
             (read-text! (head-path dir))))
      (testing "the new record arrives by rename, never by writing into HEAD"
        (make-read-only! (head-path dir))
        (fs/atomic-replace! dir "HEAD" "{:version 1, :manifest :segment/b}")
        (is (= "{:version 1, :manifest :segment/b}"
               (read-text! (head-path dir)))
            "a HEAD this process cannot write is still replaced whole"))
      (testing "an interrupted replacement leaves HEAD exactly as it was"
        (let [refusal (refusal-of
                        #(fs/atomic-replace!
                           dir "HEAD" "{:version 1, :manifest :segment/c}"
                           {:before-rename
                            (fn [temp]
                              ;; the crash tears the temp and never renames
                              (write-text! temp "{:version 1, :mani")
                              (throw (ex-info "crash" {})))}))]
          (is (some? refusal))
          (is (= "{:version 1, :manifest :segment/b}"
                 (read-text! (head-path dir)))
              "the torn temp never became HEAD")))
      (finally
        (cleanup-dir! dir)))))


(deftest a-publication-interrupted-before-the-rename-reopens-the-previous-snapshot
  (let [dir (temp-dir)]
    (try
      (let [[state _] (repl/eval-input (durable-shell! dir) "(+ 1 2)")
            manifest (get-in state [:indexer :manifest-address])
            committed (into #{} (mapcat :datoms) (transactions state))
            store (:index-store state)
            ;; the second round's blobs drain and read back, and the
            ;; process dies inside the HEAD replacement, before its rename
            crashing (assoc store
                            :head-fn (fn [address]
                                       (fs/atomic-replace!
                                         dir durable/head-name
                                         (str (pr-str {:version 1
                                                       :manifest address})
                                              "\n")
                                         {:before-rename
                                          (fn [_temp]
                                            (throw (ex-info "crash" {})))})))
            [again _] (repl/eval-input
                        (repl.frontends/create-state {:index-store crashing})
                        "(+ 2 3)")]
        (is (false? (get-in (repl/repl-state again) [:index :published?]))
            "a round whose HEAD did not move is not durably published")
        (store/close! store)
        (let [reopened (store/open {:type :file :dir dir})]
          (is (= manifest (get-in reopened [:recovery :manifest])))
          (is (= committed (set (:datoms (:recovery reopened)))))
          (store/close! reopened)))
      (finally
        (cleanup-dir! dir)))))


(deftest a-published-head-recovers-and-a-new-round-replaces-it
  (let [dir (temp-dir)]
    (try
      (let [[state _] (repl/eval-input (durable-shell! dir) "(+ 1 2)")
            manifest (get-in state [:indexer :manifest-address])
            committed (into #{} (mapcat :datoms) (transactions state))]
        (is (= {:version 1 :manifest manifest} (head-record-of dir)))
        (store/close! (:index-store state))
        (let [reopened (store/open {:type :file :dir dir})]
          (testing "a reopen exposes the validated snapshot for slice 3"
            (is (= manifest (get-in reopened [:recovery :manifest])))
            (is (= committed (set (:datoms (:recovery reopened))))
                "the recovery carries the walked datoms, not only the pointer"))
          (testing "and a fresh round over the reopened store moves HEAD"
            (let [[again _] (repl/eval-input
                              (repl.frontends/create-state {:index-store reopened})
                              "(+ 2 3)")
                  new-manifest (get-in again [:indexer :manifest-address])]
              (is (not= manifest new-manifest))
              (is (= {:version 1 :manifest new-manifest}
                     (head-record-of dir)))
              (store/close! (:index-store again))))))
      (finally
        (cleanup-dir! dir)))))


(deftest a-crash-before-the-rename-keeps-the-previous-snapshot
  (let [dir (temp-dir)]
    (try
      (let [[state _] (repl/eval-input (durable-shell! dir) "(+ 1 2)")
            manifest (get-in state [:indexer :manifest-address])]
        (store/close! (:index-store state))
        ;; The crashed writer's leftover: a complete new record in a temp
        ;; file whose rename never happened.
        (write-text! (str dir "/HEAD.tmp-crashed")
                     "{:version 1, :manifest :segment/never-renamed}")
        (let [reopened (store/open {:type :file :dir dir})]
          (is (= manifest (get-in reopened [:recovery :manifest]))
              "the previous published snapshot is what HEAD still names")
          (is (= "{:version 1, :manifest :segment/never-renamed}"
                 (read-text! (str dir "/HEAD.tmp-crashed")))
              "the torn write is left as the harmless leftover it is")
          (store/close! reopened)))
      (finally
        (cleanup-dir! dir)))))


(deftest a-malformed-head-refuses-startup-and-leaks-no-lock
  (let [dir (temp-dir)]
    (try
      (doseq [head ["not a record at all"
                    ""
                    "{}"
                    "{:version 2, :manifest :segment/blake3-00}"
                    "{:version 1}"
                    "{:version 1, :manifest \"nope\"}"]]
        (write-text! (head-path dir) head)
        (let [refusal (refusal-of #(store/open {:type :file :dir dir}))]
          (is (str/includes? (ex-message refusal) "HEAD") (pr-str head))
          (is (str/includes? (ex-message refusal) dir) (pr-str head))))
      (testing "a well-formed HEAD naming an absent snapshot is corrupt"
        (write-text! (head-path dir)
                     (str "{:version 1, :manifest " (absent-address) "}"))
        (let [refusal (refusal-of #(store/open {:type :file :dir dir}))]
          (is (str/includes? (ex-message refusal) "corrupt"))))
      (testing "a refused open releases its lock: a repaired HEAD opens"
        (cleanup-file! (head-path dir))
        (let [fresh (store/open {:type :file :dir dir})]
          (is (= {:manifest nil :datoms nil} (:recovery fresh)))
          (store/close! fresh)))
      (finally
        (cleanup-dir! dir)))))


(deftest a-missing-manifest-refuses-startup
  (let [dir (temp-dir)]
    (try
      (let [[state _] (repl/eval-input (durable-shell! dir) "(+ 1 2)")
            manifest (get-in state [:indexer :manifest-address])]
        (store/close! (:index-store state))
        (write-text! (head-path dir)
                     (str "{:version 1, :manifest " (hex-altered manifest) "}"))
        (let [refusal (refusal-of #(store/open {:type :file :dir dir}))]
          (is (str/includes? (ex-message refusal) "corrupt"))
          (is (str/includes? (ex-message refusal) "missing"))
          (is (str/includes? (ex-message refusal) dir)
              "the refusal names the directory an operator can act on")))
      (finally
        (cleanup-dir! dir)))))


(deftest an-unreadable-index-node-refuses-startup
  (let [dir (temp-dir)]
    (try
      (let [[state _] (repl/eval-input (durable-shell! dir) "(+ 1 2)")
            manifest (get-in state [:indexer :manifest-address])
            eavt (when manifest
                   (get-in (dao.index/read-manifest (:index-store state)
                                                    manifest)
                           [:indexes :eavt]))]
        (is (some? eavt) "the round published a non-empty EAVT root")
        (store/close! (:index-store state))
        (drop-frame-with-address! (durable/content-path dir) eavt)
        (let [refusal (refusal-of #(store/open {:type :file :dir dir}))]
          (is (str/includes? (ex-message refusal) "corrupt")
              "every remaining frame is valid, so only the traversal over
               the manifest HEAD names can refuse")
          (is (str/includes? (ex-message refusal) "missing"))))
      (finally
        (cleanup-dir! dir)))))


(deftest a-corrupt-content-log-refuses-startup
  (let [dir (temp-dir)]
    (try
      (let [[state _] (repl/eval-input (durable-shell! dir) "(+ 1 2)")]
        (store/close! (:index-store state))
        (corrupt-payload-byte! (durable/content-path dir))
        (let [refusal (refusal-of #(store/open {:type :file :dir dir}))]
          (is (str/includes? (ex-message refusal) dir))
          (is (some? (ex-data refusal))
              "the refusal is designed, not a host crash")))
      (finally
        (cleanup-dir! dir)))))


(deftest an-unreferenced-blob-after-a-crash-is-harmless
  (let [dir (temp-dir)]
    (try
      (let [[one _] (repl/eval-input (durable-shell! dir) "(+ 1 2)")
            first-manifest (get-in one [:indexer :manifest-address])
            first-committed (into #{} (mapcat :datoms) (transactions one))
            [two _] (repl/eval-input one "(+ 2 3)")]
        (is (not= first-manifest (get-in two [:indexer :manifest-address])))
        (store/close! (:index-store two))
        ;; The crash rewound HEAD to the first round: the second round's
        ;; blobs are unreferenced now.
        (write-text! (head-path dir)
                     (str "{:version 1, :manifest " first-manifest "}"))
        (let [reopened (store/open {:type :file :dir dir})]
          (is (= first-manifest (get-in reopened [:recovery :manifest])))
          (is (= first-committed (set (:datoms (:recovery reopened)))))
          (store/close! reopened)))
      (finally
        (cleanup-dir! dir)))))


;; =============================================================================
;; Rehydration and (reset) continuity — slice 3.  The restart sequence of
;; the durable-store ruling (§5), as the REPL input lines an operator types:
;; evaluate, stop, restart on the same directory, require dao.space.query,
;; query the old facts, evaluate more, query both.
;; =============================================================================

(def ^:private require-line
  "(require (quote dao.space.query))")


(defn- q-line
  [query & more]
  (str "(dao.space.query/q (quote " (pr-str query) ")"
       (apply str (map #(str " " %) more))
       ")"))


(defn- evaluate
  [state lines]
  (reduce (fn [[state texts] line]
            (let [[state' text] (repl/eval-input state line)]
              [state' (conj texts text)]))
          [state []]
          lines))


(defn- provenance-line
  "A history-view query for the session token and t of the literal
   `value` — a constant only one def carries.  It sits inside the quoted
   query, never as an input, so the calling line's own literals (every
   program is indexed, the calling one included) cannot match it."
  [value]
  (q-line [:find '?s '?t
           :where ['?e :yin/value value '?t '?m]
           ['?m :yin.repl/session '?s '?t1 '?m1]]
          "{:view :history}"))


(defn- provenance
  "The `[token t]` pairs a provenance answer's text names."
  [text]
  (set (map (fn [[_ token t]] [token (read-edn t)])
            (re-seq #"\[\"([^\"]+)\" (\d+)\]" (str text)))))


(defn- local-datoms
  "Every datom of the indexer's retained transaction log."
  [state]
  (mapcat :datoms (transactions state)))


(defn- ids-of
  "The entity and metadata-entity ids a datom set uses."
  [datoms]
  (into #{}
        (comp (mapcat (fn [[e _a _v _t m]] [e m]))
              (filter #(and (integer? %) (<= datom/first-user-id %))))
        datoms))


(deftest a-restarted-durable-shell-answers-q-with-the-previous-runs-facts
  (let [dir (temp-dir)
        spec {:type :file :dir dir}]
    (try
      (let [[first-run _] (evaluate (repl.frontends/create-state {:index-store-spec spec})
                                    ["(def alpha 1001)"])
            first-token (:shell-token first-run)
            first-datoms (set (local-datoms first-run))
            _ (store/close! (:index-store first-run))
            restarted (repl.frontends/create-state {:index-store-spec spec})
            [second-run [required old-only _ both-alpha both-beta]]
            (evaluate restarted
                      [require-line
                       (provenance-line 1001)
                       "(def beta 2002)"
                       (provenance-line 1001)
                       (provenance-line 2002)])
            second-token (:shell-token second-run)
            [[_ alpha-t]] (vec (provenance both-alpha))
            [[_ beta-t]] (vec (provenance both-beta))]
        (is (= "'dao.space.query" required))
        (testing "the restarted shell is seeded before any evaluation"
          (let [status (:index (repl/repl-state restarted))]
            (is (= (count (transactions first-run)) (:transactions status))
                "the restored transactions are counted")
            (is (true? (:published? status))
                "and published: q can answer before any new program")))
        (is (not= first-token second-token)
            "every process start mints its own shell token")
        (testing "q sees the old facts before any new evaluation"
          (is (= #{first-token} (set (map first (provenance old-only))))
              old-only))
        (testing "q sees old and new facts, with increasing t and distinct
                  session provenance"
          (is (= #{first-token} (set (map first (provenance both-alpha))))
              both-alpha)
          (is (= #{second-token} (set (map first (provenance both-beta))))
              both-beta)
          (is (and alpha-t beta-t (< alpha-t beta-t))
              "the new transactions continue t from the restored log"))
        (testing "restored entity ids never collide with new ones"
          (let [new-datoms (remove first-datoms (local-datoms second-run))]
            (is (seq new-datoms))
            (is (empty? (cset/intersection (ids-of first-datoms)
                                           (ids-of new-datoms))))))
        (testing "the first post-restart publication's HEAD covers old and
                  new facts"
          (let [manifest (get-in second-run [:indexer :manifest-address])
                published (set (dao.index/read-datoms
                                 (:index-store second-run) manifest))]
            (is (= {:version 1 :manifest manifest} (head-record-of dir)))
            (is (every? published first-datoms) "the old facts")
            (is (every? published (local-datoms second-run))
                "and every committed fact")))
        (testing "(reset) and (vm …) keep the facts, t and entity allocation"
          (let [before (set (local-datoms second-run))
                [after-reset [_ _ alpha-after beta-after]]
                (evaluate second-run
                          ["(reset)" require-line
                           (provenance-line 1001) (provenance-line 2002)])
                [switched [_ _ gamma-text]]
                (evaluate after-reset
                          ["(vm :stack)" "(def gamma 3003)"
                           (provenance-line 3003)])
                [switched' [_ gamma-q]]
                (evaluate switched [require-line (provenance-line 3003)])
                [[_ gamma-t]] (vec (provenance gamma-q))
                gamma-datoms (remove before (local-datoms switched'))]
            (is (= (provenance both-alpha) (provenance alpha-after)))
            (is (= (provenance both-beta) (provenance beta-after)))
            (is (str/includes? (str gamma-text) "Unable to resolve")
                "the switched session needs its own require")
            (is (and gamma-t (< beta-t gamma-t)) gamma-q)
            (is (empty? (cset/intersection (ids-of before)
                                           (ids-of gamma-datoms)))
                "entity allocation continued across the rebuilds")))
        (store/close! (:index-store second-run)))
      (finally
        (cleanup-dir! dir)))))


(deftest mem-mode-reset-still-starts-an-empty-index
  (let [[state [_ _ _ _ after-reset]]
        (evaluate (repl.frontends/create-state)
                  ["(def alpha 1001)" require-line (provenance-line 1001)
                   "(reset)"
                   require-line])
        [_ [alpha-after]] (evaluate state [(provenance-line 1001)])]
    (is (= "'dao.space.query" after-reset))
    (is (= "#{}" alpha-after)
        "the memory store's reset keeps today's empty rebuild")))


;; =============================================================================
;; Rehydration fidelity over a published fixture: several `t`, a retraction,
;; and a greatest id that occurs only in `m`.
;; =============================================================================

(defn- memory-log!
  []
  (:dao.stream/handle
    (memory-log/create! {:dao.stream/type memory-log/transport-type})))


(def ^:private fixture-txs
  "Three transactions a real transactor commits as t 0, 1 and 2: two
   assertions; an assertion whose metadata entity, 40, is the greatest id
   and occurs nowhere but in `m`; and the retraction of the first fact."
  [[[16 :fixture/name "a" nil 1] [17 :fixture/name "b" nil 1]]
   [[18 :fixture/name "c" nil 40]]
   [[16 :fixture/name "a" nil 0]]])


(defn- publish-fixture!
  "Commit `fixture-txs` through a transactor, publish the covered indexes
   into a fresh durable store at `dir`, drain them into it, move HEAD to
   the manifest as a round would, and close the store.  Answers the
   committed transaction records."
  [dir]
  (let [opened (store/open {:type :file :dir dir})
        local (memory-log!)
        intake (memory-log!)
        tx (transactor/create! {:local-stream local
                                :intake-pool [intake]
                                :name "fixture"})]
    (doseq [tx-data fixture-txs]
      (is (= :dao.stream/ok
             (:dao.stream/outcome (transactor/transact! tx tx-data)))))
    (let [{:keys [manifest-address]} (transactor/publish! tx)]
      (loop [pool (jing/observer-state
                    [{:stream intake
                      :cursor (:dao.stream/cursor
                                (dao.stream/cursor intake :dao.stream/oldest))}])]
        (let [{:keys [signal state]} (jing/observe-step! opened pool)]
          (when (= :dao.stream/ok signal)
            (recur state))))
      ((:head-fn opened) manifest-address)
      (store/close! opened)
      (mapv :dao.space/transaction (stream-values local)))))


(defn- groups
  "Transaction records as `t` -> the set of its datoms."
  [records]
  (into {} (map (fn [{:keys [t datoms]}] [t (set datoms)])) records))


(def ^:private fixture-history
  '[:find ?e ?v ?t ?m :where [?e :fixture/name ?v ?t ?m]])


(def ^:private fixture-current
  '[:find ?e ?v :where [?e :fixture/name ?v]])


(deftest a-restart-restores-every-transaction-group-history-row-and-retraction
  (let [dir (temp-dir)]
    (try
      (let [committed (publish-fixture! dir)
            expected-groups (groups committed)
            history-rows (into #{}
                               (comp (mapcat :datoms)
                                     (map (fn [[e _a v t m]] [e v t m])))
                               committed)
            restarted (repl.frontends/create-state {:index-store-spec {:type :file
                                                             :dir dir}})
            ;; The log is a live stream the later rounds append to: read
            ;; the restored groups before anything is evaluated.
            restored-groups (groups (transactions restarted))
            [before [_ history current]]
            (evaluate restarted [require-line
                                 (q-line fixture-history "{:view :history}")
                                 (q-line fixture-current)])
            [after [_ history' current']]
            (evaluate before ["(def delta 4004)"
                              (q-line fixture-history "{:view :history}")
                              (q-line fixture-current)])]
        (is (= [0 1 2] (sort (keys expected-groups)))
            "the fixture spans three transaction times")
        (is (= expected-groups restored-groups)
            "each restored transaction is its original group, at its t")
        (testing "before a new publication"
          (is (= history-rows (read-edn history))
              "history rows: every assertion and the retraction, each at its
               t and with its m")
          (is (= #{[17 "b"] [18 "c"]} (read-edn current))
              "the current view applies the retraction"))
        (testing "after a new publication"
          (is (= expected-groups
                 (select-keys (groups (transactions after))
                              (keys expected-groups)))
              "the restored groups are untouched")
          (is (every? #(< 2 %) (remove (set (keys expected-groups))
                                       (keys (groups (transactions after)))))
              "new transactions come after the restored ones")
          (is (= history-rows (read-edn history')))
          (is (= #{[17 "b"] [18 "c"]} (read-edn current'))))
        (testing "a greatest id only in m still bounds entity allocation"
          (let [new-datoms (remove (set (mapcat :datoms committed))
                                   (local-datoms after))]
            (is (seq new-datoms))
            (is (every? #(< 40 %) (ids-of new-datoms))
                "every id allocated after the restart exceeds m = 40")))
        (store/close! (:index-store after)))
      (finally
        (cleanup-dir! dir)))))


(deftest vm-selection-says-what-it-keeps
  (let [dir (temp-dir)]
    (try
      (let [[durable [switched]] (evaluate (repl.frontends/create-state
                                             {:index-store-spec {:type :file
                                                                 :dir dir}})
                                           ["(vm :stack)"])
            [_ [switched-mem]] (evaluate (repl.frontends/create-state) ["(vm :stack)"])]
        (is (= (str "Switched to DebruijnStackVM (VM store cleared; durable"
                    " code index kept)")
               switched)
            "in durable mode the code index survives the switch, and says so")
        (is (= "Switched to DebruijnStackVM (store cleared)" switched-mem)
            "the memory store's message is today's")
        (store/close! (:index-store durable)))
      (finally
        (cleanup-dir! dir)))))
