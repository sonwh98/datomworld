Completed-GMT: 2026-10-04 07:27:35 GMT
Completed-Local: 2026-10-04 14:27:35 +07

**Summary: the inspector is correct on the codec and on 11 of the 16 questions; it needs five amendments before commit (segment-address form, intent digests, non-nil emitter, an entry for every install pending, and a fixture file cut from 514 KB to about 40 KB).**

Read-only; nothing edited and no suites run. I read the engineer's findings, the check order and intent code in `checkpoint.cljc`, the fixture file's shape, and the relevant `dao.jing` and `handoff.cljc` lines. I did not read the test bodies line by line. The clock reads earlier than the brief's Created-GMT; the header is the clock's value.

## The five priority rulings

### 1. Codec: the inspector is correct as built

- **Version 1 uses `dao.jing.cbor`.** UCF 7.2.1 says the body is "one map in canonical CBOR (`dao.jing.cbor.md`) under its content address". The bytes are `jing/canonical-bytes` of the body, and the address is the hash of those bytes.
- **The landed version-0 export is inconsistent.** `handoff.cljc` line 719 encodes with `dao.stream.cbor`, while line 725 addresses with `jing/content-hash`, which hashes the `dao.jing.cbor` bytes. I did not test whether the two codecs produce different bytes for a handoff body; the engineer says they do.
- **Consequence for D.** Version-1 export and every version-1 reader use `jing/canonical-bytes` and `dao.jing.cbor/decode`. The version-0 mismatch is a third post-A defect to record beside the two in linker-dht 14.3; it is fork-only and does not touch custody.

### 2. Address form and status name

- **Form: the segment address keyword, `:segment/<algorithm>-<digest>`.** Three reasons:
  - UCF 7.2 defines an address as "the `dao.jing` segment address".
  - The plan stores accepted checkpoints in a `dao.jing.file` content file, whose put throws on anything that is not a `segment-address?` (`jing/file.cljc` 349).
  - The resumed report's `:yin.k/result` is such an address.
- **Algorithm: whatever the address names**, through the registry, not BLAKE3 only. Use `jing/segment-bytes-match?`.
- **Status: `:yin.k/hash-mismatch`, accepted.** UCF 7.9 defines it as "the address claimed and the address computed", which is not limited to code vectors. The refusal must carry both: `:yin.k/address` (claimed) and `:yin.k/computed`.
- **A claimed address that is not a segment address** is the same refusal, with the computed BLAKE3 segment address.
- **D shares this.** Version-1 `export-task` answers the segment address under `:address`. Bare hex stays a version-0 detail.

### 3. Intent comparand

- **Dedup compares actual intents only.** UCF 7.7.8: intent is computed by the consumer from "the envelope's `:yin.k/value`", the decoded program value. The dedup record holds that, and only another envelope's intent is ever compared with it.
- **The baseline's carried intent is never a dedup comparand.** It has two uses: variant against variant (7.7.8 *Snapshot variants*) and id membership (C9). Neither needs 7.5 decoding, so the authority stays free of the VM.
- **The inspector changes in one way:** store the digest of the canonical intent bytes, not the bytes as hex. The baseline goes into the ledger's offer fact, and a retained payload can be large. `:yin.k/ops` becomes `{op-id <BLAKE3 hex of the canonical bytes of [:yin.k/append target payload]>}`.
- **Target and payload choices accepted:** `:put` uses `:yin.k/stream` and `:yin.k/value`; `:ffi-request` uses `:yin.k/request` and `:yin.k/request-envelope`; `:link-request` uses `:yin.k/request` and `:yin.k/envelope`. Effect kind is always `:yin.k/append`.
- **A constraint D inherits:** two variants of one occurrence must encode a retained payload to the same bytes. If lift-local cell ids or value-table refs differ between two lifts, the authority refuses the second variant. That fails safe, but D should make the encoding deterministic.

### 4. Fixture file size: must shrink

514 KB is not acceptable. It is large because every one of the 45 fixtures stores a whole body of roughly 5.7 KB as hex, and each keyword costs about 25 bytes of tag overhead. The bodies already exist as data in `checkpoint_fixtures.cljc`, and one test already asserts the file equals `render` of them, so most of the file is redundant.

Replacement:

- **Every fixture:** name and address only. The address pins the exact bytes on each host, because the test builds the bytes from the body and hashes them.
- **Three anchors keep their full bytes:** `first-park`, `successor`, `installs`. They prove the decoder reads bytes this build did not produce.
- **`non-canonical`** is built at test time by patching the anchor's bytes, and pinned by address.
- **Target:** about 40 KB.

### 5. Fixture authority

- **Yes, D regenerates the accepted fixtures** through a real version-1 export once lift exists, so the same bytes also pass restoration. D changes the pinned addresses in that one commit.
- **Until then the inspector tests pin three things:** the fixture names, the expected outcome per name (the baseline, or the status with its path), and the addresses.
- **Refusal fixtures must be mutations of an accepted base body**, named by what they change. When D replaces a base, the refusals follow without being rewritten. The inspector runs first, so D gets the same refusal either way.

## All 16 questions

| # | Question | Ruling |
|---|---|---|
| 1 | Byte codec | Accept. See ruling 1. |
| 2 | Address form | Amend: segment address keyword, algorithm from the address. See ruling 2. |
| 3 | Mismatch status | Accept the name; amend the payload to carry claimed and computed. |
| 4 | Halted root origin required | Accept. 7.2.1: a halted root "carries `:yin.k/origin`". A halt with no origin has no custody chain, so it is a version-0 result. |
| 5 | Origin shape | Amend: `:yin.k/emitter` must be present and non-nil. 7.2.1 shows all three keys; a nil emitter is no attribution. Other qualified keys are ignored. |
| 6 | Arbitration shape | Accept. |
| 7 | Carried intent | Accept the comparand split; amend to store a digest. See ruling 3. |
| 8 | Child version statuses | Accept. A version-0 child is `:yin.k/undecodable` (clause 1, "the mixed tree"). An unpublished child version is `:yin.k/profile-mismatch` with a path, since 7.2.1 extends the gate to nested bodies. |
| 9 | First-export counter is 0 | Accept. It is a 7.2.1 header rule, and those are `:yin.k/undecodable`. |
| 10 | Ids on a halted root | Accept. The outcome is `:yin.k/undecodable` either way; the kind detail is not contract. |
| 11 | Id occurrence differs from the body's own | Accept. Ancestry is C9's. |
| 12 | Op id has exactly two keys | Accept. 7.7.8 states it for the id itself. |
| 13 | Unknown reasons refused | Accept. Same outcome as D's grammar; D still owes clause 5's evidence. |
| 14 | Install phase and parent | Do not check them; clause 5 is D's. Amend one thing: every `:install` pending must have its entry, because the baseline claims "children included" and a missing entry silently drops a child's ids. |
| 15 | Fixture realism | See ruling 5. |
| 16 | File size | Must shrink. See ruling 4. |

Deviation 1 (adding `:yin.k/kind` to the baseline) is accepted. Deviation 3 (reusing `dao.jing.cbor-fixtures`) is fine.

## Engineer checklist, now

1. `inspect` takes a segment address and verifies it with `jing/segment-bytes-match?` under the algorithm the address names.
2. `:yin.k/hash-mismatch` carries `:yin.k/address` and `:yin.k/computed`; a non-segment address gets the same refusal.
3. `:yin.k/ops` values are BLAKE3 digests of the canonical intent bytes.
4. The `checkpoint.cljc` docstring says the carried intent is for variant comparison and membership only, never a dedup comparand.
5. Origin requires a non-nil `:yin.k/emitter`; add a fixture.
6. Every `:install` pending requires its entry, refused as `:yin.k/undecodable` at the pending's path; add a fixture.
7. Reshape `checkpoint-v1.txt` as in ruling 4, and express refusal fixtures as mutations of a base body.
8. Run the Dart lane and cljstyle, which the report says were not run, then the three lanes for the changed files.

## Deferred to D

- Version-1 export with `jing/canonical-bytes` and a segment address.
- The version-0 codec and address mismatch, as a third post-A defect with its own version-0 test.
- Regenerating the accepted fixtures through a real export.
- Install `:yin.k/phase` and `:yin.k/parent`, the halted-forbids-frames rule, and the rest of clause 5.
- Deterministic payload encoding across snapshot variants.

## Deferred to document text

- **UCF 7.2.1:** name `jing/canonical-bytes` and the segment-address form; say a halt with no origin is a version-0 result; say the emitter is required.
- **UCF 7.2.1 version gate:** name the statuses for a nested body (ruling 8).
- **UCF 7.4.3:** the entry-for-every-install rule is also a custody-baseline rule.
- **UCF 7.7.8 *Snapshot variants*:** comparison is on digests of the carried encoding; a variant that encodes a payload differently is refused.
- **UCF 7.9:** `:yin.k/hash-mismatch` covers a body's own address as well as code.
- **Linker-dht 14.3:** the third version-0 defect.
