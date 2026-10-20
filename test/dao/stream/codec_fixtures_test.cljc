(ns dao.stream.codec-fixtures-test
  "Slice F0 of the dao.stream codec track: frozen pre-change wire fixtures
   (docs/design/dao.stream.codec.md 7.3).

   The fixtures under test/fixtures/codec/<profile>/ were generated once on the
   JVM from the pre-change tree and are never regenerated.  CBOR payloads are
   hex strings, Transit payloads are JSON text, expected values are EDN.  EDN
   cannot spell host bytes, metadata or a slash-bearing keyword, so expected
   values use three marker maps resolved here: {:fixture/bytes hex},
   {:fixture/meta m :fixture/value v}, {:fixture/keyword [ns name]}.

   CBOR is canonical: each fixture must decode to the expected value and the
   expected value must re-encode to the fixture bytes exactly.  Transit text is
   not canonical: each fixture is only decoded and compared, never
   byte-compared against a fresh encoding.  Equality is the 4.4 rule: Clojure
   `=` plus an integral finite double equal to the equal safe integer."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [dao.jing.stream :as jing-stream]
            [dao.stream :as stream]
            [dao.stream.cbor :as cbor]
            [dao.stream.ringbuffer :as ring]
            [dao.stream.transit :as transit]
            [dao.stream.ws :as ws]
            #?(:cljd ["dart:io" :as dart-io]))
  #?@(:cljd [(:import ["dart:typed_data" Uint8List])]))


(def root "test/fixtures/codec/")


(defn- read-file
  [path]
  #?(:clj (slurp path)
     :cljs (.readFileSync (js/require "fs") path "utf8")
     :cljd (.readAsStringSync (dart-io/File. path))))


(defn- fixtures
  "The fixture entries of one profile directory file, read as EDN."
  [dir file]
  (edn/read-string (str/trim (read-file (str root dir "/" file ".edn")))))


(defn- to-hex
  [bytes]
  (apply str
         (map (fn [b]
                (let [h #?(:clj (format "%02x" (bit-and 0xff b))
                           :cljs (.toString b 16)
                           :cljd (.toRadixString b 16))]
                  #?(:clj h
                     :default (if (< b 16) (str "0" h) h))))
              bytes)))


(defn- from-hex
  [hex]
  (let [octets (map (fn [i]
                      #?(:clj (unchecked-byte (Integer/parseInt (subs hex i (+ i 2)) 16))
                         :cljs (js/parseInt (subs hex i (+ i 2)) 16)
                         :cljd (int/parse (subs hex i (+ i 2)) .radix 16)))
                    (range 0 (count hex) 2))]
    #?(:clj (byte-array octets)
       :cljs (js/Uint8Array.from octets)
       :cljd (Uint8List.fromList (vec octets)))))


(defn- resolve-markers
  [x]
  (walk/postwalk
    (fn [f]
      (cond
        (and (map? f) (contains? f :fixture/bytes)) (from-hex (:fixture/bytes f))
        (and (map? f) (contains? f :fixture/keyword)) (apply keyword (:fixture/keyword f))
        (and (map? f) (contains? f :fixture/meta)) (with-meta (:fixture/value f) (:fixture/meta f))
        :else f))
    x))


(defn- norm
  "Both sides of an equality check in comparable form: host bytes as hex, an
   integral finite double as its equal integer."
  [x]
  (walk/postwalk
    (fn [f]
      (cond
        (cbor/byte-payload? f) {:fixture/bytes (to-hex f)}
        #?(:clj (and (double? f) (== f (Math/rint f)))
           :cljs false
           :cljd (and (double? f) (== f (.floorToDouble f))))
        #?(:clj (long f) :cljs f :cljd (.toInt f))
        :else f))
    x))


(defn- same?
  [expected actual]
  (= (norm (resolve-markers expected)) (norm actual)))


(def ^:private profiles
  {"cbor" cbor/profile
   "transit" transit/profile})


(defn- payload-key
  [dir]
  (if (= "cbor" dir) :hex :text))


(defn- payload
  "The decode-side payload of one fixture entry: bytes for CBOR, text for Transit."
  [dir entry]
  (if (= "cbor" dir)
    (from-hex (:hex entry))
    (:text entry)))


(defn- decode-fn
  [dir]
  (:ws/decode (get profiles dir)))


(defn- encode-fn
  [dir]
  (:ws/encode (get profiles dir)))


;; ---------------------------------------------------------------- negotiation

(def ^:private admission
  {:retention :evict-oldest :capacity 16 :value-domain :portable-values})


(def ^:private handoff-admission
  (assoc admission :value-domain :host-values :capacity 1))


(def ^:private served
  {:dao.stream/type :dao.stream/ws
   :dao.stream/identity "fixture-1"
   :ws/host "127.0.0.1"
   :ws/port 9182
   :ws/path "/yin/repl"})


(defn- buffer
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type :dao.stream/ringbuffer
                   :dao.stream.ringbuffer/capacity capacity})))


(defn- endpoint
  "A one-slot endpoint speaking exactly `profile`."
  [profile]
  (ws/make-endpoint
    {:descriptor served
     :codecs [profile]
     :control {:dao.stream/handle (buffer 16) :dao.stream/surface #{:writer}}
     :control-admission admission
     :slots [(let [offer (buffer 1)
                   ack (buffer 1)]
               {:offer {:dao.stream/handle offer :dao.stream/surface #{:writer}}
                :offer-admission handoff-admission
                :ack {:dao.stream/handle ack :dao.stream/surface #{:writer}}
                :ack-admission handoff-admission
                :ack-cursor (:dao.stream/cursor
                              (stream/cursor ack stream/anchor-newest))})]}))


(deftest negotiation-strings-and-refusals-match-each-profile
  (doseq [dir ["cbor" "transit"]
          :let [profile (get profiles dir)
                entries (fixtures dir "negotiation")]]
    (is (= (:offer (first entries)) (:ws/subprotocol profile))
        (str dir ": the accepted offer is exactly the string the profile offers"))
    (doseq [{:keys [name offer expect]} entries
            :let [ep (endpoint profile)
                  result (ws/accept-connection!
                           ep "/yin/repl"
                           {:send! (fn [_] nil)
                            :close! (fn [& _] nil)
                            :ws/subprotocol offer}
                           0)
                  label (str dir "/" name ": offer " (pr-str offer) " expected " expect)]]
      (if (= :refused expect)
        (is (= :ws/unsupported-subprotocol (:ws/status result)) label)
        (let [chosen (:codec (first (:slots (ws/endpoint-state ep))))]
          (is (= :ws/pending (:ws/status result)) label)
          ;; no offer is the hardcoded Transit default, even on a CBOR-only endpoint
          (is (= (if (nil? offer) transit/profile profile) chosen)
              (str label ": chosen codec"))))))
  (is (= "dao.stream.transit-json" (:ws/subprotocol transit/profile)))
  (is (= "dao.stream.cbor" (:ws/subprotocol cbor/profile))))


;; ---------------------------------------------------------------- descriptors

(deftest frozen-descriptors-decode-and-pass-the-profile-gate
  (doseq [dir ["cbor" "transit"]
          :let [profile (get profiles dir)]
          entry (fixtures dir "descriptors")
          :let [expected (:expected entry)
                label (str dir "/" (:name entry))
                decoded ((decode-fn dir) (payload dir entry))]]
    (is (same? expected decoded) label)
    (is (ws/servable-descriptor? decoded profile) label)
    (is (= (pos? (:ws/port expected)) (ws/descriptor? decoded profile))
        (str label ": a dialed descriptor names a real port, port 0 is served-only"))
    (if (= "cbor" dir)
      (is (= (:hex entry) (to-hex (cbor/encode expected)))
          (str label ": canonical re-encode"))
      (is (same? expected (transit/decode-descriptor (:text entry))) label))))


;; ------------------------------------------------------------------ envelopes

(deftest frozen-envelopes-decode-to-the-expected-value
  (doseq [dir ["cbor" "transit"]
          entry (fixtures dir "envelopes")
          :let [label (str dir "/" (:name entry))
                decoded ((decode-fn dir) (payload dir entry))]]
    (is (same? (:expected entry) decoded) label)))


(deftest frozen-cbor-envelopes-reencode-to-the-fixture-bytes
  (doseq [entry (fixtures "cbor" "envelopes")
          :let [label (str "cbor/" (:name entry))
                value (resolve-markers (:expected entry))]]
    (is (= (:hex entry) (to-hex (cbor/encode value)))
        (str label ": expected value re-encodes to the fixture bytes"))
    (is (= (:hex entry) (to-hex (cbor/encode (cbor/decode (from-hex (:hex entry))))))
        (str label ": decoded value re-encodes to the fixture bytes"))))


;; ----------------------------------------------------- baseline exceptions

(def ^:private host
  #?(:clj :clj :cljs :cljs :cljd :cljd))


(deftest frozen-baseline-exceptions-keep-their-per-host-outcome
  (doseq [dir ["cbor" "transit"]
          entry (fixtures dir "exceptions")
          :let [label (str dir "/" (:name entry) " on " (name host))
                decode (decode-fn dir)
                p (payload dir entry)]]
    (if (= :throws (get entry host))
      (is (thrown? #?(:clj Exception :cljs :default :cljd Object) (decode p)) label)
      (is (same? (:expected entry) (decode p)) label))))


;; ------------------------------------------------------------------ rejection

(deftest frozen-rejection-payloads-are-refused
  (doseq [dir ["cbor" "transit"]
          entry (fixtures dir "rejections")]
    (is (thrown? #?(:clj Exception :cljs :default :cljd Object)
          ((decode-fn dir) (payload dir entry)))
        (str dir "/" (:name entry) " decode accepted"))))


;; --------------------------------------------------------------------- jing

(deftest frozen-jing-wrapping-round-trips-per-lane
  (doseq [dir ["cbor" "transit"]
          :let [profile (get profiles dir)]
          entry (fixtures dir "jing")
          :let [label (str dir "/" (:name entry))
                bs (from-hex (:unwrapped-hex entry))
                decoded ((decode-fn dir) (payload dir entry))
                value (:ws/value decoded)]]
    (is (same? (:expected entry) decoded) label)
    (is (= (:unwrapped-hex entry)
           (to-hex (jing-stream/unwrap-bytes profile value)))
        (str label ": unwrapped bytes"))
    (is (same? (:ws/value (:expected entry)) (jing-stream/wrap-bytes profile bs))
        (str label ": wrap-bytes produces the fixture value"))
    (when (= "cbor" dir)
      (is (= (:hex entry)
             (to-hex (cbor/encode {:ws/frame :ws/value
                                   :ws/value (jing-stream/wrap-bytes profile bs)})))
          (str label ": canonical re-encode")))))
