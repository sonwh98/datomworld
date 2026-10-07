(ns yin.vm.ucf.handoff-v2-validity-test
  "UCF version 2, section 11 rows 6 and 7: register and walker validity.
   A real lifted body is mutated in the one place a rule lives and lowered
   with counting attachments: the refusal is deterministic, comes before
   any attachment, and assembles no machine.  The unchanged bodies still
   lower, and r2 images keep their exact register hash."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing.cbor :as jing.cbor]
            [yin.vm :as vm]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.ucf.v2-support :as s]))


(defn- make-stream
  []
  {:type :stream/make, :buffer 4})


(defn- reads
  "A program of `engine` parked on its first read, with live values held
   across it."
  [engine body]
  (s/run-ast engine
             (s/let1 's (make-stream)
                     (s/let1 'c (s/cursor-of (s/v 's)) body))))


(defn- lifted
  [machine]
  (let [t (s/toy)
        peer (s/served-peer t)
        export (s/lift machine t peer)]
    [t export (s/decoded export)]))


(defn- refuses
  "Lower `body` mutated by `f` into a fresh `engine` receiver: it refuses
   with one of `statuses`, attaching nothing and assembling no machine."
  [engine t body f statuses label]
  (let [[r n] (s/read! engine t (jing.cbor/encode (f body)) nil)]
    (is (contains? statuses (:yin.k/status r))
        (str label ": " (pr-str (dissoc r :vm))))
    (is (zero? n) (str label ": no stream was attached"))
    (is (not (contains? r :vm)) (str label ": no machine"))))


(def ^:private regs-path
  [:yin.k/frames 0 :yin.k/registers])


(def ^:private live-program
  "Two values live in registers across the blocking read."
  (s/app (s/lam ['a 'b 'x] (s/v 'x))
         (s/lit 10)
         (s/lit 20)
         (s/next-of (s/v 'c))))


(deftest register-bodies-keep-their-hash-and-refuse-each-mutated-rule
  (let [machine (reads :register live-program)
        [t export body] (lifted machine)
        regs (get-in body regs-path)
        undecodable #{:yin.k/undecodable}
        mut (fn [f] (fn [b] (update-in b regs-path f)))]
    (is (= :ok (:status export)) (pr-str export))
    (testing "an unchanged r2 image keeps its exact old register hash"
      (let [image (:image (rc/adapt (vm/ast->datoms
                                      (s/let1 's (make-stream)
                                              (s/let1 'c (s/cursor-of (s/v 's))
                                                      live-program)))))]
        (is (contains? (:yin.k/code body) (rcode/register-hash image)))))
    (testing "the unchanged body lowers"
      (let [[r _] (s/read! :register t (:bytes export)
                           {:address (:address export)})]
        (is (= :ok (:status r)) (pr-str r))))
    (is (< 1 (count (:yin.k/live regs))) "two live registers")
    (doseq [[label f]
            [["live order" (mut #(update % :yin.k/live (comp vec reverse)))]
             ["a live index out of the body"
              (mut #(assoc % :yin.k/live [0 99]))]
             ["a sparse pair index"
              (mut #(assoc-in % [:yin.k/regs 0 0] 99))]
             ["a missing pair"
              (mut #(update % :yin.k/regs subvec 1))]
             ["the destination"
              (mut #(update % :yin.k/dest inc))]
             ["a nil destination under write-result"
              (mut #(assoc % :yin.k/dest nil))]
             ["the tail mode"
              (mut #(assoc % :yin.k/resume-mode :return-result))]
             ["the site pc" (mut #(update % :yin.k/site-pc inc))]
             ["the pc" (mut #(update % :yin.k/pc inc))]
             ["an unknown resume mode"
              (mut #(assoc % :yin.k/resume-mode :other))]
             ["an unlisted register key"
              (mut #(assoc % :yin.k/extra 1))]]]
      (refuses :register t body f undecodable label))
    (testing "a return frame's site, return pc and destination"
      (when (seq (:yin.k/continuation regs))
        (doseq [[label f]
                [["return pc"
                  (mut #(update-in % [:yin.k/continuation 0 :yin.k/return-pc]
                                   inc))]
                 ["site pc"
                  (mut #(update-in % [:yin.k/continuation 0 :yin.k/site-pc]
                                   inc))]
                 ["destination"
                  (mut #(update-in % [:yin.k/continuation 0 :yin.k/dest]
                                   inc))]]]
          (refuses :register t body f undecodable label))))
    (testing "a body range"
      (let [a (first (:yin.k/layout body))]
        (refuses :register t body
                 #(update-in % [:yin.k/code a :bodies 0 :end] inc)
                 #{:yin.k/hash-mismatch :yin.k/undecodable}
                 "a body range")))))


(def ^:private operands-program
  (s/app (s/lam ['a 'b 'c] (s/v 'c))
         (s/lit 1)
         (s/next-of (s/v 'c))
         (s/lit 3)))


(deftest walker-bodies-refuse-each-mutated-rule
  (let [machine (reads :walker operands-program)
        [t export body] (lifted machine)
        top [:yin.k/frames 0 :yin.k/registers :yin.k/k]
        undecodable #{:yin.k/undecodable}
        code (:yin.k/code body)
        node-id (get-in body (conj top :yin.k/node))
        leaf (first (remove #{node-id}
                            (keep (fn [[id row]]
                                    (when (= :literal (nth row 1)) id))
                                  code)))]
    (is (= :ok (:status export)) (pr-str export))
    (is (= :eval-operand (get-in body (conj top :yin.k/type))))
    (testing "the unchanged body lowers"
      (let [[r _] (s/read! :walker t (:bytes export)
                           {:address (:address export)})]
        (is (= :ok (:status r)) (pr-str r))))
    (doseq [[label f statuses]
            [["a row whose content no longer matches its address"
              #(update-in % [:yin.k/code leaf]
                          (fn [row] (assoc row 2 "tampered")))
              #{:yin.k/hash-mismatch}]
             ["a missing child row"
              #(update % :yin.k/code dissoc leaf)
              undecodable]
             ["a frame tag no frame has"
              #(assoc-in % (conj top :yin.k/type) :eval-bogus)
              undecodable]
             ["a frame tag the node does not fit"
              #(assoc-in % (conj top :yin.k/type) :eval-test)
              undecodable]
             ["an addressed node no row answers"
              #(assoc-in % (conj top :yin.k/node) :segment/nope)
              undecodable]
             ["an evaluated list as long as the operands"
              #(assoc-in % (conj top :yin.k/evaluated) [1 2 3])
              undecodable]
             ["an evaluated list that is no vector"
              #(assoc-in % (conj top :yin.k/evaluated) :x)
              undecodable]
             ["a frame field no arm lists"
              #(assoc-in % (conj top :yin.k/extra) 1)
              undecodable]
             ["a next that is no frame"
              #(assoc-in % (conj top :yin.k/next) :nope)
              undecodable]]]
      (refuses :walker t body f statuses label))))


(deftest walker-ffi-correlation-is-checked-against-the-pending
  (let [[machine call-in _] (s/parked-sent-caller :walker)
        [t export body] (lifted machine)
        top [:yin.k/frames 0 :yin.k/registers :yin.k/k]
        undecodable #{:yin.k/undecodable}
        pending [:yin.k/frames 0 :yin.k/pending]]
    (is (some? call-in))
    (is (= :ok (:status export)) (pr-str export))
    (is (= :ffi (get-in body (conj pending :yin.k/reason))))
    (is (= :dao.stream.apply/eval-call (get-in body (conj top :yin.k/type))))
    (doseq [[label f]
            [["a call id the wrapper does not carry"
              #(assoc-in % (conj pending :yin.k/call-id) :parked-999)]
             ["a wrapper call id other than the pending's"
              #(assoc-in % (conj top :yin.k/call-id) :parked-999)]
             ["a wrapper of the other FFI arm"
              #(assoc-in % (conj top :yin.k/type)
                         :dao.stream.apply/request-sent)]
             ["a bookkeeping record no pending justifies"
              #(assoc % :yin.k/frames
                      (update (:yin.k/frames %) 0 assoc :yin.k/pending
                              {:yin.k/reason :next
                               :yin.k/cell (get-in % (conj pending
                                                           :yin.k/cell))}))]]]
      (refuses :walker t body f undecodable label))))
