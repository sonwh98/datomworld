Created-GMT: 2026-09-26 00:23:38 GMT
Created-Local: 2026-09-26 07:23:38 +0700
Coding-Agent: glm
Session-ID: resume-of-f234a52a-3ca3-4b49-80f4-566ca39afcea

# Task: authority policy fix round 1 (the small items from the DeepSeek gate)

Role: VM Runtime Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-26 07:23:38 +0700 | Status: active | Rationale: owner directive "fix the small items first with glm"; resumes your authority-policy session

Repository: /Users/sto/workspace/datomworld-ucf-authority (branch ucf-authority, base 0fc931fc;
your uncommitted work is in the tree). Collab files: /Users/sto/workspace/datomworld/collab/.
Same rules as your first brief: TDD (failing test first), mise, JVM lane only (the orchestrator
runs Node and Dart), kondo via mise exec -- clojure -M:kondo, cljstyle, pure ASCII and <= 80
columns on every line you add or edit, no em dashes, no commit, no stage, no checkout, no reset,
no stash, no merge. Be efficient: GLM's weekly budget is small; keep the report tight.

## Owner statements (verbatim quotes)

"fix the small items first with glm"
"authorize deepseek"

## The gate result: READY, GRANTED (DeepSeek); fix these small items first
Read /Users/sto/workspace/datomworld/collab/1790381890403-architect-linker-authority-gate.deepseek-v4-pro.findings.md.
1. P2 authority.cljc lines 36-38 (used near lines 50 and 54): the private segment-address? only
   checks that a value is a keyword in the "segment" namespace, a weak copy of the public
   dao.jing/segment-address? (src/cljc/dao/jing.cljc near line 349), which validates the
   algorithm id, digest length and hex digits. So :segment/fake passes the shape gate as a
   well-formed :yin.module/manifest or :yin.module/of. Delete the private defn and call
   jing/segment-address? at both sites. Write the failing test FIRST: an assertion envelope whose
   manifest is :segment/fake (and one whose retraction :yin.module/of is :segment/fake) is
   :malformed-envelope; a real segment key from jing/segment-key still passes. Check that every
   existing test still uses real addresses.
2. P3 two untested section 8.2 edges. Add (a) a test that a principal declared with NEITHER proof
   kind cannot assert anything (:no-proof-kind; assert the exact discard kind and that no name is
   honored), and (b) a test for the multi-asserter single-address success: two declared
   principals each properly prove an assertion of the SAME name to the SAME manifest address, so
   the name resolves :ok to that one address with both asserters named in the provenance (assert
   the provenance vectors, including that proof kinds are collapsed by distinct).
3. Record the defensible interpretive choices in the spec, as one plain sentence each, in section
   8.2 ONLY (no other section, no other file; keep the existing wording style): (i) an authority
   declaration carries :seq-floor, the highest sequence already honored for that principal;
   (ii) the result carries :honored-seq per principal, the highest sequence honored in this
   snapshot, which the composition declares back as the next :seq-floor; (iii) the three shape and
   declaration diagnostics beyond the four discard kinds: :malformed-envelope (an envelope that
   fails the shape gate), :undeclared-principal (an author the authority map does not declare) and
   :no-proof-kind (a principal declared with neither proof kind); (iv) on equivocation the pair
   and every later envelope of that principal are discarded with kind :equivocation. Match the
   names and behaviour of the code exactly; if any of the four differs from the code, fix the
   sentence, not the code, and tell me.
Do NOT change any behavior beyond item 1, and do not touch linker.cljc or any other file.

## Verify
Run the JVM lane (before your first edit for a baseline, and after): last figures 2,064 tests /
181,135 assertions. kondo and cljstyle on the two changed clj files; 80 columns and ASCII on the
spec edit. Report exact counts.

Write your report to
/Users/sto/workspace/datomworld/collab/1790382218543-vm-engineer-linker-authority-fix1.glm-5.3.report.md
(same header fields) and return it as your final response.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED - <reason>
