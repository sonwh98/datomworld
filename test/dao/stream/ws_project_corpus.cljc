(ns dao.stream.ws-project-corpus
  "The live wire corpus of the dao.stream.codec §7.3 bidirectional proof:
   the §4.4 envelope corpus, each profile's own extra values, and one
   Jing-wrapped payload, served as the toy peers' \"str-3\".

   The clean fixtures are all here: the shared values, collection and
   symbol metadata (Transit drops it on the wire, CBOR carries it, and
   `normalize` compares what each profile promises), CBOR lists and host
   bytes, and Transit's integral double and slash-bearing keyword.

   Two baseline exceptions of §7.3 are NOT in the live corpus, because their
   frozen outcomes are not a universal round trip and the frozen fixtures
   (`test/fixtures/codec/*/exceptions.edn`) pin them per host instead:
   Transit lists (the JVM Transit decoder throws on list text) and the CBOR
   integral double `1.0` (`f93c00`: Node's decoder rejects it)."
  (:require [clojure.string :as str]
            [dao.jing.stream :as jing-stream]
            [dao.stream.cbor :as cbor])
  #?(:cljd (:import ["dart:typed_data" Uint8List])))


(def identity-name "str-3")


(def jing-payload
  "The Jing payload whose canonical bytes cross the wire wrapped."
  {:jing/probe [1 "two" :three]})


(defn- octets
  "Host bytes of `xs`."
  [xs]
  #?(:clj (byte-array (map unchecked-byte xs))
     :cljs (js/Uint8Array.from (clj->js xs))
     :cljd (Uint8List.fromList (vec xs))))


(def plain
  "Values both profiles carry: compared with `=` after the wire (plus the
   profile's metadata promise, see `normalize`)."
  [nil true false "" "hello" "héllo 世界" 0 1 -1 1000000
   9007199254740991 -9007199254740991 1.5 0.001
   :k :a/b 'k 'a/b
   [] [1 2 3] #{1 2 3}
   {} {:a 1 :b [2]} {1000 "x" "a" :y}
   {:v [1 {:s #{:x :y}} [2 3]] :n nil}
   (with-meta [1] {:line 1})
   (with-meta 'x {:line 1})
   [(with-meta 'x {:line 1})]
   {:k (with-meta 'x {:line 1})}
   (with-meta 'y {:m (with-meta 'x {:c 2})})
   {(with-meta [1] {:k 1}) :v}])


(def transit-extra
  "Values only Transit's clean fixtures cover: an integral double and the
   keyword whose name carries a slash (`:user/foo/bar`)."
  [1.0 (keyword "user" "foo/bar")])


(defn cbor-extra
  "Values only CBOR's clean fixtures cover: lists, nested lists, host
   bytes, and a reader-metadata nest."
  []
  [(list 1 2)
   [1 (list 2 #{3})]
   [(list 1 2) [1 2]]
   {:v [1 {:s #{:x :y}} (list 2 3)] :n nil}
   (octets [0 1 2 3 255])
   (octets [])
   {:b [(octets [222 173 190 239])]}
   (with-meta [1 :two (with-meta 'x {:line 7 :col 9})]
     {:reader/pos [12 34]})])


(defn values
  "The corpus as the served stream carries it for `profile`: the shared
   values, the profile's extras, then the wrapped Jing payload."
  [profile]
  (conj (into plain (if (= profile cbor/profile)
                      (cbor-extra)
                      transit-extra))
        (jing-stream/wrap-payload profile jing-payload)))


(defn- integral-double?
  [x]
  #?(:clj (and (double? x) (== x (Math/rint x)))
     :cljs false
     :cljd (and (double? x) (== x (.floorToDouble x)))))


(defn- norm
  "`x` in comparable form: host bytes as octet vectors, an integral double
   as its equal integer (the §4.4 equality rule), lists marked, and under
   CBOR the metadata it carries kept beside the value."
  [keep-meta? x]
  (let [n #(norm keep-meta? %)
        v (cond
            (cbor/byte-payload? x)
            {:octets (mapv #(bit-and 0xff %) x)}

            (integral-double? x)
            #?(:clj (long x) :cljs x :cljd (.toInt x))

            (map? x) (into {} (map (fn [[k v]] [(n k) (n v)])) x)
            (set? x) (into #{} (map n) x)
            (vector? x) (mapv n x)
            (seq? x) {:list (mapv n x)}
            :else x)
        m (when keep-meta? (meta x))]
    (if (seq m) {:meta (n m) :value v} v)))


(defn normalize
  "What a received value is compared as: Jing wrapping unwrapped to
   octets, everything else through `norm` (CBOR keeps metadata, Transit
   drops it on the wire so it is not compared)."
  [profile value]
  (if (and (map? value) (contains? value :dao.jing/canonical-bytes))
    {:jing-octets (mapv #(bit-and 0xff %)
                        (jing-stream/unwrap-bytes profile value))}
    (norm (= profile cbor/profile) value)))


(defn canon
  "A canonical text of a normalized value (maps and sets in sorted order),
   so an out-of-process driver can compare values without decoding
   Transit."
  [x]
  (cond
    (map? x) (str "{" (str/join " " (sort (map (fn [[k v]] (str (canon k) " " (canon v))) x))) "}")
    (set? x) (str "#{" (str/join " " (sort (map canon x))) "}")
    (vector? x) (str "[" (str/join " " (map canon x)) "]")
    :else (pr-str x)))


(defn expected
  "The corpus as it must compare after crossing the wire."
  [profile]
  (mapv #(normalize profile %) (values profile)))
