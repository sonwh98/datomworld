Created-GMT: 2026-11-18 18:54:43 GMT
Created-Local: 2026-11-19 01:54:43 ICT (+07)
Session-ID: 01a1ef93-bee4-7c00-9752-4e034b3efcd1 (RESUME of your review thread)
# Task: confirm P1a-r2 witness repairs + the P1a-MT experiment verdict

Role: Review (continuation of your P1a review; same read-only sandbox)

You reviewed P1a (commit dc6176dc) earlier in this conversation and returned
VERDICT-AMENDED with findings F1-F6 and a five-step experiment spec. Two repair
rounds have since completed. Confirm them.

## Inputs (all read-only for you)

1. P1a-r2 (your F1-F3, F5 repairs): commit 6507ec1e on gambit-ffi-p1, worktree
   /Users/sto/workspace/jolt-ffi-p1. Repairs: W1 capture-before-allocation +
   intervening-clobber controls (shape F defeated, shape G asserts the clobber is
   reported; 5901/5901); W2 new qualify mode (rendezvous before entry, B observed
   executing, release handshake called from Scheme only after real ##gc mutating
   live rooted objects, state-derived verdicts; provisioned build derives FAIL:
   rounds-during-block=0, handshake-during-block=0); W3 (int int) signatures with
   make verify-w3-c asserting ___NARGS 2/0 in generated C, owner-thread control
   (results/w3-control.out) before every phase; Makefile hygiene (artifacts under
   build/, tracked results only via record-tracked after verified PASS, assertive
   make verify). Engineer claims: with valid entries, SERIALIZED foreign entry
   (owner parked in C) SURVIVES 3/3 with correct values (one drove 3 GC rounds
   from the foreign pthread); CONCURRENT entry crashes 3/3.
   Findings: /Users/sto/workspace/datomworld/collab/jolt/1795033000000-engineer-p1a-r2-witness-repair.glm-5.3-flash.findings.md
   Note: your rerun artifacts under p1a/witnesses/build/results/ were lost to a
   `make clean` in the r2 agent's first draft (disclosed in its findings; clean is
   now restricted). Your Q1 evidence survives in your own findings file; judge r2
   from the committed sources + fresh tracked results.
2. P1a-MT (your five-step experiment spec, executed): commit 1b9b6ed0 on
   gambit-ffi-mt, worktree /Users/sto/workspace/jolt-ffi-mt. It did a REAL SMP
   bootstrap (stock _thread.c is non-SMP; --enable-smp alone gives
   multiple-threaded-VMS without SMP scheduling; they bootstrapped from a git
   archive copy, regenerated all 10 runtime modules with the SMP prelude, and
   verified SMP live: two threads on processors 0/1, TLS pstate,
   smp-feature=ENABLED). Results: blocked-GC FAIL (A blocked in ordinary c-lambda
   on processor 1, B provably ran on processor 0 during the block, B's (##gc)
   waited the whole block, collection completed 183us after C's deadline return —
   the all-processor barrier held; no release/claim bridge exists in stock
   source); foreign-pthread: owner-thread control PASS, naive serialized and
   concurrent entry SIGSEGV (NULL TLS), and the best-effort undocumented attach
   bridge (___setup_pstate + ___tls_ptr bind, all six ingredients cited) executes
   Scheme and returns correct values but ANY collection from the attached pstate
   deadlocks the whole VM (watchdog SIGKILL) and teardown corrupts (SIGSEGV).
   Side findings: default parallelism (4 procs) fatally overflows the heap at
   startup; heap growth under -:p2 SIGSEGVs the compiler during multi-module
   gsc -exe. Linux leg PENDING. Their MT-VERDICT: MT build fails; P2/P3 remain
   NO-GO on multithreaded 4.9.8.
   Findings: /Users/sto/workspace/datomworld/collab/jolt/1795034000000-engineer-p1a-mt-experiment.glm-5.3-flash.findings.md

## Questions

C1 Do the r2 repairs actually implement your F1/F2/F3 prescriptions (verify the
sources and re-run what you need from p1a/witnesses; tracked results are in
p1a/witnesses/results/)? Confirm or flag per finding.
C2 Does the r2 serialized-entry-survives result change YOUR Q3/Q4 analysis (it
was done on the provisioned single-threaded build; entry with owner parked
survives, concurrent with live runtime crashes)? Does this narrow or alter the
refusal boundary you would require in the adapter?
C3 Does the MT experiment satisfy your spec's five steps and standard of proof?
Is its MT-VERDICT correct given the evidence (especially: B provably on another
processor during the block; the attach bridge's GC deadlock + teardown corruption)?
C4 Final verdict for the track, per the astra plan's criteria verbatim: is the
standing ruling now "PARTIAL-GO: bounded P1b (memory/loader/ordinary calls/errno/
qualified-callable-modes) with blocking + foreign-thread rows in honest refusal,
P2/P3 Chez-only unless an upgrade/runtime-patch path is separately chosen"?
State any remaining unqualified claims you would NOT let P1b assert.
C5 Anything still missing before a P1b brief can be written (the inventory lane's
Q1-Q6 and your F6 trampoline constraint are already in scope for it).

## Deliverable

Return your complete confirmation as your final agent message (read-only
sandbox), beginning with Completed-GMT/Local/Coding-Agent/Session-ID, per-question
answers with file:line evidence, per-finding CONFIRMED/NOT-CONFIRMED for F1-F3,
and a closing line: CONFIRMED-VERDICT or AMENDED-VERDICT with its one-line text.
The orchestrator promotes it to
/Users/sto/workspace/datomworld/collab/jolt/1795040000000-reviewer-p1a-confirm-codex.gpt-6.1-sol.findings.md
