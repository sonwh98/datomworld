(ns dao.stream.v2.transit
  "The DaoStream v2 portable-value codec.

   This is deliberately a small boundary around Transit JSON.  The boundary
   validates the v2 portable domain before writing and after reading; Transit
   handlers and metadata are not part of the wire contract."
  (:refer-clojure :exclude [read])
  (:require
    [dao.stream.v2 :as stream]
    #?(:clj [cognitect.transit :as transit]
       :cljs [cognitect.transit :as transit]
       :cljd [dao.stream.v2.transit.cljd :as transit])))


(def max-safe-integer 9007199254740991)
(def min-safe-integer (- max-safe-integer))


(defn- finite-number?
  [x]
  #?(:clj (and (number? x)
               (not (ratio? x))
               (not (and (float? x) (or (Double/isNaN (double x))
                                        (Double/isInfinite (double x))))))
     :cljs (and (number? x) (js/isFinite x))))


(defn- safe-number?
  [x]
  (and (finite-number? x)
       (if (integer? x)
         (<= min-safe-integer x max-safe-integer)
         (and (number? x) (<= (double min-safe-integer) x
                              (double max-safe-integer))))))


(declare portable-value?)


(defn- portable-map?
  [x]
  (and (every? portable-value? (keys x))
       (every? portable-value? (vals x))))


(defn portable-value?
  "True when x belongs to the v2 portable value domain."
  [x]
  (cond
    (nil? x) true
    (or (true? x) (false? x) (string? x) (keyword? x) (symbol? x)) true
    (number? x) (safe-number? x)
    (map? x) (portable-map? x)
    (vector? x) (every? portable-value? x)
    (list? x) (every? portable-value? x)
    (set? x) (every? portable-value? x)
    :else false))


(defn validate-portable
  "Returns nil for a portable value, or a small diagnostic map otherwise."
  [x]
  (when-not (portable-value? x)
    {:error :non-portable-value :value x}))


(defn valid-descriptor?
  "The descriptor gate is intentionally explicit: identity is required before
   a descriptor can cross the boundary, and every part must be portable."
  [x]
  (and (map? x)
       (stream/valid-descriptor? x)
       (contains? x :dao.stream/identity)
       (portable-value? x)))


(defn- ensure-portable!
  [x]
  (when-let [error (validate-portable x)]
    (throw (ex-info "value is outside the DaoStream v2 portable domain" error)))
  x)


(defn encode
  "Encode one portable value as Transit JSON text."
  [value]
  (ensure-portable! value)
  #?(:clj (let [out (java.io.ByteArrayOutputStream.)]
            (transit/write (transit/writer out :json) value)
            (.toString out "UTF-8"))
     :cljs (transit/write (transit/writer nil :json) value)
     :cljd (transit/encode value)))


(defn decode
  "Decode Transit JSON text and reject values outside the portable domain."
  [text]
  (let [value
        #?(:clj (let [in (java.io.ByteArrayInputStream. (.getBytes text "UTF-8"))]
                  (transit/read (transit/reader in :json)))
           :cljs (transit/read (transit/reader nil :json) text)
           :cljd (transit/decode text))]
    (ensure-portable! value)))


(defn encode-descriptor
  "Encode a descriptor only after the contract identity-key gate passes."
  [descriptor]
  (when-not (valid-descriptor? descriptor)
    (throw (ex-info "invalid DaoStream v2 descriptor"
                    {:error :invalid-descriptor :descriptor descriptor})))
  (encode descriptor))


(defn decode-descriptor
  "Decode and apply the descriptor identity-key gate."
  [text]
  (let [descriptor (decode text)]
    (when-not (valid-descriptor? descriptor)
      (throw (ex-info "decoded value is not a DaoStream v2 descriptor"
                      {:error :invalid-descriptor :descriptor descriptor})))
    descriptor))
