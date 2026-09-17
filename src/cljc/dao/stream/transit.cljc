(ns dao.stream.transit
  "The DaoStream v2 portable-value codec.

   This is deliberately a small boundary around Transit JSON.  The boundary
   validates the v2 portable domain before writing and after reading; Transit
   handlers and metadata are not part of the wire contract."
  (:require
    [dao.stream :as stream]
    #?(:cljd [dao.stream.transit.cljd :as transit]
       :clj [cognitect.transit :as transit]
       :cljs [cognitect.transit :as transit]
       :default [cognitect.transit :as transit])))


(def max-safe-integer 9007199254740991)
(def min-safe-integer (- max-safe-integer))


(defn- finite-number?
  [x]
  ;; ClojureDart supplies both :clj and :cljd reader features, so its branch
  ;; must come first in every mixed-host reader conditional in this namespace.
  #?(:cljd (and (number? x) (.-isFinite ^num x))
     :clj (or (instance? java.lang.Byte x)
              (instance? java.lang.Short x)
              (instance? java.lang.Integer x)
              (instance? java.lang.Long x)
              (and (instance? java.lang.Double x)
                   (Double/isFinite ^Double x)))
     :cljs (and (number? x) (js/isFinite x))
     :default false))


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
    ;; The ClojureDart decoder represents out-of-domain Transit tags as
    ;; records. Reject those before the recursive map case admits them.
    #?(:cljd (or (transit/tagged-value? x)
                 (transit/uuid? x)
                 (transit/uri? x)
                 (transit/bigint? x)
                 (transit/bigdec? x)
                 (transit/quoted? x)
                 (transit/link? x))
       :default false) false
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
  #?(:cljd (transit/encode value)
     :clj (let [out (java.io.ByteArrayOutputStream.)]
            (transit/write (transit/writer out :json) value)
            (.toString out "UTF-8"))
     :cljs (transit/write (transit/writer :json) value)
     :default (throw (ex-info "no DaoStream v2 Transit implementation" {}))))


(defn decode
  "Decode Transit JSON text and reject values outside the portable domain."
  [text]
  (let [value
        #?(:cljd (transit/decode text)
           :clj (let [in (java.io.ByteArrayInputStream. (.getBytes text "UTF-8"))]
                  (transit/read (transit/reader in :json)))
           :cljs (transit/read (transit/reader :json) text)
           :default (throw (ex-info "no DaoStream v2 Transit implementation" {})))]
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
