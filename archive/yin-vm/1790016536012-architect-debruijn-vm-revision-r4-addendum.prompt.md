Created-GMT: 2026-09-21 18:50:30 GMT
Created-Local: 2026-09-22 01:50:30 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (sent together with 1790016536012-architect-debruijn-vm-revision-r4.prompt.md, same turn)
# Addendum: the fable review is now available, and one finding reverses an owner-stated wish

The fable-5-1 review is at
collab/1790016340343-architect-debruijn-vm-design-fable.claude-fable-5-1.findings.md.
Verdict SOUND WITH CHANGES. Its findings, for your dispositions:

- P1-1 the lossless record DAG (`encode-db`/`decode-db`, B0) has NO CONSUMER: B2
  runs `linearize/lower` on the named datoms and B5's pipeline is
  `ast->datoms -> B2 -> VM`, so nothing reads the DAG. It is an isomorphic copy of
  the named datoms plus a column derivable by `resolve-name` ("derive, don't
  persist"), and it is what puts owner decisions 1 (tempid identity) and 3
  (non-`:yin` attributes) in front of the VM. Its proposed fix: cut
  `encode-db`/`decode-db` from B0 and the VM's critical path, keep in B0 only the
  normalizer and the frozen parity corpus, and make any lossless view an optional,
  separately reviewed phase with no downstream dependency.
- P2-1 the design never states why a second VM should exist (no benefit, metric,
  benchmark gate, or end state); add a "Benefit and exit criterion" paragraph, a
  benchmark gate after B3 using the semantic-VM section 8 harness, and an owner
  decision on the end state (retire one VM, or make frames the default).
- P2-2 image identity includes BINDER names (`(fn [x] x)` and `(fn [y] y)` would
  get different executable hashes, so it partitions programs like
  `jing/segment-key` does and rehashes dependents when a local is renamed): put
  binder names in the pc-indexed diagnostic side table outside the hash; free
  names stay hashed as `:load-free` operands.
- P2-3 "published" dimension overclaim and an underspecified descriptor (declare
  arity, slots, encoding, and one lift morphism from `:yin.debruijn.code/*` to
  `:yin.code/*`; exact-spelling slots typed Bytes so the no-NFC rule is a slot
  type, not an exception; and say "defines and exports; publication is outside
  B0-B6"). The lift also gives B2 a stronger test: `lift(adapt(lower x)) = lower x`.
- P2-4 a second continuation shape forks UCF's canonical state and hides frames
  from `yin.vm.completion`: lift frames to a named env through the P2-3 morphism
  or add a frame-aware completion adapter in B4, and list "continuations are not
  interchangeable between the two VMs" in section 1's not-promised list.
- P2-5 the "injectable loader" is a relocated callback: replace with "the VM
  accepts loaded images only as values in its state and never invokes a loader;
  absence is a park plus a request emission".
- P2-6 what `:call-hash H` denotes is a missing owner decision (image, named
  definition, or SCC component; whether a contract stamp is inside it); decide it
  before B1 freezes the image hash.
- P3s: "Two artifacts" vs three listed and an undefined "Architecture A";
  the exact-spelling side table may hold nothing the `:const` operand does not;
  `closure-ranges`/`layout-conforms?` private-helper reuse contradicts "Existing
  edits: none" (expose as a declared one-line visibility edit, or tie a copy by a
  test); derive the operand table as data from `vector-operand-table` with two
  entries replaced; the section 4 state map omits parked records, gensym counter,
  primitives, modules and `:code-aliases` ("plus the semantic VM's
  non-environment registers, unchanged"); the `environment` protocol method
  silently changes meaning (return the lifted environment or do not implement
  it); "cache key" is used but no cache is specified; B6 should move out of
  section 6 into 7.2; and one unverified observation: section 2 implies the
  projection folds integral doubles, which may conflict with the projection's
  section 10 ruling that "identity never merges distinguishable values"
  (verify at the merged source, `canonical-value` in debruijn.cljc, and correct
  the design's wording if needed).

## The tension you must rule on explicitly

The owner asked for a "bijection" and then agreed to a lossless, invertible de
Bruijn encoding of the named AST (with a name table) as the executable source. The
lossless record DAG is that request made concrete. Fable's P1-1 says the DAG is
redundant. Do NOT silently drop or keep it. Rule on the merits: is the
invertibility invariant needed by any consumer, or can the property the owner
cares about ("nothing is lost before execution") be delivered by lowering from the
named datoms plus the diagnostic name and spelling side table, which is already
lossless with respect to what execution needs? State what exactly the owner would
give up by accepting P1-1, and mark that as an OWNER DECISION for the owner to
confirm, since it reverses a stated wish; do not treat it as settled either way.
If you cut the DAG from B0, say which of the B0 owner decisions stop blocking. The
owner has NOT approved dropping it.
