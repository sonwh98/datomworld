Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: assigned at dispatch
Session-ID: pending (provider-generated)

# Task: UCF M-next D10b — four-kernel lift and lower generalization
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: assigned at dispatch | Assigned: <local timestamp> | Status: active | Rationale: the lift/lower generalization owner; handoff.cljc and the four kernels are its engine room

Implement D10b in /Users/sto/workspace/datomworld-d10b (worktree,
branch ucf-d10b-kernel-lift-lower, based on master with D1-D12
landed). D10b is astra's option-c prerequisite to D16 and does not
block D11-D14; this brief is the slice-side record of the D10 scope
amendment the ruling's landing condition requires. Read first, in the
worktree's collab/ (supplied untracked, house practice): the option-c
ruling (1791262000000-architect-d10-kernel-scope-ruling-astra-r2
.gpt-6-astra.findings.md — the five-part acceptance contract and the
register constraint, restated below), D10's disclosed limitation
(1791253000000-compiler-engineer-ucf-d10-v1-lower.findings.md —
limitation 1 and the `v1-lower-supported-kernel-boundary` pin in
test/yin/vm/ucf/handoff_v1_test.cljc), the register kernel's
boundary-opcodes constraint (1791197000000-architect-d5-cursor
-register-ruling.claude-fable-5-1.findings.md), the D10 lower-inputs
ruling (1791240000000-architect-d10-lower-inputs-ruling
.gpt-6-astra.findings.md) and the D10 fix-round gate
(1791262500000-reviewer-d10-fix-round-gate.findings.md — its findings
1, 2 and 4 are not closed by the scope amendment). Then the landed
code: src/cljc/yin/vm/ucf/handoff.cljc (`seg-address!` and
export-task's code addressing, `encode-registers`/`decode-registers`,
`validate-body`'s code and register checks, `resume-task*`'s
version-aware reader pipeline), ucf.cljc (`contract-stamp`,
`safepoint-table`'s `:yin.safepoint/engine`, `code-address`), and the
four kernels' addressing and restore seams (`semantic.cljc`'s
`semantic-restore` and `attach-image`, `debruijn/stack.cljc`'s
`stack-restore`, `debruijn/register.cljc`'s `register-restore` and
the register image hash, `ast_walker.cljc`'s load/attach path).

The contract (astra's option-c ruling, which amends D10's scope and
names D10b):

1. **Both halves, four kernels.** D10b owns lift and lower
   generalization: addressed code, kernel-specific continuation and
   register restoration, and any necessary code-profile validation.
   Today a walker, stack or register source is refused at lift with
   the pre-existing `:yin.k/non-portable`, kind `:unaddressed-segment`
   refusal, and only the semantic kernel's lower is exercised. D10b
   removes that limitation at both ends.
2. **Lift: addressed bodies from real machines.** Generalize
   export-task's code addressing (`seg-address!` and the completion
   walk's segment discovery) so a real blocked, parked or halted
   walker, stack, register or semantic machine produces a body whose
   carried code is addressed and validates — each kernel's own
   content hash is the address — with identical prepared state
   producing identical bytes and addresses across hosts.
3. **Lower: kernel-specific restoration.** Generalize the lower and
   the grammar it proves (`validate-registers`, `check-frame-pc`, the
   register decode) so each kernel restores through its own restore
   seam into a runnable receiver machine, carrying that kernel's
   continuation and register representation as it stands — never a
   converted one.
4. **Code-profile validation.** A body declares the kernel/profile
   its code and registers ride, and the receiver refuses an
   unsupported profile before any restoration
   (`:yin.k/profile-mismatch`), with no silent profile conversion.
   `:yin.safepoint/engine` is the existing precedent; restoration
   uses the supported kernel/profile pairing.
5. **No code-format change.** The register kernel's boundary-opcodes
   constraint stands: no new boundary opcodes, no liveness or
   register layout change, no opcode arity change — a boundary
   opcode carries an in-band `:live` operand at a fixed tuple index,
   and changing that changes the validator's admission, the liveness
   pass, the encoded bytes and every image's address. That is a new
   contract ("r3", not "r2") and must be refused. Generalize
   addressing and restoration around the existing code format and
   continuation representation. If investigation demonstrates that a
   code-format change is necessary — or any change to
   `ucf/contract-stamp`, a kernel contract stamp, or the handoff
   body's wire grammar — stop and report: that is a new contract
   requiring a separate architectural ruling with explicit
   stamp/version and compatibility consequences before anything is
   implemented.
6. **Grant machinery is applied, not redesigned.** Version-1 runnable
   roots lower only through the landed grant acceptance (checkpoint,
   lease, holder, evidence, tenure, protection declarations), now on
   the new kernels exactly as on semantic. D13/D14's carried duties —
   evidence authentication, the admitted occurrence variant, the
   tenure recheck, release after post-grant failure — stay with
   D13/D14.

Test contract (the option-c ruling's five parts, as rows):

- **Round trips** (part 1): on JVM, Node and Dart, produce bodies
  from real semantic, walker, stack and register machines and restore
  into fresh receivers using the supported kernel/profile pairing.
  Exercise version 0 and version 1; version-1 runnable roots require
  valid grants. Refusal assertions do not count as restoration
  coverage.
- **Continuation correctness** (part 2): cover every applicable
  liftable safepoint row per kernel, including explicit park, ordered
  retained waits, install children and halted results. After
  restoration and controlled delivery of pending outcomes, match the
  uninterrupted execution's result and observable trace;
  initialization must not rerun.
- **State fidelity** (part 3): preserve cursor positions, aliasing
  and distinctness, closures, module stores, carried operation IDs,
  exact operation counters and restored issue ordering;
  receiver-local guest state must not affect resolution.
- **Custody enforcement** (part 4): repeat the grant/protection
  refusal checks and gate checks across all four kernels, including
  zero observation calls while fenced and child gate propagation.
- **Addressing and compatibility** (part 5): identical prepared state
  produces identical bytes and addresses across hosts; existing valid
  semantic version-0 fixtures and D9 pins are preserved; address
  mismatch and unsupported code profiles refuse before restoration.
- The kernel-boundary test's walker/stack/register refusal pins are
  replaced by round-trip restoration rows; the semantic path they pin
  stays green.

Acceptance criteria:
- Test-first per behavior; portable `.cljc`; JVM during iteration,
  three lanes at landing.
- A part of the five-part contract is met only by successful
  restoration and execution, never by refusal assertions.
- The permitted diff is src/cljc/yin/vm/ucf/handoff.cljc and the
  kernel files src/cljc/yin/vm/semantic.cljc,
  src/cljc/yin/vm/ast_walker.cljc, src/cljc/yin/vm/debruijn/stack.cljc,
  src/cljc/yin/vm/debruijn/register.cljc — IF AND ONLY IF no code
  format changes — plus their test files. Anything touching a
  contract stamp or the wire grammar: stop and report (that is a
  ruling). If a public seam is missing outside the permitted diff,
  stop and report.
- D16 cannot pass until D10b lands; stage D must not be declared
  complete while D10b is outstanding. D11-D14 advance on the semantic
  path meanwhile.

Constraints:
- The slice proceeds alongside D13/D14: rebase onto master after each
  of their landings and rerun the lanes before answering.
- No git writes. kondo/cljstyle may be sandbox-blocked; note it.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd
  first); a 0.0 literal is the integer 0 on JS.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.
