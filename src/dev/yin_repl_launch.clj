(ns yin-repl-launch
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [build-cache :as cache]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gambit-ffi-cache :as ffi]
            [gambit-yin-progress :as progress]
            [jolt-launch :as jolt]))


(def ^:private backends #{"jvm" "node" "dart" "jolt-chez" "jolt-gambit"})


(def ^:private dart-capabilities
  "programmatic Transit peer — input is Transit-encoded lines (machine protocol); for an interactive REPL use --backend jvm, node, jolt-chez, or jolt-gambit")


(def ^:private gambit-capabilities
  "Local --index-store mem session; no :yang/python frontend or WebSocket serving.")


(def ^:private gambit-blocking
  "Blocking FFI refuses inline: blocking ffi is unsupported on the gambit target.")


(def ^:private gambit-startup
  ["First launch builds the boot and bridge (~16 min to yin> on macOS). Cache hits reach native Gambit in ~0.6 s."
   "The pinned loader interprets Yin namespaces from source (~11 min to yin> with caches)."])


(def ^:private usage
  (str "Usage: bb yin-repl [--backend jvm|node|dart|jolt-chez|jolt-gambit] [yin args...]

  jvm          Default. Shipped JVM shell with Python and PHP frontends.
  node         Shipped Node shell with Python and PHP frontends.
  dart         " dart-capabilities "
  jolt-chez    Pinned Jolt shell with the native Python frontend.
  jolt-gambit  " gambit-capabilities "
               " gambit-blocking "
               " (str/join "\n               " gambit-startup) "

Use -- --help after the backend to pass --help to the selected program."))


(defn- parse-launch
  [args]
  (let [[backend args] (cond
                         (= "--backend" (first args)) [(second args) (nnext args)]
                         (some-> (first args) (str/starts-with? "--backend="))
                         [(subs (first args) (count "--backend=")) (next args)]
                         :else ["jvm" args])]
    (when-not (backends backend)
      (throw (ex-info (str "Unknown or missing --backend " (pr-str backend)
                           ". Run bb yin-repl --help.") {:exit 2})))
    [backend args]))


(defn- checked-shell
  [opts & command]
  (let [result (apply p/shell (merge {:continue true :shutdown nil} opts) command)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "yin-repl prerequisite failed: " (str/join " " command))
                      {:exit (:exit result)})))
    result))


(defn- task!
  [task & args]
  (apply checked-shell {} "bb" task args))


(defn- terminal-exit
  [command args]
  (:exit (apply p/shell {:in :inherit :continue true :shutdown nil}
                (into command args))))


(defn- dart-session
  [args]
  (let [child (apply p/process {:in :inherit :out :stream :err :inherit :shutdown nil}
                     "build/yin-repl-peer" args)]
    (try
      (with-open [out (java.io.BufferedInputStream. (:out child))]
        ;; Forward the first frame byte for byte and flush it before the note.
        (let [line (java.io.ByteArrayOutputStream.)]
          (loop [b (.read out)]
            (when-not (neg? b)
              (.write line b)
              (when-not (= b 10)
                (recur (.read out)))))
          (.writeTo line System/out)
          (.flush System/out)
          (when (str/includes? (.toString line "UTF-8") "\"~:ready\"")
            (.println System/err (str "dart: " dart-capabilities))))
        (io/copy out System/out)
        (.flush System/out))
      (:exit @child)
      (finally
        (when (p/alive? child)
          (p/destroy child))))))


(defn- ensure-jolt!
  []
  (when-not (fs/regular-file? "build/tools/jolt-env.sh")
    (task! "bootstrap:jolt")))


(defn- ensure-java-parsers!
  []
  (when-not (and (fs/regular-file? "build/antlr/python3/classes/yang/python/antlr/gen/Python3Parser.class")
                 (fs/regular-file? "build/antlr/php/classes/yang/php/antlr/gen/PhpParser.class"))
    (task! "gen:antlr" "--" "--target" "java")))


(defn- ensure-chez-native!
  []
  (let [darwin? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
        arm64? (#{"aarch64" "arm64"} (System/getProperty "os.arch"))
        platform (str (if darwin? "darwin-" "linux-") (if arm64? "arm64" "x86_64"))
        extension (if darwin? ".dylib" ".so")]
    (when-not (every? #(fs/regular-file? (str "build/native/" platform "/jolt-" %
                                              "/lib/libjolt_" % extension))
                      ["crypto" "file" "parser"])
      (task! "build:jolt-native"))))


(defn- jolt-session
  [target extra-env command args]
  (apply jolt/run-terminal-child
         {:extra-env extra-env
          :identity-prefix jolt/identity-prefix
          :relay-stderr! (partial progress/relay! (some? (System/getenv "JOLT_TRACE_SOURCE")))
          :assert-identity! #(jolt/assert-child-target! {:target target :output %
                                                         :context "yin-repl"})}
         "sh" "-c" command "jolt" args))


(defn- gambit-session
  [args]
  (ensure-jolt!)
  (let [[jolt-home gsi] (-> (checked-shell {:out :string}
                                           "sh" "-c" (jolt/toolchain-command
                                                       "printf '%s\\n' \"$JOLT_HOME\" \"$JOLT_GAMBIT\""))
                            :out
                            str/split-lines)
        prefix (str (fs/parent (fs/parent gsi)))
        cache-dir (fs/absolutize "build/gambit-yin")
        _ (cache/local-path! cache-dir)
        ffi-entry (ffi/ensure! jolt-home prefix)
        _ (.println System/err "jolt-gambit: Checking compiled Yin boot (a cold build can take several minutes)")
        boot (-> (checked-shell {:dir jolt-home :out :string}
                                "python3" "host/gambit/build-yin-boot.py"
                                (str (fs/path prefix "bin/gsc")) (str cache-dir))
                 :out str/trim)
        runtime (ffi/stage-runtime! cache-dir jolt-home boot ffi-entry)
        driver (str (fs/path jolt-home "host/gambit/yin-repl-native.ss"))
        identity "(begin (display \"jolt-target: gambit\" (current-error-port)) (newline (current-error-port)) (force-output (current-error-port)))"]
    (.println System/err (str "jolt-gambit  " gambit-capabilities))
    (.println System/err gambit-blocking)
    (doseq [line gambit-startup]
      (.println System/err line))
    (jolt-session :gambit {"JOLT_GAMBIT_YIN_BOOT" boot
                           "JOLT_TRACE_SOURCE" "1"}
                  (jolt/launch-command {:target :gambit :cd? true})
                  (into [runtime "-e" identity driver (str (fs/absolutize "."))] args))))


(defn- launch
  [backend args]
  (case backend
    "jvm" (do (ensure-java-parsers!)
              (terminal-exit ["clj" "-M:clj-yin-repl"] args))
    "node" (do (when-not (fs/regular-file? "target/yin-repl.js")
                 (task! "build:yin-repl-node"))
               (terminal-exit ["node" "target/yin-repl.js"] args))
    "dart" (do (when-not (fs/regular-file? "build/yin-repl-peer")
                 (task! "build:yin-repl-peer"))
               (dart-session args))
    "jolt-chez" (do (ensure-jolt!)
                    (ensure-chez-native!)
                    (jolt/preflight! :chez "yin-repl")
                    (terminal-exit ["sh" "-c" (jolt/launch-command {:target :chez :alias "jolt-yin-repl"}) "jolt"]
                                   args))
    "jolt-gambit" (gambit-session args)))


(let [exit (try
             (let [[backend args] (parse-launch *command-line-args*)]
               (if (#{"-h" "--help"} (first args))
                 (do (println usage) (flush) 0)
                 (launch backend (if (= "--" (first args)) (rest args) args))))
             (catch Exception e
               (.println System/err (ex-message e))
               (or (:exit (ex-data e)) 1)))]
  (System/exit exit))
