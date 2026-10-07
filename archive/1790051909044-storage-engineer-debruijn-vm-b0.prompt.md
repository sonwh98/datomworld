Created-GMT: 2026-09-22 04:38:29 GMT
Created-Local: 2026-09-22 11:38:29 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: pending (new session, caller-generated)
# Task: debruijn-vm-b0 — freeze the result/error normalizer and the parity corpus
Role: yin.vm / Interpreter Engineer
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-22 11:38:29 +07 | Status: active | Rationale: mechanical, test-only phase with an architect-signed design and clear must-not-change boundary; balanced daily engineering work

Work in /Users/sto/workspace/datomworld (your launch directory; branch master, HEAD
e5408acd). Do NOT stage, commit, merge or push.

## Spec (B0, from the architect-signed design)

Read docs/design/yin.vm.debruijn-vm.md IN FULL first, then section "B0: contract and
normalizer" (restated below) and section 1 (Architecture and invariants) for context.
This phase adds no new evaluator, no new AST, no new dimension: it freezes the tools
later phases (B1+) will depend on.

File box (exactly this):
- NEW test/yin/vm/debruijn_vm_contract_test.cljc
- Existing edits: NONE
- Must not change: the Universal AST (`:yin/*`), the emitter (`yin.vm.linearize`),
  the merged de Bruijn projection namespace (`yin.vm.debruijn` /
  `yin.vm.pipeline`, dormant per D12), the named semantic VM, the `:yin.code/*`
  dimension.

## Do

1. **Freeze a result/error normalizer.** A pure function (or small set of functions)
   that takes whatever the named semantic VM (ast-walker) produces for a program —
   a value, a parked/blocked state, a thrown error, a continuation, a stream event,
   a cursor, a store snapshot — and returns a normalized, comparable representation
   independent of incidental host or object identity (so two runs that are
   semantically equal compare `=`). Reuse `yin.vm.parity-test`'s existing comparison
   helpers where they already do this; do not reinvent what `parity-test` already
   normalizes for closures, continuations, errors, streams, cursors, and stores —
   extend or wrap it, cite what you reused.
2. **Freeze the actual parity corpus.** A frozen, deterministic corpus of programs
   (as Universal AST datoms, the way `yin.vm.linearize` consumes them) covering every
   AST tag the emitter handles, reusing the "every-tag corpora" already present in
   `test/yin/vm/content_test.cljc` and `test/yin/vm/completion_test.cljc` — do not
   duplicate those corpora; require and reuse them, or extract a shared fixture if
   that is cleaner, citing which.
3. **Completion criteria (all of these, as deftests in the new namespace):**
   - Named-VM self-parity: running the same program against the named VM twice
     (or by two paths, e.g. ast-walker vs any existing alternate execution path in
     this repo) and normalizing both results is equal.
   - Idempotence of the normalizer: normalizing an already-normalized result returns
     the same value.
   - Normalized comparisons hold for: closures, continuations (UCF-shaped, per
     `yin.vm.completion`), errors, streams, cursors, and store snapshots — one
     deftest per category, each exercising at least one corpus program of that
     shape.
   - Duplicate-parameter programs (a lambda/closure whose parameter list has a
     repeated name) normalize and compare correctly — this is explicitly named in
     the design as a required case, since it is exactly the kind of case a de Bruijn
     encoding later must handle without collision.
   - Every corpus program from content_test/completion_test's "all-node" fixtures is
     covered (a coverage assertion: every AST tag those fixtures exercise appears at
     least once in what this test namespace runs).
4. Do not touch the merged projection, the linearizer, the named VM, or the code
   dimension. If you find you need to change one to make this work, STOP and report
   why instead of editing it.

## Environment

Default PATH gives Java 21 and the mise clojure and bb. Focused JVM run:
`clojure -M:test -n yin.vm.debruijn-vm-contract-test` (use the namespace your file
actually defines; check how the runner discovers test namespaces first). If you need
kondo, cljstyle, the Java 17 lane, or the CLJD lane and they are denied, say exactly
what was denied and stop; do not retry, do not use `--dangerously-skip-permissions`.
Keep files pure ASCII, no em dashes, cljstyle-style Clojure (blank lines between
top-level forms), docstrings that say what and why. Reader conditionals: `:cljd`
FIRST if any branch must exclude cljd.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <your session id>
Then: the normalizer's shape and what it reuses from parity-test; the corpus's shape
and what it reuses from content_test/completion_test; how each completion criterion
is met, naming the deftest; what you ran with exact counts; what you could not run;
every deviation. Facts only; promise nothing.
