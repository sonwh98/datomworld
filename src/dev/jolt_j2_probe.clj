;; The J2 staged load probe, run UNDER jolt one namespace at a time by
;; src/dev/jolt_j2_lane.clj (bb probe:jolt-j2), never by hand:
;;
;;   . build/tools/jolt-env.sh
;;   "$JOLT_HOME/bin/jolt" -Srepro -M:jolt:jolt-test src/dev/jolt_j2_probe.clj some.ns
;;
;; One fresh process per step, so an aborting load leaves the step's report in
;; the log the driver already captured. The step:
;;
;;   1. snapshots (loaded-libs), requires exactly one namespace, and prints
;;      PROBE <ns> load-ok <msec> | PROBE <ns> <failure-class> <detail>;
;;   2. classifies the first failure by walking the exception chain:
;;      require-failure (a dependency's source was not found; the failing
;;      namespace is named), unresolved-symbol (the message names the symbol),
;;      jolt-unimplemented (ex-data :jolt/unimplemented), host-refusal
;;      (ex-data :dao.host/refusal) or load-error (anything else);
;;   3. prints the sorted loaded-libs delta as ADDED [...] (the jolt_smoke
;;      closure pattern), and TRANSIT absent|present — dao.stream.transit must
;;      never enter a jolt closure (design 9.4; J2 gate d), so a present
;;      transit is a probe failure even when the load succeeded.
;;
;; The full exception chain is printed as ERROR/CAUSE lines for the step log;
;; exit status mirrors the step result.
(ns jolt-j2-probe)


(defn- chain
  "Throwable `e` and its causes, outermost first, at most `depth` deep."
  [e depth]
  (take depth (take-while some? (iterate ex-cause e))))


(defn- clip
  [^String s n]
  (subs s 0 (min n (count s))))


(defn- classify
  "The first failure class of require failure `e`: [class detail]."
  [e]
  (loop [[t & more] (chain e 8)]
    (if-not t
      [:load-error (clip (or (ex-message e) "<no message>") 160)]
      (let [m (or (ex-message t) "")
            d (ex-data t)]
        (cond
          (contains? d :jolt/unimplemented)
          [:jolt-unimplemented (pr-str (:jolt/unimplemented d))]

          (contains? d :dao.host/refusal)
          [:host-refusal (str (:dao.host/refusal d) " " (:dao.host/entry d))]

          ;; jolt: "Could not locate some/ns (or .clj/.cljc) on the source
          ;; roots" (java.io.FileNotFoundException)
          (re-find #"(?i)could not locate" m)
          [:require-failure
           (str (or (second (re-find #"(?i)could not locate ([^\s(]+)" m)) ""))]

          (re-find #"(?i)unresolved symbol|unable to resolve" m)
          [:unresolved-symbol
           (str (or (second (re-find #"(?i)unresolved symbol:? (\S+)" m))
                    (second (re-find #"(?i)unable to resolve symbol:? (\S+)" m))
                    ""))]

          :else (recur more))))))


(let [arg (first *command-line-args*)]
  (if-not arg
    (do (println "PROBE usage-error pass-one-namespace") (System/exit 2))
    (let [ns-sym (symbol arg)
          before (set (loaded-libs))
          t0 (System/nanoTime)
          e (try (require ns-sym) nil (catch Throwable t t))
          ms (long (/ (- (System/nanoTime) t0) 1e6))
          added (vec (sort (remove before (loaded-libs))))
          transit? (some #(= 'dao.stream.transit %) (loaded-libs))]
      (if (nil? e)
        (println "PROBE" ns-sym "load-ok" ms)
        (let [[class detail] (classify e)]
          (println "PROBE" ns-sym (name class) detail)))
      (when e
        (doseq [[i t] (map-indexed vector (chain e 8))]
          (println (if (zero? i) "ERROR" "CAUSE") i (pr-str (class t))
                   (pr-str (clip (or (ex-message t) "<no message>") 300)))))
      (println "ADDED" (pr-str added))
      (println "TRANSIT" (if transit? "present" "absent"))
      (System/exit (if (and (nil? e) (not transit?)) 0 1)))))
