Created-GMT: 2026-09-20 19:25:23 GMT
Created-Local: 2026-09-21 02:25:23 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 923b8885-4549-4b46-ad11-0731ebb614ef (resumed — your D0+D1 session)
# Task: implement D2 of the de Bruijn projection — Merkle records and storage adapter
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 02:25:23 +07 | Status: active | Rationale: same implementer; D0+D1 signed off (gpt-5.6-sol granted proceeding to D2)

You are in the git worktree at /Users/sto/workspace/worktree-debruijn-impl
(branch debruijn-impl, your D0+D1 committed as ceae3cc8). Same rules as your
build brief: work only in this worktree; do not stage, commit, merge, or push;
the environment and one-simple-command discipline are unchanged.

Read: docs/design/yin.vm.debruijn-projection.md §4-§5 and §7's D2 (the
governing contract — every sentence a rule), your own debruijn.cljc, and the
dao.jing content-addressing precedent you already found for the dimension
descriptor. Note the design doc on this branch predates one master commit
(dd5a567a): the vector/list split you already implemented is the recorded
ruling.

Deliverable — D2 only, in src/cljc/yin/vm/debruijn.cljc and
test/yin/vm/debruijn_test.cljc:
- Node hashes per §5's shape: hash(node) over the dimension hash and the
  tag-specific slots in descriptor order; :yin.debruijn/hash and :root
  excluded from every node hash; ordered child hashes preserving operand and
  branch order; fingerprint = hash(root). The encoder follows §5's encoding
  paragraph (tagged, length-delimited; the value-table classes you already
  implemented; NFC through the one seam). D3 will harden the numeric/string
  byte rules and prove cross-host byte identity — do not chase that here, but
  write the encoder through the descriptor so D3 changes rules, not shape.
- Hash-consing: equal subterms within a projection share one hash/record.
- The :yin.debruijn/* projected records and the d5 storage adapter as PURE
  functions in this namespace (projected records ↔ d5 tuple shape with local
  e handles and :yin.debruijn/hash on each entity; the root as an explicit
  root-hash marker; :operands an ordered vector of hashes, never
  cardinality-many). Do NOT modify dao.jing or any existing storage
  namespace; if real integration demands edits there, STOP and report the
  file and shape of the change instead.
- The §8 rows D2 can exercise: the same term emitted as a tree and as a
  graph → identical Merkle nodes and root fingerprint; ordered operand
  changes → different hashes; free-name, literal, key, arity, branch and
  stream-op changes → fingerprint changes; binder names, bound occurrence
  names, :yin/tail?, :yin/macro-name and source tempids never enter identity
  (now proven at hash level, not just graph level); hash-consing of equal
  subterms; shared nodes under equal vs unequal lexical contexts hashing
  accordingly.

Completion (§7-D2): no binder names, bound occurrence names, tail flags, or
source tempids enter identity; tree and graph emission of the same term
produce one semantic graph and one fingerprint.

Verification (report exact counts): focused JVM (clojure -M:test -n
yin.vm.debruijn-test), kondo on both files, cljstyle check. The orchestrator
reruns the full three-host lanes. Same environment rules as the build brief
(mise exports, one simple command per step).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact outcomes, unresolved concerns, incomplete work.
