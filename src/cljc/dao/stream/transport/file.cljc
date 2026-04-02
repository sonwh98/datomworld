(ns dao.stream.transport.file
  "Local file-backed stream implementation.
   
   Supports append-only writes and cursor-based reading.
   Uses RandomAccessFile for clj to enable efficient offset-based seeking.
   Uses Node.js fs for cljs.
   Serialization is line-delimited Transit JSON."
  (:require
    #?(:clj [clojure.java.io :as io])
    [dao.stream :as ds]
    [dao.stream.transit :as transit]
    #?(:cljs [fs :as fs])
    #?(:cljs [path :as path]))
  #?(:clj
     (:import
       (java.io
         RandomAccessFile))))


#?(:clj
   (defrecord FileStream
     [path raf-atom closed?-atom]

     ds/IDaoStreamWriter

     (put!
       [_this val]
       (if @closed?-atom
         (throw (ex-info "Cannot put to closed stream" {:path path}))
         (let [s (transit/encode val)
               line (str s "\n")
               bytes (.getBytes line "UTF-8")]
           (.seek ^RandomAccessFile @raf-atom (.length ^RandomAccessFile @raf-atom))
           (.write ^RandomAccessFile @raf-atom bytes)
           {:result :ok, :woke []})))


     ds/IDaoStreamReader

     (next
       [_this cursor]
       (let [pos (or (:position cursor) 0)
             raf ^RandomAccessFile @raf-atom
             len (.length raf)]
         (cond
           (< pos len)
           (do
             (.seek raf pos)
             (let [line (.readLine raf)]
               (if (nil? line)
                 :end
                 {:ok (transit/decode line)
                  :cursor {:position (.getFilePointer raf)}})))
           @closed?-atom :end
           :else :blocked)))


     ds/IDaoStreamBound

     (close!
       [_this]
       (when-not @closed?-atom
         (.close ^RandomAccessFile @raf-atom)
         (reset! closed?-atom true))
       {:woke []})


     (closed? [_this] @closed?-atom)))


#?(:cljs
   (defrecord NodeFileStream
     [path fd-atom closed?-atom]

     ds/IDaoStreamWriter

     (put!
       [_this val]
       (if @closed?-atom
         (throw (ex-info "Cannot put to closed stream" {:path path}))
         (let [s (transit/encode val)
               line (str s "\n")
               buffer (js/Buffer.from line "utf8")]
           (fs/writeSync @fd-atom buffer 0 (.-length buffer) nil)
           {:result :ok, :woke []})))


     ds/IDaoStreamReader

     (next
       [_this cursor]
       (let [pos (or (:position cursor) 0)
             stats (fs/fstatSync @fd-atom)
             len (.-size stats)]
         (cond
           (< pos len)
           (let [buffer (js/Buffer.alloc 4096)
                 bytes-read (fs/readSync @fd-atom buffer 0 4096 pos)
                 s (.toString buffer "utf8" 0 bytes-read)
                 newline-idx (.indexOf s "\n")]
             (if (== newline-idx -1)
               ;; Incomplete line or very long line (simpler: assume transit lines < 4096 for now)
               ;; A more robust impl would loop and grow buffer.
               :blocked
               (let [line (.substring s 0 newline-idx)
                     val (transit/decode line)]
                 {:ok val
                  :cursor {:position (+ pos newline-idx 1)}})))
           @closed?-atom :end
           :else :blocked)))


     ds/IDaoStreamBound

     (close!
       [_this]
       (when-not @closed?-atom
         (fs/closeSync @fd-atom)
         (reset! closed?-atom true))
       {:woke []})


     (closed? [_this] @closed?-atom)))


(defn make-file-stream
  [path]
  #?(:clj
     (let [file (io/file path)]
       (when-not (.exists file)
         (io/make-parents file)
         (.createNewFile file))
       (->FileStream path (atom (RandomAccessFile. file "rw")) (atom false)))
     :cljs
     (do
       (when-not (fs/existsSync path)
         ;; Ensure directory exists
         (let [dir (path/dirname path)]
           (when-not (fs/existsSync dir)
             (fs/mkdirSync dir #js {:recursive true})))
         (fs/writeFileSync path ""))
       (let [fd (fs/openSync path "r+")]
         (->NodeFileStream path (atom fd) (atom false))))))


(defmethod ds/open! :file [descriptor]
  (let [path (get-in descriptor [:transport :path])]
    (make-file-stream path)))
