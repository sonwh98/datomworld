(ns yin.vm.ucf.scalar-round-trip-test
  "S6: Jing numeric content in version-1 task bodies; honest v0 refusal."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [dao.jing.cbor :as cbor]
    [dao.jing.cbor-fixtures :as fx]
    [yang.python.antlr.int-contract-fixtures :as f]
    [yang.python.antlr.int-ops-test :refer [canon-ints float-bits]]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.ucf.handoff :as handoff]
    [yin.vm.ucf.lift-support :as ls]))


(def path "test/resources/yin/vm/ucf/scalars-v1.txt")


(defn scalar-value
  "The deterministic scalar body payload, with floats explicitly typed."
  []
  (let [z (data/float-value (cbor/float64 0))
        inf (data/float-value (cbor/float64 ##Inf))]
    (reduce
      (fn [m s] (assoc m s (f/value s)))
      (assoc {}
             "floats" (mapv #(assoc {} :py/float (cbor/float64 %))
                            [z (* -1.0 z) (- inf inf) inf 5e-324
                             (f/float-value "f:2^53")
                             (f/float-value "f:2^63")])
             "strings" ["plain" "\uD83D\uDE00"]
             "true" true "none" nil)
      f/int-names)))


(defn minted
  "Only the JVM minting script calls this to write the golden once."
  []
  (ls/lift (assoc (ls/halted-machine) :value (scalar-value)) (ls/header 0)))


(defn- normalized
  [x]
  (canon-ints (float-bits x)))


(deftest scalar-round-trip-test
  (let [r (minted)
        [version address & hex] (str/split-lines (fx/read-path path))]
    (is (= :ok (:status r)) (pr-str (dissoc r :bytes :body :record)))
    (when (= :ok (:status r))
      (is (= "scalars-v1" version))
      (is (= 1 (:yin.k/version (:body r))))
      (is (= address (str (:address r))))
      (is (= (apply str hex) (fx/bytes->hex (:bytes r))))
      (let [out (handoff/resume-task (ls/new-machine) (:bytes r)
                                     (fn [_descriptor] nil))]
        (is (= :ok (:status out)) (pr-str (dissoc out :vm)))
        (when (= :ok (:status out))
          (is (vm/halted? (:vm out)))
          (is (= (normalized (scalar-value))
                 (normalized (vm/value (:vm out))))))))
    (let [fixture (f/read-file)]
      (mapv (fn [s]
              (is (= (f/column fixture s "cbor")
                     (fx/bytes->hex (cbor/encode (f/value s)))) s))
            f/int-names))))


(deftest version-zero-refuses-big-content-test
  (let [r (try (ls/lift (assoc (ls/halted-machine) :value (scalar-value)) nil)
               (catch #?(:cljd Object :clj Exception :cljs :default) e
                 {:thrown (ex-message e)}))]
    (is (= :yin.k/non-portable (:yin.k/status r)) (pr-str r))
    (is (= :host-object (:yin.k/kind r)) (pr-str r))))
