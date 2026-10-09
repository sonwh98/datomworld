(ns yin.repl-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [yin.repl :as repl]
            [yin.repl.frontends :as repl.frontends]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-register-code :as rcode]
            [dao.stream.observer :as observer]))


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
  (reduce (fn [[state _] line] (repl/eval-input state line))
          [state nil]
          lines))


(deftest fresh-state-is-a-semantic-shell-with-an-untried-ledger
  (let [state (repl.frontends/create-state)]
    (is (= :semantic (:vm-type state))
        "the semantic VM is the default after Phase 4 (yin.vm.semantic.md §8)")
    (is (= :clojure (:lang state)))
    (is (true? (:running? state)))
    (is (= :untried (get-in state [:ledger :output]))
        "an idle medium has no last operation, so the ledger says so")
    (is (some? (:program-stream state))
        "the shell owns the program medium's writer handle")
    (is (some? (:observer state))
        "an observer is attached beside the VM, not inside it")
    (is (zero? (:ingress-gaps (:observer state))))
    (is (some? (:row-stream state))
        "the semantic session owns a row medium beside the program medium")
    (is (some? (:row-observer state))
        "the VM's own observer is attached to the row medium")
    (is (zero? (:ingress-gaps (:row-observer state))))
    (is (false? (:ingress-loss? state)))))


(deftest ordinary-source-evaluates-locally
  (let [[_ result] (repl/eval-input (repl.frontends/create-state) "(+ 1 2)")]
    (is (= "3" result))))


(deftest printed-output-precedes-the-value
  (let [[state result] (repl/eval-input (repl.frontends/create-state) "(println \"hi\")")]
    (is (= "hi\nnil" result))
    (is (= :dao.stream/blocked (get-in state [:ledger :output]))
        "the drain records the outcome that ended it")))


(deftest value-history-is-injected-for-the-next-evaluation
  (let [[_ result] (evaluate (repl.frontends/create-state) ["(+ 1 2)" "(+ *1 1)"])]
    (is (= "4" result))))


(deftest multi-line-input-accumulates-until-the-brackets-balance
  (let [[state partial] (repl/eval-input (repl.frontends/create-state) "(+ 1")
        [state' result] (repl/eval-input state " 2)")]
    (is (= "" partial))
    (is (= "(+ 1" (:pending-input state)))
    (is (= "3" result))
    (is (nil? (:pending-input state')))))


(deftest the-vm-command-offers-the-ast-walker-and-the-semantic-vm
  (let [[state result] (repl/eval-input (repl.frontends/create-state) "(vm :ast-walker)")
        [state' semantic] (repl/eval-input state "(vm :semantic)")
        [_ rejected] (repl/eval-input state' "(vm :bytecode)")]
    (is (= "Switched to ASTWalkerVM (store cleared)" result))
    (is (= "Switched to SemanticVM (store cleared)" semantic))
    (is (= :semantic (:vm-type state')))
    (is (str/includes? rejected "Unknown Yin REPL VM type"))
    (is (str/includes? rejected ":semantic"))))


(deftest host-primitives-survive-reset-and-vm-selection
  (let [state (repl.frontends/create-state {:primitives {'answer (fn [] 42)}})
        [state before] (repl/eval-input state "(answer)")
        [state _] (repl/eval-input state "(reset)")
        [state after-reset] (repl/eval-input state "(answer)")
        [state _] (repl/eval-input state "(vm :ast-walker)")
        [_ after-vm] (repl/eval-input state "(answer)")]
    (is (= "42" before))
    (is (= "42" after-reset) "(reset) rebuilds the session with the host functions")
    (is (= "42" after-vm) "(vm …) rebuilds the session with the host functions")))


(deftest the-semantic-vm-evaluates-through-both-observer-stages
  ;; The program media are shared by every state threaded from one session,
  ;; so each case starts from its own session rather than a stale state.
  (let [semantic-state #(first (repl/eval-input (repl.frontends/create-state) "(vm :semantic)"))]
    (testing "source loads a code segment and runs"
      (let [[state' result] (repl/eval-input (semantic-state) "(+ 1 2)")]
        (is (= "3" result))
        (is (= 1 (count (:code (:vm state')))))))
    (testing "the segment came through the row lane, not the datom lane"
      (let [[state' _] (repl/eval-input (semantic-state) "(+ 1 2)")]
        (is (every? :address (vals (:code (:vm state'))))
            "only load-vector aliases a segment by its vector's address")))
    (testing "the session composes two stages over two media"
      (let [[state' _] (repl/eval-input (semantic-state) "(+ 1 2)")]
        (is (not (identical? (:program-stream state') (:row-stream state')))
            "the program medium and the row medium are distinct")
        (is (not (identical? (:stream (:observer state'))
                             (:stream (:row-observer state'))))
            "the encoder and the evaluator are attached independently")))
    (testing "a map AST travels the medium as emitted, not as datoms"
      (is (= "7" (second (repl/eval-input (semantic-state)
                                          "{:type :literal, :value 7}")))))
    (testing "definitions, recursion, and history span successive programs"
      (let [[state' result]
            (evaluate (semantic-state)
                      ["(defn sum-to [n] (if (= n 0) 0 (+ n (sum-to (- n 1)))))"
                       "(sum-to 100)"
                       "(+ *1 1)"])]
        (is (= "5051" result))
        (is (= 3 (count (:code (:vm state'))))
            "each program is its own segment; earlier closures still resolve")))
    (testing "printed output precedes the value"
      (is (= "hi\nnil" (second (repl/eval-input (semantic-state) "(println \"hi\")")))))
    (testing "a datom literal rides the projection adapter like compiled source"
      (is (= "99" (second (repl/eval-input (semantic-state)
                                           "[[-1 :yin/type :literal 0 1]
                                             [-1 :yin/value 99 0 1]]")))))
    (testing "reset keeps the evaluator and rebuilds its session"
      (let [[state' message] (repl/eval-input (semantic-state) "(reset)")]
        (is (= "SemanticVM reset" message))
        (is (= :semantic (get-in (repl/repl-state state') [:vm :type])))
        (is (= "7" (second (repl/eval-input state' "(+ 3 4)"))))))))


(deftest a-failed-input-is-consumed-exactly-once
  (doseq [vm-type [:semantic :ast-walker]]
    (testing (str vm-type " : an evaluation error and its effects do not replay")
      (let [state (first (repl/eval-input (repl.frontends/create-state) (str "(vm " vm-type ")")))
            [state' failed] (repl/eval-input state "(do (println \"before\") (/ 1 0))")
            [state'' next-result] (repl/eval-input state' "(+ 1 2)")]
        (is (str/starts-with? failed "before\n"))
        (is (str/includes? failed "Error: "))
        (is (= "3" next-result) "the next input runs alone")
        (is (= "4" (second (repl/eval-input state'' "(+ *1 1)")))
            "observation progress and history continue past the failure"))))
  (testing "a batch the loader rejects is consumed, not re-read"
    (let [[state failed] (repl/eval-input (repl.frontends/create-state)
                                          "[[1 :a 1 0 true] [1 :b 2 0 true]]")]
      (is (str/starts-with? failed "Error: "))
      (is (= "3" (second (repl/eval-input state "(+ 1 2)")))))))


(deftest lexical-scope-does-not-escape-a-top-level-evaluation
  (doseq [vm-type [:semantic :ast-walker]]
    (testing (str vm-type)
      (let [state (first (repl/eval-input (repl.frontends/create-state) (str "(vm " vm-type ")")))
            [state' _] (evaluate state ["(def x 1)" "(let [x 7] x)"])
            [_ result] (repl/eval-input state' "x")]
        (is (= "1" result))))))


(deftest reset-and-vm-selection-clear-the-value-history
  (doseq [command ["(reset)" "(vm :semantic)"]]
    (testing command
      (let [[state _] (evaluate (repl.frontends/create-state) ["(fn [x] (+ x 1))" command])
            [state' result] (repl/eval-input state "(*1 4)")]
        (is (nil? (:last-value state)) "no closure outlives its code segment")
        (is (str/starts-with? result "Error: "))
        (is (true? (:running? state')))))))


(deftest language-and-compile-commands-behave-as-they-do-in-v1
  (let [[state result] (repl/eval-input (repl.frontends/create-state) "(lang :python)")
        [_ compiled] (repl/eval-input state "(compile \"1 + 2\")")]
    (is (= "Switched to Python" result))
    (is (str/includes? compiled "AST:"))
    (is (str/includes? compiled "Input rows:"))
    (is (str/includes? compiled "Expanded rows:"))
    (is (str/includes? compiled "Events:"))))


(deftest reset-rebuilds-the-vm-and-its-attachment
  (let [[state _] (evaluate (repl.frontends/create-state) ["(+ 1 2)"])
        [state' message] (repl/eval-input state "(reset)")]
    (is (= "SemanticVM reset" message))
    (is (not (identical? (:vm state) (:vm state'))))
    (is (not (identical? (:program-stream state) (:program-stream state')))
        "the program medium is rebuilt with the VM")
    (is (not (identical? (:observer state) (:observer state')))
        "the observer is reattached to the new medium")
    (is (zero? (:ingress-gaps (:observer state'))))
    (is (not (identical? (:row-stream state) (:row-stream state')))
        "the row medium is rebuilt with the VM")
    (is (not (identical? (:row-observer state) (:row-observer state')))
        "the evaluator observer is reattached to the new row medium")
    (is (zero? (:ingress-gaps (:row-observer state'))))
    (is (identical? (:output-stream state) (:output-stream state'))
        "the output medium belongs to the composition, not to the VM")))


(deftest the-program-medium-attaches-by-descriptor
  (let [state (repl.frontends/create-state)
        writer (:program-stream state)
        descriptor (:dao.stream/descriptor (stream/descriptor writer))
        attach! (ring/make-attacher {(:dao.stream/identity descriptor) writer})
        observer (observer/attach attach! descriptor)]
    (stream/append! writer [[:batch]])
    (let [observed (observer/observe-next observer)]
      (is (= :ok (:status observed)))
      (is (= [[:batch]] (:batch observed))
          "the resolver maps the descriptor's identity to the owner handle"))))


(deftest quit-stops-the-shell-without-touching-the-host
  (let [[state result] (repl/eval-input (repl.frontends/create-state) "(quit)")]
    (is (= "Bye" result))
    (is (false? (:running? state)))))


(deftest telemetry-is-rejected-and-points-at-the-built-emit-path
  (let [[state result] (repl/eval-input (repl.frontends/create-state) "(telemetry)")]
    (is (str/includes? result "yin.vm.telemetry.implementation-plan.md"))
    (is (not (str/includes? result "yin.repl")))
    (is (true? (:running? state)))))


(deftest repl-state-reports-the-ledger-rather-than-asking-a-stream
  (let [state (repl.frontends/create-state)
        summary (repl/repl-state state)]
    (is (= :semantic (get-in summary [:vm :type])))
    (is (false? (get-in summary [:telemetry :supported?])))
    (is (= :untried (get-in summary [:output :last-outcome])))
    (is (contains? (:output summary) :cursor))
    (let [[_ rendered] (repl/eval-input state "(repl-state)")]
      (is (str/includes? rendered ":semantic")))))


(deftest datom-literal-evaluation-runs-through-the-program-medium
  (testing "a runnable datom program evaluates to its value, as in v1"
    (let [[state result] (repl/eval-input (repl.frontends/create-state)
                                          "[[-1 :yin/type :literal 0 1]
                                            [-1 :yin/value 99 0 1]]")]
      (is (= "99" result))
      (is (= 99 (:last-value state)))))
  (testing "a non-program datom stream is reported, never thrown at the host"
    (let [[state result] (repl/eval-input (repl.frontends/create-state)
                                          "[[1 :a 1 0 true] [1 :b 2 0 true]]")]
      (is (str/starts-with? result "Error: "))
      (is (true? (:running? state))))))


(deftest an-ingress-gap-is-fatal-to-evaluation-until-reset
  (let [state (repl.frontends/create-state)
        writer (:program-stream state)]
    ;; Evict one batch the observer never sees: the shell is the only
    ;; appender, so only a flood beyond the declared capacity can produce the
    ;; gap.
    (doseq [_ (range (inc repl/ingress-capacity))]
      (stream/append! writer [[-1 :yin/type :literal 0 1] [-1 :yin/value 1 0 1]]))
    (let [[state' result] (repl/eval-input state
                                           "[[-1 :yin/type :literal 0 1]
                                             [-1 :yin/value 2 0 1]]")]
      (is (str/starts-with? result "Error: ") "the loss is reported")
      (is (str/includes? result "(reset)"))
      (is (true? (:ingress-loss? state')))
      (testing "further evaluation is refused rather than resumed as if complete"
        (let [[_ refused] (repl/eval-input state' "(+ 1 2)")]
          (is (str/starts-with? refused "Error: "))))
      (testing "commands still answer, and (reset) recovers the shell"
        (let [[state'' message] (repl/eval-input state' "(reset)")]
          (is (= "SemanticVM reset" message))
          (is (false? (:ingress-loss? state'')))
          (is (zero? (get-in (repl/repl-state state'') [:vm :in-stream :gaps])))
          (let [[_ recovered] (repl/eval-input state'' "(+ 1 2)")]
            (is (= "3" recovered)))
          (let [[_ datoms] (repl/eval-input state''
                                            "[[-1 :yin/type :literal 0 1]
                                              [-1 :yin/value 8 0 1]]")]
            (is (= "8" datoms)
                "datom evaluation succeeds on the rebuilt attachment")))))))


(deftest unknown-commands-and-reader-failures-are-reported-not-thrown
  (testing "an unknown command"
    (let [[state result] (repl/eval-input (repl.frontends/create-state) "(nope)")]
      (is (str/starts-with? result "Error: "))
      (is (true? (:running? state)))))
  (testing "unreadable input"
    (let [[_ result] (repl/eval-input (repl.frontends/create-state) "\"unterminated")]
      (is (str/starts-with? result "Error: ")))))


(deftest the-reader-keeps-datalog-symbols-on-every-host
  ;; A Datalog query or rule set typed at the prompt names its rules input
  ;; `%`, a collection binding `...` and a blank `_`: the input reader must
  ;; read each as the plain symbol on every host.
  (doseq [[line expected]
          [["(quote [:in $ast $occ % ?root])" "[:in '$ast '$occ '% '?root]"]
           ["(quote [% ?root])" "['% '?root]"]
           ["(quote [?root %])" "['?root '%]"]
           ["(quote [?name ...])" "['?name '...]"]
           ["(quote [:in % :where (r ?x)])" "[:in '% :where ('r '?x)]"]
           ["(quote [$ast ?lam :lambda ?params _])"
            "['$ast '?lam :lambda '?params '_]"]]]
    (is (= expected (second (repl/eval-input (repl.frontends/create-state) line)))
        line)))


(defn- answer
  [line]
  (second (repl/eval-input (repl.frontends/create-state) line)))


(deftest the-reader-reads-percent-forms-alike-on-every-host
  ;; Cross-host parity: each line reads to the same form, printed as the
  ;; same text, on the JVM, Node and Dart readers, including a `%` right
  ;; after a discard, a string and a discarded character literal.
  (doseq [[line expected]
          [["(quote [#_% %])" "['%]"]
           ["(quote [#_#_% % %1])" "['%1]"]
           ["(quote [#_ % %&])" "['%&]"]
           ["(quote [\"%\" #_\\% %])" "[\"%\" '%]"]
           ["(quote {% [%]})" "{'% ['%]}"]]]
    (is (= expected (answer line)) line)))


(deftest a-percent-reads-as-any-other-symbol-does
  ;; On each host, a line with `%` answers exactly what the same line with
  ;; an ordinary symbol answers (map type and order, metadata, duplicate
  ;; handling and tag refusals included).
  (doseq [[line plain]
          [["(quote [% {:z %, :y 2, :x 3}])" "(quote [zz9 {:z zz9, :y 2, :x 3}])"]
           ["(quote [% {:c 1, :b 2, :a 3} #{%}])"
            "(quote [zz9 {:c 1, :b 2, :a 3} #{zz9}])"]
           ["(quote [% ^{:m 1} [1 {:b 2, :a 1}] (f (g %))])"
            "(quote [zz9 ^{:m 1} [1 {:b 2, :a 1}] (f (g zz9))])"]
           ["(quote #{% %})" "(quote #{zz9 zz9})"]
           ["(quote {% 1 % 2})" "(quote {zz9 1 zz9 2})"]
           ["(quote [#foo %])" "(quote [#foo zz9])"]]]
    (is (= (str/replace (answer plain) "zz9" "%") (answer line)) line)))


(defn- shape
  "`form` as plain data that prints alike on every host and shows its
   metadata: a map becomes `[:map [k v] ...]` and a set `[:set x ...]`,
   entries sorted by printed text, and a form carrying metadata becomes
   `[:meta <shape of the metadata> <shape of the form>]`, at every depth."
  [form]
  (let [v (cond (seq? form) (apply list (map shape form))
                (vector? form) (mapv shape form)
                (map? form) (into [:map]
                                  (sort-by pr-str
                                           (map (fn [[k x]] [(shape k) (shape x)]) form)))
                (set? form) (into [:set] (sort-by pr-str (map shape form)))
                :else form)
        m (meta form)]
    (if (seq m) [:meta (shape m) v] v)))


(defn- read-shape
  "The shape of `line`'s forms, or the refusal's message."
  [line]
  (try (pr-str (shape (repl/read-forms line)))
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (str "refused: " (or (ex-message e) (str e))))))


(deftest a-percent-reads-alike-in-every-symbol-position
  ;; Every reader position where a symbol can appear, read to the same
  ;; forms, metadata included, on the JVM, Node and Dart.
  (let [cases
        [[:value "[% %1 %& (f %) {:k %}]" "[[% %1 %& (f %) [:map [:k %]]]]"]
         [:qualified-value "[%/foo %x/y]" "[[%/foo %x/y]]"]
         [:meta-tag "^% x" "[[:meta [:map [:tag %]] x]]"]
         [:meta-map-value "^{:tag %} x" "[[:meta [:map [:tag %]] x]]"]
         [:meta-map-key "^{% 1} [x]" "[[:meta [:map [% 1]] [x]]]"]
         [:meta-nested "^{:k [%/foo]} (f)" "[[:meta [:map [:k [%/foo]]] (f)]]"]
         [:meta-on-coll "[^% [1] ^% {:a 1} ^% #{1}]"
          "[[[:meta [:map [:tag %]] [1]] [:meta [:map [:tag %]] [:map [:a 1]]] [:meta [:map [:tag %]] [:set 1]]]]"]
         [:map-key "{% 1}" "[[:map [% 1]]]"]
         [:map-key-qualified "{%/foo [%]}" "[[:map [%/foo [%]]]]"]
         [:set-element "#{% [%]}" "[[:set % [%]]]"]
         [:set-element-qualified "#{%/foo}" "[[:set %/foo]]"]
         [:namespaced-map-key "#:a{% 1}" "[[:map [a/% 1]]]"]
         [:namespaced-map-qualified-key "#:a{%/foo 1, _/% 2}" "[[:map [% 2] [%/foo 1]]]"]
         [:namespaced-map-value "#:a{:b %}" "[[:map [:a/b %]]]"]]]
    ;; one assertion naming every position that differs: a host test
    ;; runner that stops at the first failure still reports them all
    (is (= [] (into []
                    (keep (fn [[position line expected]]
                            (let [actual (read-shape line)]
                              (when (not= expected actual)
                                [position line actual]))))
                    cases)))))


(deftest a-percent-reads-as-a-symbol-reads-under-syntax-quote
  ;; Syntax quote qualifies an unqualified symbol with the reading
  ;; namespace and leaves a qualified one alone; its expansion, like the
  ;; `'`, `#'` and `@` expansions, is each host's own (Node's EDN reader
  ;; has none of them and refuses), so the `%` line is compared with a
  ;; plain-symbol line on the same host.
  (is (= [] (into []
                  (keep (fn [[line plain]]
                          (let [actual (read-shape line)
                                expected (str/replace (read-shape plain) "zz9" "%")]
                            (when (not= expected actual) [line actual expected]))))
                  [["['% #'% @% '%/foo]" "['zz9 #'zz9 @zz9 'zz9/foo]"]
                   ["`%" "`zz9"]
                   ["`%/foo" "`zz9/foo"]
                   ["`[% ~% ~@%]" "`[zz9 ~zz9 ~@zz9]"]
                   ["`(f %/foo ^% x)" "`(f zz9/foo ^zz9 x)"]]))))


(deftest tagged-literals-and-reader-conditionals-refuse-alike
  ;; An unknown tag refuses on every host; the input reader enables no
  ;; reader conditionals on any host.
  (doseq [line ["[#foo %]" "[#?(:clj %)]" "[#?@(:clj [%])]"]]
    (is (str/starts-with? (read-shape line) "refused: ") line)))


(deftest regex-and-anonymous-fn-forms-read-as-on-the-jvm
  ;; A `%` after a regex or an anonymous fn literal. Node reads input as
  ;; EDN, which has neither form and refuses both; Dart must read them as
  ;; the JVM does.
  (doseq [line ["(quote [#_#\"%\" %])" "(quote [#_#(+ %1 1) %])"]]
    (let [result (answer line)]
      #?(:cljd (is (= "['%]" result) line)
         :cljs (is (str/starts-with? result "Error: ") line)
         :clj (is (= "['%]" result) line)))))


(deftest duplicate-percent-keys-are-refused-where-the-reader-refuses-them
  ;; Every host's reader refuses duplicate set elements and map keys,
  ;; `%` included.
  (doseq [[line fragment] [["(quote #{% %})" "uplicate key: %"]
                           ["(quote {% 1 % 2})" "uplicate key: %"]]]
    (let [[state result] (repl/eval-input (repl.frontends/create-state) line)]
      (is (str/starts-with? result "Error: ") (str line " => " result))
      (is (str/includes? result fragment) (str line " => " result))
      (is (true? (:running? state)) line))))


(deftest the-output-drain-is-total-over-next
  (testing "a gap prints a loss notice and resumes at the recovery cursor"
    (let [output (handle 2)
          state (repl.frontends/create-state {:output-stream output})]
      (doseq [n (range 5)]
        (stream/append! output {:type :repl/output :op :print :text (str n)}))
      (let [[state' text] (repl/drain-output state)]
        (is (str/includes? text "output lost"))
        (is (str/includes? text "34"))
        (is (= :dao.stream/blocked (get-in state' [:ledger :output])))
        (let [[_ text'] (repl/drain-output state')]
          (is (= "" text') "the recovery cursor is retained, not re-read")))))
  (testing "a closed medium ends the drain and is recorded once"
    (let [output (handle 8)
          state (repl.frontends/create-state {:output-stream output})]
      (stream/append! output {:type :repl/output :op :print :text "x"})
      (stream/close! output)
      (let [[state' text] (repl/drain-output state)]
        (is (str/includes? text "x"))
        (is (= :dao.stream/end (get-in state' [:ledger :output])))))))


(deftest a-gap-does-not-cost-the-drain-an-element
  (let [output (handle repl/output-capacity)
        state (repl.frontends/create-state {:output-stream output})]
    (dotimes [_ repl/output-capacity]
      (stream/append! output {:type :repl/output :op :print :text "x"}))
    (stream/append! output {:type :repl/result :round [:r 1] :value 7})
    (let [[_ text results] (repl/drain-output state)]
      (is (str/includes? text "output lost"))
      (is (= [{:type :repl/result :round [:r 1] :value 7}] results)
          "the result behind a full ring of prints is still read"))))


(deftest a-full-output-ring-still-yields-the-result
  (doseq [vm-type [:ast-walker :semantic :stack :register]]
    (testing (str vm-type)
      (let [[state text]
            (evaluate
              (repl.frontends/create-state {:vm-type vm-type})
              [(str "(defn spam [n]"
                    " (if (= n 0) :done (do (print \"x\") (spam (- n 1)))))")
               (str "(spam " repl/output-capacity ")")])]
        (is (str/ends-with? text ":done")
            "the round's prints fill the ring, and its result follows")
        (is (= :done (:last-value state)))))))


(deftest only-the-current-rounds-result-is-accepted
  (let [output (handle 16)]
    (stream/append! output {:type :repl/result :round ["other" 1] :value 99})
    (let [[state text] (repl/eval-input
                         (repl.frontends/create-state {:output-stream output})
                         ":first")]
      (is (= ":first" text)
          "a preloaded result from another shell is not this round's")
      (stream/close! output)
      (let [[state' text'] (repl/eval-input
                             (assoc state :output-cursor (oldest output))
                             "(+ 40 2)")]
        (is (str/includes? text' repl/result-loss-text)
            "the drain re-reads round 1's result, which is not round 2's")
        (is (str/includes? text' "(append: closed)")
            "the refused append is surfaced, not discarded")
        (is (not (str/ends-with? text' ":first")))
        (is (= :first (:last-value state')))
        (is (nil? (:last-value-2 state'))
            "neither a stale nor a lost result enters the history")))))


(deftest de-bruijn-hash-is-canonical-over-the-loaded-segment
  (doseq [[vm-type canonical] [[:stack dcode/image-hash]
                               [:register rcode/register-hash]]]
    (testing (str vm-type)
      (let [state (repl.frontends/create-state {:vm-type vm-type})
            [one _] (repl/eval-input state "(def f (fn [x] (+ x 1)))")
            [two text] (repl/eval-input one "(f 1)")
            [three text'] (repl/eval-input two "(f 41)")]
        (is (= ["2" "42"] [text text'])
            "a closure from an earlier segment still resolves")
        (doseq [vm (map :vm [one two three])]
          (is (= (canonical (:segment vm)) (:hash vm))
              "every load's :hash is H (or R) over the whole :segment"))))))


(deftest de-bruijn-appends-go-through-attach-image
  (doseq [[vm-type size] [[:stack count]
                          [:register (comp count :instructions)]]]
    (testing (str vm-type)
      (let [[state text] (evaluate (repl.frontends/create-state {:vm-type vm-type})
                                   ["(def f (fn [x] (+ x 1)))"
                                    "(f 1)"
                                    "(f 1)"
                                    "(f 41)"])
            vm (:vm state)
            images (:images vm)]
        (is (= "42" text))
        (is (= 2 (:last-value-2 state))
            "an input whose image is already held reruns at its row")
        (is (= (map #(nth % 1) images)
               (reductions + 0 (map #(nth % 2) (butlast images))))
            "each attach appends one row at the held length")
        (is (= (size (:segment vm))
               (reduce + (map #(nth % 2) images)))
            "the rows tile the whole code space")
        (is (= 4 (count images))
            "the empty base row and one row per distinct image")))))


(deftest the-output-cursor-is-minted-not-fabricated
  (let [output (handle 8)
        state (repl.frontends/create-state {:output-stream output})]
    (is (= (oldest output) (:output-cursor state)))))


;; =============================================================================
;; Macro expansion between program-in and program-out (yin.vm.macro.md §10.1)
;; =============================================================================

(def ^:private unless-source
  "(defmacro unless [c a b] (yin/if c b a))")


(def ^:private twice-source
  "(defmacro twice [x] (yin/application (yin/variable (quote +)) (conj [] x x)))")


(defn- vm-state
  [vm-type]
  (first (repl/eval-input (repl.frontends/create-state) (str "(vm " vm-type ")"))))


(defn- results
  "Evaluate `lines` in order against one threaded state; `[state texts]`.
   The program media are shared by every state threaded from one session,
   so a state is never evaluated against twice."
  [state lines]
  (reduce (fn [[state texts] line]
            (let [[state' text] (repl/eval-input state line)]
              [state' (conj texts text)]))
          [state []]
          lines))


(deftest a-defmacro-expands-later-inputs-on-both-evaluators
  (doseq [vm-type [:semantic :ast-walker]]
    (testing (str vm-type)
      (let [[state [defined & texts]]
            (results (vm-state vm-type)
                     [unless-source
                      "(unless false 1 2)"
                      "(unless true 1 2)"
                      ;; a lambda parameter shadows the macro name
                      "((fn [unless] (unless 7)) (fn [x] x))"])]
        (is (str/includes? defined "unless")
            "the definition reaches the evaluator as the literal naming it")
        (is (= ["1" "2" "7"] texts))
        (is (= 7 (:last-value state)))))))


(deftest a-macro-defined-and-called-in-one-input-expands
  (let [[state [result]] (results (repl.frontends/create-state)
                                  [(str twice-source " (twice 21)")])]
    (is (= "42" result))
    (is (contains? (:macros (repl/repl-state state)) 'twice)
        "the declaration persists into the next batch's store")
    (is (= "10" (second (repl/eval-input state "(twice 5)"))))))


(deftest the-standard-forms-are-seeded-through-the-row-native-store
  (let [state (repl.frontends/create-state)]
    (is (= ['defn] (keys (:macros (repl/repl-state state))))
        "a fresh session's store holds the standard defn")
    (testing "an ordinary defn application is rewritten by the stored defn"
      ;; A map AST is the frontend-neutral carrier: no frontend lowering runs,
      ;; so only the expander's store can turn this call into a definition.
      (let [[state' _] (repl/eval-input
                         state
                         (str "{:type :application,"
                              " :operator {:type :variable, :name defn},"
                              " :operands [{:type :variable, :name sq}"
                              "            {:type :literal, :value [x]}"
                              "            {:type :application,"
                              "             :operator {:type :variable, :name *},"
                              "             :operands [{:type :variable, :name x}"
                              "                        {:type :variable, :name x}]}]}"))]
        (is (= "49" (second (repl/eval-input state' "(sq 7)"))))))))


(deftest repl-state-lists-macro-names-and-root-addresses
  (let [[state _] (evaluate (repl.frontends/create-state) [unless-source twice-source])
        macros (:macros (repl/repl-state state))
        store (get-in state [:expander :ctx :store])]
    (is (= #{'defn 'unless 'twice} (set (keys macros))))
    (doseq [[sym address] macros]
      (is (= (first (:yin.macro/tree (get store sym))) address)
          (str sym " maps to its lambda packet's root address")))
    (is (str/includes? (second (repl/eval-input state "(repl-state)")) "unless"))))


(deftest compile-renders-rows-expansion-and-events-without-committing
  (let [[state _] (repl/eval-input (repl.frontends/create-state) unless-source)
        attempt (get-in state [:expander :ctx :attempt])
        [state' [compiled defined failed]]
        (results state ["(compile (unless false 1 2))"
                        (str "(compile " unless-source ")")
                        "(compile (unless 1 2))"])]
    (is (str/includes? compiled ":yin.program/batch"))
    (is (str/includes? compiled "Expanded rows:"))
    (is (str/includes? compiled ":yin.macro/expand")
        "the expansion's event row is rendered")
    (is (= attempt (get-in state' [:expander :ctx :attempt]))
        "the preview commits no attempt")
    (testing "a defmacro renders its declaration and harvest rows"
      (is (str/includes? defined ":yin.macro/definition"))
      (is (str/includes? defined ":yin.macro/harvest")))
    (testing "a failing expansion renders the error and its event"
      (is (str/includes? failed "Expansion error:"))
      (is (str/includes? failed ":arity")))
    (is (= "1" (second (repl/eval-input state' "(unless false 1 2)")))
        "compiling appended nothing to program-in")))


(deftest reset-rebuilds-the-expander-with-only-the-standard-forms
  (let [[state _] (repl/eval-input (repl.frontends/create-state) unless-source)
        [state' [_ unbound added]] (results state ["(reset)"
                                                   "(unless false 1 2)"
                                                   "(+ 1 2)"])]
    (is (= ['defn] (keys (:macros (repl/repl-state state')))))
    (is (not= (get-in state [:expander :ctx :incarnation])
              (get-in state' [:expander :ctx :incarnation]))
        "a rebuilt expander is a new incarnation")
    (is (str/starts-with? unbound "Error: ")
        "the reset session no longer expands the old macro")
    (is (= "3" added) "an evaluator failure is consumed like any other")))


(deftest evaluation-continues-after-an-expansion-failure
  (doseq [vm-type [:semantic :ast-walker]]
    (testing (str vm-type)
      (let [[state _] (evaluate (vm-state vm-type)
                                [unless-source
                                 "(defmacro spin [] (yin/application (yin/variable (quote spin)) []))"
                                 "(+ 1 1)"])
            [state' failed] (repl/eval-input state "(unless 1 2)")
            [state'' [guarded added unless-again]]
            (results state' ["(spin)" "(+ 1 2)" "(unless false 1 2)"])]
        (is (str/starts-with? failed "Error: Macro expansion failed"))
        (is (str/includes? failed ":arity"))
        (is (= 2 (:last-value state')) "a failed expansion records no value")
        (is (= (get-in state [:expander :ctx :store])
               (get-in state' [:expander :ctx :store]))
            "a failed batch leaves the store as it was")
        (is (str/includes? guarded ":depth-guard"))
        (is (= "3" added))
        (is (= "1" unless-again) "the source cursor advanced past both failures")
        (is (= 1 (:last-value state'')))))))


(deftest a-clojure-macro-expands-calls-from-other-languages
  (let [[state _] (evaluate (repl.frontends/create-state) [unless-source twice-source
                                                 "(defn inc2 [x] (+ x 2))"])]
    (testing "Python"
      (let [[state' texts] (results state ["(lang :python)"
                                           "unless(False, 1, 2)"
                                           "twice(21)"
                                           "inc2(3)"])]
        (is (= ["1" "42" "5"] (rest texts)))
        (testing "then PHP, on the same session"
          (let [[_ texts] (results state' ["(lang :php)" "twice(21);" "inc2(3);"])]
            (is (= ["42" "5"] (rest texts)))))))))


(deftest a-host-function-renders-as-a-named-portable-marker
  (doseq [vm-type (keys repl/vm-constructors)]
    (testing (str vm-type)
      (let [[_ [plus _ q nested alias _ closure]]
            (results (repl.frontends/create-state {:vm-type vm-type})
                     ["+"
                      "(require (quote dao.space.query))"
                      "dao.space.query/q"
                      "(conj [] + (assoc {} :f -))"
                      "=="
                      "(defn inc [i] (+ i 1))"
                      "inc"])]
        (is (= "{:type :host-fn, :name '+}" plus))
        (is (= "{:type :host-fn, :name 'dao.space.query/q}" q))
        (is (= "[{:type :host-fn, :name '+} {:f {:type :host-fn, :name '-}}]"
               nested))
        (is (= "{:type :host-fn, :name '=}" alias)
            "an alias renders under its canonical name")
        (is (str/starts-with? closure "{:type :closure")
            "a data closure keeps its own rendering")
        (is (not-any? #(str/includes? % "#object[")
                      [plus q nested alias closure]))))))


(deftest a-typed-value-renders-in-one-key-order-on-every-host
  ;; kept under dao.pretty's 60-column ClojureDart budget, so every host
  ;; prints it on one line
  (is (= "{:type :closure, :entry 3, :params ['i], :segment -1}"
         (repl/format-value
           {:type :closure, :params ['i], :entry 3, :segment -1}))
      ":type first, then the other keys in printed order, whatever the
       host's map iteration order")
  (is (= "{:b {:type :x, :z 2}}"
         (repl/format-value (sorted-map :b {:z 2 :type :x})))
      "a typed map nested in an untyped one is ordered too")
  (is (= "{1 2}" (repl/format-value (sorted-map 1 2)))
      "a sorted map whose keys don't compare with :type still renders"))


(deftest a-host-function-without-a-known-name-renders-nameless
  (is (= "{:type :host-fn}" (repl/format-value inc)))
  (is (= "[{:type :host-fn} 1]" (repl/format-value [inc 1]))))


(deftest a-host-function-inside-a-quoted-form-renders-as-the-marker
  (is (= "'{:type :host-fn}" (repl/format-value (list 'quote inc))))
  (is (= "'[a {:type :host-fn} {:k '{:type :host-fn}}]"
         (repl/format-value (list 'quote ['a inc {:k (list 'quote dec)}])))
      "symbols inside the quote stay as they are; host functions at any
       depth, nested quotes included, become the marker"))


(deftest map-entries-whose-rendered-keys-coincide-all-render
  (is (= "{{:type :host-fn} 1, {:type :host-fn} 2}"
         (repl/format-value {inc 1, dec 2}))
      "two distinct nameless host functions as keys")
  (is (= "{:type :x, {:type :host-fn} 1, {:type :host-fn} 2}"
         (repl/format-value {:type :x, inc 1, dec 2}))
      "the same inside a typed map")
  (is (= "{'a 1, 'a 2}" (repl/format-value {'a 1, (list 'quote 'a) 2}))
      "a symbol and a quoted-symbol list render alike")
  (is (= "{:type :x, 'a 1, 'a 2}"
         (repl/format-value {:type :x, 'a 1, (list 'quote 'a) 2}))
      "the same inside a typed map"))
