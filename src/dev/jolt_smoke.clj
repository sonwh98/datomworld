;; bb smoke:jolt — the J0a minimum-baseline smoke, run UNDER jolt with the
;; :jolt alias (docs/design/yin.repl-jolt-implementation.md §7.8, W8):
;;
;;   1. the :jolt alias loads yang.antlr.export and yang.antlr.packet from the
;;      tracked roots, with no overlay, and both answer a known value;
;;   2. jolt.ffi defcfn :blocking calls pass (libc usleep and strlen).
;;
;; Collect-safety is NOT asserted here: the RECORD lines show when a full
;; collection finishes relative to a 400 ms call in each mode, but the plain
;; (non-:blocking) control did not wait for its call to return on jolt
;; 96d7b866 (J0a round-1 report), so the observation does not yet separate
;; the two modes. The long-hash collect-safety gate is later J0a work.
;;
;; Prints PASS/FAIL per check and RECORD per observation; exits nonzero on any
;; FAIL.
(ns jolt-smoke
  (:require [jolt.ffi :as ffi]))


;; Loaded here rather than in the ns form, so the libs they add are observable.
(def ^:private before (set (loaded-libs)))
(require '[yang.antlr.export :as export] '[yang.antlr.packet :as packet])
(def ^:private added (sort (remove before (loaded-libs))))

(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


;; 1. tracked load
(check "the closure of yang.antlr.export is exactly it, yang.antlr.packet, dao.host.text and jolt.scheme (no transit)"
       (= '(dao.host.text jolt.scheme yang.antlr.export yang.antlr.packet) added)
       added)


(check "yang.antlr.packet/code-points \"aé😀\" = [97 233 128512] (its :clj branch)"
       (= [97 233 128512] (packet/code-points "aé😀"))
       (packet/code-points "aé😀"))


(check "yang.antlr.packet/utf8-bytes \"aé😀\" = 7"
       (= 7 (packet/utf8-bytes "aé😀"))
       (packet/utf8-bytes "aé😀"))


(check "yang.antlr.export/byte-offsets of [97 233 128512] = [0 1 3 7]"
       (= [0 1 3 7] (vec (export/byte-offsets [97 233 128512])))
       (export/byte-offsets [97 233 128512]))


;; 2. defcfn :blocking
(ffi/load-library)
(ffi/defcfn c-usleep-blocking "usleep" [:uint] :int :blocking)
(ffi/defcfn c-strlen-blocking "strlen" [:pointer] :size_t :blocking)
(ffi/defcfn c-usleep "usleep" [:uint] :int)


(defn- ms
  [t0]
  (long (/ (- (System/nanoTime) t0) 1e6)))


(let [t0 (System/nanoTime) r (c-usleep-blocking 200000) dt (ms t0)]
  (check "defcfn :blocking usleep(200 ms) returns 0 after >= 200 ms"
         (and (= 0 r) (>= dt 200)) {:result r :ms dt}))


(let [p (ffi/string->ptr "a\u00e9\ud83d\ude00")
      r (try (c-strlen-blocking p) (finally (ffi/free p)))]
  (check "defcfn :blocking strlen of the UTF-8 C string \"aé😀\" = 7"
         (= 7 r) {:result r}))


(defn- collect-during
  "On another thread run (call 400000); 50 ms in, run a full collection here.
   Answers the timeline in ms from the thread's start."
  [call]
  (let [t0 (System/nanoTime)
        log (atom [])
        th (Thread. (fn []
                      (swap! log conj [:call-start (ms t0)])
                      (swap! log conj [:call-result (call 400000)])
                      (swap! log conj [:call-end (ms t0)])))]
    (.start th)
    (Thread/sleep 50)
    (swap! log conj [:collect-start (ms t0)])
    (jolt.host/gc-full!)
    (swap! log conj [:collect-end (ms t0)])
    (.join th)
    (into {} @log)))


(println "RECORD collect timeline, :blocking usleep(400 ms)" (pr-str (collect-during c-usleep-blocking)))
(println "RECORD collect timeline, plain usleep(400 ms)" (pr-str (collect-during c-usleep)))

(println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
(System/exit (if (zero? @failures) 0 1))
