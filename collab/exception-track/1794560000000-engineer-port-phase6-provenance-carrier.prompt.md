Created-GMT: 2026-11-12 22:58:00 GMT
Created-Local: 2026-11-13 05:58:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 9fe7a945-b811-4dd8-b871-1b6f85653ee7
# Task: engineer — PORT Phase 6: implement the provenance carrier per sol's consult (exact path)
Role: Engineer (implementer family codex; the carrier design is sol's consult; qwen re-reviews after)
Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-13 05:58 +0700 | Status: active | Rationale: continuity; sol's spec names YOUR branch state as the baseline.

## Read first (the design is DONE — implement it exactly)

`/Users/sto/workspace/datomworld/collab/exception-track/1794559000000-consult-carrier-authentication.gpt-6.1-sol.findings.md` (ABSOLUTE — r3-orch has no collab/)
— CARRIER RESOLVED VIA PROVENANCE, with the exact mint/carry/authenticate path, the generated
egress snippet, the saved-case analysis, and the ledger claim language. Worktree
`/Users/sto/workspace/datomworld-r3-orch` (launch cwd), branch `exception-r3-impl` @ `f15423c2`.

## The implementation (sol's section 3, in order)

1. **Mint at trusted protocol reception**: in `compile-guest-call`'s raise exit (`:1396`),
   qualified protocol conditions `c` are delivered as `(cell/dsg__new-provenance c c)`; `:thrown`
   keeps the raw path. Same constructor at the compiler-generated refusal sites (`:1409`, `:2378`).
   A source `(throw condition-shaped-map)` NEVER mints.
2. **Carry the carrier reference unchanged** through clj/throw, clj/raise, catches, finally
   unwinding — no decode-into-visible during transfer or saving.
3. **Authenticate at public egress**: replace `call-entry` handler's unconditional fresh-thrown
   (`:1211`) with the query-once-and-forward pattern (`(let [p (cell/dsg__provenance e)]
   (raise-exit (if (nil? p) (yang.call/thrown e) p)))`). Never inspect visible contents, never
   require a frontend tag, never apply `e`. Emit protected primitives.
4. **Construction authority private**: direct generated UAST; any helper uses protected `dsg__`
   names outside exports/projections/aliases.
5. **Protocol observation projections**: condition lookups/authenticate-then-project accessors
   allowed; projection must never replace the reference used for throw/save.
6. **Pins**: content-clone loses provenance; alias retains authority; overwrite-visible forwards
   original condition; a thrown closure's body NEVER runs at egress; saved rethrows forward
   byte-identical (the kit's retain/later shapes). Replace the stale "no carrier" test
   assumption (`clojure_test.clj:1866`).

## Constraints

- No kit files; no VM files (`src/cljc/yin/vm/` empty-diff; provenance doors already on master).
- Preserve the intra-unit private tail lane (direct body application `:1213` — no handler added
  there; tail-mark final raise calls); depth gates RE-RUN at acceptance (fixed work at boundaries
  is expected compatible; measure, don't assume).

## Verification

`clojure -M:test -n yang.clojure-test` green + the new pins; `yang.call.python-exception-test`
and shared host suites where touched; depth harness re-run producing the comparison refresh.
kondo 0/0. **Contract test remote-only** — the orchestrator runs the kit on shiva at your SHA.
Commit + push. End with Completed-GMT/Completed-Local and: **CARRIER LANDED: <sha>** or
**BLOCKED: <what>**.

---
## Provenance correction (append-only, orchestrator)
- The `Session-ID:` header value above was FABRICATED — codex assigns IDs (thread.started);
  caller-selected session IDs do not apply to this CLI.
- Real thread id of the first attempt: `01a1d1ee-523c-7be2-b321-8eb8aae65cb7` — captured from `thread.started` in
  `...gpt-6.1-terra.stdout.log`. The r2 resume failed on the fabricated id ("no rollout found");
  the r3 resume used the captured id. Rule for this seat: for codex/agy, ALWAYS capture the
  generated id from the first run's structured output before any resume; the brief header gets the
  captured value, not an invented one.
