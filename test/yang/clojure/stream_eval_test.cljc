(ns yang.clojure.stream-eval-test
  "yang.clojure stream programs on yin.vm, evaluated through the yin.repl
   shell, which is the composition around dao.stream media: each program
   is compiled by yang.clojure, appended to the program medium as one row
   batch, expanded onto the row medium, loaded and run by the evaluator's
   observer, and answered on the output medium as printed chunks followed
   by one result token (yin.repl; docs/design/dao.stream.md).  The streams
   a program makes -- `stream/make`, `put!`, `cursor`, `next!`, `close!`
   -- are ring buffers the shell hands the VM as `:make-stream`, so every
   stream a program touches is a dao.stream medium.

   Every case runs on the four evaluators the shell composes, with the
   value texts pinned, so the cases double as a cross-VM parity corpus."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [yin.repl :as repl]))


;; =============================================================================
;; Harness
;; =============================================================================

(def vm-types
  "Every evaluator the shell composes (`yin.repl/vm-constructors`)."
  [:ast-walker :semantic :stack :register])


(defn- session
  "A fresh shell on `vm-type`, optionally around a caller-owned output
   medium."
  ([vm-type] (session vm-type nil))
  ([vm-type output]
   (repl/create-state (cond-> {:vm-type vm-type}
                        output (assoc :output-stream output)))))


(defn- evaluate
  "Evaluate `lines` in order against one threaded state; `[state texts]`.
   The shell is synchronous, so no driver is needed.  The program media are
   shared by every state threaded from one session, so a state is never
   evaluated against twice."
  [state lines]
  (reduce (fn [[state texts] line]
            (let [[state' text] (repl/eval-input state line)]
              [state' (conj texts text)]))
          [state []]
          lines))


(defn- texts
  [vm-type lines]
  (second (evaluate (session vm-type) lines)))


(defn- ring-handle
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(defn- read-all
  "Every element on `handle` from its oldest, then `[:end outcome]` for
   the outcome that ended the read."
  [handle]
  (loop [cursor (:dao.stream/cursor
                  (stream/cursor handle :dao.stream/oldest))
         elements []]
    (let [result (stream/next handle cursor)]
      (if (= :dao.stream/ok (:dao.stream/outcome result))
        (recur (:dao.stream/cursor result)
               (conj elements (:dao.stream/value result)))
        (conj elements [:end (:dao.stream/outcome result)])))))


(defn- stream-ref?
  "A rendered stream reference; hosts print map keys in their own order."
  [text]
  (and (str/starts-with? text "{")
       (str/includes? text ":type :stream-ref")))


(defn- error?
  [text]
  (str/starts-with? text "Error: "))


;; =============================================================================
;; Pure expressions and let bindings
;; =============================================================================

(def pure-corpus
  "`[source expected]` pairs: literals, primitives, conditionals, lambdas,
   and let bindings, each answered as the shell renders the value."
  [["42" "42"]
   ["\"hi\"" "\"hi\""]
   [":k" ":k"]
   ["nil" "nil"]
   ["[1 2 3]" "[1 2 3]"]
   ["(+ 1 2)" "3"]
   ["(* (+ 1 2) (- 10 6))" "12"]
   ["(if (< 1 2) :yes :no)" ":yes"]
   ["(if false 1)" "nil"]
   ["(and 1 2)" "2"]
   ["(or nil 3)" "3"]
   ["(first [9 8 7])" "9"]
   ["(conj [1 2] 3)" "[1 2 3]"]
   ["(let [x 5] (+ x 3))" "8"]
   ["(let [x 2 y 3] (* x y))" "6"]
   ["(let [x 1] (let [y 2] (+ x y)))" "3"]
   ["(let [x 1 x (+ x 1)] x)" "2"]
   ["(let [x 5] (+ x 1) (* x 3))" "15"]
   ["(let [double (fn [x] (* x 2))] (double 5))" "10"]
   ["((fn [x y] (+ x y)) 10 20)" "30"]
   ["((fn [f x] (f x)) (fn [n] (* n 2)) 21)" "42"]
   ["(do 1 2 3)" "3"]])


(deftest pure-expressions-and-let-bindings-evaluate-on-every-vm
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state results]
            (evaluate (session vm-type) (map first pure-corpus))]
        (doseq [[[source expected] result] (map vector pure-corpus results)]
          (is (= expected result) source))
        (is (= 3 (:last-value state))
            "the last value is the last program's, not a stream token")))))


;; =============================================================================
;; Stream ingestion, transformation, and emission
;; =============================================================================

(def ^:private pump-source
  (str "(defn pump [c out]"
       " (let [v (stream/next! c)]"
       "  (if (= v nil)"
       "   out"
       "   (do (stream/put! out (* v 10)) (pump c out)))))"))


(def ^:private drain-source
  (str "(defn drain [c acc]"
       " (let [v (stream/next! c)]"
       "  (if (= v nil) acc (drain c (conj acc v)))))"))


(deftest a-program-makes-writes-and-reads-a-stream-in-one-round
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (is (= ["42" "99"]
             (texts vm-type
                    [(str "(let [s (stream/make 4)"
                          "      _ (stream/put! s 42)"
                          "      c (stream/cursor s)]"
                          "  (stream/next! c))")
                     (str "(let [s (stream/make)"
                          "      _ (stream/put! s 99)]"
                          "  (stream/next! (stream/cursor s)))")]))
          "with a declared capacity, and with the VM's default"))))


(deftest a-stream-is-ingested-transformed-and-emitted-across-rounds
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state results]
            (evaluate (session vm-type)
                      ["(def in (stream/make 8))"
                       "(stream/put! in 1)"
                       "(stream/put! in 2)"
                       "(stream/put! in 3)"
                       "(stream/close! in)"
                       "(def out (stream/make 8))"
                       pump-source
                       "(pump (stream/cursor in) out)"
                       "(stream/close! out)"
                       drain-source
                       "(drain (stream/cursor out) [])"
                       "(drain (stream/cursor in) [])"])
            [in put-1 put-2 put-3 closed out _ pumped closed-out _ drained
             replayed] results]
        (is (stream-ref? in) "make answers a stream reference")
        (is (= ["1" "2" "3"] [put-1 put-2 put-3]) "put! answers the value")
        (is (= "nil" closed) "close! answers nil")
        (is (stream-ref? out))
        (is (= out pumped) "the pump returns the output stream it filled")
        (is (= "nil" closed-out))
        (is (= "[10 20 30]" drained)
            "the transformed elements are read back in order")
        (is (= "[1 2 3]" replayed)
            "a second cursor on the source reads its whole history again")
        (is (= [1 2 3] (:last-value state)))))))


(deftest a-cursor-is-one-readers-progress
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (is (= ["1" "2" "1" "1"]
             (drop 4 (texts vm-type
                            ["(def s (stream/make 4))"
                             "(stream/put! s 1)"
                             "(stream/put! s 2)"
                             "(def c (stream/cursor s))"
                             "(stream/next! c)"
                             "(stream/next! c)"
                             "(stream/next! (stream/cursor s))"
                             (str "(let [c2 (stream/cursor s)]"
                                  " (stream/next! c2))")])))
          "a held cursor advances per read; a fresh one starts at oldest"))))


(deftest a-full-ring-evicts-its-oldest-element
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (is (= ["2"]
             (texts vm-type
                    [(str "(let [t (stream/make 1)"
                          "      _ (stream/put! t 1)"
                          "      _ (stream/put! t 2)"
                          "      c (stream/cursor t)]"
                          "  (stream/next! c))")]))
          "put! never answers full; the oldest element is gone"))))


(deftest a-closed-stream-refuses-writes-and-ends-reads
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state results]
            (evaluate (session vm-type)
                      ["(def s (stream/make 4))"
                       "(stream/put! s 1)"
                       "(stream/close! s)"
                       "(stream/put! s 2)"
                       "(+ 1 2)"
                       drain-source
                       "(drain (stream/cursor s) [])"
                       (str "(let [c (stream/cursor s)]"
                            " (stream/next! c) (stream/next! c))")])
            [_ _ _ refused next-round _ drained past-end] results]
        (is (error? refused) "put! on a closed stream is an error")
        (is (str/includes? refused "Stream append failed"))
        (is (= "3" next-round) "the failed round is consumed, not replayed")
        (is (= "[1]" drained) "the elements before the close remain")
        (is (= "nil" past-end) "reading past a closed stream's end is nil")
        (is (true? (:running? state)))))))


(deftest a-read-on-an-empty-stream-parks-and-the-round-is-consumed
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state results]
            (evaluate (session vm-type)
                      ["(def e (stream/make 2))"
                       "(stream/next! (stream/cursor e))"
                       "(+ 1 2)"
                       "(stream/put! e 5)"
                       "(stream/next! (stream/cursor e))"])
            [_ parked next-round put after] results
            summary (repl/repl-state state)]
        (is (error? parked)
            "a program that blocks on its own stream is not a halted one")
        (is (str/includes? parked "runnable"))
        (is (= "3" next-round) "the next input runs on the restored VM")
        (is (= ["5" "5"] [put after])
            "the stream outlives the parked round; a later read succeeds")
        (is (true? (get-in summary [:vm :halted?])))
        (is (false? (get-in summary [:vm :blocked?])))))))


;; =============================================================================
;; The shell's own media: program-in, program-out, and output
;; =============================================================================

(deftest output-and-results-are-emitted-on-the-output-medium
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [output (ring-handle 16)
            [state results]
            (evaluate (session vm-type output)
                      ["(do (print \"a\") (println \"b\") 7)"
                       "(prn :k)"])
            token (:shell-token state)]
        (is (= ["ab\n7" ":k\nnil"] results)
            "printed text precedes the value in what the shell answers")
        (is (= [{:type :repl/output :op :print :text "a"}
                {:type :repl/output :op :println :text "b\n"}
                {:type :repl/result :round [token 1] :value 7}
                {:type :repl/output :op :prn :text ":k\n"}
                {:type :repl/result :round [token 2] :value nil}
                [:end :dao.stream/blocked]]
               (read-all output))
            "every print is an element, and each round ends in its result")
        (is (= :dao.stream/blocked (get-in state [:ledger :output]))
            "the drain ran to the end of the medium")))))


(deftest programs-are-ingested-as-batches-on-the-program-media
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state results]
            (evaluate (session vm-type)
                      ["(+ 1 2)"
                       "(help)"
                       "(let [s (stream/make 2)] (stream/put! s :v))"
                       "(repl-state)"
                       "(stream/put! 1 2)"])
            batches (butlast (read-all (:program-stream state)))
            packets (butlast (read-all (:row-stream state)))]
        (is (= ["3" ":v"] [(nth results 0) (nth results 2)]))
        (is (error? (nth results 4)) "a bad put! fails in the evaluator")
        (is (= 3 (count batches))
            "one batch per program; a command appends nothing")
        (doseq [batch batches]
          (is (= :yin.program/batch (first batch)))
          (is (vector? (second batch)) "the batch carries its rows"))
        (is (= 3 (count packets))
            "the expander forwards every program, the failing one too")
        (doseq [[root rows] packets]
          (is (some #(= root (first %)) rows)
              "a packet's root names one of its own rows"))
        (is (zero? (get-in (repl/repl-state state) [:vm :in-stream :gaps]))
            "no batch was lost on the way")))))


;; =============================================================================
;; Multi-turn sessions
;; =============================================================================

(deftest a-session-threads-definitions-streams-and-history
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (let [[state results]
            (evaluate (session vm-type)
                      ["(def s (stream/make 4))"
                       "(defn twice [x] (* 2 x))"
                       "(stream/put! s 1)"
                       "(def c (stream/cursor s))"
                       "(stream/next! c)"
                       "(+ *1 41)"
                       "(twice *1)"
                       "(stream/put! s (+ *1 (+ *2 *3)))"
                       "(stream/next! c)"])]
        (is (= ["1" "42" "84" "127" "127"] (drop 4 results))
            "a stream, a closure, and the history span successive rounds")
        (is (= [127 127 84]
               [(:last-value state)
                (:last-value-2 state)
                (:last-value-3 state)]))))))


(deftest reset-and-vm-selection-forget-streams-with-the-session
  (doseq [vm-type vm-types
          command ["(reset)" "(vm :semantic)"]]
    (testing (str vm-type " " command)
      (let [[state results]
            (evaluate (session vm-type)
                      ["(def s (stream/make 4))"
                       "(stream/put! s 1)"
                       "(def c (stream/cursor s))"
                       command
                       "(stream/next! c)"
                       "(let [s (stream/make 1) _ (stream/put! s 9)]"
                       "  (stream/next! (stream/cursor s)))"])
            [_ _ _ _ gone fresh] (remove #(= "" %) results)]
        (is (error? gone) "the old cursor names nothing in the new session")
        (is (str/includes? gone "resolve symbol"))
        (is (= "9" fresh) "a new stream in the new session is unaffected")
        (is (nil? (:last-value-2 state))
            "the rebuilt session started its history over")))))


(deftest multi-line-input-accumulates-a-stream-program
  (doseq [vm-type vm-types]
    (testing (str vm-type)
      (is (= ["" "" "" "5"]
             (texts vm-type
                    ["(let [s (stream/make 2)"
                     "      _ (stream/put! s 5)"
                     "      c (stream/cursor s)]"
                     "  (stream/next! c))"]))
          "no text until the brackets balance, then one round"))))


;; =============================================================================
;; Cross-VM parity
;; =============================================================================

(def ^:private parity-script
  "One stream program per VM, closure values kept out of the answers so the
   texts can be compared byte for byte."
  ["(def in (stream/make 8))"
   "(stream/put! in 1)"
   "(stream/put! in 2)"
   "(stream/put! in 3)"
   "(stream/close! in)"
   "(def out (stream/make 8))"
   (str "(do " pump-source " :pump)")
   "(pump (stream/cursor in) out)"
   "(stream/close! out)"
   (str "(do " drain-source " :drain)")
   "(drain (stream/cursor out) [])"
   "(println \"done\" *1)"
   "(stream/next! (stream/cursor (stream/make 1)))"
   "(+ 1 2)"
   "(stream/put! out 4)"
   "(repl-state)"])


(deftest every-vm-answers-the-same-stream-script
  (let [answers (into {}
                      (map (fn [vm-type]
                             [vm-type (texts vm-type parity-script)]))
                      vm-types)
        summaries (map #(str/replace (peek (get answers %))
                                     (str %)
                                     ":<vm>")
                       vm-types)
        strip (fn [text] (str/replace text #"\"[0-9a-f-]{36}\"" "<id>"))
        ;; a reference's seal is its task's (yin.vm.linker.md 7.3, r10):
        ;; each shell mints its own secret, so seals differ by design
        unseal (fn [texts]
                 (mapv #(str/replace % #":seal\s+\"[0-9a-f]{64}\""
                                     ":seal <seal>")
                       texts))]
    (is (= ["[10 20 30]" "done [10 20 30]\nnil"]
           (subvec (get answers :semantic) 10 12)))
    (is (error? (get-in answers [:semantic 12])))
    (is (= "3" (get-in answers [:semantic 13])))
    (is (error? (get-in answers [:semantic 14])))
    (doseq [vm-type vm-types]
      (is (= (unseal (pop (get answers :semantic)))
             (unseal (pop (get answers vm-type))))
          (str vm-type " answers what the semantic VM answers")))
    (is (apply = (map strip summaries))
        "repl-state agrees up to the VM's name and the medium's identity")))
