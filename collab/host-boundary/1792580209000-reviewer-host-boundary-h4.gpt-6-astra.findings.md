# Independent architectural review — Slice H4

Date: 2026-10-21  
Reviewer: Codex, independent non-Claude architectural review  
Worktree: `/Users/sto/workspace/datomworld-host-h4`  
Reviewed commit: `36d00f3cf24137468506488036bca0b468d2fab5`  
Comparison parent: `13bf720d6cb856b8388bf8dd79f8d45f593489e1`

**Verdict: SIGNED OFF WITH CHANGES.** The portable writer's architecture and inspected byte production are sound. No encoding regression was found. Unconditional H4 acceptance requires the ownership regression test, missing Node benchmark evidence, and final gate classification described below. Repository landing verification remains separate.

I reviewed all nine changed files, including both deleted writers, against the supplied governing document at `/Users/sto/workspace/datomworld/docs/design/dao.stream.host-boundary.md`, particularly §§2.4, 4.2, 8–8.3 and 14, and the worktree's development rules. The requested document is described as r10 in the commission, but the actual file includes the later r11 retained-number-arm amendment at lines 1084–1087 and an older revision header. This report follows the text actually supplied, including that amendment. Its SHA-256 at review was `eea74f5da2b38b0c8b6df1014fe4cee6b56d51c0461a9eec17568157a74daedd`.

## Required changes

**H4-R1 — Medium: add the explicit §2.4(iii) writer ownership regression test.**

`test/dao/jing/cbor_test.cljc:289` checks that mutating an input after encoding does not change the output, and that decoded bytes are isolated. It does not snapshot the input before encoding, encode that same input twice, and assert that encoding left the input unchanged. Nor does it establish independence of the two returned buffers. The H4 diff removes the obsolete Boring-options test and adds no replacement ownership test. §2.4 expressly assigns this test to H4; source inspection and host primitive tests do not discharge that obligation.

Add a portable test that snapshots a byte input, encodes the same value twice, verifies equal outputs and unchanged input, then mutates one output and verifies that the other output and input are unchanged. Include a nested byte payload to exercise transitive ownership. Run it on all three lanes. My temporary JVM probe passed these properties for a direct byte payload, vector, map, set and metadata-bearing vector; this supports the implementation but is not a committed three-lane regression test.

**H4-R2 — Medium: complete and record the required Node measurements.**

The §8 H4 row requires old/new measurements for both the corpus and a large module image on **JVM and Node**. The commission supplies only JVM timings: 370/372 ms for the corpus and 297/382 ms for the image. These are engineer-reported figures, not independently reproduced measurements. No H4 implementation findings report or benchmark harness is included in this commit, and no Node measurements were supplied.

Record old/new Node results for both workloads in the slice report, together with the commands/harness, runtime versions, warm-up, iterations, and old/new revisions. Preserve the JVM results with equivalent provenance. The approximately 1.29× JVM image time is not itself a rejection: the H4 acceptance row specifies measurement without a numeric threshold. This review does not invent an OQ7 threshold or claim an owner decision absent from the evidence.

**H4-R3 — Low: finish the H4 gate classification.**

`test/dao/host/gate_test.clj:648` still marks `dao.jing.cbor` as `:migration-source` clearing in `"H4"`, with a reason saying that the writer remains to be migrated. The writer is now migrated. The governing document's retained-number-arm amendment explicitly allows the number-model arms to remain after H4. Change this row to the documented retained status (`:stays`) and describe the actual remaining number-model/runtime compatibility arms. Preserve the existing narrow category/class mapping; do not delete a still-needed allowlist entry or widen exemptions to hide it. Refresh the stale H2 totals description near line 986 while updating this ledger. Re-run the gate and closure check.

## Independent confirmation

| Invariant | Result and evidence |
|---|---|
| Self-contained portable byte writer | Confirmed. The namespace requires only `dao.host.bytes`, `.float`, `.integer`, `.number`, and `.text`. The new writer has no host-library import, Boring call, Dart CBOR call, IO, scheduling, global mutable buffer or added reader-conditional host implementation. Existing numeric classification and runtime compatibility arms elsewhere in the namespace remain; this is not a claim that the entire namespace has zero host syntax. |
| Shortest headers and numeric representation | Inspected `write-head` and `int-bytes` at lines 634–714: immediate/8/16/32/64-bit arguments, zero padding, negative `-1-n` mapping, and bignum tags retain the profile. Float64 frames, decimal/ratio tags, identifier/list/metadata frames and depth/refusal handling preserve the former wire construction. Corpus tests provide the executable oracle. |
| Canonical key ordering | Confirmed at lines 681–804. The comparator reads unsigned bytes via `bytes/get`, compares the first differing byte, and orders a proper prefix first. Both maps and sets use it. It does not sort by total encoded length first. A separate JVM probe verified `{24 0, -1 0}` produces `a21818002000`, distinguishing the two orders. Duplicate-encoding and portable-equality-collapse checks remain. |
| Mutation ownership | Source audit passes. Six `bytes/copy!` calls and two `bytes/set!` calls all write buffers allocated locally before return. Seven sites belong to encoding; the eighth is the unchanged reader assembly site at line 1023. Input byte arrays are copy sources only. No published buffer is mutated. Durable test obligation remains H4-R1. |
| Deleted writers and codec boundary | Both Jing wrappers are deleted. Separate `--required-by` scans each read 581 source/test files and report no remaining require of either namespace. `dao.stream.cbor.boring`, the Dart stream writer, and codec-track sources/tests are untouched. No dependency removal breaks the retained stream writers. |
| Closure and host-library gate | Confirmed. `host-boundary.closure --check` passes at CLJ 147 (139 shared/8 specific), CLJS 145 (139/6), CLJD 146 (139/7). Union/gate scope is 159. The diff removes only each lane's deleted Jing writer from the prior closure; traversal logic is unchanged. The passing JVM gate includes independent traversal agreement, arm/effect/library checks, negative fixtures and scan-failure tests. List 2's ceiling of eight matches the audited sites. H4-R3 concerns ledger accuracy, not a bypass of the scanner. |
| Corpus, errata and track scope | Corpus resources, errata, skip tables and goldens are unchanged in the diff. Only the obsolete Boring-options test is removed. The conformance suite's coverage and documented-skip assertions remain. No deferred §9 work was introduced. |

The existing `encoded-compare` docstring at `cbor.cljc:914` incorrectly says its length-first ordering is also the map/set order. That inconsistency predates H4; the new map/set writer does not call it. Correct the wording opportunistically without changing its public tie-break behavior in this slice. The CBOR test namespace docstring also still promises Boring-option checks after their removal.

## Executed verification and limits

Toolchain was activated through `mise` after trusting this worktree's inspected configuration. Missing npm dependencies were installed using `npm ci`; no tracked source or lockfile was changed by review.

- `mise exec -- bb test:sub dao.host dao.jing dao.stream`: JVM **787 tests, 15,129 assertions, zero failures/errors**; Dart **698 tests passed**. These equal the sums of the three subsystem counts in the commission.
- `mise exec -- node target/node-tests.js`, using the subsystem-selected build after `npm ci`: Node **769 tests, 10,531 assertions, zero failures/errors**. This equals the sum of the supplied Node subsystem counts.
- Corpus conformance: JVM 372 cases / 367 run; Node 372 / 366; Dart 372 / 367. Each reports the same canonical digest for 208 comparable cases: `8268b4d38ae11743dc6fc9f36191c7e68077c84f4abcbc2a7392aabff6c9c299`. Documented skip assertions pass; no skip lists were edited.
- Closure `--check`, both deleted-namespace `--required-by` scans, `git diff HEAD^ HEAD --check`, and the independent JVM ownership/order probe passed.

The first Node compile invocation exited zero although its autorun failed to load `@noble/hashes/blake3.js` because `node_modules` was absent. The aggregate runner ultimately labelled that lane FAIL because it had no test summary. I did not count that as a test pass; Node was rerun directly after `npm ci` and passed. Review logs and the temporary probe are retained under `/Users/sto/workspace/datomworld-host-h4/target/profile/h4-review/` (local, untracked verification artifacts).

**Landing gate remains outstanding.** `bb test:changed:list --base HEAD^` exits 2 and classifies the `src/dev/host_boundary/closure.clj` change as wide, directing the operator to run `bb test`. The three subsystem suites are G1 evidence, not the §14 G2/full-suite substitute; in particular they do not independently exercise the linker image suites. Run the required full fast gate before landing and the mandated master gate after merge, and the slow tier once after H4 landing. I did not run those repository-wide gates in this architectural review. The JVM subsystem log also explicitly skips the Dart WebSocket peer proof because `build/ws-project-peer` is absent; this is outside the CBOR corpus skips and must not be represented as an executed cross-host proof.

**Disposition:** the architectural direction and inspected implementation are independently confirmed, subject to H4-R1 through H4-R3. This is conditional slice sign-off, not an assertion that every landing acceptance obligation is already complete. No production changes were made by the reviewer.
