Created-GMT: 2026-09-21 21:30:00 GMT
Created-Local: 2026-09-22 04:30:00 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d (resumed: your DaoJing CBOR work-package thread)
# Task: jing-cbor-j0-signoff: rule on the frozen-byte ambiguities and sign off J0
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 04:30:00 +07 | Status: active | Rationale: you authored the J0 work package; J0 fixtures (claude-opus-5) were reviewed by deepseek-v4-pro and qwen3.8-max; one batched GPT turn (weekly budget about 19 percent, resets 2026-09-23 20:50 +07)

Work in /Users/sto/workspace/worktree-jing-cbor (your launch directory; branch
jing-cbor). READ-ONLY: edit nothing. BE ECONOMICAL. Give the complete answer now.

## Read

- test/resources/dao/jing/cbor-v1.README.md (provenance, DSL, ambiguities, the new
  "Rulings" section); skim cbor-v1.json only where you need bytes.
- collab/1790023590463-ref-review-qwen.findings.md and
  collab/1790023590463-ref-review-deepseek.findings.md (both READY WITH CHANGES; no
  byte disagreement; the fix round is applied: 372 cases = 209 canonical, 31 encode
  refusals, 132 decode refusals; all lanes green).
- docs/design/dao.jing.cbor.md sections "Encoding contract", "Numeric identity".

## Rule (ADOPT, MODIFY or REJECT, one line of reason each)

The orchestrator and both reviewers converge on these; a wrong frozen byte is
expensive, so you own the final say:
- A4: with-meta wraps OUTSIDE the `dao.jing/list` frame and the `dao.jing/symbol`
  frame (payload of the frame stays items-only / [ns name]).
- A5: strip only the unqualified keys :line :column :end-line :end-column, at the top
  level of every metadata map (metadata carried by metadata is treated the same way).
- A6: sequential equality (list = vector), so a set holding [1] and (1) is refused
  `equality-collapse` on every host.
- A8: an empty name is accepted (provisional).
- A9: validate BEFORE strip: a lone surrogate under a stripped key is refused. On the
  wire only invalid UTF-8 can express it; the decode fixture is refused as
  `invalid-utf8` and is the one deliberate two-defect case.
- A11: ratio with denominator 1 is accepted and retains rational kind.
- P2-1 rule: a metadata map carrying its own metadata is `malformed-frame`.
Also decide: is the with-meta tag-27 name `clojure/with-meta` (the stream profile's
name) the right frozen spelling for the Jing profile, or must Jing use its own
`dao.jing/with-meta` name? (The stream profile is a separate profile.) If you change
any frozen byte, list the exact case ids.

## Deliver

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d
then: the sign-off line (J0 FROZEN, or NOT FROZEN with the required changes), the
ruling on each item above, any frozen-byte change with case ids, and the one line you
want the README's immutability note to say. Nothing needs owner action unless you say
so.
