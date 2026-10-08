Both facts verified: the checkout is at `c56e2dc1` (five commits past the repro pin; host Clojure 1.11.1 declared in its deps.edn, no tools.reader), and every reader.cljd code-path reference checks out — `(def ^:dynamic *resolver* nil)` is exactly at line 260, the macros table maps `\%` to `read-anon-arg` at line 768, and `mk-read-coll`/`do-read-coll`/`skip-space` sit at 205/207/143. Placeholders resolved; delivering the report.

Completed-GMT: 2026-10-01 18:30:56 GMT
Completed-Local: 2026-10-02 01:30:56 +0700

# ClojureDart runtime reader (cljd.reader): five defects

Filed by the datom.world team; happy to split into five issues with the
probe code attached.

## Environment

- ClojureDart: tensegritics/clojuredart
  81b5c03a55cf52b21dc0be8ccfa4827b9889f488
  (git describe: paris2022-611-g81b5c03, 2026-01-07)
- Host Clojure for cljd.build, per the checkout's deps.edn:
  org.clojure/clojure 1.11.1; no org.clojure/tools.reader dependency
  is declared - the runtime reader is its own implementation in
  clj/src/cljd/reader.cljd
- Source references were checked against a local checkout at
  c56e2dc1bef1841a7ce58ce2546946dd0be414e1 (paris2022-616-gc56e2dc,
  2026-01-21, five commits past the repro pin)
- cljd results reproduced with a minimal namespace compiled by
  cljd.build (:cljd/opts {:kind :dart}) and run on the Dart VM:
  Dart SDK 3.13.3 (stable), macOS arm64
- The cljd lines below are clojure.edn/read-string as it runs on Dart
  (aliases cljd.edn/read-string = cljd.reader/read-string). JVM lines
  are clojure.edn/read-string unless stated; re-verified for this
  report on Clojure 1.12.4, cross-checked against tools.reader 1.5.2

Provenance: every cljd -> and JVM -> result below comes from a
mechanical run at the pinned sha (cljd) or this report's verification
runs (JVM) and is quoted verbatim; the one Suggestion line per defect
is ours.

Scope: all five defects are in the runtime reader cljd.reader (what
cljd.edn / clojure.edn call on Dart at runtime). The compile-time
reader that reads .cljd sources during compilation follows Clojure
semantics; only runtime reads of user-provided text diverge.

## 1. % outside #() runs the argument dispatch; the % is dropped

Reproduction (each reads one form from a string):

    (read-string "%1")     cljd -> 1              JVM -> %1
    (read-string "%x")     cljd -> x              JVM -> %x
    (read-string "%&")     cljd -> &              JVM -> %&
    (read-string "%x/y")   cljd -> x/y            JVM -> %x/y
    (read-string "%/foo")  cljd -> refuses        JVM -> %/foo
                           "Invalid token: /foo"
    (read-string "%")      cljd -> refuses        JVM -> the symbol %
                           "Invalid value"
    (read-string "[% %1 %& (f %) {:k %}]")
                           cljd -> refuses "Unexpected closing
                           square bracket."       JVM -> reads fine

Inside #(), the bare % the JVM accepts is also refused:

    (read-string "#(f %)")
      cljd -> refuses "arg literal must be %, %& or %integer"
      JVM -> reads fine (fn* [p1__auto] (f p1__auto))
    (read-string "#(+ %1 1)")  cljd -> reads fine (%integer works)

Expected: outside #(), a token starting with % reads as a plain symbol
(%, %1, %&, %/foo, %x/y); inside #() the bare % is arg 1. tools.reader
1.5.2 reads the same as the JVM here.

Actual: the reader's % dispatch runs regardless of #() context and
drops the % character; the remaining text is read on its own, so %1
becomes the number 1 and %x the symbol x. (Discovery case in our
codebase: a Datalog query vector [:in $ ... % ...] printed with % as a
symbol with an empty name.)

Code path: clj/src/cljd/reader.cljd - the macros table maps % to
read-anon-arg unconditionally; outside #() (no :args-env binding on
the stack) read-anon-arg interprets the token after the %.

Impact: silently wrong forms, not just refusals, so any program that
reads user text mis-parses. Our Datalog front end names its rule-set
input % ([:in $ ... % ...]); every such query refused to read. We now
escape % tokens outside #(...) before reading and restore them after,
walking form starts as the reader does.

Suggestion: run the % dispatch only inside an #() context; read the
full token as a symbol otherwise.

## 2. Duplicate map keys and set elements are silently accepted

Reproduction:

    (read-string "{:a 1 :a 2}")  cljd -> {:a 2} (last wins)
                                 JVM  -> refuses "Duplicate key: :a"
    (read-string "#{1 1}")       cljd -> #{1}
                                 JVM  -> refuses "Duplicate key: 1"
    (read-string "#{x x}")       cljd -> #{x}

Expected: refuse with a duplicate-key error, as the JVM reader does
(tools.reader 1.5.2 agrees); the edn spec also makes duplicate map
keys invalid.

Actual: silently accepted; the later entry wins for maps, duplicates
collapse for sets. The reader contains no duplicate check at all.

Code path: clj/src/cljd/reader.cljd - mk-read-coll's map and set
constructors (hash-map, set) never check what they are given.

Impact: typo'd or corrupted literals read as valid data instead of
failing, and silently rather than as an exception the caller could
catch.

Suggestion: check keys/elements for duplicates in mk-read-coll's map
and set constructors and refuse as the JVM reader does.

## 3. Syntax-quote at runtime crashes: no resolver is bound

Reproduction:

    (read-string "`x")
      cljd -> refuses "No extension of protocol IResolver found for
                       type Null."
      JVM (clojure.edn)       -> refuses "Invalid leading character: `"
      JVM (core read-string) -> resolves at read time:
                                (quote user/x)
    (read-string "`x/foo"), (read-string "`(a b ~c)"),
    (read-string "`{:k v}")  cljd -> all refuse, same message

Expected: either resolve unqualified symbols against the reading
namespace as the JVM's read-string does at read time (verified: `foo
gives (quote user/foo), and even unresolvable symbols come out
namespace-qualified; tools.reader 1.5.2 behaves the same), or refuse
cleanly the way clojure.edn refuses (EDN has no syntax-quote).

Actual: the reader's syntax-quote support runs unconditionally and
dispatches the resolver protocol on nil.

Code path: clj/src/cljd/reader.cljd - syntax-quote calls resolveClass /
resolveVar / currentNS on *resolver* (line 260, default nil);
cljd.edn/read-string binds no resolver. Only the compiler binds one
(with-cljd-reader in clj/src/cljd/compiler.cljc), so compile-time
syntax-quote works and every runtime syntax-quote crashes.

Impact: a raw protocol-dispatch crash naming a Dart protocol, hard to
explain to users; neither defensible behavior (resolve, or refuse as
EDN) is available. Interacts with defect 1: our % escape/restore
workaround uses placeholder symbols, and syntax-quote expansion of
those placeholders is what surfaced the missing resolver.

Suggestion: bind a default resolver at the runtime read entry points,
or refuse ` there as clojure.edn does.

## 4. The list constructor stamps reader metadata (a Dart :tag) on
   every list it builds

Reproduction:

    (meta (list 1 2))
      cljd -> {:end-line 3436, :tag PersistentList<dynamic>,
               :end-column 54, :line 3436, :column 36}
      JVM  -> nil
    (meta (conj (list) 1))        cljd -> same map;  JVM -> nil
    (meta [1 2])                  cljd -> nil (vectors are fine)
    (meta (read-string "(1 2)"))  cljd -> nil (the runtime reader
                                   itself stamps nothing)

Expected: (meta (list 1 2)) is nil, as on the JVM.

Actual: every list returned by list (and preserved by conj) shares one
metadata map: the source position of the () literal inside list's own
definition in core.cljd, plus :tag holding the Dart Type
PersistentList<dynamic> (from the ^PersistentList hint on that
literal).

Code path: clj/src/cljd/core.cljd - defn list (~line 3430) seeds its
build loop with the () literal from its own source; the compile-time
reader stamps that literal with :line/:column/:end-line/:end-column
and :tag; PersistentList's -conj copies meta through, so the seed's
metadata reaches every constructed list.

Impact: implementation metadata leaks into user data. Lists that are
otherwise equal differ under metadata-aware equality and printing, and
:tag holds a Dart Type object: our content-addressed store encodes
values with a strict CBOR encoder that refuses the Type outright
("unsupported-value (Type)"), so any list that reached storage
unstripped was unencodable. We strip it with (with-meta l nil) at six
sites in our codebase.

Suggestion: build the seed list without the source literal's metadata.

## 5. Whitespace between an element and the closing delimiter is
   refused

Reproduction:

    (read-string "[1 2 ]")  cljd -> refuses "Unexpected closing
                                        square bracket."
                           JVM  -> [1 2]
    (read-string "[1 2\t]"), (read-string "[1 2\n]"),
    (read-string "[1 2,]")
                           cljd -> all refuse; JVM -> all read
    (read-string "{:a 1 }")  cljd -> "Unexpected closing curly brace."
    (read-string "(1 )")     cljd -> "Unexpected closing parenthesis."
    (read-string "#{1 }")    cljd -> "Unexpected closing curly brace."
    (read-string "[ ]")      cljd -> []    (leading whitespace fine)
    (read-string "[1 2] ")   cljd -> [1 2] (trailing after the closer
                                            fine)

Expected: whitespace (and commas) before any closing delimiter is
skipped, as on the JVM (tools.reader agrees).

Actual: after at least one element, any whitespace run before the
closer makes the closer itself be refused.

Code path: clj/src/cljd/reader.cljd - mk-read-coll's do-read-coll loop
seeds with (skip-space s from to) but recurs after each element at the
raw position, so the closer check sees the whitespace character and
dispatches read, which skips it and lands on the closer, itself an
"unexpected closing delimiter" dispatch. Only whitespace before the
first element is skipped.

Impact: multi-line or pretty-printed data with a space before a closer
refuses to read. We hit this wrapping signing-key text into {:k ...}
EDN envelopes and now trim trailing whitespace before closing the
wrapper; our linker-lane engineering notes warn about the quirk.

Suggestion: skip whitespace at the top of do-read-coll's loop, not
only before the first element.

## Cross-cutting notes

All five defects surfaced while compiling and running one cljc codebase
(a Datalog virtual machine with a multi-host reader-parity test suite)
on the ClojureDart lane, during unrelated ANTLR grammar work. All five
live in the runtime reader cljd.reader; the compile-time reader is
unaffected by 1, 3 and 5, and sources compiled by cljd.build read as on
the JVM. Bugs 1 and 3 interact as described above.
