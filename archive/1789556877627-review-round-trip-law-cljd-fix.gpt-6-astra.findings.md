Coding-Agent: codex
Model: gpt-6-astra

# Review: ClojureDart round-trip-law content-addressing fix

**The two fixes are correct, but the review found an unfixed producer path
that contradicts the new documentation.**

1. **Real gap — Dart Transit decoders also mint the unwanted metadata.**
   Both `src/cljd/dao/stream/transit/cljd.cljd:275` and
   `src/cljd/dao/stream/transit.cljd:268` reconstruct lists with
   `(apply list v)` without clearing the wrapper metadata.

   This is a live input path: `src/cljc/dao/stream/ws.cljc:266` decodes
   incoming frames and emits their payloads. Portable-value validation does
   not remove metadata. Subsequently hashing a decoded metadata-free wire
   list preserves the fabricated `:tag`, yielding a different address from
   the original list. Passing through either fixed function does **not**
   repair this: both correctly preserve significant metadata already
   present on their input.

   Clear newly fabricated wrapper metadata in both decoders and add a CLJD
   regression checking decoded-list metadata and hash equality with a
   clean list.

2. **Real gap — "No producer is known to hit this today" is inaccurate.**
   `docs/design/dao.jing.md:430` overlooks those Transit constructors. The
   distinction between normalization-created metadata and input metadata is
   otherwise accurate. Document the known codec path and its disposition;
   upstream repair or the future canonical encoder are not the only
   possible remedies.

   Other searched constructors have different roles: Yang's `and`/`or`
   expansion constructs syntax that compilation consumes; REPL constructors
   support display; postgraphics constructors initialize internal stacks. No
   comparable direct payload leak found at those sites.

3. **Already correct — the root cause is independently confirmed.**
   The pinned ClojureDart `core.cljd:3436` seeds `list` with
   `^PersistentList ()`; its list `-conj` preserves metadata. The generated
   `lib/cljd-out/cljd/core.dart:31865` explicitly attaches source
   coordinates and a `PersistentList` type-valued `:tag` to that seed. This
   confirms the explanation independently of test results, including for
   empty lists.

4. **Already correct — both fixes operate at the right layer and preserve
   meaningful metadata.** `jing.cljc:143` clears the newly constructed
   wrapper, then `attach-meta` restores normalized input metadata.
   `v2.cljc:628` likewise clears its wrapper before restoring input
   metadata minus reader positions. Neither `with-meta` call clears element
   metadata — elements undergo the existing recursive processing. Blanket
   removal of input `:tag` would violate the current metadata contract;
   this patch correctly avoids that.

5. **Already correct, with a coverage limitation — the pinned hash is
   load-bearing.** The equality assertion at `jing_test.cljc:200` alone
   could pass when every input receives identical contamination. The
   pinned assertion catches that common-mode failure. Independently
   calculated SHA-256 of UTF-8 `[1 (2 3) #{4}]` (no newline) matches the
   supplied hash exactly. `v2_test.cljc:230` directly checks projected
   wrapper metadata, catching the second defect independently. Both tests
   would pass before this fix on JVM; executing them under CLJD is
   essential. Neither covers Transit reconstruction. Recording the exact
   hash preimage would make future fixture changes easier to audit.

6. **Already correct — compatible with `0cafb2d`; no new CLJS-specific
   defect identified.** The patch preserves list/seq canonical equivalence
   and meaningful collection metadata introduced by that work. Standard
   CLJS list `with-meta` semantics support this change. CLJS execution
   remains unverified.

Inspected source and generated Dart, independently verified the hash; did
not rerun the suites or edit files.

**Verdict: fix the Transit constructor gap and correct the documentation
before Architect sign-off as-is. The two proposed normalization changes
should remain.**
