Created-GMT: 2026-11-09 18:24:00 GMT
Created-Local: 2026-11-10 01:24:00 +07
Model: gpt-6-astra
Role: Lead System Architect
Session-Type: read-only
Worktree: /Users/sto/workspace/datomworld-yin-repl-dht
Branch: yin-repl-dht-composition
Base-Commit: 71dd717da07ce23a744aed4fe10ccbeabeae9333

# Architectural Review Findings: Step 2 — Engine/Composition Separation & Adapters Extraction

**Recommendation: withhold Step 2 sign-off.** I reviewed the uncommitted changes against `71dd717da07ce23a744aed4fe10ccbeabeae9333`. The extraction makes useful progress, but neither part of the §9 exit condition—“Engine owns no DHT state or dependency; local synchronous behavior preserved”—is fully satisfied.

The following findings block approval:

1. **[P1] The engine still loads the DHT implementation.**  
   `yin.repl` still requires `yin.vm.linker.interpreter`, whose namespace imports include `dao.space.dht` and `yin.vm.linker.dht`. `round-local` adds a node-free wrapper but does not separate the dependency closure, which §2 explicitly requires.
   *Verified:* in a fresh JVM, requiring only `yin.repl` left both `dao.space.dht` and `dao.jing.dht` loaded. Split the generic linker machinery from DHT serving, or move interpreter ownership entirely to composition.

2. **[P1] Publication still invokes an injected HEAD callback inside the engine.**  
   Engine construction extracts `:head-fn` into `:publication-media`. The indexer then calls `service-local!`, which invokes that function directly (`src/cljc/yin/repl/publication.cljc:89`). Adding request/result records around this call does not establish the composition-owned interpreter required by §§3–4.
   Store selection also remains in `create-state` through `store/checked-spec` and `store/open`; reset retention still uses `store/durable?`. Composition must own these decisions, pump publication requests, and deliver results before reporting local evaluation settled.

3. **[P1] Registered composition calls are consumed and refused by the engine.**  
   Local query serving defers requests only when `registered-ops` is supplied. The engine caller never supplies it, and no other code wires it.
   *Reproduced:* after constructing an engine with `shell.query/registrations` and requiring `yin.head`, `(yin.head/heads)` returns `:dao.stream.apply/unknown-operation`. Its request has already been consumed before composition can answer. Carry the registered operation identities through the generic service contract while preserving local refusal of genuinely unknown operations.

4. **[P1] Composition progress does not resume the parked VM.**  
   Session tick handling calls `repl/recheck-pending` after serving a response. That operation passes `false` as initial progress; the driver therefore requires another serving round to make progress before running the VM. Once composition has consumed the request and delivered its response, that additional progress need not occur.
   *Reproduced:* a parked local require received one response through `session/recheck-on-tick` but remained parked. Calling the internal recheck with initial progress `true` completed it as `mod`. The public completion path must explicitly carry readiness into the driver and consume the response once.

5. **[P1] A new publication overwrites an outstanding settlement before consuming its result.**  
   Publication emission unconditionally replaces `:pending-publication`. Both `step` and `commit-names` publish before settling queued results.
   *Reproduced:* emit request 1, append its successful result, then commit another transaction. The resulting state has `:published 0`, `:transactions 2`, and pending request 2: request 1’s queued success was discarded as mismatched. If request 2 fails, the engine does not retain request 1’s successful frontier. Consume available results first and explicitly serialize or retain outstanding publications.

6. **[P1] Signed publication answers success before publication completion.**  
   The extracted `link-answer` calls `commit-names`, checks only whether `:failure` changed, and immediately constructs an `:ok` response. With externally serviced publication media, `commit-names` can return a pending request with no failure.
   This violates §7’s requirement that signed publication await local publication completion. Retain the call correlation until its envelope commit receives a matching publication result; a later refusal must reach that call.

**Protocol / Adapter Test Oracle:**
The reported 102 tests / 1,396 assertions are useful regression evidence, but they do not establish the new boundary. In particular, the Step 2 protocol oracle (`test/yin/repl/index_test.cljc:1081`) still tests `fixture/accept-result`, not the production result consumer. The common constructor still injects the combined backend handle rather than separately composing content, recovery, policy, and publication service media.

**Disposition:** Retain this as intermediate refactor work, not a completed Step 2. Re-review after fixing the six blockers and moving the protocol/adaptor assertions onto the actual engine/composition path. Full boundary-merge acceptance remains a separate later gate.
