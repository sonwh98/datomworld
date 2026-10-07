Created-GMT: 2026-09-20 19:55:00 GMT
Created-Local: 2026-09-21 02:55:00 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your de Bruijn projection authoring thread)
# Task: architect sign-off — de Bruijn projection D2
Role: architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-21 02:55:00 +07 | Status: active | Rationale: the design's author is the sign-off authority

# Task: architect sign-off — de Bruijn projection D2 (Merkle records and storage adapter)

D2 is implemented in this worktree (branch debruijn-impl, uncommitted on top
of D0+D1's ceae3cc8), adversarially reviewed by claude-fable-5-1, fixed, and
confirmed READY by the same reviewer. Review the final state and grant or
withhold sign-off.

Scope: node hashes over tag-specific descriptor slots (:hash/:root excluded),
fingerprint = hash(root), hash-consing, :yin.debruijn/* records, and the pure
in-namespace d5 storage adapter (projected->datoms / datoms->projected).

The review round and resolution:
- P1 (soundness): the consing memo was keyed by Clojure =, merging a list
  literal with a vector literal and 0.0 with -0.0 — non-equivalent programs
  could share a fingerprint, traversal-order-dependent. Fixed per the
  orchestrator's ruling by keying the memo on the node's PREIMAGE (the
  concatenated encodings), which separates exactly what the hash separates;
  tests prove list-vs-vector and ±0.0 in one program hash differently.
- P2: ident namespace/name parts now route through normalize-nfc (value
  table declares :nfc-utf-8; datom.md requires it).
- P2: the reader recomputes each record's hash and raises :hash-mismatch
  (precedent: yin.vm.content); non-string hashes raise :malformed-hash.
- P3: provisional int64 content renders via (str (long v)), so 1 ≡ 1.0 on
  every host now; tests exist.
- Carried forward (reviewer-classed P3, deferred by phase): D3 must
  canonicalize scalars INSIDE records (long for integral doubles, NFC for
  strings/idents) so one address holds one content; D4 owns the reader's
  diagnostic ordering (hash check before slot-type checks can raise a host
  exception on wrongly-typed slots).

Already verified by the orchestrator in this exact worktree — do not rerun
suites: focused JVM 47/159/0; kondo 0/0; JVM full 1605/168945/0; CLJS full
green including the ±0.0 and 1/1.0 fixtures on the JS host; CLJD full 1487
passed; reviewer confirmation round READY.

Questions for your verdict:
1. Does the preimage-keyed consing memo satisfy §5's identity rules with no
   residual path to traversal-order dependence?
2. Is the d5 adapter's shape (one entity per hash, root marker on the
   fingerprint entity, :operands as one ordered vector datom, deterministic
   handles) what D5's pipeline integration should expect?
3. Do you bless the two carried-forward P3 dispositions (D3 record-scalar
   canonicalization; D4 reader diagnostic ordering)?

Deliver exactly one of: SIGN-OFF GRANTED for committing D2 on debruijn-impl
and proceeding to D3, or SIGN-OFF WITHHELD with the blocking list.
