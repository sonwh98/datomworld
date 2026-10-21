;; J0c round 4, step B: the child of src/dev/jolt_j0c_stack.clj (never run
;; inside a harness process: a stack overflow here kills only this process).
;;
;;   "$JOLT_HOME"/bin/jolt -Srepro -M:jolt src/dev/jolt_j0c_stack_child.clj \
;;     <shape> <main|thread> <full|native> <max-ms> <depth> [<depth> ...]
;;
;; full parses through cst/parse-unit (native parse, then the jolt decode and
;; the shared exporter); native calls jolt_parser_export only and frees the
;; buffer, so a crash there is the native recursion's.
;;
;; Prints `INFO {...}` with the parsing thread's stack (jolt_parser_thread_info,
;; read on that thread, before the first parse), then for each depth in order
;; parses the shape at that nesting depth with one worker and prints
;; `OK <depth> <outcome> <ms>` (flushed) or `ERR <depth> <message>`. A parse
;; slower than <max-ms> prints `SLOW <depth> <ms>` and ends the ladder (some
;; shapes grow super-linearly in time long before they overflow). A native
;; crash ends the process at the depth after the last OK line.
(ns jolt-j0c-stack-child
  (:require
    [clojure.string :as str]
    [jolt.ffi :as ffi]
    [yang.antlr.cst :as cst]))


(defn- rep
  [n s]
  (apply str (repeat n s)))


(def shapes
  {"parens" (fn [n] (str "x = " (rep n "(") "1" (rep n ")") "\n"))
   "list" (fn [n] (str "x = " (rep n "[") "1" (rep n "]") "\n"))
   "call" (fn [n] (str "x = " (rep n "f(") "1" (rep n ")") "\n"))
   "unary" (fn [n] (str "x = " (rep n "-") "1\n"))
   "not" (fn [n] (str "x = " (rep n "not ") "1\n"))
   "power" (fn [n] (str "x = " (rep n "2 ** ") "2\n"))
   "lambda" (fn [n] (str "x = " (rep n "lambda: ") "1\n"))
   "ternary" (fn [n] (str "x = " (rep n "1 if 1 else ") "1\n"))
   "indent" (fn [n]
              (str (apply str (for [i (range n)] (str (rep i " ") "if x:\n")))
                   (rep n " ") "pass\n"))})


(ffi/defcfn c-thread-info "jolt_parser_thread_info" [:pointer] :int :blocking)
(ffi/defcfn c-export "jolt_parser_export" [:pointer :pointer :size_t :pointer :pointer] :int :blocking)
(ffi/defcfn c-free "jolt_parser_free" [:pointer] :void)


(defn- native-only
  "jolt_parser_export alone: its status, the buffer freed unread."
  [w ^String src]
  (let [bs (.getBytes src "UTF-8")
        n (alength bs)
        in (ffi/alloc (max 1 n))
        out-cell (ffi/alloc :pointer)
        len-cell (ffi/alloc :size_t)]
    (try
      (ffi/write-array in bs)
      (let [status (c-export @(:yang.antlr.cst/native w) in n out-cell len-cell)
            p (ffi/read out-cell :pointer)]
        (when-not (ffi/null? p) (c-free p))
        (if (zero? status) :native-ok (str "ERR status " status)))
      (finally (ffi/free in) (ffi/free out-cell) (ffi/free len-cell)))))


(defn- thread-info
  []
  (let [cell (ffi/alloc 40)]
    (try
      (c-thread-info cell)
      (let [[size low high sp main] (mapv #(ffi/read cell :uint64 (* 8 %)) (range 5))]
        {:stack-bytes size, :low low, :high high, :sp sp,
         :used-at-call (- high sp), :main-thread (= 1 main)})
      (finally (ffi/free cell)))))


(defn- run
  [shape mode max-ms depths]
  (println "INFO" (pr-str (thread-info)))
  (flush)
  (let [make (shapes shape)
        w (cst/make-worker {:grammar-id "jolt-j0c-stack"})]
    (loop [[d & more] depths]
      (when d
        (let [src (make d)
              t0 (System/nanoTime)
              r (try (if (= "native" mode)
                       (native-only w src)
                       (:yang.cst/outcome (cst/parse-unit w [:unit 0] src)))
                     (catch Throwable e (str "ERR " (ex-message e) " " (pr-str (ex-data e)))))
              ms (long (/ (- (System/nanoTime) t0) 1e6))]
          (if (and (string? r) (str/starts-with? r "ERR"))
            (println "ERR" d r)
            (println "OK" d (name r) ms))
          (flush)
          (if (> ms max-ms)
            (do (println "SLOW" d ms) (flush))
            (recur more)))))
    (cst/close-worker w)))


(let [[shape where mode max-ms & ds] *command-line-args*
      max-ms (Long/parseLong max-ms)
      depths (mapv #(Long/parseLong %) ds)]
  (when-not (shapes shape)
    (println "unknown shape" shape (sort (keys shapes)))
    (System/exit 2))
  (if (= "thread" where)
    (let [t (Thread. #(run shape mode max-ms depths))]
      (.start t)
      (.join t))
    (run shape mode max-ms depths))
  (println "DONE")
  (System/exit 0))
