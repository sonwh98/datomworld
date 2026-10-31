# Four runtime-reader bugs in ClojureDart, plus invalid-token validation

## Version, scope and reproduction

Verified by compiling and running every input below on the Dart VM at
upstream HEAD requested for this report:

- ClojureDart: 0cbd5408906ccea0c50e0eb264b273d3a2c64a30 (2026-10-01).
- Dart SDK 3.13.3 stable, macOS arm64.
- Clojure CLI 1.12.4.1602; JVM comparison runtime Clojure 1.12.4.
- Dart calls use cljd.edn/read-string, an alias of cljd.reader/read-string.
- JVM EDN means clojure.edn/read-string; JVM core means
  clojure.core/read-string, evaluated in namespace user.

The four original runtime-reader bugs remain, numbered 1, 2, 3 and 5
for continuity. Confirmed bonus Bug 6 is a fifth current finding.
The former list-metadata Bug 4 is omitted: upstream commits 0a33cbb
and 23738b4 replaced the list constructor's literal seed with
-EMPTY-LIST. That issue is fixed and should not be filed.

## Optional introduction variants (choose one before posting)

### Unnamed variant

We found these runtime-reader differences while checking JVM/Dart reader
parity. A generic affected use is a Datalog query with a rule-set input
symbol, such as [:in % ...].

### Named variant

We found these runtime-reader differences while checking JVM/Dart reader
parity for datom.world. A generic affected use is a Datalog query with a
rule-set input symbol, such as [:in % ...].

## Bug 1 - percent dispatch loses the initial character

Severity: high; valid symbols silently become different values.

Observed Dart values:

    "%1"   -> 1
    "%x"   -> x
    "%&"   -> &
    "%x/y" -> x/y

Both JVM readers preserve each of those symbols. Other Dart outcomes:

    "%/foo"
      throws FormatException: Invalid token: /foo
    "%"
      throws RangeError (index): Invalid value: Only valid value is 0: 1
    "[% %1 %& (f %) {:k %}]"
      throws FormatException: Unexpected closing square bracket.

Both JVM readers accept all three inputs, returning respectively
%/foo, %, and [% %1 %& (f %) {:k %}].

Inside anonymous functions, Dart outcomes are:

    "#(f %)"
      throws FormatException: arg literal must be %, %& or %integer
    "#(f %&)"
      throws FormatException: arg literal must be %, %& or %integer
    "#(+ %1 1)"
      -> (fn* [p1__1] (+ p1__1 1))

JVM core accepts all three: the first uses a single argument, the second
uses a rest argument, and the third uses a single argument. Generated
argument names vary. JVM EDN throws java.lang.RuntimeException:
No dispatch macro for: ( for all three; #() is not EDN.

Expected: preserve percent-prefixed symbols outside #(); recognize bare
% and %& as argument 1 and the rest argument inside #().

Source cause: in clj/src/cljd/reader.cljd, macros maps % to
read-anon-arg. dispatch passes the position after %, but read-token
unconditionally seeds its buffer from the character at that position.
The leading % is lost; a bare % consumes the next character even when
it is whitespace or a closing delimiter, or indexes past the string's
end at EOF. The "%" and "%&" case branches inside read-anon-arg cannot
match the intended tokens because the dispatch character is missing.
Clojure's LispReader.ArgReader supplies the initial '%' character to
readToken; this reader needs the corresponding initial-character step.

## Bug 2 - duplicate map keys and set elements are accepted

Severity: low to medium; validation leniency hides duplicate literals.

    Dart input       Dart result
    "{:a 1 :a 2}"   {:a 2}
    "#{1 1}"        #{1}
    "#{x x}"        #{x}

Both JVM readers throw java.lang.IllegalArgumentException with the
respective full messages:

    Duplicate key: :a
    Duplicate key: 1
    Duplicate key: x

Expected: reject duplicate literal keys/elements if JVM reader parity
is intended. Maps keep the last value and sets collapse duplicates.

Source cause: reader.cljd's macros map entry uses (apply hash-map kvs),
and its dispatch-macros set entry uses set. Neither checks duplicates.
mk-read-coll gathers items, but the constructors are in those tables.
This is an acceptance-policy difference, not a claim of general data
corruption. Is cljd.edn intended to enforce EDN/JVM literal validation?

## Bug 3 - EDN alias exposes resolver-dependent full-reader forms

Severity: low; missing EDN mode / unclear runtime error contract.

All these Dart calls, without binding cljd.reader/*resolver*, throw:

    Exception: No extension of protocol IResolver found for type Null.

    "`x"
    "`x/foo"
    "`(a b ~c)"
    "`{:k v}"
    "::foo"

JVM EDN throws java.lang.RuntimeException for each syntax-quote input:

    Invalid leading character: `

For "::foo", JVM EDN throws java.lang.RuntimeException:

    Invalid token: ::foo

JVM core accepts the four syntax-quotes, resolving unqualified symbols
in user and preserving x/foo; it reads "::foo" as :user/foo.

Expected: clarify whether cljd.edn is an EDN-only API. If so, reject
these non-EDN forms with reader errors. If full-reader behavior is
intended, document the resolver requirement and provide a clear error
when it is absent. Full syntax-quote support itself is intentional.

Source cause: *resolver* defaults to nil. syntax-quote calls IResolver
methods; interpret-token also calls currentNS for ::foo. cljd.edn
aliases the full runtime reader without binding a resolver. The
compiler binds cljd-resolver through with-cljd-reader at compile time;
that binding does not accompany later Dart runtime reads. Upstream
reader tests explicitly bind a fake resolver for syntax-quote and
auto-resolved keywords, so the report does not presume resolution
without a binding is supported.

## Bug 5 - whitespace before a closing delimiter rejects valid EDN

Severity: medium; ordinary valid EDN fails to read.

    "[1 2 ]", "[1 2\t]", "[1 2\n]", "[1 2,]"
      throw FormatException: Unexpected closing square bracket.
    "{:a 1 }", "#{1 }"
      throw FormatException: Unexpected closing curly brace.
    "(1 )"
      throws FormatException: Unexpected closing parenthesis.

Both JVM readers accept each input, yielding [1 2], {:a 1}, #{1}, or
(1) as appropriate. Controls work on Dart and both JVM readers:

    "[ ]"     -> []
    "[1 2] "  -> [1 2]

Expected: skip spaces, tabs, newlines and commas before a closer after
any element, as before the first element.

Source cause: mk-read-coll's do-read-coll loop initially calls
skip-space, then recurs with the raw position after an element. Its
closer check therefore sees whitespace; read skips that whitespace and
dispatches the closer as an unexpected closing delimiter. A fix should
also preserve the -1 result used for chunk boundaries.

## Bug 6 - invalid-token checks construct exceptions without throwing

Severity: low to medium; invalid symbol tokens are silently accepted.

    Dart input    Dart result (symbol)
    "foo:"        foo:
    "a::b"        a::b
    "x:/y"        x:/y

For each input, both JVM readers throw java.lang.RuntimeException with
these respective full messages:

    Invalid token: foo:
    Invalid token: a::b
    Invalid token: x:/y

Control ":foo" returns :foo on Dart and both JVM readers.
Expected: throw a reader exception for invalid tokens.

Source cause: interpret-token's validation when constructs a
FormatException without throw (line 131 at the old pin; unchanged at
this HEAD). Execution confirms this discarded exception permits the
three invalid tokens above to continue to symbol construction.
The unresolved auto-keyword alias branch also constructs rather than
throws FormatException (line 135 at the old pin). That second branch
was observed in source, but is not claimed as an executed repro here;
::foo with the default nil resolver fails earlier, as Bug 3 records.

## Complete Dart probe

Create a fresh directory named reader_probe under /tmp. Put these files
in it, with Dart 3.13.3 and Clojure CLI 1.12.4.1602 on PATH. The :sha
pins ClojureDart independently of any application project.

### deps.edn

```clojure
{:deps {tensegritics/clojuredart
        {:git/url "https://github.com/tensegritics/ClojureDart.git"
         :sha "0cbd5408906ccea0c50e0eb264b273d3a2c64a30"}}
 :paths ["src"]
 :aliases {:cljd {:main-opts ["-m" "cljd.build"]}}
 :cljd/opts {:kind :dart
             :main probe.core}}
```

### src/probe/core.cljd

```clojure
;; Reader probe for tensegritics/ClojureDart.
;; Runs each repro through cljd.edn/read-string (which is
;; cljd.reader/read-string) on the Dart VM and prints either the value
;; or the thrown exception's type and message.
(ns probe.core
  (:require [cljd.edn :as edn]))

(defn attempt [s]
  (println "input:" (pr-str s))
  (try
    (println "  OK    ->" (pr-str (edn/read-string s)))
    (catch dynamic e
      (println "  THROW ->" (.toString e)))))

(defn -main-rows [title rows]
  (println "=== " title)
  (doseq [s rows] (attempt s)))

(defn main []
  (println "ClojureDart reader probe")
  (-main-rows "bug 1: % outside #()"
    ["%1" "%x" "%&" "%x/y" "%/foo" "%"
     "[% %1 %& (f %) {:k %}]"])
  (-main-rows "bug 1: % inside #()"
    ["#(f %)" "#(f %&)" "#(+ %1 1)"])
  (-main-rows "bug 2: duplicate keys/elements"
    ["{:a 1 :a 2}" "#{1 1}" "#{x x}"])
  (-main-rows "bug 3: syntax-quote / :: keywords, no resolver"
    ["`x" "`x/foo" "`(a b ~c)" "`{:k v}" "::foo"])
  (-main-rows "bug 5: whitespace before closing delimiter"
    ["[1 2 ]" "[1 2\t]" "[1 2\n]" "[1 2,]"
     "{:a 1 }" "(1 )" "#{1 }" "[ ]" "[1 2] "])
  (-main-rows "bonus: invalid tokens"
    ["foo:" ":foo" "a::b" "x:/y"]))
```

### Commands

```sh
cd /tmp/reader_probe
clojure -M:cljd init
clojure -M:cljd compile
dart lib/cljd-out/probe/core.dart
```

The init command creates pubspec.yaml and the Dart project scaffolding.
The compile command emits the namespace above, whose main is directly
run by the final command. Each input is read once and caught separately.

## Observed Dart output

```text
ClojureDart reader probe
===  bug 1: % outside #()
input: "%1"
  OK    -> 1
input: "%x"
  OK    -> x
input: "%&"
  OK    -> &
input: "%x/y"
  OK    -> x/y
input: "%/foo"
  THROW -> FormatException: Invalid token: /foo
input: "%"
  THROW -> RangeError (index): Invalid value: Only valid value is 0: 1
input: "[% %1 %& (f %) {:k %}]"
  THROW -> FormatException: Unexpected closing square bracket.
===  bug 1: % inside #()
input: "#(f %)"
  THROW -> FormatException: arg literal must be %, %& or %integer
input: "#(f %&)"
  THROW -> FormatException: arg literal must be %, %& or %integer
input: "#(+ %1 1)"
  OK    -> (fn* [p1__1] (+ p1__1 1))
===  bug 2: duplicate keys/elements
input: "{:a 1 :a 2}"
  OK    -> {:a 2}
input: "#{1 1}"
  OK    -> #{1}
input: "#{x x}"
  OK    -> #{x}
===  bug 3: syntax-quote / :: keywords, no resolver
input: "`x"
  THROW -> Exception: No extension of protocol IResolver found for type Null.
input: "`x/foo"
  THROW -> Exception: No extension of protocol IResolver found for type Null.
input: "`(a b ~c)"
  THROW -> Exception: No extension of protocol IResolver found for type Null.
input: "`{:k v}"
  THROW -> Exception: No extension of protocol IResolver found for type Null.
input: "::foo"
  THROW -> Exception: No extension of protocol IResolver found for type Null.
===  bug 5: whitespace before closing delimiter
input: "[1 2 ]"
  THROW -> FormatException: Unexpected closing square bracket.
input: "[1 2\t]"
  THROW -> FormatException: Unexpected closing square bracket.
input: "[1 2\n]"
  THROW -> FormatException: Unexpected closing square bracket.
input: "[1 2,]"
  THROW -> FormatException: Unexpected closing square bracket.
input: "{:a 1 }"
  THROW -> FormatException: Unexpected closing curly brace.
input: "(1 )"
  THROW -> FormatException: Unexpected closing parenthesis.
input: "#{1 }"
  THROW -> FormatException: Unexpected closing curly brace.
input: "[ ]"
  OK    -> []
input: "[1 2] "
  OK    -> [1 2]
===  bonus: invalid tokens
input: "foo:"
  OK    -> foo:
input: ":foo"
  OK    -> :foo
input: "a::b"
  OK    -> a::b
input: "x:/y"
  OK    -> x:/y
```

## JVM comparison commands and full output

Save the following as jvm.clj in the same directory and run
`clojure -M jvm.clj`. This explicitly compares both JVM APIs.

```clojure
(require '[clojure.edn :as edn])
(def inputs ["%1" "%x" "%&" "%x/y" "%/foo" "%"
 "[% %1 %& (f %) {:k %}]" "#(f %)" "#(f %&)" "#(+ %1 1)"
 "{:a 1 :a 2}" "#{1 1}" "#{x x}" "`x" "`x/foo" "`(a b ~c)"
 "`{:k v}" "::foo" "[1 2 ]" "[1 2\t]" "[1 2\n]" "[1 2,]"
 "{:a 1 }" "(1 )" "#{1 }" "[ ]" "[1 2] "
 "foo:" ":foo" "a::b" "x:/y"])
(println (clojure-version))
(doseq [s inputs]
 (prn s)
 (doseq [[label f] [["core" read-string] ["edn" edn/read-string]]]
  (try (println label (pr-str (f s)))
   (catch Exception e
    (println label (.getName (class e)) (pr-str (.getMessage e)))))))
```

```text
1.12.4
"%1"
core %1
edn %1
"%x"
core %x
edn %x
"%&"
core %&
edn %&
"%x/y"
core %x/y
edn %x/y
"%/foo"
core %/foo
edn %/foo
"%"
core %
edn %
"[% %1 %& (f %) {:k %}]"
core [% %1 %& (f %) {:k %}]
edn [% %1 %& (f %) {:k %}]
"#(f %)"
core (fn* [p1__178#] (f p1__178#))
edn java.lang.RuntimeException "No dispatch macro for: ("
"#(f %&)"
core (fn* [& rest__179#] (f rest__179#))
edn java.lang.RuntimeException "No dispatch macro for: ("
"#(+ %1 1)"
core (fn* [p1__180#] (+ p1__180# 1))
edn java.lang.RuntimeException "No dispatch macro for: ("
"{:a 1 :a 2}"
core java.lang.IllegalArgumentException "Duplicate key: :a"
edn java.lang.IllegalArgumentException "Duplicate key: :a"
"#{1 1}"
core java.lang.IllegalArgumentException "Duplicate key: 1"
edn java.lang.IllegalArgumentException "Duplicate key: 1"
"#{x x}"
core java.lang.IllegalArgumentException "Duplicate key: x"
edn java.lang.IllegalArgumentException "Duplicate key: x"
"`x"
core (quote user/x)
edn java.lang.RuntimeException "Invalid leading character: `"
"`x/foo"
core (quote x/foo)
edn java.lang.RuntimeException "Invalid leading character: `"
"`(a b ~c)"
core (clojure.core/seq (clojure.core/concat (clojure.core/list (quote user/a))
(clojure.core/list (quote user/b)) (clojure.core/list c)))
edn java.lang.RuntimeException "Invalid leading character: `"
"`{:k v}"
core (clojure.core/apply clojure.core/hash-map (clojure.core/seq
(clojure.core/concat (clojure.core/list :k) (clojure.core/list (quote
user/v)))))
edn java.lang.RuntimeException "Invalid leading character: `"
"::foo"
core :user/foo
edn java.lang.RuntimeException "Invalid token: ::foo"
"[1 2 ]"
core [1 2]
edn [1 2]
"[1 2\t]"
core [1 2]
edn [1 2]
"[1 2\n]"
core [1 2]
edn [1 2]
"[1 2,]"
core [1 2]
edn [1 2]
"{:a 1 }"
core {:a 1}
edn {:a 1}
"(1 )"
core (1)
edn (1)
"#{1 }"
core #{1}
edn #{1}
"[ ]"
core []
edn []
"[1 2] "
core [1 2]
edn [1 2]
"foo:"
core java.lang.RuntimeException "Invalid token: foo:"
edn java.lang.RuntimeException "Invalid token: foo:"
":foo"
core :foo
edn :foo
"a::b"
core java.lang.RuntimeException "Invalid token: a::b"
edn java.lang.RuntimeException "Invalid token: a::b"
"x:/y"
core java.lang.RuntimeException "Invalid token: x:/y"
edn java.lang.RuntimeException "Invalid token: x:/y"
```

## Duplicate-issue search and closing scope

On 2026-10-01 UTC, searched all open and closed issues in
[tensegritics/ClojureDart](https://github.com/tensegritics/ClojureDart).
The GitHub REST issues listing returned 383 entries across four pages,
including 232 issues after excluding pull requests. Checked titles and
bodies for reader, read-string, EDN, syntax-quote, duplicate keys, percent
arguments, whitespace, and invalid tokens. No duplicate issue was found.
[Issue #174](https://github.com/tensegritics/ClojureDart/issues/174),
"Reader is slow and synchronous read-string should be supported",
concerns performance and synchronous/chunked support, not these cases.
This search establishes no known duplicate, not a guarantee that none
exists under different wording or outside GitHub issues.

These findings concern runtime reads. Compilation succeeded with this
probe; its repro strings are interpreted later on Dart. The JVM-side
compile-time reader is distinct. The JVM core comparisons cover Bugs
1, 2, 3, 5 and 6; they do not imply cljd.edn must accept non-EDN syntax.
The EDN API's intended contract matters especially for Bugs 2 and 3.
