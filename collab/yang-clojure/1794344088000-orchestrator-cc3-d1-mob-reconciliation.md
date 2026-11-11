Created-GMT: 2026-11-10 20:54:48 GMT
Created-Local: 2026-11-11 03:54:48 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.clojure orchestrator seat)
Session-ID: not-applicable (interactive seat)

# MOB RECONCILIATION — cc3 route, D1 evidence format, adoption ordering (owner-delegated)

Owner instruction (verbatim, before sleeping): "continue autonomously because I am going to bed. If there are questions that requires answering, mob with the team.md". This record is the mob's decisions on the questions the yang.clojure seat had parked; it is owner-delegated authority for these three decisions only.

## The mob

Three read-only architect legs, independent families, identical briefs (`1794343683000-architect-cc3-d1-mob.<model>.prompt.md`):

| Leg | Family / lane | Conversation / thread | Findings |
|---|---|---|---|
| gpt-6-astra | GPT / codex | thread `01a1c738-ae96-73f2-9766-3b99769c0a19` | `.gpt-6-astra.findings.md` |
| gemini-4-argon-high | Gemini / agy | conversation `4b7ced60-62f7-4668-82a3-c72aececc945` | `.gemini-4-argon-high.findings.md` |
| deepseek-v4-pro | DeepSeek / deepseek | session `5e8d6062-9ea6-4e31-b677-d9e8515b461b` | `.deepseek-v4-pro.stdout.log` (verdict inline) |

The orchestrator spot-checked load-bearing claims before reconciliation: `slice9_site_tables.edn` has no readers outside `latte_oracle_test.clj` + docs (grep); `derive.cljc` stack-image side-table carries only `:closure`/`:var` diagnostics, no `:call` provenance (source read). Both deepseek claims held.

## Q1 — cc3 route (linearizer-only) sufficiency: 3/3 SUFFICIENT, with binding conditions

All three approve route (a): extend `yin/vm/semantic/linearize.cljc` + `semantic/derive.cljc` (native-image PC→K reconstruction is LOWERING, not evaluator runtime — inside the yang.clojure seat's authorized surface) and bind oracle sites via verified static correspondence. No `ast_walker`/`semantic`/`stack`/`register` runtime changes; general runtime caller-K propagation remains a continuation-seat boundary item (3/3 agree it is NOT required for cc3 or D1).

The binding conditions (union of the three, each traceable to the design):
1. **Coverage separation** (astra): the oracle's site table covers SELECTED guest-call shapes, never the complete C1 application set. The verified bijection is oracle-site ↔ its selected compiled occurrence; full C1 coverage is verified independently, including uncredited helpers, duplicate-content occurrences and transform multiplicities.
2. **Retained-history binding + honest origins** (astra, corroborated): `kernel-compile` compiles FIRST and stamps by insertion afterward — the scoping's chronology was wrong. cc3 must account for that transformation, and `[:oracle/fixture <id>]` alone is NOT a valid persisted origin; a fixture id may only supply a recorded admission coordinate per `yin/vm.cljc:938-958`'s validator.
3. **Static ≠ runtime** (astra): the shared "latest site" guest cell cannot prove preservation across suspension/interleaving; cc3 verifies its correspondence claims in both lanes and refuses ambiguous cases; it must not claim migration coverage.
4. **Provenance must become durable** (deepseek, argon): `publish-closure!` (`publish.cljc:312`) and `yin.repl` (`repl.cljc:158`) discard `:provenance` today; cc3 persists it as a side artifact OUTSIDE the schema-1 manifest hash (the cc1 precedent, `publish.cljc:352-367`).
5. **Datom lane** (deepseek): `project-datoms` (`linearize.cljc:283-302`) returns `[pc entity]` with no K bridge — cc3 either restricts to the row lane or lands the entity→occurrence bridge.
6. **Numeric preflight bounds** (astra, design §6.3): finite ceilings for rows/bytes/records/steps declared BEFORE any tagged run; no silent raising of oracle fuel.

## Q2 — D1 evidence format: 3/3 SEPARATE COMPANION FILE

Do NOT upgrade `slice9_site_tables.edn` in place. D1 is a separately addressed `:yang.correspondence/schema 1` record (companion `test/yang/latte_oracle/slice9_correspondence.edn`) whose `:evidence` names the `:version 1` tables VERBATIM (design §4.1: "do not project a subset or copy its rows into a second durable site-table shape"; §4.2: "D1 does not revise old site tables"). K-keyed access is a verified query through the `:origin-bindings` relation, not a second attribution table. Integer site ids are scoped per compilation (they restart); historical runs without retained admissions never acquire invented origins — a new recorded run is produced instead (owner ruling U: origins are never minted).

Corroborating facts: the only executable v1 readers are the round-trip test (`latte_oracle_test.clj:3412`) and the manifest filename string (`:3247`); the round-trip test is weak (inline tables removed first; nil payloads can satisfy version+count assertions) — astra's flag, to be hardened when D1 lands, not silently relied on.

## Q3 — ordering: 2/3 cc2 → cc3 → D1 → cc4 (final)

- argon + astra: D1's independent verification lands BEFORE cc4's final four-host/budget acceptance (design §8 step 4→5: a frontend acceptance that relies on correspondence cites a completed D1 verification; astra adds that cc4's final measurements must include D1's retained reconstruction inputs).
- deepseek (dissent, recorded): cc3 → cc4 → D1, with cc4/D1 parallelizable — its dependency analysis is IDENTICAL to the majority (cc4 ⫫ D1, both depend only on cc3); the disagreement is solely which gate counts as the frontend's "final" acceptance. Its concern (D1 should reconstruct the final post-cc4 compiler) is answered by the majority: cc4's final acceptance after D1 means the compiler D1 pins IS the final one.
- Adopted: **cc2 → cc3 → D1 → cc4-final**. cc4 preparation (static equality checks, baseline measurements) may start in parallel with D1, but cannot self-report acceptance.

## Consequences for this seat

1. cc3's brief will carry Q1's six conditions as acceptance criteria, and Q2's format as the D1-adjacent constraint (cc3 publishes the provenance side artifact; D1 consumes it).
2. cc4 dispatches after D1's verification, with preflight bounds declared before cc3's tagged runs.
3. The continuation-seat boundary holds: nothing in the adopted plan touches evaluator runtime. The reply note of record (`1794342913000-to-continuation-seat-reply-cc2-dispatched-cc3-boundary.md`) promised exactly route (a) — the mob confirms it is sufficient, so no boundary renegotiation is needed.
4. Owner risks surfaced for morning review: the stale-site diagnostics tension (scoping expects empty diagnostics for passing fixtures; the current oracle records NONEMPTY diagnostics at `latte_oracle_test.clj:3248` — preserve and reproduce, do not silently strengthen the historical contract); the weak round-trip test; single-origin scope of cc3 (multi-origin runtime-origin retention explicitly deferred to a production-runtime round, not silently done).

— yang.clojure orchestrator seat (mob reconciliation, owner-delegated)
