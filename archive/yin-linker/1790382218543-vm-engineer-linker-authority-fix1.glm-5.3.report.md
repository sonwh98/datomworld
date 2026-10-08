Created-GMT: 2026-09-26 00:23:38 GMT
Created-Local: 2026-09-26 07:23:38 +0700
Completed-GMT: 2026-09-26 00:27:54 GMT
Completed-Local: 2026-09-26 07:27:54 +07
Coding-Agent: glm
Session-ID: resume-of-f234a52a-3ca3-4b49-80f4-566ca39afcea

# Report: authority policy fix round 1 (DeepSeek gate small items)

Status: COMPLETE. All three items done; no behavior changed beyond
item 1; nothing staged, committed, checked out, reset, stashed, or
merged. Working tree: the same three files as round 1.

## JVM lane counts

- Before (baseline, matches the prompt's last figures): 2064 tests,
  181135 assertions, 0 failures, 0 errors.
- After: 2067 tests, 181146 assertions, 0 failures, 0 errors.
  Test delta is exactly the 3 new deftests. The new file contributes
  12 assertions (isolated run: 16 tests / 69 assertions vs 13 / 57);
  the full-suite assertion delta is 11, so the pre-existing
  variable-count test noted in round 1 drifted by -1 this run. All
  green throughout.
- kondo on both cljc files: 0 errors, 0 warnings. cljstyle on both:
  clean. Every added/edited line in code, tests, and spec: <= 80
  columns, pure ASCII, no em dashes.

## Item 1 (P2 weak segment-address check) -- TDD

- Test FIRST: new deftest `fake-segment-address-is-malformed`.
  Red run confirmed it failed for the right reason: a manifest of
  `:segment/fake` passed the weak gate (0 :malformed-envelope
  discards; the name present in :names). A retraction
  `:yin.module/of :segment/fake` and a real `jing/segment-key`
  address passing alongside are both asserted in the same test.
- Fix: deleted the private `segment-address?` (was lines 36-38) and
  replaced both call sites with the public
  `jing/segment-address?` (closed-registry validation of algorithm
  id, digest length, hex digits, canonical spelling; total, never
  throws). Green after the one-word fix.
- Existing tests all use real addresses already (every address comes
  from the `addr` helper = `jing/segment-key`; the only deliberate
  non-address, `:not-a-segment` in `malformed-envelope-is-discarded`,
  is rejected by both the weak and the authoritative gate, and still
  is). No existing assertion changed.

## Item 2 (P3 untested edges) -- two new tests

- `principal-without-proof-kind-cannot-assert`: a principal declared
  `{:seq-floor 0}` (neither proof kind) with a signed assertion
  yields exactly one `:no-proof-kind` discard (reason
  `:undeclared-principal`), empty `:honored-seq`, name `:absent`.
- `two-asserters-of-one-address-resolve-together`: alice and bob
  (signatures) plus carol (attested log) each prove an assertion of
  one name to ONE same manifest address; the name resolves `:ok` to
  that address with provenance asserters `[alice bob carol]` and
  proof kinds `[:yin.module/signature :yin.module/attested]` (the two
  signatures collapse by distinct). Both passed against existing
  behavior, as expected for coverage additions.

## Item 3 (spec sentences, section 8.2 only)

Four sentences added, one per site, each verified against the code
before writing; none differed, so no sentence had to be corrected
away from the prompt's wording and the code is untouched:

1. Floor naming, in the replay bullet: "The declaration carries that
   floor as `:seq-floor`."
2. Advance mechanics, at the end of the `:authority` bullet: "The
   snapshot result carries `:honored-seq` per principal, the highest
   sequence honored in this snapshot, which the composition declares
   back as the next `:seq-floor`."
3. Extra diagnostics, at the end of the per-name fold bullet: "Three
   shape and declaration diagnostics sit beside the four discard
   kinds: `:malformed-envelope` (an envelope that fails the shape
   gate), `:undeclared-principal` (an author the authority map does
   not declare), and `:no-proof-kind` (a principal declared with
   neither proof kind)."
4. Equivocation scope, in pass 2: "The pair and every later envelope
   of that principal are discarded with `:kind :equivocation`."

The spec diff contains only these four hunks (plus round 1's already
reported file-box line and 8.2 namespace sentence, still uncommitted
in the tree). No other section or file touched; `linker.cljc`
untouched.

## Unrun checks

Node and Dart lanes, per instructions (orchestrator runs them).
Item 1's fix leans on `jing/segment-address?`, already used
cross-host in the `dao.jing` corpus, so no new host hazard is
introduced; `parse-segment-address` is documented "never throws",
keeping `envelope-defect` total on every host.
