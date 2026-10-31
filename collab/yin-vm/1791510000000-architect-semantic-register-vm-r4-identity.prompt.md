Created-GMT: 2026-10-10 00:55:00 GMT
Created-Local: 2026-10-10 07:55:00 Asia/Bangkok

# Task: Round 4 -- walker executable identity in the linker amendment

Role: Lead System Architect (continuing thread 01a121d7-5ba4-71b1-a70b-f6b4cf3d056a)

Your round-3 finding taken; the commit on `a-only-code-identity` was
amended in place. Run `git diff master HEAD -- docs/design/yin.vm.linker.md`
and read the §3 block from disk.

Change: the first bullet now says "every EXECUTABLE backend: A" and
adds the contract-revision qualification on the `[A f]` index, that the
semantic format's storage address is A itself, and that the canonical
vector stays obtainable independently of a native-image entry. A new
bullet handles the AST walker: root row address retained as a
syntax-content address (code-as-tuples §4.1), superseded as an
EXECUTABLE identity; walker admission fetches the tree by storage
address, validates it, verifies tree -> A under the declared contract;
no reconstruction from A required or assumed; a trusted derivation
record never replaces that check.

Check, read-only: does this close the gap without opening another
(e.g. does "lowering it under the declared contract produces A" assume
the walker's tree and the semantic vector are related by the lowering
of yin.vm.linearize, which is the case today; is that the right
reference)?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then anything remaining, and end with one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED.
