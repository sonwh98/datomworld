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
   [target remaining-args]. Throws on an unknown target."
  [args]
  (loop [args (vec args) target nil acc []]
    (if (seq args)
      (let [a (first args)]
        (cond
          (= "--target" a) (recur (subvec args 2) (resolve-target (nth args 1 nil)) acc)
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


(defn launch-command
  "The sh -c command string that runs `script` (a path relative to the
   repo root, or nil for stdin/-e style use) under `target`, with \"$@\"
   carrying extra arguments. `alias` extends the -M: alias for :chez
   (e.g. \"jolt-test\"). The child sees JOLT_TARGET so it can report its
   own identity."
  [{:keys [target script alias]}]
  (let [target (resolve-target target)
        alias (if (seq alias) (str ":" alias) "")
        script (if script (str " " script " "))
        script (or script " ")]
    (case target
      :chez (str ". build/tools/jolt-env.sh && export JOLT_TARGET=chez"
                 " && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt" alias script "\"$@\"")
      :gambit (str ". build/tools/jolt-env.sh && export JOLT_TARGET=gambit"
                   " && exec \"$JOLT_GAMBIT\"" script "\"$@\""))))


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
