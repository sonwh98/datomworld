(ns dao.stream.journal.file
  "The journal's file backend (docs/design/dao.stream.journal.md, File
   backend): the three-function seam of dao.stream.journal over one
   directory, holding `journal.jing`, a dao.jing.file content file, under
   the directory lock of dao.space.store.fs.

   Each journal frame is one content frame, put at the address of its
   bytes.  Replay order is dao.jing.file/records order, and each record's
   value is re-encoded to its bytes, checked against its address.  The
   content file already owns framing, fsync per put, and dropping a torn
   tail at open, so this namespace adds none of them.  A put answering
   `:present` means a frame with those bytes already exists; journal
   frames embed a dense position, so that is a defect, answered as a
   failure the journal turns into poison.

   `backend!` takes the lock before touching anything else in the
   directory; `close!` closes the content file and releases the lock.
   The backend carries the journal's durability declaration under
   :dao.stream.journal/durability, a function answering data."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [dao.jing :as jing]
            [dao.jing.file :as jing-file]
            [dao.space.store.fs :as fs]))


(def content-name "journal.jing")


(def durability
  "What a file journal survives on this host (plan 1.8).  Every host
   declares only a process crash: dao.jing.file syncs each frame but never
   the directory that holds the new file, so a power cut right after the
   first open can lose the file and its identity.  A directory sync would
   let the JVM and Node declare :power-loss.  Node's lock is a claim file
   with the pid-reuse refusal of dao.space.store.fs."
  {:dao.stream.journal/backend :file
   :dao.stream.journal/failure-model :process-crash
   :dao.stream.journal/lock-kind #?(:cljd :os-lock
                                    :clj :os-lock
                                    :cljs :claim-file)
   :dao.stream.journal/persisted #{:identity :content-references}})


(defn- result
  [outcome]
  {:dao.stream/outcome outcome})


(defn- failure
  [defect]
  (assoc (result :dao.stream/transport-error)
         :dao.stream.journal/defect defect))


(defn- ensure-dir!
  "Create dir and its parents when absent: the lock needs it to exist."
  [dir]
  #?(:cljd (let [d (dart-io/Directory. dir)]
             (when-not (.existsSync d) (.createSync d .recursive true)))
     :clj (.mkdirs (java.io.File. ^String dir))
     :cljs (.mkdirSync ^js (js/require "fs") dir #js {:recursive true})))


(defn- address-of
  "The content address of frame bytes under the default algorithm."
  [bs]
  (let [algorithm jing/default-hash-algorithm]
    (keyword "segment"
             (str (get-in jing/registry [algorithm :address-id]) "-"
                  (jing/digest-bytes algorithm bs)))))


(defn- seam
  [path content lock]
  {:dao.stream.journal/frames
   (fn []
     ;; segment-bytes re-encodes and throws unless the bytes hash to
     ;; the address: decode then encode is the identity on accepted
     ;; content, and a throw is a read failure.
     (assoc (result :dao.stream/ok)
            :dao.stream.journal/frames
            (mapv (fn [[address value]] (jing/segment-bytes address value))
                  (jing-file/records path))))
   :dao.stream.journal/write-frame!
   (fn [bs]
     (case ((:put-bytes-fn content) (address-of bs) bs)
       :inserted (result :dao.stream/ok)
       (failure :present)))
   ;; The content file drops a torn tail itself and validates every frame
   ;; it keeps, so the journal never finds an undecodable frame to drop.
   :dao.stream.journal/truncate! (fn [_n] (failure :truncate-unsupported))
   :dao.stream.journal/durability (fn [] durability)
   ::lock lock
   ::close! (fn []
              (try ((:close-fn content))
                   (finally (fs/unlock! lock))))})


(defn backend!
  "Lock dir, creating it when absent, and open its content file.  Answers
   ok with ::backend, or transport-error with :dao.stream.journal/defect
   :locked when dir cannot be locked (another owner holds it, or it
   cannot be created), :open-failed when the content
   file refuses to open.  A refused open releases the lock it took."
  [dir]
  (let [lock (try (ensure-dir! dir)
                  (fs/lock! dir)
                  (catch #?(:cljd Object :clj Throwable :cljs :default) _
                    nil))]
    (if (nil? lock)
      (failure :locked)
      (let [path (str dir "/" content-name)
            content (try (jing-file/create-content-file path)
                         (catch #?(:cljd Object
                                   :clj Throwable
                                   :cljs :default)
                                _
                           nil))]
        (if (nil? content)
          (do (fs/unlock! lock) (failure :open-failed))
          (assoc (result :dao.stream/ok)
                 ::backend (seam path content lock)))))))


(defn close!
  "Close the backend's content file and release its directory lock.
   Idempotent."
  [backend]
  ((::close! backend)))
