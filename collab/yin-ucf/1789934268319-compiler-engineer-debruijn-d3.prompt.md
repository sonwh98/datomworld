Created-GMT: 2026-09-20 19:57:48 GMT
Created-Local: 2026-09-21 02:57:48 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 923b8885-4549-4b46-ad11-0731ebb614ef (resumed — your D0+D1+D2 session)
# Task: implement D3 of the de Bruijn projection — canonical encoder and cross-host byte identity
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 02:57:48 +07 | Status: active | Rationale: same implementer; D2 signed off (gpt-5.6-sol granted proceeding to D3)

You are in the git worktree at /Users/sto/workspace/worktree-debruijn-impl
(branch debruijn-impl, your D2 committed as 102ba192). Same rules as before:
work only in this worktree; do not stage, commit, merge, or push; one simple
command per step; the mise environment block from your build brief applies.

Read: docs/design/yin.vm.debruijn-projection.md §5 and §7's D3 (every sentence
a rule), §8's cross-host rows, your own encoder paths in debruijn.cljc, and
the repo's existing cross-host pair-test mechanism (bb test builds a Dart
peer; the JVM/Node lanes spawn it — reuse that precedent for byte-equality
assertions rather than inventing a new one).

Deliverable — D3 only, in src/cljc/yin/vm/debruijn.cljc and
test/yin/vm/debruijn_test.cljc (plus, if the cross-host fixture transport
demands it, the minimal existing-pair-test scaffolding — ask before touching
anything beyond those files):
- Finalize the byte rules IN PLACE (shape stays, rules settle), §5:
  - integers and integral doubles in int64 range encode as int64 on every
    host (1 ≡ 1.0 recorded collision); other in-domain numbers as IEEE-754
    doubles with one quiet-NaN encoding; +0.0 and -0.0 distinct; a JS number
    is int64 only when a safe integer; unsafe-integral classification loss →
    diagnostic.
  - length prefixes count BYTES of the UTF-8 encoding, not UTF-16 code units
    (your D2 provisional counted code units).
  - keyword/symbol namespace and name parts, and all strings, NFC through the
    one seam (already true for idents from your D2 fix; make strings'
    canonicalization uniform).
- Canonicalize scalars INSIDE records (the carried D3 obligation): a record's
  stored scalar is its canonical spelling — long for integral doubles, the
  NFC form for strings/ident parts — so one content address holds one record
  content and equal fingerprints imply equal :records maps (verify JVM {:value
  1} vs {:value 1.0} records now compare equal).
- Re-pin the dimension hash: encode the descriptor data through the now-settled
  canonical encoder and hash it, replacing the transitional dao.jing digest —
  descriptor content unchanged, digest now from the settled encoder. Update
  the docstrings that called the digest transitional.
- §8 cross-host rows: shuffled datom input → identical bytes; numeric boundary,
  NaN, signed-zero, JS-number, and NFC-limit fixtures; integral-double hashing
  identically to its int64 counterpart on every host. Assert byte-identity
  across hosts on the canonical fixtures through the repo's cross-host pair
  mechanism (preimage hex and fingerprint hex must agree JVM ↔ CLJS ↔ CLJD).

Completion (§7-D3): CLJ, CLJS, and CLJD produce identical bytes and all
canonical fixtures hash identically across hosts.

Verification (report exact counts): focused JVM, kondo, cljstyle; the CLJS
lane; and the cross-host pair assertions you added. The orchestrator reruns
everything including CLJD. One simple command per step.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
