Created-GMT: 2026-09-21 22:50:00 GMT
Created-Local: 2026-09-22 05:50:00 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8 (resumed: your J1 turn)
# Task: jing-cbor-j1-fix: apply the J1 reviewers' agreed findings
Role: DaoSpace & DaoJing Storage Engineer
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-22 05:50:00 +07 | Status: active | Rationale: same implementer fixes its own code (resume rule); reviewers were qwen3.8-max and deepseek-v4-pro (different families)

Work ONLY in /Users/sto/workspace/worktree-jing-cbor (launch directory, branch jing-cbor,
HEAD f5f71e95). Do NOT stage, commit, merge or push. The frozen corpus
(test/resources/dao/jing/cbor-v1.json, cbor-v1.README.md, cbor-v1.generate.py) and
cbor_fixtures_test.cljc are IMMUTABLE: do not touch them. File box: your J1 files
(src/cljc/dao/jing/cbor.cljc, cbor/boring.cljc, test/dao/jing/cbor_test.cljc,
helpers in test/dao/jing/cbor_fixtures.cljc) plus ONE new file
test/resources/dao/jing/cbor-v1.errata.md (see item 7). Same Bash notes as before.
NOTE: the orchestrator already ran `cljstyle fix` on your files, merged two redundant
lets and gated `brem` and `babs` to `#?(:cljs ...)` (kondo 0/0). Re-read the files
before editing; keep kondo/cljstyle clean by the same conventions.

## Inputs (this worktree's collab/)
- 1790026900000-ref-j1-review-qwen.findings.md
- 1790026900000-ref-j1-review-deepseek.findings.md (stdout log; the review is after
  the two banner lines)
Both are READY WITH CHANGES. Both recommend ratifying the Jing-owned reader (3a) and a
documented `:host-collapse` (3b); the ARCHITECT rules on those afterwards, so change
nothing about them beyond item 7.

## Do (in order)

1. **Depth limit (both reviewers' P2).** Thread a depth counter through `read-item`,
   `item->value` (and the frame recursion) on decode and through the wire builder on
   encode; refuse past a named constant `max-depth` (use 128; document that it is a J1
   policy pending architect ratification) with `:malformed-cbor` on decode and
   `:unsupported-value` on encode. No corpus payload nests beyond a handful of levels.
   Add tests on JVM and Node: about 10 000 nested `81` arrays, and nested `d81b` list
   frames, refuse with the class (never StackOverflowError/RangeError); depth exactly
   `max-depth` is accepted, depth `max-depth`+1 refused, on both encode and decode.
   Make the test runners catch Throwable-class failures so one bad case cannot abort a
   whole deftest (qwen P3-5c).
2. **Decimal exponent window (qwen P2-2).** Mirror the JVM window EXACTLY in the CLJS
   branch of `decimal` so both hosts accept and refuse the same exponents (refuse
   `:malformed-number` outside it). Add a decode test (an exponent just inside and just
   outside the window) that runs on both hosts and asserts identical outcomes. Record
   the window in the errata file for J2.
3. **JS safety (deepseek P3, qwen P3-3).** In the CLJS arm of `exact` (not in
   `host-integer?`) refuse integral numbers that fail `Number.isSafeInteger`, so
   `num=`, `num-hash` and `num-compare` never operate on a silently rounded value.
4. **JVM `big` (qwen P3-2).** Refuse non-integral Number input loudly rather than
   truncating. **Rational carrier (qwen P3-1).** Make `exact` reduce the ratio arm (or
   validate in the constructors) so a hand-built unreduced carrier cannot make `num=`
   and `num-compare` disagree; add a test.
5. **Keyword metadata (deepseek P3).** Verify on JVM and CLJS whether a keyword can
   carry metadata at all. If it cannot on either host, add a one-line comment saying so
   where `keyword?` is encoded; if it can, refuse it with `:unsupported-value` rather
   than dropping it silently, and test that.
6. **Test tightening (qwen P3-5).** The print-settings independence proof runs over ALL
   canonical cases, not the first 60. Tighten `collapse-not-applicable` to the merges
   you actually observed (JVM: signed-zero, decimal-scale, vector-list, integer-width;
   Node: vector-list, set-nan, map-nan) so a future regression that starts merging
   fails instead of skipping. Fix the `exact-key` docstring: the key is host-independent
   but its `hash` value is not (Murmur3 vs the CLJS scheme); hash values must not cross
   hosts. Fix the "tag -1" refusal text (deepseek P3): report the huge tag honestly.
   Decline, with a one-line reason in the report: `collapse?` worst-case O(n^2) (bounded
   by input, needs crafted hash collisions; record it in the errata file).
7. **Errata file (new, additive; the frozen README is not edited).** Create
   test/resources/dao/jing/cbor-v1.errata.md, pure ASCII, 80 columns, headed
   "Status: PENDING architect ratification", with: (a) the corrected per-host N/A
   table (JVM holds set-int-ratio-collapse, set-nan-payload-duplicate,
   map-nan-payload-duplicate and refuses; Node holds map-int-float-key-collapse,
   set-float-decimal-collapse, set-signed-zero-collapse, set-int-ratio-collapse,
   set-decimal-scale-collapse and refuses; the README table assumed raw host numbers,
   the DSL builds carriers); (b) the `host-collapse` refusal class as a J1 host-capability
   class outside the frozen 16, applying to CLJS map keys and set elements whose joined
   keyword or symbol name collides (`coll/map-both-slash-keywords`); (c) the decimal
   exponent window; (d) `max-depth`; (e) the O(n^2) note and the JS/JVM hash-value
   non-portability note.
8. Re-run: generator `--check` (must still match), focused JVM
   `clojure -M:test -n dao.jing.cbor-fixtures-test -n dao.jing.cbor-test`, and
   `bb test:cljs` if allowed. Report counts you observe. Do not report what you did not
   run.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8
then: per finding (agree, or declined with reason), files changed, the decimal window
you found, new tests, what you ran with exact counts, what you could not run.
