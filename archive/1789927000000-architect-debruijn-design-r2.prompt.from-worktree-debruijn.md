Created-GMT: 2026-09-20 17:33:30 GMT
Created-Local: 2026-09-21 00:33:30 +07 (Indochina Time)
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your consensus/design thread)
# Task: reconcile the de Bruijn projection design with its adversarial review

Your design (`docs/design/yin.vm.debruijn-projection.md`, authored in this
thread's earlier rounds) was adversarially reviewed by a second architect
(fable-5-1, different family). Verdict: not ready — 2 P1, 8 P2, plus
rulings on the six open questions and a completeness confirmation.

Read first:
- `collab/1789927000000-reviewer-debruijn-design.claude-fable-5-1.findings.md`
  (this tree's copy) — the full review: 2 P1, 8 P2, P3s, the six
  open-question rulings, and the completeness confirmation
- `docs/design/yin.vm.debruijn-projection.md` — your design, to revise

Revise the design to resolve every finding. All are ACCEPTED except where
you improve the prescription:

- P1-1 (duplicate parameters): adopt rightmost-wins within a frame,
  matching `bind-params`; add `(fn [x x] x)` to the test matrix.
- P1-2 (fingerprint vs node sharing): adopt the Merkle correction —
  hash(node) = H(dimension-hash ‖ tag ‖ scalar slots ‖ child hashes in
  order); fingerprint = root hash; ordinals, row-count and the -16 base
  leave the identity; the [eid, context] memo stays as an optimisation
  only. Hash-consed subterms become the real compression story (owner
  constraint 4b).
- P2 macro domain: declare the domain the fully expanded AST; an
  unexpanded macro call site is a diagnostic.
- P2 :yin/tail?: excluded from rows and hash; derived, not persisted.
- P2 root selection: require exactly one :yin/root per projected graph;
  several roots = diagnostic.
- P2 m operation: require assert-only input; a retract is a diagnostic.
  Only then may t and m drop from the identity.
- P2 graph framing: frame on the :yin/root marker; end-of-stream with a
  partial graph is the diagnostic.
- P2 unknown attributes: unknown :yin/* attributes on a walked node are a
  diagnostic; other namespaces ignored; :yin/macro-name named explicitly.
- P2 numeric/NFC: the D0 canonicalisation table (int64; IEEE-754 doubles
  with NaN/±0 rule; JS number classification; out-of-domain = diagnostic);
  NFC collision recorded as an inherited limit; Dart NFC source settled
  before D3.
- P2 lexical-context key: the stack of frame parameter vectors.
- P3s: ordered child hashes for :yin.db/operands (Merkle); rename the
  namespace :yin.debruijn/*; published dimension descriptor per
  datom.md:66 as the fingerprint domain separator; the
  source-to-projected index ephemeral by default.
- Six open-question rulings: adopt all as ruled, per the review's closing
  section.

Also add the review's two P1 regression scenarios to the test matrix
((fn [x x] x); tree-vs-graph emission of the same term) and the review's
P3 notes it marks as test-matrix additions.

Scope: ONLY `docs/design/yin.vm.debruijn-projection.md`. No staging, no
commit. Single simple commands.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07 (Indochina Time)>

Then report: per-finding disposition, the revised test-matrix additions,
and anything unresolved.
