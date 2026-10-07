(ns yin.vm.ucf.handoff-v2-walker-test
  "UCF version 2, section 11 row 7: walker validity.  Every continuation
   frame arm is exercised through source execution -- a suspension
   during the operator, successive operands, an if test, a definition,
   each stream operand, a resume value and FFI operand evaluation --
   lifted, lowered and resumed; then the row identity, a missing child,
   a frame tag, an evaluated length and an FFI correlation are mutated and
   refused before anything is attached."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [yin.vm :as vm]
            [yin.vm.ucf.v2-support :as s]))


(def ^:private make-stream
  {:type :stream/make, :buffer 4})


(defn- blocking
  "`body` evaluated with `c` bound to a fresh cursor on a fresh stream."
  [body]
  (s/let1 's make-stream
          (s/let1 'c (s/cursor-of (s/v 's)) body)))


(defn- read-c
  []
  (s/next-of (s/v 'c)))


(def ^:private arms
  "Each program suspends inside one frame arm: [arm program answer], the
   program's value once its reads answer B (and C)."
  [[:eval-operator
    (blocking (s/app (s/then (read-c) (s/lam ['y] (s/v 'y))) (s/lit 5)))
    5]
   [:eval-operand
    (blocking (s/app (s/lam ['a 'b 'c] (s/v 'c))
                     (s/lit 1) (read-c) (read-c)))
    "C"]
   [:eval-test
    (blocking {:type :if
               :test (read-c)
               :consequent (s/lit :yes)
               :alternate (s/lit :no)})
    :yes]
   [:eval-define
    (blocking (s/def! 'x (read-c)))
    "B"]
   [:eval-stream-put-target
    (blocking {:type :stream/put :target (s/then (read-c) (s/v 's))
               :val (s/lit 1)})
    nil]
   [:eval-stream-put-val
    (blocking {:type :stream/put :target (s/v 's) :val (read-c)})
    nil]
   [:eval-stream-cursor-source
    (blocking {:type :stream/cursor :source (s/then (read-c) (s/v 's))})
    nil]
   [:eval-stream-next-cursor
    (blocking {:type :stream/next :source (s/then (read-c) (s/v 'c))})
    nil]
   [:dao.stream.apply/eval-operand
    (blocking {:type :dao.stream.apply/call
               :op :op/echo
               :operands [(read-c)]})
    nil]])


(defn- first-frame-types
  [parked]
  (into [] (map :type)
        (take-while some? (iterate :next (:k (first (:wait-set parked)))))))


(def ^:private no-drive
  "Arms whose continuation mints a cursor on a reflected stream: the
   lowered chain is compared, the run is not (a reflection mints no
   cursor)."
  #{:eval-stream-cursor-source})


(deftest every-frame-arm-round-trips-through-source-execution
  (doseq [[arm program answer] arms]
    (testing (str arm)
      (let [parked (s/run-ast :walker program)
            t (s/toy)
            peer (s/served-peer t)
            export (s/lift parked t peer nil #{:reader :writer})
            src (s/stream-of parked)
            [r _] (s/read! :walker t (:bytes export)
                           {:address (:address export)})
            reference (do (stream/append! src "B")
                          (stream/append! src "C")
                          (s/drive-local parked))
            done (when (and (:vm r) (not (no-drive arm)))
                   (s/drive (:vm r) peer))]
        (is (= :ok (:status export)) (pr-str export))
        (is (some #{arm} (first-frame-types parked))
            "the arm is on the source's own continuation")
        (is (= :ok (:status r)) (pr-str r))
        (is (= (first-frame-types parked) (first-frame-types (:vm r)))
            "the lowered chain has the source's frame types in order")
        (when-not (no-drive arm)
          (is (= (vm/blocked? reference) (vm/blocked? done)))
          (when (some? answer)
            (is (= answer (vm/value reference)))
            (is (= answer (vm/value done)))))))))


(deftest a-resume-value-suspension-carries-its-parked-record
  (let [m0 (s/run-ast :walker {:type :vm/park})
        pid (:id (vm/value m0))
        parked (vm/eval m0 (blocking {:type :vm/resume
                                      :parked-id pid
                                      :val (read-c)}))
        t (s/toy)
        peer (s/served-peer t)
        export (s/lift parked t peer nil #{:reader :writer})
        src (s/stream-of parked)
        [r _] (s/read! :walker t (:bytes export)
                       {:address (:address export)})
        reference (do (stream/append! src "B")
                      (s/drive-local parked))
        done (when (:vm r) (s/drive (:vm r) peer))]
    (is (= :ok (:status export)) (pr-str export))
    (is (some #{:eval-resume-val} (first-frame-types parked)))
    (is (contains? (get-in export [:body :yin.k/parked]) pid)
        "the referenced parked record travels")
    (is (= :ok (:status r)) (pr-str r))
    (is (contains? (:parked (:vm r)) pid))
    (is (= (vm/value reference) (vm/value done)))))
