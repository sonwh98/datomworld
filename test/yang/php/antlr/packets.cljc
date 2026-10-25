(ns yang.php.antlr.packets
  "CST packets of the portable fixtures, checked in as `packet` forms (rule
   `[:rule child ...]`, token `[\"TYPE\" \"text\"]`) so the Node and Dart lanes lower
   and run them without a parser. Regenerate from `yang.php.antlr.cst` when the
   grammar package moves; spans are dropped, the lowering reads them only for
   diagnostics.")


(def forms
  '{:array-key-illegal-offset
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-key-value
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'x'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'y'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:statement
              [:echoStatement
               ["Echo" "echo"]
               [:expressionList
                [:expression
                 [:string
                  ["DoubleQuote" "\""]
                  [:interpolatedStringPart
                   [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
                  [:interpolatedStringPart ["StringPart" "="]]
                  [:interpolatedStringPart
                   [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
                  [:interpolatedStringPart ["StringPart" ","]]
                  ["DoubleQuote" "\""]]]]
               ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]]
     ["EOF" "<EOF>"]],
    :array-vivify-undefined
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$u"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                ["CloseSquareBracket" "]"]]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant ["Null" "null"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$n"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'a'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$e"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$e"]
               [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]
               [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$u"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$e"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :static-initializer-call
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "g"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "init"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "67"]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:staticVariableStatement
             ["Static" "static"]
             [:staticInitializer
              ["VarName" "$n"]
              ["Eq" "="]
              [:expression
               [:chain
                [:chainOrigin
                 [:functionCall
                  [:functionCallName
                   [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "g"]]]]]
                  [:actualArguments
                   [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
             [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :single-quote-escapes
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:expression
            [:expression
             [:expression [:string ["SingleQuoteString" "'a\\\\'"]]]
             ["Dot" "."]
             [:expression [:string ["SingleQuoteString" "'b\\'c'"]]]]
            ["Dot" "."]
            [:expression [:string ["SingleQuoteString" "'\\\\\\'d'"]]]]
           ["Dot" "."]
           [:expression [:string ["SingleQuoteString" "'e\\nf'"]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :sort-asort-stable
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'a'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'b'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'c'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'d'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "asort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              [:interpolatedStringPart ["StringPart" "="]]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "arsort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              [:interpolatedStringPart ["StringPart" "="]]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]]
     ["EOF" "<EOF>"]],
    :array-isset-empty-unset
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'x'"]]]
              ["DoubleArrow" "=>"]
              [:expression
               [:arrayCreation
                ["OpenSquareBracket" "["]
                [:arrayItemList
                 [:arrayItem
                  [:expression [:string ["SingleQuoteString" "'y'"]]]
                  ["DoubleArrow" "=>"]
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
                 ["Comma" ","]
                 [:arrayItem
                  [:expression [:string ["SingleQuoteString" "'n'"]]]
                  ["DoubleArrow" "=>"]
                  [:expression [:constant ["Null" "null"]]]]]
                ["CloseSquareBracket" "]"]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'z'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$a"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'x'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$a"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'x'"]]]
                             ["CloseSquareBracket" "]"]]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'y'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$a"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'x'"]]]
                             ["CloseSquareBracket" "]"]]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'n'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$a"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'q'"]]]
                             ["CloseSquareBracket" "]"]]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'r'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$a"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'x'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["Comma" ","]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$a"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'z'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$a"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'x'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["Comma" ","]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$a"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'nope'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$u"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression
                              [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Empty" "empty"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$a"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'x'"]]]
                             ["CloseSquareBracket" "]"]]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'y'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Empty" "empty"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$a"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'z'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Empty" "empty"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$a"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'q'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Empty" "empty"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$u"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'k'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Unset" "unset"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:string ["SingleQuoteString" "'x'"]]]
                      ["CloseSquareBracket" "]"]]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:string ["SingleQuoteString" "'y'"]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:string ["SingleQuoteString" "'nope'"]]]
                      ["CloseSquareBracket" "]"]]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:string ["SingleQuoteString" "'deep'"]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$u"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:string ["SingleQuoteString" "'v'"]]]
                      ["CloseSquareBracket" "]"]]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$u"]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-int-string-5-apples
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "5 apples"]]
                   ["DoubleQuote" "\""]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :numeric-strings
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "5"]]
                   ["DoubleQuote" "\""]]]
                 ["Plus" "+"]
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "3"]]
                   ["DoubleQuote" "\""]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" " 5"]]
                   ["DoubleQuote" "\""]]]
                 ["Plus" "+"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "5 "]]
                   ["DoubleQuote" "\""]]]
                 ["Plus" "+"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "1.5"]]
                   ["DoubleQuote" "\""]]]
                 ["Plus" "+"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "1e2"]]
                   ["DoubleQuote" "\""]]]
                 ["Plus" "+"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-in-function
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "s"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$xs"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$t"]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:foreachStatement
             ["Foreach" "foreach"]
             ["OpenRoundBracket" "("]
             [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$xs"]]]]]
             ["As" "as"]
             [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
             ["CloseRoundBracket" ")"]
             [:statement
              [:blockStatement
               ["OpenCurlyBracket" "{"]
               [:innerStatementList
                [:innerStatement
                 [:statement
                  [:expressionStatement
                   [:expression
                    [:assignable
                     [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$t"]]]]]]
                    [:assignmentOperator ["PlusEqual" "+="]]
                    [:expression
                     [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]]
                   ["SemiColon" ";"]]]]]
               ["CloseCurlyBracket" "}"]]]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$t"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "s"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                    ["Comma" ","]
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                    ["Comma" ","]
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                    ["Comma" ","]
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "4"]]]]]]]
                   ["CloseSquareBracket" "]"]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-plus-int
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]
          ["Plus" "+"]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-float-null
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "fmod"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument [:expression [:constant ["Null" "null"]]]]
                      ["Comma" ","]
                      [:actualArgument
                       [:expression
                        [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :sort-thousand
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:forStatement
         ["For" "for"]
         ["OpenRoundBracket" "("]
         [:forInit
          [:expressionList
           [:expression
            [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
            [:assignmentOperator ["Eq" "="]]
            [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]]
         ["SemiColon" ";"]
         [:expressionList
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
           ["Less" "<"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1000"]]]]]]]
         ["SemiColon" ";"]
         [:forUpdate
          [:expressionList
           [:expression
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]
            ["Inc" "++"]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:statement
              [:expressionStatement
               [:expression
                [:assignable
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      ["CloseSquareBracket" "]"]]]]]]]
                [:assignmentOperator ["Eq" "="]]
                [:expression
                 [:expression
                  [:parentheses
                   ["OpenRoundBracket" "("]
                   [:expression
                    [:expression
                     [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                    ["Asterisk" "*"]
                    [:expression
                     [:constant [:literalConstant [:numericConstant ["Decimal" "7919"]]]]]]
                   ["CloseRoundBracket" ")"]]]
                 ["Percent" "%"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1000"]]]]]]]
               ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "sort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          ["Comma" ","]
          [:expression [:string ["SingleQuoteString" "','"]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          ["Comma" ","]
          [:expression [:string ["SingleQuoteString" "','"]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "999"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          ["Comma" ","]
          [:expression [:string ["SingleQuoteString" "','"]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "count"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :interpolation
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "w"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant ["Real" "2.5"]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "a "]]
            [:interpolatedStringPart
             [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
            [:interpolatedStringPart ["StringPart" " b "]]
            [:interpolatedStringPart
             [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
            [:interpolatedStringPart ["StringPart" "c "]]
            [:interpolatedStringPart
             [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
            [:interpolatedStringPart
             [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
            [:interpolatedStringPart ["StringPart" " "]]
            [:interpolatedStringPart ["StringPart" "\\$"]]
            [:interpolatedStringPart ["StringPart" "n x"]]
            [:interpolatedStringPart ["StringPart" "\\n"]]
            ["DoubleQuote" "\""]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "f="]]
            [:interpolatedStringPart
             [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
            ["DoubleQuote" "\""]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart
             [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
            ["DoubleQuote" "\""]]]
          ["Comma" ","]
          [:expression [:string ["DoubleQuote" "\""] ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :sort-basic
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "sort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              [:interpolatedStringPart ["StringPart" "="]]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "rsort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              [:interpolatedStringPart ["StringPart" "="]]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]]
     ["EOF" "<EOF>"]],
    :arg-float-string-2
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "fmod"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:string
                  ["DoubleQuote" "\""]
                  [:interpolatedStringPart ["StringPart" "1x"]]
                  ["DoubleQuote" "\""]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :variables-and-assignment
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
          [:assignmentOperator ["PlusEqual" "+="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
          [:assignmentOperator ["Concaequal" ".="]]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "a"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$z"]]]]]]
           ["NullCoalescing" "??"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
           [:assignmentOperator ["Eq" "="]]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
           ["Plus" "+"]
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]
          [:assignmentOperator ["PowEqual" "**="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]
          [:assignmentOperator ["MinusEqual" "-="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]
          [:assignmentOperator ["MulEqual" "*="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]
          [:assignmentOperator ["DivEqual" "/="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "4"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]
          [:assignmentOperator ["ModEqual" "%="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]
          [:assignmentOperator ["ShiftLeftEqual" "<<="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:expression
            [:chain
             [:chainOrigin
              [:functionCall
               [:functionCallName
                [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
               [:actualArguments
                [:arguments
                 ["OpenRoundBracket" "("]
                 [:actualArgument
                  [:expression
                   [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]]
                 ["CloseRoundBracket" ")"]]]]]]]
           ["QuestionMark" "?"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
           ["Colon" ":"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
          ["Comma" ","]
          [:expression
           [:expression
            [:chain
             [:chainOrigin
              [:functionCall
               [:functionCallName
                [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Empty" "empty"]]]]]
               [:actualArguments
                [:arguments
                 ["OpenRoundBracket" "("]
                 [:actualArgument
                  [:expression
                   [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]]
                 ["CloseRoundBracket" ")"]]]]]]]
           ["QuestionMark" "?"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
           ["Colon" ":"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Unset" "unset"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:expression
            [:chain
             [:chainOrigin
              [:functionCall
               [:functionCallName
                [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
               [:actualArguments
                [:arguments
                 ["OpenRoundBracket" "("]
                 [:actualArgument
                  [:expression
                   [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]]
                 ["CloseRoundBracket" ")"]]]]]]]
           ["QuestionMark" "?"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
           ["Colon" ":"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :static-initializer-twice
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "g"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "init"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "67"]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:staticVariableStatement
             ["Static" "static"]
             [:staticInitializer
              ["VarName" "$n"]
              ["Eq" "="]
              [:expression
               [:chain
                [:chainOrigin
                 [:functionCall
                  [:functionCallName
                   [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "g"]]]]]
                  [:actualArguments
                   [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
             [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
             [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :sort-ksort-mixed-keys
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "10"]]]]]
              ["DoubleArrow" "=>"]
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "a"]]
                ["DoubleQuote" "\""]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "9"]]
                ["DoubleQuote" "\""]]]
              ["DoubleArrow" "=>"]
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "b"]]
                ["DoubleQuote" "\""]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "x"]]
                ["DoubleQuote" "\""]]]
              ["DoubleArrow" "=>"]
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "c"]]
                ["DoubleQuote" "\""]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "A"]]
                ["DoubleQuote" "\""]]]
              ["DoubleArrow" "=>"]
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "d"]]
                ["DoubleQuote" "\""]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "ksort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              [:interpolatedStringPart ["StringPart" "="]]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]]
     ["EOF" "<EOF>"]],
    :builtin-count-type-error
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "count"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-int-neg-1e30
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  ["Minus" "-"]
                  [:expression [:constant [:literalConstant ["Real" "1e30"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :sort-uksort
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "d"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter [:variableInitializer ["VarName" "$x"]]]
         ["Comma" ","]
         [:formalParameter [:variableInitializer ["VarName" "$y"]]]]
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
              ["Spaceship" "<=>"]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "b"]]
                ["DoubleQuote" "\""]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "c"]]
                ["DoubleQuote" "\""]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "a"]]
                ["DoubleQuote" "\""]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "uksort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:string
                  ["DoubleQuote" "\""]
                  [:interpolatedStringPart ["StringPart" "d"]]
                  ["DoubleQuote" "\""]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              [:interpolatedStringPart ["StringPart" "="]]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]]
     ["EOF" "<EOF>"]],
    :array-operators
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                    ["Comma" ","]
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
                   ["CloseSquareBracket" "]"]]]
                 ["Plus" "+"]
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
                    ["Comma" ","]
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "6"]]]]]]
                    ["Comma" ","]
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]]
                   ["CloseSquareBracket" "]"]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                   ["CloseSquareBracket" "]"]]]
                 ["IsEqual" "=="]
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                   ["CloseSquareBracket" "]"]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                   ["CloseSquareBracket" "]"]]]
                 ["IsIdentical" "==="]
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList [:arrayItem [:expression [:string ["SingleQuoteString" "'1'"]]]]]
                   ["CloseSquareBracket" "]"]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:string ["SingleQuoteString" "'a'"]]]
                     ["DoubleArrow" "=>"]
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                    ["Comma" ","]
                    [:arrayItem
                     [:expression [:string ["SingleQuoteString" "'b'"]]]
                     ["DoubleArrow" "=>"]
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
                   ["CloseSquareBracket" "]"]]]
                 ["IsEqual" "=="]
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:string ["SingleQuoteString" "'b'"]]]
                     ["DoubleArrow" "=>"]
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                    ["Comma" ","]
                    [:arrayItem
                     [:expression [:string ["SingleQuoteString" "'a'"]]]
                     ["DoubleArrow" "=>"]
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                   ["CloseSquareBracket" "]"]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:string ["SingleQuoteString" "'a'"]]]
                     ["DoubleArrow" "=>"]
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                    ["Comma" ","]
                    [:arrayItem
                     [:expression [:string ["SingleQuoteString" "'b'"]]]
                     ["DoubleArrow" "=>"]
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
                   ["CloseSquareBracket" "]"]]]
                 ["IsIdentical" "==="]
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:string ["SingleQuoteString" "'b'"]]]
                     ["DoubleArrow" "=>"]
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                    ["Comma" ","]
                    [:arrayItem
                     [:expression [:string ["SingleQuoteString" "'a'"]]]
                     ["DoubleArrow" "=>"]
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                   ["CloseSquareBracket" "]"]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]
                 ["IsEqual" "=="]
                 [:expression [:constant [:literalConstant ["BooleanConstant" "false"]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
                   ["CloseSquareBracket" "]"]]]
                 ["IsEqual" "=="]
                 [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 ["OpenRoundBracket" "("]
                 [:castOperation ["BoolType" "bool"]]
                 ["CloseRoundBracket" ")"]
                 [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 ["OpenRoundBracket" "("]
                 [:castOperation ["BoolType" "bool"]]
                 ["CloseRoundBracket" ")"]
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
                   ["CloseSquareBracket" "]"]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 ["OpenRoundBracket" "("]
                 [:castOperation ["IntType" "int"]]
                 ["CloseRoundBracket" ")"]
                 [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 ["OpenRoundBracket" "("]
                 [:castOperation ["IntType" "int"]]
                 ["CloseRoundBracket" ")"]
                 [:expression
                  [:arrayCreation
                   ["OpenSquareBracket" "["]
                   [:arrayItemList
                    [:arrayItem
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
                   ["CloseSquareBracket" "]"]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-key-expr-write
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:expression
          [:arrayCreation
           ["OpenSquareBracket" "["]
           [:arrayItemList
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]]
           ["CloseSquareBracket" "]"]]]
         ["As" "as"]
         [:assignable
          [:chain
           [:chainOrigin
            [:chainBase
             [:keyedVariable
              ["VarName" "$a"]
              [:squareCurlyExpression
               ["OpenSquareBracket" "["]
               [:expression
                [:assignable
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]
                [:assignmentOperator ["Eq" "="]]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
               ["CloseSquareBracket" "]"]]]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :undefined-function
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "undefined_fn"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-null-coalesce
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'x'"]]]
              ["DoubleArrow" "=>"]
              [:expression
               [:arrayCreation
                ["OpenSquareBracket" "["]
                [:arrayItemList
                 [:arrayItem
                  [:expression [:string ["SingleQuoteString" "'y'"]]]
                  ["DoubleArrow" "=>"]
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                ["CloseSquareBracket" "]"]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:expression
            [:chain
             [:chainOrigin
              [:chainBase
               [:keyedVariable
                ["VarName" "$a"]
                [:squareCurlyExpression
                 ["OpenSquareBracket" "["]
                 [:expression [:string ["SingleQuoteString" "'x'"]]]
                 ["CloseSquareBracket" "]"]]
                [:squareCurlyExpression
                 ["OpenSquareBracket" "["]
                 [:expression [:string ["SingleQuoteString" "'y'"]]]
                 ["CloseSquareBracket" "]"]]]]]]]
           ["NullCoalescing" "??"]
           [:expression [:string ["SingleQuoteString" "'d'"]]]]
          ["Comma" ","]
          [:expression
           [:expression
            [:chain
             [:chainOrigin
              [:chainBase
               [:keyedVariable
                ["VarName" "$a"]
                [:squareCurlyExpression
                 ["OpenSquareBracket" "["]
                 [:expression [:string ["SingleQuoteString" "'x'"]]]
                 ["CloseSquareBracket" "]"]]
                [:squareCurlyExpression
                 ["OpenSquareBracket" "["]
                 [:expression [:string ["SingleQuoteString" "'z'"]]]
                 ["CloseSquareBracket" "]"]]]]]]]
           ["NullCoalescing" "??"]
           [:expression [:string ["SingleQuoteString" "'d'"]]]]
          ["Comma" ","]
          [:expression
           [:expression
            [:chain
             [:chainOrigin
              [:chainBase
               [:keyedVariable
                ["VarName" "$a"]
                [:squareCurlyExpression
                 ["OpenSquareBracket" "["]
                 [:expression [:string ["SingleQuoteString" "'q'"]]]
                 ["CloseSquareBracket" "]"]]
                [:squareCurlyExpression
                 ["OpenSquareBracket" "["]
                 [:expression [:string ["SingleQuoteString" "'z'"]]]
                 ["CloseSquareBracket" "]"]]]]]]]
           ["NullCoalescing" "??"]
           [:expression [:string ["SingleQuoteString" "'d'"]]]]
          ["Comma" ","]
          [:expression
           [:expression
            [:chain
             [:chainOrigin
              [:chainBase
               [:keyedVariable
                ["VarName" "$u"]
                [:squareCurlyExpression
                 ["OpenSquareBracket" "["]
                 [:expression [:string ["SingleQuoteString" "'k'"]]]
                 ["CloseSquareBracket" "]"]]]]]]]
           ["NullCoalescing" "??"]
           [:expression [:string ["SingleQuoteString" "'d'"]]]]
          ["Comma" ","]
          [:expression
           [:expression
            [:chain
             [:chainOrigin
              [:chainBase
               [:keyedVariable
                ["VarName" "$a"]
                [:squareCurlyExpression
                 ["OpenSquareBracket" "["]
                 [:expression [:string ["SingleQuoteString" "'x'"]]]
                 ["CloseSquareBracket" "]"]]
                [:squareCurlyExpression
                 ["OpenSquareBracket" "["]
                 [:expression [:string ["SingleQuoteString" "'y'"]]]
                 ["CloseSquareBracket" "]"]]
                [:squareCurlyExpression
                 ["OpenSquareBracket" "["]
                 [:expression [:string ["SingleQuoteString" "'w'"]]]
                 ["CloseSquareBracket" "]"]]]]]]]
           ["NullCoalescing" "??"]
           [:expression [:string ["SingleQuoteString" "'d'"]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-local-loop
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:forStatement
             ["For" "for"]
             ["OpenRoundBracket" "("]
             [:forInit
              [:expressionList
               [:expression
                [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                [:assignmentOperator ["Eq" "="]]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]]
             ["SemiColon" ";"]
             [:expressionList
              [:expression
               [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
               ["Less" "<"]
               [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
             ["SemiColon" ";"]
             [:forUpdate
              [:expressionList
               [:expression
                [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]
                ["Inc" "++"]]]]
             ["CloseRoundBracket" ")"]
             [:statement
              [:blockStatement
               ["OpenCurlyBracket" "{"]
               [:innerStatementList
                [:innerStatement
                 [:statement
                  [:expressionStatement
                   [:expression
                    [:assignable
                     [:chain
                      [:chainOrigin
                       [:chainBase
                        [:keyedVariable
                         ["VarName" "$a"]
                         [:squareCurlyExpression
                          ["OpenSquareBracket" "["]
                          ["CloseSquareBracket" "]"]]]]]]]
                    [:assignmentOperator ["Eq" "="]]
                    [:expression
                     [:expression
                      [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                     ["Asterisk" "*"]
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
                   ["SemiColon" ";"]]]]]
               ["CloseCurlyBracket" "}"]]]]]]
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable
               [:chain
                [:chainOrigin
                 [:chainBase
                  [:keyedVariable
                   ["VarName" "$a"]
                   [:squareCurlyExpression
                    ["OpenSquareBracket" "["]
                    [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                    ["CloseSquareBracket" "]"]]]]]]]
              [:assignmentOperator ["PlusEqual" "+="]]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
                    [:actualArguments
                     [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :builtin-exists-in-array
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName
                      [:namespaceNameList [:identifier ["Label" "array_key_exists"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument [:expression [:string ["SingleQuoteString" "'1'"]]]]
                      ["Comma" ","]
                      [:actualArgument
                       [:expression
                        [:arrayCreation
                         ["OpenSquareBracket" "["]
                         [:arrayItemList
                          [:arrayItem
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                           ["DoubleArrow" "=>"]
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
                         ["CloseSquareBracket" "]"]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName
                      [:namespaceNameList [:identifier ["Label" "array_key_exists"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument [:expression [:string ["SingleQuoteString" "'z'"]]]]
                      ["Comma" ","]
                      [:actualArgument
                       [:expression
                        [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName
                      [:namespaceNameList [:identifier ["Label" "in_array"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument [:expression [:string ["SingleQuoteString" "'1'"]]]]
                      ["Comma" ","]
                      [:actualArgument
                       [:expression
                        [:arrayCreation
                         ["OpenSquareBracket" "["]
                         [:arrayItemList
                          [:arrayItem
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                          ["Comma" ","]
                          [:arrayItem
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
                         ["CloseSquareBracket" "]"]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName
                      [:namespaceNameList [:identifier ["Label" "in_array"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument [:expression [:string ["SingleQuoteString" "'1'"]]]]
                      ["Comma" ","]
                      [:actualArgument
                       [:expression
                        [:arrayCreation
                         ["OpenSquareBracket" "["]
                         [:arrayItemList
                          [:arrayItem
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                          ["Comma" ","]
                          [:arrayItem
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
                         ["CloseSquareBracket" "]"]]]]
                      ["Comma" ","]
                      [:actualArgument
                       [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName
                      [:namespaceNameList [:identifier ["Label" "in_array"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument [:expression [:constant ["Null" "null"]]]]
                      ["Comma" ","]
                      [:actualArgument
                       [:expression
                        [:arrayCreation
                         ["OpenSquareBracket" "["]
                         [:arrayItemList
                          [:arrayItem
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
                         ["CloseSquareBracket" "]"]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :int-overflow
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:constant [:literalConstant [:stringConstant ["Label" "PHP_INT_MAX"]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:constant [:literalConstant [:stringConstant ["Label" "PHP_INT_MIN"]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:constant [:literalConstant [:stringConstant ["Label" "PHP_INT_MAX"]]]]]
                 ["Plus" "+"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:constant [:literalConstant [:stringConstant ["Label" "PHP_INT_MIN"]]]]]
                 ["Minus" "-"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:constant [:literalConstant [:stringConstant ["Label" "PHP_INT_MAX"]]]]]
                 ["Asterisk" "*"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 ["Minus" "-"]
                 [:expression
                  [:constant [:literalConstant [:stringConstant ["Label" "PHP_INT_MIN"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:constant [:literalConstant [:numericConstant ["Decimal" "9223372036854775807"]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:constant [:literalConstant [:numericConstant ["Decimal" "9223372036854775808"]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:constant [:literalConstant [:numericConstant ["Hex" "0x7FFFFFFFFFFFFFFF"]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:constant [:literalConstant [:numericConstant ["Hex" "0xFFFFFFFFFFFFFFFF"]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression [:constant [:literalConstant [:numericConstant ["Octal" "010"]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression [:constant [:literalConstant [:numericConstant ["Binary" "0b101"]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-value
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:expression
          [:arrayCreation
           ["OpenSquareBracket" "["]
           [:arrayItemList
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
            ["Comma" ","]
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
            ["Comma" ","]
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
           ["CloseSquareBracket" "]"]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
           ["SemiColon" ";"]]]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :builtin-push-pop
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "array_push"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "array_pop"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-spread-scalar
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:arrayCreation
                  ["OpenSquareBracket" "["]
                  [:arrayItemList
                   [:arrayItem
                    ["Ellipsis" "..."]
                    [:expression
                     [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                   ["Comma" ","]
                   [:arrayItem
                    ["Ellipsis" "..."]
                    [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                  ["CloseSquareBracket" "]"]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :silence-nested
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "h"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter [:variableInitializer ["VarName" "$n"]]]
         ["Comma" ","]
         [:formalParameter [:variableInitializer ["VarName" "$s"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "H"]]
                ["DoubleQuote" "\""]]]
              ["Comma" ","]
              [:expression
               [:chain
                [:chainOrigin
                 [:functionCall
                  [:functionCallName
                   [:qualifiedNamespaceName
                    [:namespaceNameList [:identifier ["Label" "error_reporting"]]]]]
                  [:actualArguments
                   [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
              ["Comma" ","]
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" ";"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName
               [:namespaceNameList [:identifier ["Label" "set_error_handler"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument [:expression [:string ["SingleQuoteString" "'h'"]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$u"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           ["SuppressWarnings" "@"]
           [:expression
            [:parentheses
             ["OpenRoundBracket" "("]
             [:expression
              ["SuppressWarnings" "@"]
              [:expression
               [:chain
                [:chainOrigin
                 [:functionCall
                  [:functionCallName
                   [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
                  [:actualArguments
                   [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
             ["CloseRoundBracket" ")"]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName
                [:namespaceNameList [:identifier ["Label" "error_reporting"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-eval-order
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "k"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$n"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "k"]]
                [:interpolatedStringPart
                 [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
                [:interpolatedStringPart ["StringPart" " "]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "v"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "v "]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:string ["SingleQuoteString" "'V'"]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "k"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]
                ["CloseSquareBracket" "]"]]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "k"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "v"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "k"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Concaequal" ".="]]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "v"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:chainBase
             [:keyedVariable
              ["VarName" "$a"]
              [:squareCurlyExpression
               ["OpenSquareBracket" "["]
               [:expression
                [:chain
                 [:chainOrigin
                  [:functionCall
                   [:functionCallName
                    [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "k"]]]]]
                   [:actualArguments
                    [:arguments
                     ["OpenRoundBracket" "("]
                     [:actualArgument
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "4"]]]]]]
                     ["CloseRoundBracket" ")"]]]]]]]
               ["CloseSquareBracket" "]"]]]]]]
          ["Inc" "++"]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-int-weak-mode
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument [:expression [:constant [:literalConstant ["Real" "1.0"]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument [:expression [:constant [:literalConstant ["Real" "1.5"]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  ["Minus" "-"]
                  [:expression [:constant [:literalConstant ["Real" "1.5"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "1.5"]]
                   ["DoubleQuote" "\""]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "1e3"]]
                   ["DoubleQuote" "\""]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument [:expression [:constant ["Null" "null"]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  [:constant [:literalConstant [:stringConstant ["Label" "PHP_INT_MAX"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  [:constant [:literalConstant [:stringConstant ["Label" "PHP_INT_MIN"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  ["Minus" "-"]
                  [:expression [:constant [:literalConstant ["Real" "9223372036854775808.0"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant ["Real" "9223372036854774784.0"]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-compound-assign
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'n'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'s'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:string ["SingleQuoteString" "'a'"]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'n'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["PlusEqual" "+="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'s'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Concaequal" ".="]]
          [:expression [:string ["SingleQuoteString" "'b'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'n'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["MulEqual" "*="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'m'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["NullCoalescingEqual" "??="]]
          [:expression [:string ["SingleQuoteString" "'dflt'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'m'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["NullCoalescingEqual" "??="]]
          [:expression [:string ["SingleQuoteString" "'other'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:chainBase
             [:keyedVariable
              ["VarName" "$a"]
              [:squareCurlyExpression
               ["OpenSquareBracket" "["]
               [:expression [:string ["SingleQuoteString" "'n'"]]]
               ["CloseSquareBracket" "]"]]]]]]
          ["Inc" "++"]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          ["Dec" "--"]
          [:chain
           [:chainOrigin
            [:chainBase
             [:keyedVariable
              ["VarName" "$a"]
              [:squareCurlyExpression
               ["OpenSquareBracket" "["]
               [:expression [:string ["SingleQuoteString" "'n'"]]]
               ["CloseSquareBracket" "]"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-vivify-false
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant ["BooleanConstant" "false"]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$f"]
               [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-write-scalar
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$i"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-snapshot
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:statement
              [:expressionStatement
               [:expression
                [:assignable
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      ["CloseSquareBracket" "]"]]]]]]]
                [:assignmentOperator ["Eq" "="]]
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
               ["SemiColon" ";"]]]]
            [:innerStatement
             [:statement
              [:expressionStatement
               [:expression
                [:chain
                 [:chainOrigin
                  [:functionCall
                   [:functionCallName
                    [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Unset" "unset"]]]]]
                   [:actualArguments
                    [:arguments
                     ["OpenRoundBracket" "("]
                     [:actualArgument
                      [:expression
                       [:chain
                        [:chainOrigin
                         [:chainBase
                          [:keyedVariable
                           ["VarName" "$a"]
                           [:squareCurlyExpression
                            ["OpenSquareBracket" "["]
                            [:expression
                             [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                            ["CloseSquareBracket" "]"]]]]]]]]
                     ["CloseRoundBracket" ")"]]]]]]]
               ["SemiColon" ";"]]]]
            [:innerStatement
             [:statement
              [:echoStatement
               ["Echo" "echo"]
               [:expressionList
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
               ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "count"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :static-variables
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "counter"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:staticVariableStatement
             ["Static" "static"]
             [:staticInitializer
              ["VarName" "$n"]
              ["Eq" "="]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
             ["Comma" ","]
             [:staticInitializer ["VarName" "$m"]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]
              ["Inc" "++"]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$m"]]]]]]
              [:assignmentOperator ["Concaequal" ".="]]
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "x"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
              ["Dot" "."]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$m"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "counter"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "counter"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "counter"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "rec"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$d"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:staticVariableStatement
             ["Static" "static"]
             [:staticInitializer
              ["VarName" "$calls"]
              ["Eq" "="]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$calls"]]]]]
              ["Inc" "++"]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:ifStatement
             ["If" "if"]
             [:parentheses
              ["OpenRoundBracket" "("]
              [:expression
               [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$d"]]]]]]
               ["Greater" ">"]
               [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
              ["CloseRoundBracket" ")"]]
             [:statement
              [:expressionStatement
               [:expression
                [:chain
                 [:chainOrigin
                  [:functionCall
                   [:functionCallName
                    [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "rec"]]]]]
                   [:actualArguments
                    [:arguments
                     ["OpenRoundBracket" "("]
                     [:actualArgument
                      [:expression
                       [:expression
                        [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$d"]]]]]]
                       ["Minus" "-"]
                       [:expression
                        [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                     ["CloseRoundBracket" ")"]]]]]]]
               ["SemiColon" ";"]]]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$calls"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "rec"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "rec"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "a"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:staticVariableStatement
             ["Static" "static"]
             [:staticInitializer
              ["VarName" "$v"]
              ["Eq" "="]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
              ["Inc" "++"]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "b"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:staticVariableStatement
             ["Static" "static"]
             [:staticInitializer
              ["VarName" "$v"]
              ["Eq" "="]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "100"]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
              ["Inc" "++"]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "a"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "b"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "a"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "b"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-next-index
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression
                 ["Minus" "-"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$b"]
               [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$b"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'x'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$b"]
               [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "4"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]
              ["DoubleArrow" "=>"]
              [:expression [:string ["SingleQuoteString" "'a'"]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Unset" "unset"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$c"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$c"]
               [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'b'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-int-2-63
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant ["Real" "9.2233720368547758E+18"]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-int-float-int-max
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  ["OpenRoundBracket" "("]
                  [:castOperation ["FloatCast" "float"]]
                  ["CloseRoundBracket" ")"]
                  [:expression
                   [:constant [:literalConstant [:stringConstant ["Label" "PHP_INT_MAX"]]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :sort-usort
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "d"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter [:variableInitializer ["VarName" "$x"]]]
         ["Comma" ","]
         [:formalParameter [:variableInitializer ["VarName" "$y"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]
              ["Spaceship" "<=>"]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "usort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["Comma" ","]
               [:actualArgument [:expression [:string ["SingleQuoteString" "'d'"]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              [:interpolatedStringPart ["StringPart" "="]]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]]
     ["EOF" "<EOF>"]],
    :html-leading-newline
    [:htmlDocument
     [:inlineHtml
      [:htmlElement ["HtmlText" "\n"]]
      [:htmlElement ["HtmlOpen" "<"]]
      [:htmlElement ["HtmlName" "p"]]
      [:htmlElement ["HtmlClose" ">"]]
      [:htmlElement ["HtmlText" "x"]]
      [:htmlElement ["HtmlOpen" "<"]]
      [:htmlElement ["HtmlSlash" "/"]]
      [:htmlElement ["HtmlName" "p"]]
      [:htmlElement ["HtmlClose" ">"]]]
     ["EOF" "<EOF>"]],
    :functions
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "fib"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "10"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "add"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "add"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "ADD"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "fib"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$n"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:ifStatement
             ["If" "if"]
             [:parentheses
              ["OpenRoundBracket" "("]
              [:expression
               [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
               ["Less" "<"]
               [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
              ["CloseRoundBracket" ")"]]
             [:statement
              [:returnStatement
               ["Return" "return"]
               [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
               ["SemiColon" ";"]]]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:expression
               [:chain
                [:chainOrigin
                 [:functionCall
                  [:functionCallName
                   [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "fib"]]]]]
                  [:actualArguments
                   [:arguments
                    ["OpenRoundBracket" "("]
                    [:actualArgument
                     [:expression
                      [:expression
                       [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
                      ["Minus" "-"]
                      [:expression
                       [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                    ["CloseRoundBracket" ")"]]]]]]]
              ["Plus" "+"]
              [:expression
               [:chain
                [:chainOrigin
                 [:functionCall
                  [:functionCallName
                   [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "fib"]]]]]
                  [:actualArguments
                   [:arguments
                    ["OpenRoundBracket" "("]
                    [:actualArgument
                     [:expression
                      [:expression
                       [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
                      ["Minus" "-"]
                      [:expression
                       [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
                    ["CloseRoundBracket" ")"]]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "add"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter [:variableInitializer ["VarName" "$a"]]]
         ["Comma" ","]
         [:formalParameter
          [:variableInitializer
           ["VarName" "$b"]
           ["Eq" "="]
           [:constantInitializer
            [:constant [:literalConstant [:numericConstant ["Decimal" "10"]]]]]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
              ["Plus" "+"]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "first_over"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$limit"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:forStatement
             ["For" "for"]
             ["OpenRoundBracket" "("]
             [:forInit
              [:expressionList
               [:expression
                [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                [:assignmentOperator ["Eq" "="]]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]]
             ["SemiColon" ";"]
             ["SemiColon" ";"]
             [:forUpdate
              [:expressionList
               [:expression
                [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]
                ["Inc" "++"]]]]
             ["CloseRoundBracket" ")"]
             [:statement
              [:blockStatement
               ["OpenCurlyBracket" "{"]
               [:innerStatementList
                [:innerStatement
                 [:statement
                  [:ifStatement
                   ["If" "if"]
                   [:parentheses
                    ["OpenRoundBracket" "("]
                    [:expression
                     [:expression
                      [:expression
                       [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                      ["Asterisk" "*"]
                      [:expression
                       [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]]
                     ["Greater" ">"]
                     [:expression
                      [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$limit"]]]]]]]
                    ["CloseRoundBracket" ")"]]
                   [:statement
                    [:returnStatement
                     ["Return" "return"]
                     [:expression
                      [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                     ["SemiColon" ";"]]]]]]]
               ["CloseCurlyBracket" "}"]]]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "first_over"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "50"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "noret"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "noret"]]]]]
                    [:actualArguments
                     [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "nested"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:functionDeclaration
            ["Function_" "function"]
            [:identifier ["Label" "inner"]]
            ["OpenRoundBracket" "("]
            [:formalParameterList]
            ["CloseRoundBracket" ")"]
            [:blockStatement
             ["OpenCurlyBracket" "{"]
             [:innerStatementList
              [:innerStatement
               [:statement
                [:returnStatement
                 ["Return" "return"]
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "inner"]]
                   ["DoubleQuote" "\""]]]
                 ["SemiColon" ";"]]]]]
             ["CloseCurlyBracket" "}"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:string
               ["DoubleQuote" "\""]
               [:interpolatedStringPart ["StringPart" "outer"]]
               ["DoubleQuote" "\""]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "nested"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "inner"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:ifStatement
         ["If" "if"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]
          ["CloseRoundBracket" ")"]]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:functionDeclaration
              ["Function_" "function"]
              [:identifier ["Label" "cond"]]
              ["OpenRoundBracket" "("]
              [:formalParameterList]
              ["CloseRoundBracket" ")"]
              [:blockStatement
               ["OpenCurlyBracket" "{"]
               [:innerStatementList
                [:innerStatement
                 [:statement
                  [:returnStatement
                   ["Return" "return"]
                   [:expression
                    [:string
                     ["DoubleQuote" "\""]
                     [:interpolatedStringPart ["StringPart" "cond"]]
                     ["DoubleQuote" "\""]]]
                   ["SemiColon" ";"]]]]]
               ["CloseCurlyBracket" "}"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "cond"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "early"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$x"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:ifStatement
             ["If" "if"]
             [:parentheses
              ["OpenRoundBracket" "("]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
              ["CloseRoundBracket" ")"]]
             [:statement
              [:blockStatement
               ["OpenCurlyBracket" "{"]
               [:innerStatementList
                [:innerStatement
                 [:statement
                  [:returnStatement
                   ["Return" "return"]
                   [:expression
                    [:string
                     ["DoubleQuote" "\""]
                     [:interpolatedStringPart ["StringPart" "early"]]
                     ["DoubleQuote" "\""]]]
                   ["SemiColon" ";"]]]]]
               ["CloseCurlyBracket" "}"]]]]]]
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "late;"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "early"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:expression
            [:expression
             [:chain
              [:chainOrigin
               [:functionCall
                [:functionCallName
                 [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "early"]]]]]
                [:actualArguments
                 [:arguments
                  ["OpenRoundBracket" "("]
                  [:actualArgument
                   [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
                  ["CloseRoundBracket" ")"]]]]]]]
            ["IsIdentical" "==="]
            [:expression [:constant ["Null" "null"]]]]
           ["QuestionMark" "?"]
           [:expression
            [:string
             ["DoubleQuote" "\""]
             [:interpolatedStringPart ["StringPart" "N"]]
             ["DoubleQuote" "\""]]]
           ["Colon" ":"]
           [:expression
            [:string
             ["DoubleQuote" "\""]
             [:interpolatedStringPart ["StringPart" "?"]]
             ["DoubleQuote" "\""]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :sort-bool-comparator
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "c"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter [:variableInitializer ["VarName" "$x"]]]
         ["Comma" ","]
         [:formalParameter [:variableInitializer ["VarName" "$y"]]]]
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
              ["Greater" ">"]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "usort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:string
                  ["DoubleQuote" "\""]
                  [:interpolatedStringPart ["StringPart" "c"]]
                  ["DoubleQuote" "\""]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              [:interpolatedStringPart ["StringPart" "="]]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]]
     ["EOF" "<EOF>"]],
    :array-cow
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$b"]
               [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$b"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$p"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable
               [:chain
                [:chainOrigin
                 [:chainBase
                  [:keyedVariable
                   ["VarName" "$p"]
                   [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$p"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-int-handler
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "h"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter [:variableInitializer ["VarName" "$n"]]]
         ["Comma" ","]
         [:formalParameter [:variableInitializer ["VarName" "$s"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
              ["Comma" ","]
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" ":"]]
                ["DoubleQuote" "\""]]]
              ["Comma" ","]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$s"]]]]]]
              ["Comma" ","]
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" ";"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName
               [:namespaceNameList [:identifier ["Label" "set_error_handler"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument [:expression [:string ["SingleQuoteString" "'h'"]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument [:expression [:constant [:literalConstant ["Real" "1.5"]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-key-coercion
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'a'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'1'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'b'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant ["Real" "1.0"]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'c'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'d'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'01'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'e'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'-0'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'f'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant ["Null" "null"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'g'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant ["BooleanConstant" "false"]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'h'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'-5'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'i'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "' 1'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'j'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'1.5'"]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'k'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :loops
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:whileStatement
         ["While" "while"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
           ["Less" "<"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
          ["CloseRoundBracket" ")"]]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:statement
              [:echoStatement
               ["Echo" "echo"]
               [:expressionList
                [:expression
                 [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]
                 ["Inc" "++"]]]
               ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:doWhileStatement
         ["Do" "do"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:statement
              [:echoStatement
               ["Echo" "echo"]
               [:expressionList
                [:expression
                 [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]
                 ["Dec" "--"]]]
               ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]
         ["While" "while"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
           ["Greater" ">"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
          ["CloseRoundBracket" ")"]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:forStatement
         ["For" "for"]
         ["OpenRoundBracket" "("]
         [:forInit
          [:expressionList
           [:expression
            [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
            [:assignmentOperator ["Eq" "="]]
            [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
           ["Comma" ","]
           [:expression
            [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
            [:assignmentOperator ["Eq" "="]]
            [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]]
         ["SemiColon" ";"]
         [:expressionList
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
           ["Less" "<"]
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]]
         ["SemiColon" ";"]
         [:forUpdate
          [:expressionList
           [:expression
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
            ["Inc" "++"]]
           ["Comma" ","]
           [:expression
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]
            ["Dec" "--"]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
            ["Comma" ","]
            [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
            ["Comma" ","]
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:forStatement
         ["For" "for"]
         ["OpenRoundBracket" "("]
         ["SemiColon" ";"]
         ["SemiColon" ";"]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement [:statement [:breakStatement ["Break" "break"] ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:whileStatement
         ["While" "while"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
           ["Less" "<"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
          ["CloseRoundBracket" ")"]]
         ["Colon" ":"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "w"]]
                ["DoubleQuote" "\""]]]
              ["Comma" ","]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]
              ["Inc" "++"]]
             ["SemiColon" ";"]]]]]
         ["EndWhile" "endwhile"]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:forStatement
         ["For" "for"]
         ["OpenRoundBracket" "("]
         [:forInit
          [:expressionList
           [:expression
            [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]]
            [:assignmentOperator ["Eq" "="]]
            [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]]
         ["SemiColon" ";"]
         [:expressionList
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]]
           ["Less" "<"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
         ["SemiColon" ";"]
         [:forUpdate
          [:expressionList
           [:expression
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]
            ["Inc" "++"]]]]
         ["CloseRoundBracket" ")"]
         ["Colon" ":"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "f"]]
                ["DoubleQuote" "\""]]]
              ["Comma" ","]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["EndFor" "endfor"]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :silence-error-reporting-zero
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:chain
               [:chainOrigin
                [:functionCall
                 [:functionCallName
                  [:qualifiedNamespaceName
                   [:namespaceNameList [:identifier ["Label" "error_reporting"]]]]]
                 [:actualArguments
                  [:arguments
                   ["OpenRoundBracket" "("]
                   [:actualArgument
                    [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
                   ["CloseRoundBracket" ")"]]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:chain
                [:chainOrigin
                 [:functionCall
                  [:functionCallName
                   [:qualifiedNamespaceName
                    [:namespaceNameList [:identifier ["Label" "error_reporting"]]]]]
                  [:actualArguments
                   [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          ["SuppressWarnings" "@"]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName
                [:namespaceNameList [:identifier ["Label" "error_reporting"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :static-initializer-unreached
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "g"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "init"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$x"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:ifStatement
             ["If" "if"]
             [:parentheses
              ["OpenRoundBracket" "("]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
              ["CloseRoundBracket" ")"]]
             [:statement
              [:blockStatement
               ["OpenCurlyBracket" "{"]
               [:innerStatementList
                [:innerStatement
                 [:statement
                  [:returnStatement
                   ["Return" "return"]
                   [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                   ["SemiColon" ";"]]]]]
               ["CloseCurlyBracket" "}"]]]]]]
          [:innerStatement
           [:statement
            [:staticVariableStatement
             ["Static" "static"]
             [:staticInitializer
              ["VarName" "$n"]
              ["Eq" "="]
              [:expression
               [:chain
                [:chainOrigin
                 [:functionCall
                  [:functionCallName
                   [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "g"]]]]]
                  [:actualArguments
                   [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "end"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :sort-mixed-scalars
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "10"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "9"]]
                ["DoubleQuote" "\""]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "abc"]]
                ["DoubleQuote" "\""]]]]
             ["Comma" ","]
             [:arrayItem [:expression [:constant [:literalConstant ["Real" "1.5"]]]]]
             ["Comma" ","]
             [:arrayItem [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]]
             ["Comma" ","]
             [:arrayItem [:expression [:constant ["Null" "null"]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "sort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-key-float-deprecation
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant ["Real" "1.5"]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'x'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression
                 ["Minus" "-"]
                 [:expression [:constant [:literalConstant ["Real" "1.5"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'y'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :string-offset-read
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$s"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'abc'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$s"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$s"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression
                       ["Minus" "-"]
                       [:expression
                        [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$s"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:string ["SingleQuoteString" "'1'"]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$s"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression
                              [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression
                              [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'x'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'1'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Empty" "empty"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression
                              [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :silence-with-counting-handler
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "h"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter [:variableInitializer ["VarName" "$n"]]]
         ["Comma" ","]
         [:formalParameter [:variableInitializer ["VarName" "$s"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "H"]]
                ["DoubleQuote" "\""]]]
              ["Comma" ","]
              [:expression
               [:chain
                [:chainOrigin
                 [:functionCall
                  [:functionCallName
                   [:qualifiedNamespaceName
                    [:namespaceNameList [:identifier ["Label" "error_reporting"]]]]]
                  [:actualArguments
                   [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
              ["Comma" ","]
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" ";"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName
               [:namespaceNameList [:identifier ["Label" "set_error_handler"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument [:expression [:string ["SingleQuoteString" "'h'"]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          ["SuppressWarnings" "@"]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$u"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:expression
            ["SuppressWarnings" "@"]
            [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$u"]]]]]]]
           ["NullCoalescing" "??"]
           [:expression
            [:string
             ["DoubleQuote" "\""]
             [:interpolatedStringPart ["StringPart" "x"]]
             ["DoubleQuote" "\""]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "done|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName
                [:namespaceNameList [:identifier ["Label" "error_reporting"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :increments
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]
           ["Inc" "++"]]
          ["Comma" ","]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
          ["Comma" ","]
          [:expression
           ["Inc" "++"]
           [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
          ["Comma" ","]
          [:expression
           [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]
           ["Dec" "--"]]
          ["Comma" ","]
          [:expression
           ["Dec" "--"]
           [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$s"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "a"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$s"]]]]]
          ["Inc" "++"]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$s"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$z"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "Zz"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$z"]]]]]
          ["Inc" "++"]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$z"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant ["Null" "null"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]
          ["Inc" "++"]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$m"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant ["Null" "null"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$m"]]]]]
          ["Dec" "--"]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$m"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant ["Real" "1.5"]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]
          ["Dec" "--"]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$big"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_INT_MAX"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$big"]]]]]
          ["Inc" "++"]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$big"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :null-coalescing-and-elvis
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$t"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$t"]]]]]]
           ["QuestionMark" "?"]
           ["Colon" ":"]
           [:expression
            [:string
             ["DoubleQuote" "\""]
             [:interpolatedStringPart ["StringPart" "elvis"]]
             ["DoubleQuote" "\""]]]]
          ["Comma" ","]
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$t"]]]]]]
           ["NullCoalescing" "??"]
           [:expression
            [:string
             ["DoubleQuote" "\""]
             [:interpolatedStringPart ["StringPart" "n"]]
             ["DoubleQuote" "\""]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$q"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$q"]]]]]]
          [:assignmentOperator ["NullCoalescingEqual" "??="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$r"]]]]]]
          [:assignmentOperator ["NullCoalescingEqual" "??="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$q"]]]]]]
          ["Comma" ","]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$r"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :unset-dim-false
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant ["BooleanConstant" "false"]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Unset" "unset"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList [:expression [:string ["SingleQuoteString" "'k'"]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-int-string-abc
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "abc"]]
                   ["DoubleQuote" "\""]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-int-inf
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:stringConstant ["Label" "INF"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :single-quote-raw-nul
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "strlen"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument [:expression [:string ["SingleQuoteString" "'a b'"]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression [:string ["SingleQuoteString" "' '"]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :sort-inconsistent-cmp-departure
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "c"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter [:variableInitializer ["VarName" "$a"]]]
         ["Comma" ","]
         [:formalParameter [:variableInitializer ["VarName" "$b"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              ["OpenRoundBracket" "("]
              [:castOperation ["IntType" "int"]]
              ["CloseRoundBracket" ")"]
              [:expression
               [:parentheses
                ["OpenRoundBracket" "("]
                [:expression
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
                 ["Greater" ">"]
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
                ["CloseRoundBracket" ")"]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "usort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:string
                  ["DoubleQuote" "\""]
                  [:interpolatedStringPart ["StringPart" "c"]]
                  ["DoubleQuote" "\""]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :html-markup-attributes
    [:htmlDocument
     [:inlineHtml
      [:htmlElement ["HtmlOpen" "<"]]
      [:htmlElement ["HtmlName" "p"]]
      [:htmlElement ["HtmlText" " "]]
      [:htmlElement ["HtmlName" "class"]]
      [:htmlElement ["HtmlEquals" "="]]
      [:htmlElement ["HtmlStartDoubleQuoteString" "\""]]
      [:htmlElement ["HtmlDoubleQuoteString" "a"]]
      [:htmlElement ["HtmlEndDoubleQuoteString" "\""]]
      [:htmlElement ["HtmlClose" ">"]]
      [:htmlElement ["HtmlText" "x"]]
      [:htmlElement ["HtmlOpen" "<"]]
      [:htmlElement ["HtmlSlash" "/"]]
      [:htmlElement ["HtmlName" "p"]]
      [:htmlElement ["HtmlClose" ">"]]]
     ["EOF" "<EOF>"]],
    :break-continue-levels
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:forStatement
         ["For" "for"]
         ["OpenRoundBracket" "("]
         [:forInit
          [:expressionList
           [:expression
            [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
            [:assignmentOperator ["Eq" "="]]
            [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]]
         ["SemiColon" ";"]
         [:expressionList
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
           ["Less" "<"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
         ["SemiColon" ";"]
         [:forUpdate
          [:expressionList
           [:expression
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]
            ["Inc" "++"]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:statement
              [:forStatement
               ["For" "for"]
               ["OpenRoundBracket" "("]
               [:forInit
                [:expressionList
                 [:expression
                  [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]]
                  [:assignmentOperator ["Eq" "="]]
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]]
               ["SemiColon" ";"]
               [:expressionList
                [:expression
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]]
                 ["Less" "<"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
               ["SemiColon" ";"]
               [:forUpdate
                [:expressionList
                 [:expression
                  [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]
                  ["Inc" "++"]]]]
               ["CloseRoundBracket" ")"]
               [:statement
                [:blockStatement
                 ["OpenCurlyBracket" "{"]
                 [:innerStatementList
                  [:innerStatement
                   [:statement
                    [:ifStatement
                     ["If" "if"]
                     [:parentheses
                      ["OpenRoundBracket" "("]
                      [:expression
                       [:expression
                        [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]]
                       ["IsEqual" "=="]
                       [:expression
                        [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                      ["CloseRoundBracket" ")"]]
                     [:statement
                      [:continueStatement
                       ["Continue" "continue"]
                       [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
                       ["SemiColon" ";"]]]]]]
                  [:innerStatement
                   [:statement
                    [:ifStatement
                     ["If" "if"]
                     [:parentheses
                      ["OpenRoundBracket" "("]
                      [:expression
                       [:expression
                        [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                       ["IsEqual" "=="]
                       [:expression
                        [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                      ["CloseRoundBracket" ")"]]
                     [:statement
                      [:breakStatement
                       ["Break" "break"]
                       [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
                       ["SemiColon" ";"]]]]]]
                  [:innerStatement
                   [:statement
                    [:echoStatement
                     ["Echo" "echo"]
                     [:expressionList
                      [:expression
                       [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                      ["Comma" ","]
                      [:expression
                       [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]]
                      ["Comma" ","]
                      [:expression
                       [:string
                        ["DoubleQuote" "\""]
                        [:interpolatedStringPart ["StringPart" ";"]]
                        ["DoubleQuote" "\""]]]]
                     ["SemiColon" ";"]]]]]
                 ["CloseCurlyBracket" "}"]]]]]]]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:whileStatement
         ["While" "while"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]
          ["CloseRoundBracket" ")"]]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:statement
              [:expressionStatement
               [:expression
                [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]
                ["Inc" "++"]]
               ["SemiColon" ";"]]]]
            [:innerStatement
             [:statement
              [:ifStatement
               ["If" "if"]
               [:parentheses
                ["OpenRoundBracket" "("]
                [:expression
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
                 ["Less" "<"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                ["CloseRoundBracket" ")"]]
               [:statement [:continueStatement ["Continue" "continue"] ["SemiColon" ";"]]]]]]
            [:innerStatement
             [:statement
              [:ifStatement
               ["If" "if"]
               [:parentheses
                ["OpenRoundBracket" "("]
                [:expression
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
                 ["Greater" ">"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "4"]]]]]]
                ["CloseRoundBracket" ")"]]
               [:statement [:breakStatement ["Break" "break"] ["SemiColon" ";"]]]]]]
            [:innerStatement
             [:statement
              [:echoStatement
               ["Echo" "echo"]
               [:expressionList
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]]
               ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:doWhileStatement
         ["Do" "do"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:statement
              [:echoStatement
               ["Echo" "echo"]
               [:expressionList
                [:expression
                 [:string
                  ["DoubleQuote" "\""]
                  [:interpolatedStringPart ["StringPart" "once"]]
                  ["DoubleQuote" "\""]]]]
               ["SemiColon" ";"]]]]
            [:innerStatement [:statement [:breakStatement ["Break" "break"] ["SemiColon" ";"]]]]
            [:innerStatement
             [:statement
              [:echoStatement
               ["Echo" "echo"]
               [:expressionList
                [:expression
                 [:string
                  ["DoubleQuote" "\""]
                  [:interpolatedStringPart ["StringPart" "never"]]
                  ["DoubleQuote" "\""]]]]
               ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]
         ["While" "while"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]
          ["CloseRoundBracket" ")"]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-key-target-order
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:expression
          [:arrayCreation
           ["OpenSquareBracket" "["]
           [:arrayItemList
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]
             ["DoubleArrow" "=>"]
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]]
           ["CloseSquareBracket" "]"]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain
          [:chainOrigin
           [:chainBase
            [:keyedVariable
             ["VarName" "$a"]
             [:squareCurlyExpression
              ["OpenSquareBracket" "["]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              ["CloseSquareBracket" "]"]]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :builtin-shift-unshift
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]
              ["DoubleArrow" "=>"]
              [:expression [:string ["SingleQuoteString" "'a'"]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'k'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:string ["SingleQuoteString" "'b'"]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]
              ["DoubleArrow" "=>"]
              [:expression [:string ["SingleQuoteString" "'c'"]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "array_shift"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "array_unshift"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["Comma" ","]
               [:actualArgument [:expression [:string ["SingleQuoteString" "'z'"]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-non-array
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList [:expression [:string ["SingleQuoteString" "'x'"]]]]
           ["SemiColon" ";"]]]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList [:expression [:string ["SingleQuoteString" "'done'"]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :static-initializer-recursion
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "g"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "i"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "r"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$d"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:staticVariableStatement
             ["Static" "static"]
             [:staticInitializer
              ["VarName" "$n"]
              ["Eq" "="]
              [:expression
               [:chain
                [:chainOrigin
                 [:functionCall
                  [:functionCallName
                   [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "g"]]]]]
                  [:actualArguments
                   [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:ifStatement
             ["If" "if"]
             [:parentheses
              ["OpenRoundBracket" "("]
              [:expression
               [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$d"]]]]]]
               ["Greater" ">"]
               [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
              ["CloseRoundBracket" ")"]]
             [:statement
              [:blockStatement
               ["OpenCurlyBracket" "{"]
               [:innerStatementList
                [:innerStatement
                 [:statement
                  [:expressionStatement
                   [:expression
                    [:chain
                     [:chainOrigin
                      [:functionCall
                       [:functionCallName
                        [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "r"]]]]]
                       [:actualArguments
                        [:arguments
                         ["OpenRoundBracket" "("]
                         [:actualArgument
                          [:expression
                           [:expression
                            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$d"]]]]]]
                           ["Minus" "-"]
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                         ["CloseRoundBracket" ")"]]]]]]]
                   ["SemiColon" ";"]]]]]
               ["CloseCurlyBracket" "}"]]]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "r"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "r"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-int-neg-inf
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  ["Minus" "-"]
                  [:expression [:constant [:literalConstant [:stringConstant ["Label" "INF"]]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-alt-syntax
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:expression
          [:arrayCreation
           ["OpenSquareBracket" "["]
           [:arrayItemList
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
            ["Comma" ","]
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
           ["CloseSquareBracket" "]"]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         ["Colon" ":"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              ["Comma" ","]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["EndForeach" "endforeach"]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :builtin-by-ref-non-variable
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "g"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:arrayCreation
               ["OpenSquareBracket" "["]
               [:arrayItemList
                [:arrayItem
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                ["Comma" ","]
                [:arrayItem
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
               ["CloseSquareBracket" "]"]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "sort"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  [:chain
                   [:chainOrigin
                    [:functionCall
                     [:functionCallName
                      [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "g"]]]]]
                     [:actualArguments
                      [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :modulo-by-zero
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
           ["Percent" "%"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-spread-renumber
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]
              ["DoubleArrow" "=>"]
              [:expression [:string ["SingleQuoteString" "'x'"]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]
              ["DoubleArrow" "=>"]
              [:expression [:string ["SingleQuoteString" "'y'"]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:arrayCreation
                  ["OpenSquareBracket" "["]
                  [:arrayItemList
                   [:arrayItem
                    ["Ellipsis" "..."]
                    [:expression
                     [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]]
                  ["CloseSquareBracket" "]"]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-dim-of-string-dim
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'x'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:string ["SingleQuoteString" "'str'"]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'x'"]]]
                ["CloseSquareBracket" "]"]]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'x'"]]]
                ["CloseSquareBracket" "]"]]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :by-ref-late-read-sibling
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression
               [:arrayCreation
                ["OpenSquareBracket" "["]
                [:arrayItemList
                 [:arrayItem
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                ["CloseSquareBracket" "]"]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "array_push"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:assignable
                  [:chain
                   [:chainOrigin
                    [:chainBase
                     [:keyedVariable
                      ["VarName" "$a"]
                      [:squareCurlyExpression
                       ["OpenSquareBracket" "["]
                       [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                       ["CloseSquareBracket" "]"]]]]]]]
                 [:assignmentOperator ["Eq" "="]]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-int-string-1e30
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "1e30"]]
                   ["DoubleQuote" "\""]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :intdiv-min-by-minus-one
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  [:constant [:literalConstant [:stringConstant ["Label" "PHP_INT_MIN"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression
                  ["Minus" "-"]
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :html-script-body
    [:htmlDocument
     [:inlineHtml [:htmlElement ["HtmlScriptOpen" "<script"]] [:htmlElement ["HtmlClose" ">"]]]
     [:inlineHtml [:scriptText ["ScriptText" "var a = 1;"]]]
     [:inlineHtml [:htmlElement ["HtmlScriptClose" "</script>"]]]
     ["EOF" "<EOF>"]],
    :pow-negative-underflowing-power
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:expression [:constant [:literalConstant ["Real" "2.0"]]]]
                 ["Pow" "**"]
                 [:expression
                  ["Minus" "-"]
                  [:expression
                   [:constant [:literalConstant [:numericConstant ["Decimal" "1074"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression [:constant [:literalConstant ["Real" "0.5"]]]]
                 ["Pow" "**"]
                 [:expression
                  ["Minus" "-"]
                  [:expression
                   [:constant [:literalConstant [:numericConstant ["Decimal" "2000"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :numeric-string-padding
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "0000000000000000000000012"]]
                   ["DoubleQuote" "\""]]]
                 ["Plus" "+"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "1e0000000000005"]]
                   ["DoubleQuote" "\""]]]
                 ["Plus" "+"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "1e-0000000000005"]]
                   ["DoubleQuote" "\""]]]
                 ["Plus" "+"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :by-ref-late-read
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression
               [:arrayCreation
                ["OpenSquareBracket" "["]
                [:arrayItemList
                 [:arrayItem
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                ["CloseSquareBracket" "]"]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "array_push"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:assignable
                  [:chain
                   [:chainOrigin
                    [:chainBase
                     [:keyedVariable
                      ["VarName" "$a"]
                      [:squareCurlyExpression
                       ["OpenSquareBracket" "["]
                       [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                       ["CloseSquareBracket" "]"]]
                      [:squareCurlyExpression
                       ["OpenSquareBracket" "["]
                       ["CloseSquareBracket" "]"]]]]]]]
                 [:assignmentOperator ["Eq" "="]]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-float-string-1
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "fmod"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:string
                  ["DoubleQuote" "\""]
                  [:interpolatedStringPart ["StringPart" "x"]]
                  ["DoubleQuote" "\""]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :sort-uasort
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "d"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter [:variableInitializer ["VarName" "$x"]]]
         ["Comma" ","]
         [:formalParameter [:variableInitializer ["VarName" "$y"]]]]
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
              ["Spaceship" "<=>"]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "p"]]
                ["DoubleQuote" "\""]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "q"]]
                ["DoubleQuote" "\""]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "r"]]
                ["DoubleQuote" "\""]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "uasort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:string
                  ["DoubleQuote" "\""]
                  [:interpolatedStringPart ["StringPart" "d"]]
                  ["DoubleQuote" "\""]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              [:interpolatedStringPart ["StringPart" "="]]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]]
     ["EOF" "<EOF>"]],
    :silence-intersects-saved-mask
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName
               [:namespaceNameList [:identifier ["Label" "error_reporting"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "8191"]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           ["SuppressWarnings" "@"]
           [:expression
            [:chain
             [:chainOrigin
              [:functionCall
               [:functionCallName
                [:qualifiedNamespaceName
                 [:namespaceNameList [:identifier ["Label" "error_reporting"]]]]]
               [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName
                [:namespaceNameList [:identifier ["Label" "error_reporting"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          ["SuppressWarnings" "@"]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName
                [:namespaceNameList [:identifier ["Label" "error_reporting"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "32767"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName
                [:namespaceNameList [:identifier ["Label" "error_reporting"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :argument-count-error
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter [:variableInitializer ["VarName" "$a"]]]
         ["Comma" ","]
         [:formalParameter [:variableInitializer ["VarName" "$b"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement ["OpenCurlyBracket" "{"] [:innerStatementList] ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "a"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-read-warnings
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'x'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:string ["SingleQuoteString" "'y'"]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant ["Null" "null"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$n"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$i"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:string ["SingleQuoteString" "'k'"]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$t"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$t"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant ["Real" "1.5"]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$f"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$u"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-key-expr-once
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "k"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "k"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:expression
          [:arrayCreation
           ["OpenSquareBracket" "["]
           [:arrayItemList
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
            ["Comma" ","]
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
           ["CloseSquareBracket" "]"]]]
         ["As" "as"]
         [:assignable
          [:chain
           [:chainOrigin
            [:chainBase
             [:keyedVariable
              ["VarName" "$a"]
              [:squareCurlyExpression
               ["OpenSquareBracket" "["]
               [:expression
                [:chain
                 [:chainOrigin
                  [:functionCall
                   [:functionCallName
                    [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "k"]]]]]
                   [:actualArguments
                    [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
               ["CloseSquareBracket" "]"]]]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "count"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :static-initializer-increment
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "g"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList [:expression [:string ["SingleQuoteString" "'init'"]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:staticVariableStatement
             ["Static" "static"]
             [:staticInitializer
              ["VarName" "$n"]
              ["Eq" "="]
              [:expression
               [:chain
                [:chainOrigin
                 [:functionCall
                  [:functionCallName
                   [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "g"]]]]]
                  [:actualArguments
                   [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              ["Inc" "++"]
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-literals
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'k'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "4"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'k'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "6"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]
             ["Comma" ","]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation ["Array" "array"] ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["Array" "array"]
            ["OpenRoundBracket" "("]
            [:arrayItemList
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'x'"]]]
              ["DoubleArrow" "=>"]
              [:expression
               [:arrayCreation
                ["Array" "array"]
                ["OpenRoundBracket" "("]
                [:arrayItemList
                 [:arrayItem
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                 ["Comma" ","]
                 [:arrayItem
                  [:expression
                   [:arrayCreation
                    ["Array" "array"]
                    ["OpenRoundBracket" "("]
                    ["CloseRoundBracket" ")"]]]]]
                ["CloseRoundBracket" ")"]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
            ["CloseRoundBracket" ")"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "10"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "20"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "30"]]]]]]]
            ["CloseSquareBracket" "]"]
            ["OpenSquareBracket" "["]
            [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
            ["CloseSquareBracket" "]"]]]
          ["Comma" ","]
          [:expression
           [:arrayCreation
            ["Array" "array"]
            ["OpenRoundBracket" "("]
            [:arrayItemList
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'p'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:string ["SingleQuoteString" "'q'"]]]]]
            ["CloseRoundBracket" ")"]
            ["OpenSquareBracket" "["]
            [:expression [:string ["SingleQuoteString" "'p'"]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-keyed-target
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:expression
          [:arrayCreation
           ["OpenSquareBracket" "["]
           [:arrayItemList
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]
            ["Comma" ","]
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "8"]]]]]]]
           ["CloseSquareBracket" "]"]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain
          [:chainOrigin
           [:chainBase
            [:keyedVariable
             ["VarName" "$r"]
             [:squareCurlyExpression
              ["OpenSquareBracket" "["]
              [:expression [:string ["SingleQuoteString" "'last'"]]]
              ["CloseSquareBracket" "]"]]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$r"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:string ["SingleQuoteString" "'last'"]]]
                ["CloseSquareBracket" "]"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :string-offset-write
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$s"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'abc'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$s"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'x'"]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-assign-value
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:expression
            [:parentheses
             ["OpenRoundBracket" "("]
             [:expression
              [:assignable
               [:chain
                [:chainOrigin
                 [:chainBase
                  [:keyedVariable
                   ["VarName" "$a"]
                   [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
             ["CloseRoundBracket" ")"]]]
           ["Plus" "+"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$r"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:parentheses
            ["OpenRoundBracket" "("]
            [:expression
             [:assignable
              [:chain
               [:chainOrigin
                [:chainBase
                 [:keyedVariable
                  ["VarName" "$a"]
                  [:squareCurlyExpression
                   ["OpenSquareBracket" "["]
                   [:expression [:string ["SingleQuoteString" "'k'"]]]
                   ["CloseSquareBracket" "]"]]]]]]]
             [:assignmentOperator ["Eq" "="]]
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]
            ["CloseRoundBracket" ")"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$r"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :builtin-by-ref-nested
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression
               [:arrayCreation
                ["OpenSquareBracket" "["]
                [:arrayItemList
                 [:arrayItem
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                 ["Comma" ","]
                 [:arrayItem
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                ["CloseSquareBracket" "]"]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "array_push"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "sort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain
          [:chainOrigin
           [:chainBase
            [:keyedVariable
             ["VarName" "$a"]
             [:squareCurlyExpression
              ["OpenSquareBracket" "["]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
              ["CloseSquareBracket" "]"]]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              [:interpolatedStringPart ["StringPart" "="]]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]]
     ["EOF" "<EOF>"]],
    :string-supplementary-unicode
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:string
                  ["DoubleQuote" "\""]
                  [:interpolatedStringPart ["StringPart" "😀"]]
                  ["DoubleQuote" "\""]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :builtin-count-keys-values
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'x'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]
              ["DoubleArrow" "=>"]
              [:expression [:string ["SingleQuoteString" "'b'"]]]]
             ["Comma" ","]
             [:arrayItem [:expression [:string ["SingleQuoteString" "'c'"]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "count"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression [:string ["SingleQuoteString" "'|'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName
                      [:namespaceNameList [:identifier ["Label" "array_keys"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName
                      [:namespaceNameList [:identifier ["Label" "array_values"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:arrayCreation
                         ["OpenSquareBracket" "["]
                         [:arrayItemList
                          [:arrayItem
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]
                           ["DoubleArrow" "=>"]
                           [:expression [:string ["SingleQuoteString" "'p'"]]]]
                          ["Comma" ","]
                          [:arrayItem
                           [:expression [:string ["SingleQuoteString" "'q'"]]]
                           ["DoubleArrow" "=>"]
                           [:expression [:string ["SingleQuoteString" "'r'"]]]]]
                         ["CloseSquareBracket" "]"]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-nested-escapes
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:expression
          [:arrayCreation
           ["OpenSquareBracket" "["]
           [:arrayItemList
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
            ["Comma" ","]
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
            ["Comma" ","]
            [:arrayItem
             [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
           ["CloseSquareBracket" "]"]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:statement
              [:foreachStatement
               ["Foreach" "foreach"]
               ["OpenRoundBracket" "("]
               [:expression
                [:arrayCreation
                 ["OpenSquareBracket" "["]
                 [:arrayItemList
                  [:arrayItem
                   [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                  ["Comma" ","]
                  [:arrayItem
                   [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                  ["Comma" ","]
                  [:arrayItem
                   [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
                 ["CloseSquareBracket" "]"]]]
               ["As" "as"]
               [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]]
               ["CloseRoundBracket" ")"]
               [:statement
                [:blockStatement
                 ["OpenCurlyBracket" "{"]
                 [:innerStatementList
                  [:innerStatement
                   [:statement
                    [:ifStatement
                     ["If" "if"]
                     [:parentheses
                      ["OpenRoundBracket" "("]
                      [:expression
                       [:expression
                        [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]]
                       ["IsEqual" "=="]
                       [:expression
                        [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                      ["CloseRoundBracket" ")"]]
                     [:statement
                      [:continueStatement
                       ["Continue" "continue"]
                       [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
                       ["SemiColon" ";"]]]]]]
                  [:innerStatement
                   [:statement
                    [:ifStatement
                     ["If" "if"]
                     [:parentheses
                      ["OpenRoundBracket" "("]
                      [:expression
                       [:expression
                        [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                       ["IsEqual" "=="]
                       [:expression
                        [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                      ["CloseRoundBracket" ")"]]
                     [:statement
                      [:breakStatement
                       ["Break" "break"]
                       [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
                       ["SemiColon" ";"]]]]]]
                  [:innerStatement
                   [:statement
                    [:echoStatement
                     ["Echo" "echo"]
                     [:expressionList
                      [:expression
                       [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                      ["Comma" ","]
                      [:expression
                       [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]]
                      ["Comma" ","]
                      [:expression [:string ["SingleQuoteString" "','"]]]]
                     ["SemiColon" ";"]]]]]
                 ["CloseCurlyBracket" "}"]]]]]]]
           ["CloseCurlyBracket" "}"]]]]]]]
     ["EOF" "<EOF>"]],
    :arg-int-nan
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:stringConstant ["Label" "NAN"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :static-initializer-expression
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:staticVariableStatement
             ["Static" "static"]
             [:staticInitializer
              ["VarName" "$n"]
              ["Eq" "="]
              [:expression
               [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
               ["Plus" "+"]
               [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
             ["Comma" ","]
             [:staticInitializer
              ["VarName" "$m"]
              ["Eq" "="]
              [:expression
               [:expression
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
                ["Asterisk" "*"]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
               ["Plus" "+"]
               [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]
              ["Inc" "++"]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$m"]]]]]
              ["Inc" "++"]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
              ["Dot" "."]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$m"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-next-index-saturated
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$d"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_INT_MAX"]]]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$d"]
               [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-to-string
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:expression
            [:expression [:string ["SingleQuoteString" "'x'"]]]
            ["Dot" "."]
            [:expression
             [:arrayCreation
              ["OpenSquareBracket" "["]
              [:arrayItemList
               [:arrayItem
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
              ["CloseSquareBracket" "]"]]]]
           ["Dot" "."]
           [:expression [:string ["SingleQuoteString" "'y'"]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-spread-nested
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:arrayCreation
                  ["OpenSquareBracket" "["]
                  [:arrayItemList
                   [:arrayItem
                    ["Ellipsis" "..."]
                    [:expression
                     [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]
                  ["CloseSquareBracket" "]"]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:arrayCreation
                  ["OpenSquareBracket" "["]
                  [:arrayItemList
                   [:arrayItem
                    [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                   ["Comma" ","]
                   [:arrayItem
                    ["Ellipsis" "..."]
                    [:expression
                     [:arrayCreation
                      ["OpenSquareBracket" "["]
                      [:arrayItemList
                       [:arrayItem
                        [:expression
                         [:arrayCreation
                          ["OpenSquareBracket" "["]
                          [:arrayItemList
                           [:arrayItem
                            [:expression
                             [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                           ["Comma" ","]
                           [:arrayItem
                            [:expression
                             [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
                          ["CloseSquareBracket" "]"]]]]]
                      ["CloseSquareBracket" "]"]]]]
                   ["Comma" ","]
                   [:arrayItem
                    ["Ellipsis" "..."]
                    [:expression
                     [:arrayCreation
                      ["OpenSquareBracket" "["]
                      [:arrayItemList
                       [:arrayItem
                        [:expression
                         [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]
                      ["CloseSquareBracket" "]"]]]]]
                  ["CloseSquareBracket" "]"]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :html-close-tag-blank-line
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement [:statement [:emptyStatement_ ["SemiColon" "?>\n"]]]]
      [:topStatement
       [:statement
        [:inlineHtmlStatement
         [:inlineHtml
          [:htmlElement ["HtmlText" "\n"]]
          [:htmlElement ["HtmlOpen" "<"]]
          [:htmlElement ["HtmlName" "p"]]
          [:htmlElement ["HtmlClose" ">"]]]]]]]
     ["EOF" "<EOF>"]],
    :sort-rsort-stable
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem [:expression [:constant [:literalConstant ["Real" "1.0"]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "1"]]
                ["DoubleQuote" "\""]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "rsort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arg-int-1e30
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument [:expression [:constant [:literalConstant ["Real" "1e30"]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :builtin-array-merge
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName
                      [:namespaceNameList [:identifier ["Label" "array_merge"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:arrayCreation
                         ["OpenSquareBracket" "["]
                         [:arrayItemList
                          [:arrayItem
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]
                           ["DoubleArrow" "=>"]
                           [:expression [:string ["SingleQuoteString" "'a'"]]]]
                          ["Comma" ","]
                          [:arrayItem
                           [:expression [:string ["SingleQuoteString" "'k'"]]]
                           ["DoubleArrow" "=>"]
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                         ["CloseSquareBracket" "]"]]]]
                      ["Comma" ","]
                      [:actualArgument
                       [:expression
                        [:arrayCreation
                         ["OpenSquareBracket" "["]
                         [:arrayItemList
                          [:arrayItem
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]
                           ["DoubleArrow" "=>"]
                           [:expression [:string ["SingleQuoteString" "'b'"]]]]
                          ["Comma" ","]
                          [:arrayItem
                           [:expression [:string ["SingleQuoteString" "'k'"]]]
                           ["DoubleArrow" "=>"]
                           [:expression
                            [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
                         ["CloseSquareBracket" "]"]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-sort-source-inside
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:statement
              [:expressionStatement
               [:expression
                [:chain
                 [:chainOrigin
                  [:functionCall
                   [:functionCallName
                    [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "sort"]]]]]
                   [:actualArguments
                    [:arguments
                     ["OpenRoundBracket" "("]
                     [:actualArgument
                      [:expression
                       [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                     ["CloseRoundBracket" ")"]]]]]]]
               ["SemiColon" ";"]]]]
            [:innerStatement
             [:statement
              [:echoStatement
               ["Echo" "echo"]
               [:expressionList
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
               ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]]
     ["EOF" "<EOF>"]],
    :local-scope
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "global"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$p"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$l"]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression
               [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$p"]]]]]]
               ["Dot" "."]
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "!"]]
                 ["DoubleQuote" "\""]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$l"]]]]]]
              ["Dot" "."]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  [:string
                   ["DoubleQuote" "\""]
                   [:interpolatedStringPart ["StringPart" "a"]]
                   ["DoubleQuote" "\""]]]]
                ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]
          ["Comma" ","]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :mixed-text-and-code
    [:htmlDocument
     [:inlineHtml [:htmlElement ["HtmlText" "a "]]]
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement [:statement [:emptyStatement_ ["SemiColon" "?>"]]]]
      [:topStatement
       [:statement [:inlineHtmlStatement [:inlineHtml [:htmlElement ["HtmlText" "b "]]]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "<?="]
         [:expressionList
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
         ["SemiColon" "?>"]]]]
      [:topStatement
       [:statement [:inlineHtmlStatement [:inlineHtml [:htmlElement ["HtmlText" "c"]]]]]]]
     ["EOF" "<EOF>"]],
    :close-tag-newline
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" "?>\n"]]]]
      [:topStatement
       [:statement [:inlineHtmlStatement [:inlineHtml [:htmlElement ["HtmlText" "x"]]]]]]]
     ["EOF" "<EOF>"]],
    :echo-scalars
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
           ["Plus" "+"]
           [:expression
            [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
            ["Asterisk" "*"]
            [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "x"]]
            ["DoubleQuote" "\""]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant ["Real" "2.5"]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant ["BooleanConstant" "false"]]]]
          ["Comma" ","]
          [:expression [:constant ["Null" "null"]]]
          ["Comma" ","]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "|"]]
            ["DoubleQuote" "\""]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :string-offset-peek
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$s"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'abc'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:constant [:literalConstant ["BooleanConstant" "false"]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:constant ["Null" "null"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:constant [:literalConstant ["Real" "1.0"]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:constant [:literalConstant ["Real" "1.5"]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression
                              ["Minus" "-"]
                              [:expression
                               [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression
                              ["Minus" "-"]
                              [:expression
                               [:constant [:literalConstant [:numericConstant ["Decimal" "4"]]]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression
                              [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "'1.5'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["IsSet" "isset"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:string ["SingleQuoteString" "' 1'"]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$z"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'0b'"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Empty" "empty"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$z"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Empty" "empty"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$z"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression
                              [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:functionCall
                    [:functionCallName
                     [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Empty" "empty"]]]]]
                    [:actualArguments
                     [:arguments
                      ["OpenRoundBracket" "("]
                      [:actualArgument
                       [:expression
                        [:chain
                         [:chainOrigin
                          [:chainBase
                           [:keyedVariable
                            ["VarName" "$s"]
                            [:squareCurlyExpression
                             ["OpenSquareBracket" "["]
                             [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]
                             ["CloseSquareBracket" "]"]]]]]]]]
                      ["CloseRoundBracket" ")"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :sort-ksort
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'b'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'a'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:string ["SingleQuoteString" "'c'"]]]
              ["DoubleArrow" "=>"]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "ksort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              [:interpolatedStringPart ["StringPart" "="]]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "krsort"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:echoStatement
           ["Echo" "echo"]
           [:expressionList
            [:expression
             [:string
              ["DoubleQuote" "\""]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
              [:interpolatedStringPart ["StringPart" "="]]
              [:interpolatedStringPart
               [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
              [:interpolatedStringPart ["StringPart" ","]]
              ["DoubleQuote" "\""]]]]
           ["SemiColon" ";"]]]]]]]
     ["EOF" "<EOF>"]],
    :unset-dim-true
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$t"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant ["BooleanConstant" "true"]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Unset" "unset"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$t"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :html-comment
    [:htmlDocument [:inlineHtml [:htmlElement ["HtmlText" "<!-- c -->"]]] ["EOF" "<EOF>"]],
    :switch
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:switchStatement
         ["Switch" "switch"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
          ["CloseRoundBracket" ")"]]
         ["OpenCurlyBracket" "{"]
         [:switchBlock
          ["Case" "case"]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
          ["Colon" ":"]
          [:innerStatementList
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "one"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]]]
         [:switchBlock
          ["Case" "case"]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
          ["Colon" ":"]
          [:innerStatementList
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "two"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]]]
         [:switchBlock
          ["Case" "case"]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]
          ["Colon" ":"]
          [:innerStatementList
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "three"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]
           [:innerStatement [:statement [:breakStatement ["Break" "break"] ["SemiColon" ";"]]]]
           [:innerStatement [:statement [:identifier ["Default" "default"]] ["Colon" ":"]]]
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "dflt"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:switchStatement
         ["Switch" "switch"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "x"]]
            ["DoubleQuote" "\""]]]
          ["CloseRoundBracket" ")"]]
         ["OpenCurlyBracket" "{"]
         [:switchBlock
          ["Case" "case"]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
          ["Colon" ":"]
          [:innerStatementList
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "one"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]
           [:innerStatement [:statement [:breakStatement ["Break" "break"] ["SemiColon" ";"]]]]
           [:innerStatement [:statement [:identifier ["Default" "default"]] ["Colon" ":"]]]
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "dflt"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]]]
         [:switchBlock
          ["Case" "case"]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
          ["Colon" ":"]
          [:innerStatementList
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "two"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:switchStatement
         ["Switch" "switch"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression
           [:string
            ["DoubleQuote" "\""]
            [:interpolatedStringPart ["StringPart" "2"]]
            ["DoubleQuote" "\""]]]
          ["CloseRoundBracket" ")"]]
         ["OpenCurlyBracket" "{"]
         [:switchBlock
          ["Case" "case"]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
          ["Colon" ":"]
          [:innerStatementList
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "a"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]
           [:innerStatement [:statement [:breakStatement ["Break" "break"] ["SemiColon" ";"]]]]]]
         [:switchBlock
          ["Case" "case"]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
          ["Colon" ":"]
          [:innerStatementList
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "loose"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]
           [:innerStatement [:statement [:breakStatement ["Break" "break"] ["SemiColon" ";"]]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:forStatement
         ["For" "for"]
         ["OpenRoundBracket" "("]
         [:forInit
          [:expressionList
           [:expression
            [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
            [:assignmentOperator ["Eq" "="]]
            [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]]
         ["SemiColon" ";"]
         [:expressionList
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
           ["Less" "<"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
         ["SemiColon" ";"]
         [:forUpdate
          [:expressionList
           [:expression
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]
            ["Inc" "++"]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:statement
              [:switchStatement
               ["Switch" "switch"]
               [:parentheses
                ["OpenRoundBracket" "("]
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                ["CloseRoundBracket" ")"]]
               ["OpenCurlyBracket" "{"]
               [:switchBlock
                ["Case" "case"]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                ["Colon" ":"]
                [:innerStatementList
                 [:innerStatement
                  [:statement
                   [:continueStatement
                    ["Continue" "continue"]
                    [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
                    ["SemiColon" ";"]]]]
                 [:innerStatement [:statement [:identifier ["Default" "default"]] ["Colon" ":"]]]
                 [:innerStatement
                  [:statement
                   [:echoStatement
                    ["Echo" "echo"]
                    [:expressionList
                     [:expression
                      [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]]
                    ["SemiColon" ";"]]]]]]
               ["CloseCurlyBracket" "}"]]]]
            [:innerStatement
             [:statement
              [:echoStatement
               ["Echo" "echo"]
               [:expressionList
                [:expression
                 [:string
                  ["DoubleQuote" "\""]
                  [:interpolatedStringPart ["StringPart" ","]]
                  ["DoubleQuote" "\""]]]]
               ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:switchStatement
         ["Switch" "switch"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]
          ["CloseRoundBracket" ")"]]
         ["OpenCurlyBracket" "{"]
         [:switchBlock
          ["Case" "case"]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
          ["Colon" ":"]
          [:innerStatementList
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "no"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:switchStatement
         ["Switch" "switch"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]
          ["CloseRoundBracket" ")"]]
         ["OpenCurlyBracket" "{"]
         [:switchBlock
          ["Default" "default"]
          ["Colon" ":"]
          [:innerStatementList
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "d"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]
           [:innerStatement [:statement [:breakStatement ["Break" "break"] ["SemiColon" ";"]]]]]]
         [:switchBlock
          ["Case" "case"]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]
          ["Colon" ":"]
          [:innerStatementList
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "three"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]]]
         ["CloseCurlyBracket" "}"]]]]]
     ["EOF" "<EOF>"]],
    :if-elseif-else
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:forStatement
         ["For" "for"]
         ["OpenRoundBracket" "("]
         [:forInit
          [:expressionList
           [:expression
            [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
            [:assignmentOperator ["Eq" "="]]
            [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]]
         ["SemiColon" ";"]
         [:expressionList
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
           ["Less" "<"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "4"]]]]]]]
         ["SemiColon" ";"]
         [:forUpdate
          [:expressionList
           [:expression
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]
            ["Inc" "++"]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement
             [:statement
              [:ifStatement
               ["If" "if"]
               [:parentheses
                ["OpenRoundBracket" "("]
                [:expression
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                 ["IsEqual" "=="]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
                ["CloseRoundBracket" ")"]]
               [:statement
                [:blockStatement
                 ["OpenCurlyBracket" "{"]
                 [:innerStatementList
                  [:innerStatement
                   [:statement
                    [:echoStatement
                     ["Echo" "echo"]
                     [:expressionList
                      [:expression
                       [:string
                        ["DoubleQuote" "\""]
                        [:interpolatedStringPart ["StringPart" "zero"]]
                        ["DoubleQuote" "\""]]]]
                     ["SemiColon" ";"]]]]]
                 ["CloseCurlyBracket" "}"]]]
               [:elseIfStatement
                ["ElseIf" "elseif"]
                [:parentheses
                 ["OpenRoundBracket" "("]
                 [:expression
                  [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                  ["IsEqual" "=="]
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                 ["CloseRoundBracket" ")"]]
                [:statement
                 [:echoStatement
                  ["Echo" "echo"]
                  [:expressionList
                   [:expression
                    [:string
                     ["DoubleQuote" "\""]
                     [:interpolatedStringPart ["StringPart" "one"]]
                     ["DoubleQuote" "\""]]]]
                  ["SemiColon" ";"]]]]
               [:elseStatement
                ["Else" "else"]
                [:statement
                 [:ifStatement
                  ["If" "if"]
                  [:parentheses
                   ["OpenRoundBracket" "("]
                   [:expression
                    [:expression
                     [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
                    ["IsEqual" "=="]
                    [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                   ["CloseRoundBracket" ")"]]
                  [:statement
                   [:blockStatement
                    ["OpenCurlyBracket" "{"]
                    [:innerStatementList
                     [:innerStatement
                      [:statement
                       [:echoStatement
                        ["Echo" "echo"]
                        [:expressionList
                         [:expression
                          [:string
                           ["DoubleQuote" "\""]
                           [:interpolatedStringPart ["StringPart" "two"]]
                           ["DoubleQuote" "\""]]]]
                        ["SemiColon" ";"]]]]]
                    ["CloseCurlyBracket" "}"]]]
                  [:elseStatement
                   ["Else" "else"]
                   [:statement
                    [:blockStatement
                     ["OpenCurlyBracket" "{"]
                     [:innerStatementList
                      [:innerStatement
                       [:statement
                        [:echoStatement
                         ["Echo" "echo"]
                         [:expressionList
                          [:expression
                           [:string
                            ["DoubleQuote" "\""]
                            [:interpolatedStringPart ["StringPart" "many"]]
                            ["DoubleQuote" "\""]]]]
                         ["SemiColon" ";"]]]]]
                     ["CloseCurlyBracket" "}"]]]]]]]]]]
            [:innerStatement
             [:statement
              [:echoStatement
               ["Echo" "echo"]
               [:expressionList
                [:expression
                 [:string
                  ["DoubleQuote" "\""]
                  [:interpolatedStringPart ["StringPart" ","]]
                  ["DoubleQuote" "\""]]]]
               ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]
      [:topStatement
       [:statement
        [:ifStatement
         ["If" "if"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
           ["Greater" ">"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
          ["CloseRoundBracket" ")"]]
         ["Colon" ":"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "alt"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]]
         [:elseIfColonStatement
          ["ElseIf" "elseif"]
          [:parentheses
           ["OpenRoundBracket" "("]
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
           ["CloseRoundBracket" ")"]]
          ["Colon" ":"]
          [:innerStatementList
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "no"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]
           [:innerStatement [:statement [:identifier ["Else" "else"]] ["Colon" ":"]]]
           [:innerStatement
            [:statement
             [:echoStatement
              ["Echo" "echo"]
              [:expressionList
               [:expression
                [:string
                 ["DoubleQuote" "\""]
                 [:interpolatedStringPart ["StringPart" "no"]]
                 ["DoubleQuote" "\""]]]]
              ["SemiColon" ";"]]]]]]
         ["EndIf" "endif"]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:ifStatement
         ["If" "if"]
         [:parentheses
          ["OpenRoundBracket" "("]
          [:expression
           [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
           ["Less" "<"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
          ["CloseRoundBracket" ")"]]
         ["Colon" ":"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "no"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement [:statement [:identifier ["Else" "else"]] ["Colon" ":"]]]
          [:innerStatement
           [:statement
            [:echoStatement
             ["Echo" "echo"]
             [:expressionList
              [:expression
               [:string
                ["DoubleQuote" "\""]
                [:interpolatedStringPart ["StringPart" "-else"]]
                ["DoubleQuote" "\""]]]]
             ["SemiColon" ";"]]]]]
         ["EndIf" "endif"]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :echo-floats
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression
           [:expression [:constant [:literalConstant ["Real" "0.1"]]]]
           ["Plus" "+"]
           [:expression [:constant [:literalConstant ["Real" "0.2"]]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant ["Real" "1e100"]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
          ["Comma" ","]
          [:expression ["Minus" "-"] [:expression [:constant [:literalConstant ["Real" "0.0"]]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
          ["Comma" ","]
          [:expression
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
           ["Divide" "/"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant ["Real" "100000000000000000000.0"]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant ["Real" "2.5"]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant ["Real" "1e14"]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant ["Real" "1e13"]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant ["Real" "0.0001"]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant ["Real" "0.00001"]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
          ["Comma" ","]
          [:expression
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]
           ["Divide" "/"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
          ["Comma" ","]
          [:expression
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "6"]]]]]
           ["Divide" "/"]
           [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
          ["Comma" ","]
          [:expression [:constant [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-spread-mixed
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem [:expression [:string ["SingleQuoteString" "'p'"]]]]
             ["Comma" ","]
             [:arrayItem [:expression [:string ["SingleQuoteString" "'q'"]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:arrayCreation
                  ["OpenSquareBracket" "["]
                  [:arrayItemList
                   [:arrayItem
                    [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
                   ["Comma" ","]
                   [:arrayItem
                    ["Ellipsis" "..."]
                    [:expression
                     [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                   ["Comma" ","]
                   [:arrayItem
                    [:expression [:string ["SingleQuoteString" "'k'"]]]
                    ["DoubleArrow" "=>"]
                    [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                   ["Comma" ","]
                   [:arrayItem
                    ["Ellipsis" "..."]
                    [:expression
                     [:arrayCreation
                      ["OpenSquareBracket" "["]
                      [:arrayItemList
                       [:arrayItem
                        [:expression [:string ["SingleQuoteString" "'k'"]]]
                        ["DoubleArrow" "=>"]
                        [:expression
                         [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                       ["Comma" ","]
                       [:arrayItem
                        [:expression
                         [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]
                        ["DoubleArrow" "=>"]
                        [:expression [:string ["SingleQuoteString" "'z'"]]]]]
                      ["CloseSquareBracket" "]"]]]]]
                  ["CloseSquareBracket" "]"]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-order-after-unset
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Unset" "unset"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$a"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$a"]
               [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Unset" "unset"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression
                 [:chain
                  [:chainOrigin
                   [:chainBase
                    [:keyedVariable
                     ["VarName" "$b"]
                     [:squareCurlyExpression
                      ["OpenSquareBracket" "["]
                      [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
                      ["CloseSquareBracket" "]"]]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
           [:chain
            [:chainOrigin
             [:chainBase
              [:keyedVariable
               ["VarName" "$b"]
               [:squareCurlyExpression ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]]})
