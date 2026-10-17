(ns yang.python.antlr.trace-programs
  "Python programs for safepoint slice 3 (tracing), as the CST packets
   the JVM parser produces for their sources (each def's docstring is
   the source). `yang.python.antlr.e2e-test` checks each packet against
   the parser, so the two cannot drift."
  (:require
    [yang.python.antlr.lower-portable-test :refer [packet]]))


(def trace-basic
  "import sys\ndef tr(frame, event, arg):\n    print(event, frame.f_code.co_name, frame.f_lineno)\n    return tr\ndef g(n):\n    return n + 1\ndef f(n):\n    x = g(n)\n    return x\nsys.settrace(tr)\nf(1)\nsys.settrace(None)\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name [:dotted_name [:name ["NAME" "sys"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "tr"]]
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist
          [:tfpdef [:name ["NAME" "frame"]]]
          ["COMMA" ","]
          [:tfpdef [:name ["NAME" "event"]]]
          ["COMMA" ","]
          [:tfpdef [:name ["NAME" "arg"]]]]
         ["CLOSE_PAREN" ")"]]
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
                               [:atom [:name ["NAME" "event"]]]]]]]]]]]
                       ["COMMA" ","]
                       [:argument
                        [:test
                         [:or_test
                          [:and_test
                           [:not_test
                            [:comparison
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "frame"]]]
                               [:trailer
                                ["DOT" "."]
                                [:name ["NAME" "f_code"]]]
                               [:trailer
                                ["DOT" "."]
                                [:name ["NAME" "co_name"]]]]]]]]]]]
                       ["COMMA" ","]
                       [:argument
                        [:test
                         [:or_test
                          [:and_test
                           [:not_test
                            [:comparison
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "frame"]]]
                               [:trailer
                                ["DOT" "."]
                                [:name ["NAME" "f_lineno"]]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
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
                     [:atom_expr [:atom [:name ["NAME" "tr"]]]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "g"]]
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef [:name ["NAME" "n"]]]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
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
                     [:expr [:atom_expr [:atom [:name ["NAME" "n"]]]]]
                     ["ADD" "+"]
                     [:expr [:atom_expr [:atom ["NUMBER" "1"]]]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "f"]]
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef [:name ["NAME" "n"]]]]
         ["CLOSE_PAREN" ")"]]
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
                   [:expr [:atom_expr [:atom [:name ["NAME" "x"]]]]]]]]]]]
             ["ASSIGN" "="]
             [:testlist_star_expr
              [:test
               [:or_test
                [:and_test
                 [:not_test
                  [:comparison
                   [:expr
                    [:atom_expr
                     [:atom [:name ["NAME" "g"]]]
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
                               [:atom [:name ["NAME" "n"]]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
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
                     [:atom_expr [:atom [:name ["NAME" "x"]]]]]]]]]]]]]]
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
                 [:atom [:name ["NAME" "sys"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "settrace"]]]
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
                           [:atom [:name ["NAME" "tr"]]]]]]]]]]]]
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
                 [:atom [:name ["NAME" "f"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom ["NUMBER" "1"]]]]]]]]]]]
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
                 [:atom [:name ["NAME" "sys"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "settrace"]]]
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
                          [:atom_expr [:atom ["NONE" "None"]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def trace-profile
  "import sys\nevents = []\ndef p(frame, event, arg):\n    events.append(event)\ndef g(n):\n    return n + 1\nsys.setprofile(p)\ng(1)\ng(2)\nsys.setprofile(None)\nprint(len(events))\nprint(events)\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name [:dotted_name [:name ["NAME" "sys"]]]]]]]]
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
               [:expr [:atom_expr [:atom [:name ["NAME" "events"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom ["OPEN_BRACK" "["] ["CLOSE_BRACK" "]"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "p"]]
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist
          [:tfpdef [:name ["NAME" "frame"]]]
          ["COMMA" ","]
          [:tfpdef [:name ["NAME" "event"]]]
          ["COMMA" ","]
          [:tfpdef [:name ["NAME" "arg"]]]]
         ["CLOSE_PAREN" ")"]]
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
                     [:atom [:name ["NAME" "events"]]]
                     [:trailer ["DOT" "."] [:name ["NAME" "append"]]]
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
                               [:atom [:name ["NAME" "event"]]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "g"]]
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef [:name ["NAME" "n"]]]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
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
                     [:expr [:atom_expr [:atom [:name ["NAME" "n"]]]]]
                     ["ADD" "+"]
                     [:expr [:atom_expr [:atom ["NUMBER" "1"]]]]]]]]]]]]]]
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
                 [:atom [:name ["NAME" "sys"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "setprofile"]]]
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
                          [:atom_expr [:atom [:name ["NAME" "p"]]]]]]]]]]]]
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
                 [:atom [:name ["NAME" "g"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom ["NUMBER" "1"]]]]]]]]]]]
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
                 [:atom [:name ["NAME" "g"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
                  [:arglist
                   [:argument
                    [:test
                     [:or_test
                      [:and_test
                       [:not_test
                        [:comparison
                         [:expr [:atom_expr [:atom ["NUMBER" "2"]]]]]]]]]]]
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
                 [:atom [:name ["NAME" "sys"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "setprofile"]]]
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
                          [:atom_expr [:atom ["NONE" "None"]]]]]]]]]]]
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
                           [:atom [:name ["NAME" "len"]]]
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
                                      [:name ["NAME" "events"]]]]]]]]]]]]
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
                           [:atom [:name ["NAME" "events"]]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def trace-unwind
  "import sys\ndef tr(frame, event, arg):\n    print(event, frame.f_code.co_name, frame.f_lineno)\n    return tr\ndef thrower():\n    raise ValueError\ndef mid():\n    try:\n        thrower()\n    except ValueError:\n        pass\n    return 1\nsys.settrace(tr)\nmid()\nsys.settrace(None)\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name [:dotted_name [:name ["NAME" "sys"]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "tr"]]
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist
          [:tfpdef [:name ["NAME" "frame"]]]
          ["COMMA" ","]
          [:tfpdef [:name ["NAME" "event"]]]
          ["COMMA" ","]
          [:tfpdef [:name ["NAME" "arg"]]]]
         ["CLOSE_PAREN" ")"]]
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
                               [:atom [:name ["NAME" "event"]]]]]]]]]]]
                       ["COMMA" ","]
                       [:argument
                        [:test
                         [:or_test
                          [:and_test
                           [:not_test
                            [:comparison
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "frame"]]]
                               [:trailer
                                ["DOT" "."]
                                [:name ["NAME" "f_code"]]]
                               [:trailer
                                ["DOT" "."]
                                [:name ["NAME" "co_name"]]]]]]]]]]]
                       ["COMMA" ","]
                       [:argument
                        [:test
                         [:or_test
                          [:and_test
                           [:not_test
                            [:comparison
                             [:expr
                              [:atom_expr
                               [:atom [:name ["NAME" "frame"]]]
                               [:trailer
                                ["DOT" "."]
                                [:name ["NAME" "f_lineno"]]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
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
                     [:atom_expr [:atom [:name ["NAME" "tr"]]]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "thrower"]]
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
                     [:atom [:name ["NAME" "ValueError"]]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "mid"]]
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
                      [:comparison
                       [:expr
                        [:atom_expr
                         [:atom [:name ["NAME" "thrower"]]]
                         [:trailer
                          ["OPEN_PAREN" "("]
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
                  [:expr
                   [:atom_expr
                    [:atom [:name ["NAME" "ValueError"]]]]]]]]]]]
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
             [:return_stmt
              ["RETURN" "return"]
              [:testlist
               [:test
                [:or_test
                 [:and_test
                  [:not_test
                   [:comparison
                    [:expr [:atom_expr [:atom ["NUMBER" "1"]]]]]]]]]]]]]
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
                 [:atom [:name ["NAME" "sys"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "settrace"]]]
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
                           [:atom [:name ["NAME" "tr"]]]]]]]]]]]]
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
                 [:atom [:name ["NAME" "mid"]]]
                 [:trailer
                  ["OPEN_PAREN" "("]
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
                 [:atom [:name ["NAME" "sys"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "settrace"]]]
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
                          [:atom_expr [:atom ["NONE" "None"]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def trace-recursion
  "import sys\nevents = []\ndef tr(frame, event, arg):\n    events.append(event)\ndef f(n):\n    return f(n + 1)\ndef probe():\n    try:\n        f(0)\n    except RecursionError:\n        return 'rec'\nsys.setprofile(tr)\nprint(probe())\nsys.setprofile(None)\nprint(len(events))\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name [:dotted_name [:name ["NAME" "sys"]]]]]]]]
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
               [:expr [:atom_expr [:atom [:name ["NAME" "events"]]]]]]]]]]]
         ["ASSIGN" "="]
         [:testlist_star_expr
          [:test
           [:or_test
            [:and_test
             [:not_test
              [:comparison
               [:expr
                [:atom_expr
                 [:atom ["OPEN_BRACK" "["] ["CLOSE_BRACK" "]"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "tr"]]
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist
          [:tfpdef [:name ["NAME" "frame"]]]
          ["COMMA" ","]
          [:tfpdef [:name ["NAME" "event"]]]
          ["COMMA" ","]
          [:tfpdef [:name ["NAME" "arg"]]]]
         ["CLOSE_PAREN" ")"]]
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
                     [:atom [:name ["NAME" "events"]]]
                     [:trailer ["DOT" "."] [:name ["NAME" "append"]]]
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
                               [:atom [:name ["NAME" "event"]]]]]]]]]]]]
                      ["CLOSE_PAREN" ")"]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "f"]]
        [:parameters
         ["OPEN_PAREN" "("]
         [:typedargslist [:tfpdef [:name ["NAME" "n"]]]]
         ["CLOSE_PAREN" ")"]]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
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
                      [:atom [:name ["NAME" "f"]]]
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
                               [:expr
                                [:atom_expr [:atom [:name ["NAME" "n"]]]]]
                               ["ADD" "+"]
                               [:expr
                                [:atom_expr
                                 [:atom ["NUMBER" "1"]]]]]]]]]]]]
                       ["CLOSE_PAREN" ")"]]]]]]]]]]]]]
           ["NEWLINE" "\n"]]]
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:funcdef
        ["DEF" "def"]
        [:name ["NAME" "probe"]]
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
                      [:comparison
                       [:expr
                        [:atom_expr
                         [:atom [:name ["NAME" "f"]]]
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
                                   [:atom ["NUMBER" "0"]]]]]]]]]]]
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
                  [:expr
                   [:atom_expr
                    [:atom [:name ["NAME" "RecursionError"]]]]]]]]]]]
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
                   [:test
                    [:or_test
                     [:and_test
                      [:not_test
                       [:comparison
                        [:expr
                         [:atom_expr [:atom ["STRING" "'rec'"]]]]]]]]]]]]]
               ["NEWLINE" "\n"]]]
             ["DEDENT" ""]]]]]
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
                 [:atom [:name ["NAME" "sys"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "setprofile"]]]
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
                           [:atom [:name ["NAME" "tr"]]]]]]]]]]]]
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
                           [:atom [:name ["NAME" "probe"]]]
                           [:trailer
                            ["OPEN_PAREN" "("]
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
                 [:atom [:name ["NAME" "sys"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "setprofile"]]]
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
                          [:atom_expr [:atom ["NONE" "None"]]]]]]]]]]]
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
                           [:atom [:name ["NAME" "len"]]]
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
                                      [:name ["NAME" "events"]]]]]]]]]]]]
                            ["CLOSE_PAREN" ")"]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(def trace-unset
  "import sys\nsys.settrace(None)\n"
  (packet
    [:file_input
     [:stmt
      [:simple_stmts
       [:simple_stmt
        [:import_stmt
         [:import_name
          ["IMPORT" "import"]
          [:dotted_as_names
           [:dotted_as_name [:dotted_name [:name ["NAME" "sys"]]]]]]]]
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
                 [:atom [:name ["NAME" "sys"]]]
                 [:trailer ["DOT" "."] [:name ["NAME" "settrace"]]]
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
                          [:atom_expr [:atom ["NONE" "None"]]]]]]]]]]]
                  ["CLOSE_PAREN" ")"]]]]]]]]]]]]
       ["NEWLINE" "\n"]]]
     ["EOF" "<EOF>"]]))


(defn- with-newline
  "`trace-basic` with every NEWLINE token's text replaced by `nl`."
  [nl]
  (update trace-basic
          :yang.cst/nodes
          (fn [nodes]
            (mapv #(if (and (= :token (:kind %)) (= "NEWLINE" (:type %)))
                     (assoc % :text nl)
                     %)
                  nodes))))


(def trace-basic-crlf
  "import sys\r\ndef tr(frame, event, arg):\r\n    print(event, frame.f_code.co_name, frame.f_lineno)\r\n    return tr\r\ndef g(n):\r\n    return n + 1\r\ndef f(n):\r\n    x = g(n)\r\n    return x\r\nsys.settrace(tr)\r\nf(1)\r\nsys.settrace(None)\r\n"
  (with-newline "\r\n"))


(def trace-basic-ff
  "import sys\fdef tr(frame, event, arg):\f    print(event, frame.f_code.co_name, frame.f_lineno)\f    return tr\fdef g(n):\f    return n + 1\fdef f(n):\f    x = g(n)\f    return x\fsys.settrace(tr)\ff(1)\fsys.settrace(None)\f"
  (with-newline "\f"))
