(ns yin.repl.python-antlr-test
  "Slice F3 (docs/design/yang.antlr.md section 8.5.6): the Python ANTLR
   frontend at the REPL. State persists across submissions, the shell
   shows what a submission printed and not its value, a failed round
   keeps the session's runtime state as it was, a round parked on a
   require commits its frontend session only when it completes, and
   `(reset)` and `(vm ...)` start a new process.

   The parser is JVM-only, so the session scenarios run on every host
   through a binding whose parse stage answers the parser's packets,
   precompiled below (each def's docstring is the source the REPL hands
   the parser); on the JVM they run through the installed frontend too,
   and the packets are checked against the parser so the two cannot
   drift. The completeness probe is JVM-only, as the parser is."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [dao.jing.mem :as mem]
    [dao.stream :as stream]
    [dao.stream.ringbuffer :as ring]
    [yang.clojure :as yang.clojure]
    [yang.frontend :as frontend]
    [yang.python.antlr.c3-programs :refer [asg call e ex lit op st tc tx vr]]
    [yang.python.antlr.lower-portable-test :refer [packet]]
    [yang.python.antlr.repl :as python.repl]
    #?@(:cljd [] :clj [[yang.python.antlr.parser :as parser]
                       [yang.python.antlr.repl-probe :as probe]])
    [yin.repl :as repl]
    [yin.repl.frontends :as frontends]
    [yin.repl.link :as link]
    [yin.vm.linker.publish :as publish]))


;; =============================================================================
;; Precompiled packets
;; =============================================================================

(defn- module
  [& stmts]
  (packet (-> [:file_input] (into stmts) (conj ["EOF" "<EOF>"]))))


(def ^:private packets
  "The parser's packet for each line the session scenarios submit; the
   REPL hands the parser the line with a newline appended."
  {"x = 1" (module (st (asg (tc (vr "x")) (tc (lit "1"))))),
   "x = 5" (module (st (asg (tc (vr "x")) (tc (lit "5"))))),
   "print(x)" (module (st (ex (tc (call "print" (tc (vr "x"))))))),
   "1/0" (module (st (ex (tx (op (e (lit "1")) "/" (e (lit "0")))))))})


;; =============================================================================
;; Sessions
;; =============================================================================

(def ^:private cst-manifest
  {:yang.frontend/id :yang.python/cst,
   :yang.frontend/spi 1,
   :yang.frontend/revision "f3-test",
   :yang.frontend/language :python.cst,
   :yang.frontend/grammar {:yang.grammar/package "python3",
                           :yang.grammar/entries {:module "file_input"},
                           :yang.grammar/export-profile :yang.cst/v1},
   :yang.frontend/options-schema :segment/none,
   :yang.frontend/lowering-profile :segment/f3-test,
   :yang.frontend/runtime-profile :segment/f3-test,
   :yang.frontend/support-profile :segment/f3-test})


(defn- cst-catalog
  "The standard catalog plus the Python lowering behind a parse stage
   that answers the precompiled packets."
  []
  (frontend/install (frontends/standard)
                    cst-manifest
                    {:parse (fn [source] (get packets source)),
                     :lower python.repl/lower,
                     :lower-session python.repl/lower-session,
                     :display :output}))


(defn- session
  "A shell switched to `lang`, composed with `opts`."
  ([lang] (session lang {}))
  ([lang opts]
   (first (repl/eval-input
            (frontends/create-state (merge {:frontends (cst-catalog)} opts))
            (str "(lang " lang ")")))))


(def ^:private langs
  "The ids every session scenario runs under: the precompiled packets on
   every host, and the installed parser where it runs."
  #?(:cljd [:yang.python/cst]
     :clj [:yang.python/cst :yang.python/antlr]
     :cljs [:yang.python/cst]))


(defn- feed
  "Submit each line in order; every `[state text]` answered."
  [state lines]
  (rest (reductions (fn [[st _] line] (repl/eval-input st line))
                    [state nil]
                    lines)))


(defn- texts
  [state lines]
  (mapv second (feed state lines)))


;; =============================================================================
;; Session scenarios (every host)
;; =============================================================================

(deftest assignment-then-print-test
  (doseq [lang langs]
    (testing (str lang)
      (let [[[assigned t1] [_ t2]] (feed (session lang) ["x = 1" "print(x)"])]
        (is (= lang (:lang (session lang))))
        (is (= ["" "1\n"] [t1 t2]))
        (is (= :primed (:frontend-session assigned)))))))


(deftest an-exception-keeps-the-session-test
  (doseq [lang langs]
    (testing (str lang)
      (is (= ["" "ZeroDivisionError: division by zero\n" "5\n"]
             (texts (session lang) ["x = 5" "1/0" "print(x)"]))))))


(deftest reset-is-a-new-process-test
  (doseq [lang langs]
    (testing (str lang)
      (let [[_ before [reset-state _] after]
            (feed (session lang) ["x = 1" "print(x)" "(reset)" "print(x)"])]
        (is (= "1\n" (second before)))
        (is (nil? (:frontend-session reset-state)))
        (is (= "NameError: name 'x' is not defined\n" (second after)))))))


(deftest vm-rebuild-is-a-new-process-test
  (doseq [lang langs]
    (testing (str lang)
      (let [vm-type (first (remove #{(:vm-type (session lang))}
                                   [:semantic :register]))
            [_ [switched _] gone _ again]
            (feed (session lang)
                  ["x = 1" (str "(vm " vm-type ")") "print(x)" "x = 5"
                   "print(x)"])]
        (is (= vm-type (:vm-type switched)))
        (is (nil? (:frontend-session switched)))
        (testing "the new VM runs the prelude again and has no x"
          (is (= "NameError: name 'x' is not defined\n" (second gone))))
        (is (= "5\n" (second again)))))))


(deftest a-failed-first-round-keeps-the-runtime-uninitialized-test
  (doseq [lang langs]
    (testing (str lang)
      (let [calls (atom 0)
            report (fn [out exception]
                     (if (= 1 (swap! calls inc))
                       (throw (ex-info "report failed" {}))
                       (python.repl/report out exception)))
            [[failed failed-text] _ [_ text]]
            (feed (session lang {:primitives {'py-report report}})
                  ["x = 1" "x = 1" "print(x)"])]
        (testing "the failed round is reported and leaves no session marker"
          (is (str/includes? failed-text "report failed"))
          (is (nil? (:frontend-session failed))))
        (testing "the retry carries the prelude again and the session works"
          (is (= "1\n" text)))))))


(deftest a-failed-later-round-keeps-the-previous-marker-test
  (let [calls (atom 0)
        report (fn [out exception]
                 (if (= 2 (swap! calls inc))
                   (throw (ex-info "report failed" {}))
                   (python.repl/report out exception)))
        [_ [failed _] [_ text]]
        (feed (session :yang.python/cst {:primitives {'py-report report}})
              ["x = 1" "x = 5" "print(x)"])]
    (is (= :primed (:frontend-session failed)))
    (testing "the failed round's binding rolled back with its VM"
      (is (= "1\n" text)))))


;; =============================================================================
;; The staged session through a parked require (every host)
;; =============================================================================

(def ^:private counting-manifest
  (assoc cst-manifest
         :yang.frontend/id :yang.test/counting
         :yang.frontend/language :clojure.counting))


(defn- counting-session
  "A shell on a Clojure binding whose lowering counts its rounds in the
   frontend session, so the marker shows which round was committed."
  [opts]
  (let [catalog (frontend/install
                  (frontends/standard)
                  counting-manifest
                  {:input :forms,
                   :parse identity,
                   :lower #(yang.clojure/compile (first %)),
                   :lower-session (fn [forms n]
                                    [(yang.clojure/compile (first forms))
                                     (inc (or n 0))])})]
    (first (repl/eval-input
             (frontends/create-state (merge {:frontends catalog,
                                             :vm-type :stack}
                                            opts))
             "(lang :yang.test/counting)"))))


(def ^:private module-ast
  "`(yin/def f (fn [] 42))`."
  {:type :application,
   :operator {:type :variable, :name 'yin/def},
   :operands [{:type :literal, :value 'f}
              {:type :lambda, :params [], :body {:type :literal, :value 42}}]})


(defn- silent-client
  "A content-pair client on a wire nothing answers: a link over it stays
   pending."
  []
  (let [medium #(-> {:dao.stream/type ring/transport-type,
                     ring/capacity-key 64}
                    ring/create!
                    :dao.stream/handle)
        responses (medium)]
    {:requests (medium),
     :answers responses,
     :cursor (:dao.stream/cursor
               (stream/cursor responses stream/anchor-newest))}))


(defn- parked
  "A counting shell that committed one round, then typed a require of
   `mod` that parks: nothing answers its link."
  [opts]
  (let [[primed _] (repl/eval-input (counting-session opts) "(+ 1 2)")]
    (first (repl/eval-input primed "(require (quote mod))"))))


(deftest a-parked-round-stages-its-session-test
  (let [state (parked {})]
    (is (some? (:pending-run state)))
    (testing "the parked round's session rides on the run, uncommitted"
      (is (= 1 (:frontend-session state)))
      (is (= 2 (get-in state [:pending-run :frontend :session]))))))


(defn- published
  "`[store address]`: a store holding `mod`, published at `address`."
  []
  (let [store (mem/create-content-mem)]
    [store
     (:address (publish/publish-module!
                 store
                 {:name 'mod, :ast module-ast, :exports #{'f},
                  :requires {}, :primitives {}}))]))


(deftest a-parked-round-commits-its-session-on-completion-test
  (let [[store address] (published)
        state (parked {:content-client (silent-client),
                       :name-env {'mod address}})
        [done _] (repl/recheck-pending
                   (assoc state
                          :link-source (link/composition
                                         {:content-store store,
                                          :name-env {'mod address}})))]
    (is (some? (:pending-run state)))
    (is (nil? (:pending-run done)))
    (is (= 'mod (:last-value done)))
    (is (= 2 (:frontend-session done)))))


(deftest a-refused-parked-round-keeps-the-previous-session-test
  (let [state (parked {:content-client (silent-client),
                       :name-env {'mod (second (published))}})
        [refused text] (repl/recheck-pending
                         (assoc state
                                :link-source (link/composition
                                               {:content-store
                                                (mem/create-content-mem),
                                                :name-env {}})))]
    (is (some? (:pending-run state)))
    (is (nil? (:pending-run refused)))
    (is (str/includes? text "absent"))
    (is (= 1 (:frontend-session refused)))))


(deftest an-abandoned-parked-round-keeps-the-previous-session-test
  (let [[freed text] (repl/eval-input (parked {}) "(abandon)")]
    (is (str/includes? text "abandoned"))
    (is (nil? (:pending-run freed)))
    (is (= 1 (:frontend-session freed)))
    (testing "the next round lowers against the kept session"
      (is (= 2 (:frontend-session
                 (first (repl/eval-input freed "(+ 1 2)"))))))))


(deftest other-frontends-still-echo-their-value-test
  (let [state (frontends/create-state)
        [python _] (repl/eval-input state "(lang :yang.python/legacy)")]
    (is (= "3" (second (repl/eval-input state "(+ 1 2)"))))
    (is (= "3" (second (repl/eval-input python "1 + 2"))))))


;; =============================================================================
;; The parser and the probe (JVM)
;; =============================================================================

#?(:cljd nil
   :clj
   (defn- node-shape
     [node]
     (select-keys node [:id :kind :type :text :rule :children])))


#?(:cljd nil
   :clj
   (deftest precompiled-packets-are-the-parsers-test
     (doseq [[source p] packets]
       (is (= (map node-shape
                   (:yang.cst/nodes (parser/parse-source (str source "\n"))))
              (map node-shape (:yang.cst/nodes p)))
           source))))


#?(:cljd nil
   :clj
   (deftest probe-buffers-an-open-block-test
     (let [[[s1 t1] [s2 t2] [s3 t3] [_ t4]]
           (feed (session :yang.python/antlr)
                 ["def f(n):" "    return n + 1" "" "print(f(2))"])]
       (testing "the header and body are buffered"
         (is (= "" t1))
         (is (= "def f(n):" (:pending-input s1)))
         (is (= "" t2))
         (is (some? (:pending-input s2))))
       (testing "a blank line closes the block"
         (is (nil? (:pending-input s3)))
         (is (= "" t3)))
       (testing "the function is callable afterwards"
         (is (= "3\n" t4))))))


#?(:cljd nil
   :clj
   (deftest probe-buffers-across-lines-test
     (testing "an open bracket"
       (is (= ["" "(1, 2)\n"]
              (texts (session :yang.python/antlr) ["print((1," "2))"]))))
     (testing "a blank line inside brackets does not submit"
       (let [[[s1 t1] [s2 t2] [s3 t3]]
             (feed (session :yang.python/antlr) ["print(" "" "1)"])]
         (is (= ["" "" "1\n"] [t1 t2 t3]))
         (is (= "print(\n" (:pending-input s2)))
         (is (nil? (:pending-input s3)))
         (is (some? (:pending-input s1)))))
     (testing "an open triple string, brackets inside it"
       (is (= ["" "" "hello\n(\n\n"]
              (texts (session :yang.python/antlr)
                     ["s = \"\"\"hello" "(" "\"\"\"; print(s)"]))))
     (testing "explicit backslash continuation"
       (is (= ["" "" "3\n"]
              (texts (session :yang.python/antlr)
                     ["x = 1 \\" "+ 2" "print(x)"]))))))


#?(:cljd nil
   :clj
   (deftest probed-lines-keep-their-whitespace-test
     (testing "trailing spaces and tabs inside a triple string are kept"
       (is (= ["" "" "" "a  \t\n  \n\tb \n"]
              (texts (session :yang.python/antlr)
                     ["s = \"\"\"a  \t" "  " "\tb \"\"\"" "print(s)"]))))
     (testing "a short string ending in a backslash and spaces is broken"
       (is (= :complete (probe/probe "s = 'a\\  ")))
       (let [[[s t] [_ t']] (feed (session :yang.python/antlr)
                                  ["s = 'a\\  " "print(2)"])]
         (is (nil? (:pending-input s)))
         (is (str/includes? t "SyntaxError"))
         (is (= "2\n" t'))))))


#?(:cljd nil
   :clj
   (deftest probe-reports-irreparable-errors-at-once-test
     (doseq [bad ["x = (1 + * 2" "foo(1 2" "x = )" "x = (]" "x =" "1 +"
                  "lambda x:" "s = 'abc"
                  ;; an error before a string still open
                  "x = 1 \"\"\"abc" "x = = \"\"\"" "foo(1 2 '''"
                  "x = (1 + * 2 'a\\"]]
       (testing bad
         (is (= :complete (probe/probe bad)))
         (let [[[s t] [_ t']] (feed (session :yang.python/antlr)
                                    [bad "print(2)"])]
           (is (nil? (:pending-input s)))
           (is (str/includes? t "SyntaxError"))
           (testing "and the next submission evaluates"
             (is (= "2\n" t'))))))))


#?(:cljd nil
   :clj
   (deftest probe-classifies-test
     (doseq [[text expected]
             [["x = 1" :complete]
              ["print(" :incomplete]
              ["print(\n" :incomplete]
              ["print((1," :incomplete]
              ["d = {'a': [1, 2" :incomplete]
              ["if x:\n  y = {1: (2" :incomplete]
              ["f(a for a in b" :incomplete]
              ["x = (1 if y else" :incomplete]
              ["def f(n):" :incomplete]
              ["def f(n): # body next" :incomplete]
              ["@dec" :incomplete]
              ["def f(n):\n    return n" :incomplete]
              ["def f(n):\n    return n\n" :complete]
              ["s = \"\"\"hello" :incomplete]
              ["s = \"\"\"a\n  b\"\"\"" :complete]
              ["s = \"\"\"(\"\"\"" :complete]
              ["s = '(' # )" :complete]
              ["s = 'it\\'s ('" :complete]
              ["s = 'abc\\" :incomplete]
              ["s = 'abc\\  " :complete]
              ["foo(\"\"\"a" :incomplete]
              ["if x:\n    s = '''a" :incomplete]
              ;; `x = *"""a""",` is a valid star expression
              ["x = * \"\"\"" :incomplete]
              ["x = 1 \\" :incomplete]
              ["x = 1 \\\n+ 2" :complete]
              ["x = 1 # \\" :complete]
              ["x = (1 + * 2" :complete]
              ["x = )" :complete]]]
       (is (= expected (probe/probe text)) (pr-str text)))))
