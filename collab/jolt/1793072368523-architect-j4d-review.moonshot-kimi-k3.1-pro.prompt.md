Created-GMT: 2026-10-27 02:46:00 GMT
Created-Local: 2026-10-27 09:46:00 +07

# Review: the J4d implementation (local REPL persistence and authority integration)

You are the independent technical reviewer. Author: claude (session
8f45af4f) — a different family from you (Moonshot Kimi), per the
reviewer-independence rule. You have not seen this lane before; the
evidence base below is self-contained, and you are encouraged to read
beyond it where a claim needs checking.

Under review — branch `jolt-j4d` (6 commits on master 71fd56f8, pushed):
- 080618d4 native/jolt-file layout 3: jolt_file_open_private (O_WRONLY |
  O_CREAT | O_EXCL | O_CLOEXEC, 0600) and jolt_file_eexist; 24 exports,
  libc only
- f4ccd054 yin.repl opens file: stores and persists saved flags on jolt;
  the dht: refusal narrowed to composition time (moved from parse-arg to
  checked-spec)
- 9b0c04b2 every J4-owned REPL applicability entry retired by per-var
  evidence; the 18 J4/J7 CLI guards split by dependency (8 rejoined, 10
  re-guarded J7); the J3/J4 label drift reconciled
- cebffc9f the J4d gates in bb probe:jolt-file (9 new rows)
- 2c00707f SEPARABLE: dao.stream.http-test excluded from the J2 manifest
  (the dao.star Mode 1 track added it without a listing — the fourth such
  break)
- f35df68a the portable load-key test binds the refusal text as a string
  (a Node-only fix found by the shiva test:cljs comparison)

The implementer's findings (read first, in full):
collab/1793027866046-engineer-j4d-local-persistence.claude-opus-5-5.findings.md
— includes the 42-entry retirement table with per-suite jolt counts, the
J4 closure statement (§9), and the audit of adjacent helpers (§5).

Design authorities:
- Design §5 J4d row and §6: collab/1792769713135-architect-j4-file-seams-design.claude-fable-5-1.findings.md
- The J4b review's F8 and the spec §5 Signing/Main rows (0600 key files;
  strict load-key): collab/1792984961521-architect-j4b-review.claude-fable-5-1.findings.md
- The lane spec: docs/design/yin.repl-jolt-implementation.md (§5 Files/
  Signing/Main rows, §10.1)

Review tasks:
1. THE F8/NARROWED REFUSAL: the dht: refusal moved from parse-arg to
   checked-spec. Verify every flag parses and validates as on the other
   hosts before composition (the evidence: an-oversized-number
   7-assertions failed under parse-time, passes at composition time), and
   that only composing a dht: store answers the J7 message. No guard
   deleted wholesale.
2. THE KEY FILES: 0600 from creation (O_EXCL, never overwrite, EEXIST
   raises the existing refusal), strict load-key (absent/malformed/
   mismatched refuse NAMING THE PATH, never the seed), and the signing
   vectors green.
3. SAVED STATE + INDEX + FLAGS: the state round-trip, the refusal set
   (unreadable, dangling symlink, non-regular), reset/no-state semantics,
   the index reopen — each verified against store_test/state_test/
   index_test semantics on the other hosts.
4. THE 42-ENTRY RETIREMENT: every J4-owned manifest entry retired by
   per-var evidence; the 18 J4/J7 CLI guards split by dependency with run
   evidence (8 rejoined green, 10 re-guarded J7 with the honest reason);
   no false claims that fixture code runs unchanged on Chez.
5. THE J3/J4 LABEL DRIFT (F6): reconciled per the J4b findings' table.
6. CUSTODY SHUTDOWN: the exit-path closes the custody journals before the
   host exits — passes on jolt; EOF-while-pending covered.
7. NO SHARED-CODE CHANGE: dao.jing.file's framing/recovery/make-put are
   untouched; the only shared-file edit is the :jolt require splice and
   the eight arms.
8. THE SEPARABLE http-test EXCLUSION: verify it is honest (the dao.star
   track's http-test loads dao.stream.http.jvm/http-kit and cannot load
   on jolt; the exclusion names owner dao.star) and that its listing is
   the dao.star seat's to land.

Write to:
collab/1793072368523-architect-j4d-review.moonshot-kimi-k3.1-pro.findings.md
End with APPROVE / APPROVE WITH CHANGES / REQUEST CHANGES + per-finding
rows and the properties that pass. Write ONLY that findings file.
