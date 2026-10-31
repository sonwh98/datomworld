Created-GMT: 2026-10-16 09:10:55 GMT
Created-Local: 2026-10-16 16:10:55 +07
Coding-Agent: codex
Session-ID: 01a143f7-0693-7a32-9229-1fccbcc526a9
# Task: mob consensus review of the 6d plan v2 (any-pc snapshots) — codex gpt-6-astra findings
Role: Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-16 16:06 +07 | Status: completed | Rationale: one of three mob reviewers (codex gpt-6-astra, muse muse-wonder-1.0, glm glm-5.3); reviewed Fable's 6d plan v2 against origin/master@c6bc91f7 read-only; final message promoted verbatim below by the orchestrator (reviewed checkout recorded as c6bc91f7aaa7efa4fff5694fd41384cb943b6404)

MOB-VERDICT: NOT-READY

Reviewed checkout: `c6bc91f7aaa7efa4fff5694fd41384cb943b6404`, including module-pins commit 2. I read both supplied reports completely and checked the relevant design and implementation paths. This was a source-based review; no tests were run and no files were modified. Source paths below use the actual `src/cljc/` layout.

V2 substantially improves the architecture, but its ready-outcome grammar and fuel implementation still exclude or mishandle supported execution states. Those are 6d-a blockers under the stated defaults.

**Disposition of the nine findings**

1. F1 — NOT-ANSWERED (v2 §§3.1–3.2 carry ordinary wakes, but exclude existing link/install wakes and do not correctly specify cursor-update census handling).
2. F2 — ANSWERED (v2 §§3.1–3.2, §3.9 acceptance 3: active control and remaining waits form a supported product).
3. F3 — NOT-ANSWERED (v2 §3.4 propagates child budgets, but `ffi/maybe-run` can invoke additional slices with fresh budgets inside the same call).
4. F4 — NOT-ANSWERED (v2 §3.3 specifies expiry authority and recovery correctly, but associating intermediate immutable states does not make the outer exception handler receive them).
5. F5 — NOT-ANSWERED (v2 §3.5 repairs jump layouts and addresses tail residuals, but §3.9 acceptance 7 retains an invalid cardinality equation).
6. F6 — ANSWERED (v2 §§3.1, 3.9 preserve the halted grant exemption and require a regression).
7. F7 — NOT-ANSWERED (v2 §§3.2, 3.6 widen frame traversal, but omit the new ready outcome’s value from the claimed full-payload primitive oracle).
8. F8 — ANSWERED (v2 §3.6 specifies immediate relift, isolated worlds, structural coverage and negative controls).
9. F9 — NOT-ANSWERED (v2 §§3.7–3.8 correct most terminology, but retain unsupported one-cycle hold claims and omit UCF’s explicit “Quiesce” export step).

**New or surviving defects in v2**

1. **P1 — Supported link/install completions remain unrepresentable.**  
   **Plan:** §§3.1, 3.11 treat link wakes as an unverified possibility and permit refusing them as a follow-up.  
   **Evidence:** `src/cljc/yin/vm/engine.cljc:1327–1341` explicitly queues every completed `:install` waiter; `:1627–1643` explicitly queues link outcomes. Successful entries may have no status; failed ordinary requires carry `:refusal` (`:1150–1173`). Moreover, `rows.cljc:1559–1561` requires an install pending to name a still-present install, whereas completion removes that install.  
   Merely expanding the ready reason allowlist cannot fix this: the proposed “completed wait validated exactly as a pending wait” model imposes obsolete dependencies and omits error data.  
   **Minimal fix:** Define completed outcomes for all existing queue producers, including link/install success, try outcomes and raised refusals; separate completed-state validation from outstanding-pending validation. Add queue-plus-active tests after install completion and link refusal. This belongs in 6d-a.

2. **P1 — Cursor-update rows conflict with the existing census algorithm.**  
   **Plan:** §3.2 says `cells-node` uses `cell!` to carry another position of the same cell while leaving the remaining census walk unchanged.  
   **Evidence:** `src/cljc/yin/vm/ucf/rows.cljc:1388–1393` stores only the first body for a source identity. `walk-cells` at `:397`, numbering traversal at `:468`, and decoded validation at `:819` skip **every** `:cursor-cells` row, not just task slot 9.  
   Consequently, an additional `:cursor-cells` row is not an ordinary traversed update map, and `cell!` cannot represent multiple bodies for one identity as described. Shared-cell wakes can carry different updates; resume merges each update (`engine.cljc:2299–2307`).  
   **Minimal fix:** Specify a separate update row, or explicitly distinguish census ownership from update references throughout numbering and validation. Keep one cell identity while independently encoding each update body. Test multiple queued outcomes sharing a cell at different positions, including immediate relift and subsequent resume.

3. **P1 — The advertised per-tick instruction bound is false.**  
   **Plan:** §3.4 wraps the budgeted runner in unchanged `ffi/maybe-run`, while promising at most `n` instructions per task per program tick.  
   **Evidence:** `src/cljc/yin/vm/ffi.cljc:334–350` calls `run-fn` again after every handled bridge operation. The proposed closure supplies `n` afresh each time. A sequence of short computations separated by handled FFI calls can therefore execute multiple budgets in one invocation.  
   **Minimal fix:** Carry remaining fuel across bridge iterations, or make the fuelled entry return after a bounded bridge/scheduler transition. Specify bounds for diagnostic-only bridge loops as well. Test repeated bridge handling, not merely one bridge pass.

4. **P1 — “Fork-only 6d-a” is not enforced by its specified changes.**  
   **Plan:** §3.9 describes 6d-a as fork-only, but adds running kinds to custody validation and restores running exclusive/fenced roots; the kernel guard is deferred to 6d-b.  
   **Evidence:** `src/cljc/yin/vm/ucf/rows.cljc:2071–2088` permits administrative fenced lowering without a grant. `src/cljc/yin/vm/semantic.cljc:137–143` executes active control without checking the gate.  
   The proposed 6d-a therefore exposes a running fenced state that the existing entry point can execute.  
   **Minimal fix:** Ship the entry guard with 6d-a, including direct `vm/step`, `vm/run`, and `run-fuel` checks; alternatively explicitly reject running exclusive/fenced lowering until 6d-b.

5. **P2 — Completed-op validation contradicts the proposed fork rule.**  
   **Plan:** §3.1 prescribes `check-op-id!` for completed writes while allowing those writes in fork history; §3.10 default 6 ratifies this.  
   **Evidence:** `src/cljc/yin/vm/ucf/rows.cljc:1243–1259` does considerably more than shape validation: it requires root origin, rejects the root’s own occurrence, and requires a root counter greater than the operation sequence. A fork root does not supply that custody context.  
   **Minimal fix:** Separate historical operation-ID shape validation from retained-write custody validation. Specify optional IDs for ungated completed writes and test completed-write history under both fork and exclusive roots.

6. **P2 — The static acceptance equation cannot count duplicates as stated.**  
   **Plan:** §3.9, 6d-a acceptance 7 requires `|layout(p)| + |uses(p)| = |L(p)|` “with duplicates counted.”  
   **Evidence:** `src/cljc/yin/vm/semantic/analysis.cljc:45–64` defines `L` as a set. Stack emission preserves ordered operand positions (`derive.cljc:92–97`). Repeated virtual operands cannot increase the cardinality of `L`; dead ambient tail operands also invalidate an unconditional cardinality equality.  
   **Minimal fix:** Remove this equality. Check ordered region length against the ordered operand/layout sequence, check repeated-register values for agreement, and separately check the appropriate set equation and tail rule.

7. **P2 — The ready value escapes the “full-payload” refusal oracle.**  
   **Plan:** §§3.2 and 3.6 inspect a ready entry’s saved frame but not its `:V` outcome.  
   **Evidence:** `src/cljc/yin/vm/semantic/layout.cljc:853–874` traverses frame windows, lexical bindings and K; it does not inspect a future delivered value. `semantic.cljc:115–118` places that delivered value into the destination window.  
   A primitive, including one nested in a collection or capture, can therefore become live only when the ready outcome resumes.  
   **Minimal fix:** Include ready `:V` in both implementation traversal and independent oracle. Add a register-target case where the only unsupported value is in the queued outcome.

8. **P2 — Intermediate retention still needs an explicit exception-state channel.**  
   **Plan:** §§3.3 and 3.9 say that associating `m1` before the next check makes `step-active`’s catch hold the latest state.  
   **Evidence:** `src/cljc/yin/vm/ucf/holder/driver.cljc:2117–2146` computes local immutable values; the catch at `:2252–2262` closes over its original `state` argument. Inner rebinding does not update that argument.  
   **Minimal fix:** Return explicit stage outcomes carrying the latest state, move catches to stage boundaries, or attach the retained state to the propagated failure. Pin exact retained machine equality at each tenure cut. The recovery-authority policy itself is sound.

9. **P2 — Holds are not guaranteed to resolve within one cycle.**  
   **Plan:** §§3.7 and 3.10 default 8 describe holds as mid-step seams resolved by the next cycle.  
   **Evidence:** `src/cljc/yin/vm/ucf/holder/reader.cljc:484–496` preserves a held observation when append returns `:dao.stream/full`; `:577–579` retries it on subsequent steps. Backpressure can persist indefinitely.  
   **Minimal fix:** Remove the one-cycle guarantee and specify the admissible suspension/export boundary and recovery behavior while acknowledgement remains outstanding. A known-gaps row alone does not establish the promised normalization.

10. **P2 — The document inventory leaves an explicit contradictory export requirement.**  
    **Plan:** §§3.7.3 and 3.8 amend export step 3 and adjacent prose but omit step 1.  
    **Evidence:** `docs/design/yin.vm.universal-continuation-format.md:1904` still says: “Quiesce: the ready-queue must be empty.”  
    **Minimal fix:** Replace that step with retention of the ordered queue in the export record, synchronized with the implementation.

**The eight §3.10 defaults as reviewed**

| Default | Decision | Reason |
|---|---|---|
| 1. Separate task slots 17/18; remint fixtures after module-pins | **SAFE** | Separate typed fields are appropriate, although the ready/update grammar needs the fixes above. |
| 2. Holder `:fuel`, default nil | **SAFE** | This is a valid opt-in scheduling policy, provided nil is explicitly documented as offering no instruction-budget preemption. |
| 3. Explicit `hand-off`; no automatic export | **SAFE** | Export policy can remain composition-owned without changing representability or custody authority. |
| 4. Retained boundary is inspection-only; recover the durable checkpoint | **SAFE** | It gives expired bytes no execution authority and explicitly acknowledges lost progress. |
| 5. Keep edge-reachability liveness and pin `ghost` refusal | **SAFE** | Conservative dependency refusal is a documented portability limitation, not missing state representation. |
| 6. Completed writes excluded from retained writes and `protect!` | **UNSAFE as stated** | The non-reappend policy is correct, but the mandated validator rejects the fork history the default explicitly permits. |
| 7. Relabel stack `:push` to its successor | **SAFE** | The opcode only increments PC; no guest operation is required for normalization. |
| 8. Holds remain refusals and resolve within one cycle | **UNSAFE as stated** | Persistent backpressure demonstrably preserves holds across cycles. |

**Citation sample**

The plan abbreviates source filenames. These checks use their actual paths; “shifted” means the cited behavior holds but the old line number no longer identifies it exactly.

| Plan citation | Checked worktree location | Result |
|---|---|---|
| `engine.cljc:2211,2216` | `src/cljc/yin/vm/engine.cljc:2211–2219` | Holds: active execution precedes queue draining. |
| `engine.cljc:2305–2312` | same path, `:2305–2312` | Holds: resume retains unresolved waits. |
| `semantic.cljc:116–118` | `src/cljc/yin/vm/semantic.cljc:116–118` | Holds: register restoration materializes control. |
| `semantic.cljc:142–143` | same path, `:142–143` | Holds: exhausted fuel materializes registers. |
| `engine.cljc:1475` | `src/cljc/yin/vm/engine.cljc:1475` | Holds: child executes through `vm/run`. |
| `ffi.cljc:334–348` | `src/cljc/yin/vm/ffi.cljc:334–350` | Holds; also exposes renewed-budget defect. |
| `layout.cljc:118–131` | `src/cljc/yin/vm/semantic/layout.cljc:118–131` | Holds: layouts cover parse nodes only. |
| `stack.cljc:722–723` | `src/cljc/yin/vm/debruijn/stack.cljc:722–723` | Holds: `:push` only advances PC. |
| `stack.cljc:731,747` | same path, `:731,747` | Holds: tail closure entry retains the residual stack. |
| `rows.cljc:2039` | `src/cljc/yin/vm/ucf/rows.cljc:2087` | Shifted; halted/fenced grant exemption holds. |
| `rows.cljc:1862–1863` | same path, `:1890–1891` | Shifted; child-kind check requires blocked. |
| `layout.cljc:947–950` | `src/cljc/yin/vm/semantic/layout.cljc:947–950` | Holds: wait-state and parked-state primitive traversal. |
| `driver.cljc:2252–2253` | `src/cljc/yin/vm/ucf/holder/driver.cljc:2252–2262` | Catch behavior holds; exact `end-run` location shifted. |
| Tuples `:913–917` | `docs/design/yin.vm.ucf-transport-tuples.md:934` | Shifted; law 2 still forbids intervening execution. |
| UCF `:1912–1913` | `docs/design/yin.vm.universal-continuation-format.md:1922` | Shifted; “no engine change” text remains. |
| UCF `:2842` | same path, `:2851` | Shifted; blocked-machine/hot-loop prose remains. |

The plan’s claim that link wakes “today restore directly” does **not** hold; `poll-links` and `wake-installed` explicitly enqueue them.

**What 6d-a additionally needs**

Before ratification, the brief needs a complete completed-outcome contract, explicit census/update traversal rules, a fuel budget that survives bridge iterations, and fencing shipped with the first admitted running fenced state. Acceptance must cover link/install completion, shared-cell queued updates, completed fork-write history, and ready values containing unsupported primitives.

The module-pins prerequisite is satisfied. The remaining blockers are in v2’s own specification, not the checkout or the owner’s settled invariant.
