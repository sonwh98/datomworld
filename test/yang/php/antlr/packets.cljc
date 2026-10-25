(ns yang.php.antlr.packets
  "CST packets of the portable fixtures, checked in as `packet` forms (rule
   `[:rule child ...]`, token `[\"TYPE\" \"text\"]`) so the Node and Dart lanes lower
   and run them without a parser. Regenerate from `yang.php.antlr.cst` when the
   grammar package moves; spans are dropped, the lowering reads them only for
   diagnostics.")


(def forms
  '{:mixed-text-and-code  [:htmlDocument
                           [:inlineHtml [:htmlElement ["HtmlText" "a "]]]
                           [:phpBlock
                            [:topStatement
                             [:statement
                              [:echoStatement
                               ["Echo" "echo"]
                               [:expressionList
                                [:expression
                                 [:constant
                                  [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                               ["SemiColon" ";"]]]]
                            [:topStatement [:statement [:emptyStatement_ ["SemiColon" "?>"]]]]
                            [:topStatement
                             [:statement
                              [:inlineHtmlStatement
                               [:inlineHtml [:htmlElement ["HtmlText" "b "]]]]]]
                            [:topStatement
                             [:statement
                              [:echoStatement
                               ["Echo" "<?="]
                               [:expressionList
                                [:expression
                                 [:constant
                                  [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                               ["SemiColon" "?>"]]]]
                            [:topStatement
                             [:statement
                              [:inlineHtmlStatement
                               [:inlineHtml [:htmlElement ["HtmlText" "c"]]]]]]]
                           ["EOF" "<EOF>"]]
    :close-tag-newline  [:htmlDocument
                         [:phpBlock
                          [:topStatement
                           [:statement
                            [:echoStatement
                             ["Echo" "echo"]
                             [:expressionList
                              [:expression
                               [:constant
                                [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                             ["SemiColon" "?>\n"]]]]
                          [:topStatement
                           [:statement
                            [:inlineHtmlStatement
                             [:inlineHtml [:htmlElement ["HtmlText" "x"]]]]]]]
                         ["EOF" "<EOF>"]]
    :echo-scalars  [:htmlDocument
                    [:phpBlock
                     [:topStatement
                      [:statement
                       [:echoStatement
                        ["Echo" "echo"]
                        [:expressionList
                         [:expression
                          [:expression
                           [:constant
                            [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                          ["Plus" "+"]
                          [:expression
                           [:expression
                            [:constant
                             [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
                           ["Asterisk" "*"]
                           [:expression
                            [:constant
                             [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]]
                         ["Comma" ","]
                         [:expression
                          [:string
                           ["DoubleQuote" "\""]
                           [:interpolatedStringPart ["StringPart" "x"]]
                           ["DoubleQuote" "\""]]]
                         ["Comma" ","]
                         [:expression [:constant [:literalConstant ["Real" "2.5"]]]]
                         ["Comma" ","]
                         [:expression
                          [:constant [:literalConstant ["BooleanConstant" "true"]]]]
                         ["Comma" ","]
                         [:expression
                          [:constant [:literalConstant ["BooleanConstant" "false"]]]]
                         ["Comma" ","]
                         [:expression [:constant ["Null" "null"]]]
                         ["Comma" ","]
                         [:expression
                          [:string
                           ["DoubleQuote" "\""]
                           [:interpolatedStringPart ["StringPart" "|"]]
                           ["DoubleQuote" "\""]]]]
                        ["SemiColon" ";"]]]]]
                    ["EOF" "<EOF>"]]
    :echo-floats  [:htmlDocument
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
                        [:expression
                         [:constant
                          [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
                        ["Comma" ","]
                        [:expression [:constant [:literalConstant ["Real" "1e100"]]]]
                        ["Comma" ","]
                        [:expression
                         [:constant
                          [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
                        ["Comma" ","]
                        [:expression
                         ["Minus" "-"]
                         [:expression [:constant [:literalConstant ["Real" "0.0"]]]]]
                        ["Comma" ","]
                        [:expression
                         [:constant
                          [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
                        ["Comma" ","]
                        [:expression
                         [:expression
                          [:constant
                           [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                         ["Divide" "/"]
                         [:expression
                          [:constant
                           [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                        ["Comma" ","]
                        [:expression
                         [:constant
                          [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
                        ["Comma" ","]
                        [:expression
                         [:constant
                          [:literalConstant ["Real" "100000000000000000000.0"]]]]
                        ["Comma" ","]
                        [:expression
                         [:constant
                          [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
                        ["Comma" ","]
                        [:expression [:constant [:literalConstant ["Real" "2.5"]]]]
                        ["Comma" ","]
                        [:expression
                         [:constant
                          [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
                        ["Comma" ","]
                        [:expression [:constant [:literalConstant ["Real" "1e14"]]]]
                        ["Comma" ","]
                        [:expression
                         [:constant
                          [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
                        ["Comma" ","]
                        [:expression [:constant [:literalConstant ["Real" "1e13"]]]]
                        ["Comma" ","]
                        [:expression
                         [:constant
                          [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
                        ["Comma" ","]
                        [:expression [:constant [:literalConstant ["Real" "0.0001"]]]]
                        ["Comma" ","]
                        [:expression
                         [:constant
                          [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
                        ["Comma" ","]
                        [:expression [:constant [:literalConstant ["Real" "0.00001"]]]]
                        ["Comma" ","]
                        [:expression
                         [:constant
                          [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
                        ["Comma" ","]
                        [:expression
                         [:expression
                          [:constant
                           [:literalConstant [:numericConstant ["Decimal" "7"]]]]]
                         ["Divide" "/"]
                         [:expression
                          [:constant
                           [:literalConstant [:numericConstant ["Decimal" "2"]]]]]]
                        ["Comma" ","]
                        [:expression
                         [:constant
                          [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]
                        ["Comma" ","]
                        [:expression
                         [:expression
                          [:constant
                           [:literalConstant [:numericConstant ["Decimal" "6"]]]]]
                         ["Divide" "/"]
                         [:expression
                          [:constant
                           [:literalConstant [:numericConstant ["Decimal" "3"]]]]]]
                        ["Comma" ","]
                        [:expression
                         [:constant
                          [:literalConstant [:stringConstant ["Label" "PHP_EOL"]]]]]]
                       ["SemiColon" ";"]]]]]
                   ["EOF" "<EOF>"]]
    :int-overflow  [:htmlDocument
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
                              [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
                            [:actualArguments
                             [:arguments
                              ["OpenRoundBracket" "("]
                              [:actualArgument
                               [:expression
                                [:constant
                                 [:literalConstant
                                  [:stringConstant ["Label" "PHP_INT_MAX"]]]]]]
                              ["Comma" ","]
                              [:actualArgument
                               [:expression
                                [:constant
                                 [:literalConstant
                                  [:stringConstant ["Label" "PHP_INT_MIN"]]]]]]
                              ["Comma" ","]
                              [:actualArgument
                               [:expression
                                [:expression
                                 [:constant
                                  [:literalConstant
                                   [:stringConstant ["Label" "PHP_INT_MAX"]]]]]
                                ["Plus" "+"]
                                [:expression
                                 [:constant
                                  [:literalConstant
                                   [:numericConstant ["Decimal" "1"]]]]]]]
                              ["Comma" ","]
                              [:actualArgument
                               [:expression
                                [:expression
                                 [:constant
                                  [:literalConstant
                                   [:stringConstant ["Label" "PHP_INT_MIN"]]]]]
                                ["Minus" "-"]
                                [:expression
                                 [:constant
                                  [:literalConstant
                                   [:numericConstant ["Decimal" "1"]]]]]]]
                              ["Comma" ","]
                              [:actualArgument
                               [:expression
                                [:expression
                                 [:constant
                                  [:literalConstant
                                   [:stringConstant ["Label" "PHP_INT_MAX"]]]]]
                                ["Asterisk" "*"]
                                [:expression
                                 [:constant
                                  [:literalConstant
                                   [:numericConstant ["Decimal" "2"]]]]]]]
                              ["Comma" ","]
                              [:actualArgument
                               [:expression
                                ["Minus" "-"]
                                [:expression
                                 [:constant
                                  [:literalConstant
                                   [:stringConstant ["Label" "PHP_INT_MIN"]]]]]]]
                              ["Comma" ","]
                              [:actualArgument
                               [:expression
                                [:constant
                                 [:literalConstant
                                  [:numericConstant ["Decimal" "9223372036854775807"]]]]]]
                              ["Comma" ","]
                              [:actualArgument
                               [:expression
                                [:constant
                                 [:literalConstant
                                  [:numericConstant ["Decimal" "9223372036854775808"]]]]]]
                              ["Comma" ","]
                              [:actualArgument
                               [:expression
                                [:constant
                                 [:literalConstant
                                  [:numericConstant ["Hex" "0x7FFFFFFFFFFFFFFF"]]]]]]
                              ["Comma" ","]
                              [:actualArgument
                               [:expression
                                [:constant
                                 [:literalConstant
                                  [:numericConstant ["Hex" "0xFFFFFFFFFFFFFFFF"]]]]]]
                              ["Comma" ","]
                              [:actualArgument
                               [:expression
                                [:constant
                                 [:literalConstant [:numericConstant ["Octal" "010"]]]]]]
                              ["Comma" ","]
                              [:actualArgument
                               [:expression
                                [:constant
                                 [:literalConstant
                                  [:numericConstant ["Binary" "0b101"]]]]]]
                              ["CloseRoundBracket" ")"]]]]]]]
                        ["SemiColon" ";"]]]]]
                    ["EOF" "<EOF>"]]
    :intdiv-min-by-minus-one  [:htmlDocument
                               [:phpBlock
                                [:topStatement
                                 [:statement
                                  [:echoStatement
                                   ["Echo" "echo"]
                                   [:expressionList
                                    [:expression
                                     [:constant
                                      [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
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
                                          [:namespaceNameList [:identifier ["Label" "intdiv"]]]]]
                                        [:actualArguments
                                         [:arguments
                                          ["OpenRoundBracket" "("]
                                          [:actualArgument
                                           [:expression
                                            [:constant
                                             [:literalConstant
                                              [:stringConstant ["Label" "PHP_INT_MIN"]]]]]]
                                          ["Comma" ","]
                                          [:actualArgument
                                           [:expression
                                            ["Minus" "-"]
                                            [:expression
                                             [:constant
                                              [:literalConstant
                                               [:numericConstant ["Decimal" "1"]]]]]]]
                                          ["CloseRoundBracket" ")"]]]]]]]]
                                   ["SemiColon" ";"]]]]]
                               ["EOF" "<EOF>"]]
    :modulo-by-zero  [:htmlDocument
                      [:phpBlock
                       [:topStatement
                        [:statement
                         [:echoStatement
                          ["Echo" "echo"]
                          [:expressionList
                           [:expression
                            [:constant
                             [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                          ["SemiColon" ";"]]]]
                       [:topStatement
                        [:statement
                         [:echoStatement
                          ["Echo" "echo"]
                          [:expressionList
                           [:expression
                            [:expression
                             [:constant
                              [:literalConstant [:numericConstant ["Decimal" "1"]]]]]
                            ["Percent" "%"]
                            [:expression
                             [:constant
                              [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]
                          ["SemiColon" ";"]]]]]
                      ["EOF" "<EOF>"]]
    :numeric-strings  [:htmlDocument
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
                                 [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
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
                                   [:expression
                                    [:constant
                                     [:literalConstant
                                      [:numericConstant ["Decimal" "1"]]]]]]]
                                 ["Comma" ","]
                                 [:actualArgument
                                  [:expression
                                   [:expression
                                    [:string
                                     ["DoubleQuote" "\""]
                                     [:interpolatedStringPart ["StringPart" "5 "]]
                                     ["DoubleQuote" "\""]]]
                                   ["Plus" "+"]
                                   [:expression
                                    [:constant
                                     [:literalConstant
                                      [:numericConstant ["Decimal" "1"]]]]]]]
                                 ["Comma" ","]
                                 [:actualArgument
                                  [:expression
                                   [:expression
                                    [:string
                                     ["DoubleQuote" "\""]
                                     [:interpolatedStringPart ["StringPart" "1.5"]]
                                     ["DoubleQuote" "\""]]]
                                   ["Plus" "+"]
                                   [:expression
                                    [:constant
                                     [:literalConstant
                                      [:numericConstant ["Decimal" "1"]]]]]]]
                                 ["Comma" ","]
                                 [:actualArgument
                                  [:expression
                                   [:expression
                                    [:string
                                     ["DoubleQuote" "\""]
                                     [:interpolatedStringPart ["StringPart" "1e2"]]
                                     ["DoubleQuote" "\""]]]
                                   ["Plus" "+"]
                                   [:expression
                                    [:constant
                                     [:literalConstant
                                      [:numericConstant ["Decimal" "0"]]]]]]]
                                 ["CloseRoundBracket" ")"]]]]]]]
                           ["SemiColon" ";"]]]]]
                       ["EOF" "<EOF>"]]
    :break-continue-levels  [:htmlDocument
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
                                          [:assignable
                                           [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]]
                                          [:assignmentOperator ["Eq" "="]]
                                          [:expression
                                           [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]]]
                                       ["SemiColon" ";"]
                                       [:expressionList
                                        [:expression
                                         [:expression
                                          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$j"]]]]]]
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
                                               [:expression
                                                [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
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
                                               [:expression
                                                [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
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
                                         [:expression
                                          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
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
                                         [:expression
                                          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]
                                         ["Greater" ">"]
                                         [:expression [:constant [:literalConstant [:numericConstant ["Decimal" "4"]]]]]]
                                        ["CloseRoundBracket" ")"]]
                                       [:statement [:breakStatement ["Break" "break"] ["SemiColon" ";"]]]]]]
                                    [:innerStatement
                                     [:statement
                                      [:echoStatement
                                       ["Echo" "echo"]
                                       [:expressionList
                                        [:expression
                                         [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$k"]]]]]]]
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
                             ["EOF" "<EOF>"]]
    :switch  [:htmlDocument
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
                         [:expression
                          [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$i"]]]]]]
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
                             [:expression
                              [:constant [:literalConstant [:numericConstant ["Decimal" "2"]]]]]
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
              ["EOF" "<EOF>"]]
    :static-variables  [:htmlDocument
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
                                [:variableInitializer
                                 ["VarName" "$n"]
                                 ["Eq" "="]
                                 [:constantInitializer
                                  [:constant [:literalConstant [:numericConstant ["Decimal" "5"]]]]]]
                                ["Comma" ","]
                                [:variableInitializer ["VarName" "$m"]]
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
                                 [:expression
                                  [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$m"]]]]]]]
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
                                 [:actualArguments
                                  [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
                             ["Comma" ","]
                             [:expression
                              [:chain
                               [:chainOrigin
                                [:functionCall
                                 [:functionCallName
                                  [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "counter"]]]]]
                                 [:actualArguments
                                  [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
                             ["Comma" ","]
                             [:expression
                              [:chain
                               [:chainOrigin
                                [:functionCall
                                 [:functionCallName
                                  [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "counter"]]]]]
                                 [:actualArguments
                                  [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
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
                                [:variableInitializer
                                 ["VarName" "$calls"]
                                 ["Eq" "="]
                                 [:constantInitializer
                                  [:constant [:literalConstant [:numericConstant ["Decimal" "0"]]]]]]
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
                                  [:expression
                                   [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$d"]]]]]]
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
                                [:expression
                                 [:chain [:chainOrigin [:chainBase [:keyedVariable ["VarName" "$calls"]]]]]]
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
                                [:variableInitializer
                                 ["VarName" "$v"]
                                 ["Eq" "="]
                                 [:constantInitializer
                                  [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
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
                                [:variableInitializer
                                 ["VarName" "$v"]
                                 ["Eq" "="]
                                 [:constantInitializer
                                  [:constant [:literalConstant [:numericConstant ["Decimal" "100"]]]]]]
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
                                 [:actualArguments
                                  [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
                             ["Comma" ","]
                             [:expression
                              [:chain
                               [:chainOrigin
                                [:functionCall
                                 [:functionCallName
                                  [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "b"]]]]]
                                 [:actualArguments
                                  [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
                             ["Comma" ","]
                             [:expression
                              [:chain
                               [:chainOrigin
                                [:functionCall
                                 [:functionCallName
                                  [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "a"]]]]]
                                 [:actualArguments
                                  [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]
                             ["Comma" ","]
                             [:expression
                              [:chain
                               [:chainOrigin
                                [:functionCall
                                 [:functionCallName
                                  [:qualifiedNamespaceName [:namespaceNameList [:identifier ["Label" "b"]]]]]
                                 [:actualArguments
                                  [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
                            ["SemiColon" ";"]]]]]
                        ["EOF" "<EOF>"]]
    :silence-with-counting-handler  [:htmlDocument
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
                                              [:actualArguments
                                               [:arguments ["OpenRoundBracket" "("] ["CloseRoundBracket" ")"]]]]]]]]
                                         ["SemiColon" ";"]]]]]
                                     ["EOF" "<EOF>"]]
    :html-markup-attributes  [:htmlDocument
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
                              ["EOF" "<EOF>"]]
    :html-comment  [:htmlDocument
                    [:inlineHtml [:htmlElement ["HtmlText" "<!-- c -->"]]]
                    ["EOF" "<EOF>"]]
    :html-leading-newline  [:htmlDocument
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
                            ["EOF" "<EOF>"]]
    :html-script-body  [:htmlDocument
                        [:inlineHtml
                         [:htmlElement ["HtmlScriptOpen" "<script"]]
                         [:htmlElement ["HtmlClose" ">"]]]
                        [:inlineHtml [:scriptText ["ScriptText" "var a = 1;"]]]
                        [:inlineHtml [:htmlElement ["HtmlScriptClose" "</script>"]]]
                        ["EOF" "<EOF>"]]
    :html-close-tag-blank-line  [:htmlDocument
                                 [:phpBlock
                                  [:topStatement
                                   [:statement
                                    [:echoStatement
                                     ["Echo" "echo"]
                                     [:expressionList
                                      [:expression
                                       [:constant
                                        [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
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
                                 ["EOF" "<EOF>"]]
    :pow-negative-underflowing-power  [:htmlDocument
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
                                                 [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
                                               [:actualArguments
                                                [:arguments
                                                 ["OpenRoundBracket" "("]
                                                 [:actualArgument
                                                  [:expression
                                                   [:expression
                                                    [:constant [:literalConstant ["Real" "2.0"]]]]
                                                   ["Pow" "**"]
                                                   [:expression
                                                    ["Minus" "-"]
                                                    [:expression
                                                     [:constant
                                                      [:literalConstant
                                                       [:numericConstant ["Decimal" "1074"]]]]]]]]
                                                 ["Comma" ","]
                                                 [:actualArgument
                                                  [:expression
                                                   [:expression
                                                    [:constant [:literalConstant ["Real" "0.5"]]]]
                                                   ["Pow" "**"]
                                                   [:expression
                                                    ["Minus" "-"]
                                                    [:expression
                                                     [:constant
                                                      [:literalConstant
                                                       [:numericConstant ["Decimal" "2000"]]]]]]]]
                                                 ["CloseRoundBracket" ")"]]]]]]]
                                           ["SemiColon" ";"]]]]]
                                       ["EOF" "<EOF>"]]
    :numeric-string-padding  [:htmlDocument
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
                                        [:namespaceNameList [:identifier ["Label" "var_dump"]]]]]
                                      [:actualArguments
                                       [:arguments
                                        ["OpenRoundBracket" "("]
                                        [:actualArgument
                                         [:expression
                                          [:expression
                                           [:string
                                            ["DoubleQuote" "\""]
                                            [:interpolatedStringPart
                                             ["StringPart" "0000000000000000000000012"]]
                                            ["DoubleQuote" "\""]]]
                                          ["Plus" "+"]
                                          [:expression
                                           [:constant
                                            [:literalConstant
                                             [:numericConstant ["Decimal" "0"]]]]]]]
                                        ["Comma" ","]
                                        [:actualArgument
                                         [:expression
                                          [:expression
                                           [:string
                                            ["DoubleQuote" "\""]
                                            [:interpolatedStringPart
                                             ["StringPart" "1e0000000000005"]]
                                            ["DoubleQuote" "\""]]]
                                          ["Plus" "+"]
                                          [:expression
                                           [:constant
                                            [:literalConstant
                                             [:numericConstant ["Decimal" "0"]]]]]]]
                                        ["Comma" ","]
                                        [:actualArgument
                                         [:expression
                                          [:expression
                                           [:string
                                            ["DoubleQuote" "\""]
                                            [:interpolatedStringPart
                                             ["StringPart" "1e-0000000000005"]]
                                            ["DoubleQuote" "\""]]]
                                          ["Plus" "+"]
                                          [:expression
                                           [:constant
                                            [:literalConstant
                                             [:numericConstant ["Decimal" "0"]]]]]]]
                                        ["CloseRoundBracket" ")"]]]]]]]
                                  ["SemiColon" ";"]]]]]
                              ["EOF" "<EOF>"]]
    :single-quote-escapes  [:htmlDocument
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
                            ["EOF" "<EOF>"]]})
