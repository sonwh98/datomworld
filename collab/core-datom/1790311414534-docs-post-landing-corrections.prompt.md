Created-GMT: 2026-09-24 21:10:00 GMT
Created-Local: 2026-09-25 04:10:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (post-landing docs pass)

# Task: Post-landing documentation corrections (architect-prescribed)

Role: Docs / Scoped Subagent (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master, clean). The
DaoJing CBOR swap and the multihash registry epic have landed; the
architect's merge-gate review prescribed four documentation corrections
(collab/1790280923711-architect-cbor-swap-signoff.gpt-6-sol.findings.md —
read it first). Apply exactly these, docs files only:

1. docs/design/dao.jing.cbor.md:3 — the header still calls the swap "not
   yet implemented". Update the status to landed on master (2026-09-25,
   merge e149aa31), keeping the doc's own header format.
2. docs/design/dao.jing.cbor.md:147 — qualify the "supported values"
   wording: on Dart, a runtime-created list can carry an unsupported
   constructor `:tag` (a Dart Type), and internal producers (e.g. the VM's
   reader-position projection) can reattach such metadata; supported
   means the encoder accepts the value once its metadata is host-clean.
3. docs/design/dao.jing.cbor.md:179 — the constructor-metadata warning
   names Dart producers; broaden it to explicitly include internal
   producers (lists reconstructed by Jing-bound code such as the de Bruijn
   projection), per the architect's finding.
4. docs/design/dao.jing.hash-registry.md:293-297 — the still-normative
   text says `get` need not hash-verify reads and describes a
   pr-str/EDN file round trip. Mark those registry-era sections
   historical (with a note pointing to docs/design/dao.jing.cbor.md's
   current read and replay rules: hash verification on reads, strict CBOR
   validation at file replay) or replace them with the current rules —
   choose the smaller edit that preserves the document's voice.
5. docs/design/dao.jing.hash-registry.md:3 and :508 — the status headers
   still describe the CBOR swap as future work; update to landed
   (matching correction 1's convention).

Constraints:
- Touch ONLY the two named doc files. Pure ASCII, <= 80 columns on every
  line you add or edit (the repo gate applies to docs).
- Preserve each document's voice and structure; smallest faithful edit.
- Do NOT commit or stage. Do not touch any other file.
- Verify with a width/ASCII scan of the two files (0 violations on edited
  regions) and report the per-correction before/after line numbers.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
