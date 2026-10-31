Created-GMT: 2026-09-15 19:23:21 GMT
Created-Local: 2026-09-16 02:23:21 +07
Coding-Agent: claude
Session-ID: 0e7e744b-6d6b-420e-aca6-a8ce9386179d
# Task: compiler-stream-pipeline-review-v4
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-16 02:23:21 +07 | Status: active | Rationale: Architect review of revised stream pipeline plan V4

Perform a read-only architecture review of the REVISED semantic bytecode migration and stream pipeline plan.

Read first:
- docs/design/datom.world.md
- docs/design/yin.vm.code-as-tuples.md 
- /Users/sto/.gemini/antigravity-cli/brain/d463453f-497a-4a75-a0bd-20cd63ac6838/vm_semantic_bytecode_migration_plan.md

### Owner's Context and Overrides for this Review:
The system owner has explicitly directed the following architectural overrides which are now reflected in the documents:
1. **Named Variables Everywhere:** We have completely purged the De Bruijn indexing mandate from the Semantic layer in `yin.vm.code-as-tuples.md`. Both the Map AST and the Semantic Tuples strictly retain Named Variables to preserve original semantics. De Bruijn computation is deferred entirely to a downstream Register VM compiler.
2. **Naming of the Encoder:** The owner explicitly rejected the generic name `project` in favor of `yin.vm/ast->semantic-bytecode`. This function performs the boundary projection. It lives in `yin.vm` alongside the legacy `ast->datoms` function it is replacing.
3. **Map AST Indexer:** Deleted entirely as per your V3 finding. The $ast relation is the only query surface for code.
4. **Occurrence Keys:** The transactor uses the composite key `[:yin.occ/key [[:source medium batch j] root-addr path]]` to prevent upsert collisions.

Evaluate if this revised plan successfully addresses all the critical defects found in the previous review (V3), taking into account the owner's explicit overrides above.

Distinguish architectural defects from implementation gaps or intentionally deferred work. Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended correction.
Also confirm the requested properties that passed review. Output your verdict as either APPROVE or REJECT.
