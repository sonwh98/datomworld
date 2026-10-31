Created-GMT: 2026-09-30 04:33:02 GMT
Created-Local: 2026-09-30 11:33:02 +07 (+0700)
Coding-Agent: claude
Session-ID: 771d73b8-33cb-4277-815e-2b72fe699c1c
# Task: $ast row relation — slice 1: yin.repl.ast-index observer and the $ast / $occ relations

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-30 11:33:02 +07 (+0700) | Status: active | Rationale: OWNER "queue the $ast row relation too"; owner selected "Commit, then dispatch $ast (Recommended)"

Work in /Users/sto/workspace/datomworld (master 2f030c66). Do not stage or commit. Run every check in the FOREGROUND;
your turn must not end while a lane runs.

GOVERNING DESIGN (read in full): collab/1790708845000-architect-repl-ast-row-relation.gpt-6-sol.findings.md.
OWNER APPROVAL (verbatim selected option): "Approve all three (Recommended)" — (1) $ast/$occ supplied by the bridge
only when named in :in; (2) in-memory session projections, same under :current and :history, not published to
dao.jing; (3) rules stay opt-in.

THIS SLICE = design section 1 + 2 only ("observer and relations"). NOT this slice: the query bridge ($ast/$occ in :in,
slice 2) and the end-to-end REPL query tests (slice 3). Build it so slice 2 can read the relations and the lost/failure
status without changing them.

Scope:
- New src/cljc/yin/repl/ast_index.cljc: a separate session-owned observer on program-out beside the evaluator and
  yin.repl.index; per forwarded [root rows] packet add canonical rows to a relation keyed by node address (one row per
  distinct address: $ast) and derive [root path node] occurrences via the existing yin.vm/occurrences ($occ, one tuple
  per distinct [root path node]); identical roots/rows across programs do not multiply either relation.
- Compose, advance (after expansion, before evaluation, also when the program parks or raises), skip-drain on a failed
  round, report status, and rebuild on (reset)/(vm ...) in src/cljc/yin/repl.cljc, mirroring yin.repl.index's placement
  (design cites repl.cljc ~639, ~1338, ~894). The two indexers share only the stream; neither reads the other.
- Loss: a reader gap makes the AST indexer lost (counted, reported); later packets are consumed without indexing until
  (reset)/(vm); a malformed packet or derivation failure also makes it unavailable (never a partial snapshot).
  Evaluation always continues. Expose status in repl-state (VM-independent, like :index) — e.g. :ast-index.
- No dao.jing publication, no origin/clock slots in either relation.
Allowed files: src/cljc/yin/repl/ast_index.cljc (new), src/cljc/yin/repl.cljc, test/yin/repl/ast_index_test.cljc (new),
and existing yin.repl tests only if repl-state shape assertions need the new key (list each). yin.vm unchanged (reuse
yin.vm/occurrences as is); if you need a yin.vm change, STOP and report.

Acceptance (test-first where a behaviour is new; each must fail if broken; prove key ones by mutation, revert, grep):
rows and occurrences after evaluating code on all four VMs; identical rows shared across two roots with distinct
occurrences; repeated identical evaluation adds no duplicate rows/occurrences; failed expansion adds nothing; a
parking/raising program is still indexed; gap -> lost, evaluation continues, later packets not indexed, (reset)
recovers; malformed packet -> unavailable; status in repl-state identical across VMs; yang.clojure.stream-eval-test's
cross-VM repl-state equality still holds.

Portable CLJC: on CLJD #?(:clj ...) is NOT excluded — use #?(:cljd nil :clj ...) with :cljd first; no cross-ns
#'private access; no array-map (absent on CLJD).

Verify and report, in the foreground: kondo; cljstyle check (say if blocked); focused JVM (yin.repl.ast-index-test,
yin.repl-test, yin.repl.index-test, yin.repl.query-test, yang.clojure.stream-eval-test); full clj -M:test; bb test:cljs;
bb test:cljd. Write collab/1790742782000-vm-engineer-repl-ast-index-slice1.claude-opus-5-5.report.md and give it as your final
response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 771d73b8-33cb-4277-815e-2b72fe699c1c

## Fix round 1 (2026-09-30 11:59:09 +07, orchestrator) — gate findings
Gate (collab/1790744254000-reviewer-repl-ast-index-slice1-gate.gpt-6-sol.findings.md) REQUEST CHANGES; the orchestrator
accepts both:
P1 | src/cljc/yin/repl/ast_index.cljc ~59 | vm/validate-rows checks structure but not that each row id matches its
content address, so a forged row can appear as a canonical $ast fact. Fix: verify each row's content address before
merging the packet (reuse the existing address computation — yin.vm / dao.jing segment-key — whatever the encoder uses;
do NOT write a second hash scheme); a mismatch records a failure like the other validation failures. Test: a structurally
valid row with a forged id is refused and nothing merges. If verification needs a yin.vm or dao.jing change, STOP and
report the exact need.
P2 | src/cljc/yin/repl/ast_index.cljc ~56 | the duplicate check accepts two identical rows at one address because it
compares each row with the final map value. Fix: detect repeated ids while reading packet rows, before building the
map. Test: an identical-duplicate packet is refused.
The owner has already said to commit when green and then dispatch slice 2, so keep changes tight. Test first (record
failures on the current code), then fix. Same files. Run IN THE FOREGROUND: kondo; focused JVM (yin.repl.ast-index-test,
yin.repl-test); bb test:cljs; bb test:cljd. Report as report-r2.md.

## Fix round 2 (2026-09-30 12:01:06 +07, orchestrator) — OWNER ADDITION (after fix round 1 completes)
Owner, verbatim: "add the per-round warning for a lost AST indexer".
While the AST indexer is lost (reader gap) or failed (a refused packet), each round's output carries one warning line,
in the same style and placement as the code indexer's existing "Warning: ..." line (see yin.repl.cljc where the
code-index warning is emitted), naming the cause (gap vs which validation failure) and that (reset) recovers. Healthy
rounds look exactly as before; a round where both indexers are unhealthy shows both warnings in a stable order. The
warning text must be identical on every host and every VM. Test first: lost -> warning every round until (reset); failed
packet -> warning; healthy -> no warning; both-unhealthy ordering. Same files (+ yin.repl tests if the warning changes
existing expected output — list each). Run IN THE FOREGROUND: kondo; focused JVM (yin.repl.ast-index-test,
yin.repl-test, yin.repl.index-test); bb test:cljs; bb test:cljd. Report as report-r3.md.
(Pointer correction: the code indexer's warning text is built by yin.repl.index/notice (src/cljc/yin/repl/index.cljc) and appended to the round output in yin.repl.cljc; mirror that.)

## Fix round 3 (2026-09-30 12:49:25 +07, orchestrator) — Architect sign-off review finding
Architect review (collab/1790744254000-reviewer-repl-ast-index-slice1-gate-r2.gpt-6-sol.findings.md) withheld sign-off;
the orchestrator accepts:
P1 | src/cljc/yin/repl/ast_index.cljc ~70 | dao.jing/segment-matches? accepts two rows with the same address whose scalar
metadata differs (yin.vm.macro's validator at src/cljc/yin/vm/macro.cljc ~129 explicitly checks this case). Removing
the held-row conflict check in round 2 lets a later packet silently replace the $ast row at that address.
Correction: compare incoming rows with held rows (and within the packet), including nested metadata, before merging;
fail the indexer on a conflict (restore :address-conflict or equivalent); pin it with the existing metadata-collision
fixture the macro validator's tests use. Keep the P1 content-address and P2 duplicate checks.
Test first (record the failure on the current code), then fix. Same files. Run IN THE FOREGROUND: kondo; focused JVM
(yin.repl.ast-index-test, yin.repl-test); bb test:cljs; bb test:cljd. Report as report-r4.md.
