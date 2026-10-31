Created-GMT: 2026-10-10 00:20:00 GMT
Created-Local: 2026-10-10 07:20:00 Asia/Bangkok

# Task: Round 3 -- identity amendments rewritten to blanket precedence; linker amendment added

Role: Lead System Architect (continuing thread 01a121d7-5ba4-71b1-a70b-f6b4cf3d056a)

All round-2 findings taken. The commit on branch `a-only-code-identity`
was amended in place (one commit on top of master f633adf3). Run
`git diff master HEAD` and read the five amendment blocks from disk:

- yin.vm.debruijn.stack.md (after Status)
- yin.vm.debruijn.register.md (after Status)
- yin.vm.debruijn.targets.md (after Status)
- yin.vm.universal-continuation-format.v2-amendment.md (head of §4)
- yin.vm.linker.md (head of §3) -- NEW, the coordinated amendment you
  required before merge

What changed:
1. Each block now states BLANKET PRECEDENCE over every statement in its
   document that makes H or R a name (request, fetch, index,
   publication, pin, dependency, captured-code, cache authority,
   admission identity), and then enumerates the instances you listed so
   none survives by omission: stack §2 code-image identity and H-keyed
   caches, §3.1, §5 parked/reified payloads, §7.2 request key /
   `:call-hash H` / H index / fetch-by-H, B7 SCC ordering, D5, D8, D12,
   D16; register line 51, §1.1 "with R alone", invariant 3, DECIDED 2,
   §3 line 319, §5.1 captured identity and fetch, R5 / §9 R index and
   end condition; targets line 70, line 322, T-D5/T-D11 keys, T-D12,
   T8, line 556 reconstruction, line 1248, Level-2 fetch-by-R; v2 §4.1
   layouts name A with offsets derived, version-2 bytes historical not
   authorizing A-less admission.
2. The three-step admission procedure (verify vector against A; derive
   or verify native bytes against that lowering; native validation and
   closure checks) is stated in the stack block and referenced from the
   others; "reachable" is defined as obtainable and verified before
   admission, a claimed address or pairing datom insufficient; checking
   an image against its own H/R proves integrity, not relationship to A.
3. H and R are stated to remain INDEPENDENT SIBLING lowerings of A;
   only the upstream topology is superseded.
4. Targets: "near-direct derivation" replaced by "a forthcoming
   A-derived lowering, whose simplicity is not assumed".
5. Linker §3 block: format identity = A for every backend; H/R retired
   as a family of names and kept as checksums used in §4.2 step 3; the
   format index keyed `[A f]`; derivation records lead tree -> A ->
   native image with the checksum as evidence; I5's two checks kept with
   the identity check directed by A; "strict decoupling only for H and
   R" superseded; nothing in M1-M5's landed code changes. The DHT, head,
   remote and code-as-tuples implementation-plan documents were grepped
   for H/R-as-key statements and have none.

Check, read-only:
A. Coverage now complete against your round-2 table? Anything that a
   blanket-precedence clause still fails to reach (e.g. an acceptance
   criterion that would be read as a test oracle rather than a
   statement)?
B. Is the linker block consistent with I5/I8 and with §5.5 as
   generalized, and is "[A f] -> storage address" the right shape for
   the format index?
C. Does any block now pre-empt the forthcoming semantic-register-vm
   design (numbering, windows, allocation, stackification, delivery)?
D. Confirm the owner's reading that "one truth, many interpretations"
   is not violated, as the blocks now state.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: severity | file:line | evidence | correction, for anything
remaining, and end with one line: VERDICT: READY_TO_MERGE | CHANGES_REQUESTED.
