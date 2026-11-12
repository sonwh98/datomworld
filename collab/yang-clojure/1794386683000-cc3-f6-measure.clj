;; cc3 F6 measurement — v2 bounded run (declaration:
;; collab/yang-clojure/1794390804000-cc3-bounded-run-declaration-v2.md).
;; Method (unchanged corrections from r4): (a) no structure is held by
;; a global Var — everything is built inside functions, held only by
;; locals, sampled while provably alive, released on return; (b) the
;; peak sampler spans lowering -> binding/projection/derives -> actual
;; serialization (canonical bytes) AND durable persistence
;; (materialize! into a file-backed store, closed) -> tagged
;; execution; (c) a discarded warmup + GC pin precedes measurement.
;; r5 changes: pass/fail is MAXIMUM-based (any sample over its ceiling
;; = EXCEEDED; medians are informational only); the acceptance holder
;; is the compiled-inclusive variant; repetitions are 5 retained per
;; variant and 3 pipeline runs, ALL samples recorded; every admission
;; takes a distinct ordinal within this one measurement run's scope.
(require 'yang.latte-oracle-test 'dao.jing 'yin.vm.semantic.linearize
         'yin.vm.semantic.derive 'yin.repl.store)


(import '(java.lang Runtime System)
        '(java.util UUID))


(def ^:private run-scope (str "cc3-f6-measure-v2-" (UUID/randomUUID)))
(def ^:private next-ordinal (atom 0))


(defn- admit-ordinal
  []
  (let [o @next-ordinal] (swap! next-ordinal inc) o))


(defn- used
  []
  (let [rt (Runtime/getRuntime)] (- (.totalMemory rt) (.freeMemory rt))))


(defn- gc-used
  []
  (System/gc) (Thread/sleep 50) (System/gc) (used))


(def ^:private ceilings-v2
  {:per-artifact-bytes 2000000
   :aggregate-storage-bytes 4000000
   :retained-aggregate 12000000
   :heap 1073741824 :time-ms 10000})


(defn- build-retained
  [compiled-inclusive?]
  ;; A fresh retained structure built and held ONLY by the caller's
  ;; local. Returns plain data; nothing here is def'd. The
  ;; compiled-inclusive variant also retains the compiled input and
  ;; the binding's reconstruction evidence (correspondence etc.).
  (let [kc @#'yang.latte-oracle-test/kernel-compile
        binding @#'yang.latte-oracle-test/cc3-oracle-binding
        c (kc {:id :cc3-binding :call '(latte-kernel.utils/vectorn? [1 2 3] 2)}
              run-scope (admit-ordinal))
        b (binding c)
        sem (yin.vm.semantic.linearize/project-rows (:tree b) nil (:rows b))
        stack (yin.vm.semantic.derive/stack-image
                (:vector sem) {:segment-address :seg/stack
                               :call-provenance (:call-provenance sem)})
        reg (yin.vm.semantic.derive/register-image
              (:vector sem) {:segment-address :seg/register
                             :call-provenance (:call-provenance sem)})
        relation {:yin.vm.provenance/schema 1
                  :yin.vm.provenance/admission (:origin b)
                  :yin.vm.provenance/root (:root (:tree b))
                  :yin.vm.provenance/semantic (:call-provenance sem)
                  :yin.vm.provenance/stack (:call-provenance stack)
                  :yin.vm.provenance/register (:call-provenance reg)}
        holder {:binding b :sem sem :stack stack :reg reg :relation relation}]
    (if compiled-inclusive? (assoc holder :compiled c) holder)))


(defn- measure-retained
  [compiled-inclusive?]
  ;; Baseline and retained samples are taken in this one scope; the
  ;; holder local is used AFTER the second sample so it cannot be
  ;; collected early. Returns [delta alive-check].
  (let [b0 (gc-used)
        holder (build-retained compiled-inclusive?)
        a (gc-used)
        alive (System/identityHashCode holder)]
    [(- a b0) alive]))


(defn- measure-pipeline
  []
  ;; Peak used-heap sampled every 2 ms across the whole pipeline,
  ;; INCLUDING serialization + durable persistence. All state local.
  (let [kc @#'yang.latte-oracle-test/kernel-compile
        binding @#'yang.latte-oracle-test/cc3-oracle-binding
        kernel-c @#'yang.latte-oracle-test/kernel-c
        peak (atom 0)
        stop (atom false)
        sampler (Thread. (fn []
                           (while (not @stop)
                             (swap! peak max (used))
                             (Thread/sleep 2))))
        stages (atom [])
        mark (fn [label] (swap! stages conj [label (used)]))
        t0 (System/currentTimeMillis)
        base (gc-used)]
    (.start sampler)
    (let [compiled (kc {:id :cc3-binding
                        :call '(latte-kernel.utils/vectorn? [1 2 3] 2)}
                       run-scope (admit-ordinal))
          _ (mark :after-compile)
          b (binding compiled)
          _ (mark :after-binding)
          sem (yin.vm.semantic.linearize/project-rows (:tree b) nil (:rows b))
          _ (mark :after-project-rows)
          stack (yin.vm.semantic.derive/stack-image
                  (:vector sem) {:segment-address :seg/stack
                                 :call-provenance (:call-provenance sem)})
          reg (yin.vm.semantic.derive/register-image
                (:vector sem) {:segment-address :seg/register
                               :call-provenance (:call-provenance sem)})
          _ (mark :after-derives)
          relation {:yin.vm.provenance/schema 1
                    :yin.vm.provenance/admission (:origin b)
                    :yin.vm.provenance/root (:root (:tree b))
                    :yin.vm.provenance/semantic (:call-provenance sem)
                    :yin.vm.provenance/stack (:call-provenance stack)
                    :yin.vm.provenance/register (:call-provenance reg)}
          record {:yin.repl.call-provenance/schema 1
                  :yin.repl.call-provenance/admission (:origin b)
                  :yin.repl.call-provenance/root (:root (:tree b))
                  :yin.repl.call-provenance/relation nil}
          ;; actual serialization, inside the sampled window
          rows-bytes (count (dao.jing/canonical-bytes (:rows b)))
          rel-bytes (count (dao.jing/canonical-bytes relation))
          _ (mark :after-serialization)
          ;; durable persistence, inside the sampled window: a
          ;; file-backed content store, materialized and closed
          dir (str "/tmp/cc3-f6-store-" (System/currentTimeMillis))
          store (yin.repl.store/open {:type :file :dir dir})
          rel-addr (dao.jing/materialize! store relation)
          rec-addr (dao.jing/materialize!
                     store (assoc record :yin.repl.call-provenance/relation rel-addr))
          record-bytes (count (dao.jing/canonical-bytes
                                (assoc record :yin.repl.call-provenance/relation rel-addr)))
          _ (yin.repl.store/close! store)
          _ (mark :after-persistence)
          c-walker (kernel-c compiled :c-walker)
          c-sem (kernel-c compiled :c-semantic)
          _ (mark :after-tagged-execution)
          elapsed (- (System/currentTimeMillis) t0)]
      (reset! stop true)
      (.join sampler)
      {:compiled-status (:status compiled)
       :c-status [(:status c-walker) (:status c-sem)]
       :rows-canonical-bytes rows-bytes
       :relation-canonical-bytes rel-bytes
       :record-canonical-bytes record-bytes
       :aggregate-storage-bytes (+ rows-bytes rel-bytes record-bytes)
       :store-dir dir :relation-address rel-addr :record-address rec-addr
       :heap-baseline-gc-used base
       :stage-used @stages
       :peak-used-during-pipeline @peak
       :peak-over-baseline (- @peak base)
       :elapsed-ms elapsed})))


;; warmup: build + persist + execute once, DISCARD, GC-pin, so the
;; measured runs do not charge class loading / JIT to the structures.
(build-retained true)
(measure-pipeline)
(gc-used)


(let [binding-only (mapv (fn [_] (first (measure-retained false))) (range 5))
      compiled-incl (mapv (fn [_] (first (measure-retained true))) (range 5))
      after-release (gc-used)
      pipes (mapv (fn [_] (measure-pipeline)) (range 3))
      median (fn [xs] (nth (sort xs) (quot (count xs) 2)))
      retained-max (apply max compiled-incl)
      binding-max (apply max binding-only)
      peak-max (apply max (map :peak-used-during-pipeline pipes))
      elapsed-max (apply max (map :elapsed-ms pipes))
      storage-max (apply max (map (fn [p]
                                    (max (:rows-canonical-bytes p)
                                         (:relation-canonical-bytes p))) pipes))
      aggregate-max (apply max (map :aggregate-storage-bytes pipes))]
  (println :run-scope run-scope)
  (println :retained-binding-only-samples binding-only)
  (println :retained-binding-only-max binding-max)
  (println :retained-binding-only-median-informational (median binding-only))
  (println :retained-compiled-inclusive-samples compiled-incl)
  (println :retained-compiled-inclusive-max retained-max)
  (println :retained-compiled-inclusive-median-informational (median compiled-incl))
  (println :after-release-gc-used after-release)
  (doseq [[i pipe] (map-indexed vector pipes)]
    (println :pipeline-repetition i)
    (doseq [[k v] (sort-by key pipe)] (println (str "  " k) v)))
  (println :ceilings-v2 ceilings-v2)
  ;; MAXIMUM-based pass/fail (v2 declaration section 4): any single
  ;; sample over its ceiling = EXCEEDED. Medians never decide.
  (println :under-ceilings-v2
           {:relation-bytes (<= storage-max (:per-artifact-bytes ceilings-v2))
            :rows-bytes (<= storage-max (:per-artifact-bytes ceilings-v2))
            :per-artifact-storage (<= storage-max (:per-artifact-bytes ceilings-v2))
            :aggregate-storage (<= aggregate-max (:aggregate-storage-bytes ceilings-v2))
            :retained-aggregate-max (<= retained-max (:retained-aggregate ceilings-v2))
            :retained-binding-only-max-informational (<= binding-max (:retained-aggregate ceilings-v2))
            :peak-heap-max (<= peak-max (:heap ceilings-v2))
            :elapsed-max (<= elapsed-max (:time-ms ceilings-v2))
            :all-c-lanes-completed (every? #(= [:completed :completed] (:c-status %)) pipes)}))


(System/exit 0)
