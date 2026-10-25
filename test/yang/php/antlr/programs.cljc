(ns yang.php.antlr.programs
  "PHP fixtures for slices P1 and P2: source, expected output, the oracle
   status and the exit criterion each pins (docs/design/yang.php.md sections
   3, 14, 15, 17). Covers the P1 scalar, operator, string, control-flow,
   function, static-variable, warning and `@` groups and the P2 array groups
   (key coercion, copy on write, order after unset, next-index, evaluation
   order, vivification, read warnings, string offsets, spread,
   `isset`/`empty`/`unset`, literals, compound assignment, null coalescing,
   array operators, `foreach` by value, the core array builtins and sorting).
   Every `:out` is the text the program prints; `:oracle` records the PHP 8.3
   CLI banner (`\"php-8.3.35 shiva 2026-10-25\"`) or `:departure` for a named
   known-gap departure (`:departure {:gap ... :zend ...}`). The tests compare
   `:out` byte for byte; an uncaught error ends the rendered output with
   `PHP Fatal error:  Uncaught <class>: <message>` and no path. A fixture with
   `:diag` (a vector of `[errno message]`) or `:uncaught` (`{:class c :message
   m}`) is compared raw: `:out` is then the unit's own output, without the
   fatal line.")


(def fixtures
  [{:id :mixed-text-and-code,
    :criterion "mixed text and code order",
    :source "a <?php echo 1; ?>b <?= 2 ?>c",
    :out "a 1b 2c",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :html-markup-attributes,
    :criterion "inline HTML keeps markup bytes",
    :source "<p class=\"a\">x</p>",
    :out "<p class=\"a\">x</p>",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :html-comment,
    :criterion "inline HTML keeps a comment",
    :source "<!-- c -->",
    :out "<!-- c -->",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :html-leading-newline,
    :criterion "inline HTML keeps a newline before markup",
    :source "\n<p>x</p>",
    :out "\n<p>x</p>",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :html-script-body,
    :criterion "inline HTML keeps a script body",
    :source "<script>var a = 1;</script>",
    :out "<script>var a = 1;</script>",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :html-close-tag-blank-line,
    :criterion "a close tag swallows one newline before markup",
    :source "<?php echo 1; ?>\n\n<p>",
    :out "1\n<p>",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :pow-negative-underflowing-power,
    :criterion "2 ** -1074 is the smallest subnormal",
    :source "<?php var_dump(2.0 ** -1074, 0.5 ** -2000);",
    :out "float(5.0E-324)\nfloat(INF)\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :numeric-string-padding,
    :criterion "leading zeros do not change a numeric string's value",
    :source (str "<?php var_dump(\"0000000000000000000000012\" + 0, \"1e0000000000005\" + 0,"
                 " \"1e-0000000000005\" + 0);"),
    :out "int(12)\nfloat(100000)\nfloat(1.0E-5)\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :string-supplementary-unicode,
    :criterion "a supplementary code point is four bytes",
    :source "<?php var_dump(\"😀\");",
    :out "string(4) \"😀\"\n",
    ;; the portable lanes compare the byte carrier (UTF-8 bytes as chars)
    :carrier "string(4) \"\u00F0\u009F\u0098\u0080\"\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :single-quote-escapes,
    :criterion "single-quoted escapes resolve in one scan",
    :source "<?php echo 'a\\\\'.'b\\'c'.'\\\\\\'d'.'e\\nf';",
    :out "a\\b'c\\'de\\nf",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :close-tag-newline,
    :criterion "the ?> newline rule",
    :source "<?php echo 1 ?>\nx",
    :out "1x",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :close-tag-ends-statement,
    :criterion "a close tag ends a statement",
    :source "<?php echo 1 ?> text <?php echo 2;\n?>\ntail",
    :out "1 text 2tail",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :echo-scalars,
    :criterion "literals and echo juggling",
    :source "<?php echo 1 + 2 * 3, \"x\", 2.5, true, false, null, \"|\";",
    :out "7x2.51|",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :echo-floats,
    :criterion "echo uses precision 14",
    :source (str "<?php echo 0.1 + 0.2, PHP_EOL, 1e100, PHP_EOL, -0.0, PHP_EOL,"
                 " 1/3, PHP_EOL, 100000000000000000000.0, PHP_EOL, 2.5, PHP_EOL,"
                 " 1e14, PHP_EOL, 1e13, PHP_EOL, 0.0001, PHP_EOL, 0.00001,"
                 " PHP_EOL, 7/2, PHP_EOL, 6/3, PHP_EOL;"),
    :out (str "0.3\n1.0E+100\n-0\n0.33333333333333\n1.0E+20\n2.5\n1.0E+14\n"
              "10000000000000\n0.0001\n1.0E-5\n3.5\n2\n"),
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :var-dump-scalars,
    :criterion "var_dump prints the shortest round trip",
    :source (str "<?php var_dump(0.1 + 0.2, 2.0, 1e25, -0.0, NAN, INF, -INF, 42,"
                 " \"abc\", true, false, null, \"é\", 1.5, 0.1);"),
    :out (str "float(0.30000000000000004)\nfloat(2)\nfloat(1.0E+25)\nfloat(-0)\n"
              "float(NAN)\nfloat(INF)\nfloat(-INF)\nint(42)\nstring(3) \"abc\"\n"
              "bool(true)\nbool(false)\nNULL\nstring(2) \"é\"\nfloat(1.5)\n"
              "float(0.1)\n"),
    :oracle "php-8.3.35 shiva 2026-10-25"}
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
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :int-literals,
    :criterion "integer literals past PHP_INT_MAX are floats",
    :source (str "<?php var_dump(100000000000000000000, 1_000, 0x1F, 0b11, 017,"
                 " 0o17, 9007199254740993, 9007199254740992 + 1);"),
    :out (str "float(1.0E+20)\nint(1000)\nint(31)\nint(3)\nint(15)\nint(15)\n"
              "int(9007199254740993)\nint(9007199254740993)\n"),
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :relational,
    :criterion "relational operators over scalars",
    :source (str "<?php var_dump(1 < 2, 2 <= 2, 3 > 4, 4 >= 4, \"a\" < \"b\","
                 " \"10\" < \"9\", \"abc\" < \"abd\", 10 < \"9a\", NAN >= NAN,"
                 " 1.5 > 1);"),
    :out (str "bool(true)\nbool(true)\nbool(false)\nbool(true)\nbool(true)\n"
              "bool(false)\nbool(true)\nbool(true)\nbool(false)\nbool(true)\n"),
    :oracle "php-8.3.35 shiva 2026-10-25"}
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
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :intdiv-min-by-minus-one,
    :criterion "intdiv(PHP_INT_MIN, -1) throws ArithmeticError",
    :source "<?php echo 1; echo intdiv(PHP_INT_MIN, -1);",
    :out "1",
    :diag [],
    :uncaught {:class "ArithmeticError",
               :message "Division of PHP_INT_MIN by -1 is not an integer"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :modulo-by-zero,
    :criterion "% by zero throws DivisionByZeroError",
    :source "<?php echo 1; echo 1 % 0;",
    :out "1",
    :diag [],
    :uncaught {:class "DivisionByZeroError", :message "Modulo by zero"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :division-by-zero,
    :criterion "/ by zero throws DivisionByZeroError",
    :source "<?php echo 1 / 0;",
    :out "",
    :diag [],
    :uncaught {:class "DivisionByZeroError", :message "Division by zero"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :intdiv-by-zero,
    :criterion "intdiv by zero throws DivisionByZeroError",
    :source "<?php echo intdiv(1, 0);",
    :out "",
    :diag [],
    :uncaught {:class "DivisionByZeroError", :message "Division by zero"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :numeric-strings,
    :criterion "numeric strings in arithmetic",
    :source (str "<?php var_dump(\"5\" + \"3\", \" 5\" + 1, \"5 \" + 1,"
                 " \"1.5\" + 1, \"1e2\" + 0);"),
    :out "int(8)\nint(6)\nint(6)\nfloat(2.5)\nfloat(100)\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
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
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :short-circuit-and-ternary,
    :criterion "operators short circuit",
    :source (str "<?php echo strlen(\"héllo\"), \" \", true ? \"y\" : \"n\","
                 " 0 ?: \"e\", 1 && 0, 1 || 0, 1 xor 1;"),
    :out "6 ye1",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :string-escapes,
    :criterion "string escapes resolve to bytes",
    :source "<?php echo \"a\\x41\\u{e9}\\101\\n\", 'a\\n\\'b\\\\';",
    :out "aAéA\na\\n'b\\",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :print-returns-one,
    :criterion "print is an expression",
    :source "<?php echo print \"x\";",
    :out "x1",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :variables-and-assignment,
    :criterion "top-level variables, compound assignment, isset, empty, unset",
    :source (str "<?php $x = 1; $x += 2; $x .= \"a\"; echo $x, \"|\"; $y = $z ?? 7; echo $y;"
                 " $a = $b = 3; echo $a + $b; $c = 5; $c **= 2; $c -= 1; $c *= 2;"
                 " $c /= 4; $c %= 7; $c <<= 1; echo $c; echo isset($c) ? 1 : 0,"
                 " empty($c) ? 1 : 0; unset($c); echo isset($c) ? 1 : 0;"),
    :out "3a|7610100",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :null-coalescing-and-elvis,
    :criterion "??, ?:, ??= on variables",
    :source (str "<?php $t = 0; echo $t ?: \"elvis\", $t ?? \"n\", \"|\"; $q = 5; $q ??= 9;"
                 " $r ??= 9; echo $q, $r;"),
    :out "elvis0|59",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :increments,
    :criterion "++ and -- over int, string, null and float",
    :source (str "<?php $i = 5; echo $i++, $i, ++$i, $i--, --$i, \"|\"; $s = \"a\"; $s++;"
                 " echo $s; $z = \"Zz\"; $z++; echo $z; $n = null; $n++; var_dump($n);"
                 " $m = null; $m--; var_dump($m); $f = 1.5; $f--; var_dump($f);"
                 " $big = PHP_INT_MAX; $big++; var_dump($big);"),
    :out (str "56775|bAAa" "int(1)\nNULL\nfloat(0.5)\nfloat(9.223372036854776E+18)\n"),
    :diag [[2 "Decrement on type null has no effect, this will change in the next major version of PHP"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :if-elseif-else,
    :criterion "if, elseif, else if and the alternative syntax",
    :source (str "<?php for ($i = 0; $i < 4; $i++) { if ($i == 0) { echo \"zero\"; }"
                 " elseif ($i == 1) echo \"one\"; else if ($i == 2) { echo \"two\"; }"
                 " else { echo \"many\"; } echo \",\"; } if ($i > 3): echo \"alt\";"
                 " elseif ($i): echo \"no\"; else: echo \"no\"; endif; if ($i < 0):"
                 " echo \"no\"; else: echo \"-else\"; endif;"),
    :out "zero,one,two,many,alt-else",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :loops,
    :criterion "while, do, for with comma lists, alternative syntax",
    :source (str "<?php $i = 0; while ($i < 3) { echo $i++; } echo \"|\";"
                 " do { echo $i--; } while ($i > 0); echo \"|\";"
                 " for ($a = 0, $b = 3; $a < $b; $a++, $b--) echo $a, $b, \",\";"
                 " echo \"|\"; for (;;) { break; } while ($i < 2): echo \"w\", $i; $i++;"
                 " endwhile; for ($j = 0; $j < 2; $j++): echo \"f\", $j; endfor;"),
    :out "012|321|03,12,|w0w1f0f1",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :break-continue-levels,
    :criterion "break n and continue n index the loop stack",
    :source (str "<?php for ($i = 0; $i < 3; $i++) { for ($j = 0; $j < 3; $j++) {"
                 " if ($j == 1) continue 2; if ($i == 2) break 2; echo $i, $j, \";\"; } }"
                 " echo \"|\"; $k = 0; while (true) { $k++; if ($k < 3) continue;"
                 " if ($k > 4) break; echo $k; } echo \"|\";"
                 " do { echo \"once\"; break; echo \"never\"; } while (true);"),
    :out "00;10;|34|once",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :switch,
    :criterion "switch: loose match, fallthrough, default anywhere, one loop level",
    :source (str "<?php switch (2) { case 1: echo \"one\"; case 2: echo \"two\";"
                 " case 3: echo \"three\"; break; default: echo \"dflt\"; }"
                 " switch (\"x\") { case 1: echo \"one\"; break; default: echo \"dflt\";"
                 " case 2: echo \"two\"; } switch (\"2\") { case 1: echo \"a\"; break;"
                 " case 2: echo \"loose\"; break; } for ($i = 0; $i < 3; $i++) {"
                 " switch ($i) { case 1: continue 2; default: echo $i; } echo \",\"; }"
                 " switch (9) { case 1: echo \"no\"; }"
                 " switch (3) { default: echo \"d\"; break; case 3: echo \"three\"; }"),
    :out "twothreedflttwoloose0,2,three",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :interpolation,
    :criterion "scalar string interpolation: $x and {$x}",
    :source (str "<?php $n = \"w\"; $i = 5; $f = 2.5; echo \"a $n b {$n}c $i$i \\$n x\\n\","
                 " \"f=$f\", \"$n\", \"\";"),
    :out "a w b wc 55 $n x\nf=2.5w",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :functions,
    :criterion "declarations, hoisting, defaults, return, recursion",
    :source (str "<?php echo fib(10), \"|\", add(1), \"|\", add(1, 5), \"|\", ADD(2, 2), \"|\";"
                 " function fib($n) { if ($n < 2) return $n; return fib($n - 1) + fib($n - 2); }"
                 " function add($a, $b = 10) { return $a + $b; }"
                 " function first_over($limit) { for ($i = 0; ; $i++) {"
                 " if ($i * $i > $limit) return $i; } } echo first_over(50), \"|\";"
                 " function noret() { $x = 1; } var_dump(noret());"
                 " function nested() { function inner() { return \"inner\"; } return \"outer\"; }"
                 " echo nested(), inner(), \"|\"; if (true) { function cond() { return \"cond\"; } }"
                 " echo cond(); function early($x) { if ($x) { return \"early\"; } echo \"late;\"; }"
                 " echo early(1), early(0) === null ? \"N\" : \"?\";"),
    :out "55|11|6|4|8|NULL\nouterinner|condearlylate;N",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :static-variables,
    :criterion "static slots survive calls and recursion, one per function",
    :source (str "<?php function counter() { static $n = 5, $m; $n++; $m .= \"x\"; return $n . $m; }"
                 " echo counter(), counter(), counter(), \"|\";"
                 " function rec($d) { static $calls = 0; $calls++; if ($d > 0) rec($d - 1);"
                 " return $calls; } echo rec(3), rec(0), \"|\";"
                 " function a() { static $v = 1; return $v++; }"
                 " function b() { static $v = 100; return $v++; } echo a(), b(), a(), b();"),
    :out "6x7xx8xxx|45|11002101",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :local-scope,
    :criterion "a function sees its own variables, not the unit's",
    :source (str "<?php $g = \"global\"; function f($p) { $l = $p . \"!\"; return $l . $g; }"
                 " echo f(\"a\"), \"|\", $g;"),
    :out "a!|global",
    :diag [[2 "Undefined variable $g"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :argument-count-error,
    :criterion "too few arguments is an uncaught ArgumentCountError",
    :source "<?php function f($a, $b) {} echo \"a\"; f(1);",
    :out "a",
    :diag [],
    :uncaught {:class "ArgumentCountError",
               :message "Too few arguments to function f(), 1 passed and exactly 2 expected"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :silence-with-counting-handler,
    :criterion "@ with a counting handler: the handler runs and sees 4437",
    :source (str "<?php function h($n, $s) { echo \"H\", error_reporting(), \";\"; return true; }"
                 " set_error_handler('h'); @$u; echo @$u ?? \"x\"; echo \"done|\";"
                 " echo $v; echo error_reporting();"),
    :out "H4437;H4437;xdone|H32767;32767",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :undefined-function,
    :criterion "an undefined function is an Error",
    :source "<?php echo undefined_fn(1);",
    :out "",
    :diag [],
    :uncaught {:class "Error", :message "Call to undefined function undefined_fn()"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :static-initializer-call,
    :criterion "a static initializer is an expression, evaluated once in the function",
    :source (str "<?php function g() { echo \"init\"; return 67; }"
                 " function f() { static $n = g(); echo $n; } f();"),
    :out "init67",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :static-initializer-twice,
    :criterion "the initializer runs on the first reach only",
    :source (str "<?php function g() { echo \"init\"; return 67; }"
                 " function f() { static $n = g(); echo $n; } f(); f();"),
    :out "init6767",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :static-initializer-expression,
    :criterion "a static initializer may be an arithmetic expression, two per statement",
    :source (str "<?php function f() { static $n = 1 + 2, $m = $n * 2 + 1; $n++; $m++; return $n . $m; }"
                 " echo f(), f();"),
    :out "4859",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :static-initializer-recursion,
    :criterion "a recursing first call runs the initializer once",
    :source (str "<?php function g() { echo \"i\"; return 1; }"
                 " function r($d) { static $n = g(); if ($d > 0) { r($d - 1); } return $n; }"
                 " echo r(3), r(0);"),
    :out "i11",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :static-initializer-unreached,
    :criterion "a declaration never reached never runs its initializer",
    :source (str "<?php function g() { echo \"init\"; return 1; }"
                 " function f($x) { if ($x) { return 0; } static $n = g(); return $n; }"
                 " echo f(1), \"end\";"),
    :out "0end",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :silence-error-reporting-zero,
    :criterion "under @ error_reporting(0) is seen, and the saved mask is restored after",
    :source (str "<?php function f() { error_reporting(0); echo error_reporting(); }"
                 " @f(); echo \"|\", error_reporting();"),
    :out "0|32767",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :silence-nested,
    :criterion "a nested @ sees the intersected mask and restores in order",
    :source (str "<?php function h($n, $s) { echo \"H\", error_reporting(), \";\"; return true; }"
                 " set_error_handler('h'); function f() { return $u; }"
                 " echo @(@f()), error_reporting();"),
    :out "H4437;32767",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :silence-intersects-saved-mask,
    :criterion "@ enters with saved & 4437; a mask the guarded code raised is kept",
    :source (str "<?php error_reporting(8191); echo @error_reporting(), \"|\";"
                 " echo error_reporting(), \"|\"; @error_reporting(32767); echo error_reporting();"),
    :out "4437|8191|32767",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :single-quote-raw-nul,
    :criterion "a raw NUL byte in a single-quoted string survives",
    :source (str "<?php echo strlen('a" "\u0000" "b'), '" "\u0000" "';"),
    :out (str "3" "\u0000"),
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-int-weak-mode,
    :criterion "weak-mode int coercion of scalars to intdiv: truncation, deprecations, boundaries",
    :source "<?php echo intdiv(1.0, 1); echo \"|\"; echo intdiv(1.5, 1); echo \"|\"; echo intdiv(-1.5, 1); echo \"|\"; echo intdiv(\"1.5\", 1); echo \"|\"; echo intdiv(\"1e3\", 1); echo \"|\"; echo intdiv(null, 1); echo \"|\"; echo intdiv(true, 1); echo \"|\"; echo intdiv(PHP_INT_MAX, 1); echo \"|\"; echo intdiv(PHP_INT_MIN, 1); echo \"|\"; echo intdiv(-9223372036854775808.0, 1); echo \"|\"; echo intdiv(9223372036854774784.0, 1);",
    :out "1|1|-1|1|1000|0|1|9223372036854775807|-9223372036854775808|-9223372036854775808|9223372036854774784",
    :diag [[8192 "Implicit conversion from float 1.5 to int loses precision"] [8192 "Implicit conversion from float -1.5 to int loses precision"] [8192 "Implicit conversion from float-string \"1.5\" to int loses precision"] [8192 "intdiv(): Passing null to parameter #1 ($num1) of type int is deprecated"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-int-inf,
    :criterion "intdiv rejects INF with a TypeError",
    :source "<?php echo intdiv(INF, 1);",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "intdiv(): Argument #1 ($num1) must be of type int, float given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-int-neg-inf,
    :criterion "intdiv rejects -INF with a TypeError",
    :source "<?php echo intdiv(-INF, 1);",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "intdiv(): Argument #1 ($num1) must be of type int, float given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-int-nan,
    :criterion "intdiv rejects NAN with a TypeError",
    :source "<?php echo intdiv(NAN, 1);",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "intdiv(): Argument #1 ($num1) must be of type int, float given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-int-1e30,
    :criterion "intdiv rejects 1e30 with a TypeError",
    :source "<?php echo intdiv(1e30, 1);",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "intdiv(): Argument #1 ($num1) must be of type int, float given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-int-neg-1e30,
    :criterion "intdiv rejects -1e30 with a TypeError",
    :source "<?php echo intdiv(-1e30, 1);",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "intdiv(): Argument #1 ($num1) must be of type int, float given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-int-2-63,
    :criterion "intdiv rejects 9.2233720368547758E+18 with a TypeError",
    :source "<?php echo intdiv(9.2233720368547758E+18, 1);",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "intdiv(): Argument #1 ($num1) must be of type int, float given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-int-float-int-max,
    :criterion "intdiv rejects (float)PHP_INT_MAX with a TypeError",
    :source "<?php echo intdiv((float)PHP_INT_MAX, 1);",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "intdiv(): Argument #1 ($num1) must be of type int, float given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-int-string-1e30,
    :criterion "intdiv rejects \"1e30\" with a TypeError",
    :source "<?php echo intdiv(\"1e30\", 1);",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "intdiv(): Argument #1 ($num1) must be of type int, string given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-int-string-abc,
    :criterion "intdiv rejects \"abc\" with a TypeError",
    :source "<?php echo intdiv(\"abc\", 1);",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "intdiv(): Argument #1 ($num1) must be of type int, string given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-int-string-5-apples,
    :criterion "intdiv rejects a leading-numeric string as the second argument",
    :source "<?php echo intdiv(1, \"5 apples\");",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "intdiv(): Argument #2 ($num2) must be of type int, string given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-float-null,
    :criterion "fmod(null) deprecates and is 0.0",
    :source "<?php var_dump(fmod(null, 2));",
    :out "float(0)
",
    :diag [[8192 "fmod(): Passing null to parameter #1 ($num1) of type float is deprecated"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-float-string-2,
    :criterion "fmod rejects a non-numeric string, second argument",
    :source "<?php fmod(1, \"1x\");",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "fmod(): Argument #2 ($num2) must be of type float, string given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-float-string-1,
    :criterion "fmod rejects a non-numeric string, first argument",
    :source "<?php fmod(\"x\", 1);",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "fmod(): Argument #1 ($num1) must be of type float, string given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :arg-int-handler,
    :criterion "deprecations route through a user error handler",
    :source "<?php function h($n, $s) { echo $n, \":\", $s, \";\"; return true; } set_error_handler('h'); echo intdiv(1.5, 1);",
    :out "8192:Implicit conversion from float 1.5 to int loses precision;1",
    :diag [],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :static-initializer-increment,
    :criterion "a static cell is incremented after its initializer ran once",
    :source "<?php function g() { echo 'init'; return 5; } function f() { static $n = g(); return ++$n; } echo f(), f();",
    :out "init67",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-key-coercion,
    :criterion "1, \"1\", 1.0 and true share a slot; non-canonical strings stay strings; null is \"\"",
    :source "<?php $a = []; $a[1] = 'a'; $a['1'] = 'b'; $a[1.0] = 'c'; $a[true] = 'd'; $a['01'] = 'e'; $a['-0'] = 'f'; $a[null] = 'g'; $a[false] = 'h'; $a['-5'] = 'i'; $a[' 1'] = 'j'; $a['1.5'] = 'k'; var_dump($a);",
    :out "array(8) {\n  [1]=>\n  string(1) \"d\"\n  [\"01\"]=>\n  string(1) \"e\"\n  [\"-0\"]=>\n  string(1) \"f\"\n  [\"\"]=>\n  string(1) \"g\"\n  [0]=>\n  string(1) \"h\"\n  [-5]=>\n  string(1) \"i\"\n  [\" 1\"]=>\n  string(1) \"j\"\n  [\"1.5\"]=>\n  string(1) \"k\"\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-key-float-deprecation,
    :criterion "a fractional float key truncates with a deprecation",
    :source "<?php $a = []; $a[1.5] = 'x'; $a[-1.5] = 'y'; var_dump($a);",
    :out "array(2) {\n  [1]=>\n  string(1) \"x\"\n  [-1]=>\n  string(1) \"y\"\n}\n",
    :diag [[8192 "Implicit conversion from float 1.5 to int loses precision"] [8192 "Implicit conversion from float -1.5 to int loses precision"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-key-illegal-offset,
    :criterion "an array as a key is a TypeError",
    :source "<?php $a = []; $a[[]] = 1;",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "Cannot access offset of type array on array"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-cow,
    :criterion "a copy is unaffected by writes to the other, also through a parameter",
    :source "<?php $a = [1, 2]; $b = $a; $b[] = 3; $b[0] = 9; var_dump($a, $b); function f($p) { $p[] = 1; return $p; } $x = [0]; $y = f($x); var_dump($x, $y);",
    :out "array(2) {\n  [0]=>\n  int(1)\n  [1]=>\n  int(2)\n}\narray(3) {\n  [0]=>\n  int(9)\n  [1]=>\n  int(2)\n  [2]=>\n  int(3)\n}\narray(1) {\n  [0]=>\n  int(0)\n}\narray(2) {\n  [0]=>\n  int(0)\n  [1]=>\n  int(1)\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-order-after-unset,
    :criterion "order after unset and re-add; append never reuses a freed key",
    :source "<?php $a = [0, 1, 2]; unset($a[1]); $a[1] = 9; var_dump($a); $a[] = 7; var_dump($a); $b = [0, 1, 2]; unset($b[2]); $b[] = 5; var_dump($b);",
    :out "array(3) {\n  [0]=>\n  int(0)\n  [2]=>\n  int(2)\n  [1]=>\n  int(9)\n}\narray(4) {\n  [0]=>\n  int(0)\n  [2]=>\n  int(2)\n  [1]=>\n  int(9)\n  [3]=>\n  int(7)\n}\narray(3) {\n  [0]=>\n  int(0)\n  [1]=>\n  int(1)\n  [3]=>\n  int(5)\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-next-index,
    :criterion "the next-index family: negative first key, string keys, unset",
    :source "<?php $a = []; $a[-5] = 1; $a[] = 2; var_dump($a); $b = [5 => 1]; $b[] = 2; $b['x'] = 3; $b[] = 4; var_dump($b); $c = [3 => 'a']; unset($c[3]); $c[] = 'b'; var_dump($c);",
    :out "array(2) {\n  [-5]=>\n  int(1)\n  [-4]=>\n  int(2)\n}\narray(4) {\n  [5]=>\n  int(1)\n  [6]=>\n  int(2)\n  [\"x\"]=>\n  int(3)\n  [7]=>\n  int(4)\n}\narray(1) {\n  [4]=>\n  string(1) \"b\"\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-next-index-saturated,
    :criterion "an append after PHP_INT_MAX is an Error",
    :source "<?php $d = [PHP_INT_MAX => 1]; $d[] = 2;",
    :out "",
    :diag [],
    :uncaught {:class "Error", :message "Cannot add element to the array as the next element is already occupied"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-eval-order,
    :criterion "keys, then the right side, then the write; a compound key runs once",
    :source "<?php function k($n) { echo \"k$n \"; return $n; } function v() { echo \"v \"; return 'V'; } $a = []; $a[k(1)][k(2)] = v(); echo \"|\"; $a[k(3)] .= v(); echo \"|\"; $a[k(4)]++; var_dump($a);",
    :out "k1 k2 v |k3 v |k4 array(3) {\n  [1]=>\n  array(1) {\n    [2]=>\n    string(1) \"V\"\n  }\n  [3]=>\n  string(1) \"V\"\n  [4]=>\n  int(1)\n}\n",
    :diag [[2 "Undefined array key 3"] [2 "Undefined array key 4"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-vivify-undefined,
    :criterion "a write to an undefined or null variable creates the array silently",
    :source "<?php $u[1][2] = 3; $n = null; $n['a'] = 1; $e = []; $e[][] = 1; var_dump($u, $n, $e);",
    :out "array(1) {\n  [1]=>\n  array(1) {\n    [2]=>\n    int(3)\n  }\n}\narray(1) {\n  [\"a\"]=>\n  int(1)\n}\narray(1) {\n  [0]=>\n  array(1) {\n    [0]=>\n    int(1)\n  }\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-vivify-false,
    :criterion "a write to false creates the array with a deprecation",
    :source "<?php $f = false; $f[] = 1; var_dump($f);",
    :out "array(1) {\n  [0]=>\n  int(1)\n}\n",
    :diag [[8192 "Automatic conversion of false to array is deprecated"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-write-scalar,
    :criterion "a write to an int is an Error",
    :source "<?php $i = 5; $i[0] = 1;",
    :out "",
    :diag [],
    :uncaught {:class "Error", :message "Cannot use a scalar value as an array"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-read-warnings,
    :criterion "undefined key and offset-on-type warnings",
    :source "<?php $a = ['x' => 1]; var_dump($a['y']); var_dump($a[3]); $n = null; var_dump($n[0]); $i = 5; var_dump($i['k']); $t = true; var_dump($t[0]); $f = 1.5; var_dump($f[0]); var_dump($u[0]);",
    :out "NULL\nNULL\nNULL\nNULL\nNULL\nNULL\nNULL\n",
    :diag [[2 "Undefined array key \"y\""] [2 "Undefined array key 3"] [2 "Trying to access array offset on null"] [2 "Trying to access array offset on int"] [2 "Trying to access array offset on true"] [2 "Trying to access array offset on float"] [2 "Undefined variable $u"] [2 "Trying to access array offset on null"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :string-offset-read,
    :criterion "string offset reads, negative offsets, isset",
    :source "<?php $s = 'abc'; var_dump($s[0], $s[-1], $s['1']); echo $s[5]; var_dump(isset($s[1]), isset($s[5]), isset($s['x']), isset($s['1']), empty($s[0]));",
    :out "string(1) \"a\"\nstring(1) \"c\"\nstring(1) \"b\"\nbool(true)\nbool(false)\nbool(false)\nbool(true)\nbool(false)\n",
    :diag [[2 "Uninitialized string offset 5"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :string-offset-write,
    :criterion "G-str-offset-write: string offset writes are refused",
    :source "<?php $s = 'abc'; $s[0] = 'x';",
    :out "",
    :diag [],
    :uncaught {:class "Error", :message "string offset writes are not supported yet (yang.php known gap G-str-offset-write)"},
    :gap "G-str-offset-write",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :string-offset-peek,
    :criterion "isset/empty on a string coerce bool and null silently and deprecate fractional floats",
    :source "<?php $s = 'abc'; var_dump(isset($s[true]), isset($s[false]), isset($s[null]), isset($s[1.0]), isset($s[1.5]), isset($s[-1]), isset($s[-4]), isset($s[3]), isset($s['1.5']), isset($s[' 1'])); $z = '0b'; var_dump(empty($z[true]), empty($z[0]), empty($s[true]));",
    :out "bool(true)\nbool(true)\nbool(true)\nbool(true)\nbool(true)\nbool(true)\nbool(false)\nbool(false)\nbool(false)\nbool(true)\nbool(false)\nbool(true)\nbool(false)\n",
    :diag [[8192 "Implicit conversion from float 1.5 to int loses precision"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-spread-renumber,
    :criterion "spread renumbers int keys",
    :source "<?php $a = [5 => 'x', 7 => 'y']; var_dump([...$a]);",
    :out "array(2) {\n  [0]=>\n  string(1) \"x\"\n  [1]=>\n  string(1) \"y\"\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-spread-mixed,
    :criterion "spread mixed with keys: string keys overwrite in place, int keys append",
    :source "<?php $a = ['p', 'q']; var_dump([0, ...$a, 'k' => 1, ...['k' => 2, 3 => 'z']]);",
    :out "array(5) {\n  [0]=>\n  int(0)\n  [1]=>\n  string(1) \"p\"\n  [2]=>\n  string(1) \"q\"\n  [\"k\"]=>\n  int(2)\n  [3]=>\n  string(1) \"z\"\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-spread-nested,
    :criterion "spread of empty and nested arrays",
    :source "<?php var_dump([...[]]); var_dump([1, ...[[2, 3]], ...[[]]]);",
    :out "array(0) {\n}\narray(3) {\n  [0]=>\n  int(1)\n  [1]=>\n  array(2) {\n    [0]=>\n    int(2)\n    [1]=>\n    int(3)\n  }\n  [2]=>\n  array(0) {\n  }\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-spread-scalar,
    :criterion "spreading a non-array is an Error",
    :source "<?php $a = [1]; var_dump([...$a, ...1]);",
    :out "",
    :uncaught {:class "Error", :message "Only arrays and Traversables can be unpacked"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :unset-dim-false,
    :criterion "unset on false is deprecated and a no-op",
    :source "<?php $a = false; unset($a[0]); var_dump($a); echo 'k';",
    :out "bool(false)\nk",
    :diag [[8192 "Automatic conversion of false to array is deprecated"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :unset-dim-true,
    :criterion "unset on true is an Error",
    :source "<?php $t = true; unset($t[0]);",
    :out "",
    :uncaught {:class "Error", :message "Cannot unset offset in a non-array variable"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-isset-empty-unset,
    :criterion "isset, empty and unset over nested keys, never vivifying",
    :source "<?php $a = ['x' => ['y' => 0, 'n' => null], 'z' => 1]; var_dump(isset($a['x']), isset($a['x']['y']), isset($a['x']['n']), isset($a['q']['r']), isset($a['x'], $a['z']), isset($a['x'], $a['nope']), isset($u[0]), empty($a['x']['y']), empty($a['z']), empty($a['q']), empty($u['k'])); unset($a['x']['y'], $a['nope']['deep'], $u['v'][1]); var_dump($a); var_dump(isset($u));",
    :out "bool(true)\nbool(true)\nbool(false)\nbool(false)\nbool(true)\nbool(false)\nbool(false)\nbool(true)\nbool(false)\nbool(true)\nbool(true)\narray(2) {\n  [\"x\"]=>\n  array(1) {\n    [\"n\"]=>\n    NULL\n  }\n  [\"z\"]=>\n  int(1)\n}\nbool(false)\n",
    :diag [[2 "Undefined variable $u"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-literals,
    :criterion "literals: duplicate keys, trailing comma, array(), nesting, dereference",
    :source "<?php $a = [1, 2, 'k' => 3, 4, 'k' => 5, 9 => 6, 7,]; var_dump($a); $b = array(); var_dump($b); $c = array('x' => array(1, array()), 2); var_dump($c); echo [10, 20, 30][1], array('p' => 'q')['p'];",
    :out "array(6) {\n  [0]=>\n  int(1)\n  [1]=>\n  int(2)\n  [\"k\"]=>\n  int(5)\n  [2]=>\n  int(4)\n  [9]=>\n  int(6)\n  [10]=>\n  int(7)\n}\narray(0) {\n}\narray(2) {\n  [\"x\"]=>\n  array(2) {\n    [0]=>\n    int(1)\n    [1]=>\n    array(0) {\n    }\n  }\n  [0]=>\n  int(2)\n}\n20q",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-compound-assign,
    :criterion "compound assignment, ??= and ++/-- on array elements",
    :source "<?php $a = ['n' => 1, 's' => 'a']; $a['n'] += 5; $a['s'] .= 'b'; $a['n'] *= 2; $a['m'] ??= 'dflt'; $a['m'] ??= 'other'; $a['n']++; --$a['n']; var_dump($a);",
    :out "array(3) {\n  [\"n\"]=>\n  int(12)\n  [\"s\"]=>\n  string(2) \"ab\"\n  [\"m\"]=>\n  string(4) \"dflt\"\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-null-coalesce,
    :criterion "?? over nested keys never warns",
    :source "<?php $a = ['x' => ['y' => 1]]; echo $a['x']['y'] ?? 'd', $a['x']['z'] ?? 'd', $a['q']['z'] ?? 'd', $u['k'] ?? 'd', $a['x']['y']['w'] ?? 'd';",
    :out "1dddd",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-to-string,
    :criterion "an array in string context is Array with a warning",
    :source "<?php echo 'x' . [1] . 'y';",
    :out "xArrayy",
    :diag [[2 "Array to string conversion"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-operators,
    :criterion "array union, comparison, and casts",
    :source "<?php var_dump([1, 2] + [5, 6, 7]); var_dump([1] == [1], [1] === ['1'], ['a' => 1, 'b' => 2] == ['b' => 2, 'a' => 1], ['a' => 1, 'b' => 2] === ['b' => 2, 'a' => 1], [] == false, [0] == true); var_dump((bool)[], (bool)[0], (int)[], (int)[3]);",
    :out "array(3) {\n  [0]=>\n  int(1)\n  [1]=>\n  int(2)\n  [2]=>\n  int(7)\n}\nbool(true)\nbool(false)\nbool(true)\nbool(false)\nbool(true)\nbool(true)\nbool(false)\nbool(true)\nint(0)\nint(1)\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-plus-int,
    :criterion "array + int is a TypeError",
    :source "<?php [] + 1;",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "Unsupported operand types: array + int"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-assign-value,
    :criterion "an array element assignment yields the assigned value",
    :source "<?php $x = ($a[] = 5) + 1; $r = ($a['k'] = 7); var_dump($x, $r, $a);",
    :out "int(6)\nint(7)\narray(2) {\n  [0]=>\n  int(5)\n  [\"k\"]=>\n  int(7)\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-local-loop,
    :criterion "arrays in a function body, built in a loop",
    :source "<?php function f() { $a = []; for ($i = 0; $i < 3; $i++) { $a[] = $i * 2; } $a[1] += 1; return $a; } var_dump(f());",
    :out "array(3) {\n  [0]=>\n  int(0)\n  [1]=>\n  int(3)\n  [2]=>\n  int(4)\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :array-dim-of-string-dim,
    :criterion "a string element is read by offset",
    :source "<?php $a = ['x' => 'str']; echo $a['x'][0], $a['x'][2];",
    :out "sr",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :foreach-value,
    :criterion "foreach by value; the value variable survives the loop",
    :source "<?php foreach ([1, 2, 3] as $v) echo $v; echo $v;",
    :out "1233",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :foreach-key-value,
    :criterion "foreach with key and value over string and int keys",
    :source "<?php $a = ['x' => 1, 'y' => 2, 5 => 3]; foreach ($a as $k => $v) { echo \"$k=$v,\"; }",
    :out "x=1,y=2,5=3,",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :foreach-snapshot,
    :criterion "body writes to the source never affect the iteration",
    :source "<?php $a = [1, 2, 3]; foreach ($a as $v) { $a[] = $v; unset($a[0]); echo $v; } echo count($a);",
    :out "1235",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :foreach-nested-escapes,
    :criterion "break 2 and continue 2 across nested foreach",
    :source "<?php foreach ([1, 2, 3] as $i) { foreach ([1, 2, 3] as $j) { if ($j == 2) continue 2; if ($i == 3) break 2; echo $i, $j, ','; } }",
    :out "11,21,",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :foreach-non-array,
    :criterion "foreach over a non-array warns and runs zero times",
    :source "<?php foreach (5 as $v) echo 'x'; echo 'done';",
    :out "done",
    :diag [[2 "foreach() argument must be of type array|object, int given"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :foreach-alt-syntax,
    :criterion "foreach in the alternative syntax",
    :source "<?php foreach ([1, 2] as $k => $v): echo $k, $v; endforeach;",
    :out "0112",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :foreach-keyed-target,
    :criterion "foreach writes through a keyed value target",
    :source "<?php foreach ([7, 8] as $k => $r['last']) {} echo $k, $r['last'];",
    :out "18",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :foreach-in-function,
    :criterion "foreach inside a function body",
    :source "<?php function s($xs) { $t = 0; foreach ($xs as $x) { $t += $x; } return $t; } echo s([1, 2, 3, 4]);",
    :out "10",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :builtin-count-keys-values,
    :criterion "count, array_keys and array_values",
    :source "<?php $a = ['x' => 1, 5 => 'b', 'c']; echo count($a), '|'; var_dump(array_keys($a)); var_dump(array_values([3 => 'p', 'q' => 'r']));",
    :out "3|array(3) {\n  [0]=>\n  string(1) \"x\"\n  [1]=>\n  int(5)\n  [2]=>\n  int(6)\n}\narray(2) {\n  [0]=>\n  string(1) \"p\"\n  [1]=>\n  string(1) \"r\"\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :builtin-array-merge,
    :criterion "array_merge renumbers integer keys and overwrites string keys",
    :source "<?php var_dump(array_merge([5 => 'a', 'k' => 1], [9 => 'b', 'k' => 2]));",
    :out "array(3) {\n  [0]=>\n  string(1) \"a\"\n  [\"k\"]=>\n  int(2)\n  [1]=>\n  string(1) \"b\"\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :builtin-exists-in-array,
    :criterion "array_key_exists and in_array",
    :source "<?php var_dump(array_key_exists('1', [1 => 0]), array_key_exists('z', []), in_array('1', [1, 2]), in_array('1', [1, 2], true), in_array(null, [0]));",
    :out "bool(true)\nbool(false)\nbool(true)\nbool(false)\nbool(true)\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :builtin-push-pop,
    :criterion "array_push and array_pop; pop releases the next index",
    :source "<?php $a = [1]; echo array_push($a, 2, 3), array_pop($a); $a[] = 9; var_dump($a);",
    :out "33array(3) {\n  [0]=>\n  int(1)\n  [1]=>\n  int(2)\n  [2]=>\n  int(9)\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :builtin-shift-unshift,
    :criterion "array_shift and array_unshift renumber integer keys",
    :source "<?php $a = [3 => 'a', 'k' => 'b', 7 => 'c']; echo array_shift($a); array_unshift($a, 'z'); var_dump($a);",
    :out "aarray(3) {\n  [0]=>\n  string(1) \"z\"\n  [\"k\"]=>\n  string(1) \"b\"\n  [1]=>\n  string(1) \"c\"\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :builtin-by-ref-nested,
    :criterion "a by-reference builtin on a nested location",
    :source "<?php $a = [[3, 1]]; array_push($a[0], 0); sort($a[0]); foreach ($a[0] as $k => $v) echo \"$k=$v,\";",
    :out "0=0,1=1,2=3,",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :builtin-by-ref-non-variable,
    :criterion "a by-reference builtin given a non-variable notices",
    :source "<?php function g() { return [3, 1]; } echo sort(g());",
    :out "1",
    :diag [[8 "Only variables should be passed by reference"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :builtin-count-type-error,
    :criterion "count of a scalar is a TypeError",
    :source "<?php count(5);",
    :out "",
    :diag [],
    :uncaught {:class "TypeError", :message "count(): Argument #1 ($value) must be of type Countable|array, int given"},
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :sort-basic,
    :criterion "sort and rsort renumber",
    :source "<?php $a = [3, 1, 2]; sort($a); foreach ($a as $k => $v) echo \"$k=$v,\"; rsort($a); foreach ($a as $k => $v) echo \"$k=$v,\";",
    :out "0=1,1=2,2=3,0=3,1=2,2=1,",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :sort-asort-stable,
    :criterion "asort and arsort keep keys and are stable",
    :source "<?php $a = ['a' => 2, 'b' => 1, 'c' => 2, 'd' => 1]; asort($a); foreach ($a as $k => $v) echo \"$k=$v,\"; arsort($a); foreach ($a as $k => $v) echo \"$k=$v,\";",
    :out "b=1,d=1,a=2,c=2,a=2,c=2,b=1,d=1,",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :sort-ksort,
    :criterion "ksort and krsort order by key",
    :source "<?php $a = ['b' => 1, 'a' => 2, 'c' => 3]; ksort($a); foreach ($a as $k => $v) echo \"$k=$v,\"; krsort($a); foreach ($a as $k => $v) echo \"$k=$v,\";",
    :out "a=2,b=1,c=3,c=3,b=1,a=2,",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :sort-usort,
    :criterion "usort calls a named comparator",
    :source "<?php function d($x, $y) { return $y <=> $x; } $a = [3, 1, 2]; usort($a, 'd'); foreach ($a as $k => $v) echo \"$k=$v,\";",
    :out "0=3,1=2,2=1,",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :sort-thousand,
    :criterion "a 1000 element sort",
    :source "<?php $a = []; for ($i = 0; $i < 1000; $i++) { $a[] = ($i * 7919) % 1000; } sort($a); echo $a[0], ',', $a[1], ',', $a[999], ',', count($a);",
    :out "0,1,999,1000",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :foreach-key-target-order,
    :criterion "foreach stores the value before the key (Zend order)",
    :source "<?php foreach ([5 => 7] as $k => $a[$k]) {} var_dump($a, $k);",
    :out "array(1) {\n  [\"\"]=>\n  int(7)\n}\nint(5)\n",
    :diag [[2 "Undefined variable $k"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :foreach-key-expr-write,
    :criterion "a foreach target key expression binds before the holder is fetched",
    :source "<?php foreach ([7] as $a[$a[0] = 1]) {} var_dump($a);",
    :out "array(2) {\n  [0]=>\n  int(1)\n  [1]=>\n  int(7)\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :foreach-key-expr-once,
    :criterion "a foreach target key expression runs once per iteration",
    :source "<?php function k() { echo \"k\"; return 0; } foreach ([1, 2] as $a[k()]) {} echo count($a);",
    :out "kk1",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :by-ref-late-read,
    :criterion "a by-reference builtin reads its location after later arguments run",
    :source "<?php $a = [[1]]; array_push($a[0], $a[0][] = 2); var_dump($a);",
    :out "array(1) {\n  [0]=>\n  array(3) {\n    [0]=>\n    int(1)\n    [1]=>\n    int(2)\n    [2]=>\n    int(2)\n  }\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :by-ref-late-read-sibling,
    :criterion "a later argument writing a sibling key survives the by-reference write",
    :source "<?php $a = [[1], 0]; array_push($a[0], $a[1] = 5); var_dump($a);",
    :out "array(2) {\n  [0]=>\n  array(2) {\n    [0]=>\n    int(1)\n    [1]=>\n    int(5)\n  }\n  [1]=>\n  int(5)\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :sort-mixed-scalars,
    :criterion "sort over mixed scalars uses the loose comparison",
    :source "<?php $a = [10, \"9\", \"abc\", 1.5, true, null]; sort($a); var_dump($a);",
    :out "array(6) {\n  [0]=>\n  NULL\n  [1]=>\n  float(1.5)\n  [2]=>\n  string(1) \"9\"\n  [3]=>\n  int(10)\n  [4]=>\n  string(3) \"abc\"\n  [5]=>\n  bool(true)\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :sort-bool-comparator,
    :criterion "usort with a bool comparator retries swapped and deprecates once",
    :source "<?php function c($x, $y) { return $x > $y; } $a = [3, 1, 2]; usort($a, \"c\"); foreach ($a as $k => $v) echo \"$k=$v,\";",
    :out "0=1,1=2,2=3,",
    :diag [[8192 "usort(): Returning bool from comparison function is deprecated, return an integer less than, equal to, or greater than zero"]],
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :sort-ksort-mixed-keys,
    :criterion "ksort over mixed int and string keys",
    :source "<?php $a = [10 => \"a\", \"9\" => \"b\", \"x\" => \"c\", \"A\" => \"d\"]; ksort($a); foreach ($a as $k => $v) echo \"$k=$v,\";",
    :out "9=b,10=a,A=d,x=c,",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :sort-uasort,
    :criterion "uasort keeps keys",
    :source "<?php function d($x, $y) { return $x <=> $y; } $a = [\"p\" => 3, \"q\" => 1, \"r\" => 2]; uasort($a, \"d\"); foreach ($a as $k => $v) echo \"$k=$v,\";",
    :out "q=1,r=2,p=3,",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :sort-uksort,
    :criterion "uksort orders by key through the comparator",
    :source "<?php function d($x, $y) { return $x <=> $y; } $a = [\"b\" => 1, \"c\" => 2, \"a\" => 3]; uksort($a, \"d\"); foreach ($a as $k => $v) echo \"$k=$v,\";",
    :out "a=3,b=1,c=2,",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :sort-rsort-stable,
    :criterion "rsort keeps equal values in their original order",
    :source "<?php $a = [1, 1.0, \"1\", 2]; rsort($a); var_dump($a);",
    :out "array(4) {\n  [0]=>\n  int(2)\n  [1]=>\n  int(1)\n  [2]=>\n  float(1)\n  [3]=>\n  string(1) \"1\"\n}\n",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :foreach-sort-source-inside,
    :criterion "sort of the source inside the loop leaves the by-value iteration alone",
    :source "<?php $a = [3, 1, 2]; foreach ($a as $v) { sort($a); echo $v; }",
    :out "312",
    :oracle "php-8.3.35 shiva 2026-10-25"}
   {:id :sort-inconsistent-cmp-departure,
    :criterion "a 0/1 integer comparator is not a total preorder; the merge sort departs from zend_sort (G-sort-inconsistent-cmp)",
    :source "<?php function c($a, $b) { return (int)($a > $b); } $a = [3, 2, 1]; usort($a, \"c\"); var_dump($a);",
    :out "array(3) {\n  [0]=>\n  int(1)\n  [1]=>\n  int(2)\n  [2]=>\n  int(3)\n}\n",
    :departure {:gap "G-sort-inconsistent-cmp", :zend "array(3) {\n  [0]=>\n  int(2)\n  [1]=>\n  int(1)\n  [2]=>\n  int(3)\n}\n"},
    :diag [],
    :oracle :departure}])


;; =============================================================================
;; The scalar loose-comparison matrix (plan section 4, exit criterion 6)
;; =============================================================================

(def matrix-values
  "The scalar subset of the section 3.5 matrix, as PHP source. The `[]` and
   `[0]` columns are P2's and the object columns P4's."
  ["null" "true" "false" "0" "1" "-1" "\"0\"" "\"\"" "\"1\"" "\"01\"" "\"1e1\""
   "\"10\"" "\"abc\"" "\" 1\"" "\"1 \"" "0.0" "NAN" "INF" "[]" "[0]"])


(def matrix-slice
  "The slice that owns each matrix column; later slices add columns and never
   regenerate the cells of earlier ones."
  (into (zipmap matrix-values (repeat :p1)) [["[]" :p2] ["[0]" :p2]]))


(def equal-matrix
  "`==` over every pair of `matrix-values`: row `a`, column `b`, `1` for true.
   `:oracle \"php-8.3.35 shiva 2026-10-25\"`: pinned from the PHP 8 comparison
   rules, with the cells below checked by hand against them."
  ["10110001000000010010"
   "01001100111111101101"
   "10110011000000010010"
   "10110010000000010000"
   "01001000110001100000"
   "01000100000000000000"
   "00110010000000010000"
   "10100001000000000000"
   "01001000110001100000"
   "01001000110001100000"
   "01000000001100000000"
   "01000000001100000000"
   "01000000000010000000"
   "01001000110001100000"
   "01001000110001100000"
   "10110010000000010000"
   "01000000000000000000"
   "01000000000000000100"
   "10100000000000000010"
   "01000000000000000001"])


(def spaceship-matrix
  "`<=>` over every pair, `<` `=` `>` for -1, 0, 1. `:oracle \"php-8.3.35 shiva 2026-10-25\"`."
  ["=<==<<<=<<<<<<<=<<=<"
   ">=>>==>>=======>==>="
   "=<==<<==<<<<<<<=<<=<"
   "=<==<>=><<<<<<<=><<<"
   ">=>>=>>>==<<<==>><<<"
   ">=><<=<><<<<<<<<><<<"
   "><==<>=><<<<<<<=><<<"
   "=<=<<<<=<<<<<<<<><<<"
   ">=>>=>>>==<<<==>><<<"
   ">=>>=>>>==<<<==>><<<"
   ">=>>>>>>>>==<>>>><<<"
   ">=>>>>>>>>==<>>>><<<"
   ">=>>>>>>>>>>=>>>>><<"
   ">=>>=>>>==<<<==>><<<"
   ">=>>=>>>==<<<==>><<<"
   "=<==<>=><<<<<<<=><<<"
   ">=>>>>>>>>>>>>>>>><<"
   ">=>>>>>>>>>><>>>>=<<"
   "=<=>>>>>>>>>>>>>>>=<"
   ">=>>>>>>>>>>>>>>>>>="])


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
   ["false" "\"0\"" true 0] ["[]" "false" true 0] ["[]" "null" true 0]
   ["[0]" "true" true 0] ["[]" "1" false 1] ["[0]" "\"abc\"" false 1]
   ["[]" "[0]" false -1]])


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
