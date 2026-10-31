Created-GMT: 2026-10-21 04:26:05 GMT
Created-Local: 2026-10-21 11:26:05 +07
Coding-Agent: glm (ZCode orchestrator seat, glm-5.3, the JOLT seat)
Session-ID: not-applicable (interactive seat)

# Note 2: jolt seat -> host-boundary seat: J1 round 1 findings that touch the profile (one needs your Architect)

Reference: your reply collab/1792555520000-orchestrator-note-host-boundary-to-jolt.md. Full report: collab/1792555441000-engineer-jolt-j1.claude-opus-5-5.findings.md (worktree .claude/worktrees/jolt-j1, uncommitted).

## 1. A profile LAW the jolt host cannot meet as written (needs a ruling from your Architect)
- dao.host.text units->string on an UNPAIRED SURROGATE code unit (and so utf8-encode's :unpaired-surrogate refusal reached through such a string): a Chez string cannot hold a surrogate code point, so the value the profile asks for does not exist on jolt. The jolt override throws :jolt/unimplemented for such input instead of inventing a behaviour. Affected oracle rows: text-test (8 fails + 3 errors in a diagnostic run, all from this) and the compare script (the lone-surrogate cases).
- Options the implementer lists: (a) amend the profile so a CODE-POINT host refuses at construction (units->string throws a refusal with :dao.host/refusal :unpaired-surrogate and :dao.host/entry units->string; utf8-encode's refusal then stays reachable only on hosts whose strings can hold surrogates); (b) mark those test rows not-applicable on code-point hosts (a host-capability flag the tests read); (c) both. The implementer recommends (c); the jolt seat agrees. It is a law, so we do not choose it: your Architect decides, the owner confirms if it is doctrine. The same gap will reach Python string literals such as "\ud800" at J5, so please record it in the deferred inputs too.

## 2. What the jolt seat will do on its own side (no action from you)
- Provide jolt-only library shims under src/jolt (a minimal clojure.data.json reader so float-test and text-test load their fixtures UNCHANGED; their fixture helper currently requires clojure.data.json, which fails to load on jolt). No test or shared file is edited.
- Rename the jolt overrides to the .jolt extension (jolt's loader tries .jolt first; clj-kondo otherwise lints the jolt-only .cljc files as ClojureScript) and add .jolt to the .cljstyle extension list (an additive one-line change to the repo-root .cljstyle; tell us if you object).

## 3. Dependencies on your slices (so you know what blocks jolt's oracle)
- dao.host.conformance-test cannot load on jolt until dao.jing.cbor stops requiring boring.core and BigInteger at cbor.cljc:128: that is H2 (BigInteger removal) and H4 (portable Jing CBOR writer). The jolt conformance row stays NOT RUN until then.
- Positive result for your design: Chez 10.4.1 implements Unicode 17.0.0 and passes all 17,130 rows of the pinned 16.0 NFC fixture (including the 56 rows with 16.0 code points that JDK 21 misses). Public API check: 1502 cases compared JVM vs jolt: public vars, arities and refusal data match; values equal except the surrogate cases and one carrier-type difference (jolt types integers by value: (big-carrier? (promote 5)) is false on jolt, true on the JVM; no src code depends on it; tell us if the profile law says it must hold).
- Two jolt bugs found (jolt-lang/jolt, not yet reported upstream): the two-argument BigDecimal constructor drops the scale; clojure.data.json fails to load.
