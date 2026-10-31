Completed-GMT: 2026-10-07 13:06:17 GMT
Completed-Local: 2026-10-07 20:06:17 Asia/Ho_Chi_Minh

**Ruling: strengthen the assembly contract. D15a must receive replayable, non-destructive inboxes; the existing function-shaped readers are insufficient.** The reported crash interval is real. A journal append after a destructive read cannot repair it. D15a owns the driver contract and test-composition upgrade; D15 owns the production adapters.

### 1. Inbox contract

Replace the driver’s `:read-reply!` and `:read-outcome!` assembly inputs with two explicitly versioned inbox descriptors:

```clojure
:reply-inbox
{:version 1
 :identity portable-stable-identity
 :read-at! (fn [position] result)}

:outcome-inbox
{:version 1
 :identity portable-stable-identity
 :read-at! (fn [position] result)}
```

Each source has dense positions starting at zero. A position is an exact portable integer within the journal’s existing bound; exhaustion refuses advancement, never wraps.

The result is a composition-local closed union:

```clojure
{:status :record
 :position n
 :author authenticated-source-attribution
 :record plain-canonical-data}

{:status :empty}

{:status :unavailable}
```

These are **new assembly-result shapes**, not additions to DaoStream outcome maps or UCF bodies.

The required guarantees are:

- `read-at!` is non-destructive. It never advances a hidden consumption cursor.
- The same identity and position always name the same attributed record, including after process restart.
- `:empty` means that position is beyond the currently available tail. It does not advance the position.
- Missing history, an inaccessible source or a retention gap answers `:unavailable`, never `:empty`.
- Records needed by the holder remain reproducible throughout its recovery lifetime. D15a has no truncation/acknowledgment protocol; therefore this version requires complete retention.
- Attribution remains subject to the existing front/outcome authentication checks. Durable storage does not make an unauthenticated record authoritative.

Assembly checks the descriptor version, stable identity and callable operation, and verifies that reopened descriptors match the journal’s recorded source identities. It cannot prove storage durability by checking a function. The adapter’s recovery guarantees must be established by its implementation and crash tests. A `:durable? true` flag is not proof.

There is **no automatic adapter around the old destructive functions**. An upstream adapter must retain a record durably before acknowledging or irreversibly consuming its source. Writing to a spool after calling the existing `reader-over` merely relocates the same defect.

### 2. Ordering and the four-state retention machine

Clarification of my previous ruling: preserve **per-source order and the driver’s durably selected merge order**. The two independent streams do not establish an external global arrival order. We must not invent one.

Use the current reply-first selection policy for compatibility. Once the driver selects a record, append a progress-journal inbox-retention record containing:

- source lane and stable source identity;
- source position;
- complete author and record;
- the relevant local run/binding association, when one exists.

Its journal position establishes the merge order. Do not give it a second persisted ordering counter.

The four states are:

| State | Contract |
|---|---|
| **Unselected** | The next source position is derived from reconciled retention records. Reading is non-destructive. |
| **Selected, not retained** | The complete observation exists only locally. It may not be dispatched, applied or skipped. Crash recovery reads the same source position again. |
| **Durably retained** | The retention append is confirmed or found during journal reconciliation. Only now may the source position advance and the observation enter the ordered dispatch queue. |
| **Dispatched** | A control record has entered the existing authenticated protocol handling, or a program record has been applied by an authorized program step. The durable retention record remains recovery evidence. |

An uncertain retention append stalls the driver. Neither source advancement nor application may proceed until reopen determines whether that record exists. Repeated recovery of the same lane/identity/position is one observation; conflicting contents are corruption and refuse recovery.

Control records may be handled while earlier program records remain deferred—otherwise a program record could prevent renewal. Preserve order among deferred program records, and among the selected control records. This is the explicit exception to global application order required by the split.

**Dispatched is not a durable assertion that an arbitrary VM mutation survived.** Do not add an “applied” bit and then skip that input when rebuilding a fresh machine. Existing recovery still governs:

- control actions reconcile through their journal brackets and authenticated evidence;
- a surviving in-memory machine must not receive the same observation twice;
- a restarted machine follows the existing checkpoint/regrant/replay procedure;
- records belonging to an old lease are never blindly applied to a new run.

The inbox journal protects receipt and attribution. It does not replace the authority’s recorded input prefix or create a new machine checkpoint.

This protocol closes every read/retain cut: before retention, the source remains replayable; after retention, the journal contains the complete observation. A crash before retention commits need not preserve an uncommitted choice between two simultaneously available sources.

### 3. Existing compositions and ownership

**The current test world does not satisfy this contract.** `reader-over` advances its private cursor before returning (`driver_test.cljc:239–250`), and assembly only checks that reader seams are functions (`driver.cljc:134–136`, `190–191`, `253–256`). Upgrade it in D15a.

Use complete-retention sources with stable identities and positional reads. Tests may use memory-backed journals whose backing frames survive reconstruction of the driver and adapter. Label that as simulated process recovery, not proof of durable media.

The future REPL composition must provide conforming adapters in D15:

- The outcome adapter may derive a stable ordered source from complete authenticated ledger history. Its indexing must remain stable as the ledger grows; missing history is unavailable.
- The reply adapter needs an actual replayable reply source or an upstream durable capture protocol. An arbitrary zero-argument callback or ring subject to overwrite is insufficient.
- D15 must pin both adapters’ recovery guarantees. If the landed front/reply transport cannot support one without changes outside D15, report that specific prerequisite; do not weaken this contract or claim request retries reconstruct the original inbox order.

Request retry remains essential for custody progress, but is not the chosen proof of inbox retention.

### 4. D15a implementation delta and acceptance

D15a’s permitted scope now includes the two inbox descriptors, assembly/reopen validation, progress-journal retention records and folding, a small holder inbox helper if useful, and all affected test fixtures. No authority, lease, engine or handoff-wire change is authorized.

Remove the legacy destructive-reader dependency from the split driver, including its convenience `step`. Existing tests must be migrated rather than silently retaining an unsafe compatibility path.

Add these acceptance rows to the previous ten:

1. Crash after source read but before retention: fresh driver and adapter reproduce the same attributed record.
2. Crash after retention but before local cursor advancement: recovery creates one queue entry, not two.
3. Uncertain retention append, both persisted and absent cases: stall first; reconcile before advancing or dispatching.
4. Mixed replies/outcomes: retained journal order survives reopen; deferred program records do not block renewal and preserve their own order.
5. Stable-source enforcement: changed identity, conflicting contents at one position, missing history and position overflow all refuse progress.
6. Fresh-machine recovery: no old-lease acknowledgment is applied to a new run; recorded inputs replay through the existing prefix contract.
7. Assembly rejects old reader-only configurations. The upgraded adapters pass the crash rows on JVM, Node and Dart.

**Disposition: continue D15a under this amended contract; no further architectural blocker remains for the driver split.** D15’s production inbox adapters are explicit integration obligations, not assumptions inherited from the old callbacks.

No files were edited and no suites were run.