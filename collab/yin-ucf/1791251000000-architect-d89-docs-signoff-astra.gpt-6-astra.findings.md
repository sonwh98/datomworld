Completed-GMT: 2026-10-05 22:17:04 GMT
Completed-Local: 2026-10-06 05:17:04 +0700

CHANGES

1. **Correct §7.7.4’s “every observation parks” claim and name the hold carriers.** Gated cursor creation returns an unminted reference immediately; gated close queues work and returns `nil`. Replace that phrase with “internal computation proceeds; stream observations and effects are deferred to the driver.” Name `:reason :observe`, `:yin.k/held` on entries/cells, `:yin.k/unminted` on cursor cells, `:yin.k/closes`, and absence of `:cursor` on a `:link-request` entry. This preserves the D5 corrections to the earlier D4 design.

2. **Make the header channel and self-check explicit.** Name `holder.export/prepare [machine record serve! header]`, the record’s `:header`, and `handoff/export-task`’s `:header` option. State that a version-1 root runs `checkpoint/inspect` on bytes/address, **then** `handoff/validate-body` on the decoded body before `:ok`. A header-bearing halt without origin is refused; it is not automatically downgraded. Qualify “never encoded” as “never encoded into the UCF body”: the enrolled set remains in the retained header, and the export record supports serialization.

3. **Scope the refusal descriptions precisely in both documents.** The protection checks cover retained `:put`, `:ffi-request`, and `:link-request` entries, including install children—not every retained entry. Counter exhaustion applies to blocked/parked version-1 roots; halted roots ignore that header counter. Name the bound as `2^52-1`.

4. **Distinguish the general abort rule from D8’s implemented evidence interface.** Name `holder.export/abort`, the persisted `attempts` sequence with each outcome under `:append`, and the closed set `#{:dao.stream/full :dao.stream/invalid-value :dao.stream/closed :dao.stream/refused}`. D8 accepts no attempts or exclusively those outcomes; it has no separate authoritative-clearance argument for an ambiguous attempt. Name successor tenure as `{:now n :bound b :live true}`, with `:live` supplied by ledger evidence. Retain the broader normative rule without implying that additional clearance path is implemented.

5. **Remove the obsolete `emitted-version` reference from revisions §6.** D9 removed that variable. Say: “`handoff-version` is `#{0 1}`; a nil header selects the frozen version-0 wire, and a supplied custody header selects version 1.”

The amendments preserve the intended gate, child-stamping, lift-refusal and abort safeguards, but the issues above misdescribe landed behavior or omit carriers needed to implement the contract accurately. The D10–D16/E remainder and D11 protection-class/fenced-writer deferral are appropriate; my earlier implementation sign-off stands. No files were edited and no suites were run.