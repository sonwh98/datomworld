;; J0c round 4, step B: a full jolt collection during a long native parse
;; (docs/design/yin.repl-jolt-implementation.md §7.5).
;;
;;   . build/tools/jolt-env.sh
;;   "$JOLT_HOME"/bin/jolt -Srepro -M:jolt src/dev/jolt_j0c_gc.clj
;;
;; Thread P makes ONE call to jolt_parser_export on a large source, through
;; the :blocking binding the adapter uses (and, as a recorded comparison, a
;; plain binding). The source is encoded and copied to native memory before P
;; starts, so P's time is the native call. Thread U, unrelated, counts ticks
;; every ~5 ms. 200 ms into P's call, the main thread runs jolt.host/gc-full!.
;; Required for the :blocking binding: the collection completes before P's
;; call returns, and U progresses during P's call. The plain binding's
;; timeline is printed, not asserted (J0a found its control did not wait).
(ns jolt-j0c-gc
  (:require
    [jolt.ffi :as ffi]
    [yang.antlr.cst :as cst]))


;; J5: yang.antlr.cst loads the native library on first use; load it before
;; any raw jolt_parser_* binding below is called
(when-not (cst/available?)
  (println "FAIL the native parser library did not load")
  (System/exit 1))


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(ffi/defcfn c-export-blocking "jolt_parser_export" [:pointer :pointer :size_t :pointer :pointer] :int :blocking)
(ffi/defcfn c-export-plain "jolt_parser_export" [:pointer :pointer :size_t :pointer :pointer] :int)
(ffi/defcfn c-free "jolt_parser_free" [:pointer] :void)


(def source (apply str (repeat 30000 "x = (1 + 2) * 3\n")))


(defn- timeline
  [call]
  (let [w (cst/make-worker {:grammar-id "jolt-j0c-gc"})
        bs (.getBytes ^String source "UTF-8")
        n (alength bs)
        in (ffi/alloc n)
        out-cell (ffi/alloc :pointer)
        len-cell (ffi/alloc :size_t)
        _ (ffi/write-array in bs)
        t0 (System/nanoTime)
        ms #(/ (- (System/nanoTime) t0) 1e6)
        log (atom {})
        ticks (atom [])
        stop (atom false)
        u (Thread. (fn [] (while (not @stop) (swap! ticks conj (ms)) (Thread/sleep 5))))
        p (Thread. (fn []
                     (swap! log assoc :call-start (ms))
                     (swap! log assoc :status (call @(:yang.antlr.cst/native w) in n out-cell len-cell))
                     (swap! log assoc :call-end (ms))))]
    (.start u)
    (.start p)
    (while (nil? (:call-start @log)) (Thread/sleep 1))
    (Thread/sleep 200)
    (swap! log assoc :gc-start (ms))
    (jolt.host/gc-full!)
    (swap! log assoc :gc-end (ms))
    (.join p)
    (reset! stop true)
    (.join u)
    (let [p (ffi/read out-cell :pointer)
          len (ffi/read len-cell :size_t)]
      (when-not (ffi/null? p) (c-free p))
      (ffi/free in) (ffi/free out-cell) (ffi/free len-cell)
      (cst/close-worker w)
      (let [{:keys [call-start call-end gc-start gc-end]} @log
            in-call (filter #(< call-start % call-end) @ticks)
            in-gc (filter #(< gc-start % gc-end) @ticks)
            round #(/ (Math/round (* 10.0 %)) 10.0)]
        (into (update-vals @log #(if (number? %) (round %) %))
              {:buffer-bytes len,
               :u-ticks-during-call (count in-call),
               :u-ticks-during-gc (count in-gc),
               :u-ticks-total (count @ticks),
               :gc-finished-before-call (< gc-end call-end)})))))


(let [b (timeline c-export-blocking)]
  (println "RECORD :blocking" (pr-str b))
  (check "a full collection started 200 ms into a :blocking parse completes before the call returns"
         (and (zero? (:status b)) (:gc-finished-before-call b)) (select-keys b [:status :call-start :gc-start :gc-end :call-end]))
  (check "an unrelated thread progresses during the :blocking parse"
         (pos? (:u-ticks-during-call b)) (select-keys b [:u-ticks-during-call :u-ticks-during-gc])))


(println "RECORD plain (not asserted)" (pr-str (timeline c-export-plain)))


(println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
(System/exit (if (zero? @failures) 0 1))
