(ns dao.jing.file
  "Content-addressed segment store backed by a private framed append-only
   file (docs/design/dao.jing.md; docs/design/dao.jing.cbor.md, Memory and
   files; dao.jing.implementation-plan.md, Decision 2 and invariants
   F1-F6).

   Framing: each record is one frame of [4-byte big-endian signed length]
   [CBOR array of two byte strings: the raw digest the address carries,
   then the exact canonical payload bytes]. The length prefix is the
   mechanism F3 needs -- it is what makes an incomplete tail detectable: a
   tail shorter than the prefix, a negative length, or a length reaching
   past end of file is torn. The two-element array is this backend's own
   frame, written and parsed with Jing's shared codec (the one exception
   docs/design/dao.jing.cbor.md names, because this is the only backend
   that re-ingests its own output across process death). The algorithm is
   not carried: at replay the payload is hashed under each registered
   algorithm and the one whose digest the frame carries names the address.
   The outer frame never contributes to a content hash; the payload bytes
   are stored and served exactly as written, never replaced by a decoded
   and re-encoded value.

   Opening validates before it mutates: every complete frame must parse,
   carry a digest of its payload, and hold a canonical payload, and a
   nonempty file must yield at least one such frame, or the open fails
   with the file untouched. Only then is an incomplete tail truncated. No
   magic, no header: a file that is not a content log fails loudly, and
   an interrupted first write (never acknowledged) is recreated, not
   recovered.

   The durable log is not a stream (Decision 2): one reader -- this backend,
   once, at open -- and one writer -- its own put. No cursor is handed out,
   no descriptor projected, nothing attaches, and no dao.stream namespace is
   required.

   The handle is a plain-data byte store (D1): {:put-bytes-fn f
   :get-bytes-fn g :close-fn c} and nothing else. Bytes are copied on the
   way in and on the way out, so no caller can change a stored snapshot.
   records (F5) is the test's view of a path: every decoded frame in order,
   equal duplicates included."
  (:require #?@(:cljd [["dart:convert" :as convert] ["dart:io" :as dart-io]
                       ["dart:typed_data" :as typed]])
            [clojure.string :as str]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]))


;; =============================================================================
;; Host bytes
;; =============================================================================

(defn ->bytes
  "Convert a string into UTF-8 bytes on the current host."
  [payload]
  #?(:clj (.getBytes ^String payload "UTF-8")
     :cljs (js/Buffer.from payload "utf8")
     :cljd (typed/Uint8List.fromList (convert/utf8.encode payload))))


(defn- byte-count
  [bs]
  #?(:clj (alength ^bytes bs)
     :cljs (.-length bs)
     :cljd (.-length ^typed/Uint8List bs)))


(def ^:private hex-digits "0123456789abcdef")


(defn- bytes->hex
  [bs]
  (apply str (map (fn [b]
                    (str (nth hex-digits (quot b 16))
                         (nth hex-digits (rem b 16))))
                  #?(:clj (map #(bit-and (int %) 0xff) bs)
                     :cljs (js/Array.from bs)
                     :cljd bs))))


(defn- hex->bytes
  [hex]
  (let [ints (mapv (fn [i]
                     (+ (* 16 (str/index-of hex-digits (subs hex i (inc i))))
                        (str/index-of hex-digits
                                      (subs hex (inc i) (+ i 2)))))
                   (range 0 (count hex) 2))]
    #?(:clj (byte-array (map unchecked-byte ints))
       :cljs (js/Uint8Array.from (clj->js ints))
       :cljd (typed/Uint8List.fromList ints))))


;; =============================================================================
;; Frame codec: [digest payload-bytes] as one canonical CBOR array
;; =============================================================================

(def ^:private max-frame-length
  "The largest frame the signed 32-bit length prefix can describe."
  2147483647)


(defn- frame-bytes
  "The frame (without its length prefix) carrying payload bytes at address."
  [address payload-bytes]
  (let [frame (cbor/encode [(hex->bytes (jing/segment-digest address))
                            payload-bytes])]
    (when (> (byte-count frame) max-frame-length)
      (throw (ex-info "Frame exceeds the length prefix's positive range"
                      {:address address, :length (byte-count frame)})))
    frame))


(defn encode-record
  "Encode an [address payload] pair into frame bytes (the length prefix is
   written by the frame layer). The payload must hash to the address."
  [address payload]
  (frame-bytes address (jing/segment-bytes address payload)))


(defn- decode-frame
  "[address payload-bytes] of one frame: the frame must be exactly one
   canonical two-element CBOR array of byte strings, the digest must be
   the payload's under some registered algorithm (which then names the
   address), and the payload must itself be a canonical Jing payload."
  [frame]
  (let [v (try (cbor/decode frame)
               (catch #?(:cljd Object :clj Throwable :cljs :default) e
                 (throw (ex-info "Frame is not a canonical CBOR record"
                                 {:frame frame} e))))]
    (when-not (and (vector? v) (= 2 (count v))
                   (every? cbor/byte-payload? v))
      (throw (ex-info "Frame is not a [digest payload] array of byte strings"
                      {:record v})))
    (let [[digest payload] v
          hex (bytes->hex digest)
          address-id
          (some (fn [[algorithm {:keys [address-id digest-bytes]}]]
                  (when (and (= digest-bytes (byte-count digest))
                             (= hex (jing/digest-bytes algorithm payload)))
                    address-id))
                (sort-by key jing/registry))]
      (when-not address-id
        (throw (ex-info
                 (str "Frame digest is not the payload's under any "
                      "registered algorithm")
                 {:digest hex})))
      (try (cbor/decode payload)
           (catch #?(:cljd Object :clj Throwable :cljs :default) e
             (throw (ex-info "Frame payload is not a canonical Jing payload"
                             {:digest hex} e))))
      [(keyword "segment" (str address-id "-" hex)) payload])))


;; =============================================================================
;; Host file primitives -- the only host-aware code below
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
  "The len bytes at offset as the host byte array the codec accepts."
  [f offset len]
  #?(:clj (do (.seek ^java.io.RandomAccessFile f offset)
              (let [b (byte-array len)]
                (.readFully ^java.io.RandomAccessFile f b)
                b))
     :cljs (let [buf (js/Buffer.alloc len)]
             (.readSync ^js (:fs f) (:fd f) buf 0 len offset)
             (js/Uint8Array. buf))
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
;; Validate-then-truncate replay (F3, F2, F4)
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


(defn- recover!
  "Every complete frame of the file, decoded and validated in order as
   [address payload-bytes], with the incomplete tail truncated afterwards.
   Validation precedes mutation: a corrupt complete frame, or a nonempty
   file with no valid first frame, throws with the file untouched."
  [f]
  (let [end (file-length! f)
        cut (torn-tail-offset f end)
        records (loop [offset 0, acc []]
                  (if (>= offset cut)
                    acc
                    (let [len (read-int! f offset)]
                      (recur (+ offset prefix-size len)
                             (conj acc
                                   (decode-frame
                                     (read-bytes! f (+ offset prefix-size)
                                                  len)))))))]
    (when (and (pos? end) (empty? records))
      (throw (ex-info "Not a content log: no valid first frame"
                      {:length end})))
    (when (< cut end) (truncate-file! f cut))
    records))


(defn- replay-records
  "Accumulate validated records into the content map. An equal duplicate
   record is tolerated (F2), which requires comparing the incoming bytes
   with the stored ones; the unequal arm of that comparison fails the open.

   That arm is unreachable twice over: decode-frame has already enforced
   that a frame's digest hashes its payload, so two validated frames at one
   address carry equal bytes; and reaching it otherwise would take a hash
   collision, which is not a case to design for. It is retained not as
   defence but because F2's tolerate-equal rule forces the comparison, and
   the only alternatives to throwing here are to overwrite silently or to
   ignore silently -- both of which break `materialize!`'s promise that
   nothing is ever overwritten."
  [records]
  (reduce (fn [content [address payload]]
            (if (contains? content address)
              (if (cbor/bytes= (clojure.core/get content address) payload)
                content
                (throw (ex-info
                         "Collision: address already holds a different payload"
                         {:address address})))
              (assoc content address payload)))
          {}
          records))


;; =============================================================================
;; Handle functions (D1, D3, F1)
;; =============================================================================

(defn- with-lock
  #_{:clj-kondo/ignore [:unused-binding]}
  [lock f]
  #?(:clj (locking lock (f))
     :default (f)))


(defn- make-put
  [state f lock]
  (fn content-put!
    [address bs]
    ;; copy first: the caller owns bs and may change it at any moment, so
    ;; validation, framing and every comparison see only the snapshot
    (let [snapshot (cbor/copy-bytes bs)]
      (when-not (jing/segment-address? address)
        (throw (ex-info "Not a segment content address" {:address address})))
      (when-not (jing/segment-bytes-match? address snapshot)
        (throw (ex-info "Segment address does not match the bytes"
                        {:address address})))
      (let [frame (frame-bytes address snapshot)]
        (with-lock
          lock
          (fn []
            (let [{:keys [closed? content]} @state]
              (when closed?
                (throw (ex-info "Content file is closed" {:address address})))
              (if (contains? content address)
                (if (cbor/bytes= (clojure.core/get content address) snapshot)
                  :present
                  (throw
                    (ex-info
                      (str "Collision: address already holds a "
                           "different payload")
                      {:address address})))
                (do
                  (append-frame! f frame)
                  (sync-file! f)
                  (swap! state assoc-in [:content address] snapshot)
                  :inserted)))))))))


(defn- make-get
  [state]
  (fn content-get
    [address not-found]
    (let [{:keys [closed? content]} @state]
      (when closed?
        (throw (ex-info "Content file is closed" {:address address})))
      (if (contains? content address)
        (cbor/copy-bytes (clojure.core/get content address))
        not-found))))


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
  "Open or create a content file at path: validate every complete frame,
   truncate any incomplete tail (F3), replay every record into an
   in-memory map of address to payload bytes (F2, F4), and return the
   plain-data byte-store handle {:put-bytes-fn :get-bytes-fn :close-fn}
   (D1). A
   frame that is not a canonical [digest payload] record, whose digest is
   not its payload's under a registered algorithm, or whose payload is not
   canonical fails the open, loudly and without mutation (F4)."
  [path]
  (let [f (open-file! path)]
    (try (let [recovered (replay-records (recover! f))
               state (atom {:closed? false, :content recovered})
               lock #?(:clj (Object.)
                       :cljs (js-obj)
                       :cljd (Object.))]
           {:put-bytes-fn (make-put state f lock),
            :get-bytes-fn (make-get state),
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
  "The decoded, validated [address value] record vector of the file at
   path -- every frame in order, equal duplicates included. Opens with the
   same validate-then-truncate pass as create-content-file, reads and
   closes. This is the file backend's test-facing view (D4): what the
   in-memory map holds is observable through the handle; what the file
   holds is observable here."
  [path]
  (let [f (open-file! path)]
    (try (mapv (fn [[address payload]]
                 [address (jing/segment-value address payload)])
               (recover! f))
         (finally (close-file! f)))))
