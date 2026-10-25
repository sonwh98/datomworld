(ns yang.php.antlr.programs
  "PHP fixtures for P1 steps 1 and 2: source, expected output, the oracle
   status and the exit criterion each pins (plan section 4). Every
   `:out` is the text the program prints; `:oracle :pending` marks an
   expectation reasoned from the PHP 8.3 sources and manual but not yet
   confirmed against a PHP 8.3 CLI, which must be provisioned before the
   slice lands (section 17.3). The tests compare `:out` byte for byte; an
   uncaught error ends the output with `PHP Fatal error:  Uncaught <class>:
   <message>` and no path.")


(def fixtures
  [{:id :mixed-text-and-code,
    :criterion "mixed text and code order",
    :source "a <?php echo 1; ?>b <?= 2 ?>c",
    :out "a 1b 2c",
    :oracle :pending}
   {:id :close-tag-newline,
    :criterion "the ?> newline rule",
    :source "<?php echo 1 ?>\nx",
    :out "1x",
    :oracle :pending}
   {:id :close-tag-ends-statement,
    :criterion "a close tag ends a statement",
    :source "<?php echo 1 ?> text <?php echo 2;\n?>\ntail",
    :out "1 text 2tail",
    :oracle :pending}
   {:id :echo-scalars,
    :criterion "literals and echo juggling",
    :source "<?php echo 1 + 2 * 3, \"x\", 2.5, true, false, null, \"|\";",
    :out "7x2.51|",
    :oracle :pending}
   {:id :echo-floats,
    :criterion "echo uses precision 14",
    :source (str "<?php echo 0.1 + 0.2, PHP_EOL, 1e100, PHP_EOL, -0.0, PHP_EOL,"
                 " 1/3, PHP_EOL, 100000000000000000000.0, PHP_EOL, 2.5, PHP_EOL,"
                 " 1e14, PHP_EOL, 1e13, PHP_EOL, 0.0001, PHP_EOL, 0.00001,"
                 " PHP_EOL, 7/2, PHP_EOL, 6/3, PHP_EOL;"),
    :out (str "0.3\n1.0E+100\n-0\n0.33333333333333\n1.0E+20\n2.5\n1.0E+14\n"
              "10000000000000\n0.0001\n1.0E-5\n3.5\n2\n"),
    :oracle :pending}
   {:id :var-dump-scalars,
    :criterion "var_dump prints the shortest round trip",
    :source (str "<?php var_dump(0.1 + 0.2, 2.0, 1e25, -0.0, NAN, INF, -INF, 42,"
                 " \"abc\", true, false, null, \"é\", 1.5, 0.1);"),
    :out (str "float(0.30000000000000004)\nfloat(2)\nfloat(1.0E+25)\nfloat(-0)\n"
              "float(NAN)\nfloat(INF)\nfloat(-INF)\nint(42)\nstring(3) \"abc\"\n"
              "bool(true)\nbool(false)\nNULL\nstring(2) \"é\"\nfloat(1.5)\n"
              "float(0.1)\n"),
    :oracle :pending}
   {:id :int-overflow,
    :criterion "PHP_INT_MAX + 1 is a float on every host",
    :source (str "<?php var_dump(PHP_INT_MAX, PHP_INT_MIN, PHP_INT_MAX + 1,"
                 " PHP_INT_MIN - 1, PHP_INT_MAX * 2, -PHP_INT_MIN,"
                 " 9223372036854775807, 9223372036854775808,"
                 " 0x7FFFFFFFFFFFFFFF, 0xFFFFFFFFFFFFFFFF, 010, 0b101);"),
    :out (str "int(9223372036854775807)\nint(-9223372036854775808)\n"
              "float(9.223372036854776E+18)\nfloat(-9.223372036854776E+18)\n"
              "float(1.8446744073709552E+19)\nfloat(9.223372036854776E+18)\n"
              "int(9223372036854775807)\nfloat(9.223372036854776E+18)\n"
              "int(9223372036854775807)\nfloat(1.8446744073709552E+19)\n"
              "int(8)\nint(5)\n"),
    :oracle :pending}
   {:id :int-literals,
    :criterion "integer literals past PHP_INT_MAX are floats",
    :source (str "<?php var_dump(100000000000000000000, 1_000, 0x1F, 0b11, 017,"
                 " 0o17, 9007199254740993, 9007199254740992 + 1);"),
    :out (str "float(1.0E+20)\nint(1000)\nint(31)\nint(3)\nint(15)\nint(15)\n"
              "int(9007199254740993)\nint(9007199254740993)\n"),
    :oracle :pending}
   {:id :relational,
    :criterion "relational operators over scalars",
    :source (str "<?php var_dump(1 < 2, 2 <= 2, 3 > 4, 4 >= 4, \"a\" < \"b\","
                 " \"10\" < \"9\", \"abc\" < \"abd\", 10 < \"9a\", NAN >= NAN,"
                 " 1.5 > 1);"),
    :out (str "bool(true)\nbool(true)\nbool(false)\nbool(true)\nbool(true)\n"
              "bool(false)\nbool(true)\nbool(true)\nbool(false)\nbool(true)\n"),
    :oracle :pending}
   {:id :int-edges,
    :criterion "% and intdiv edges",
    :source (str "<?php var_dump(PHP_INT_MIN % -1, 7 % 3, -7 % 3, 7 % -3,"
                 " intdiv(7, 2), intdiv(-7, 2), 2 ** 10, 2 ** 63, 2 ** 62,"
                 " 2 ** -1, 2.0 ** 3, (-2) ** 3, 0 ** 0, (-1) ** 100001,"
                 " fmod(1, 0), fmod(7.5, 2), fmod(-7.5, 2), 7 / 2, 8 / 2,"
                 " 1 <=> 2);"),
    :out (str "int(0)\nint(1)\nint(-1)\nint(1)\nint(3)\nint(-3)\nint(1024)\n"
              "float(9.223372036854776E+18)\nint(4611686018427387904)\n"
              "float(0.5)\nfloat(8)\nint(-8)\nint(1)\nint(-1)\nfloat(NAN)\n"
              "float(1.5)\nfloat(-1.5)\nfloat(3.5)\nint(4)\nint(-1)\n"),
    :oracle :pending}
   {:id :intdiv-min-by-minus-one,
    :criterion "intdiv(PHP_INT_MIN, -1) throws ArithmeticError",
    :source "<?php echo 1; echo intdiv(PHP_INT_MIN, -1);",
    :out (str "1PHP Fatal error:  Uncaught ArithmeticError: "
              "Division of PHP_INT_MIN by -1 is not an integer"),
    :oracle :pending}
   {:id :modulo-by-zero,
    :criterion "% by zero throws DivisionByZeroError",
    :source "<?php echo 1; echo 1 % 0;",
    :out "1PHP Fatal error:  Uncaught DivisionByZeroError: Modulo by zero",
    :oracle :pending}
   {:id :division-by-zero,
    :criterion "/ by zero throws DivisionByZeroError",
    :source "<?php echo 1 / 0;",
    :out "PHP Fatal error:  Uncaught DivisionByZeroError: Division by zero",
    :oracle :pending}
   {:id :intdiv-by-zero,
    :criterion "intdiv by zero throws DivisionByZeroError",
    :source "<?php echo intdiv(1, 0);",
    :out "PHP Fatal error:  Uncaught DivisionByZeroError: Division by zero",
    :oracle :pending}
   {:id :numeric-strings,
    :criterion "numeric strings in arithmetic",
    :source (str "<?php var_dump(\"5\" + \"3\", \" 5\" + 1, \"5 \" + 1,"
                 " \"1.5\" + 1, \"1e2\" + 0);"),
    :out "int(8)\nint(6)\nint(6)\nfloat(2.5)\nfloat(100)\n",
    :oracle :pending}
   {:id :juggling,
    :criterion "casts, bitwise operators and concatenation",
    :source (str "<?php var_dump((int)\"12abc\", (int)1.9, (int)-1.9, (int)NAN,"
                 " (float)\"1.5e3\", (string)1.0, (string)true, (bool)\"0\","
                 " (bool)\"0.0\", (bool)0.0, (bool)\"\", !0, -\"5\", +\"3.5\","
                 " ~5, 1 << 63, 1 << 64, -8 >> 1, 5 & 3, 5 | 3, 5 ^ 3,"
                 " \"a\" . 1 . 2.5);"),
    :out (str "int(12)\nint(1)\nint(-1)\nint(0)\nfloat(1500)\nstring(1) \"1\"\n"
              "string(1) \"1\"\nbool(false)\nbool(true)\nbool(false)\n"
              "bool(false)\nbool(true)\nint(-5)\nfloat(3.5)\nint(-6)\n"
              "int(-9223372036854775808)\nint(0)\nint(-4)\nint(1)\nint(7)\n"
              "int(6)\nstring(5) \"a12.5\"\n"),
    :oracle :pending}
   {:id :short-circuit-and-ternary,
    :criterion "operators short circuit",
    :source (str "<?php echo strlen(\"héllo\"), \" \", true ? \"y\" : \"n\","
                 " 0 ?: \"e\", 1 && 0, 1 || 0, 1 xor 1;"),
    :out "6 ye1",
    :oracle :pending}
   {:id :string-escapes,
    :criterion "string escapes resolve to bytes",
    :source "<?php echo \"a\\x41\\u{e9}\\101\\n\", 'a\\n\\'b\\\\';",
    :out "aAéA\na\\n'b\\",
    :oracle :pending}
   {:id :print-returns-one,
    :criterion "print is an expression",
    :source "<?php echo print \"x\";",
    :out "x1",
    :oracle :pending}
   {:id :undefined-function,
    :criterion "an undefined function is an Error",
    :source "<?php echo undefined_fn(1);",
    :out "PHP Fatal error:  Uncaught Error: Call to undefined function undefined_fn()",
    :oracle :pending}])


;; =============================================================================
;; The scalar loose-comparison matrix (plan section 4, exit criterion 6)
;; =============================================================================

(def matrix-values
  "The scalar subset of the section 3.5 matrix, as PHP source. The `[]` and
   `[0]` columns are P2's and the object columns P4's."
  ["null" "true" "false" "0" "1" "-1" "\"0\"" "\"\"" "\"1\"" "\"01\"" "\"1e1\""
   "\"10\"" "\"abc\"" "\" 1\"" "\"1 \"" "0.0" "NAN" "INF"])


(def matrix-slice
  "The slice that owns each matrix column; later slices add columns and never
   regenerate the cells of earlier ones."
  (zipmap matrix-values (repeat :p1)))


(def equal-matrix
  "`==` over every pair of `matrix-values`: row `a`, column `b`, `1` for true.
   `:oracle :pending`: pinned from the PHP 8 comparison rules, with the
   cells below checked by hand against them."
  ["101100010000000100"
   "010011001111111011"
   "101100110000000100"
   "101100100000000100"
   "010010001100011000"
   "010001000000000000"
   "001100100000000100"
   "101000010000000000"
   "010010001100011000"
   "010010001100011000"
   "010000000011000000"
   "010000000011000000"
   "010000000000100000"
   "010010001100011000"
   "010010001100011000"
   "101100100000000100"
   "010000000000000000"
   "010000000000000001"])


(def spaceship-matrix
  "`<=>` over every pair, `<` `=` `>` for -1, 0, 1. `:oracle :pending`."
  ["=<==<<<=<<<<<<<=<<"
   ">=>>==>>=======>=="
   "=<==<<==<<<<<<<=<<"
   "=<==<>=><<<<<<<=><"
   ">=>>=>>>==<<<==>><"
   ">=><<=<><<<<<<<<><"
   "><==<>=><<<<<<<=><"
   "=<=<<<<=<<<<<<<<><"
   ">=>>=>>>==<<<==>><"
   ">=>>=>>>==<<<==>><"
   ">=>>>>>>>>==<>>>><"
   ">=>>>>>>>>==<>>>><"
   ">=>>>>>>>>>>=>>>>>"
   ">=>>=>>>==<<<==>><"
   ">=>>=>>>==<<<==>><"
   "=<==<>=><<<<<<<=><"
   ">=>>>>>>>>>>>>>>>>"
   ">=>>>>>>>>>><>>>>="])


(def matrix-source
  "One program printing the `==` matrix, a newline, then the `<=>` matrix
   (as 0, 1, 2 for -1, 0, 1)."
  (str "<?php\n"
       (apply str (for [a matrix-values b matrix-values]
                    (str "echo " a " == " b " ? '1' : '0';\n")))
       "echo PHP_EOL;\n"
       (apply str (for [a matrix-values b matrix-values]
                    (str "echo (" a " <=> " b ") + 1;\n")))))


(def known-cells
  "Cells reasoned from the PHP 8 manual's comparison table, independent of
   the generated matrix: [a b == <=>]."
  [["null" "false" true 0] ["null" "\"\"" true 0] ["null" "\"0\"" false -1]
   ["0" "\"\"" false 1] ["0" "\"abc\"" false -1] ["\"abc\"" "0" false 1]
   ["\"1\"" "\"01\"" true 0] ["\"10\"" "\"1e1\"" true 0]
   ["\"abc\"" "\"abc\"" true 0] ["NAN" "NAN" false 1] ["INF" "INF" true 0]
   ["1" "\" 1\"" true 0] ["1" "\"1 \"" true 0] ["true" "\"abc\"" true 0]
   ["false" "\"0\"" true 0]])


(def float-cases
  "Float literals with their `echo` (precision 14) and `var_dump` (shortest
   round trip) text, computed by an independent reference (Python's correctly
   rounded `%.13e` and `repr` laid out by zend_gcvt's rules). [literal echo var_dump]."
  [["-1.0539756485530688e+143" "-1.0539756485531E+143" "-1.0539756485530688E+143"]
   ["-1.2143721666352482e+132" "-1.2143721666352E+132" "-1.2143721666352482E+132"]
   ["-6.360375184993316e+38" "-6.3603751849933E+38" "-6.360375184993316E+38"]
   ["1.526087629730313e+249" "1.5260876297303E+249" "1.526087629730313E+249"]
   ["-7.02551513787579e+129" "-7.0255151378758E+129" "-7.02551513787579E+129"]
   ["1.6260771785726701e-74" "1.6260771785727E-74" "1.6260771785726701E-74"]
   ["-5.984009685093657e+66" "-5.9840096850937E+66" "-5.984009685093657E+66"]
   ["4.8959585264576934e+278" "4.8959585264577E+278" "4.8959585264576934E+278"]
   ["-3.7634144828029947e-168" "-3.763414482803E-168" "-3.7634144828029947E-168"]
   ["4.2301541954837196e-79" "4.2301541954837E-79" "4.2301541954837196E-79"]
   ["-811.753087541563" "-811.75308754156" "-811.753087541563"]
   ["-393.197474750949" "-393.19747475095" "-393.197474750949"]
   ["-818.6589250163212" "-818.65892501632" "-818.6589250163212"]
   ["619.2890687343552" "619.28906873436" "619.2890687343552"]
   ["386.87696508247814" "386.87696508248" "386.87696508247814"]
   ["-916.239327260308" "-916.23932726031" "-916.239327260308"]
   ["964.3868415975564" "964.38684159756" "964.3868415975564"]
   ["929.5155622511336" "929.51556225113" "929.5155622511336"]
   ["65392.2534" "65392.2534" "65392.2534"]
   ["65007.0974" "65007.0974" "65007.0974"]
   ["1500.0737" "1500.0737" "1500.0737"]
   ["6316.0" "6316" "6316"]
   ["19020.8" "19020.8" "19020.8"]
   ["59961.808495" "59961.808495" "59961.808495"]
   ["0.1" "0.1" "0.1"]
   ["1.5" "1.5" "1.5"]
   ["100.0" "100" "100"]
   ["1000000000000000.0" "1.0E+15" "1000000000000000"]
   ["1e+16" "1.0E+16" "10000000000000000"]
   ["1e+17" "1.0E+17" "1.0E+17"]
   ["100000000000000.0" "1.0E+14" "100000000000000"]
   ["10000000000000.0" "10000000000000" "10000000000000"]
   ["0.5" "0.5" "0.5"]
   ["5e-324" "4.9406564584125E-324" "5.0E-324"]
   ["1.7976931348623157e+308" "1.7976931348623E+308" "1.7976931348623157E+308"]
   ["2.2250738585072014e-308" "2.2250738585072E-308" "2.2250738585072014E-308"]
   ["0.30000000000000004" "0.3" "0.30000000000000004"]
   ["0.3333333333333333" "0.33333333333333" "0.3333333333333333"]
   ["0.6666666666666666" "0.66666666666667" "0.6666666666666666"]
   ["9007199254740992.0" "9.007199254741E+15" "9007199254740992"]
   ["12345678901234.5" "12345678901234" "12345678901234.5"]
   ["1.00000000000005" "1" "1.00000000000005"]
   ["0.000123456789012345" "0.00012345678901234" "0.000123456789012345"]
   ["99999999999999.5" "1.0E+14" "99999999999999.5"]
   ["999999999999995.0" "1.0E+15" "999999999999995"]
   ["1.0" "1" "1"]
   ["1.2345678901234568e+17" "1.2345678901235E+17" "1.2345678901234568E+17"]
   ["0.30000000000000004" "0.3" "0.30000000000000004"]
   ["1e-07" "1.0E-7" "1.0E-7"]
   ["-1.5e-09" "-1.5E-9" "-1.5E-9"]])
