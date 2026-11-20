;; Target-aware jolt launcher seam (Lane B, astra plan C5).
;;
;; One place resolves HOW a jolt child process is launched, from an explicit
;; target (default :chez, so every existing caller is unchanged):
;;
;;   :chez   -> ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\"
;;               -Srepro -M:jolt[...] script args..."
;;             (the invocation every existing launcher uses today)
;;   :gambit -> ". build/tools/jolt-env.sh && exec \"$JOLT_GAMBIT\" ..."
;;             (gsi resolved by bb bootstrap:jolt into JOLT_GAMBIT; the
;;             fork's own gates invoke the same binary directly, e.g.
;;             `sh host/gambit/ffi/watchdog.sh 120 "$GAMBIT_GSI"
;;             host/gambit/gambitcheck.ss` under GAMBIT_PREFIX — see
;;             host/gambit/ffi/README.md in the jolt checkout)
;;
;; Every launch exports JOLT_TARGET=<chez|gambit> into the child; the child
;; (or the driver on its behalf) prints the identity line
;; "jolt-target: <target>" and the driver ASSERTS the observed line equals the
;; requested target before trusting any pass/fail. Absent line or mismatch is
;; a failure (fail-closed): a Chez result can never qualify a Gambit claim.
;;
;; Babashka-only (bb script), like the rest of src/dev/jolt_*.
(ns jolt-launch
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))


(def known-targets #{:chez :gambit})


(defn resolve-target
  "Resolve `t` (nil, \"chez\", \"gambit\", :chez, :gambit) to a known target
   keyword; nil answers :chez (today's default). Anything else throws."
  [t]
  (let [t (cond
            (nil? t) :chez
            (keyword? t) t
            :else (keyword (str/trim (str t))))]
    (when-not (known-targets t)
      (throw (ex-info (str "unknown jolt target " (pr-str (str t))
                           " (known: " (pr-str (sort (map name known-targets))) ")")
                      {:target t})))
    t))


(defn parse-target-arg
  "Pull [--target <t> | --target=<t>] out of `args`; answers
   [target remaining-args]. Throws on an unknown target or a trailing bare
   --target with no value."
  [args]
  (loop [args (vec args) target nil acc []]
    (if (seq args)
      (let [a (first args)]
        (cond
          (= "--target" a)
          (do (when-not (seq (rest args))
                (throw (ex-info "--target needs a value (chez or gambit)"
                                {:arg a})))
              (recur (subvec args 2) (resolve-target (nth args 1)) acc))
          (str/starts-with? a "--target=") (recur (subvec args 1) (resolve-target (subs a 9)) acc)
          :else (recur (subvec args 1) target (conj acc a))))
      [(or target :chez) (vec acc)])))


(def identity-prefix "jolt-target: ")


(defn identity-line
  "The exact identity line the CHILD prints for `target`."
  [target]
  (str identity-prefix (name (resolve-target target))))


(defn child-target
  "The target a child reports for itself from its environment: JOLT_TARGET
   when set, :chez otherwise (every existing launcher is a Chez one)."
  []
  (resolve-target (System/getenv "JOLT_TARGET")))


(defn toolchain-command
  "Run a shell command with the bootstrapped Jolt toolchain."
  [command]
  (str ". build/tools/jolt-env.sh && " command))


(defn launch-command
  "The sh -c command string that runs `script` (a path relative to the
   repo root, or nil for stdin/-e style use) under `target`, with \"$@\"
   carrying extra arguments. `alias` extends the -M: alias for :chez
   (e.g. \"jolt-test\"). The child sees JOLT_TARGET so it can report its
   own identity. With :cd?, the first shell argument is the child's
   working directory, consumed before passing on the remaining arguments."
  [{:keys [target script alias cd?]}]
  (let [target (resolve-target target)
        alias (if (seq alias) (str ":" alias) "")
        script (if script (str " " script " "))
        script (or script " ")]
    (toolchain-command
      (str (when cd? "cd \"$1\" && shift && ")
           (case target
             :chez (str "export JOLT_TARGET=chez"
                        " && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt" alias script "\"$@\"")
             :gambit (str "export JOLT_TARGET=gambit"
                          " && exec \"$JOLT_GAMBIT\"" script "\"$@\""))))))


(defn run-child
  "Run `script` with `args` under `target` (default :chez) via
   launch-command; answers the babashka process result map with :out/:err
   strings. `dir` selects the working directory (default: repo root)."
  [{:keys [target script alias dir] :or {dir "."}} & args]
  (let [cmd (launch-command {:target target :script script :alias alias})
        r (apply p/shell {:dir dir :out :string :err :string :continue true}
                 "sh" "-c" cmd "jolt" (map str args))]
    {:exit (:exit r) :out (str (:out r)) :err (str (:err r))
     :target (resolve-target target)}))


(defn assert-child-target!
  "Fail-closed gate: `output` (the child's combined stdout+err) must contain
   the exact identity line of `target`. Throws with a diagnosis naming the
   observed line when the line is absent or names another host — the caller
   must not trust any pass/fail of such a child."
  [{:keys [target output context]}]
  (let [target (resolve-target target)
        want (identity-line target)
        observed (some #(when (str/starts-with? % identity-prefix)
                          (str/trim %))
                       (str/split-lines (str output)))
        ok? (and observed (= observed want))]
    (when-not ok?
      (throw (ex-info (str "jolt child target mismatch"
                           (when context (str " (" context ")"))
                           ": expected " (pr-str want)
                           ", observed " (pr-str observed)
                           " — refusing to trust this child's result")
                      {:wanted target :observed observed})))
    target))


(defn run-terminal-child
  "Run a REPL with terminal stdin and stdout. Relay stderr and validate
   the child's startup identity there before returning its exit status."
  [{:keys [extra-env identity-prefix assert-identity!]} & command]
  (let [child (apply p/process {:in :inherit :out :inherit :err :stream
                                :extra-env extra-env}
                     command)]
    (try
      (with-open [err (io/reader (:err child))]
        (let [identity (loop []
                         (when-let [line (.readLine err)]
                           (.println System/err line)
                           (if (str/starts-with? line identity-prefix)
                             line
                             (recur))))]
          (assert-identity! identity))
        (io/copy err System/err)
        (.flush System/err))
      (:exit @child)
      (finally
        (when (p/alive? child)
          (p/destroy child))))))


;; The child-side identity print, for repo scripts that run UNDER jolt (where
;; the bb seam namespace cannot load): scripts embed this one line near their
;; entry. Silent when JOLT_TARGET is unset, so dual-host scripts (JVM leg) and
;; legacy invocations keep their output byte-identical.
(def child-print-snippet
  "(when-let [t (System/getenv \"JOLT_TARGET\")] (println (str \"jolt-target: \" t)))")


(def ^:private identity-probe-forms
  {:chez "(do (println (str \"jolt-target: \" (or (System/getenv \"JOLT_TARGET\") \"chez\"))))"
   :gambit "(begin (display \"jolt-target: \")(display (or (getenv \"JOLT_TARGET\") \"gambit\"))(newline))"})


(defn identity-probe-form
  "The -e form a trivial identity child evaluates under `target` (chez:
   bin/jolt -e evaluates Clojure; gambit: gsi -e evaluates Scheme)."
  [target]
  (get identity-probe-forms (resolve-target target)))


(defn require-chez!
  "Fail-closed refusal for lanes whose children are Chez-tier only (the
   fork's Gambit FFI tier carries no native wrapper / build CLI yet —
   host/gambit/ffi/README.md): `lane` names the refusing driver in the error."
  [target lane]
  (when (= :gambit (resolve-target target))
    (binding [*out* *err*]
      (println lane "FAILED: target gambit is not qualified for this lane;"
               "the Gambit FFI tier does not carry it yet"
               "(jolt fork host/gambit/ffi/README.md); refusing to run"))
    (System/exit 1)))


(defn preflight!
  "Fail-closed identity preflight for a driver: run a trivial child through
   the same launch seam and assert it reports `target` itself, before any
   real child's pass/fail is trusted. `context` names the driver in errors.
   Set :identity-opt-out true for a call that runs only JVM transcript children."
  ([target context] (preflight! target context {}))
  ([target context {:keys [identity-opt-out]}]
   (let [t (resolve-target target)]
     (when-not (true? identity-opt-out)
       (let [r (run-child {:target t} "-e" (identity-probe-form t))]
         (assert-child-target! {:target t
                                :output (str (:out r) (:err r))
                                :context context})
         (println "PASS identity gate: child reported" (identity-line t))))
     t)))
