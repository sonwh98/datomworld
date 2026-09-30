(ns yin.repl.store-test
  "The index store's startup selection (yin.repl.store): the default is
   the in-memory store today's shell uses, `file:<dir>` opens
   `<dir>/content.jing` and receives the shell's publications, and every
   invalid spec — a missing value, an unknown scheme, an empty or
   unopenable directory, a host without file support, a handle and a spec
   together — is refused with its reason, never answered with a silent
   memory fallback."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing.file :as dao.jing.file]
            [dao.jing.mem :as jing.mem]
            [dao.space.index :as dao.index]
            [dao.stream :as dao.stream]
            [yin.repl :as repl]
            [yin.repl.store :as store]))


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
  (let [state (repl/create-state)]
    (is (= :mem (:index-store-spec state)))
    (is (fn? (:put-bytes-fn (:index-store state))))
    (is (fn? (:get-bytes-fn (:index-store state))))
    (is (fn? (:close-fn (:index-store state))))))


(deftest a-host-without-file-support-is-refused-not-fallen-back
  (is (store/host-file-support)
      "the JVM, Node, and Dart lanes this suite runs on can all open files")
  (testing "the refusal names the host, whatever the directory"
    (is (str/includes? (str (store/file-refusal false "/any/dir"))
                       "not supported on this host")))
  (let [dir (temp-dir)]
    (try
      (is (nil? (store/file-refusal true dir))
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
                                       #(repl/create-state
                                          {:index-store-spec
                                           {:type :file :dir blocker}})))
                         "not a directory")
          "and the shell's construction refuses it too, before it composes")
      (finally
        (cleanup-file! blocker)))))


(deftest a-handle-and-a-spec-together-are-refused
  (is (str/includes? (ex-message (refusal-of #(repl/create-state
                                                {:index-store
                                                 (jing.mem/create-content-mem)
                                                 :index-store-spec :mem})))
                     "not both"))
  (testing "either alone is accepted"
    (let [injected (jing.mem/create-content-mem)]
      (is (identical? injected (:index-store (repl/create-state
                                               {:index-store injected}))))
      (is (nil? (:index-store-spec (repl/create-state
                                     {:index-store injected})))))))


;; =============================================================================
;; The durable store receives what the memory store receives
;; =============================================================================

(deftest a-file-store-opens-content-jing-and-receives-publications
  (let [dir (temp-dir)]
    (try
      (let [[state text] (repl/eval-input
                           (repl/create-state
                             {:index-store-spec {:type :file :dir dir}})
                           "(+ 1 2)")
            manifest (get-in state [:indexer :manifest-address])]
        (is (= "3" text) "the shell evaluates as it does on memory")
        (is (= {:type :file :dir dir} (:index-store-spec state)))
        (is (some? manifest) "the round published")
        (testing "the publication is on disk, at <dir>/content.jing"
          (let [on-disk (dao.jing.file/records (store/content-path dir))]
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
