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
        (is (= 1 (count (typed pk "CloseBrace"))))))))


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
