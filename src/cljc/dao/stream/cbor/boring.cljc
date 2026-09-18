(ns dao.stream.cbor.boring
  "The Boring half of the `dao.stream.cbor` codec (JVM and ClojureScript).

   Boring supplies CBOR bytes; this namespace supplies the stream profile on
   top of them:

   * `:canonical` with string references, shaped arrays, and index frames
     disabled, so bytes are deterministic and peer-decodable.
   * Boring's native mappings are the wire vocabulary: tag 39 identifiers,
     tag 258 sets, and the `clojure/with-meta` tag 27 frame for collection
     and symbol metadata, reader positions included.
   * Boring encodes vectors, lists, and seqs as one ordinary CBOR array, so
     the profile adds its own named frame `dao.stream/list` — payload
     `[metadata elements]`, metadata nil when absent — to keep lists and
     vectors distinct across the wire.

   Nothing here is Jing's: no identifier component frames, no `:line`/`
   :column` stripping, no canonical re-encoding for content addressing, and
   no numeric carriers beyond the safe portable numbers.

   ClojureDart compiles every namespace on the source path, and Boring has
   no Dart half, so every form below is `:cljd nil`-gated exactly as
   `dao.jing.remote` gates its JVM glue: the Dart emit pass produces an
   empty namespace here while the JVM and ClojureScript passes define it."
  (:require
    #?@(:cljd []
        :default [[boring.core :as boring]])))


#?(:cljd nil
   :default
   (def opts
     "The one encode option map every call in this namespace uses."
     {:profile :canonical :stringref false :shapes false}))


#?(:cljd nil
   :default
   (defn byte-payload?
     "True for the host byte payload Boring writes as a CBOR byte string
      (major type 2): byte[] on the JVM, Uint8Array on ClojureScript. The
      carrier Jing's boundary adapter rides its canonical bytes on."
     [x]
     #?(:clj (bytes? x)
        :cljs (instance? js/Uint8Array x))))


#?(:cljd nil
   :default
   (def list-frame-tag
     "The symbol naming the list frame on both the encode and decode sides."
     'dao.stream/list))


#?(:cljd nil
   :default
   (def ^:private decode-opts
     (assoc opts
            :tolerate-unknown-tags false
            :on-unknown-record
            (fn [name payload]
              ;; Boring surfaces unknown tag 27 names here.  The stream's own
              ;; list frame becomes the tagged literal `convert` expects; any
              ;; other name is out of profile.
              (if (= "dao.stream/list" name)
                (tagged-literal list-frame-tag payload)
                (throw (ex-info "cbor record outside the stream profile"
                                {:name name})))))))


#?(:cljd nil
   :default
   (defn- rewrite
     "Fold lists into their named frames, including inside metadata, and leave
      every other value for Boring's own mappings.  Collections are rebuilt so
      metadata can be rewritten recursively; content is unchanged."
     [x]
     (cond
       (list? x)
       (tagged-literal list-frame-tag
                       [(some-> (meta x) rewrite) (mapv rewrite x)])

       (vector? x)
       (if-some [m (meta x)]
         (with-meta (mapv rewrite x) (rewrite m))
         (mapv rewrite x))

       (set? x)
       (if-some [m (meta x)]
         (with-meta (into #{} (map rewrite) x) (rewrite m))
         (into #{} (map rewrite) x))

       (map? x)
       (if-some [m (meta x)]
         (with-meta (into {} (map (fn [[k v]] [(rewrite k) (rewrite v)])) x)
           (rewrite m))
         (into {} (map (fn [[k v]] [(rewrite k) (rewrite v)])) x))

       (symbol? x)
       (if-some [m (meta x)]
         (with-meta x (rewrite m))
         x)

       :else x)))


#?(:cljd nil :default (declare convert))


#?(:cljd nil
   :default
   (defn- from-frame
     "Convert one decoded `dao.stream/list` frame back into a list.  Boring
      decodes named frames as tagged literals; the payload is the rewritten
      `[metadata elements]` pair."
     [tl]
     (let [[m elems] (:form tl)]
       (with-meta (apply list (mapv convert elems)) (some-> m convert)))))


#?(:cljd nil
   :default
   (defn- convert-meta
     "Convert one value's metadata, if any, onto its converted replacement.
      The metadata is read from the original: rebuilds inside `convert` have
      none."
     [x converted]
     (if-some [m (meta x)]
       (with-meta converted (convert m))
       converted)))


#?(:cljd nil
   :default
   (defn convert
     "Walk one decoded value, converting list frames and rejecting every other
      carrier Boring can surface for out-of-profile content."
     [x]
     (cond
       (tagged-literal? x)
       (if (= list-frame-tag (:tag x))
         (from-frame x)
         (throw (ex-info "cbor frame outside the stream profile" {:tag (:tag x)})))

       ;; Boring's own carriers (SimpleValue, TaggedValue, UnknownRecord, ...)
       ;; are records: in the portable domain by no other route than this error.
       (record? x)
       (throw (ex-info "cbor value outside the stream profile" {:value x}))

       (map? x)
       (convert-meta x
                     (into {} (map (fn [[k v]] [(convert k) (convert v)])) x))

       (vector? x)
       (convert-meta x (mapv convert x))

       (set? x)
       (convert-meta x (into #{} (map convert) x))

       (symbol? x)
       (if-some [m (meta x)]
         (with-meta x (convert m))
         x)

       ;; Boring surfaces half- and single-precision floats as java.lang.Float
       ;; on the JVM; the stream's portable numbers are integers and doubles.
       #?@(:clj [(instance? java.lang.Float x) (double x)])

       :else x)))


#?(:cljd nil
   :default
   (defn encode
     "Encode one portable value as canonical CBOR bytes."
     [value]
     (boring/encode (rewrite value) opts)))


#?(:cljd nil
   :default
   (defn- bytes=
     [a b]
     #?(:clj (java.util.Arrays/equals ^bytes a ^bytes b)
        :cljs (let [n (alength a)]
                (and (= n (alength b))
                     (loop [i 0]
                       (or (= i n)
                           (and (= (aget a i) (aget b i))
                                (recur (inc i))))))))))


#?(:cljd nil
   :default
   (defn decode
     "Decode exactly one CBOR item and convert it into the portable domain.
      Trailing items, malformed input, unknown tags or names, duplicate keys,
      and out-of-domain values are errors.

      The trailing byte-compare is the canonicality guard the Dart half also
      applies: the decoder beneath may accept indefinite lengths and
      non-minimal integers a canonical peer never emits, and re-encoding the
      decoded value is the one check that rejects every such frame."
     [bytes]
     (let [items (boring/decode-seq bytes decode-opts)]
       (when-not (= 1 (count items))
         (throw (ex-info "cbor frame must be exactly one item" {:items (count items)})))
       (let [value (convert (first items))]
         (when-not (bytes= bytes (encode value))
           (throw (ex-info "cbor value outside the stream profile"
                           {:reason :non-canonical})))
         value))))
