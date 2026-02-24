(ns dao.stream.storage
  "Storage protocol for DaoStream.

  Streams are append-only logs. Storage is the pluggable backend
  that holds the value sequence. This namespace defines the protocol
  and provides in-memory, file, and ring-buffer backends.

  Storage is a pure data structure: append returns a new storage,
  it does not mutate in place."
  #?(:clj (:require [clojure.edn :as edn]
                    [clojure.java.io :as io]
                    [clojure.string :as str])))


;; =============================================================================
;; Storage Protocol
;; =============================================================================

(defprotocol IStreamStorage
  (append [this val]
    "Append a value to the log. Returns updated storage.")
  (read-at [this pos]
    "Read the value at position pos. Returns nil if out of range.")
  (length [this]
    "Current number of values in the log."))


;; =============================================================================
;; In-Memory Backend
;; =============================================================================

(defrecord MemoryStorage [log]
  IStreamStorage
    (append [this val] (assoc this :log (conj log val)))
    (read-at [_this pos] (get log pos))
    (length [_this] (count log)))


(defn memory-storage
  "Create an empty in-memory storage backed by a vector."
  []
  (->MemoryStorage []))


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


(defrecord FileStorage [path log]
  IStreamStorage
    (append [this val]
      #?(:clj (let [f (ensure-file! path)]
                (append-file-line! f val)
                (assoc this :log (conj log val)))
         :cljs (throw (ex-info "FileStorage is only available on JVM Clojure"
                               {:path path}))))
    (read-at [_this pos] (get log pos))
    (length [_this] (count log)))


(defn file-storage
  "Create or open file-backed storage at path.
   Values are persisted as one EDN form per line."
  [path]
  #?(:clj (let [f (ensure-file! path)]
            (->FileStorage (.getPath f) (read-file-log f)))
     :cljs (throw (ex-info "file-storage is only available on JVM Clojure"
                           {:path path}))))


;; =============================================================================
;; Ring Buffer Backend
;; =============================================================================

(defrecord RingBufferStorage [capacity slots head count]
  IStreamStorage
    (append [this val]
      (cond (zero? capacity) this
            (< count capacity) (let [idx (mod (+ head count) capacity)]
                                 (assoc this
                                   :slots (assoc slots idx val)
                                   :count (inc count)))
            :else (assoc this
                    :slots (assoc slots head val)
                    :head (mod (inc head) capacity))))
    (read-at [_this pos]
      (when (and (integer? pos) (<= 0 pos) (< pos count) (pos? capacity))
        (get slots (mod (+ head pos) capacity))))
    (length [_this] count))


(defn ring-buffer-storage
  "Create an empty ring-buffer storage with fixed capacity.
   Once full, appends evict the oldest value."
  [capacity]
  (when-not (and (integer? capacity) (not (neg? capacity)))
    (throw (ex-info "Ring buffer capacity must be a non-negative integer"
                    {:capacity capacity})))
  (->RingBufferStorage capacity (vec (repeat capacity nil)) 0 0))
