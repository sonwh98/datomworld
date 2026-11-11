Created-GMT: 2026-11-11 03:52:07 GMT
Created-Local: 2026-11-11 03:52:07 +0700
Coding-Agent: muse
Session-ID: 1f899bc1-b01e-40d5-ac17-6238e74896a3

# Task: cc2 — transform & session C1 preservation (yang.clojure adoption round 2)

Role: VM Engineer (compiler provenance)

Implementers:
- Model: muse-wonder-1.0 | Assigned: 2026-11-11 03:52 +0700 | Status: active | Rationale: flagship flat-lane model; just completed the macro-build round successfully on this repo

## Where you work

Worktree: `/Users/sto/workspace/datomworld-cc2` (branch `cc2-transform-session-c1`, cut from `origin/master` @ `4cfa609d`). Work ONLY there. Do not touch `/Users/sto/workspace/datomworld` or any other worktree. TDD: write the failing test first, then implement.

## Goal

cc1 landed the C1 call-site occurrence contract for the Clojure frontend ([origin root-address path :yang/call-site 1] rows in frontend-metadata). Three downstream surfaces still silently strip or fail to carry C1 claims. Close them. The authoritative scoping (with file:line evidence, verified against master) is `collab/yang-clojure/1794314809000-architect-adoption-rounds-2-4-d1-scoping-r2.gemini-4-argon-high.findings.md` §1.2 rows 5/7/12 and §2.2 Round 2. Read it first, then the cited code — the citations are against ~`8cd9fc92`, re-derive exact lines in your worktree.

## The three work items

1. **Safepoint transform** — `src/cljc/yang/safepoint.cljc` `derive-envelope`: today the derived tree's `:yin/frontend-metadata` is built only from safepoint site marks. When the INPUT tree carries C1 rows, the derived tree must carry recomputed C1 rows for its surviving applications (paths shift — the tree changed), and injected `(check-safepoint)` applications must be covered with origins that are HONEST (see ruling below). Record an explicit input→output occurrence correspondence as data (which old `[root path]` maps to which new one; injected occurrences identified as injected, with a real origin — a publication/admission origin supplied by the caller, never fabricated from structure). If a correspondence is ambiguous, refuse attribution for that occurrence — never guess.
2. **Encoder projected member** — `src/cljc/yin/vm/encoder.cljc` `project-member` (~:108-114): the `(and (map? member) (contains? member :root) (contains? member :rows))` branch hardcodes `:frontend-metadata []`. Preserve metadata the caller actually supplied on an already-projected member (validate it the same way map-AST members are validated via `check-call-site-claim!`), instead of dropping it. Read the surrounding code to see what "supplied" means for such members before designing — do not invent a carrier that other callers never pass; if none can pass metadata today, the fix is to accept and validate it, with the test proving it survives.
3. **Index/REPL publication path** — `src/cljc/yin/vm/linker/publish.cljc` (`indexed-programs` ~:487-501, `sequenced` ~:527-541, `module-from-index` ~:562-635) and `src/cljc/yin/repl.cljc` (~:1205-1231 expander state, ~:1808-1822 `render-compile-output`): `datoms->ast` reconstruction loses C1 markers, and synthesized `sequenced` wrapper applications are unmarked, so a session module republished from the index silently loses (or falsely claims) C1. Thread the expander's retained `(:call-site-tables (:expander state))` (or an explicit option) into `module-from-index` so a C1-claimed session publishes its side table; recovered trees must NOT carry minted markers — either recover the real rows from the retained tables or publish without a C1 claim. Annotate the `sequenced` wrapper with a real admission origin from the publication path. Surface `:call-sites` in `render-compile-output` when non-empty.

## Non-negotiable invariants (a violation is a failed round even if tests pass)

- **Origins are never minted** (owner ruling, `collab/yang-clojure/1795310000000-owner-ruling-occurrence-identity-unitarity.md`): an origin is read from retained admission/expansion/publication records. If no real record exists, there is no origin — refuse or omit the claim, never fabricate one that shape-checks.
- **Zero canonical change**: adding/deriving C1 metadata must not change canonical rows, root addresses, instruction vectors, or hashes for otherwise identical input. Your tests must assert canonical invariance (existing test `canonical-invariance-test` in `test/yang/clojure_callsite_test.cljc` shows the pattern).
- **Legacy stays loadable**: unannotated trees/artifacts remain valid; absence of C1 is not an error.
- **Construction origins are content-invisible**: they never reach CBOR/hashing (the macro.cljc discipline — you are not editing macro.cljc, but anything you add must respect this at the encoder boundary).

## Bounded file ownership

MAY edit: `src/cljc/yang/safepoint.cljc`, `src/cljc/yin/vm/encoder.cljc`, `src/cljc/yin/vm/linker/publish.cljc`, `src/cljc/yin/repl.cljc`, `test/yang/clojure_callsite_test.cljc`, existing safepoint/encoder/repl test files, and (only if a check demands it) `src/cljc/yin/vm.cljc` validators.
MUST NOT edit: `src/cljc/yang/clojure.cljc` (ceded to the exception track, `:1506-1629` region is theirs; one-writer), `src/cljc/yang/core/prelude.cljc` (php/ts seat), `src/cljc/yin/vm/macro.cljc` (cc1's two carrier invariants — off-limits this round), `src/cljc/yin/vm/semantic/*` (cc3's surface).

## Verification (laptop, G0 focused only — never the full suite)

`bb` is NOT on PATH; use `clojure -M:test`. All foreground, with explicit timeouts. Do NOT background anything.

1. `clojure -M:test -n yang.clojure-callsite-test` — your new tests + all 14 existing ones green.
2. The touched surfaces' own test namespaces (safepoint, encoder/linker, repl — find them; e.g. `clojure -M:test -n yang.safepoint-test` etc.).
3. `clojure -M:test -n yang.call.exception-contract-test` — the exception-track registry requires this in every frontend round's verification. Record the pass/fail row counts; if any row you did not author is red, STOP and report it as a pre-existing condition (baseline check: run it on your base commit `4cfa609d` first if any row is red, so you can attribute).
4. `clj -M:kondo --lint` on every file you changed — 0 errors 0 warnings.

## Deliverable

Write your findings to `/Users/sto/workspace/datomworld/collab/yang-clojure/1794342727000-engineer-cc2-transform-session-c1.muse-wonder-1.0.findings.md` (absolute path; the worktree has no writable collab of record). Header per protocol (Created-GMT/Created-Local/Coding-Agent/Session-ID). Contents: what changed per file with line references, the exact verification commands + assertion counts + outcomes, any deviation from this brief with the reason, and anything you could not verify. Commit nothing — leave the worktree dirty for orchestrator review.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
