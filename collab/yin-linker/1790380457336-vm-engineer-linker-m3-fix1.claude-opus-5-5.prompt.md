Created-GMT: 2026-09-25 23:54:17 GMT
Created-Local: 2026-09-26 06:54:17 +0700
Coding-Agent: claude
Session-ID: resume-of-a680556f-b767-4211-bafa-5e67257b844d

# Task: M3 fix round 1 (the small items from the DeepSeek gate)

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-26 06:54:17 +0700 | Status: active | Rationale: owner directive "fix small items then commit m3"; resumes your M3 implementation session

Repository: /Users/sto/workspace/datomworld-ucf-phase2 (branch ucf-phase2, HEAD 19719d66;
your uncommitted M3 work is in the tree). Collab files:
/Users/sto/workspace/datomworld/collab/. Same constraints as before: TDD, mise, kondo via
mise exec -- clojure -M:kondo, cljstyle, ASCII and <= 80 columns on every line you add or
edit, no em dashes, the cross-host traps, no commit, no stage, no reset, no stash, no merge.

## Owner statements (verbatim quotes)

"fix small items then commit m3"
"authorize deepseek for m3"

## The gate result: READY, GRANTED (DeepSeek); these are the small items to fix first
Read /Users/sto/workspace/datomworld/collab/1790380048061-architect-linker-m3-gate.deepseek-v4-pro.findings.md.
Fix exactly these, and nothing else:
1. P2 docs/design/yin.vm.linker.md section 6.4: the sentence says the drive "steps the
   linker's client side and whatever serves the content pair", but the code has fetch step
   the client and the :drive serve only ((fn [state] state')). Reconcile the sentence with
   the code (the code's own docstring is accurate, and step hands completions out once,
   so a drive that stepped the linker would lose one). Check section 6.3 and any other
   sentence that repeats the wrong description.
2. Base64 text cap before decoding (found by a separate review): answered-bytes
   (linker.cljc, near line 129 in your version) base64-decodes the whole reply text
   BEFORE the :max-bytes cap sees the decoded length, so an oversize reply is materialized
   in memory before it is refused. Apply the cap to the Base64 text length (a decoded size
   of at most ceil(3 * n / 4)) BEFORE decoding, keep the existing decoded-length cap, and
   refuse in the same way and order as the existing byte cap (:parts-limit with
   :bound :max-bytes and the address), with no decoding of the oversize text. Add a
   failing-first test that an oversize Base64 reply is refused as :parts-limit without being
   decoded (counting or throwing decoder is fine), and that a reply at the limit still
   decodes and checks as before. State how this interacts with the strict-Base64 fail-closed
   :absent path so the refusal order stays deterministic.
3. P3 request-defect (linker.cljc near line 1177): :missing :contract is tested before
   :unsupported-format, so a contract-less unknown format reads :invalid-request. Reorder
   so :unsupported-format precedes the contract checks, and add or adjust a test that pins
   the order. Keep every other admission check and its order exactly as M2 had it.
4. P3 two untested fail-closed paths: add one focused test each for (a) the terminal
   (rpc client at a terminal state) path through issue-request and (b) the writer-full
   retry path. If ring buffers cannot produce a full writer, use a small stub writer handle
   in the test that answers the dao.stream full outcome; if a path truly cannot be
   exercised without changing production code, STOP on that one and report why instead of
   changing production code.
DO NOT add a fetch deadline or an abort hook: the owner has not decided that (liveness
staying entirely the drive's is the current, spec-compliant stance).

## Verify
Rerun all three lanes sequentially and solo under mise (JVM, Node, Dart with rm -rf
test/cljd-out first), kondo on every changed file, cljstyle, and git diff HEAD --check.
Report exact counts against your last figures (JVM 2,098 / 181,640; Node 2,011 / 48,483;
Dart 1,973). List anything you left, and any scratch files you removed.

Write your report to
/Users/sto/workspace/datomworld/collab/1790380457336-vm-engineer-linker-m3-fix1.claude-opus-5-5.report.md
(same header fields) and return it as your final response.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
