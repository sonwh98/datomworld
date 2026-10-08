Created-GMT: 2026-09-21 17:37:18 GMT
Created-Local: 2026-09-22 00:37:18 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d (resumed — sent together with 1790011825648-architect-jing-cbor-prior-art.prompt.md)
# Task: architect addendum — Eve flat versus CBOR as Jing's canonical byte encoding
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 00:37:18 +07 | Status: active | Rationale: owner asked for the Eve-versus-CBOR question to be decided on the record inside the same architect thread

This addendum is ADDED to the follow-up brief
`1790011825648-architect-jing-cbor-prior-art.prompt.md` (same turn). Answer it as
item 7 of that deliverable, in the same final response, under the same header and
rules (read-only, edit no file, complete answer now).

## The question the owner asked

The owner asked whether Eve would be a better choice than CBOR for the
canonical byte encoding in Jing's storage epic, and then pointed out that both
are binary encodings of data. The orchestrator's first answer drew the line too
sharply (Eve as "in-memory layout", CBOR as "serialization"); the fair
comparison is Eve's FLAT cross-process encoding versus CBOR as the value-to-bytes
format. Decide it on the record.

## Facts to verify (do not trust them)

From the orchestrator's July 2026 notes on https://github.com/SeniorCareMarket/eve
(its real docs live in `doc/`, singular; API namespace `eve.alpha`; the squashed
"Initial commit" was pinned then; treat anything not visible in this repository
as UNVERIFIED and say so):
- Four platforms with one format: browser CLJS, Node CLJS, JVM, Babashka. No
  Dart/ClojureDart listed.
- Two encodings: slab pointer tags 0x10-0x13 are in-process only and "must never
  appear in cross-process atom serialization"; Flat tags 0xED/0xEF are the
  self-contained length-prefixed cross-process form. Raw slab bytes never cross
  the network.
- Node references are slab-qualified offsets; unstable alpha API.

In THIS repository:
- `docs/design/dao.data.btree.md` line ~700 says: "When the canonical byte
  encoding lands (Eve flat, per `dao.jing.dht.md`), the default flips to on and
  the same-host restriction disappears". But `docs/design/dao.jing.dht.md` no
  longer mentions Eve (a case-sensitive grep for `Eve\b` finds nothing), and
  `docs/design/dao.jing.cbor.md` never mentions Eve at all. So one doc still
  names Eve flat as the planned canonical encoding while the later CBOR plan
  (architecture-reviewed 2026-09-17) chose CBOR.
- `docs/design/yin.vm-in-dao.space.md` mentions Eve only as "Eve Programming
  Language", an unrelated thing; and the blog public/chp/blog/eve.blog proposes
  Eve as a zero-copy storage/transactor substrate. Nothing in deps.edn,
  package.json, pubspec.yaml, src or test uses Eve.

## Answer (item 7)

1. **Ruling on the encoding role.** Eve flat versus CBOR for Jing's canonical
   bytes: which, and on which criteria (Dart/ClojureDart support, canonical and
   deterministic profile, independent readers for fixture verification,
   stability and version pinning, tag/extension mechanism for Clojure types,
   ownership of the byte-level spec)? State plainly which criteria are decided
   by evidence in this repository and which are UNVERIFIED because they concern
   Eve's current external state, and what single check would settle each.
2. **Was the Eve-flat plan of record ever real and, if so, superseded?** Explain
   the history the docs imply, and say whether the CBOR plan should state that it
   supersedes an earlier Eve-flat intention.
3. **The stale reference.** Give exact 80-column replacement wording for
   `dao.data.btree.md` line ~700 (and any other stale statement you find; grep
   the docs) so it points at the CBOR plan. Do not apply it.
4. **Eve in its proper role.** Separately from the encoding: is Eve a legitimate
   candidate for a byte-store BACKEND or a local index substrate behind Jing's
   opaque-bytes boundary, and if so does anything in the CBOR work need to be
   shaped now to keep that door open (or is it fully independent)? Keep this to
   a paragraph; do not design it.
5. **eve.blog.** Say in three or four bullets which of its present-tense claims
   the repository does not support (it presents the integration as done). The
   owner has not asked for edits; this is so a later fact-check is scoped.
