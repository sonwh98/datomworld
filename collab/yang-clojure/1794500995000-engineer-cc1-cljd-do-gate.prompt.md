Created-GMT: 2026-11-10 (real header below)
Coding-Agent: qwen
Session-ID: 0e224583-7574-4389-b202-332a941217a9

# Task: gate repl-query-link-forwarding-test on :cljd using the established (do ...) pattern

Role: VM Engineer (precise fix; the pattern is established in the repo)

Implementers:
- Model: qwen3.8-max | Assigned: (header above) | Status: active | Rationale: owner relay - qwen is NOT out of quota; use qwen lane

## The failure (measured, three compile attempts)

`yang.clojure-callsite-test` fails cljd compilation because
`repl-query-link-forwarding-test` uses `with-redefs`, which cljd does not
support — "Unknown symbol: with-redefs at line: 1737". A naive
`#?(:cljd nil :default (testing ...))` gate does NOT work: the compile still
fails because the deftest's body extends past the gate with unbalanced parens,
and the cljd reader still processes the :default branch's contents.

## The established pattern (from test/dao/stream/ws_codec_test.cljc:14)

```clojure
#?(:cljd nil
   :default
   (do
     ...the entire gated body...))
```

The gate must wrap the ENTIRE remaining form in a `(do ...)` with balanced
parens, so cljd reads a single `nil` for the branch and never sees the body.

## Your job

In the scratch worktree /tmp/cc1-p1 (or re-derive from origin/callsite-contract-
clojure @ f440a2fb): wrap the ENTIRE `repl-query-link-forwarding-test` deftest
in the established pattern:

```clojure
#?(:cljd nil
   :default
   (deftest repl-query-link-forwarding-test
     ...))
```

Note: a deftest itself can live inside a reader conditional (the whole deftest
form is the :default branch's value). Match the file's existing structure and
paren balance exactly. Do not change any other deftest. Do not commit.

## Verify

1. LOCAL: cljd compile of the namespace succeeds
   (clj -M:clojuredart:cljd compile yang.clojure-callsite-test — musesandbox
   cannot ssh; this runs LOCALLY on the laptop, which is permitted: it is a
   COMPILE, not a test run).
2. Report git diff --stat and the compile output verbatim.

Begin the final response exactly with Completed-GMT / Completed-Local.
