(ns dao.jing.cbor.boring
  "The Boring half of the `dao.jing.cbor` codec (JVM and ClojureScript).

   Boring is Jing's canonical CBOR writer. `dao.jing.cbor` never hands it a
   Clojure value to interpret: it hands it a wire tree whose every node
   already has exactly one CBOR meaning, so no Boring value mapping decides
   a Jing byte:

   * nil, booleans, strings, and host byte arrays;
   * exact integers inside the 64-bit CBOR range (major types 0/1);
   * vectors (CBOR arrays) and maps (CBOR maps, whose keys Boring's
     `:canonical` profile sorts bytewise);
   * `tagged` nodes: `boring.data/TaggedValue`, written as the tag number
     over its content with no interpretation (tags 2, 3, 4, 30, 258);
   * `frame` nodes: tagged literals, written as tag 27 over
     `[name-string payload]` (Jing's four names and `clojure/with-meta`).

   Metadata never rides on a wire node: Boring's own metadata mapping would
   emit tag-39 keywords, so metadata is always an explicit
   `clojure/with-meta` frame.

   Encoding uses the ordinary one-item `boring.core/encode`, never the
   indexed API, under `:canonical` with string references and shapes off.
   Boring 0.1.30 locks those two fields under `:canonical` and throws on a
   conflicting override, which the tests pin.

   Decoding does not go through Boring: see `dao.jing.cbor`.

   ClojureDart compiles every namespace on the source path and Boring has no
   Dart half, so every form is `:cljd nil`-gated; J2 supplies Dart."
  (:require
    #?@(:cljd []
        :default [[boring.core :as boring]
                  [boring.data :as boring-data]])))


#?(:cljd nil
   :default
   (def opts
     "The one encode option map every call in this namespace uses."
     {:profile :canonical :stringref false :shapes false}))


#?(:cljd nil
   :default
   (defn byte-payload?
     "True for a host byte array: byte[] on the JVM, Uint8Array on JS."
     [x]
     #?(:clj (bytes? x)
        :cljs (instance? js/Uint8Array x))))


#?(:cljd nil
   :default
   (defn tagged
     "A wire node Boring writes as `tag` over `content`, uninterpreted."
     [tag content]
     (boring-data/->TaggedValue tag content)))


#?(:cljd nil
   :default
   (defn frame
     "A wire node Boring writes as tag 27 over [name payload]; `tag-symbol`
      names the frame, e.g. 'dao.jing/list."
     [tag-symbol payload]
     (tagged-literal tag-symbol payload)))


#?(:cljd nil
   :default
   (defn encode
     "Canonical CBOR bytes of one wire tree."
     [wire]
     (boring/encode wire opts)))
