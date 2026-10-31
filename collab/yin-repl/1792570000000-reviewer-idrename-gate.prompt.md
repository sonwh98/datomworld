Created-GMT: 2026-10-18 14:10:00 GMT
Created-Local: 2026-10-18 21:10:00 +07 (+0700)
Coding-Agent: claude-deepseek-flash

# Task: the frontend-id rename landing gate (read-only)

WORK TREE: /Users/sto/workspace/datomworld-idrename (branch frontend-id-rename, tip f06a252e). THE
DIFF: `git diff origin/master f06a252e` — one commit. The owner's rulings, in sequence: ":yang.python/antlr
should be removed and the documentation should be updated"; "we have no legacy, in the system. remove
the legacy in the keyword"; ":yang/clojure, :yang/php and :yang/python are acceptable"; "(lang :python)
should not work. it should be consistent throughout"; "this is a simple search and replace. no need to
route it. do it yourself".

WHAT THE DIFF DOES:
1. The ids: :yang.python/antlr → :yang/python, :yang.clojure/legacy → :yang/clojure,
   :yang.php/legacy → :yang/php (6 code files).
2. select-frontend refuses anything but a qualified id or [id revision] — the language-keyword
   fallback and its language-ids helper and lang-labels are deleted; the shell's default lang is
   :yang/clojure; the Switched message is the id; the help text and the create-state docstring teach
   the ids.
3. The tests: every (lang :<language>) submission becomes the full id, plus refusal assertions for
   the language keywords (frontend_catalog_test's toy + standard suites, repl_test's three sites,
   the python_antlr php selector, the dht legs' ids).
4. The docs: yin.repl.md (the catalog, the example (lang :yang/python) → "Switched to :yang/python")
   and yang.antlr.md's 8.5.6 amendment (the convention + the owner's ruling recorded).

VERIFY:
1. No :yang.python/antlr, :yang.clojure/legacy or :yang.php/legacy remains in src/ or test/ (grep).
   The docs' historical prose (the M3 retirement record) keeps its past-tense legacy mentions —
   append-only history, acceptable.
2. The refusal path: (lang :python) → the shell's "Error: unavailable-parser" line; the vector form
   [id revision] still selects; the unknown-id path unchanged.
3. The default: :lang defaults to :yang/clojure everywhere (create-state, the help, the docs) —
   grep for any remaining language-keyword default.
4. The pins: no fixture/golden/manifest address in the diff (git diff --stat + grep the test
   resources) — the frontend id must not feed module hashes.
5. The blast radius: repl.cljc's lang branch, select-frontend, and the deleted helpers — is anything
   else referencing lang-labels/language-ids (grep)? The demo cljs's own :lang case values are its
   internal state, not the catalog — confirm from compilation_pipeline.cljs's compile-source!.

EVIDENCE ALREADY IN (do not redo): the four focused JVM namespaces green (catalog 8/42, repl 49/254,
python-antlr 21/194, main 45/370 — 0 failures each); the JVM quick tier 4100/285545/0; kondo 0/0;
cljstyle clean; the live smoke ((lang :python) refuses, (lang :yang/python) switches, 1 + 2 → 3).
The slow tiers are running on shiva (the JVM slow tier + the full Dart suite) and jupiter (the full
Node suite with slow bodies) against this exact commit — the landing also requires their green.

Verdict: end with exactly one line "Verdict: READY (sign-off granted)" or "Verdict: REQUEST CHANGES"
with findings. Begin the final response exactly with Completed-GMT / Completed-Local.
