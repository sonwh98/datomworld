(ns dao.jing.file
  "Content-addressed segment store backed by a private framed append-only
   file (docs/design/dao.jing.md; dao.jing.implementation-plan.md, Decision 2
   and invariants F1-F6).

   Framing, chosen rather than inherited: each record is one frame of
   [4-byte big-endian signed length][UTF-8 EDN text of [address payload]].
   The length prefix is the mechanism F3 needs — it is what makes an
   incomplete tail detectable: a tail shorter than the prefix, a negative
   length, or a length reaching past end of file is torn and truncated to
   the last frame boundary. EDN is the one value codec clj, cljs and cljd
   share with structural round-trip. Integrity rests on neither choice:
   every frame must carry a segment address that hashes to its payload
   (B6/F4), so the day the pinned canonical byte encoding lands it replaces
   the payload text without touching the frame — and no version header is
   owed, because that day every minted address changes with it (dao.jing.md,
   Canonical encoding), so re-materialization is required regardless and a
   frame version buys nothing. No magic, no header: a file that is not a
   content file fails per-record validation loudly.

   The durable log is not a stream (Decision 2): one reader — this backend,
   once, at open — and one writer — its own put. No cursor is handed out,
   no descriptor projected, nothing attaches, and no dao.stream namespace is
   required.

   The handle is plain data (D1): {:put-content-fn f :get-content-fn g
   :close-fn c} and nothing else. records (F5) is the test's view of a path:
   every decoded frame in order, equal duplicates included."
  (:require #?@(:cljd [["dart:convert" :as convert] ["dart:io" :as dart-io]
                       ["dart:typed_data" :as typed]])
            [clojure.edn :as edn]
            [dao.jing :as jing]))


;; =============================================================================
;; Serialization / byte conversion
;; =============================================================================

(defn ->bytes
  "Convert a string into UTF-8 bytes on the current host."
  [payload]
  #?(:clj (.getBytes ^String payload "UTF-8")
     :cljs (js/Buffer.from payload "utf8")
     :cljd (typed/Uint8List.fromList (convert/utf8.encode payload))))


(defn bytes->str
  "Convert UTF-8 bytes back into a string."
  [b]
  #?(:clj (String. ^bytes b "UTF-8")
     :cljs (.toString ^js b "utf8")
     :cljd (convert/utf8.decode b)))


(defn encode-record
  "Encode an [address payload] pair into frame bytes (the payload text; the
   length prefix is written by the frame layer)."
  [address payload]
  (->bytes (pr-str [address payload])))


(defn decode-record
  "Decode frame bytes back into their EDN value. Returns the parsed value
   as-is; throws on malformed EDN."
  [b]
  (edn/read-string (bytes->str b)))


;; =============================================================================
;; Record validation (B6 at this backend's door; F4 at replay)
;; =============================================================================

(defn- validate-address-payload!
  [address payload]
  (if (and (jing/segment-address? address)
           (= (jing/segment-hash address) (jing/content-hash payload)))
    [address payload]
    (throw (ex-info "Segment address does not match payload"
                    {:address address, :payload payload}))))


(defn- validate-codec-round-trip!
  "Fail closed on content this backend's own text codec cannot carry
   (dao.jing.md, Open items: every backend must either carry metadata
   through or refuse). The frame layer writes `pr-str` and replays through
   EDN, and `pr-str` drops collection metadata — which the address does
   NOT drop — so a metadata-bearing payload would be written with its
   metadata silently gone and fail the open on replay, unopenably. The
   rule is the doc's own: a payload whose round trip through the backend's
   codec does not hash back to its address is refused here, before any
   byte is written. Reader positions round-trip fine (the address ignores
   them), so they are not refused."
  [payload]
  (let [replayed (edn/read-string (pr-str payload))]
    (when-not (= (jing/content-hash payload) (jing/content-hash replayed))
      (throw (ex-info "Payload does not survive this backend's text codec: its round trip would not hash to its content address"
                      {:payload payload, :replayed replayed})))))


(defn- validate-frame!
  [record]
  (if (and (vector? record) (= 2 (count record)))
    (validate-address-payload! (first record) (second record))
    (throw (ex-info "Record is not a 2-element [address payload] vector"
                    {:record record}))))


;; =============================================================================
;; Host file primitives — the only host-aware code in this namespace
;; =============================================================================

(def ^:private prefix-size 4)


(defn- open-file!
  "Open or create the file at path for reading and appending, creating
   parent directories. Returns the host file object."
  [path]
  #?(:clj (let [f (java.io.File. path)]
            (when-let [parent (.getParentFile f)] (.mkdirs parent))
            (java.io.RandomAccessFile. f "rw"))
     :cljs (if-not (exists? js/require)
             (throw (ex-info "dao.jing.file requires the Node.js fs module."
                             {:path path}))
             (let [fs (js/require "fs")
                   path-module (js/require "path")
                   dir (.dirname path-module path)]
               (when-not (.existsSync fs dir)
                 (.mkdirSync fs dir #js {:recursive true}))
               {:fs fs, :fd (.openSync fs path "a+")}))
     :cljd (let [f (dart-io/File. path)
                 dir (.-parent f)]
             (when-not (.existsSync dir) (.createSync dir .recursive true))
             (.openSync f .mode dart-io/FileMode.append))))


(defn- file-length!
  [f]
  #?(:clj (.length ^java.io.RandomAccessFile f)
     :cljs (.-size (.fstatSync ^js (:fs f) (:fd f)))
     :cljd (.lengthSync ^dart-io/RandomAccessFile f)))


(defn- read-int!
  "The 4-byte big-endian signed frame length at offset."
  [f offset]
  #?(:clj (do (.seek ^java.io.RandomAccessFile f offset)
              (.readInt ^java.io.RandomAccessFile f))
     :cljs (let [head (js/Buffer.alloc prefix-size)]
             (.readSync ^js (:fs f) (:fd f) head 0 prefix-size offset)
             (.readInt32BE head 0))
     :cljd (do (.setPositionSync ^dart-io/RandomAccessFile f offset)
               (let [^typed/Uint8List bs (.readSync ^dart-io/RandomAccessFile f
                                                    prefix-size)]
                 (.getInt32 (typed/ByteData.view (.-buffer bs)) 0)))))


(defn- read-bytes!
  [f offset len]
  #?(:clj (do (.seek ^java.io.RandomAccessFile f offset)
              (let [b (byte-array len)]
                (.readFully ^java.io.RandomAccessFile f b)
                b))
     :cljs (let [buf (js/Buffer.alloc len)]
             (.readSync ^js (:fs f) (:fd f) buf 0 len offset)
             buf)
     :cljd (do (.setPositionSync ^dart-io/RandomAccessFile f offset)
               (.readSync ^dart-io/RandomAccessFile f len))))


(defn- truncate-file!
  [f offset]
  #?(:clj (.setLength ^java.io.RandomAccessFile f offset)
     :cljs (.ftruncateSync ^js (:fs f) (:fd f) offset)
     :cljd (.truncateSync ^dart-io/RandomAccessFile f offset)))


(defn- append-frame!
  "Write the length-prefixed frame at the current end of the file."
  [f frame]
  #?(:clj (let [raf ^java.io.RandomAccessFile f
                len (alength ^bytes frame)]
            (.seek raf (.length raf))
            (.writeInt raf len)
            (.write raf ^bytes frame))
     :cljs (let [{:keys [fs fd]} f
                 len (.-length ^js frame)
                 head (js/Buffer.alloc prefix-size)]
             (.writeInt32BE head len 0)
             (.writeSync ^js fs fd head)
             (.writeSync ^js fs fd frame))
     :cljd (let [raf ^dart-io/RandomAccessFile f
                 len (.-length ^typed/Uint8List frame)
                 head (typed/ByteData. prefix-size)]
             (.setInt32 head 0 len)
             (.setPositionSync raf (.lengthSync raf))
             (.writeFromSync raf (.asUint8List (.-buffer head)))
             (.writeFromSync raf frame))))


(defn- sync-file!
  "Flush the frame just written to durable storage (F1: a put is
   acknowledged only after this)."
  [f]
  #?(:clj (.sync (.getFD ^java.io.RandomAccessFile f))
     :cljs (.fsyncSync ^js (:fs f) (:fd f))
     :cljd (.flushSync ^dart-io/RandomAccessFile f)))


(defn- close-file!
  [f]
  #?(:clj (.close ^java.io.RandomAccessFile f)
     :cljs (.closeSync ^js (:fs f) (:fd f))
     :cljd (.closeSync ^dart-io/RandomAccessFile f)))


;; =============================================================================
;; Torn-tail truncation and replay (F3, F2, F4)
;; =============================================================================

(defn- torn-tail-offset
  "The offset of the first incomplete frame, scanning from 0: a tail shorter
   than the length prefix, a negative length, or a length reaching past
   `end` is torn. Returns `end` when every frame is complete."
  [f end]
  (loop [offset 0]
    (cond
      (>= offset end) end
      (< (- end offset) prefix-size) offset
      :else (let [len (read-int! f offset)]
              (if (or (neg? len) (> (+ offset prefix-size len) end))
                offset
                (recur (+ offset prefix-size len)))))))


(defn- truncate-incomplete-tail!
  "Truncate the incomplete tail, returning the surviving byte length."
  [f]
  (let [end (file-length! f)
        cut (torn-tail-offset f end)]
    (when (< cut end) (truncate-file! f cut))
    cut))


(defn- replay-frames
  "Decode and validate every frame into the content map. An equal duplicate
   record is tolerated (F2), which requires comparing the incoming payload
   with the stored one; the unequal arm of that comparison fails the open.

   That arm is unreachable twice over: `validate-frame!` has already enforced
   that a frame's address hashes its payload, so two validated frames at one
   address carry equal payloads; and reaching it otherwise would take a
   SHA-256 collision, which is not a case to design for. It is retained not as
   defence but because F2's tolerate-equal rule forces the comparison, and the
   only alternatives to throwing here are to overwrite silently or to ignore
   silently — both of which break `materialize!`'s promise that nothing is
   ever overwritten."
  [f end]
  (loop [offset 0, content {}]
    (if (>= offset end)
      content
      (let [len (read-int! f offset)
            [address payload] (validate-frame!
                                (decode-record
                                  (read-bytes! f (+ offset prefix-size) len)))]
        (recur (+ offset prefix-size len)
               (if (contains? content address)
                 (if (= (clojure.core/get content address) payload)
                   content
                   (throw (ex-info
                            "Collision: address already holds a different payload"
                            {:address address})))
                 (assoc content address payload)))))))


;; =============================================================================
;; Handle functions (D1, D3, F1)
;; =============================================================================

#_{:clj-kondo/ignore [:unused-binding]}


(defn- with-lock
  [lock f]
  #?(:clj (locking lock (f))
     :default (f)))


(defn- make-put
  [state f lock]
  (fn content-put!
    [address payload]
    (validate-address-payload! address payload)
    (validate-codec-round-trip! payload)
    (with-lock
      lock
      (fn []
        (let [{:keys [closed? content]} @state]
          (when closed?
            (throw (ex-info "Content file is closed" {:address address})))
          (if (contains? content address)
            (if (= (clojure.core/get content address) payload)
              :present
              (throw (ex-info
                       "Collision: address already holds a different payload"
                       {:address address})))
            (let [frame (encode-record address payload)]
              (append-frame! f frame)
              (sync-file! f)
              (swap! state assoc-in [:content address] payload)
              :inserted)))))))


(defn- make-get
  [state]
  (fn content-get
    [address not-found]
    (let [{:keys [closed? content]} @state]
      (when closed?
        (throw (ex-info "Content file is closed" {:address address})))
      (clojure.core/get content address not-found))))


(defn- make-close
  [state f lock]
  (fn content-close!
    []
    (with-lock
      lock
      (fn []
        (when-not (:closed? @state)
          (swap! state assoc :closed? true)
          (close-file! f))
        nil))))


;; =============================================================================
;; Constructor
;; =============================================================================

(defn create-content-file
  "Open or create a content file at path: truncate any incomplete tail
   (F3), replay every record into an in-memory content map (F2, F4), and
   return the plain-data handle {:put-content-fn :get-content-fn
   :close-fn} (D1). A record that cannot be decoded, is not a two-element
   [address payload] vector, carries a non-segment address, or does not
   hash to its payload fails the open, loudly (F4)."
  [path]
  (let [f (open-file! path)]
    (try (let [recovered (replay-frames f (truncate-incomplete-tail! f))
               state (atom {:closed? false, :content recovered})
               lock #?(:clj (Object.)
                       :cljs (js-obj)
                       :cljd (Object.))]
           {:put-content-fn (make-put state f lock),
            :get-content-fn (make-get state),
            :close-fn (make-close state f lock)})
         (catch #?(:clj Exception
                   :cljs :default
                   :cljd Object)
                e
           (try (close-file! f)
                (catch #?(:clj Exception
                          :cljs :default
                          :cljd Object)
                       _
                  nil))
           (throw e)))))


;; =============================================================================
;; The test's view of a path (F5)
;; =============================================================================

(defn records
  "The decoded, validated record vector of the file at path — every frame
   in order, equal duplicates included. Opens with tail truncation, reads
   and closes. This is the file backend's test-facing view (D4): what the
   in-memory map holds is observable through the handle; what the file
   holds is observable here."
  [path]
  (let [f (open-file! path)]
    (try (let [end (truncate-incomplete-tail! f)]
           (loop [offset 0, acc []]
             (if (>= offset end)
               acc
               (let [len (read-int! f offset)]
                 (recur (+ offset prefix-size len)
                        (conj acc (validate-frame!
                                    (decode-record
                                      (read-bytes! f (+ offset prefix-size)
                                                   len)))))))))
         (finally (close-file! f)))))
