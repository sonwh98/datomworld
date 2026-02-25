(ns dao.stream.storage
  "Standalone storage utilities for DaoStream.

  Ring-buffer and file backends for specialized use cases.
  The default stream path uses inline vectors (no storage needed).
  These backends exist for cases that need eviction (ring buffer)
  or persistence (file)."
  #?(:clj
     (:require
       [clojure.edn :as edn]
       [clojure.java.io :as io]
       [clojure.string :as str])))


;; =============================================================================
;; File Backend
;; =============================================================================

#?(:clj (defn- ensure-file!
          [path]
          (let [f (io/file path)
                parent (.getParentFile f)]
            (when parent (.mkdirs parent))
            (when-not (.exists f) (spit f ""))
            f)))


#?(:clj (defn- read-file-log
          [f]
          (with-open [rdr (io/reader f)]
            (->> (line-seq rdr)
                 (remove str/blank?)
                 (mapv edn/read-string)))))


#?(:clj (defn- append-file-line!
          [f val]
          (spit f (str (pr-str val) "\n") :append true)))


(defn file-append
  "Append a value to file-backed storage. Returns updated storage map."
  [storage val]
  #?(:clj (let [f (ensure-file! (:path storage))]
            (append-file-line! f val)
            (update storage :log conj val))
     :cljs (throw (ex-info "File storage is only available on JVM Clojure"
                           {:path (:path storage)}))))


(defn file-read-at
  "Read value at position from file-backed storage."
  [{:keys [log]} pos]
  (get log pos))


(defn file-length
  "Number of values in file-backed storage."
  [{:keys [log]}]
  (count log))


(defn file-storage
  "Create or open file-backed storage at path.
   Values are persisted as one EDN form per line.
   Returns a plain map: {:type :file, :path path, :log [...]}"
  [path]
  #?(:clj (let [f (ensure-file! path)]
            {:type :file, :path (.getPath f), :log (read-file-log f)})
     :cljs (throw (ex-info "file-storage is only available on JVM Clojure"
                           {:path path}))))


;; =============================================================================
;; Ring Buffer Backend
;; =============================================================================

(defn ring-buffer-append
  "Append a value to ring buffer storage. Returns updated storage map."
  [{:keys [capacity slots head count], :as storage} val]
  (cond (zero? capacity) storage
        (< count capacity) (let [idx (mod (+ head count) capacity)]
                             (assoc storage
                                    :slots (assoc slots idx val)
                                    :count (inc count)))
        :else (assoc storage
                     :slots (assoc slots head val)
                     :head (mod (inc head) capacity))))


(defn ring-buffer-read-at
  "Read value at position from ring buffer storage."
  [{:keys [capacity slots head count]} pos]
  (when (and (integer? pos) (<= 0 pos) (< pos count) (pos? capacity))
    (get slots (mod (+ head pos) capacity))))


(defn ring-buffer-length
  "Number of values in ring buffer storage."
  [{:keys [count]}]
  count)


(defn ring-buffer-storage
  "Create an empty ring-buffer storage with fixed capacity.
   Once full, appends evict the oldest value.
   Returns a plain map: {:type :ring-buffer, :capacity n, :slots [...], :head 0, :count 0}"
  [capacity]
  (when-not (and (integer? capacity) (not (neg? capacity)))
    (throw (ex-info "Ring buffer capacity must be a non-negative integer"
                    {:capacity capacity})))
  {:type :ring-buffer,
   :capacity capacity,
   :slots (vec (repeat capacity nil)),
   :head 0,
   :count 0})
