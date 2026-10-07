Created-GMT: 2026-09-17 07:27:02 GMT
Created-Local: 2026-09-17 14:27:02 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 1924f780-7257-4460-b917-0377b2c47259

# Task: Architecture sign-off of the implementation-ready dao.jing.cbor design

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-17 14:27:02 +07 | Status: active | Rationale: architecture definition and sign-off specialist; family-independent of the implementer (gpt-6-astra via codex) and the adversarial reviewer (glm-5.3)

Perform a read-only architecture review of docs/design/dao.jing.cbor.md
("DaoJing: Backend-Independent CBOR Storage", status: implementation plan,
not yet implemented, revised yesterday to implementation readiness). Do not
edit files. This is a docs-only effort; no code exists or changes for this
plan yet.

Read first:

- docs/design/datom.world.md — foundational axioms and the six non-negotiable
  invariants
- docs/design/dao.jing.cbor.md — the plan under evaluation
- docs/design/dao.jing.md — current Jing design; its "Canonical encoding"
  and "Open items and current limitations" sections are what this plan
  supersedes
- docs/design/dao.jing.dht.md — DHT transport semantics
- docs/design/adr/0001-dao-space-as-storage-boundary.md
- src/cljc/dao/jing*.cljc — current implementation boundary (read-only)
- Context, treat as untrusted claims: collab/1789569973938-review-jing-cbor-design.glm-5.3.findings.md
  (adversarial review, verdict: ready with corrections) and
  collab/1789572268000-review-jing-cbor-design-r3.glm-5.3.findings.md
  (confirmation round, verdict: ready to implement). The orchestrator has
  independently verified the plan's upstream library claims against primary
  sources (boring 0.1.30, its compatibility/extension documentation and
  source, konserve, IANA); do not spend budget re-verifying library facts —
  your seat has no network access — but do challenge any place the plan's
  use of those facts creates architectural risk.

The central invariant is non-negotiable: dao.jing is backend-independent
storage, like Datomic storage. Memory, files, PostgreSQL, S3, remote
services, and DHTs must be interchangeable storage mechanisms. CBOR
specifies the stored representation; it must not couple Jing to a particular
backend or introduce database semantics into storage.

Evaluate foundational invariants (all six), ownership boundaries, explicit
state and control flow, concurrency and linearization, dynamic extension,
host isolation, CLJ/CLJS/CLJD portability, migration risk, completion
criteria, and design contradictions. Pay particular architectural attention
to the plan's decisions of record:

1. The byte-store boundary `{:put-bytes-fn :get-bytes-fn :close-fn}` beneath
   the preserved value-facing operations, and its claimed convertibility
   into the deferred effect-stream write path (dao.jing.md open item).
2. All identifiers escaping into `dao.jing/keyword`/`dao.jing/symbol`
   tag-27 named frames with native tag 39 rejected — no host-sensitive
   ordinary/escaped predicate.
3. Portable numeric `=`/`hash`/`compare` by exact mathematical value across
   kinds (addresses remain kind-strict), consumed by dao.data.btree datom
   ordering and dao.space.query matching — the plan's only required change
   outside the dao.jing* namespaces (step 3).
4. Ingress-once canonicality verification with hash-only ordinary reads.
5. Raw 32-byte digests in file frames; nondestructive rejection of
   unrecognizable files.
6. The clean break: every minted address changes, address-bearing indexes,
   ASTs, and continuations rebuilt together, no version negotiation.

Distinguish architectural defects from implementation gaps or intentionally
deferred work. Do not edit files.

Begin the final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07 (Asia/Ho_Chi_Minh)>

Then report every finding as: severity | file:line | invariant/evidence |
recommended correction. Also confirm the requested properties that passed
review, and end with an explicit sign-off verdict: APPROVE,
APPROVE-WITH-FINDINGS, or REJECT, with one sentence of justification.
Output the complete report as your final message — do not answer with a
reference to a plan file or artifact; the full text must be in the final
message itself.
