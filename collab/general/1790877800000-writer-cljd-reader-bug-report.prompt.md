Created-GMT: 2026-10-01 18:37:00 GMT
Created-Local: 2026-10-02 01:37:00 +07 (+0700)
Coding-Agent: glm
Session-ID: c5ef618c-7fbd-455e-ae2c-39f2fe8da983

# Task: Draft the upstream ClojureDart reader-bug report (five defects)

Role: Scoped Writer (docs) with read-only evidence gathering

Implementers:
- Model: glm-5.3 | Assigned: 2026-10-02 01:37:00 +07 (+0700) | Status: active | Rationale: bounded self-contained doc; GLM strengths (evidence gathering, protocol discipline)

READ-ONLY everywhere: do not edit any file in any repository. Your deliverable is the report text as your final
response; the orchestrator promotes it to collab/.

Background: during the yang.python ANTLR work (CLJD compilation lane) this project found five ClojureDart reader
defects, recorded in docs/orchestrator-log.md (entry "2026-10-01 — session stop state", last bullet) and observed in
this project's compilation runs:

1. `%` outside `#()` — the reader mishandles the `%` arg-symbol outside an anonymous-function literal.
2. No duplicate refusal — the reader does not reject duplicate keys/cases where Clojure's reader refuses.
3. No syntax-quote resolver — syntax-quote symbol resolution is missing or incomplete versus tools.reader.
4. List `:tag <Type>` metadata — type-hint metadata on lists is mishandled (dropped or misread) versus the host reader.
5. Whitespace before a closer — the reader mis-lexes whitespace immediately preceding a closing delimiter.

Evidence phase (read-only):
- The local ClojureDart checkout is at /Users/sto/workspace/ClojureDart — identify its version/commit, locate the
  reader entry points and the relevant source lines for each defect if findable (search terms: reader, dispatch
  macro, %, arg, syntax-quote, resolve-symbol, tag, hint).
- This repo's observation records: grep docs/orchestrator-log.md for the reader-bug bullet and the CLJD lane failure
  notes; the compiled test artifacts under test/cljd-out/ (main repo) may carry the failing generated names — cite
  what exists, do not fabricate. Minimal reproductions: write the smallest literal snippet per defect that a
  ClojureDart user can paste (derive from the recorded observations; mark clearly which reproduce verbatim from the
  record versus which are inferred and need the maintainer's confirmation).

Draft phase: produce an upstream-ready issue report in clean Markdown: title, environment (ClojureDart commit/
version, host Clojure/tools.reader versions as found in the checkout's deps), one section per defect with
reproduction, expected (per Clojure/tools.reader behaviour), actual, and impact on downstream compilers; then a
short cross-cutting note (all five surfaced compiling one cljc codebase through the CLJD reader). Keep it factual;
do not speculate about fixes beyond one suggestion line per defect. ASCII, lines <= 80 columns.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
