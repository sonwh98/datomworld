(ns dao.host.text
  "The host strings of the primitive profile
   (docs/design/dao.stream.host-boundary.md §4.2, table A). A host string
   is a sequence of UTF-16 code units on every host (JVM `String`, JS
   string, Dart `String`).

   Table A, kind P (§2.4): `unit-count`, `code-unit`, `units->string`,
   `utf8-encode`, `utf8-decode`, `nfc`. Equal arguments give equal
   results on every host:
   - `utf8-encode` refuses an unpaired surrogate `:unpaired-surrogate`
     instead of substituting (the JVM's `?`, U+FFFD elsewhere), so the
     bytes of every string it accepts are identical on every host; it
     returns fresh bytes the caller holds the mutation right to.
   - `utf8-decode` is strict RFC 3629: an overlong form, an encoded
     surrogate, a code point above U+10FFFF or a truncated sequence is
     refused `:invalid-utf8`, never decoded to U+FFFD; a leading BOM is
     content. It is the decoder of `dao.jing.cbor` (`utf8`), extracted.
   - `nfc` is Unicode Normalization Form C (`java.text.Normalizer`,
     `String.prototype.normalize`, the `unorm_dart` package); the profile
     pins Unicode 16.0 (§2.5).

   Table B, P, no host arm: `code-points`, over `unit-count` and
   `code-unit` (the private `yin.vm.data` helper, made public here).

   Oracle: `dao.host.text-test` and the text rows of table D, re-asserted
   by `dao.host.conformance-test`. A refusal throws `ex-info` whose data
   carries `:dao.host/refusal`, a class keyword, and `:dao.host/entry`.
   Every reader conditional names `:cljd` first, because the ClojureDart
   build also reads `:clj`."
  #?@(:cljd [(:require ["dart:convert" :as convert]
                       ["package:unorm_dart/unorm_dart.dart" :as unorm])
             (:import ["dart:core" FormatException String]
                      ["dart:typed_data" Uint8List])]
      :clj [(:import (java.nio ByteBuffer)
                     (java.nio.charset CharacterCodingException CodingErrorAction
                                       StandardCharsets))]))


(defn- refuse
  [entry cls detail]
  (throw (ex-info (str "dao.host refused: " entry " " (name cls) " (" detail ")")
                  {:dao.host/refusal cls :dao.host/entry entry :detail detail})))


;; =============================================================================
;; Code units
;; =============================================================================

(defn unit-count
  "P. The number of UTF-16 code units of string `s`."
  [s]
  #?(:cljd (.-length ^String s)
     :clj (.length ^String s)
     :cljs (.-length s)))


(defn code-unit
  "P. The UTF-16 code unit at index `i` of `s`, 0 to 0xFFFF; the caller
   has checked 0 <= i < `unit-count`."
  [s i]
  #?(:cljd (.codeUnitAt ^String s i)
     :clj (int (.charAt ^String s (int i)))
     :cljs (.charCodeAt s i)))


(defn units->string
  "P. The host string of the UTF-16 code units `units` (each 0 to
   0xFFFF), unpaired surrogates included."
  [units]
  #?(:cljd (let [sb (StringBuffer)]
             (doseq [u units] (.writeCharCode sb u))
             (.toString sb))
     :clj (let [sb (StringBuilder.)]
            (doseq [u units] (.append sb (char u)))
            (.toString sb))
     :cljs (let [a (array)]
             (doseq [u units] (.push a (.fromCharCode js/String u)))
             (.join a ""))))


(defn- high-surrogate?
  [u]
  (<= 0xD800 u 0xDBFF))


(defn- low-surrogate?
  [u]
  (<= 0xDC00 u 0xDFFF))


(defn code-points
  "P, table B. The code points of string `s` as a vector: a surrogate
   pair decodes to one code point, an unpaired surrogate is its own."
  [s]
  (let [n (unit-count s)]
    (loop [i 0
           out (transient [])]
      (if (< i n)
        (let [u (code-unit s i)
              v (when (< (inc i) n) (code-unit s (inc i)))]
          (if (and (high-surrogate? u) v (low-surrogate? v))
            (recur (+ i 2)
                   (conj! out (+ 0x10000
                                 (* (- u 0xD800) 0x400)
                                 (- v 0xDC00))))
            (recur (inc i) (conj! out u))))
        (persistent! out)))))


;; =============================================================================
;; UTF-8
;; =============================================================================

(defn- check-surrogates!
  "Refuse `:unpaired-surrogate` unless every surrogate of `s` is paired."
  [entry s]
  (let [n (unit-count s)]
    (loop [i 0]
      (when (< i n)
        (let [c (code-unit s i)]
          (cond
            (high-surrogate? c)
            (if (and (< (inc i) n) (low-surrogate? (code-unit s (inc i))))
              (recur (+ i 2))
              (refuse entry :unpaired-surrogate (str "unpaired high surrogate at " i)))
            (low-surrogate? c)
            (refuse entry :unpaired-surrogate (str "unpaired low surrogate at " i))
            :else (recur (inc i))))))))


(defn utf8-encode
  "P, fresh. The UTF-8 bytes of string `s`. A string holding an unpaired
   surrogate is refused `:unpaired-surrogate` before any host encoder
   sees it."
  [s]
  (when-not (string? s)
    (refuse 'utf8-encode :unsupported-value "not a string"))
  (check-surrogates! 'utf8-encode s)
  #?(:cljd (convert/utf8.encode s)
     :clj (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs (.encode (js/TextEncoder.) s)))


(defn- byte-count
  [bs]
  #?(:cljd (.-length ^Uint8List bs)
     :clj (alength ^bytes bs)
     :cljs (.-length bs)))


(defn utf8-decode
  "P. The string of the UTF-8 bytes `bs`, or of `bs[start, start+len)`.
   Strict: any ill-formed sequence is refused `:invalid-utf8`, never
   substituted; a range outside `bs` is refused `:out-of-range`."
  ([bs] (utf8-decode bs 0 (byte-count bs)))
  ([bs start len]
   (let [n (byte-count bs)]
     (when-not (and (int? start) (int? len) (<= 0 start) (<= 0 len) (<= (+ start len) n))
       (refuse 'utf8-decode :out-of-range (str "[" start ", +" len ") outside " n " bytes"))))
   ;; Dart's decoder drops a U+FEFF at the start of what it decodes; a BOM
   ;; is content here, so leading BOM bytes are taken one by one and the
   ;; decoder only ever starts after them.
   #?(:cljd (let [end (+ start len)]
              (loop [at start boms 0]
                (if (and (<= (+ at 3) end)
                         (= 0xef (. ^Uint8List bs "[]" at))
                         (= 0xbb (. ^Uint8List bs "[]" (+ at 1)))
                         (= 0xbf (. ^Uint8List bs "[]" (+ at 2))))
                  (recur (+ at 3) (inc boms))
                  (str (apply str (repeat boms (String.fromCharCode 0xfeff)))
                       (try
                         (.convert (convert/Utf8Decoder.) bs at end)
                         (catch FormatException e
                           (refuse 'utf8-decode :invalid-utf8 (str e))))))))
      :clj (try
             (str (.decode (-> StandardCharsets/UTF_8
                               .newDecoder
                               (.onMalformedInput CodingErrorAction/REPORT)
                               (.onUnmappableCharacter CodingErrorAction/REPORT))
                           (ByteBuffer/wrap ^bytes bs (int start) (int len))))
             (catch CharacterCodingException e
               (refuse 'utf8-decode :invalid-utf8 (.getMessage e))))
      :cljs (try
              (.decode (js/TextDecoder. "utf-8"
                                        #js {:fatal true :ignoreBOM true})
                       (.subarray bs start (+ start len)))
              (catch :default e
                (refuse 'utf8-decode :invalid-utf8 (str e)))))))


;; =============================================================================
;; Normalization
;; =============================================================================

(defn nfc
  "P. Unicode Normalization Form C of string `s`.
   The profile pin is Unicode 16.0 (§2.5). On the JVM, the normalization
   version is determined by the host JDK (Unicode 16.0 from JDK 24; JDK 21
   supports Unicode 15.0). This lane gap is pinned in `dao.host.text-test`
   and recorded in `docs/agents/known-gaps.md`."
  [s]
  #?(:cljd (unorm/nfc s)
     :clj (java.text.Normalizer/normalize ^String s java.text.Normalizer$Form/NFC)
     :cljs (.normalize s "NFC")))
