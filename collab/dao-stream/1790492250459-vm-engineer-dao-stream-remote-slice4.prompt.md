Created-GMT: 2026-09-27 14:25:00 GMT
Created-Local: 2026-09-27 21:25:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (dao.stream.remote slice 4)

# Task: dao.stream.remote Implementation — Slice 4 (dao.jing.content)

Role: Stream & Network Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld (branch master @ 83cc8bcd,
clean tracked tree; slices 0-3 are committed).

Implement Slice 4 exactly as the plan defines it. Read first, in order:
- docs/design/dao.stream.remote.implementation-plan.md section 1 (the
  dao.jing.content design: the vocabulary, serve-step with THE one
  ingress canonicality check, dao.jing/accept-bytes! unifying the three
  copies, the stepped client over reflections-or-local-handles, the clj
  blocking driver replacing connect-content!, the async facade, the
  two-descriptor coordinate with :url gone) and the slice-4 row of
  section 3 ("dao.jing.remote tests ported and passing over the new
  module; dao.space.index, btree hydration, the linker M3 and M4 tests
  unchanged in outcome; no require of dao.jing.remote remains")
- docs/design/dao.stream.remote.md section 5 (request-and-response
  service convention; the Linda shape yin.repl and content lookup take)
- docs/design/dao.jing.remote.implementation-plan.md (the old plan,
  for what the old module did)
- The old module: src/cljc/dao/jing/remote.cljc, remote/step.cljc,
  remote/async.cljc, and their tests (the tests you PORT)
- The consumers: src/cljc/dao/space/query.cljc (the coordinate open),
  src/cljc/dao/data/btree/storage.cljc (hydrate-async,
  store-tree-async), yin.vm.linker (the linker's own ingress copy),
  src/cljc/yin/repl/link.cljc if present

Work items:
1. dao.jing/accept-bytes! — the ONE ingress check moved from the old
   private accept-bytes! (including the canonical CBOR decode; the
   linker's copy and remote/step's copy unify onto it).
2. NEW src/cljc/dao/jing/content.cljc (serve-step: the interpreter over
   a requests reader + answers writer + a dao.jing handle; the
   vocabulary exactly as section 1 defines), NEW
   src/cljc/dao/jing/content/step.cljc (the portable stepped client:
   request-put, request-get, request-materialize, step, abandon — the
   old remote.step shape over reflections or local handles), NEW
   src/cljc/dao/jing/content/driver.cljc (clj-only blocking driver
   replacing connect-content!), NEW src/cljc/dao/jing/content/async.cljc
   (the async facade over the new client, same consumer API as
   remote/async).
3. NEW or rewritten src/cljc/dao/jing/coordinate.cljc: the
   two-descriptor coordinate
   ({:dao.jing/type :dao.jing/remote :dao.jing/requests ...
   :dao.jing/answers ...}) resolved to the driver on clj, the stepped
   client elsewhere; the :url form gone.
4. Port the dao.jing.remote tests over the new module (the plan's
   proof), migrate the consumers (dao.space via the coordinate,
   btree/storage to content.async, yin.vm.linker to
   dao.jing/accept-bytes!), and DELETE src/cljc/dao/jing/remote.cljc,
   remote/step.cljc, remote/async.cljc and their tests. No require of
   dao.jing.remote remains anywhere.
5. The deferred ws retirements from slice 3 are NOT in this slice
   (they wait for slice 5's yin.repl.serve deletion) — but if
   deleting dao.jing.remote removes the last consumer blocking one of
   them, note it in your report for slice 5.

Constraints:
- The linker M3/M4 tests, dao.space.index tests, and btree hydration
  tests must pass UNCHANGED IN OUTCOME (their handle sources change,
  their assertions do not).
- No backward compatibility: the old module and its wire shapes are
  deleted, not shimmed (the repo's standing no-backward-compat rule).
- Pure ASCII, <= 80 columns on every added/edited line; cljstyle and
  kondo clean; no commit/stage/checkout/reset/stash; no leftover
  diagnostics.
- Verify: JVM full suite green (current baseline 2,272/183,324/0; the
  counts move as tests are ported — report exact), Node green, Dart
  green. Sequential, solo. Exact counts.
- If the design cannot be implemented as specified (a vocabulary or
  API gap), STOP and report BLOCKED with the specific gap.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
