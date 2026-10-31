I’ll review the plan against the committed contract, the manifest validator, and the actual conformance-law harness. I’ll pay special attention to whether the proposed falsification test holds a pre-eviction cursor—otherwise it could repeat the exact blind spot this transport is meant to eliminate.

I’m using the repository’s `code-review-pr` checklist because this is a pre-implementation architecture and conformance review; I’ll use it to test the plan’s outcome algebra, behavioral laws, portability, and handoff completeness.

Completed-GMT: 2026-09-08 14:31:55 GMT  
Completed-Local: 2026-09-08 21:31:55 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Architect review: complete-retention v2 transport plan

## Verdict

**Not ready to implement.** The transport choice and core state model are sound, but three P1 issues must be resolved first: the contract’s `transport-error` wording, incomplete cursor validation, and an underspecified conformance law that could certify more than it proves.

## Findings

### P1 — The plan’s `transport-error` exclusion is right; the contract needs qualification

Files and lines:

- [plan](/Users/sto/workspace/datomworld/collab/1788876999259-architect-v2-complete-retention-transport.claude-fable-5-1.findings.md:107)
- [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:727)

**Blocks implementation: yes, because the committed contract currently says otherwise.**

The plan’s conclusion is correct: `memory-log` should exclude `transport-error` from all operations. A fatal host heap exhaustion is not a portable, recoverable operation result:

- JVM `OutOfMemoryError` should not be caught and presented as continued operation.
- JavaScript and Dart do not provide an equivalent portable interception point.
- The operation returns no result; it does not answer `ok` and subsequently lose an acknowledged value.
- The existing ring-buffer manifest already uses the same exclusion model for in-memory state.

The argument that the heap “is not the transport’s medium” is unnecessary and debatable—the vector is stored on that heap. The relevant distinction is between a failure the transport operation can observe and return from, and fatal host failure from which no operation result exists.

**Concrete change:** amend the contract before or in Phase A:

> Unexpected exhaustion that the transport can observe and return from is `transport-error`; fatal host/runtime exhaustion from which the operation produces no result lies outside the operation outcome algebra. It must never be converted into eviction of acknowledged history.

For `memory-log`, `transport-error` therefore has no firing condition. If an operation returns, its coherent in-memory transition has succeeded or produced one of its other declared outcomes.

Phase A currently says `dao.stream.md` is untouched; that must change.

### P1 — `next` is not total for negative or future fabricated positions

File: [plan](/Users/sto/workspace/datomworld/collab/1788876999259-architect-v2-complete-retention-transport.claude-fable-5-1.findings.md:168), especially line 174

**Blocks implementation: yes.**

The proposed validation accepts every integer position and then applies:

- `pos < tail` → `nth`
- otherwise → `blocked` or `end`

A cursor with the correct identity and position `-1` reaches `nth` with a negative index, which throws on at least the JVM. That violates `next`’s total outcome contract and creates cross-host behavior differences.

A fabricated position greater than the tail is also not a cursor the stream could have minted. Returning `blocked` treats it as valid despite the contract’s cursor-provenance rule.

**Concrete change:** validate the range before reading:

- Non-integer, negative, or `pos > tail` → `invalid-cursor`.
- `0 <= pos < tail` → `ok`.
- `pos = tail` → `blocked` while open, `end` after close.

Add tests for negative and beyond-tail positions on all three hosts.

### P1 — `run-retention-laws` needs a stricter, generic contract

File: [plan](/Users/sto/workspace/datomworld/collab/1788876999259-architect-v2-complete-retention-transport.claude-fable-5-1.findings.md:141)

**Blocks implementation: yes.**

The proposed law fixes the original blind spot for this writable transport because it retains an origin cursor before appending. However, four details remain:

1. **The falsification test must be committed.** Temporarily bolting `:retention :complete` onto the ring-buffer manifest proves the harness once; it does not prevent regression. Commit a test asserting the modified ring manifest fails with the expected retention-law violation.

2. **Do not require structural cursor equality generically.** Cursor representation is transport-owned, and two cursors denoting the same position need not be structurally identical. The memory-log-specific test may assert equality, but the shared law should compare their observations.

3. **Close must be included.** Complete history lasts for the logical stream’s lifetime, and the existing close laws only close a fresh instance. For a closable complete-history transport, append values, close it, and prove both a kept origin cursor and a newly minted owner `:oldest` still replay every value before ending.

4. **Reader-only complete histories need a fixture strategy.** `:retention :complete` is intended as a generic declaration, but a read-only immutable transport cannot be populated through `append!`. Either require a manifest retention fixture supplying a known prepopulated handle and expected values, or explicitly make the initial law writer-only until that fixture contract is designed. Do not let a reader-only manifest receive an effectively vacuous certification.

The shared law should:

- Validate that `:retention :complete` requires a reader surface.
- Validate that `next` excludes `gap`.
- Reject unknown `:retention` values so a typo cannot silently disable the laws.
- Retain an origin cursor before mutation.
- Compare complete observed sequences, not cursor representations.
- Exercise post-close retention where `:closable` is declared.
- Commit the ring-buffer falsification test.

With those changes, the test genuinely targets the P0 mechanism rather than freshly minting after loss.

### P2 — The unbounded decision is sound, but the permanent-`full` rationale contradicts the contract

File: [plan](/Users/sto/workspace/datomworld/collab/1788876999259-architect-v2-complete-retention-transport.claude-fable-5-1.findings.md:107), especially line 119

**Blocks independently: no.**

Excluding `full` is the correct semantic match for the old capacity-less v1 ring buffer. A configured maximum would introduce a new transaction failure for which `dao.space` currently has no policy.

But permanent `full` is not invalid, and retry is not the writer’s only sanctioned response. The contract explicitly says `full` may be transient or permanent and that the writer decides what to do with any non-OK result.

**Concrete change:** retain the decision but rewrite the rationale:

> A finite complete log could legitimately return permanent `full`, but doing so here would change existing `dao.space` semantics and require a rollover/abort policy that this migration does not have.

### P2 — Publication is not yet a sufficient rotation checkpoint

File: [plan](/Users/sto/workspace/datomworld/collab/1788876999259-architect-v2-complete-retention-transport.claude-fable-5-1.findings.md:121), and handoff at line 202

**Blocks independently: no.**

`publish-index!` produces a complete index manifest, but that alone does not make log rotation available:

- A new transactor over an empty log derives `t = 0`, colliding with prior history.
- Subsequent publication of only the new log omits earlier history.
- Queries need a snapshot chain or merge policy across rotations.

Rotation may be a future solution, but it requires a checkpoint carrying causality plus an index/query composition design.

**Concrete change:** say rotation is separate future work and is not currently available. Remove the implication that callers can already “publish and start a fresh stream.”

### P2 — Tighten the handoff and constructor contract

File: [plan](/Users/sto/workspace/datomworld/collab/1788876999259-architect-v2-complete-retention-transport.claude-fable-5-1.findings.md:192)

**Blocks independently: no.**

The handoff should preserve ownership: `dao.space` does not create its local stream; the host composition supplies it. Prefer:

> The host composition supplies `dao.space` a local handle created by `dao.stream.memory-log/create!`. Its declared complete retention makes fresh `:oldest` cursors true origin cursors for `derive-next-t` and `publish-index!`; supplying an evicting transport is a host-assembly defect.

Also state whether creation specs with extra keys are rejected or ignored. “No transport-owned keys” does not settle that, and the sole invalid-spec fixture covers only a wrong type. Add non-map, missing-type, wrong-type, and extra-key tests according to the chosen rule.

## Confirmed correct

- **Scope:** an in-memory log is the faithful migration. A durable file log would alter restart and identity semantics.
- **Manifest partitions:** every declared operation’s `produces` and exclusions are disjoint and cover its complete contract outcome set; all reasons are non-empty.
- **Absent `attach!`:** `validate-manifest` permits it. Only `descriptor` plus surface-licensed operations are mandatory. Host attach dispatch may therefore return `not-found`.
- **Retention model:** a dense vector with no first-position watermark structurally supports complete retention.
- **Anchors:** `:oldest = 0` and `:newest = count(values)` are correct; fresh `:oldest` is genuinely the origin.
- **Close:** owner cursors remain mintable after close, retained values remain readable, and the tail changes from `blocked` to `end`.
- **Append/close linearization:** one atomic state transition is the right implementation shape.
- **Portability:** the production design needs no reader conditionals and uses primitives already proven across clj/cljs/cljd.
- **No capacity, retention query, `closed?`, readiness, or encoding surface:** all are correctly excluded.
- **The proposed ring-buffer falsification is conceptually the right adversary**, provided it becomes a committed test and observes through a kept pre-eviction cursor.

**Final judgment: request plan revision before implementation.** The transport itself is the right one; the remaining work is to reconcile the contract’s fatal-host-failure wording, make `next` total, and define a conformance law that cannot over-certify completeness.
