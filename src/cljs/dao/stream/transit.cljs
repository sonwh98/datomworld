(ns dao.stream.transit
  "Transit JSON support for stream link messages.

   This namespace provides the `:json` reader and writer shape used by the
   datom.world stream layer. It mirrors the JVM and CLJD implementations so
   callers can use the same API on all supported platforms."
  (:refer-clojure :exclude [read uuid])
  (:require
    [cognitect.transit :as transit]))


(defn reader
  "Construct a Transit reader for `:json`."
  ([kind]
   (transit/reader kind))
  ([source kind]
   (reader source kind nil))
  ([source kind opts]
   (if opts
     (transit/reader kind opts)
     (transit/reader kind))))


(defn writer
  "Construct a Transit writer for `:json`."
  ([kind]
   (transit/writer kind))
  ([sink kind]
   (writer sink kind nil))
  ([sink kind opts]
   (if opts
     (transit/writer kind opts)
     (transit/writer kind))))


(defn read
  "Decode transit JSON from a reader and source string."
  [r source]
  (transit/read r source))


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
  (transit/integer n))


(defn bigint
  "Construct a transit big integer value."
  [n]
  (transit/bigint n))


(defn bigdec
  "Construct a transit big decimal value."
  [s]
  (transit/bigdec s))


(defn uri
  "Construct a transit URI value."
  [s]
  (transit/uri s))


(defn uuid
  "Construct a transit UUID value."
  [s]
  (transit/uuid s))


(defn binary
  "Construct a transit binary value."
  [s]
  (transit/binary s))


(defn quoted
  "Construct a quoted transit value."
  [x]
  (transit/quoted x))


(defn link
  "Construct a transit link value."
  [x]
  (transit/link x))


(defn write-meta
  "Wrap metadata-bearing values so the writer can emit `~#with-meta`."
  [x]
  (transit/write-meta x))


(defn encode
  [msg]
  (write (writer :json) msg))


(defn decode
  [s]
  (read (reader :json) s))
