Created-GMT: 2026-09-26 12:35:00 GMT
Created-Local: 2026-09-26 19:35:00 +0700
Coding-Agent: glm
Session-ID: 134d70a7-27aa-470f-9315-284a6969bb72

# Task: yin.vm.linker M5, wire yin.repl to the linker (`(require 'foo)` at the prompt)

Role: VM Runtime Engineer

Implementers:
- Model: glm-5.3-flash | Assigned: 2026-09-26 19:35:00 +0700 | Status: active | Rationale: owner directive "route m5 to glm-5.3-flash"

Worktree: /Users/sto/workspace/datomworld-m5 (branch m5 from master 78163e92, which has M1-M4 merged). Uncommitted work only; do NOT commit, merge or push. Read docs/build-n-test.md first for verification commands (kondo is `mise exec -- clojure -M:kondo`, never report it as "not installed").

## Spec
docs/design/yin.vm.linker.md section 9, "M5. yin.repl wiring": the REPL composes the link pair per VM and the content pair per connection (connect already opens the RPC client); `(require 'foo)` at the prompt exercises the whole path. Completion criterion (section 11, the last clause of the M4/M5 test list near line 2026): `(vm :stack)` and `(vm :register)` in yin.repl link the same manifest by H and R respectively and produce B0-equal results. Also read sections 6.3, 7.2, 7.3, 7.4 for the runtime/drive contract.

## What exists (do not rebuild)
- src/cljc/yin/vm/linker.cljc: link-state / request-link / step / abandon, fetch as the blocking driver over a runtime, link-manifest, formats. src/cljc/yin/vm/engine.cljc + module.cljc: require lowering, install child, link-module. src/cljc/yin/repl.cljc: make-vm (~line 410) now mints capability secrets; connect path opens the RPC client.
- test/yin/vm/linker_require_test.cljc has a hand-built `link-pair` and stub responder (~line 188) and installs modules through a REPL-shaped VM built by yin.repl/make-vm PLUS a hand-added link pair. M5 replaces that hand wiring with the real REPL wiring: study that test to see exactly which pieces the REPL must now supply (link pair per VM, content pair per connection, a responder/driver that serves link requests).

## Deliverables
1. In yin.repl (src/cljc/yin/repl.cljc and, only if needed, its neighbors yin/repl/*.cljc): compose the link pair per VM and the content pair per connection so a `(require 'foo)` typed at the prompt reaches a working by-name link and installs. Keep the change small and follow the surrounding style; the REPL owns no clock and no global atom (see the ns docstring).
2. Tests (JVM plus cljs/cljd portable; a new test file such as test/yin/repl/require_test.cljc, or additions to repl_test): (a) require at the prompt of a module served from a stub or in-process content source, on each backend the REPL supports; (b) the H/R criterion: the same manifest linked by (vm :stack) and (vm :register) gives B0-equal results; (c) a link that stays :pending does not wedge the REPL (see failure policy).
3. FAILURE POLICY (open owner decision, linker.md section 12 bullet 4): do NOT invent a deadline or timer in fetch or the engine (fetch stays clock-free by owner ruling; the composition owns liveness). Implement only the simplest thing that does not wedge: the REPL surfaces :pending as its own non-blocking state and offers abandon. In your report, list the options (dao.jing.remote timing options, dao.lease, own) with a recommendation; the owner decides. Do not wire dao.lease.

## Rules (non-negotiable)
Rule R (yin/def is syntax, never a name). require stays an ordinary function. No contract stamp is ever assigned to external input. ClojureDart trap: #?(:clj ...) does NOT exclude code from cljd builds; put :cljd first (#?(:cljd nil :clj ...)); no private var-quote access across namespaces; no bare `type` (use .-runtimeType under :cljd). Code is content-addressed; names come via authority policy. No backward-compat shims. Smallest diff that satisfies the criterion. ASCII only, 80 columns, cljstyle clean (`mise exec -- cljstyle check <files>`), kondo no new warnings.
If the spec or code makes something impossible or ambiguous, choose the fail-closed reading and record it; do not stop silently.

## Verification you run
Touched-namespace JVM tests plus cljstyle/kondo on changed files. The orchestrator runs the three full lanes (JVM, Node after npm ci, Dart solo).

## Report
collab/1790437900000-vm-engineer-linker-m5-repl-wiring.glm-5.3-flash.report.md: what changed (file:line), tests added, decisions, what is left, failure-policy options with recommendation.
