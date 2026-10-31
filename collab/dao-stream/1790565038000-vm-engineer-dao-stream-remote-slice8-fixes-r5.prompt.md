# Dispatch: dao.stream.remote slice 8 — fix round r5 (verbatim request envelope)

Role: yin.vm engineer. Gate confirm r9 (collab/1790564383038-architect-dao-stream-remote-slice8-confirm-r9.gpt-6-sol.stdout.log) returned REQUEST CHANGES with exactly one unmet ruling requirement. Fix only this.

## The finding (quote from confirm r9)

> One ruling requirement remains unmet: the retained request envelope is not preserved verbatim. Lift keeps only its operation and arguments (src/cljc/yin/vm/ucf/remote.cljc:348); lower constructs a new envelope from those fields (src/cljc/yin/vm/ucf/remote.cljc:638). The request protocol permits additional keys, so those keys would be lost (src/cljc/dao/stream/apply.cljc:38). The round-trip test uses a constructor-made envelope and does not pin preservation of additional fields (test/yin/vm/ucf/remote_test.cljc:1182, test/yin/vm/ucf/remote_test.cljc:607).

Ruling being implemented: collab/1790533100000-architect-ffi-migration-semantics.gpt-6-sol.findings.md — the retained FFI request must travel verbatim so a receiver can re-establish the carried pair exactly as the sender held it. `apply2/request?` documents that unknown keys are intentionally legal ("the envelope remains open"), so reconstruction from op+args is lossy by construction.

## Required changes

1. **Lift** (`lift-retained-request`, src/cljc/yin/vm/ucf/remote.cljc:340-359): carry the envelope verbatim on the pending — add `:yin.k/request-envelope envelope` (envelope is `(:datom entry)`, already in hand). Keep the existing `:yin.k/request-op` / `:yin.k/request-args` fields (they remain the op source for the lowered entry's `:op` and are asserted by existing tests at remote_test.cljc:999-1000). Update the docstring: the envelope rides verbatim; op/args are derived views.

2. **Lower** (`:ffi-request` branch, src/cljc/yin/vm/ucf/remote.cljc:625-646): the lowered entry's `:datom` must be the carried envelope verbatim — `(:yin.k/request-envelope pending)` — not a fresh `(apply2/request ...)`. Since lift always sets the envelope key, a missing key on a `:ffi-request` pend is a malformed pend: refuse it as `:yin.k/unsatisfied` naming the request identity rather than silently reconstructing (silent reconstruction is exactly what the gate denied). Check whether any other code path constructs `:ffi-request` pends; if tests construct them by hand, update those constructors to carry the envelope.

3. **Test pins** (test/yin/vm/ucf/remote_test.cljc):
   - The lift assertion near :607/:999: extend (or add a sibling test) so the pending's `:yin.k/request-envelope` equals the retained envelope including at least one additional key a constructor-made envelope would not have (e.g. `:producer/nonce "n-1"`).
   - The round-trip test near :1182: make the retained envelope carry an additional key and assert the lowered entry's `:datom` is `=` to the original envelope — additional keys intact, not merely op/args equal.
   - Add the refusal pin: a `:ffi-request` pend without `:yin.k/request-envelope` lowers to `:yin.k/unsatisfied` naming the request identity.

## Constraints

- Allowed files: src/cljc/yin/vm/ucf/remote.cljc, test/yin/vm/ucf/remote_test.cljc only. Do not touch dao.stream.apply — its open-envelope protocol is the reason verbatim carriage is required.
- Run the suite on JVM via mise: `mise exec -- clj -M:test -e ...` or the project's usual `bb test` lane for `test/yin/vm/ucf/remote_test.cljc`. All 21+ tests green before you report.
- Report back in collab/${TS}-vm-engineer-dao-stream-remote-slice8-fixes-r5.glm-flash.report.md: what changed (with line refs), test counts, and confirmation no other file was touched.
