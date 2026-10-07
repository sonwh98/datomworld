Created-GMT: 2026-10-02 19:45:00 GMT
Created-Local: 2026-10-03 02:45:00 +07 (+0700)
Coding-Agent: claude
Session-ID: 65b24574-4b26-45d4-993a-cfccf0b2604a

# Task: Python C3 slice S2 — numeric dict keys, guest hashing, integer is

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude opus (opus-5.5) | Assigned: 2026-10-03 02:45:00 +07 (+0700) | Status: active | Rationale: bounded prelude-surface slice over the landed integer module; keys and hashing are Python-semantic placement

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-py-c3key1 (branch yang-python-c3-s2, based on master
69e58662, which contains C3-S1 as 54536317 — the yin.vm.integer module and carrier recognition). Do not touch
/Users/sto/workspace/datomworld, the datomworld-linker-* worktrees (another seat owns them), or any other worktree.
Do not stage or commit.

DESIGN (binding): read collab/1790874940000-architect-python-c3-bignum-design.gpt-6-astra.findings.md and
collab/1790875860000-architect-c3-bignum-crossruling.claude-fable-5-1.findings.md (the converged rulings; 6, 7 and
8 are this slice). ALSO binding: collab/1790968830636-architect-float-address-mob.gpt-6-astra.findings.md — its
gate says C3-S2 proceeds INTEGER-ONLY: float-bearing address acceptance is gated on the separate float64-carrier
slice; "float-free" must describe the entire addressed payload including the prelude.

S2 SCOPE (rulings 6, 7, 8; integer-only):
1. Numeric dict/set keys (ruling 6): replace py/key's double normalization with canonical reduced-rational
   decimal-string keys in ONE form; integers (any carrier) key by their exact decimal value; infinities get
   signed string keys; NaN keeps the current behavior (identity semantics, unchanged); bools key as their
   integer values per Python's 1 == 1.0 == True rule (the existing normalization, restated on the new key form);
   insertion-order bookkeeping keeps the FIRST INSERTED ORIGINAL key. The C1 corpus and every existing dict test
   must stay green.
2. Guest hashing (ruling 7): hash() uses P = 2^61-1 on EVERY host for int, bool and finite float, independent of
   host hash and Jing hashes; the construction must be cross-host identical (pure prelude arithmetic over the
   integer module's exact kernels — no host hash calls). hash() of identity objects (generators, functions,
   cells-backed objects) raises TypeError "unhashable type" in this slice unless they already hash — do not
   regress existing hashable surfaces. NOTE: any new prelude numeric literal must be JS-safe-integer construction
   (see the landed pattern (* 2 4503599627370496) in prelude.cljc:553+ and the C3-r3 lesson) and NO cross-host
   address goldens over float-bearing trees (astra's gate).
3. Integer is (ruling 8): value-based through the unchanged py/is, carrier-independent — add tests pinning that
   a big-carrier integer and its native demotion are is-equal per the convention (the identity convention is the
   documented value-based one; no CPython allocation fidelity).
OUT OF SCOPE: float64 carriers / float-bearing addresses (the separate float slice), conversions beyond what
  the keys need, pow/extensions, stream transport, linker/portable-scalar (explicitly left unchanged by S1).

ACCEPTANCE (four VMs across the JVM/Node/CLJD lanes):
- Dict keys: {1: 'a', 1.0: 'b', True: 'c'} collapses to one slot per Python (first-inserted original kept as the
  visible key); bignum keys (beyond ±2^53) behave identically on all hosts; -0.0 and 0.0 key together with
  Python's sign rules; Infinity/-Infinity key distinctly; NaN lookups behave as before.
- Hash: hash(2**80) is the same value on JVM, Node and Dart; hash of small ints/bools matches Python's documented
  scheme modulo the P modulus (assert cross-host equality of the guest-visible value, not CPython's exact
  constant — state what you pin in the report); hash(0) == hash(False) == hash(0.0) values consistent with the
  key collapse.
- is: the carrier-independence tests; x = 2**70; y = 2**70; the documented convention holds and is TESTED (the
  report states exactly what is promises in this codebase).
- The full existing lanes stay green; the C1/C2 e2e corpora unchanged.

MECHANICS:
- Before any JVM lane: mise exec -- bb gen:python-antlr then mise exec -- bb build:yin-repl-node. Lanes
  FOREGROUND, one at a time, waiting for each: mise exec -- bb test:clj, bb test:cljs, bb test:cljd;
  clj -M:kondo + cljstyle check on touched files. mise is trusted; node_modules installed.
- No new AST tag; no wire/codec change (dao.jing.cbor untouched); do not weaken tests; if a ruling element
  conflicts with the code, stop that element and report.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

Report changed files, exact counts per lane, the pinned hash/key conventions, unresolved concerns, incomplete
work. Do not claim edits or tests that did not occur.
