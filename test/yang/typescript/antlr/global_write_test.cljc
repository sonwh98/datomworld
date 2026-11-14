(ns yang.typescript.antlr.global-write-test
  "T2 — assignment to a name no lexical binding reaches (design
   yang.typescript.md §3.1.4 ruling 4 and §3.7). Strict mode is always on, so
   the write is a strict object-environment SetMutableBinding over the heap
   global object (ES2022 §9.1.1.3.3), which separates three cases earlier
   rounds collapsed into one blanket refusal:

   - nothing binds the name and the global object has no such property: a
     catchable ReferenceError NAMING it, never an implicit global;
   - an existing WRITABLE global property: the write SUCCEEDS — strict mode
     forbids creating an implicit global, not updating one already there, so
     `globalThis.saved = null; saved = 7;` holds at module goal too;
   - an existing NON-WRITABLE global property (`undefined`): a catchable
     TypeError, not a ReferenceError.

   The accessor fixture pins the fourth fact: assignment resolves the
   reference without getting its value, so the setter runs and the getter
   stays silent. A retained `js/global-get` read would log `getter` first.

   Portable (JVM/Node/Dart): every fixture is a hand-built CST packet in the
   `lower-portable-test` idiom, so no JVM-only parser is involved, and each
   program runs on all four evaluators. §3.7 script-goal DECLARATION
   placement — a top-level `var` becoming a property of the global object —
   stays the design-specified deferred slice and is not pinned here."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.typescript.antlr.lower :as lower]
    [yang.typescript.antlr.lower-portable-test :as portable]
    [yang.typescript.antlr.prelude-parity-test :as parity]
    [yang.typescript.antlr.render :as render]))


;; =============================================================================
;; CST forms, hand-built in the parser's own shapes
;; =============================================================================

(defn- member-form
  "`obj.prop` as a singleExpression form."
  [obj prop]
  [:singleExpression
   (portable/name-expr obj)
   ["Dot" "."]
   [:identifierName [:identifier ["Identifier" prop]]]])


(defn- str-form
  "`'text'` as a singleExpression form."
  [text]
  [:singleExpression [:literal ["StringLiteral" (str "'" text "'")]]])


(defn- null-form
  "`null` as a singleExpression form."
  []
  [:singleExpression [:literal ["NullLiteral" "null"]]])


(defn- assign-form
  "`lhs = rhs` as a singleExpression form."
  [lhs rhs]
  [:singleExpression lhs ["Assign" "="] rhs])


(defn- strict-eq-form
  "`a === b` as a singleExpression form."
  [a b]
  [:singleExpression a ["IdentityEquals" "==="] b])


(defn- call-form
  "`callee(arg ...)` as a singleExpression form."
  [callee & args]
  [:singleExpression
   callee
   [:arguments
    ["OpenParen" "("]
    (into [:argumentList] (map (fn [a] [:argument a]) args))
    ["CloseParen" ")"]]])


(defn- object-form
  "`{a: x, b: y}` as a singleExpression form, over `[name value-form]` pairs."
  [& pairs]
  [:singleExpression
   (vec (concat [:objectLiteral ["OpenBrace" "{"]]
                (map (fn [[nm v]]
                       [:propertyAssignment
                        [:propertyName
                         [:identifierName [:identifier ["Identifier" nm]]]]
                        ["Colon" ":"]
                        v])
                     pairs)
                [["CloseBrace" "}"]]))])


(defn- expr-stmt
  "One expression statement over a singleExpression form."
  [expr]
  [:expressionStatement [:expressionSequence expr] ["SemiColon" ";"]])


(defn- return-stmt
  "`return expr;` over a singleExpression form."
  [expr]
  [:returnStatement
   ["Return" "return"]
   [:expressionSequence expr]
   [:eos ["SemiColon" ";"]]])


(defn- block-form
  "`{ stmt ... }` as a block form, over statement forms."
  [& stmts]
  (vec (concat [:block ["OpenBrace" "{"]]
               [(into [:statementList] (map (fn [s] [:statement s]) stmts))]
               [["CloseBrace" "}"]])))


(defn- log-stmt
  "`console.log(arg ...)` over singleExpression forms."
  [& args]
  (expr-stmt (apply call-form (member-form "console" "log") args)))


(defn- arrow-form
  "`() => { body }` as a singleExpression form, over statement forms."
  [& body]
  [:singleExpression
   [:anonymousFunction
    [:arrowFunctionDeclaration
     [:arrowFunctionParameters ["OpenParen" "("] ["CloseParen" ")"]]
     ["ARROW" "=>"]
     [:arrowFunctionBody
      ["OpenBrace" "{"]
      [:functionBody
       (into [:sourceElements]
             (map (fn [s] [:sourceElement [:statement s]]) body))]
      ["CloseBrace" "}"]]]]])


(defn- try-catch-stmt
  "`try { body } catch (ident) { handler }` over statement forms."
  [body ident handler]
  [:tryStatement
   ["Try" "try"]
   (apply block-form body)
   [:catchProduction
    ["Catch" "catch"]
    ["OpenParen" "("]
    [:identifier ["Identifier" ident]]
    ["CloseParen" ")"]
    (apply block-form handler)]])


;; =============================================================================
;; The four programs
;; =============================================================================

(def ^:private absent-name
  "`try { undeclaredProbe = 7; } catch (e) { console.log(e.name);
    console.log(e.message); }` — the guest catches the failure itself, so the
   pin reads both the class and the promised naming message off the error."
  (portable/program
    (try-catch-stmt
      [(expr-stmt (assign-form (portable/name-expr "undeclaredProbe")
                               (portable/num-expr "7")))]
      "e"
      [(log-stmt (member-form "e" "name"))
       (log-stmt (member-form "e" "message"))])))


(def ^:private writable-global
  "`globalThis.saved = null; saved = 7; console.log(globalThis.saved === 7);`
   — the member write creates a writable global property, and the bare write
   to the same name updates it instead of refusing."
  (portable/program
    (expr-stmt (assign-form (member-form "globalThis" "saved") (null-form)))
    (expr-stmt (assign-form (portable/name-expr "saved")
                            (portable/num-expr "7")))
    (log-stmt (strict-eq-form (member-form "globalThis" "saved")
                              (portable/num-expr "7")))))


(def ^:private non-writable-global
  "`try { undefined = 7; } catch (e) { console.log(e.name); }` — the prelude
   installs `undefined` as a non-writable global property, so the write is a
   failed [[Set]], which strict mode reports as a TypeError."
  (portable/program
    (try-catch-stmt
      [(expr-stmt (assign-form (portable/name-expr "undefined")
                               (portable/num-expr "7")))]
      "e"
      [(log-stmt (member-form "e" "name"))])))


(def ^:private accessor-global
  "`Object.defineProperty(globalThis, 'acc', {get: () => { console.log('getter');
    return 1; }, set: () => { console.log('setter'); }}); acc = 5;` — the
   getter logging at all would be the retained value read the
   SetMutableBinding contract forbids."
  (portable/program
    (expr-stmt
      (call-form (member-form "Object" "defineProperty")
                 (portable/name-expr "globalThis")
                 (str-form "acc")
                 (object-form
                   ["get" (arrow-form (log-stmt (str-form "getter"))
                                      (return-stmt (portable/num-expr "1")))]
                   ["set" (arrow-form (log-stmt (str-form "setter")))])))
    (expr-stmt (assign-form (portable/name-expr "acc")
                            (portable/num-expr "5")))))


(defn- check-program
  "The rendered output of one hand-built packet on all four evaluators."
  [packet expected label]
  (testing label
    (let [ast (lower/lower-packet packet)]
      (doseq [[backend run] parity/runners]
        (is (= expected (render/output (run ast)))
            (str label " — " backend))))))


(deftest absent-name-is-a-reference-error-naming-it-test
  (check-program
    absent-name
    {:js/out ["ReferenceError" "undeclaredProbe is not defined"],
     :js/uncaught nil}
    "a binding-less name the global object does not hold either: a catchable
     ReferenceError whose message names the variable, and nothing escapes the
     guest's own catch"))


(deftest existing-writable-global-assignment-succeeds-test
  (check-program
    writable-global
    {:js/out ["true"], :js/uncaught nil}
    "an existing writable global property: the bare assignment writes it, so
     `globalThis.saved === 7` after `globalThis.saved = null; saved = 7;`"))


(deftest non-writable-global-assignment-is-a-type-error-test
  (check-program
    non-writable-global
    {:js/out ["TypeError"], :js/uncaught nil}
    "an existing NON-writable global property (`undefined`): a catchable
     TypeError, not the ReferenceError an absent name gets"))


(deftest global-accessor-setter-runs-and-getter-does-not-test
  (check-program
    accessor-global
    {:js/out ["setter"], :js/uncaught nil}
    "a global accessor: assignment invokes the setter once and never reads
     through the getter"))
