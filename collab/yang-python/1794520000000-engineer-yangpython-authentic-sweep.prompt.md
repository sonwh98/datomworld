Created-GMT: 2026-11-05 03:30:00 GMT
Created-Local: 2026-11-05 10:30:00 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: the yang.python deref-site sweep — consult cell/authentic? before every cell/get

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6-luna | Assigned: 2026-11-05 10:30 +07 | Status: active | Rationale: bounded, precisely-scoped sweep in the Python frontend's prelude

WORKTREE: /Users/sto/workspace/datomworld-pysweep (detached at origin/master, tip 56d27100 — the
cell/authentic? ruling has LANDED on master per the yang.clojure seat's note
collab/yang-call/1794440000000-orchestrator-cell-ref-ruling-landed-skip-closed.md; parser built,
node_modules installed). Commit to a new branch `yangpython-authentic-sweep`. You are the only writer.

## The ruling (read first)

- collab/yang-call/1794340000000-orchestrator-to-ts-php-python-seeds-cell-ref-ruling.md (the relay)
- collab/yang-call/1794330000000-architect-cell-ref-ruling-of-record.md (the owner-approved ruling of record)
- collab/yang-python/1794390000000-…: my seat's audit — twelve py/cell? sites, three dereference
  sites identified. NOTE the file says "collab/yang-call/1794390000000-…-audit-reply.md" — that is
  the reply filed under collab/yang-call/; read it there.

## The pattern (from the ruling + the landing note, byte-for-byte the guarded shape)

  (if (and (py/cell? x) (cell/authentic? x)) (cell/get x) <data-branch>)

Shape (py/cell?) selects WHEN to ask; `cell/authentic?` decides meaning; inauthentic cell-shaped
data takes the data branch (ordinary dict semantics); genuine references resolve exactly as before.
The engine's `authentic-ref?` is a pure non-fatal query — state unchanged.

## THE THREE DEREFS (mandatory)

1. `prelude.cljc:245` — `py/content-type`: `(if (py/cell? x) (get (cell/get x) :py/type) nil)`
   → guard: authentic → the cell/get; inauthentic → nil (a look-alike dict has no python type).
2. `prelude.cljc:1242` — the type-resolution fn in py/add's dispatch:
   `(if (py/cell? x) (get (cell/get x) :py/type) (get x :py/type))`
   → guard: authentic → the cell/get; inauthentic → the data branch `(get x :py/type)` (a look-alike
   dict answers as ordinary dict data — Python arithmetic on a dict is a TypeError path, not an abort).
3. `prelude.cljc:1624-1631` — `py/eq` → `py/eq-objects`:
   `(if (py/cell? a) (if (py/cell? b) (py/eq-objects a b) false) …)`
   → guard BOTH operands: authentic → eq-objects; inauthentic cell-shaped data is ordinary data →
   fall through to the dict/tuple/data comparison clauses.

## THE NINE BRANCH-ONLY SITES (audit-only, no code)

:1111, :1176, :1732, :1953, :2288, :2418, :2487, :2562 — verified shape-check-then-fall-through,
never deref on inauthentic data. Add ONE docstring/comment line to `py/cell?`'s registration noting
the ruling (shape selects WHEN; authentic? decides meaning; never deref an inauthentic shape). No
code change at the nine.

## REGRESSIONS (the ruling requires the pair, mirroring the clojure-side pair)

1. A look-alike dict `{"type": "cell-ref", "id": 7, "seal": "<64 chars>"}` at a content-type/eq
   position ANSWERS as data (content-type nil, eq False) — never host-fatal.
2. A genuine reference still resolves and dereferences byte-identically (existing assertions cover it;
   add one explicit authentic-deref if none exists at these sites).

## VERIFY (your scope: single namespaces on THIS machine are allowed — individual tests, not suites;
the orchestrator runs the subsystem/full lanes on shiva)

- `clojure -M:test -n yang.python.antlr.call-entry-test`
- the new regression tests (add them to an existing yang.python test file, or a new one)
- `bb test:sub:clj yang.python` is the G1 bar — if the laptop load forbids it, run it on
  shiva.stigmergy.systems (scratch clone ~/ci/datomworld-pysweep from ~/ci/datomworld-python-s3,
  fetch your branch, mise activate, gen:antlr) and record the counts.
- kondo 0/0 + cljstyle on changed files.

CONSTRAINTS: single turn; commit to yangpython-authentic-sweep; do NOT push; do NOT touch master; do
NOT edit yin.vm, dao.jing, or the linker. Scope: src/cljc/yang/python/antlr/prelude.cljc + one test
file. Begin the final response exactly with Completed-GMT / Completed-Local, then report: the three
site fixes, the regression tests added, the audit comment, the namespace counts, and anything open.
