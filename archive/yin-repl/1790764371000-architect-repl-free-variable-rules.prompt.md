Created-GMT: 2026-09-30 10:32:51 GMT
Created-Local: 2026-09-30 17:32:51 +07 (+0700)
Coding-Agent: claude
Session-ID: 4dc62d9f-13f1-49f4-ad40-1b82eb7a5e31
# Task: Architect ruling — how REPL users run occurrence-aware free-variable (and structural) rules through q

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-30 17:32:51 +07 (+0700) | Status: active | Rationale: OWNER routing ("use fable more ... as the architect"); design gap surfaced by $ast slice 3 QA

Read-only; do not edit files. Headless: your final response is the deliverable.

Finding (QA, gemini-3.8-flash, report collab/1790759260000-qa-engineer-repl-ast-index-slice3.gemini-3.8-flash.report.md §2):
the approved $ast design (collab/1790708845000-architect-repl-ast-row-relation.gpt-6-sol.findings.md §4 "Rules": rules stay
opt-in; the production root-scoped occurrence rules live in yin.vm and are exposed as an opt-in library the caller passes
as %) cannot be realised through the REPL q bridge: (1) yin.vm/occurrence-rules (src/cljc/yin/vm.cljc ~1624) need host
query functions path-pop and member? (yin.vm/occurrence-fns ~1649), which cannot cross the dao.stream.apply bridge as data,
and the bridge accepts no :fns option (src/cljc/yin/repl/query.cljc view-of ~401-415); (2) dao.space.query has no such
builtins; (3) the rules are written against $ datom triples ([$ ?lam :yin/type :lambda]), not $ast rows / $occ tuples.
The QA agent wrote test occurrence-rules-passed-as-data-only-refuse-due-to-missing-host-fns in
test/yin/repl/ast_query_e2e_test.cljc (uncommitted) asserting the refusal.

Read: docs/design/datom.world.md; docs/design/yin.vm.code-as-tuples.md (§4.5/§7.7 free vs bound derived by rules; the
occurrence relation); src/cljc/yin/vm.cljc ~1600-1700; src/cljc/dao/space/query.cljc (builtins, rule evaluation);
src/cljc/yin/repl/query.cljc; src/cljc/yin/repl/ast_index.cljc; test/dao/space/query_test.cljc ~1121-1161, ~1415; the
owner invariants (dao.stream.apply independent of rpc; derive, don't persist; host functions stay opaque but render as
{:type :host-fn ...}).

Rule precisely enough to implement without another design round:
1. Which fix: (a) make path-pop / member? (or equivalents) portable dao.space.query builtins; (b) rewrite the occurrence
   rules over $ast rows and $occ tuples using only existing builtins (and your (a) if needed); (c) the bridge offers
   named, versioned rule sets (e.g. :rules :yin/occurrence) resolved server-side, never host fns crossing the wire; or
   other. Weigh invariants, portability, keeping q's contract data-only, and whether yin.vm's own callers change.
2. The user-facing REPL form for "free variables of this root" (exact q call), and whether the shell ships the rule set
   by default (the design says rules stay opt-in — does that still hold?).
3. What the slice-3 refusal test should become (delete, invert to a positive test after the fix, or keep as a guard for
   raw host-fn passing).
4. Files, acceptance tests, and whether this is a follow-up slice (slice 4) or a change to slice 3 before commit.
5. OWNER decisions, listed separately.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
