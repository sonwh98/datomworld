;; J0c round 5: fault injection over the native acquisitions of
;; yang.antlr.cst (Architect J1b-merge item 2's cst.jolt sites; the approach
;; of native/jolt-crypto/probes/faults.clj on jolt-j1b).
;;
;;   bb jolt:run -Srepro -M:jolt src/dev/jolt_j0c_faults.clj
;;
;; jolt.ffi/alloc, write-array, read, read-array and free are redefined to
;; record every pointer and to fail the Nth allocation, copy, read or
;; array read, or the free of the Nth allocation (it throws WITHOUT freeing:
;; a leak the injection itself causes). The wrapper entries cst calls
;; privately are wrapped too: c-export records the buffer the wrapper handed
;; out, c-free records its release (or throws without releasing),
;; c-worker-new/c-worker-free record the worker handle, and clojure.core/atom
;; can fail while make-worker builds the worker around its handle.
;;
;; Each scenario checks:
;;   * every jolt allocation is freed exactly once (apart from an injected
;;     failing free);
;;   * every buffer the wrapper handed out is released exactly once through
;;     jolt_parser_free (apart from an injected failing release);
;;   * every worker handle is either owned by the answered worker or freed
;;     exactly once;
;;   * the call throws the injected failure, or (no injection) answers what
;;     the uninstrumented call answers.
;; Prints PASS/FAIL per scenario; exits nonzero on any FAIL.
(ns jolt-j0c-faults
  (:require
    [jolt.ffi :as ffi]
    [yang.antlr.cst :as cst]))


;; J5: yang.antlr.cst loads the native library on first use; load it before
;; any raw jolt_parser_* binding below is called
(when-not (cst/available?)
  (println "FAIL the native parser library did not load")
  (System/exit 1))


(def failures (atom 0))

(def orig-alloc ffi/alloc)
(def orig-free ffi/free)
(def orig-write ffi/write-array)
(def orig-read ffi/read)
(def orig-read-array ffi/read-array)
(def orig-atom atom)
(def export-var (ns-resolve 'yang.antlr.cst 'c-export))
(def c-free-var (ns-resolve 'yang.antlr.cst 'c-free))
(def worker-new-var (ns-resolve 'yang.antlr.cst 'c-worker-new))
(def worker-free-var (ns-resolve 'yang.antlr.cst 'c-worker-free))
(def orig-export @export-var)
(def orig-c-free @c-free-var)
(def orig-worker-new @worker-new-var)
(def orig-worker-free @worker-free-var)

(def profile {:grammar-id "jolt-j0c-faults"})
(def ok-source "def f(a):\n    return [a, {1: 'x'}]\nf(3)\n")
(def bad-source "def f(:\n    return 1\n")


(defn- injected
  [k]
  (throw (ex-info (str "injected " (name k) " failure") {:injected k})))


(defn- run-scenario
  "Run (call) under the instrumentation `inject` describes; answer the trace."
  [{:keys [fail-alloc fail-write fail-read fail-read-array fail-free
           fail-c-free fail-atom fail-out-cell-reread]} call]
  (let [st (orig-atom {:n-alloc 0, :n-write 0, :n-read 0, :n-read-array 0,
                       :allocs [], :index {}, :frees [], :out-cell-reads 0,
                       :handed [], :released [],
                       :workers [], :workers-freed [], :atoms 0})
        alloc (fn [& args]
                (let [i (:n-alloc (swap! st update :n-alloc inc))]
                  (when (= i fail-alloc) (injected :alloc))
                  (let [p (apply orig-alloc args)]
                    (swap! st #(-> % (update :allocs conj p) (assoc-in [:index p] i)))
                    p)))
        write (fn [& args]
                (let [i (:n-write (swap! st update :n-write inc))
                      r (apply orig-write args)]
                  (when (= i fail-write) (injected :write))
                  r))
        read (fn [cell & more]
               (let [i (:n-read (swap! st update :n-read inc))
                     ;; the out cell is allocation 2 (see call-export below)
                     k (when (= 2 (get-in @st [:index cell]))
                         (:out-cell-reads (swap! st update :out-cell-reads inc)))]
                 (when (= i fail-read) (injected :read))
                 (when (and fail-out-cell-reread k (> k 1)) (injected :reread))
                 (apply orig-read cell more)))
        read-array (fn [& args]
                     (let [i (:n-read-array (swap! st update :n-read-array inc))]
                       (when (= i fail-read-array) (injected :read-array))
                       (apply orig-read-array args)))
        free (fn [p]
               (swap! st update :frees conj p)
               (if (= fail-free (get-in @st [:index p]))
                 (injected :free)
                 (orig-free p)))
        export (fn [h in n out-cell len-cell]
                 (let [status (orig-export h in n out-cell len-cell)
                       p (orig-read out-cell :pointer)]
                   (when-not (ffi/null? p) (swap! st update :handed conj p))
                   status))
        c-free (fn [p]
                 (swap! st update :released conj p)
                 (if fail-c-free
                   (injected :c-free)
                   (orig-c-free p)))
        worker-new (fn []
                     (let [h (orig-worker-new)]
                       (swap! st update :workers conj h)
                       h))
        worker-free (fn [h]
                      (swap! st update :workers-freed conj h)
                      (orig-worker-free h))
        an-atom (fn [& args]
                  (when (and fail-atom (= 1 (:atoms (swap! st update :atoms inc))))
                    (injected :atom))
                  (apply orig-atom args))
        outcome (with-redefs-fn {#'ffi/alloc alloc, #'ffi/write-array write,
                                 #'ffi/read read, #'ffi/read-array read-array,
                                 #'ffi/free free,
                                 export-var export, c-free-var c-free,
                                 worker-new-var worker-new,
                                 worker-free-var worker-free
                                 #'clojure.core/atom an-atom}
                  (fn []
                    (try {:value (call)}
                         (catch Throwable e
                           {:threw (or (:injected (ex-data e)) (ex-message e))}))))]
    (assoc @st :outcome outcome)))


(defn- check
  "`owned` answers the worker handles the call's result still owns."
  ([label inject call expect] (check label inject call expect (constantly #{})))
  ([label inject call expect owned]
   (let [{:keys [allocs frees handed released workers workers-freed outcome
                 out-cell-reads]}
         (run-scenario inject call)
         reads-ok (if-let [k (:out-cell-reads expect)] (= k out-cell-reads) true)
         freed (frequencies frees)
         each-freed-once (every? #(= 1 (freed %)) allocs)
         rel (frequencies released)
         each-released-once (every? #(= 1 (rel %)) handed)
         wfreed (frequencies workers-freed)
         still-owned (owned outcome)
         workers-ok (every? #(if (contains? still-owned %)
                               (nil? (wfreed %))
                               (= 1 (wfreed %)))
                            workers)
         outcome-ok (cond (contains? expect :threw) (= (:threw expect) (:threw outcome))
                          (contains? expect :pred) (and (contains? outcome :value)
                                                        ((:pred expect) (:value outcome)))
                          :else (= (:value expect) (:value outcome)))
         ok? (and outcome-ok each-freed-once each-released-once workers-ok reads-ok)]
     (when-not ok? (swap! failures inc))
     (println (if ok? "PASS" "FAIL") label
              (pr-str {:allocs (count allocs), :frees (count frees),
                       :each-freed-once each-freed-once,
                       :out-cell-reads out-cell-reads,
                       :wrapper-buffers (count handed),
                       :each-released-once each-released-once,
                       :workers (count workers), :workers-ok workers-ok,
                       :outcome (if (contains? outcome :threw) outcome :value)})))))


;; -- call-export, through export-records ---------------------------------------
;; acquisitions in order: alloc 1 the source copy `in`, alloc 2 `out-cell`,
;; alloc 3 `len-cell`; write 1 copies the source; read 1 is the out cell,
;; read 2 the length cell; read-array 1 copies the wrapper's buffer. The
;; release uses the pointer read 1 obtained; it reads the out cell again only
;; when read 1 failed (then that second read is the one nothing can follow,
;; so it is not injected). `:fail-out-cell-reread` makes every out-cell read
;; after the first throw, so a release that read the cell again after a
;; successful acquisition would fail the scenario; `:out-cell-reads` in an
;; expectation pins the count.

(let [w (cst/make-worker profile)
      good {:value (cst/export-records w ok-source)}
      good-bad {:value (cst/export-records w bad-source)}
      call #(cst/export-records w ok-source)]
  (check "export success" {} call good)
  (check "export of a syntax error success" {} #(cst/export-records w bad-source) good-bad)
  (check "the source buffer allocation fails" {:fail-alloc 1} call {:threw :alloc})
  (check "the out-cell allocation fails after the source buffer" {:fail-alloc 2} call {:threw :alloc})
  (check "the length-cell allocation fails after the out cell" {:fail-alloc 3} call {:threw :alloc})
  (check "the source copy fails (after writing)" {:fail-write 1} call {:threw :write})
  (check "reading the out cell fails" {:fail-read 1} call {:threw :read})
  (check "reading the length cell fails after the wrapper handed out its buffer"
         {:fail-read 2} call {:threw :read})
  (check "copying the wrapper's buffer fails; the buffer is still released"
         {:fail-read-array 1} call {:threw :read-array})
  (check "releasing the wrapper's buffer fails; every jolt allocation still freed"
         {:fail-c-free true} call {:threw :c-free})
  (check "the source-buffer free fails; the cells are still freed" {:fail-free 1} call {:threw :free})
  (check "the out-cell free fails; the others are still freed" {:fail-free 2} call {:threw :free})
  (check "the length-cell free fails; the others are still freed" {:fail-free 3} call {:threw :free})
  ;; round 6: after a successful acquisition the release reads no cell again
  (check "success: the release uses the acquired pointer (one out-cell read)"
         {:fail-out-cell-reread true} call (assoc good :out-cell-reads 1))
  (check "the length-cell read fails after acquisition: released with no further out-cell read"
         {:fail-read 2, :fail-out-cell-reread true} call {:threw :read, :out-cell-reads 1})
  (check "the buffer copy fails after acquisition: released with no further out-cell read"
         {:fail-read-array 1, :fail-out-cell-reread true} call {:threw :read-array, :out-cell-reads 1})
  (check "the release primitive fails after acquisition: attempted once, no further out-cell read"
         {:fail-c-free true, :fail-out-cell-reread true} call {:threw :c-free, :out-cell-reads 1})
  (check "the acquiring out-cell read fails: the release reads the cell once more"
         {:fail-read 1} call {:threw :read, :out-cell-reads 2})
  (cst/close-worker w))


;; -- make-worker ------------------------------------------------------------------

(let [made (volatile! nil)
      owned (fn [outcome]
              (if-let [h (some-> outcome :value :yang.antlr.cst/native deref)] #{h} #{}))]
  (check "make-worker then close-worker frees the handle once" {}
         #(let [w (cst/make-worker profile)] (cst/close-worker w) :closed)
         {:value :closed})
  (check "make-worker answers a worker that owns its handle (not freed)" {}
         #(vreset! made (cst/make-worker profile))
         {:pred #(some? (some-> % :yang.antlr.cst/native deref))} owned)
  (cst/close-worker @made)
  (check "building the worker around the handle fails; the handle is freed"
         {:fail-atom true} #(cst/make-worker profile) {:threw :atom}))


(println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
(System/exit (if (zero? @failures) 0 1))
