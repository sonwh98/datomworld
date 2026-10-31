Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: deepseek (deepseek-v4-pro)
Session-ID: c5daf375-4353-42b2-b470-8c102756ec92

# Task: UCF doc batch — the amendments owed by the landed stage-D slices
Role: Scoped / Subagent (docs)

Write the doc amendments owed by the landed stage-D slices, in
/Users/sto/workspace/datomworld-docs (worktree, branch ucf-docs off
master). Read first: docs/design/yin.vm.universal-continuation-format.md
(7.2.1, 7.4.1-7.4.3, 7.7.4-7.7.8, 7.9, 7.11.1 as they stand),
docs/design/yin.vm.linker.dht.md (14.2.1-14.3),
docs/design/dao.lease.md (Composition duties/Carriage),
docs/design/yin.vm.ucf-revisions.md (section 6),
docs/design/dao.stream.journal.md (the landed C12 additions), and the
rulings that name the owed text: collab/1791370000000-architect
-d15-driver-seam-ruling-astra.gpt-6-astra.findings.md,
collab/1791374000000-architect-d15a-inbox-ruling-astra
.gpt-6-astra.findings.md,
collab/1791379000000-architect-d15a-inbox-unavailable-ruling-astra
.gpt-6-astra.findings.md (all in the main tree's collab/), plus the
landed code the text must match: src/cljc/yin/vm/ucf/holder/driver.cljc
(the control/program split, the inbox retention machine), holder/
export.cljc, holder/inbox.cljc, authority.cljc (exclusive-capable?).

The owed amendments (verify each against the landed code before
writing; the code is the truth):

1. UCF 7.7.4/7.7.5: the control/program split as built
   (control-step/program-step/stop/owed-control-write?), the inbox
   retention machine, the unavailable-lane semantics (neither lane's
   :unavailable stops renewal or cleanup; reply-lane unavailability
   sends due renewals without crediting them; the bound still ends the
   run), and the candidate semantics (an :activating candidate still
   stops at its own lease bound).
2. UCF 7.7.8/7.9: the inbox-retention record's merge-order rule; that
   an input conflict is never translated to :intent-conflict;
   :yin.k/refused and the hold vocabularies D8/D14 coined.
3. linker-dht 14.2.2: the source structure inside :yin.k/name; the
   enrolled set is a lift input and never travels; the journal's
   write-ahead contract.
4. linker-dht 14.3: the third version-0 defect record (D10b-B landed
   its fix — mark it fixed, do not just delete the entry).
5. dao.lease.md Carriage: a renewal carried by the front (D2).
6. yin.vm.ucf-revisions.md section 6: status through D15a (D1-D14,
   D10b-A/B, D15a commits; D15/D16/stage-E remaining; deepseek's
   F2/F3 D16-entry conditions named).
7. docs/design/dao.stream.journal.md and yin.vm.ucf handoff docstrings
   only if a reader of the batch would otherwise be misled — do not
   invent new sections.

Style: ASCII only, 80-column margin, match each document's existing
voice and slice-marker conventions ((M-next Dx, as built.) / (M-next
D15a.) markers). Update the format.md-style revision lines where the
documents carry them. Do not touch orchestrator-log.md or
routing-status.md.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report the files changed and a one-line summary per file. ASCII and
80-column checks are yours to run (python3 or awk).
