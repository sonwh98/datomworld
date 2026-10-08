Created-GMT: 2026-09-22 21:03:19 GMT
Created-Local: 2026-09-23 04:03:19 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0cad7-2f30-7b82-ae67-288922310f75 (resume of your prior hash-registry plan session)
# Task: architect-hash-registry-plan-fold-fixes — fold review fixes in, emit final doc text
Role: Lead System Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-23 04:03:19 +07 | Status: active | Rationale: same author, resumed to fold in an independent review's accepted findings and produce the promotable document text

Still read-only: do not write any file, do not run git add/commit. Your
final report's text IS the deliverable this time -- the orchestrator will
write it verbatim into docs/design/dao.jing.hash-registry.md and commit
it, so it must be complete, self-contained markdown from the title down,
not a diff or a summary of changes.

## Context

Your plan (collab/1790109451076-architect-hash-registry-plan.gpt-5.6-sol.stdout.log)
was independently reviewed by glm-5.3
(collab/1790110150738-reviewer-hash-registry-plan.glm-5.3.stdout.log --
read it in full). Verdict: READY WITH CHANGES, no P1. Every factual claim
in your call-site audit was independently reverified against source and
confirmed, including finding a nuance you glossed over (backends compare
segment-hash vs content-hash; yin.vm/DaoSpace consumers re-mint with
segment-key and compare whole addresses -- same defect, different
mechanism, segment-matches? fixes both). Two P2s and six P3s need folding
in before this becomes a committed design doc.

## P2-1: three sites you classified as segment-matches? validation sites are actually mint sites

`dao.jing.dht`'s `make-get` (`dht.cljc:229`) and `dao.data.btree.storage`'s
`hydrate!` (sync `pull!` at `storage.cljc:186`, async `fetched!` at
`storage.cljc:286`) all write FETCHED content into a local store via the
one-argument `(jing/materialize! local value)` and compare the result to
the fetched address, throwing on mismatch. After your plan's H3 flips the
default to BLAKE3, materializing a legacy SHA-256 payload through the
one-argument form mints a NEW print-v1+blake3 address -- which will never
equal the fetched print-v1+sha256 address, so every DHT fetch or B-tree
hydration of pre-flip content throws after H3 ships, even though the
content is intact. Reclassify these three sites as address-directed MINT
sites: they must call the explicit-arity `materialize!` with
`{:encoding (segment-encoding addr), :algorithm (segment-algorithm addr)}`
derived from the address they already have in hand, not the one-argument
default-minting form. This is a site-classification fix in your existing
"Existing-address validation sites" list and your `materialize!` section
already has the mechanism -- just move these three out of the
segment-matches? list and into a new short "address-directed mint sites"
list with this explanation.

## P2-2: a canonical CBOR codec already exists in the tree, corpus-frozen -- your plan doesn't mention it

`src/cljc/dao/jing/cbor.cljc` (landed, commits 7968884b/e3917e69) already
implements canonical CBOR encode/decode behind a frozen corpus
(test/resources/dao/jing/cbor-v1.json), currently used only for
`encoded-compare` (`dao/space/query.cljc:850`), not for addressing yet.
Fold in:
- H2/H4's "simulated second encoding profile" test should use
  `dao.jing.cbor/encode` as the real second profile instead of fabricating
  one -- it is strictly stronger evidence and invents nothing. If you have
  a reason to still prefer a simulated profile (e.g. keeping the registry
  closed against a not-yet-ratified id), state that reason explicitly
  rather than leaving dao.jing.cbor unacknowledged either way.
- H5 should state plainly that the future `cbor-v1` profile (your plan
  already uses `:segment/cbor-v1+blake3-<digest>` as an example) is
  expected to be backed by this exact codec and corpus, and note one
  value-domain caveat: the print profile throws on records while CBOR has
  its own distinct refusal classes (`::refusal` `:non-canonical`,
  `:unpaired-surrogate`, etc.) -- a cbor-v1 profile will not cover every
  value print-v1 covers. One sentence closes this.

## Six P3s (editorial, fold in briefly)

1. `file.cljc:82-97` `validate-codec-round-trip!` is not an address/payload
   check (it compares content-hash of a payload against its own pr-str
   round-trip, no address involved) -- remove it from the
   "must use segment-matches?" list; note instead that both sides must be
   computed under the mint profile.
2. No regression guard against a future call site reintroducing
   default-following verification. Add to H0: commit the site-
   classification table as a real artifact, and to H5: state as a law in
   dao.jing.md that "verification must be address-directed; minting
   primitives are not validators," ideally backed by a lint (clj-kondo
   rule or test) forbidding jing/content-hash or jing/segment-key in
   equality positions outside dao.jing itself.
3. H2's completion criteria should reference the FULL site list (it
   currently names a subset and omits yin.vm.macro, yin.vm.semantic,
   yin.vm.completion, yin.vm.ledger, and yin.vm's row validation) so the
   phase cannot be greened against only the named subset.
4. The portability spike's cross-provider digest table left no artifact
   in the repo -- note in H0/H1 that its official-vector fixtures should
   be committed as the artifact of record, since the spike itself was
   never captured as a collab log.
5. `yin.vm.debruijn/dimension-hash` (`debruijn.cljc:946-952`) is a `def`
   (computed once at load), not a `defn` -- describe it accurately in the
   H0 audit table wherever you list it as a "call site."
6. State explicitly in the address-parsing section that encoding ids
   contain `-` themselves (`print-v1`, future `cbor-v1`), so the parser
   must match against registry membership, not split the string on `-` by
   character class -- one sentence prevents an implementer writing a
   naive regex split.

## What NOT to change

Your core architecture (dual-id `:segment/<encoding>+<algorithm>-<hex>`
addresses, closed registries, `segment-matches?` centralization, H/R
frozen independent of the DaoJing default, hash-before-CBOR sequencing,
the H0-H5 phase structure, the non-goals list) was confirmed sound by the
review -- do not rework it, only fold in the two P2s and six P3s above.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0cad7-2f30-7b82-ae67-288922310f75
Then, IMMEDIATELY after that header, the complete final document,
starting with a top-level markdown title and proceeding through every
section (Decision, Invariants, DaoJing design, call-site audit/findings,
yin.vm H/R disposition, host libraries, sequencing, phased rollout H0-H5,
test obligations, explicit non-goals) with the ten fixes above folded in.
This text will be written verbatim to
docs/design/dao.jing.hash-registry.md -- do not include your own meta-
commentary about what changed inside the document body; if you want to
note anything about the fold-in process for the orchestrator, put it
AFTER a `---` horizontal rule following the document's own final section,
clearly separated from the document text itself.
