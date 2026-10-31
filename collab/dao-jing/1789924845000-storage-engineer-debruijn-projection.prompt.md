Created-GMT: 2026-09-20 23:22:00 GMT
Created-Local: 2026-09-21 23:22:00 +07 (Indochina Time)
Session-ID: 1bd16886-75ce-4889-a69a-c8a20463cad7
# Task: the de Bruijn projection — canonical form and alpha-equivalence fingerprint

Role: Compiler & AST Engineer (bounded pure-function unit)

You are on glm-5.3-flash on the flat plan: bounded unit, work tight.

The Universal AST is stored in its NAMED form — bijective, queryable,
renderable. This unit adds the PROJECTION: a deterministic transformation
of the named AST into de Bruijn form, with two contract properties:

1. **Alpha-invariance (the fingerprint):** two ASTs are alpha-equivalent
   (differ only in bound-variable names) if and only if their projections
   are identical — same projected datom set, same canonical encoding,
   same hash.
2. **Compression:** the projected form is smaller (arity instead of name
   lists, depth indices instead of names), so identical projections
   dedupe and the projected bytes compress better than the named bytes.

Read first, in this working tree (branch `debruijn-projection` off
master@ca04467f):
- `src/cljc/yin/vm.cljc` — the AST schema (`:yin/*` attributes), the
  emitter (`emit!`, `ast->datoms-with-root`), `gen-id`
- `test/yin/vm/linearize_test.cljc` — the corpus helpers (`app`, `lam`,
  `v`, `lit`, `worked-example`) and the `lowered` idiom
- `docs/design/dao.lease.implementation-plan.md` §0.1 — for the
  plain-data discipline only

Scope — exactly two NEW files, nothing else:
- `src/cljc/yin/vm/debruijn.cljc` — the projection
- `test/yin/vm/debruijn_test.cljc` — its tests

**The projection:**

Input: the AST as a datom set plus a root entity id (the
`ast->datoms-with-root` output shape). Walk the entity graph from the
root (same seen-ref discipline as the emitter — shared refs visited
once), maintaining a **scope stack**: an ordered mapping from binder
names to depths, pushed on lambda entry (innermost = depth 0), popped
on exit.

Projected node shapes (attribute names are yours to settle within the
existing `:yin/*` or a new `:db.debruijn/*` convention — state your
choice):
- lambda: `:type :lambda`, an **arity** (count of params), the body —
  the original param names are DROPPED (this is what makes the
  projection alpha-invariant).
- variable occurrence: if the name resolves to an in-scope binder, a
  **depth index** (0 = innermost; shadowing = nearest binder wins). If
  the name resolves to nothing, it is FREE and keeps its name — free
  names are semantic (environment keys) and must survive.
- application, literal, and every other node type: shape unchanged,
  with the scope context flowing through their children unchanged.
- refs: preserved, pointing at the projected entities.

**The canonical fingerprint:** assign projected entity ids by
deterministic traversal order (root = 1, then depth-first in child
order) so identical structures produce identical canonical encodings.
The fingerprint = hash of the canonical encoding — provide a function
that returns it (any hash is fine; the property to test is
equal-structure → equal encoding).

**Tests (the property list):**
1. `(fn [count] (+ count 1))` and `(fn [x] (+ x 1))` → **identical**
   projections and identical fingerprints (alpha-equivalence).
2. `(fn [count] (+ count 1))` vs `(fn [count] (+ count 2))` → different
   fingerprints (not alpha-equivalent: different constant).
3. Shadowing: `(fn [x] (fn [x] x))` — the inner occurrence resolves to
   the inner binder (index 0), and renaming the OUTER x does not change
   the projection.
4. Free names survive: `+` stays a free name; a free `count` (no binder)
   stays `count`, distinct from a bound `count`.
5. Determinism: the same AST projected twice → identical encoding.
6. The round-trip sanity: the projection of `((fn [x] (+ x 1)) 10)` and
   of `((fn [y] (+ y 1)) 10)` are identical, and both differ from
   `((fn [x] (+ x 2)) 10)`.

Verification, one single simple command per step (no chaining):
1. `clojure -M:test -n yin.vm.debruijn-test` — green.
2. `clojure -M:test` — full JVM green (1558/168786 baseline from
   master; nothing existing may break).
3. `clj -M:cljs -m shadow.cljs.devtools.cli compile slice-peer test` —
   green (mise Java 21 workaround if the bare command fails).
The orchestrator runs the CLJD lane. No staging, no commit. If a change
outside the two files seems required, stop and report.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then report: the projected node shapes you chose, the fingerprint
encoding, test counts, exact lane outcomes, and anything unresolved.
