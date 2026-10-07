Created-GMT: 2026-09-14 13:12:32 GMT
Created-Local: 2026-09-14 20:12:32 +07
Coding-Agent: glm
Session-ID: ee3147ec-24c9-4fd7-8578-395b3282ab53 (resume — your own r2 revision session)
# Task: UCF §7.3 amendment — the canonical form is the positional instruction tuple vector (r3)
Role: Lead System Architect
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-14 20:12:32 +07 | Status: active | Rationale: same session as the r2 revision; small targeted amendment of §7.3 only.

## Owner rulings this round implements

Two rulings arrived after your r2 revision, both from the user:

1. **"Tuples are the correct way to represent a semantic AST, but the datom [e a v t m] might not be the way to do it if I want content-hashing of code like with Unison."** R2's §7.3.2 record — `{pc → {attr → value}}` attributed maps — works, but it carries datom vocabulary (attribute names, admissibility rules) into the identity layer. Replace it with the positional tuple vector.
2. **"dao.space.query doesn't assume [e a v t m]; any n-tuple will work."** Verified: `relation` is an arbitrary mixed-dimensional tuple collection (`src/cljc/dao/space/query.cljc:153-158`). Therefore EAV is not required for Datalog-over-code, and UCF must not assume it anywhere for code.

## The amendment

**§7.3.2.** The canonical form of a segment is a **positional instruction tuple vector**:

```clojure
;; the §2.7 worked segment, canonical form (excerpt)
[[:closure [x] 6]           ; opcode mnemonic, params, resolved body pc
 [:push]
 [:const 10]
 [:call 1 false]            ; argc, tail?
 …]
```

Rules (each replaces heavier machinery from the attributed-map record):
- **pc is the index.** No entity ids, no attribute names — position fixes meaning; the §2.4 opcode table fixes each mnemonic's tuple arity and operand kinds.
- **Saturation:** defaults the loader applies are materialized in the canonical tuple (`:gensym` absent prefix → `"id"`, `:ffi-call` absent argc → `0`, `:stream-make` absent buffer → default capacity), so an omitted-default batch and a stated-default batch canonicalize identically, because they execute identically.
- **Refs are resolved pcs**, exactly as the loader resolves them. Segment header facts fold away (`:yin.code/length` = `(count vector)`, `:yin.code/type` implied); provenance, eids, `t`, `m` were already excluded.
- **Address** = `(dao.jing/segment-key vector)`. Hashing an ordered vector needs no key-order normalization; the collision-freedom argument from r2 (resolved interpretation first, last-wins applied) is unchanged — the vector IS the resolved interpretation.
- The "equal addresses ⟺ equal resolved interpretations" statement, and the no-broader-equivalence honesty, carry over verbatim.

**§7.3.3.** The stamp now versions the tuple grammar: the mnemonic set, each mnemonic's tuple arity/kinds, and the saturation/defaults table. (Simpler than versioning attribute admissibility.)

**§7.3.4.** The datom batch is demoted to a **projection**: vector→datoms emission stays as the specified conversion (eids `0`/`(inc p)`, pc operands back to refs, `:yin.code/segment 0`), and the §2.6 loader path remains valid — but additionally spec the **direct path**: a conforming engine may decode the canonical vector into its image directly, and both paths must yield the same image (state it as a conformance obligation). `:yin.k/carried` carries canonical vectors. Everything else in §7.3.4 (addressed-payload-is-the-canonical-form, additive address index, id/occurrence split) stands.

**Sweep.** Update any passage whose wording still means the attributed-map record by "canonical instruction record" (§7.3.4, §7.8 step 5, §7.11 blockers, the §7.3 intro). Rename consistently — suggest "canonical instruction vector" — and keep r2's precedent of naming what was replaced and why (one short paragraph in §7.3.2 noting the r2 record and the owner ruling that simplified it).

**Do not touch**: §7.4–§7.7 mechanisms, §7.9–§7.11 content beyond the record-shape wording, `yin.vm.semantic.md` (the §2.2 `:yin.code/hash` note already says "canonical instruction record" — if you re-word it, that one line may change "record" to "vector"; nothing else). No staging, no commit.

## Deliverable

The amended document plus a short report: sections touched, wording renamed, any place the vector form forced a rule change beyond renaming. Begin the final response exactly with:

Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and named timezone>
Coding-Agent: glm
Session-ID: ee3147ec-24c9-4fd7-8578-395b3282ab53
