---
description: Build, lint, test commands, TDD guidelines, and bracket debugging for Clojure, ClojureScript, Node.js, and ClojureDart
---

# BUILD & TEST

## Commands

```sh
# Tests (Babashka runner)
bb test              # Run all tests (JVM, Node/CLJS, Dart/CLJD)
bb test:clj          # JVM tests only (or: clj -M:test)
bb test:cljs         # ClojureScript / Node tests (via shadow-cljs :cljs alias)
bb test:cljd         # ClojureDart tests (requires flutter on PATH)
npm test             # Node.js tests

# ClojureScript / shadow-cljs (always via deps.edn :cljs alias, never npx)
clj -M:cljs -m shadow.cljs.devtools.cli watch <build-id>    # e.g. watch demo
clj -M:cljs -m shadow.cljs.devtools.cli compile <build-id>  # e.g. compile demo
clj -M:cljs -m shadow.cljs.devtools.cli release <build-id>  # production release

# ClojureDart
clj -M:cljd compile

# Linting
clj -M:kondo --lint <path>
```

`bb test:cljd`'s `dart test` globs everything under `test/cljd-out/`,
which the cljd compile does not prune when a source file is deleted or
renamed. A stale compiled `.dart` test for an already-deleted namespace
can silently pass, giving a false-clean run. Run `rm -rf test/cljd-out`
before any `bb test:cljd` you're relying on to prove a deletion (or
similar rename/removal) actually took effect.

## Testing Philosophy & TDD

Write tests before implementing features (TDD).

Tests define the contract and expected behavior. Implementation follows from the tests, not the other way around.

When implementing a feature or fix:
1. **Red phase**: Write the test first — define what should happen.
2. **Green phase**: Implement the minimum code to pass the test.
3. **Refactor phase**: Refactor if needed while keeping tests green.

This ensures code is testable by design and implementation matches actual requirements.

## Debugging Malformed CLJ / EDN / CLJS

A single missing bracket can silently break an entire file. The compiler may still succeed, with subsequent forms nesting inside the broken one.

1. **Run clj-kondo first**:
   ```sh
   clj -M:kondo --lint path/to/file.cljs
   ```

2. **If clj-kondo reports a mismatch, write a bracket-tracing script**:
   Do NOT try to eyeball-count brackets. Track a stack of `(char, line, col)` for every opener. Handle: string literals, escaped chars, and `;;` line comments.

3. **Fix one bracket at a time, re-run checker after each fix**:
   Common patterns:
   - Hiccup vector missing `]` causes siblings to become extra arguments.
   - `defn`/`let` missing `)` causes subsequent top-level forms to nest inside.
   - Both can coexist while total bracket count is zero-sum.

4. **After brackets balance**, rerun `clj-kondo`, then rebuild with `clj -M:cljs -m shadow.cljs.devtools.cli compile <build>`.
