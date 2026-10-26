(ns yang.php.antlr.packets-p3b
  "CST packets of the enabled Stage 0 fixtures (`programs-p3b`), checked in so
   the Node and Dart lanes lower them without a parser. Regenerate from
   `yang.php.antlr.cst` when the grammar package moves or a fixture is enabled.")


(def forms
  '{:closure-bind-class-seam
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:lambdaFunctionExpr
            ["Function_" "function"]
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
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                 ["SemiColon" ";"]]]]]
             ["CloseCurlyBracket" "}"]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:classConstant
                [:qualifiedStaticTypeRef
                 [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "Closure"]]]]]
                ["DoubleColon" "::"]
                [:identifier ["Label" "bind"]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]]
                ["Comma" ","]
                [:actualArgument [:expression [:constant ["Null" "null"]]]]
                ["Comma" ","]
                [:actualArgument [:expression [:constant ["Null" "null"]]]]
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
              [:functionCallName [:chainBase [:keyedVariable ["VarName" "$g"]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-ref-leftover-binding
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
         ["Ampersand" "&"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList]
           ["CloseCurlyBracket" "}"]]]]]]
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
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "implode"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument [:expression [:string ["SingleQuoteString" "','"]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :list-short-positional
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:arrayDestructuring
           ["OpenSquareBracket" "["]
           [:indexedDestructItem
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
           ["Comma" ","]
           [:indexedDestructItem
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
           ["CloseSquareBracket" "]"]]
          ["Eq" "="]
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
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          ["Comma" ","]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-static-rebind-leaves-persistent
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "c"]]
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
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "100"]]]]]]
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
              [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
              ["Eq" "="]
              ["Ampersand" "&"]
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]
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
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "c"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
          ["Comma" ","]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "c"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :closure-use-by-reference
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
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:lambdaFunctionExpr
            ["Function_" "function"]
            ["OpenRoundBracket" "("]
            [:formalParameterList]
            ["CloseRoundBracket" ")"]
            [:lambdaFunctionUseVars
             ["Use" "use"]
             ["OpenRoundBracket" "("]
             [:lambdaFunctionUseVar ["Ampersand" "&"] ["VarName" "$x"]]
             ["CloseRoundBracket" ")"]]
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
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
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
              [:functionCallName [:chainBase [:keyedVariable ["VarName" "$f"]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :call-spread-positional
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
                 ["Ellipsis" "..."]
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
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :list-keyed
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:arrayDestructuring
           ["OpenSquareBracket" "["]
           [:keyedDestructItem
            [:expression [:string ["SingleQuoteString" "'x'"]]]
            ["DoubleArrow" "=>"]
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$p"]]]]]]
           ["Comma" ","]
           [:keyedDestructItem
            [:expression [:string ["SingleQuoteString" "'y'"]]]
            ["DoubleArrow" "=>"]
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$q"]]]]]]
           ["CloseSquareBracket" "]"]]
          ["Eq" "="]
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
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$p"]]]]]]
          ["Comma" ","]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$q"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :list-null-source-assigns-null
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:arrayDestructuring
           ["OpenSquareBracket" "["]
           [:indexedDestructItem
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
           ["Comma" ","]
           [:indexedDestructItem
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
           ["CloseSquareBracket" "]"]]
          ["Eq" "="]
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
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-pointer-copied-with-value
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
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "next"]]]]]
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
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
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
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "current"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-ref-append-while-iterating
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
        [:foreachStatement
         ["Foreach" "foreach"]
         ["OpenRoundBracket" "("]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]
         ["As" "as"]
         ["Ampersand" "&"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
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
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
                 ["Less" "<"]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                ["CloseRoundBracket" ")"]]
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
                        [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
                       ["Plus" "+"]
                       [:expression
                        [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
                     ["SemiColon" ";"]]]]]
                 ["CloseCurlyBracket" "}"]]]]]]
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
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-global-at-root-noop
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:globalStatement ["Global" "global"] [:globalVar ["VarName" "$g"]] ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :closure-use-by-value
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
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:lambdaFunctionExpr
            ["Function_" "function"]
            ["OpenRoundBracket" "("]
            [:formalParameterList]
            ["CloseRoundBracket" ")"]
            [:lambdaFunctionUseVars
             ["Use" "use"]
             ["OpenRoundBracket" "("]
             [:lambdaFunctionUseVar ["VarName" "$x"]]
             ["CloseRoundBracket" ")"]]
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
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
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
              [:functionCallName [:chainBase [:keyedVariable ["VarName" "$f"]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-global-binds-local
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
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
            [:globalStatement ["Global" "global"] [:globalVar ["VarName" "$g"]] ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
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
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :call-variadic-named-collected
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "s"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter ["Ellipsis" "..."] [:variableInitializer ["VarName" "$xs"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:chain
               [:chainOrigin
                [:functionCall
                 [:functionCallName
                  [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "implode"]]]]]
                 [:actualArguments
                  [:arguments
                   ["OpenRoundBracket" "("]
                   [:actualArgument [:expression [:string ["SingleQuoteString" "','"]]]]
                   ["Comma" ","]
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
                            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$xs"]]]]]]]
                          ["CloseRoundBracket" ")"]]]]]]]]
                   ["CloseRoundBracket" ")"]]]]]]]
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
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:argumentName [:identifier ["Label" "k"]] ["Colon" ":"]]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :call-dynamic-chained
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$mk"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:lambdaFunctionExpr
            ["LambdaFn" "fn"]
            ["OpenRoundBracket" "("]
            [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$x"]]]]
            ["CloseRoundBracket" ")"]
            ["DoubleArrow" "=>"]
            [:expression
             [:lambdaFunctionExpr
              ["LambdaFn" "fn"]
              ["OpenRoundBracket" "("]
              [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$y"]]]]
              ["CloseRoundBracket" ")"]
              ["DoubleArrow" "=>"]
              [:expression
               [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
               ["Plus" "+"]
               [:expression
                [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]]]]]]]
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
              [:functionCallName [:chainBase [:keyedVariable ["VarName" "$mk"]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-unset-breaks-binding
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
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]
          ["Eq" "="]
          ["Ampersand" "&"]
          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
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
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
          ["Comma" ","]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :list-skip
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          ["List" "list"]
          ["OpenRoundBracket" "("]
          [:assignmentList
           [:assignmentListElement
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
           ["Comma" ","]
           ["Comma" ","]
           [:assignmentListElement
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]]
          ["CloseRoundBracket" ")"]
          ["Eq" "="]
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
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          ["Comma" ","]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-element-target
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
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          ["Eq" "="]
          ["Ampersand" "&"]
          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
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
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "4"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-static-binds-persistent-slot
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "c"]]
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
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
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
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "c"]]]]]
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
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "c"]]]]]
             [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
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
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "c"]]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-self-assign
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
          ["Eq" "="]
          ["Ampersand" "&"]
          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-ref-unset-current
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
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
         ["DoubleArrow" "=>"]
         ["Ampersand" "&"]
         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]
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
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
                 ["IsEqual" "=="]
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
                          [:qualifiedNamespaceName
                           [:namespaceNameList [:identifier ["Unset" "unset"]]]]]
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
                                   [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                                  ["CloseSquareBracket" "]"]]]]]]]]
                           ["CloseRoundBracket" ")"]]]]]]]
                     ["SemiColon" ";"]]]]]
                 ["CloseCurlyBracket" "}"]]]]]]
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
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :list-writes-through-fetches
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$t"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:arrayCreation ["OpenSquareBracket" "["] ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:arrayDestructuring
           ["OpenSquareBracket" "["]
           [:indexedDestructItem
            [:chain
             [:chainOrigin
              [:chainBase
               [:keyedVariable
                ["VarName" "$t"]
                [:squareCurlyExpression
                 ["OpenSquareBracket" "["]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                 ["CloseSquareBracket" "]"]]]]]]]
           ["Comma" ","]
           [:indexedDestructItem
            [:chain
             [:chainOrigin
              [:chainBase
               [:keyedVariable
                ["VarName" "$t"]
                [:squareCurlyExpression
                 ["OpenSquareBracket" "["]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                 ["CloseSquareBracket" "]"]]]]]]]
           ["CloseSquareBracket" "]"]]
          ["Eq" "="]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "8"]]]]]]]
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
               ["VarName" "$t"]
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
               ["VarName" "$t"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-param-array-element
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter ["Ampersand" "&"] [:variableInitializer ["VarName" "$n"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]
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
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
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
                ["CloseSquareBracket" "]"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :closure-use-by-reference-writes
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
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:lambdaFunctionExpr
            ["Function_" "function"]
            ["OpenRoundBracket" "("]
            [:formalParameterList]
            ["CloseRoundBracket" ")"]
            [:lambdaFunctionUseVars
             ["Use" "use"]
             ["OpenRoundBracket" "("]
             [:lambdaFunctionUseVar ["Ampersand" "&"] ["VarName" "$x"]]
             ["CloseRoundBracket" ")"]]
            [:blockStatement
             ["OpenCurlyBracket" "{"]
             [:innerStatementList
              [:innerStatement
               [:statement
                [:expressionStatement
                 [:expression
                  [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]
                  ["Inc" "++"]]
                 ["SemiColon" ";"]]]]]
             ["CloseCurlyBracket" "}"]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName [:chainBase [:keyedVariable ["VarName" "$f"]]]]
             [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName [:chainBase [:keyedVariable ["VarName" "$f"]]]]
             [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-vivify-by-reference
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$r"]]]]]]
          ["Eq" "="]
          ["Ampersand" "&"]
          [:chain
           [:chainOrigin
            [:chainBase
             [:keyedVariable
              ["VarName" "$a"]
              [:squareCurlyExpression
               ["OpenSquareBracket" "["]
               [:expression [:string ["SingleQuoteString" "'k'"]]]
               ["CloseSquareBracket" "]"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$r"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
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
                [:expression [:string ["SingleQuoteString" "'k'"]]]
                ["CloseSquareBracket" "]"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :call-unknown-named-uncaught
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$a"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
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
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
               ["Comma" ","]
               [:actualArgument
                [:argumentName [:identifier ["Label" "z"]] ["Colon" ":"]]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :list-nested
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable
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
                   [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                 ["Comma" ","]
                 [:arrayItem
                  [:expression
                   [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]]
                ["CloseSquareBracket" "]"]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]]]
            ["CloseSquareBracket" "]"]]]
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
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                 ["Comma" ","]
                 [:arrayItem
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
                ["CloseSquareBracket" "]"]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          ["Comma" ","]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
          ["Comma" ","]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-alias-write
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
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]
          ["Eq" "="]
          ["Ampersand" "&"]
          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :call-unknown-named-argument
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$a"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:tryCatchFinally
         ["Try" "try"]
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
                   [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
                  [:actualArguments
                   [:arguments
                    ["OpenRoundBracket" "("]
                    [:actualArgument
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                    ["Comma" ","]
                    [:actualArgument
                     [:argumentName [:identifier ["Label" "z"]] ["Colon" ":"]]
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                    ["CloseRoundBracket" ")"]]]]]]]
              ["SemiColon" ";"]]]]]
          ["CloseCurlyBracket" "}"]]
         [:catchClause
          ["Catch" "catch"]
          ["OpenRoundBracket" "("]
          [:qualifiedStaticTypeRef
           [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "Error"]]]]]
          ["VarName" "$e"]
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
                 [:chain
                  [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$e"]]]]
                  [:memberAccess
                   ["ObjectOperator" "->"]
                   [:keyedFieldName [:keyedSimpleFieldName [:identifier ["Label" "getMessage"]]]]
                   [:actualArguments
                    [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
               ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]]
     ["EOF" "<EOF>"]],
    :list-swap
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:arrayDestructuring
           ["OpenSquareBracket" "["]
           [:indexedDestructItem
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
           ["Comma" ","]
           [:indexedDestructItem
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
           ["CloseSquareBracket" "]"]]
          ["Eq" "="]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
             ["Comma" ","]
             [:arrayItem
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          ["Comma" ","]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-param-by-value-is-a-copy
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$n"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
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
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-cycle-unset-terminates
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
          ["Eq" "="]
          ["Ampersand" "&"]
          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
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
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList [:expression [:string ["SingleQuoteString" "'done'"]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-array-literal-nested-path
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
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
                ["CloseSquareBracket" "]"]]]]]
            ["CloseSquareBracket" "]"]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$r"]]]]]]
          ["Eq" "="]
          ["Ampersand" "&"]
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
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$r"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "7"]]]]]]
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
                ["CloseSquareBracket" "]"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :call-variadic-collects
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "s"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter ["Ellipsis" "..."] [:variableInitializer ["VarName" "$xs"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
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
                   [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "count"]]]]]
                  [:actualArguments
                   [:arguments
                    ["OpenRoundBracket" "("]
                    [:actualArgument
                     [:expression
                      [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$xs"]]]]]]]
                    ["CloseRoundBracket" ")"]]]]]]]
              ["Dot" "."]
              [:expression
               [:chain
                [:chainOrigin
                 [:functionCall
                  [:functionCallName
                   [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "array_sum"]]]]]
                  [:actualArguments
                   [:arguments
                    ["OpenRoundBracket" "("]
                    [:actualArgument
                     [:expression
                      [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$xs"]]]]]]]
                    ["CloseRoundBracket" ")"]]]]]]]]
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
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :list-by-reference-element
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$arr"]]]]]]
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
          [:arrayDestructuring
           ["OpenSquareBracket" "["]
           [:indexedDestructItem
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
           ["Comma" ","]
           [:indexedDestructItem
            ["Ampersand" "&"]
            [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
           ["CloseSquareBracket" "]"]]
          ["Eq" "="]
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$arr"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]]
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
               ["VarName" "$arr"]
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-ref-doubles
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
         ["Ampersand" "&"]
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
                [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
                [:assignmentOperator ["MulEqual" "*="]]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
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
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Unset" "unset"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
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
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "implode"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument [:expression [:string ["SingleQuoteString" "','"]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :call-too-few-named-not-passed
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
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:tryCatchFinally
         ["Try" "try"]
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
                   [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
                  [:actualArguments
                   [:arguments
                    ["OpenRoundBracket" "("]
                    [:actualArgument
                     [:argumentName [:identifier ["Label" "b"]] ["Colon" ":"]]
                     [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                    ["CloseRoundBracket" ")"]]]]]]]
              ["SemiColon" ";"]]]]]
          ["CloseCurlyBracket" "}"]]
         [:catchClause
          ["Catch" "catch"]
          ["OpenRoundBracket" "("]
          [:qualifiedStaticTypeRef
           [:qualifiedNamespaceName
            [:namespaceNameList [:identifier ["Label" "ArgumentCountError"]]]]]
          ["VarName" "$e"]
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
                 [:chain
                  [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$e"]]]]
                  [:memberAccess
                   ["ObjectOperator" "->"]
                   [:keyedFieldName [:keyedSimpleFieldName [:identifier ["Label" "getMessage"]]]]
                   [:actualArguments
                    [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
               ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]]
     ["EOF" "<EOF>"]],
    :foreach-ref-break-frees-iterator
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
         ["Ampersand" "&"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
         ["CloseRoundBracket" ")"]
         [:statement
          [:blockStatement
           ["OpenCurlyBracket" "{"]
           [:innerStatementList
            [:innerStatement [:statement [:breakStatement ["Break" "break"] ["SemiColon" ";"]]]]]
           ["CloseCurlyBracket" "}"]]]]]]
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
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
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
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "4"]]]]]]
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
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-cycle-count
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
          ["Eq" "="]
          ["Ampersand" "&"]
          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
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
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-function-release-cvs
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
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
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
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
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
                    [:expression
                     [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
                   ["CloseRoundBracket" ")"]]]]]]]
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
    :call-named-arguments-reorder
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
         [:formalParameter
          [:variableInitializer
           ["VarName" "$b"]
           ["Eq" "="]
           [:constantInitializer [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:expression
               [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
               ["Dot" "."]
               [:expression [:string ["SingleQuoteString" "'-'"]]]]
              ["Dot" "."]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
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
                 [:argumentName [:identifier ["Label" "b"]] ["Colon" ":"]]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
                ["Comma" ","]
                [:actualArgument
                 [:argumentName [:identifier ["Label" "a"]] ["Colon" ":"]]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-global-unset-keeps-global
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
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
            [:globalStatement ["Global" "global"] [:globalVar ["VarName" "$g"]] ["SemiColon" ";"]]]]
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
                     [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]]
                   ["CloseRoundBracket" ")"]]]]]]]
             ["SemiColon" ";"]]]]
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]
              [:assignmentOperator ["Eq" "="]]
              [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
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
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$g"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :closure-counter-factory
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$mk"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:lambdaFunctionExpr
            ["Function_" "function"]
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
                  [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
                  [:assignmentOperator ["Eq" "="]]
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
                 ["SemiColon" ";"]]]]
              [:innerStatement
               [:statement
                [:returnStatement
                 ["Return" "return"]
                 [:expression
                  [:lambdaFunctionExpr
                   ["Function_" "function"]
                   ["OpenRoundBracket" "("]
                   [:formalParameterList]
                   ["CloseRoundBracket" ")"]
                   [:lambdaFunctionUseVars
                    ["Use" "use"]
                    ["OpenRoundBracket" "("]
                    [:lambdaFunctionUseVar ["Ampersand" "&"] ["VarName" "$n"]]
                    ["CloseRoundBracket" ")"]]
                   [:blockStatement
                    ["OpenCurlyBracket" "{"]
                    [:innerStatementList
                     [:innerStatement
                      [:statement
                       [:returnStatement
                        ["Return" "return"]
                        [:expression
                         ["Inc" "++"]
                         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
                        ["SemiColon" ";"]]]]]
                    ["CloseCurlyBracket" "}"]]]]
                 ["SemiColon" ";"]]]]]
             ["CloseCurlyBracket" "}"]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:chain
            [:chainOrigin
             [:functionCall
              [:functionCallName [:chainBase [:keyedVariable ["VarName" "$mk"]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName [:chainBase [:keyedVariable ["VarName" "$c"]]]]
             [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName [:chainBase [:keyedVariable ["VarName" "$c"]]]]
             [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
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
              [:functionCallName [:chainBase [:keyedVariable ["VarName" "$c"]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :array-pointer-reset-end
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
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "next"]]]]]
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
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "current"]]]]]
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
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "end"]]]]]
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
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "current"]]]]]
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
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "reset"]]]]]
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
             [:functionCall
              [:functionCallName
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "current"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :call-return-owned-temp-released
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "mk"]]
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
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["Comma" ","]
                [:arrayItem
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                ["Comma" ","]
                [:arrayItem
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
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
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "count"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression
                  [:chain
                   [:chainOrigin
                    [:functionCall
                     [:functionCallName
                      [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "mk"]]]]]
                     [:actualArguments
                      [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]
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
                 [:expression
                  [:chain
                   [:chainOrigin
                    [:functionCall
                     [:functionCallName
                      [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "mk"]]]]]
                     [:actualArguments
                      [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-ref-leftover-unset-fixes
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
         ["Ampersand" "&"]
         [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
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
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Unset" "unset"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
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
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "implode"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument [:expression [:string ["SingleQuoteString" "','"]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :call-dynamic-dim-paren
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
               [:parentheses
                ["OpenRoundBracket" "("]
                [:expression
                 [:lambdaFunctionExpr
                  ["LambdaFn" "fn"]
                  ["OpenRoundBracket" "("]
                  [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$x"]]]]
                  ["CloseRoundBracket" ")"]
                  ["DoubleArrow" "=>"]
                  [:expression
                   [:expression
                    [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
                   ["Plus" "+"]
                   [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]]
                ["CloseRoundBracket" ")"]]]]]
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
               [:chainBase
                [:keyedVariable
                 ["VarName" "$a"]
                 [:squareCurlyExpression
                  ["OpenSquareBracket" "["]
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                  ["CloseSquareBracket" "]"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :closure-static-per-object
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:lambdaFunctionExpr
            ["Function_" "function"]
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
                  [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
                 ["SemiColon" ";"]]]]
              [:innerStatement
               [:statement
                [:returnStatement
                 ["Return" "return"]
                 [:expression
                  ["Inc" "++"]
                  [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]]
                 ["SemiColon" ";"]]]]]
             ["CloseCurlyBracket" "}"]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:chain
           [:chainOrigin
            [:functionCall
             [:functionCallName [:chainBase [:keyedVariable ["VarName" "$f"]]]]
             [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
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
              [:functionCallName [:chainBase [:keyedVariable ["VarName" "$f"]]]]
              [:actualArguments [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :foreach-by-value-snapshot
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
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]]
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
    :arrow-captures-by-value-at-creation
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:lambdaFunctionExpr
            ["LambdaFn" "fn"]
            ["OpenRoundBracket" "("]
            [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$x"]]]]
            ["CloseRoundBracket" ")"]
            ["DoubleArrow" "=>"]
            [:expression
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
             ["Plus" "+"]
             [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "10"]]]]]]
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
              [:functionCallName [:chainBase [:keyedVariable ["VarName" "$f"]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-element-unwrapped-after-unset
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
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$r"]]]]]]
          ["Eq" "="]
          ["Ampersand" "&"]
          [:chain
           [:chainOrigin
            [:chainBase
             [:keyedVariable
              ["VarName" "$a"]
              [:squareCurlyExpression
               ["OpenSquareBracket" "["]
               [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
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
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Unset" "unset"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$r"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
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
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]]
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
                ["CloseSquareBracket" "]"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-builtin-sort-over-reference-slot
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
         ["SemiColon" ";"]]]]
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
              ["Ampersand" "&"]
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
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
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]]
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
               [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "implode"]]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument [:expression [:string ["SingleQuoteString" "','"]]]]
                ["Comma" ","]
                [:actualArgument
                 [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-element-survives-copy
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
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$r"]]]]]]
          ["Eq" "="]
          ["Ampersand" "&"]
          [:chain
           [:chainOrigin
            [:chainBase
             [:keyedVariable
              ["VarName" "$a"]
              [:squareCurlyExpression
               ["OpenSquareBracket" "["]
               [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
               ["CloseSquareBracket" "]"]]]]]]]
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
               [:squareCurlyExpression
                ["OpenSquareBracket" "["]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]]
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
                ["CloseSquareBracket" "]"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-array-literal-element
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
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:arrayCreation
            ["OpenSquareBracket" "["]
            [:arrayItemList
             [:arrayItem
              ["Ampersand" "&"]
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]]
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
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]
                ["CloseSquareBracket" "]"]]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :ref-param-by-reference
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "inc"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter ["Ampersand" "&"] [:variableInitializer ["VarName" "$n"]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:expressionStatement
             [:expression
              [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$n"]]]]]
              ["Inc" "++"]]
             ["SemiColon" ";"]]]]]
         ["CloseCurlyBracket" "}"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]
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
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "inc"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:echoStatement
         ["Echo" "echo"]
         [:expressionList
          [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$v"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :call-dynamic-variable
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:string ["SingleQuoteString" "'strlen'"]]]]
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
              [:functionCallName [:chainBase [:keyedVariable ["VarName" "$f"]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument [:expression [:string ["SingleQuoteString" "'abc'"]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :arrow-nested-transitive-capture
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
         ["SemiColon" ";"]]]]
      [:topStatement
       [:statement
        [:expressionStatement
         [:expression
          [:assignable [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$f"]]]]]]
          [:assignmentOperator ["Eq" "="]]
          [:expression
           [:lambdaFunctionExpr
            ["LambdaFn" "fn"]
            ["OpenRoundBracket" "("]
            [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$x"]]]]
            ["CloseRoundBracket" ")"]
            ["DoubleArrow" "=>"]
            [:expression
             [:lambdaFunctionExpr
              ["LambdaFn" "fn"]
              ["OpenRoundBracket" "("]
              [:formalParameterList [:formalParameter [:variableInitializer ["VarName" "$y"]]]]
              ["CloseRoundBracket" ")"]
              ["DoubleArrow" "=>"]
              [:expression
               [:expression
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$x"]]]]]]
                ["Plus" "+"]
                [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$y"]]]]]]]
               ["Plus" "+"]
               [:expression
                [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]]]]]]]
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
              [:functionCallName [:chainBase [:keyedVariable ["VarName" "$f"]]]]
              [:actualArguments
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                ["CloseRoundBracket" ")"]]
               [:arguments
                ["OpenRoundBracket" "("]
                [:actualArgument
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :call-named-default-skipped
    [:htmlDocument
     [:phpBlock
      [:topStatement
       [:functionDeclaration
        ["Function_" "function"]
        [:identifier ["Label" "f"]]
        ["OpenRoundBracket" "("]
        [:formalParameterList
         [:formalParameter
          [:variableInitializer
           ["VarName" "$a"]
           ["Eq" "="]
           [:constantInitializer [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]]
         ["Comma" ","]
         [:formalParameter
          [:variableInitializer
           ["VarName" "$b"]
           ["Eq" "="]
           [:constantInitializer [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]]
         ["Comma" ","]
         [:formalParameter
          [:variableInitializer
           ["VarName" "$c"]
           ["Eq" "="]
           [:constantInitializer [:constant [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]]
        ["CloseRoundBracket" ")"]
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
          [:innerStatement
           [:statement
            [:returnStatement
             ["Return" "return"]
             [:expression
              [:expression
               [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$a"]]]]]]
               ["Dot" "."]
               [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$b"]]]]]]]
              ["Dot" "."]
              [:expression [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$c"]]]]]]]
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
                 [:argumentName [:identifier ["Label" "c"]] ["Colon" ":"]]
                 [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "9"]]]]]]
                ["CloseRoundBracket" ")"]]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]],
    :call-too-few-named-uncaught
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
        [:blockStatement
         ["OpenCurlyBracket" "{"]
         [:innerStatementList
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
              [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "f"]]]]]
             [:actualArguments
              [:arguments
               ["OpenRoundBracket" "("]
               [:actualArgument
                [:argumentName [:identifier ["Label" "b"]] ["Colon" ":"]]
                [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
               ["CloseRoundBracket" ")"]]]]]]]
         ["SemiColon" ";"]]]]]
     ["EOF" "<EOF>"]]})
