Created-GMT: 2026-10-09 19:30:00 GMT
Created-Local: 2026-10-10 02:30:00 Asia/Bangkok

# Task: Round 3 -- canonical numbering over alias topology, and the remaining gaps

Role: Lead System Architect (continuing thread 01a121aa-4d3b-7bb1-90d7-eeb2b04e5d75)

All eight round-2 findings and both editorial corrections were taken;
answers 7-9 adopted. New HEAD is one commit on top of `e0c843c9`. Run
`git diff HEAD~1 HEAD` and re-read §§2, 3, 4, 5.1, 5.3-5.5, 6, 8, 9, 10
of `docs/design/yin.vm.ucf-transport-tuples.md` from disk.

What changed, per finding:

1. §6 rewritten. Canonical numbering is now DEFINED as the least root
   encoding over all bijective cell renamings (automorphic renamings
   tie by construction, so "immaterial" is by definition). The
   ALGORITHM is colour refinement over the reference graph (signature =
   body, then iteratively the multiset of reference positions under the
   current classes) followed by individualization-and-refinement on the
   smallest residual class, taking the least root bytes. Your
   counterexample ('a -> #{X Y}, 'b -> X) is cited as the reason local
   rules fail; under refinement X and Y separate at round 1 because X
   has an extra reference position. Skeletons keep full value-union
   tags. Context lookups are not expanded during refinement.
2. §8 step 3 "Canonical form": sorted-slot order, key uniqueness under
   canonical bytes AND portable decoded equality, one spelling of
   optional absence, reference-free collections must be [:lit], empty
   collections are literals, admissible [:row B] target kinds per
   position (§5 preamble).
3. §8: reified pc must be a :current-continuation successor (also §5.1);
   install response receiver-verification spelled out (§5.4);
   correlation AGREEMENT not presence (§5.3, step 8); requires and code
   map must agree exactly with the recomputed census (step 9); census
   reachability through task roots excluding census edges (step 5).
4. §5.5 custody table by role x kind x policy, both halted arms, and
   the projection of the explicit fork slot onto the amendment's
   empty-header fork.
5. §4 literal domain includes Jing Decimal and Rational carriers; a
   separate structural-data domain D for install responses, envelopes,
   op/link/call ids; those slots retyped D in §5.3/5.4.
6. §4 metadata is walked for references; reference-bearing metadata
   refused by path; stripped reader metadata distinguished.
7. §3 wire unit {:yin.k/handoff :yin.k/version :yin.k/root :yin.k/rows};
   duplicate ids refused before traversal; bounded visited-set gate walk
   over install crefs only; malformed vs unsupported distinguished;
   semantic-only support declared (§2); digest dispatched on the
   address's multihash.
8. §9 round trip restricted to successful lowers under a fresh-receiver
   reconstruction context (counter 0; custody supplied from the restored
   record); legacy normalization partial with named refusals; direct
   version-aware lowering of 0-2 per your answer 8.
Editorial: :noncanonical-cells is a lower-side :yin.k/undecodable kind
only; :reason-mismatch likewise; §11 count corrected.

Open question added (§10.10): a receiver work bound for §6's
individualization on adversarial symmetric inputs.

Check, read-only:
A. §6: is the definition well-formed and is the algorithm correct
   against it? Does refinement with reference-position multisets
   actually separate your counterexample, and is there a counterexample
   it does not separate that is NOT an automorphism? Is "terminates in
   at most n rounds" right for the refinement phase?
B. Each of the other corrections does what the finding asked without a
   new conflict with UCF §7.2.1, §7.4.3 r5, §7.5, §7.6, amendment
   §§1, 2, 3, 6, 7, 8, or the six invariants.
C. §8: is the sequence now sufficient for step 12 to be safe?
D. Anything in the applied text that is a new owner-level decision
   rather than an inherited requirement.
E. Your view on §10.10.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: severity | file:line | evidence | correction, for anything
remaining; the properties that passed; and end with one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED (merge publishes a Draft).
