(ns yin.repl.state-test
  "The node's saved configuration (yin.repl.state): the flags a node was last
   started with, kept in `<node dir>/state.edn` and resumed by a bare
   `yin-repl`, changed by the command line."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.space.store.fs :as fs]
            [yin.repl.main :as repl]
            [yin.repl.state :as state]))


(defn- temp-dir
  []
  (str "target/test-state-" (random-uuid)))


(defn- refusal-of
  [thunk]
  (try (thunk) nil (catch #?(:cljd Object :clj Throwable :cljs :default) e e)))


(deftest args-split-into-saved-flags-and-the-rest
  (let [{:keys [flags rest]}
        (state/split-args ["--port" "8080" "--headless" "--dht-peer" "a:1"
                           "--dht-peer" "b:2" "--dht-manifest" ":segment/x"
                           "--telemetry"])]
    (is (= {"--port" "8080" "--headless" true "--dht-peer" ["a:1" "b:2"]}
           flags))
    (testing "one-shot flags and unknown arguments pass through, in order"
      (is (= ["--dht-manifest" ":segment/x" "--telemetry"] rest)))
    (is (= ["--port" "8080" "--headless" "--dht-peer" "a:1" "--dht-peer" "b:2"]
           (state/flags->args flags))
        "a fixed order, a repeated flag once per value")))


(deftest a-flag-with-no-value-is-refused-not-resolved-to-the-saved-one
  (doseq [args [["--port"] ["--port" "--headless"] ["--vm"] ["--dht-peer"]
                ["--dht-peer" "a:1" "--dht-peer"] ["--dht-manifest"]
                ["--dht-keygen" "--help"]]]
    (let [e (refusal-of #(state/split-args args))]
      (is (some? (ex-data e)) (pr-str args))
      (is (str/includes? (ex-message e) "needs a value") (pr-str args)))))


(deftest the-command-line-replaces-saved-values-whole
  (let [saved {"--dht-peer" ["a:1"] "--dht-publish" true "--port" "1"}]
    (is (= {"--dht-peer" ["b:2" "c:3"] "--dht-publish" true "--port" "1"}
           (state/resolve-flags saved {"--dht-peer" ["b:2" "c:3"]} nil))
        "a repeated flag's values replace the saved ones, not extend them")
    (is (= {"--dht-peer" ["a:1"] "--port" "1"}
           (state/resolve-flags saved {} #{"--dht-publish"}))
        "a subcommand clears a saved switch")
    (is (= ["--dht-peer" "--dht-publish"]
           (state/changed saved {"--dht-peer" ["b:2"] "--port" "1"})))))


(deftest state-round-trips-through-its-file
  (let [dir (temp-dir)
        flags {"--index-store" "dht:/tmp/x" "--headless" true
               "--dht-peer" ["127.0.0.1:4001" "127.0.0.1:4002"]
               "--dht-publish" true "--port" "8080"}]
    (is (nil? (state/load-flags dir)) "no file, no state")
    (state/save! dir flags)
    (is (= flags (state/load-flags dir)))
    (let [text (fs/read-file-text dir state/file-name)]
      (is (str/starts-with? text "{:version 1"))
      (is (not (re-find #"\s[\]\}]" text))
          "no space before a closer: ClojureDart's EDN reader refuses one"))
    (state/save! dir {"--vm" "stack"})
    (is (= {"--vm" "stack"} (state/load-flags dir)) "a save replaces the file")))


(deftest an-unreadable-state-file-is-refused-not-skipped
  (doseq [[text why] [["{:version 1 :config" "not readable EDN"]
                      ["{:version 2 :config {}}" "version-1"]
                      ["{:version 1}" ":config"]
                      ["{:version 1 :config {:nonsense \"x\"}}" "unknown setting"]
                      ["{:version 1 :config {42 \"x\"}}" "unknown setting"]
                      ["{:version 1 :config {\"--port\" \"1\"}}" "unknown setting"]
                      ["{:version 1 :config {:port 8080}}" "must be a string"]
                      ["{:version 1 :config {:dht-peer \"a:1\"}}"
                       "vector of strings"]
                      ["{:version 1 :config {:headless false}}" "must be true"]]]
    (let [dir (temp-dir)]
      (state/save! dir {})
      (fs/atomic-replace! dir state/file-name text)
      (let [e (refusal-of #(state/load-flags dir))]
        (is (some? (ex-data e)) text)
        (is (str/includes? (ex-message e) why) text)
        (is (str/includes? (ex-message e) "--reset") text)))))


(deftest a-state-path-that-cannot-be-read-is-refused-naming-the-file
  (let [dir (temp-dir)]
    ;; a directory where the file should be: existing, but not readable text
    (state/save! (str dir "/" state/file-name) {})
    (let [e (refusal-of #(state/load-flags dir))]
      (is (some? (ex-data e)) "a designed refusal, not a raw I/O exception")
      (is (str/includes? (ex-message e) "cannot be read"))
      (is (str/includes? (ex-message e) "--reset")))
    (let [started (repl/startup ["--dir" dir] {:persist? true})]
      (is (str/includes? (:refusal started) "cannot be read")
          "a bare start is refused too, not started without its state"))))


(deftest a-value-that-begins-with-two-dashes-is-a-missing-value
  (is (= {"--dht-key" "./--x.key"}
         (:flags (state/split-args ["--dht-key" "./--x.key"])))
      "a path that really begins with -- is written ./--name")
  (is (some? (ex-data (refusal-of #(state/split-args
                                     ["--dht-key" "--x.key"]))))))


(deftest a-missing-value-never-starts-with-the-saved-one
  (let [dir (temp-dir)]
    (state/save! dir {"--port" "8080"})
    (doseq [args [["--port"] ["--port" "9000" "--port"]]]
      (let [started (repl/startup (into ["--dir" dir] args) {:persist? true})]
        (is (str/includes? (:refusal started) "--port needs a value")
            (pr-str args))
        (is (nil? (:state started)))))
    (is (= {"--port" "8080"} (state/load-flags dir))
        "a refused start saves nothing")))


(defn- vm-of
  [started]
  (get-in started [:state :repl :vm-type]))


(defn- banner-of
  [started]
  (str/join "\n" (repl/banner (:opts started))))


(deftest a-bare-start-resumes-the-last-state-and-the-command-line-changes-it
  (let [dir (temp-dir)
        start (fn [& args]
                (repl/startup (into ["--dir" dir] args) {:persist? true}))]
    (testing "the first run saves what it started with"
      (let [started (start "--vm" "stack")]
        (is (= :stack (vm-of started)))
        (is (str/includes? (banner-of started) "state: saved to"))
        (is (= {"--vm" "stack"} (state/load-flags dir)))))
    (testing "a bare start resumes it"
      (let [started (start)]
        (is (= :stack (vm-of started)))
        (is (str/includes? (banner-of started) "state: resumed from"))))
    (testing "the command line changes it, and the change is saved"
      (let [started (start "--vm" "register")]
        (is (= :register (vm-of started)))
        (is (str/includes? (banner-of started) "the command line changed --vm"))
        (is (= :register (vm-of (start))))))
    (testing "--no-state neither reads nor writes"
      (let [started (start "--no-state" "--vm" "ast-walker")]
        (is (= :ast-walker (vm-of started)))
        (is (not (str/includes? (banner-of started) "state:")))
        (is (= {"--vm" "register"} (state/load-flags dir)))))
    (testing "--reset starts from the command line alone and saves that"
      (let [started (start "--reset")]
        (is (= :semantic (vm-of started)))
        (is (= {} (state/load-flags dir)))))))


(deftest persistence-is-off-unless-the-host-entry-point-asks
  (let [dir (temp-dir)
        started (repl/startup ["--dir" dir "--vm" "stack"])]
    (is (= :stack (vm-of started)))
    (is (nil? (state/load-flags dir)) "the one-argument startup saves nothing")))


(deftest a-bad-saved-state-or-a-clashing-flag-refuses-startup
  (testing "an unreadable file refuses, naming the file and --reset"
    (let [dir (temp-dir)]
      (state/save! dir {})
      (fs/atomic-replace! dir state/file-name "{:version 9}")
      (let [started (repl/startup ["--dir" dir] {:persist? true})]
        (is (str/includes? (:refusal started) "state file"))
        (is (str/includes? (:refusal started) "--reset")))))
  (testing "saved --dht flags with a store that is not a DHT's refuse, with the
            saved state named so --reset is the obvious way out"
    (let [dir (temp-dir)]
      (state/save! dir {"--dht-peer" ["127.0.0.1:4001"]})
      (let [started (repl/startup ["--dir" dir] {:persist? true})]
        (is (str/includes? (:refusal started) "--index-store dht:<dir>"))
        (is (str/includes? (:refusal started) "with the saved state in"))
        (is (str/includes? (:refusal started) "--reset forgets it")))
      (is (some? (:state (repl/startup ["--dir" dir "--reset"]
                                       {:persist? true})))
          "--reset clears the clash"))))


(deftest a-dht-subcommand-resumes-and-serve-stops-publishing
  (let [dir (temp-dir)
        start (fn [& args] (repl/expand-args args))]
    (testing "init saves publishing; serve unsets it"
      (let [[args extra] (start "dht" "init" "--dir" dir "--key" "k"
                                "--peer" "localhost:4002")
            {:keys [flags]} (state/split-args args)]
        (is (true? (get flags "--dht-publish")))
        (is (= dir (:dir extra))))
      (let [[args extra] (start "dht" "serve" "--dir" dir "--peer"
                                "localhost:4002")
            saved {"--dht-publish" true "--dht-peer" ["127.0.0.1:4002"]}
            resolved (state/resolve-flags saved (:flags (state/split-args args))
                                          (:unset extra))]
        (is (not (contains? resolved "--dht-publish")))
        (is (= ["127.0.0.1:4002"] (get resolved "--dht-peer")))))))
