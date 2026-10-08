I’ll review the four documentation edits against the surrounding v2 contract and the existing transport declarations. I’ll also search for surviving stream-level “must detect loss” language and check whether the new retention rule leaves any required behavior unspecified for the forthcoming complete-history transport.

I’m using the repository’s `code-review-pr` skill because this is a contract diff review; its checklist will keep the pass focused on consistency, downstream obligations, and documentation residue.

The main distinction is sound: declared no-gap retention supplies completeness, while an origin cursor only detects loss. I found two contract-tightening issues in the new wording and several surviving “reader-visible gap” sentences, including two in the WebSocket design, that still overstate what a fresh cursor can observe.

Completed-GMT: 2026-09-08 14:18:58 GMT  
Completed-Local: 2026-09-08 21:18:58 +0700 (Asia/Bangkok)  
Coding-Agent: codex  
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0

# Architect review: complete-history contract correction

## Verdict

**The architectural decision closes the original P0, but this wording is not yet ready to commit.** One P1 contract issue and two smaller consistency defects should be corrected first.

## Findings

### P1 — The `gap`/`full` rule over-constrains complete-history transports

File: [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:711)

**Blocks commit: yes.**

The principle is right for a writable, finite-capacity stream: if acknowledged values cannot be evicted, reaching capacity must refuse the new append with `full`.

The current formulation is too broad:

- It requires every complete-history transport to declare both reader and writer surfaces. A finite immutable/read-only complete-history stream is valid and has no `append!` or `full` obligation.
- “Genuinely unbounded” is ambiguous between logically unbounded and physically infinite. No physical transport has infinite resources. A log with no declared capacity can exclude ordinary `full` while still reporting unexpected disk/resource exhaustion as `transport-error`.
- “Refusing and evicting are the two answers” omits elastic growth or spill to another storage tier. Those are implementations of a logically unbounded stream, provided previously acknowledged values remain observable.
- The paragraph does not collect the most important retention obligation: an `ok` append must never later disappear while that logical stream exists.

**Concrete change:** scope the rule to writable complete-history transports and define logical unboundedness. For example:

> A complete-history reader retains every successfully appended value from the logical stream’s origin; its `:oldest` anchor therefore remains at that origin and `gap` is impossible. If it also has a writer surface and declares a finite capacity, reaching that capacity returns `full` without appending or evicting. A writer with no declared capacity may exclude `full`; unexpected storage exhaustion is `transport-error`, never eviction of acknowledged history. Whether `full` is transient or permanent remains part of the transport’s declared nature.

That gives the forthcoming transport the complete checklist:

- Reader surface and complete retention declaration.
- `:oldest` remains the logical origin.
- Successfully appended values are never evicted.
- `gap` is excluded for that reason.
- A finite-capacity writer includes `full`, which changes nothing.
- A logically unbounded writer may exclude `full`.
- Unexpected resource failure is `transport-error`.
- Reader-only complete histories owe no writer outcomes.

The general Reading, Writing, Closing, and concurrency sections already supply the remaining operational obligations.

### P2 — One new cursor paragraph conflates completeness with detection

File: [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:504)

**Blocks commit: yes.**

This sentence is categorical:

> “A composition that intends to observe a stream’s complete history mints an origin cursor…”

But the new Complete history section correctly says an origin cursor does not deliver completeness. A consumer of a declared complete-history transport may also arrive after appends and mint `:oldest`; because that transport retains from origin, it still gets complete history.

**Concrete change:** make this paragraph explicitly about detection on an evicting transport:

> A composition using a transport that can evict, and intending to detect whether history was lost, mints an origin cursor before the first append and keeps it.

That preserves the useful symmetry with minting `:newest` before an operation without implying that an origin cursor is the completeness mechanism.

### P2 — Stronger reader-level loss promises remain in both documents

Files:

- [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:659), especially lines 665–666
- [dao.stream.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:731), especially lines 734–736
- [dao.stream.ws.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.ws.md:396)
- [dao.stream.ws.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.ws.md:429)

**Blocks commit: yes, because correcting this promise is an explicit purpose of the change.**

The main document still says:

- “a reader that falls behind is told what it missed”
- “a reader that falls behind is evicted past and told so”

The WebSocket design similarly says:

- loss surfaces as gaps “at lagging readers”
- pressure produces “reader-visible gaps”

Those formulations still make the promise to a reader rather than to a cursor held across the eviction. The WebSocket composition does mint its cursors before delivery, so its mechanism is sound; its language is merely too broad.

**Concrete change:** consistently use formulations such as:

- “a cursor that falls behind receives `gap`”
- “a reader retaining a cursor across the eviction observes `gap`”
- “pressure surfaces as gaps to retained cursors that span eviction”

The outcome-exclusion example around `dao.stream.md:445` would also benefit from the same qualification: an evicting ring buffer makes pressure observable to a cursor spanning eviction, rather than universally “surfacing” it.

## Confirmed correct

- A fresh `:oldest` cursor is now plainly described as the earliest retained position, not proof of completeness.
- The text correctly says an origin cursor cannot be minted retrospectively or reconstructed.
- Completeness and detection are separated correctly in the dedicated section.
- Wiring a completeness-dependent log onto an evicting transport is correctly classified as a host-assembly defect.
- Capacity sizing is correctly rejected as a completeness guarantee.
- “Declared, never interrogated” remains consistent: transport nature and creation configuration are composition provenance, not runtime handle state.
- The new absence of a retention/completeness predicate does not imply another hidden predicate elsewhere.
- The change introduces no `seek`: every origin cursor still comes from normal anchored minting.
- It does not conflict with the absence of `closed?`; operational lifecycle answers remain authoritative.
- The existing v2 ring buffer remains valid as an evicting transport: it includes `gap` and excludes `full` by its declared eviction policy.
- The WebSocket architecture is unaffected. Its composed media already retain cursors across delivery; only its loss wording needs tightening.
- No tests were run; this was a prose-contract review.

**Final judgment: request changes.** The original completeness P0 is resolved in substance. After scoping the `full` rule correctly and replacing the remaining reader-level promises with cursor-level ones, the contract will be ready to commit.
