Created-GMT: 2026-10-19 12:29:20 GMT
Created-Local: 2026-10-19 19:29:20 +07

# Task: feasibility spike — get yin.repl to run under jolt (Clojure on Chez Scheme)

Role: Engineer (implementation spike; exploratory, time-boxed)

## Context

datom.world is a Clojure(Script) monorepo. `src/cljc/yin/repl.cljc` (2,174 lines)
is the REPL entry namespace; it requires the yin.vm kernel stack, dao.jing
(content-addressed storage, BLAKE3 hashing, CBOR), and dao.stream machinery.
The codebase is CLJC with per-host branches (`:clj` JVM, `:cljs` Node/browser,
`:cljd` Dart) — see e.g. `src/cljc/yin/vm/integer/host.cljc` and the B1 scalar
encoder's per-host byte handling.

An architecture discussion (orchestrator seat, 2026-10-19) identified jolt —
https://github.com/lotuc/jolt, a Clojure implementation that compiles to Chez
Scheme for native AOT binaries — as a candidate FOURTH host lane: a cheap road
to a native standalone node, competing with a planned Rust port. Your spike's
findings feed that decision. The question you are answering, concretely: **how
far can the existing yin.repl codebase get under jolt today, and what exactly
blocks it?**

No Chez or jolt is installed on this machine yet. You will install both.

## Plan (phased; stop and report if a phase is truly blocking)

0. **Install.** Build/install Chez Scheme and jolt (clone lotuc/jolt; follow its
   README). Keep everything under a scratch directory that does not pollute the
   repo (suggest `build/jolt-spike/` — `build/` is already untracked build
   space). Record exact versions/commits used.
1. **Smoke jolt itself.** Get jolt's own examples running (a REPL round-trip,
   defn + persistent map/vector basics). Record what of clojure.core works and
   what is missing/stubbed, especially: reader conditionals (`#?`), `:require`
   with `:as`/`:refer`, `deftype`/`defrecord`/protocols, `atom`/`volatile!`,
   lazy seqs, `clojure.edn`, string/keyword interop surface.
2. **Load one small namespace.** Try making jolt load a leaf yin/dao namespace
   (e.g. `src/cljc/dao/jing/hash_registry.cljc` or similar small one; pick
   minimal transitive weight). You will likely need a `#?` default-branch
   strategy or a preprocessing shim (jolt may not read `.cljc` reader
   conditionals — determine this and solve it the least-invasive way: a
   preprocessor that strips/expands conditionals for the `:cljs`-ish default is
   acceptable if documented).
3. **Hash parity spot-check.** dao.jing's BLAKE3 + canonical CBOR must be
   bit-exact across hosts. Find how hashing is done on the JVM branch
   (java.security / a lib?) and on :cljs; determine jolt/Chez's options (Chez
   FFI to a C blake3, or a pure-Scheme implementation). Getting actual parity
   is a stretch goal; cataloging the gap precisely is the required outcome.
4. **Climb the dependency graph toward yin.repl.** Attempt as much of the
   transitive graph as time allows (dao.jing → dao.stream.apply → yin.vm
   values/engine/register → yin.repl). At each blocker, record: the construct
   that failed, the file:line, jolt's error, and the smallest workaround
   (shim/patch/branch) if one exists.
5. **REPL entry.** If the graph ever loads, try yin.repl's entry function(s)
   with a trivial program. Full success is unlikely and that is FINE — the
   deliverable is the gap catalog and feasibility verdict, not a working port.

## Constraints

- Do NOT commit anything. Do not modify tracked files under src/ test/ docs/
  unless strictly needed as a minimal, clearly-listed patch — prefer shims
  living outside src/ (scratch dir) or a git branch you create for the spike;
  if you patch tracked files, list every one in the findings with the reason.
- Installs stay in the scratch dir (or standard toolchain locations if the
  build requires it — record what went where; nothing system-wide beyond
  Chez/jolt build outputs).
- Time-box: prefer breadth of the gap catalog over depth on any one blocker.
  If jolt cannot even read .cljc files and no shim is workable, that IS the
  finding — report it with evidence.

## Deliverables

Write your report to:
collab/1792412960461-engineer-jolt-yin-repl-spike.claude-opus-5-5.findings.md

Required sections:
1. Environment: exact Chez/jolt versions, install method, what's where.
2. jolt capability inventory (phase 1 results) — what works, what's missing,
   with the smallest repro for each gap.
3. The .cljc/reader-conditional question and your solution (or why none is
   workable).
4. Hash/FFI gap analysis (phase 3).
5. The climb log: how far the graph got, every blocker with file:line + error
   + workaround status.
6. Feasibility verdict for "jolt as a fourth host lane": distance-to-viable
   (what would need to be built, rough size), and whether the result would be
   worth it versus the Rust port (which already has a design doc,
   docs/design/yin.vm.rust.md).
7. Repro notes: commands to recreate your farthest point.

Be honest and evidence-based throughout — a negative result with precise gaps
is exactly as valuable as a boot. Begin with phase 0 now.
