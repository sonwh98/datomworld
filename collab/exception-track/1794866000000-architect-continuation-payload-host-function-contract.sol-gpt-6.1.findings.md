# Ruling: host functions in register continuation payloads

Task-ID: `1794866000000-architect-continuation-payload-host-function-contract`

Seat: codex gpt-6.1-sol, review/ruling lane. Read-only investigation; this findings file is the only task write. No source edits, test runs, or runtime probes. Inspected worktree HEAD: `156de2ae68ab8b953e7970cceac677327e7eb251`. Existing unrelated worktree modifications were left untouched.

## Decision

**Choose truth 2 for the de Bruijn register continuation payload. Do not widen `vm/machine-data?`, add a registered-function exemption, or make validation consult a primitive/module registry. Route the fix upstream to PHP's emitted guest call/capture sequencing.**

There is an essential scope correction to the proposed dichotomy: host functions are legitimate executable VM values, and profiled primitives are legitimate UCF carrier values. They are **not admitted reachable values in this register backend's saved payload**. This is an explicit receiver limitation, not a universal prohibition on primitives in continuations or on calling host functions. Calling the failure “corrupt or tampered” does not establish malicious modification; internally generated state outside the supported register fragment reaches the same validator.

The controlling text is already decisive: `docs/design/yin.vm.semantic.continuation.md:821` (§4.2, D4 amendment, adopted by R6) says a primitive is an admitted carrier value but the register kernel cannot restore one reachable through saved registers or lexical frames, **including nested collections, closure captures and reified continuation state**. Lowering must refuse `:yin.k/unsupported :continuation-registers` with a path before attachment. It explicitly requires a separate kernel seam to expand that domain and says the fragment is not widened. §4.6 reiterates the retained backend limits. This dispatch is not authorization to overturn that ruling.

UCF §7.5.2 (`docs/design/yin.vm.universal-continuation-format.md:1267`) describes identity lookup and semantic profiles for portable primitive encoding. That encoder contract does not authorize raw function objects in a register snapshot, and the existence of `cell/set!` in a host-module registry does not change the receiver's domain. A symbol-shaped map or an unvalidated symbolic replacement is not an executable or authenticated substitute.

## Enforcement and fix loci

The enforcement path is correct under the present contract:

- `src/cljc/yin/vm.cljc:938`, `machine-data?`: recursively unwraps kernel-minted closures/continuations and checks their payloads; raw functions and opaque host objects remain outside its admitted leaves. On the JVM its identity memo is local to each invocation. There is no process-wide “validated function” cache or registry lookup here to warm.
- `src/cljc/yin/vm/debruijn_register_effects.cljc:201–209` and `:332–339`: require sparse register shape/index agreement and recursively admitted values, for return frames and the outer payload respectively. Lexical frames have their own recursive checks. This namespace explicitly reads no namespace registry or mutable state.
- `src/cljc/yin/vm/debruijn/register.cljc:490`, `register-restore`: checks the payload before delivery and throws the reported exception with the defect. Its separate resume-value check must also remain.
- `src/cljc/yin/vm/semantic/layout.cljc:1153`, `lower-defect`: enforces the same register restriction before attachment for transported run/wait/ready/parked state. Do not remove that check to make local PHP execution green.

**Implementation ownership is the PHP producer, not the validation recognition set or registry initialization.** File-level loci for the engineer are:

1. `src/cljc/yang/php/antlr/prelude.cljc:2699–2759`, `php/make-function`, especially the unary entry body at `:2737`: `(cell/set! result (let [...] ((get (cell/get callee) :code) ...)))`. The linearizer evaluates the operator before the operands (`src/cljc/yin/vm/semantic/linearize.cljc`, namespace contract and `:application` arm). This form therefore resolves `cell/set!` before running the callee. It gives a concrete, source-visible route for `set-cell!` to remain live across guest execution and its escape captures. It is the first fix candidate, not a proven identification of the exact instruction in the recorded nested payload.
2. `src/cljc/yang/php/antlr/prelude.cljc:248–301`, `php/call-terminal`, `php/try`, `php/call-ec`: these create the reified escapes with `%capture`. `php/try-finally` at `:277` participates in cleanup without itself capturing. Preserve handler, unwind, return, and exception-transfer behavior.
3. `src/cljc/yang/php/antlr/lower.cljc:3149–3235`, function lowering: the dynamic-scope profile, return escape, `cell/set!` initializers, scope adoption and cleanup wrappers; and `:1253–1285`, `delivery-thunk` / `lower-include`, which introduce the statement-time link boundary. `src/cljc/yang/php/antlr/prelude_module.cljc:84–125` supplies `include-body`, `include-run` and `include-computed`. These are the additional producer loci if the captured chain identifies a different pending application.

The engineer should sequence an effectful/capturing value computation before resolving the primitive used to store its result, where the specific binding and evaluation-order proof permits it. For example, binding the result of the callee computation first, then applying `cell/set!` to that value, avoids retaining that host operator across the callee. This is a targeted producer rewrite, not permission to reorder arbitrary guest applications. Check all other saved values in the nested chain; removing one offending operator is insufficient if another remains.

Do not delete the rejected pair, clear captured frames, or alter the verified live set merely because a host function was found. `docs/design/yin.vm.debruijn.register.md` §5.2.2 requires the exact sparse live set and retains lexical frames pending a separate frame-liveness design. `register.cljc:651–656` captures those frames, and `:688–700` saves the caller's verified live registers. A host operator awaiting a later application may be semantically live. Generic register capture/liveness changes would need their own proof and broader design scope.

The recorded path establishes the forbidden leaf but not its source instruction or semantic liveness. This ruling settles the contract and implementation surface; it does **not** claim the precise PHP rewrite or cold/warm cause has already been proved.

## Non-weakening bar

After the producer fix, the original payload containing raw `yin.vm.module/set-cell!` must still fail the same recursive admissibility check. Registration must not turn it into accepted machine data. The same holds for a raw anonymous function, atom, host exception, or opaque object, whether direct or nested under a guest collection or a kernel-minted value. A kernel wrapper is not an exemption for its contents.

Malformed register pairs, invalid indices, missing/extra/reordered pairs relative to `:live`, a live set differing from the validated instruction, illegal destination/resume mode, invalid pc/body ownership, bad image/hash/format and malformed return frames must retain their qualified refusals. In `register-restore`, defective payloads must still throw `Corrupt or tampered continuation payload` with the applicable `:rule` before restoration changes state; register-value violations remain `:continuation-registers` (lexical-frame violations can be `:continuation-frames`). Foreign-format admission retains its existing earlier refusal. Non-admitted resume values retain `:resume-value`. Transported unsupported primitives must still refuse before any attachment.

Existing evidence for these guards: the engineer recorded `yin.vm.debruijn-register-effects-test` passing 12 tests / 148 assertions; its mutation matrix is in `test/yin/vm/debruijn_register_effects_test.cljc`. Restore refusal coverage is in `test/yin/vm/debruijn/register_test.cljc:529–590`; transported nested primitive refusal coverage is in `test/yin/vm/semantic/safepoint_harness_test.cljc` and the layout checks above. These are recorded/read assertions, not tests executed in this ruling round. Structural validation is not a cryptographic guarantee that every substitution of one otherwise valid scalar for another is detectable.

## Dyn fixture setup and evidence limits

`test/yang/php/antlr/include_test.clj:40–59` sends the fixture through `linked-harness/run-fixture`; it catches a thrown run and substitutes `{:values {}}`. Thus an empty backend map does not show that all backends individually rejected a payload: `run-fixture` eagerly collects the backend results and an exception aborts the whole collection.

`test/yang/php/antlr/linked_harness.cljc:46–62` publishes the runtime/hook modules through a process-wide delay into a shared content store. `publish-units!` adds fixture units there; `source`, `composition`, `run-linked` and `drive` supply the link service and run it. `test/yang/php/antlr/portable_harness.cljc:100`, `linked-registry`, explicitly installs the cell module on each registry construction. `set-cell!` was demonstrably resolved in the failing run. None of this supports a missing function-registration hypothesis in the validator.

The dyn setup must provision the units, scope and link composition explicitly and behave independently of preceding subsystem tests. If prior runs change publication/service state and therefore the occurrence of suspension, that may mask the unsupported capture; it cannot make the same raw-function-bearing payload valid. If fixture setup itself is incomplete, fix that setup as well, with a standalone reproduction. Do not add a warm-up require/test, pre-run unrelated fixtures, omit the register lane, suppress the error, or change expected PHP results as a substitute for fixing the producer. Keep any fixture edits within the dyn setup scope authorized by the dispatch; do not touch include-red r2/siteset work.

Evidence read: `/tmp/datom-dyn-cold/collab/exception-track/1794854000000-engineer-dyn-continuation-corruption-isolated.codex-gpt-6.1-terra.findings.md` and `collab/yang-php/1794813045000-to-master-green-seat-include-red-verified-fixed-dyn-unit-new.md`. The former proves a focused cold `:dyn-shares-locals` failure and the same leaf/path after requiring `e2e-test`; it explicitly reports interrupted full isolated runs and an unproved warm/cold explanation. The relay reports broader isolated reds/subsystem greens. Treat that divergence as reported evidence to investigate, not proof of validator initialization dependence. No additional probe is needed for this contract ruling; exact source-instruction attribution and controlled cold/warm verification remain engineering work.

`docs/design/yin.repl.dht-composition.md` §7's deferred fixture-drive/progress work concerns shell/recheck helpers, not this register value domain. It supplies no exception here. The include-red r2 design is outside this ruling and remains untouched.
