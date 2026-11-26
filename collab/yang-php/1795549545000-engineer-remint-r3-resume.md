Created-GMT: 2026-11-25 (resume time)
Coding-Agent: codex (gpt-6.1-terra, jupiter POOL 2 — RESUME of thread 01a20de0)

# remint r3 — the independent reviewer REQUESTED CHANGES; resolve the 5 findings

An independent reviewer (agy gemini-4-neon-high, not your thread — independence by design)
reviewed the branch diff vs master and REQUESTED CHANGES. Findings of record:
collab/yang-php/1795565000000-reviewer-remint-independent-review.gemini-4-neon-high.findings.md
(ON YOUR WORKTREE — the seat copied it to ~/ci/dw-remint/collab/yang-php/; read it first).

## The findings (fix in this order)
1. HIGH test/resources/yang/python/dht-import-v1.edn: stale 59efa8b3... corpus (3 lines) —
   regenerate against 6e60da0c... (the manifest golden your branch re-minted), two-run
   byte-identity, then yang.python.antlr.dht-import-test green.
2. HIGH test/yang/python/antlr/linked_safepoint_test.cljc:263-264: stale pysp-manifest-golden
   5f1ba8b4... — re-mint to the actual d33e3a2c... with two-JVM verification
   (pysp-manifest-address-test green).
3. MEDIUM test/yang/python/antlr/linked_prelude_test.cljc:184-193: the provenance comment
   attributes the 71->72 change to the py/assert thunk series; the reviewer says 60cda9b6
   ("lower the assert statement") added py.b/AssertionError. Verify which commit actually
   added the key (git log -S py.b/AssertionError) and fix the comment to the true commit;
   ALSO add the membership assert: (is (contains? (set prelude/runtime-keys)
   (quote py.b/AssertionError))) per the invariant-is-the-exact-key-SET rule.
4. MEDIUM collab findings files: your r1/r2 findings claim a yin.repl REPL gate result that
   the branch state contradicts, and cite a dao.stream gate without the run being in the
   branch's custody — reconcile the findings text to the actual runs (or mark the claims as
   superseded with pointers), and remove the draft status markers now that the gates completed.
5. LOW: the provenance riders in linked_prelude_test.cljc:253-255 and
   float_address_test.cljc:227-230/258-261 should include 60cda9b6, 392746bd, 906586c5
   alongside 7692d347.

## Confirmed good (do not touch)
- data_test.cljc zipmap form (reviewer verified). The provisional python-exception failures
  are PRE-EXISTING from 60cda9b6 and deferred by the brief — not yours.
- The manifest golden 6e60da0c..., the 5 float pins, the profiles.cljc locks — verified green
  twice by the countersign; keep them.

## Bar
Re-run: dht-import-test green; pysp-manifest-address-test green; float-address +
linked-prelude 26/230/0; descriptor-profile 11/107/0; yin.repl.python-antlr-test 22/201/0.
Log custody target/remint-r3/. Findings: update your findings file with a r3 section mapping
each finding -> the fix -> the gate. No commits.
