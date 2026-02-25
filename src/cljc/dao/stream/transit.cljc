(ns dao.stream.transit
  "Transit JSON serialization for stream link messages.

   Encodes to / decodes from UTF-8 JSON strings (text WebSocket frames).
   Works on both CLJ (ByteArray) and CLJS (string)."
  (:require
    [cognitect.transit :as transit])
  #?(:clj
     (:import
       (java.io
         ByteArrayInputStream
         ByteArrayOutputStream))))


#?(:clj (defn encode
          [msg]
          (let [out (ByteArrayOutputStream.)]
            (transit/write (transit/writer out :json) msg)
            (.toString out "UTF-8"))))


#?(:clj (defn decode
          [s]
          (let [in (ByteArrayInputStream. (.getBytes s "UTF-8"))]
            (transit/read (transit/reader in :json)))))


#?(:cljs (defn encode
           [msg]
           (transit/write (transit/writer :json) msg)))


#?(:cljs (defn decode
           [s]
           (transit/read (transit/reader :json) s)))
