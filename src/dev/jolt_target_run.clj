;; bb -cp src/dev src/dev/jolt_target_run.clj — the Lane B generic
;; target-aware jolt task driver (astra plan C5). Two modes:
;;
;;   --script <path> [--alias <alias>] -- <child args...>
;;       Run the repo script as a jolt child through the jolt-launch seam
;;       (the same command string the task used before, plus the exported
;;       JOLT_TARGET), ASSERT the child's own "jolt-target: <target>" line
;;       fail-closed, and propagate its exit only after the gate passes.
;;       The child prints the line via the seam's child-print-snippet.
;;
;;   (no --script) -- <args passed to the jolt binary...>
;;       Passthrough (the old `bb jolt` behavior): an arbitrary child's
;;       output cannot be forced to carry the identity line, so the driver
;;       runs the fail-closed identity preflight child first and only then
;;       passes the arguments through unchanged.
;;
;; --target chez (default) or --target gambit. Babashka-only.
(ns jolt-target-run
  (:require [jolt-launch :as jlt]))


(let [[target args] (jlt/parse-target-arg *command-line-args*)
      [mode script alias child-args]
      (loop [xs args script nil alias nil]
        (cond
          (nil? (seq xs)) [:passthrough nil nil []]
          (= "--script" (first xs)) (recur (nnext xs) (second xs) alias)
          (= "--alias" (first xs)) (recur (nnext xs) script (second xs))
          (= "--" (first xs)) (if script
                                [:script script alias (vec (rest xs))]
                                [:passthrough nil nil (vec (rest xs))])
          :else [:passthrough script alias (vec xs)]))]
  (println "== jolt-target-run" (name target)
           (when script (str "--script " script)))
  (if (= :script mode)
    ;; Known repo child: assert the child's own identity line before
    ;; trusting its exit. The child is a Clojure script, so :gambit (a bare
    ;; gsi) cannot run it — refused fail-closed; use bb smoke:jolt-target
    ;; for the Gambit host-scheme smoke.
    (do (jlt/require-chez! target (str "jolt-target-run --script " script))
        (let [{:keys [exit out err]} (jlt/run-child {:target target
                                                     :script script
                                                     :alias alias}
                                                    child-args)
              combined (str out err)]
          (print out)
          (binding [*out* *err*] (print err))
          (jlt/assert-child-target! {:target target :output combined
                                     :context (str "jolt-target-run --script " script)})
          (when-not (zero? exit)
            (println "jolt-target-run: child exited" exit))
          (System/exit exit)))
    ;; Passthrough: preflight identity gate, then hand the arguments to the
    ;; child unchanged.
    (let [{:keys [exit out err]} (jlt/run-child {:target target}
                                                "-e" (jlt/identity-probe-form target))]
      (jlt/assert-child-target! {:target target
                                 :output (str out err)
                                 :context "jolt-target-run passthrough preflight"})
      (println out)
      (when-not (zero? exit)
        (println "jolt-target-run: preflight child exited" exit)
        (System/exit exit))
      (println "PASS identity gate: child reported" (jlt/identity-line target))
      ;; The real child, byte-identical command shape, exit propagated.
      (let [{:keys [exit out err]} (apply jlt/run-child
                                          {:target target}
                                          child-args)]
        (print out)
        (binding [*out* *err*] (print err))
        (System/exit exit)))))
