Completed-GMT: 2026-09-03 07:31:00 GMT
Completed-Local: 2026-09-03 14:31:00 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: none (new provider, previous was agy)

Read-only verification completed. No file was edited, staged, or committed.

**Verdict: both corrections are correctly applied. The design sign-off is now unconditional.**

**Finding 1, protocol-error lifecycle: satisfied.**

- `docs/design/dao.stream.ws.md:140` carries the recommended diagnostic wording verbatim. The traffic group now states that `:ws/error` is never terminal by itself and that a protocol failure is followed by the terminal event its teardown produces.
- `docs/design/dao.stream.ws.md:535` carries the recommended close-code paragraph verbatim. Code 4002 now has an explicit terminal mapping. An established attachment gets exactly one `:ws/closed` at both the initiating endpoint and the observing peer. The pre-resolution handshake rule is preserved and correctly scoped so an unresolved attachment gets one `:ws/transport-error` and no second completion.
- The decode-failure sequence at lines 520 to 533 still says the receiver deposits the error first and then closes with 4002. That sequence and the new terminal mapping agree.
- `docs/design/yin.vm.implementation-plan.md:471-476` keeps `:ws/closed` as the transition that loses every outstanding request and permits rebind, and reclassifies `:ws/error` as a retained-state diagnostic. The gap in the original algebra is closed because a 4002 teardown now provably arrives as `:ws/closed`.
- `docs/design/yin.repl.implementation-plan.md:211-220` and lines 461 to 468 mirror that algebra. The decoder preserves requests across a survivable error and treats closure as the terminal event.
- The required test is present in both plans at lines 484 and 475 respectively.

**Finding 2, completion retention: satisfied.**

- The completion-consumption paragraph is inserted verbatim in both transition-algebra descriptions, at `docs/design/yin.vm.implementation-plan.md:448` and `docs/design/yin.repl.implementation-plan.md:226`.
- The state comment at `docs/design/yin.repl.implementation-plan.md:176` reads as recommended: unpublished completions since the last publication.
- The allocation rules at lines 179 to 184 of the REPL plan and lines 428 to 431 of the VM plan still list `:completed` in the collision check. That is consistent, because the new paragraph defines the check as covering currently unpublished completions, and `:next-id` is now the stated proof of never-reuse.
- The four required tests appear in both plans.

**Two non-blocking notes, not conditions.**

- **Test placement for the transport half.** The protocol-error test is placed in V1 and R1, both socket-free phases. Hand-built envelope sequences can prove the RPC-side transitions there. Proving that a real endpoint deposits `:ws/error` then `:ws/closed` and that the peer deposits `:ws/closed` needs the ws transport. That half should land in the sibling plan's Phase 4a or in R3, and the sibling plan was not in this round's edit set.
- **A loose sentence.** `docs/design/dao.stream.ws.md:305` says a closing frame arriving means the stream ended. In context it describes the 4000 case, but 4002 is also a closing frame and maps to `:ws/closed`. A one-clause qualifier would remove the ambiguity. It does not contradict the new mapping at line 535, which is the authoritative statement.

**Recommendation.** Accept the r4 edits as satisfying both r3 corrections. Record the transport-side test placement as an item for the sibling stream plan's next amendment.
