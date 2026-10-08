Created-GMT: 2026-10-07 16:40:00 GMT
Coding-Agent: claude (fable-5-1)
Role: Lead System Architect
Task: architect-python-c3-s7-signoff-r2
Responds to: collab/1791379500000-engineer-s7-signoff-pack.md
Supersedes: collab/1791377000000-architect-s7-signoff.gpt-6-astra.findings.md (WITHHELD)

# Python C3 Slice S7 — Architectural Sign-Off, round 2

**Lead System Architect Sign-Off: ACCEPTED.**

The three completion requirements that withheld round 1 (S7-1 integration
gate, S7-2 ruling-14 mutation ledger, S7-3 heap-test composition) are
closed on the tree reviewed. The production design was already judged
sound in round 1 and nothing in the pack or the tree changes that
judgement. S7 may land.

## Review basis

Tree: branch yang-python-c3-s7, HEAD 49960017efef87195920061dd3436063e97a4812
(unchanged since round 1). Working tree carries the 16 modified and 5
untracked non-collab paths the pack lists; `git status -- src test docs`
agrees exactly.

This round is an evidence review against the final tree. I did not re-run
the three lanes. I verified every S7-1 claim directly against
`target/s7-test-all.log` (mtime 23:18:43 +07, later than every production
and test source mtime, so it postdates the last mutation revert), read the
gate test and heap composition sources, and checked the restoration claims
with git. The S7-2 ledger is accepted on the engineer's report plus the
restoration checks below; its individual red runs are not independently
reproducible from a log, which the design accepts for mutation evidence.

## S7-1 — Integration gate: CLOSED

Verified in `target/s7-test-all.log`:

| Lane | Evidence | Line |
| --- | --- | --- |
| JVM | `Ran 3740 tests containing 241544 assertions.` / `0 failures, 0 errors.` | 615–616 |
| Node | `Ran 3490 tests containing 104962 assertions.` / `0 failures, 0 errors.` | 1184–1185 |
| Dart | `+3445: All tests passed!` | 5153 |

- `grep -c "SKIP slow"` over the log is 0. The round-1 blocker was
  guarded bodies printing SKIP on Node and Dart; none did in this run.
- `Testing yang.python.antlr.c3-gate-test` appears at line 224 (JVM) and
  823 (Node). On Dart, `c3-gate-test/c3-gate-test` progress ticks appear
  from line 1840 in shard 1. The gate test is `^:slow` under `slow/guard`
  (`c3_gate_test.cljc:106-107`), so its presence as an executed test with
  zero SKIP lines means the bodies ran.
- `c3-gate-parser-test` (JVM naive and hooked source execution) ran at
  line 222. `int-heap-test` ran at lines 242 (JVM) and 833 (Node).
- Four-VM coverage is structural, not asserted by the engineer:
  `run-program` iterates the profile's runner map (`c3_gate_test.cljc:69-81`),
  and `runners-under` builds `:ast-walker`, `:semantic`, `:stack`,
  `:register` (`int_ops_test.cljc:43-58`). `gate` asserts exact
  `{:py/out stdout, :py/exception nil}` per program per VM.
- Fixtures are unchanged since round 1: `c3-corpus-v1.txt` and
  `int-conv-v1.txt` mtimes are 19:45:44 +07 (12:45 GMT), before the
  round-1 review (12:57 GMT) that independently regenerated and hashed
  them. The hashes in that review therefore still describe these files.

The pack's three corrections to its brief (fast `bb test:cljd` is not
Dart corpus evidence; the Node focused run is superseded by the full
lane; 7,831 → 7,923 changed-suite assertions after the heap migration)
are the right corrections and show the engineer read the evidence rather
than copying the brief.

## S7-2 — Ruling-14 mutation ledger: CLOSED

The ledger covers all seven mutations the design names (a–g), each with
file, edit, detecting host, detecting test or program, and the observed
failure text or first differing stdout line. Restoration is verified:

- `git diff --stat -- src/cljc/yin/vm/engine.cljc src/cljc/yin/vm/integer.cljc src/cljc/dao/jing/cbor.cljc`
  is empty on the reviewed tree (these three files are not in the S7
  diff at all, so an unreverted mutation would show as a modification).
- The `py/key` mutation (c) lives in `prelude.cljc`, which is an S7 file.
  Its restoration is covered by the final-tree green run: the `keys`
  program asserts the exact row `9007199254740993: 'odd', 9007199254740992.0: 'even'`
  that the mutation collapses, and the gate passed on all three hosts
  after the revert.

Three ledger observations matter architecturally and are accepted as
recorded, not as defects:

1. **Mutation (a) is Node-only.** The JVM fast path admits only operands
   within ±(2^53 − 1), so a long cannot wrap there; the mutation is
   detectable only where the native carrier is a double. This is the
   expected shape of ruling 14 on a host-canonical carrier design and is
   evidence the promotion boundary sits where the design placed it.
2. **Mutation (b) splits by host.** Node detects skipped demotion through
   the demotion program (lowering refuses a raw `bigint`); the JVM
   detects it only through `yin.vm.integer-test` and the int-literal
   carrier test because a small `BigInteger` is `=` to its long and
   renders identically. The detectors are in-tree and ran green on the
   final tree, so the design's intent (every host has a detector) holds.
3. **Mutation (e) detector correction.** The engineer found that
   `int-contract-test/canonical-widths-at-the-boundaries` compares frozen
   fixture columns and does not exercise the codec, so it is not a tag-3
   detector as design §2.1 claimed. The real detectors (S6 T4
   `scalar-round-trip-test`, the int-contract recompute and round-trip
   tests, four-VM guest values) went red. This is a documentation
   correction, carried below as a non-blocking follow-up.

Mutation (g) re-executes S6's scalar-gate mutation on this tree because
the S6 report is not reachable from the worktree; `big-integer-images-test`
and `cell-refusal-test` are the S6-designed detectors and went red while
`c3-gate-test` stayed green. Accepted.

## S7-3 — Heap-test composition: CLOSED

`test/yang/python/antlr/int_heap_test.cljc:34-39` now composes
`(data/register-data-module {::data/max-items 1048576})` before the
integer registrar and ends with `prelude/admit`. Every in-tree Python
composition that calls `register-integer-module` under `test/yang/`
(ten namespaces) also calls `admit`; the only registrars without `admit`
are `yin.vm.integer-test` and the prelude itself, which are generic
module tests, not Python compositions. The design's "every Python
composition calls it" statement is now true for the tree.

## Non-blocking follow-ups (do not gate landing)

- **Design §2.1 detector wording.** Replace the `canonical-widths-at-the-boundaries`
  tag-3 detector with S6 T4 `scalar-round-trip-test` and the
  `int-contract-test` recompute/round-trip tests, in
  `collab/1791270000000-architect-python-c3-s6-s7-design.claude-fable-5-1.findings.md:63`
  or, better, in the §8.5.4 ledger paragraph of `docs/design/yang.antlr.md`
  when the commit touches it.
- **"Mutation evidence is in the S7 engineer's report."** `yang.antlr.md`
  around line 2430 should point at the sign-off pack
  (`1791379500000-engineer-s7-signoff-pack.md`), since the earlier S7
  report remains marked IN PROGRESS. A one-line edit in the landing
  commit is sufficient.
- **"S7 has landed" at `yang.antlr.md:~1978`** becomes true at commit
  time; acceptable as the commit's own wording.

## Landing decision

**ACCEPTED.** S7-1, S7-2 and S7-3 are satisfactorily closed on the
reviewed tree. Proceed to commit under the project's convention, fold the
two documentation one-liners above into the landing commit if convenient,
and fast-forward master.

Only this findings artifact was written by the reviewer; source, tests,
fixtures and logs were not changed.
