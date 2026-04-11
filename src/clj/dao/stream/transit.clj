(ns dao.stream.transit
  "Transit JSON support for stream link messages.

   This namespace provides the `:json` reader and writer shape used by the
   datom.world stream layer. It delegates to `cognitect.transit` on the JVM,
   and mirrors the CLJD implementation so callers can use the same API on all
   supported platforms."
  (:refer-clojure :exclude [read bigint bigdec])
  (:require
    [cognitect.transit :as transit])
  (:import
    (java.io
      ByteArrayInputStream
      ByteArrayOutputStream)))


(defn reader
  "Construct a Transit reader for `:json`."
  ([source kind]
   (reader source kind nil))
  ([source kind opts]
   (transit/reader source kind opts)))


(defn writer
  "Construct a Transit writer for `:json`."
  ([sink kind]
   (writer sink kind nil))
  ([sink kind opts]
   (transit/writer sink kind opts)))


(defn read
  "Decode transit JSON from a reader and source string."
  ([r]
   (transit/read r))
  ([r source]
   (transit/read r)))


(defn write
  "Encode a value into transit JSON and append it to the writer sink."
  [w v]
  (transit/write w v))


(defn read-handler
  "Construct a transit read handler."
  [from-rep]
  (transit/read-handler from-rep))


(defn write-handler
  "Construct a transit write handler."
  ([tag-fn rep-fn]
   (transit/write-handler tag-fn rep-fn))
  ([tag-fn rep-fn str-rep-fn]
   (transit/write-handler tag-fn rep-fn str-rep-fn)))


(defn tagged-value
  "Construct a tagged transit value."
  [tag rep]
  (transit/tagged-value tag rep))


(defn integer
  "Construct a transit integer value."
  [n]
  n)


(defn bigint
  "Construct a transit big integer value."
  [n]
  (clojure.core/bigint n))


(defn bigdec
  "Construct a transit big decimal value."
  [s]
  (clojure.core/bigdec s))


(defn uri
  "Construct a transit URI value."
  [s]
  (java.net.URI. (str s)))


(defn uuid
  "Construct a transit UUID value."
  [s]
  (java.util.UUID/fromString (str s)))


(defn binary
  "Construct a transit binary value."
  [s]
  (cond
    (string? s) (.decode (java.util.Base64/getDecoder) s)
    :else s))


(defn quoted
  "Construct a quoted transit value."
  [x]
  (transit/tagged-value "'" x))


(defn link
  "Construct a transit link value."
  [x]
  (transit/tagged-value "link" x))


(defn write-meta
  "Wrap metadata-bearing values so the writer can emit `~#with-meta`."
  [x]
  (transit/write-meta x))


(defn encode
  "Encode a value to Transit JSON text."
  [msg]
  (let [out (ByteArrayOutputStream.)]
    (write (writer out :json) msg)
    (.toString out "UTF-8")))


(defn decode
  "Decode Transit JSON text into a value."
  [s]
  (let [in (ByteArrayInputStream. (.getBytes s "UTF-8"))]
    (read (reader in :json))))
