Created-GMT: 2026-09-02 18:04:53 GMT
Created-Local: 2026-09-03 02:04:53 Asia/Shanghai

# Role: Lead Systems Architecture Reviewer — sign-off, round 3

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-03 01:53:55 Asia/Shanghai | Status: active | Rationale: Architect seat; sign-off authority
- Round 3 | Assigned: 2026-09-03 02:04:53 Asia/Shanghai | Rationale: verify two stale sentences and the cursor-rule trigger

Your round-2 sign-off was withheld on two stale sentences and an under-scoped
cursor rule. Both required items and all three minor ones are applied to
`docs/design/yin.vm.implementation-plan.md`:

1. **Phase V2 now says `take!` **removed**, not "redefined"** (line 278).
2. **The divergence register now says "the four user-visible changes"** (line
   317), and it explicitly lists the FFI pair's bounded outstanding-call count
   where v1 was unbounded, as you asked, rather than leaving it implied.
3. **The cursor rule's trigger is widened** (lines ~148-153): minting happens
   the moment the VM first holds the handle — store entry, the `:in-stream`
   field (`stream_driver.cljc:25`, `ast_walker.cljc:784`), or a bridge attach
   (`ffi.cljc:31-32,96`, which can run on an already-built VM) — and
   `dao.stream.apply` has **no cursorless arities**, so `apply.cljc:170,184`
   lose their defaults rather than gaining a mint site.
4. **Citation corrected**: `ringbuffer.cljc:72` was `evict-oldest-state`, so the
   drain claim now cites 161 only.
5. **Telemetry**: a cursor-ref's payload is summarised **without descent**,
   since an opaque cursor may itself be a map and walking it would re-emit the
   position field this port exists to remove.

I swept both plans for stale counts; the only remaining "three" is the REPL
plan's three spec blockers, which is correct.

## What to judge

Only whether these five landed and introduced nothing new. This is the third
round on a document whose recurring failure has been fixes that relocate their
problems — if it is implementable, say so; if a sixth thing is wrong, name it
and I will fix it.

Read:
- docs/design/yin.vm.implementation-plan.md
- docs/design/yin.repl.implementation-plan.md
- src/cljc/dao/stream/ringbuffer.cljc, src/cljc/yin/vm/{ffi,telemetry,stream_driver,ast_walker}.cljc

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with an explicit line:

SIGN-OFF: GRANTED   — implementable as written, or
SIGN-OFF: WITHHELD  — followed by the shortest list of changes that would earn it.

Do not edit files.
