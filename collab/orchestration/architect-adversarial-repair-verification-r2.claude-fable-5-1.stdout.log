Completed-GMT: 2026-09-03 09:31:00 GMT
Completed-Local: 2026-09-03 16:31:00 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: 82ACFFF4-F684-479B-B869-3DA23A491A07

I re-read the affected clauses in `dao.stream.ws.md`, `dao.stream.md`, `dao.stream.implementation-plan.md`, `datom.world.md`, `yin.vm.implementation-plan.md`, and `yin.repl.implementation-plan.md` in full context. Nothing was edited, staged, or committed.

**Verdict: GRANTED.**

## Disposition of the eight follow-ups

1. **Pre-accept and post-accept routing.** RESOLVED. `dao.stream.ws.md:274-279` routes every pre-accept event, including decode diagnostics and the pre-accept terminal event, to the control medium, and every post-accept event to the acknowledged per-attachment medium. `dao.stream.implementation-plan.md:349-351, 369-371` and `yin.repl.implementation-plan.md:559-562, 573-576` match, and the REPL server driver reads both paths. The control medium's `:portable-values` declaration at `dao.stream.ws.md:392-393` is consistent because offers travel through slots, so no handle ever lands there.

2. **Wrong-identity versus matching-malformed acknowledgement.** RESOLVED. `dao.stream.ws.md:267-272`: wrong identity is stale and ignored; a matching but malformed acknowledgement releases the slot, closes pre-accept, and deposits `:ws/closed` on the control medium. `dao.stream.implementation-plan.md:367-369` tests it; `yin.repl.implementation-plan.md:593-594` consumes it. The composition's already-created per-attachment medium is retired via the control path, so no orphan state remains.

3. **Disclaim and slot exhaustion stay in the upgrade callback.** RESOLVED. `dao.stream.ws.md:505-508` and `dao.stream.implementation-plan.md:333-335`. Consistent with slot assignment following resolution at `dao.stream.ws.md:235-238`, and with the exactly-once rule at lines 148-159, since a disclaimed or refused connection never mints a server-side identity.

4. **Code 4000 deposits `:ws/ended` at both endpoints.** RESOLVED. `dao.stream.ws.md:152-153`, consistent with line 151, line 628, and `yin.repl.implementation-plan.md:613-614`.

5. **In-band `:ws/end` fallback.** RESOLVED. `dao.stream.implementation-plan.md:379-382` names the amendment and forbids shipping the code-only design if any host library fails the gate.

6. **Identity equality.** RESOLVED. `dao.stream.md:207-208` requires structural equality between the sibling projection and the value inside the descriptor; `dao.stream.implementation-plan.md:112-113` has the harness assert it.

7. **Advertised host and port defaults.** RESOLVED. `dao.stream.ws.md:454-459`: concrete bind host is the default advertised host, wildcard bind requires an explicit one, and the advertised port defaults to the actual bound port including a zero bind. This composes correctly with asynchronous bind at `yin.repl.implementation-plan.md:620-624`, because accepted handles exist only after bind.

8. **Master rule and cross-reference.** RESOLVED. `datom.world.md:56-59` sanctions DaoStream handle operations and scopes the adapter rule at lines 66-68 beneath it. `yin.vm.implementation-plan.md:330` now cites *Phase 2 — Ring buffer reference*, which is where `dao.stream.implementation-plan.md:175-176, 227` state the claim.

## Remaining blocking findings

None. The eight corrections introduced no contradiction with the exactly-once lifecycle rule, the admission declarations, the endpoint-step contract, or the identity invariant.

One non-blocking observation. Because disclaimed and slot-refused connections never mint a server-side attachment identity, the serving composition cannot observe them as ws-transport data. The REPL plan's `:upgrade-failed` lifecycle event at `yin.repl.implementation-plan.md:605-606` is the natural place to surface them if that visibility is ever wanted. This is a composition choice, not a defect.

## Staging readiness

The working-tree repair is ready to stage over the restored index.
