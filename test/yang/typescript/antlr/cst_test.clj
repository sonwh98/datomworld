(ns yang.typescript.antlr.cst-test
  "T0: TypeScript CST export under the ts-g4-1 patch set. One fixture per
   advertised construct; each asserts the shape of the intended rule, because
   the upstream grammar parses much of this input as plain variable
   statements."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.antlr.packet :as packet]
    [yang.typescript.antlr.cst :as cst])
  (:import
    (java.nio.charset
      StandardCharsets)))


(def ts-worker (cst/make-worker :ts))


(def js-worker (cst/make-worker :js))


(defn- tokens
  [pk]
  (filterv #(= :token (:kind %)) (:yang.cst/nodes pk)))


(defn- real-tokens
  [pk]
  (remove :synthetic (tokens pk)))


(defn- parse
  "The validated ok packet for `src` under `worker`, whose real tokens are in
   source order and whose every span is a byte range reading back as exactly
   the token's text."
  ([src] (parse ts-worker src))
  ([worker src]
   (let [pk (cst/parse-source worker [:unit 0] src)
         bs (.getBytes ^String src StandardCharsets/UTF_8)]
     (is (= :yang.cst/ok (:yang.cst/outcome pk)) (pr-str src))
     (is (some? (packet/validate! pk)))
     (doseq [{:keys [text span type]} (real-tokens pk)
             :let [[a b] span]]
       (is (= text (String. bs (int a) (int (- b a)) StandardCharsets/UTF_8))
           (str type " span " span)))
     (is (apply <= 0 (mapcat :span (real-tokens pk))) "spans ascend")
     (is (= [(count bs) (count bs)] (:span (peek (tokens pk)))) "EOF")
     pk)))


(defn- shapes
  "Every `rule` node of `pk` as its child shape: a child rule is its rule
   name, a child token is its text."
  [pk rule]
  (let [by (into {} (map (juxt :id identity)) (:yang.cst/nodes pk))]
    (vec (for [n (:yang.cst/nodes pk)
               :when (= rule (:rule n))]
           (mapv #(let [c (by %)]
                    (if (= :token (:kind c)) (:text c) (:rule c)))
                 (:children n))))))


(defn- typed
  [pk type]
  (filterv #(= type (:type %)) (tokens pk)))


(defn- rule?
  [pk rule]
  (boolean (some #(= rule (:rule %)) (:yang.cst/nodes pk))))


(deftest byte-spans-test
  (let [src "const s = \"héllo 世\"; // é\nlet n = 1"
        pk (parse src)
        bs (.getBytes ^String src StandardCharsets/UTF_8)]
    (is (< (count bs) (* 4 (count src))))
    (is (> (count bs) (count src)) "the source has multi-byte characters")
    (is (= "\"héllo 世\""
           (:text (first (typed pk "StringLiteral")))))
    (is (= [10 22] (:span (first (typed pk "StringLiteral")))) "byte offsets")))


(deftest conditional-and-infer-test
  (let [pk (parse "type X<T> = T extends string ? 1 : 2")]
    (is (= [["unionOrIntersectionOrPrimaryType" "extends"
             "unionOrIntersectionOrPrimaryType" "?" "type_" ":" "type_"]]
           (shapes pk "conditionalType")))
    (is (rule? pk "typeAliasDeclaration")))
  (let [pk (parse "type U<T> = T extends Array<infer E> ? E : never")]
    (is (= [["infer" "identifier"]]
           (filterv #(= "infer" (first %)) (shapes pk "primaryType")))))
  (let [pk (parse "type R<T> = T extends (...a: any[]) => infer R ? R : never")]
    (is (= ["unionOrIntersectionOrPrimaryType" "extends" "functionType" "?"
            "type_" ":" "type_"]
           (first (shapes pk "conditionalType"))))))


(deftest import-export-type-test
  (testing "import type"
    (let [pk (parse "import type {X} from \"x\"")]
      (is (= [["import" "type" "importFromBlock"]]
             (shapes pk "importStatement")))))
  (testing "inline type specifiers"
    (let [pk (parse "import {type X, y as z} from \"x\"")]
      (is (= [["type" "moduleExportName"]
              ["moduleExportName" "as" "importedBinding"]]
             (shapes pk "importAliasName")))))
  (testing "export type is an export statement"
    (let [pk (parse "export type {X} from \"x\"")]
      (is (= "type" (first (first (shapes pk "exportFromBlock")))))
      (is (rule? pk "exportStatement")))
    (let [pk (parse "export {type X, y}")]
      (is (= [["type" "moduleExportName"] ["moduleExportName"]]
             (shapes pk "exportAliasName")))))
  (testing "a default import named type is not a type import"
    (is (= [["import" "importFromBlock"]]
           (shapes (parse "import type from \"x\"") "importStatement"))))
  (testing "ordinary exports keep their export statement"
    (let [pk (parse "export const a = 1;\nexport function f() {}\nexport default class {}")]
      (is (= 3 (count (shapes pk "exportStatement")))))))


(deftest satisfies-test
  (let [pk (parse "x satisfies Y")]
    (is (some #{["singleExpression" "satisfies" "type_"]}
              (shapes pk "singleExpression")))
    (is (rule? pk "expressionStatement"))))


(deftest mapped-type-test
  (testing "optional mapped member"
    (let [pk (parse "let m: { [K in keyof T]?: T[K] }")]
      (is (= [["{" "[" "identifier" "in" "type_" "]" "mappedOptional"
               "typeAnnotation" "}"]]
             (shapes pk "mappedType")))
      (is (= [["?"]] (shapes pk "mappedOptional")))))
  (testing "as clause with -readonly and -?"
    (let [pk (parse
               "type M<T> = { -readonly [K in keyof T as Foo<K>]-?: T[K] }")]
      (is (= [["{" "mappedReadOnly" "[" "identifier" "in" "type_" "as" "type_"
               "]" "mappedOptional" "typeAnnotation" "}"]]
             (shapes pk "mappedType")))
      (is (= [["-" "readonly"]] (shapes pk "mappedReadOnly")))
      (is (= [["-" "?"]] (shapes pk "mappedOptional")))))
  (testing "an index signature is still an object type"
    (let [pk (parse "let o: { [k: string]: number }")]
      (is (rule? pk "objectType"))
      (is (not (rule? pk "mappedType"))))))


(deftest template-literal-test
  (testing "template-literal type"
    (let [pk (parse "type T = `a${string}b${number}`")]
      (is (= [["`" "templateLiteralTypeAtom" "templateLiteralTypeAtom"
               "templateLiteralTypeAtom" "templateLiteralTypeAtom" "`"]]
             (shapes pk "templateLiteralType")))
      (is (= ["`" "`"] (mapv :text (typed pk "BackTick"))))))
  (testing "template string expression, nested template depth"
    (let [pk (parse "const s = `a${`b${c}d`}e`; const t = {}")]
      (is (= 4 (count (typed pk "BackTick"))))
      (is (= 2 (count (typed pk "TemplateStringStartExpression"))))
      (is (= 2 (count (typed pk "TemplateCloseBrace"))))
      (is (= 2 (count (shapes pk "templateStringLiteral"))))
      (testing "a brace after the template is a plain brace"
        (is (= 1 (count (typed pk "OpenBrace"))) "the object literal")
        (is (= 1 (count (typed pk "CloseBrace")))))))
  (testing "a template inside an interpolated object literal"
    (let [pk (parse "const s = `${{a: `x${y}`}.a}`;")]
      (is (= 4 (count (typed pk "BackTick"))))
      (is (= 2 (count (typed pk "TemplateStringStartExpression"))))
      (is (= 2 (count (typed pk "TemplateCloseBrace"))))
      (is (= 1 (count (typed pk "OpenBrace"))))
      (is (= 1 (count (typed pk "CloseBrace"))))
      (is (= 1 (count (shapes pk "objectLiteral"))))))
  (testing "a template inside a plain block"
    (let [pk (parse "{ `${a}` }")]
      (is (= 1 (count (typed pk "CloseBrace"))))
      (is (= 1 (count (typed pk "TemplateCloseBrace"))))))
  (testing "a template inside a function body inside an interpolation"
    (let [pk (parse "`${f(() => { return `${x}`; })}`")]
      (is (= 2 (count (typed pk "TemplateCloseBrace"))))
      (is (= 1 (count (typed pk "CloseBrace")))))))


(deftest typeof-and-indexed-access-test
  (let [pk (parse "let v: typeof w")]
    (is (= [["typeof" "typeQueryExpression"]] (shapes pk "typeQuery"))))
  (testing "indexed access is its own alternative, T[] stays an array type"
    (let [pk (parse "let k: T[K][]")
          ps (shapes pk "primaryType")]
      (is (some #{["primaryType" "[" "type_" "]"]} ps))
      (is (some #{["primaryType" "[" "]"]} ps)))))


(deftest dialect-generic-call-test
  (testing ":ts reads a<b>(c) as a call with type arguments"
    (let [pk (parse ts-worker "a<b>(c)")]
      (is (some #{["singleExpression" "typeArguments" "arguments"]}
                (shapes pk "singleExpression")))
      (is (not-any? #(some #{"<"} %) (shapes pk "singleExpression"))
          "no relational operator")))
  (testing ":js reads it as relational"
    (let [pk (parse js-worker "a<b>(c)")]
      (is (not (rule? pk "typeArguments")))
      (is (some #{["singleExpression" "<" "singleExpression"]}
                (shapes pk "singleExpression")))))
  (testing "a member call with type arguments"
    (is (some #{["singleExpression" "typeArguments" "arguments"]}
              (shapes (parse ts-worker "a.b<c>(d)") "singleExpression"))))
  (testing "relational chains stay relational in :ts"
    (let [pk (parse ts-worker "if (a < b && c > (d)) {}")]
      (is (not (rule? pk "typeArguments")))
      (is (= ["<" ">"]
             (mapv :text (concat (typed pk "LessThan")
                                 (typed pk "MoreThan"))))))))


;; The generic-call predicate scopes its abort set to nesting level 0, so
;; object, tuple and function types are opaque to the scan.
(deftest generic-call-nested-type-arguments-test
  (doseq [src ["f<{a: string; b: number}>(x)"
               "f<{ m(): void; [k: string]: number }>(x)"
               "g<[number, string]>(y)"]]
    (testing src
      (let [pk (parse ts-worker src)]
        (is (= 1 (count (shapes pk "expressionStatement"))))
        (is (some #{["singleExpression" "typeArguments" "arguments"]}
                  (shapes pk "singleExpression")))
        (is (not-any? #(some #{"<"} %) (shapes pk "singleExpression"))))))
  (testing ":js stays relational across statements"
    (let [pk (parse js-worker "a < b; c > (d)")]
      (is (= 2 (count (shapes pk "expressionStatement"))))
      (is (not (rule? pk "typeArguments"))))))


(deftest identifier-expression-tail-test
  (testing "binary arithmetic is not an identifier followed by an expression"
    (doseq [[src op] [["a + b" "+"] ["a - b" "-"]]]
      (testing src
        (let [ss (shapes (parse src) "singleExpression")]
          (is (some #{["singleExpression" op "singleExpression"]} ss))
          (is (not-any? #{["identifierName" "singleExpression"]} ss))))))
  (testing "precedence"
    (let [ss (shapes (parse "a + b * c") "singleExpression")]
      (is (some #{["singleExpression" "+" "singleExpression"]} ss))
      (is (some #{["singleExpression" "*" "singleExpression"]} ss))
      (is (not-any? #{["identifierName" "singleExpression"]} ss))))
  (testing "a newline separates statements"
    (is (= 2 (count (shapes (parse "x\ny") "expressionStatement"))))))


(deftest asi-test
  (testing "return ends at the newline"
    (let [pk (parse "return\nx")]
      (is (= [["return" "eos"]] (shapes pk "returnStatement")))
      (is (= 1 (count (shapes pk "expressionStatement"))))))
  (testing "throw forbids a newline before its operand"
    (is (= :yang.cst/syntax-error
           (:yang.cst/outcome
             (cst/parse-source ts-worker [:unit 0] "throw\nnew Error()"))))))


(deftest regex-versus-division-test
  (let [pk (parse "x = a / b / c; y = /re/g.test(z)")]
    (is (= ["/" "/"] (mapv :text (typed pk "Divide"))))
    (is (= ["/re/g"] (mapv :text (typed pk "RegularExpressionLiteral"))))))


(deftest error-packet-test
  (let [pk (cst/parse-source ts-worker [:unit 0] "type X = ;")]
    (is (= :yang.cst/syntax-error (:yang.cst/outcome pk)))
    (is (seq (:yang.cst/errors pk)))
    (is (every? :span (:yang.cst/errors pk)))))


(deftest worker-reuse-test
  (testing "one worker serves many parses with the same result"
    (let [a (shapes (parse "x satisfies Y") "singleExpression")
          b (shapes (parse "x satisfies Y") "singleExpression")]
      (is (= a b)))))


;; ts-g4-2 (T3): class grammar fixtures, one per rule (a)-(f) plus new.target.

(deftest static-block-test
  (let [pk (parse "class A { static {} static { x = 1; } }")]
    (is (= [["static" "{" "}"] ["static" "{" "statementList" "}"]]
           (shapes pk "staticBlock")))
    (is (empty? (shapes pk "propertyMemberDeclaration")))))


(deftest class-heritage-test
  (testing "expression heritage"
    (doseq [src ["class B extends null {}" "class B extends mixin(A) {}"
                 "class B extends (f(), A) {}" "class B extends a.b.C {}"]]
      (is (= [["extends" "singleExpression"]]
             (shapes (parse src) "classExtendsClause"))
          src)))
  (testing "generic heritage keeps typeGeneric for the :T8 refusal"
    (doseq [src ["class B extends A<T> {}" "class B extends A<T> implements I {}"]]
      (is (= [["extends" "singleExpression" "typeGeneric"]]
             (shapes (parse src) "classExtendsClause"))
          src))))


(deftest class-field-shape-test
  (testing "public and private fields, with and without semicolons"
    (doseq [src ["class A { x = 1; #y = 2; z; }"
                 "class A { x = 1\n #y = 2\n z }"
                 "class A { x = 1; #y; z }"]]
      (let [pk (parse src)
            fields (shapes pk "propertyMemberDeclaration")]
        (is (= 3 (count fields)) src)
        (is (every? #(some #{"eos"} %) fields) src)
        (is (empty? (shapes pk "expressionStatement")) src)))
    (is (= [["propertyMemberBase" "classElementName" "initializer" "eos"]]
           (shapes (parse "class A { static #s = 1; }")
                   "propertyMemberDeclaration"))))
  (testing "annotated fields still carry typeAnnotation"
    (is (= [["propertyMemberBase" "classElementName" "typeAnnotation" "eos"]]
           (shapes (parse "class A { x: number; }")
                   "propertyMemberDeclaration")))))


(deftest class-member-shape-test
  (testing "methods and accessors, public and private, are not fields"
    (doseq [[src n] [["class A { m(o) { return o; } }" 1]
                     ["class A { #m(o) {} }" 1]
                     ["class A { get a() { return 1; } set a(v) {} }" 2]
                     ["class A { get #g() { return 1; } static set #g(v) {} }" 2]]]
      (is (= n (count (shapes (parse src) "propertyMemberDeclaration"))) src)
      (is (empty? (shapes (parse src) "expressionStatement")) src))
    (is (= [["propertyMemberBase" "getAccessor"]]
           (shapes (parse "class A { get a() {} }")
                   "propertyMemberDeclaration")))
    (is (= [["propertyMemberBase" "classElementName" "callSignature" "{"
             "functionBody" "}"]]
           (shapes (parse "class A { #m() {} }") "propertyMemberDeclaration")))))


(deftest private-brand-check-test
  (doseq [src ["#x in o;" "class A { #x; m(o) { return #x in o; } }"
               "class A { #x; m(o) { return (#x in o) && o; } }"]]
    (is (some #{["privateIdentifier" "in" "singleExpression"]}
              (shapes (parse src) "singleExpression"))
        src)))


(deftest new-target-test
  (is (= [["new" "." "target"]]
         (shapes (parse "function f() { return new.target; }")
                 "singleExpression"))))


(deftest new-operand-test
  (testing "new binds a member expression, then its arguments (:G-new)"
    (let [ss (shapes (parse "new X(a).m();") "singleExpression")]
      (is (some #{["new" "newOperand" "arguments"]} ss))
      (is (some #{["singleExpression" "." "identifierName"]} ss)
          "the member is read off the new-expression"))
    (is (= [["newOperand" "." "identifierName"] ["identifierName"]]
           (shapes (parse "new a.b;") "newOperand")))
    (is (some #{["new" "newOperand" "arguments"]}
              (shapes (parse "new new X()();") "newOperand")))
    (is (some #{["new" "newOperand" "typeArguments" "arguments"]}
              (shapes (parse "new X<T>(1);") "singleExpression")))))


(deftest is-property-name-test
  (testing "`is` is a property name (:G-is)"
    (is (= [["is"]] (shapes (parse "Object.is(a, b);") "keyword")))
    (is (= [["is"]] (shapes (parse "x = { is: 1 };") "keyword")))))
