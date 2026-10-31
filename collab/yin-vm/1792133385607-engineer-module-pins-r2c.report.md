Completed-GMT: 2026-10-16 06:55:57 GMT
Completed-Local: 2026-10-16 13:55:57 +07
Coding-Agent: codex
Session-ID: unavailable (provider not exposed)
Role: VM Runtime
Branch: srvm-module-pins
Base: 62121c7e
Brief: collab/1793600000000-engineer-module-pins-r2c.prompt.md

# Module pins r2c: empty emission resolved; address-value type stopped

## Decision supplied and recorded

Both `:yin.k/modules` and `:yin.k/host-exports` must ALWAYS be emitted,
including `{}` when empty. A missing key is `:yin.k/undecodable :requires`.
This supersedes the earlier omission sentence. Recorded as a definite decision
in transport grammar, I-8, continuation Q5, UCF acceptance blockers and known-gaps.
No “Architect to confirm” qualification is attached to the empty-map decision.

## STOPS

The inherited brief explicitly requires stopping an ambiguous sub-part.
Architect B.1.3 and step 9 require module-pin values to be **string-or-nil**,
while B.1.2 and B.1.4 require keeping `(:address entry)` and comparing it by
exact equality with the receiver's address. Actual linked entry addresses are
**keywords**, not strings: `module/link-module` assigns
`(jing/segment-key manifest)` (`src/cljc/yin/vm/module.cljc:92`), and
`rows-support/installer-lower-opts` uses that same value. A concrete example
from the fixed module-response manifest is
`:segment/blake3-c293663b75d7884646a22b9d7ae53356464a20a1a542b1a648489782480f2efa`.

Copying the address violates the stated string validator; converting it changes
the carrier's canonical representation and also requires a specified conversion
for receiver equality. I did not silently choose either format. An asynchronous
question asks whether to carry existing keyword addresses or convert them to
strings. No answer has been received at report creation.

## Per-step results

8. STOPPED pin collector/emission on the address-value type. The empty-map
   decision is resolved and is not the stop. Existing r2b lift admission-key
   stripping remains intact.
9. STOPPED encoder/decoder/validator and malformed-pin collection pending the
   address type. Draft malformed cases and T1–T4 tests were explored, then
   retained outside the active suite in `/tmp/module-pins-r2c-pending-tests.patch`;
   they are unfinished drafts, not completed verification.
10. STOPPED the collected module/host-export gate pending the carrier. The
    existing install-admission gate remains; no claim of collected discovery
    or new pin mismatches in implemented code.
11. Existing r2b driver seam and driver regression preserved and reverified.
12. T9 IMPLEMENTED: source-based gate in `yin.vm.linker.layering-test` scans
    engine, semantic and semantic subnamespaces, and rows, including reader
    conditional branches, for forbidden linker interpreter/DHT/lease requires.
    T10 IMPLEMENTED: direct `engine/resolve-var` lookup of absent qualified
    `absent.mod/f` retains the exact `Unable to resolve symbol` exception;
    both composition link streams remain empty. Engine implementation unchanged.
    T1–T4 and T8 remain pending; T5 existing assertions preserved and exercised,
    without falsely updating them to the unimplemented collected refusal shape.
    T6/T7 remain composition work outside this slice.
13. Five mutants NOT RUN because the implementation they mutate is stopped:
    all-exports lift; name-presence lower; different-profile acceptance;
    incomplete discovery; attachment before module check. No named-killer or
    kill-count claim is made. No mutant code is present in the worktree.
14. NOT RE-MINTED. No fixture changes, old/new address table: none.
    All 53 rows fixture packs were captured before work, with raw CBOR hex
    saved in `/tmp/module-pins-old-hex.edn`; the old ledger render is in
    `/tmp/module-pins-old-ledger.txt`. `git diff --quiet -- test/resources`
    exits 0. There is no re-mint or “only two added keys” comparison to certify.
    No pinned A, descriptor, non-rows golden or scanner pin moved.
15. Partial docs only: empty-map decision and new address-type stop recorded.
    I-8 remains explicitly incomplete. Q5 remains an implementation gap;
    existing R4-landed and driver-seam-implemented statements preserved.
    No docs falsely mark the absent pin carrier, new gate or fixture re-mint
    complete. Remaining completion amendments await the stopped implementation.

## Verification

- Independent T9/T10 run: **33 tests, 173 assertions, 0 failures, 0 errors**.
  `/tmp/module-pins-r2c-independent.log`.
- Exploratory draft T1–T4 red run: 46 tests, 750 assertions, 20 failures,
  1 error. The draft also needs helper correction; it is not presented as a
  clean contract-red run. Removed from the active suite and saved in the
  draft patch above; no pre-existing substantive assertion was removed.
- Sandboxed focused run: **687 tests, 18720 assertions, 0 failures, 1 error**
  in the real-loopback socket test (sandbox bind restriction).
  `/tmp/module-pins-r2c-focused.log`.
- Unrestricted focused run: **687 tests, 18721 assertions, 0 failures,
  0 errors**, exit 0. Includes requested rows, custody, checkpoint, remote,
  holder.*, safepoint harness, frame/layout, Stage E, linker, manifest, mint,
  dao.jing namespaces, plus engine and layering tests.
  `/tmp/module-pins-r2c-focused-unrestricted.log`.
- Kondo on all six changed Clojure files: **0 errors, 0 warnings**.
  `/tmp/module-pins-r2c-kondo.log`.
- `git diff --check`: clean.
- Whole unrestricted JVM quick tier (`clojure -M:test -e :slow`):
  **4027 tests, 249915 assertions, 0 failures, 0 errors**, exit 0.
  `/tmp/module-pins-r2c-quick-unrestricted.log`.

No Node/Dart lane invoked. No commit or staging performed. Existing untracked
collab artifacts preserved. No src/yang or test/yang edits. Initial r2b partial
work preserved.

## Proposed commit split after the stop is resolved

1. Code: pin collector/validator/whole-tree lower check in `rows.cljc`.
   Tests plus re-minted fixtures and completed I-8 should accompany it to
   make one coherent passing carrier change.
2. Driver seam and its parked-installer regression remain separable, as in
   r2b. Existing admission-key cleanup and lower docstring cleanup remain.
3. Independent T9/T10 tests are separable test-only changes. They do not
   establish that pin checks are implemented. Documentation completion must
   describe actual landed code and fixture state.

BLOCKED
