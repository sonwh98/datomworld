Created-GMT: 2026-09-21 23:05:00 GMT
Created-Local: 2026-09-22 06:05:00 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8 (resumed: your J0 and J1 turns)
# Task: jing-cbor-j2: the Dart (ClojureDart) canonical CBOR codec and carriers
Role: DaoSpace & DaoJing Storage Engineer
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-22 06:05:00 +07 | Status: active | Rationale: same implementer as J0 and J1 (resume; it holds the codec and corpus context); J1 is committed and architect-signed; the Dart reviewer will be a different family

Work ONLY in /Users/sto/workspace/worktree-jing-cbor (branch jing-cbor, HEAD 4f928dbd;
launch directory). Do NOT stage, commit, merge or push. This session may run the
CLJD lane and the linters through TWO wrapper scripts (the only sanctioned way; you
are the single owner of the CLJD lane and generated output for this task):
- Full CLJD lane (builds the AOT peers, then `clojure -M:cljd test`; slow):
  /private/tmp/claude-501/-Users-sto-workspace-datomworld/a9895f5c-8978-4964-9887-e3f9f05e8caf/scratchpad/lanes/cljd.sh
- Faster iteration (only `clojure -M:cljd test`; skips the peer builds):
  /private/tmp/claude-501/-Users-sto-workspace-datomworld/a9895f5c-8978-4964-9887-e3f9f05e8caf/scratchpad/lanes/cljd-fast.sh
- Lint (kondo and cljstyle over the jing cbor files; `lint.sh fix` runs cljstyle fix):
  /private/tmp/claude-501/-Users-sto-workspace-datomworld/a9895f5c-8978-4964-9887-e3f9f05e8caf/scratchpad/lanes/lint.sh
Run each script ALONE as one simple command (no pipes, no `&&`, no env prefixes).
Each writes its full log next to itself (cljd-last.log, cljd-fast-last.log) and
prints the verdict and the first failures; read those logs with the Read tool. The
JVM (`clojure -M:test -n dao.jing.cbor-fixtures-test -n dao.jing.cbor-test`) and
`bb test:cljs` you already run directly.

## Spec (architect's J2; sign-off constraints from the J1 turn)

Read: collab/1790011562103-architect-jing-cbor-work-package.gpt-5.6-sol.findings.md
(sections J2 and "Dart route"), collab/1790027800000-ref-architect-j1-signoff.findings.md (the ratified
constraints, restated here):
- Use the pinned `cbor` 6.5.1 package as the Dart byte WRITER; do not hand-roll a
  CBOR parser as the writer. The READER mirrors J1: a Jing-owned structural validator
  runs BEFORE any package materialization; every accepted decoded value is re-encoded
  and must reproduce the input bytes exactly. (J1's reader in src/cljc/dao/jing/cbor.cljc
  is a Jing-owned tree reader over bytes; port or reuse its structure for Dart. If a
  shared part can stay in the cljc file with `:cljd` branches, prefer that over a
  copy; if not, a src/cljd file is the box. Justify the choice in the report.)
- Emit the outer `clojure/with-meta` frame explicitly (around dao.jing/list and
  dao.jing/symbol too); the exact decimal exponent window [-2147483647, 2147483648];
  `max-depth` 128 (decode :malformed-cbor, encode :unsupported-value); the
  `host-collapse` class only if Dart genuinely cannot materialize a frozen value
  without merging entries (test Dart identifier equality for the slash-crossed
  keyword and symbol cases and report what you observe).
- Preserve float64 kind, signed zero, infinities and the one canonical NaN; never
  narrow an integral float to an integer; native CBOR floats are refused. BigInt or
  explicit carriers for exact integers, decimal and rational; reject unsafe or lossy
  conversions. Do NOT compare hash numbers across hosts; fixture hex and SHA-256 are
  the cross-host authority.
- Read test/resources/dao/jing/cbor-v1.errata.md (RATIFIED) as well as the README.
- The frozen corpus, README, generator and cbor_fixtures_test.cljc are IMMUTABLE.
  The loader helpers in cbor_fixtures.cljc are yours to extend (test-only).
- STOP CONDITION: if `cbor` 6.5.1 cannot produce a frozen fixture's exact bytes, stop
  and write a fixture-specific capability report; do not hand-roll a writer.
- File box: the J1 files (src/cljc/dao/jing/cbor.cljc, cbor/boring.cljc) may gain
  `:cljd` branches (the J1 body is currently gated `:cljd nil`; JVM and CLJS behavior
  must not change and must stay green); NEW src/cljd/dao/jing/cbor/... files as
  needed; NEW test files (a cljd test namespace, or cbor_test.cljc extended so the
  same corpus runner executes on Dart); the fixture helpers. No pubspec.yaml or
  pubspec.lock edits; no edits to dao.jing, its backends, dao.space or transport;
  no deps.edn or bb.edn edits.

## Completion criteria

- Dart runs EVERY corpus case it can build: canonical cases encode to the frozen hex
  and SHA-256 and decode back equal; every encode and decode refusal case is refused
  with the frozen class; skips only by id with a reason in the errata table, never
  silent. Report the run and skipped counts per kind.
- Direct portable equality/hash/compare and the numeric edge tests agree with JVM and
  Node on the same cases (exact comparison, symmetry, zero-sign, NaN, infinities).
- Nothing regresses: JVM (Java 17, full `clojure -M:test` is run by the orchestrator;
  you run the focused pair), CLJS, and the whole CLJD lane (cljd.sh) stay green.

## ClojureDart traps (from this repo)

- `#?(:clj ...)` does NOT exclude code from the cljd build; use
  `#?(:cljd X :clj Y)` with :cljd FIRST.
- A Dart list literal is refused by dao.jing.file; a cljd ExceptionInfo, the `dart:core`
  alias, cljs keyword identity and private mutable fields all cross hosts badly; read
  an existing cljd file (src/cljc/dao/stream/cbor.cljc and its Dart path, dao.data.btree)
  for the working idioms.
- Dart `int` is 64-bit on the VM, so an integral double and an integer are different
  Dart types but a JS-style conflation must not creep in; use `BigInt` beyond 64 bits.
- Keep files pure ASCII, no em dashes, cljstyle-style Clojure.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8
Then: files created or changed (and the shared-vs-separate choice with its reason);
how each completion criterion is met; corpus cases run per kind and skipped by id with
reason; the host-collapse and Dart-equality observations; every deviation; what you ran
with exact counts (JVM focused, CLJS, cljd.sh, lint.sh); what you could not run. If you
stopped at the cbor 6.5.1 stop condition, say which fixtures and why. Facts only.
