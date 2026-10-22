;; J1b discipline for fresh seeds: inspect native memory at free and inject
;; failures after a partial draw/copy. The failing free releases the pointer
;; before throwing so this harness itself does not leak memory.
(ns random-faults
  (:require [jolt.ffi :as ffi]
            [dao.host.bytes :as bytes]
            [yin.vm.linker.random :as random]))


(def failures (atom 0))
(def original-alloc ffi/alloc)
(def original-free ffi/free)
(def original-read ffi/read-array)
(def draw-var (ns-resolve 'yin.vm.linker.random 'c-random))
(def cleanse-var (ns-resolve 'yin.vm.linker.random 'c-cleanse))
(def original-cleanse @cleanse-var)


(doseq [fault [nil :alloc :null :draw :read :free :cleanse]]
  (let [st (atom {:allocs [] :frees [] :zero [] :cleanup false :cleanup-allocs 0})
        outcome
        (with-redefs-fn
          {#'ffi/alloc (fn [n]
                         (when (:cleanup @st) (swap! st update :cleanup-allocs inc))
                         (case fault
                           :alloc (throw (ex-info "alloc" {:injected :alloc}))
                           :null 0
                           (let [p (original-alloc n)]
                             (swap! st update :allocs conj p) p)))
           draw-var (fn [p n]
                      (ffi/write-array p (bytes/from-vector (vec (repeat n 91))))
                      (if (= fault :draw) -2 0))
           #'ffi/read-array (fn [p n]
                              (let [bs (original-read p n)]
                                (when (= fault :read) (throw (ex-info "read" {:injected :read})))
                                bs))
           cleanse-var (fn [p n]
                         (swap! st assoc :cleanup true)
                         (original-cleanse p n)
                         (when (= fault :cleanse) (throw (ex-info "cleanse" {:injected :cleanse}))))
           #'ffi/free (fn [p]
                        (when-not (ffi/null? p)
                          (swap! st update :zero conj (every? zero? (bytes/to-vector (original-read p 32))))
                          (swap! st update :frees conj p))
                        (original-free p)
                        (when (= fault :free) (throw (ex-info "free" {:injected :free}))))}
          #(try (random/rand-bytes 32) :ok (catch Exception _ :threw)))
        {:keys [allocs frees zero cleanup-allocs]} @st
        ok (and (= outcome (if fault :threw :ok))
                (= (frequencies allocs) (frequencies frees))
                (every? true? zero) (zero? cleanup-allocs))]
    (println (if ok "PASS" "FAIL") "random" fault
             (pr-str {:outcome outcome :allocated (count allocs) :freed (count frees)
                      :zero-at-free zero :cleanup-allocs cleanup-allocs}))
    (when-not ok (swap! failures inc))))


(println "SUMMARY" (if (zero? @failures) "ok" "failed"))
(System/exit (if (zero? @failures) 0 1))
