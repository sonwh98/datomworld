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


(defn type-rank
  "Total ordering for heterogeneous datom values.
   nil < boolean < integer < float < string < keyword < symbol < other."
  [x]
  (cond
    (nil? x)     0
    (boolean? x) 1
    (integer? x) 2
    #?(:clj  (float? x)
       :cljs  (and (number? x) (not (integer? x)))
       :cljd  (dart/is? x double)) 3
    (string? x)  4
    (keyword? x) 5
    (symbol? x)  6
    :else         7))


(defn compare-vals
  "Compare two datom values across heterogeneous types using type-rank."
  [a b]
  (let [ra (type-rank a) rb (type-rank b)]
    (if (= ra rb) (compare a b) (compare ra rb))))


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
