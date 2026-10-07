Created-GMT: 2026-10-01 19:00:00 GMT
Created-Local: 2026-10-02 02:00:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Apply the fable Review Edits to the CLJD Reader Bugs Upstream Report

Role: QA & Verification (report finalization)

A previous agent was killed by a quota limit before starting; the draft
is untouched at collab/1790865064522-cljd-reader-bugs-upstream-report.md.

Read the fable-5.1 review at
collab/1790879091091-qa-cljd-report-review.claude-fable-5-1.stdout.log
(the "Required edits before submission" list and the two source
observations), then apply its prescriptions — the review is detailed
and self-contained: remove Bug 4 (fixed at upstream HEAD); retitle to
four runtime-reader bugs; re-run every remaining repro at upstream HEAD
0cbd540 (throwaway probe project in /tmp with the pinned toolchain,
Dart VM run); make the probe self-contained (deps.edn, one .cljd
namespace, exact compile/run commands, embedded); fix Bug 1 root cause
(dispatch/read-token position bug; bare % swallows the next character;
inside #() the "%" and "%&" branches can never match; Clojure ArgReader
passes (quote %) to readToken); add #(f %&) only after running it;
quote full exception types and messages; fix JVM comparison labels
(clojure.core/read-string vs clojure.edn); reframe Bug 3 (resolver bound
only at compile time; test (read-string "::foo")); fix Bug 2 code path
attribution and lower severity; raise Bug 5 to medium; fix the closing
compile-time note to cover bug 2; strip internal headers and the
private-architecture sentences (keep at most the generic Datalog
example; include BOTH named and unnamed datom.world variants clearly
separated for the owner to choose).

Also run the review bonus observation: interpret-token constructing
FormatException without throw (lines 131/135 at the pin; foo: accepted
silently?) — if confirmed by execution, add as a sixth bug with the
same rigor; if not, note investigated-and-rejected. Verify no upstream
duplicate issues exist (search tensegritics/ClojureDart issues) and
state the search.

Deliverable: the submission-ready report at
collab/1790865064522-cljd-reader-bugs-upstream-report-v2.md (the
original draft left intact). ASCII, <= 80 cols. Do NOT submit anything
upstream; do not commit or stage; do not modify other files; clean up
all scratch.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
