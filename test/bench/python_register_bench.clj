(ns bench.python-register-bench
  "CPython vs yang.python on the yin.vm register kernel
   (`yin.vm.debruijn.register`, src/cljc/yin/vm/debruijn/register.cljc):
   the same Python source, run by both, timed per run.

   The workload is the `recursion-test` fib from
   `test/yang/python/antlr/e2e_test.clj`: recursive `fib`, then a single
   `print`. On the yang.python side the program goes through the real
   pipeline (parser stage -> lowering stage with the bundled prelude ->
   linearization -> `derive/register-image`) and the register VM runs the
   image.

   Timed regions mirror `bench.yin-vm-bench` (the compile stays outside;
   the VM is a persistent value, so one loaded image is re-run):
     * register load  = `rvm/create-vm` over the compiled image
     * register run   = `vm/run` + value/render on a loaded VM
     * cpython run    = the same `main()` (fib + print) called N times in
       one process, mean wall time per call, stdout discarded
     * cpython full   = the whole `python3 prog.py` process, startup and
       compile included, for the one-time-compile context

   JVM:
     clj -Sdeps '{:aliases {:pybench {:extra-paths [\"test\"]}}}'
       -M:pybench -m bench.python-register-bench 15
   The argument is the fib `n` (default 15)."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.integer :as integer]
            [yin.vm.module :as module]
            [yin.vm.semantic.derive :as derive]
            [yin.vm.semantic.linearize :as sr-linearize]
            [yin.vm.test-utils :as tu]
            [yang.python.antlr.lower :as lower]
            [yang.python.antlr.parser :as parser]
            [yang.python.antlr.prelude :as prelude]
            [yang.python.antlr.render :as render]))


;; =============================================================================
;; The workload
;; =============================================================================

(defn fib-source
  "The Python program: the e2e `recursion-test` fib with `n`."
  [n]
  (str "def fib(n):\n"
       "    if n < 2:\n"
       "        return n\n"
       "    return fib(n - 1) + fib(n - 2)\n"
       (format "print(fib(%d))\n" n)))


(defn fib-value
  [n]
  (loop [i 0 a 0 b 1]
    (if (>= i n) a (recur (inc i) b (+ a b)))))


;; =============================================================================
;; The yang.python -> register-image pipeline (as in e2e_test run-python)
;; =============================================================================

(def integer-limits
  {::integer/max-bits 100000, ::integer/max-digits 4300})


(def data-limits
  {::data/max-items 1048576})


(defn host-registry
  []
  (prelude/admit
    (-> (module/empty-registry)
        module/register-cell-module
        (data/register-data-module data-limits)
        (prelude/register-integer-module integer-limits))))


(defn- source-events
  [unit text]
  [{:yang.source/event :yang.source/chunk,
    :yang.source/unit unit,
    :yang.source/ordinal 0,
    :yang.source/text text}
   {:yang.source/event :yang.source/seal,
    :yang.source/unit unit,
    :yang.source/chunk-count 1}])


(defn- run-member
  [{:yin/keys [batch root]}]
  (nth batch root))


(defn compile-image
  "The register image for Python `source`: parse, lower (bundled prelude),
   project, derive. Returns {:ast :image}; a lowering diagnostic throws."
  [source]
  (let [registry (host-registry)
        src (tu/new-memory-log)
        cst (tu/new-memory-log)
        program (tu/new-memory-log)
        diagnostics (tu/new-memory-log)]
    (doseq [e (source-events [:bench 0] source)] (stream/append! src e))
    (parser/step-stage (parser/open-stage src cst))
    (lower/step-stage (lower/open-stage cst program diagnostics
                                        {:medium lower/program-medium
                                         :max-digits (::integer/max-digits
                                                       integer-limits)
                                         :prelude :bundled}))
    (when-let [problems (seq (tu/drain diagnostics))]
      (throw (ex-info "Lowering diagnostics" {:problems (vec problems)})))
    (let [ast (run-member (first (tu/drain program)))]
      {:ast ast
       :image (derive/register-image (:vector (sr-linearize/project ast)))
       :registry registry})))


(defn vm-opts
  [registry]
  {:make-stream tu/make-stream
   :capability-secret tu/secret
   :primitives vm/primitives
   :modules registry
   :contract vm/register-contract})


(defn load-vm
  [bench]
  (rvm/create-vm (:image bench) (vm-opts (:registry bench))))


(defn run-once
  "One whole run: load the image into a fresh VM, run it, render the output."
  [bench]
  (render/output (vm/value (vm/run (load-vm bench)))))


;; =============================================================================
;; Timing
;; =============================================================================

(defn measure
  "Mean ms per call over `runs` timed calls after `warmups`, printed with
   min/max for context."
  [label warmups runs f]
  (dotimes [_ warmups] (f))
  (let [starts (volatile! [])
        ends (volatile! [])
        samples (dotimes [i runs]
                  (vswap! starts conj (System/nanoTime))
                  (f)
                  (vswap! ends conj (System/nanoTime)))
        ms (mapv #(/ (- (nth @ends %) (nth @starts %)) 1e6) (range runs))
        mean (/ (double (reduce + ms)) runs)]
    (printf "%-28s mean %10.3f ms  min %10.3f  max %10.3f%n"
            label mean (apply min ms) (apply max ms))
    (flush)
    mean))


(defn- python-program
  "The exact same Python source CPython is asked to run as a program."
  [n]
  (fib-source n))


(defn- cpython-in-process-ms
  "Mean ms for one `main()` (fib + print) in a warm CPython process."
  [n runs]
  (let [script (str "import sys, time, os\n"
                    "sys.stdout = open(os.devnull, 'w')\n"
                    (python-program n)
                    (format "def main():\n    print(fib(%d))\n" n)
                    "for _ in range(3):\n    main()\n"
                    "t0 = time.perf_counter()\n"
                    (format "for _ in range(%d):\n    main()\n" runs)
                    "dt = (time.perf_counter() - t0) * 1000.0 / " runs "\n"
                    "sys.stdout.close()\n"
                    "sys.stdout = sys.__stdout__\n"
                    "print(dt)\n")
        {:keys [exit out err]} (sh "python3" "-c" script)]
    (when-not (zero? exit)
      (throw (ex-info "CPython in-process run failed" {:exit exit :err err})))
    (Double/parseDouble (str/trim out))))


(defn- cpython-process-ms
  "Mean ms of a full `python3 prog.py` (startup + compile + run)."
  [n runs]
  (let [path (str (System/getProperty "java.io.tmpdir") "/bench_fib_" n ".py")]
    (spit path (python-program n))
    (let [script (str "import subprocess, time\n"
                      "times = []\n"
                      (format "for _ in range(%d):\n" runs)
                      "    t0 = time.perf_counter()\n"
                      (format "    subprocess.run(['python3', '%s'], stdout=subprocess.DEVNULL)\n"
                              path)
                      "    times.append((time.perf_counter() - t0) * 1000.0)\n"
                      "print(sum(times) / len(times))\n")
          {:keys [exit out err]} (sh "python3" "-c" script)]
      (when-not (zero? exit)
        (throw (ex-info "CPython process run failed" {:exit exit :err err})))
      (Double/parseDouble (str/trim out)))))


(defn -main
  [& args]
  (let [n (if (seq args) (Long/parseLong (first args)) 15)
        runs (if (> (count args) 1) (Long/parseLong (second args)) 5)
        source (fib-source n)
        expected (str (fib-value n))]
    (println (str "\n=== workload: fib(" n ") == " expected ", runs=" runs " ==="))
    (flush)

    ;; compile once, outside every timed region
    (let [t0 (System/nanoTime)
          bench (compile-image source)
          compile-ms (/ (- (System/nanoTime) t0) 1e6)]
      (printf "%-28s %10.3f ms (one time)%n" "yang.python compile" compile-ms)

      ;; correctness of the whole path, once
      (let [out (run-once bench)]
        (when-not (= {:py/out [expected], :py/exception nil} out)
          (throw (ex-info "Register VM disagreed with the expected output"
                          {:out out, :expected expected}))))

      ;; timed: load, full run (load + run), and cpython
      ;; (the correctness run above already warmed the JVM paths)
      (let [load-ms (measure "register load (create-vm)" 0 runs #(load-vm bench))
            run-ms (measure "register load+run+render" 0 runs #(run-once bench))
            py-run (cpython-in-process-ms n (* runs 50))
            py-proc (cpython-process-ms n (max 3 runs))]
        (println "\n=== results (ms per program run) ===")
        (printf "  yang.python register VM, load+run+render : %10.3f%n" run-ms)
        (printf "  yang.python register VM, run+render      : %10.3f%n" (- run-ms load-ms))
        (printf "  cpython in-process main()                : %10.3f%n" py-run)
        (printf "  cpython full process (startup+compile)   : %10.3f%n" py-proc)
        (printf "  ratio register(run) / cpython(in-proc)   : %10.1fx%n"
                (/ (- run-ms load-ms) py-run))
        (printf "  ratio register(load+run) / cpython(proc) : %10.1fx%n"
                (/ run-ms py-proc)))
      (flush))
    (shutdown-agents)))
