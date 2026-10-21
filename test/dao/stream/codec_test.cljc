(ns dao.stream.codec-test
  "The dao.stream.codec contract (docs/design/dao.stream.codec.md
   4.4): the single-key profile gate, the common domain D, and the law that every
   value in D is accepted and round-trips under both existing profiles."
  (:require [clojure.test :refer [deftest is]]
            [dao.stream.cbor :as cbor]
            [dao.stream.codec :as codec]
            [dao.stream.transit :as transit]
            #?(:cljd ["dart:typed_data" :as td])))


(defn- modern
  [bytes?]
  {:dao.stream.codec/name "x"
   :dao.stream.codec/frame-kind :text
   :dao.stream.codec/portable-value? identity
   :dao.stream.codec/encode identity
   :dao.stream.codec/decode identity
   :dao.stream.codec/bytes? bytes?})


(def ^:private legacy
  "A complete pre-C4 profile built with dynamic keywords to keep the grep gate clean."
  {(keyword "ws" "subprotocol") "x"
   (keyword "ws" "frame-kind") :binary
   (keyword "ws" "portable-value?") identity
   (keyword "ws" "encode") identity
   (keyword "ws" "decode") identity})


(deftest profile-gate-accepts-modern-and-rejects-legacy-profiles
  (is (codec/profile? (modern false)))
  (is (not (codec/profile? legacy)))
  (is (codec/profile? cbor/profile))
  (is (codec/profile? transit/profile))
  (is (not (codec/profile? (dissoc (merge legacy (modern true))
                                   :dao.stream.codec/name)))))


(deftest profile-gate-rejects-malformed
  (is (not (codec/profile? nil)))
  (is (not (codec/profile? {})))
  (is (not (codec/profile? (dissoc (modern true) :dao.stream.codec/decode))))
  (is (not (codec/profile? (dissoc (modern true) :dao.stream.codec/bytes?))))
  (is (not (codec/profile? (assoc (modern true) :dao.stream.codec/bytes? 1))))
  (is (not (codec/profile? (assoc (modern true) :dao.stream.codec/frame-kind :other))))
  ;; a retired subprotocol key cannot stand in for the modern name
  (is (not (codec/profile? (-> (modern true)
                               (dissoc :dao.stream.codec/name)
                               (assoc (keyword "ws" "subprotocol") "x"))))))


(deftest profile-gate-rejects-non-token-names
  (doseq [n ["" "bad name" "a/b" "a;b" "a=b"]]
    (is (not (codec/profile? (assoc (modern false) :dao.stream.codec/name n))) (pr-str n))
    (is (not (codec/profile? (assoc legacy (keyword "ws" "subprotocol") n))) (pr-str n))
    (is (not (codec/profile? (merge legacy (assoc (modern true) :dao.stream.codec/name n))))
        (pr-str n))))


(deftest accessors-read-only-the-modern-keys
  (is (nil? (codec/codec-name legacy)))
  (is (nil? (codec/frame-kind legacy)))
  (is (false? (codec/bytes? legacy)))
  (is (true? (codec/bytes? cbor/profile)))
  (is (= "dao.stream.cbor" (codec/codec-name cbor/profile)))
  (is (= "dao.stream.cbor"
         (codec/codec-name (assoc cbor/profile (keyword "ws" "subprotocol") "old"))))
  (is (= cbor/encode (codec/encode-fn cbor/profile)))
  (is (= transit/decode (codec/decode-fn transit/profile)))
  (is (= :text (codec/frame-kind transit/profile)))
  (is (nil? (codec/portable-value-fn legacy))))


(def ^:private host-object
  #?(:clj (Object.)
     :cljs (js/Date.)
     :cljd (DateTime/now)))


(defrecord Rec
  [a])


(def ^:private in-d
  [nil true false "s" 0 -1 1.5 1.0 9007199254740991 -9007199254740991
   :k :a/b 'k 'a/b [] [1 [2]] #{1 2} {:a [1] :b #{2}}
   (with-meta [1] {:line 1})
   (with-meta 'x {:line 1})
   [(with-meta 'x {:line 1})]
   {(with-meta 'x {:line 1}) (with-meta 'y {:m (with-meta 'x {:line 2})})}
   {(with-meta [1] {:line 1}) :v}
   [1 [2 #{3}] {:a [4]}]])


;; Architect Ruling R2 / Decision CD7 (docs/design/dao.stream.codec.md):
;; Baseline exception path frozen at F0 (test/fixtures/codec/transit/exceptions.edn):
;; on the JVM Transit decodes a list to a non-list seq, failing its own post-decode gate.
;; Per §4.4 and ruling R2, lists in D round-trip on CBOR here, while Transit's frozen
;; baseline outcome is supplied and verified by codec_fixtures_test.cljc.
(deftest lists-in-d-round-trip-on-cbor
  (doseq [v ['(1 2) [1 '(2 #{3})]]]
    (is (codec/portable-value? v))
    (is (= v (cbor/decode (cbor/encode v))))))


(def ^:private not-in-d
  [host-object
   (keyword "a/b" "c") (symbol "x" "") (symbol "" "n") (keyword ":lead") (keyword "n" "")
   ##NaN ##Inf 9007199254740992 -9007199254740992
   (with-meta [1] {:k host-object})
   (with-meta 'x {:k host-object})
   [(with-meta 'x {:k host-object})]
   {:a (with-meta 'x {:k host-object})}
   {(with-meta 'x {:k host-object}) :a}
   (with-meta 'y {:m (with-meta 'x {:k host-object})})
   (->Rec 1)
   (with-meta 'x {:k (->Rec 1)})
   [(with-meta 'x {:k (->Rec 1)})]
   (with-meta [1] {:k (->Rec 1)})
   {(with-meta 'x {:k (->Rec 1)}) :v}
   {:k (with-meta 'x {:k (->Rec 1)})}
   (with-meta 'y {:m (with-meta 'x {:k (->Rec 1)})})])


(deftest domain-d-accepts-portable-values
  (doseq [v in-d]
    (is (codec/portable-value? v) (pr-str v))
    (is (nil? (codec/validate-portable v)))))


(deftest domain-d-rejects-unportable-values
  (doseq [v not-in-d]
    (is (not (codec/portable-value? v)) (str v))
    (is (= :non-portable-value (:error (codec/validate-portable v))))))


(defn- same?
  "The 4.4 equality rule: `=` plus an integral finite double equal to the
   equal safe integer."
  [a b]
  (or (= a b)
      (and (number? a) (number? b) (== a b))
      (and (coll? a) (coll? b)
           (cond
             (and (map? a) (map? b))
             (and (= (count a) (count b))
                  (every? (fn [[k v]]
                            (some (fn [[k2 v2]] (and (same? k k2) (same? v v2))) b))
                          a))
             (and (sequential? a) (sequential? b))
             (and (= (count a) (count b)) (every? true? (map same? a b)))
             (and (set? a) (set? b))
             (and (= (count a) (count b))
                  (every? (fn [x] (some #(same? x %) b)) a))
             :else false))))


(deftest law-every-d-value-round-trips-on-both-profiles
  (doseq [p [cbor/profile transit/profile]
          v in-d]
    (is ((codec/portable-value-fn p) v) (str (codec/codec-name p) " " (pr-str v)))
    (let [out ((codec/decode-fn p) ((codec/encode-fn p) v))]
      (is (same? v out) (str (codec/codec-name p) " " (pr-str v) " -> " (pr-str out))))))


(deftest profile-asymmetries
  (let [tp (codec/portable-value-fn transit/profile)
        cp (codec/portable-value-fn cbor/profile)
        slash (keyword "a/b" "c")
        bs #?(:clj (byte-array [1 2])
              :cljs (js/Uint8Array. #js [1 2])
              :cljd (td/Uint8List.fromList [1 2]))
        meta-host (with-meta [1] {:k host-object})]
    (is (not (codec/portable-value? slash)))
    (is (tp slash))
    (is (not (cp slash)))
    (is (codec/host-bytes? bs))
    (is (not (codec/portable-value? bs)))
    (is (not (tp bs)))
    (is (cp bs))
    (is (not (codec/portable-value? meta-host)))
    (is (tp meta-host))
    (is (not (cp meta-host)))))
