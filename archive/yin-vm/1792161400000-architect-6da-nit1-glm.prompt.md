Created-GMT: 2026-10-16 13:15:00 GMT
Created-Local: 2026-10-16 20:15:00 +07
Coding-Agent: glm
Session-ID: e33f308d-2ca8-4019-917d-4c85854673b4 (resume)
# Task: ruling — opus's STOPPED question on nit 1 (repeated-register agreement, plan v2.1 §3.5 item 9)
Role: Architect
Implementers:
- Model: glm-5.3 | Assigned: 2026-10-16 20:15 +07 | Status: active | Rationale: acting Architect; one design question left open by the fix round; everything else is settled

Short ruling, resume of your thread. Opus STOPPED on your nit 1: your suggested `(is (pos? @repeated))` for `layout-test/executed-ordered-regions` is unattainable — it measured the counter at 0 for all four corpus ASTs INCLUDING `(+ x x)`, because the linearizer gives every operand a fresh register (`(fn [x] (+ x x))` → `[:var 1 +] [:var 2 x] [:var 3 x] [:call 0 1 [2 3]]`), so `layout(p) ++ uses(p)` never repeats a register in any projected program. Opus applied `(is (zero? @repeated))` with a comment (strictly stronger than the old `<= 0`: a future register-reusing linearizer fails loudly) and kept the per-group agreement assertion. Read /Users/sto/workspace/datomworld-srvm-6d-a/collab/1792159200000-engineer-6da-fixes.report.md section "Nit 1" and plan v2.1 §3.5 item 9 (collab/1792145566000-architect-6d-plan-v21.report.md) if you need the clause's wording. Rule: (a) the repeated-register agreement clause is for NON-projected vectors (hand-built), so the executed pin is honestly `zero?` for 6d-a and any non-projected exercise belongs to 6e — or (b) it must be exercised now, and say what fixture/seam provides it. Also confirm or amend the `zero?` pin's comment wording. One paragraph; deliver the ruling as your final message.
