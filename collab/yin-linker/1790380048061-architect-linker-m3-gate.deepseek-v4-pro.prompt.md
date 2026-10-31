Created-GMT: 2026-09-25 23:47:28 GMT
Created-Local: 2026-09-26 06:47:28 +0700
Coding-Agent: deepseek
Session-ID: resume-of-07f898c2-3785-4732-b095-cc4b1b6ce7e9

# Task: M3 gate (stepped core and link runtime), the uncommitted M3 diff

Role: Adversarial Code Reviewer and Security Auditor + Lead System Architect (combined commit gate)

Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-09-26 06:47:28 +0700 | Status: active | Rationale: owner directive "authorize deepseek for m3"; codex is down. You reviewed the M2 gate in this session. The M3 code was authored by claude-opus-5-5, so you are independent of it.

You are a HEADLESS plan-mode reviewer. Produce the COMPLETE review in your final
response now. Do not wait for approval, do not ask questions, do not end with a plan or
a promise of a verdict. Read-only: do not edit files. Cite file:line. Do not rerun
the test suites.

## Owner statements (verbatim quotes)

"use claude cli for m3"
"for 4, use opus"
"authorize deepseek for m3"
"dao.jing.remote.step is probably unnecessary or will be redundant because of there was dao.stream design specs that mechanically expose any dao.stream implementation via websocket or UDP."

## What is under review
The uncommitted M3 work in /Users/sto/workspace/datomworld-ucf-phase2 (branch ucf-phase2,
HEAD 19719d66 = the M2 commit you gated READY). Modified: src/cljc/yin/vm/linker.cljc,
test/yin/vm/linker_test.cljc, test/yin/vm/content_test.cljc. New (untracked, read it
directly): test/yin/vm/linker_step_test.cljc. Read the change with git -C
/Users/sto/workspace/datomworld-ucf-phase2 diff HEAD and the files directly.
Spec: docs/design/yin.vm.linker.md section 9 (the M3 paragraph and its test list),
sections 6 (linking is a stream exchange incl. 6.4 local-fetch traffic test), 4, 2, 10
(file box, must-not-change list), 11 (completion criteria). The implementer's report
(untrusted): /Users/sto/workspace/datomworld/collab/1790358000000-vm-engineer-linker-m3-stepped-core.claude-opus-5-5.report.md
The design rules in force: Rule R (yin/def is syntax, never a name; only yin/def is
reserved); fetch requires a contract and never assigns a stamp to external input; the M2
pipeline semantics (byte cap before hashing and decoding, :max-parts, identity-directed
match, the 5a dominance join) must be preserved exactly.

## Orchestrator evidence (independently run in the worktree, solo; do not rerun)
JVM 2,098 tests / 181,640 assertions / 0 failures; Node 2,011 / 48,483 / 0; Dart 1,973
passed (all tests passed); clj-kondo on the four changed clj/cljc files: 0 errors, 0
warnings, none new versus the M2 commit; cljstyle exit 0; git diff HEAD --check clean;
no leftover scratch files. Baseline at the M2 commit: JVM 2,089 / 181,424, Node 2,002 /
48,286, Dart 1,964. These match the implementer exactly.

## Orchestrator framing (my reading; challenge it)
Claimed: new link-state, request-link, step, abandon, verify in linker.cljc;
free-name-defect renamed discharge; fetch reimplemented as the blocking driver over a
runtime with no content handle of its own (the two fallbacks take a runtime);
:unsupported-format added to the refusal set; a new 9-test linker_step_test.cljc
(refusal matrix through step for all four formats, a :pending link answered one part per
step, function or handle requests refused :invalid-request, the section 6.4 traffic test
with equal get counts and no handle in the linker state, the DHT handle behind the served
boundary, abandon completing :lost exactly once, two links on one content pair, the
code-identity row); every M2 fetch test now goes over a ring-buffer content pair; the
JVM WebSocket test runs through the linker's own RPC client for all four formats.
KEY DESIGN CHOICE to scrutinize: the linker talks :jing/get-content directly over
dao.stream.rpc and does NOT use dao.jing.remote.step, because remote.step hashes and
decodes the payload itself, which would break M2's order (byte cap, then address check,
then decode). The owner also suspects remote.step is redundant given a planned
dao.stream.serve spec (not yet written into docs/design). Deviations reported: fetch
takes a fifth argument opts {:contract c} and format is a keyword; fetch calls step
itself (:drive serves the content pair and returns the state, because step hands out
completions only once); a corrupted RPC reply is now :address-mismatch where M2 returned
:absent (the M2 test was renamed and re-specified); a runtime serves one fetch;
free-name-defect became discharge; shared test helpers in linker_test became public; the
core was written before the step tests (not strict TDD); by-name requests answer :absent
(the name environment is M4). Not tested: the terminal-client path, the writer-full retry.
Also: a server that never answers makes fetch loop forever unless the drive gives up
(no built-in deadline). I have not read the diff.

## What to produce
1. M2 SEMANTICS PRESERVED: does the stepped core keep every M2 behavior (byte cap before
   hashing and decoding, :max-parts, identity-directed match, refusal ORDER, the 5a join,
   contract admission, no stamp assigned to external input, traffic and get counts)?
   Compare the M2 fetch logic to the M3 step logic and name any divergence.
2. STATE MACHINE: are link-state, request-link, step and abandon pure functions over
   explicit data with no handle, atom, callback or ambient state in the linker state?
   Is each completion handed out exactly once? Any way to lose or duplicate a
   completion, leak a request, mishandle abandon after completion, or misroute two links
   sharing a content pair? Is :drive's contract sound and documented?
3. THE remote.step BYPASS: is talking :jing/get-content over dao.stream.rpc directly
   sound? Does it lose any verification remote.step performed, or skip anything the spec
   requires of the M3 route? The spec allows either route; say whether the choice is right.
4. THE :address-mismatch vs :absent CHANGE and the re-specified M2 test: correct, or was
   an M2 assertion weakened? Compare removed and changed assertions in git diff HEAD.
5. TESTS: falsifiable, and do they cover the spec's M3 test list, including all four
   formats on all three hosts, the traffic test, the DHT, abandon and two-link cases?
   Anything the spec lists that is untested? Anything unfalsifiable?
6. The no-deadline behavior of fetch, the fifth-argument fetch API, the dropped or kept
   arities, and the M4-scope holdbacks: defects, or acceptable deferred work? Flag any
   that is an architectural decision for the owner.
7. Cross-host correctness (JVM, Node, Dart) and any doc or docstring inaccuracy.
8. Anything new. Distinguish defects from deferred work. Your overall verdict on whether
   the whole M3 diff is ready to commit as the M3 commit.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
(meaning: whether the M3 diff is ready to commit.)
