;; bb smoke:jolt-target [--target chez|gambit] — the Lane B target-aware
;; launcher smoke (astra plan C5): resolve the launch command through the
;; jolt-launch seam, run a trivial child under the requested host scheme, and
;; assert the child's own "jolt-target: <target>" identity line before
;; trusting its arithmetic answer. Absent line or mismatch: nonzero exit
;; (fail-closed), so a Chez result can never stand in for a Gambit claim.
;;
;;   :chez   child: "$JOLT_HOME/bin/jolt" -e the probe form (the existing
;;           J0a invocation shape)
;;   :gambit child: "$JOLT_GAMBIT" -e the probe form (gsi as the fork's own
;;           host/gambit gates invoke it; README host/gambit/ffi)
;;
;; This is a launcher-seam smoke, not the Gambit FFI qualification — the
;; fork's Gambit tier is gated by its own make targets (gambitffi, gambitcheck,
;; ...) in the jolt checkout, not by `bin/jolt build`.
(ns jolt-target-smoke
  (:require [jolt-launch :as launch]
            [clojure.string :as str]))


(defn- fail!
  [& msg]
  (binding [*out* *err*]
    (println "smoke:jolt-target FAILED:" (apply str msg)))
  (System/exit 1))


;; :chez — bin/jolt -e evaluates a Clojure form (the J0a smoke shape);
;; :gambit — gsi -e evaluates Scheme (the fork's host/gambit gate shape).
;; Both print the child's own identity line, then the known value 3.
(def ^:private probe-forms
  {:chez "(do (println (str \"jolt-target: \" (or (System/getenv \"JOLT_TARGET\") \"chez\"))) (println (+ 1 2)))"
   :gambit "(begin (display \"jolt-target: \")(display (or (getenv \"JOLT_TARGET\") \"gambit\"))(newline)(display (+ 1 2))(newline))"})


(defn- run-probe
  [target]
  ;; With a nil script the seam builds the exact existing invocation shape —
  ;; chez: `"$JOLT_HOME/bin/jolt" -Srepro -M:jolt "$@"` (what `bb jolt` and
  ;; bb smoke:jolt use); gambit: `"$JOLT_GAMBIT" "$@"` — and the probe rides
  ;; the -e argument, so no per-target script is needed.
  (launch/run-child {:target target :script nil :alias nil}
                    "-e" (get probe-forms (launch/resolve-target target))))


(let [[target _args] (launch/parse-target-arg *command-line-args*)]
  (println "== smoke:jolt-target" (name target))
  (let [{:keys [exit out err]} (run-probe target)
        combined (str out err)]
    (println out)
    (when-not (zero? exit)
      (fail! "child exited " exit "\n" combined))
    ;; Fail-closed identity gate before trusting the child's answer.
    (launch/assert-child-target! {:target target :output combined
                                  :context "smoke:jolt-target"})
    (when-not (str/includes? (str/trim out) "3")
      (fail! "child did not answer 3: " (pr-str out)))
    (println "VERDICT target" (name target)
             "identity" (pr-str (launch/identity-line target))
             "child answered 3: ok")
    (System/exit 0)))
