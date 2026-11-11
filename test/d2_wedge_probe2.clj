(ns d2-wedge-probe2
  "H3-1 wedge diagnostic, round 2: drive the REAL kernel-c semantic path.

  Round 1's probe (test/d2_wedge_probe.clj) never queued the fixture onto
  the session medium — `tu/make-observer-session` mints an EMPTY medium and
  the probe called no queue variant — so its evaluator-session drive was
  vacuous: a fresh SemanticVM is `:halted? true` (yin.vm.semantic/create-vm)
  before any step, and run-on-stream over an empty medium loads nothing and
  runs nothing. Stage A below reproduces that drive and measures its step
  count to confirm the false negative.

  Stage B re-measures the exact kernel-c semantic path —
  `yang.clojure-test/run-guest-machine`'s semantic branch
  (tu/make-observer-session + tu/queue-rows! + observer/run-on-stream +
  run-bounded over vm/step) — instrumented at vm/step (count, wall time,
  machine entering each step) and at the run-on-stream boundary (ready
  checks, batches loaded, run entries with per-run step deltas), with a
  watchdog that separates the three failure shapes:

    - BUDGET-THROWN   run-bounded's 2M exhaustion throw reaches the caller
    - WEDGE-IN-STEP   step count frozen: one vm/step never returns; the
                      worker stack trace plus the entering machine are dumped
    - RATE-COLLAPSE   steps still advance but steps/sec decays toward zero;
                      the per-tick rate table plus a final snapshot are dumped"
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream.observer :as observer]
            [yang.clojure :as yang]
            [yang.clojure-test :as compiler-test]
            [yang.latte-oracle-test :as latte]
            [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.semantic :as semantic]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.test-utils :as tu]))

(def ^:private original-step vm/step)
(def ^:private original-run-on-stream observer/run-on-stream)

;; ---------------------------------------------------------------- instrumentation

(def steps (atom 0))
(def in-step-nanos (atom 0))
(def recent-durations (atom []))          ;; last 64 step durations, nanos
(def last-machine (volatile! nil))        ;; machine entering the latest step
(def load-count (atom 0))
(def ready-count (atom 0))
(def run-log (atom []))                   ;; one entry per run-on-stream `run`
(def start-ms (atom nil))

(defn- reset-instrumentation!
  []
  (reset! steps 0)
  (reset! in-step-nanos 0)
  (reset! recent-durations [])
  (vreset! last-machine nil)
  (reset! load-count 0)
  (reset! ready-count 0)
  (reset! run-log [])
  (reset! start-ms (System/currentTimeMillis)))

(defn- counted-step
  [machine]
  (let [n (swap! steps inc)]
    (when (= n 1) (println :STEP-STARTED) (flush))
    (vreset! last-machine machine)
    (let [t0 (System/nanoTime)
          v (original-step machine)
          t1 (System/nanoTime)
          d (- t1 t0)]
      (swap! in-step-nanos + d)
      (swap! recent-durations (fn [r] (vec (take-last 64 (conj r d)))))
      v)))

(defn- instrumented-run-on-stream
  [session ready? load run]
  (let [ready?* (fn [c] (swap! ready-count inc) (ready? c))
        load* (fn [c batch] (swap! load-count inc) (load c batch))
        run* (fn [c]
               (let [before @steps]
                 (try
                   (let [v (run c)]
                     (swap! run-log conj {:entry-steps before
                                          :exit-steps @steps
                                          :threw false})
                     v)
                   (catch Throwable t
                     (swap! run-log conj {:entry-steps before
                                          :exit-steps @steps
                                          :threw (ex-message t)})
                     (throw t)))))]
    (original-run-on-stream session ready?* load* run*)))

;; ---------------------------------------------------------------- state capture

(defn- bounded-str
  [x]
  (let [s (pr-str x)]
    (if (> (count s) 240) (str (subs s 0 240) "…") s)))

(defn- instruction-at
  "The register instruction the machine's :control points at — op and
  operands, bounded. The instruction vector is static code, so its size is
  the compiler's, not the machine's."
  [machine]
  (try
    (let [{:keys [segment pc]} (:control machine)
          image (get (:code machine) segment)]
      (if (and image (number? pc) (vector? (:vector image)))
        (bounded-str (try (nth (:vector image) pc)
                          (catch Exception e
                            {:nth-failed (ex-message e)})))
        {:segment segment :pc pc :image (nil? image)}))
    (catch Exception e {:unreadable (ex-message e)})))

(defn- machine-snapshot
  "Bounded view of one machine: no closures, no environments, no stores."
  [m]
  (when m
    (let [k (:k m)
          top (when (vector? k) (peek k))]
      {:halted? (:halted? m)
       :blocked? (:blocked? m)
       :cont-depth (if (vector? k) (count k) (when k 1))
       :control (when-let [c (:control m)] (select-keys c [:segment :pc]))
       :control-inst (instruction-at m)
       :top-frame (when (map? top) (select-keys top [:type :segment :pc :rd]))
       :wait-set (count (or (:wait-set m) []))
       :ready-queue (count (or (:ready-queue m) []))
       :window-size (count (:window m))
       :value (bounded-str (:value m))})))

(defn- duration-stats
  []
  (let [rs @recent-durations]
    (when (seq rs)
      (let [sorted (sort rs)]
        {:n (count sorted)
         :min-ns (first sorted)
         :p50-ns (nth sorted (quot (count sorted) 2))
         :max-ns (last sorted)}))))

;; ---------------------------------------------------------------- stage A

(defn- compile-fixture
  "Compile `fixture` under scoped admission coordinates (the r5
   pattern): this composition allocates ONE fresh admission run and
   admits the fixture as that run's next admission, so the compilation
   retains its run scope and distinct ordinal as plain data
   (`:admission-origin` / `:admission-scope` / `:admission-ordinal`).
   Every stage here is a separate run composition and allocates a
   fresh scope; no shared default identity is restored."
  [fixture]
  (let [run ((deref #'latte/fresh-admission-run) :d2-wedge-probe2)]
    ((deref #'latte/kernel-compile-in-run) run fixture)))


(defn- make-load-rows
  []
  (vm/fresh-code-loader
    (fn [m rows stamp]
      (vm/check-contract! vm/ast-contract stamp)
      (semantic/load-vector m
                            (:vector (linearize/project-rows rows))
                            vm/semantic-contract))
    vm/ast-contract))

(defn- old-probe-bounded-run
  "Round 1's runner, verbatim semantics."
  [machine]
  (loop [v machine n 0]
    (cond
      (or (vm/halted? v) (vm/blocked? v)) v
      (= n 2000000)
      (throw (ex-info "Guest instruction budget exhausted"
                      {:lane :semantic :steps n :consumer v}))
      :else (recur (vm/step v) (inc n)))))

(defn- stage-a-vacuity
  "Reproduce round 1's evaluator-session drive exactly — including its
  missing queue step — and measure how many steps it actually executed."
  []
  (println :STAGE-A :round-1-probe-reproduction
           {:medium :empty-at-start :queue :absent-as-in-round-1})
  (flush)
  (reset-instrumentation!)
  (let [fixture (first (filter #(= :check-assume-have-discharge (:id %))
                               (:fixtures (deref #'latte/kernel-gate-corpus))))
        ;; round 1 compiled the fixture and never used the result; the call
        ;; itself is kept so stage A matches that run's cost shape
        _ (compile-fixture fixture)
        lm (deref #'compiler-test/library-modules)
        machine (semantic/create-vm {:make-stream tu/make-stream
                                     :capability-secret tu/secret
                                     :modules lm})
        session (tu/make-observer-session machine)]
    (with-redefs [vm/step counted-step
                  observer/run-on-stream instrumented-run-on-stream]
      (let [done (observer/run-on-stream session
                                         engine/ready-for-ingress?
                                         (make-load-rows)
                                         old-probe-bounded-run)]
        (println :STAGE-A-RESULT
                 {:steps @steps
                  :loads @load-count
                  :halted (vm/halted? (:consumer done))
                  :blocked (vm/blocked? (:consumer done))
                  :verdict (if (zero? @steps)
                             :VACUOUS-no-program-ever-loaded
                             :REAL-round1-was-valid)})
        (flush)))))

;; ---------------------------------------------------------------- stage B

(def ^:private tick-ms 10000)
(def ^:private wall-cap-ms 1800000)
(def ^:private frozen-ticks 3)

(defn- print-tick
  [t steps-prev]
  (let [n @steps
        in-step-ms (quot @in-step-nanos 1000000)
        elapsed (quot (- (System/currentTimeMillis) @start-ms) 1000)
        m @last-machine
        k (:k m)
        stats (duration-stats)
        rate (if (>= steps-prev 0)
               (quot (* (- n steps-prev) 1000) t)
               :first-tick)]
    (println :TICK {:s elapsed :steps n :in-step-ms in-step-ms
                    :steps-per-s-last-tick rate
                    :step-ns stats
                    :cont-depth (when (vector? k) (count k))
                    :loads @load-count :runs (count @run-log)})
    (flush)
    n))

(defn- dump-state
  [worker label]
  (println label {:steps @steps
                  :in-step-ms (quot @in-step-nanos 1000000)
                  :loads @load-count
                  :ready-checks @ready-count
                  :runs @run-log
                  :entering-machine (machine-snapshot @last-machine)})
  (when worker
    (println :WORKER-STACK)
    (doseq [frame (take 60 (.getStackTrace worker))]
      (println "  " (str frame)))
    (flush)))

(defn- stage-b-real-path
  []
  (println :STAGE-B :real-kernel-c-semantic-path
           {:driver 'yang.clojure-test/run-guest-machine
            :fixture :check-assume-have-discharge
            :step-limit 2000000
            :wall-cap-ms wall-cap-ms})
  (flush)
  (reset-instrumentation!)
  (let [fixture (first (filter #(= :check-assume-have-discharge (:id %))
                               (:fixtures (deref #'latte/kernel-gate-corpus))))
        compiled (compile-fixture fixture)
        _ (when-not (= :compiled (:status compiled))
            (println :STAGE-B-BLOCKED-AT-COMPILE
                     (select-keys compiled [:status :blocked-by]))
            (flush))
        ast (:ast compiled)
        modules ((deref #'latte/oracle-registry) yang/kind-module)
        result (atom {:kind :none})
        worker (Thread. (fn []
                          (try
                            (let [machine (compiler-test/run-guest-machine
                                            ast modules :semantic 2000000)]
                              (reset! result {:kind :returned
                                              :machine (machine-snapshot machine)
                                              :value (bounded-str (vm/value machine))}))
                            (catch Throwable t
                              (reset! result
                                      {:kind :threw
                                       :class (str (class t))
                                       :message (ex-message t)
                                       :data-keys (vec (keys (ex-data t)))
                                       :consumer (machine-snapshot
                                                   (:consumer (ex-data t)))
                                       :session-consumer (machine-snapshot
                                                           (:consumer
                                                             (:session (ex-data t))))})))))]
    (.setName worker "wedge-worker")
    (.setDaemon worker true)
    (with-redefs [vm/step counted-step
                  observer/run-on-stream instrumented-run-on-stream]
      (.start worker)
      (loop [prev -1 frozen 0]
        (Thread/sleep (long tick-ms))
        (let [alive (.isAlive worker)
              n (when alive (print-tick tick-ms prev))]
          (cond
            (not alive)
            (do (println :WORKER-DONE (assoc @result
                                             :steps @steps
                                             :in-step-ms (quot @in-step-nanos 1000000)
                                             :loads @load-count
                                             :ready-checks @ready-count
                                             :runs @run-log))
                (flush)
                (condp = (:kind @result)
                  :returned (System/exit 0)
                  :threw (System/exit 1)
                  (System/exit 4)))

            (>= (- (System/currentTimeMillis) @start-ms) (long wall-cap-ms))
            (do (dump-state worker :RATE-COLLAPSE-WALL-CAP) (System/exit 2))

            (and n (= n prev))
            (do (when (>= (inc frozen) frozen-ticks)
                  (dump-state worker :WEDGE-CONFIRMED-IN-STEP)
                  (System/exit 3))
                (recur (or n prev) (inc frozen)))

            :else (recur (or n prev) 0)))))))

(defn -main
  [& _args]
  (println :PIN {:commit "8ea857c1+probe2"
                 :fixture :check-assume-have-discharge
                 :lane :semantic
                 :budget "2M instrumented steps"
                 :jvm (str (System/getProperty "java.version"))})
  (flush)
  (stage-a-vacuity)
  (stage-b-real-path))

(deftest test-h31-wedge-probe2
  (testing "diagnostic probe only — contributes zero coverage"
    (-main)
    (is true)))

;; ---------------------------------------------------------------- stage C

;; Stage B showed the fixture's own semantic cell completes (1.1M steps,
;; seconds of stepping) when driven alone. The reported stalls were observed
;; on the whole-corpus gate, so stage C drives the gate itself — every
;; fixture's A/B/compile/walker/semantic legs — under timing wrappers and
;; the vm/step counter, with freeze detection that dumps the worker stack
;; wherever the run stops advancing (C lane stepping, host A/B eval, or
;; anywhere else).

(def ^:private gate-frozen-ticks 30)          ;; 5 min without a step
(def ^:private gate-wall-cap-ms 5400000)      ;; the longest observed trial

(defn- wall-ms-since
  [t0]
  (- (System/currentTimeMillis) t0))

(defn- stage-c-gate
  []
  (println :STAGE-C :full-differential-gate
           {:test 'yang.latte-oracle-test/test-kernel-differential-gate
            :freeze-ticks gate-frozen-ticks
            :wall-cap-ms gate-wall-cap-ms})
  (flush)
  (reset-instrumentation!)
  (let [gate-t0 (System/currentTimeMillis)
        leg (fn [label f]
              (fn [& args]
                (let [t0 (System/currentTimeMillis)
                      before @steps
                      r (apply f args)]
                  (println :LEG label
                           {:wall-ms (- (System/currentTimeMillis) t0)
                            :steps (- @steps before)
                            :arg0 (bounded-str (first args))})
                  (flush)
                  r)))
        orig-kernel-a (deref #'latte/kernel-a)
        orig-kernel-b (deref #'latte/kernel-b)
        orig-kernel-compile (deref #'latte/kernel-compile)
        orig-kernel-c (deref #'latte/kernel-c)
        orig-kernel-row (deref #'latte/kernel-row)
        kernel-c* (fn [compiled lane]
                    (let [before @steps]
                      (println :C-ENTER {:lane lane
                                         :at-steps before
                                         :total-s (quot (wall-ms-since gate-t0) 1000)})
                      (flush)
                      (let [t0 (System/currentTimeMillis)
                            r (orig-kernel-c compiled lane)]
                        (println :C-EXIT {:lane lane
                                          :wall-ms (- (System/currentTimeMillis) t0)
                                          :steps (- @steps before)
                                          :status (:status r)
                                          :outcome-tag (-> r :outcome first)})
                        (flush)
                        r)))
        result (atom {:kind :none})
        worker (Thread. (fn []
                          (try
                            ;; test-var output goes to this process's stdout
                            ;; on purpose: the gate's own KERNEL-FIXTURE lines
                            ;; are the live mid-fixture progress signal.
                            (clojure.test/test-var
                              #'yang.latte-oracle-test/test-kernel-differential-gate)
                            (reset! result {:kind :returned
                                            :wall-ms (wall-ms-since gate-t0)})
                            (catch Throwable t
                              (reset! result {:kind :threw
                                              :class (str (class t))
                                              :message (ex-message t)
                                              :wall-ms (wall-ms-since gate-t0)})))))]
    (.setName worker "wedge-worker")
    (.setDaemon worker true)
    (with-redefs-fn {#'vm/step counted-step
                     #'observer/run-on-stream instrumented-run-on-stream
                     #'latte/kernel-a (leg :a orig-kernel-a)
                     #'latte/kernel-b (leg :b orig-kernel-b)
                     #'latte/kernel-compile (leg :compile orig-kernel-compile)
                     #'latte/kernel-c kernel-c*
                     #'latte/kernel-row (leg :row orig-kernel-row)}
      (fn []
        (.start worker)
        (loop [prev -1 frozen 0]
          (Thread/sleep (long tick-ms))
          (let [alive (.isAlive worker)
                n (when alive (print-tick tick-ms prev))]
            (cond
              (not alive)
              (do (println :GATE-DONE (assoc @result
                                             :steps @steps
                                             :in-step-ms (quot @in-step-nanos 1000000)
                                             :wall-ms (wall-ms-since gate-t0)
                                             :runs @run-log))
                  (flush)
                  (condp = (:kind @result)
                    :returned (System/exit 0)
                    :threw (System/exit 1)
                    (System/exit 4)))

              (>= (- (System/currentTimeMillis) gate-t0) (long gate-wall-cap-ms))
              (do (dump-state worker :GATE-WALL-CAP-REACHED) (System/exit 2))

              (and n (= n prev))
              (do (when (>= (inc frozen) gate-frozen-ticks)
                    (dump-state worker :GATE-FROZEN-5MIN)
                    (System/exit 3))
                  (recur (or n prev) (inc frozen)))

              :else (recur (or n prev) 0))))))))

(deftest test-h31-wedge-probe2-gate
  (testing "diagnostic probe only — contributes zero coverage"
    (stage-c-gate)
    (is true)))

;; ---------------------------------------------------------------- stage D

;; The in-gate freeze sat in check-assume-have-discharge's WALKER cell: it
;; consumed exactly its whole 2,000,000-step budget and then the run never
;; returned while no step advanced. Stage D drives that one cell in
;; isolation — same driver, same instrumentation — to (a) confirm the
;; exhaustion reproduces without the corpus context and (b) capture the
;; bounded exhaustion state: the advanced :consumer's halted/blocked flags,
;; continuation depth, control, and the top frame.

(defn- stage-d-walker
  []
  (println :STAGE-D :walker-cell-in-isolation
           {:driver 'yang.clojure-test/run-guest-machine
            :fixture :check-assume-have-discharge
            :step-limit 2000000})
  (flush)
  (reset-instrumentation!)
  (let [fixture (first (filter #(= :check-assume-have-discharge (:id %))
                               (:fixtures (deref #'latte/kernel-gate-corpus))))
        compiled (compile-fixture fixture)
        ast (:ast compiled)
        modules ((deref #'latte/oracle-registry) yang/kind-module)
        result (atom {:kind :none})
        worker (Thread. (fn []
                          (try
                            (let [machine (compiler-test/run-guest-machine
                                            ast modules :walker 2000000)]
                              (reset! result {:kind :returned
                                              :machine (machine-snapshot machine)}))
                            (catch Throwable t
                              (reset! result
                                      {:kind :threw
                                       :class (str (class t))
                                       :message (ex-message t)
                                       :data-keys (vec (keys (ex-data t)))
                                       :consumer (machine-snapshot
                                                   (:consumer (ex-data t)))
                                       :session-consumer (machine-snapshot
                                                           (:consumer
                                                             (:session (ex-data t))))})))))]
    (.setName worker "wedge-worker")
    (.setDaemon worker true)
    (with-redefs [vm/step counted-step
                  observer/run-on-stream instrumented-run-on-stream]
      (.start worker)
      (loop [prev -1 frozen 0]
        (Thread/sleep (long tick-ms))
        (let [alive (.isAlive worker)
              n (when alive (print-tick tick-ms prev))]
          (cond
            (not alive)
            (do (println :WORKER-DONE (assoc @result
                                             :steps @steps
                                             :in-step-ms (quot @in-step-nanos 1000000)
                                             :loads @load-count
                                             :runs @run-log))
                (flush)
                (condp = (:kind @result)
                  :returned (System/exit 0)
                  :threw (System/exit 1)
                  (System/exit 4)))

            (>= (- (System/currentTimeMillis) @start-ms) (long wall-cap-ms))
            (do (dump-state worker :RATE-COLLAPSE-WALL-CAP) (System/exit 2))

            (and n (= n prev))
            (do (when (>= (inc frozen) frozen-ticks)
                  (dump-state worker :WEDGE-CONFIRMED-IN-STEP)
                  (System/exit 3))
                (recur (or n prev) (inc frozen)))

            :else (recur (or n prev) 0)))))))

(deftest test-h31-wedge-probe2-walker
  (testing "diagnostic probe only — contributes zero coverage"
    (stage-d-walker)
    (is true)))

;; ---------------------------------------------------------------- stage F

;; The walker cell now exhausts 2M (stage D) while the semantic cell
;; completes at 1.1M (stage B). How far over the budget is the walker?
;; One bounded diagnostic trial at 8M — the H3-1 round's authorized trial
;; ladder — reporting the walker's true completion count, or a second
;; exhaustion. Diagnostic configuration only: NOT an acceptance-budget
;; proposal.

(defn- stage-f-walker-8m
  []
  (println :STAGE-F :walker-cell-8m-trial
           {:driver 'yang.clojure-test/run-guest-machine
            :fixture :check-assume-have-discharge
            :step-limit 8000000})
  (flush)
  (reset-instrumentation!)
  (let [fixture (first (filter #(= :check-assume-have-discharge (:id %))
                               (:fixtures (deref #'latte/kernel-gate-corpus))))
        compiled (compile-fixture fixture)
        ast (:ast compiled)
        modules ((deref #'latte/oracle-registry) yang/kind-module)
        result (atom {:kind :none})
        worker (Thread. (fn []
                          (try
                            (let [machine (compiler-test/run-guest-machine
                                            ast modules :walker 8000000)]
                              (reset! result {:kind :returned
                                              :steps @steps
                                              :machine (machine-snapshot machine)
                                              :value (bounded-str (vm/value machine))}))
                            (catch Throwable t
                              (reset! result
                                      {:kind :threw
                                       :class (str (class t))
                                       :message (ex-message t)
                                       :steps @steps})))))]
    (.setName worker "wedge-worker")
    (.setDaemon worker true)
    (with-redefs [vm/step counted-step
                  observer/run-on-stream instrumented-run-on-stream]
      (.start worker)
      (loop [prev -1 frozen 0]
        (Thread/sleep (long tick-ms))
        (let [alive (.isAlive worker)
              n (when alive (print-tick tick-ms prev))]
          (cond
            (not alive)
            (do (println :WORKER-DONE (assoc @result
                                             :in-step-ms (quot @in-step-nanos 1000000)
                                             :loads @load-count
                                             :runs @run-log))
                (flush)
                (condp = (:kind @result)
                  :returned (System/exit 0)
                  :threw (System/exit 1)
                  (System/exit 4)))

            (>= (- (System/currentTimeMillis) @start-ms) (long wall-cap-ms))
            (do (dump-state worker :RATE-COLLAPSE-WALL-CAP) (System/exit 2))

            (and n (= n prev))
            (do (when (>= (inc frozen) frozen-ticks)
                  (dump-state worker :WEDGE-CONFIRMED-IN-STEP)
                  (System/exit 3))
                (recur (or n prev) (inc frozen)))

            :else (recur (or n prev) 0)))))))

(deftest test-h31-wedge-probe2-walker-8m
  (testing "diagnostic probe only — contributes zero coverage"
    (stage-f-walker-8m)
    (is true)))
