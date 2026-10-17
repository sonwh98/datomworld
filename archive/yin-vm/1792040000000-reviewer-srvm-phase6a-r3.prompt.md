Created-GMT: 2026-10-11 17:20:00 GMT
Created-Local: 2026-10-12 00:20:00 Asia/Bangkok

# Task: confirm 6a round 3 (adversarial, final sign-off review)

Role: Adversarial Reviewer. Worktree `/Users/sto/workspace/datomworld-srvm-p6a`, branch `srvm-phase6a`, HEAD 6b7e65ed (round 3 over bb4a6558; `git diff bb4a6558 HEAD`; the whole slice: `git diff master...HEAD`). READ-ONLY except your findings file; no git add/commit. Write each `-n ns` literally; foreground; no slow tests. Take a verified byte backup of `src/cljc/yin/vm/ucf/rows.cljc` into `collab/` before any mutant and restore from it (`cmp`).

Authors: codex (round 1, 3), claude-opus-5-5 (round 2). Prior reviews: your own round 1 `collab/1791940000000-reviewer-srvm-phase6a.claude-fable-5-1.findings.md`; codex's independent round-2 review `collab/1792010000000-reviewer-srvm-phase6a-r2.gpt-6.1-sol.findings.md` (1 HIGH, 3 MEDIUM); round-3 report `collab/1792030000000-vm-engineer-srvm-phase6a-r3.report.md`. Orchestrator verified JVM: frame-test + rows-test 30 tests / 549 assertions, 0 failures; kondo 0/0. Dart not yet run.

Check, adversarially:
1. The four findings of codex's round-2 review: marker-shaped guest maps refused in EVERY literal context (window values, task results, store values, custody, nested collections, metadata) on encode AND decode, regardless of payload validity; the explicit-marker path (caller's encoder argument) still round-trips; no legitimate value is refused that was accepted before.
2. The tagged equality representation: injective (cell vs guest literal vs vector/list/map/set/identity); the previous genuine duplicate-key refusals preserved; no change to canonical bytes: recompute the root-id probes (`(fixture :s1 :s2 :x :y)` -> :segment/blake3-be82d50b14804bb7a69103631b9c45759b49b67dbb3cebe75a1259fed6b4a937; `(frame->rows ft/worked-frame)` -> :segment/blake3-c4a7c7e6de3b44400efec5930bc3d85d86b29153b4b2d548acba630bfbc3da7d; the three-cell pin :segment/blake3-8899f95d330a174ee098e10f16ecf2d017d5aae67d4f50d8369222342e6d36e9) and confirm they are unmoved.
3. The `:label-bytes` bound (default 1048576): is the preflight really BEFORE allocation on every path (initial labels, position encodings, signatures, individualization labels, comparison vectors, class keys, leaf keys)? Reproduce the seven-cell fixture refusal at `-J-Xmx256m` (`clojure -J-Xmx256m -M:test -n yin.vm.ucf.rows-test` or the report's command) and try to find a path that still allocates unboundedly. Is the default high enough that any realistic task (the 10-cell, 20-cell cases you can construct) is unaffected? Is the refusal kind consistent with existing budget refusals? State whether the design needs an amendment (the open Architect question whether the draft section 6 seven-cell case must canonicalize instead of refuse).
4. Apply at least eight mutants to the NEW code (drop the guest/explicit distinction; skip marker refusal in one literal context at a time: store values, metadata, nested vectors; collapse the equality tags; remove each byte-bound preflight site; change the default bound to 1) and confirm each KILLED by a named test; restore.
5. Scope: only `rows.cljc` and `rows_test.cljc` changed in this round; no multi-key assoc on a vector or nil (ClojureDart); no private-var access; JVM-only code under `#?(:cljd nil :clj ...)` with :cljd first; the test that runs a subprocess with a small heap: portable on Node/Dart (guarded to the JVM?).

Write `collab/1792040000000-reviewer-srvm-phase6a-r3.claude-fable-5-1.findings.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Then findings (severity | file:line | evidence | correction), answers 1-5, the mutant table, and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
Do not claim runs that did not occur.
