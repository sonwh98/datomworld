Created-GMT: 2026-10-09 23:40:00 GMT
Created-Local: 2026-10-10 06:40:00 Asia/Bangkok

# Task: Round 2 -- review the phase-1 identity amendments (A is the only code identity)

Role: Lead System Architect (continuing thread 01a121d7-5ba4-71b1-a70b-f6b4cf3d056a)

The owner confirmed the re-foundation, verbatim:

> "rebuilding the semantic-vm as as register shaped instead of
> stack-shaped is the right way to go, delaying this will only add tech
> debt in the future"

Your sequencing item 1 ("amend identity now, before register
implementation") is done as one commit on branch `a-only-code-identity`,
one commit on top of master f633adf3. Run `git diff HEAD~1 HEAD` and
read the four amended files from disk:

- docs/design/yin.vm.debruijn.stack.md (amendment block after Status;
  inline markers at §1 "sole sharing identity", §2 "H is the only
  executable and sharing identity", D12)
- docs/design/yin.vm.debruijn.register.md (amendment block after Status;
  inline markers at the status sentence and §1.1 linker request)
- docs/design/yin.vm.debruijn.targets.md (amendment block after Status;
  inline marker at T-D12)
- docs/design/yin.vm.universal-continuation-format.v2-amendment.md
  (amendment block at the head of §4)

Design of the amendments: dated blocks quoting the ruling verbatim and
listing what they supersede BY SECTION/DECISION NUMBER, plus one-line
markers at the strongest contradicted sentences; no prose rewritten.
The register-shaped-VM ruling is referenced as forthcoming design, not
applied. On the principle quoted in register §1 the owner ruled,
verbatim: > "one truth many interpretation" is not violated
-- so the amendments state that the principle stands (the Universal AST
is the one truth; the three VMs remain its interpretations) and
supersede only the register design's narrower topology sentence "no
projection is compiled from another" as applied at the de Bruijn layer.

Check, read-only:
A. Completeness against your finding 5 ("requests, publication, pins,
   dependencies, and executable admission name A"): is every statement
   in the four documents that makes H or R a NAME (not a checksum) now
   superseded? List any missed, with line numbers. Also check the
   stack design's §7.2 (B6/B7 linker topology) and the register
   design's R5, which the amendments reference only by implication.
B. Is "a kernel holding only an H/R-addressed image with no A reachable
   refuses" the right stated posture, and is it consistent with D8/D14
   wire verification?
C. Is the amendments' treatment of "one truth, many interpretations"
   (not violated; only the de Bruijn-layer "no projection is compiled
   from another" sentence superseded) a faithful reading of the owner's
   ruling and of axiom 2 of datom.world.md?
D. Does anything in the amendments pre-empt a decision that belongs to
   the forthcoming semantic-register-vm design (your items 2-5)?
E. Are there other documents (linker, dht, pipeline, UCF parent §7.3.4,
   code-as-tuples §5) that must carry the same amendment before this
   lands, or can they follow?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: severity | file:line | evidence | correction, for anything
remaining, and end with one line: VERDICT: READY_TO_MERGE | CHANGES_REQUESTED.
