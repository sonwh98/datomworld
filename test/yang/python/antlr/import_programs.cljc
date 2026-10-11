(ns yang.python.antlr.import-programs
  "The C4 slice I1 and I2 modules and programs, as the CST packets the
   JVM parser produces for their sources (each def's docstring is the
   source). The `i2-*` modules and the I2 mains are the sources
   `collab/1791580000000-engineer-c4-i2.oracle.py` ran under CPython
   3.9.6, which fixed their expected output.
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


(def i2-a-packet
  "print(\"a-body\")\nv = 1\n"
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
                         [:expr
                          [:atom_expr [:atom ["STRING" "\"a-body\""]]]]]]]]]]]
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
               [:expr [:atom_expr [:atom [:name ["NAME" "v"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison [:expr [:atom_expr [:atom ["NUMBER" "1"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def i2-ab-packet
  "print(\"a.b-body\")\nw = 2\n"
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
                         [:expr
                          [:atom_expr [:atom ["STRING" "\"a.b-body\""]]]]]]]]]]]
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
               [:expr [:atom_expr [:atom [:name ["NAME" "w"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison [:expr [:atom_expr [:atom ["NUMBER" "2"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def i2-abc-packet
  "print(\"a.b.c-body\")\nfrom . import w\nfrom .. import v\nfrom .d import y\nprint(__name__, __package__, w, v, y)\ntry:\n    from ... import z\nexcept ImportError as e:\n    print(e.args[0])\n"
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
                         [:expr
                          [:atom_expr
                           [:atom ["STRING" "\"a.b.c-body\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_from
          ["FROM" "from"]
          ["DOT" "."]
          ["IMPORT" "import"]
          [:import_as_names [:import_as_name [:name ["NAME" "w"]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_from
          ["FROM" "from"]
          ["DOT" "."]
          ["DOT" "."]
          ["IMPORT" "import"]
          [:import_as_names [:import_as_name [:name ["NAME" "v"]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_from
          ["FROM" "from"]
          ["DOT" "."]
          [:dotted_name [:name ["NAME" "d"]]]
          ["IMPORT" "import"]
          [:import_as_names [:import_as_name [:name ["NAME" "y"]]]]]]]
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
                          [:atom_expr [:atom [:name ["NAME" "__name__"]]]]]]]]]]]
                   ["COMMA" ","]
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr
                          [:atom_expr
                           [:atom [:name ["NAME" "__package__"]]]]]]]]]]]
                   ["COMMA" ","]
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom [:name ["NAME" "w"]]]]]]]]]]]
                   ["COMMA" ","]
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom [:name ["NAME" "v"]]]]]]]]]]]
                   ["COMMA" ","]
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom [:name ["NAME" "y"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
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
            [:import_stmt
             [:import_from
              ["FROM" "from"]
              ["ELLIPSIS" "..."]
              ["IMPORT" "import"]
              [:import_as_names [:import_as_name [:name ["NAME" "z"]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison
              [:expr [:atom_expr [:atom [:name ["NAME" "ImportError"]]]]]]]]]]
         ["AS" "as"]
         [:name ["NAME" "e"]]]
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
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "e"]]]
                               [:trailer ["DOT" "."] [:name ["NAME" "args"]]]
                               [:trailer
                                ["OPEN_BRACK" "["]
                                [:subscriptlist
                                 [:subscript_
                                  [:test
                                   [:or_test
                                    [:and_test
                                     [:not_test
                                      [:comparison
                                       [:expr
                                        [:atom_expr
                                         [:atom ["NUMBER" "0"]]]]]]]]]]]
                                ["CLOSE_BRACK" "]"]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" "<EOF>"]]]]]
     ["EOF" "<EOF>"]]))


(def i2-abd-packet
  "y = 3\n"
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
               [:expr [:atom_expr [:atom [:name ["NAME" "y"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison [:expr [:atom_expr [:atom ["NUMBER" "3"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def i2-g-packet
  "print(\"g-body\")\ntry:\n    from . import x\nexcept ImportError as e:\n    print(e.args[0])\nprint(__name__, repr(__package__))\n"
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
                         [:expr
                          [:atom_expr [:atom ["STRING" "\"g-body\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
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
            [:import_stmt
             [:import_from
              ["FROM" "from"]
              ["DOT" "."]
              ["IMPORT" "import"]
              [:import_as_names [:import_as_name [:name ["NAME" "x"]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison
              [:expr [:atom_expr [:atom [:name ["NAME" "ImportError"]]]]]]]]]]
         ["AS" "as"]
         [:name ["NAME" "e"]]]
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
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "e"]]]
                               [:trailer ["DOT" "."] [:name ["NAME" "args"]]]
                               [:trailer
                                ["OPEN_BRACK" "["]
                                [:subscriptlist
                                 [:subscript_
                                  [:test
                                   [:or_test
                                    [:and_test
                                     [:not_test
                                      [:comparison
                                       [:expr
                                        [:atom_expr
                                         [:atom ["NUMBER" "0"]]]]]]]]]]]
                                ["CLOSE_BRACK" "]"]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
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
                         [:expr
                          [:atom_expr [:atom [:name ["NAME" "__name__"]]]]]]]]]]]
                   ["COMMA" ","]
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr
                          [:atom_expr
                           [:atom [:name ["NAME" "repr"]]]
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
                                     [:atom
                                      [:name ["NAME" "__package__"]]]]]]]]]]]]
                            ["CLOSE_PAREN" ")"]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def i2-sx-packet
  "p = 1\nq = 2\n_h = 3\n"
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
               [:expr [:atom_expr [:atom [:name ["NAME" "p"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison [:expr [:atom_expr [:atom ["NUMBER" "1"]]]]]]]]]]]]
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
               [:expr [:atom_expr [:atom [:name ["NAME" "q"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison [:expr [:atom_expr [:atom ["NUMBER" "2"]]]]]]]]]]]]
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
               [:expr [:atom_expr [:atom [:name ["NAME" "_h"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison [:expr [:atom_expr [:atom ["NUMBER" "3"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def i2-sy-packet
  "__all__ = [\"r\"]\nr = 4\nu = 5\n"
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
               [:expr [:atom_expr [:atom [:name ["NAME" "__all__"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom
                  ["OPEN_BRACK" "["]
                  [:testlist_comp
                   [:test
                    [:or_test
                     [:and_test
                      [:not_test
                       [:comparison
                        [:expr [:atom_expr [:atom ["STRING" "\"r\""]]]]]]]]]]
                  ["CLOSE_BRACK" "]"]]]]]]]]]]]]
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
               [:expr [:atom_expr [:atom [:name ["NAME" "r"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison [:expr [:atom_expr [:atom ["NUMBER" "4"]]]]]]]]]]]]
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
               [:expr [:atom_expr [:atom [:name ["NAME" "u"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison [:expr [:atom_expr [:atom ["NUMBER" "5"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def i2-nf-packet
  "print(\"nf-body\")\n"
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
                         [:expr
                          [:atom_expr [:atom ["STRING" "\"nf-body\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def i2-nfsub-packet
  "print(\"sub-body\")\n"
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
                         [:expr
                          [:atom_expr [:atom ["STRING" "\"sub-body\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def dotted-main-packet
  "import a.b.c\nimport a.b.c\nprint(a.b.c.__name__)\nprint(a.b.__package__, a.__package__, a.b.c.__package__)\nprint(a.b.d.y)\n"
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
            [:dotted_name
             [:name ["NAME" "a"]]
             ["DOT" "."]
             [:name ["NAME" "b"]]
             ["DOT" "."]
             [:name ["NAME" "c"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name
            [:dotted_name
             [:name ["NAME" "a"]]
             ["DOT" "."]
             [:name ["NAME" "b"]]
             ["DOT" "."]
             [:name ["NAME" "c"]]]]]]]]
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
                           [:atom [:name ["NAME" "a"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "b"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "c"]]]
                           [:trailer
                            ["DOT" "."]
                            [:name ["NAME" "__name__"]]]]]]]]]]]]
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
                           [:atom [:name ["NAME" "a"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "b"]]]
                           [:trailer
                            ["DOT" "."]
                            [:name ["NAME" "__package__"]]]]]]]]]]]
                   ["COMMA" ","]
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr
                          [:atom_expr
                           [:atom [:name ["NAME" "a"]]]
                           [:trailer
                            ["DOT" "."]
                            [:name ["NAME" "__package__"]]]]]]]]]]]
                   ["COMMA" ","]
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr
                          [:atom_expr
                           [:atom [:name ["NAME" "a"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "b"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "c"]]]
                           [:trailer
                            ["DOT" "."]
                            [:name ["NAME" "__package__"]]]]]]]]]]]]
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
                           [:atom [:name ["NAME" "a"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "b"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "d"]]]
                           [:trailer ["DOT" "."] [:name ["NAME" "y"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def packageless-main-packet
  "try:\n    from . import x\nexcept ImportError as e:\n    print(e.args[0])\ntry:\n    from .m import y\nexcept ImportError as e:\n    print(e.args[0])\ntry:\n    from .. import z\nexcept ImportError as e:\n    print(e.args[0])\nprint(\"after\", repr(__package__))\n"
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
             [:import_from
              ["FROM" "from"]
              ["DOT" "."]
              ["IMPORT" "import"]
              [:import_as_names [:import_as_name [:name ["NAME" "x"]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison
              [:expr [:atom_expr [:atom [:name ["NAME" "ImportError"]]]]]]]]]]
         ["AS" "as"]
         [:name ["NAME" "e"]]]
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
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "e"]]]
                               [:trailer ["DOT" "."] [:name ["NAME" "args"]]]
                               [:trailer
                                ["OPEN_BRACK" "["]
                                [:subscriptlist
                                 [:subscript_
                                  [:test
                                   [:or_test
                                    [:and_test
                                     [:not_test
                                      [:comparison
                                       [:expr
                                        [:atom_expr
                                         [:atom ["NUMBER" "0"]]]]]]]]]]]
                                ["CLOSE_BRACK" "]"]]]]]]]]]]]
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
             [:import_from
              ["FROM" "from"]
              ["DOT" "."]
              [:dotted_name [:name ["NAME" "m"]]]
              ["IMPORT" "import"]
              [:import_as_names [:import_as_name [:name ["NAME" "y"]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison
              [:expr [:atom_expr [:atom [:name ["NAME" "ImportError"]]]]]]]]]]
         ["AS" "as"]
         [:name ["NAME" "e"]]]
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
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "e"]]]
                               [:trailer ["DOT" "."] [:name ["NAME" "args"]]]
                               [:trailer
                                ["OPEN_BRACK" "["]
                                [:subscriptlist
                                 [:subscript_
                                  [:test
                                   [:or_test
                                    [:and_test
                                     [:not_test
                                      [:comparison
                                       [:expr
                                        [:atom_expr
                                         [:atom ["NUMBER" "0"]]]]]]]]]]]
                                ["CLOSE_BRACK" "]"]]]]]]]]]]]
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
             [:import_from
              ["FROM" "from"]
              ["DOT" "."]
              ["DOT" "."]
              ["IMPORT" "import"]
              [:import_as_names [:import_as_name [:name ["NAME" "z"]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison
              [:expr [:atom_expr [:atom [:name ["NAME" "ImportError"]]]]]]]]]]
         ["AS" "as"]
         [:name ["NAME" "e"]]]
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
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "e"]]]
                               [:trailer ["DOT" "."] [:name ["NAME" "args"]]]
                               [:trailer
                                ["OPEN_BRACK" "["]
                                [:subscriptlist
                                 [:subscript_
                                  [:test
                                   [:or_test
                                    [:and_test
                                     [:not_test
                                      [:comparison
                                       [:expr
                                        [:atom_expr
                                         [:atom ["NUMBER" "0"]]]]]]]]]]]
                                ["CLOSE_BRACK" "]"]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
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
                         [:expr
                          [:atom_expr [:atom ["STRING" "\"after\""]]]]]]]]]]
                   ["COMMA" ","]
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr
                          [:atom_expr
                           [:atom [:name ["NAME" "repr"]]]
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
                                     [:atom
                                      [:name ["NAME" "__package__"]]]]]]]]]]]]
                            ["CLOSE_PAREN" ")"]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def nosub-main-packet
  "import nf\ntry:\n    from nf import sub\nexcept ImportError as e:\n    print(e.args[0])\ntry:\n    from nf import zz\nexcept ImportError as e:\n    print(e.args[0])\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name [:dotted_name [:name ["NAME" "nf"]]]]]]]]
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
            [:import_stmt
             [:import_from
              ["FROM" "from"]
              [:dotted_name [:name ["NAME" "nf"]]]
              ["IMPORT" "import"]
              [:import_as_names [:import_as_name [:name ["NAME" "sub"]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison
              [:expr [:atom_expr [:atom [:name ["NAME" "ImportError"]]]]]]]]]]
         ["AS" "as"]
         [:name ["NAME" "e"]]]
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
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "e"]]]
                               [:trailer ["DOT" "."] [:name ["NAME" "args"]]]
                               [:trailer
                                ["OPEN_BRACK" "["]
                                [:subscriptlist
                                 [:subscript_
                                  [:test
                                   [:or_test
                                    [:and_test
                                     [:not_test
                                      [:comparison
                                       [:expr
                                        [:atom_expr
                                         [:atom ["NUMBER" "0"]]]]]]]]]]]
                                ["CLOSE_BRACK" "]"]]]]]]]]]]]
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
             [:import_from
              ["FROM" "from"]
              [:dotted_name [:name ["NAME" "nf"]]]
              ["IMPORT" "import"]
              [:import_as_names [:import_as_name [:name ["NAME" "zz"]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison
              [:expr [:atom_expr [:atom [:name ["NAME" "ImportError"]]]]]]]]]]
         ["AS" "as"]
         [:name ["NAME" "e"]]]
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
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "e"]]]
                               [:trailer ["DOT" "."] [:name ["NAME" "args"]]]
                               [:trailer
                                ["OPEN_BRACK" "["]
                                [:subscriptlist
                                 [:subscript_
                                  [:test
                                   [:or_test
                                    [:and_test
                                     [:not_test
                                      [:comparison
                                       [:expr
                                        [:atom_expr
                                         [:atom ["NUMBER" "0"]]]]]]]]]]]
                                ["CLOSE_BRACK" "]"]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" "<EOF>"]]]]]
     ["EOF" "<EOF>"]]))


(def forms-main-packet
  "import a.b as x\nprint(x.__name__)\nfrom a.b import w as ww\nprint(ww)\nfrom a import b\nprint(b.__name__)\nimport a\ndel a.b\nfrom a import b as bb\nprint(bb.__name__)\nimport g\nprint(g.__package__ == \"\")\n"
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
            [:dotted_name [:name ["NAME" "a"]] ["DOT" "."] [:name ["NAME" "b"]]]
            ["AS" "as"]
            [:name ["NAME" "x"]]]]]]]
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
                           [:atom [:name ["NAME" "x"]]]
                           [:trailer
                            ["DOT" "."]
                            [:name ["NAME" "__name__"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_from
          ["FROM" "from"]
          [:dotted_name [:name ["NAME" "a"]] ["DOT" "."] [:name ["NAME" "b"]]]
          ["IMPORT" "import"]
          [:import_as_names
           [:import_as_name
            [:name ["NAME" "w"]]
            ["AS" "as"]
            [:name ["NAME" "ww"]]]]]]]
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
                          [:atom_expr [:atom [:name ["NAME" "ww"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_from
          ["FROM" "from"]
          [:dotted_name [:name ["NAME" "a"]]]
          ["IMPORT" "import"]
          [:import_as_names [:import_as_name [:name ["NAME" "b"]]]]]]]
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
                           [:atom [:name ["NAME" "b"]]]
                           [:trailer
                            ["DOT" "."]
                            [:name ["NAME" "__name__"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name [:dotted_name [:name ["NAME" "a"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:del_stmt
         ["DEL" "del"]
         [:exprlist
          [:expr
           [:atom_expr
            [:atom [:name ["NAME" "a"]]]
            [:trailer ["DOT" "."] [:name ["NAME" "b"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_from
          ["FROM" "from"]
          [:dotted_name [:name ["NAME" "a"]]]
          ["IMPORT" "import"]
          [:import_as_names
           [:import_as_name
            [:name ["NAME" "b"]]
            ["AS" "as"]
            [:name ["NAME" "bb"]]]]]]]
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
                           [:atom [:name ["NAME" "bb"]]]
                           [:trailer
                            ["DOT" "."]
                            [:name ["NAME" "__name__"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name [:dotted_name [:name ["NAME" "g"]]]]]]]]
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
                           [:atom [:name ["NAME" "g"]]]
                           [:trailer
                            ["DOT" "."]
                            [:name ["NAME" "__package__"]]]]]
                         [:comp_op ["EQUALS" "=="]]
                         [:expr [:atom_expr [:atom ["STRING" "\"\""]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def star-main-packet
  "from sx import *\nprint(p, q)\ntry:\n    print(_h)\nexcept NameError as e:\n    print(e.args[0])\nfrom sy import *\nprint(r)\ntry:\n    print(u)\nexcept NameError as e:\n    print(e.args[0])\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_from
          ["FROM" "from"]
          [:dotted_name [:name ["NAME" "sx"]]]
          ["IMPORT" "import"]
          ["STAR" "*"]]]]
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
                         [:expr [:atom_expr [:atom [:name ["NAME" "p"]]]]]]]]]]]
                   ["COMMA" ","]
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom [:name ["NAME" "q"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
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
                              [:atom_expr [:atom [:name ["NAME" "_h"]]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison
              [:expr [:atom_expr [:atom [:name ["NAME" "NameError"]]]]]]]]]]
         ["AS" "as"]
         [:name ["NAME" "e"]]]
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
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "e"]]]
                               [:trailer ["DOT" "."] [:name ["NAME" "args"]]]
                               [:trailer
                                ["OPEN_BRACK" "["]
                                [:subscriptlist
                                 [:subscript_
                                  [:test
                                   [:or_test
                                    [:and_test
                                     [:not_test
                                      [:comparison
                                       [:expr
                                        [:atom_expr
                                         [:atom ["NUMBER" "0"]]]]]]]]]]]
                                ["CLOSE_BRACK" "]"]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_from
          ["FROM" "from"]
          [:dotted_name [:name ["NAME" "sy"]]]
          ["IMPORT" "import"]
          ["STAR" "*"]]]]
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
                         [:expr [:atom_expr [:atom [:name ["NAME" "r"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
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
                              [:atom_expr [:atom [:name ["NAME" "u"]]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison
              [:expr [:atom_expr [:atom [:name ["NAME" "NameError"]]]]]]]]]]
         ["AS" "as"]
         [:name ["NAME" "e"]]]
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
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "e"]]]
                               [:trailer ["DOT" "."] [:name ["NAME" "args"]]]
                               [:trailer
                                ["OPEN_BRACK" "["]
                                [:subscriptlist
                                 [:subscript_
                                  [:test
                                   [:or_test
                                    [:and_test
                                     [:not_test
                                      [:comparison
                                       [:expr
                                        [:atom_expr
                                         [:atom ["NUMBER" "0"]]]]]]]]]]]
                                ["CLOSE_BRACK" "]"]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" "<EOF>"]]]]]
     ["EOF" "<EOF>"]]))


(def star-in-function-packet
  "def f():\n    from sx import *\n"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "f"]]
        [:parameters ["OPEN_PAREN" "("] ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:simple_stmts
           [:simple_stmt
            [:import_stmt
             [:import_from
              ["FROM" "from"]
              [:dotted_name [:name ["NAME" "sx"]]]
              ["IMPORT" "import"]
              ["STAR" "*"]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" "<EOF>"]]]]]
     ["EOF" "<EOF>"]]))


(def alias-rebind-main-packet
  "import a.b\na.b = 42\nimport a.b as x\nprint(x)\ndel a.b\nimport a.b as y\nprint(y.__name__)\n"
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
            [:dotted_name
             [:name ["NAME" "a"]]
             ["DOT" "."]
             [:name ["NAME" "b"]]]]]]]]
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
                 [:atom [:name ["NAME" "a"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "b"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison [:expr [:atom_expr [:atom ["NUMBER" "42"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name
            [:dotted_name [:name ["NAME" "a"]] ["DOT" "."] [:name ["NAME" "b"]]]
            ["AS" "as"]
            [:name ["NAME" "x"]]]]]]]
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
                         [:expr [:atom_expr [:atom [:name ["NAME" "x"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:del_stmt
         ["DEL" "del"]
         [:exprlist
          [:expr
           [:atom_expr
            [:atom [:name ["NAME" "a"]]]
            [:trailer ["DOT" "."] [:name ["NAME" "b"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name
            [:dotted_name [:name ["NAME" "a"]] ["DOT" "."] [:name ["NAME" "b"]]]
            ["AS" "as"]
            [:name ["NAME" "y"]]]]]]]
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
                           [:atom [:name ["NAME" "y"]]]
                           [:trailer
                            ["DOT" "."]
                            [:name ["NAME" "__name__"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def from-dict-main-packet
  "from sx import __dict__ as d\nprint(d[\"p\"], d[\"__name__\"])\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_from
          ["FROM" "from"]
          [:dotted_name [:name ["NAME" "sx"]]]
          ["IMPORT" "import"]
          [:import_as_names
           [:import_as_name
            [:name ["NAME" "__dict__"]]
            ["AS" "as"]
            [:name ["NAME" "d"]]]]]]]
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
                           [:atom [:name ["NAME" "d"]]]
                           [:trailer
                            ["OPEN_BRACK" "["]
                            [:subscriptlist
                             [:subscript_
                              [:test
                               [:or_test
                                [:and_test
                                 [:not_test
                                  [:comparison
                                   [:expr
                                    [:atom_expr
                                     [:atom ["STRING" "\"p\""]]]]]]]]]]]
                            ["CLOSE_BRACK" "]"]]]]]]]]]]
                   ["COMMA" ","]
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr
                          [:atom_expr
                           [:atom [:name ["NAME" "d"]]]
                           [:trailer
                            ["OPEN_BRACK" "["]
                            [:subscriptlist
                             [:subscript_
                              [:test
                               [:or_test
                                [:and_test
                                 [:not_test
                                  [:comparison
                                   [:expr
                                    [:atom_expr
                                     [:atom ["STRING" "\"__name__\""]]]]]]]]]]]
                            ["CLOSE_BRACK" "]"]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def star-bad-main-packet
  "import sy\nsy.__all__ = [1]\ntry:\n    from sy import *\nexcept TypeError as e:\n    print(e.args[0])\nimport sx\nsx.__dict__[1] = 42\ntry:\n    from sx import *\nexcept TypeError as e:\n    print(e.args[0])\nprint(p, q)\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name [:dotted_name [:name ["NAME" "sy"]]]]]]]]
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
                 [:atom [:name ["NAME" "sy"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "__all__"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom
                  ["OPEN_BRACK" "["]
                  [:testlist_comp
                   [:test
                    [:or_test
                     [:and_test
                      [:not_test
                       [:comparison
                        [:expr [:atom_expr [:atom ["NUMBER" "1"]]]]]]]]]]
                  ["CLOSE_BRACK" "]"]]]]]]]]]]]]
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
            [:import_stmt
             [:import_from
              ["FROM" "from"]
              [:dotted_name [:name ["NAME" "sy"]]]
              ["IMPORT" "import"]
              ["STAR" "*"]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison
              [:expr [:atom_expr [:atom [:name ["NAME" "TypeError"]]]]]]]]]]
         ["AS" "as"]
         [:name ["NAME" "e"]]]
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
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "e"]]]
                               [:trailer ["DOT" "."] [:name ["NAME" "args"]]]
                               [:trailer
                                ["OPEN_BRACK" "["]
                                [:subscriptlist
                                 [:subscript_
                                  [:test
                                   [:or_test
                                    [:and_test
                                     [:not_test
                                      [:comparison
                                       [:expr
                                        [:atom_expr
                                         [:atom ["NUMBER" "0"]]]]]]]]]]]
                                ["CLOSE_BRACK" "]"]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name [:dotted_name [:name ["NAME" "sx"]]]]]]]]
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
                 [:atom [:name ["NAME" "sx"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "__dict__"]]]
                 [:trailer
                  ["OPEN_BRACK" "["]
                  [:subscriptlist
                   [:subscript_
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom ["NUMBER" "1"]]]]]]]]]]]
                  ["CLOSE_BRACK" "]"]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison [:expr [:atom_expr [:atom ["NUMBER" "42"]]]]]]]]]]]]
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
            [:import_stmt
             [:import_from
              ["FROM" "from"]
              [:dotted_name [:name ["NAME" "sx"]]]
              ["IMPORT" "import"]
              ["STAR" "*"]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         [:test
          [:or_test
           [:and_test
            [:not_test
             [:comparison
              [:expr [:atom_expr [:atom [:name ["NAME" "TypeError"]]]]]]]]]]
         ["AS" "as"]
         [:name ["NAME" "e"]]]
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
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "e"]]]
                               [:trailer ["DOT" "."] [:name ["NAME" "args"]]]
                               [:trailer
                                ["OPEN_BRACK" "["]
                                [:subscriptlist
                                 [:subscript_
                                  [:test
                                   [:or_test
                                    [:and_test
                                     [:not_test
                                      [:comparison
                                       [:expr
                                        [:atom_expr
                                         [:atom ["NUMBER" "0"]]]]]]]]]]]
                                ["CLOSE_BRACK" "]"]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
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
                         [:expr [:atom_expr [:atom [:name ["NAME" "p"]]]]]]]]]]]
                   ["COMMA" ","]
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom [:name ["NAME" "q"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))
