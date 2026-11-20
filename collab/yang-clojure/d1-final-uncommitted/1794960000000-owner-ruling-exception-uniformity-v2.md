Created-GMT: 2026-11-07 04:30:00 GMT
Created-Local: 2026-11-07 11:30:00 +07 (+0700)
Status: OWNER-APPROVED 2026-11-07 ("enforce everywhere consistently. this should be a separate track."); v2 SUPERSEDES v1 (1794920000000) per the review round — architect gpt-6-astra, engineer mob gpt-6.1-sol + gemini-3.8-flash: all three ACCEPT WITH AMENDMENTS, folded below. Qwen aborted (headless tool denial); kimi quota-exhausted (429).
Review records: collab/exception-track/1794930000000-architect-exception-ruling-review.gpt-6-astra.stdout.log, 1794930000000-engineer-exception-ruling-review.gpt-6.1-sol.stdout.log, 1794930000000-engineer-exception-ruling-review.gemini-3.8-flash.stdout.log.

# RULING OF RECORD v2 — the exception track: §8.10.7 enforced uniformly in all four frontends

## The decision

Exception handling is enforced uniformly across yang.clojure, yang.typescript (js),
yang.php, and yang.python. At every admitted guest-call boundary, an escaping source
exception compiles down to THE SAME continuation: the caller's supplied raise exit
receiving the §8.10.7 condition

    {:yang.call/reason :yang.call/thrown, :yang.call/value original-thrown-value}

and every receiving runtime implements the §8.10.7 reception rules as written. This is a
SEPARATE TRACK: its own folder (collab/exception-track/), its own rounds, its own
acceptance gate; the four frontend seats cede the exception-fix surfaces to the track and
receive per-frontend results back into their records.

## Scope bounds (v2: sharpened per astra §5)

- "Every boundary" means ADMITTED GUEST-CALL boundaries. VM/custody defects and host
  implementation failures remain operational failures (design :3904); no broad
  host catch-and-relabel is authorized.
- A RETURNED condition-shaped map is ordinary data; a THROWN condition-shaped map is the
  payload of a fresh condition; shape is never evidence of an existing condition to
  forward (the cell-ref rule, restated — R5).
- "Same continuation" does NOT require identical internal lowering (R4's allowance).

## The requirements (v2: amended per the review round)

**R1 — the callee bridge (js AND php non-compliant today).** Every frontend's published
function entry, closure entry, and higher-order entry installs the exception bridge
BEFORE argument binding: it reads the supplied `:yang.call/raise` and delivers every
escaping source exception AND argument-binding failure to it as the thrown condition,
exactly once, with execution after the call unreachable. js entries apply `:code` without
reading the raise exit (prelude.cljc:1581); php's function entry (:2264) and Closure entry
(:4217) lack it, and php's raise with an empty handler stack RETURNS normally
(prelude.cljc:194-197). The bridge must also satisfy §8.10.8: complete cleanup and
handler/depth/frame restoration BEFORE the transfer; a later generator resume never
reuses a raise exit belonging to a completed call.

**R2 — foreign reception and retained-condition lifetime (python, php non-compliant
today).** On receiving a thrown condition: (1) a payload valid in the receiving runtime
keeps its ordinary catching and identity behavior — delivered UNCHANGED (python raising
an already-local exception raw is compliant; python raising an OTHERWISE-FOREIGN payload
raw at :1034 is not); (2) js may deliver the otherwise-foreign payload raw; (3) python
and php represent an otherwise-foreign payload with their own generated
`ForeignException`, retaining the complete condition and original payload — catch-all
catches it, typed catches match the LOCAL hierarchy, never guessed foreign class names,
and formatting it never implicitly invokes foreign methods. Retention rides the generated
exception's IDENTITY for its ENTIRE LIFETIME, outside source-writable attributes, with no
ambient registry (design :3878, :3885). Raising the whole envelope (php :2384) or an
otherwise-foreign raw payload (python :1034) is non-compliant.

**R3 — protocol failures retain and forward the condition (ALL FOUR frontends; v2
widened per astra §1 / sol §1).** `:yang.call/not-callable`, `:yang.call/invalid-invocation`,
`:yang.call/unsupported-binding` (fully qualified) map to a documented local exception
form retaining the complete original condition — qualified reason AND plain evidence — so
an unchanged rethrow FORWARDS the original condition without a new wrapper. js discards
the condition into fresh text (:1627); python builds a generic TypeError (:1046); clojure
retains it as catch data (:1306) but public escape reclassifies through unconditional
`yang.call/thrown` (:1088); php constructs a fresh Error for not-callable and raises the
envelope otherwise (:2384), and its source rethrow rejects that envelope (:4085). All
four are in scope, reception AND outbound rethrow.

**R4 — clojure unifies or proves equivalence.** The intra-unit outcome-token channel
(clojure.cljc:1115, :1125-1133) may remain an optimization only with equivalence proof
across caught/uncaught throws, protocol rethrow, binding failures, and higher-order
re-entry (§8.10.9's bar) — a single passing example is insufficient. Public
protocol-rethrow repair (R3) does not wait for this decision. Clojure's restricted catch
surface (single catch, no `finally`) is a source-semantics decision this ruling does not
change.

**R5 — no new shape-routing.** Per the scope bounds above; plus: forged or
source-writable attributes on a wrapper-looking value never authenticate a retained
condition.

## The baseline (audit of record)

The gpt-6.1-sol audit (collab/yang-python/1794900000000, verdict DIVERGENT, HEAD
e9a0b430) is the baseline. Citation corrections of record from the review round: js
follows its GUEST continuation handler stack (js.rt/ctx, prelude.cljc:1704), not the host
engine's; php's empty-stack return is :194-197 (envelope reception :2384); clojure's
actual private branches are :1115 and :1125-1133 (:1110 is the packet-length guard).

## Acceptance gate (v2: widened per astra §4 / sol §4)

The track is DONE when ALL of the following hold, with real source on both sides of every
leg:

1. **The directed pair matrix — 12 cross-language directions** derived from the four
   admitted frontends (clojure↔python and clojure↔php explicitly included), plus local
   regressions.
2. **Callable-kind coverage**: named exports AND closure/higher-order entries (php
   Closures concretely), supported methods/callable objects per frontend capability.
3. **The §8.10.11 exception scenarios**: binding failures; arbitrary foreign thrown
   data; catch-all and typed catches (where the source has them — js has no typed-catch
   syntax, clojure's admitted surface rejects `finally`; exercise cleanup in a frontend
   that supports it and PRESERVE clojure's rejection tests); unchanged rethrow; finally
   replacement; protocol-error rethrow; condition-shaped thrown maps staying data;
   A→B→A propagation asserting original identity and cleanup order; the stored
   ForeignException rethrown from another activation.
4. **Context lifetime**: repeated success→raise→success, cross-runtime recursion,
   suspension/resume — handler/depth/frame state restored, exactly-once
   binding/cleanup.
5. **Host-by-VM results**: JVM, Node, and Dart across the admitted VM paths, with
   explicit refusals; portable parser-produced fixtures where the JVM-only kit cannot
   reach; bundled-walker paths where admitted, never requiring unsupported linked
   execution; bundled/linked parity and task isolation preserving existing custody
   refusals.
6. **Identity assertions BEFORE rendering**: original reasons, complete evidence, payload
   identity, wrapper non-stacking, cleanup order — equal rendered text is not identity
   evidence.

## Sequencing (v2: corrected per astra §3 / sol §3 — the v1 order is REJECTED)

0. Contract + focused FAILING fixtures first (retained-condition provenance; boundary
   fixtures construct malformed invocations source cannot naturally build).
1. **JS R1 + R3** together (R3 alone cannot cross the boundary while entries ignore the
   exit).
2. **PHP R1 + R2 + R3 as ONE coherent round** — function entry, Closure entry, and all
   three call helpers (php/call, call-with-binding-profile, call-named). Landing the
   bridge before reception repair would route local Throwables into the envelope-raising
   receiver and break existing local typed catches.
3. **Python R2 + R3 with the egress change** (prelude.cljc:912's always-fresh condition
   must become condition-preserving for retained wrappers).
4. **Clojure R3, then R4** (the optimization decision last).
JS and Python work proceed independently once the contract lands; no Clojure-containing
pair declares conformance before its R3 gap closes.

— filed by the yang.python orchestrator seat; owner decision 2026-11-07

---

## SUPERSESSION (append-only) — the clojure `finally`-refusal clause, retired

**Owner ruling RETIRE, 2026-11-13 09:20 +07** (`1794556000000-owner-decision-finally-refusal-row.md`):
dialect surface derives from the source language; the LaTTe corpus verifies admissions and no
longer authors them. **v2 §3's finally-refusal clause is superseded.**

Two clauses of this ruling are affected. Neither is edited in place; both are superseded here.

1. **Acceptance gate §3** — "clojure's admitted surface rejects `finally`; exercise cleanup in a
   frontend that supports it and PRESERVE clojure's rejection tests". The preservation duty is
   discharged. Clojure-caller `:finally-replacement` legs are now live in the pair matrix and
   exercise cleanup in clojure itself, on all three vector VMs.
2. **R4** — "Clojure's restricted catch surface (single catch, no `finally`) is a source-semantics
   decision this ruling does not change." Only the `no finally` half is superseded. The
   **single-catch and catch-class restriction still stands** and is still pinned
   (`exception_source_test.clj`, `(try 1 (catch Throwable e 2))` remains refused;
   `pair-matrix`'s `:catch-surface :single-Exception` for a clojure caller is unchanged).
   Widening the catch classes belongs to the queued dialect-definition round, which derives the
   portable-clojure subset from the language rather than from corpus residue.

Governing principle, verbatim from the ruling: *a dialect's surface derives from its source
language; the proof corpus verifies admissions, it does not author them.* The corpus-driven
minimalism was sound as a scope cut for kernel hardening (`211dded9`) and wrong as inherited
policy once the frontend's job became general Clojure.

Kit round implementing this: branch `exception-kit-finally`.

Completion clarification (gpt-6.1-sol, 2026-11-13): the admission above is the
governing contract. Executable pair deftests now cover python→clojure,
php→clojure and typescript→clojure; their measured outcomes and the separate
port-composition prerequisite are recorded append-only in `acceptance-ledger.md`.
