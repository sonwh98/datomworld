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
                       ["EOF" "<EOF>"]]})
