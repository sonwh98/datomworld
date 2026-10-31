Created-GMT: 2026-10-03 19:44:30 GMT
Created-Local: 2026-10-04 02:44:30 +07 (+0700)
Coding-Agent: codex (gpt-6-astra, resume of thread 01a0f878-281b-7253-ac44-ff2402583d35)
Session-ID: 01a0f878-281b-7253-ac44-ff2402583d35

# Task: Adversarial review of the UCF version-1 amendment (M-next B), second architect

Role: Architect (adversarial reviewer of a design document)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-04 02:44 +07 | Status: active | Rationale: standing mob partner; independent adversarial second opinion on fable-5.1's amendment

Read-only. Do not edit, do not run suites.

## What to review

The orchestrator and fable-5.1 (Architect) published the UCF version-1 amendment for fenced custody, M-next B of docs/design/yin.vm.linker.dht.md section 14.3. It is UNCOMMITTED in
/Users/sto/workspace/datomworld-ucf-b (branch linker-ucf-b, master 42472621): run `git diff` there. Two files: docs/design/yin.vm.universal-continuation-format.md (+596, additions only) and
docs/design/yin.vm.linker.dht.md (+34/-27). Read fable's findings first (the reconciliation list of 16 items and Q1 to Q9):
/Users/sto/workspace/datomworld-ucf-b/collab/1791055853000-architect-ucf-v1-amendment-m-next-b.claude-fable-5-1.findings.md. The task it answers is quoted in the brief
/Users/sto/workspace/datomworld-ucf-b/collab/1791055853000-architect-ucf-v1-amendment-m-next-b.prompt.md (the section 14.3 item 2 text and its closing paragraph; the constraints "Lease vocabulary and
DaoStream outcome maps gain no keys", no privileged node, derive-don't-persist, and M-next A is landed and must not be reopened).

## Break it

Try to find, with section and line evidence:
1. Q1 (blocking): the carried-op-id versus occurrence-closure problem. fable's rule: tenure is checked on the envelope's lease binding, and the op id must belong to that occurrence or to an ancestor in the same
   chain. Alternative: key ids by a chain-root id. Is fable's rule sound? Construct the failure cases: a retained write retried after a successor is granted; two candidates racing for the successor; a source
   that was fenced, lost its lease and still holds a pending op; chain forks; restart of the authority; an op id reused with a different intent across the chain. Rule on it: accept, or choose the alternative, or
   propose a third. Does the choice change any other clause (the :stale versus :intent-conflict dispatch, the dedup record key, replay)?
2. Exactly-once and admission: with the check order in 7.7.8, can any interleaving commit an effect twice, commit under a stale epoch, or lose a committed effect? Include crash between commit and result record,
   retry after unknown acceptance, regrant of the same checkpoint, a successor carrying the next sequence, and epoch or sequence overflow (2^52-1) suspending admission and export.
3. Consistency: does the new text contradict, duplicate or silently bend any existing UCF clause (7.4.1 to 7.4.3, 7.5, 7.6, 7.7.1 to 7.7.7, 7.9 outcome algebra, 7.10, 7.11.1) or the DHT doc's 14.1 and 14.2.3/14.2.4?
   Is `:yin.k/admission` as a second closed dispatch key inside 7.9 consistent with how 7.9's existing algebra dispatches (statuses versus kinds)? Does anything add a key to the lease vocabulary or a DaoStream
   outcome map?
4. The version gate: does `:yin.k/version 1` plus refusal as `:yin.k/profile-mismatch` BEFORE restoration compose with the existing code-stamp `:yin.k/version` key (fable's Q4)? Can a version-0 reader mistake a
   version-1 body for fork-only data, or a version-1 reader silently upgrade a version-0 body (the closing paragraph of 14.3 forbids it)?
5. Testability: are the ten 7.11.1 clauses implementable and falsifiable on JVM, Node and Dart as stated, with each assigned to the right stage (C, D or E)? Are there clauses a stage cannot satisfy without a
   contract this document does not give?
6. The install/explicit-park shape in 7.4.3: is the "complete install" entry actually complete for a foreign resumer, and is the explanation of why the name-only sketch must never be exported correct?
7. fable's two "possible landed bugs", from reading src/cljc/yin/vm/ucf.cljc and src/cljc/yin/vm/ucf/ (read them): (a) `export-task` emits frames for a `:parked` body and then `resume-task` sets the wait set to
   empty; (b) `validate-body` does not check that an `:install` pending has its entry (only the lift does). Are they real? If so, are they M4/M-next A defects, or only gaps relative to the new amendment, and are
   they assigned to the right stage?
8. Q2 to Q9 in fable's list: for each, say whether the Architect pair can settle it now (give your ruling) or it genuinely needs an owner decision (state the question in one sentence and a recommendation).
   fable's recommendations: Q2 no automatic regrant after :intent-conflict; Q3 declare consumer enrollment as a fact on the arbitration medium, not a body field; Q4 keep both :yin.k/version keys; Q5
   exclusive only (a version-1 body is never a fork); Q6 a defective envelope yields a diagnostic, not an admission outcome; Q7 for M-next C; Q8 a terminal failure is a recorded result; Q9 occurrence id form open.
9. Stale record: yin.vm.ucf-revisions.md section 6 lists this amendment as "named but not landed". Say what must change there.

Be adversarial and specific; a vague "looks fine" is a failure. Give a recommendation, not a survey. Begin with a one-line verdict: ACCEPT / ACCEPT-WITH-CHANGES (list them, each as a concrete edit) / REJECT.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
