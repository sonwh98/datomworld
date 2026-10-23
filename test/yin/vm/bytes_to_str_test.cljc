(ns yin.vm.bytes-to-str-test
  "Table D (3) of docs/design/dao.stream.host-boundary.md: the `yin.vm`
   primitive `bytes->str` decodes strict UTF-8 on every lane (slice H2,
   a named behaviour change: before it, JS and Dart read each byte as a
   char code and the JVM substituted U+FFFD). Ratified OQ11: the JVM-only
   encoding arity is removed, leaving bytes->str with a single unary UTF-8
   decoding arity on every lane."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.host.bytes :as bytes]
            [yin.vm :as vm]))


(def ^:private bytes->str
  (vm/primitive-function (get vm/primitives 'bytes->str)))


(defn- refusal
  [bs]
  (try
    (bytes->str bs)
    nil
    (catch #?(:cljd Object :clj Throwable :cljs :default) e
      (:dao.host/refusal (ex-data e)))))


(deftest bytes->str-is-strict-utf-8-on-every-lane
  (testing "the UTF-8 bytes of \"héllo\" decode to \"héllo\""
    (is (= "héllo" (bytes->str (bytes/from-vector [0x68 0xc3 0xa9 0x6c 0x6c 0x6f])))))
  (testing "ASCII bytes are unchanged"
    (is (= "abc" (bytes->str (bytes/from-vector [0x61 0x62 0x63]))))
    (is (= "" (bytes->str (bytes/alloc 0)))))
  (testing "ill-formed UTF-8 is refused, never read as a char or U+FFFD"
    (is (= :invalid-utf8 (refusal (bytes/from-vector [0xe9]))))
    (is (= :invalid-utf8 (refusal (bytes/from-vector [0xc3 0x28])))))
  (testing "the published arity is unary UTF-8 only"
    (is (= [1] (:yin.k/arities (get vm/primitives 'bytes->str))))))
