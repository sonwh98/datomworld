Session-ID: 01a0779a-7a9d-79e0-930a-ac19af7164b4

I’ll inspect revision 4’s lifecycle table, the delta review, and the RPC terminal paths, then decide the disposition and the two scope/documentation questions without editing files.
Completed-GMT: 2026-09-06 18:24:31 GMT
Completed-Local: 2026-09-07 01:24:31 Asia/Bangkok
Coding-Agent: codex
Session-ID: 01a0779a-7a9d-79e0-930a-ac19af7164b4

1. **Disposition: (a), with a J3 completion gate on the RPC fix.**

The plan should not compensate for `dao.stream.rpc`’s allocator defect. `allocation-failure` is a transport-layer terminal transition that fails to discharge `:outstanding`; `dao.jing.v2.remote` has no principled way to repair that state. Option (b) would make the Jing client understand and sweep an RPC implementation omission, duplicate transport lifecycle knowledge, and still leave the underlying RPC state stranded for any other consumer.

The plan should record the defect explicitly:

> `allocator-error` may strand outstanding RPC requests because the RPC layer enters terminal state without `lose-outstanding`; this is a `dao.stream.rpc` defect and is not compensated for by DaoJing.

The current “every state/event is exhaustive” wording is not honest while this behavior remains. Add an explicit lifecycle row for an allocator-error terminal with an already outstanding put or verify hop: no RPC completion reaches that record; the record remains unresolved under the documented lower-layer defect. Also narrow the preamble from “every event that can reach it” to “every completion/event delivered to the materializer,” while separately naming terminal transitions that produce no completion.

That keeps the table truthful without pretending the Jing client provides a total completion protocol over a broken lower layer.

2. **The RPC defect is an independent dependency and should gate J3’s completion, not be papered over inside J3.**

J3 may be developed against the normal RPC paths, but its completion criterion and end condition should require the allocator fix first: `allocation-failure` must discharge `:outstanding` before setting terminal, just like the other terminal paths. The fix belongs in `dao.stream.rpc`, with its own tests for `id-exhausted`, `id-collision`, and an outstanding bystander request.

If the stream fix lands before J3, DaoJing needs only the ordinary `allocator-error` row for the request that failed to allocate. If it has not landed, J3 can remain an explicitly incomplete implementation with the disclaimer above; it must not claim lifecycle completeness. Once the RPC fix lands, the disclaimer and special stranded-state row can be removed or converted into the normal loss row.

This is a dependency gate rather than a reason to add transport-specific cleanup to `dao.jing.v2.remote`.

3. **GLM’s P3: use explicit grouped no-op rows, and annotate synthesized completions.**

A footnote alone is too weak because the table explicitly instructs readers to verify exhaustiveness against its rows. Add grouped rows for:

- `:verify-unissued` plus driver `abandon`;
- `:verify-issued` with the get outstanding plus driver `abandon`;
- response-medium `gap` while `:verify-unissued`.

Each should state that the materialization record is unchanged and no materialization completion is published. Keep the rows compact; they are editorially small but make “impossible,” “no-op,” and “unhandled” distinguishable.

Also change the `routes by` heading or cells for synthesized terminal-sweep completions to say `synthesized; published under put id`, since those completions do not carry an RPC id. The existing rows that route by put or get id remain correct for RPC-carried completions.


