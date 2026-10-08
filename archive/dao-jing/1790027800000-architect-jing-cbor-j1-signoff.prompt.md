Created-GMT: 2026-09-21 22:40:00 GMT
Created-Local: 2026-09-22 05:40:00 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d (resumed: your J0 sign-off turn)
# Task: jing-cbor-j1-signoff: rule on the J1 deviations and sign off the JVM/Node codec
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 05:40:00 +07 | Status: active | Rationale: you own the J1 sign-off (Boring behavior and no existing-code change); one batched GPT turn (weekly budget about 19 percent, resets 2026-09-23 20:50 +07)

Work in /Users/sto/workspace/worktree-jing-cbor (launch directory; branch jing-cbor;
J0 is committed as f5f71e95; the J1 files are UNCOMMITTED in the working tree).
READ-ONLY: edit nothing. BE ECONOMICAL. Give the complete answer now.

## Read

- collab/1790026500000-ref-implementer-j1-report.md (the implementer's J1 report)
- collab/1790027400000-ref-implementer-j1-fix-report.md (the fix round; the depth limit,
  decimal window and symbol-collision correction)
- collab/1790026900000-ref-j1-review-qwen.findings.md and
  collab/1790026900000-ref-j1-review-deepseek.findings.md (both READY WITH CHANGES,
  no P1; both recommend ratifying the two deviations)
- test/resources/dao/jing/cbor-v1.errata.md (PENDING your ratification)
- src/cljc/dao/jing/cbor.cljc, cbor/boring.cljc, test/dao/jing/cbor_test.cljc only as
  needed. The fix round is applied (depth limit, decimal window, JS safety, tests);
  all lanes green (JVM 32 focused tests / 4446 assertions, full JVM, CLJS 1607 tests,
  CLJD unchanged, kondo and cljstyle clean).

## Rule (ADOPT, MODIFY or REJECT, one line of reason each)

1. **Decode does not go through Boring 0.1.30.** A Jing-owned structural reader
   validates shapes and canonicality first; every decoded value is re-encoded through
   Boring and must reproduce the input bytes; Boring is the only byte WRITER. Both
   reviewers recommend ratifying it (Boring's decoder loses ratio kind for 3/1,
   accepts negative denominators, maps stringrefs/dates/UUIDs and with-meta itself,
   drops a trailing index frame). Your work package expected a callback/sentinel path;
   confirm this is the correct reading of "validate shapes before any lossy host
   conversion" and that no plan clause is violated.
2. **The `host-collapse` refusal class** (a 17th class, outside the frozen 16, for
   CLJS map keys and set elements whose joined keyword or symbol name collides:
   `coll/map-both-slash-keywords` cannot be decoded on Node; symbols collide too).
   Options: ratify a new class as a documented host-capability refusal (both reviewers
   and the orchestrator recommend), map it to an existing class, or require Node to
   support the value some other way. State the rule and where it is recorded (the
   frozen README is not edited; the errata file is additive). Does it contradict "do not
   let the accepting host determine this boundary", and does J3's cross-host
   acceptance parity need an explicit statement (for example: identical outcomes
   except this named class on hosts whose identifier equality is joined-name-based)?
3. **Numeric window and depth policy:** ratify or change the decimal exponent window
   [-(2^31-1), 2^31] (the JVM BigDecimal scale range, applied on both hosts) and
   `max-depth` 128 (CBOR item nesting; a list frame costs three levels; class
   `:malformed-cbor` on decode, `:unsupported-value` on encode). J2 (Dart) must mirror
   both: say whether they belong in the design doc's Encoding contract at step 3.
4. **The README N/A table errata** (E1 in the errata file): confirm it as the
   authoritative correction.
5. **Boring behavior sign-off:** the option lock (canonical profile, stringref and
   shapes off, ordinary encoder), the outer with-meta frame built explicitly where
   Boring cannot emit it, and "no existing entry point changed". Anything you would
   require before J1 is committed?
6. **J2 readiness:** any constraint the Dart codec (cbor 6.5.1) must honour that J1
   revealed (identifier equality on Dart, native float handling, decoded-float vs
   integer, hash values not crossing hosts).

## Deliver

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d
then: the sign-off line (J1 SIGNED OFF, or NOT SIGNED with the required changes), a
one-line ruling on each numbered item, the exact wording you want at the top of the
errata file to mark it ratified, and the constraints for J2. Nothing needs owner
action unless you say so.
