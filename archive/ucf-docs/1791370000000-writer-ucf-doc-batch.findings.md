Completed-GMT: 2026-10-07 20:09:22 GMT
Completed-Local: 2026-10-08 03:09:22 Asia/Ho_Chi_Minh
Coding-Agent: deepseek (deepseek-v4-pro)
Session-ID: c5daf375-4353-42b2-b470-8c102756ec92

# UCF doc batch -- findings

Wrote the doc amendments owed by the landed stage-D slices, each
verified against the landed code (src/cljc/yin/vm/ucf/holder/driver,
inbox, export; authority.cljc) and the three naming rulings. ASCII
and 80-column checks pass: 102 added lines, 0 non-ASCII, 0 over 80.

## Files changed

- docs/design/yin.vm.universal-continuation-format.md -- 7.7.4 gains a
  (M-next D15a, as built.) driver-split note; 7.9 gains the
  :yin.k/refused status row and a (M-next D, as built.) block covering
  the D8/D14 refusal and hold vocabularies, the input-conflict vs
  :intent-conflict rule, and the inbox-retention merge-order rule.
- docs/design/yin.vm.linker.dht.md -- 14.2.2 gains the (M-next D14.)
  write-ahead progress journal contract; 14.3 records the third
  version-0 defect (export address digest, D1 90bd8e57) and marks all
  three fixed.
- docs/design/dao.lease.md -- Carriage gains the front-carried renewal
  (D2): same attribution and carriage as a proposal or release.
- docs/design/yin.vm.ucf-revisions.md -- section 6 status advanced
  through D15a (D10-D14, D10b-A/B, D15a commits), naming the deepseek
  F2/F3 D16-entry conditions and D15/D16/E as remaining.

## Notes

- Item 1 (control/program split, inbox retention machine, the
  unavailable-lane semantics, candidate semantics) was already landed
  in UCF 7.11's "Holder driver scheduling and retained inboxes
  (D15a)" section by commit 2cf99c13. Verified accurate against the
  code and the three rulings; added a concise cross-reference at 7.7.4
  rather than duplicating the section.
- Item 4's "D10b-B landed its fix" parenthetical names the wrong
  slice: the third version-0 defect (export address is not the digest
  of the emitted bytes) was fixed by D1 (90bd8e57), per the commit and
  the code, which is what 14.3 and section 6 now record.
- Item 7 (dao.stream.journal.md and handoff docstrings): no change;
  a reader of this batch is not misled, and no new section was
  invented. orchestrator-log.md and routing-status.md were not touched.
