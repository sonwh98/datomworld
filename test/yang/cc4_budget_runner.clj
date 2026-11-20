(ns yang.cc4-budget-runner
  "cc4 budget closure runner (declaration v1: 1795016601236). Validated
   descendant of the unexecuted r2 draft 1795017203479; that draft stays
   untouched. Differences from the draft, each required for a valid run:
   - ONE configuration per process (CLI arg B or S): the draft ran both
     in one process, contaminating the second config cold sample;
   - the retained holder is pinned in a volatile read AFTER the
     after-GC sample, making reachability independent of locals
     clearing;
   - per-sample records and max-based verdicts print as one EDN map;
   - stage timing stays in two buckets (compile-expand-publish,
     lower-persist-load-query); a bucket bound bounds every stage
     inside it, so bucket verdicts are conservative per-stage verdicts.
   Trace stays disabled throughout; no runtime tracing is constructed."
  (:require [clojure.string :as str]
            [yang.cc4-corpus :as corpus]
            [dao.host.bytes :as bytes]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [yin.vm :as vm]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.semantic.derive :as derive]))


(def ceilings
  {:per-artifact 2000000 :storage 32000000 :retained 96000000
   :heap 2147483648 :pipeline 80000 :cold 160000
   :rows 50000 :occurrences 100000 :applications 10000
   :depth 256 :path-bytes 8000000})


(defn now
  []
  (System/currentTimeMillis))


(defn heap
  []
  (let [r (Runtime/getRuntime)] (- (.totalMemory r) (.freeMemory r))))


(defn gc-heap
  []
  (System/gc) (Thread/sleep 50) (System/gc) (heap))


(defn- check-input-bounds!
  [counts scope]
  (doseq [k (keys counts)]
    (when (> (get counts k) (get ceilings k))
      (throw (ex-info "cc4 input bound exceeded"
                      (assoc counts :scope scope))))))


(def ^:private excluded
  (atom #{}))


(defn pipeline
  [annotated?]
  (let [start (now)
        artifacts (corpus/replay-corpus annotated? @excluded)
        built (now)
        store (mem/create-content-mem)
        entries
        (mapv
          (fn [{:keys [tree rows] :as artifact}]
            (let [occ (vec (vm/occurrences tree))
                  apps (filterv (fn [[_ _ id]]
                                  (= :application (nth (get (:rows tree) id) 1)))
                                occ)
                  paths (mapv second apps)
                  counts {:rows (count (:rows tree))
                          :occurrences (count occ)
                          :applications (count apps)
                          :depth (apply max 0 (map count paths))
                          :path-bytes (bytes/length
                                        (jing/canonical-bytes paths))}
                  _ (check-input-bounds! counts (:id artifact))
                  sem (linearize/project-rows tree nil (if annotated? rows []))
                  stack (derive/stack-image
                          (:vector sem)
                          {:segment-address :cc4/stack
                           :call-provenance (:call-provenance sem)})
                  reg (derive/register-image
                        (:vector sem)
                        {:segment-address :cc4/register
                         :call-provenance (:call-provenance sem)})
                  relation [(:call-provenance sem)
                            (:call-provenance stack)
                            (:call-provenance reg)]
                  record (when annotated?
                           {:cc4/schema 1
                            :root (:root tree)
                            :rows (jing/materialize! store rows)
                            :relation (jing/materialize! store relation)})
                  address (when record (jing/materialize! store record))
                  payloads (if annotated?
                             (into [rows relation record]
                                   (:published-payloads artifact))
                             [])
                  sizes (if annotated?
                          (mapv #(bytes/length (jing/canonical-bytes %))
                                payloads)
                          [0])
                  loaded (when address (jing/get store address nil))
                  loaded-rows (if loaded (jing/get store (:rows loaded) nil) [])
                  query (into #{} (map #(vec (take 3 %))) loaded-rows)]
              (when (and annotated?
                         (not= (set (map #(vec (take 3 %)) rows)) query))
                (throw (ex-info "cc4 persisted query mismatch"
                                {:id (:id artifact)})))
              {:input artifact
               :images [(:vector sem) (:image stack) (:image reg)]
               :relation (when annotated? relation)
               :record loaded
               :counts counts :sizes sizes :side-payloads payloads
               :stage-heap (heap)}))
          artifacts)
        done (now)
        totals (reduce (fn [a e] (merge-with + a (dissoc (:counts e) :depth)))
                       {} entries)
        totals (assoc totals
                      :depth (apply max (map #(get-in % [:counts :depth])
                                             entries)))
        _ (check-input-bounds! totals :aggregate)]
    {:holder [artifacts entries store]
     :roots (mapv #(get-in % [:input :tree :root]) entries)
     :image-digest (jing/content-hash (mapv :images entries))
     :counts totals
     :per-member (mapv #(select-keys % [:counts :sizes]) entries)
     :storage (reduce +
                      (map #(bytes/length (jing/canonical-bytes %))
                           (vals (into {}
                                       (map (juxt jing/segment-key identity))
                                       (mapcat :side-payloads entries)))))
     :largest-artifact (apply max (mapcat :sizes entries))
     :stage-heap-max (apply max (heap) (keep :stage-heap entries))
     :stages-ms {:compile-expand-publish (- built start)
                 :lower-persist-load-query (- done built)}
     :elapsed-ms (- done start)}))


(defn sample
  [annotated?]
  (let [stop (atom false)
        peak (atom (heap))
        sampler (Thread. (fn []
                           (while (not @stop)
                             (swap! peak max (heap))
                             (Thread/sleep 2))))]
    (.start sampler)
    (try
      (let [result (pipeline annotated?)]
        (assoc (dissoc result :holder) :peak-used-heap @peak))
      (finally
        (reset! stop true)
        (.join sampler)))))


(defn retained
  [annotated?]
  (let [before (gc-heap)
        result (pipeline annotated?)
        pin (volatile! (:holder result))
        after (gc-heap)
        alive (count (first @pin))]
    {:before before :after after :delta (- after before)
     :retained-members alive}))


(defn measure
  [annotated?]
  (let [cold (sample annotated?)
        _ (sample annotated?)
        pipes (mapv (fn [_] (sample annotated?)) (range 3))
        holds (vec (map (fn [_] (retained annotated?)) (range 5)))
        max-of (fn [k] (apply max (map k pipes)))
        peak (max-of :peak-used-heap)
        retained-max (apply max (map :delta holds))]
    {:configuration (if annotated? :S :B)
     :memory-method :jvm-gc-used-heap-2ms-sampled-peak
     :cold cold :pipeline-samples pipes :retained-samples holds
     :maxima {:time (max-of :elapsed-ms)
              :storage (max-of :storage)
              :per-artifact (max-of :largest-artifact)
              :heap peak
              :retained retained-max}
     :verdicts {:cold (<= (:elapsed-ms cold) (:cold ceilings))
                :pipeline (<= (max-of :elapsed-ms) (:pipeline ceilings))
                :storage (<= (max-of :storage) (:storage ceilings))
                :per-artifact (<= (max-of :largest-artifact)
                                  (:per-artifact ceilings))
                :heap (<= peak (:heap ceilings))
                :retained (<= retained-max (:retained ceilings))
                :stage-buckets (<= (apply max
                                          (mapcat #(vals (:stages-ms %))
                                                  (cons cold pipes)))
                                   (:pipeline ceilings))}}))


(defn -main
  [& args]
  (let [cfg (case (first args)
              "B" :B "S" :S
              (throw (ex-info "usage: -m yang.cc4-budget-runner <B|S> [EX=ids]"
                              {:got args})))
        _ (when-let [ex (second args)]
            (when-not (re-find #"^EX=" ex)
              (throw (ex-info "second arg must be EX=<comma ids>" {:got ex})))
            (reset! excluded
                    (into #{} (map keyword)
                          (str/split (subs ex 3) #","))))
        result (measure (= cfg :S))]
    (prn (assoc result :cc4/budget 2 :ceilings ceilings))
    (flush)))
