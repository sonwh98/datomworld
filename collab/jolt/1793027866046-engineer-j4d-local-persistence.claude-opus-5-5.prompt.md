Created-GMT: 2026-10-26 14:24:26 GMT
Created-Local: 2026-10-26 21:24:26 +07

# Task: J4d — local REPL persistence and authority integration (the last J4 slice)

Role: Engineer (jolt lane). Your session built J4a/J4b/J4c; J4d closes the
remaining local integration obligations.

## Authorities

- Design: collab/1792769713135-architect-j4-file-seams-design...findings.md
  §5 J4d row + §6 (the additional local-integration impact and the exact
  J4-only REPL deferrals table — 42 entries transcribed from the
  applicability manifest).
- Reviews: collab/1792791000000 (design review, F7: J4d's regression clause
  must name lanes and expected outcomes) and
  collab/1792984961521-architect-j4b-review (F6: the J3/J4 label drift —
  main_test.cljc:279's "J4/J6" comment vs the manifest's J3 row, and the
  :1937 custody-drain "J4/J6" vs "J3/J4" — J4d owns the reconciliation).
- The landed J4a/J4b/J4c work on master: native/jolt-file (layout 2),
  dao.posix.file (fail-closed state included), the fs store :jolt arms, the
  journal :jolt arms, and the lane driver at 217-ns manifest with 0 J4
  exclusions left in the J4a/J4b/J4c sense.

## Scope (design §5 J4d row + §6)

1. **Local `file:` API/CLI**: store.cljc:52-57,158-161 currently refuse both
   file and DHT store specs on jolt — narrow the refusals to the still-
   unavailable DHT capability only (per review F8/the design's §6: do not
   delete the guard wholesale); the file store opens content-jing and
   receives publications (store_test semantics).
2. **Saved state, index, flags**: reopen the published index and saved
   flags; reset/no-state behavior; state round-trips through its file; the
   state refusals (unreadable, dangling symlink, path naming).
3. **Key files / authority**: strict key-file read; exclusive non-overwrite
   keygen (0600 per F4 — private permissions ARE the key-file requirement);
   interrupted publication and failing-content-close release. Signing
   vectors stay green.
4. **Shutdown**: local custody journals flush/close at shutdown; EOF-while-
   pending and outstanding-custody cases.
5. **Retire the J4-owned REPL applicability entries by actual var evidence**
   (the design §6 table's J4d rows — store/state/index/main; split mixed
   J4/J7 CLI cases by dependency: local file/key behavior rejoins, DHT
   transport waits for J7). Reconcile the F6 label drift while there.
6. **Audit adjacent helpers** (design §6): dao.space.store directory
   creation, journal.file/ensure-dir!, temp/test helpers, local state/main
   file helpers — working pwrite is not proof their remaining Java branches
   work on jolt.
7. **Ledger/spec**: known-gaps row 74 (saved flags/reset/no-state under J4)
   closed with evidence; the J4a-era "both sets" rows already closed stay;
   the fs-test row duplication (line 64 open vs the J4b Closed row — noted
   by deepseek's J4c review as pre-J4-signoff cleanup) reconciled here per
   the ledger merge rule (close in place, union, old schema).

## Acceptance (design §5 J4d row, made falsifiable per review F7)

- `bb test:jolt-core` with the J4-owned REPL entries removed from the
  applicability manifest and no `:owner "J4"` rows remaining — zero pins,
  zero unexplained skips; per-lane counts recorded.
- `bb test:clj`, `bb test:cljs`, `bb test:cljd` unchanged counts (the
  shared-source edits are jolt-arms-only).
- store/CLI/state/index/keys suites green on jolt, macOS AND Linux (shiva
  recipe), with the cross-host reopen fixtures where the design's §5 rows
  name them (JVM/Node/Dart write → jolt replays → original host reopens).
- Signing/envelope vectors green; keygen refuses overwrite; the key-file
  0600 mode verified.
- known-gaps/spec/README edits in the same acceptance change.

## Constraints

- Branch jolt-j4d off master (c85eccc8); commits in repo style; merging is
  the orchestrator's. Review: deepseek is the assigned independent
  reviewer for J4d (the opus implementer is Claude-family).
- J4d closes J4, not J5: no UDP/WebSocket enablement, no DHT claims — the
  J6/J7 exclusions stand.
- The 42 J4-only manifest entries: retire by actual var evidence; host-
  specific process/Node cases keep their original-host tests plus a jolt
  equivalent, never a false claim that fixture code runs unchanged.
- The fail-closed observation from deepseek's J4c review (any write
  throwable, broader than the design's persistence-uncertain criterion —
  conservative-safe): carry the docstring note in the facade if you touch
  those functions; no code change required.
- Remote verification per build-n-test (shiva; the remote-agent pattern is
  available to you).

Deliverable: branch + findings to
collab/1793027866046-engineer-j4d-local-persistence.claude-opus-5-5.findings.md
(the store/CLI/keys diffs, the manifest retirement with per-var evidence,
per-gate results both platforms, the J3/J4 label-drift reconciliation,
J4-closure statement). Begin now.
