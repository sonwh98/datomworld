Created-GMT: 2026-10-04 06:40:45 GMT
Created-Local: 2026-10-04 13:40:45 +07 (+0700)
Coding-Agent: claude
Session-ID: ca67385c-91bf-415b-b38b-aadb290f6936

# Task: Fix the two version-0 handoff defects found during the UCF v1 amendment

Role: VM Runtime Engineer (UCF handoff)

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-04 13:41 +07 | Status: active | Rationale: owner asked to close the open engineering items; wave 1 of the sequenced plan

Work in /Users/sto/workspace/datomworld-v0fix (branch ucf-v0-defects, master ff1e0195; a NEW worktree; node_modules installed).

## Context
The UCF version-1 amendment is published (UCF 7.2.1, 7.4.3, 7.7.8; docs/design/yin.vm.universal-continuation-format.md and yin.vm.linker.dht.md 14.3). While writing it, the architect pair found two defects in the
LANDED version-0 reader in src/cljc/yin/vm/ucf/handoff.cljc. They are version-0 preservation and validation defects, NOT version-1 gaps, and they do not reopen M-next A's kept-cursor evidence or the M4 gate
(14.3 says so). Read the "reconciliation" in collab/1791055853000-architect-ucf-v1-amendment-m-next-b.claude-fable-5-1.findings.md and gpt-6-astra's review
(collab/1791056670000-architect-ucf-v1-amendment-review.gpt-6-astra.final.md; copies are staged in this worktree collab/) before editing.

1. Parked frames: `export-task` picks the `:parked` body shape before considering nonempty waits and serializes those waits (about handoff.cljc:641); `lower` constructs their entries and then assigns `:wait-set []`
   for a `:parked` body (about :1386), so an admitted body carrying both a park and other waits loses the waits. Per UCF 7.4.3 (r7): "waits on nothing" describes the explicit parked activation, NOT the whole task;
   other carried frames remain ordered waits and must be restored.
2. Missing install entry: lift checks that every install pending has an entry (about :497); `validate-body` (about :996) validates entries that exist but does not require one per install pending, so a foreign
   malformed body bypasses the lift check.

## What to do
- Verify each defect is real by writing a FAILING version-0 test first (red) on the unchanged code, on the same host matrix the neighboring handoff tests use (read test/yin/vm/ucf/handoff_test.cljc; they are .cljc and run
  on JVM, Node and Dart): a parked body that also carries waits must keep them across export, lower and resume; a body with an install pending and no install entry must be refused before restoration with an EXISTING
  outcome status (read UCF 7.9 and the existing validate-body refusals; add no new status). Show the red output, then fix minimally (green).
- Do not change any existing assertion of M-next A's tests; add new tests only. Do not change opcode semantics, code stamps or the wire format for valid version-0 bodies.
- Allowed files: src/cljc/yin/vm/ucf/handoff.cljc and any ucf*.cljc it needs, test/yin/vm/ucf/handoff_test.cljc (or a new sibling test namespace), and one status line in docs/design/yin.vm.ucf-revisions.md section 6
  recording that the two version-0 defects are fixed. Ask before anything else.
- Focused runs: the handoff and UCF namespaces (`-n yin.vm.ucf.handoff-test` and the other yin.vm.ucf.* tests), `yin.vm.ucf-test`, plus the linker tests that use handoff (`-n yin.vm.linker-test`).

## Rules (apply to every engineer round)
- You cannot run git write commands (stash, checkout, rebase, reset, commit, stage): do not try; the orchestrator does all git steps, including rebases. Edit files directly. Do not touch collab/ in the main tree.
- Run ONLY focused tests (`clojure -M:test -n <ns>`; add `-e :slow` to skip the slow ones). The orchestrator runs the full JVM, Node and Dart lanes. NEVER run `clojure -A:test -M -e` (the :test alias's runner launches the whole suite).
- Foreground only, single turn: no background processes; chunk anything that could pass 10 minutes. Kill nothing you did not start.
- kondo 0 errors; `mise exec -- cljstyle fix` then `check` on changed files (run directly, not through a piped loop). ASCII; keep added lines <= 80 columns where practical.
- Anything touching the Python prelude moves content-address goldens in test/yang/python/antlr/float_address_test.cljc: re-mint them LAST and only once (run `clojure -M:test -n yang.python.antlr.float-address-test`, write the actual values in the same :segment/blake3-... form; only address-golden lines may change). If master moves before landing, the orchestrator rebases you and you re-mint again.
- Write your findings to the path named below. Begin the final response exactly with: Completed-GMT / Completed-Local (named timezone) / Coding-Agent: claude / Session-ID: <exact id>. Then report changed files, exact test outcomes with counts, deviations, and anything unfinished. Do not claim edits or runs that did not occur.

Findings: /Users/sto/workspace/datomworld-v0fix/collab/1791096045000-compiler-engineer-ucf-v0-handoff-defects.claude-opus-5-5.findings.md
