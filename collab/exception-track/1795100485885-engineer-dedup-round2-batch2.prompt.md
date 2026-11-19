Created-GMT: 2026-11-19 15:01:25 GMT
Created-Local: 2026-11-19 22:01:25 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: yang-dedup-round2-batch2
Role: Engineer

Implementers:
- Model: gpt-6.1-terra | Assigned: 2026-11-19 22:01:25 +0700 | Status: active | Rationale: same implementer family as batches/rounds prior; the plan is fully reviewer-fixed (argon audit + conformance amendments, astra dual-role conditions)

Implement round-2 batch 2 in this worktree (detached at current origin/master). You may edit
files and run tests. **Do not commit, do not push, do not touch collab/.** Scratch scripts in
/tmp. Match surrounding style. Reviewer findings are in the worktree at
collab/exception-track/1795020669393-…findings.md (argon audit, sections C4/C5/C6),
1795022027297-…findings.md (conformance amendments), 1795022967614-…findings.md (astra
conditions incl. the C5 export-tree signature fix and the eval sanitization preservation) —
read those sections first.

## Extractions (all reviewer-amended)

### C4: io.clj + io.cljs → src/cljc/yang/io.cljc
- One cljc file; `#?(:clj … :cljs …)` for the ns header's reader requires and for exactly
  the two host lines: `read-source` (io/reader vs .readFileSync) and `write-ast` (spit vs
  .writeFileSync). The other 4 functions (read-forms, compile-file, compile-string,
  compile-and-save) are character-identical — single bodies. Public API stays 5 entry
  points. Delete the two old files. Only JVM and Node load yang.io (cljd/jolt do not).
  Update any ns references if the require path changes (it shouldn't: yang.io stays the name).

### C5: CST internals + export consolidation
- In `src/clj/yang/antlr/cst.clj`: extract `own-lexer!` and `own-parser!` (private) to match
  the existing structure of cst.cljs/cst.cljd — removing the duplicated 14-line
  LexerATNSimulator./ParserATNSimulator. installation blocks in parse-unit and first-failure.
- Into `src/cljc/yang/antlr/export.cljc`: add `lexer-span` `[offsets start stop]` →
  `[(offset-at offsets start) (offset-at offsets stop)]` and `export-tree`
  `[profile unit source errors ops-fn tree]` per the audit — BUT per astra's P2 amendment,
  the signature must accept the ALREADY-COMPUTED code points and offsets (pass them directly
  or as one context map), NOT bare `source` (callers compute them before parsing for the
  error listeners; do not re-compute source-sized offsets on successful parses). Preserve
  error-first behavior: a syntax-error result must NOT walk the recovered tree. Keep
  host-specific ops construction in the host files.
- **Demote in the same pass** (argon conformance amendment): `offset-at`, `export-nodes`,
  `result` → `defn-` once all four hosts (cst.clj, cst.cljs, cst.cljd, cst.jolt) call the
  new helpers — export's public surface shrinks 7 → 6 (byte-offsets, token-span,
  lexer-span, syntax-error, export-tree, failure-kind). Verify zero external callers before
  each demotion (grep src/ and test/).

### C6: eval rejection-envelope factoring (php-owned surface; the r18 landing released it)
- Into `src/cljc/yang/php/antlr/eval_transport.cljc` (2 → 4 publics): `correlation`
  `[request]` → the `[:yang.compile/request :yang.compile/unit :yang.compile/provenance]`
  select-keys projection (nil-safe: `(when (map? request) …)`), and `rejected`
  `[request reason extra]` → the rejection map construction (`:yang.compile/outcome
  :yang.compile/rejected` + reason + merged extra). Replace the 6 repeated constructions in
  `eval_admission.cljc:77-86` and `eval_coordinator.cljc:61-113` with calls.
- **Preserve EXACTLY** the special sanitization of invalid transport requests at
  eval_coordinator.cljc:46 (astra condition) and the r18-added structured-refusal payload
  with its discriminator `:yang.compile/outcome` inside `:php/fatal` (ruling
  1795028000000 Q1) — refactor the repetition only, not the payload semantics.

### Hygiene (same pass, argon/amstra list)
- `yang.antlr.export` demotions (above) and any remaining caller-less publics you can
  verify: grep before each demotion; if a function has ANY external caller, leave it.

## Mandatory validation plan

1. **Baseline capture BEFORE changes** (script in /tmp): (a) the eval rejection envelopes
   for the 6 sites' representative inputs — exact maps incl. the r18 discriminator; (b) a
   CST parse of a fixture with a syntax error AND one clean parse — the error packet + the
   exported tree + offsets (all four hosts where runnable: JVM clj + Node cljs here;
   cljd/jolt coverage comes from their lanes, note it); (c) io round-trip: compile-file +
   write-ast + read back, JVM and Node. Save to /tmp/r2b2-baseline.txt.
2. **Post-change:** identical outputs (byte/map equality). Any divergence = fix the refactor.
3. **Focused gates** (report each separately): yang.php.antlr.cst-test (discover exact name
   via ls test/yang/php/antlr | grep -i cst), the node-targeted CST/io tests if they run via
   clojure -M:cljs (build with `bb build:yin-repl-node` first; skip if the lane setup is
   heavy — note it and let the orchestrator run the Node leg), yang.php.antlr eval tests
   (eval_service_test/eval_bridge_test), yang.python.antlr.parser-test,
   yang.clojure-test, and yang.php.antlr.include-test quick rows.
4. `git diff --check` clean.

## Report format (mandatory)

Begin exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then: per-cluster summary (file:line, before/after, the seam), baseline comparisons
verbatim, per-invocation test counts, git diff --stat, noticed-but-not-done. End with:
VERDICT: DONE — <one line> | or | BLOCKED — <why>
