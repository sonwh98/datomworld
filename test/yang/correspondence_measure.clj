(ns yang.correspondence-measure
  "Resumable measurement records. These are not D1 verification results."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [dao.stream :as stream]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [yang.correspondence-lane :as lane]
            [yang.correspondence-verifier :as verifier]
            [yin.vm :as vm]))


(defn- sync-directory!
  [dir]
  (try
    (with-open [channel (java.nio.channels.FileChannel/open
                          (.toPath dir)
                          (into-array java.nio.file.OpenOption
                                      [java.nio.file.StandardOpenOption/READ]))]
      (.force channel true))
    (catch Exception _ nil)))


(defn- write-progress!
  [file value]
  (let [f (io/file file) parent (or (.getParentFile f) (io/file "."))
        temp (io/file parent (str (.getName f) ".partial-" (random-uuid)))]
    (try
      (.mkdirs parent)
      (with-open [out (java.io.FileOutputStream. temp)]
        (.write out (.getBytes (str (pr-str value) "\n") "UTF-8"))
        (.flush out)
        (.sync (.getFD out)))
      (java.nio.file.Files/move (.toPath temp) (.toPath f)
                                (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                                                      java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (sync-directory! parent)
      (finally (when (.exists temp) (.delete temp))))))


(defn initialize!
  ([file ids] (initialize! file ids {:scope :d1-c-lane-measurement
                                     :pre-declaration-tagged-execution true}))
  ([file ids {:keys [scope pre-declaration-tagged-execution]}]
   (when (or (.exists (io/file file)) (empty? ids) (not= (count ids) (count (set ids)))
             (not (keyword? scope)) (not (boolean? pre-declaration-tagged-execution)))
     (throw (ex-info "Invalid new progress" {:stage :progress})))
   (write-progress! file {:scope scope :not-a-verification-result true
                          :pre-declaration-tagged-execution pre-declaration-tagged-execution
                          :outcomes (zipmap ids (repeat {:status :not-run}))})))


(defn read-progress
  [file]
  (edn/read-string (slurp file)))


(defn record!
  [file id outcome]
  (let [p (read-progress file)]
    (when-not (and (= :not-run (get-in p [:outcomes id :status]))
                   (contains? #{:pass :refused :failed-bound} (:status outcome))
                   (or (not= :failed-bound (:status outcome))
                       (and (= :bound-exceeded (:stage outcome))
                            (keyword? (:quantity outcome))
                            (nat-int? (:observed outcome))
                            (nat-int? (:ceiling outcome))
                            (> (:observed outcome) (:ceiling outcome)))))
      (throw (ex-info "Outcome is not recordable" {:stage :progress :fixture id})))
    (write-progress! file (assoc-in p [:outcomes id]
                                    (cond-> outcome
                                      (:pre-declaration-tagged-execution p)
                                      (assoc :pre-declaration true))))))


(defn check-retained-trees!
  "Re-fetch both complete row closures under their recorded admission."
  [handle fixture outcome & [{:keys [max-rows] :or {max-rows 100000}}]]
  (let [addresses (:addresses outcome)
        missing ::missing
        input (jing/get handle (:input addresses) missing)
        snapshot (jing/get handle (:snapshot addresses) missing)]
    (when-not (and (= :pass (:status outcome))
                   (= fixture (get-in input [:fixture :id]) (:fixture snapshot))
                   (= (:admission-origin outcome) (:admission-origin input) (:origin snapshot))
                   (= (:root addresses) (:root snapshot))
                   (= (:stamped-root addresses) (:stamped-root snapshot)))
      (throw (ex-info "Retained admission mismatch" {:stage :admission :fixture fixture})))
    (let [fetch! (fn [which address]
                   (try (verifier/fetch-tree! handle address max-rows)
                        (catch clojure.lang.ExceptionInfo e
                          (if (= :verifier/tree-bound (:rule (ex-data e)))
                            (throw (ex-info "Row traversal demand exceeded bound"
                                            {:stage :bound-exceeded :quantity :rows
                                             :tree which :observed (inc max-rows)
                                             :observed-kind :distinct-required-addresses
                                             :ceiling max-rows} e))
                            (throw e)))))
          before (fetch! :before (:root addresses))
          stamped (fetch! :stamped (:stamped-root addresses))]
      {:rows {:pre-stamp (count (:rows before)) :stamped (count (:rows stamped))}
       :before before :stamped stamped})))


(defn tree-statistics
  "Occurrence and path sizes for one validated row tree; path bytes are the
   sum of canonical Jing CBOR encodings of each application path."
  [tree]
  (let [occurrences (vm/occurrences tree)
        applications (vm/application-occurrences tree)]
    {:rows (count (:rows tree))
     :occurrences (count occurrences)
     :applications (count applications)
     :depth (reduce max 0 (map (comp count second) occurrences))
     :application-path-bytes (reduce + 0 (map (fn [[_ path]] (alength (cbor/encode path)))
                                              applications))}))


(defn measure-fixture!
  "Fetch both retained row closures and measure each tagged C lane. This
   preflight collects data; it does not assert a bounded verification result."
  [handle fixture outcome modules budget]
  (let [start (System/nanoTime)
        ms (fn [t] (quot (- (System/nanoTime) t) 1000000))
        addresses (:addresses outcome)
        {:keys [before stamped]} (check-retained-trees! handle fixture outcome)
        ast (vm/semantic-bytecode->ast stamped)
        fetch-ms (ms start)
        lanes (into {}
                    (for [backend [:walker :semantic]]
                      (let [t (System/nanoTime)
                            {:keys [machine steps]} (lane/run-measured! ast modules backend budget)
                            run-ms (ms t)
                            ref (get-in machine [:store 'gate-execution-trace])
                            trace-handle (get-in machine [:resources (:id ref)])
                            _ (when-not (and (vm/halted? machine) trace-handle)
                                (throw (ex-info "Lane did not halt with trace"
                                                {:stage :lane-completion :lane backend})))
                            t (System/nanoTime)
                            trace (lane/read-trace! trace-handle)]
                        [backend (assoc (dissoc trace :records) :steps steps
                                        :run-ms run-ms :drain-ms (ms t))])))]
    {:status :pass :admission-origin (:admission-origin outcome) :addresses addresses
     :rows {:pre-stamp (count (:rows before)) :stamped (count (:rows stamped))}
     :fetch-ms fetch-ms :lanes lanes :elapsed-ms (ms start)}))


(defn check-bounds!
  "Refuse missing/nonfinite data and any observed maximum over its declared
   ceiling. This comparison alone does not enforce limits during execution."
  [limits observed]
  (when-not (and (map? limits) (seq limits)
                 (every? (fn [[k v]] (and (keyword? k) (nat-int? v))) limits))
    (throw (ex-info "Invalid bounds declaration" {:stage :bound-declaration})))
  (doseq [[quantity ceiling] limits]
    (let [value (get observed quantity)]
      (when-not (nat-int? value)
        (throw (ex-info "Missing or invalid bound measurement"
                        {:stage :bound-measurement :quantity quantity})))
      (when (> value ceiling)
        (throw (ex-info "Declared bound exceeded"
                        {:stage :bound-exceeded :quantity quantity
                         :observed value :ceiling ceiling})))))
  true)


(defn runtime-checker
  "Build a per-step checker for elapsed time, used heap, trace counts and
   printed trace bytes. `clock` returns monotonic nanoseconds; `heap` reads
   host used heap. Limits are observations, not an OS memory cap."
  [limits started clock heap]
  (when-not (= #{:steps :elapsed-ms :heap-bytes :trace-records
                 :trace-record-bytes :trace-bytes} (set (keys limits)))
    (throw (ex-info "Incomplete runtime bounds" {:stage :bound-declaration})))
  (let [monitored (atom nil)]
    (fn [steps machine]
      (let [ref (get-in machine [:store 'gate-execution-trace])
            handle (when ref (get-in machine [:resources (:id ref)]))
            _ (when (and ref (nil? handle))
                (throw (ex-info "Missing trace handle" {:stage :trace-cursor})))
            oldest (when handle (stream/cursor handle stream/anchor-oldest))
            newest (when handle (stream/cursor handle stream/anchor-newest))
            a (:dao.stream/cursor oldest)
            b (:dao.stream/cursor newest)
            first-pos (:dao.stream.ringbuffer/position a)
            tail (:dao.stream.ringbuffer/position b)
            _ (when (and handle
                         (not (and (= :dao.stream/ok (:dao.stream/outcome oldest))
                                   (= :dao.stream/ok (:dao.stream/outcome newest))
                                   (nat-int? first-pos) (nat-int? tail)
                                   (= (:dao.stream.ringbuffer/identity a)
                                      (:dao.stream.ringbuffer/identity b)))))
                (throw (ex-info "Invalid trace cursor" {:stage :trace-cursor})))
            _ (when (and handle (pos? first-pos))
                (throw (ex-info "Trace prefix evicted" {:stage :trace-loss :lost first-pos})))
            prior (when handle (or @monitored {:cursor a :count 0 :sum 0 :max 0}))
            _ (when (and handle (not= (:dao.stream.ringbuffer/identity (:cursor prior))
                                      (:dao.stream.ringbuffer/identity a)))
                (throw (ex-info "Trace changed during run" {:stage :trace-cursor})))
            current (when handle
                      (loop [{:keys [cursor] :as state} prior]
                        (if (= cursor b)
                          state
                          (let [r (stream/next handle cursor)]
                            (when-not (= :dao.stream/ok (:dao.stream/outcome r))
                              (throw (ex-info "Trace monitor could not read" {:stage :trace-drain
                                                                              :outcome (:dao.stream/outcome r)})))
                            (let [n (alength (.getBytes (pr-str (:dao.stream/value r)) "UTF-8"))]
                              (recur (-> state (assoc :cursor (:dao.stream/cursor r))
                                         (update :count inc) (update :sum + n)
                                         (update :max max n))))))))
            _ (when (and handle (not= tail (:count current)))
                (throw (ex-info "Trace monitor count disagrees" {:stage :trace-drain})))
            delta (- (clock) started)
            _ (when (neg? delta) (throw (ex-info "Clock moved backwards" {:stage :bound-measurement})))
            n (or (:count current) 0)
            bytes (if handle (+ 2 (or (:sum current) 0) (max 0 (dec n))) 0)
            observed {:steps steps :elapsed-ms (quot (+ delta 999999) 1000000)
                      :heap-bytes (heap) :trace-records (or tail 0)
                      :trace-record-bytes (or (:max current) 0) :trace-bytes bytes}]
        (check-bounds! limits observed)
        (when handle (reset! monitored current))
        nil))))


(defn bounded-tree-statistics!
  "Walk occurrence places with explicit row, occurrence, application, depth
   and encoded application-path limits. This checks observed maxima during the
   walk; a caller must also bound row retrieval and individual row sizes."
  [{:keys [root rows]} limits]
  (when-not (= #{:rows :occurrences :applications :depth :application-path-bytes}
               (set (keys limits)))
    (throw (ex-info "Incomplete tree bounds" {:stage :bound-declaration})))
  (loop [pending [[[] root]]
         stats {:rows (count rows) :occurrences 0 :applications 0
                :depth 0 :application-path-bytes 0}]
    (check-bounds! limits stats)
    (if-let [[path id] (peek pending)]
      (let [row (get rows id)
            tag (second row)
            grammar (get vm/semantic-bytecode-grammar tag)]
        (when-not (and (vector? row) grammar (= (count row) (+ 2 (count grammar))))
          (throw (ex-info "Missing or malformed row" {:stage :tree :address id})))
        (let [application? (= :application tag)
              next-stats (cond-> (-> stats (update :occurrences inc)
                                     (update :depth max (count path)))
                           application? (update :applications inc)
                           application? (update :application-path-bytes +
                                                (alength (cbor/encode path))))
              _ (check-bounds! limits next-stats)
              children (mapcat (fn [j [_ kind] value]
                                 (let [position (+ j 2)]
                                   (case kind
                                     :node (when value [[(conj path position) value]])
                                     :nodes (map-indexed (fn [i child]
                                                           [(conj path [position i]) child]) value)
                                     [])))
                               (range (count grammar)) grammar (drop 2 row))]
          (recur (into (pop pending) children) next-stats)))
      stats)))
