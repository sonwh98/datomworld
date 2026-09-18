(ns dao.stream.cbor
  "The DaoStream v2 CBOR wire codec: the binary twin of `dao.stream.transit`.

   The portable domain here is stream-owned and deliberately NOT Jing's: it
   keeps the Transit profile's base domain (nil, booleans, strings, safe
   numbers, qualified identifiers, maps, vectors, lists, sets) and widens it
   with exactly what D7 asked the intake stream to carry — metadata,
   reader positions included, preserved on collections and symbols, and the
   list/vector distinction.  Identifiers ride Boring's native tag 39 mapping,
   so an identifier whose printed form would not round-trip (a name or
   namespace containing `/`, a name starting with `:`, an empty component)
   is rejected loudly before the wire rather than silently munged.

   Implementations: pinned Boring (0.1.30) on the JVM and ClojureScript; the
   pub.dev `cbor` package (6.5.1) behind the same interface on Dart.  The
   byte-level contract both implement is `dao.stream.cbor.boring`'s doc."
  (:require [clojure.string :as str]
            [dao.stream.transit :as transit]
            #?(:cljd [dao.stream.cbor.cljd :as impl]
               :default [dao.stream.cbor.boring :as impl])))


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


(defn- portable-meta?
  [x]
  ;; ClojureDart attaches call-site metadata (with a dart Type `:tag`) to
  ;; list call results; the Dart codec treats that as absent, so the domain
  ;; gate must not see it either.
  (let [m #?(:cljd (impl/strip-constructor-meta (meta x))
             :default (meta x))]
    (or (nil? m) (portable-value? m))))


(defn portable-value?
  "True when x belongs to the CBOR profile's portable domain: the Transit
   domain plus metadata (recursively portable) on collections and symbols."
  [x]
  (cond
    (nil? x) true
    (or (true? x) (false? x) (string? x)) true
    (or (keyword? x) (symbol? x)) (portable-identifier? x)
    (number? x) (transit/safe-number? x)
    (map? x) (and (not (record? x)) (portable-meta? x)
                  (every? (fn [[k v]] (and (portable-value? k) (portable-value? v))) x))
    (vector? x) (and (portable-meta? x) (every? portable-value? x))
    (list? x) (and (portable-meta? x) (every? portable-value? x))
    (set? x) (and (portable-meta? x) (every? portable-value? x))
    :else false))


(defn validate-portable
  "Returns nil for a portable value, or a small diagnostic map otherwise."
  [x]
  (when-not (portable-value? x)
    {:error :non-portable-value :value x}))


(defn- ensure-portable!
  [x]
  (when-let [error (validate-portable x)]
    (throw (ex-info "value is outside the dao.stream.cbor portable domain" error)))
  x)


(defn encode
  "Encode one portable value as CBOR bytes (a host byte payload: byte[] on
   the JVM, Uint8Array on ClojureScript, Uint8List on Dart)."
  [value]
  (ensure-portable! value)
  (impl/encode value))


(defn decode
  "Decode CBOR bytes into the portable domain, rejecting anything outside
   it — trailing items, unknown tags or names, out-of-domain values."
  [bytes]
  (ensure-portable! (impl/decode bytes)))


(def profile
  "The `dao.stream.ws` codec profile for this codec: one binary WebSocket
   message per value, the `dao.stream.cbor` subprotocol, and the portable
   domain above."
  {:ws/subprotocol "dao.stream.cbor"
   :ws/frame-kind :binary
   :ws/portable-value? portable-value?
   :ws/encode encode
   :ws/decode decode})
