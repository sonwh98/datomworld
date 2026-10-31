;; Diagnostic only (engineer, C4 P2 step 0 follow-up): where does
;; link-local spend its time on the wide-layout py module? Publishes the
;; wide layout with :max-parts raised (with-redefs), but replaces
;; link-local by a sampler: it runs the real link-local for one format on
;; a worker thread, samples the worker's stack every 250 ms for 90 s, and
;; prints the hottest yin.* frames.
(load-file "collab/p2probe-walk-defs.clj")

(require 'yin.vm.linker.publish)
(def orig-link-local @(resolve 'yin.vm.linker.publish/link-local))


(defn sample-link
  [h a f]
  (let [result (promise)
        t (Thread. (fn [] (deliver result (orig-link-local h a f nil))))
        counts (atom {})
        top-counts (atom {})]
    (.start t)
    (dotimes [_ 360]
      (Thread/sleep 250)
      (let [frames (->> (.getStackTrace t)
                        (map (fn [^StackTraceElement e] (str (.getClassName e) "." (.getMethodName e))))
                        (filter #(re-find #"^(yin|dao)\." %))
                        distinct)]
        (when-let [top (first frames)] (swap! top-counts update top (fnil inc 0)))
        (doseq [fr frames] (swap! counts update fr (fnil inc 0)))))
    (println "==== format" (:format f))
    (println "-- top yin/dao frame (self-ish):")
    (doseq [[fr n] (take 15 (sort-by (comp - val) @top-counts))] (println "  " n fr))
    (println "-- inclusive (frame anywhere on stack):")
    (doseq [[fr n] (take 30 (sort-by (comp - val) @counts))] (println "  " n fr))
    (flush)
    (.stop t)
    {:status :refused :reason :sampled}))


(with-redefs [yin.vm.linker/default-bounds
              (assoc (deref (resolve 'yin.vm.linker/default-bounds)) :max-parts 1000000)
              yin.vm.linker.publish/link-local
              (fn [h a f & _]
                (if (#{:yin.semantic/code} (:format f))
                  (sample-link h a f)
                  {:status :refused :reason :skipped}))]
  (let [store (mem/create-content-mem)
        res (publish/publish-module! store {:name 'py :ast wide-layout :exports exports
                                            :requires {} :primitives primitives})]
    (println "status" (:status res) (keys res))))


(shutdown-agents)
(System/exit 0)
