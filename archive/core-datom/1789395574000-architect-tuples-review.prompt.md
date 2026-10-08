Created-GMT: 2026-09-14 14:19:34 GMT
Created-Local: 2026-09-14 21:19:34 +07
Coding-Agent: codex
Session-ID: 01a09fcc-3b9f-7172-b592-f15348d6c88b (resume — your own review thread)

# Task: Review the tuple code-representation design

Role: Architect (reviewer)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-14 21:19:34 +07 | Status: active | Rationale: you reviewed the UCF document twice on this thread and endorsed the owner's tuple rulings; the author of this new document is claude-fable-5-1 (Anthropic family) — independence preserved. The orchestrator (glm-5.3) verified the draft's surprising claims against source before dispatching you.

## Context

After your UCF re-review (verdict REJECT, findings in `collab/1789392400000-architect-ucf-rereview.gpt-6-astra.findings.md`), the owner settled seven rulings in an interactive session with the orchestrator, each verified live where checkable:

1. The Universal AST becomes flat tag-first positional tuples (Erlang abstract-format shape); walker semantics unchanged; motive is Unison-style content-hashing.
2. Fixed arity per tag; operand vectors; `tail?` a saturated trailing boolean; saturation of loader defaults; no source positions or `:macro?` in canonical form (side tables keyed by address).
3. `dao.space.query/q` performs Datalog over these tuples directly (verified: exact-arity positional unification in `eval-pattern-clause`→`unify-slots`, query.cljc:894/799; the 3-slot EAV fast path at :899 engages only for explicit `fact-relation` values).
4. The semantic VM is designed around this: tuples canonical at both levels (AST nodes, segment instructions); direct tuple loading primary, datom loading the projection; validation = the tuple grammar; the UCF §7.6.1 dependency fixed point recasts as Datalog; the hot loop never queries.
5. Code-on-stream is tuples end-to-end.
6. `t` and `m` are higher-level tuples over code addresses — the hash chain IS the ledger; open op vocabulary (`:assert`/`:retract`/`:derive`/`:expand`/`:publish`); `:yin.code/derived-from`, the macro ledger, and UCF §7.7's occurrence ledger are rows of one relation.
7. Datoms are the reference layer: `[e a v t m]` with `a` like `:yin/code`, `v` = the content address (git refs / Unison namespace). Objects / refs / projection layering.

claude-fable-5-1 then drafted `docs/design/yin.vm.tuples.md` (780 lines, untracked, one file, nothing else touched) against a charter containing exactly these rulings as settled inputs.

## Task

Review `docs/design/yin.vm.tuples.md` on its merits. The seven rulings are owner decisions — their implementation is in scope, the rulings are not.

1. **The grammar (§2):** check the table against the actual arms — `ast_walker.cljc:359-469` (cold case), `:600-626` (hot inline arms), the codec's case at `v2.cljc:413-478`, the linearizer at `linearize.cljc:91-160`. Is every dispatch tag covered with the right arity and slot kinds? Are the subtle calls right — `:vm/store-put`'s value slot is data (stored unevaluated) while `:vm/resume`'s value slot is a node (evaluated)? Is the `tail?`-on-`:application`-only rule sound given the linearizer's single read at `linearize.cljc:107-108` and the yang frontends' unobservable marks?
2. **The boundary calls (§3):** `:vm/store-update` excluded (the doc argues an `:application` of `yin/def` over `:vm/store-get` already expresses it, so a tag would mint a second address for one program) and `:stream/close` kept with the walker gaining the arm (the doc claims every other component already implements it). Challenge both arguments.
3. **Addressing (§4):** determinism and order-sensitivity claims vs `order-normalize` (`jing.cljc:45-64`); is the inherited transitional-encoder blocker honestly declared (not claimed closed)?
4. **Querying (§6):** do the stated mechanics match `query.cljc` (exact-arity matching, the fast-path condition, `match` not binding, `:fns` keys)? Are the three datom-projection query classes the right residue?
5. **The VM (§7):** load paths, both-paths-same-image as conformance, validation-as-grammar, dependency closure as Datalog — sound, implementable, and consistent with UCF §7.3/§7.6?
6. **The ledger and refs (§8):** the synthesis of rulings 6+7 — ledger ops mapped onto the datom `m` slot, two new reserved ids (`:yin.ledger/expand` 3, `:yin.ledger/publish` 4, after `{:db/retract 0, :db/assert 1, :db/derived 2}`), derived-from/macro/UCF-occurrence as rows of one relation. Is the synthesis coherent, and does anything in it contradict `dao.datom`'s reserved-`m` semantics or `dao.lease.md`?
7. **Migration honesty (§9):** the sweep counts, the observer-lane and `executable-program-datom?` flags — understated or overstated?
8. **Blockers (§10):** anything missing that should be a blocker, or listed as a blocker that isn't?
9. New defects are P1s regardless of origin. Cite file and line for everything.

## Deliverable

Verdict (`APPROVE` | `APPROVE-WITH-FINDINGS` | `REJECT`), numbered findings with severity/line/defect/fix, and a short "verified sound" list. Ground everything in the document and the sources. Produce the complete review in this turn — no plan-only responses, no requests for human input.

Begin the final response exactly with:

Completed-GMT: <actual GMT timestamp>
Completed-Local: <actual local timestamp and named timezone>
Coding-Agent: codex
Session-ID: 01a09fcc-3b9f-7172-b592-f15348d6c88b
