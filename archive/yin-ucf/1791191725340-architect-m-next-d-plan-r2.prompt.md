Created-GMT: 2026-10-05 09:16:00 GMT
Created-Local: 2026-10-05 16:16:00 +0700
Coding-Agent: claude (fable-5-1, resume of session ff5b8c32-5cb0-4d81-9476-0a88c6109319)
Session-ID: ff5b8c32-5cb0-4d81-9476-0a88c6109319

# Task: reconcile the M-next D plan to r2 against the adversarial review (read-only; the revised plan is the deliverable)
Role: Architect

Your stage-D plan (collab/1791190689957-architect-linker-m-next-d-plan
.claude-fable-5-1.findings.md) was reviewed adversarially by the second
architect. Verdict: REQUEST CHANGES, 12 findings. The review is at
collab/1791191261015-architect-m-next-d-plan-review.gpt-6-astra
.findings.md — read it in full.

Produce D-plan r2: for each of the 12 findings, either accept and fold
the correction into the contract and the slices, or rebut it against the
published text or the landed code (cite the section or file:line). The
reviewer's specific asks include, at minimum:

- 1: the gate's coverage of every observation path (check-wait-set's
  link-poll, install-child advance, FFI routing before the ordinary
  sweep; the child receiving the root's policy at engine.cljc:1232's
  direct vm/run); the running/exporting/ended distinction; the widened
  zero-observation test list.
- 2: held immediates retained until durably acknowledged, retried as the
  same input request; the export refusal for any task holding one; the
  operation discriminator inside :yin.k/name (input.cljc:83's accepted
  shape); portable cursor observations.
- 3: portable source identity for replay (task/child path + cursor or
  correlation id), deterministic wait selection with aliasing kept, the
  four retained states, and divergence only after all permitted internal
  computation is exhausted.
- 4: request id as correlation, not dedup; the reply-matching fields;
  the projection outcome's own authentication shape; input k
  independence.
- 5: the missing remote paths — lease renewal and the replay-prefix
  read — chosen and documented (amended front vs attributed lease-fact
  channel; authenticated ledger reader vs query requests), and
  incomplete history never read as an empty prefix.
- 6: release-after-divergence is cleanup, not recovery; release cannot
  clear quarantine or regrant; run-ending stops all program IO;
  input conflict is not effect intent conflict.
- 7: version-aware reader dispatch before checkpoint/inspect (which
  speaks version 1 only, checkpoint.cljc:50); the precedence between
  unsupported-version and body-address failures.
- 8: resource preparation split from encoding so lift is pure; ordering
  by canonical encoded keys without reordering observable waits; the
  envelope-shape claim narrowed to the driver's transport wrapper.
- 9: the abort rule per the reviewer's replacement text; the
  pre-send persisted offer-attempt intent; the holder-side tenure
  requirement.
- 10: the progress journal's recovery contract — durable references,
  write ordering relative to external actions, uncertainty stops
  execution, tenure re-established through the authority.
- 11: the memory-seam test vs the exclusivity gate; no silent downgrade
  to fork; D12 exclusions for explicit park and held immediates;
  D2 split into reviewable subrounds; D1/D3 serialized (D1 is already
  in flight under its existing contracts — keep it a standalone slice).
- 12: the REPL insertion point qualified against yin.repl.main/step-all's
  actual order (DHT step, load-event recheck, shell, serve) so custody
  control-plane work is serviced during hydration.

Keep r2 in the mold of r1: contract, slices D1..Dn with files, test
contracts, order/concurrency, E boundary, risks, doc amendments. Mark
what changed from r1 where it matters for the engineers. Begin the
final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
