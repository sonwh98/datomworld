Created-GMT: 2026-09-21 08:43:09 GMT
Created-Local: 2026-09-21 15:43:09 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 69f82e15-85c9-4754-a273-a5f4ad68d932 (resumed — your D6 session)
# Task: debruijn-d6fix-claude — make the durable-storage breadth test honest about the Dart file codec
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-21 14:19:09 +07 | Status: active | Rationale: same implementer; a one-file test correction found by the CLJD lane you cannot run

Work only in /Users/sto/workspace/worktree-debruijn-impl. Edit ONLY
test/yin/vm/pipeline_test.cljc. No staging, committing, docs/, or collab/
edits.

## What the orchestrator found (CLJD lane, Dart, verified by a per-literal probe)

Your D6 round is green on JVM (1654/169230/0, Java 17) and CLJS (1573/39085/0),
lint clean. On CLJD exactly ONE test fails:
`every-node-type-and-scalar-class-round-trips-through-durable-storage`.
Program 1 (every node type, macro lambda included) persists and projects fine
on Dart. Program 2 fails because `dao.jing.file`'s Dart text codec cannot
carry a LIST literal: persisting `(lit (list 1 2.5))` or `(lit (list 1 2))`
returns `:projected {:outcome :diagnostic :rule :projected-write-failed}` with
message "Payload does not survive this backend's text codec: its round trip
would not hash to its content address". That is the file store's own
fail-safe refusing before anything is stored. Every other scalar class in
your fixture persists on Dart: nil, both booleans, composed and decomposed
NFC strings, integral and non-integral doubles, keyword, symbol, vector, set,
and the nested map holding `#{nil}`. It is the same class of host-codec limit
as the CLJS `-0.0` case you already pinned, it lives in dao.jing.file (outside
the design's authority, §9), and it is NOT a defect in the projection.

## Fix (test only)

1. Remove the two list literals from the shared `durable-programs` fixture
   (`(lit (list 1 2.5))`) and update its docstring: lists join negative zero
   as values the file store's codec does not carry on every host.
2. Generalize the existing host-neutral integrity-law test
   (`negative-zero-does-not-survive-the-file-codec-on-cljs`, rename it to say
   what it now pins, e.g.
   `values-the-file-codec-cannot-carry-are-refused-not-corrupted`) to a small
   table of cases — `-0.0` and a list literal (`(list 1 2)`, and one with a
   double) — each asserting the SAME law on every host: the durable round trip
   is EITHER exact (the reopened envelope reads back to the original
   projection) OR refused loudly, either at write
   (`:projected {:outcome :diagnostic :rule :projected-write-failed}`, nothing
   stored — the Dart list case) or at read (`datoms->projected` diagnoses
   `:hash-mismatch` — the CLJS -0.0 case) — never a silent change of value.
   On JVM every case must be exact, as before (keep that stricter assertion
   for JVM and CLJD-non-list cases only where you already had it; do NOT
   require exactness for lists on CLJD, and do not require the refusal to
   happen on any particular host — a jing fix must not break this test).
   The write-refusal branch must also assert the store stayed empty for that
   program (nothing half-written) — open the file store and check the
   content count/reopen returns absent for the fingerprint address, using
   whatever the existing helpers make cheapest.
3. Keep the D6 evidence: lists remain covered by the pure/in-memory suite
   (`debruijn_test` list-vs-vector distinctness, in-memory `pipeline_test`
   persistence via `mem-store`). If no in-memory pipeline test currently
   persists a list literal successfully, add one line to an existing test or a
   tiny new one so lists are still exercised through `persist-compiled!`.

## Verification

Focused JVM `clojure -M:test -n yin.vm.debruijn-test -n yin.vm.pipeline-test`
and `bb test:cljs` (Java 21), exactly as before; report counts (baseline this
round: focused 96 tests / 444 assertions; CLJS 1573 / 39085). kondo, cljstyle,
env-block exports, the Java-17 lane and CLJD are denied in your headless
session — skip them and do not retry; the orchestrator reruns everything,
including the CLJD lane that found this.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 69f82e15-85c9-4754-a273-a5f4ad68d932
