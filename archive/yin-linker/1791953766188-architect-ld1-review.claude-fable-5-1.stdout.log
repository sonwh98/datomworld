Completed-GMT: 2026-10-14 05:00:46 GMT
Completed-Local: 2026-10-14 12:00:46 +07
Coding-Agent: claude
Session-ID: 4f892b09-722d-4e48-a630-fb78449bddcf

# L-d-1 architectural review: `try-require`, refusal-as-data, spec diff

Verdict: **ARCHITECTURAL_SIGN_OFF**, with three ledger entries required at commit. One is a P1 the design itself got wrong, outside this slice's files, and it must close before I4-a consumes `try-require`.

Sources read: `datom.world.md` axioms and invariants, `yin.vm.linker.md` §7.2 step 7, `yin.vm.linker.dht.md` §7.5.3 and §9, `architect.md`, the I4 scoping design and mob consolidation (both in the main checkout's `collab/`), the engineer and reviewer findings, the full uncommitted diff, and the UCF carrier code the diff does not touch (`ucf/rows.cljc`, `ucf/handoff.cljc`, `ucf/remote.cljc`).

## 1. Foundational invariants

- **A is the only code identity. Preserved.** Under `try?` the handler takes the same branches as `require` and only the resumed value differs. `settle` still retires the id, runs the name check, the discharge check and `start-install`. `link-install` still runs export, pin, lift and foreign-image checks before any receive. Nothing about what installs changes, only who learns of a refusal. Checked by reading `module.cljc:545-604` and `engine.cljc:1153-1170, 1510-1555`.
- **No hidden state, no implicit control flow. Improved.** The refusal that was a raise at `refused-entry` becomes a value in the entry's `:value` and reaches the continuation through the ordinary ready queue. `throw-terminal-resume!` is untouched: a try entry carries no `:status`, so the generic resume path delivers the datum. There is one exception, see §2 below: after a UCF lift/lower the contract flips silently, which is exactly the implicit control-flow change the invariant forbids.
- **Language-blindness. Preserved.** The source diff contains no Python, import, frontend or ANTLR vocabulary. New words are `try-require`, `:module/try-require`, `:try?`. The link request envelope and the closed key set the interpreter, admission and DHT source read are unchanged.
- **Confinement of the macro environment. Preserved.** `macro.cljc:666` now dissocs `try-require` beside `require`, so a macro body cannot observe linking. The reviewer's P2 is closed.

## 2. Subsystem boundaries and layering

- **Outcome delivery only.** Every refusal path funnels through `refused-entry`: settle (response, name mismatch, discharge defect, loader defect), `link-read-step` (gap and terminal outcomes), `refuse-install` via `wake-installed`, `abandon-link`, `abandon-installs`. Both success wakes go through `linked-entry`. Per-waiter delivery on one install is correct and tested with real handlers.
- **Handler registration and park fallback. Sound.** `(or try-require-builder require-builder)` is the `:stream/poll` precedent. No kernel supplies a `:module/try-require` builder, so all five backends park through the `require` builder with `:try? true` assoc'd on plain registers. Restore ignores the extra key. Tests pass on walker, semantic, semantic-register and both de Bruijn kernels.
- **P1, UCF seat, design-originated. `:try?` does not survive lift/lower.** The pending-entry encodings are closed. `rows.cljc:1014-1016` lists the slots for `:link-request`, `:link-response` and `:install`; `handoff.cljc:2036-2042` validates closed required/optional sets; `remote.cljc:368-393` lifts link entries field by field. None carries `:try?`, and `:install` carries only `:name`. A continuation parked on `try-require`, checkpointed, handed off or served remotely, then lowered, resumes as a plain `require` waiter: a refusal raises instead of returning data, and success returns a symbol instead of the ok map. The scoping design (§1.2 and its portability paragraph) asserted the new keys "ride lift/lower unchanged". That assertion was mine and it was wrong. This is a defect in the architectural contract, not an engineer deviation, and it is in the UCF seat's files, outside this slice. Under the Q6 rule it is not blocking here. It must be ledgered as P1 with closing slice L-d-1b, landing before I4-a, because I4-a is the first consumer. The fix is small under the no-compat policy: one optional boolean slot on the three pending variants, in rows, handoff validators and remote lift, plus a round-trip test.
- **P3, linker. Joiner `:link-id`.** `module.cljc:572` gives a try waiter that joins an in-flight install the install's `:parent` link id. On `abandon-installs` the joiner gets a loss datum naming an id it never held. The same key is also what UCF drops for `:install` entries, so after a lower the loss datum has no id at all. Recommended correction, folded into L-d-1b: install waiters carry no `:link-id`; the `abandon-installs` loss datum carries `:module`, matching the `:refused` shape; `:link-id` appears only on `abandon-link` losses, where the entry truly holds the id. That shrinks the carried state to the one `:try?` flag.

## 3. Specification and contract coherence

- The three edits are minimal and mutually consistent. `linker.md` step 7 names the primitive, the effect, the seat, data instead of raising, the ok shape and the per-waiter rule. `dht.md` §7.5.3 ties admission refusals to the same delivery. `dht.md` §9 states the data-versus-raise split under the exact-shape table.
- **Nit, P3 docs.** Neither doc pins the two additions the engine makes to a try datum: `:module` on a `:refused` map lacking one, and `:link-id` on a `:lost` map. The scoping design said `:link-module`; the implementation chose `:module`, which matches install refusals and is the better choice. One sentence in §9 should say so, in L-d-1b with the loss-shape change above.
- **Deferred, other seat.** Mob action 2.2 asked `yang.antlr.md` §8.5.6 to say `module/try-require` is an effect kind, not a host module. That file belongs to the yang.python track. A collab note is needed; nothing here.

## 4. Readiness

The slice meets all seven acceptance lines, the targeted and JVM subsystem lanes are green, the kondo delta is empty, and the reviewer's P2 and test typo are closed in the diff. L-d-2 (pin invariant) depends on none of the gaps above. Ready to land once the ledger carries:

| gap | evidence | owner seat | closing slice | severity |
|---|---|---|---|---|
| `:try?` not carried by UCF pending entries; a lifted try waiter resumes under the `require` contract | fable L-d-1 architect review; `ucf/rows.cljc:1014`, `ucf/handoff.cljc:2036`, `ucf/remote.cljc:368` | UCF | L-d-1b, before I4-a | P1 |
| try joiner's `:link-id` is the install's parent id; drop `:link-id` from install waiters, loss datum carries `:module` | fable L-d-1 review P3-1 | linker | L-d-1b | P3 |
| `publish/requirements` pins only operator `require`; module-level `try-require` yields no pin | fable L-d-1 review P3-3 | linker | L-d-3 | P3 |

Also outstanding from the mob, not this slice: the Q1 ledger line for publication-time-unavailable imports has not been written yet.
