Created-GMT: 2026-10-04 07:30:00 GMT
Coding-Agent: claude (fable-5-1, resume of session ff5b8c32-5cb0-4d81-9476-0a88c6109319)

# Task: rule on the C4 checkpoint-inspector engineer's open questions (read-only; final response is the deliverable)
Role: Architect

Engineer report: /Users/sto/workspace/datomworld/collab/1791097800000-compiler-engineer-ucf-c4-checkpoint-inspector.claude-opus-5-5.findings.md (read the "Unresolved questions" section, all 16). Code is uncommitted in /Users/sto/workspace/datomworld-c4inspect: src/cljc/yin/vm/ucf/checkpoint.cljc, test/yin/vm/ucf/checkpoint_{fixtures,test}.cljc, test/resources/yin/vm/ucf/checkpoint-v1.txt (514 KB of hex fixtures).
Rule on EACH question: accept the engineer's choice, or amend with the exact replacement, citing UCF 7.2.1/7.7.x/7.9/7.11.1 text and the plan you wrote. Treat these with priority and decisiveness:
1. Codec: the UCF says canonical dao.jing.cbor but the landed v0 handoff encodes with dao.stream.cbor. State what version-1 export/inspection must use, and whether the inspector is correct as built.
2. Address form (bare BLAKE3 hex vs :segment/ keyword) and the hash-mismatch status name: pick the one the admission outcomes (7.9) and D will share.
3. Intent payload: the baseline stores the encoded intent as carried in the body; admission computes intent from the decoded program value. Which must be the dedup comparand, and does the inspector change?
4. The 514 KB hex fixture file: is that size acceptable to commit, or must it shrink (what makes it large? say what to cut or generate instead, e.g. build fixtures from a small seed at test time)? Ruling needed; a repo-weight decision.
5. Fixture authority: accepted fixtures are hand-built with placeholder code. Should D regenerate them through a real version-1 export when D exists, and what must the inspector tests pin meanwhile.
Then list which amendments (if any) the engineer must make now, as a precise checklist, and which are deferred to D or to document text. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
