Completed-GMT: 2026-10-05 17:55:04 GMT
Completed-Local: 2026-10-06 00:55:04 +07

**Ruling: accept the engineer's option A with amendments. The header enters at `prepare` as one argument in wire keys, is stored in the export record, and the lift proves its own output by running the version-1 inspector and the D7 reader's validation on it before answering. Phase and parent already come from the machine's install entries. The fixture regeneration commits to real lifts for the bases, mutations found by predicate, and hand-set op ids marked for D11.**

Read-only; nothing edited, no suites run. I read the engineer's report (in the `datomworld-d9` worktree; it is not in the main tree's `collab/`), `export-installs` in `handoff.cljc`, and the D7 reader's phase and parent check. I did not re-read the fixture namespace or `checkpoint-v1.txt`.

## 1. The header channel

**Accepted: option A.** Options B and C are rejected for the engineer's reasons: B needs the custody map, which D10 defines, and C leaves the record unable to rebuild the encode for D14's journal.

### The argument

`(export/prepare machine record serve! header)`, with `header` either `nil` or:

```clojure
{:yin.k/occurrence  O
 :yin.k/arbitration {:dao.stream/identity i :dao.stream/descriptor d}
 :yin.k/origin      {:yin.k/occurrence P :dao.lease/lease L :yin.k/emitter a} ; absent on a first export
 :yin.k/next-op-seq n
 :yin.k/enrolled    #{stream-identity ...}}
```

- **Wire keys.** The first four are the body's own header keys and are copied verbatim. `:yin.k/enrolled` is not a wire key and is never encoded.
- **`nil` header means version 0.** The lift is today's fork lift, in the stream codec, unchanged. A header means version 1, exclusive, in `dao.jing.cbor`. The lift adds `:yin.k/policy :yin.k/exclusive` itself.
- **Stored in the record** as `:header`, beside `:served`. `encode` reads only the machine and the record.
- **`prepare` mints nothing.** The occurrence is minted once by the driver and persisted before `prepare` is called (D14).

### Who supplies what

- **First export:** the composition mints `O`, names the arbitration medium, passes `:yin.k/next-op-seq 0` and no origin, and takes `:yin.k/enrolled` from the ledger reader's fold.
- **Successor:** the driver builds the header from the holder's custody map: a fresh `O`, the same arbitration, an origin naming the predecessor occurrence and lease, and the current counter. D9 does not read `:yin.k/custody`; D10 defines that map and D14 builds headers from it.
- **`:yin.k/emitter`** is the composition's own attribution identity. It is a claim; the inspector only requires it non-nil.

### Who validates what

- **`prepare`, on the argument's shape.** A header that is not a map, or lacks `:yin.k/arbitration`, `:yin.k/next-op-seq` or `:yin.k/enrolled`, is a caller defect and throws, like other argument defects.
- **The lift, as data refusals:**
  - `:yin.k/non-portable`, kind `:op-seq-exhausted`, when the header's counter is 2^52-1.
  - `:yin.k/non-portable`, kind `:unprotected-pending`, for a retained `:put`, `:ffi-request` or `:link-request` entry, in the root or a child, that has no op id and whose target identity is in `:yin.k/enrolled`. This applies to every export, not only the first.
  - `:yin.k/unsatisfied` naming the stream, for an entry that carries an op id whose target is not in `:yin.k/enrolled`.
- **Kind and header agreement is enforced at lift, not left to lower.** The lift selects header keys by the machine's kind:
  - blocked or parked root: occurrence, arbitration, counter, policy, and origin when present;
  - halted root: origin only; the other header values are ignored;
  - install children: no header key.
- **Self-check.** Before answering `:ok`, the lift runs `checkpoint/inspect` on its own bytes and address, then the D7 reader's `validate-body` on the body. Any refusal from either is returned as the lift's refusal and nothing is published. This keeps one implementation of each grammar and makes "the reader accepts every lift" a property of the code.
- **A first-export halt** has no origin, so the self-check refuses it. That is intended: per the C4 ruling a halt with no origin is a version-0 result, and the composition lifts it with a `nil` header.

### What `:yin.k/unsatisfied` covers

- **Before `prepare`, from the composition:** an incomplete or unavailable ledger fold, or no arbitration descriptor. The lift never sees a partial enrolled set.
- **From the lift:** a stream that cannot be served (as today), and an op id on an unenrolled target.

Enrollment is add-only, so a stale enrolled set can only miss a newer enrollment. The lift then does not refuse, and the resumer's protection check at lower refuses instead. That is acceptable.

## 2. Phase and parent on install entries

They come from the machine's own install entries, and nothing new is needed. `export-installs` already reads `phase`, `parent` and `response` from each entry of `(:installs vm)` and emits `:yin.k/phase` and `:yin.k/parent`. `enter` does not move `:installs`, so they are still on the exporting machine when `encode` runs.

Rules for D9:

- Under version 1 the lift always emits both keys.
- A phase outside `:running` and `:parked` refuses the lift as `:yin.k/non-portable`, kind `:incomplete-install`. The engine's transient phases are not wire phases.
- Whether a `nil` parent is admissible is the D7 reader's rule, and the self-check applies it. D9 adds no rule of its own.

They do not belong in the header, which is the root's custody data, or in the child's body, which under 7.2.1 carries no header and under 7.4.3 is the child's machine state.

## 3. The fixtures

**What D9's regeneration commits to:**

- **Bases come from real lifts.** `first-park`, `successor`, `parked`, `installs` and `halted-root` are produced by `enter`, `prepare` and `encode` over real machines built in the fixture namespace, with headers supplied by the fixture. The placeholder segment goes away.
- **Machine builders may be shared.** If building those machines needs helpers that live in `handoff_test.cljc`, move them to a shared test-support namespace. The permitted diff widens to that file and the new namespace.
- **Mutations are found by predicate, never by index.** A mutation names its target as "the first frame whose pending reason is `:put`", not `[:yin.k/frames 1 ...]`. Each mutation fixture's test asserts two things: the mutated body differs from its base, and the refusal's `:yin.k/path` names the mutated place.
- **Variants stay mutations** of the `successor` base: `variant-equal`, `variant-different-intent`, `variant-counter`.
- **`counter-at-bound` stays a mutation.** A conforming lift refuses a counter of 2^52-1, so no lift can emit it. The inspector still accepts it, and the fixture keeps pinning that.
- **Byte-level fixtures** (`non-canonical`, `hash-mismatch`) keep the existing patch mechanism.
- **Addresses are re-pinned once, in this commit.** The file keeps full bytes for the three anchors only.

**Op ids set by hand.** No code assigns them until D11, so the `successor` and `installs` bases set `:yin.k/op-id` on the machine's wait entries directly. D9 commits to:

- one helper in the fixture namespace that sets them, with a comment naming D11 as the slice that replaces it;
- an obligation recorded for D11: a test that a real fenced-writer run produces wait entries equal in shape to what the helper sets. If D11 changes the shape, D11 regenerates the fixtures.

## 4. The report's other questions

1. **Halted root origin:** taken from the header's `:yin.k/origin`. A first-export halted root is refused under a header and lifted as version 0 without one (section 1).
2. **Emitter:** supplied by the composition, yes.
3. **Re-keying the served table:** yes, it lands in D9 with the header. Key each answer by task path and resource id, and store the retained descriptor as the value. The record is then plain data, which D14 needs. `encode` looks answers up by that key and still throws on a miss.
4. **`prepare` running a whole lift:** unchanged for D9. It is the simplest way to collect every stream.

**Changes to the D9 brief:**

- The permitted diff is `holder/export.cljc`, `handoff.cljc`, `checkpoint_fixtures.cljc`, `checkpoint-v1.txt`, their tests, and the test-support namespace of section 3.
- Added test rows:
  - a `nil` header gives today's version-0 bytes unchanged;
  - a header gives a body the inspector and `validate-body` both accept;
  - a halted machine with a header and no origin is refused; with an origin it emits origin only;
  - each of the three lift refusals of section 1, in the root and in a child;
  - a transient install phase refuses;
  - the record round-trips through the canonical codec and encodes to the same bytes afterwards.
- **Document text:** UCF 7.7.4 or 7.8 gains the header as the lift's input and the self-check; linker-dht 14.2.2 notes that the enrolled set is a lift input and never travels.
