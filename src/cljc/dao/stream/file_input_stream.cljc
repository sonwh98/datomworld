(ns dao.stream.file-input-stream
  "Finite snapshot file reader exposed as a DaoStream of byte chunks.

   The file contents are read into memory when the stream is opened, so:
   - Subsequent mutations to the underlying file do not affect reads.
   - close! marks the stream closed but does not erase unread data;
     cursors can continue draining the snapshot after close."
  (:require
    #?@(:cljd [["dart:io" :as dart-io] ["dart:typed_data" :as typed]]
        :clj [[clojure.java.io :as io]])
    [dao.stream :as ds]))


#?(:clj (defn- normalize-chunk-size
          [chunk-size]
          (let [size (or chunk-size 65536)]
            (when-not (pos-int? size)
              (throw (ex-info
                       "file-input-stream chunk-size must be a positive integer"
                       {:chunk-size chunk-size})))
            size)))


#?(:clj (defrecord FileInputStream
          [path chunk-size snapshot closed?-atom]

          ds/IDaoStreamWriter

          (put!
            [_this _val]
            (throw (ex-info "file-input-stream is read-only" {:path path})))


          ds/IDaoStreamReader

          (next
            [_this cursor]
            (let [pos (or (:position cursor) 0)
                  offset (* (long pos) (long chunk-size))
                  total (alength ^bytes snapshot)]
              (cond
                (neg? pos)
                (throw
                  (ex-info
                    "file-input-stream cursor position must be non-negative"
                    {:path path, :cursor cursor}))
                (>= offset total) :end
                :else (let [remaining (- total offset)
                            to-read (int (min (long chunk-size) remaining))
                            buffer (byte-array to-read)]
                        (System/arraycopy snapshot offset buffer 0 to-read)
                        {:ok buffer, :cursor {:position (inc pos)}}))))


          ds/IDaoStreamBound

          (close! [_this] (reset! closed?-atom true) {:woke []})


          (closed? [_this] @closed?-atom)))


#?(:clj (defn make-file-input-stream
          [path chunk-size]
          (let [size (normalize-chunk-size chunk-size)
                file (io/file path)]
            (when-not (.exists file)
              (throw (ex-info "file-input-stream path does not exist"
                              {:path path})))
            (when-not (.isFile file)
              (throw (ex-info "file-input-stream path must be a regular file"
                              {:path path})))
            (->FileInputStream path
                               size
                               (with-open [in (io/input-stream file)]
                                 (.readAllBytes in))
                               (atom false)))))


#?(:clj (ds/defopen :file-input-stream
                    [descriptor]
                    (make-file-input-stream (:path descriptor)
                                            (:chunk-size descriptor))))


;; ---------------------------------------------------------------------------
;; ClojureDart implementation (dart:io File, synchronous, in-memory snapshot)
;; ---------------------------------------------------------------------------

#?(:cljd (defn- normalize-chunk-size
           [chunk-size]
           (let [size (or chunk-size 65536)]
             (when-not (pos-int? size)
               (throw
                 (ex-info
                   "file-input-stream chunk-size must be a positive integer"
                   {:chunk-size chunk-size})))
             size)))


#?(:cljd
   (defrecord FileInputStream
     [path chunk-size snapshot closed?-atom]

     ds/IDaoStreamWriter

     (put!
       [_this _val]
       (throw (ex-info "file-input-stream is read-only" {:path path})))


     ds/IDaoStreamReader

     (next
       [_this cursor]
       (let [^typed/Uint8List snapshot snapshot
             pos (or (:position cursor) 0)
             offset (* pos chunk-size)
             total (.-length snapshot)]
         (cond
           (neg? pos)
           (throw
             (ex-info
               "file-input-stream cursor position must be non-negative"
               {:path path, :cursor cursor}))
           (>= offset total) :end
           :else (let [remaining (- total offset)
                       to-read (min chunk-size remaining)
                       buffer (.sublist snapshot offset (+ offset to-read))]
                   {:ok buffer, :cursor {:position (inc pos)}}))))


     ds/IDaoStreamBound

     (close! [_this] (reset! closed?-atom true) {:woke []})


     (closed? [_this] @closed?-atom)))


#?(:cljd
   (defn make-file-input-stream
     [path chunk-size]
     (let [size (normalize-chunk-size chunk-size)
           file (dart-io/File. path)]
       (when-not (.existsSync file)
         (throw (ex-info "file-input-stream path does not exist"
                         {:path path})))
       (->FileInputStream path size (.readAsBytesSync file) (atom false)))))


#?(:cljd (ds/defopen :file-input-stream
                     [descriptor]
                     (make-file-input-stream (:path descriptor)
                                             (:chunk-size descriptor))))
