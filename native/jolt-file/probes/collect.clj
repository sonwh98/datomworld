;; Run under jolt -Srepro -M:jolt by probes/run.clj: collect-safety of the
;; jolt-file bindings (design §2: GC completes during a blocked native call).
;; One thread runs dao.posix.file's own :blocking binding of a disk entry, a
;; 1 GiB jolt_file_pwrite and then the jolt_file_sync of that data; once the
;; call is under way this thread runs a full collection. The gate holds when
;; the collection both starts and completes while the pwrite is still
;; running; the fsync's timeline is recorded (its duration is the
;; filesystem's, so it is not gated).
;; The file is deleted afterwards.
(ns collect
  (:require [jolt.ffi :as ffi]))


(require '[dao.posix.file :as hf])


(def c-pwrite @(ns-resolve 'dao.posix.file 'c-pwrite))
(def c-sync @(ns-resolve 'dao.posix.file 'c-sync))


(defn- ms
  [t0]
  (long (/ (- (System/nanoTime) t0) 1e6)))


(defn- timeline
  "Run (call) on a thread; once it has started, run a full collection here.
   Answers the timeline and the call's result."
  [call]
  (let [t0 (System/nanoTime)
        log (atom {})
        th (Thread. (fn []
                      (swap! log assoc :call-start (ms t0))
                      (swap! log assoc :result (call))
                      (swap! log assoc :call-end (ms t0))))]
    (.start th)
    (loop [] (when-not (:call-start @log) (Thread/sleep 1) (recur)))
    (Thread/sleep 20)
    (swap! log assoc :collect-start (ms t0))
    (jolt.host/gc-full!)
    (swap! log assoc :collect-end (ms t0))
    (.join th)
    @log))


(def n (* 1024 1024 1024))
(def path "build/j4a-probe/collect.bin")
(.mkdirs (java.io.File. "build/j4a-probe"))
(.delete (java.io.File. path))

(def h (hf/open path))
(def fd (:fd h))
(def buf (ffi/alloc n))


(def results
  (try
    (let [w (timeline #(c-pwrite fd 0 buf 0 n))
          s (timeline #(c-sync fd))]
      [["jolt_file_pwrite 1 GiB" w (= n (:result w))]
       ["jolt_file_sync of that 1 GiB" s (= 0 (:result s))]])
    (finally
      (ffi/free buf)
      (hf/close! h)
      (.delete (java.io.File. path)))))


(def ok?
  ;; the gate is the pwrite; the fsync timeline is recorded, not gated: its
  ;; duration is the filesystem's (APFS answers a 1 GiB fsync in a few ms)
  (let [[[label {:keys [call-start call-end collect-start collect-end] :as t} result-ok?]
         [sync-label sync-t sync-ok?]] results
        ok (and result-ok? sync-ok? (< call-start collect-start) (< collect-end call-end))]
    (println "RECORD collect timeline, :blocking" label (pr-str t))
    (println "RECORD collect timeline, :blocking" sync-label (pr-str sync-t)
             (if (and (< (:call-start sync-t) (:collect-start sync-t))
                      (< (:collect-end sync-t) (:call-end sync-t)))
               "(the collection also completed inside the fsync)"
               "(the fsync returned before the collection began or ended)"))
    (println (if ok "PASS" "FAIL")
             (str "a full collection starts and completes while the :blocking " label
                  " runs; the fsync of that data succeeds"))
    ok))


(System/exit (if ok? 0 1))
