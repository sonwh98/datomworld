# DRAFT - five reader bugs in ClojureDart's runtime reader

Status: DRAFT for owner review. Not submitted upstream.
Prepared: 2026-10-01, by the datom.world team.
Intended for: https://github.com/tensegritics/ClojureDart (five issues, or
one tracking issue if the maintainers prefer).

## Version and environment

- ClojureDart: tensegritics/clojuredart
  81b5c03a55cf52b21dc0be8ccfa4827b9889f488
  (2026-01-07, describe: paris2022-611-g81b5c03)
- All five behaviors were reproduced with a minimal namespace compiled
  with `cljd.build` (`:cljd/opts {:kind :dart}`) and run on the Dart VM:
  Dart SDK 3.13.3 (stable), macOS arm64.
- JVM comparisons: Clojure 1.12.4, `clojure.edn/read-string`.
- The `cljd` lines below are `clojure.edn/read-string` as executed on
  Dart (it aliases `cljd.edn/read-string`, which is
  `cljd.reader/read-string`). The JVM lines are
  `clojure.edn/read-string`. All snippets read one form from a string.

Scope note: every bug below is in the runtime reader `cljd.reader`
(what `cljd.edn` / `clojure.edn` call at runtime on Dart). The
compile-time reader (the JVM-side `cljd.lang.LispReader` that reads
`.cljd` sources during compilation) follows Clojure semantics; only
runtime reads of user-provided text diverge.

---

## Bug 1 - `%` outside `#()` is read as an argument dispatch (the `%`
## is silently dropped)

Repro:

    (read-string "%1")    ; cljd -> 1           JVM -> %1
    (read-string "%x")    ; cljd -> x           JVM -> %x
    (read-string "%&")    ; cljd -> &           JVM -> %&
    (read-string "%x/y")  ; cljd -> x/y         JVM -> %x/y
    (read-string "%/foo") ; cljd -> refuses "Invalid token: /foo"
                          ; JVM  -> %/foo
    (read-string "%")     ; cljd -> refuses "Invalid value"
                          ; JVM  -> the symbol %
    (read-string "[% %1 %& (f %) {:k %}]")
                          ; cljd -> refuses "Unexpected closing square
                          ;         bracket."
                          ; JVM  -> [% %1 %& (f %) {:k %}]

Inside `#()` the bare `%` the JVM accepts is also refused:

    (read-string "#(f %)")
      ; cljd -> refuses "arg literal must be %, %& or %integer"
      ; JVM (reader) -> reads fine as (fn* [p1__auto] (f p1__auto))

    (read-string "#(+ %1 1)")
      ; cljd -> reads fine, (fn* [p1__...] (+ p1__... 1)) -- works

Expected: outside `#()`, a token starting with `%` reads as a plain
symbol (`%`, `%1`, `%&`, `%/foo`, `%x/y`), as on the JVM; inside `#()`
the bare `%` is accepted as arg 1.

Actual: the reader's `%` dispatch runs regardless of any `#()` context
and the `%` character is dropped from the token: the remaining text is
read on its own, so `%1` becomes the number 1 and `%x` the symbol `x`.

Code path: `clj/src/cljd/reader.cljd` - the `macros` table maps `%` to
`read-anon-arg` unconditionally; `read-anon-arg` starts the token scan
at the character after the `%` and, when no `:args-env` binding is on
the stack (outside `#()`), interprets that truncated token.

Severity: high. It is not only a refusal: it silently yields wrong
forms (`%1` reads as `1`, `%x` as `x`), so any program that reads user
input mis-parses. Our concrete case is a Datalog front end where `%`
names the rule-set input (`[:in % ...]`); every such query refused to
read. We now escape `%` tokens outside `#(...)` before reading and
restore them after, walking form starts as the reader does.

## Bug 2 - duplicate map keys and set elements are silently accepted

Repro:

    (read-string "{:a 1 :a 2}") ; cljd -> {:a 2} (last wins)
                                ; JVM  -> refuses "Duplicate key: :a"
    (read-string "#{1 1}")      ; cljd -> #{1}
                                ; JVM  -> refuses "Duplicate key: 1"
    (read-string "#{x x}")      ; cljd -> #{x}

Expected: refuse with a duplicate-key error, as the JVM reader (and
the edn spec, which makes duplicate map keys invalid) do.

Actual: silently accepted; the later entry wins for maps, duplicates
collapse for sets.

Code path: `clj/src/cljd/reader.cljd` - `mk-read-coll`'s map and set
constructors (`hash-map`, `set`) never check what they are given.

Severity: medium. Typo'd or corrupted literals read as valid data
instead of failing, and the failure is silent rather than an exception
the caller could catch.

## Bug 3 - syntax-quote at runtime crashes: no resolver is bound

Repro:

    (read-string "`x")
      ; cljd -> refuses "Exception: No extension of protocol IResolver
      ;         found for type Null."
      ; JVM (clojure.edn) -> refuses "Invalid leading character: `"
      ; JVM (clojure.core/read-string) -> resolves to the current ns
    (read-string "`x/foo"), (read-string "`(a b ~c)"),
    (read-string "`{:k v}") ; all refuse on cljd with the same message

Expected: either resolve unqualified symbols against the reading
namespace like the JVM's `read-string`, or refuse cleanly the way the
JVM's `clojure.edn` refuses (EDN has no syntax-quote). Today it is a
raw protocol-dispatch crash on a nil resolver.

Actual: the reader's syntax-quote support runs unconditionally and
calls the resolver protocol on nil.

Code path: `clj/src/cljd/reader.cljd` - `syntax-quote` calls
`resolveClass` / `resolveVar` / `currentNS` on `*resolver*`
(`reader.cljd` line 260, default nil); `cljd.edn/read-string` is
`cljd.reader/read-string` and binds no resolver. Only the compiler
binds one (`cljd-resolver` via `with-cljd-reader` in
`clj/src/cljd/compiler.cljc`), so compile-time syntax-quote works and
every runtime syntax-quote crashes.

Severity: medium. We compare macro expansions across hosts in our
REPL's reader-parity tests; a crash naming a Dart protocol extension
is hard to explain to users, and neither of the two defensible
behaviors (resolve, or refuse as EDN) is available.

## Bug 4 - the list constructor stamps reader metadata (including a
## Dart Type as `:tag`) on every list it builds

Repro:

    (meta (list 1 2))
      ; cljd -> {:end-line 3436, :tag PersistentList<dynamic>,
      ;          :end-column 54, :line 3436, :column 36}
      ; JVM  -> nil
    (meta (conj (list) 1)) ; cljd -> the same map; JVM -> nil
    (meta [1 2])           ; cljd -> nil (vectors are fine)
    (meta (read-string "(1 2)")) ; cljd -> nil (the runtime reader
                                 ; itself stamps nothing)

Expected: `(meta (list 1 2))` is nil, as on the JVM.

Actual: every list returned by `list` (and preserved by `conj`) shares
one metadata map: the position of the empty-list literal inside `list`'s
own definition in `core.cljd`, plus `:tag` bound to the Dart Type
`PersistentList<dynamic>` (the `^PersistentList` hint on that literal).

Code path: `clj/src/cljd/core.cljd` - `defn list` (~line 3430) seeds
its build loop with the literal `()` from its own source; the
compile-time reader stamps that literal with `:line`, `:column`,
`:end-line`, `:end-column` and, from the `^PersistentList` hint,
`:tag`; `PersistentList`'s `-conj` copies `meta` through, so the
seed's metadata reaches every constructed list.

Severity: medium. Metadata leaks from the implementation into user
data. Two otherwise-equal lists differ under metadata-aware equality
and printing, and `:tag` holds a Dart Type object: our content-
addressed store encodes record values with a strict CBOR encoder that
(mostly) accepts only portable data, and it refuses the Type outright,
so any list that reached storage un-stripped was unencodable. We strip
it with `(with-meta l nil)` at the storage boundary, but a value that
carries the implementation's private provenance by default is a trap.

## Bug 5 - whitespace between an element and the closing delimiter is
## refused

Repro:

    (read-string "[1 2 ]") ; cljd -> refuses "Unexpected closing
                           ;         square bracket."
                           ; JVM  -> [1 2]
    (read-string "[1 2\t]"), (read-string "[1 2\n]"),
    (read-string "[1 2,]") ; all refuse on cljd; all read on the JVM
    (read-string "{:a 1 }") ; cljd -> "Unexpected closing curly brace."
    (read-string "(1 )")    ; cljd -> "Unexpected closing parenthesis."
    (read-string "#{1 }")   ; cljd -> "Unexpected closing curly brace."
    (read-string "[ ]")     ; cljd -> [] (leading whitespace is fine)
    (read-string "[1 2] ")  ; cljd -> [1 2] (trailing whitespace after
                            ; the closer is fine)

Expected: whitespace (and commas) before any closing delimiter is
skipped, as on the JVM.

Actual: after at least one element, any whitespace run before the
closer makes the closer itself be refused.

Code path: `clj/src/cljd/reader.cljd` - `mk-read-coll`'s `do-read-coll`
loop seeds with `(skip-space s from to)` but `recur`s after each
element with the raw position, so the closer check sees the whitespace
character, dispatches `read`, which skips the whitespace and lands the
dispatch on the closer, which is an "unexpected closing delimiter"
macro. Only the whitespace before the first element is skipped.

Severity: low to medium. Multi-line or pretty-printed data with a
space before a closer (a common human format, and what `pr`-style
wrapping produces when text is embedded) refuses to read. We hit this
wrapping signing key text into `{:k ...}` EDN envelopes and now trim
trailing whitespace before closing the wrapper.

---

## Closing notes

- All five repros above were run mechanically on the pinned sha; the
  cljd results are quoted verbatim from the run. Happy to split this
  into five separate issues with the probe code attached.
- The `%` behavior (bug 1) interacts with bug 3: our escape/restore
  workaround relies on placeholders, and syntax-quote expansion of
  those placeholders is what surfaced the missing resolver.
- If it helps triage: the compile-time reader is unaffected by bugs 1,
  3 and 5; sources compiled by `cljd.build` read as on the JVM. The
  runtime reader is what diverges.
