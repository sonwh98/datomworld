Created-GMT: 2026-11-24 (dispatch time)
Coding-Agent: codex (gpt-6.1-terra, jupiter POOL 2)
Session-ID: 01a20de0-f80b-7ff2-8885-de32daef9dcd (codex thread.started, jupiter POOL 2)

# Task: remint amendment — Assignment B of the consolidated master-reds brief

Worktree: ~/ci/dw-remint (branch fix/py-manifest-remint @ b825db65 — ALREADY MERGED with
current master; zipmap form of data_test.cljc preserved per the brief). No commits — leave
edits, the seat publishes.

## The brief of record (READ FIRST)
- The master-green seat's consolidated brief: collab/general/1795550000000-dispatch-brief-all-master-reds-jolt-yangphp.md (Assignment B)
- The verdict: commit 48a2f9e2 + ADDENDUM 2 (778ca8b2) — collab/yang-php/1795460000000-to-yangphp-remint-countersign-reconcile-note.md (on master)
- Canonical state: FRESH ANTLR generation (your own prior ruling); manifest golden 6e60da0c... already verified green under it.

## The amendment work (three items)
1. **The 5 float-address pins** in test/yang/python/antlr/float_address_test.cljc (:229, :258,
   :263, :268, :275) — the expected/actual pairs are in the verdict. Update the pins to the
   fresh-generation values.
2. **linked_prelude_test.cljc:258 manifest-address golden** — your branch already carries the
   6e60da0c... re-mint; verify it holds on the merged tip (master moved since).
3. **NEW — the profile-lock in src/cljc/yang/python/antlr/profiles.cljc (module-locks)**:
   the py prelude content moved in 7692d347 without the lock update; every python-REPL row on
   both hosts refuses with :defect :address-mismatch (yin.repl 1F/3E JVM quick; python-antlr
   slow 0F/20E; dht-process partial; shiva node shard; dart descriptor-profile-test).
   Re-derive the lock from the fresh-generation path, TWO-RUN determinism, provenance comment
   per convention. This is production src — your surface (your landing moved the prelude).

## Constraints
- Keep the zipmap form of data_test.cljc (do NOT re-revert; the dart lane depends on it).
- No other files. gen:antlr fresh (the canonical state IS fresh generation).
- Log custody in target/remint-amend/.

## Bar (expanded amendment)
- `-n yang.python.antlr.float-address-test -n yang.python.antlr.linked-prelude-test` 26/0
  TWICE in fresh JVMs after a fresh bb gen:antlr.
- `clojure -M:test -n yin.repl.python-antlr-test` green on the JVM.
- Determinism runs recorded per pin (two-run SHA or byte-identical outputs).
Findings: collab/yang-php/<ts>-engineer-remint-amendment.gpt-6.1-terra.findings.md (worktree
root if blocked): per-item diffs, the determinism runs, gate numbers, the exact new lock value.
Stop rule: if the lock re-derivation disagrees with the published pins (a design-level
mismatch between the fresh-generation path and the manifest), STOP and record it.
