Created-GMT: 2026-09-16 15:31:00 GMT
Created-Local: 2026-09-16 22:31:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: fcfd1a00-5beb-4333-a17c-0128b463c211 (resume)

# Task: Confirm corrections to the dao.jing.cbor design

Role: Adversarial Review

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-16 22:31:00 +07 | Status: active | Rationale: same reviewer session, confirming the revision your findings drove

Your findings were reconciled and an implementer (gpt-6-astra, independent
family) revised docs/design/dao.jing.cbor.md (design documentation only; no
code changed). The orchestrator verified the revision independently:
file-change events confined to that one file; the "existing four-byte
big-endian signed length prefix" claim spot-checked against
src/cljc/dao/jing/file.cljc (correct); and all upstream facts the revision
cites were re-verified by the orchestrator against primary sources earlier
today (boring 0.1.30 latest on Clojars; COMPATIBILITY.md byte-stability
policy; writer.cljs tag-39 identifier arm with colon prefix and
slash-joined text; tag-27 frames as [name payload] arrays with built-in
array payloads — clojure/queue, clojure/with-meta; tagged-literal write
path and UnknownRecord passthrough; TaggedValue retention for unknown
numeric tags; Decimal/Rational carriers on both hosts in data.cljc; no
upstream float64 wrapper; konserve BoringSerializer marked BETA).

Disposition of your findings — all accepted and incorporated:

- P2-1 → new portable `=`/`hash`/`compare` contract paragraph in *Numeric
  identity*, naming dao.data.btree datom ordering and dao.space.query
  matching/unification, plus Node/Dart ordered/matched-position scenarios.
- P2-2 → unpaired-surrogate rejection on every host (including identifier
  components and metadata), invalid-UTF-8 decode rejection, your
  unverified-assumption 2 recorded as "Boring's own surrogate
  pre-validation is unverified; Jing owns this rule regardless".
- P2-3 → all six additions: surrogate fixture, float32-widening scenario,
  empty-collections±metadata, carrier equality/ordering smoke, named
  pathological identifier fixtures, and an injectivity acceptance clause
  scoped to identity-after-declared-normalizations.
- P3-4 → raw 32-byte digest frames; wrapper owns keyword↔digest; replay is
  byte-compare.
- P3-5 → 1200-byte DHT budget named (node.cljc), no WS cap today
  (remote.cljc), Base64 arithmetic with envelope-overhead E and
  final-message-size check, local-only degradation kept; DECIDED: no new
  WS byte cap in this migration.
- P3-6 → canonicality verification once at ingress (remote/DHT receipt,
  file replay acceptance); ordinary reads hash-verify and decode without
  re-encoding.
- P3-7 → wrapper derives/verifies; backends re-verify untrusted-party puts.
- P3-8 → mixed-version failure modes recorded both directions, including
  Base64-shaped legacy strings rejected at profile/address checks;
  coordinated upgrades stated.
- P3-9 → Konserve BETA qualifier with source.
- Unresolved decision 2 → DECIDED: ALL identifiers escape to
  dao.jing/keyword|symbol named frames; native tag 39 is rejected entirely;
  your unverified-assumption 1 is now RESOLVED by orchestrator verification
  (tag 39, colon-prefixed slash-joined text; collision classes exactly as
  the design now records).
- Architect addition → effect-payload-shape/convertibility sentence added
  in *Layering and interfaces*.

New decisions the implementer made beyond your findings, which you should
challenge specifically:

A. Portable numeric EQUALITY is by exact mathematical value across kinds
   (integer 1 and float 1.0 compare equal and hash equal; addresses still
   differ). Is this consistent with dao.data.btree ordering and
   dao.space.query matching semantics, or does it create a consumer-visible
   inconsistency with Clojure host `=` on the JVM (where (= 1 1.0) is
   false)?
B. Maps/sets whose entries collide under that portable equality
   (e.g. {1 :a, 1.0 :b}) are rejected on encode and before decoded
   collection construction — "do not let the accepting host determine this
   boundary". Sound, or too restrictive?
C. First-frame recognition: a nonempty file with no valid first frame is
   rejected without mutation, deliberately refusing an interrupted first
   write rather than adding a format header. Acceptable?
D. On JavaScript, native negative zero is classified as floating-point
   content (sign survives via the float64 carrier) while other integral
   Numbers are integers. Consistent?

Re-read the revised docs/design/dao.jing.cbor.md (and only whatever else
you need). Do not repeat resolved findings unless the correction is
incomplete. Challenge the dispositions and decisions above. Do not edit
files.

Begin the final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07 (Asia/Ho_Chi_Minh)>

Then: any NEW findings as `P0-P3 | file:line | evidence | concrete fix`;
your verdict on each challenged decision A-D; and a final verdict —
ready, ready with corrections, or needs redesign — for the design's
implementation readiness. State explicitly whether the design is ready to
implement.
