;; J5b (known-gaps row 59): one shape at given depths through the native
;; parser alone, with what its guards saw. A child of src/dev/jolt_j5_run.clj
;; and src/dev/jolt_j5_calibrate.clj (never run inside a harness process: an
;; unbounded build can exhaust the parse thread's stack here).
;;
;;   . build/tools/jolt-env.sh
;;   "$JOLT_HOME"/bin/jolt -Srepro -M:jolt src/dev/jolt_j5_measure.clj \
;;     [--lib <path>] [--probe] [--thread] <shape> <n> [<n> ...]
;;
;; --lib loads that library instead of the build's (the calibration build);
;; --probe paints the parse thread's stack and reports its high-water mark;
;; --thread makes the calls from a thread jolt starts (the jolt thread)
;; instead of the main thread. Prints, flushed, one line per depth:
;;
;;   M <shape> <n> {:status s :message m :rule r :look l :full f
;;                  :full-predictions p :high-water h :stack z :enforced e
;;                  :bytes b :ms t}
;;
;; then DONE. A crash ends the process after the last M line.
(ns jolt-j5-measure
  (:require
    [jolt.ffi :as ffi]
    [jolt-j5-shapes :as shapes]
    [yang.antlr.cst :as cst]))


(defn- options
  [args]
  (loop [[a b & more :as args] args, o {:depths []}]
    (cond (empty? args) o
          (= "--lib" a) (recur more (assoc o :lib b))
          (= "--probe" a) (recur (rest args) (assoc o :probe true))
          (= "--thread" a) (recur (rest args) (assoc o :thread true))
          (= "--fresh" a) (recur (rest args) (assoc o :fresh true))
          (nil? (:shape o)) (recur (rest args) (assoc o :shape a))
          :else (recur (rest args) (update o :depths conj (parse-long a))))))


(def opts (options *command-line-args*))


(if-let [lib (:lib opts)]
  (ffi/load-library lib)
  (when-not (cst/available?)
    (println "FAIL the native parser library did not load")
    (System/exit 1)))


(ffi/defcfn c-worker-new "jolt_parser_worker_new" [] :pointer)
(ffi/defcfn c-worker-free "jolt_parser_worker_free" [:pointer] :void)
(ffi/defcfn c-export "jolt_parser_export" [:pointer :pointer :size_t :pointer :pointer] :int :blocking)
(ffi/defcfn c-free "jolt_parser_free" [:pointer] :void)
(ffi/defcfn c-last-stats "jolt_parser_last_stats" [:pointer] :int)
(ffi/defcfn c-stack-probe "jolt_parser_stack_probe" [:int] :int)


(defn- last-stats
  []
  ;; a library without the seam (an older build, compared by --lib) answers {}
  (let [p (ffi/alloc 56)]
    (try (c-last-stats p)
         (zipmap [:rule :look :full :full-predictions :high-water :stack :enforced]
                 (vec (ffi/read-array p :uint64 7)))
         (catch Throwable _ {})
         (finally (ffi/free p)))))


(defn- export
  "jolt_parser_export alone: status and message (a packet is freed unread)."
  [w ^String src]
  (let [bs (.getBytes src "UTF-8")
        n (alength bs)
        in (ffi/alloc (max 1 n))
        out-cell (ffi/alloc :pointer)
        len-cell (ffi/alloc :size_t)]
    (try
      (when (pos? n) (ffi/write-array in bs))
      (let [status (c-export w in n out-cell len-cell)
            p (ffi/read out-cell :pointer)
            len (ffi/read len-cell :size_t)
            msg (when-not (ffi/null? p)
                  (try (when-not (zero? status)
                         (String. ^bytes (ffi/read-array p len) "UTF-8"))
                       (finally (c-free p))))]
        {:status status, :message msg, :bytes n})
      (finally (ffi/free in) (ffi/free out-cell) (ffi/free len-cell)))))


(defn- run
  [{:keys [shape depths probe fresh]}]
  (when probe (try (c-stack-probe 1) (catch Throwable _ nil)))
  (let [make (or (shapes/shapes shape)
                 (do (println "unknown shape" shape (sort (keys shapes/shapes)))
                     (System/exit 2)))
        shared (when-not fresh (c-worker-new))]
    (doseq [n depths]
      (let [src (make n)
            w (or shared (c-worker-new))
            t0 (System/nanoTime)
            r (export w src)
            ms (long (/ (- (System/nanoTime) t0) 1e6))]
        (println "M" shape n (pr-str (merge r (last-stats) {:ms ms})))
        (flush)
        (when-not shared (c-worker-free w))))
    (when shared (c-worker-free shared))))


(if (:thread opts)
  (let [t (Thread. #(run opts))]
    (.start t)
    (.join t))
  (run opts))


(println "DONE")
(flush)
(System/exit 0)
