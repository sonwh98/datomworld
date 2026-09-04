(ns yin.repl.v2-core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as ring]
            [yin.repl.v2.core :as core]))


(defn- handle
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- oldest
  [h]
  (:dao.stream/cursor (stream/cursor h :dao.stream/oldest)))


(defn- evaluate
  "Evaluate each line in order against one state value, returning the last
   result text.  The core is synchronous, so a test needs no driver."
  [state lines]
  (reduce (fn [[state _] line] (core/eval-input state line))
          [state nil]
          lines))


(deftest fresh-state-is-an-ast-walker-shell-with-an-untried-ledger
  (let [state (core/create-state)]
    (is (= :ast-walker (:vm-type state)))
    (is (= :clojure (:lang state)))
    (is (true? (:running? state)))
    (is (= :untried (get-in state [:ledger :output]))
        "an idle medium has no last operation, so the ledger says so")
    (is (some? (:in-stream (:vm state)))
        "the v2 ast-walker owns its v2 ingress medium")
    (is (false? (:ingress-loss? state)))))


(deftest ordinary-source-evaluates-locally
  (let [[_ result] (core/eval-input (core/create-state) "(+ 1 2)")]
    (is (= "3" result))))


(deftest printed-output-precedes-the-value
  (let [[state result] (core/eval-input (core/create-state) "(println \"hi\")")]
    (is (= "hi\nnil" result))
    (is (= :dao.stream/blocked (get-in state [:ledger :output]))
        "the drain records the outcome that ended it")))


(deftest value-history-is-injected-for-the-next-evaluation
  (let [[_ result] (evaluate (core/create-state) ["(+ 1 2)" "(+ *1 1)"])]
    (is (= "4" result))))


(deftest multi-line-input-accumulates-until-the-brackets-balance
  (let [[state partial] (core/eval-input (core/create-state) "(+ 1")
        [state' result] (core/eval-input state " 2)")]
    (is (= "" partial))
    (is (= "(+ 1" (:pending-input state)))
    (is (= "3" result))
    (is (nil? (:pending-input state')))))


(deftest the-vm-command-offers-only-the-ast-walker
  (let [[state result] (core/eval-input (core/create-state) "(vm :ast-walker)")
        [_ rejected] (core/eval-input state "(vm :semantic)")]
    (is (= "Switched to ASTWalkerVM (store cleared)" result))
    (is (str/includes? rejected "Unknown Yin REPL VM type"))
    (is (str/includes? rejected ":ast-walker"))))


(deftest language-and-compile-commands-behave-as-they-do-in-v1
  (let [[state result] (core/eval-input (core/create-state) "(lang :python)")
        [_ compiled] (core/eval-input state "(compile \"1 + 2\")")]
    (is (= "Switched to Python" result))
    (is (str/includes? compiled "AST:"))
    (is (str/includes? compiled "Datoms:"))))


(deftest reset-rebuilds-the-vm
  (let [[state _] (evaluate (core/create-state) ["(+ 1 2)"])
        [state' message] (core/eval-input state "(reset)")]
    (is (= "ASTWalkerVM reset" message))
    (is (not (identical? (:vm state) (:vm state'))))
    (is (identical? (:output-stream state) (:output-stream state'))
        "the output medium belongs to the composition, not to the VM")))


(deftest quit-stops-the-shell-without-touching-the-host
  (let [[state result] (core/eval-input (core/create-state) "(quit)")]
    (is (= "Bye" result))
    (is (false? (:running? state)))))


(deftest telemetry-is-rejected-and-names-the-v1-repl
  (let [[state result] (core/eval-input (core/create-state) "(telemetry)")]
    (is (str/includes? result "yin.repl"))
    (is (true? (:running? state)))))


(deftest repl-state-reports-the-ledger-rather-than-asking-a-stream
  (let [state (core/create-state)
        summary (core/repl-state state)]
    (is (= :ast-walker (get-in summary [:vm :type])))
    (is (false? (get-in summary [:telemetry :supported?])))
    (is (= :untried (get-in summary [:output :last-outcome])))
    (is (contains? (:output summary) :cursor))
    (let [[_ rendered] (core/eval-input state "(repl-state)")]
      (is (str/includes? rendered ":ast-walker")))))


(deftest datom-literal-evaluation-runs-on-the-v2-ingress-medium
  (testing "a runnable datom program evaluates to its value, as in v1"
    (let [[state result] (core/eval-input (core/create-state)
                                          "[[-1 :yin/type :literal 0 1]
                                            [-1 :yin/value 99 0 1]]")]
      (is (= "99" result))
      (is (= 99 (:last-value state)))))
  (testing "a non-program datom stream is reported, never thrown at the host"
    (let [[state result] (core/eval-input (core/create-state)
                                          "[[1 :a 1 0 true] [1 :b 2 0 true]]")]
      (is (str/starts-with? result "Error: "))
      (is (true? (:running? state))))))


(deftest an-ingress-gap-is-fatal-to-evaluation-until-reset
  (let [state (core/create-state)
        ingress (get-in state [:vm :in-stream])]
    ;; Evict one batch the VM never sees: the shell is the only appender, so
    ;; only a flood beyond the declared capacity can produce the gap.
    (doseq [_ (range (inc core/ingress-capacity))]
      (stream/append! ingress [[-1 :yin/type :literal 0 1] [-1 :yin/value 1 0 1]]))
    (let [[state' result] (core/eval-input state
                                           "[[-1 :yin/type :literal 0 1]
                                             [-1 :yin/value 2 0 1]]")]
      (is (str/starts-with? result "Error: ") "the loss is reported")
      (is (str/includes? result "(reset)"))
      (is (true? (:ingress-loss? state')))
      (testing "further evaluation is refused rather than resumed as if complete"
        (let [[_ refused] (core/eval-input state' "(+ 1 2)")]
          (is (str/starts-with? refused "Error: "))))
      (testing "commands still answer, and (reset) recovers the shell"
        (let [[state'' message] (core/eval-input state' "(reset)")]
          (is (= "ASTWalkerVM reset" message))
          (is (false? (:ingress-loss? state'')))
          (is (zero? (get-in (core/repl-state state'') [:vm :in-stream :gaps])))
          (let [[_ recovered] (core/eval-input state'' "(+ 1 2)")]
            (is (= "3" recovered))))))))


(deftest unknown-commands-and-reader-failures-are-reported-not-thrown
  (testing "an unknown command"
    (let [[state result] (core/eval-input (core/create-state) "(nope)")]
      (is (str/starts-with? result "Error: "))
      (is (true? (:running? state)))))
  (testing "unreadable input"
    (let [[_ result] (core/eval-input (core/create-state) "\"unterminated")]
      (is (str/starts-with? result "Error: ")))))


(deftest the-output-drain-is-total-over-next
  (testing "a gap prints a loss notice and resumes at the recovery cursor"
    (let [output (handle 2)
          state (core/create-state {:output-stream output})]
      (doseq [n (range 5)]
        (stream/append! output {:type :repl/output :op :print :text (str n)}))
      (let [[state' text] (core/drain-output state)]
        (is (str/includes? text "output lost"))
        (is (str/includes? text "34"))
        (is (= :dao.stream/blocked (get-in state' [:ledger :output])))
        (let [[_ text'] (core/drain-output state')]
          (is (= "" text') "the recovery cursor is retained, not re-read")))))
  (testing "a closed medium ends the drain and is recorded once"
    (let [output (handle 8)
          state (core/create-state {:output-stream output})]
      (stream/append! output {:type :repl/output :op :print :text "x"})
      (stream/close! output)
      (let [[state' text] (core/drain-output state)]
        (is (str/includes? text "x"))
        (is (= :dao.stream/end (get-in state' [:ledger :output])))))))


(deftest the-output-cursor-is-minted-not-fabricated
  (let [output (handle 8)
        state (core/create-state {:output-stream output})]
    (is (= (oldest output) (:output-cursor state)))))
