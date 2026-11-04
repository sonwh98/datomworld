(ns d2-wedge-min-repro
  "The minimized reproduction of the H3-1 'wedge' — which is a false
  negative, not a cycle.

  What round 1 measured as 'the evaluator session terminates': a probe that
  built `semantic/create-vm` + `tu/make-observer-session` and called
  `observer/run-on-stream` — but never appended the fixture to the session
  medium (no `tu/queue-rows!`). An observer session over an empty medium
  loads nothing and runs nothing, and a fresh SemanticVM is `:halted? true`
  before any program (yin.vm.semantic/create-vm mints `:halted? true`), so
  the probe read 'halted, zero budget spent' off a machine that never
  executed an instruction.

  This file is the A/B/C pair that isolates it, each arm runnable alone:

    arm A  reproduces round 1's drive exactly — 0 steps, halted=true,
           the false negative in three lines
    arm B  adds the ONE missing line (tu/queue-rows!) and drives the same
           D2-compiled fixture through the same evaluator session: it
           completes under the 2M budget with the correct outcome —
           there is no non-terminating cycle in the evaluator session,
           the observer coordination, or any encoder layer
    arm C  shrinks the budget to 10000 steps and shows run-bounded's
           exhaustion throw PROPAGATES out of observer/run-on-stream
           (carry-session rethrows; it never feeds the loop), carrying
           the advanced machine under :consumer and the partial session
           under :session — the opt-in recovery data, not a retry cycle.

  The kernel-c semantic path (`run-guest-machine`) has no encoder session
  in it at any commit: it is tu/make-observer-session + tu/queue-rows! +
  observer/run-on-stream, single stage. The two C lanes differ only in
  machine construction and loader."
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
(def steps (atom 0))

(defn- counted-step
  [machine]
  (swap! steps inc)
  (original-step machine))

(defn- fixture-ast
  "The D2-compiled check-assume-have-discharge fixture, as kernel-c sees it."
  []
  (let [fixture (first (filter #(= :check-assume-have-discharge (:id %))
                               (:fixtures (deref #'latte/kernel-gate-corpus))))
        compiled ((deref #'latte/kernel-compile) fixture)]
    (when-not (= :compiled (:status compiled))
      (throw (ex-info "fixture no longer compiles" {:status (:status compiled)})))
    (:ast compiled)))

(defn- load-rows
  []
  (vm/fresh-code-loader
    (fn [m rows stamp]
      (vm/check-contract! vm/ast-contract stamp)
      (semantic/load-vector m
                            (:vector (linearize/project-rows rows))
                            vm/semantic-contract))
    vm/ast-contract))

(defn- bounded-run
  "run-guest-machine's runner, verbatim."
  [step-limit]
  (fn [machine]
    (loop [v machine n 0]
      (cond
        (or (vm/halted? v) (vm/blocked? v)) v
        (= n step-limit) (throw (ex-info "Guest instruction budget exhausted"
                                         {:lane :semantic :steps n :consumer v}))
        :else (recur (vm/step v) (inc n))))))

(defn- bounded [x]
  (let [s (pr-str x)]
    (if (> (count s) 200) (subs s 0 200) s)))

(defn- arm-a-round-1-drive
  []
  (reset! steps 0)
  (let [machine (semantic/create-vm {:make-stream tu/make-stream
                                     :capability-secret tu/secret
                                     :modules (deref #'compiler-test/library-modules)})
        session (tu/make-observer-session machine)] ;; medium is EMPTY
    (with-redefs [vm/step counted-step]
      (let [done (observer/run-on-stream session
                                         engine/ready-for-ingress?
                                         (load-rows)
                                         (bounded-run 2000000))]
        {:steps @steps
         :halted (vm/halted? (:consumer done))
         :blocked (vm/blocked? (:consumer done))
         :queue-line :absent}))))

(defn- arm-b-true-drive
  []
  (reset! steps 0)
  (let [ast (fixture-ast)
        machine (semantic/create-vm {:make-stream tu/make-stream
                                     :capability-secret tu/secret
                                     :modules ((deref #'latte/oracle-registry) yang/kind-module)})
        session (tu/make-observer-session machine)]
    (with-redefs [vm/step counted-step]
      (let [done (as-> session s
                  (tu/queue-rows! s ast) ;; <- the ONE line round 1 omitted
                  (observer/run-on-stream s engine/ready-for-ingress?
                                          (load-rows) (bounded-run 2000000)))
            m (:consumer done)]
        {:steps @steps
         :halted (vm/halted? m)
         :blocked (vm/blocked? m)
         :outcome (bounded (vm/value m))}))))

(defn- arm-c-exhaustion-propagates
  []
  (reset! steps 0)
  (let [ast (fixture-ast)
        machine (semantic/create-vm {:make-stream tu/make-stream
                                     :capability-secret tu/secret
                                     :modules ((deref #'latte/oracle-registry) yang/kind-module)})
        session (tu/queue-rows! (tu/make-observer-session machine) ast)]
    (try
      (with-redefs [vm/step counted-step]
        (observer/run-on-stream session engine/ready-for-ingress?
                                (load-rows) (bounded-run 10000))
        {:propagated false})
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)
              consumer (:consumer d)]
          {:propagated true
           :message (ex-message e)
           :data-has-consumer (contains? d :consumer)
           :data-has-session (contains? d :session)
           :consumer-advanced {:halted (:halted? consumer)
                               :blocked (:blocked? consumer)
                               :cont-depth (count (:k consumer))
                               :control (select-keys (:control consumer) [:segment :pc])}
           :cause-preserved (some? (ex-cause e))})))))

(defn- arm-d-gate-freeze-mechanism
  "What the gate does with the exhaustion throw: kernel-attempt's catch
  expands the exception data with `evidence-data` — dissoc'ing :session but
  NOT :consumer, which after a full 2M-step run is the entire advanced
  machine. This arm reproduces exactly that expansion in isolation, under a
  wall bound with a stack dump, so the gate's post-exhaustion freeze is
  attributable without running the corpus."
  []
  (let [ast (fixture-ast)
        modules ((deref #'latte/oracle-registry) yang/kind-module)]
    (try
      (compiler-test/run-guest-machine ast modules :walker 2000000)
      {:exhausted false}
      (catch clojure.lang.ExceptionInfo e
        (let [data (dissoc (ex-data e) :session)  ;; kernel-attempt's own dissoc
              evidence (deref #'latte/evidence-data)
              t0 (System/currentTimeMillis)
              result (atom {:finished false})
              worker (doto (Thread. (fn []
                                      (try
                                        (let [v (evidence data)]
                                          (reset! result
                                                  {:finished true
                                                   :wall-ms (- (System/currentTimeMillis) t0)
                                                   :walked-keys (count v)}))
                                        (catch Throwable t
                                          (reset! result
                                                  {:finished true
                                                   :wall-ms (- (System/currentTimeMillis) t0)
                                                   :threw (ex-message t)})))))
                          (.setName "arm-d-evidence-walk")
                          (.setDaemon true))]
          (.start worker)
          (loop [waited 0]
            (Thread/sleep 5000)
            (let [r @result]
              (cond
                (:finished r) r

                (>= waited 120000)
                (do (println :ARM-D-UNFINISHED-AFTER-MS waited)
                    (doseq [frame (take 25 (.getStackTrace worker))]
                      (println "  " (str frame)))
                    (flush)
                    {:finished false :capped-at-ms waited})

                :else (recur (+ waited 5000))))))))))

(defn- cycle-scan
  "Identity-based DFS over the same shapes evidence-data/postwalk traverse
  (maps, vectors, seqs, sets, records). Reports unique node count, the
  deepest sharing, and — decisively — whether any container re-enters a node
  already on the DFS path: that is a true cycle, on which clojure.walk's
  postwalk (no seen-set) never terminates. Sharing off the path is normal
  persistent-structure DAG sharing and terminates."
  [x]
  (let [seen (java.util.IdentityHashMap.)
        path (java.util.IdentityHashMap.)
        nodes (atom 0)
        shared (atom 0)
        cycle (atom nil)
        container? (fn [v] (or (map? v) (vector? v) (sequential? v) (set? v) (record? v)))
        children (fn [v]
                   (cond
                     (map? v) (concat (keys v) (vals v))
                     (record? v) (vals v)
                     :else (seq v)))]
    (letfn [(go [v depth]
              (when (> depth 200000) (throw (ex-info "scan depth cap" {:depth depth})))
              (when (container? v)
                (when (.containsKey path v)
                  (when-not @cycle (reset! cycle {:type (str (class v)) :at-depth depth}))
                  nil)
                (if (.containsKey seen v)
                  (do (swap! shared inc) nil)
                  (do (.put seen v true)
                      (.put path v true)
                      (swap! nodes inc)
                      (doseq [c (children v)] (go c (inc depth)))
                      (.remove path v)))))]
      (try
        (go x 0)
        (catch Throwable t {:scan-threw (ex-message t)}))
      {:unique-containers @nodes
       :shared-revisits @shared
       :cycle @cycle})))

(defn- arm-e-cycle-scan
  "Scan the exhausted machine itself (the :consumer from arm D's throw) for
  a cycle with the identity DFS above — the difference between an expensive
  finite expansion and a non-terminating one."
  []
  (let [ast (fixture-ast)
        modules ((deref #'latte/oracle-registry) yang/kind-module)]
    (try
      (compiler-test/run-guest-machine ast modules :walker 2000000)
      {:exhausted false}
      (catch clojure.lang.ExceptionInfo e
        (cycle-scan (:consumer (ex-data e)))))))

(defn -main
  [& _args]
  (println :PIN {:commit "8ea857c1+min-repro" :fixture :check-assume-have-discharge})
  (println :ARM-A (arm-a-round-1-drive))
  (println :ARM-B (arm-b-true-drive))
  (println :ARM-C (arm-c-exhaustion-propagates))
  (println :ARM-D (arm-d-gate-freeze-mechanism))
  (println :ARM-E (arm-e-cycle-scan))
  (flush))

(deftest test-h31-wedge-min-repro
  (testing "diagnostic reproduction only — contributes zero coverage"
    (let [a (arm-a-round-1-drive)
          _ (println :ARM-A a)
          b (arm-b-true-drive)
          _ (println :ARM-B b)
          c (arm-c-exhaustion-propagates)
          _ (println :ARM-C c)
          d (arm-d-gate-freeze-mechanism)
          _ (println :ARM-D d)
          e (arm-e-cycle-scan)
          _ (println :ARM-E e)]
      (is (zero? (:steps a)) "arm A reproduces the round-1 false negative: no step ever ran")
      (is (and (:halted a) (not (:blocked a)))
          "arm A's machine reads halted only because create-vm mints :halted? true")
      (is (and (:halted b) (pos? (:steps b)) (< (:steps b) 2000000))
          "arm B: the same session with the fixture queued completes under the budget")
      (is (and (:propagated c) (:data-has-consumer c) (:data-has-session c))
          "arm C: the budget throw escapes run-on-stream carrying its recovery data")
      (is (map? d) "arm D reports the post-exhaustion evidence walk's cost shape")
      (is (map? e) "arm E reports the exhausted machine's cycle scan")
      (flush))))
