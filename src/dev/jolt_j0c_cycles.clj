;; J0c round 4, step D: repeated parse/free cycles from jolt with stable
;; native memory.
;;
;;   . build/tools/jolt-env.sh
;;   "$JOLT_HOME"/bin/jolt -Srepro -M:jolt src/dev/jolt_j0c_cycles.clj [cycles]
;;
;; Needs the test build (jolt_parser_heap_in_use: malloc's bytes in use for
;; the whole process). After a warm-up pass that fills one persistent worker's
;; caches over the corpus, runs `cycles` (default 5000) cst/parse-unit calls on
;; it, round-robin over every corpus source (ok and error packets), and every
;; 25th cycle also makes, uses and closes a fresh worker. A second phase runs
;; the same number of bare jolt_parser_export/jolt_parser_free cycles. Heap
;; samples are printed; growth across each phase must stay under 1 MiB.
(ns jolt-j0c-cycles
  (:require
    [clojure.edn :as edn]
    [jolt.ffi :as ffi]
    [yang.antlr.cst :as cst]))


;; J5: yang.antlr.cst loads the native library on first use; load it before
;; any raw jolt_parser_* binding below is called
(when-not (cst/available?)
  (println "FAIL the native parser library did not load")
  (System/exit 1))


(ffi/defcfn c-heap "jolt_parser_heap_in_use" [] :uint64)
(ffi/defcfn c-export "jolt_parser_export" [:pointer :pointer :size_t :pointer :pointer] :int :blocking)
(ffi/defcfn c-free "jolt_parser_free" [:pointer] :void)


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(def cycles (Long/parseLong (or (first *command-line-args*) "5000")))
(def sources (mapv :source (:entries (edn/read-string (slurp "test/yang/python/antlr/fixtures/jolt_j0c_corpus.edn")))))
(def profile {:grammar-id "jolt-j0c-cycles"})
(def bound (* 1024 1024))


(defn- settle
  []
  (jolt.host/gc-full!)
  (c-heap))


(def w (cst/make-worker profile))


(doseq [s sources] (cst/parse-unit w [:unit 0] s))
(let [fresh (cst/make-worker profile)] (cst/parse-unit fresh [:unit 0] (sources 0)) (cst/close-worker fresh))


(let [h0 (settle)
      samples (volatile! [[0 h0]])
      t0 (System/nanoTime)]
  (dotimes [i cycles]
    (cst/parse-unit w [:unit 0] (sources (mod i (count sources))))
    (when (zero? (mod i 25))
      (let [fresh (cst/make-worker profile)]
        (cst/parse-unit fresh [:unit 0] (sources (mod i (count sources))))
        (cst/close-worker fresh)))
    (when (zero? (mod (inc i) 1000))
      (vswap! samples conj [(inc i) (settle)])))
  (let [h1 (second (peek @samples))]
    (println "RECORD cst/parse-unit cycles heap samples [cycle bytes]" (pr-str @samples)
             {:seconds (long (/ (- (System/nanoTime) t0) 1e9))})
    (check (str cycles " cst/parse-unit cycles (+ " (quot cycles 25) " worker make/close) keep malloc growth under 1 MiB")
           (< (- h1 h0) bound) {:start h0, :end h1, :growth (- h1 h0)})))


(let [h (deref (:yang.antlr.cst/native w))
      encoded (mapv #(.getBytes ^String % "UTF-8") sources)
      out-cell (ffi/alloc :pointer)
      len-cell (ffi/alloc :size_t)
      h0 (settle)
      samples (volatile! [[0 h0]])]
  (dotimes [i cycles]
    (let [^bytes bs (encoded (mod i (count encoded)))
          n (alength bs)
          in (ffi/alloc (max 1 n))]
      (when (pos? n) (ffi/write-array in bs))
      (c-export h in n out-cell len-cell)
      (c-free (ffi/read out-cell :pointer))
      (ffi/free in))
    (when (zero? (mod (inc i) 1000))
      (vswap! samples conj [(inc i) (settle)])))
  (ffi/free out-cell)
  (ffi/free len-cell)
  (let [h1 (second (peek @samples))]
    (println "RECORD bare export/free cycles heap samples [cycle bytes]" (pr-str @samples))
    (check (str cycles " bare jolt_parser_export/jolt_parser_free cycles keep malloc growth under 1 MiB")
           (< (- h1 h0) bound) {:start h0, :end h1, :growth (- h1 h0)})))


;; J5 (known-gaps row 59): the convenience calls make and dispose a worker per
;; call (yang.python.antlr.parser/parse-source and first-failure, the REPL's
;; path); their native memory must stay flat as well
(require '[yang.python.antlr.parser :as parser])


(let [h0 (settle)
      samples (volatile! [[0 h0]])]
  (dotimes [i cycles]
    (let [src (sources (mod i (count sources)))]
      (try (parser/parse-source src) (catch Throwable _ nil))
      (parser/first-failure src))
    (when (zero? (mod (inc i) 1000))
      (vswap! samples conj [(inc i) (settle)])))
  (let [h1 (second (peek @samples))]
    (println "RECORD convenience parse-source/first-failure cycles heap samples [cycle bytes]" (pr-str @samples))
    (check (str cycles " convenience parse-source + first-failure calls (a worker made and disposed in each) keep malloc growth under 1 MiB")
           (< (- h1 h0) bound) {:start h0, :end h1, :growth (- h1 h0)})))


(cst/close-worker w)
(println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
(System/exit (if (zero? @failures) 0 1))
