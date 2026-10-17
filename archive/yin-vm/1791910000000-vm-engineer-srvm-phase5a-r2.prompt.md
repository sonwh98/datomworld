Created-GMT: 2026-10-11 07:10:00 GMT
Created-Local: 2026-10-11 14:10:00 Asia/Bangkok

# Task: slice 5a round 2 -- close the reviewer's blocking gap (continuing your thread)

Worktree `/Users/sto/workspace/datomworld-srvm-p5a`, branch `srvm-phase5a`, HEAD 8df1d117 (your slice, committed by the orchestrator). Do NOT `git add`/commit. TEST-ONLY unless noted: `derive.cljc` must stay byte-identical except the optional one-token change in item 4 (report it explicitly). Read the review: `collab/1791870000000-reviewer-srvm-phase5a.claude-fable-5-1.findings.md`. Take a verified byte backup of `derive.cljc` into `collab/` BEFORE applying any mutant and restore from it (`cmp` and `git diff --quiet HEAD -- src/`).

1. BLOCKING (finding 1): mutants X13 (`:ffi-call` operand pushes omitted, derive.cljc:130) and X14 (`:stream-put` operands swapped, :132-133) survive. Add a NEW deftest (keep `stack-goldens`' ten rows and meaning intact) pinning two hand-derived goldens from design §3.2: an `:ffi-call` with two operands, e.g. `[[:const 1] [:push] [:const 2] [:push] [:ffi-call :op/echo 2] [:halt]]`, and a `:stream/put` with `[[:const :s] [:push] [:const 1] [:stream-put] [:halt]]`; DERIVE them yourself from §3.2 first, report if any differs from the examples. Also add two runtime rows that reach a value on BOTH pipelines under the existing oracle: the ffi row under an explicit `{:op/echo (fn [a b] [a b])}` bridge, and a put onto a made stream `{:type :stream/put :target {:type :stream/make :buffer 4} :val (lit 1)}`; keep the asserted counts consistent (state the new counts). Apply X13 and X14, confirm each is KILLED by a named test, restore.
2. Non-blocking, take them (test comments/wording only): finding 3 (point the non-vacuity assertion at `(derive/resolved-addresses (dr/resolve (vm/ast->datoms ast)))` so it can fail), finding 4 (reword the `stack-corpus` docstring claim you cannot prove).
3. Finding 5 (hard-coded `'yin/def` at derive.cljc:81 vs `vm/definition-operator`): change that one token ONLY if it needs no new require beyond what the namespace already has; otherwise leave it and say so.
4. Finding 2 is a design-text defect (§6.1.3 omits the `list`/`vector`/`inc` primitives the twelve value rows need); record in the report that you kept the minimal extension; the orchestrator fixes the design.

Verify, foreground, literal `-n` flags: JVM for derive-test plus the semantic-register namespaces as in your first report; Node as in your first report; kondo and cljstyle; Dart attempt as before (NOT RUN if blocked).

Report to `collab/1791910000000-vm-engineer-srvm-phase5a-r2.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per item: change, mutant killed by which test, exact lane counts. Do not claim runs that did not occur.
