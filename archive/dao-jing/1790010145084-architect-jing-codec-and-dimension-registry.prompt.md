Created-GMT: 2026-09-21 17:02:25 GMT
Created-Local: 2026-09-22 00:02:25 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your de Bruijn thread)
# Task: architect rulings — (A) dao.jing.file cross-host losslessness, (B) dimension-protocol registration of :yin.debruijn/*
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 00:02:25 +07 | Status: active | Rationale: owner asked the architect to resolve two open items from the de Bruijn epic

Read-only. Work in /Users/sto/workspace/datomworld (master; the de Bruijn work is
merged and pushed; local master has 4 docs commits on top). Give the complete
answer now; do not wait for approval and do not promise one. Edit no file.

Two SEPARATE questions. For each: a RULING (not just analysis), the smallest
change that implements it, where it belongs (which document/namespace), and
whether it is a design decision you can make or genuinely the owner's.

## A. dao.jing.file is not lossless across hosts

Facts (verified by the orchestrator during the D5/D6 work; check them yourself
against src/cljc/dao/jing.cljc, src/cljc/dao/jing/file.cljc,
docs/design/dao.jing.md, docs/design/dao.jing.cbor.md and the tests pinned in
test/yin/vm/pipeline_test.cljc, test
`values-the-file-codec-cannot-carry-are-refused-not-corrupted`):

- jing content-addresses a value by a "transitional" PRINT-BASED hash
  (`canonical-print` in dao/jing.cljc) and the file backend stores values as
  printed EDN text, with a write-time round-trip check: a payload whose text
  round trip would not hash to its address is refused.
- On CLJS, `pr-str` prints -0.0 as "0", so the file codec turns -0.0 into 0. The
  print-based address does not notice (both print alike), so the write succeeds
  and the value silently changes; the projected reader later catches it as
  `:hash-mismatch` because the Merkle fingerprint is computed independently.
- On Dart (CLJD), the file codec refuses LIST literals at write
  (`Payload does not survive this backend's text codec ...`), fail-safe.
- The de Bruijn design (docs/design/yin.vm.debruijn-projection.md) declares
  dao.jing outside its authority (section 9) and the physical jing address is
  documented as portable only between implementations sharing jing's print rule;
  the Merkle fingerprint is the cross-host identity.

Decide:
1. Is this a defect to fix, a documented limit to accept, or a reason to
   change the codec? Consider that `docs/design/dao.jing.cbor.md` exists: is a
   CBOR-based canonical encoding the intended fix, and is the print hash meant
   to be retired? What is the actual plan of record, and is it scheduled?
2. Severity: can any value other than -0.0 and lists silently change or be
   refused on some host (think NaN, doubles that print differently, sorted sets
   and metadata, chars, ratios)? Say what you can determine from the source and
   the tests, and what needs a probe.
3. Ruling and smallest change: for example (i) accept and document in
   dao.jing.md with the tests as the contract; (ii) make the file backend
   refuse -0.0 on CLJS at write like Dart refuses lists, so the failure is at
   the right layer; (iii) route the file store through the CBOR encoding; or
   something else. Say which owner (dao.jing engineer / storage role) and
   whether it deserves its own epic with an architect design round.
4. Whether the pinned integrity-law test is the right contract to keep, and
   whether the blog/status wording ("pinned by tests") needs anything more.

## B. Is the :yin.debruijn/* dimension "published" per the dimension protocol?

Section 4 of the de Bruijn design says: "The projection publishes a
`:yin.debruijn/*` dimension descriptor, as required by `docs/design/datom.md`'s
dimension protocol. Its descriptor hash is the hash domain separator." The
implementation defines the descriptor as a value in src/cljc/yin/vm/debruijn.cljc
(with `dimension-hash`, digest pinned in tests). The orchestrator found that
nothing else in `src` refers to it.

Decide:
1. What does datom.md's dimension protocol require of a publisher? Read it. Is
   there a registry, a well-known location, a transaction that must assert the
   descriptor, a discovery attribute, or is "publish" satisfied by defining a
   content-addressed descriptor whose hash is the domain separator?
2. Does the merged implementation satisfy it fully, partially, or not? If
   partially, what exactly is missing (registration call, a descriptor datom in
   the store, a doc entry)?
3. Ruling and smallest change to close it, or "already satisfied, and here is
   the sentence in the design that says exactly what publication means so the
   status stays honest". Say whether this affects the doc's "implemented
   through D6" status line, and give exact wording for any design-doc edit
   (80 columns), but do not apply it.

## Deliverable

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then "A. RULING: ..." (accept / fix now / new epic, with the smallest change) and
"B. RULING: ..." (satisfied / partially / not, with the smallest change), each
followed by the numbered answers above, and an explicit list of anything that
is the OWNER's decision rather than yours. Edit no file.
