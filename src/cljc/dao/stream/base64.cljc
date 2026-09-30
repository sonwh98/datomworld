(ns dao.stream.base64
  "The strict Base64 codec of the raw datagram layer
   (docs/design/dao.stream.datagram.md 2): padded standard alphabet, no
   whitespace, no URL-safe alphabet, the empty string for zero bytes.

   This is the one codec at or below dao.stream for datagram payloads on
   stream-visible values; it is byte-for-byte the format of dao.jing's
   frozen bytes->base64 / base64->bytes (dao.jing.dht.md 10, Base64 seam),
   which S3 repoints onto this namespace. Nothing here knows a datagram:
   it is bytes in, text out, and the exact inverse under text?."

  #?(:cljs (:require [goog.crypt.base64 :as base64]))
  #?(:cljd (:require ["dart:convert" :as convert])))


(def ^:private text-pattern
  "Padded standard-alphabet Base64, anchored at both ends (re-matches is
   exec plus equality on some hosts, and an unanchored pattern would
   answer prefixes): no whitespace, no URL-safe alphabet. A length-4k
   tail is 4 alphabet characters, a length-4k+2 tail is two characters and
   two pads, a length-4k+3 tail is three characters and one pad, and no
   other shape is text."
  #"^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")


(defn text?
  "True iff `s` is strict padded standard-alphabet Base64 text, the empty
   string included: exactly what a datagram event's :bytes field carries."
  [s]
  (and (string? s) (boolean (re-matches text-pattern s))))


(defn encode
  "The Base64 text of host bytes `bs` (byte[] / Uint8Array / Uint8List):
   the transport representation of a datagram payload on any stream, so
   that a traffic stream survives any dao.stream codec."
  [bs]
  #?(:cljd (convert/base64Encode bs)
     :clj (.encodeToString (java.util.Base64/getEncoder) ^bytes bs)
     :cljs (base64/encodeByteArray bs)))


(defn decode
  "The host bytes of Base64 `s`, decoded strictly -- nil, never a throw,
   for anything that is not strict text: a layer that refuses a value
   answers an outcome, and its codec is total like it."
  [s]
  (when (text? s)
    #?(:cljd (try (convert/base64Decode s)
                  (catch Object _
                    nil))
       :clj (.decode (java.util.Base64/getDecoder) ^String s)
       ;; The bundled goog decoder's return shape is not guaranteed to be
       ;; a Uint8Array on every Closure version; copy it into one, so the
       ;; payload is the same host byte value on every host.
       :cljs (js/Uint8Array.from (base64/decodeStringToUint8Array s)))))
