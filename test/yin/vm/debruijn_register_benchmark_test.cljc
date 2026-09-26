(ns yin.vm.debruijn-register-benchmark-test
  "R3 (docs/design/yin.vm.debruijn.register.md S6, 'R3: benchmark
   report'): an informational report comparing the de Bruijn stack VM
   (`yin.vm.debruijn.stack`) with the real R4 register kernel
   (`yin.vm.debruijn.register`) over identical pure-program workloads.

   Reported per workload and machine: throughput (mean ms per run),
   lowering cost (`lower-stack` vs `lower-register` over one shared
   resolved-tuple set), image size (instruction count and canonical
   encoded bytes), load time (`create-vm`), and step counts with peak
   frame and continuation depth as the portable allocation proxy.

   The numbers gate nothing and no threshold is defined for them. The
   only assertions are B0-normalized value parity between the two
   machines on every workload, and that each metric was collected."
  (:require [clojure.test :refer [deftest is testing]]
            [yin.vm :as vm]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-linearize :as linearize]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as register-compile]
            [yin.vm.debruijn-resolve :as resolve]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.parity-test :as parity]
            [yin.vm.test-utils :as tu])
  #?(:cljd (:import ["dart:core" DateTime])
     :clj (:import [java.io File]
                   [jdk.jfr Configuration Recording]
                   [jdk.jfr.consumer RecordingFile])))


;; =============================================================================
;; AST helpers
;; =============================================================================

(defn- lit
  [x]
  {:type :literal, :value x})


(defn- v
  [sym]
  {:type :variable, :name sym})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- tail
  [node]
  (assoc node :tail? true))


(defn- if-node
  [test-node cons-node alt-node]
  {:type :if, :test test-node, :consequent cons-node, :alternate alt-node})


;; =============================================================================
;; Workloads
;; =============================================================================

(defn tail-countdown-ast
  "Self-passing tail-recursive countdown from `n` to 0: loop throughput,
   tail-call dispatch, and frame reuse."
  [n]
  (let [self-fn (lam '[self n]
                     (tail (if-node (app (v '<) (v 'n) (lit 1))
                                    (lit 0)
                                    (tail (app (v 'self)
                                               (v 'self)
                                               (app (v '-) (v 'n) (lit 1)))))))]
    (tail (app self-fn self-fn (lit n)))))


(defn fibonacci-ast
  "Self-passing doubly recursive fib: call/return overhead, frame
   allocation, and binary arithmetic."
  [n]
  (let [rec (fn [k]
              (app (v 'self) (v 'self) (app (v '-) (v 'n) (lit k))))
        self-fn (lam '[self n]
                     (tail (if-node (app (v '<) (v 'n) (lit 2))
                                    (v 'n)
                                    (tail (app (v '+) (rec 1) (rec 2))))))]
    (tail (app self-fn self-fn (lit n)))))


(defn lexical-binding-ast
  "`depth` nested let-shaped lambda applications binding x0..x(depth-1),
   whose innermost body sums every binding: bound-variable access at
   every lexical depth."
  [depth]
  (let [names (mapv #(symbol (str "x" %)) (range depth))
        sum (reduce (fn [acc sym] (app (v '+) acc (v sym)))
                    (v (first names))
                    (rest names))]
    (reduce (fn [body i]
              (tail (app (lam [(nth names i)] body) (lit (inc i)))))
            (tail sum)
            (range (dec depth) -1 -1))))


(def ^:private corpus-sample
  "Representative pure-program rows of the B0 parity corpus."
  #{"literal map" "addition" "nested application" "if true branch"
    "if false branch" "lambda application" "closure value"
    "higher order" "collection primitives"})


(def workloads
  "`[label ast iterations]`: `iterations` is the timed run count."
  (into [["tail-countdown" (tail-countdown-ast 500) 10]
         ["fibonacci" (fibonacci-ast 12) 10]
         ["lexical-binding" (lexical-binding-ast 8) 50]]
        (comp (filter (fn [[label _ _]] (contains? corpus-sample label)))
              (map (fn [[label ast _]] [(str "corpus: " label) ast 50])))
        parity/corpus))


;; =============================================================================
;; Timing (the only host-specific part)
;; =============================================================================

(defn- now-ms
  []
  #?(:cljd (/ (.-microsecondsSinceEpoch (DateTime/now)) 1000.0)
     :clj (/ (System/nanoTime) 1e6)
     :cljs (if (exists? js/performance)
             (.now js/performance)
             (.now js/Date))))


(defn- mean-ms
  "Mean wall-clock ms per call of `f` over `iterations` calls, after one
   warmup call."
  [iterations f]
  (f)
  (let [start (now-ms)]
    (dotimes [_ iterations] (f))
    (/ (- (now-ms) start) iterations)))


;; =============================================================================
;; Measurement
;; =============================================================================

(def ^:private vm-opts
  {:make-stream tu/make-stream, :primitives vm/primitives})


(def ^:private step-cap 10000000)


(defn- step-profile
  "Step `vm` to completion, counting steps and tracking peak frame and
   continuation depth. Refuses to spin past `step-cap`."
  [vm]
  (loop [vm vm, steps 0, frames 0, cont 0]
    (let [frames (max frames (count (:frames vm)))
          cont (max cont (count (:continuation vm)))]
      (cond (or (vm/halted? vm) (vm/blocked? vm))
            {:vm vm, :steps steps, :peak-frames frames, :peak-cont cont}
            (>= steps step-cap)
            (throw (ex-info "Benchmark workload exceeded the step cap"
                            {:steps steps}))
            :else (recur (vm/step vm) (inc steps) frames cont)))))


(defn- encoded-bytes
  "Size of a canonical encoding as H and R hash it: `jing/sha256` digests
   the string's UTF-8 bytes, and both encoders emit ASCII here (hex
   operands; the register encoder also carries mnemonic text), so the
   string's length is its byte count."
  [encoding]
  (count encoding))


(defn- machine
  "The two machines, each as `{:lower, :count, :encode, :create}` over
   the shared resolved-tuple set."
  [kind]
  (case kind
    :stack {:lower linearize/lower-stack,
            :count count,
            :encode dcode/encode-image,
            :create #(dvm/create-vm %1 (assoc %2 :contract
                                              vm/stack-contract))}
    :register {:lower register-compile/lower-register,
               :count (comp count :instructions),
               :encode rcode/encode-register-image,
               :create #(rvm/create-vm %1 (assoc %2 :contract
                                                 vm/register-contract))}))


(defn- measure
  [kind resolved iterations]
  (let [{:keys [lower encode create], count-of :count} (machine kind)
        image (:image (lower resolved))
        run-once #(vm/run (create image vm-opts))
        profile (step-profile (create image vm-opts))]
    {:value (b0/normalize (vm/value (run-once))),
     :stepped-value (b0/normalize (vm/value (:vm profile))),
     :instructions (count-of image),
     :bytes (encoded-bytes (encode image)),
     :lower-ms (mean-ms iterations #(lower resolved)),
     :load-ms (mean-ms iterations #(create image vm-opts)),
     :run-ms (mean-ms iterations run-once),
     :steps (:steps profile),
     :peak-frames (:peak-frames profile),
     :peak-cont (:peak-cont profile)}))


(defn- measure-workload
  [[label ast iterations]]
  (let [resolved (resolve/resolve (vm/ast->datoms ast))]
    {:label label,
     :iterations iterations,
     :stack (measure :stack resolved iterations),
     :register (measure :register resolved iterations)}))


;; =============================================================================
;; Report
;; =============================================================================

(defn- fmt-ms
  [x]
  (str (/ (int (* 1000.0 x)) 1000.0)))


(defn- ratio
  [num den]
  (if (pos? den) (fmt-ms (/ num den)) "n/a"))


(defn- report-row
  [machine-label m]
  (println (str "  " machine-label
                " run=" (fmt-ms (:run-ms m)) "ms"
                " lower=" (fmt-ms (:lower-ms m)) "ms"
                " load=" (fmt-ms (:load-ms m)) "ms"
                " instrs=" (:instructions m)
                " bytes=" (:bytes m)
                " steps=" (:steps m)
                " peak-frames=" (:peak-frames m)
                " peak-cont=" (:peak-cont m))))


(defn- report!
  [{:keys [label iterations stack register]}]
  (println (str "\n[R3] " label " (" iterations " timed runs, value "
                (pr-str (:value stack)) ")"))
  (report-row "stack   " stack)
  (report-row "register" register)
  (println (str "  register/stack: run="
                (ratio (:run-ms register) (:run-ms stack))
                " lower=" (ratio (:lower-ms register) (:lower-ms stack))
                " load=" (ratio (:load-ms register) (:load-ms stack))
                " steps=" (ratio (:steps register) (:steps stack))
                " bytes=" (ratio (:bytes register) (:bytes stack)))))


;; =============================================================================
;; Tests
;; =============================================================================

(deftest workload-values-test
  (testing "the workloads compute their known values on both machines"
    (let [expected {"tail-countdown" 0, "fibonacci" 144,
                    "lexical-binding" 36}]
      (doseq [[label ast _] workloads
              :when (contains? expected label)]
        (testing label
          (is (= (get expected label)
                 (b0/normalize
                   (vm/value (vm/run (dvm/create-vm
                                       (:image (linearize/adapt
                                                 (vm/ast->datoms ast)))
                                       (assoc vm-opts :contract
                                              vm/stack-contract)))))
                 (b0/normalize
                   (vm/value (vm/run (rvm/create-vm
                                       (:image (register-compile/adapt
                                                 (vm/ast->datoms ast)))
                                       (assoc vm-opts :contract
                                              vm/register-contract))))))))))))


(deftest corpus-sample-present-test
  (testing "every sampled corpus row exists in the B0 parity corpus"
    (is (= (count corpus-sample)
           (count (filter #(contains? corpus-sample (first %))
                          parity/corpus))))))


(deftest benchmark-report-test
  (println "\n[R3] de Bruijn stack VM vs register VM (informational)")
  (doseq [workload workloads]
    (let [{:keys [label stack register] :as result}
          (measure-workload workload)]
      (testing label
        (testing "B0-normalized parity between stack and register VMs"
          (is (= (:value stack) (:value register)))
          (is (= (:value stack) (:stepped-value stack)))
          (is (= (:value register) (:stepped-value register))))
        (testing "every metric was collected"
          (doseq [m [stack register]]
            (is (pos? (:instructions m)))
            (is (pos? (:bytes m)))
            (is (pos? (:steps m)))
            (is (not (neg? (:run-ms m))))
            (is (not (neg? (:lower-ms m))))
            (is (not (neg? (:load-ms m)))))))
      (report! result))))


;; =============================================================================
;; JFR Profiling (JVM only)
;; =============================================================================

#?(:clj
   (defn run-with-jfr!
     "Runs `f` inside an active Java Flight Recorder recording using the
      specified configuration (defaulting to 'profile', falling back to
      'default'). Dumps the recording to `target/jfr/<name>.jfr` and returns
      the File."
     ([recording-name f]
      (run-with-jfr! recording-name "profile" f))
     ([recording-name config-name f]
      (let [config (try (Configuration/getConfiguration config-name)
                        (catch Throwable _
                          (Configuration/getConfiguration "default")))
            rec (doto (Recording. config)
                  (.setName (str "yin-vm-" recording-name))
                  (.start))]
        (let [file (File. (str "target/jfr/" recording-name ".jfr"))]
          (try
            (f)
            (finally
              (.stop rec)
              (.. file getParentFile mkdirs)
              (.dump rec (.toPath file))
              (.close rec)))
          file)))))


#?(:clj
   (defn summarize-jfr
     "Reads a `.jfr` file and counts total events, CPU execution samples,
      and object allocation events."
     [^File jfr-file]
     (with-open [rf (RecordingFile. (.toPath jfr-file))]
       (loop [events 0, allocs 0, samples 0]
         (if (.hasMoreEvents rf)
           (let [event (.readEvent rf)
                 name (.. event getEventType getName)]
             (recur (inc events)
                    (if (.contains name "Allocation") (inc allocs) allocs)
                    (if (.contains name "ExecutionSample")
                      (inc samples)
                      samples)))
           {:jfr-file (.getPath jfr-file),
            :total-events events,
            :execution-samples samples,
            :allocation-events allocs})))))


(deftest jfr-benchmark-profiling-test
  #?(:cljd (is true "JFR is JVM-only")
     :cljs (is true "JFR is JVM-only")
     :clj
     (testing "JFR captures execution profiles for Stack and Register VMs"
       (let [countdown-ast (tail-countdown-ast 2000)
             resolved (resolve/resolve (vm/ast->datoms countdown-ast))
             stack-img (:image (linearize/lower-stack resolved))
             reg-img (:image (register-compile/lower-register resolved))
             run-stack (fn []
                         (dotimes [_ 20]
                           (vm/run (dvm/create-vm stack-img
                                                  (assoc vm-opts :contract
                                                         vm/stack-contract)))))
             stack-file (run-with-jfr! "stack-vm-countdown" run-stack)
             stack-sum (summarize-jfr stack-file)
             run-reg (fn []
                       (dotimes [_ 20]
                         (vm/run (rvm/create-vm reg-img
                                                (assoc vm-opts :contract
                                                       vm/register-contract)))))
             reg-file (run-with-jfr! "register-vm-countdown" run-reg)
             reg-sum (summarize-jfr reg-file)]
         (is (.exists stack-file))
         (is (.exists reg-file))
         (is (pos? (.length stack-file)))
         (is (pos? (.length reg-file)))
         (is (pos? (:total-events stack-sum)))
         (is (pos? (:total-events reg-sum)))
         (println "\n[R3 JFR Profiling Report]")
         (println (str "  Stack VM JFR:    " (:jfr-file stack-sum)
                       " (events=" (:total-events stack-sum)
                       ", samples=" (:execution-samples stack-sum)
                       ", allocs=" (:allocation-events stack-sum) ")"))
         (println (str "  Register VM JFR: " (:jfr-file reg-sum)
                       " (events=" (:total-events reg-sum)
                       ", samples=" (:execution-samples reg-sum)
                       ", allocs=" (:allocation-events reg-sum) ")"))))))
