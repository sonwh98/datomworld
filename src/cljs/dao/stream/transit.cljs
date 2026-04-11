(ns dao.stream.transit
  "Transit JSON serialization for stream link messages.

   Encodes to / decodes from UTF-8 JSON strings (text WebSocket frames)."
  (:require
    [datomworld.transit :as transit]))


(defn encode
  [msg]
  (transit/write (transit/writer :json) msg))


(defn decode
  [s]
  (transit/read (transit/reader :json) s))
