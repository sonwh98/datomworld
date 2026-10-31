Completed-GMT: 2026-09-03 21:08:15 GMT
Completed-Local: 2026-09-04 04:08:15 ICT
Coding-Agent: glm
Session-ID: none (new review)

# Staged DaoStream v2 implementation review

GLM reviewed all 18 staged files and confirmed the architecture, layering,
cursor discipline, WebSocket wire shape, admission enforcement, RPC allocator,
codec, and targeted CLJD portability properties.

## Findings

- **HIGH** `src/cljc/dao/stream/forward.cljc:138-141`: `:gap-policy :resume`
  can loop forever when ring-buffer gap recovery returns the same evicted
  cursor. Fix ring-buffer recovery and count gap resumes against the batch
  budget.
- **MEDIUM** `src/cljc/dao/stream/ringbuffer.cljc:88-95`: frozen attachment
  recovery can return an already-evicted tail and re-gap forever.
- **MEDIUM** `src/cljc/dao/stream/ws.cljc:321-432`: pre-accept peer loss,
  failed composition acknowledgement, and endpoint stop can leak pending
  handoff slots when expiry is disabled.
- **MEDIUM** `test/dao/stream/ringbuffer_test.cljc:190-204`: the required
  bounded concurrent linearizability histories are not demonstrated.
- **LOW** Mixed reader conditionals place `:cljd` after `:clj`; reorder them to
  follow the documented CLJD rule.
- **LOW** `rpc.cljc` diagnostics have no publication/clearing boundary and can
  grow without bound.
- **LOW** `ws.cljc:108-113` drops the `:ws/value` key for a legitimate nil
  payload.
- **INFO** The v2 adapter retains `telemetry` as a local command and uses the
  evaluating CLJ reader for command sniffing.

The report also notes that real host socket adapters, two-process Phase 5,
cljd WebSocket transport, and full `yin.repl` core/driver namespaces remain
deferred work rather than defects in this staged increment.

## Verdict

**SIGN-OFF: WITHHELD**

Withholding is based primarily on the confirmed forwarder/ringbuffer infinite
loop, pending-slot lifecycle leak, and missing concurrency-oracle evidence.
The complete unfiltered GLM output is preserved in the paired `.stdout.log`.
