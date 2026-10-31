Created-GMT: 2026-10-01 17:15:40 GMT
Created-Local: 2026-10-02 00:15:40 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0f878-281b-7253-ac44-ff2402583d35 (captured from thread.started; was pending at creation)

# Task: Design Python arbitrary-precision integers (phase C3) across the four VMs

Role: Lead System Architect

Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-02 00:15:40 +07 (+0700) | Status: active | Rationale: the hardest numeric-representation crossing (wire codec + heap + three hosts); the codex seat is reserved for architecture

Perform a read-only architecture design for Python phase C3: arbitrary-precision integers. Author the full design in
your response; findings only — do not edit any file.

Read first (main tree at /Users/sto/workspace/datomworld, master 111a9823; read-only — another seat's uncommitted work
is present there, judge committed state):
- docs/design/datom.world.md (the six non-negotiable invariants)
- docs/design/yang.antlr.md (value-encoding rulings including float tagging; §8 lowering; §11 determinism)
- docs/design/yin.vm.universal-continuation-format.md and docs/design/yin.vm.code-as-tuples.md (the canonical CBOR
  codec; identity and addressing constraints — segment-key and content hashes must stay host-stable)
- /Users/sto/workspace/datomworld/collab/1790849288904-compiler-engineer-python-phase-c1.claude-opus-5-5.report-final.md
  (current state: integer arithmetic is native; results beyond ±2^53 raise OverflowError)
- /Users/sto/workspace/datomworld/collab/1790797984227-architect-python3-mappability.claude-fable-5-1.findings.md
  (the float-tagging decision: floats tagged, ints untagged; 4/2 parity case)
- src/cljc/yang/python/antlr/prelude.cljc (the numeric tower: py/zero-like, py/float-mod, floor division, bit ops)
- the heap/reclamation machinery as landed (cell/heap entries, gc-roots/gc-children, pinning) and the :pure host
  module pattern (data primitives named by runtime profile)

Questions the design must answer:
1. Representation: the native fast path versus heap bignums; the exact boundary per host (JVM long, JS double,
   Dart int/mint/BigInt semantics); how a bignum is tagged in the value encoding without breaking the untagged-int
   ruling for in-range values.
2. Canonical wire form: does the codec gain a bignum payload kind; the digit encoding, endianness and normalization
   rules; how identity and addressing (segment-key, content hashes) stay stable and host-independent; UCF amendment
   needed or not.
3. Arithmetic placement: which operations live in the portable prelude versus a host module (the :pure host-module
   pattern); Python division/modulo semantics with negatives; bit operations on negative numbers; << >>; ** with
   bignums; int()/float()/str()/repr conversions and their precision rules.
4. Hash and equality: Python's 1 == 1.0 == True normalization versus the already-ruled dict-key normalization;
   hash() of a bignum must be cross-host identical; interning of small ints, if any.
5. Heap and reclamation: bignum payloads inside heap entries; gc-children traversal; aliasing via cells; pinned
   stream/FFI refs interacting with bignum-carrying cells.
6. Determinism and isolation: no host-observable divergence; what the acceptance matrix (§13, the verification laws)
   gains; diagnostics for results that leave the supported domain.
7. Implementation sequencing: the slices for the engineer, each with acceptance test contracts (setup/action/assertion
   per host), following the test style of yang.antlr.md §13 and the C1 corpus.

Distinguish architectural defects from implementation gaps or intentionally deferred work. Do not edit files.

End with an "Owner decisions" section enumerating every open decision with your recommendation. The owner has delegated
tonight's decision authority to the architect pair: each recommendation will be cross-ruled by claude-fable-5-1, and
converged rulings are recorded as architect rulings in the orchestrator log.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
