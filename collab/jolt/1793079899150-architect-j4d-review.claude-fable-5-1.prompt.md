Created-GMT: 2026-10-26 04:11:00 GMT
Created-Local: 2026-10-26 11:11:00 +07

# Review: the J4d implementation (local REPL persistence and authority integration)

You are the independent technical reviewer. Author: claude (session
8f45af4f) — same family as you, so this is the technical half of the
sign-off; a non-Claude co-sign (argon or deepseek) follows separately per
the commissioning condition. This is your fourth review of this lane
(the design review, J4a, J4b, now J4d); you have the full history.

Under review — branch `jolt-j4d` (6 commits on master 71fd56f8, pushed):
- 080618d4 native/jolt-file layout 3: jolt_file_open_private (O_WRONLY |
  O_CREAT | O_EXCL | O_CLOEXEC, 0600) and jolt_file_eexist; 24 exports,
  libc only
- f4ccd054 yin.repl opens file: stores and persists saved flags on jolt;
  the dht: refusal narrowed to composition time (moved from parse-arg to
  checked-spec) — with the implementer's evidence that the move matters
  (an-oversized-number 7 assertions failed under parse-time, pass at
  composition time)
- 9b0c04b2 every J4-owned REPL applicability entry retired by per-var
  evidence; the 18 J4/J7 CLI guards split by dependency (8 rejoined green,
  10 re-guarded J7 with the honest reason); the F6 label drift reconciled
- cebffc9f the J4d gates in bb probe:jolt-file (9 new rows)
- 2c00707f SEPARABLE: dao.stream.http-test excluded from the J2 manifest
  (the dao.star Mode 1 track added it without a listing — the fourth
  such break)
- f35df68a the portable load-key test binds the refusal text as a string
  (a Node-only fix found by the shiva test:cljs comparison)

The implementer's findings (read first, in full):
collab/1793027866046-engineer-j4d-local-persistence.claude-opus-5-5.findings.md
— includes the per-suite retirement table, the helper audit (§5), and the
J4 closure statement (§9).

Design authorities:
- Design §5 J4d row and §6 (the 42-entry appendix):
  collab/1792769713135-architect-j4-file-seams-design.claude-fable-5-1.findings.md
- Your J4b review's F4 (private = key files, not HEAD) and F6 (the label
  drift): collab/1792984961521-architect-j4b-review.claude-fable-5-1.findings.md
- The lane spec: docs/design/yin.repl-jolt-implementation.md (§5
  Files/Signing/Main rows, §10.1)

Review tasks:
1. THE NARROWED REFUSAL: the dht: refusal moved from parse-arg to
   checked-spec. Verify every flag parses and validates as on the other
   hosts before composition, and that only composing a dht: store answers
   the J7 message. Verify the implementer's evidence claim (the 7 failed
   assertions under parse-time).
2. THE KEY FILES: 0600 from creation (O_EXCL, never overwrite, EEXIST
   raises the existing refusal), strict load-key (absent/malformed/
   mismatched refuse NAMING THE PATH, never the seed), signing vectors
   green.
3. SAVED STATE + INDEX + FLAGS: the state round-trip, the refusal set,
   reset/no-state semantics, the index reopen — verified against
   state_test/index_test/store_test semantics.
4. THE 42-ENTRY RETIREMENT: every J4-owned manifest entry retired with
   per-var evidence; the 18 J4/J7 CLI guards split by dependency with run
   evidence (8 rejoined green, 10 re-guarded J7 with the honest reason);
   the F6 label drift reconciled per the J4b findings' table.
5. CUSTODY SHUTDOWN: the exit-path closes the custody journals before the
   host exits — passes on jolt; EOF-while-pending covered.
6. NO SHARED-CODE CHANGE: dao.jing.file's framing/recovery/make-put are
   untouched; the only shared-file edits are the :jolt require splice and
   the eight arms.
7. THE SEPARABLE http-test EXCLUSION: honest (the dao.star track's
   http-test loads dao.stream.http.jvm/http-kit and cannot load on jolt;
   the exclusion names owner dao.star) and its listing is the dao.star
   seat's to land.
8. THE LEDGER RULE: known-gaps changes follow the union/append-only rule
   (row 75 closed with mechanism and evidence; row 64 closed citing J4b's
   closure row, the F5 mixed-lock row unchanged; no rewritten file).

Write to:
collab/1793079899150-architect-j4d-review.claude-fable-5-1.findings.md
End with APPROVE / APPROVE WITH CHANGES / REQUEST CHANGES + per-finding
rows and the properties that pass. Write ONLY that findings file.
