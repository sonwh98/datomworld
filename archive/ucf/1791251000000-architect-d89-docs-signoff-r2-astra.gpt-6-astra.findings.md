Completed-GMT: 2026-10-05 22:27:49 GMT
Completed-Local: 2026-10-06 05:27:49 +0700

CHANGES

1. **Finish the linker-dht §14.2.2 correction.** Its paragraph still says the enrolled set “is never encoded and travels nowhere” and applies protection checks to any retained entry. Replace with: “`:yin.k/enrolled` is supplied in the retained custody header from the ledger reader’s fold. It is excluded from the UCF body; the export record remains serializable. For retained `:put`, `:ffi-request`, and `:link-request` entries in the root or install children, an enrolled target without an operation id refuses as `:yin.k/non-portable`, kind `:unprotected-pending`; an operation id on an unenrolled target refuses as `:yin.k/unsatisfied`, naming the stream.”

2. **Require a nonempty close queue in UCF §7.7.4.** Replace “a machine carrying `:yin.k/closes`” with “a machine with a nonempty `:yin.k/closes` queue.” The implementation checks `seq`; an empty retained queue does not prevent export.

3. **Name and scope the self-check precisely.** Replace its opening with: “Before answering `:ok`, a version-1 root lift runs `checkpoint/inspect` on its own bytes and address, then `handoff/validate-body` on the decoded body.” Install children do not independently undergo the root custody inspection.

The revised text closes the previous gate-description, header-storage, counter-scope, abort-interface, and obsolete-variable findings. The three remaining edits resolve an unchanged contradictory paragraph, an overbroad hold condition, and the root-only inspector contract; the implementation sign-off remains unchanged. No files were edited and no suites were run.