Created-GMT: 2026-11-22 20:22:57 GMT
Created-Local: 2026-11-23 03:22:57 ICT (+07)
Coding-Agent: glm-5.3 (routine review seat)
Session-ID: 1ba02c6c-a8d3-4eea-8377-14aff23bf770

# Task: dart-args fix — review of the 7-line dht_args_test.cljc change

Reviewed: uncommitted diff on store-swap-p1, test/yin/repl/dht_args_test.cljc
(+7/−2, the only tracked change; git status shows no src/ modification, only
collab/ artifacts untracked). Checked against the argument-expansion code it
exercises (yin.shell.cli dht-args/expand-args, yin.repl.main startup) and the
engineer report 1795380000000. No file edited.

## Checks

### 1. Assertions' intent unchanged — CONFIRMED

All eight assertions of join-after-global-flags-keeps-the-explicit-store and
all three of startup-expands-the-supplied-reader-command-before-parsing are
byte-identical to the pre-change versions. The diff only (a) appends
explicit "--dir target/dht-args-default-reader --key target/dht-args-reader.key"
(join-paths, dht_args_test.cljc:12-13) to both tests' argvs, and (b) adds a
test-local ":load-key (constantly nil)" seam to the startup fixture's
composition (:50). No expectation, peer/follow value, or refusal assertion
was touched.

### 2. The explicit paths do not weaken the tests — CONFIRMED (strengthened)

- Why the paths were needed: dht-args derives the store dir and key from
  state-path → home-dir (cli.cljc:420-421, 317-328), and home-dir* is
  #?(:cljd nil ...) by design, so a Dart run without explicit --dir/--key
  refused ("no home directory on this host"). The explicit flags remove the
  home dependency on every host — including the latent one on JVM/Node
  (engineer verified JVM green with home-dir* redefed to nil, and Node green
  with HOME unset), a robustness gain, not a behavior change.
- The "explicit store wins" assertion stays discriminating: dht-args emits
  its own "--index-store dht:<dir>" from --dir (cli.cljc:421), so if the
  scratch dir had been named "reader" the (= "reader" (:dir spec)) assertion
  would have gone vacuous (both sources would say "reader"). The chosen
  target/dht-args-default-reader keeps the assertion proving that the
  explicit global --index-store dht:reader overrides the subcommand-derived
  store in all three flag positions — the engineer's stated rationale is
  correct and material.
- Position safety: --dir/--key are value flags of the subcommand grammar
  (cli.cljc:378, 399-400); appended after the token they are consumed with
  their values and do not disturb token capture (first non-flag arg,
  cli.cljc:408-410) — the same tail position test 2 (init-and-serve,
  unmodified) already uses for "--dir node --key node.key". The expansion
  property under test is exercised with two more out-of-canon-position
  flags, i.e. harder, not easier.
- (:unset extra) = #{"--dht-publish" "--dht-key"} is unconditional for
  join (cli.cljc:439) and unaffected by giving --dir/--key.
- parse-args does not load key files (only startup does, via the
  composition hook at main.cljc:719-721; the in-session loader lives at
  cli.cljc:639), so test 1 passes a --key whose file does not exist without
  any file read — JVM/Node/Dart green confirms.
- The ":load-key (constantly nil)" seam: main/startup calls
  ((:load-key composition) path) only when :publisher-key-file is present
  (main.cljc:719-721) — which the new --key (emitted as --dht-key by
  dht-args, cli.cljc:420-423) now makes true. The real load-key
  (cli.cljc:136-155, hooked at :795) would refuse on the nonexistent
  scratch file; the stub returns nil, which is exactly the reader/join
  role's semantics (only init defaults a key, cli.cljc:420). The stub is
  confined to the test-local composition, mirrors the pre-existing :open
  stub pattern, and hides nothing this test asserts (expansion, peers,
  follow, no refusal).
- No filesystem side effects: :persist? false and the :open stub mean no
  state.edn write and no store/lock creation under target/.

### 3. No src/ changes — CONFIRMED

git status: the only modified tracked file is test/yin/repl/dht_args_test.cljc.

### 4. JVM/Node behavior unchanged — CONFIRMED

The change is confined to a test namespace; src/ is untouched. Verification
of record (engineer + orchestrator): JVM 4 tests/26 assertions green; JVM
with home discovery disabled green; Node with HOME unset green; Dart compile
rc=0 with all 4 tests green — matching the base-compile context (cljd rc=1
at ea8e8e15 and master 4a8ef025 before the slices).

## Findings

LOW | none | — | —

(No low-or-higher findings; the change does exactly what its report claims
and nothing else.)

INFO | test/yin/repl/dht_args_test.cljc:12-13 | join-paths bundles "--dir"
and "--key" flags under a name suggesting bare paths; purely a naming nit,
consistent with the tail idiom of the init-and-serve test. | None required.

INFO | test/yin/repl/dht_args_test.cljc:50 | The ":load-key (constantly
nil)" stub also means a future regression in startup's key threading (e.g.
dropping :publisher-key-file from opts) would pass silently here; that is
outside this test's intent (argument expansion), and the reader role is
keyless by design. | None required; if ever desired, a variant test with a
real scratch key file would cover the load path.

## Verdict

APPROVED
