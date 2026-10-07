Created-GMT: 2026-09-25 16:50:00 GMT
Created-Local: 2026-09-25 23:50:00 +0700
Coding-Agent: claude
Session-ID: resume-of-078d0a96-daf0-4cef-b994-076601d800d6

# Task: Rule R commit one, fix round 1 (one P1, two P2s from the codex gate)

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-25 23:50 +0700 | Status: active | Rationale: owner directive "implement with opus"; resumes your implementation session to fix the gate findings

Repository: /Users/sto/workspace/datomworld-ucf-rule-r (branch ucf-rule-r; your
uncommitted commit-one work is in the tree). Collab files:
/Users/sto/workspace/datomworld/collab/. Same constraints as your first brief
(no commit/stage/checkout/reset/stash/merge; ASCII, 80 columns, mise, TDD,
kondo via clojure -M:kondo, cljstyle, the cross-host traps).

## Owner statements (verbatim quotes)

"yes to 1-3, implement with opus"
"let's stick with B because that is clojure's convention to keep require as an ordinary function"

## The gate result (codex, independent reviewer): REQUEST CHANGES
Read /Users/sto/workspace/datomworld/collab/1790346600000-architect-yin-def-rule-r-impl-gate.gpt-6-sol.findings.md.
Codex confirmed the resolver refusal, the four definition transitions,
require unchanged, and no M2 or M4 scope creep. Its findings:

1. P1 src/cljc/yin/vm/linearize.cljc:460,481. The generic AST-datom and
   row-medium adapters accept incoming code with no contract, then pass
   vm/semantic-contract to the loader themselves, so the loader's stamp check
   cannot tell an old or unstamped external image from a fresh one; the direct
   loader tests (rule_r_test.cljc:454) do not exercise the adapters. Fix:
   require and verify the incoming AST contract BEFORE lowering; reserve
   automatic stamping for an explicitly trusted fresh-producer path. Add tests
   that go THROUGH the adapters (an unstamped input is :contract-missing, an
   old-stamped input is :contract-mismatch, a current-stamped input passes) and
   check every other place that stamps on the caller's behalf for the same
   defect, not only these two lines.
2. P2 test/yin/vm/store_write_audit_test.clj:17-28. The "exact allowlist" audit
   is a one-line regex, so a write such as (assoc-in state [:store 'yin/def] v)
   is not matched and would not fail. Fix: audit parsed store mutations, or
   broaden detection (assoc, assoc-in, update, update-in, merge, into, swap!,
   reset!, dissoc, select-keys, and a map rebuilt around :store), and add NEGATIVE
   fixtures proving those common unlisted write forms fail the audit.
3. P2 docs and query. code-as-tuples.md:1352 calls a :define key "never a
   store-slice requirement", while universal-continuation-format.md:825-829
   treats it like a :store-put key, and the extraction query at
   src/cljc/yin/vm.cljc:1727-1729 omits :define. The design already decides
   this: Fable's design says "a definition form contributes :vm/store-put
   syntactically, as a :vm/store-put row already does. The discovery result gets
   stronger, since every store key is now literal" (collab/1790341000000-
   architect-yin-def-unshadowable.claude-fable-5-1.findings.md:55). So reconcile
   TOWARD that: a :define key IS a store-slice requirement like :store-put;
   add :define to the extraction query and fix code-as-tuples 1352 to agree
   with the UCF text. Add a test. If you find a real reason this conflicts with
   the dependency-completion behavior you changed (:define contributes no
   effects), report it instead of guessing.
Also correct the stale "r3" in the UCF header at line 4 (r4 amendment at line 24).

## Verify
Rerun all three lanes sequentially and solo under mise (JVM, Node, Dart with
rm -rf test/cljd-out first), kondo on every changed file, cljstyle. Report
exact counts against your last figures (JVM 2,045 / 180,913; Node 1,960 /
47,956; Dart 1,922). If anything else in the tree passes an implicit contract
on the caller's behalf, list it.

Write your report to
/Users/sto/workspace/datomworld/collab/${TS}-vm-engineer-yin-def-rule-r-commit-one-fix1.claude-opus-5-5.report.md
(same header fields) and return it as your final response.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
