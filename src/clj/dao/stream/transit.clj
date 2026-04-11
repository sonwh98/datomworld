(ns dao.stream.transit
  "Transit JSON serialization for stream link messages.

   Encodes to / decodes from UTF-8 JSON strings (text WebSocket frames)."
  (:require
    [datomworld.transit :as transit])
  (:import
    (java.io
      ByteArrayInputStream
      ByteArrayOutputStream)))


(defn encode
  [msg]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) msg)
    (.toString out "UTF-8")))


(defn decode
  [s]
  (let [in (ByteArrayInputStream. (.getBytes s "UTF-8"))]
    (transit/read (transit/reader in :json))))
