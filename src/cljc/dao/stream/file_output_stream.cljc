(ns dao.stream.file-output-stream
  "Append-only file writer exposed as a DaoStream sink for byte arrays."
  (:require
    #?(:clj [clojure.java.io :as io])
    [dao.stream :as ds])
  #?(:clj
     (:import
       (java.io
         RandomAccessFile))))


#?(:clj
   (def ^Class byte-array-class
     (Class/forName "[B")))


#?(:clj
   (defn- byte-array?
     [x]
     (instance? byte-array-class x)))


#?(:clj
   (defn- ensure-output-file!
     [path]
     (let [file (io/file path)]
       (when-let [parent (.getParentFile file)]
         (.mkdirs parent))
       (when-not (.exists file)
         (.createNewFile file))
       (when-not (.isFile file)
         (throw (ex-info "file-output-stream path must be a regular file"
                         {:path path})))
       file)))


#?(:clj
   (defrecord FileOutputStream
     [path raf-atom closed?-atom]

     ds/IDaoStreamWriter

     (put!
       [_this val]
       (when-not (byte-array? val)
         (throw (ex-info "file-output-stream expects a byte-array value"
                         {:path path
                          :value-type (some-> val class str)})))
       (if @closed?-atom
         (throw (ex-info "Cannot put to closed file-output-stream" {:path path}))
         (let [raf ^RandomAccessFile @raf-atom]
           (locking raf
             (.seek raf (.length raf))
             (.write raf ^bytes val))
           {:result :ok
            :woke []})))


     ds/IDaoStreamReader

     (next
       [_this _cursor]
       (throw (ex-info "file-output-stream is write-only" {:path path})))


     ds/IDaoStreamBound

     (close!
       [_this]
       (when-not @closed?-atom
         (.close ^RandomAccessFile @raf-atom)
         (reset! closed?-atom true))
       {:woke []})


     (closed?
       [_this]
       @closed?-atom)))


#?(:clj
   (defn make-file-output-stream
     [path]
     (let [file (ensure-output-file! path)]
       (->FileOutputStream path
                           (atom (RandomAccessFile. file "rw"))
                           (atom false)))))


#?(:clj
   (defmethod ds/open! :file-output-stream
     [descriptor]
     (make-file-output-stream (:path descriptor))))
