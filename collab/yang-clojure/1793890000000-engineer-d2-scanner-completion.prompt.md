Created-GMT: 2026-11-03 08:05:00 GMT
Created-Local: 2026-11-03 15:05:00 Asia/Ho_Chi_Minh
Coding-Agent: glm (glm-5.3)
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: complete D2 — fix the oracle's context-admission scanner for the new lowering

Role: engineer (glm)
Reviewer/landing: the ZCode glm orchestrator seat; kimi holds the quality gate (session_12e44768).

## Context

Worktree `/Users/sto/workspace/datomworld-h31`, branch `hardening3-h3-1`. A stash (`stash@{0}`) holds an UNCOMMITTED compiler change: the D2 token-packet lane (the architect's ruling — an ephemeral two-element vector `[dsg__call_token dsg__args]` built by the generated intra-unit call helper, applied to the same strictly unary public entry, whose `packet` dispatch now distinguishes vector-shape (element 0 == the compiler-held token → apply the private body to element 1) from map records). Read the stash first: `git stash pop` it (it contains `src/cljc/yang/clojure.cljc` + `test/yang/latte_oracle_test.clj` edits) — or `git stash show -p` to study it. Do not lose it either way.

## The defect

The compiler loads and a minimal program compiles, but every kernel fixture now fails with `Unexpected AST in context admission` — thrown by `unbound-ast-symbols`' `scan` in `test/yang/latte_oracle_test.clj` (~line 214-233), the admission scanner for the portable context module. Its `case` only handles `:variable :literal :vm/current-continuation :lambda :application :if`; the D2 lowering introduces at least one node shape it rejects (an earlier speculative `:let` case crashed with `find not supported on type: clojure.lang.Symbol` — the actual `:let`-like bindings shape is unknown; INSTRUMENT, don't guess: the throw's ex-data now carries `{:type ... :node (pr-str ...)}` — run one fixture and READ the node).

## Your job

1. `git stash pop`; run the failing scanner on one fixture (`clojure -M:test -v yang.latte-oracle-test/test-kernel-differential-gate` or the `kernel-row` path for `:mkenv`) and capture the reported node.
2. Extend the scanner's `case` to handle every node shape the D2 lowering emits — with correct free-variable semantics for whatever binding forms appear (binders scoped over their body). Use seq-safe access (`first`/`second`/`vec`), never `get` on possibly-symbol nodes.
3. If the emitted AST contains a node type the WALKER itself cannot execute (the compiler emitting something unrunnable), report that instead of papering the scanner — the scanner is admission, not a bag of node cases.
4. VERIFY: the focused gate `clojure -M:test -v yang.latte-oracle-test/test-kernel-differential-gate` must show counts `{pass 102, recorded-upstream-failure 2, recorded-deviation 2, blocked 0}` (or better) and at most the by-design blocked assertion failing; `bb test:sub:clj yang.base` 124+/0; kondo 0 errors on touched files.
5. The acceptance budget stays 2,000,000; the machine-data validation bound stays 100,000. Do not touch `yin.vm`, `yang.call`, the evidence files, or any narrative.

## Report

State the node shapes you found (verbatim), the scanner diff, and the verification outputs. Commit on the branch (no push; the seat lands). Your implementation will be code-quality reviewed by kimi before landing.
