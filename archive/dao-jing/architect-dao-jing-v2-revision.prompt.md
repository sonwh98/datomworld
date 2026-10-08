Created-GMT: 2026-09-06 16:55:53 GMT
Created-Local: 2026-09-06 23:55:53 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
# Task: dao.jing.v2 migration plan — revision
Role: Lead System Architect (author seat)
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-06 23:55:53 +07 | Status: active | Rationale: resumed author session; holds the plan, both reviews, and both consensus rounds

Produce the **complete revised plan document**, emitted as the body of your
final response. Do not edit any file. The orchestrator promotes it. This
replaces the round-1 draft in full — write the whole document, not a diff.

The consensus round is closed. `gpt-6-astra`'s round 2 is on disk at
`collab/consensus-dao-jing-v2-r2.gpt-6-astra.findings.md`; read it before you
start. **You have not seen it** — round 2 ran both seats in parallel, so its
positions answer your round 1, not your round 2, and yours answered its round
1. It converged onto your position on every open item. Specifically: it now
rejects Sol on C1 ("N-b is decisive"), adopts the observer move-out for C2,
places stepped materialization inside J3 for C3, and adopts the three-namespace
end state for C4. The residual disagreements both of you reported in your
closing lines are artifacts of simultaneity and do not exist. Do not re-argue
any of them.

The orchestrator's ruling on the three questions you each raised: none of them
is settled here, because this deliverable is a plan and not a diff. Carry the
recommended path as the plan's position, and flag the two scope-contingent
items **inside the document** as decisions taken when implementation is
authorized, each with its named alternative. Do not treat either as blocking.

## What the revision must incorporate

Agreed defects:

- **A1.** `step` ordering: (1) if `:unsent` and not terminal, re-attempt
  through `rpc/request!`, folding that outcome into the step result; (2)
  `rpc/poll!` with the budget; (3) if the returned state is terminal and still
  holds `:unsent`, `rpc/abandon-unsent` with the terminal reason; (4) take
  completions and diagnostics exactly once. State that a driver which only
  re-calls `request-*` can never clear a `busy`.
- **A2.** `step`'s result includes `:diagnostics`, published exactly once.
- **A3.** Torn-tail coverage ported, with the fixture adaptation you found:
  `log_test.cljc:104` writes `(->bytes [11 22])`, which is not a decodable
  `[address payload]` record, so the ported test appends one valid encoded Jing
  record first, then hand-writes the overlong-length frame, then asserts
  truncation, replay of the valid record, and a clean subsequent put. Plus the
  sub-four-byte-tail variant, on all three hosts.

Contested items, as settled:

- **C1.** Decision 2 (option b) stands. Add both concessions: (i) Decision 2
  states explicitly that it amends `dao.jing.md`'s *Implemented surface*
  description of the file backend as "backed by an append-only log stream"
  (`dao.jing.md:297-299`), and that this is a design-document change routed as
  such, not an incidental edit; (ii) the pre-existing question — whether
  DaoJing's synchronous content handle conforms to Host Boundaries
  (`datom.world.md:66-68`) — goes into `dao.jing.md`'s open items by name as
  "the content write path as an effect stream", recording that it is not
  created by this plan, is not curable by any transport, and that its
  consequence is that durability would arrive as data and `materialize!`'s
  return value would change. State that the write-path redesign is out of
  scope for this plan and why (it reaches `dao.data.btree.storage` and
  `dao.space.index`).
- **C2.** J1 moves the v1 observer to `dao.jing.observer` (unchanged code,
  unchanged `dao.stream` require) and repoints **seven** test files: the five
  `test/dao/space/` files (transactor, stigmergy, index, schema, query) plus
  `test/dao/jing/mem_test.cljc:213` and `test/dao/jing/dht_test.cljc:402`. The
  v1 observer has no `src/` caller. Decision 4's table stops claiming "none"
  where the truth is transitive; the J2 gate reads "requires no `dao.stream`
  **directly or transitively**". **Scope-contingent:** five of the seven files
  are outside `dao.jing*`. Name the alternative (documentation route, with the
  end condition stating transitive v1 until J5 as a bullet, not a footnote).
- **C3.** J3a keeps `request-put address payload` and `request-get address` as
  backend primitives. New **J3c** adds `request-materialize state payload`
  over them: derive the address locally, issue put, on `:present` issue a
  second correlated get, complete with the address only after equality, else
  an `:error` completion carrying `/integrity-failure` or `/present-but-absent`
  as data. Per-id `{:payload :address :phase}` record for the verify hop.
  J3c is in this plan's end condition; read-side hydration needs only J3a;
  `store-tree-async` depends on J3c. Drop the claim that J3 alone delivers the
  async backend of `dao.data.btree.md` 5.4.
- **C4.** Decided now: end state is `dao.jing` (stream-free core),
  `dao.jing.observer`, `dao.jing.remote`. Transient names are
  `dao.jing.v2.observer` and `dao.jing.v2.remote`; bare `dao.jing.v2`
  disappears from the plan. J5 deletes the v1 observer and v1 remote, then
  renames the v2 namespaces into those slots. Independent of `dao.stream`'s
  own naming decision. Note that this follows from C2's ruling.
- **C5.** As A1 plus: expose `abandon state reason` for the composition's own
  disconnect-before-rebind, mirroring `yin.repl.driver` (driver.cljc:220,
  258, 293, 305). J3a gains an unsent-at-detach case; J3b a kill-with-unsent
  case asserting exactly one `:lost` completion and an empty `:unsent` after
  rebind.
- **C6.** Completion decode total by construction: a completion carrying a
  reason becomes `{:lost reason}` for any reason keyword, pass-through,
  qualified keyword unchanged, `/abandoned` included; reader `:dao.stream/end`
  and lifecycle `/ended` stay distinct. `request-*` outcomes are the RPC
  layer's set verbatim including `allocator-error`, which mints no id.
  Declaration-driven test that every keyword the RPC namespace can emit has a
  branch.
- **N1.** J1 deletes `src/cljc/dao/stream/log.cljc` and
  `test/dao/stream/log_test.cljc`, with *Boundary* recording it as the one
  deletion outside `dao.jing*` and the reason (per-transport retirement at the
  last consumer's migration). **Scope-contingent:** name the alternative you
  conceded — a stream-plan-owned "legacy append-log retirement" coordinated
  atomically with J1, two owners signing one change. Do not offer "leave it
  standing" as the fallback.

Also fix: the round-1 draft cited `jing_test.cljc` "lines 322-561"; the file is
560 lines.

Keep the document's existing structure, altitude and discipline. It remains
subordinate to `dao.stream.md` and `dao.jing.md`, and transient. End with a
short list of what changed from the round-1 draft and why, so the orchestrator
can diff the reasoning rather than only the text.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f
