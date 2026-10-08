Created-GMT: 2026-09-02 19:30:30 GMT
Created-Local: 2026-09-03 02:30:30 Asia/Ho_Chi_Minh

# Task: yin.repl implementation plan — sign-off round 4 (pool fix)

Role: Lead System Architect

Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-03 02:30:30 Asia/Ho_Chi_Minh | Status: active | Rationale: resume of sign-off session 01a0637c-3481-7343-aba6-b7cb632ce876; your round-3 HIGH finding is addressed

Repository root: /Users/sto/workspace/datomworld.

Your round-3 finding: "slots per connection" implied dynamic allocation
defeating the spec's bound, and R4 named no pool size. Both fixed:

1. The settled-answers item 2 now reads "a fixed pool of handoff slots,
   each a capacity-one offer and acknowledgement pair holding at most one
   pending connection".
2. R4's handoff bullet now reads "a fixed pool of 8 slots (configurable at
   `serve!`, minimum 1) … at most one pending connection occupies a slot —
   pool exhaustion is the bounded admission control the spec requires, not
   an error."

Verify via `git diff docs/design/yin.repl.implementation-plan.md` and
restate the verdict. Read-only; no tests; no edits.

Begin your final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh

Then: 1. SIGN-OFF: GRANTED or WITHHELD. 2. Any findings.
