Completed-GMT: 2026-10-04 06:51:45 GMT
Completed-Local: 2026-10-04 13:51:45 +07

**1. ACCEPT-WITH-CHANGES — the ledger-projection substrate is sound; the journal, reclaim adapter, and reopen contract need explicit corrections before implementation.**

A target projection makes the effect, its result, and dedup record interpretations of one committed transaction. That satisfies the atomic boundary required by UCF §7.7.5. Other observers can read the same ledger independently; the authority has no privileged global role. Its authority comes from possession of this admission resource, not from being a DHT node.

However, the journal is **an architectural amendment**, not already permitted merely because the transactor accepts a supplied stream. [Transactor T18](/Users/sto/workspace/datomworld/docs/design/dao.space.transactor.md:110) permits future complete-retention transports, while [“Where durability lives”](/Users/sto/workspace/datomworld/docs/design/dao.space.transactor.md:142) explicitly rejects a second durable stream record. Approve a narrowly scoped exception:

> An arbitration ledger may use a durable local stream as its sole authoritative transaction history. Its memory projection and published indexes are derived views, not separately committed authorities. This does not change the ordinary publication pipeline’s durability contract.

The plan must then resolve these concrete issues:

- **Serialize the whole decision.** One lock—or equivalent single-owner step discipline—must cover reading the current projection, checking tenure/scope/dedup, allocating transaction data, appending, and installing the resulting projection. The transactor’s internal timestamp lock alone does not prevent two decisions based on the same stale projection. Grants, reclaim, completion, quarantine, input recording, and admission all participate.
- **Do not call writability “reclaim.”** The existing [lease reclaim procedure](/Users/sto/workspace/datomworld/src/cljc/dao/lease.cljc:1380) expects the reclaim callback to perform the act before reporting success. The plan’s callback only checks writability. Specify the specialized adapter explicitly: the lapse transaction is the resource’s revocation linearization point, and no judge state claiming successful revocation may escape before that transaction commits. If preserving `judge-step` requires this interpretation, document and test it as a composition contract rather than claiming the existing callback already does it.
- **Poison the entire authority.** After uncertain durable append or failure between persistence and projection installation, block grants, reclaim decisions, completion, input admission, and protected effects—not merely `admit!`. No stale projection may remain an authoritative reader. Reopen must reconcile persisted frames before reopening service.
- **Rebuild the judge, not just epochs.** Recover `:seen`, `:answered`, accepted proposal identities, terminal lease history, and unresolved delivery state from authoritative records. Old accepted facts must not reseed tenure; old proposals must not obtain another grant. Rebuild while admission is disabled, then reclaim every recovered live tenure.
- **Adapt the content file rather than assuming it is a stream journal.** [Jing file puts deduplicate identical content](/Users/sto/workspace/datomworld/src/cljc/dao/jing/file.cljc:340). A general journal must preserve repeated equal appends as separate occurrences. Frame an explicit journal position with each value, or restrict and name the transport as a transaction journal. Persisted identity, positions, duplicate handling, and torn-tail recovery need a transport contract.
- **Define deterministic transaction ordering.** The transactor explicitly leaves entity-map attribute order host-dependent ([transactor.cljc:64](/Users/sto/workspace/datomworld/src/cljc/dao/space/transactor.cljc:64)). Authority transactions should supply deterministically ordered datom vectors. Define portable bounds for transaction time, input positions, and generated entity counters too.
- **Keep the durability claim precise.** File-backed, lock-held, and cleanly reopened is insufficient by itself for `exclusive-capable?`. The predicate must include the declared failure model, durable identity/content-reference persistence, and supported locking semantics. Dart’s directory durability limitation cannot disappear behind a boolean.

The accepted-checkpoint content file is not a second commit authority: durable immutable content may precede a ledger reference. An orphan blob is harmless; an acknowledged ledger reference to lost content is not.

**2. REVISE THE SLICING — stage-C obligations are broadly covered, but C1 is oversized and several dependencies are missing.**

C1 currently combines a transport, two backends, recovery, authority projection, enrollment, admission, and a target reader. Split it by contracts, not a 400-line threshold:

1. Journal grammar, memory backend, poison/reopen semantics.
2. File backend, identity/position persistence, locks and crash recovery.
3. Authority fold, enrollment, target projection, atomic dedup test seam.

A “dedup-only `admit!`” must remain an internal substrate test helper. It cannot be an exposed protected-admission entry point before binding and tenure checks exist.

The stage-C matrix is otherwise allocated reasonably:

| UCF clause | Plan owner | Required correction |
|---|---|---|
| 3: durable input replay | C7 | Define recovery frontier and input delivery acknowledgment. |
| 4: authoritative ancestry/membership | C5/C6 | Include child operations and unavailable checkpoint behavior. |
| 6: consumer envelope/diagnostics | C4 | Test diagnostic delivery failure and unauthenticated requests. |
| 7: grants, binding, reclaim, reopen | C2/C3 | Assign judge reconstruction explicitly to C3. |
| 8: admission and outcomes | C4/C6 | Add authenticated result redelivery after reopen. |
| 9: epoch bounds | C3 | Cover exhaustion blocking completion as well as admission/grant. |
| 10: wired composition | E | C8 proves substrate cuts, not E’s completion. |

Two sequencing changes are necessary:

- **C2 offer admission depends on C6’s baseline validation.** Either extract checkpoint verification and operation-baseline derivation before C2, or make early offers explicitly provisional and ineligible for grants. Do not admit variants first and discover conflicting pending intents later.
- **C5 depends on successor validation.** It cannot authoritatively close a predecessor using a merely readable map. It needs verified successor content, role/version validation, origin, arbitration identity, fresh occurrence, and operation baseline.

C7 needs more than dense `k` records. Specify when input recording is durably acknowledged, the replay prefix a regranted holder must consume, how it recognizes the end of that prefix, and how new observations extend it. Otherwise “missing replay evidence” is indistinguishable from “no input was recorded yet.” Include reads, gaps, FFI/link results, and ordering across child work.

C8’s repetition is intentional integration coverage, not double ownership. Its statement that C does not build “the host matrices” should be narrowed: C owns its three-host authority/consumer seam tests; E owns the wired holder/candidate matrices.

**3. AGREE — use authority-minted ledger-projection targets for the first enrolled boundary.**

This is a valid concrete realization of the already-approved atomic-resource restriction. The architect pair can settle it now; it does not require a new owner decision unless broader external-effect guarantees are requested.

State the guarantee precisely:

> Exactly-once commitment applies to insertion into the enrolled ledger-projection stream. It does not extend automatically to effects performed by downstream readers.

Forwarding from that stream to an existing FFI or link service remains at-least-once or fail-stop unless that service independently supplies the required transactional admission.

The target reader still needs a complete stream contract: stable identity, opaque positions stable across reopen, ordering, retention, and the exact append receipt stored in the admission result. Define these in the early substrate slices. An enrollment record alone is not a usable stream implementation.

Do not generalize the implementation choice into “all conforming enrolled targets must be newly minted.” A future target that genuinely joins the same atomic resource may conform without this construction.

**4. CLARIFY C/D/E — preserve the separation, but publish the shared interfaces before either side implements them.**

C should own the authority transition functions, durable input/result records, attributed binding evidence, enrollment, and pure checkpoint-baseline inspection. D owns runtime sequence assignment, retained pending state, body restoration, input delivery discipline, and fenced-writer behavior. E owns the wired end-to-end composition and its failure matrix.

Four interfaces are currently underspecified:

- **Checkpoint validation:** C cannot trust a handwritten `carried-ops` traversal while full validation waits for D. Extract a shared pure validator/inspector and common canonical fixtures. D adds restoration; C adds authoritative offer/completion checks.
- **Result delivery:** C must define durable result enumeration and redelivery with attribution. D supplies correlation and wait discharge. A crash after commitment must not require an ephemeral callback or a lost reply route.
- **Input replay:** C and D need an explicit request/record/acknowledge/deliver protocol, including replay frontier and child ordering.
- **Remote admission:** the plan excludes this front from C but later assigns it to D without a clear slice. Assign protocol and attribution contracts in C, driver integration in D, and partition/crash execution in E. Do not postpone the protocol itself to E.

The plan is ready to revise into implementation briefs. It is **not yet ready to hand C1 to an engineer unchanged**.

Read-only review; no files changed or suites run.
