Completed-GMT: 2026-10-03 19:59:14 GMT
Completed-Local: 2026-10-04 02:59:14 +07

**CONFIRM-WITH-CHANGES — retain checkpoint-unavailable suspension; make variant equivalence normative; relax the unsupported install phase pairing; complete diagnostic classification; correct the unknown-acceptance wording.**

1. **Inherited IDs: confirm `:suspended`, with clarification.** At [§7.7.8:2004–2013](/Users/sto/workspace/datomworld-ucf-b/docs/design/yin.vm.universal-continuation-format.md:2004), unavailable evidence is not evidence of an invalid ID. Suspension is correct.

   This suspension occurs **after tenure checking**; epoch exhaustion remains an earlier suspension. A stale holder therefore cannot turn its stale outcome into checkpoint-unavailable suspension. Failure to fetch a checkpoint must not quarantine or otherwise mutate the occurrence. A legitimate holder can lose availability if authoritative checkpoint storage becomes unavailable; that is a composition durability obligation, not grounds for accepting an unverified ID.

   Replace the final sentence with:

   > For an inherited id, if the authoritative checkpoint cannot be read and verified, admission answers `:suspended` without changing tenure, quarantine, or dedup state. The checkpoint is selected by the authority's accepted record, never by an envelope-supplied location. Current-occurrence ids require no inherited-membership lookup.

   **N1: agree, and strengthen.** Variants cannot change pending IDs, their associated intents, or the root sequence counter. Equal ID sets alone permit a variant to substitute a different pending payload. Add:

   > Snapshot variants of one occurrence must preserve its operation baseline: the root next-operation sequence and the mapping of retained operation ids to canonical intents, including child operations. The authority refuses a conflicting variant; an unavailable comparison suspends variant admission.

2. **Install phase pairing: correct it.** The pairing at [§7.4.3:916–921](/Users/sto/workspace/datomworld-ucf-b/docs/design/yin.vm.universal-continuation-format.md:916) is not established by `advance-install`.

   [engine.cljc:1222](/Users/sto/workspace/datomworld-ucf-b/src/cljc/yin/vm/engine.cljc:1222) updates the phase **after** running the child; a halted child normally proceeds immediately into validation/linking rather than becoming the defining `:running` state. `start-install` initially labels the child `:running` ([line 1255](/Users/sto/workspace/datomworld-ucf-b/src/cljc/yin/vm/engine.cljc:1255)). These scheduler labels do not prove the proposed exclusive phase/body-kind relationship.

   Use this replacement:

   > A live install entry carries phase `:running` or `:parked`. Its child independently satisfies the handoff grammar and quiescence requirements, with kind `:blocked`, `:parked`, or `:halted`. The phase records scheduler progress; it does not substitute for validation of the child's actual machine state. A runnable, non-quiescent child refuses export. Lower preserves both fields, and the scheduler continues from the restored child without rerunning initialization. Other install phases are not exportable live-child entries.

   **N2: allow `:running` with a blocked child.** Do not introduce a stricter relationship until a scheduler normalization invariant is specified and proved. D should test these combinations, including explicit park.

3. **Diagnostics: confirm the separate map and composition wiring; expand the definitions.** The four categories at [§7.7.8:1899–1903](/Users/sto/workspace/datomworld-ucf-b/docs/design/yin.vm.universal-continuation-format.md:1899) can suffice, but currently omit explicit treatment of missing authentication and non-canonical intent.

   Replace that bullet with:

   > The closed defect set is `:malformed` for invalid envelope structure, unsupported envelope dispatch/version, invalid numeric fields, or intent that cannot be canonically encoded; `:unbound-lease` when no valid binding establishes the claimed incarnation; `:wrong-author` when attribution is absent, invalid, or does not identify the bound holder; and `:foreign-op-id` when authoritative scope validation fails.

   An “unenrolled writer” requires precision: enrollment belongs to the **target boundary**, while writer authorization comes from attribution and binding. An unenrolled boundary cannot run this admission protocol or claim its guarantee.

   The diagnostic adds nothing to lease facts or DaoStream outcome maps. Nesting untrusted claims is appropriate, but readers must dispatch on the diagnostic key, not merely notice an ID.

   **N3: composition wiring is acceptable.** Add:

   > The composition supplies the diagnostic stream explicitly. Failure or backpressure while publishing a diagnostic never permits the rejected effect or creates an admission outcome; diagnostic publication failure remains an explicit driver outcome.

4. **One additional inconsistency remains.** [§7.7.8:2026–2029](/Users/sto/workspace/datomworld-ucf-b/docs/design/yin.vm.universal-continuation-format.md:2026) says unknown-effect transport failure means “nothing is recorded.” A remote admission may already have committed its durable record.

   Replace with:

   > Unknown transport acceptance establishes neither commitment nor absence of commitment. The writer retains the id and retries through the fenced admission boundary. The authority may already hold a committed result, which the retry must replay. An external effect whose uncertainty cannot be reconciled within that atomic boundary is outside this guarantee.

No other new inconsistency found in this confirmation pass. No files changed or suites run.