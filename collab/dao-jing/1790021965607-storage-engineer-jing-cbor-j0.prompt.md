Created-GMT: 2026-09-21 20:19:25 GMT
Created-Local: 2026-09-22 03:19:25 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: beaa6614-6252-46df-9441-9525aba543f7
# Task: jing-cbor-j0 — freeze the DaoJing canonical-CBOR encoding contract as fixtures (steps 1-2, phase J0)
Role: DaoSpace & DaoJing Storage Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-22 03:19:25 +07 | Status: active | Rationale: owner authorized autonomous work on the parked DaoJing CBOR epic with GLM; J0 is additive, test-only, needs no owner gate; glm-5.3 is the team's storage/encoding model and is off-peak now

You are a fresh implementer with no prior context. Work ONLY in the git worktree
/Users/sto/workspace/worktree-jing-cbor (branch jing-cbor, HEAD 0dc06197). Do not
touch any other tree. Do NOT stage, commit, merge, or push. One simple Bash
command per step (a compound or piped command can be denied in headless mode: split
it, never a workaround, never `--dangerously-skip-permissions`).

## What you are building

DaoJing (docs/design/dao.jing.md) is replacing its transitional print-based hash
and EDN persistence with canonical CBOR bytes (plan of record:
docs/design/dao.jing.cbor.md, read it IN FULL first, especially "Encoding
contract", "Numeric identity", "Addressing and clean break" and "Implementation
sequence and validation"). This whole epic has owner gates for later steps (dao.space
comparators, rebuild readiness) but PHASE J0 IS NOT BLOCKED: it is fixtures only.

J0 freezes the byte-level contract as literal fixtures BEFORE any codec exists. Later
phases (J1 JVM/Node codec, J2 Dart codec, J3 cross-host conformance) must reproduce
these bytes exactly. Nothing here may call Jing, Boring or any code under test to
GENERATE expected bytes.

Read also (all inside this worktree): collab/1790011562103-ref-architect-work-package.findings.md
(the architect's J0 spec, section "J0", and the risks section) and
collab/1790011825648-ref-architect-prior-art.findings.md (what already exists in
`dao.stream.cbor`: reuse patterns, but the STREAM profile is NOT Jing's profile:
Jing has component-preserving identifier frames, reader-position stripping, its own
numeric carriers, its own named frames `dao.jing/list`, `dao.jing/keyword`,
`dao.jing/symbol`, `dao.jing/float64`). For pattern only: src/cljc/dao/stream/cbor.cljc,
src/cljc/dao/stream/cbor/boring.cljc, test/dao/stream/cbor_test.cljc.

## Deliverables (file box: NEW files only; NO production source changes, NO deps.edn or
## pubspec.yaml changes, NO edits to existing files)

1. `test/resources/dao/jing/cbor-v1.json` — language-neutral, stable data: for each
   case: a stable case id; a semantic INPUT written in a small language-neutral DSL
   that YOU define and document (typed leaf forms, e.g. {"t":"int","v":"123"},
   {"t":"float64","bits":"..."} with numbers as strings so no host rounds them);
   the expected CANONICAL HEX bytes, or an expected REFUSAL class (for values or
   frames that must be rejected); the expected SHA-256 hex of those bytes; an
   equivalence-group id where cases must produce IDENTICAL bytes; an
   identity-group/retained-distinction id where they must DIFFER; and explanatory
   notes citing the plan section each case pins.
2. `test/resources/dao/jing/cbor-v1.README.md` — provenance: the plan version
   (git commit), dependency pins (Boring 0.1.30, Dart cbor 6.5.1), the DSL
   definition, the generation method, the independent-reader method, reviewers
   (leave a placeholder list), how a deliberate regeneration must be performed and
   reviewed (dependency upgrades may NEVER regenerate fixtures silently), and a
   section AMBIGUITIES listing every place the plan does not fix a byte and how you
   resolved it or that you left it open (do NOT guess silently: record it).
3. `test/resources/dao/jing/cbor-v1.generate.py` — Python 3 STANDARD LIBRARY ONLY
   (struct, hashlib, json), independent of the Clojure toolchain and of Boring: a
   small deterministic-CBOR encoder (RFC 8949 section 4.2 core deterministic rules:
   shortest integer/float forms, definite lengths, bytewise-sorted map keys, tag
   framing, byte and text strings) plus the Jing profile rules from the plan, that
   emits the JSON file. Run it once to produce the JSON; commit-ready output must be
   reproducible by re-running the script. Also include a tiny independent CBOR
   READER in the script (pure stdlib) with a `--inspect` mode that prints each
   fixture's decoded structure (major types, tags, named frames) WITHOUT any Jing
   knowledge, so a reviewer can eyeball that tags, frame names and map ordering are
   what the plan says.
4. `test/dao/jing/cbor_fixtures.cljc` — a `.cljc` namespace `dao.jing.cbor-fixtures`
   that LOADS the JSON on JVM, Node (CLJS) and Dart (CLJD) and provides accessors,
   plus GREEN structural self-tests that need NO codec: every hex string is well
   formed; the recorded SHA-256 equals the SHA-256 of the hex bytes (use
   `dao.jing/sha256-bytes` or a host digest; check what exists); case ids are
   unique; every equivalence group has identical hex; every retained-distinction
   group has pairwise different hex AND different addresses; the pathological
   identifier class is pairwise distinct; every required coverage category (below)
   has at least one case; JSON round-trips. If loading a resource file needs a
   host-specific reader, gate it with reader conditionals (`:cljd` FIRST, e.g.
   `#?(:cljd ... :clj ... :cljs ...)`; never put :cljd last, and `#?(:clj ...)` alone
   does NOT exclude code from the ClojureDart build). Look at how existing tests
   load files on all three hosts (test/dao/jing/file_test.cljc has host file
   helpers) and reuse the pattern. The Dart test build may need the resource
   copied or referenced by a path the CLJD lane can read; find out how the repo's
   CLJD lane sees `test/`, and record your finding in the README instead of
   guessing. If Dart cannot load it in J0, gate the Dart part with a clear
   `#?(:cljd nil ...)` and record it as a J2 obligation in the README.

## Coverage the corpus MUST have (from the architect's J0 spec, plan sections cited)

- Map/set insertion order; mixed-type keys; sorted collections; duplicate
  canonical/equality collapse.
- list versus vector distinction and all four tag-27 named frames
  (`dao.jing/list`, `dao.jing/keyword`, `dao.jing/symbol`, `dao.jing/float64`),
  including MALFORMED payload shapes for each.
- Metadata: retention, reader-position stripping (:line :column :end-line
  :end-column), empty-metadata omission versus empty or reader-position-only
  metadata, symbol metadata.
- Unicode: astral text, noncharacters, composed versus decomposed preserved
  (NO normalization), invalid UTF-8 rejected, unpaired surrogate rejected
  (including inside metadata and identifiers).
- Pathological identifiers: `(symbol "42")` versus integer 42; `(keyword "a/b")` with
  ns nil and name "a/b" versus `(keyword "a" "b")`; the symbol pair; names with
  whitespace or a leading colon; nil versus empty namespace; tag 39 REJECTED even
  for ordinary names.
- Byte strings: content identity and mutation isolation.
- Numerics: integer versus integral float, signed zero, infinities, ONE canonical
  NaN, integer boundaries and bignums, decimal scale, reduced ratios and
  denominator-1 ratios, native float32 widening to the exact float64 eight bytes
  (distinct from float64 0.1), native CBOR floats rejected.
- Malformed and non-canonical encodings: duplicate map keys, duplicate set
  elements, unknown tags, TaggedValue and unsupported simple values, trailing data,
  wrong key ordering, non-shortest integers, indefinite lengths.
- The effective Boring options: stringref OFF, shapes OFF, no index frame: fixtures
  where a stringref, a shaped array or an index frame WOULD appear if the option
  were on.
- Pairwise INJECTIVITY: every distinct normalized identity has distinct bytes and a
  distinct fixture address; SHA-256 collision resistance is the addressing
  assumption, never claimed as digest injectivity.
Equivalence groups (identical bytes): integer width, sortedness, sequence
realization, stripped or empty metadata, reduced ratios, canonical NaNs, float32
widening. Retained distinctions (different bytes): numeric kind, decimal scale,
signed zero, retained metadata, every distinct pathological identifier.

The frozen file must make FUTURE implementation tests red or unimplemented; it must
NOT be generated by, or depend on, the codec under test. Where the plan is silent or
self-contradictory about a byte, record it under AMBIGUITIES with the options; an
independent reviewer and the architect will resolve it. Do not invent plan text.

## Environment (mise is untrusted in this worktree: export before running builds)

    M=$HOME/.local/share/mise/installs
    export JAVA_HOME="$M/java/17"
    export PATH="$JAVA_HOME/bin:$M/clojure/1.12.4.1602/bin:$M/babashka/1.13.223:$M/babashka/1.13.223/bin:$M/flutter/3.47.4-stable/bin:$M/node/25.6.1/bin:$M/cljstyle/0.17.642:$M/cljstyle/0.17.642/bin:$PATH"

(CLJS lane needs Java 21: `export JAVA_HOME="$M/java/21"` and put its bin first.)

## Verification (report exact commands and counts)

- Run the generator: `python3 test/resources/dao/jing/cbor-v1.generate.py` and show it
  reproduces the JSON byte for byte on a second run (`shasum` twice).
- `python3 test/resources/dao/jing/cbor-v1.generate.py --inspect` prints without error.
- JVM focused: `clojure -M:test -n dao.jing.cbor-fixtures-test` (or the namespace name
  the runner discovers; check how test namespaces are discovered) with exact counts.
- If feasible, the CLJS lane focused run; the orchestrator reruns all lanes itself
  including CLJD, so say what you could not run.
- `clj-kondo` and `cljstyle check` on your `.cljc` file (they may be denied in your
  headless session: then say so and skip; the orchestrator runs them).
Never report a count you did not observe. If a step is denied, say which and why.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: beaa6614-6252-46df-9441-9525aba543f7
Then: files created; case count per coverage category; the DSL in one paragraph;
the AMBIGUITIES list; what you ran with exact counts; what you could not run; every
deviation from this brief. State facts only; promise nothing.
