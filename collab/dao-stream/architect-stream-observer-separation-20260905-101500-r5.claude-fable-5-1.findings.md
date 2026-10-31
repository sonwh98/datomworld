Completed-GMT: 2026-09-05 12:49:28 GMT
Completed-Local: 2026-09-05 19:49:28 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: f4e3fed9-73ca-4ea5-a485-a45263d8eff0

## Verdict

**Approve with nonblocking notes.** The replacement plan makes the transport separation real: the observer's inputs are a descriptor and a unary attach function, its dependencies are the DaoStream protocols and outcome vocabulary, and every ring-buffer detail is confined to the REPL realization and its own tests. Both r4 blockers are resolved in the plan text. No implementation decisions remain that could produce a faulty implementation.

## Remaining findings

Severity | Evidence | Minimum correction

**Low | §2 "Mint with the existing `vm/mint-oldest` behavior or an equivalent generic DaoStream cursor check" versus §1 "depends only on `dao.stream`"**
`vm/mint-oldest` lives in `yin.vm` at `v2.cljc:183-197`. Requiring it is not a transport leak and creates no cycle, but it makes the §1 statement literally false and weakens the §5 dependency check. Correction: pick the inline form. It is one `stream/cursor` call and one outcome test, and it lets the dependency check assert `dao.stream` alone.

**Low | §1 and §2 "expose a descriptor-only attachment entry" while "bind the capability once"**
A namespace function cannot be descriptor-only without holding the capability somewhere, and the only correct place is a lexical closure. The plan says this in prose but does not fix the arity. Correction: state that the namespace exports a two-argument function taking the attach capability and the descriptor, and that the unary entry shown in §1 is the host's partial application of it. This forecloses the one wrong reading, a namespace-level `def` holding the capability.

**Low | §3 "Remove or privatize obsolete orchestration functions that have no production callers"**
The REPL is a production caller of run coordination, since datom-literal evaluation must loop observe, load, run until blocked or end. Under the plan's own rule that coordination is therefore public. Correction: name it. The public surface becomes the attachment function, `observe-next`, and the run coordinator. Single-step coordination has no production caller and may go private or be deleted with its tests. This is a clarification, not a change of policy.

**Info | §1 example realizations**
`ringbuffer/make-attacher` exists at `ringbuffer.cljc:194`, `ws/make-attacher` exists at `ws.cljc:309`, and `host-dispatch-attach!` exists at `v2.cljc:380`. Naming them in explanatory text creates no code dependency: the observer receives a function value and never requires those namespaces. The §5 checks, no transport namespace in the observer's requires and no transport name in generic observer tests, are sufficient to enforce the distinction.

## Reconciliation of r4 findings

- **Resolver per medium (blocker).** Resolved at §4: resolver, attacher, observer, and VM are rebuilt with each new medium on reset and VM selection, with a reset-then-datom-evaluation test.
- **Failure reporting mode (blocker).** Resolved at §2: attach failure, missing reader surface, and cursor failure throw `ex-info` carrying the original outcome, and no failed observer can enter REPL state.
- **Reader-surface check.** Resolved at §2: `stream/reader?`, classified as an assembly defect, no invented outcome.
- **CLJD owner handle.** Resolved at §4: the resolver maps identity to the owner handle on every host, matching `resolve-state` at `ringbuffer.cljc:163-170`.
- **Cleanup.** Resolved by explicit deferral at §2 and §5: no `close!` on failure or reset, with the contract's permission noted.
- **Public surface.** Resolved by policy at §2 and §3, with the clarification above.

## Properties that passed review

- **Dependency boundary.** The observer touches descriptors, attach outcomes, `reader?`, cursors, `next`, and outcome maps, all in `dao.stream`. It never creates, appends, or closes.
- **Descriptor as the sole per-stream input, capability as a composition dependency.** Matches the contract's Host Dispatch and Surfaces sections: the interpreter receives the operation as an ordinary argument and does not detect its transport.
- **No hidden global state.** The unary attacher is a closure over host data built beside the medium it names. Nothing ambient is consulted.
- **Prior decisions coherent.** Idle-step guard scoped to the readiness predicate (§3), loader failure returns no session and the poison batch is re-observed (§3, §4), gap advance and count unchanged (§2), direct-eval decoupling recorded (§3), writer handle separate in shell state (§4), forged `:halted? false` gone by consequence of §2.
- **Observation stays outside the instruction loop.** Readiness gating unchanged; no prefetch.
- **Attachment semantics on the current transport.** One attach per observer, live reads through an open attachment, cursor bound to the logical stream, close of an attachment never closing the owner. All verified against `ringbuffer.cljc` and pinned by existing ring-buffer tests.
- **Test partition.** Generic tests over a fake conforming attacher; transport tests separate; dependency and stale-reference checks in the run list.
- **Portability.** Plain maps, string identities, `ex-info`, and `satisfies?`-based surface checks already in cross-host use.

No implementation decisions remain before approval. The three notes above are wording clarifications the implementer can apply without another review round.

