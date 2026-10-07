Created-GMT: 2026-09-22 19:22:00 GMT
Created-Local: 2026-09-23 02:22:00 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: pending (new session, caller-generated)
# Task: reviewer-linker-b4-continuation-design — independent review of three related design changes
Role: Architect (Routine/Architectural Review, per docs/agents/team.md's routing:
Stream & Network and Storage & Indexing roles both match this content --
DHT, storage, networking, and VM runtime design)
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-23 02:22:00 +07 | Status: active | Rationale: independent family from the design's author (claude-fable-5-1); matches this session's own model-strengths table ("VMs, storage, indexing, DHTs, and networking")

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). This is a READ-ONLY REVIEW. Do not edit any file.

## Context

The de Bruijn VM epic (docs/design/yin.vm.debruijn.stack.md,
docs/design/yin.vm.debruijn.register.md) has a committed stack VM (B0-B3
merged) and an in-progress register VM (R0, R1 built; a live-set format
extension in progress). The owner authorized the register kernel (R4)
unconditionally, which surfaced two gaps: the linker phases (B6 for the
stack format, R5 for the register format) were undesigned placeholders,
and B4 (the stack VM's own effects/continuations phase) turned out to be
a hidden prerequisite for R4's effects tier. A third gap, register
continuation cross-model transport (how a register-format continuation
and a stack-format continuation interoperate for park/resume), was also
undesigned.

An architect pass (claude-fable-5-1) just resolved all three. The changes
are staged but NOT committed -- this review is the gate before any commit
happens, per this project's standing rule that independent review must
complete and be reconciled before an autonomous commit. Run `git diff
--cached -- docs/design/yin.vm.debruijn.stack.md
docs/design/yin.vm.debruijn.register.md` to see the exact diff against
the prior committed state. Your job is to independently review that
resolution before it is committed or any implementation work starts
against it.

## Read first, in full

- docs/design/yin.vm.debruijn.stack.md's B6 section (search "### B6") and
  section 1's invariant-compliance table row "I: share executable code" --
  read the WHOLE B6 section, not just the diff, for full context.
- docs/design/yin.vm.debruijn.register.md's R5 section (search "### R5")
  and the new section 5.1 (cross-model continuations).
- `git show 0ee3a2f1` -- the exact diff, so you know precisely what
  changed versus what was already there.
- src/cljc/dao/jing.cljc, in full -- specifically `segment-key`,
  `content-hash`, `canonical-bytes`, `materialize!`, `get`, and their
  docstrings (the design's central claim is that `content-hash`'s current
  encoder is explicitly "transitional," about to change with a CBOR
  encoder -- verify this claim yourself against the actual docstring
  rather than trusting the design doc's citation of it).
- src/cljc/dao/jing/dht.cljc, in full -- specifically `make-get`
  (search for it) -- the design's claim is that this function already
  does fetch-by-hash with verify-before-trust (local read first, DHT
  lookup on miss, fetch from peers, check the fetched value's own
  `segment-key` equals the requested address BEFORE accepting it, then
  re-materialize and throw on any mismatch). Verify this is what the
  code actually does, line by line.
- src/cljc/yin/vm/content.cljc, in full -- specifically
  `materialize-vector!` and `fetch-vector` -- the design's claim is that
  this is an ALREADY-WORKING precedent for exactly the pattern B6/R5 now
  propose (materialize a canonical instruction vector at its own
  `jing/segment-key`, validate before write, verify-and-validate on
  fetch) for the named VM's `:yin.code/*` vectors today. Verify this
  precedent is real and actually matches what the new B6/R5 design
  proposes to extend to the stack and register dimensions.
- docs/agents/architecture.md's AGENTS section -- the design's section
  5.1 (register doc) cites this as the authority for "AST datoms are the
  canonical migration payload, bytecode is a per-model projection" to
  justify refusing pairwise stack-to-register continuation lifts. Verify
  this citation is accurate, not a stretch.

## What to evaluate

1. **The H-versus-Jing-address boundary.** The design's core claim: H
   (image-hash, sha256 over B1's exact byte encoding plus the descriptor
   hash) must never be conflated with or derived from `dao.jing`'s
   `segment-key` (sha256 over a transitional order-normalized print),
   because the latter's encoding is explicitly slated to change and the
   former must not fork when it does. Is this reasoning sound? Is there
   any way the design's proposed `[H address]` "H index" (composition
   data pairing the two) could itself become a source of confusion or a
   silent-fork risk the design doesn't address?
2. **The layering boundary.** The design says `dao.jing.dht`'s fetch
   verifies CONTENT integrity (bytes match their own hash) and the
   linker layer then separately runs H/R verification, the D11/D15
   receiver closure check, and the format validator, "in that order,"
   with "neither layer weakened or duplicated." Trace this composition
   yourself against the actual code paths (`make-get`'s verification,
   then whatever B6/R5 would call next per the design) and confirm no
   gap exists where a malformed or malicious payload could slip through
   between the two layers, and no redundant re-verification wastes work
   without adding safety.
3. **What's "genuinely new" versus "inherited."** The design claims B6/R5
   are now thin: a format record shape, one fetch function parameterized
   by format, the H/R index, a refusal vocabulary, and tests -- with
   addressing, peer lookup, transport, hash-before-trust, caching, and
   the server side all inherited from `dao.jing`. Does this estimate hold
   up against the actual `dao.jing.dht`/`dao.jing.remote` code, or does
   it understate real remaining work (e.g. does the "server side" really
   need nothing yin.vm-specific, or would a responder need to know how
   to answer "do you have H" before DHT peer-lookup even starts)?
4. **B4 sequencing recommendation.** The design recommends dispatching
   B4 now, as "the longest pole," with the register track (live-set, R2,
   R4's pure tier) and linker track (B6+R5) proceeding in parallel, R4's
   effects tier only after B4 and R2 land. Sanity-check this dependency
   graph against the actual phase boxes in both design docs -- is there
   a hidden dependency this ordering misses, or is it sound?
5. **Cross-model continuation refusal.** The design's decision: same-
   model resume only; cross-model continuation transport is refused, not
   attempted, because no pc correspondence exists between a stack image
   and a register image and a register file is not an operand stack.
   The only sound cross-model path goes back through named datoms via
   each format's own pc-to-source side table. Is this the right call, or
   does it foreclose something the project actually needs? Is the
   architecture.md citation genuinely load-bearing for this conclusion,
   or decorative?
6. Any other correctness, consistency, or invariant concern a fresh read
   surfaces, using the standard architect checklist (foundational
   invariants, ownership boundaries, explicit state, host portability,
   migration risk, design contradictions).

## Verdict

Sound as written / sound with changes (list them) / not sound (say why).
Findings as P1 (must fix before any implementation starts against this) /
P2 (should fix) / P3 (nice to have), each with a concrete failure
scenario or specific text location, not a vague concern.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: <your session id>
Then the verdict and findings. Facts only, each claim checked against
the actual code or document text you read, not assumed.
