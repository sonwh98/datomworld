Created-GMT: 2026-10-02 19:40:00 GMT
Created-Local: 2026-10-03 02:40:00 +07 (+0700)
Coding-Agent: claude
Session-ID: a80b326f-0efb-4ae5-ae0f-df0154076bab

# Task: Python safepoint interpreter — slice 2 (recursion via the dynamic-context record)

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude opus (opus-5.5) | Assigned: 2026-10-03 02:40:00 +07 (+0700) | Status: active | Rationale: continuation of the safepoint line; fresh seat, slice 1 landed as cf6ed9ad

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-py-safepoint2 (branch yang-python-safepoint-s2, based on
master 69e58662, which contains safepoint slice 1 as cf6ed9ad). Do not touch /Users/sto/workspace/datomworld, the
datomworld-linker-hardening / datomworld-linker-transfer worktrees (another seat owns them), or any other worktree.
Do not stage or commit.

DESIGN (binding): read collab/1790849347715-architect-safepoint-interpreter.claude-fable-5-1.findings.md (the
safepoint design; slice order: signals, RECURSION, tracing, threads — this is the recursion slice) and the landed
yang.antlr.md 8.5.2 (the safepoint section, committed on master; the corrected RecursionError line: "An escape
restores the depth saved at capture rather than decrementing by one"). Design rulings in force: hooks are ordinary
:application rows at frontend-marked sites; the canonical tree is never modified; identity = canonical tree; the
hook prelude defines only py.sp/* names; language surface (exception classes) lives in the BASE prelude.

SLICE 2 SCOPE (design Q2 "RecursionError" row + the dynamic-context correction; nothing beyond):
1. Dynamic-context record: generalise py.rt/handlers into ONE record (handlers, depth, current frame) held in one
   cell; py/try, py/call-ec and the escapes restore the WHOLE record saved at capture (depth restored at capture,
   not decremented) — per the design's own correction. The hook prelude and the base prelude share this record.
2. Depth accounting at the :call and :return hook sites: entry increments and checks; normal exit decrements;
   the check compares against the recursion limit.
3. RecursionError as a plain builtin class in the BASE prelude's builtin-classes under Exception (the fable
   KeyboardInterrupt ruling pattern: language surface in the base prelude, raising mechanism in the hook prelude).
   On exceeding the limit, py.sp/call raises it through py/raise.
4. sys.setrecursionlimit/getrecursionlimit if it falls out simply (the limit lives in a cell the hook reads;
   setrecursionlimit validates a positive integer); otherwise refuse sys.setrecursionlimit as unsupported in this
   slice and say so in the report.
OUT OF SCOPE: tracing/:line marks, threads, signal-delivery journalling, float-bearing address goldens (the
  float64-carrier mob is converging separately — see collab/1790968830636-architect-float-address-mob.*).

ACCEPTANCE (four VMs across the JVM/Node/CLJD lanes):
- Deep recursion (e.g. a to-the-limit countdown) ends with RecursionError, caught by except RecursionError, on all
  four VMs; the default limit is honored exactly (a program recursing limit-minus-one times completes).
- try/finally in a deep frame that escapes (raise caught far above) does NOT corrupt the depth: after the unwind,
  recursion at the restored depth works and a second deep recursion to the limit still raises at the right point
  (the escape-restores-depth-at-capture property, both directions).
- Generators interacting with depth: a generator that yields from deep frames suspends and resumes without the
  depth leaking across the crossing (the dynamic-context record is saved/restored at the gen boundary).
- Transparency/identity/canonical-untouched invariants from slice 1 still hold (e2e corpus runs naive and under
  no-op hooks with identical output; prelude rows keep their ids).
- setrecursionlimit: a lower limit takes effect; an invalid argument raises the Python-level error.
- sys.settrace without a tracing profile still raises the explicit unsupported error (slice 1 behavior kept).

MECHANICS:
- Before any JVM lane run: bb gen:python-antlr (mise exec -- bb gen:python-antlr), then mise exec --
  bb build:yin-repl-node. Run lanes in the FOREGROUND one at a time and WAIT for each; if a lane cannot finish
  inside a foreground cap, say so in the report rather than leaving it unresolved. mise is trusted;
  node_modules are installed.
- Lanes: mise exec -- bb test:clj, bb test:cljs, bb test:cljd; clj -M:kondo and cljstyle check on touched files.
- No new AST tag; ordinary :application rows; canonical program untouched; do not weaken tests; if a design
  element conflicts with the landed slice-1 code, stop that element and report the conflict.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

Report changed files, exact counts per lane, unresolved concerns, incomplete work. Do not claim edits or tests
that did not occur.
