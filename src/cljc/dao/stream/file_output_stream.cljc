(ns dao.stream.file-output-stream
  "Append-only file writer exposed as a DaoStream sink for byte arrays."
  ;; The :clj impl avoids JVM type references (no java.io :import, no
  ;; ^Class / ^RandomAccessFile hints): ClojureDart host-eval reads the
  ;; :clj branch and
  ;; cannot resolve JVM types as Dart types. Plain fn calls
  ;; (io/output-stream)
  ;; and method calls on untyped receivers compile cleanly.
  (:require
    #?@(:cljd [["dart:io" :as dart-io]]
        :clj [[clojure.java.io :as io]])
    [dao.stream :as ds]))


#?(:clj (defn- ensure-output-file!
          [path]
          (let [file (io/file path)]
            (when-let [parent (.getParentFile file)] (.mkdirs parent))
            (when-not (.exists file) (.createNewFile file))
            (when-not (.isFile file)
              (throw (ex-info "file-output-stream path must be a regular file"
                              {:path path})))
            file)))


#?(:clj (defrecord FileOutputStream
          [path out-atom closed?-atom]

          ds/IDaoStreamWriter

          (put!
            [_this val]
            (when-not (bytes? val)
              (throw (ex-info "file-output-stream expects a byte-array value"
                              {:path path, :value-type (str (type val))})))
            (if @closed?-atom
              (throw (ex-info "Cannot put to closed file-output-stream"
                              {:path path}))
              (let [out @out-atom]
                (locking out (.write out ^bytes val) (.flush out))
                {:result :ok, :woke []})))


          ds/IDaoStreamReader

          (next
            [_this _cursor]
            (throw (ex-info "file-output-stream is write-only" {:path path})))


          ds/IDaoStreamBound

          (close!
            [_this]
            (when-not @closed?-atom
              (.close @out-atom)
              (reset! closed?-atom true))
            {:woke []})


          (closed? [_this] @closed?-atom)))


#?(:clj (defn make-file-output-stream
          [path]
          (let [file (ensure-output-file! path)]
            (->FileOutputStream path
                                (atom (io/output-stream file :append true))
                                (atom false)))))


#?(:clj (ds/defopen :file-output-stream
                    [descriptor]
                    (make-file-output-stream (:path descriptor))))


;; ---------------------------------------------------------------------------
;; ClojureDart implementation (dart:io RandomAccessFile, append mode)
;; ---------------------------------------------------------------------------

#?(:cljd (defn- ensure-output-file!
           [path]
           (let [file (dart-io/File. path)]
             (.createSync file .recursive true)
             file)))


#?(:cljd (defrecord FileOutputStream
           [path raf-atom closed?-atom]

           ds/IDaoStreamWriter

           (put!
             [_this val]
             (when-not (bytes? val)
               (throw (ex-info "file-output-stream expects a byte-array value"
                               {:path path, :value-type (str (type val))})))
             (if @closed?-atom
               (throw (ex-info "Cannot put to closed file-output-stream"
                               {:path path}))
               (let [^dart-io/RandomAccessFile raf @raf-atom]
                 (.writeFromSync raf val)
                 {:result :ok, :woke []})))


           ds/IDaoStreamReader

           (next
             [_this _cursor]
             (throw (ex-info "file-output-stream is write-only"
                             {:path path})))


           ds/IDaoStreamBound

           (close!
             [_this]
             (when-not @closed?-atom
               (.closeSync ^dart-io/RandomAccessFile @raf-atom)
               (reset! closed?-atom true))
             {:woke []})


           (closed? [_this] @closed?-atom)))


#?(:cljd (defn make-file-output-stream
           [path]
           (let [^dart-io/File file (ensure-output-file! path)
                 raf (.openSync file .mode dart-io/FileMode.append)]
             (->FileOutputStream path (atom raf) (atom false)))))


#?(:cljd (ds/defopen :file-output-stream
                     [descriptor]
                     (make-file-output-stream (:path descriptor))))
