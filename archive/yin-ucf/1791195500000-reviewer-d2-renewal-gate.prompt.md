Created-GMT: 2026-10-05 09:46:00 GMT
Created-Local: 2026-10-05 16:46:00 +0700
Coding-Agent: glm (glm-5.3, plan review)

# Task: gate review, M-next D2 — the front's :yin.k/renewal request (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the uncommitted work in /Users/sto/workspace/datomworld-d2 (branch
ucf-d2-renewal, based on master 14d9f750). The engineer's report:
/Users/sto/workspace/datomworld-d2/collab/1791193500000-compiler-engineer
-ucf-d2-renewal.findings.md. Read it first, then `git -C
/Users/sto/workspace/datomworld-d2 diff` (two files: authority/front.cljc
and its test).

The contract (D plan r3, section 1.5 item 1 and the D2 test contract;
see /Users/sto/workspace/datomworld-d2/collab/1791194000000-architect
-m-next-d-plan-r3.claude-fable-5-1.findings.md): the front gains one
request, `{:yin.k/request :yin.k/renewal :yin.k/request-id r
:dao.lease/lease l}`, carried to the holder's lease-fact stream exactly
as a proposal and a release are carried. Tests: carried and answered
`:carried`; a poisoned authority answers `:suspended`; an unresolved
author yields a `:wrong-author` diagnostic; the judge counts the carried
renewal.

## What to attack

1. The carriage path mirrors the proposal/release path exactly: the
   same resolver attribution, the same diagnostic family, the same
   reply shape, no new keys on outcome maps. Check the diff line by
   line against how `:yin.k/proposal` and `:yin.k/release` are handled.
2. The judge actually sees the carried renewal: `lease/renewal`'s fact
   lands on the lease medium and the judge's step applies it. The
   engineer flagged their own concern: `:evidence :known` is already
   true right after the grant, so their judge assertion may be
   vacuous. Rule whether the test as written still pins "the judge
   counts the carried renewal" (the carried-fact assertion may carry
   the weight), or whether a stricter assertion is required before
   landing.
3. The closed request set and the malformed-request diagnostic list
   include the new kind; nothing else changed (`git diff` scope).
4. The version-0 wire, the six existing request kinds, and the
   DaoStream outcome maps are untouched.
5. Portability of the new tests (.cljc, :cljd-first conditionals, no
   host-number traps) and style (ASCII, <= 80 columns).

Verdict first: READY or NOT READY (with what must change), then numbered
findings with file:line evidence. Read-only: edit nothing, run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
