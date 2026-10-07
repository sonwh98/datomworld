Created-GMT: 2026-09-02 18:31:52 GMT
Created-Local: 2026-09-03 02:31:52 Asia/Shanghai

# Role: Lead Systems Architecture Reviewer — sign-off, round 6

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-03 01:53:55 Asia/Shanghai | Status: active | Rationale: Architect seat; sign-off authority
- Round 6 | Assigned: 2026-09-03 02:31:52 Asia/Shanghai | Rationale: design change to a signed plan, at the user's direction

You granted sign-off in round 5. The user then proposed a design change, and it
is a good one, so the plan changed. **A granted sign-off on an edited document
is not a sign-off.**

## The change: the host supplies streams

The user's framing: when yin.vm starts it should be *given* a dao.stream
implementation by the host, exactly as it is given `+`.

The evidence supports it and it turned out to be smaller than the design it
replaces. `handle-make` (`engine.cljc:131-141`) is the **only** handler that
needs a transport identity — it builds
`{:dao.stream/type :ringbuffer, :mode :create, :capacity capacity}` and calls
`ds/open!`. The other four operate on a handle already in the store:
`append!` at 156, `next` at 192, `close!` at 237. `open-local-stream`
(`vm.cljc:134-139`) is the second and last construction site. Those two sites
are the *only* reason `vm.cljc:7-9` and `engine.cljc:7` require
`dao.stream.ringbuffer`.

So `create-vm` now takes **`:make-stream`**, mirroring `:primitives`
(`vm.cljc:550`, `ast_walker.cljc:778`). Consequences recorded in the plan:

1. **`dao.stream.ringbuffer` leaves the closure.** `yin.vm` and
   `yin.vm.engine` require the protocols only. This removes the dangling
   dependency all five reviewers found, rather than substituting for it.
2. **`:make-stream` has no default**, unlike `:primitives` — a default would
   smuggle the hardcoded transport and its require back in. Absent one,
   `:stream/make` is unsupported and says so, mirroring the `:telemetry`-opt
   rejection you required in round 4.
3. **Prerequisites split**: sibling Phase 1 (protocols) is what the VM requires;
   Phase 2 (ring buffer) is needed by this plan's *tests and compositions*,
   which supply `:make-stream`, but not by the VM namespaces.
4. **Module registration and `:make-stream` are one composition step.** The
   effect constructors move into `yin.vm.module`, since they belong to the
   VM's vocabulary rather than to any transport.

Also added, because it was undocumented and an implementer had to reconstruct
it: a section stating that the `stream` module is **pure effect constructors**
(`ringbuffer.cljc:420-464`), that Yin programs hold `:stream-ref` values and
never handles, that the FFI is a separate path for host functions, and a
six-row `:stream/*` effect → v2 mapping table.

The consumer plan now says the REPL supplies `:make-stream` and registers the
module — it is the composition that chooses the VM's transport.

## What to judge

1. **Is the closure claim now right**, with the ring buffer removed? This is the
   third time the closure has changed; it has been wrong twice.
2. **Does `:make-stream` with no default leave a hole** — is there any path
   where a VM needs to create a stream and has no constructor, that the plan
   does not account for? The FFI pair is the case I am least sure of, since
   `empty-state` builds it during construction.
3. **Is the effect-mapping table correct and complete** against `engine.cljc`?
4. Anything the change introduces or relocates.

Read:
- docs/design/yin.vm.implementation-plan.md
- docs/design/yin.repl.implementation-plan.md
- src/cljc/yin/vm.cljc, src/cljc/yin/vm/{engine,ffi,ast_walker}.cljc
- src/cljc/dao/stream/ringbuffer.cljc, src/cljc/yin/module.cljc
- docs/design/dao.stream.md, docs/design/dao.stream.implementation-plan.md

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with an explicit line:

SIGN-OFF: GRANTED   — implementable as written, or
SIGN-OFF: WITHHELD  — followed by the shortest list of changes that would earn it.

Do not edit files.
