Created-GMT: 2026-09-21 20:46:30 GMT
Created-Local: 2026-09-22 03:46:30 +07 (Indochina Time)
Coding-Agent: cmd (qwen/qwen3.8-max) | deepseek (see the launch line; this brief is shared by two independent reviewers)
Session-ID: pending (provider-generated) for cmd; caller-generated for deepseek (see stdout log name)
# Task: jing-cbor-j0 — independent review of the frozen DaoJing CBOR fixture corpus
Role: Adversarial Review (Storage & Encoding)
Implementers:
- Model: qwen/qwen3.8-max (Qwen family, via cmd) and deepseek-v4-pro (DeepSeek family) | Assigned: 2026-09-22 03:46:30 +07 | Status: active | Rationale: the corpus was authored by claude-opus-5 (Claude family); the owner authorized these families; reviewers must differ from the author's family

Read-only. Work ONLY in /Users/sto/workspace/worktree-jing-cbor (your launch
directory; branch jing-cbor, HEAD 0dc06197). All files below are inside it. Do NOT
edit or create any file and do not run tests. You may read files and run read-only
git commands. Produce the complete review now as your final response; do not wait
for approval and do not promise a verdict.

## What you are reviewing (all NEW, untracked)

Phase J0 of the DaoJing canonical-CBOR epic: a frozen fixture corpus that later
codec phases (J1 JVM/Node, J2 Dart, J3 cross-host) must reproduce byte for byte:
- test/resources/dao/jing/cbor-v1.json (359 cases: 203 canonical, 27 encode
  refusals, 129 decode refusals; 29 equivalence groups, 25 retained-distinction
  groups; case keys: id, kind, categories, input, hex, sha256, refusal,
  equivalence, distinct, plan, notes)
- test/resources/dao/jing/cbor-v1.README.md (provenance, DSL, ambiguities A1-A17)
- test/resources/dao/jing/cbor-v1.generate.py (independent stdlib-only Python
  deterministic-CBOR encoder, reader and profile checker; emits the JSON to stdout)
- test/dao/jing/cbor_fixtures.cljc and cbor_fixtures_test.cljc (loader and green
  codec-free self-tests)
The governing spec is docs/design/dao.jing.cbor.md (read "Encoding contract",
"Numeric identity", "Addressing and clean break", "Implementation sequence and
validation" IN FULL). The architect's J0 work package is
collab/1790011562103-ref-architect-work-package.findings.md (section J0 and its risks)
and collab/1790011825648-ref-architect-prior-art.findings.md. Pattern-only prior art
(the STREAM profile, which is NOT Jing's): src/cljc/dao/stream/cbor.cljc,
src/cljc/dao/stream/cbor/boring.cljc, test/dao/stream/cbor_test.cljc. The orchestrator
already verified: the generator reproduces the JSON byte for byte; the JVM self-tests
pass (14 tests, 2623 assertions); kondo and cljstyle are clean. Do not re-verify those.

## What to judge (be concrete; cite case ids and plan sections)

1. **Independence and immutability.** Does anything in the generator, the JSON or the
   loader depend on Boring, Jing, or code under test? Can any test or lane overwrite
   the fixtures? Is the regeneration policy in the README strict enough that a
   dependency upgrade cannot silently regenerate them?
2. **Byte correctness against the plan.** Hand-decode and check AT LEAST 30 cases
   spread over ALL categories, including every one of the four named frames
   (`dao.jing/list`, `dao.jing/keyword`, `dao.jing/symbol`, `dao.jing/float64`), the
   pathological identifiers, metadata (retention and reader-position stripping), sets
   and maps ordering (bytewise), integers (shortest form, boundaries, bignums), float64
   and the one canonical NaN, float32 widening, decimals and ratios, byte strings,
   and the stringref/shape/index absence cases. For each: the plan sentence, the
   expected bytes, agree or disagree. List every disagreement as a finding.
3. **Coverage against the plan.** Compare the plan's "Required test scenarios" and the
   architect's J0 coverage list against the corpus categories and case ids: what
   required scenario has NO case, or too thin a case (for example injectivity of the
   whole pathological-identifier class, mixed-type key ordering, sorted collections
   with metadata, surrogate rejection in metadata and identifiers)?
4. **The refusal cases.** Are the 16 refusal class names sensible and each decode
   refusal single-defect? Are any refusals wrong (a value the plan ACCEPTS is marked
   refused, or the reverse)?
5. **The ambiguities A1-A17** in the README: for each, say whether the resolution the
   corpus chose is the reading the plan supports, and give your recommended ruling
   where it is open (A4, A5, A6, A8, A9, A11, A14 especially). Say which are
   dangerous to freeze wrongly (a wrong frozen byte is expensive) and which are
   safe to leave for the architect.
6. **The self-tests and the loader.** Would the self-tests fail if the corpus were
   wrong in the ways they claim to catch (mutation thinking: change one hex digit,
   drop a group member, make two identities collide)? Statically review the Dart branch
   of the loader (`dart/is?`, `Uint8List.fromList`, the relative path) and the CLJS
   branch (`fs.readFileSync` relative path) for host traps; the Dart and Node lanes
   are being run by the orchestrator separately.

## Deliverable

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <cmd|deepseek>
Session-ID: <your session id if visible, else pending>
then a verdict: READY, READY WITH CHANGES, or NOT READY; findings as P1 (a frozen byte
or a required scenario is wrong or missing, or immutability can be broken), P2 (a
significant gap), P3 (minor), each with the case id or file and line, the plan
sentence, and the smallest fix; your rulings on the ambiguities; the list of cases you
hand-decoded with agree or disagree; and what you checked and found clean. Findings
only; edit no file.
