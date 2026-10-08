Completed-Local: 2026-10-05 +07
Coding-Agent: claude (opus-5-5)

# C11, the authority's front: findings

**Summary: C11 is done test-first in one new namespace and one new test
namespace. No shared file was touched. The JVM focused run over every
`yin.vm.ucf.*` and `dao.stream.journal*` namespace is green (265 tests,
2043 assertions), and kondo is clean. All 10 mutations and the
vocabulary-guard break turned the suite red. I could not run cljstyle,
because the permission gate blocked the binary, as in C5 to C10. Node
and Dart were not run, as instructed.**

## Changed files

New:
- `src/cljc/yin/vm/ucf/authority/front.cljc`
  - `requests`: the closed request set.
  - `front`: makes the state of one holder's front from a config map.
  - `step`: the one bounded function.
  - `reply-evidence`: the pure out-attribution rule.
- `test/yin/vm/ucf/authority/front_test.cljc`: 20 tests. One is
  JVM-only (the vocabulary guard, wrapped as `#?(:cljd nil :clj ...)`).

No change to `ledger.cljc`, `authority.cljc`, `grant.cljc`,
`admission.cljc`, `completion.cljc`, `input.cljc` or `custody.cljc`. The
front requires only `dao.lease`, `dao.stream` and the UCF authority
namespaces. It requires no `yin.vm` engine namespace.

Scratch, under the ignored `target/c11mut/`: the mutation script and the
pristine copy it restores from.

## Design

**State.** `(front config)` takes:
- `:authority`, `:inbound` (reader), `:reply` (writer) and
  `:diagnostics` (writer);
- `:resolver`, `(fn [inbound-identity value] -> author or nil)`;
- `:store`, used by offers;
- `:lease-media`, `(fn [author] -> writer or nil)`;
- an optional `:cursor`.

It answers a plain map holding these values and the held inbound cursor
(oldest by default).

**`(step f n)`.** It reads at most n values from the held cursor and
answers the next state:
- `::cursor` moves past the last read.
- `::handled` holds this step's records only. Each record is a
  diagnostic, an answer with the reply append's result, or `{::gap c}`.
- `::halted` holds the inbound outcome that ended the step early
  (blocked, end, or a defect), or nil.

On a gap, the cursor moves to the recovery cursor, and the gap counts as
one read. A gap whose recovery cursor does not move halts the step. A
throwing read halts it as data. There is no clock, no thread and no
loop beyond n reads.

**Requests.** The closed set dispatches on `:yin.k/request`, and every
request also needs a `:yin.k/request-id`. The shapes are in the
namespace docstring.

| kind | required keys | landed function |
|---|---|---|
| `:yin.k/offer` | `:yin.k/id :yin.k/bytes :yin.k/medium` | `grant/offer!` (the store comes from config) |
| `:yin.k/proposal` | `:dao.lease/proposal :yin.k/occurrence` | `(lease/proposal pid (custody/subject o))` appended to the author's lease-fact stream; the judge decides on its next `grant/step!` |
| `:yin.k/resumed` | `:yin.k/report :yin.k/bytes` | `completion/report!` |
| `:yin.k/release` | `:dao.lease/lease` | `(lease/release l)` appended to the author's lease-fact stream; C8's release lapse completes or reclaims |
| `:yin.k/input` | `:yin.k/input` | `input/record-input!` |
| `:yin.k/admit` | `:yin.k/target :yin.k/fenced-envelope` | `admission/admit!` with the composition's diagnostic stream |

**Attribution in.** The front calls the resolver with the inbound
stream's `:dao.stream/identity` and the delivered value. A throw counts
as nil. An author field inside the request is never read; a test sends
`:yin.k/author "mallory"` from holder-a's inbound, and the request
commits as holder-a's.

The checks run in this order:
1. Shape. A non-map, an unknown kind, a missing request id or a missing
   required key is `:malformed`.
2. Author. No resolved author is `:wrong-author`.

Either defect commits nothing, carries nothing, sends no reply and
appends exactly one diagnostic:

```clojure
{:yin.k/diagnostic :yin.k/defective-request
 :yin.k/defect     :malformed | :wrong-author
 :yin.k/inbound    inbound-identity
 :yin.k/author     a                          ; when resolved
 :yin.k/claimed    {:yin.k/request k :yin.k/request-id r}}
```

The diagnostic carries no `:yin.k/status` or `:yin.k/admission`. The
record holds `::appended`, the stream's own answer, or `{::threw true}`
when the append throws.

An author resolved to a non-holder reaches the landed function. It is
refused exactly as a direct call would be:
- a report is refused `:not-holder`;
- an input is refused `:wrong-author`;
- an admission produces C7's own `:wrong-author` diagnostic.

**Replies.** A reply is `{:yin.k/reply k :yin.k/request-id r
:yin.k/answer x}`, where x is the landed function's answer unchanged.
That includes `:dao.space/t` on commits, as the direct functions answer
it.
- A carried proposal or release answers `{:yin.k/status :carried}`.
- An admit that admission answers with a diagnostic, or with
  `::admission/unenrolled`, gets no reply, because no outcome exists
  (UCF 7.7.8). The front records it as `{::answer x}`.

**Poison.** While the authority serves no projection, a proposal or
release is not carried. It answers `{:yin.k/status :suspended
:yin.k/reason :unavailable}`. The other kinds reach their landed
function, which answers `:suspended` (or the `:suspended` admission
outcome).

**Retry.** A lost reply is recovered by sending the same request again.
Offers, reports, inputs and admissions answer `:replayed` through the
ledger's dedup. A second copy of a proposal or release is carried again,
and the judge's `:answered` and `:seen` gates make it a no-op; the test
shows one grant.

**Attribution out.** `(reply-evidence arbitration author record)`
mirrors `custody/binding-evidence`. `author` is the attribution the
holder's composition gives the stream the record was read from. The
function answers the record when two things hold:
- `author` is non-nil and `=` the arbitration identity (identity
  equality only; no descriptor is read);
- the record is an outcome: a front reply on the closed set with a map
  answer, or a `:yin.k/admission` outcome on C7's closed family, which
  is what the outcome projection serves.

Otherwise it answers `{::no-evidence :other-author | :not-an-outcome}`.

## Red before, green after

All 20 tests were written first and run against a stub namespace
(`requests #{}`, `front` answering `{}`, `step` the identity,
`reply-evidence` nil): 0 errors, every test red.
- The truncated failure listing showed 19 tests.
- The 20th, `a-gap-on-the-inbound-is-one-read`, reads `::handled` off
  `{}` in its first assertion, so it fails against the stub by
  construction.

The first run against the implementation had 6 failures, from two
causes:
- **Test bug.** I compared journal frames as byte arrays, which compare
  by identity on the JVM, and would on JS too. I fixed it to compare
  the decoded records.
- **Implementation gap.** The author was resolved only for well-formed
  requests, so a `:malformed` diagnostic dropped a resolved author. C7
  includes the author when one resolves. I now resolve before the shape
  check.

The second run: 20/20 green, 171 assertions.

| Test | Red (stub) | Green |
|---|---|---|
| an-offer-round-trips-like-grant-offer | F | yes |
| a-report-round-trips-like-completion-report | F | yes |
| an-input-round-trips-like-record-input | F | yes |
| an-admit-round-trips-like-admission-admit | F | yes |
| a-proposal-is-carried-to-the-judge | F | yes |
| a-release-completes-through-the-judge | F | yes |
| unknown-kinds-and-malformed-values-are-diagnostics (12 values) | 36 F | yes |
| the-diagnostic-shape | F | yes, after the author fix |
| no-resolved-author-is-wrong-author | F | yes |
| a-failed-diagnostic-append-is-data | F | yes |
| a-non-holder-is-refused-as-by-the-direct-function | F | yes |
| the-step-is-bounded-and-resumable | 8 F | yes |
| a-gap-on-the-inbound-is-one-read | F (by construction, see above) | yes |
| a-poisoned-authority-answers-suspended-to-every-request | 5 F | yes |
| a-retried-request-replays | F | yes |
| a-retried-proposal-grants-once | F | yes |
| a-forged-outcome-discharges-nothing | 8 F | yes |
| evidence-survives-cbor-and-a-reflection | F | yes |
| the-front-names-no-transport-concept (JVM) | F | yes |
| the-request-set-is-closed | F | yes |

## Mutations

Each mutation was applied by `target/c11mut/run.py`, run against
`front-test`, and reverted. `cmp` against the pristine copy confirmed
the restore. All 10 went red:

| Mutation | Failures (tests) |
|---|---|
| author falls back to the request's `:yin.k/author` | 3 (no-resolved-author) |
| no author check | 5 (no-resolved-author) |
| step reads n+1 | 8 (bounded, gap) |
| evidence ignores the author | 7 (forged-outcome, cbor/reflection) |
| carriage ignores poison | 3 (poisoned) |
| a malformed request gets no diagnostic | 39 (malformed, shape, failed-append) |
| a gap does not move the cursor | 2 (gap) |
| an admission diagnostic is replied | 1 (non-holder) |
| evidence accepts any map | 4 (forged-outcome) |
| blocked does not halt (keeps reading) | 2 (bounded, gap) |

Vocabulary guard: adding "over any transport" to the `requests`
docstring failed `the-front-names-no-transport-concept`. I then
restored the file and `cmp` confirmed it.

## Coverage against the brief

- **Round trips like the direct function.** Offer, report, input and
  admit each run on twin authorities, one direct and one through the
  front. The decoded ledger records are equal, and the reply's answer
  equals the direct answer.
  - **Proposal:** the lease-fact stream receives exactly
    `(lease/proposal "p-a" (custody/subject o))`, and `grant/step!` then
    grants it to the resolved author.
  - **Release:** proposal, step, report and release all go through the
    front, then a step. The C8 `:yin.k/succeeded` edge is recorded.
- **Unknown kind and malformed values.** 12 values: a non-map, `{}`, an
  unknown kind, a missing id, missing keys per kind, and a malformed
  proposal occurrence. Each commits nothing, carries nothing, sends no
  reply and appends exactly one `:malformed` diagnostic.
- **No resolved author** is `:wrong-author`: with a nil resolver, with a
  throwing resolver, and with a forged author field. A proposal with no
  author is not carried.
- **Non-holder** answers equal the direct answers, and the ledgers are
  equal.
- **Bounded step:**
  - 5 requests: step 2 handles 2, the next step 2 handles 2 more, then
    step 10 handles 1 and halts `:dao.stream/blocked`;
  - a further step reads nothing and keeps the cursor;
  - step 0 reads nothing;
  - the replies keep inbound order.
- **Gap.** A capacity-2 inbound with 4 requests gives one `{::gap c}`
  read, then 2 requests, then blocked.
- **Poisoned authority.** All six kinds answer `:suspended`, nothing is
  carried, and nothing is committed. The poisoning is a crash cut, as in
  C7.
- **Idempotent retry.** A second offer, report, input or admit answers
  `:replayed` and writes nothing. A second proposal grants once.
- **Forged outcome.**
  - A reply read from the authority's stream counts.
  - The same reply attributed to "mallory" or nil does not.
  - A forged `:committed`, `:intent-conflict`, or a forged front reply
    from another author is `:other-author`.
  - Non-outcomes from the authority are `:not-an-outcome`.
  - An outcome projection record counts when attributed to the
    arbitration identity.
- **CBOR and reflection.** The reply stream is served under the
  arbitration identity over C5's two-ring-buffer `dao.stream.remote`
  toy channel. The reflection's identity is arb. The evidence over the
  reflected records equals the direct records, both before and after a
  canonical CBOR round trip. All of it is `:other-author` under
  "mallory".
- **Vocabulary guard (JVM).** The test reads the namespace's source text
  and finds none of `rpc|transport|apply|remote|client|server|socket|
  wire|network`, case-insensitive. This is the regex of
  `dao.stream.apply-test`, plus "apply" itself.

## Results

- **JVM focused**, in one foreground `clojure -M:test` run with `-n`
  for:
  - `yin.vm.ucf-test`, `ledger-test`, `remote-test`, `authority-test`,
    `handoff-test`, `checkpoint-test`, `custody-test`;
  - `authority.admission-test`, `completion-test`,
    `completion-fold-test`, `input-test`, `reclaim-test`, `grant-test`
    and `front-test`;
  - `dao.stream.journal-test` and `dao.stream.journal.file-test`.

  Result: 265 tests, 2043 assertions, 0 failures, 0 errors. Nothing
  needs `^:slow`.
- **kondo** over both new files: 0 errors, 0 warnings.
- **ASCII only**, and no line over 80 columns (awk and grep checked).
- **Not run:**
  - `cljstyle fix` / `check`. The permission gate blocked the binary.
    Please run both on the two new files.
  - Node and Dart: yours, as instructed.

**Portability reasoning:**
- No float literal appears anywhere.
- The only JVM-only form is the guard, wrapped `#?(:cljd nil :clj ...)`
  with `:cljd` first.
- Catches use `#?(:cljd Object :clj Throwable :cljs :default)`.
- `:lease-media` is called as a function. The tests pass a map, which
  is callable on all three hosts.
- The ledger comparisons decode the frames, so they are host-neutral.
- The evidence test compares decoded maps of keywords, strings and
  integers only, and no float64 carrier is involved, so `=` holds on
  Node as well.
- The thrown-writer test uses `reify stream/IDaoStreamWriter`, as C7's
  test does.
- The fixture namespace is only read, never written, and no `spit` is
  used.

## Smallest choices, listed as questions

1. **Carriage, not stepping.** The front holds no judge. A proposal or
   release is appended to `(lease-media author)`, the author's
   lease-fact stream. The judge must drain that stream under a source
   that its own resolver attributes to the same author. That makes two
   resolvers that must agree, which is a composition duty. The judge
   then decides on the composition's next `grant/step!`. Should the
   front instead thread a judge and call `step!` itself?
2. **Carriage answers.**
   - ok: `:carried`;
   - a non-ok append: `:suspended :uncarried`;
   - no lease-fact stream for the author: `:refused :no-lease-medium`;
   - no projection: `:suspended :unavailable`.

   The judge's answer is learned from the ledger, through
   `binding-evidence`, not from the reply. A retried proposal answers
   `:carried` again, not `:replayed`. Should the front peek at
   `(:answered p)` and the lease's `:dao.lease/cause` and answer
   `:replayed`?
3. **`:yin.k/request-id` is required.** It is opaque, chosen by the
   holder and echoed in the reply. Reports and carriage answers have
   nothing else to correlate on. Correlation itself is D's job.
4. **The `:committed` reply does not reference the projection
   position.** The brief says it "may". I left it out; D reads the
   projection with a kept cursor and correlates on
   `[op-id incarnation]`.
5. **An admit names its target in the request value.** The holder picks
   the enrolled target, as an in-process writer picks a stream.
   Authorization is still attribution plus binding (7.7.8).
6. **No reply for an admission that produced no outcome** (a diagnostic,
   or an unenrolled boundary). A holder writing to an unenrolled target
   hears nothing and retries. That is a composition defect, and 7.7.8
   says such a boundary produces nothing.
7. **A throwing landed function becomes a `:malformed` diagnostic.** The
   brief says requests never raise an exception. A throw would come from
   an argument defect: `ledger/facts->datoms` throws before any write,
   and `commit!` already catches journal throws. It could hide a bug,
   though. Should it instead answer a distinct defect?
8. **A thrown append is reported as `{::threw true}`.** C7 reports it
   as `:dao.stream/transport-error`, but the vocabulary guard forbids
   that keyword in this namespace.
9. **`reply-evidence` takes the resolved author, not a raw stream
   identity**, as `binding-evidence` does. The outcome projection's own
   identity is `"<arb>/outcomes"`. A holder reading it directly needs
   its resolver to attribute that identity to arb. Served by reflection
   under arb, it is arb by C5's rule. Should 7.9 name which identities
   a holder trusts for the reply stream and for the projection?
10. **A closed authority.** The landed offer, report and input answer
    `{:yin.k/status :closed}`, and the front passes that through
    unchanged. Only admission maps closed to `:suspended` (C7 ruling 3).
    Poison gives `:suspended` for every kind.
11. **Extra keys in a request are ignored**, as C7 ignores extra
    envelope keys. A request's deeper shape is the landed function's to
    check (`:malformed-report`, `:malformed-request`, an admission
    `:malformed` diagnostic).
12. **The guard bans more than the brief names.** Besides transport,
    apply and rpc, it also bans remote, client, server, socket, wire
    and network, after `dao.stream.apply-test`. It reads the whole
    source file, keywords included.

## Doc debt (listed, not written)

- **Linker-dht 14.2.4 row 6**, worded for the front, as plan section 5
  lists: the request set, the defective-request diagnostic
  (`:yin.k/defective-request` with `:yin.k/inbound`), the reply shape,
  and `reply-evidence` as the holder-side rule.
- **UCF 7.9:** the identities a holder trusts as authority-authored (see
  Q9), and that a front reply's `:yin.k/answer` is the landed answer
  unchanged.

## Adaptation and gate follow-ups

These are uncommitted working-tree edits on top of cea819a7, on master
9352001f (C9). No git write was run. Only `front.cljc` and
`front_test.cljc` changed.

### Code changes (`front.cljc`)

1. **C9 arity.** The `:yin.k/admit` arm now calls
   `(admission/admit! a (::store f) i author envelope diagnostics)`.
   - The store is the front's existing `::store`, the same one its
     offers use, so the constructor needs no new key.
   - The `:store` line of the `front` docstring now says that offers
     put bodies in it and admission reads accepted checkpoints from it.
   - The admit row of the namespace docstring says the same.
2. **Finding 3.** The `(media author)` call is now inside a try. A
   throwing `lease-media` function answers
   `{:yin.k/status :suspended :yin.k/reason :uncarried}` instead of a
   `:malformed` diagnostic. A throwing landed function is still
   `:malformed`.
3. **Docstring sentences added:**
   - Q3: the request-id sentence, as the gate gave it.
   - Q7: the throw sentence, as the gate gave it.
   - Q10: "poisoned or closed: a proposal or release is not carried and
     answers `:suspended :unavailable`".
   - The `:no-lease-medium` and `:uncarried` carriage answers.

   The vocabulary guard stays green on the new text.

### Test changes (`front_test.cljc`)

Both direct `admission/admit!` calls (the admit round trip and the
non-holder test) now pass `(:store d)`. The `defective` list gains
`(dissoc (offer-req) :yin.k/medium)`.

Before the code edit, the suite ran 27 tests: **12 failures**. They
were:
- every admit path (the admit round trip, non-holder, poisoned,
  retried, forged-outcome and cbor/reflection), because the old arity
  threw inside the front and the catch-all made each a `:malformed`
  diagnostic;
- the new unenrolled-admit test;
- the new throwing-lease-media test.

After the edit: 28/28 green.

### New tests, red and green

| Test | Red | Green |
|---|---|---|
| `an-admit-at-an-unenrolled-boundary-is-silent` (finding 2): no reply, no diagnostic, nothing committed, `::answer` is `{::admission/unenrolled "no-such-target"}` | yes before the edit (the old arity's throw was diagnosed); mutation "an unenrolled admission is replied": 2 F | yes |
| `a-throwing-lease-media-function-is-uncarried` (finding 3) | yes before the edit (a `:malformed` diagnostic); mutation "not caught": 2 F | yes |
| `a-holder-without-a-lease-medium-is-refused` (finding 1) | green first, since it pins an existing arm; mutation "no `:no-lease-medium` arm": 1 F | yes |
| `an-uncarried-fact-suspends` (finding 1): a closed lease-fact ring, and a throwing writer | green first; mutation "non-ok carriage counts as carried": 2 F | yes |
| `a-failed-reply-append-is-data-after-the-commit` (finding 1): with a closed reply ring, `::replied` is `:dao.stream/closed`, the answer equals the direct `record-input!`, and the ledger records equal the direct twin's; a throwing reply writer gives `{::threw true}` | green first; mutation "reply append result dropped": 2 F | yes |
| `a-closed-inbound-halts-with-end` (finding 1) | green first; mutation "a halt records no outcome": 4 F across 4 tests | yes |
| `a-throwing-inbound-read-halts-as-data` (finding 1): a reified throwing reader halts with `{::threw true}` and keeps the cursor | green first; mutation "throwing read not caught": 1 E | yes |
| `defective` + missing `:yin.k/medium` (finding 1) | green first; mutation "offer medium not required": 4 F | yes |
| `an-inherited-admission-reads-the-fronts-store` (my addition, to pin the C9 adaptation): R completes into S1, which carries R's ids, and S1 is granted as lease-2. An admit of R's id 0 through the front commits; with the front's store swapped for an empty one, it is `:suspended` | written after the edit; mutation "admit! gets no store" (nil): 1 F | yes |

All 9 mutations of this round were applied by
`target/c11mut/run2.py` and reverted, and `cmp` confirmed the restore.
Every one was red; the table above gives the failure counts.

### Results

- **JVM focused.** One foreground run over `yin.vm.ucf-test`, `ledger`,
  `remote`, `authority`, `handoff`, `checkpoint`, `custody`, and
  `authority.admission`, `inherited` (new on master), `completion`,
  `completion-fold`, `input`, `reclaim`, `grant`, `front`, plus
  `dao.stream.journal-test` and `journal.file-test`: 291 tests, 2188
  assertions, 0 failures, 0 errors.
- **kondo** over both files: 0 errors, 0 warnings.
- **ASCII only**, no line over 80 columns, and no float literal.
- **Not run:**
  - `cljstyle check` was blocked by the permission gate again. The gate
    reviewer's earlier check was clean, but it predates these edits.
    Please run fix and check on both files.
  - Node and Dart: not run, as instructed.

**Portability.**
- The reified reader implements both `cursor` and `next`, with distinct
  parameter names (`_anchor`, `_c`), for the CLJD protocol-cast trap.
- The inherited-admission fixture uses the same `"successor"` frame-path
  idiom as `inherited_test`, which runs on all three hosts.
- No JVM-only test was added.
