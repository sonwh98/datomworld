(ns datomworld
  "Core primitives for datom.world.
   A datom is the fundamental unit of information: an immutable 5-tuple [e a v t m]."
  #?(:cljs
     (:require
       [goog.crypt :as crypt]
       [goog.crypt.Sha256 :as sha256]))
  #?(:clj
     (:import
       (java.security
         MessageDigest))))


(defrecord Datom
  [e a v t m])


(defn ->datom
  [e a v t m]
  (->Datom e a v t m))


(defn sha256
  [s]
  #?(:clj (let [digest (MessageDigest/getInstance "SHA-256")
                bytes (.digest digest (.getBytes s "UTF-8"))]
            (apply str (map (partial format "%02x") bytes)))
     :cljs (let [hasher (new goog.crypt.Sha256)]
             (.update hasher s)
             (crypt/byteArrayToHex (.digest hasher)))))


(defn hash-datom-content
  [e a v]
  (sha256 (str e a v)))


(defn merkle-id-for-node
  [attributes]
  (sha256 (str (into (sorted-map) attributes))))
