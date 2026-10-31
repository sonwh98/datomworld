Created-GMT: 2026-09-30 19:02:20 GMT
Created-Local: 2026-10-01 02:02:20 +07 (+0700)
Coding-Agent: claude
Session-ID: 2cce7933-39ab-4cc2-93e3-b194fc58b9de
# Task: Pure data primitives host module (collections + strings) for language preludes

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-01 02:02:20 +07 (+0700) | Status: active | Rationale: bounded host-module addition; Architect mutable-objects ruling

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-data-prims (branch vm-data-primitives). Base is a LOCAL snapshot
commit 77ad1697 of the not-yet-committed D4 change (host-typed effects, :callable-effects, register-host-module merge).
Build on D4's registration API; do not modify D4 code. A parallel cell-slice-1 engineer works in another worktree on
engine.cljc/module.cljc: keep your change to NEW files plus the minimum registration wiring, so the two merge cleanly.
Do not touch other worktrees. Do not stage or commit.

OWNER (verbatim): "dispatch the data primitives module in parallel too". Prior owner decision (verbatim): "accept all
recommendations", which included: pure data primitives live in a :pure host module named by the language runtime
profile, NOT in the standard vm/primitives registry.
Governing ruling (copy in this worktree's collab/): 1790778866412-architect-mutable-objects.claude-fable-5-1.findings.md
(Q5, Q6, findings table row 1: vm.cljc:351-391 lacks count/dissoc/pop/subvec/contains?/strings).

Existing vm/primitives: + - * / = == != < > <= >= not nil? empty? first rest conj assoc get vec bytes->str require.

Build:
1. A new :pure host module registered via module/register-host-module (like the stream module, but every export's
   profile is :pure with declared effects #{}). Module name: `data` unless it collides with an existing module name
   (report what you chose). NOT added to vm/primitives; a composition installs it explicitly (a register-data-module fn).
2. Exports (minimum; add others only if a list/dict/set/str prelude cannot be written without them, and justify each):
   collections: count, nth, contains?, dissoc, disj, peek, pop, subvec, hash-set, into (vector/set targets only).
   strings: str-concat (n-ary), str-length, substring, str-index-of, str-split, str-join, char-at, str->code-points,
   code-points->str, str-compare.
3. Portability (required): identical results on CLJ, CLJS and CLJD. Strings are CODE-POINT indexed (Python semantics),
   not UTF-16: str-length of "a😀" is 2 on every host; substring/char-at/index-of use code-point offsets. Test with
   non-BMP input.
4. No host-map iteration export (no keys, vals, seq over maps): host map order differs by host; ordered dicts are prelude
   code (index map + order vector). Iteration over vectors/strings is fine.
5. Errors: out-of-range/wrong-type inputs fail with a qualified ex-info identical across hosts (no host exception text).
6. Every export works on all four VMs through the module path, and the D4 profile check passes them as :pure.

Acceptance tests (each must fail when its part is reverted — prove by temporary mutation, then restore):
- each export's behaviour incl. edge cases (empty, out of range, nil where legal);
- non-BMP code-point results identical on CLJ and CLJS (CLJD via the orchestrator's lane);
- the module is absent from vm/primitives, present only after register-data-module;
- a program using data/* runs on all four VMs;
- a :pure data export returning an effect-shaped map returns it as data (D4 regression).

Verify — run EVERY command in the FOREGROUND (never background): clj -M:kondo --lint on each changed file (separate
arguments); cljstyle check on each changed file (say if blocked); focused JVM; full clj -M:test; bb test:cljs (confirm
"Testing <ns>"). NOT bb test:cljd.

Write the report to /Users/sto/workspace/datomworld-data-prims/collab/1790794940418-vm-engineer-data-primitives.claude-opus-5-5.report.md
and give it as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 2cce7933-39ab-4cc2-93e3-b194fc58b9de
Report changed files, the export list with one-line semantics each, exact test outcomes, the mutation proof, unresolved
concerns, incomplete work.

## Round 2 (orchestrator) — gate REQUEST CHANGES (glm-5.3) + one CLJD failure
Gate findings (copy in this worktree's collab/): 1790796340212-reviewer-data-primitives-gate.glm-5.3.findings.md.
Orchestrator lanes on your code (rebased onto master 9a69e58f, which contains D4): JVM 2544/186381/0, Node 2459/52663/0,
CLJD +2413 -1 FAILED:
  yin.vm.data-test/non-bmp-code-points-test
  Expected: (= [55296 97] (call (quote str->code-points) "?a"))   Actual: [63 97]
  Cause (orchestrator reading, verify): the lone-surrogate string LITERAL "\uD800a" is emitted into the generated Dart
  source as "?a" (a lone surrogate cannot be UTF-8 encoded), so the test input is wrong on CLJD, not necessarily the
  implementation. Build lone-surrogate inputs at run time in the test (e.g. from code units via a host helper or
  code-points->str), never as a source literal; confirm the implementation itself handles lone surrogates on CLJD.
Fix:
1. P2: tests pinning the index-coercion guard (data.cljc ~68-83): NaN, +Inf, -Inf and 2^53 indices refused
   (:wrong-type :integer) using portable constructors ((/ 0.0 0.0), (/ 1.0 0.0), (/ -1.0 0.0), 9007199254740992);
   mutation-prove them.
2. The CLJD lone-surrogate test input as above.
3. P3 (small): one docstring line on per-call O(n) decode / O(n*m) search steering loops to str->code-points + nth;
   make pop's and nth's empty-vector refusal ::index sentinel consistent (state which value).
Run EVERY check in the FOREGROUND: kondo + cljstyle check per file, focused JVM, full clj -M:test, bb test:cljs.
Append a "Round 2" section to the report and give the full report as your final response (same header).
