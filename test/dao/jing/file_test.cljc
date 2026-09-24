(ns dao.jing.file-test
  "The file backend, written from the plan's invariants F1-F6 and D1-D5
   (docs/design/dao.jing.implementation-plan.md, P1) -- not ported from the
   v1 tests. records is the file-side view (D4/F5); the handle view is
   materialize!/get through :put-bytes-fn/:get-bytes-fn (D1). No
   observer or pool appears here: the pool drains in file_test are P2's."
  (:require #?@(:cljd [["dart:io" :as dart-io] ["dart:typed_data" :as typed]])
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.file :as jing-file]))


;; =============================================================================
;; Host helpers -- raw bytes into the file, per host, for torn tails and
;; hand-written frames. These are the only host-aware lines in the suite.
;; =============================================================================

(defn- temp-path
  [prefix]
  (str "target/test-jing-content-" prefix "-" (random-uuid) ".log"))


(defn- cleanup-file
  [path]
  #?(:clj (let [f (java.io.File. path)] (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") path) (catch :default _))
     :cljd (try (let [f (dart-io/File. path)]
                  (when (.existsSync f) (.deleteSync f)))
                (catch #?(:cljd Object
                          :default Exception)
                       _
                  nil))))


(defn- host-bytes
  "A host byte object from a seq of ints 0-255."
  [ints]
  #?(:clj (byte-array (mapv unchecked-byte ints))
     :cljs (js/Buffer.from (clj->js (vec ints)))
     :cljd (typed/Uint8List.fromList ints)))


(defn- int32-be
  "The four bytes of n as a big-endian signed int32."
  [n]
  (mapv #(bit-and (bit-shift-right n %) 0xFF) [24 16 8 0]))


(defn- byte-count
  [bs]
  #?(:clj (alength bs) :cljs (.-length bs) :cljd (.-length bs)))


(defn- bytes->ints
  [bs]
  #?(:clj (mapv #(bit-and (int %) 0xFF) bs)
     :cljs (vec (js/Array.from bs))
     :cljd (vec bs)))


(defn- frame-of
  "A complete frame around record bytes: the big-endian length prefix the
   backend writes, then the bytes. encode-record produces record bytes only
   -- the prefix is the frame layer's, so a raw append must go through here
   to produce what the scanner treats as a frame rather than a torn tail."
  [record-bytes]
  (host-bytes (concat (int32-be (byte-count record-bytes))
                      (bytes->ints record-bytes))))


(defn- set-byte!
  "Overwrite index i of host byte array bs with b, in place."
  [bs i b]
  #?(:clj (aset ^bytes bs i (unchecked-byte b))
     :cljs (aset bs i b)
     :cljd (. bs "[]=" i b)))


(defn- bytes-of
  "The canonical bytes of a value."
  [v]
  (jing/canonical-bytes v))


(def ^:private hex-digits "0123456789abcdef")


(defn- hex->ints
  [hex]
  (mapv (fn [i]
          (+ (* 16 (.indexOf hex-digits (subs hex i (inc i))))
             (.indexOf hex-digits (subs hex (inc i) (+ i 2)))))
        (range 0 (count hex) 2)))


(defn- digest-of
  "The raw blake3 digest bytes of payload bytes, as the frame carries it."
  [payload-bytes]
  (host-bytes (hex->ints (jing/digest-bytes :blake3 payload-bytes))))


(defn- cbor-frame
  "A genuine [digest payload-bytes] record, as the backend writes it."
  [digest-bytes payload-bytes]
  (cbor/encode [digest-bytes payload-bytes]))


(defn- file-length
  [path]
  #?(:clj (.length (java.io.File. path))
     :cljs (.-size (.statSync (js/require "fs") path))
     :cljd (.lengthSync (dart-io/File. path))))


(defn- overwrite-byte!
  "Replace the byte at offset in the file at path with b."
  [path offset b]
  #?(:clj (with-open [raf (java.io.RandomAccessFile.
                            (java.io.File. path) "rw")]
            (.seek raf offset)
            (.write raf (int b)))
     :cljs (let [fs (js/require "fs")
                 fd (.openSync fs path "r+")]
             (try (.writeSync fs fd (js/Buffer.from (clj->js [b])) 0 1 offset)
                  (finally (.closeSync fs fd))))
     :cljd (let [raf (.openSync (dart-io/File. path)
                                .mode dart-io/FileMode.append)]
             (try (.setPositionSync raf offset)
                  (.writeFromSync raf (typed/Uint8List.fromList [b]))
                  (finally (.closeSync raf))))))


(defn- append-raw-bytes!
  "Append raw host bytes at the end of the file at path, bypassing the
   backend -- the torn-tail and fail-closed cases must reach the framing
   layer from outside it. Takes a byte object: encode-record output, or
   (host-bytes (int32-be n)) composed with payload ints."
  [path bs]
  #?(:clj (with-open [raf (java.io.RandomAccessFile.
                            (java.io.File. path) "rw")]
            (.seek raf (.length raf))
            (.write raf bs))
     :cljs (let [fs (js/require "fs")
                 fd (.openSync fs path "a")]
             (try (.writeSync fs fd bs)
                  (finally (.closeSync fs fd))))
     :cljd (let [raf (.openSync (dart-io/File. path)
                                .mode dart-io/FileMode.append)]
             (try (.writeFromSync raf bs)
                  (finally (.closeSync raf))))))


;; =============================================================================
;; F1, F2, B5, B7 -- storage and retrieval
;; =============================================================================

(deftest round-trip-of-every-payload-kind
  (testing
    "F1/F2/B5: materialize! derives the address, the backend stores exactly
            the payload, and every payload kind round-trips -- including nil,
            distinct from absence, and the keyword that was once a sentinel"
    (let [path (temp-path "roundtrip")
          ;; B7: keyword payloads, the former sentinel value among them, so
          ;; a regression to a keyword not-found sentinel is caught here.
          payloads [nil
                    "plain text"
                    :dao.jing/content-missing
                    42
                    -7
                    true
                    [1 2 3]
                    {:a 1, :b {:nested [true nil "s"]}}
                    #{:x :y}]
          handle (jing-file/create-content-file path)]
      (try
        (doseq [payload payloads]
          (let [address (jing/materialize! handle payload)]
            (is (jing/segment-address? address))
            (is (= payload (jing/get handle address ::absent))
                (str "round trip of " (pr-str payload)))
            ;; B5: a stored nil is nil, not the not-found sentinel.
            (when (nil? payload)
              (is (nil? (jing/get handle address ::absent))))))
        (is (= ::absent
               (jing/get handle (jing/segment-key {:never "written"})
                         ::absent))
            "an absent address returns only the caller's sentinel")
        (finally (jing/close! handle) (cleanup-file path))))))


(deftest put-verdicts-and-records-are-the-file
  (testing
    "F1/F5: the first put is acknowledged :inserted after its flush, an
            equal put is :present and writes no record, and records is the
            file itself -- every frame, in order"
    (let [path (temp-path "verdicts")
          a1 (jing/segment-key "one")
          a2 (jing/segment-key {:two 2})
          handle (jing-file/create-content-file path)]
      (try
        (is (= :inserted ((:put-bytes-fn handle) a1 (bytes-of "one"))))
        (is (= :present ((:put-bytes-fn handle) a1 (bytes-of "one"))))
        (is (= 1 (count (jing-file/records path)))
            "an equal put writes no record")
        (is (= :inserted ((:put-bytes-fn handle) a2 (bytes-of {:two 2}))))
        (is (= [[a1 "one"] [a2 {:two 2}]] (jing-file/records path))
            "records preserves frame order, duplicates included")
        (finally (jing/close! handle) (cleanup-file path))))))


(deftest invalid-put-writes-nothing
  (testing
    "B6: the backend validates at its own door -- a non-segment address or an
            address that does not hash to the payload throws and stores
            nothing"
    (let [path (temp-path "invalid-put")
          handle (jing-file/create-content-file path)]
      (try
        (is (thrown? #?(:clj Exception :cljs :default :cljd Object)
              ((:put-bytes-fn handle) :root/not-content (bytes-of "x"))))
        (is (thrown? #?(:clj Exception :cljs :default :cljd Object)
              ((:put-bytes-fn handle) (jing/segment-key "y") (bytes-of "x"))))
        (is (= [] (jing-file/records path)) "nothing was written")
        (finally (jing/close! handle) (cleanup-file path))))))


(deftest a-caller-cannot-mutate-a-file-snapshot-through-its-input-array
  (testing
    "changing the byte array handed to put after the put changes nothing
            the store holds or wrote, across a reopen"
    (let [path (temp-path "isolate-input")
          payload {:isolated "input"}
          address (jing/segment-key payload)
          bs (bytes-of payload)
          handle (jing-file/create-content-file path)]
      (try
        (is (= :inserted ((:put-bytes-fn handle) address bs)))
        (set-byte! bs (dec (byte-count bs)) 0)
        (is (= payload (jing/get handle address ::absent))
            "the in-memory snapshot still decodes to the payload")
        (is (cbor/bytes= (bytes-of payload)
                         ((:get-bytes-fn handle) address ::absent))
            "the stored bytes are the original canonical bytes")
        (jing/close! handle)
        (let [h2 (jing-file/create-content-file path)]
          (try (is (= payload (jing/get h2 address ::absent))
                   "the frame on disk carries the original bytes")
               (is (= [[address payload]] (jing-file/records path)))
               (finally (jing/close! h2))))
        (finally (jing/close! handle) (cleanup-file path))))))


(deftest a-caller-cannot-mutate-a-file-snapshot-through-an-output-array
  (testing
    "changing the byte array a get answered changes nothing the store
            holds or wrote, across a reopen"
    (let [path (temp-path "isolate-output")
          payload {:isolated "output"}
          handle (jing-file/create-content-file path)
          address (jing/materialize! handle payload)]
      (try
        (let [out ((:get-bytes-fn handle) address ::absent)]
          (set-byte! out 0 0)
          (is (= payload (jing/get handle address ::absent))
              "a later read still verifies and decodes")
          (is (cbor/bytes= (bytes-of payload)
                           ((:get-bytes-fn handle) address ::absent))
              "a later read answers the original bytes"))
        (jing/close! handle)
        (let [h2 (jing-file/create-content-file path)]
          (try (is (= payload (jing/get h2 address ::absent))
                   "the frame on disk is untouched")
               (finally (jing/close! h2))))
        (finally (jing/close! handle) (cleanup-file path))))))


;; =============================================================================
;; F2, F4 -- replay, duplicates, and failing the open
;; =============================================================================

(deftest durability-across-close-and-reopen
  (testing
    "F2/F5: every record replays into the content map on the next open"
    (let [path (temp-path "durability")
          a1 (jing/segment-key "alpha")
          a2 (jing/segment-key {:n 2})
          handle (jing-file/create-content-file path)]
      (jing/materialize! handle "alpha")
      (jing/materialize! handle {:n 2})
      (jing/close! handle)
      (let [h2 (jing-file/create-content-file path)]
        (try (is (= "alpha" (jing/get h2 a1 ::absent)))
             (is (= {:n 2} (jing/get h2 a2 ::absent)))
             (finally (jing/close! h2) (cleanup-file path)))))))


(deftest acknowledged-insert-survives-immediate-close
  (testing
    "F1/F5: the :inserted verdict is returned only after the flush, so an
            immediate close cannot lose the record"
    (let [path (temp-path "ack")
          payload {:ack 1}
          address (jing/segment-key payload)
          handle (jing-file/create-content-file path)]
      (jing/materialize! handle payload)
      (jing/close! handle)
      (let [h2 (jing-file/create-content-file path)]
        (try (is (= payload (jing/get h2 address ::absent)))
             (finally (jing/close! h2) (cleanup-file path)))))))


(deftest equal-duplicate-frames-recover
  (testing
    "F2: an equal duplicate record is tolerated on replay; the duplicate
            frame is visible to records and writes nothing new"
    (let [path (temp-path "dup")
          address (jing/segment-key {:k 1})
          payload {:k 1}
          handle (jing-file/create-content-file path)]
      (jing/materialize! handle payload)
      (jing/close! handle)
      ;; Hand-write the identical frame again, bypassing the backend.
      (append-raw-bytes! path
                         (frame-of (jing-file/encode-record address payload)))
      (is (= 2 (count (jing-file/records path)))
          "records sees both frames, equal duplicates included")
      (let [h2 (jing-file/create-content-file path)]
        (try (is (= payload (jing/get h2 address ::absent)))
             (is (= :present ((:put-bytes-fn h2) address (bytes-of payload))))
             (is (= 2 (count (jing-file/records path)))
                 "a :present put writes no record")
             (finally (jing/close! h2) (cleanup-file path)))))))


(deftest fail-closed-categories-fail-the-open
  (testing
    "F4: a complete frame that is not one canonical two-element CBOR array
            of byte strings, whose digest is not its payload's under a
            registered algorithm, or whose payload bytes are not one
            canonical Jing payload fails the open, loudly and without
            mutating the file"
    (let [p (bytes-of "ok")
          d (digest-of p)
          ;; int 1 in a non-shortest head: valid CBOR, refused as
          ;; non-canonical; its digest still matches
          noncanonical (host-bytes [0x18 0x01])
          ;; the array head promises two items, one follows
          truncated (host-bytes [0x82 0x01])
          ;; two complete items where exactly one is allowed
          trailing (host-bytes [0x01 0x02])
          ;; a well-formed frame written with an indefinite-length array
          indefinite (host-bytes (concat [0x9f]
                                         (bytes->ints (cbor/encode d))
                                         (bytes->ints (cbor/encode p))
                                         [0xff]))]
      (doseq [[name record]
              [["not CBOR at all" (jing-file/->bytes "[[[not cbor")]
               ["three-element array" (cbor/encode [d p (host-bytes [1])])]
               ["text payload, not bytes" (cbor/encode [d "ok"])]
               ["a map, not an array" (cbor/encode {:digest d, :payload p})]
               ["digest of the wrong length"
                (cbor-frame (host-bytes (take 16 (bytes->ints d))) p)]
               ["digest of another payload"
                (cbor-frame (digest-of (bytes-of "different")) p)]
               ["non-canonical payload bytes"
                (cbor-frame (digest-of noncanonical) noncanonical)]
               ["truncated payload bytes"
                (cbor-frame (digest-of truncated) truncated)]
               ["payload with trailing data"
                (cbor-frame (digest-of trailing) trailing)]
               ["non-canonical frame encoding" indefinite]]]
        (let [path (temp-path "corrupt")
              frame-bytes (frame-of record)]
          (append-raw-bytes! path frame-bytes)
          (try (is (thrown? #?(:clj Exception :cljs :default :cljd Object)
                     (jing-file/create-content-file path))
                   name)
               (is (= (byte-count frame-bytes) (file-length path))
                   (str name ": the refused file is not mutated"))
               (finally (cleanup-file path))))))))


(deftest a-mutated-byte-fails-the-open-without-mutation
  (testing
    "a stored frame whose payload bytes change on disk no longer hashes to
            its digest: the open and records both refuse, and neither
            truncates or rewrites anything"
    (let [path (temp-path "mutated")
          payload {:mutation "target"}
          handle (jing-file/create-content-file path)]
      (jing/materialize! handle payload)
      (jing/close! handle)
      (let [len (file-length path)
            last-offset (dec len)
            original (last (bytes->ints
                             (jing-file/encode-record
                               (jing/segment-key payload) payload)))]
        (try
          ;; the payload is the frame's tail, so its last byte is the
          ;; file's last byte; flip it
          (overwrite-byte! path last-offset (bit-xor original 0xff))
          (is (thrown? #?(:clj Exception :cljs :default :cljd Object)
                (jing-file/create-content-file path))
              "the open refuses the mutated frame")
          (is (thrown? #?(:clj Exception :cljs :default :cljd Object)
                (jing-file/records path))
              "records refuses it too")
          (is (= len (file-length path)) "nothing was truncated")
          ;; restoring the byte restores the store
          (overwrite-byte! path last-offset original)
          (let [h2 (jing-file/create-content-file path)]
            (try (is (= payload (jing/get h2 (jing/segment-key payload)
                                          ::absent)))
                 (finally (jing/close! h2))))
          (finally (cleanup-file path)))))))


(deftest unequal-duplicate-at-one-address-fails-the-open
  (testing
    "F2's collision case. Two individually valid frames at one address must
            be equal (B6: the address hashes the payload), so an unequal
            duplicate necessarily fails the second frame's own hash check --
            the open fails on F4's ground before the accumulation's
            defensive collision branch, which is unreachable by
            construction, is ever reached"
    (let [path (temp-path "collision")
          good (jing/segment-key "ok")]
      (append-raw-bytes! path (frame-of (jing-file/encode-record good "ok")))
      (append-raw-bytes!
        path (frame-of (cbor-frame (digest-of (bytes-of "ok"))
                                   (bytes-of "other"))))
      (try (is (thrown? #?(:clj Exception :cljs :default :cljd Object)
                 (jing-file/create-content-file path)))
           (finally (cleanup-file path))))))


;; =============================================================================
;; F3 -- torn-tail truncation, each category hand-written per host
;; =============================================================================

(defn- torn-tail-recovers
  "The F3 proof shape: one valid record, a torn tail of raw bytes, and the
   open must truncate, replay the survivor, accept a clean put, and reopen
   with both records."
  [prefix torn-bytes]
  (let [path (temp-path prefix)
        a1 (jing/segment-key "survivor")
        a2 (jing/segment-key {:after :truncation})
        handle (jing-file/create-content-file path)]
    (jing/materialize! handle "survivor")
    (jing/close! handle)
    (append-raw-bytes! path torn-bytes)
    (try
      (let [h2 (jing-file/create-content-file path)]
        ;; close is idempotent, so the outer finally may re-close h2.
        (try
          (is (= [[a1 "survivor"]] (jing-file/records path))
              "the torn tail is truncated; only the survivor replays")
          (is (= "survivor" (jing/get h2 a1 ::absent)))
          (jing/materialize! h2 {:after :truncation})
          (jing/close! h2)
          (let [h3 (jing-file/create-content-file path)]
            (try
              (is (= [[a1 "survivor"] [a2 {:after :truncation}]]
                     (jing-file/records path))
                  "a put after truncation lands clean and survives")
              (is (= {:after :truncation} (jing/get h3 a2 ::absent)))
              (finally (jing/close! h3))))
          (finally (jing/close! h2))))
      (finally (cleanup-file path)))))


(deftest torn-tail-overlong-length-is-truncated
  (testing
    "F3: a length reaching past end of file is torn -- int32 999 with only
            two payload bytes (hand-written, not the v1 fixture: the torn
            bytes must follow a valid record)"
    (torn-tail-recovers "torn-overlong"
                        (host-bytes (into (int32-be 999) [1 2])))))


(deftest torn-tail-shorter-than-prefix-is-truncated
  (testing "F3: a tail shorter than the 4-byte length prefix is torn"
    (torn-tail-recovers "torn-subprefix" (host-bytes [7 7]))))


(deftest torn-tail-negative-length-is-truncated
  (testing "F3: a negative frame length is torn"
    (torn-tail-recovers "torn-negative" (host-bytes (int32-be -1)))))


;; =============================================================================
;; D2, D3, F6 -- handle lifecycle
;; =============================================================================

(deftest close-semantics
  (testing
    "D2/D3/F6: close! returns nil and is idempotent; after close every
            entry point throws; stored content is neither cleared nor
            rewritten"
    (let [path (temp-path "close")
          payload {:v 1}
          address (jing/segment-key payload)
          handle (jing-file/create-content-file path)]
      (jing/materialize! handle payload)
      (is (nil? (jing/close! handle)))
      (is (nil? (jing/close! handle)) "close is idempotent")
      (is (thrown? #?(:clj Exception :cljs :default :cljd Object)
            (jing/materialize! handle {:x 1})))
      (is (thrown? #?(:clj Exception :cljs :default :cljd Object)
            (jing/get handle address ::absent)))
      (is (thrown? #?(:clj Exception :cljs :default :cljd Object)
            ((:put-bytes-fn handle) address (bytes-of payload))))
      (is (= [[address payload]] (jing-file/records path))
          "closing does not clear or rewrite the file")
      (cleanup-file path))))


(deftest contention-writes-exactly-one-record
  (testing
    "D5/F6: under JVM contention, N concurrent puts of one payload yield
            exactly one :inserted, N-1 :present, and one record"
    #?(:clj (let [path (temp-path "contention")
                  payload {:contended true}
                  address (jing/segment-key payload)
                  handle (jing-file/create-content-file path)]
              (try (let [results (mapv #(deref % 5000 ::timeout)
                                       (doall (repeatedly
                                                16
                                                (fn []
                                                  (future
                                                    ((:put-bytes-fn handle)
                                                     address
                                                     (bytes-of payload)))))))]
                     (is (not-any? #{::timeout} results))
                     (is (= 1 (count (filter #{:inserted} results))))
                     (is (= 15 (count (filter #{:present} results))))
                     (is (= [[address payload]] (jing-file/records path))))
                   (finally (jing/close! handle) (cleanup-file path))))
       :cljs (is true "JVM contention is a clj-only property")
       :cljd (is true "JVM contention is a clj-only property"))))


(deftest multi-algorithm-file-store-and-replay-test
  (testing "file backend stores both algorithms and replays cleanly"
    (let [path         (temp-path "multi-algo")
          h1           (jing-file/create-content-file path)
          p1           {:msg "first payload", :n 1}
          p2           {:msg "second payload", :n 2}
          addr-b3      (jing/materialize! h1 p1)
          addr-s256    (jing/materialize! h1 p2 {:algorithm :sha256})
          addr-p1-s256 (jing/materialize! h1 p1 {:algorithm :sha256})]
      (is (= :blake3 (jing/segment-algorithm addr-b3)))
      (is (= :sha256 (jing/segment-algorithm addr-s256)))
      (is (= :sha256 (jing/segment-algorithm addr-p1-s256)))
      (is (not= addr-b3 addr-p1-s256))
      (is (= p1 (jing/get h1 addr-b3 ::absent)))
      (is (= p2 (jing/get h1 addr-s256 ::absent)))
      (is (= p1 (jing/get h1 addr-p1-s256 ::absent)))
      (jing/close! h1)
      (let [h2 (jing-file/create-content-file path)]
        (try
          (is (= p1 (jing/get h2 addr-b3 ::absent)))
          (is (= p2 (jing/get h2 addr-s256 ::absent)))
          (is (= p1 (jing/get h2 addr-p1-s256 ::absent)))
          (is (= 3 (count (jing-file/records path))))
          (finally
            (jing/close! h2)
            (cleanup-file path)))))))
