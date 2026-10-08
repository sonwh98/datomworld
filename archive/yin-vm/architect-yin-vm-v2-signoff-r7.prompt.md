Created-GMT: 2026-09-02 18:38:06 GMT
Created-Local: 2026-09-03 02:38:06 Asia/Shanghai

# Role: Lead Systems Architecture Reviewer — sign-off, round 7

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-03 01:53:55 Asia/Shanghai | Status: active | Rationale: Architect seat; sign-off authority
- Round 7 | Assigned: 2026-09-03 02:38:06 Asia/Shanghai | Rationale: verify the four required items from round 6

Your four required items are applied, plus the optional one. I verified each
claim against source before acting.

1. **The FFI-pair rule.** *The host supplies streams* now states: the pair comes
   from `:make-stream`; else from the `:call-in`/`:call-out` options
   `empty-state` already accepts (verified at `vm.cljc:539-540`); else no pair.
   With no pair, a non-nil `:bridge` is a construction error and a
   `:dao.stream.apply/call` fails **before `park-continuation`** — I confirmed
   the park runs nineteen lines ahead of the first stream touch
   (`ast_walker.cljc:125` vs 145), so a later error strands a continuation and
   consumes an id-counter. The bridge cursor moves to `ffi/attach` or after the
   pair exists, since `ffi/normalize` (`ffi.cljc:28-36`) has no handle to mint
   against.
2. **Construction is all-or-nothing.** Any non-`ok` from `:make-stream` or from
   any of the three cursor mints fails construction with an error carrying that
   outcome.
3. **`:stream/make` is total** over `ok`, `invalid-spec`, `not-found`,
   `transport-error`. Nil capacity from the module's zero-arity `make`
   (`ringbuffer.cljc:427-429`, reachable from source) takes the AST path's 1024
   default (`ast_walker.cljc:426`), so both paths agree.
4. **`:stream/put` is total** over all five `append!` outcomes, with `full`
   parking in the polling wait set — `runtime.cljc:114` already has the `:put`
   retry branch. `:stream/cursor` gained `invalid-anchor`. The retention
   paragraph and the fifth user-visible change are re-scoped to the ring-buffer
   composition, with a new line: **totality is the VM's, retention is the
   composition's**. The consumer plan says the same, since the REPL is the
   composition supplying the ring buffer.

Optional, applied: V1 carries a dependency check that `dao.stream.apply`
takes handles and requires only the protocols, with the ring buffers named as
its test fixture. V6 states that every v2 test supplies `:make-stream`, since
v1's suite calls `(ast-walker/create-vm)` bare.

Your point that the backpressure divergence moved from the VM to the composition
is the one I most wanted to get right, because I had stated a transport property
as a VM property. If the re-scoping is still wrong anywhere, that is the finding
I want.

## What to judge

Only whether those five landed and introduced nothing new.

Read:
- docs/design/yin.vm.implementation-plan.md
- docs/design/yin.repl.implementation-plan.md
- src/cljc/yin/vm.cljc, src/cljc/yin/vm/{engine,ffi,ast_walker}.cljc
- src/cljc/dao/runtime.cljc, src/cljc/dao/stream/ringbuffer.cljc
- docs/design/dao.stream.md

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with an explicit line:

SIGN-OFF: GRANTED   — implementable as written, or
SIGN-OFF: WITHHELD  — followed by the shortest list of changes that would earn it.

Do not edit files.
