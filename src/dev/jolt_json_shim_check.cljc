;; Tests of the jolt clojure.data.json shim (src/jolt/clojure/data/json.jolt).
;; The same file runs on both hosts, so every expectation is checked against
;; the real data.json 2.5.0 on the JVM as well as against the shim on jolt:
;;
;;   bb test:jolt-json   (jolt, through src/dev/jolt_test_runner.clj)
;;   clojure -M:dev -e "(require 'jolt-json-shim-check) (clojure.test/run-tests 'jolt-json-shim-check)"
;;
;; The one host difference, an unpaired-surrogate escape, takes a :jolt arm
;; (this is a jolt dev file, not shared code).
(ns jolt-json-shim-check
  (:require
    [clojure.data.json :as json]
    [clojure.test :refer [deftest is]]))


(defn- threw?
  [f]
  (try (f) false (catch Exception _ true)))


(deftest read-str-values
  (is (= {"a" 1 "b" [true false nil] "c" "x"}
         (json/read-str "{\"a\":1,\"b\":[true,false,null],\"c\":\"x\"}")))
  (is (= {"k" []} (json/read-str "  {\"k\" : [ ] }  ")))
  (is (= {} (json/read-str "{}")))
  (is (= [] (json/read-str "[]")))
  (is (= {"a" 2} (json/read-str "{\"a\":1,\"a\":2}")) "a duplicate key keeps the last value")
  (is (= [1] (json/read-str "[1] x")) "text after the first value is ignored")
  (is (= {"A" 1} (json/read-str "{\"a\":1}" :key-fn #(.toUpperCase ^String %))))
  (is (= {:a 1} (json/read-str "{\"a\":1}" :key-fn keyword))))


(deftest read-str-numbers
  (is (= 0 (json/read-str "-0")))
  (is (= -12 (json/read-str "-12")))
  (is (= 9007199254740993 (json/read-str "9007199254740993")))
  (is (= 18446744073709551616N (json/read-str "18446744073709551616")))
  (is (= 100000.0 (json/read-str "1e5")))
  (is (= 100.0 (json/read-str "1E+2")))
  (is (= -0.0015 (json/read-str "-1.5e-3")))
  (is (= 0.5 (json/read-str "0.5")))
  (is (instance? Long (json/read-str "7")) "an integer is exact")
  (is (instance? Double (json/read-str "7.0")) "a fraction is a double"))


(deftest read-str-strings
  (is (= "é/\b\f\n\r\t\"\\" (json/read-str "\"\\u00e9\\/\\b\\f\\n\\r\\t\\\"\\\\\"")))
  (is (= "😀" (json/read-str "\"\\ud83d\\ude00\"")) "a surrogate pair is one code point")
  (is (= "\u0000" (json/read-str "\"\\u0000\"")))
  (is (= "a\nb" (json/read-str "\"a\nb\"")) "a raw control character is accepted")
  (is (= "aé😀" (json/read-str "\"aé😀\"")) "raw non-ASCII passes through"))


(deftest read-str-unpaired-surrogate-escape
  #?(:jolt (is (= 'clojure.data.json/read-str
                  (:jolt/unimplemented
                    (try (json/read-str "\"\\ud800\"")
                         (catch Exception e (ex-data e))))))
     :clj (is (= 1 (count (json/read-str "\"\\ud800\""))))))


(deftest read-str-refuses-malformed-input
  (doseq [s ["" "   " "[1,2,]" "01" "1." ".5" "+1" "1e" "NaN" "nul" "tru"
             "\"abc" "\"\\x\"" "\"\\u12\"" "{\"a\" 1}" "{1:2}" "{\"a\":1" "[1" "[1 2]"
             "{\"a\":1,}" "-" "}"]]
    (is (threw? #(json/read-str s)) (pr-str s))))


(deftest write-str-defaults
  (is (= "{\"a\":[1,2.5,null,true,\"\\u00e9\\/\\ud83d\\ude00\\n\\u0001\"]}"
         (json/write-str {"a" [1 2.5 nil true "é/😀\n\u0001"]})))
  (is (= "\"x\"" (json/write-str "x")))
  (is (= "[]" (json/write-str [])))
  (is (= "{}" (json/write-str {})))
  (is (= "-0.0" (json/write-str -0.0)))
  (is (= "18446744073709551616" (json/write-str 18446744073709551616N)))
  (is (= "\"\\b\\f\\r\\t\\\"\\\\\"" (json/write-str "\b\f\r\t\"\\"))))


(deftest write-str-review-rows
  ;; review J1-R3, documented rows (the shim's header has the reasons):
  ;; DEL is the one known difference, pinned per host; keywords are written
  ;; by name on both, so that row holds unchanged on both
  #?(:jolt (is (= "\"a\\u007fb\"" (json/write-str "a\u007fb"))
               "the shim escapes DEL")
     :clj (is (= "\"a\u007fb\"" (json/write-str "a\u007fb"))
              "data.json 2.5.0 writes DEL raw"))
  (is (= "{\"k\":1}" (json/write-str {:ns/k 1})))
  (is (= "[\"kw\",\"a\",\"c\"]" (json/write-str [:kw :a :b/c]))))


(deftest round-trip
  (let [text "{\"cases\":[{\"id\":\"uni/astral\",\"v\":\"\\ud83d\\ude00\",\"n\":[0,-1,18446744073709551616,1.5]}]}"]
    (is (= (json/read-str text) (json/read-str (json/write-str (json/read-str text)))))))
