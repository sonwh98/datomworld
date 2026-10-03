(ns yang.python.antlr.safepoint-programs
  "Python programs for the safepoint recursion slice, as the CST packets the
   JVM parser produces for their sources (each def's docstring is the
   source). The parser is JVM-only; these let every host run the real
   lowering. `yang.python.antlr.e2e-test` checks each packet against the
   parser, so the two cannot drift. `tc` is the single-child chain from
   `test` down to `expr`, `t4` the one from `test` down to `not_test`, `at`
   an `atom_expr` holding one `atom`, `nm` a `name`."
  (:require
    [yang.python.antlr.lower-portable-test :refer [packet]]))


(defn nm
  [s]
  [:name ["NAME" s]])


(defn at
  [x]
  [:atom_expr [:atom x]])


(defn t4
  [x]
  [:test [:or_test [:and_test [:not_test x]]]])


(defn tc
  [x]
  (t4 [:comparison [:expr x]]))


(def recursion
  "def f(n):\n    if n == 0:\n        return 0\n    return f(n - 1)\ndef probe(n):\n    try:\n        return probe(n + 1)\n    except RecursionError:\n        return n\nprint(f(999))\ntry:\n    f(1000)\nexcept RecursionError:\n    print('rec')\nprint(probe(1))\n"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "f")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:if_stmt
            ["IF" "if"]
            (t4
              [:comparison
               [:expr (at (nm "n"))]
               [:comp_op ["EQUALS" "=="]]
               [:expr (at ["NUMBER" "0"])]])
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist (tc (at ["NUMBER" "0"]))]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:return_stmt
              ["RETURN" "return"]
              [:testlist
               (tc
                 [:atom_expr
                  [:atom (nm "f")]
                  [:trailer
                   ["OPEN_PAREN" "("]
                   [:arglist
                    [:argument
                     (t4
                       [:comparison
                        [:expr
                         [:expr (at (nm "n"))]
                         ["MINUS" "-"]
                         [:expr (at ["NUMBER" "1"])]]])]]
                   ["CLOSE_PAREN" ")"]]])]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "probe")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "probe")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist
                        [:argument
                         (t4
                           [:comparison
                            [:expr
                             [:expr (at (nm "n"))]
                             ["ADD" "+"]
                             [:expr (at ["NUMBER" "1"])]]])]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            [:except_clause
             ["EXCEPT" "except"]
             (tc (at (nm "RecursionError")))]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist (tc (at (nm "n")))]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "f")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "999"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "f")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at ["NUMBER" "1000"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (at (nm "RecursionError")))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "print")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at ["STRING" "'rec'"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "probe")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def unwind
  "def deep(n):\n    if n == 0:\n        raise ValueError\n    try:\n        return deep(n - 1)\n    finally:\n        pass\ndef probe(n):\n    try:\n        return probe(n + 1)\n    except RecursionError:\n        return n\ndef down(n):\n    if n == 0:\n        return probe(1)\n    return down(n - 1)\ntry:\n    deep(50)\nexcept ValueError:\n    print('unwound')\nprint(probe(1))\nprint(down(10))\nprint(probe(1))\nhits = []\ndef h():\n    hits.append(1)\ndef fin(n):\n    try:\n        return fin(n + 1)\n    finally:\n        h()\ntry:\n    fin(1)\nexcept RecursionError:\n    print(len(hits))\nprint(probe(1))\n"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "deep")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:if_stmt
            ["IF" "if"]
            (t4
              [:comparison
               [:expr (at (nm "n"))]
               [:comp_op ["EQUALS" "=="]]
               [:expr (at ["NUMBER" "0"])]])
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:raise_stmt
                  ["RAISE" "raise"]
                  (tc (at (nm "ValueError")))]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "deep")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist
                        [:argument
                         (t4
                           [:comparison
                            [:expr
                             [:expr (at (nm "n"))]
                             ["MINUS" "-"]
                             [:expr (at ["NUMBER" "1"])]]])]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            ["FINALLY" "finally"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt [:pass_stmt ["PASS" "pass"]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "probe")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "probe")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist
                        [:argument
                         (t4
                           [:comparison
                            [:expr
                             [:expr (at (nm "n"))]
                             ["ADD" "+"]
                             [:expr (at ["NUMBER" "1"])]]])]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            [:except_clause
             ["EXCEPT" "except"]
             (tc (at (nm "RecursionError")))]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist (tc (at (nm "n")))]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "down")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:if_stmt
            ["IF" "if"]
            (t4
              [:comparison
               [:expr (at (nm "n"))]
               [:comp_op ["EQUALS" "=="]]
               [:expr (at ["NUMBER" "0"])]])
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "probe")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:return_stmt
              ["RETURN" "return"]
              [:testlist
               (tc
                 [:atom_expr
                  [:atom (nm "down")]
                  [:trailer
                   ["OPEN_PAREN" "("]
                   [:arglist
                    [:argument
                     (t4
                       [:comparison
                        [:expr
                         [:expr (at (nm "n"))]
                         ["MINUS" "-"]
                         [:expr (at ["NUMBER" "1"])]]])]]
                   ["CLOSE_PAREN" ")"]]])]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "deep")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at ["NUMBER" "50"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (at (nm "ValueError")))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "print")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at ["STRING" "'unwound'"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "probe")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "10"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "probe")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr (tc (at (nm "hits")))]
         ["ASSIGN" "="]
         [:testlist_star_expr
          (tc [:atom_expr [:atom ["OPEN_BRACK" "["] ["CLOSE_BRACK" "]"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "h")
        [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "hits")]
                 [:trailer ["DOT" "."] (nm "append")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "fin")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "fin")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist
                        [:argument
                         (t4
                           [:comparison
                            [:expr
                             [:expr (at (nm "n"))]
                             ["ADD" "+"]
                             [:expr (at ["NUMBER" "1"])]]])]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            ["FINALLY" "finally"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:expr_stmt
                 [:testlist_star_expr
                  (tc
                    [:atom_expr
                     [:atom (nm "h")]
                     [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "fin")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (at (nm "RecursionError")))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "print")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    (tc
                      [:atom_expr
                       [:atom (nm "len")]
                       [:trailer
                        ["OPEN_PAREN" "("]
                        [:arglist [:argument (tc (at (nm "hits")))]]
                        ["CLOSE_PAREN" ")"]]])]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "probe")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def generators
  "def probe(n):\n    try:\n        return probe(n + 1)\n    except RecursionError:\n        return n\ndef gen():\n    while True:\n        yield probe(1)\ndef down(n, g):\n    if n == 0:\n        return next(g)\n    return down(n - 1, g)\ng = gen()\nprint(down(19, g))\nprint(next(g))\nprint(probe(1))\nprint(down(19, g))\nprint(probe(1))\ndef gen2():\n    try:\n        yield probe(1)\n        raise ValueError\n    except ValueError:\n        yield probe(1)\nh = gen2()\nprint(down(19, h))\nprint(next(h))\nprint(probe(1))\n"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "probe")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "probe")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist
                        [:argument
                         (t4
                           [:comparison
                            [:expr
                             [:expr (at (nm "n"))]
                             ["ADD" "+"]
                             [:expr (at ["NUMBER" "1"])]]])]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            [:except_clause
             ["EXCEPT" "except"]
             (tc (at (nm "RecursionError")))]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist (tc (at (nm "n")))]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "gen")
        [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:while_stmt
            ["WHILE" "while"]
            (tc (at ["TRUE" "True"]))
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:yield_stmt
                  [:yield_expr
                   ["YIELD" "yield"]
                   [:yield_arg
                    [:testlist
                     (tc
                       [:atom_expr
                        [:atom (nm "probe")]
                        [:trailer
                         ["OPEN_PAREN" "("]
                         [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                         ["CLOSE_PAREN" ")"]]])]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "down")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")] ["COMMA" ","] [:tfpdef (nm "g")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:if_stmt
            ["IF" "if"]
            (t4
              [:comparison
               [:expr (at (nm "n"))]
               [:comp_op ["EQUALS" "=="]]
               [:expr (at ["NUMBER" "0"])]])
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "next")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist [:argument (tc (at (nm "g")))]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:return_stmt
              ["RETURN" "return"]
              [:testlist
               (tc
                 [:atom_expr
                  [:atom (nm "down")]
                  [:trailer
                   ["OPEN_PAREN" "("]
                   [:arglist
                    [:argument
                     (t4
                       [:comparison
                        [:expr
                         [:expr (at (nm "n"))]
                         ["MINUS" "-"]
                         [:expr (at ["NUMBER" "1"])]]])]
                    ["COMMA" ","]
                    [:argument (tc (at (nm "g")))]]
                   ["CLOSE_PAREN" ")"]]])]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr (tc (at (nm "g")))]
         ["ASSIGN" "="]
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "gen")]
             [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument (tc (at ["NUMBER" "19"]))]
                     ["COMMA" ","]
                     [:argument (tc (at (nm "g")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "next")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at (nm "g")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "probe")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument (tc (at ["NUMBER" "19"]))]
                     ["COMMA" ","]
                     [:argument (tc (at (nm "g")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "probe")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "gen2")
        [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:yield_stmt
                  [:yield_expr
                   ["YIELD" "yield"]
                   [:yield_arg
                    [:testlist
                     (tc
                       [:atom_expr
                        [:atom (nm "probe")]
                        [:trailer
                         ["OPEN_PAREN" "("]
                         [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                         ["CLOSE_PAREN" ")"]]])]]]]]]
               ["NEWLINE" "\n"]]]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:raise_stmt
                  ["RAISE" "raise"]
                  (tc (at (nm "ValueError")))]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            [:except_clause ["EXCEPT" "except"] (tc (at (nm "ValueError")))]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:yield_stmt
                  [:yield_expr
                   ["YIELD" "yield"]
                   [:yield_arg
                    [:testlist
                     (tc
                       [:atom_expr
                        [:atom (nm "probe")]
                        [:trailer
                         ["OPEN_PAREN" "("]
                         [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                         ["CLOSE_PAREN" ")"]]])]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr (tc (at (nm "h")))]
         ["ASSIGN" "="]
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "gen2")]
             [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument (tc (at ["NUMBER" "19"]))]
                     ["COMMA" ","]
                     [:argument (tc (at (nm "h")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "next")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at (nm "h")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "probe")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def probe
  "def probe(n):\n    try:\n        return probe(n + 1)\n    except RecursionError:\n        return n\nprint(probe(1))\n"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "probe")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "probe")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist
                        [:argument
                         (t4
                           [:comparison
                            [:expr
                             [:expr (at (nm "n"))]
                             ["ADD" "+"]
                             [:expr (at ["NUMBER" "1"])]]])]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            [:except_clause
             ["EXCEPT" "except"]
             (tc (at (nm "RecursionError")))]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist (tc (at (nm "n")))]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "probe")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def admission
  "def probe(n):\n    try:\n        return probe(n + 1)\n    except RecursionError:\n        return n\ndef down(n, it):\n    if n == 0:\n        return next(it)\n    return down(n - 1, it)\ndef g():\n    x = 1\n    while True:\n        x = x + 1\n        yield x\nit = g()\ntry:\n    down(99, it)\nexcept RecursionError:\n    print('start refused')\nprint(down(10, it))\ntry:\n    down(99, it)\nexcept RecursionError:\n    print('resume refused')\nprint(down(98, it))\nprint(next(it))\nprint(probe(1))\n"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "probe")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "probe")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist
                        [:argument
                         (t4
                           [:comparison
                            [:expr
                             [:expr (at (nm "n"))]
                             ["ADD" "+"]
                             [:expr (at ["NUMBER" "1"])]]])]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            [:except_clause
             ["EXCEPT" "except"]
             (tc (at (nm "RecursionError")))]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist (tc (at (nm "n")))]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "down")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist
          [:tfpdef (nm "n")]
          ["COMMA" ","]
          [:tfpdef (nm "it")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:if_stmt
            ["IF" "if"]
            (t4
              [:comparison
               [:expr (at (nm "n"))]
               [:comp_op ["EQUALS" "=="]]
               [:expr (at ["NUMBER" "0"])]])
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "next")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist [:argument (tc (at (nm "it")))]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:return_stmt
              ["RETURN" "return"]
              [:testlist
               (tc
                 [:atom_expr
                  [:atom (nm "down")]
                  [:trailer
                   ["OPEN_PAREN" "("]
                   [:arglist
                    [:argument
                     (t4
                       [:comparison
                        [:expr
                         [:expr (at (nm "n"))]
                         ["MINUS" "-"]
                         [:expr (at ["NUMBER" "1"])]]])]
                    ["COMMA" ","]
                    [:argument (tc (at (nm "it")))]]
                   ["CLOSE_PAREN" ")"]]])]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "g")
        [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr (tc (at (nm "x")))]
             ["ASSIGN" "="]
             [:testlist_star_expr (tc (at ["NUMBER" "1"]))]]]
           ["NEWLINE" "\n"]]]
         [:stmt
          [:compound_stmt
           [:while_stmt
            ["WHILE" "while"]
            (tc (at ["TRUE" "True"]))
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:expr_stmt
                 [:testlist_star_expr (tc (at (nm "x")))]
                 ["ASSIGN" "="]
                 [:testlist_star_expr
                  (t4
                    [:comparison
                     [:expr
                      [:expr (at (nm "x"))]
                      ["ADD" "+"]
                      [:expr (at ["NUMBER" "1"])]]])]]]
               ["NEWLINE" "\n"]]]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:yield_stmt
                  [:yield_expr
                   ["YIELD" "yield"]
                   [:yield_arg [:testlist (tc (at (nm "x")))]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr (tc (at (nm "it")))]
         ["ASSIGN" "="]
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "g")]
             [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "down")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument (tc (at ["NUMBER" "99"]))]
                   ["COMMA" ","]
                   [:argument (tc (at (nm "it")))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (at (nm "RecursionError")))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "print")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument (tc (at ["STRING" "'start refused'"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument (tc (at ["NUMBER" "10"]))]
                     ["COMMA" ","]
                     [:argument (tc (at (nm "it")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "down")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument (tc (at ["NUMBER" "99"]))]
                   ["COMMA" ","]
                   [:argument (tc (at (nm "it")))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (at (nm "RecursionError")))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "print")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument (tc (at ["STRING" "'resume refused'"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument (tc (at ["NUMBER" "98"]))]
                     ["COMMA" ","]
                     [:argument (tc (at (nm "it")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "next")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at (nm "it")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "probe")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def rebase
  "def probe(n):\n    try:\n        return probe(n + 1)\n    except RecursionError:\n        return n\ndef down(n, it):\n    if n == 0:\n        return next(it)\n    return down(n - 1, it)\ndef gen():\n    while True:\n        yield probe(1)\nit = gen()\nprint(next(it))\nprint(down(19, it))\nprint(next(it))\ndef inner():\n    while True:\n        yield probe(1)\ndef outer(i):\n    while True:\n        yield next(i)\no = outer(inner())\nprint(next(o))\nprint(down(19, o))\ndef gen3():\n    try:\n        yield probe(1)\n    except ValueError:\n        pass\n    yield probe(1)\nk = gen3()\nprint(down(19, k))\nprint(next(k))\nprint(probe(1))\n"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "probe")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "probe")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist
                        [:argument
                         (t4
                           [:comparison
                            [:expr
                             [:expr (at (nm "n"))]
                             ["ADD" "+"]
                             [:expr (at ["NUMBER" "1"])]]])]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            [:except_clause
             ["EXCEPT" "except"]
             (tc (at (nm "RecursionError")))]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist (tc (at (nm "n")))]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "down")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist
          [:tfpdef (nm "n")]
          ["COMMA" ","]
          [:tfpdef (nm "it")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:if_stmt
            ["IF" "if"]
            (t4
              [:comparison
               [:expr (at (nm "n"))]
               [:comp_op ["EQUALS" "=="]]
               [:expr (at ["NUMBER" "0"])]])
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "next")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist [:argument (tc (at (nm "it")))]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:return_stmt
              ["RETURN" "return"]
              [:testlist
               (tc
                 [:atom_expr
                  [:atom (nm "down")]
                  [:trailer
                   ["OPEN_PAREN" "("]
                   [:arglist
                    [:argument
                     (t4
                       [:comparison
                        [:expr
                         [:expr (at (nm "n"))]
                         ["MINUS" "-"]
                         [:expr (at ["NUMBER" "1"])]]])]
                    ["COMMA" ","]
                    [:argument (tc (at (nm "it")))]]
                   ["CLOSE_PAREN" ")"]]])]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "gen")
        [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:while_stmt
            ["WHILE" "while"]
            (tc (at ["TRUE" "True"]))
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:yield_stmt
                  [:yield_expr
                   ["YIELD" "yield"]
                   [:yield_arg
                    [:testlist
                     (tc
                       [:atom_expr
                        [:atom (nm "probe")]
                        [:trailer
                         ["OPEN_PAREN" "("]
                         [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                         ["CLOSE_PAREN" ")"]]])]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr (tc (at (nm "it")))]
         ["ASSIGN" "="]
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "gen")]
             [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "next")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at (nm "it")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument (tc (at ["NUMBER" "19"]))]
                     ["COMMA" ","]
                     [:argument (tc (at (nm "it")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "next")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at (nm "it")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "inner")
        [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:while_stmt
            ["WHILE" "while"]
            (tc (at ["TRUE" "True"]))
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:yield_stmt
                  [:yield_expr
                   ["YIELD" "yield"]
                   [:yield_arg
                    [:testlist
                     (tc
                       [:atom_expr
                        [:atom (nm "probe")]
                        [:trailer
                         ["OPEN_PAREN" "("]
                         [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                         ["CLOSE_PAREN" ")"]]])]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "outer")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "i")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:while_stmt
            ["WHILE" "while"]
            (tc (at ["TRUE" "True"]))
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:yield_stmt
                  [:yield_expr
                   ["YIELD" "yield"]
                   [:yield_arg
                    [:testlist
                     (tc
                       [:atom_expr
                        [:atom (nm "next")]
                        [:trailer
                         ["OPEN_PAREN" "("]
                         [:arglist [:argument (tc (at (nm "i")))]]
                         ["CLOSE_PAREN" ")"]]])]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr (tc (at (nm "o")))]
         ["ASSIGN" "="]
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "outer")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "inner")]
                   [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "next")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at (nm "o")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument (tc (at ["NUMBER" "19"]))]
                     ["COMMA" ","]
                     [:argument (tc (at (nm "o")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "gen3")
        [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:yield_stmt
                  [:yield_expr
                   ["YIELD" "yield"]
                   [:yield_arg
                    [:testlist
                     (tc
                       [:atom_expr
                        [:atom (nm "probe")]
                        [:trailer
                         ["OPEN_PAREN" "("]
                         [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                         ["CLOSE_PAREN" ")"]]])]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            [:except_clause ["EXCEPT" "except"] (tc (at (nm "ValueError")))]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt [:pass_stmt ["PASS" "pass"]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:yield_stmt
              [:yield_expr
               ["YIELD" "yield"]
               [:yield_arg
                [:testlist
                 (tc
                   [:atom_expr
                    [:atom (nm "probe")]
                    [:trailer
                     ["OPEN_PAREN" "("]
                     [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                     ["CLOSE_PAREN" ")"]]])]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr (tc (at (nm "k")))]
         ["ASSIGN" "="]
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "gen3")]
             [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument (tc (at ["NUMBER" "19"]))]
                     ["COMMA" ","]
                     [:argument (tc (at (nm "k")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "next")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at (nm "k")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "probe")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def throwclose
  "def probe(n):\n    try:\n        return probe(n + 1)\n    except RecursionError:\n        return n\ndef down(n, it):\n    if n == 0:\n        return next(it)\n    return down(n - 1, it)\ndef gt():\n    try:\n        while True:\n            yield probe(1)\n    finally:\n        print(probe(1))\nt = gt()\nprint(down(19, t))\ntry:\n    t.throw(ValueError)\nexcept ValueError:\n    print('thrown')\nc = gt()\nprint(down(19, c))\nc.close()\nprint(probe(1))\n"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "probe")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "probe")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist
                        [:argument
                         (t4
                           [:comparison
                            [:expr
                             [:expr (at (nm "n"))]
                             ["ADD" "+"]
                             [:expr (at ["NUMBER" "1"])]]])]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            [:except_clause
             ["EXCEPT" "except"]
             (tc (at (nm "RecursionError")))]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist (tc (at (nm "n")))]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "down")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist
          [:tfpdef (nm "n")]
          ["COMMA" ","]
          [:tfpdef (nm "it")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:if_stmt
            ["IF" "if"]
            (t4
              [:comparison
               [:expr (at (nm "n"))]
               [:comp_op ["EQUALS" "=="]]
               [:expr (at ["NUMBER" "0"])]])
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "next")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist [:argument (tc (at (nm "it")))]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:return_stmt
              ["RETURN" "return"]
              [:testlist
               (tc
                 [:atom_expr
                  [:atom (nm "down")]
                  [:trailer
                   ["OPEN_PAREN" "("]
                   [:arglist
                    [:argument
                     (t4
                       [:comparison
                        [:expr
                         [:expr (at (nm "n"))]
                         ["MINUS" "-"]
                         [:expr (at ["NUMBER" "1"])]]])]
                    ["COMMA" ","]
                    [:argument (tc (at (nm "it")))]]
                   ["CLOSE_PAREN" ")"]]])]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "gt")
        [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:compound_stmt
               [:while_stmt
                ["WHILE" "while"]
                (tc (at ["TRUE" "True"]))
                ["COLON" ":"]
                [:block
                 ["NEWLINE" "\n"]
                 ["INDENT" "            "]
                 [:stmt
                  [:simple_stmts
                   [:simple_stmt
                    [:flow_stmt
                     [:yield_stmt
                      [:yield_expr
                       ["YIELD" "yield"]
                       [:yield_arg
                        [:testlist
                         (tc
                           [:atom_expr
                            [:atom (nm "probe")]
                            [:trailer
                             ["OPEN_PAREN" "("]
                             [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                             ["CLOSE_PAREN" ")"]]])]]]]]]
                   ["NEWLINE" "\n"]]]
                 ["DEDENT" ""]]]]]
             ["DEDENT" ""]]
            ["FINALLY" "finally"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:expr_stmt
                 [:testlist_star_expr
                  (tc
                    [:atom_expr
                     [:atom (nm "print")]
                     [:trailer
                      ["OPEN_PAREN" "("]
                      [:arglist
                       [:argument
                        (tc
                          [:atom_expr
                           [:atom (nm "probe")]
                           [:trailer
                            ["OPEN_PAREN" "("]
                            [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                            ["CLOSE_PAREN" ")"]]])]]
                      ["CLOSE_PAREN" ")"]]])]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr (tc (at (nm "t")))]
         ["ASSIGN" "="]
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "gt")]
             [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument (tc (at ["NUMBER" "19"]))]
                     ["COMMA" ","]
                     [:argument (tc (at (nm "t")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "t")]
                 [:trailer ["DOT" "."] (nm "throw")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at (nm "ValueError")))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (at (nm "ValueError")))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "print")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at ["STRING" "'thrown'"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr (tc (at (nm "c")))]
         ["ASSIGN" "="]
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "gt")]
             [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument (tc (at ["NUMBER" "19"]))]
                     ["COMMA" ","]
                     [:argument (tc (at (nm "c")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "c")]
             [:trailer ["DOT" "."] (nm "close")]
             [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "probe")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def nested-admission
  "def inner():\n    x = 0\n    while True:\n        x = x + 1\n        yield x\nt = inner()\ndef via(n):\n    if n == 0:\n        yield next(t)\n    else:\n        yield next(via(n - 1))\ntry:\n    next(via(2))\nexcept RecursionError:\n    print('refused')\nprint(next(via(1)))\ntry:\n    next(via(2))\nexcept RecursionError:\n    print('refused')\nprint(next(t))\n"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "inner")
        [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr (tc (at (nm "x")))]
             ["ASSIGN" "="]
             [:testlist_star_expr (tc (at ["NUMBER" "0"]))]]]
           ["NEWLINE" "\n"]]]
         [:stmt
          [:compound_stmt
           [:while_stmt
            ["WHILE" "while"]
            (tc (at ["TRUE" "True"]))
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:expr_stmt
                 [:testlist_star_expr (tc (at (nm "x")))]
                 ["ASSIGN" "="]
                 [:testlist_star_expr
                  (t4
                    [:comparison
                     [:expr
                      [:expr (at (nm "x"))]
                      ["ADD" "+"]
                      [:expr (at ["NUMBER" "1"])]]])]]]
               ["NEWLINE" "\n"]]]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:yield_stmt
                  [:yield_expr
                   ["YIELD" "yield"]
                   [:yield_arg [:testlist (tc (at (nm "x")))]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr (tc (at (nm "t")))]
         ["ASSIGN" "="]
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "inner")]
             [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "via")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:if_stmt
            ["IF" "if"]
            (t4
              [:comparison
               [:expr (at (nm "n"))]
               [:comp_op ["EQUALS" "=="]]
               [:expr (at ["NUMBER" "0"])]])
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:yield_stmt
                  [:yield_expr
                   ["YIELD" "yield"]
                   [:yield_arg
                    [:testlist
                     (tc
                       [:atom_expr
                        [:atom (nm "next")]
                        [:trailer
                         ["OPEN_PAREN" "("]
                         [:arglist [:argument (tc (at (nm "t")))]]
                         ["CLOSE_PAREN" ")"]]])]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            ["ELSE" "else"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:yield_stmt
                  [:yield_expr
                   ["YIELD" "yield"]
                   [:yield_arg
                    [:testlist
                     (tc
                       [:atom_expr
                        [:atom (nm "next")]
                        [:trailer
                         ["OPEN_PAREN" "("]
                         [:arglist
                          [:argument
                           (tc
                             [:atom_expr
                              [:atom (nm "via")]
                              [:trailer
                               ["OPEN_PAREN" "("]
                               [:arglist
                                [:argument
                                 (t4
                                   [:comparison
                                    [:expr
                                     [:expr (at (nm "n"))]
                                     ["MINUS" "-"]
                                     [:expr (at ["NUMBER" "1"])]]])]]
                               ["CLOSE_PAREN" ")"]]])]]
                         ["CLOSE_PAREN" ")"]]])]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "next")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    (tc
                      [:atom_expr
                       [:atom (nm "via")]
                       [:trailer
                        ["OPEN_PAREN" "("]
                        [:arglist [:argument (tc (at ["NUMBER" "2"]))]]
                        ["CLOSE_PAREN" ")"]]])]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (at (nm "RecursionError")))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "print")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at ["STRING" "'refused'"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "next")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument
                      (tc
                        [:atom_expr
                         [:atom (nm "via")]
                         [:trailer
                          ["OPEN_PAREN" "("]
                          [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                          ["CLOSE_PAREN" ")"]]])]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "next")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    (tc
                      [:atom_expr
                       [:atom (nm "via")]
                       [:trailer
                        ["OPEN_PAREN" "("]
                        [:arglist [:argument (tc (at ["NUMBER" "2"]))]]
                        ["CLOSE_PAREN" ")"]]])]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (at (nm "RecursionError")))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "print")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at ["STRING" "'refused'"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "next")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at (nm "t")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def delegation
  "def probe(n):\n    try:\n        return probe(n + 1)\n    except RecursionError:\n        return n\ndef down(n, it):\n    if n == 0:\n        return next(it)\n    return down(n - 1, it)\ndef dthrow(n, it):\n    if n == 0:\n        return it.throw(ValueError)\n    return dthrow(n - 1, it)\ndef dclose(n, it):\n    if n == 0:\n        return it.close()\n    return dclose(n - 1, it)\ndef leaf():\n    try:\n        while True:\n            yield probe(1)\n    finally:\n        print(probe(1))\ndef mid(g):\n    yield from g\ndef top(g):\n    yield from g\nc = top(mid(leaf()))\nprint(next(c))\nprint(down(19, c))\ntry:\n    down(99, c)\nexcept RecursionError:\n    print('refused')\nprint(down(19, c))\ntry:\n    dthrow(99, c)\nexcept RecursionError:\n    print('throw refused')\ntry:\n    c.throw(ValueError)\nexcept ValueError:\n    print('thrown')\nd = top(mid(leaf()))\nprint(next(d))\ntry:\n    dclose(99, d)\nexcept RecursionError:\n    print('close refused')\nd.close()\nprint(probe(1))\n"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "probe")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "n")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "probe")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist
                        [:argument
                         (t4
                           [:comparison
                            [:expr
                             [:expr (at (nm "n"))]
                             ["ADD" "+"]
                             [:expr (at ["NUMBER" "1"])]]])]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            [:except_clause
             ["EXCEPT" "except"]
             (tc (at (nm "RecursionError")))]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist (tc (at (nm "n")))]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "down")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist
          [:tfpdef (nm "n")]
          ["COMMA" ","]
          [:tfpdef (nm "it")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:if_stmt
            ["IF" "if"]
            (t4
              [:comparison
               [:expr (at (nm "n"))]
               [:comp_op ["EQUALS" "=="]]
               [:expr (at ["NUMBER" "0"])]])
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "next")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist [:argument (tc (at (nm "it")))]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:return_stmt
              ["RETURN" "return"]
              [:testlist
               (tc
                 [:atom_expr
                  [:atom (nm "down")]
                  [:trailer
                   ["OPEN_PAREN" "("]
                   [:arglist
                    [:argument
                     (t4
                       [:comparison
                        [:expr
                         [:expr (at (nm "n"))]
                         ["MINUS" "-"]
                         [:expr (at ["NUMBER" "1"])]]])]
                    ["COMMA" ","]
                    [:argument (tc (at (nm "it")))]]
                   ["CLOSE_PAREN" ")"]]])]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "dthrow")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist
          [:tfpdef (nm "n")]
          ["COMMA" ","]
          [:tfpdef (nm "it")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:if_stmt
            ["IF" "if"]
            (t4
              [:comparison
               [:expr (at (nm "n"))]
               [:comp_op ["EQUALS" "=="]]
               [:expr (at ["NUMBER" "0"])]])
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "it")]
                      [:trailer ["DOT" "."] (nm "throw")]
                      [:trailer
                       ["OPEN_PAREN" "("]
                       [:arglist [:argument (tc (at (nm "ValueError")))]]
                       ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:return_stmt
              ["RETURN" "return"]
              [:testlist
               (tc
                 [:atom_expr
                  [:atom (nm "dthrow")]
                  [:trailer
                   ["OPEN_PAREN" "("]
                   [:arglist
                    [:argument
                     (t4
                       [:comparison
                        [:expr
                         [:expr (at (nm "n"))]
                         ["MINUS" "-"]
                         [:expr (at ["NUMBER" "1"])]]])]
                    ["COMMA" ","]
                    [:argument (tc (at (nm "it")))]]
                   ["CLOSE_PAREN" ")"]]])]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "dclose")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist
          [:tfpdef (nm "n")]
          ["COMMA" ","]
          [:tfpdef (nm "it")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:if_stmt
            ["IF" "if"]
            (t4
              [:comparison
               [:expr (at (nm "n"))]
               [:comp_op ["EQUALS" "=="]]
               [:expr (at ["NUMBER" "0"])]])
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:flow_stmt
                 [:return_stmt
                  ["RETURN" "return"]
                  [:testlist
                   (tc
                     [:atom_expr
                      [:atom (nm "it")]
                      [:trailer ["DOT" "."] (nm "close")]
                      [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:return_stmt
              ["RETURN" "return"]
              [:testlist
               (tc
                 [:atom_expr
                  [:atom (nm "dclose")]
                  [:trailer
                   ["OPEN_PAREN" "("]
                   [:arglist
                    [:argument
                     (t4
                       [:comparison
                        [:expr
                         [:expr (at (nm "n"))]
                         ["MINUS" "-"]
                         [:expr (at ["NUMBER" "1"])]]])]
                    ["COMMA" ","]
                    [:argument (tc (at (nm "it")))]]
                   ["CLOSE_PAREN" ")"]]])]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "leaf")
        [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:try_stmt
            ["TRY" "try"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:compound_stmt
               [:while_stmt
                ["WHILE" "while"]
                (tc (at ["TRUE" "True"]))
                ["COLON" ":"]
                [:block
                 ["NEWLINE" "\n"]
                 ["INDENT" "            "]
                 [:stmt
                  [:simple_stmts
                   [:simple_stmt
                    [:flow_stmt
                     [:yield_stmt
                      [:yield_expr
                       ["YIELD" "yield"]
                       [:yield_arg
                        [:testlist
                         (tc
                           [:atom_expr
                            [:atom (nm "probe")]
                            [:trailer
                             ["OPEN_PAREN" "("]
                             [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                             ["CLOSE_PAREN" ")"]]])]]]]]]
                   ["NEWLINE" "\n"]]]
                 ["DEDENT" ""]]]]]
             ["DEDENT" ""]]
            ["FINALLY" "finally"]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:expr_stmt
                 [:testlist_star_expr
                  (tc
                    [:atom_expr
                     [:atom (nm "print")]
                     [:trailer
                      ["OPEN_PAREN" "("]
                      [:arglist
                       [:argument
                        (tc
                          [:atom_expr
                           [:atom (nm "probe")]
                           [:trailer
                            ["OPEN_PAREN" "("]
                            [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                            ["CLOSE_PAREN" ")"]]])]]
                      ["CLOSE_PAREN" ")"]]])]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "mid")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "g")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:yield_stmt
              [:yield_expr
               ["YIELD" "yield"]
               [:yield_arg ["FROM" "from"] (tc (at (nm "g")))]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        (nm "top")
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef (nm "g")]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:yield_stmt
              [:yield_expr
               ["YIELD" "yield"]
               [:yield_arg ["FROM" "from"] (tc (at (nm "g")))]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr (tc (at (nm "c")))]
         ["ASSIGN" "="]
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "top")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "mid")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument
                      (tc
                        [:atom_expr
                         [:atom (nm "leaf")]
                         [:trailer
                          ["OPEN_PAREN" "("]
                          ["CLOSE_PAREN" ")"]]])]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "next")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at (nm "c")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument (tc (at ["NUMBER" "19"]))]
                     ["COMMA" ","]
                     [:argument (tc (at (nm "c")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "down")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument (tc (at ["NUMBER" "99"]))]
                   ["COMMA" ","]
                   [:argument (tc (at (nm "c")))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (at (nm "RecursionError")))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "print")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at ["STRING" "'refused'"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "down")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument (tc (at ["NUMBER" "19"]))]
                     ["COMMA" ","]
                     [:argument (tc (at (nm "c")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "dthrow")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument (tc (at ["NUMBER" "99"]))]
                   ["COMMA" ","]
                   [:argument (tc (at (nm "c")))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (at (nm "RecursionError")))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "print")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument (tc (at ["STRING" "'throw refused'"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "c")]
                 [:trailer ["DOT" "."] (nm "throw")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at (nm "ValueError")))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (at (nm "ValueError")))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "print")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist [:argument (tc (at ["STRING" "'thrown'"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr (tc (at (nm "d")))]
         ["ASSIGN" "="]
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "top")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "mid")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist
                     [:argument
                      (tc
                        [:atom_expr
                         [:atom (nm "leaf")]
                         [:trailer
                          ["OPEN_PAREN" "("]
                          ["CLOSE_PAREN" ")"]]])]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "next")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at (nm "d")))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "dclose")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument (tc (at ["NUMBER" "99"]))]
                   ["COMMA" ","]
                   [:argument (tc (at (nm "d")))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (at (nm "RecursionError")))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              (tc
                [:atom_expr
                 [:atom (nm "print")]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument (tc (at ["STRING" "'close refused'"]))]]
                  ["CLOSE_PAREN" ")"]]])]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "d")]
             [:trailer ["DOT" "."] (nm "close")]
             [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          (tc
            [:atom_expr
             [:atom (nm "print")]
             [:trailer
              ["OPEN_PAREN" "("]
              [:arglist
               [:argument
                (tc
                  [:atom_expr
                   [:atom (nm "probe")]
                   [:trailer
                    ["OPEN_PAREN" "("]
                    [:arglist [:argument (tc (at ["NUMBER" "1"]))]]
                    ["CLOSE_PAREN" ")"]]])]]
              ["CLOSE_PAREN" ")"]]])]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))
