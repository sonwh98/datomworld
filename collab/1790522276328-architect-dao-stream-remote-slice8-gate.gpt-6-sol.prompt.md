Created-GMT: 2026-09-27 19:05:00 GMT
Created-Local: 2026-09-28 02:05:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Slice 8 Gate — the UCF facade (7.4.3/7.5.3 lift and lower)

Role: Lead System Architect (review + sign-off)

Slice 8 of the accepted dao.stream.remote implementation is in the
uncommitted working tree of /Users/sto/workspace/datomworld as TWO NEW
FILES (the facade module and its test -- find them: the implementer
chose the home and names; look for yin.vm.ucf.remote or similar, plus
the cursor-profile mapping). Implemented by a claude CLI session.
Implementer report (treat as untrusted):
collab/1790520054279-vm-engineer-dao-stream-remote-slice8.claude.findings.md

The contract: docs/design/yin.vm.universal-continuation-format.md
sections 7.4.3 (the exhaustive pending-wait variants) and 7.5.3 (lift
and lower through remote descriptors with the
:dao.stream.remote/v1 cursor profile); the plan's slice-8 row and its
acceptance proof (a string-backed stream migrates with a kept cursor
and resumes; forced eviction yields gap with the source's cursor);
the acceptance-matrix row in section 7.11.

Adversarial focus:
1. Variant completeness: EVERY 7.4.3 pending-wait variant has both
   lift and lower implemented and tested -- not just :stream-next.
   Name any missing variant with file:line.
2. Cursor-profile fidelity: :dao.stream.remote/v1 means cursors are
   plain data surviving the codec; the lift must map reflection
   cursors into UCF data and the lower must re-establish equivalent
   waits; kept-cursor migration and forced-eviction gap must behave
   exactly as the acceptance row says.
3. Layering: the facade bridges yin.vm parked state and dao.stream
   remote descriptors -- check the module home the implementer chose
   creates no circular require and drags no network dependency into
   yin.vm core.
4. The tree is mid-flight (concurrent slice-5 rewrite and slice-6
   fixes -- embed/main test failures are attributed to those, not
   this gate): review ONLY the two new files and their interactions
   with committed code (remote.cljc, semantic.cljc).
5. Hygiene on all added lines.

Orchestrator evidence: the implementer's runs -- its own namespace
11 tests / 54 assertions green on JVM, Node, and Dart; full-lane runs
during its pass showed failures ATTRIBUTED to the concurrent slice-5
rewrite (embed-test/main-test) and the slice-peer deletion; kondo
clean; ASCII <= 80 verified. Do not rerun suites.

Do not edit files. Cite file:line evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report findings as:
P0-P3 | file:line | evidence | concrete fix

End with exactly two lines:
Verdict: READY
Sign-off: GRANTED
or
Verdict: REQUEST CHANGES
Sign-off: DENIED
