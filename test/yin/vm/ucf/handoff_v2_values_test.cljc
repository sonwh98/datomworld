(ns yin.vm.ucf.handoff-v2-values-test
  "UCF version 2, section 11 rows 3 and 4: closures and frame values.
   Closures captured in blocked environments, operand stacks, return
   frames and partially evaluated operands round trip in every profile;
   a reified continuation lowers receiver-owned; a literal map shaped
   like a marker stays data; halting with a closure only in the result
   carries its code."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.ucf.v2-support :as s]
            [yin.vm.values :as values]))


(def ^:private make-stream
  {:type :stream/make, :buffer 4})


(defn- parked-program
  "A machine of `engine` that runs `program`, parked on its first read
   (nothing appended), or on its second when `after-first` is true: the
   first read answers A.  Answers [parked source-stream]."
  [engine program after-first]
  (let [m0 (s/run-ast engine program)
        src (s/stream-of m0)]
    (if after-first
      (do (stream/append! src "A")
          [(vm/run m0) src])
      [m0 src])))


(def ^:private closure-program
  (s/let1 's make-stream
          (s/let1 'c (s/cursor-of (s/v 's))
                  (s/let1 'f (s/lam ['x]
                                    (s/then (s/next-of (s/v 'c))
                                            (s/next-of (s/v 'c))))
                          (s/app (s/v 'f) (s/lit 1))))))


(def ^:private operand-program
  (s/let1 's make-stream
          (s/let1 'c (s/cursor-of (s/v 's))
                  (s/app (s/lam ['a 'b] (s/v 'b))
                         (s/lit 10)
                         (s/next-of (s/v 'c))))))


(def ^:private continuation-program
  (s/let1 's make-stream
          (s/let1 'c (s/cursor-of (s/v 's))
                  (s/let1 'k {:type :vm/current-continuation}
                          (s/then (s/next-of (s/v 'c)) (s/lit :done))))))


(defn- round-trip
  "Lift `parked` as a fork, lower it into a fresh receiver, deliver B
   through the reflection, and answer [reference-value lowered-value
   lowered-machine-before-delivery]."
  [engine [parked src]]
  (let [t (s/toy)
        peer (s/served-peer t)
        export (s/lift parked t peer)
        reference (do (stream/append! src "B")
                      (vm/value (s/drive-local parked)))
        [r _] (s/read! engine t (:bytes export) {:address (:address export)})]
    (is (= :ok (:status export)) (pr-str export))
    (is (= :ok (:status r)) (pr-str r))
    [reference (when (:vm r) (vm/value (s/drive (:vm r) peer))) (:vm r)]))


(deftest a-closure-in-a-blocked-environment-round-trips
  (doseq [engine s/engines]
    (testing (name engine)
      (let [[reference value] (round-trip engine
                                          (parked-program engine
                                                          closure-program
                                                          true))]
        (is (= "B" reference))
        (is (= reference value))))))


(deftest an-operand-held-across-a-blocked-read-round-trips
  (doseq [engine s/engines]
    (testing (name engine)
      (let [[reference value] (round-trip engine
                                          (parked-program engine
                                                          operand-program
                                                          false))]
        (is (= "B" reference))
        (is (= reference value))))))


(defn- continuations-in
  [x]
  (filter values/continuation?
          (tree-seq (fn [n] (or (coll? n) (values/continuation? n)))
                    (fn [n]
                      (cond (values/continuation? n) [(values/payload n)]
                            (map? n) (concat (keys n) (vals n))
                            :else (seq n)))
                    x)))


(deftest a-reified-continuation-lowers-receiver-owned
  (doseq [engine s/engines]
    (testing (name engine)
      (let [[reference value recv]
            (round-trip engine
                        (parked-program engine continuation-program false))
            held (continuations-in (:wait-set recv))]
        (is (= :done reference))
        (is (= reference value))
        (is (seq held) "the continuation value is carried")
        (is (every? #(values/owned-by? % (:owner recv)) held)
            "fresh receiver ownership, never the source's")))))


(deftest a-literal-map-shaped-like-a-marker-stays-data
  (doseq [engine s/engines]
    (testing (name engine)
      (let [shaped {:yin.k/tag :yin.k/frame :yin.k/parked-id :nowhere}
            t (s/toy)
            peer (s/served-peer t)
            parked (s/halted-with engine (s/lit shaped))
            export (s/lift parked t peer)
            [r _] (s/read! engine t (:bytes export)
                           {:address (:address export)})]
        (is (= :ok (:status export)) (pr-str export))
        (is (= :ok (:status r)) (pr-str r))
        (is (= shaped (vm/value (:vm r))))))))


(deftest a-closure-only-in-the-halted-result-carries-its-code
  (doseq [engine s/engines]
    (testing (name engine)
      (let [t (s/toy)
            peer (s/served-peer t)
            parked (s/halted-with engine
                                  (s/let1 'y (s/lit 5)
                                          (s/lam ['x] (s/v 'y))))
            export (s/lift parked t peer)
            [r _] (s/read! engine t (:bytes export)
                           {:address (:address export)})
            f (vm/value (:vm r))]
        (is (= :ok (:status export)) (pr-str export))
        (is (= :ok (:status r)) (pr-str r))
        (is (values/closure? f))
        (is (values/owned-by? f (:owner (:vm r))))
        (is (seq (get-in export [:body :yin.k/code])))))))
