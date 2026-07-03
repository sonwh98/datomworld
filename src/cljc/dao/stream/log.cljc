(ns dao.stream.log
  "Persistent, framed, seekable append-log transport.
   Reads and writes opaque byte-arrays using a 4-byte big-endian length prefix.
   Implements IDaoStreamReader, IDaoStreamWriter, and IDaoStreamBound."
  (:require #?@(:cljd [["dart:io" :as dart-io] ["dart:typed_data" :as typed]]
                :clj [[clojure.java.io :as io]])
            [dao.stream :as ds])
  #?(:cljs (:require-macros [dao.stream])))


#?(:clj (defn- byte-array?
          [v]
          (bytes? v))
   :cljd (defn- byte-array?
           [v]
           (instance? typed/Uint8List v))
   :cljs (defn- byte-array?
           [v]
           (instance? js/Uint8Array v)))


#_{:clj-kondo/ignore [:unused-private-var :unused-binding]}


(defn- do-with-lock
  [lock f]
  #?(:clj (locking lock (f))
     :default (f)))


(defn- check-put!
  [state-atom val]
  (when (:closed @state-atom) (throw (ex-info "Stream is closed" {})))
  (when-not (byte-array? val)
    (throw (ex-info "dao.stream.log expects a byte-array value" {}))))


(defn- do-close!
  [state-atom close-fn]
  (when-not (:closed @state-atom)
    (swap! state-atom assoc :closed true)
    (close-fn))
  nil)


#?(:clj (defrecord CljLogStream
          [raf lock state-atom]

          ds/IDaoStreamWriter

          (put!
            [_ val]
            (check-put! state-atom val)
            (let [len (alength ^bytes val)]
              (do-with-lock lock
                            (fn []
                              (let [offset (.length raf)]
                                (.seek raf offset)
                                (.writeInt raf len)
                                (.write raf ^bytes val)
                                {:result :ok, :cursor {:position offset}})))))


          ds/IDaoStreamReader

          (next
            [_ cursor]
            (if (:closed @state-atom)
              :end
              (let [offset (:position cursor)]
                (do-with-lock
                  lock
                  (fn []
                    (let [end (.length raf)]
                      (if (>= offset end)
                        :blocked
                        (do (.seek raf offset)
                            (let [len (.readInt raf)
                                  b (byte-array len)]
                              (.readFully raf b)
                              {:ok b,
                               :cursor {:position (+ offset 4 len)}})))))))))


          ds/IDaoStreamBound

          (close! [_] (do-close! state-atom #(.close raf)))


          (closed? [_] (:closed @state-atom))))


#?(:cljs (defrecord NodeLogStream
           [fs fd state-atom]

           ds/IDaoStreamWriter

           (put!
             [_ val]
             (check-put! state-atom val)
             (let [len (.-length val)
                   head (js/Buffer.alloc 4)
                   _ (.writeInt32BE head len 0)]
               (do-with-lock nil
                             (fn []
                               (let [offset (.-size (.fstatSync ^js fs fd))]
                                 (.writeSync ^js fs fd head 0 4 offset)
                                 (.writeSync ^js fs fd val 0 len (+ offset 4))
                                 {:result :ok,
                                  :cursor {:position offset}})))))


           ds/IDaoStreamReader

           (next
             [_ cursor]
             (if (:closed @state-atom)
               :end
               (let [offset (:position cursor)]
                 (do-with-lock
                   nil
                   (fn []
                     (let [end (.-size (.fstatSync ^js fs fd))]
                       (if (>= offset end)
                         :blocked
                         (let [head (js/Buffer.alloc 4)]
                           (.readSync ^js fs fd head 0 4 offset)
                           (let [len (.readInt32BE head 0)
                                 buf (js/Buffer.alloc len)]
                             (.readSync ^js fs fd buf 0 len (+ offset 4))
                             {:ok buf,
                              :cursor {:position (+ offset 4 len)}})))))))))


           ds/IDaoStreamBound

           (close! [_] (do-close! state-atom #(.closeSync ^js fs fd)))


           (closed? [_] (:closed @state-atom))))


#?(:cljd
   (defrecord DartLogStream
     [^dart-io/RandomAccessFile raf lock state-atom]

     ds/IDaoStreamWriter

     (put!
       [_ ^typed/Uint8List val]
       (check-put! state-atom val)
       (let [len (.-length val)
             head (typed/ByteData. 4)
             _ (.setInt32 head 0 len)
             head-bytes (.asUint8List (.-buffer head))]
         (do-with-lock lock
                       (fn []
                         (let [offset (.lengthSync raf)]
                           (.setPositionSync raf offset)
                           (.writeFromSync raf head-bytes)
                           (.writeFromSync raf val)
                           {:result :ok, :cursor {:position offset}})))))


     ds/IDaoStreamReader

     (next
       [_ cursor]
       (if (:closed @state-atom)
         :end
         (let [offset (:position cursor)]
           (do-with-lock
             lock
             (fn []
               (let [end (.lengthSync raf)]
                 (if (>= offset end)
                   :blocked
                   (do (.setPositionSync raf offset)
                       (let [^typed/Uint8List head-bytes (.readSync raf 4)
                             head (typed/ByteData.view (.-buffer
                                                         head-bytes))
                             len (.getInt32 head 0)
                             bytes (.readSync raf len)]
                         {:ok bytes,
                          :cursor {:position (+ offset 4 len)}})))))))))


     ds/IDaoStreamBound

     (close! [_] (do-close! state-atom #(.closeSync raf)))


     (closed? [_] (:closed @state-atom))))


(defn make-log-stream
  "Open a framed, seekable append-log stream at `path`."
  [path]
  #?(:clj (let [f (io/file path)]
            (when-let [parent (.getParentFile f)] (.mkdirs parent))
            (let [raf (java.io.RandomAccessFile. f "rw")
                  end (.length raf)]
              (loop [offset 0]
                (cond (>= offset end) nil
                      (< (- end offset) 4) (.setLength raf offset)
                      :else (do (.seek raf offset)
                                (let [len (.readInt raf)]
                                  (if (or (neg? len) (> (+ offset 4 len) end))
                                    (.setLength raf offset)
                                    (recur (+ offset 4 len)))))))
              (->CljLogStream raf (Object.) (atom {:closed false}))))
     :cljs (if-not (exists? js/require)
             (throw (ex-info "dao.stream.log requires Node.js fs module."
                             {:path path}))
             (let [fs (js/require "fs")
                   path-module (js/require "path")
                   dir (.dirname path-module path)]
               (when-not (.existsSync fs dir)
                 (.mkdirSync fs dir #js {:recursive true}))
               (let [fd (.openSync fs path "a+")
                     end (.-size (.fstatSync fs fd))]
                 (loop [offset 0]
                   (cond (>= offset end) nil
                         (< (- end offset) 4) (.ftruncateSync fs fd offset)
                         :else (let [head (js/Buffer.alloc 4)]
                                 (.readSync fs fd head 0 4 offset)
                                 (let [len (.readInt32BE head 0)]
                                   (if (or (neg? len) (> (+ offset 4 len) end))
                                     (.ftruncateSync fs fd offset)
                                     (recur (+ offset 4 len)))))))
                 (->NodeLogStream fs fd (atom {:closed false})))))
     :cljd (let [f (dart-io/File. path)
                 dir (.-parent f)]
             (when-not (.existsSync dir) (.createSync dir .recursive true))
             (let [raf (.openSync f .mode dart-io/FileMode.append)
                   end (.lengthSync raf)]
               (loop [offset 0]
                 (cond (>= offset end) nil
                       (< (- end offset) 4) (.truncateSync raf offset)
                       :else (do (.setPositionSync raf offset)
                                 (let [^typed/Uint8List head-bytes
                                       (.readSync raf 4)
                                       head (typed/ByteData.view (.-buffer
                                                                   head-bytes))
                                       len (.getInt32 head 0)]
                                   (if (or (neg? len) (> (+ offset 4 len) end))
                                     (.truncateSync raf offset)
                                     (recur (+ offset 4 len)))))))
               (->DartLogStream raf (Object.) (atom {:closed false}))))))


(ds/defopen :append-log [descriptor] (make-log-stream (:path descriptor)))
