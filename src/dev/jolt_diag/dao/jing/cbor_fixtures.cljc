(ns dao.jing.cbor-fixtures
  "DIAGNOSTIC ONLY, jolt only, never an oracle: a stand-in for the corpus
   LOADER test/dao/jing/cbor_fixtures.cljc, used by the J1 diagnostic run
   (bb test:jolt-host-diag, alias :jolt-diag, whose source root
   src/dev/jolt_diag precedes test).

   Why it still exists (J1 round 2): the real namespace's :clj arm requires
   clojure.data.json, which jolt now has (src/jolt/clojure/data/json.jolt),
   AND dao.jing.cbor, which still cannot load on jolt: first boring.core is
   absent from the :jolt alias, and with boring supplied it fails on its
   own :clj arm (cbor.cljc:128, BigInteger/valueOf) until the host-boundary
   slices move the codec onto dao.host.*. dao.host.float-test and
   dao.host.text-test use only four of the loader's functions, all data
   plumbing with no assertion in them: `read-path`, `cases-of-kind`,
   `hex->ints`, `bytes->hex`. This file supplies exactly those four with
   the same code as the real file's :clj arm (the corpus parsed by the
   same `json/read-str` call), and differs only in not requiring
   dao.jing.cbor. dao.host.conformance-test calls dao.jing.cbor directly,
   so this stand-in cannot make that namespace load.

   A result obtained with it is reported as diagnostic, beside, never in
   place of, the unchanged oracle run (bb test:jolt-host). Delete this file
   once dao.jing.cbor loads on jolt."
  (:require
    [clojure.data.json :as json]))


(def path
  "The corpus, relative to the repository root."
  "test/resources/dao/jing/cbor-v1.json")


(defn read-path
  "The text of any repository-relative file."
  [p]
  (slurp p))


(def ^:private corpus* (delay (json/read-str (read-path path))))


(defn cases-of-kind
  "Cases of one kind: \"canonical\", \"encode-refusal\", or \"decode-refusal\"."
  [kind]
  (filterv #(= kind (get % "kind")) (get @corpus* "cases")))


(def ^:private hex-values
  {"0" 0 "1" 1 "2" 2 "3" 3 "4" 4 "5" 5 "6" 6 "7" 7
   "8" 8 "9" 9 "a" 10 "b" 11 "c" 12 "d" 13 "e" 14 "f" 15})


(defn hex->ints
  [s]
  (mapv (fn [i]
          (+ (* 16 (get hex-values (subs s i (inc i))))
             (get hex-values (subs s (inc i) (+ i 2)))))
        (range 0 (count s) 2)))


(defn bytes->hex
  "Lowercase hex of host bytes."
  [bs]
  (apply str (map (fn [i]
                    (let [b (bit-and (aget bs i) 0xff)]
                      (str (when (< b 16) "0") (Integer/toHexString b))))
                  (range (alength bs)))))
