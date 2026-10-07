(ns yang.python.antlr.c3-programs
  "The C3 integration gate's Python programs (C3 slice S7, ruling 14), as
   the CST packets the JVM parser produces for their sources: each def's
   docstring is the source, and its expected stdout is the program of the
   same name in test/resources/yang/python/c3-corpus-v1.txt. The parser
   is JVM-only; these let every host run the real lowering.
   `yang.python.antlr.c3-gate-parser-test` checks each packet against the
   parser and each docstring against the corpus, so none of the three can
   drift. One program per ruling 14 detector theme: promotion then
   cancellation, demotion, numeric keys and hashes, floor division, shifts,
   power, conversions, signed zero, and the resource limits of the `wide`
   and `small` profiles (hand pins: CPython 3.9.6 has no such limits).

   Helpers abbreviate the parser's single-child chains: `nm` a `name`,
   `vr` a variable and `lit` a number as an `atom_expr`, `par` a
   parenthesized expression, `t4` the chain from `test` down to
   `not_test`, `tx` from `test` down to `comparison`, `tc` down to `expr`,
   `e` a one-child `expr`, `op` and `un` a binary and a unary operator
   `expr`, `call` a call of a name with plain arguments, `st` a simple
   statement line, `ex` an expression statement and `asg` an assignment."
  (:require
    [yang.python.antlr.lower-portable-test :refer [packet]]))


(defn nm
  [s]
  [:name ["NAME" s]])


(defn at
  [x]
  [:atom_expr [:atom x]])


(defn vr
  [s]
  (at (nm s)))


(defn lit
  [s]
  (at ["NUMBER" s]))


(defn par
  [x]
  [:atom_expr
   [:atom ["OPEN_PAREN" "("] [:testlist_comp x] ["CLOSE_PAREN" ")"]]])


(defn t4
  [x]
  [:test [:or_test [:and_test [:not_test x]]]])


(defn tx
  [x]
  (t4 [:comparison x]))


(defn tc
  [x]
  (tx [:expr x]))


(defn e
  [x]
  [:expr x])


(def ^:private op-types
  "The parser's token type of each operator these programs use."
  {"**" "POWER", "*" "STAR", "/" "DIV", "//" "IDIV", "%" "MOD", "+" "ADD",
   "-" "MINUS", "~" "NOT_OP", "<<" "LEFT_SHIFT", ">>" "RIGHT_SHIFT",
   "&" "AND_OP", "^" "XOR", "|" "OR_OP"})


(defn op
  [l o r]
  [:expr l [(get op-types o) o] r])


(defn un
  [o r]
  [:expr [(get op-types o) o] r])


(defn call
  [f & args]
  [:atom_expr
   [:atom (nm f)]
   [:trailer
    ["OPEN_PAREN" "("]
    (into [:arglist]
          (interpose ["COMMA" ","] (mapv (fn [a] [:argument a]) args)))
    ["CLOSE_PAREN" ")"]]])


(defn st
  [x]
  [:stmt [:simple_stmts [:simple_stmt x] ["NEWLINE" "\n"]]])


(defn ex
  [x]
  [:expr_stmt [:testlist_star_expr x]])


(defn asg
  [a b]
  [:expr_stmt
   [:testlist_star_expr a]
   ["ASSIGN" "="]
   [:testlist_star_expr b]])


(def promotion
  "a = 2 ** 53
b = a + 1
print(b, b - a, b - 1 == a)
x = 9007199254740991
print(x + 1, x + 2, x + 2 - 2)
y = 2 ** 63 - 1
print(y + 1, y + 1 - 1 == y, -y - 2)
n = 1
for i in range(70):
    n = n * 2
print(n, n // 2 ** 69, n - n)
m = n
while m > 1:
    m = m // 2
print(m, n * n // n == n)
print(3 * 2 ** 62, 2 ** 62 * 3 - 2 ** 63)
s = 0
for k in range(5):
    s = s + 2 ** 64
print(s, s - 5 * 2 ** 64)
print(-(2 ** 63) - 1 + 1, (2 ** 64 + 1) * (2 ** 64 - 1))
"
  (packet
    [:file_input
     (st (asg (tc (vr "a")) (tx (op (e (lit "2")) "**" (e (lit "53"))))))
     (st (asg (tc (vr "b")) (tx (op (e (vr "a")) "+" (e (lit "1"))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (vr "b"))
             (tx (op (e (vr "b")) "-" (e (vr "a"))))
             (t4
               [:comparison
                (op (e (vr "b")) "-" (e (lit "1")))
                [:comp_op ["EQUALS" "=="]]
                (e (vr "a"))])))))
     (st (asg (tc (vr "x")) (tc (lit "9007199254740991"))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (vr "x")) "+" (e (lit "1"))))
             (tx (op (e (vr "x")) "+" (e (lit "2"))))
             (tx
               (op (op (e (vr "x")) "+" (e (lit "2"))) "-" (e (lit "2"))))))))
     (st
       (asg
         (tc (vr "y"))
         (tx (op (op (e (lit "2")) "**" (e (lit "63"))) "-" (e (lit "1"))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (vr "y")) "+" (e (lit "1"))))
             (t4
               [:comparison
                (op (op (e (vr "y")) "+" (e (lit "1"))) "-" (e (lit "1")))
                [:comp_op ["EQUALS" "=="]]
                (e (vr "y"))])
             (tx (op (un "-" (e (vr "y"))) "-" (e (lit "2"))))))))
     (st (asg (tc (vr "n")) (tc (lit "1"))))
     [:stmt
      [:compound_stmt
       [:for_stmt
        ["FOR" "for"]
        [:exprlist (e (vr "i"))]
        ["IN" "in"]
        [:testlist (tc (call "range" (tc (lit "70"))))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st (asg (tc (vr "n")) (tx (op (e (vr "n")) "*" (e (lit "2"))))))
         ["DEDENT" ""]]]]]
     (st
       (ex
         (tc
           (call
             "print"
             (tc (vr "n"))
             (tx (op (e (vr "n")) "//" (op (e (lit "2")) "**" (e (lit "69")))))
             (tx (op (e (vr "n")) "-" (e (vr "n"))))))))
     (st (asg (tc (vr "m")) (tc (vr "n"))))
     [:stmt
      [:compound_stmt
       [:while_stmt
        ["WHILE" "while"]
        (t4
          [:comparison
           (e (vr "m"))
           [:comp_op ["GREATER_THAN" ">"]]
           (e (lit "1"))])
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st (asg (tc (vr "m")) (tx (op (e (vr "m")) "//" (e (lit "2"))))))
         ["DEDENT" ""]]]]]
     (st
       (ex
         (tc
           (call
             "print"
             (tc (vr "m"))
             (t4
               [:comparison
                (op (op (e (vr "n")) "*" (e (vr "n"))) "//" (e (vr "n")))
                [:comp_op ["EQUALS" "=="]]
                (e (vr "n"))])))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "3")) "*" (op (e (lit "2")) "**" (e (lit "62")))))
             (tx
               (op
                 (op (op (e (lit "2")) "**" (e (lit "62"))) "*" (e (lit "3")))
                 "-"
                 (op (e (lit "2")) "**" (e (lit "63")))))))))
     (st (asg (tc (vr "s")) (tc (lit "0"))))
     [:stmt
      [:compound_stmt
       [:for_stmt
        ["FOR" "for"]
        [:exprlist (e (vr "k"))]
        ["IN" "in"]
        [:testlist (tc (call "range" (tc (lit "5"))))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (asg
             (tc (vr "s"))
             (tx
               (op (e (vr "s")) "+" (op (e (lit "2")) "**" (e (lit "64")))))))
         ["DEDENT" ""]]]]]
     (st
       (ex
         (tc
           (call
             "print"
             (tc (vr "s"))
             (tx
               (op
                 (e (vr "s"))
                 "-"
                 (op
                   (e (lit "5"))
                   "*"
                   (op (e (lit "2")) "**" (e (lit "64"))))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx
               (op
                 (op
                   (un
                     "-"
                     (e (par (tx (op (e (lit "2")) "**" (e (lit "63")))))))
                   "-"
                   (e (lit "1")))
                 "+"
                 (e (lit "1"))))
             (tx
               (op
                 (e
                   (par
                     (tx
                       (op
                         (op (e (lit "2")) "**" (e (lit "64")))
                         "+"
                         (e (lit "1"))))))
                 "*"
                 (e
                   (par
                     (tx
                       (op
                         (op (e (lit "2")) "**" (e (lit "64")))
                         "-"
                         (e (lit "1"))))))))))))
     ["EOF" "<EOF>"]]))


(def demotion
  "big = 2 ** 64
z = big - big
zero = 0
print(z, z == 0, z is zero, -z, z + 1)
d = {0: 'zero', 1: 'one'}
print(d[z], d[(big + 1) - big], d[big // big])
d[big - 1 - (big - 2)] = 'uno'
print(d)
s = {z, 0, 0.0, big - big}
print(len(s), s)
print(hash(z), hash(big), hash(big - big + 2 ** 61 - 1))
print(z in [0], [z] == [0], (z,) == (0,), {z: 1}[0])
t = big * 3
print(t // 3 - big, t % 3, (t // big) * 7)
"
  (packet
    [:file_input
     (st (asg (tc (vr "big")) (tx (op (e (lit "2")) "**" (e (lit "64"))))))
     (st (asg (tc (vr "z")) (tx (op (e (vr "big")) "-" (e (vr "big"))))))
     (st (asg (tc (vr "zero")) (tc (lit "0"))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (vr "z"))
             (t4
               [:comparison
                (e (vr "z"))
                [:comp_op ["EQUALS" "=="]]
                (e (lit "0"))])
             (t4
               [:comparison
                (e (vr "z"))
                [:comp_op ["IS" "is"]]
                (e (vr "zero"))])
             (tx (un "-" (e (vr "z"))))
             (tx (op (e (vr "z")) "+" (e (lit "1"))))))))
     (st
       (asg
         (tc (vr "d"))
         (tc
           [:atom_expr
            [:atom
             ["OPEN_BRACE" "{"]
             [:dictorsetmaker
              (tc (lit "0"))
              ["COLON" ":"]
              (tc (at ["STRING" "'zero'"]))
              ["COMMA" ","]
              (tc (lit "1"))
              ["COLON" ":"]
              (tc (at ["STRING" "'one'"]))]
             ["CLOSE_BRACE" "}"]]])))
     (st
       (ex
         (tc
           (call
             "print"
             (tc
               [:atom_expr
                [:atom (nm "d")]
                [:trailer
                 ["OPEN_BRACK" "["]
                 [:subscriptlist [:subscript_ (tc (vr "z"))]]
                 ["CLOSE_BRACK" "]"]]])
             (tc
               [:atom_expr
                [:atom (nm "d")]
                [:trailer
                 ["OPEN_BRACK" "["]
                 [:subscriptlist
                  [:subscript_
                   (tx
                     (op
                       (e (par (tx (op (e (vr "big")) "+" (e (lit "1"))))))
                       "-"
                       (e (vr "big"))))]]
                 ["CLOSE_BRACK" "]"]]])
             (tc
               [:atom_expr
                [:atom (nm "d")]
                [:trailer
                 ["OPEN_BRACK" "["]
                 [:subscriptlist
                  [:subscript_ (tx (op (e (vr "big")) "//" (e (vr "big"))))]]
                 ["CLOSE_BRACK" "]"]]])))))
     (st
       (asg
         (tc
           [:atom_expr
            [:atom (nm "d")]
            [:trailer
             ["OPEN_BRACK" "["]
             [:subscriptlist
              [:subscript_
               (tx
                 (op
                   (op (e (vr "big")) "-" (e (lit "1")))
                   "-"
                   (e (par (tx (op (e (vr "big")) "-" (e (lit "2"))))))))]]
             ["CLOSE_BRACK" "]"]]])
         (tc (at ["STRING" "'uno'"]))))
     (st (ex (tc (call "print" (tc (vr "d"))))))
     (st
       (asg
         (tc (vr "s"))
         (tc
           [:atom_expr
            [:atom
             ["OPEN_BRACE" "{"]
             [:dictorsetmaker
              (tc (vr "z"))
              ["COMMA" ","]
              (tc (lit "0"))
              ["COMMA" ","]
              (tc (lit "0.0"))
              ["COMMA" ","]
              (tx (op (e (vr "big")) "-" (e (vr "big"))))]
             ["CLOSE_BRACE" "}"]]])))
     (st
       (ex (tc (call "print" (tc (call "len" (tc (vr "s")))) (tc (vr "s"))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "hash" (tc (vr "z"))))
             (tc (call "hash" (tc (vr "big"))))
             (tc
               (call
                 "hash"
                 (tx
                   (op
                     (op
                       (op (e (vr "big")) "-" (e (vr "big")))
                       "+"
                       (op (e (lit "2")) "**" (e (lit "61"))))
                     "-"
                     (e (lit "1"))))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (t4
               [:comparison
                (e (vr "z"))
                [:comp_op ["IN" "in"]]
                (e
                  [:atom_expr
                   [:atom
                    ["OPEN_BRACK" "["]
                    [:testlist_comp (tc (lit "0"))]
                    ["CLOSE_BRACK" "]"]]])])
             (t4
               [:comparison
                (e
                  [:atom_expr
                   [:atom
                    ["OPEN_BRACK" "["]
                    [:testlist_comp (tc (vr "z"))]
                    ["CLOSE_BRACK" "]"]]])
                [:comp_op ["EQUALS" "=="]]
                (e
                  [:atom_expr
                   [:atom
                    ["OPEN_BRACK" "["]
                    [:testlist_comp (tc (lit "0"))]
                    ["CLOSE_BRACK" "]"]]])])
             (t4
               [:comparison
                (e
                  [:atom_expr
                   [:atom
                    ["OPEN_PAREN" "("]
                    [:testlist_comp (tc (vr "z")) ["COMMA" ","]]
                    ["CLOSE_PAREN" ")"]]])
                [:comp_op ["EQUALS" "=="]]
                (e
                  [:atom_expr
                   [:atom
                    ["OPEN_PAREN" "("]
                    [:testlist_comp (tc (lit "0")) ["COMMA" ","]]
                    ["CLOSE_PAREN" ")"]]])])
             (tc
               [:atom_expr
                [:atom
                 ["OPEN_BRACE" "{"]
                 [:dictorsetmaker (tc (vr "z")) ["COLON" ":"] (tc (lit "1"))]
                 ["CLOSE_BRACE" "}"]]
                [:trailer
                 ["OPEN_BRACK" "["]
                 [:subscriptlist [:subscript_ (tc (lit "0"))]]
                 ["CLOSE_BRACK" "]"]]])))))
     (st (asg (tc (vr "t")) (tx (op (e (vr "big")) "*" (e (lit "3"))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (op (e (vr "t")) "//" (e (lit "3"))) "-" (e (vr "big"))))
             (tx (op (e (vr "t")) "%" (e (lit "3"))))
             (tx
               (op
                 (e (par (tx (op (e (vr "t")) "//" (e (vr "big"))))))
                 "*"
                 (e (lit "7"))))))))
     ["EOF" "<EOF>"]]))


(def numeric-keys
  "d = {}
d[1] = 'a'
d[1.0] = 'b'
d[True] = 'c'
print(d)
d[2 ** 64] = 'e'
d[18446744073709551616.0] = 'f'
print(d)
print(hash(-1), hash(-2), hash(-1.0), hash(2 ** 61 - 1), hash(2 ** 61))
print(hash(-(2 ** 61)), hash(2 ** 64), hash(-(2 ** 64)))
print(hash(0.5), hash(1.5), hash(-0.5), hash(1e100))
k = {0.5: 'half', 2 ** 53 + 1: 'odd', 9007199254740992.0: 'even'}
print(k[1 / 2], 2 ** 53 + 1 in k, 2 ** 53 in k, k[2 ** 53])
print(k)
e = {2 ** 53: 'a', 2 ** 53 + 1: 'b', 2 ** 64: 'c', 2 ** 64 + 1: 'd'}
print(len(e), e[2 ** 64 + 1], 18446744073709551617 in e)
s = {-1, -2, -1.0}
print(len(s), -1.0 in s, -2.0 in s, -3 in s)
"
  (packet
    [:file_input
     (st
       (asg
         (tc (vr "d"))
         (tc [:atom_expr [:atom ["OPEN_BRACE" "{"] ["CLOSE_BRACE" "}"]]])))
     (st
       (asg
         (tc
           [:atom_expr
            [:atom (nm "d")]
            [:trailer
             ["OPEN_BRACK" "["]
             [:subscriptlist [:subscript_ (tc (lit "1"))]]
             ["CLOSE_BRACK" "]"]]])
         (tc (at ["STRING" "'a'"]))))
     (st
       (asg
         (tc
           [:atom_expr
            [:atom (nm "d")]
            [:trailer
             ["OPEN_BRACK" "["]
             [:subscriptlist [:subscript_ (tc (lit "1.0"))]]
             ["CLOSE_BRACK" "]"]]])
         (tc (at ["STRING" "'b'"]))))
     (st
       (asg
         (tc
           [:atom_expr
            [:atom (nm "d")]
            [:trailer
             ["OPEN_BRACK" "["]
             [:subscriptlist [:subscript_ (tc (at ["TRUE" "True"]))]]
             ["CLOSE_BRACK" "]"]]])
         (tc (at ["STRING" "'c'"]))))
     (st (ex (tc (call "print" (tc (vr "d"))))))
     (st
       (asg
         (tc
           [:atom_expr
            [:atom (nm "d")]
            [:trailer
             ["OPEN_BRACK" "["]
             [:subscriptlist
              [:subscript_ (tx (op (e (lit "2")) "**" (e (lit "64"))))]]
             ["CLOSE_BRACK" "]"]]])
         (tc (at ["STRING" "'e'"]))))
     (st
       (asg
         (tc
           [:atom_expr
            [:atom (nm "d")]
            [:trailer
             ["OPEN_BRACK" "["]
             [:subscriptlist [:subscript_ (tc (lit "18446744073709551616.0"))]]
             ["CLOSE_BRACK" "]"]]])
         (tc (at ["STRING" "'f'"]))))
     (st (ex (tc (call "print" (tc (vr "d"))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "hash" (tx (un "-" (e (lit "1"))))))
             (tc (call "hash" (tx (un "-" (e (lit "2"))))))
             (tc (call "hash" (tx (un "-" (e (lit "1.0"))))))
             (tc
               (call
                 "hash"
                 (tx
                   (op
                     (op (e (lit "2")) "**" (e (lit "61")))
                     "-"
                     (e (lit "1"))))))
             (tc (call "hash" (tx (op (e (lit "2")) "**" (e (lit "61"))))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc
               (call
                 "hash"
                 (tx
                   (un
                     "-"
                     (e (par (tx (op (e (lit "2")) "**" (e (lit "61"))))))))))
             (tc (call "hash" (tx (op (e (lit "2")) "**" (e (lit "64"))))))
             (tc
               (call
                 "hash"
                 (tx
                   (un
                     "-"
                     (e
                       (par
                         (tx (op (e (lit "2")) "**" (e (lit "64"))))))))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "hash" (tc (lit "0.5"))))
             (tc (call "hash" (tc (lit "1.5"))))
             (tc (call "hash" (tx (un "-" (e (lit "0.5"))))))
             (tc (call "hash" (tc (lit "1e100"))))))))
     (st
       (asg
         (tc (vr "k"))
         (tc
           [:atom_expr
            [:atom
             ["OPEN_BRACE" "{"]
             [:dictorsetmaker
              (tc (lit "0.5"))
              ["COLON" ":"]
              (tc (at ["STRING" "'half'"]))
              ["COMMA" ","]
              (tx
                (op (op (e (lit "2")) "**" (e (lit "53"))) "+" (e (lit "1"))))
              ["COLON" ":"]
              (tc (at ["STRING" "'odd'"]))
              ["COMMA" ","]
              (tc (lit "9007199254740992.0"))
              ["COLON" ":"]
              (tc (at ["STRING" "'even'"]))]
             ["CLOSE_BRACE" "}"]]])))
     (st
       (ex
         (tc
           (call
             "print"
             (tc
               [:atom_expr
                [:atom (nm "k")]
                [:trailer
                 ["OPEN_BRACK" "["]
                 [:subscriptlist
                  [:subscript_ (tx (op (e (lit "1")) "/" (e (lit "2"))))]]
                 ["CLOSE_BRACK" "]"]]])
             (t4
               [:comparison
                (op (op (e (lit "2")) "**" (e (lit "53"))) "+" (e (lit "1")))
                [:comp_op ["IN" "in"]]
                (e (vr "k"))])
             (t4
               [:comparison
                (op (e (lit "2")) "**" (e (lit "53")))
                [:comp_op ["IN" "in"]]
                (e (vr "k"))])
             (tc
               [:atom_expr
                [:atom (nm "k")]
                [:trailer
                 ["OPEN_BRACK" "["]
                 [:subscriptlist
                  [:subscript_ (tx (op (e (lit "2")) "**" (e (lit "53"))))]]
                 ["CLOSE_BRACK" "]"]]])))))
     (st (ex (tc (call "print" (tc (vr "k"))))))
     (st
       (asg
         (tc (vr "e"))
         (tc
           [:atom_expr
            [:atom
             ["OPEN_BRACE" "{"]
             [:dictorsetmaker
              (tx (op (e (lit "2")) "**" (e (lit "53"))))
              ["COLON" ":"]
              (tc (at ["STRING" "'a'"]))
              ["COMMA" ","]
              (tx
                (op (op (e (lit "2")) "**" (e (lit "53"))) "+" (e (lit "1"))))
              ["COLON" ":"]
              (tc (at ["STRING" "'b'"]))
              ["COMMA" ","]
              (tx (op (e (lit "2")) "**" (e (lit "64"))))
              ["COLON" ":"]
              (tc (at ["STRING" "'c'"]))
              ["COMMA" ","]
              (tx
                (op (op (e (lit "2")) "**" (e (lit "64"))) "+" (e (lit "1"))))
              ["COLON" ":"]
              (tc (at ["STRING" "'d'"]))]
             ["CLOSE_BRACE" "}"]]])))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "len" (tc (vr "e"))))
             (tc
               [:atom_expr
                [:atom (nm "e")]
                [:trailer
                 ["OPEN_BRACK" "["]
                 [:subscriptlist
                  [:subscript_
                   (tx
                     (op
                       (op (e (lit "2")) "**" (e (lit "64")))
                       "+"
                       (e (lit "1"))))]]
                 ["CLOSE_BRACK" "]"]]])
             (t4
               [:comparison
                (e (lit "18446744073709551617"))
                [:comp_op ["IN" "in"]]
                (e (vr "e"))])))))
     (st
       (asg
         (tc (vr "s"))
         (tc
           [:atom_expr
            [:atom
             ["OPEN_BRACE" "{"]
             [:dictorsetmaker
              (tx (un "-" (e (lit "1"))))
              ["COMMA" ","]
              (tx (un "-" (e (lit "2"))))
              ["COMMA" ","]
              (tx (un "-" (e (lit "1.0"))))]
             ["CLOSE_BRACE" "}"]]])))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "len" (tc (vr "s"))))
             (t4
               [:comparison
                (un "-" (e (lit "1.0")))
                [:comp_op ["IN" "in"]]
                (e (vr "s"))])
             (t4
               [:comparison
                (un "-" (e (lit "2.0")))
                [:comp_op ["IN" "in"]]
                (e (vr "s"))])
             (t4
               [:comparison
                (un "-" (e (lit "3")))
                [:comp_op ["IN" "in"]]
                (e (vr "s"))])))))
     ["EOF" "<EOF>"]]))


(def floor-division
  "for a in (7, -7):
    for b in (2, -2):
        print(a // b, a % b, divmod(a, b))
big = 2 ** 64
print(divmod(big + 1, 3), divmod(-big - 1, 3))
print(divmod(big, -7), divmod(-big, -7))
print(-2 ** 64 // 10 ** 10, (-2) ** 63 % 10 ** 9)
print(7.5 // 2, -7.5 // 2, 7.5 % -2, divmod(-7.5, 2))
print(-big // 3, -big % 3, big // -3, big % -3)
print((big + 1) // (big + 2), -(big + 1) // (big + 2), -1 // big)
"
  (packet
    [:file_input
     [:stmt
      [:compound_stmt
       [:for_stmt
        ["FOR" "for"]
        [:exprlist (e (vr "a"))]
        ["IN" "in"]
        [:testlist
         (tc
           [:atom_expr
            [:atom
             ["OPEN_PAREN" "("]
             [:testlist_comp
              (tc (lit "7"))
              ["COMMA" ","]
              (tx (un "-" (e (lit "7"))))]
             ["CLOSE_PAREN" ")"]]])]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         [:stmt
          [:compound_stmt
           [:for_stmt
            ["FOR" "for"]
            [:exprlist (e (vr "b"))]
            ["IN" "in"]
            [:testlist
             (tc
               [:atom_expr
                [:atom
                 ["OPEN_PAREN" "("]
                 [:testlist_comp
                  (tc (lit "2"))
                  ["COMMA" ","]
                  (tx (un "-" (e (lit "2"))))]
                 ["CLOSE_PAREN" ")"]]])]
            ["COLON" ":"]
            [:block
             ["NEWLINE" "\n"]
             ["INDENT" "        "]
             (st
               (ex
                 (tc
                   (call
                     "print"
                     (tx (op (e (vr "a")) "//" (e (vr "b"))))
                     (tx (op (e (vr "a")) "%" (e (vr "b"))))
                     (tc (call "divmod" (tc (vr "a")) (tc (vr "b"))))))))
             ["DEDENT" ""]]]]]
         ["DEDENT" ""]]]]]
     (st (asg (tc (vr "big")) (tx (op (e (lit "2")) "**" (e (lit "64"))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc
               (call
                 "divmod"
                 (tx (op (e (vr "big")) "+" (e (lit "1"))))
                 (tc (lit "3"))))
             (tc
               (call
                 "divmod"
                 (tx (op (un "-" (e (vr "big"))) "-" (e (lit "1"))))
                 (tc (lit "3"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "divmod" (tc (vr "big")) (tx (un "-" (e (lit "7"))))))
             (tc
               (call
                 "divmod"
                 (tx (un "-" (e (vr "big"))))
                 (tx (un "-" (e (lit "7"))))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx
               (op
                 (un "-" (op (e (lit "2")) "**" (e (lit "64"))))
                 "//"
                 (op (e (lit "10")) "**" (e (lit "10")))))
             (tx
               (op
                 (op (e (par (tx (un "-" (e (lit "2")))))) "**" (e (lit "63")))
                 "%"
                 (op (e (lit "10")) "**" (e (lit "9")))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "7.5")) "//" (e (lit "2"))))
             (tx (op (un "-" (e (lit "7.5"))) "//" (e (lit "2"))))
             (tx (op (e (lit "7.5")) "%" (un "-" (e (lit "2")))))
             (tc
               (call
                 "divmod"
                 (tx (un "-" (e (lit "7.5"))))
                 (tc (lit "2"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (un "-" (e (vr "big"))) "//" (e (lit "3"))))
             (tx (op (un "-" (e (vr "big"))) "%" (e (lit "3"))))
             (tx (op (e (vr "big")) "//" (un "-" (e (lit "3")))))
             (tx (op (e (vr "big")) "%" (un "-" (e (lit "3")))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx
               (op
                 (e (par (tx (op (e (vr "big")) "+" (e (lit "1"))))))
                 "//"
                 (e (par (tx (op (e (vr "big")) "+" (e (lit "2"))))))))
             (tx
               (op
                 (un "-" (e (par (tx (op (e (vr "big")) "+" (e (lit "1")))))))
                 "//"
                 (e (par (tx (op (e (vr "big")) "+" (e (lit "2"))))))))
             (tx (op (un "-" (e (lit "1"))) "//" (e (vr "big"))))))))
     ["EOF" "<EOF>"]]))


(def shifts
  "print(1 >> 2 ** 70, -1 >> 2 ** 70, 2 ** 100 >> 2 ** 70)
print(-(2 ** 100) >> 2 ** 70, 3 << 0, 0 >> 2 ** 70)
print(1 << 64, -1 << 64, 2 ** 64 >> 1, -(2 ** 64) >> 63, 5 >> 1, -5 >> 1)
try:
    print(1 << -1)
except ValueError as e:
    print('ValueError', e.args)
try:
    print(1 >> -(2 ** 70))
except ValueError as e:
    print('ValueError', e.args)
print(1 << 100 >> 99, (2 ** 64 + 1) >> 64, -(2 ** 64 + 1) >> 64)
print(~(2 ** 64), ~-(2 ** 64), 2 ** 64 & -1, 2 ** 64 | 1, 2 ** 64 ^ 2 ** 64)
print(-(2 ** 64) >> 2 ** 64, 2 ** 64 >> 2 ** 64, 2 ** 64 >> 64)
"
  (packet
    [:file_input
     (st
       (ex
         (tc
           (call
             "print"
             (tx
               (op (e (lit "1")) ">>" (op (e (lit "2")) "**" (e (lit "70")))))
             (tx
               (op
                 (un "-" (e (lit "1")))
                 ">>"
                 (op (e (lit "2")) "**" (e (lit "70")))))
             (tx
               (op
                 (op (e (lit "2")) "**" (e (lit "100")))
                 ">>"
                 (op (e (lit "2")) "**" (e (lit "70")))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx
               (op
                 (un
                   "-"
                   (e (par (tx (op (e (lit "2")) "**" (e (lit "100")))))))
                 ">>"
                 (op (e (lit "2")) "**" (e (lit "70")))))
             (tx (op (e (lit "3")) "<<" (e (lit "0"))))
             (tx
               (op
                 (e (lit "0"))
                 ">>"
                 (op (e (lit "2")) "**" (e (lit "70")))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "1")) "<<" (e (lit "64"))))
             (tx (op (un "-" (e (lit "1"))) "<<" (e (lit "64"))))
             (tx
               (op (op (e (lit "2")) "**" (e (lit "64"))) ">>" (e (lit "1"))))
             (tx
               (op
                 (un "-" (e (par (tx (op (e (lit "2")) "**" (e (lit "64")))))))
                 ">>"
                 (e (lit "63"))))
             (tx (op (e (lit "5")) ">>" (e (lit "1"))))
             (tx (op (un "-" (e (lit "5"))) ">>" (e (lit "1"))))))))
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tx (op (e (lit "1")) "<<" (un "-" (e (lit "1")))))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "ValueError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'ValueError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tx
                   (op
                     (e (lit "1"))
                     ">>"
                     (un
                       "-"
                       (e
                         (par
                           (tx (op (e (lit "2")) "**" (e (lit "70")))))))))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "ValueError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'ValueError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     (st
       (ex
         (tc
           (call
             "print"
             (tx
               (op
                 (op (e (lit "1")) "<<" (e (lit "100")))
                 ">>"
                 (e (lit "99"))))
             (tx
               (op
                 (e
                   (par
                     (tx
                       (op
                         (op (e (lit "2")) "**" (e (lit "64")))
                         "+"
                         (e (lit "1"))))))
                 ">>"
                 (e (lit "64"))))
             (tx
               (op
                 (un
                   "-"
                   (e
                     (par
                       (tx
                         (op
                           (op (e (lit "2")) "**" (e (lit "64")))
                           "+"
                           (e (lit "1")))))))
                 ">>"
                 (e (lit "64"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx
               (un "~" (e (par (tx (op (e (lit "2")) "**" (e (lit "64"))))))))
             (tx
               [:expr
                ["NOT_OP" "~"]
                ["MINUS" "-"]
                (e (par (tx (op (e (lit "2")) "**" (e (lit "64"))))))])
             (tx
               (op
                 (op (e (lit "2")) "**" (e (lit "64")))
                 "&"
                 (un "-" (e (lit "1")))))
             (tx (op (op (e (lit "2")) "**" (e (lit "64"))) "|" (e (lit "1"))))
             (tx
               (op
                 (op (e (lit "2")) "**" (e (lit "64")))
                 "^"
                 (op (e (lit "2")) "**" (e (lit "64")))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx
               (op
                 (un "-" (e (par (tx (op (e (lit "2")) "**" (e (lit "64")))))))
                 ">>"
                 (op (e (lit "2")) "**" (e (lit "64")))))
             (tx
               (op
                 (op (e (lit "2")) "**" (e (lit "64")))
                 ">>"
                 (op (e (lit "2")) "**" (e (lit "64")))))
             (tx
               (op
                 (op (e (lit "2")) "**" (e (lit "64")))
                 ">>"
                 (e (lit "64"))))))))
     ["EOF" "<EOF>"]]))


(def power
  "print(2 ** 64, (-2) ** 63, 3 ** 40, (-3) ** 41)
print(2 ** -1, 2 ** -2, (-2) ** -3, 10 ** -2)
print(0 ** 0, 0.0 ** 0, 1 ** 2 ** 70, (-1) ** (2 ** 70 + 1), (-1) ** 2 ** 70)
print(2.0 ** 64, 2.5 ** 2, (-8) ** 2, 2 ** 2 ** 3, (-2.0) ** 3)
print(pow(2, 10), pow(2, 100), pow(-2, -1), pow(2.5, 2))
try:
    print(0 ** -1)
except ZeroDivisionError as e:
    print('ZeroDivisionError', e.args)
try:
    print(2.0 ** 1024)
except OverflowError as e:
    print('OverflowError', e.args)
try:
    print(2 ** -(2 ** 70))
except OverflowError as e:
    print('OverflowError', e.args)
print(2 ** -1074, 2 ** -1075, (2 ** 64) ** 2 == 2 ** 128)
print(1.0 ** (2 ** 70), (-1.0) ** (2 ** 64 + 1), 0.5 ** 1074)
"
  (packet
    [:file_input
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "2")) "**" (e (lit "64"))))
             (tx
               (op (e (par (tx (un "-" (e (lit "2")))))) "**" (e (lit "63"))))
             (tx (op (e (lit "3")) "**" (e (lit "40"))))
             (tx
               (op
                 (e (par (tx (un "-" (e (lit "3"))))))
                 "**"
                 (e (lit "41"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "2")) "**" (un "-" (e (lit "1")))))
             (tx (op (e (lit "2")) "**" (un "-" (e (lit "2")))))
             (tx
               (op
                 (e (par (tx (un "-" (e (lit "2"))))))
                 "**"
                 (un "-" (e (lit "3")))))
             (tx (op (e (lit "10")) "**" (un "-" (e (lit "2")))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "0")) "**" (e (lit "0"))))
             (tx (op (e (lit "0.0")) "**" (e (lit "0"))))
             (tx
               (op (op (e (lit "1")) "**" (e (lit "2"))) "**" (e (lit "70"))))
             (tx
               (op
                 (e (par (tx (un "-" (e (lit "1"))))))
                 "**"
                 (e
                   (par
                     (tx
                       (op
                         (op (e (lit "2")) "**" (e (lit "70")))
                         "+"
                         (e (lit "1"))))))))
             (tx
               (op
                 (op (e (par (tx (un "-" (e (lit "1")))))) "**" (e (lit "2")))
                 "**"
                 (e (lit "70"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "2.0")) "**" (e (lit "64"))))
             (tx (op (e (lit "2.5")) "**" (e (lit "2"))))
             (tx (op (e (par (tx (un "-" (e (lit "8")))))) "**" (e (lit "2"))))
             (tx (op (op (e (lit "2")) "**" (e (lit "2"))) "**" (e (lit "3"))))
             (tx
               (op
                 (e (par (tx (un "-" (e (lit "2.0"))))))
                 "**"
                 (e (lit "3"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "pow" (tc (lit "2")) (tc (lit "10"))))
             (tc (call "pow" (tc (lit "2")) (tc (lit "100"))))
             (tc
               (call
                 "pow"
                 (tx (un "-" (e (lit "2"))))
                 (tx (un "-" (e (lit "1"))))))
             (tc (call "pow" (tc (lit "2.5")) (tc (lit "2"))))))))
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tx (op (e (lit "0")) "**" (un "-" (e (lit "1")))))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "ZeroDivisionError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'ZeroDivisionError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tx (op (e (lit "2.0")) "**" (e (lit "1024"))))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "OverflowError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'OverflowError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tx
                   (op
                     (e (lit "2"))
                     "**"
                     (un
                       "-"
                       (e
                         (par
                           (tx (op (e (lit "2")) "**" (e (lit "70")))))))))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "OverflowError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'OverflowError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "2")) "**" (un "-" (e (lit "1074")))))
             (tx (op (e (lit "2")) "**" (un "-" (e (lit "1075")))))
             (t4
               [:comparison
                (op
                  (e (par (tx (op (e (lit "2")) "**" (e (lit "64"))))))
                  "**"
                  (e (lit "2")))
                [:comp_op ["EQUALS" "=="]]
                (op (e (lit "2")) "**" (e (lit "128")))])))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx
               (op
                 (e (lit "1.0"))
                 "**"
                 (e (par (tx (op (e (lit "2")) "**" (e (lit "70"))))))))
             (tx
               (op
                 (e (par (tx (un "-" (e (lit "1.0"))))))
                 "**"
                 (e
                   (par
                     (tx
                       (op
                         (op (e (lit "2")) "**" (e (lit "64")))
                         "+"
                         (e (lit "1"))))))))
             (tx (op (e (lit "0.5")) "**" (e (lit "1074"))))))))
     ["EOF" "<EOF>"]]))


(def conversions
  "print(int('  -0x_1f ', 0), int('101', 2), int(-2.9), int(True))
print(int(2 ** 64 + 0.0), int('9' * 30), int('-' + '7' * 20, 8))
print(float('1_0.5e1'), float(' -inf '), float(2 ** 64), float('1e-400'))
print(float(7), float(2 ** 53 + 1), float('-1.5e3'))
print(str(2 ** 64), repr(-2 ** 64), str(1e16), repr(1e-05), str(0.1))
print(repr(1 / 3), str(2.0 ** 70), repr(123456789.0), str(-1e-07))
print(hex(2 ** 64), oct(-8), bin(5), hex(-(2 ** 70)), bin(0), oct(2 ** 64))
print(round(2.5), round(3.5), round(-2.5), round(0.5), round(-0.4))
print(round(1234, -2), round(1250, -2), round(1350, -2), round(-1250, -2))
print(round(2 ** 64 + 2 ** 63, -19), round(2 ** 64, 5), round(1e20))
print(abs(-2 ** 64), abs(-0.0), abs(-2.5), abs(True))
print(bool(0), bool(2 ** 64), bool(0.0), bool(''), bool('0'))
try:
    int('0x1g', 16)
except ValueError as e:
    print(e.args)
try:
    int(1e400)
except OverflowError as e:
    print(e.args)
try:
    int(float('nan'))
except ValueError as e:
    print(e.args)
try:
    float('1e')
except ValueError as e:
    print(e.args)
try:
    round(1.5, 'x')
except TypeError as e:
    print(e.args)
try:
    round('a', 1.5)
except TypeError as e:
    print(e.args)
print(pow(base=2, exp=10), round(number=1250, ndigits=-2))
"
  (packet
    [:file_input
     (st
       (ex
         (tc
           (call
             "print"
             (tc
               (call "int" (tc (at ["STRING" "'  -0x_1f '"])) (tc (lit "0"))))
             (tc (call "int" (tc (at ["STRING" "'101'"])) (tc (lit "2"))))
             (tc (call "int" (tx (un "-" (e (lit "2.9"))))))
             (tc (call "int" (tc (at ["TRUE" "True"]))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc
               (call
                 "int"
                 (tx
                   (op
                     (op (e (lit "2")) "**" (e (lit "64")))
                     "+"
                     (e (lit "0.0"))))))
             (tc
               (call
                 "int"
                 (tx (op (e (at ["STRING" "'9'"])) "*" (e (lit "30"))))))
             (tc
               (call
                 "int"
                 (tx
                   (op
                     (e (at ["STRING" "'-'"]))
                     "+"
                     (op (e (at ["STRING" "'7'"])) "*" (e (lit "20")))))
                 (tc (lit "8"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "float" (tc (at ["STRING" "'1_0.5e1'"]))))
             (tc (call "float" (tc (at ["STRING" "' -inf '"]))))
             (tc (call "float" (tx (op (e (lit "2")) "**" (e (lit "64"))))))
             (tc (call "float" (tc (at ["STRING" "'1e-400'"]))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "float" (tc (lit "7"))))
             (tc
               (call
                 "float"
                 (tx
                   (op
                     (op (e (lit "2")) "**" (e (lit "53")))
                     "+"
                     (e (lit "1"))))))
             (tc (call "float" (tc (at ["STRING" "'-1.5e3'"]))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "str" (tx (op (e (lit "2")) "**" (e (lit "64"))))))
             (tc
               (call
                 "repr"
                 (tx (un "-" (op (e (lit "2")) "**" (e (lit "64")))))))
             (tc (call "str" (tc (lit "1e16"))))
             (tc (call "repr" (tc (lit "1e-05"))))
             (tc (call "str" (tc (lit "0.1"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "repr" (tx (op (e (lit "1")) "/" (e (lit "3"))))))
             (tc (call "str" (tx (op (e (lit "2.0")) "**" (e (lit "70"))))))
             (tc (call "repr" (tc (lit "123456789.0"))))
             (tc (call "str" (tx (un "-" (e (lit "1e-07"))))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "hex" (tx (op (e (lit "2")) "**" (e (lit "64"))))))
             (tc (call "oct" (tx (un "-" (e (lit "8"))))))
             (tc (call "bin" (tc (lit "5"))))
             (tc
               (call
                 "hex"
                 (tx
                   (un
                     "-"
                     (e (par (tx (op (e (lit "2")) "**" (e (lit "70"))))))))))
             (tc (call "bin" (tc (lit "0"))))
             (tc (call "oct" (tx (op (e (lit "2")) "**" (e (lit "64"))))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "round" (tc (lit "2.5"))))
             (tc (call "round" (tc (lit "3.5"))))
             (tc (call "round" (tx (un "-" (e (lit "2.5"))))))
             (tc (call "round" (tc (lit "0.5"))))
             (tc (call "round" (tx (un "-" (e (lit "0.4"))))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "round" (tc (lit "1234")) (tx (un "-" (e (lit "2"))))))
             (tc (call "round" (tc (lit "1250")) (tx (un "-" (e (lit "2"))))))
             (tc (call "round" (tc (lit "1350")) (tx (un "-" (e (lit "2"))))))
             (tc
               (call
                 "round"
                 (tx (un "-" (e (lit "1250"))))
                 (tx (un "-" (e (lit "2"))))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc
               (call
                 "round"
                 (tx
                   (op
                     (op (e (lit "2")) "**" (e (lit "64")))
                     "+"
                     (op (e (lit "2")) "**" (e (lit "63")))))
                 (tx (un "-" (e (lit "19"))))))
             (tc
               (call
                 "round"
                 (tx (op (e (lit "2")) "**" (e (lit "64"))))
                 (tc (lit "5"))))
             (tc (call "round" (tc (lit "1e20"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc
               (call
                 "abs"
                 (tx (un "-" (op (e (lit "2")) "**" (e (lit "64")))))))
             (tc (call "abs" (tx (un "-" (e (lit "0.0"))))))
             (tc (call "abs" (tx (un "-" (e (lit "2.5"))))))
             (tc (call "abs" (tc (at ["TRUE" "True"]))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "bool" (tc (lit "0"))))
             (tc (call "bool" (tx (op (e (lit "2")) "**" (e (lit "64"))))))
             (tc (call "bool" (tc (lit "0.0"))))
             (tc (call "bool" (tc (at ["STRING" "''"]))))
             (tc (call "bool" (tc (at ["STRING" "'0'"]))))))))
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc (call "int" (tc (at ["STRING" "'0x1g'"])) (tc (lit "16"))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "ValueError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st (ex (tc (call "int" (tc (lit "1e400"))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "OverflowError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call "int" (tc (call "float" (tc (at ["STRING" "'nan'"]))))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "ValueError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st (ex (tc (call "float" (tc (at ["STRING" "'1e'"]))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "ValueError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc (call "round" (tc (lit "1.5")) (tc (at ["STRING" "'x'"]))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "TypeError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc (call "round" (tc (at ["STRING" "'a'"])) (tc (lit "1.5"))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "TypeError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     (st
       (ex
         (tc
           (call
             "print"
             (tc
               [:atom_expr
                [:atom (nm "pow")]
                [:trailer
                 ["OPEN_PAREN" "("]
                 [:arglist
                  [:argument (tc (vr "base")) ["ASSIGN" "="] (tc (lit "2"))]
                  ["COMMA" ","]
                  [:argument (tc (vr "exp")) ["ASSIGN" "="] (tc (lit "10"))]]
                 ["CLOSE_PAREN" ")"]]])
             (tc
               [:atom_expr
                [:atom (nm "round")]
                [:trailer
                 ["OPEN_PAREN" "("]
                 [:arglist
                  [:argument
                   (tc (vr "number"))
                   ["ASSIGN" "="]
                   (tc (lit "1250"))]
                  ["COMMA" ","]
                  [:argument
                   (tc (vr "ndigits"))
                   ["ASSIGN" "="]
                   (tx (un "-" (e (lit "2"))))]]
                 ["CLOSE_PAREN" ")"]]])))))
     ["EOF" "<EOF>"]]))


(def signed-zero
  "print(4.0 % -2.0, -4.0 % 2.0, 4.0 // -2.0, -4.0 // 2.0, 4.0 % 2.0)
print(-4.0 % -2.0, 6.0 // 3.0, -6.0 // -3.0)
print(4 % -2.0, 4.0 % -2, 0.0 % 5.0, 0.0 % -5.0, -0.0 % 5.0)
print(0.0 // 5.0, 0.0 // -5.0, -0.0 // 5.0)
inf = 1e400
print(5.0 % inf, -5.0 % inf, 5.0 % -inf, -5.0 % -inf, 0.0 % inf, 0.0 % -inf)
print(5.0 // inf, -5.0 // inf, 5.0 // -inf, -5.0 // -inf, 0.0 // inf)
print(0.0 // -inf)
print(-0.0, +(-0.0), -(0.0), -(-0.0), 0.0 * -1, -1e400, 1e400)
z = -0.0
print(z == 0.0, z == 0, hash(z), {0.0: 'a'}[z], str(z), repr(z), abs(z))
print(-0.0 + 0.0, -0.0 - 0.0, z * 0, 0 * -1.0, (-0.0) ** 3, (-0.0) ** 2)
print(int(z), round(z), float('-0'), -0.0 * 2 ** 64, z / 1, -(2 ** 64) * 0.0)
print(divmod(-0.0, 1.0), divmod(0.0, -1.0), z // 1, 0.0 % -1)
"
  (packet
    [:file_input
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "4.0")) "%" (un "-" (e (lit "2.0")))))
             (tx (op (un "-" (e (lit "4.0"))) "%" (e (lit "2.0"))))
             (tx (op (e (lit "4.0")) "//" (un "-" (e (lit "2.0")))))
             (tx (op (un "-" (e (lit "4.0"))) "//" (e (lit "2.0"))))
             (tx (op (e (lit "4.0")) "%" (e (lit "2.0"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (un "-" (e (lit "4.0"))) "%" (un "-" (e (lit "2.0")))))
             (tx (op (e (lit "6.0")) "//" (e (lit "3.0"))))
             (tx
               (op (un "-" (e (lit "6.0"))) "//" (un "-" (e (lit "3.0")))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "4")) "%" (un "-" (e (lit "2.0")))))
             (tx (op (e (lit "4.0")) "%" (un "-" (e (lit "2")))))
             (tx (op (e (lit "0.0")) "%" (e (lit "5.0"))))
             (tx (op (e (lit "0.0")) "%" (un "-" (e (lit "5.0")))))
             (tx (op (un "-" (e (lit "0.0"))) "%" (e (lit "5.0"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "0.0")) "//" (e (lit "5.0"))))
             (tx (op (e (lit "0.0")) "//" (un "-" (e (lit "5.0")))))
             (tx (op (un "-" (e (lit "0.0"))) "//" (e (lit "5.0"))))))))
     (st (asg (tc (vr "inf")) (tc (lit "1e400"))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "5.0")) "%" (e (vr "inf"))))
             (tx (op (un "-" (e (lit "5.0"))) "%" (e (vr "inf"))))
             (tx (op (e (lit "5.0")) "%" (un "-" (e (vr "inf")))))
             (tx (op (un "-" (e (lit "5.0"))) "%" (un "-" (e (vr "inf")))))
             (tx (op (e (lit "0.0")) "%" (e (vr "inf"))))
             (tx (op (e (lit "0.0")) "%" (un "-" (e (vr "inf")))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "5.0")) "//" (e (vr "inf"))))
             (tx (op (un "-" (e (lit "5.0"))) "//" (e (vr "inf"))))
             (tx (op (e (lit "5.0")) "//" (un "-" (e (vr "inf")))))
             (tx (op (un "-" (e (lit "5.0"))) "//" (un "-" (e (vr "inf")))))
             (tx (op (e (lit "0.0")) "//" (e (vr "inf"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (lit "0.0")) "//" (un "-" (e (vr "inf")))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (un "-" (e (lit "0.0"))))
             (tx (un "+" (e (par (tx (un "-" (e (lit "0.0"))))))))
             (tx (un "-" (e (par (tc (lit "0.0"))))))
             (tx (un "-" (e (par (tx (un "-" (e (lit "0.0"))))))))
             (tx (op (e (lit "0.0")) "*" (un "-" (e (lit "1")))))
             (tx (un "-" (e (lit "1e400"))))
             (tc (lit "1e400"))))))
     (st (asg (tc (vr "z")) (tx (un "-" (e (lit "0.0"))))))
     (st
       (ex
         (tc
           (call
             "print"
             (t4
               [:comparison
                (e (vr "z"))
                [:comp_op ["EQUALS" "=="]]
                (e (lit "0.0"))])
             (t4
               [:comparison
                (e (vr "z"))
                [:comp_op ["EQUALS" "=="]]
                (e (lit "0"))])
             (tc (call "hash" (tc (vr "z"))))
             (tc
               [:atom_expr
                [:atom
                 ["OPEN_BRACE" "{"]
                 [:dictorsetmaker
                  (tc (lit "0.0"))
                  ["COLON" ":"]
                  (tc (at ["STRING" "'a'"]))]
                 ["CLOSE_BRACE" "}"]]
                [:trailer
                 ["OPEN_BRACK" "["]
                 [:subscriptlist [:subscript_ (tc (vr "z"))]]
                 ["CLOSE_BRACK" "]"]]])
             (tc (call "str" (tc (vr "z"))))
             (tc (call "repr" (tc (vr "z"))))
             (tc (call "abs" (tc (vr "z"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (un "-" (e (lit "0.0"))) "+" (e (lit "0.0"))))
             (tx (op (un "-" (e (lit "0.0"))) "-" (e (lit "0.0"))))
             (tx (op (e (vr "z")) "*" (e (lit "0"))))
             (tx (op (e (lit "0")) "*" (un "-" (e (lit "1.0")))))
             (tx
               (op (e (par (tx (un "-" (e (lit "0.0")))))) "**" (e (lit "3"))))
             (tx
               (op
                 (e (par (tx (un "-" (e (lit "0.0"))))))
                 "**"
                 (e (lit "2"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "int" (tc (vr "z"))))
             (tc (call "round" (tc (vr "z"))))
             (tc (call "float" (tc (at ["STRING" "'-0'"]))))
             (tx
               (op
                 (un "-" (e (lit "0.0")))
                 "*"
                 (op (e (lit "2")) "**" (e (lit "64")))))
             (tx (op (e (vr "z")) "/" (e (lit "1"))))
             (tx
               (op
                 (un "-" (e (par (tx (op (e (lit "2")) "**" (e (lit "64")))))))
                 "*"
                 (e (lit "0.0"))))))))
     (st
       (ex
         (tc
           (call
             "print"
             (tc
               (call "divmod" (tx (un "-" (e (lit "0.0")))) (tc (lit "1.0"))))
             (tc
               (call "divmod" (tc (lit "0.0")) (tx (un "-" (e (lit "1.0"))))))
             (tx (op (e (vr "z")) "//" (e (lit "1"))))
             (tx (op (e (lit "0.0")) "%" (un "-" (e (lit "1")))))))))
     ["EOF" "<EOF>"]]))


(def limits
  "try:
    x = 2 ** 100000
except MemoryError as e:
    print('MemoryError', e.args)
print(2 ** 99999 > 0)
try:
    x = 1 << 2 ** 70
except MemoryError as e:
    print('MemoryError', e.args)
try:
    x = 1 << 100000
except MemoryError as e:
    print('MemoryError', e.args)
try:
    str(10 ** 4300)
except ValueError as e:
    print('ValueError', e.args)
try:
    int('9' * 4301)
except ValueError as e:
    print('ValueError', e.args)
print(len(str(10 ** 4299)), int('9' * 4300) % 7)
try:
    print('lost', 10 ** 4300)
except ValueError:
    print('nothing printed')
try:
    'a' * (2 ** 53 - 1)
except MemoryError as e:
    print('MemoryError', e.args)
try:
    [0] * 2 ** 62
except MemoryError as e:
    print('MemoryError', e.args)
print(repr('' * 2 ** 62), 'ab' * 3, len([1, 2] * 524288))
try:
    [1, 2] * 524289
except MemoryError as e:
    print('MemoryError', e.args)
try:
    'a' * 2 ** 63
except OverflowError as e:
    print('OverflowError', e.args)
x = [1]
try:
    x *= 1048577
except MemoryError:
    print('MemoryError', len(x))
"
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
         (st
           (asg (tc (vr "x")) (tx (op (e (lit "2")) "**" (e (lit "100000"))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "MemoryError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'MemoryError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     (st
       (ex
         (tc
           (call
             "print"
             (t4
               [:comparison
                (op (e (lit "2")) "**" (e (lit "99999")))
                [:comp_op ["GREATER_THAN" ">"]]
                (e (lit "0"))])))))
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (asg
             (tc (vr "x"))
             (tx
               (op
                 (e (lit "1"))
                 "<<"
                 (op (e (lit "2")) "**" (e (lit "70")))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "MemoryError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'MemoryError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (asg (tc (vr "x")) (tx (op (e (lit "1")) "<<" (e (lit "100000"))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "MemoryError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'MemoryError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc (call "str" (tx (op (e (lit "10")) "**" (e (lit "4300"))))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "ValueError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'ValueError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "int"
                 (tx (op (e (at ["STRING" "'9'"])) "*" (e (lit "4301"))))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "ValueError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'ValueError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     (st
       (ex
         (tc
           (call
             "print"
             (tc
               (call
                 "len"
                 (tc
                   (call
                     "str"
                     (tx (op (e (lit "10")) "**" (e (lit "4299"))))))))
             (tx
               (op
                 (e
                   (call
                     "int"
                     (tx (op (e (at ["STRING" "'9'"])) "*" (e (lit "4300"))))))
                 "%"
                 (e (lit "7"))))))))
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'lost'"]))
                 (tx (op (e (lit "10")) "**" (e (lit "4300"))))))))
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (vr "ValueError"))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st (ex (tc (call "print" (tc (at ["STRING" "'nothing printed'"]))))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tx
               (op
                 (e (at ["STRING" "'a'"]))
                 "*"
                 (e
                   (par
                     (tx
                       (op
                         (op (e (lit "2")) "**" (e (lit "53")))
                         "-"
                         (e (lit "1"))))))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "MemoryError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'MemoryError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tx
               (op
                 (e
                   [:atom_expr
                    [:atom
                     ["OPEN_BRACK" "["]
                     [:testlist_comp (tc (lit "0"))]
                     ["CLOSE_BRACK" "]"]]])
                 "*"
                 (op (e (lit "2")) "**" (e (lit "62")))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "MemoryError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'MemoryError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     (st
       (ex
         (tc
           (call
             "print"
             (tc
               (call
                 "repr"
                 (tx
                   (op
                     (e (at ["STRING" "''"]))
                     "*"
                     (op (e (lit "2")) "**" (e (lit "62")))))))
             (tx (op (e (at ["STRING" "'ab'"])) "*" (e (lit "3"))))
             (tc
               (call
                 "len"
                 (tx
                   (op
                     (e
                       [:atom_expr
                        [:atom
                         ["OPEN_BRACK" "["]
                         [:testlist_comp
                          (tc (lit "1"))
                          ["COMMA" ","]
                          (tc (lit "2"))]
                         ["CLOSE_BRACK" "]"]]])
                     "*"
                     (e (lit "524288"))))))))))
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tx
               (op
                 (e
                   [:atom_expr
                    [:atom
                     ["OPEN_BRACK" "["]
                     [:testlist_comp
                      (tc (lit "1"))
                      ["COMMA" ","]
                      (tc (lit "2"))]
                     ["CLOSE_BRACK" "]"]]])
                 "*"
                 (e (lit "524289"))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "MemoryError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'MemoryError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tx
               (op
                 (e (at ["STRING" "'a'"]))
                 "*"
                 (op (e (lit "2")) "**" (e (lit "63")))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "OverflowError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'OverflowError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     (st
       (asg
         (tc (vr "x"))
         (tc
           [:atom_expr
            [:atom
             ["OPEN_BRACK" "["]
             [:testlist_comp (tc (lit "1"))]
             ["CLOSE_BRACK" "]"]]])))
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           [:expr_stmt
            [:testlist_star_expr (tc (vr "x"))]
            [:augassign ["MULT_ASSIGN" "*="]]
            [:testlist (tc (lit "1048577"))]])
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (vr "MemoryError"))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'MemoryError'"]))
                 (tc (call "len" (tc (vr "x"))))))))
         ["DEDENT" "<EOF>"]]]]]
     ["EOF" "<EOF>"]]))


(def small-profile
  "print(hex(2 ** 59), hex(2 ** 59 - 1 + 2 ** 59), 99999, -99999)
try:
    x = 2 ** 60
except MemoryError as e:
    print('MemoryError', e.args)
try:
    print(99999 + 1)
except ValueError as e:
    print('ValueError', e.args)
d = {1: 'a'}
try:
    d[5e-324] = 'b'
except MemoryError:
    print('MemoryError', len(d))
try:
    hash(1)
except MemoryError as e:
    print('MemoryError', e.args)
try:
    'a' * 2 ** 59
except MemoryError as e:
    print('MemoryError', e.args)
try:
    [1, 2] * 2 ** 59
except MemoryError as e:
    print('MemoryError', e.args)
print('ab' * 3, len([0] * 1000), int('99999'), str(-99999))
try:
    int('100000')
except ValueError as e:
    print('ValueError', e.args)
"
  (packet
    [:file_input
     (st
       (ex
         (tc
           (call
             "print"
             (tc (call "hex" (tx (op (e (lit "2")) "**" (e (lit "59"))))))
             (tc
               (call
                 "hex"
                 (tx
                   (op
                     (op
                       (op (e (lit "2")) "**" (e (lit "59")))
                       "-"
                       (e (lit "1")))
                     "+"
                     (op (e (lit "2")) "**" (e (lit "59")))))))
             (tc (lit "99999"))
             (tx (un "-" (e (lit "99999"))))))))
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st (asg (tc (vr "x")) (tx (op (e (lit "2")) "**" (e (lit "60"))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "MemoryError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'MemoryError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call "print" (tx (op (e (lit "99999")) "+" (e (lit "1"))))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "ValueError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'ValueError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     (st
       (asg
         (tc (vr "d"))
         (tc
           [:atom_expr
            [:atom
             ["OPEN_BRACE" "{"]
             [:dictorsetmaker
              (tc (lit "1"))
              ["COLON" ":"]
              (tc (at ["STRING" "'a'"]))]
             ["CLOSE_BRACE" "}"]]])))
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (asg
             (tc
               [:atom_expr
                [:atom (nm "d")]
                [:trailer
                 ["OPEN_BRACK" "["]
                 [:subscriptlist [:subscript_ (tc (lit "5e-324"))]]
                 ["CLOSE_BRACK" "]"]]])
             (tc (at ["STRING" "'b'"]))))
         ["DEDENT" ""]]
        [:except_clause ["EXCEPT" "except"] (tc (vr "MemoryError"))]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'MemoryError'"]))
                 (tc (call "len" (tc (vr "d"))))))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st (ex (tc (call "hash" (tc (lit "1"))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "MemoryError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'MemoryError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tx
               (op
                 (e (at ["STRING" "'a'"]))
                 "*"
                 (op (e (lit "2")) "**" (e (lit "59")))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "MemoryError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'MemoryError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tx
               (op
                 (e
                   [:atom_expr
                    [:atom
                     ["OPEN_BRACK" "["]
                     [:testlist_comp
                      (tc (lit "1"))
                      ["COMMA" ","]
                      (tc (lit "2"))]
                     ["CLOSE_BRACK" "]"]]])
                 "*"
                 (op (e (lit "2")) "**" (e (lit "59")))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "MemoryError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'MemoryError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" ""]]]]]
     (st
       (ex
         (tc
           (call
             "print"
             (tx (op (e (at ["STRING" "'ab'"])) "*" (e (lit "3"))))
             (tc
               (call
                 "len"
                 (tx
                   (op
                     (e
                       [:atom_expr
                        [:atom
                         ["OPEN_BRACK" "["]
                         [:testlist_comp (tc (lit "0"))]
                         ["CLOSE_BRACK" "]"]]])
                     "*"
                     (e (lit "1000"))))))
             (tc (call "int" (tc (at ["STRING" "'99999'"]))))
             (tc (call "str" (tx (un "-" (e (lit "99999"))))))))))
     [:stmt
      [:compound_stmt
       [:try_stmt
        ["TRY" "try"]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st (ex (tc (call "int" (tc (at ["STRING" "'100000'"]))))))
         ["DEDENT" ""]]
        [:except_clause
         ["EXCEPT" "except"]
         (tc (vr "ValueError"))
         ["AS" "as"]
         (nm "e")]
        ["COLON" ":"]
        [:block
         ["NEWLINE" "\n"]
         ["INDENT" "    "]
         (st
           (ex
             (tc
               (call
                 "print"
                 (tc (at ["STRING" "'ValueError'"]))
                 (tc
                   [:atom_expr
                    [:atom (nm "e")]
                    [:trailer ["DOT" "."] (nm "args")]])))))
         ["DEDENT" "<EOF>"]]]]]
     ["EOF" "<EOF>"]]))


(def programs
  "`[corpus-name packet]` of every program, in corpus order."
  [["promotion" promotion]
   ["demotion" demotion]
   ["keys" numeric-keys]
   ["divmod" floor-division]
   ["shifts" shifts]
   ["power" power]
   ["conversions" conversions]
   ["signed-zero" signed-zero]
   ["limits" limits]
   ["small" small-profile]])
