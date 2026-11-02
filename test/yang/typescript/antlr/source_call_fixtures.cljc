(ns yang.typescript.antlr.source-call-fixtures
  "Real PHP and TS source with parser-produced CST forms. Regeneration is
   checked by source-call-parser-test; portable hosts lower these same forms.")


(def direct
  '{:module phpm.s11-direct,
    :php-source
    "<?php function phpfn($x, $y = 2.5) { return $x + $y; }\n             function identity($x) { return $x; }",
    :functions ["phpfn" "identity"],
    :ts-source
    "const stored = identity(phpfn);\n     console.log(stored === phpfn, stored(1.5), phpfn(1.5, 3.5));\n     const box = {f: stored}; console.log(box.f(2.5));",
    :expected "true 4 5\n5",
    :php-form
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "phpfn"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter [:variableInitializer ["VarName" "$x"]]]
         ["Comma" ","]
         [:formalParameter
          [:variableInitializer
           ["VarName" "$y"]
           ["Eq" "="]
           [:constantInitializer [:constant [:literalConstant ["Real" "2.5"]]]]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
              ["Plus" "+"]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "identity"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$x"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]]
     ["EOF" "<EOF>"]],
    :ts-form
    [:program
     [:sourceElements
      [:sourceElement
       [:statement
        [:variableStatement
         [:varModifier ["Const" "const"]]
         [:variableDeclarationList
          [:variableDeclaration
           [:identifierOrKeyWord [:identifier ["Identifier" "stored"]]]
           ["Assign" "="]
           [:singleExpression
            [:singleExpression [:identifierName [:identifier ["Identifier" "identity"]]]]
            [:arguments
             ["OpenParen" "("]
             [:argumentList
              [:argument
               [:singleExpression [:identifierName [:identifier ["Identifier" "phpfn"]]]]]]
             ["CloseParen" ")"]]]]]
         ["SemiColon" ";"]]]]
      [:sourceElement
       [:statement
        [:expressionStatement
         [:expressionSequence
          [:singleExpression
           [:singleExpression
            [:singleExpression [:identifierName [:identifier ["Identifier" "console"]]]]
            ["Dot" "."]
            [:identifierName [:identifier ["Identifier" "log"]]]]
           [:arguments
            ["OpenParen" "("]
            [:argumentList
             [:argument
              [:singleExpression
               [:singleExpression [:identifierName [:identifier ["Identifier" "stored"]]]]
               ["IdentityEquals" "==="]
               [:singleExpression [:identifierName [:identifier ["Identifier" "phpfn"]]]]]]
             ["Comma" ","]
             [:argument
              [:singleExpression
               [:singleExpression [:identifierName [:identifier ["Identifier" "stored"]]]]
               [:arguments
                ["OpenParen" "("]
                [:argumentList
                 [:argument
                  [:singleExpression [:literal [:numericLiteral ["DecimalLiteral" "1.5"]]]]]]
                ["CloseParen" ")"]]]]
             ["Comma" ","]
             [:argument
              [:singleExpression
               [:singleExpression [:identifierName [:identifier ["Identifier" "phpfn"]]]]
               [:arguments
                ["OpenParen" "("]
                [:argumentList
                 [:argument
                  [:singleExpression [:literal [:numericLiteral ["DecimalLiteral" "1.5"]]]]]
                 ["Comma" ","]
                 [:argument
                  [:singleExpression [:literal [:numericLiteral ["DecimalLiteral" "3.5"]]]]]]
                ["CloseParen" ")"]]]]]
            ["CloseParen" ")"]]]]
         ["SemiColon" ";"]]]]
      [:sourceElement
       [:statement
        [:variableStatement
         [:varModifier ["Const" "const"]]
         [:variableDeclarationList
          [:variableDeclaration
           [:identifierOrKeyWord [:identifier ["Identifier" "box"]]]
           ["Assign" "="]
           [:singleExpression
            [:objectLiteral
             ["OpenBrace" "{"]
             [:propertyAssignment
              [:propertyName [:identifierName [:identifier ["Identifier" "f"]]]]
              ["Colon" ":"]
              [:singleExpression [:identifierName [:identifier ["Identifier" "stored"]]]]]
             ["CloseBrace" "}"]]]]]
         ["SemiColon" ";"]]]]
      [:sourceElement
       [:statement
        [:expressionStatement
         [:expressionSequence
          [:singleExpression
           [:singleExpression
            [:singleExpression [:identifierName [:identifier ["Identifier" "console"]]]]
            ["Dot" "."]
            [:identifierName [:identifier ["Identifier" "log"]]]]
           [:arguments
            ["OpenParen" "("]
            [:argumentList
             [:argument
              [:singleExpression
               [:singleExpression
                [:singleExpression [:identifierName [:identifier ["Identifier" "box"]]]]
                ["Dot" "."]
                [:identifierName [:identifier ["Identifier" "f"]]]]
               [:arguments
                ["OpenParen" "("]
                [:argumentList
                 [:argument
                  [:singleExpression [:literal [:numericLiteral ["DecimalLiteral" "2.5"]]]]]]
                ["CloseParen" ")"]]]]]
            ["CloseParen" ")"]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]]})


(def callback
  '{:module phpm.s11-callback,
    :php-source
    "<?php function remember($f) { global $saved; $saved = $f; return $saved; }\n             function recall() { global $saved; return $saved; }\n             function callback($x) { global $saved; return $saved($x); }",
    :functions ["remember" "recall" "callback"],
    :ts-source
    "let calls = 0; const captured = 2;\n     const f = x => { calls++; return x + captured; };\n     const returned = remember(f);\n     console.log(returned === f, recall() === f, calls);\n     console.log(callback(3), calls, recall() === f);\n     console.log(returned(4), calls);",
    :expected "true true 0\n5 1 true\n6 2",
    :php-form
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "remember"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$f"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:globalStatement
             ["Global" "global"]
             [:globalVar ["VarName" "$saved"]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$saved"]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$saved"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "recall"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:globalStatement
             ["Global" "global"]
             [:globalVar ["VarName" "$saved"]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$saved"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "callback"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$x"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:globalStatement
             ["Global" "global"]
             [:globalVar ["VarName" "$saved"]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:chain
               [:chainOrigin
                [:functionCall
                 [:functionCallName [:chainBase [:keyedVariable ["VarName" "$saved"]]]]
                 [:actualArguments
                  [:arguments
                   ["OpenRoundBracket" "("]
                   [:actualArgument
                    [:expression
                     [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]]
                   ["CloseRoundBracket" ")"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]]
     ["EOF" "<EOF>"]],
    :ts-form
    [:program
     [:sourceElements
      [:sourceElement
       [:statement
        [:variableStatement
         [:varModifier ["Let" "let"]]
         [:variableDeclarationList
          [:variableDeclaration
           [:identifierOrKeyWord [:identifier ["Identifier" "calls"]]]
           ["Assign" "="]
           [:singleExpression [:literal [:numericLiteral ["DecimalLiteral" "0"]]]]]]
         ["SemiColon" ";"]]]]
      [:sourceElement
       [:statement
        [:variableStatement
         [:varModifier ["Const" "const"]]
         [:variableDeclarationList
          [:variableDeclaration
           [:identifierOrKeyWord [:identifier ["Identifier" "captured"]]]
           ["Assign" "="]
           [:singleExpression [:literal [:numericLiteral ["DecimalLiteral" "2"]]]]]]
         ["SemiColon" ";"]]]]
      [:sourceElement
       [:statement
        [:variableStatement
         [:varModifier ["Const" "const"]]
         [:variableDeclarationList
          [:variableDeclaration
           [:identifierOrKeyWord [:identifier ["Identifier" "f"]]]
           ["Assign" "="]
           [:singleExpression
            [:anonymousFunction
             [:arrowFunctionDeclaration
              [:arrowFunctionParameters
               [:propertyName [:identifierName [:identifier ["Identifier" "x"]]]]]
              ["ARROW" "=>"]
              [:arrowFunctionBody
               ["OpenBrace" "{"]
               [:functionBody
                [:sourceElements
                 [:sourceElement
                  [:statement
                   [:expressionStatement
                    [:expressionSequence
                     [:singleExpression
                      [:singleExpression [:identifierName [:identifier ["Identifier" "calls"]]]]
                      ["PlusPlus" "++"]]]
                    ["SemiColon" ";"]]]]
                 [:sourceElement
                  [:statement
                   [:returnStatement
                    ["Return" "return"]
                    [:expressionSequence
                     [:singleExpression
                      [:singleExpression [:identifierName [:identifier ["Identifier" "x"]]]]
                      ["Plus" "+"]
                      [:singleExpression
                       [:identifierName [:identifier ["Identifier" "captured"]]]]]]
                    [:eos ["SemiColon" ";"]]]]]]]
               ["CloseBrace" "}"]]]]]]]
         ["SemiColon" ";"]]]]
      [:sourceElement
       [:statement
        [:variableStatement
         [:varModifier ["Const" "const"]]
         [:variableDeclarationList
          [:variableDeclaration
           [:identifierOrKeyWord [:identifier ["Identifier" "returned"]]]
           ["Assign" "="]
           [:singleExpression
            [:singleExpression [:identifierName [:identifier ["Identifier" "remember"]]]]
            [:arguments
             ["OpenParen" "("]
             [:argumentList
              [:argument [:singleExpression [:identifierName [:identifier ["Identifier" "f"]]]]]]
             ["CloseParen" ")"]]]]]
         ["SemiColon" ";"]]]]
      [:sourceElement
       [:statement
        [:expressionStatement
         [:expressionSequence
          [:singleExpression
           [:singleExpression
            [:singleExpression [:identifierName [:identifier ["Identifier" "console"]]]]
            ["Dot" "."]
            [:identifierName [:identifier ["Identifier" "log"]]]]
           [:arguments
            ["OpenParen" "("]
            [:argumentList
             [:argument
              [:singleExpression
               [:singleExpression [:identifierName [:identifier ["Identifier" "returned"]]]]
               ["IdentityEquals" "==="]
               [:singleExpression [:identifierName [:identifier ["Identifier" "f"]]]]]]
             ["Comma" ","]
             [:argument
              [:singleExpression
               [:singleExpression
                [:singleExpression [:identifierName [:identifier ["Identifier" "recall"]]]]
                [:arguments ["OpenParen" "("] ["CloseParen" ")"]]]
               ["IdentityEquals" "==="]
               [:singleExpression [:identifierName [:identifier ["Identifier" "f"]]]]]]
             ["Comma" ","]
             [:argument [:singleExpression [:identifierName [:identifier ["Identifier" "calls"]]]]]]
            ["CloseParen" ")"]]]]
         ["SemiColon" ";"]]]]
      [:sourceElement
       [:statement
        [:expressionStatement
         [:expressionSequence
          [:singleExpression
           [:singleExpression
            [:singleExpression [:identifierName [:identifier ["Identifier" "console"]]]]
            ["Dot" "."]
            [:identifierName [:identifier ["Identifier" "log"]]]]
           [:arguments
            ["OpenParen" "("]
            [:argumentList
             [:argument
              [:singleExpression
               [:singleExpression [:identifierName [:identifier ["Identifier" "callback"]]]]
               [:arguments
                ["OpenParen" "("]
                [:argumentList
                 [:argument
                  [:singleExpression [:literal [:numericLiteral ["DecimalLiteral" "3"]]]]]]
                ["CloseParen" ")"]]]]
             ["Comma" ","]
             [:argument [:singleExpression [:identifierName [:identifier ["Identifier" "calls"]]]]]
             ["Comma" ","]
             [:argument
              [:singleExpression
               [:singleExpression
                [:singleExpression [:identifierName [:identifier ["Identifier" "recall"]]]]
                [:arguments ["OpenParen" "("] ["CloseParen" ")"]]]
               ["IdentityEquals" "==="]
               [:singleExpression [:identifierName [:identifier ["Identifier" "f"]]]]]]]
            ["CloseParen" ")"]]]]
         ["SemiColon" ";"]]]]
      [:sourceElement
       [:statement
        [:expressionStatement
         [:expressionSequence
          [:singleExpression
           [:singleExpression
            [:singleExpression [:identifierName [:identifier ["Identifier" "console"]]]]
            ["Dot" "."]
            [:identifierName [:identifier ["Identifier" "log"]]]]
           [:arguments
            ["OpenParen" "("]
            [:argumentList
             [:argument
              [:singleExpression
               [:singleExpression [:identifierName [:identifier ["Identifier" "returned"]]]]
               [:arguments
                ["OpenParen" "("]
                [:argumentList
                 [:argument
                  [:singleExpression [:literal [:numericLiteral ["DecimalLiteral" "4"]]]]]]
                ["CloseParen" ")"]]]]
             ["Comma" ","]
             [:argument [:singleExpression [:identifierName [:identifier ["Identifier" "calls"]]]]]]
            ["CloseParen" ")"]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]]})
