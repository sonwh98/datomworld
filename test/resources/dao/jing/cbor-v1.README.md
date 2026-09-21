# DaoJing canonical CBOR fixtures, contract v1

Status: J0 candidate, reviewed (deepseek-v4-pro and qwen3.8-max, both READY
WITH CHANGES, no byte disagreements) and revised with their agreed findings;
**not yet frozen**. The corpus becomes the frozen contract only after the
architect signs off. The ambiguity rulings are collected under Rulings below.

These fixtures freeze the byte-level contract of
[`docs/design/dao.jing.cbor.md`](../../../../docs/design/dao.jing.cbor.md)
before any Jing codec exists. Phases J1 (JVM and Node codec), J2 (Dart codec),
and J3 (three-host conformance) must reproduce these bytes exactly. The corpus
was not produced by Jing, by Boring, or by any code under test.

## Files

| File | Role |
|---|---|
| `cbor-v1.json` | The corpus: stable, language-neutral case data. |
| `cbor-v1.generate.py` | Independent Python 3 generator, self-verifier, and `--inspect` reader. |
| `cbor-v1.README.md` | This file: provenance, DSL, review and regeneration rules. |
| `test/dao/jing/cbor_fixtures.cljc` | `dao.jing.cbor-fixtures`: loads the corpus on JVM, Node, and Dart; accessors. |
| `test/dao/jing/cbor_fixtures_test.cljc` | `dao.jing.cbor-fixtures-test`: codec-free structural self-tests. |

## Provenance

- Plan: `docs/design/dao.jing.cbor.md` as last changed in commit
  `c96492f353354a661f6ad843f84d40d440193563` (2026-09-18), read at tree
  `0dc06197` on branch `jing-cbor`.
- Work-package spec: `collab/1790011562103-ref-architect-work-package.findings.md`
  (section J0) and `collab/1790011825648-ref-architect-prior-art.findings.md`.
- Dependency pins the codecs must satisfy without regeneration:
  `org.replikativ/boring 0.1.30` (JVM and JavaScript, `deps.edn`) and Dart
  `cbor 6.5.1` (`pubspec.yaml`). Neither was used to produce these bytes.
- Generated 2026-09-22 by `claude-opus-5` (Storage Engineer role, task
  jing-cbor-j0).
- Byte facts taken from outside the plan text: the Boring set mapping (tag 258)
  and `clojure/with-meta` payload shape `[meta value]` were read from the
  Boring-produced frozen stream fixtures in `test/dao/stream/cbor_test.cljc`
  (the `clojure/with-meta` and `#{...}` hex there). See AMBIGUITIES A2 and A3.

## Reviewers

Sign-off required before the corpus is frozen (names and dates to fill in):

- [x] Independent different-family reviewers: deepseek-v4-pro and qwen3.8-max
  (`collab/1790023590463-ref-review-*.findings.md`), READY WITH CHANGES,
  changes applied 2026-09-22.
- [x] Architect sign-off on fixture immutability, profile closure and
  injectivity (gpt-5.6-sol, 2026-09-22): J0 FROZEN; A4, A5, A6, A8
  (provisional), A9, A11 and the metadata-with-own-metadata rule adopted;
  the with-meta frame name stays `clojure/with-meta`.
- [x] AMBIGUITIES resolution recorded by: the architect rulings above, folded
  into the Rulings section of this file.

This file is an immutable v1 contract: tests may verify it, never regenerate
or overwrite it.

## The profile these bytes encode

- RFC 8949 section 4.2.1 core deterministic encoding: shortest heads for all
  integers, lengths and tags; definite lengths only; map keys and set elements
  sorted by their encoded bytes, unsigned lexicographic (not length-first).
- Integers: major types 0/1 through `2^64-1` and `-2^64`; beyond that, tags
  2/3 over a minimal big-endian magnitude (no leading zero byte).
- Floating point: never a native CBOR float. Every float is
  `27(["dao.jing/float64", h'<8 big-endian IEEE-754 bytes>'])`; float32 input
  is widened exactly; every NaN becomes `7ff8000000000000`.
- Decimals: `4([exponent, mantissa])`, scale preserved. Ratios:
  `30([numerator, denominator])`, reduced, denominator positive, denominator 1
  retained.
- Strings: UTF-8, no normalization. Byte strings: major type 2.
- Vectors: plain arrays. Lists and finite sequences:
  `27(["dao.jing/list", [items...]])`.
- Keywords and symbols, all of them: `27(["dao.jing/keyword", [ns, name]])`,
  `27(["dao.jing/symbol", [ns, name]])`, ns `null` or text. Tag 39 never.
- Sets: `258([elements...])`. Metadata on collections and symbols:
  `27(["clojure/with-meta", [meta-map, value]])`, after stripping unqualified
  `:line :column :end-line :end-column`; empty metadata is omitted.
- One item per payload: no stringref (tags 256/25), no shaped arrays, no index
  frame, no trailing bytes.

## Corpus schema

Top level: `format`, `version`, `contract`, `plan`, `pins`, `generator`,
`frame-names`, `refusal-classes`, `required-categories`, `cases`.

Each case has every key below, with `null` or `[]` where it does not apply:

| Key | Meaning |
|---|---|
| `id` | Stable, unique case id (`area/name`). |
| `kind` | `canonical`, `encode-refusal`, or `decode-refusal`. |
| `categories` | Coverage categories; `injectivity` is added to every case in a distinction group. |
| `input` | The semantic input in the DSL below (`null` for decode refusals). |
| `hex` | Canonical bytes (canonical), or the offered bytes (decode refusal); `null` for encode refusals. |
| `sha256` | Lowercase hex SHA-256 of `hex`'s bytes. The fixture address is this digest. |
| `refusal` | The refusal class a conforming codec must raise, or `null`. |
| `equivalence` | Equivalence group id: every member has identical bytes. |
| `distinct` | Retained-distinction group ids: members differ pairwise in bytes and address. |
| `plan` | The plan section the case pins. |
| `notes` | Why the case exists, and any host obligation. |

Case kinds:

- `canonical`: encode `input` and get exactly `hex`; decode `hex`, get a value
  equal to the input after the declared normalizations, and re-encode to
  exactly `hex`.
- `encode-refusal`: encoding `input` must fail with `refusal`, before any
  bytes are stored.
- `decode-refusal`: decoding (ingress acceptance of) `hex` must fail with
  `refusal`. Each such case carries exactly one defect, so check order across
  implementations cannot change the class. The one deliberate exception,
  `meta/decode-invalid-utf8-under-line`, pins validate-before-strip (A9).

Injectivity rule over the whole corpus: two canonical cases have equal bytes
if and only if they share an `equivalence` group. The generator and the
self-tests both enforce it, so every distinct normalized identity in the
corpus has distinct bytes and a distinct address. SHA-256 collision resistance
is the addressing assumption; nothing here claims digest injectivity.

## Input DSL

Every leaf is a JSON object with a type tag `t`; numbers are always decimal or
hex strings so no JSON parser rounds them. A `text` is either a JSON string or
`{"utf16": ["d83d", "de00", ...]}`, a list of UTF-16 code units in hex, used
wherever a case needs an unpaired surrogate (the JSON file itself is pure
ASCII and never contains a lone-surrogate escape).

| Form | Host value |
|---|---|
| `{"t":"nil"}` / `{"t":"bool","v":true}` | nil, booleans. |
| `{"t":"int","v":"-42"}` | Exact integer. Optional `"host":"big"` asks for the host big-integer type (BigInt/BigInteger, JS BigInt, Dart BigInt) holding the same value. Values outside the JS safe range must be BigInt on Node. |
| `{"t":"float64","bits":"3ff0000000000000"}` | Floating-point content with these IEEE-754 bits (the float64 carrier on Node for integral values and for decoded floats). |
| `{"t":"float32","bits":"3dcccccd"}` | A host single-precision value (JVM `Float`); on Node and Dart, the double obtained by exact widening. |
| `{"t":"decimal","exponent":"-1","mantissa":"10"}` | Decimal `mantissa x 10^exponent` with exactly this scale (JVM `1.0M`). |
| `{"t":"ratio","num":"2","den":"4"}` | The rational num/den through the portable ratio constructor, which reduces and moves the sign to the numerator; `den` 1 still yields a ratio carrier. |
| `{"t":"str","v":text}` | String. |
| `{"t":"bytes","hex":"00ff"}` | A fresh host byte array with this content. |
| `{"t":"keyword","ns":text-or-null,"name":text}` | Keyword built from exact components, never by parsing slash text. |
| `{"t":"symbol","ns":...,"name":...}` | Symbol built from exact components. |
| `{"t":"vector","items":[...]}` | Vector. |
| `{"t":"list","items":[...]}` | List (`list?`). |
| `{"t":"seq","items":[...]}` | A realized non-list finite sequence (lazy seq, cons, range). |
| `{"t":"map","entries":[[k,v],...]}` | Hash map built by inserting entries in this order. |
| `{"t":"sorted-map","entries":...}` / `{"t":"sorted-set","items":...}` | Sorted collections with the host's default comparator. |
| `{"t":"set","items":[...]}` | Hash set built by inserting items in this order. |
| `{"t":"host","kind":"char","v":"a"}` | An unsupported host value (`char`, `inst`, `uuid`, `fn`, `record`, `js-unsafe-integer`); encode refusals only. |

Any collection or symbol node may carry `"meta": {"t":"map",...}`, the metadata
map to attach before the codec strips reader positions.

## Refusal classes

| Class | Raised for |
|---|---|
| `malformed-cbor` | Not well-formed CBOR: truncation, reserved additional information, stray break, indefinite integer or tag, two-byte simple value below 32, empty input. |
| `trailing-data` | Bytes after the one item. |
| `invalid-utf8` | Text that is not valid UTF-8 (including encoded surrogates, overlongs, values above U+10FFFF). |
| `unpaired-surrogate` | A host string with an unpaired UTF-16 surrogate, anywhere: text, identifier components, map keys, set elements, metadata. |
| `non-canonical` | Well-formed and in-profile, but re-encoding the decoded value gives different bytes: non-shortest heads, indefinite lengths, key or element order, in-range bignums, bignum leading zeros, unreduced ratios, non-canonical NaN bits, empty or reader-position metadata on the wire. |
| `duplicate-key` / `duplicate-element` | Two identical encoded map keys or set elements, on the wire or introduced by normalization. |
| `equality-collapse` | Distinct encodings that portable decoded equality would merge (`1` and `1.0`, `0.0` and `-0.0`, `1` and `1/1`, `1M` and `1.0M`, `[1]` and `(1)`). |
| `unknown-tag` | Any tag outside 2, 3, 4, 27, 30, 258 (Boring would surface it as `TaggedValue`, or it is a stringref/shared-reference tag). |
| `identifier-tag-39` | Tag 39, even for an ordinary name. |
| `unknown-frame-name` | Tag 27 with a name outside `dao.jing/list`, `dao.jing/keyword`, `dao.jing/symbol`, `dao.jing/float64`, `clojure/with-meta`. |
| `malformed-frame` | Tag 27 or tag 258 whose shape is wrong: not `[name-string payload]`, wrong payload type or arity, metadata that is not a map, a metadata map that itself carries metadata, metadata on a non-collection non-symbol or on a value already carrying metadata. On encode: a host metadata map that carries its own metadata. |
| `malformed-number` | Tag 2/3/4/30 payloads outside their grammar: non-byte-string bignum, decimal or ratio not a two-integer array, bignum exponent, zero or negative denominator; a zero denominator on encode. |
| `native-float` | Any native CBOR float (major type 7, additional information 25, 26, 27). |
| `unsupported-simple` | Any simple value other than false, true, null. |
| `unsupported-value` | A host value outside the supported domain. |

The class names are this corpus's vocabulary, not the plan's (AMBIGUITIES A10).
J1 must map its error data onto them.

## Generation method

`cbor-v1.generate.py` (standard library only: `struct`, `hashlib`, `json`,
`fractions`, `math`, `os`, `sys`) holds the case list, a small
deterministic-CBOR encoder, and the Jing profile rules written from the plan.
It never writes the resource. Running it:

1. encodes each canonical input and, where the case lists a hand-written
   expected hex, asserts the encoder agrees with it;
2. decodes each canonical encoding with the independent reader and a profile
   checker, re-encodes, and asserts identical bytes;
3. asserts every encode refusal and decode refusal raises exactly its declared
   class;
4. asserts unique ids, equivalence and distinction groups, the injectivity
   rule, and coverage of every required category;
5. prints the corpus JSON (pure ASCII, one case per line) to stdout.

`--check` recomputes the corpus and exits 1 unless it equals `cbor-v1.json`
byte for byte, without writing anything.

## Independent reader method

`--inspect` reads the committed `cbor-v1.json` and decodes every case's bytes
with the script's generic CBOR reader, which knows only RFC 8949 data items.
It prints major types, lengths, text, tags with their registered meanings, the
tag-27 name string, simple values, native floats, flags for non-shortest heads
and indefinite lengths, whether map keys and set elements are in bytewise
order, trailing bytes, and reader errors. It applies no Jing rule: a reviewer
uses it to check by eye that tags, frame names, and ordering are what the plan
says.

## Loading on each host

`dao.jing.cbor-fixtures` reads `test/resources/dao/jing/cbor-v1.json` relative
to the working directory: `slurp` plus `clojure.data.json` on the JVM
(already a top-level dependency), `fs.readFileSync` plus `JSON.parse` on
Node, and `dart:io` plus `dart:convert` on Dart (`jsonDecode` output is
converted to Clojure data as `dao.stream.transit.cljd` already does).

CLJD finding: `clojure -M:cljd test` puts `test/` on the ClojureDart classpath
(the `:cljd` alias's `:extra-paths`), compiles every `.cljc` namespace there,
and runs the generated tests with `flutter test` from the package root, where
`pubspec.yaml` lives. The existing Dart tests already use repository-relative
paths (`test/dao/jing/file_test.cljc` writes under `target/`), so the resource
needs no copy. This finding comes from reading the build configuration and
`test/README.md`; the J0 implementer did not run the CLJD lane, so the Dart
load is unverified until the orchestrator's CLJD run. If it fails, the J2
obligation is to make the resource readable from the Dart test process
without copying it into a host-local table.

## Obligations the bytes cannot express

These belong to the J1/J2 tests that consume the corpus:

- Mutation isolation: `bytes/content-a` and `bytes/content-b` must be built
  as separate host arrays; mutating the input array after encoding, and the
  decoded array after decoding, must change neither the stored bytes nor the
  address.
- Independence from ambient print settings (`*print-length*`,
  `*print-meta*`, and similar): encode the canonical cases under altered
  bindings and get the same bytes.
- Encode-side collapse and normalization-duplicate refusals require a host
  collection that still holds both members. Where the host's own constructor
  already merges them, the test reports the case as not applicable (N/A) on
  that host, never as passed. The decode-side twins (`mal/collapse-*`,
  `mal/duplicate-*`) enforce the rule on every host regardless. Expected
  constructibility, from reading host equality rules (not run):

  | Case | JVM | Node | Dart |
  |---|---|---|---|
  | `coll/map-int-float-key-collapse` | live | N/A | N/A |
  | `coll/set-float-decimal-collapse` | live | N/A | N/A |
  | `coll/set-signed-zero-collapse` | N/A | N/A | N/A |
  | `coll/set-int-ratio-collapse` | N/A | N/A | N/A |
  | `coll/set-decimal-scale-collapse` | N/A | N/A | N/A |
  | `coll/set-vector-list-collapse` | N/A | N/A | N/A |
  | `coll/map-integer-width-duplicate` | N/A | live | live |
  | `coll/set-nan-payload-duplicate` | host-dependent | host-dependent | live |
  | `coll/map-nan-payload-duplicate` | host-dependent | host-dependent | live |

  "N/A" on all three hosts is the expected result for the four middle rows:
  Clojure `=` and correct portable carriers merge those pairs by contract.
  "host-dependent" means it holds both NaNs only where the host set keeps
  two unequal NaN objects; J1/J2 record what they observe. The table is a
  review estimate, not a contract: an implementer who finds a row wrong
  corrects the table, not the bytes.
- `num/js-unsafe-integer` applies to Node only.
- Carrier equality, hashing, ordering and query matching (`dao.space`) are
  outside the corpus.

## Regeneration and review

The corpus is a contract, not a build product. Dependency upgrades, codec
work and test runs never regenerate it. A deliberate change requires:

1. A written reason: a new contract version (`cbor-v2.json`, never an edit of
   v1 in place) or a reviewed correction of a v1 error.
2. Editing `cbor-v1.generate.py`, then running it by hand and redirecting its
   output to the resource; nothing else writes that file.
3. A case-by-case byte delta (old and new hex and digest for every changed
   case) attached to the change.
4. Architect approval and an independent different-family review of that
   delta.
5. `python3 cbor-v1.generate.py --check` passing on the committed result.

No test, build task, or script may overwrite the resource.

## AMBIGUITIES

Places where the plan does not fix a byte, or where this corpus had to choose.
Each says how it was resolved or that it is left open for the reviewer and
the architect.

- **A1. Tag-27 payload arity.** The IANA tag-27 form is
  `[typename, constructor-args...]`; the plan says exactly `[name-string,
  payload]`. Resolved per the plan: two elements, the payload is one item. The
  flattened forms are `malformed-frame` fixtures (`frame/list-extra-element`,
  `meta/decode-flattened-form`). Separately, the work package records that
  Boring 0.1.30 surfaces array-payload names through `:on-unknown-record`, not
  as `UnknownRecord`; that changes the decode path, not bytes.
- **A2. Set mapping.** The plan leaves the set mapping to fixtures. Resolved:
  tag 258 over a definite array sorted bytewise, taken from Boring-produced
  hex in `test/dao/stream/cbor_test.cljc` (`d90102820102`). Not re-verified
  against Boring by this task.
- **A3. `clojure/with-meta` shape.** Resolved:
  `27(["clojure/with-meta", [meta, value]])`, from the same stream fixture
  (the hex beginning `d81b8271636c6f6a7572652f776974682d6d657461`). The
  metadata map is an ordinary canonical map and must be bare: a metadata map
  that itself carries metadata is `malformed-frame` on decode
  (`meta/decode-meta-with-own-meta`) and on encode (`meta/meta-with-own-meta`).
  **Ruled (reviewers).**
- **A4. Metadata nesting around named frames.** The plan says lists use a
  `dao.jing/list` frame with an array argument and metadata uses
  `clojure/with-meta`; it does not say which wraps which. The stream profile
  instead carries list metadata inside its list frame. Resolved: `with-meta`
  is outermost and wraps the list frame (`meta/list-1-doc`); the list payload
  stays a plain item array. The same holds for symbols: `with-meta` wraps the
  `dao.jing/symbol` frame (`meta/symbol-x-doc`). **Ruled keep (reviewers).**
  J1 must confirm Boring can emit this shape or build the frame explicitly.
- **A5. Reader-position stripping scope.** Resolved: only the unqualified
  keywords `:line :column :end-line :end-column`, at the top level of every
  metadata map, including metadata carried by values inside metadata.
  Ordinary maps keep `:line` even inside metadata
  (`meta/vector-1-nested-line`); `:a/line` and `"line"` are retained.
  **Ruled keep (reviewers).**
- **A6. Portable equality over collections.** The plan defines portable
  numeric equality and says it recurses through collections, but not whether
  a list equals a vector. Resolved with Clojure's sequential equality on
  every host, metadata ignored, byte strings compared by content: `#{[1] (1)}`
  is `equality-collapse`. **Ruled keep (reviewers).**
- **A7. Empty sequences.** An empty realized sequence encodes as the empty
  list frame, not nil (`frame/empty-seq`). Host code where `(seq [])` is nil
  never produces this input.
- **A8. Empty identifier names.** The plan says a name is a string. The
  corpus accepts `(keyword "")` (`id/keyword-empty-name`) and `(symbol "")`
  (`id/symbol-empty-name`). **Ruled accept (reviewers), provisional**: a
  later reversal is a reviewed v1 correction.
- **A9. Surrogates under stripped keys.** The plan rejects unpaired surrogates
  in metadata and also strips reader-position keys. **Ruled reject: validate
  before strip.** A lone surrogate under a stripped key is `unpaired-surrogate`
  on encode (`meta/surrogate-under-stripped-line`). On the wire a surrogate
  can only appear as invalid UTF-8, so the decode twin
  (`meta/decode-invalid-utf8-under-line`) is `invalid-utf8`; it deliberately
  carries a second defect (the `:line` key itself is non-canonical) to pin
  that validation precedes the canonical check.
- **A10. Refusal taxonomy and precedence.** The plan lists what to reject but
  names no classes and no order. The 16 classes are this corpus's v1
  vocabulary. Every decode-refusal fixture has a single defect, except
  `meta/decode-invalid-utf8-under-line` (A9), so precedence otherwise cannot
  matter. Borderline assignments: a negative or zero ratio denominator on the
  wire is `malformed-number` (grammar) while an unreduced ratio is
  `non-canonical`; a non-canonical NaN inside a well-formed float64 frame is
  `non-canonical`; empty or reader-position metadata on the wire is
  `non-canonical`; a metadata map carrying its own metadata is
  `malformed-frame` (A3). **Ruled confirm (reviewers).**
- **A11. Denominator 1 against the tag-30 specification.** The plan retains
  denominator-1 ratios as tag 30 (`num/ratio/1-1`, `num/ratio/3-1`): "Retain
  rational kind even when the denominator is 1". The registered tag-30 text
  was not checked (no network). **Ruled accept (reviewers).**
- **A12. Decimal details.** Resolved: the exponent must be a major type 0/1
  integer (RFC 8949 section 3.4.4), so a bignum exponent is
  `malformed-number`; the mantissa uses a bignum only beyond 64 bits; zero
  mantissas with different exponents are distinct values
  (`decimal-scale/zero`). The plan says only `[exponent mantissa]`.
- **A13. NaN coverage.** Resolved: every NaN, including sign-bit, signaling,
  and float32 NaNs, canonicalizes to `7ff8000000000000` on encode; on decode a
  frame holding any other NaN bits is refused as `non-canonical`, not silently
  canonicalized.
- **A14. Encode-side collapse the host already performed.** A host whose map
  or set constructor merges `1` and `1.0` hands the encoder one entry, which
  it cannot detect. J1/J2 report such cases as not applicable (see the
  constructibility table under Obligations). **Ruled confirm (reviewers).**
- **A15. Effective-option proofs.** The fixtures prove stringref, shapes and
  index frames are absent by exact bytes and one-item payloads. The refused
  stringref inputs use the registered stringref tags 256 and 25; whether
  Boring's stringref uses exactly these tags, and Boring's shape tag number
  and index-frame layout, were not verified, so no fixture names Boring's
  shape or index encodings.
- **A16. Float32 on Node and Dart.** Neither host has a native float32 value;
  `float32` DSL cases mean the exactly widened double there. Only the JVM
  exercises a true float32 input.
- **A17. Address keyword.** Fixtures record the raw SHA-256 hex. Deriving
  `:segment/sha256-<hex>` from it is the step-3 wrapper's job and is not
  pinned here.

## Rulings

One place for a J1/J2 implementer to read every settled choice (recorded by
the orchestrator from the two reviews, 2026-09-22):

- A3: metadata is `27(["clojure/with-meta", [meta, value]])`; the metadata
  map must be bare, else `malformed-frame`.
- A4: `with-meta` is outermost, wrapping the `dao.jing/list` frame and the
  `dao.jing/symbol` frame alike.
- A5: strip only unqualified `:line :column :end-line :end-column`, at the top
  level of every metadata map (metadata carried by metadata is handled the
  same way).
- A6: sequential equality (a list equals a vector) governs collapse, so
  `#{[1] (1)}` is refused.
- A8: empty identifier names are accepted (provisional).
- A9: validate before strip; a lone surrogate under a stripped key is refused.
- A10: the 16 refusal classes are the v1 vocabulary.
- A11: denominator-1 ratios stay tag 30.
- A1, A2, A7, A12, A13, A14, A15, A16, A17: confirmed as written above.

## Coverage summary

Counts are reproduced by reading `cbor-v1.json`; a case can be in several
categories. 372 cases: 209 canonical, 31 encode refusals, 132 decode
refusals; 30 equivalence groups and 25 retained-distinction groups.
