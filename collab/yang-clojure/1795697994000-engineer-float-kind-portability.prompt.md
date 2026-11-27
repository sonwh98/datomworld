Created-GMT: 2026-11-26 12:59:54 GMT
Created-Local: 2026-11-26 19:59:54 +0700
Coding-Agent: agy
Session-ID: pending (conversation_id)

# Task: fix/float-kind-portability r1 (migration) — TDD: lexical preservation of Clojure float literals, per architect ruling 1795693415000

Role: Compiler Engineer (numeric semantics)

## State resume (by state — fresh session on jupiter; the prior darkstar session was superseded mid-bootstrap)

Work clone: ~/ci/datomworld-floatkind on jupiter, detached @ origin/fix/float-kind-portability (09401506, CURRENT verified). The prior darkstar glm session (519cc353) was stopped during bootstrap before writing any gate tests — its sole on-disk product is the gate-6 legacy-custody capture pair (test/yang/floatkind_legacy_capture_test.cljc + test/resources/yang/clojure/float-kind-legacy-jvm-float.cbor, committed 09401506). Everything else is yours to start.

## Authority (read first, in the clone)

1. **The ruling (binding, normative)**: collab/yang-clojure/1795693415000-architect-float-kind-portability-ruling.gpt-6-astra.findings.md — all five rulings. RULING 5 is your contract: file boundaries, the seven acceptance gates, the re-pin protocol.
2. The original round brief (unchanged requirements): collab/yang-clojure/1795695531000-engineer-float-kind-portability.prompt.md — TDD IS THE REQUIRED METHOD (owner instruction, binding): write the seven gates as tests FIRST, run them RED on JVM + Node, save red evidence to collab/yang-clojure/<new-ts>-floatkind-evidence/, THEN implement. The shared numeric-token factory goes in the yang.core shared layer (one implementation, per-language lexeme tables — owner direction: all four frontends share it; ts/php/python adapters move to it, their existing boxing is a control).
3. The ts precedent: src/cljc/yang/typescript/antlr/lower.cljc:429-440 (token → (cbor/float64 v)).
4. Probes already established (astra's verification + r3 evidence): JVM read-forms keeps Double for 1.0/0.0; BOTH cljs readers (tools.reader + cljs.reader) return bare Numbers for 1/1.0/1e0; data/equiv(float(1), int(1)) is false in the VM model; content= is kind-strict.

## Toolchain (jupiter)

mise shims for bb/clojure/clj; node under ~/.nvm/versions/node/v24.13.0/bin. In every fresh shell: export PATH="$HOME/.nvm/versions/node/v24.13.0/bin:$HOME/.local/share/mise/shims:$HOME/.local/bin:$PATH"; mise trust in the clone; npm deps: bb deps:npm or npm ci; bb gen:antlr before any compiler lane. Run long lanes FOREGROUND with explicit timeouts; do NOT background anything and wait on notifications.

## The work (TDD order — same as the original brief)

1. RED: the seven gates as portable tests (RULING 5's gate list is your test plan; gate 1 first: identical source strings JVM vs Node through the frontend entry + REPL/catalog route; the token list, controls, and refusal categories are specified there). Run RED on JVM and Node locally on jupiter (48 cores; single-namespace + targeted runs are fine; one Dart lane at a time; Jolt via jolt_j2_lane with --jobs 16 per build-n-test). RED evidence: collab/yang-clojure/<new-ts>-floatkind-evidence/.
2. GREEN: the shared yang.core numeric-token factory; the Clojure ingestion stage (lexical-coverage demonstration BEFORE choosing the minimal mechanism — the ruling requires this demonstration); carrier admission + execution reboxing. Then all seven gates green: JVM + Node locally; Dart + Jolt legs on this host (it has flutter + jolt; the J2 manifest note in test/yang context: the four formerly-unlisted namespaces were reconciled as a no-op — verify the manifest passes; if the J2 inventory still refuses, record it as the known external dependency, do not bypass).
3. Migration per RULING 3: new Clojure frontend revision + profile (retaining the collection-ordering contract), legacy custody (the committed capture pair is your gate-6 seed — extend per RULING 5 gate 6), doc placements per RULING 4.
4. Re-pin per RULING 5's protocol, only after the gates explain the change.

## Bounds + deliverable

RULING 5's file boundaries exactly; outside them (codec, VM equality, upstream reader) report as dependencies — never fix incidentally. kondo 0 errors on touched files. Deliverable: collab/yang-clojure/<new-ts>-engineer-float-kind-portability-r1.gemini-4-neon.findings.md (write in the clone; the orchestrator mirrors). RED evidence + per-gate commands/counts per host are mandatory. Do not end the turn with runnable work remaining. Commit NOTHING — the orchestrator commits after review.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
