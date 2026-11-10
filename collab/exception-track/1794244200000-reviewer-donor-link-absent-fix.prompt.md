Created-GMT: 2026-11-09 17:32 GMT
Created-Local: 2026-11-10 00:32 +0700
Coding-Agent: kimi
Session-ID: pending (provider-generated)

# Task: adversarial review — the donor-link-absent fix in the exception kit

Role: Review (adversarial, routine-correctness flavor)

Implementers:
- Model: k3.1-pro | Assigned: 2026-11-10 00:32 +0700 | Status: active | Rationale: deep invariant review; different family from the Gemini author (team.md independence rule)

## Read-only review

You review; you edit NOTHING. The change under review lives UNCOMMITTED in
the worktree `/Users/sto/workspace/datomworld-xlink` (branch detached at
origin/master `a5a3ac0e`):

- `git -C /Users/sto/workspace/datomworld-xlink diff` — the fix (one file,
  `test/yang/call/conformance.clj`, +12/−3).
- Author's findings:
  `/Users/sto/workspace/datomworld/collab/exception-track/1794241000000-engineer-donor-link-absent.gemini-3.8-flash-high.findings.md`
- Original brief (constraints the fix had to satisfy):
  `/Users/sto/workspace/datomworld/collab/exception-track/1794241000000-engineer-donor-link-absent.prompt.md`
- Problem statement:
  `/Users/sto/workspace/datomworld/collab/exception-track/1794240000000-orchestrator-problem-donor-link-absent.md`

Read the actual source in the worktree, not just the findings' quotes:
`src/cljc/yin/vm/linker/publish.cljc` (the C1 call-site claim), `src/cljc/
yang/core/uast.cljc` (`annotate-call-sites`), `src/cljc/yang/clojure.cljc`
(`lower-unit`), and the kit `test/yang/call/conformance.clj`.

## Context (verified by the orchestrator)

On master `a5a3ac0e` the single test `yang.python.antlr.exception-call-test/
clojure-to-python-test` produced 24 operational errors (`Module link
refused: absent` for `xlang.donor.clojure`). The author attributes this to
commit `b825498e` (C1 call-site coverage enforced in `publish`): the kit's
`publish-donor!` wraps a C1-annotated Clojure unit in synthetic wrapper
applications that carry no call-site marks, so publication is refused
(`:yin.link.publish/incomplete-call-site-coverage`), `(:address result)` is
nil, the serving name-env maps the donor to nil, and every link refuses
`:absent`. The fix C1-finalizes the donor tree (`u/annotate-call-sites`)
when the tree claims call sites. Post-fix the author reports 9 failures /
0 errors, matching the ledger of record's red rows.

## Review questions (answer each with evidence)

1. **Mechanism correctness**: Is the attribution right — publication
   refusal → nil address → name-env nil → `:absent`? Verify the exact code
   paths; flag any hand-waving.
2. **Fix semantics**: Does `u/annotate-call-sites` on the SYNTHETIC wrapper
   tree change execution semantics of the donor module (call-site ids are
   occurrence identity — does annotating wrapper applications invent
   identities the cc1 contract does not expect)? Compare with how
   `yang.core.call` and `yang.clojure.prelude` finalize their trees.
3. **Coverage**: All 24 affected rows (8 Clojure→Python pairs, 6 PHP
   provenance, 6 Python→PHP) flow through this one `publish-donor!` — does
   the fix cover them all, including the frontends whose donors do NOT
   claim call sites (the `cond->` leaves them untouched)?
4. **The silent nil**: `publish-donor!` returns `(:address result)` without
   checking `result`'s status — a refused publication becomes a nil address
   and fails far from its cause. Should the kit throw on a refused
   publication instead? (Flag as a finding with severity; do not fix.)
5. **Duplication**: the kit now has a local `claims-call-sites?` copying
   `publish.cljc`'s private predicate. Acceptable, or should it read the
   claim from the publish API?
6. **Constraint compliance**: no ledger edits, no weakened tests, no
   commits, G0-only test runs claimed. Any violation?
7. **Risk of regression**: which OTHER tests exercise `publish-donor!`
   (`conformance.clj` callers: `call`, `export-program` users, source-call
   tests)? Does annotating the tree risk breaking currently-green rows?
   Name the namespaces most at risk (do NOT run suites; static reasoning
   only).

## Deliverable

Write your findings to
`/Users/sto/workspace/datomworld/collab/exception-track/1794244200000-reviewer-donor-link-absent-fix.kimi-k3.1-pro.findings.md`
(absolute path — the worktree has no collab/). One disposition per question
(accept / defect with severity P0–P3 / observation), each with file:line
evidence. End with a one-line verdict: ACCEPT or CHANGES-REQUIRED with the
blocking findings numbered.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Do not claim to have run tests; this review is static.
