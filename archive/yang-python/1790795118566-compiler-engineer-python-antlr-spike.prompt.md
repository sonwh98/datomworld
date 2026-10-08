Created-GMT: 2026-09-30 19:05:18 GMT
Created-Local: 2026-10-01 02:05:18 +07 (+0700)
Coding-Agent: claude
Session-ID: 976059c2-629b-4b91-8b63-7c4d9604d7cf
# Task: Python ANTLR spike, phase A — parser interpreter, CST export, scope analysis, lowering to Universal AST, prelude

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-01 02:05:18 +07 (+0700) | Status: active | Rationale: compiler lowering spike; owner-directed parallel dispatch

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-py-spike (branch yang-python-antlr-spike). Base is a LOCAL snapshot
commit 77ad1697 of the uncommitted D4 change (host-typed effects). Two parallel engineers are building what you depend
on, NOT present in your base: cell slice 1 (cell/new, cell/get, cell/set!, sealed :cell-ref, task :heap) and the pure
`data` host module (count, nth, contains?, dissoc, disj, peek, pop, subvec, hash-set, into, code-point string ops). Code
against those names; do not implement them yourself. Keep all your code in NEW namespaces (e.g. yang.python.antlr.*)
plus deps/build wiring; do not edit yin.vm engine/module/VM files, yang.python (legacy stays selectable), or
yin.repl's dispatcher. Do not touch other worktrees. Do not stage or commit.

OWNER (verbatim): "dispatch the python spike in parallel too". Owner direction (verbatim, binding):
- "integrating antlr should be straight forward. antlr will construct an AST that gets mapped to yin.vm universal AST.
  The Unversal AST is clear and small and the mapping from antlr AST to it should be a straight forward but tedious mapping"
- "if yin.vm universal AST has continuations, all control flow can be mapped to continuations"
- "compilers have a pipeline of transformation. yang/yin.vm compilation pipeline is dynamic where interpreters read from
  dao.stream and make transformation onto another dao.stream. any number of interpreters can attach to those dao.stream
  to do more transformation of its own"
Governing decisions (copies in this worktree's collab/; read): mob D5 and D10
(1790776815400-architect-mob-outstanding-decisions.*.findings-r2.md), cell ruling
(1790773810605-architect-cell-primitive...), mutable objects ruling (1790778866412-architect-mutable-objects...).
Design context: docs/design/yang.antlr.md §1.1, §4-§5, §8.1, §8.5 (read, but the spike is deliberately smaller: no
parse service, no request/attempt identities, no admission ledger). Captured continuations are invocable (commit
8f9f90b0; src/cljc/yin/vm/docs/co-routines.md).

Build — a stream topology, each stage an interpreter reading one dao.stream and writing another:
1. Build: add the ANTLR 4 Java runtime (pinned) to deps.edn; generate the grammars-v4 Python3 parser (pinned revision;
   record URL, revision and digests) at build time reproducibly (a bb/clj task), JVM target only, including its Java
   lexer base helpers for INDENT/DEDENT. Keep generated/vendored files out of cljstyle/kondo scope; check license notice.
2. Parser interpreter (JVM host, .clj): reads sealed source units from a stream, parses with ANTLR, writes one CST packet
   per unit to a CST stream: flat preorder records {id, rule-name, children ids, byte span [start end)} + terminal
   records (token type name, span), plus syntax-error records. Generic: no Python-specific code in the exporter; no
   ANTLR object ever leaves the stage. Syntax errors produce error records, never partial programs.
3. Lowering interpreter (portable .cljc): reads the CST stream, writes Universal AST (canonical map AST / the program
   batch shape the evaluators already consume; see test/yin/vm/test_utils.cljc) to a program stream. Required phases:
   binding collection / scope analysis (locals, params, global, nonlocal), then lowering, one `case` arm per grammar
   rule; unknown/unhandled rules FAIL with a qualified error naming the rule (never silently dropped). Deterministic
   name supply (no gensym).
   Lowering rules (decided):
   - every function-local binding AND parameter is a cell: cell/new at function entry (params initialised, other locals
     an explicit unbound sentinel); reads cell/get (unbound -> Python UnboundLocalError); assignment cell/set!;
     module-level / `global` names via (yin/def name v).
   - control flow via continuations: `def` body captures a return continuation; return/break/continue/raise invoke
     captured continuations; loops are recursive lambdas with break/continue continuations; try/except captures a
     handler; raise invokes the innermost handler (handler stack in a cell or hidden param — your choice, justify).
     finally: implement if simple, else reject with a qualified unsupported diagnostic.
   - mutable objects: one cell per object holding a tagged persistent value; identity = the ref; list = vector;
     dict = ordered (index map + order vector) with normalized scalar keys (1, 1.0, True are one key); simple classes
     with single inheritance; instances hash by identity. Never iterate a host map.
   - operators/truthiness/equality: Python semantics in a PRELUDE written as Universal AST (py/add, py/truthy, py/eq,
     ...), not Clojure semantics. Numbers-only arithmetic plus str concat is enough for phase A.
4. Supported subset (reject everything else with a stable diagnostic): literals (int, float, str, True/False/None),
   arithmetic/comparison/and/or/not, if/elif/else, while, for over list/range, def/return/lambda/closures/nonlocal/global,
   break/continue, try/except/raise (simple), list/dict literals + index/assign + len + append, simple classes with
   __init__ and methods, print as an effect-free value collector or skip.

Tests:
- CST export golden tests (rule names, spans in bytes, errors as records) — runnable now on JVM.
- Scope-analysis tests (locals/params/global/nonlocal, forward capture) — runnable now.
- Lowering golden tests (UAST shape for representative programs; unknown rule -> qualified error) — runnable now.
- End-to-end tests (source -> parse -> lower -> evaluate on all four VMs): WRITE them now; they need cell slice 1 and
  the data module, which are not in your base. Mark them clearly (e.g. a separate namespace listed in your report) and
  report exactly which are expected to fail until those land. Do not stub cell/* or data/* to make them pass.
- Chunk invariance: different source chunk boundaries give identical CST/UAST.

Verify — run EVERY command in the FOREGROUND (never background): clj -M:kondo --lint on each changed .clj/.cljc
(separate arguments); cljstyle check on each (say if blocked); the generation task from clean; focused JVM tests; full
clj -M:test; bb test:cljs (portable lowering namespaces must compile on CLJS; parser stays JVM-only). NOT bb test:cljd.

Write the report to /Users/sto/workspace/datomworld-py-spike/collab/1790795118566-compiler-engineer-python-antlr-spike.claude-opus-5-5.report.md
and give it as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 976059c2-629b-4b91-8b63-7c4d9604d7cf
Report: changed/new files, grammar pin + license, the stream topology as built, the rule->UAST mapping table (rules
handled, rules rejected), prelude contents, exact test outcomes (which e2e tests await cells/data), and — per the owner's
claim that the mapping is "straight forward but tedious" — an honest note of which constructs were mechanical and which
were not. Do not claim edits or tests that did not occur.

## PHASE B (orchestrator, resume of the same session)
OWNER (verbatim): "dispatch spike phase B in parallel now"; then "actually it can wait if spike 8 is dependent on
something else" (orchestrator reading: phase B waits for its dependencies — so it now runs on them).
BASE NOW: your phase A files were moved (unchanged, uncommitted) onto local commit fe8bce4a = master 5e790683 (D4
host-typed effects, cell slice 1) + the data module commit (yin.vm.data). Real names:
- cell: yin.vm.module/register-cell-module; exports cell/new, cell/get, cell/set!.
- data: yin.vm.data/register-data-module (NOT yin.vm.module); exports data/count, data/nth, data/contains?, data/dissoc,
  data/disj, data/peek, data/pop, data/subvec, data/hash-set, data/into, data/str-concat, data/str-length,
  data/substring, data/str-index-of, data/str-split, data/str-join, data/char-at, data/str->code-points,
  data/code-points->str, data/str-compare. Your guesses data/code-points and data/from-code-points are WRONG: use
  data/str->code-points and data/code-points->str. Read src/cljc/yin/vm/data.cljc for semantics and refusal shapes.
Architect ruling to apply (copy in this worktree's collab/):
1790797984227-architect-python3-mappability.claude-fable-5-1.findings.md. OWNER DECISIONS (verbatim: "accept all
recommendations"), recorded as:
 (1) a Python module namespace is a dict object in a heap cell; global reads/writes go through it; yin/def is reserved
     for prelude and builtins (fixes globals()/del/exec-with-namespace/setattr(module); undefined global -> NameError);
 (2) traceback line numbers are derived at the boundary from position side tables by default (no guest-visible lines
     in phase B);
 (3) the Python value encoding tags floats (ints untagged); add print(4/2) -> "2.0" to the Node/all-host parity set;
 (4) safepoints are a separately attached interpreter — do NOT add them to the lowering in phase B.
Phase B work:
 a. Fix the two data-module names and the data registrar location; wire e2e-test/host-registrars to the real fns.
 b. Module namespaces as heap dicts per (1); NameError on miss.
 c. Replace py/kont? with a per-capture flag cell (fable: allocate (cell/new :first) before %capture; set :re-entered on
    the first pass) — no test of the continuation's representation anywhere in the prelude.
 d. Float tagging per (3), incl. truediv, arithmetic promotion, equality 1 == 1.0, printing, dict key normalization.
 e. Function objects in cells (arity check -> TypeError; __name__; identity) — defaults/*args/**kwargs may stay
    rejected if not simple; say which.
 f. Run ALL 21 e2e tests (plus any new) against the REAL cell and data modules on all four VMs; nothing stubbed.
Run EVERY check in the FOREGROUND: kondo (files as separate args), cljstyle check per file (say if blocked), bb
gen:python-antlr from clean, focused JVM, full clj -M:test, bb test:cljs. Not bb test:cljd.
Append a "Phase B" section to your report and give the full report as your final response (same header, Session-ID
976059c2-629b-4b91-8b63-7c4d9604d7cf).

## PHASE B ROUND 2 (orchestrator) — gate REQUEST CHANGES + CLJD compile failure + owner decisions
Gate (copy in this worktree's collab/): 1790800316849-reviewer-python-antlr-spike-gate.gpt-6-sol.findings.md, thread
01a0f404-65c3-78c3-8c38-8bbd38719685. Orchestrator: cljstyle fixed 4 files (whitespace); kondo 0/0; lanes on your phase B
code: JVM 2635/186960/0, Node green, CLJD FAILED TO COMPILE:
  compiling namespace yang.python.antlr.lower: "Map literal must contain an even number of forms" at line 265 col 67 —
  lower.cljc:264 `{:py/float #?(:clj (Double/parseDouble t) :cljs (js/parseFloat t))}` has no :cljd branch, so on CLJD
  the conditional reads as nothing. Repo rule: every reader conditional in portable code needs a :cljd branch, listed
  FIRST (a :cljd branch in tail position silently fails).
Fix:
1. CLJD: add the :cljd branch (first) at lower.cljc:264 and sweep EVERY .cljc you added (stage, uast, scope, lower,
   prelude, render, packet, and the .cljc tests) for reader conditionals lacking a :cljd branch or with :cljd not first;
   fix each; list them in the report. (CLJD lane is the orchestrator's; make the code correct by reading.)
2. P1 lower.cljc:364 / prelude.cljc:165: a module-assigned name must fall back to builtins at run time when the module
   dict has no key yet (print(len([])); len = 1 prints 0); a present module key wins. Test it.
3. P2 scope.cljc:238 / lower.cljc:361: class-body name reads check the class namespace at run time, then fall back to the
   enclosing (module dict / builtins) lookup (x = 1; class C: print(x); x = 2 prints 1). Test it.
4. OWNER DECISION (verbatim option chosen): "Vendor .g4 only (Recommended)" — "Commit the two MIT-licensed grammar files
   (with their notices); keep fetching the two unlicensed Java helpers by pinned digest until their provenance is
   resolved." Vendor Python3Lexer.g4 and Python3Parser.g4 under antlr/python3/ (outside cljstyle/kondo scope), keep their
   MIT headers verbatim, have bb gen:python-antlr use the vendored copies (still verifying their digests) and fetch only
   the two helpers; update the manifest (record the helper licence as unresolved, provenance needed before distributing
   generated artifacts).
5. OWNER DECISION (verbatim option chosen): "Accept for now (Recommended)" — per-unit prelude stays for the spike; note it
   in the report as phase C work. No code change.
Run EVERY check in the FOREGROUND: kondo (files as separate args), cljstyle check per file (say if blocked), bb
gen:python-antlr from clean, focused JVM, full clj -M:test, bb test:cljs. Append a "Phase B round 2" section; give the
full report as your final response (same header).
