(ns dao.stream.codec
  "The dao.stream codec contract: the numeric rule, the host-bytes predicate,
   the common portable domain D (docs/design/dao.stream.codec.md 4.4), and
   profile accessors that read the `:dao.stream.codec/*` keys and fall back
   to the legacy `:ws/*` keys.  No codec implementation is required here."
  (:refer-clojure :exclude [bytes?])
  (:require [clojure.string :as str]
            #?(:cljd ["dart:typed_data" :as td])))


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


(defn safe-number?
  "True for the cross-host numeric domain: finite and within the safe
   integer bounds."
  [x]
  (and (finite-number? x)
       (if (integer? x)
         (<= min-safe-integer x max-safe-integer)
         (and (number? x) (<= (double min-safe-integer) x
                              (double max-safe-integer))))))


(defn host-bytes?
  "True for a host byte payload: byte[] on the JVM, Uint8Array on
   ClojureScript, Uint8List on Dart."
  [x]
  #?(:cljd (dart/is? x td/Uint8List)
     :clj (clojure.core/bytes? x)
     :cljs (instance? js/Uint8Array x)
     :default false))


(defn- portable-identifier?
  [x]
  (and (or (keyword? x) (symbol? x))
       (let [ns (namespace x)
             name (name x)]
         (and (pos? (count name))
              (not (str/starts-with? name ":"))
              (not (str/includes? name "/"))
              (or (nil? ns)
                  (and (pos? (count ns))
                       (not (str/starts-with? ns ":"))
                       (not (str/includes? ns "/"))))))))


(declare portable-value?)


(defn- constructor-meta?
  "ClojureDart call-site metadata: a map whose `:tag` is a Dart Type."
  [m]
  #?(:cljd (and (map? m) (dart/is? (:tag m) Type))
     :default false))


(defn- portable-meta?
  [x]
  (let [m (meta x)]
    (or (nil? m) (constructor-meta? m) (portable-value? m))))


(defn portable-value?
  "True when x belongs to the common portable domain D."
  [x]
  (cond
    (nil? x) true
    (or (true? x) (false? x) (string? x)) true
    (or (keyword? x) (symbol? x)) (and (portable-identifier? x)
                                       (or (keyword? x) (portable-meta? x)))
    (number? x) (safe-number? x)
    (map? x) (and (not (record? x)) (portable-meta? x)
                  (every? (fn [[k v]] (and (portable-value? k) (portable-value? v))) x))
    (vector? x) (and (portable-meta? x) (every? portable-value? x))
    (list? x) (and (portable-meta? x) (every? portable-value? x))
    (set? x) (and (portable-meta? x) (every? portable-value? x))
    :else false))


(defn validate-portable
  "Returns nil for a value in D, or a small diagnostic map otherwise."
  [x]
  (when-not (portable-value? x)
    {:error :non-portable-value :value x}))


(defn- lookup
  [profile new-key old-key]
  (if (contains? profile new-key)
    (get profile new-key)
    (get profile old-key)))


(defn codec-name
  [p]
  (lookup p :dao.stream.codec/name :ws/subprotocol))


(defn frame-kind
  [p]
  (lookup p :dao.stream.codec/frame-kind :ws/frame-kind))


(defn portable-value-fn
  [p]
  (lookup p :dao.stream.codec/portable-value? :ws/portable-value?))


(defn encode-fn
  [p]
  (lookup p :dao.stream.codec/encode :ws/encode))


(defn decode-fn
  [p]
  (lookup p :dao.stream.codec/decode :ws/decode))


(defn bytes?
  "True when the profile's encoded payload is host bytes; false if absent."
  [p]
  (boolean (get p :dao.stream.codec/bytes? false)))


(defn- token?
  "RFC 7230 token: one or more tchar characters."
  [x]
  (and (string? x) (some? (re-matches #"[!#$%&'*+\-.^_`|~0-9A-Za-z]+" x))))


(defn profile?
  "True for a complete modern 6-key profile or a complete legacy 5-key one."
  [x]
  (and (map? x)
       ;; A present name key must be a token even when the other key set is
       ;; complete: accessors read the modern key first.
       (every? (fn [k] (or (not (contains? x k)) (token? (get x k))))
               [:dao.stream.codec/name :ws/subprotocol])
       (or (and (token? (:dao.stream.codec/name x))
                (contains? #{:text :binary} (:dao.stream.codec/frame-kind x))
                (fn? (:dao.stream.codec/portable-value? x))
                (fn? (:dao.stream.codec/encode x))
                (fn? (:dao.stream.codec/decode x))
                (boolean? (:dao.stream.codec/bytes? x)))
           (and (token? (:ws/subprotocol x))
                (contains? #{:text :binary} (:ws/frame-kind x))
                (fn? (:ws/portable-value? x))
                (fn? (:ws/encode x))
                (fn? (:ws/decode x))))))
