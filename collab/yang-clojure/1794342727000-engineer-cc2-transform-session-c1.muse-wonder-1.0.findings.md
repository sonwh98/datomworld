Created-GMT: 2026-11-10 21:20:45 GMT
Created-Local: 2026-11-11 04:20:45 +0700
Coding-Agent: muse
Session-ID: 1f899bc1-b01e-40d5-ac17-6238e74896a3

# cc2 — transform & session C1 preservation (yang.clojure adoption round 2)

Worktree: `/Users/sto/workspace/datomworld-cc2`, branch `cc2-transform-session-c1` @ `4cfa609d`. Left dirty, nothing committed. Five files changed, all inside bounded ownership; no forbidden file touched.

## What changed

### 1. Safepoint transform — `src/cljc/yang/safepoint.cljc`

- `selected-hooks` (line 202) extracted from `insert` (behavior identical) so derivation and tracking share one hook selection.
- `track-occurrences` (line 236): pure path arithmetic over the input map AST. Wrapping never drops/duplicates/reorders a subtree, so each input `:application` has exactly one tracked output place; sequencing insertions place their node under `[2 3]`, a lambda site's body shifts under the entry sequencing application and the exit hook application. Correspondence is derived from the rewrite's own insertions, never matched by content.
- `derive-call-sites` (line 293): recomputes C1 rows for the derived tree from the input rows. Surviving occurrences keep their input row's own origin at the shifted path; injected occurrences are claimed only under the caller-supplied `:injected-origin`. A row claimed by two origins (`:ambiguous`) or naming no input application (`:no-such-occurrence`) is refused attribution as `:unmapped` — never guessed.
- `derive-envelope` (line 381): new 4-arity with `{:injected-origin o}` (validated via `vm/occurrence-origin?`, refused with `:rule :origin`). The derived envelope carries the recomputed rows in `:yin/frontend-metadata` and the explicit correspondence `{:input-root :output-root :entries [...]}` (entries `:surviving` / `:safepoint/injected` / `:unmapped`) as `:yang.safepoint/correspondence`; the answer map also carries `:correspondence`. The 3-arity is unchanged; with no input C1 claim the derived envelope, tree, and record are byte-identical to before (golden roots in `yang.safepoint-test` still pass).

### 2. Encoder projected member — `src/cljc/yin/vm/encoder.cljc`

- `project-member` ({:root :rows} branch, line 106): preserves caller-supplied `:frontend-metadata` (and `:source-positions`) instead of hardcoding `[]`; a non-sequential supplied value is refused (`:rule :frontend-metadata`).
- `check-projected-claim!` (line 308): validates supplied C1 rows the way `check-call-site-claim!` validates map-AST members — version must be 1 (`:yang/call-site-unsupported-version`), and the claimed `[root path]` set must equal the tree's application occurrences (`:yang/call-site-incomplete-coverage`, with `:missing`/`:extra`). No rows = no claim = legacy pass.

### 3. Index/REPL publication — `src/cljc/yin/vm/linker/publish.cljc`, `src/cljc/yin/repl.cljc`

- `module-from-index` (line 618) accepts `:call-site-tables`. `proven-call-sites?` (line 553) requires, for every collected program, a retained table keyed by that program's canonical root whose version-1 rows cover exactly its application occurrences. Only then `mark-call-sites` (line 534) recovers the markers over the whole sequenced tree — recovered subtrees plus the `sequenced` wrappers this derivation synthesizes, whose origin is the admission `publish-module!` records. Without full proof the tree stays unmarked and publishes with no C1 claim; no marker is minted.
- `yin.repl/call-site-tables` (repl.cljc line 1205) exposes the expander's retained tables; `link-context` (line 1317) now carries `:call-site-tables`; `render-compile-output` (line 1834) renders a `Call sites:` section when the expansion's `:frontend-metadata` is non-empty, and only then.

### Tests — `test/yang/clojure_callsite_test.cljc`

Four new deftests: `safepoint-c1-preservation-test` (surviving/injected/ambiguous+stale, with and without supplied origin, canonical invariance vs the unmarked derivation), `encoder-projected-member-test` (verbatim survival, incomplete/unsupported refusal, legacy), `module-from-index-c1-test` (proven recovery publishes a retrievable side table; absent/empty proof publishes claim-free), `repl-call-site-surface-test` (rendering + link-context carriage).

## Verification (all foreground, `clojure -M:test`, explicit timeouts)

| Command | Outcome |
|---|---|
| `clojure -M:test -n yang.clojure-callsite-test` (baseline, pre-change) | 19 tests, 243 assertions, 0 failures |
| `clojure -M:test -n yang.clojure-callsite-test` (final) | **23 tests, 285 assertions, 0 failures, 0 errors** (+4 tests / +42 assertions are cc2's) |
| `clojure -M:test -n yang.safepoint-test -n yin.vm.linker.publish-test` | 24 tests, 102 assertions, 0 failures |
| `clojure -M:test -n yang.typescript.antlr.safepoint-test` | 9 tests, 70 assertions, 0 failures |
| `clojure -M:test -n yin.repl.ast-index-test` | 13 tests, 138 assertions, 0 failures |
| `clojure -M:test -n yin.vm.linker.layering-test` | 4 tests, 27 assertions, 0 failures |
| `clj -M:kondo --lint` (5 changed files) | 0 errors, 1 warning (pre-existing, see below) |

## Deviations and unverified items

- **Exception-contract gate (brief item 3): STOP condition recorded.** `yang.call.exception-contract-test` (and its `:clojure` var alone) does not complete within 200–280 s in this environment — 0 provenance rows printed — on both the modified tree and the stashed base `4cfa609d`. No red row authored by cc2 was observed; the suite is documented in-tree as intentionally red pending exception rounds 1–4, and here it is additionally unverifiable by timeout on either tree. This is a pre-existing condition, not a cc2 regression.
- **Python safepoint consumers:** `yang.python.antlr.safepoint-test` and `yang.python.antlr.linked-safepoint-test` likewise time out at 280 s on both trees (pre-existing heavy namespace load); unverified here. The TypeScript consumer and the core safepoint suite are green.
- **Kondo warning:** `yin/vm/encoder.cljc` — `load already refers to #'clojure.core/load` — present identically on the untouched base (line 355 there, 400 here). Not introduced by cc2; fixing it would rename a public API, out of scope.
- **REPL auto-threading boundary:** `yin.repl.query/link-answer` (outside bounded ownership) builds the `publish-name!` opts explicitly, so `(yin.link/publish ...)` does not yet forward the `:call-site-tables` the link context now carries. One-line forward in `yin/repl/query.cljc` completes the automatic path; until then compositions call `module-from-index` with `yin.repl/call-site-tables` directly. `yin.vm.linker.dht/publish-name!` already forwards opts wholesale.
- **Environment:** the worktree lacked the gitignored ANTLR generated classes, so every `yin.repl`-loading suite failed with `ClassNotFoundException yang.php.antlr.gen.PhpLexer`. `clojure -M:antlr-gen` generated the PHP sources then failed on a network fetch for a later grammar (sandbox policy). The regenerable, gitignored `build/antlr/{php,python3,typescript}` classes were copied from the sibling checkout of the same repo; `build/` is gitignored and does not enter the diff.
- Prompt line citations and its "14 existing tests" count are stale against this branch (actual baseline: 19 tests / 243 assertions); all work was re-derived against the worktree as instructed.

- **Findings delivery:** this file was staged at `/tmp/cc2-findings.md` because the session sandbox denies writes to the sibling checkout; deliver with: `cp /tmp/cc2-findings.md /Users/sto/workspace/datomworld/collab/yang-clojure/1794342727000-engineer-cc2-transform-session-c1.muse-wonder-1.0.findings.md`
