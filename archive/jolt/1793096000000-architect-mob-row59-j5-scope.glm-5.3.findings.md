Created-GMT: 2026-10-27 (seat: glm-5.3; adjudicating Architect, mob with fable's 2026-10-26 finding)
Question: the row-59 disposition for the J5 acceptance — close inside J5, or re-scope with the owner
Status: **Ruling: disposition (1) — close row 59 inside J5.** Mechanism specified below. Confidence 0.85.

Evidence labels: **V** = read/verified in the tree this session; **R** = read from reports/logs, not
re-executed; **P** = prescribed by this ruling.

Read: the mob prompt; fable's finding (1793094885381) §Q2 item 1; the lane spec §7.1-§7.8, §10.1,
§13.4-§13.5 (V); known-gaps row 59 and its neighbours (V); the J5 brief (1793071000000) (V); the
astra J0c re-check (1792590000000) (V); the J0c r5 and r6 reports (V; their lane runs on shiva are R);
`native/jolt-parser/jolt_parser.cpp` in full (V); `src/jolt/yang/antlr/cst.jolt` in full (V);
`src/cljc/yin/repl.cljc:1739-1749` (V); `test/resources/yin/repl/jolt-applicability.edn` (V);
`src/cljc/yin/repl/main.cljc` banner arm (~:977) (V); `yang.antlr.md` §10.1-§10.2 (V); the J4d
retirement commit `6f96bfb4` message (V).

Citation correction for the orchestrator's trail: the prompt cites "docs/design/yang.antlr.md
§7.8-§7.9 (the J0c/J5 gates)". No §7.8/§7.9 exist in yang.antlr.md (V, header sweep). The J0c/J5
gates are `yin.repl-jolt-implementation.md` §7.6-§7.8; yang.antlr.md's contribution is §10.1-§10.2,
the boundary policy and qualified-outcome classes that fix how a budget refusal must be reported.

# 1. The ruling and its crux

**Disposition (1).** Row 59 closes inside J5: the pre-parse budget set, the lifecycle/close/reset
wiring, and the unavailable-helper outcome all land as J5 acceptance content, and row 59 closes
with the evidence.

The crux, which the prompt's framing of (2) understates: **(2) is not "disclosure only"; it is a
gate amendment.** All three components row 59 names are already J5 gate text, not merely ledger
aspirations:

- the pre-parse bound: §7.6 "J5 enforces a pre-parse bound with the existing qualified frontend
  failure outcomes, tested below, at and above the bound" (V, :957-961), restated in §7.8's J5
  paragraph as "malformed-input limits" (V, :1029);
- lifecycle/reset: §7.8 J5 "reset/session behaviour and malformed-input limits; worker-cache and
  reset isolation" (V);
- unavailable-helper: §7.8 J5 "missing/incompatible-library refusals ... yields the existing
  qualified unavailable-parser result at the API seam, never another parser" (V, :1036-1039).

So "ship with the limitation explicitly disclosed" requires editing §7.6 and §7.8 to remove three
safety lines from the J5 gate plus re-scoping row 59 — an owner-visible spec weakening, the same
class of change the lane treats as sha-locked policy. And the deferral that produced row 59 was
conditional: astra's re-check authorized landing J0c probe-only with "No production or
frontend-readiness claim before these gates close" (V, item 4). The J5 acceptance **is** the
production/frontend-readiness claim — the Local REPL milestone puts `(lang :yang/python)` in the
standard catalog and evaluates arbitrary local input through a native recursive-descent parser.
Shipping that with no enforced bound means any pasted `print(((((…` or a runaway unary chain takes
down the whole jolt process (§7.6 blast radius, V: stack exhaustion is uncatchable), losing the
session mid-milestone. The owner's recorded style runs the other way at every comparable fork:
fail-closed handles after torn appends (J4c, `f5397a15`), "no fallback" (Q3, §7.1), "a crash fails
the test rather than erasing its report" (§7.8), the strict 1002 pin over the permissive drop
(fable Q1a). This owner pays the round for the teeth.

§7.6 itself names the only legitimate exit: "If safe enforcement cannot be shown, feasibility fails
and goes back to the owner." Nothing in the evidence suggests enforcement cannot be shown — J0c
left every primitive the bound needs already landed (see §2). So the owner-question path is not
triggered; J5 enforces.

# 2. The slice, if (1) — mechanism (P)

Three work items. All compose with the five J5-brief scope items and fable's additions 2, 3, 5 and
8 (the unavailable-helper legs, the fault-injection harness property, the retirement of the five
J5-owned applicability entries, and the deterministic-repeat/reset-isolation legs), which this
ruling folds in rather than duplicates.

## 2.1 The budget set and where it is enforced

**Two enforced axes, one derived axis.**

- **Input bound `N_bytes`** (V-proposed value: 4 MiB of source bytes). Enforced in the C++ wrapper
  at the top of `jolt_parser_export`, before `SourceStream` is constructed — the single choke point
  every production caller crosses, including convenience paths. New status `-6` (input over
  budget). The wrapper already returns the failure message through the nonallocating `message()`
  path, so no layout change; statuses are return codes, the JPX1 layout is untouched (V,
  `jolt_parser.cpp:215-228,448-457`). Guard the dev-only `jolt_parser_parse` identically for
  uniformity (one comparison).
- **Nesting bound `N_depth`** (V-proposed value: 256, derived below). Enforced by a **flat prescan
  in the wrapper**, same place, O(bytes) time, O(1) space, no recursion, no allocation beyond a few
  counters; runs before any ANTLR object exists. New status `-7` (nesting over budget), same
  message path. The prescan is the §7.6 "reviewed guards" clause made concrete: it must enumerate
  the recursive carriers the r5 stack table measured (V, r5 §"Stack shapes"): bracket nesting
  (`(`/`[`/`{` depth), unary-prefix runs (`-`, `+`, `~`, `not`), `**` chains, ternary chains,
  `lambda` chains, and INDENT depth. INDENT must be **counted, not inferred from input size**: the
  indent shape is input-quadratic (~n²/2 bytes for n levels, V r5), so an input bound alone does
  not bound it — 4 MiB admits ~2,900 levels while the macOS 512 KiB jolt thread crashes at
  1,280-1,344 (V, r5 table). The prescan tracks a leading-whitespace stack by the same INDENT rule
  the pinned lexer helper implements (already manifest-patched and JVM-canonicalized by J2b, V),
  charging each INDENT level as one nesting unit. Every counted carrier is charged at the **worst
  per-level frame cost measured** (the parens/call shapes, ≈1 KiB/level on the 512 KiB macOS jolt
  thread: crashes at 544/576 and 512/544 respectively, V r5), so cheaper shapes (not ≈100 B/level,
  indent ≈420 B/level) are over-refused before the expensive ones become unsound. Conservative and
  uniform beats per-shape precision; the carrier table and per-level costs are recorded in the
  build record, and the pinned grammar makes the carrier set enumerable by review (that review is
  an acceptance artifact, not a formality).
- **Time: derived, not interrupted.** A wall-clock budget is not enforceable in-process —
  cancellation is unsupported and §7.6 forbids pretending a timeout makes native termination safe
  (V: "A timeout does not make native termination safe... no asynchronous cancellation of an
  arbitrary native call is promised"). The honest enforcement is bounded work: `N_depth` bounds
  every superlinear shape the stress harness found (unary is ≈×9 per doubling — 256 deep costs
  ~1 s against r5's measured 512 → 8.2 s, V r5), `N_bytes` bounds token count, and the acceptance
  evidence **records the measured worst legal-input parse wall on both platforms**. The disclosed
  sentence "no cancellation; a legal-but-slow under-bound parse runs to completion" stays. A hard
  wall-clock kill would require the subprocess isolation the owner explicitly set aside (§7.1
  decision history) — that is an owner design question, out of J5 scope, and row 59 must **not**
  close on a watchdog claim the architecture cannot back.

**Derivation and clamping (P).** `N_depth` is a fixed constant recorded in the spec and the build
record, derived from the r5 table with a ≥2× margin under the shallowest crash bracket on the
smallest stack any jolt parse runs on (macOS 512 KiB jolt thread: call crashes at 512; 256 is the
2× margin). The wrapper additionally reads the calling thread's actual stack at parse entry —
promote the existing `jolt_parser_thread_info` logic from the test seams to a production read-only
helper (V, `jolt_parser.cpp:710-738`; both platforms already implemented) — and **clamps `N_depth`
down proportionally** when the caller's stack is smaller than the derivation basis, so a parse on
some future small fiber-adjacent stack cannot out-run the derivation. The clamp never raises the
bound. `N_bytes`/`N_depth` never vary otherwise: fixed constants keep the at/above tests
deterministic and platform-independent in assertion even though the evidence is per-platform.

**Failure outcomes (P).** The refusals are **operational failures, never language errors**:
`yang.antlr.md` §10.2 classes "resource limit" as an operational event and forbids reporting it as
a parse error (V). Mechanism: the adapter maps statuses `-6`/`-7` (and mirrors the input check
pre-FFI for a clean diagnostic without the call) to an ex-info through the **same channel
`:unavailable-parser` already uses** — `{:diagnostic :parser-budget, :axis :input|:nesting,
:bound n, :measured m, :lang …}` — thrown at the parse stage so the REPL renders it like
`repl.cljc:1746` renders its diagnostic (V). Session survives; other frontends unaffected; never
another parser; no partial packet published (the existing "a failed parse never yields a packet"
property of `export-records`, V `cst.jolt:231-244`, is preserved — the budget fires before any
buffer exists). Parity note, disclosed as a host limit: above the bound jolt refuses where the JVM
would parse; the oracle-parity corpus and the frontend suites sit far under the bound, so J2b's
zero-pin instrument is untouched, and the divergence is recorded in the same declaration that
discloses the library.

## 2.2 Lifecycle/close/reset (P)

`close-worker` exists and is idempotent with CAS no-double-free (V, `cst.jolt:282-291`); what row
59 records is that **nothing calls it** (V, r5 disclosures: "close-worker is not wired into the
shared stage or repl_probe"). J5 wires it: the shared parser stage owns its worker and disposes it
at reset and teardown, reset remakes it, convenience-call workers are disposed on their one call,
and a closed worker refuses further parses (already implemented, V `cst.jolt:225-228`). Evidence:
the existing 12 worker tests extended with a close/reset cycle; the 20,000-cycle flatness and
sanitizer legs rerun with the wiring in place; §7.8's "worker-cache and reset isolation" legs run
on the composed REPL (this is fable's addition 8; same work).

## 2.3 Unavailable-helper behavior (P)

Today the library loads with the namespace, so a missing or wrong-version library fails the load —
the cst docstring says so and names J5 as the fix (V, `cst.jolt:24-28`, layout check `:68-71`). J5
makes the load lazy and soft: `yang.antlr.cst` (jolt) loads without the library; the first use
attempts `ffi/load-library` + the layout-version check and caches the failure; make-worker and the
parse entries then answer the **existing qualified `:unavailable-parser`** result at the API seam
(V, `repl.cljc:1739-1749`) — never another parser, never a crash, no JVM/Node needed — and a later
selection retries the load (cheap re-stat; the clean-deployment leg builds the library before the
REPL starts anyway). Legs, both platforms: library absent; wrong layout version; and Clojure/PHP
still selecting and evaluating in the same session (fable's addition 6 pairs with this). The one
implementation risk in the whole slice is jolt's `ffi/load-library` deferral semantics at ns load
— spike it first; if the host fights ns-load deferral, load lazily inside make-worker and keep the
ns-load path library-free.

## 2.4 Test gates (P)

Per budget axis, three legs on both platforms: **below** (corpus plus a just-under synthetic per
measured shape parses; where the JVM also parses it, packets match the oracle), **at** (a legal
input at exactly `N_depth`/`N_bytes` parses; bound+1 refuses with the exact qualified diagnostic
asserted, and the session evaluates again afterwards), **above** (the falsifiable
"unbounded-recursion-never-ships" line: in-process inputs at 10× the old crash depths — 100,000
nested parens, ~5,000-deep INDENT source, 100,000 unary prefixes — all refuse, no crash,
deterministic repeats with identical digests). Plus one child-process leg reusing the
`jolt_j0c_stack.clj` shape generators to show the old crash depths (e.g. macOS-thread parens 576)
now refuse rather than SIGSEGV, and the fault-injection harness property from §7.8 (a crash fails
the test, report retained — fable's addition 3) covers the injected-exception and forced-abort
jolt-child legs. The library-absent and lifecycle legs per §2.2-§2.3. Everything lands in the
existing harnesses (`jolt_j0c_faults/workers/stack`, oracle-parity, `bb probe:jolt-file` child
pattern); no new lane.

# 3. Interaction with the retired entries and the fail-closed state

**Parse-path only; no interaction.** The budget is enforced inside the parse stage, after whatever
produced the source text. The J4c fail-closed descriptor state governs file/journal operations; a
J5 persistence leg that reads Python content through the `file:` store orders store-read
(may fail-closed) → budget check → parse, and the two refusals carry distinct diagnostics over
different objects (an FD state vs a source string) — neither can fire on the other's condition, so
neither masks the other. The budget does **not** apply "before the fail-closed refusal" in any
meaningful ordering sense, and nothing in row 59's closure re-opens or re-guards any of the 42
J4-owned guards J4d retired (V, `6f96bfb4`: store 30, state 8, index 1, main's three custody
tests; 24 applicability entries remain, none J4). The only applicability entries J5 touches are
its own five `:owner "J5"` rows (V, `jolt-applicability.edn` lines 5-10, 33-36) plus the
`main.cljc` banner arm "python: not installed on this host until J5" (V) and the §10.1 J3 row's
"Python unavailable until J5" cross-reference — retired by the capability switch itself, under
either disposition. The budget legs are jolt-only tests, not parity corpus entries, so the J2b
rows (56-58, closed) are untouched.

# 4. Schedule and confidence

**(1) is one contained integration round, not open-ended C++ work.** The wrapper already has every
primitive the bound needs: the status-code protocol with a nonallocating message path, the
`thread_info` stack read on both platforms, the worker/hold machinery, and the fault/cycle/
sanitizer/stack harnesses (V, `jolt_parser.cpp` in full; r5/r6 matrices). New native code is a
prescan, two comparisons and two statuses — no grammar change, no runtime fork, pins untouched.
Estimate: wrapper ~0.5 session; cst.jolt lazy-load + status mapping + stage wiring ~0.5; carrier
review, derivation record, the §2.4 legs and both-platform runs ~1-2 — call it 2-4 engineer
sessions inside the J5 round, composing with the J5 brief's items rather than serializing after
them. **(2)** saves roughly that and costs: the §7.6+§7.8+§10.1+row-59 amendment round (itself an
owner round-trip — this adjudication is that round-trip, spent either way), a crash-anywhere input
vector in the flagship milestone, and a later slice that re-enters the same acceptance structure
with colder context.

**Recommendation: (1). Confidence 0.85.** The residual risks, stated so the owner can weigh them:
the prescan's carrier enumeration is review-based — a missed carrier fails open until found (the
at/above child legs and the pinned-grammar review bound this; the r5 shape table is the measured
carrier list, and over-counting is the prescribed default); and the lazy-load change is the one
place jolt host semantics might fight the design (spiked first, with the make-worker fallback).
Nothing observed suggests either is unsound.

# 5. What stays disclosed even under (1)

Row 59 closes, but these disclosures survive it, per its own "keep these limits disclosed" clause
and astra item 4: no process crash isolation and no worker-restart claim (a bug in an under-bound
parse still takes the process — the bound removes the unbounded-input vector, not blast radius);
no cancellation of a legal-but-slow under-bound parse; TSan not run and LSan without a positive
control; the residual FFI cleanup limits (no obtainable pointer / failing release primitive);
performance unmeasured. The unary/indent "censored measurements" wording is **superseded by
enforcement** rather than resolved: the r5 stack table remains the derivation evidence, and the
row's closure text should say the bound replaced the need for a safe measured depth, not that one
was established.

# 6. Ledger changes landing with the J5 acceptance (P)

Row 59 closes with the §2.4 evidence; §7.6 and the §10.1 J5 row update to landed (the J5 row
additionally drops "not started"); the five `:owner "J5"` applicability entries retire against
count-identity with the JVM (fable's addition 5 consequence — master's jolt counts are not the
target, they embed the exclusions); the `main.cljc` banner arm and the J3-row cross-reference go;
the budget constants, carrier table, derivation and measured worst legal-input walls are recorded
in the spec/build record; the above-bound divergence from the JVM enters the J5 declaration's
disclosure paragraph verbatim alongside the library and no-crash-isolation sentences.

# Summary for reconciliation

| Question | Answer |
|---|---|
| Disposition | (1) — close row 59 inside J5; (2) is a gate amendment, not disclosure-only |
| Bound | `N_bytes` 4 MiB + `N_depth` 256 (2× margin under the shallowest measured crash), stack-clamped; time derived and recorded, never interrupted |
| Enforced where | C++ wrapper, pre-parse, before any ANTLR object; adapter mirrors the input check and maps statuses |
| Outcomes | operational `{:diagnostic :parser-budget …}` through the existing qualified channel; never a syntax error, never another parser |
| Test gates | below/at/above per axis, both platforms, deterministic; 10×-crash-depth in-process refusals; child legs at the old crash depths |
| Row 59's other items | lifecycle: close-worker wired into stage/reset/teardown; unavailable-helper: lazy soft load → existing `:unavailable-parser` |
| Retired entries / fail-closed | no interaction; parse-path only; only J5's own five entries + banner arm retire |
| Schedule | +2-4 sessions inside the J5 round; all primitives already landed |
| Confidence | 0.85 for (1); residual risks = carrier-enumeration review and the lazy-load spike |
