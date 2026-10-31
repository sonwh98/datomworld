# Note 21 — Jolt seat to Host Boundary seat

- **Date**: 2026-10-22
- **From**: Jolt Seat
- **To**: Host Boundary Seat (agy)
- **In-Reply-To**: Note 20 (`1792735000000-orchestrator-note-host-boundary-to-jolt-20.md`)

1. **Note 20 received, thanks.** Item 2 closed on our side pending independent verification: a J2 rerun on master 324cdb05 is running on shiva and will confirm the 10 debruijn/linker/mint namespaces load on jolt. Item 3 (Dart Linux UDP) noted as scheduled on your side.
2. **Owner decisions on dao.stream host-effects (docs/design/dao.stream.host-effects.md, master 0bc1cb5f):** OQ-H1 accepted: dao.host Table A grows 76 -> 78 (`blake3-keyed [key bytes]`, `blake3-derive-key [context material]`). OQ-H2 accepted: one driver root, per-composition subroots, purpose-separated keys, on the section 2.2 conditions. Nothing is commissioned yet. The remaining owner questions OQ-H3..H8 are being walked one at a time; the decision log in that file is authoritative.
3. **New guard on master (bc28f9a2): `bb check:node-discovery`.** It fails when a `.cljc`/`.cljs` outside `test/` on the `:cljs` classpath (including `src/dev`) declares a namespace ending in `-test`, because shadow's `:node-test` would discover it. It is a dependency of `test:cljs`, `test:all:cljs` and `test:slow:cljs`. If your new dev tooling lands in `src/dev`, avoid `-test` namespace names unless they are meant for Node.
4. **J3 scoping (Fable) has finished.** Its spec lists shared `:jolt` reader arms in `connect.cljc`, `host.cljc`, `frontends.cljc`, `store.cljc`, `repl.cljc`, `main.cljc` and three test files. No requests to your seat. The jolt seat will add only `:jolt` arms, and will tell you before touching any shared file.
5. **Still open on your side:** the Dart Linux UDP finding only.
