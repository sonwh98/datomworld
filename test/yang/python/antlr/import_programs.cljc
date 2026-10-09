(ns yang.python.antlr.import-programs
  "The C4 slice I1 modules and programs, as the CST packets the JVM
   parser produces for their sources (each def's docstring is the source).
   The parser is JVM-only; these let every host run the real lowering.
   `yang.python.antlr.import-test` runs them, and
   `yang.python.antlr.e2e-test` checks each against the parser, so the
   two cannot drift."
  (:require [yang.python.antlr.lower-portable-test :refer [packet]]))


(def m-packet
  "y = 1\nval = 7\ndef read():\n    try:\n        a = y\n    except NameError:\n        a = \"gone\"\n    return (a, val)\nprint(\"m-body\")\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test [:comparison [:expr [:atom_expr [:atom [:name ["NAME" "y"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test [:comparison [:expr [:atom_expr [:atom ["NUMBER" "1"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison [:expr [:atom_expr [:atom [:name ["NAME" "val"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test [:comparison [:expr [:atom_expr [:atom ["NUMBER" "7"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "read"]]
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
                [:expr_stmt
                 [:testlist_star_expr
                  [:test
                   [:or_test
                    [:and_test
                     [:not_test
                      [:comparison [:expr [:atom_expr [:atom [:name ["NAME" "a"]]]]]]]]]]]
                 ["ASSIGN" "="]
                 [:testlist_star_expr
                  [:test
                   [:or_test
                    [:and_test
                     [:not_test
                      [:comparison [:expr [:atom_expr [:atom [:name ["NAME" "y"]]]]]]]]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]
            [:except_clause
             ["EXCEPT" "except"]
             [:test
              [:or_test
               [:and_test
                [:not_test
                 [:comparison [:expr [:atom_expr [:atom [:name ["NAME" "NameError"]]]]]]]]]]]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             [:stmt
              [:simple_stmts
               [:simple_stmt
                [:expr_stmt
                 [:testlist_star_expr
                  [:test
                   [:or_test
                    [:and_test
                     [:not_test
                      [:comparison [:expr [:atom_expr [:atom [:name ["NAME" "a"]]]]]]]]]]]
                 ["ASSIGN" "="]
                 [:testlist_star_expr
                  [:test
                   [:or_test
                    [:and_test
                     [:not_test
                      [:comparison [:expr [:atom_expr [:atom ["STRING" "\"gone\""]]]]]]]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:return_stmt
              ["RETURN" "return"]
              [:testlist
               [:test
                [:or_test
                 [:and_test
                  [:not_test
                   [:comparison
                    [:expr
                     [:atom_expr
                      [:atom
                       ["OPEN_PAREN" "("]
                       [:testlist_comp
                        [:test
                         [:or_test
                          [:and_test
                           [:not_test
                            [:comparison
                             [:expr [:atom_expr [:atom [:name ["NAME" "a"]]]]]]]]]]
                        ["COMMA" ","]
                        [:test
                         [:or_test
                          [:and_test
                           [:not_test
                            [:comparison
                             [:expr [:atom_expr [:atom [:name ["NAME" "val"]]]]]]]]]]]
                       ["CLOSE_PAREN" ")"]]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom ["STRING" "\"m-body\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def k-packet
  "kval = 3\nprint(\"k-body\")\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison [:expr [:atom_expr [:atom [:name ["NAME" "kval"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test [:comparison [:expr [:atom_expr [:atom ["NUMBER" "3"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom ["STRING" "\"k-body\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def t-packet
  "import k\nprint(\"t-sees\", k.kval)\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "k"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom ["STRING" "\"t-sees\""]]]]]]]]]]
                   ["COMMA" ","]
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr
                          [:atom_expr
                           [:atom [:name ["NAME" "k"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "kval"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def w-packet
  "print(\"w-once\")\nraise ValueError(\"boom\")\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom ["STRING" "\"w-once\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:flow_stmt
         [:raise_stmt
          ["RAISE" "raise"]
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "ValueError"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom ["STRING" "\"boom\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def e-packet
  "def boom():\n    raise ValueError(\"from-e\")\n"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "boom"]]
        [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:flow_stmt
             [:raise_stmt
              ["RAISE" "raise"]
              [:test
               [:or_test
                [:and_test
                 [:not_test
                  [:comparison
                   [:expr
                    [:atom_expr
                     [:atom [:name ["NAME" "ValueError"]]]
                     [:trailer
                      ["OPEN_PAREN" "("]
                      [:arglist
                       [:argument
                        [:test
                         [:or_test
                          [:and_test
                           [:not_test
                            [:comparison
                             [:expr [:atom_expr [:atom ["STRING" "\"from-e\""]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" "<EOF>"]]]]]
     ["EOF" "<EOF>"]]))


(def n-packet
  "print(__name__)\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom [:name ["NAME" "__name__"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def o-packet
  "print(\"a\")\nimport m\nprint(\"b\")\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison [:expr [:atom_expr [:atom ["STRING" "\"a\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "m"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison [:expr [:atom_expr [:atom ["STRING" "\"b\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def d-packet
  "import m\nx = m.val\nprint(x)\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "m"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test [:comparison [:expr [:atom_expr [:atom [:name ["NAME" "x"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "m"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "val"]]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison [:expr [:atom_expr [:atom [:name ["NAME" "x"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def c-packet
  "import m\nm.val = 5\ndel m.y\nprint(m.__dict__.get(\"val\"))\nprint(m.__dict__.get(\"y\", \"absent\"))\nprint(m.read())\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "m"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "m"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "val"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test [:comparison [:expr [:atom_expr [:atom ["NUMBER" "5"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:del_stmt
         ["DEL" "del"]
         [:exprlist
          [:expr
           [:atom_expr
            [:atom [:name ["NAME" "m"]]]
            [:trailer ["DOT" "."] [:name ["NAME" "y"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr
                          [:atom_expr
                           [:atom [:name ["NAME" "m"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "__dict__"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "get"]]]
                           [:trailer
                            ["OPEN_PAREN" "("]
                            [:arglist
                             [:argument
                              [:test
                               [:or_test
                                [:and_test
                                 [:not_test
                                  [:comparison
                                   [:expr [:atom_expr [:atom ["STRING" "\"val\""]]]]]]]]]]]
                            ["CLOSE_PAREN" ")"]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr
                          [:atom_expr
                           [:atom [:name ["NAME" "m"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "__dict__"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "get"]]]
                           [:trailer
                            ["OPEN_PAREN" "("]
                            [:arglist
                             [:argument
                              [:test
                               [:or_test
                                [:and_test
                                 [:not_test
                                  [:comparison
                                   [:expr [:atom_expr [:atom ["STRING" "\"y\""]]]]]]]]]]
                             ["COMMA" ","]
                             [:argument
                              [:test
                               [:or_test
                                [:and_test
                                 [:not_test
                                  [:comparison
                                   [:expr
                                    [:atom_expr [:atom ["STRING" "\"absent\""]]]]]]]]]]]
                            ["CLOSE_PAREN" ")"]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr
                          [:atom_expr
                           [:atom [:name ["NAME" "m"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "read"]]]
                           [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def r-packet
  "try:\n    import w\nexcept ValueError:\n    print(\"cleaned\")\ntry:\n    import w\nexcept ValueError:\n    print(\"again\")\n"
  (packet
    [:file_input
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
            [:import_stmt
             [:import_name
              ["IMPORT" "import"]
              [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "w"]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison [:expr [:atom_expr [:atom [:name ["NAME" "ValueError"]]]]]]]]]]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              [:test
               [:or_test
                [:and_test
                 [:not_test
                  [:comparison
                   [:expr
                    [:atom_expr
                     [:atom [:name ["NAME" "print"]]]
                     [:trailer
                      ["OPEN_PAREN" "("]
                      [:arglist
                       [:argument
                        [:test
                         [:or_test
                          [:and_test
                           [:not_test
                            [:comparison
                             [:expr [:atom_expr [:atom ["STRING" "\"cleaned\""]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
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
            [:import_stmt
             [:import_name
              ["IMPORT" "import"]
              [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "w"]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison [:expr [:atom_expr [:atom [:name ["NAME" "ValueError"]]]]]]]]]]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              [:test
               [:or_test
                [:and_test
                 [:not_test
                  [:comparison
                   [:expr
                    [:atom_expr
                     [:atom [:name ["NAME" "print"]]]
                     [:trailer
                      ["OPEN_PAREN" "("]
                      [:arglist
                       [:argument
                        [:test
                         [:or_test
                          [:and_test
                           [:not_test
                            [:comparison
                             [:expr [:atom_expr [:atom ["STRING" "\"again\""]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" "<EOF>"]]]]]
     ["EOF" "<EOF>"]]))


(def x-packet
  "import e\ntry:\n    e.boom()\nexcept ValueError:\n    print(\"caught-e\")\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "e"]]]]]]]]
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
              [:test
               [:or_test
                [:and_test
                 [:not_test
                  [:comparison
                   [:expr
                    [:atom_expr
                     [:atom [:name ["NAME" "e"]]]
                     [:trailer ["DOT" "."] [:name ["NAME" "boom"]]]
                     [:trailer ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison [:expr [:atom_expr [:atom [:name ["NAME" "ValueError"]]]]]]]]]]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:expr_stmt
             [:testlist_star_expr
              [:test
               [:or_test
                [:and_test
                 [:not_test
                  [:comparison
                   [:expr
                    [:atom_expr
                     [:atom [:name ["NAME" "print"]]]
                     [:trailer
                      ["OPEN_PAREN" "("]
                      [:arglist
                       [:argument
                        [:test
                         [:or_test
                          [:and_test
                           [:not_test
                            [:comparison
                             [:expr [:atom_expr [:atom ["STRING" "\"caught-e\""]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" "<EOF>"]]]]]
     ["EOF" "<EOF>"]]))


(def s-packet
  "import sys\nimport m\nprint(sys.modules.get(\"m\") is m)\nprint(sys.modules.get(\"sys\") is sys)\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "sys"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "m"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr
                          [:atom_expr
                           [:atom [:name ["NAME" "sys"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "modules"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "get"]]]
                           [:trailer
                            ["OPEN_PAREN" "("]
                            [:arglist
                             [:argument
                              [:test
                               [:or_test
                                [:and_test
                                 [:not_test
                                  [:comparison
                                   [:expr [:atom_expr [:atom ["STRING" "\"m\""]]]]]]]]]]]
                            ["CLOSE_PAREN" ")"]]]]
                         [:comp_op ["IS" "is"]]
                         [:expr [:atom_expr [:atom [:name ["NAME" "m"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr
                          [:atom_expr
                           [:atom [:name ["NAME" "sys"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "modules"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "get"]]]
                           [:trailer
                            ["OPEN_PAREN" "("]
                            [:arglist
                             [:argument
                              [:test
                               [:or_test
                                [:and_test
                                 [:not_test
                                  [:comparison
                                   [:expr [:atom_expr [:atom ["STRING" "\"sys\""]]]]]]]]]]]
                            ["CLOSE_PAREN" ")"]]]]
                         [:comp_op ["IS" "is"]]
                         [:expr [:atom_expr [:atom [:name ["NAME" "sys"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def q-packet
  "import m\nimport m\nprint(\"done\")\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "m"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "m"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom ["STRING" "\"done\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def tt-packet
  "import t\nprint(\"t-done\")\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "t"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:expr_stmt
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom [:name ["NAME" "print"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom ["STRING" "\"t-done\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def nn-packet
  "import n\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names [:dotted_as_name [:dotted_name [:name ["NAME" "n"]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def dotted-packet
  "import a.b\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name
            [:dotted_name [:name ["NAME" "a"]] ["DOT" "."] [:name ["NAME" "b"]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def aliased-packet
  "import m as mm\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name
            [:dotted_name [:name ["NAME" "m"]]]
            ["AS" "as"]
            [:name ["NAME" "mm"]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def from-packet
  "from m import val\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_from
          ["FROM" "from"]
          [:dotted_name [:name ["NAME" "m"]]]
          ["IMPORT" "import"]
          [:import_as_names [:import_as_name [:name ["NAME" "val"]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def del-packet
  "del m.y\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:del_stmt
         ["DEL" "del"]
         [:exprlist
          [:expr
           [:atom_expr
            [:atom [:name ["NAME" "m"]]]
            [:trailer ["DOT" "."] [:name ["NAME" "y"]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))
