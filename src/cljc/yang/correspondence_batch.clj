(ns yang.correspondence-batch
  "Resumable per-fixture outcome records for one corpus static-gate run.
   The outcomes file IS the progress record: every recorded outcome is
   final (append-only), an interruption loses at most the in-flight
   fixture, and `:not-run` is never a pass or a refusal. These records
   are run bookkeeping, not a verification result."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [yin.vm :as vm]))


(def ^:private recordable-statuses #{:pass :refused})


(defn- outcome-refusal
  [reason detail]
  (ex-info "Outcome record refused"
           (assoc detail :rule :correspondence/outcome :reason reason)))


(defn- checked-outcome
  "Validate one outcome entry per status: a pass carries its recorded
   admission origin and the storage it measured; a refusal names its
   stage. Anything else refuses rather than recording half a fact."
  [fixture outcome]
  (when-not (map? outcome) (throw (outcome-refusal :outcome-shape {:fixture fixture})))
  (let [status (:status outcome)]
    (when-not (contains? recordable-statuses status)
      (throw (outcome-refusal :outcome-status {:fixture fixture :status status})))
    (case status
      :pass (when-not (and (vm/occurrence-origin? (:admission-origin outcome))
                           (pos-int? (:storage-bytes outcome)))
              (throw (outcome-refusal :pass-evidence
                                      {:fixture fixture
                                       :requires [:admission-origin :storage-bytes]})))
      :refused (when-not (keyword? (:stage outcome))
                 (throw (outcome-refusal :refusal-stage {:fixture fixture}))))
    outcome))


(defn- sync-directory!
  "Flush the directory entry so a renamed-in progress record survives a
   process crash. Best-effort on platforms without directory sync."
  [dir-file]
  (try
    (let [channel (java.nio.channels.FileChannel/open
                    (.toPath dir-file)
                    (into-array java.nio.file.OpenOption
                                [java.nio.file.StandardOpenOption/READ]))]
      (try (.force channel true) (finally (.close channel))))
    (catch Exception _ nil)))


(defn- write-atomically!
  "Write `value` to `file` through a same-directory temporary file, so a
   reader never observes a partial progress record."
  [file value]
  (let [target (io/file file)
        parent (or (.getParentFile target) (io/file "."))
        temporary (io/file parent (str (.getName target) ".partial-" (random-uuid)))]
    (try
      (when-not (.exists parent) (.mkdirs parent))
      (let [bytes (.getBytes (with-out-str (prn value)) "UTF-8")]
        (with-open [out (java.io.FileOutputStream. temporary)]
          (.write out bytes)
          (.flush out)
          (.sync (.getFD out))))
      (java.nio.file.Files/move (.toPath temporary) (.toPath target)
                                (into-array java.nio.file.CopyOption
                                            [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                             java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (sync-directory! parent)
      nil
      (finally
        (when (.exists temporary) (.delete temporary))))))


(defn initialize!
  "Create the progress record for one corpus run: every supplied fixture
   id starts `:not-run`. An existing file refuses — a run's record is
   never reset."
  [file fixtures]
  (let [ids (vec fixtures)]
    (when-not (and (every? keyword? ids)
                   (= (count ids) (count (distinct ids))))
      (throw (outcome-refusal :fixture-ids {:fixtures ids})))
    (when (.exists (io/file file))
      (throw (outcome-refusal :progress-exists {:file (str file)})))
    (write-atomically! file
                       {:scope :d1-per-fixture-static-gates
                        :not-a-verification-result true
                        :fixture-count (count ids)
                        :outcomes (into {} (map (fn [id] [id {:status :not-run}])) ids)})
    nil))


(defn read-progress
  "Read and shape-check the progress record at `file`."
  [file]
  (let [f (io/file file)]
    (when-not (.exists f)
      (throw (outcome-refusal :progress-absent {:file (str file)})))
    (let [progress (edn/read-string (slurp f))]
      (when-not (and (map? progress) (map? (:outcomes progress))
                     (pos-int? (:fixture-count progress)))
        (throw (outcome-refusal :progress-shape {:file (str file)})))
      progress)))


(defn record!
  "Record one fixture's FINAL outcome. The fixture must be one this run
   initialized, the outcome must be complete for its status, and a
   fixture whose outcome is already recorded never changes: re-running a
   batch skips it instead of rewriting it."
  [file fixture outcome]
  (let [progress (read-progress file)
        outcomes (:outcomes progress)]
    (when-not (contains? outcomes fixture)
      (throw (outcome-refusal :unknown-fixture {:fixture fixture})))
    (let [checked (checked-outcome fixture outcome)]
      (when-not (= :not-run (:status (get outcomes fixture)))
        (throw (outcome-refusal :outcome-final
                                {:fixture fixture
                                 :recorded (get outcomes fixture)})))
      (write-atomically! file (assoc progress :outcomes
                                     (assoc outcomes fixture checked)))
      nil)))


(defn summary
  "Count outcomes by status; `:not-run` counts stay visible so an
   incomplete run never reads as complete."
  [progress]
  (let [counts (frequencies (map (comp :status val) (:outcomes progress)))]
    {:fixture-count (:fixture-count progress)
     :pass (get counts :pass 0)
     :refused (get counts :refused 0)
     :not-run (get counts :not-run 0)}))
