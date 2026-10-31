[claude-code:unrecognized_model] {"model":"glm-5.3","query_source":"sdk"}
Completed-GMT: 2026-09-07 12:28:33 GMT
Completed-Local: 2026-09-07 19:28:33 +07 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac

# Review — the traces-not-policy proposal (amends `datom.world.md`)

Verdict up front: **the principle is axiom-level and belongs beside the six; the draft invariant is not yet invariant-grade; the implementation is right in shape and wrong in two concrete details; and the rhetoric toward the predecessor is overheated in a way that matters because the predecessor was this project's own reviewed decision 36 hours ago.** Not over-reach — but not landable as drafted. The seven questions, then findings.

## 1. The two-case distinction: real, and one case short of exhaustive

The split is sound and load-bearing. An unrecognized *operation answer* leaves no successor, so "stop" is not chosen — it is the only total move, and the cursor law is satisfied vacuously. An unrecognized *value* is ordinary syntax (Axiom 2), and the proposal is right that `rpc/poll-read` already does the second case correctly: `decode-value` catches everything into a diagnostic, the element is consumed, the disposition recorded. Verified in source.

The third case the taxonomy glosses: **the throwing operation.** `(stream/next source cursor)` that *throws* is neither an unrecognized answer (nothing was answered) nor an unrecognized value (no value exists). Its existing treatment — propagate — is correct, because the contract reserves exceptions for defects in the host's own assembly, and a transport that throws is exactly that. The proposal half-acknowledges it in §2 ("if the transport *threw*, that is a different trace") without admitting it into the taxonomy, so the claim of exhaustiveness is imprecise. It costs one sentence to name: *answers and values are data and become traces; throws are defects and propagate — the invariant deliberately binds the first two only.* Without that sentence, the first implementer will ask whether throwing reads must now be caught and traced, and the honest answer should be in the document, not in a review.

## 2. "The core deposits nothing itself": right mechanism, and the culture already accepts its weakness

Returning the trace as data is correct — handing the step a writer would make the deposit destination the core's knowledge (the inversion Host Boundaries names) and give it a second failure mode. And the objection "every caller now holds a trace it may drop" is answered by the system's own precedent: `rpc`'s diagnostics already carry exactly that contract ("a caller that discards them discards them knowingly"). Knowing-discard with named responsibility is house culture. The real problem is not the mechanism but that the *invariant's wording* promises more than any layer delivers — see question 6.

## 3. Is `transport-error` actually wrong? Half-right — the contract is silent, not deciding for the fold

The precise reading: `dao.stream.md`'s Reading table gives `transport-error` a specific meaning — "the transport failed to perform the read; nothing was observed" — and a non-conforming answer does not satisfy it (the proposal is right that the transport may have performed a read and answered in an unknown vocabulary; also right that it may not have — *what actually happened is unknown*, which is the whole point). But the Result Convention's "transport defects are all outcomes" shows the contract anticipated transport defects while leaving their honest naming undecided — it could not do otherwise, since the case is outside its vocabulary by hypothesis. So the current fold is **a legal, lossy encoding, not a meaning invented from nothing**: it carries the distinguishing marker `:dao.stream/invalid-operation-result`, and since `b2bf609`, the raw `:dao.stream/answer`. The loss is real — a completion reader cannot distinguish "network died" from "transport answered gibberish" without digging, and the REPL reports "transport error" for a scripted `:wholly-unexpected` — so `:unrecognized` is the better encoding and the change is justified. But "invents a meaning" and "laundering" overstate a predecessor that this project reviewed and landed with its markers visible. What lands should make the change and retire the accusation — the git history records that the fold was a decision, not an oversight.

## 4. The status-set question: sound, with an arithmetic slip

The step's statuses were never contract outcomes — `:defect` and `:failed` are already step vocabulary naming groups of contract outcomes. `:unrecognized` names the complement: non-answers. It is not the invention the catch-alls are accused of, because no contract keyword is forged and the raw travels unaltered. The self-objection is answered correctly, and the proposed docstring line is the right precaution. One precision slip: the set is **seven**, not eight — six statuses today (`:advance`, `:retry`, `:ended`, `:gap`, `:defect`, `:failed`) plus one. In an axiom-adjacent document, the self-count should be right.

## 5. The diagnostics-vector claim: fair in substance, overstated in one clause

Single reader, drained once, not a stream — yes, that is ADR-0003's rejected shape minus even stream properties, and "one layer lower" is accurate. But "no retention policy" is not: `take-diagnostics`' own docstring documents the bounding discipline and warns precisely of the unbounded case. It is acknowledged interim debt with a named end condition, not unnoticed debt. The Wait section handles the conclusion correctly; the clause should be softened because the docstring did the acknowledging.

## 6. The invariant: belongs beside the six — after rewording

The principle is genuinely axiom-level: it is Axiom 2 turned on the interpreter itself, and the alternative it forecloses — an `:on-unknown` parameter, a severity, a default action — is coordination smuggled into the one layer that must not coordinate. It contradicts nothing among the six; it is a prohibition in their family. **But the draft is not invariant-grade, three defects:**

1. **"Leaves it in the medium as a trace" is false at the layer it binds first.** The pure step has no medium; §3's "returning the trace *is* the deposit into the only medium it has, the caller's state" is a fudge of the word *medium*. Nothing deposits to a stream until `dao.space`'s writer face exists — the proposal's own Wait section says so. An invariant cannot promise what its own plan defers.
2. **"Takes no action on its behalf" is violated by a flagship case as worded.** `rpc`'s response — go terminal and lose every outstanding request — is a significant action taken upon meeting an unrecognized answer. The proposal's defense (a consequence for its *own state*, not an interpretation of the answer) is correct but lives in a review, not in the sentence. An invariant that needs a defense brief for one of its four exhibits is worded too loosely.
3. **It flattens answer and value** — whose correct treatments *differ* (no advance possible vs advance after a total effect records a disposition). The proposal calls that distinction load-bearing; the invariant should not blur it.

A draft in the six's register that fixes all three:

> *Do not interpret what you cannot interpret: an answer or a value outside an interpreter's vocabulary is carried onward exactly as it came, as data; the interpreter assigns it no meaning and takes no action on its behalf — consequences for its own state are its policy. What the carried thing means belongs to whoever reads it.*

"Carried onward … as data" is true today of a return value, an `ex-data`, and a future medium alike; the carve-out is in the sentence; answers and values share the carrying duty without sharing a disposition. The *Host Boundaries* sentence is the right addition with the same disease in miniature: "never what it means or what should happen" would indict `dispatch-request`'s `:handler-error "Handler failed"` unless the happened/meant line is drawn — propose *"says what was received and what happened, never what it means or what should be done about it."*

## 7. Cost accounting: nearly complete; I checked the two candidates it might have missed

- **`serving`** — needs no change, and the proposal's claim is verified: its status `case` ends in a default that closes the session (serving.cljc:317–330), so the two new terminal statuses flow through unhandled-by-name, which is the correct disposition of the *connection*.
- **`conformance.cljc`** — verified clean: no malformed-answer fixtures, no `transport-error` expectations; untouched.
- **The only other fold consumer is `rpc.cljc:125` itself** — counted.
- It is **six** code namespaces, not five (`runtime.v2` is listed in §6 but the framing counts five) — a slip, not an omission.
- The genuine misses are filed below: the descriptor enrichment, the docstring paragraph that does not "stand," and the correction-of-`b2bf609` lineage.

## Findings

**P1 | proposal §5, the invariant draft | Not invariant-grade as worded: "in the medium" is false at the core layer, "takes no action" lacks the own-state carve-out that `rpc`'s terminal-and-lose requires, and answer/value are flattened despite their different dispositions. | Adopt the reworded draft above (and the happened/meant form of the Host Boundaries sentence) before any of this lands.**

**P2 | proposal §2, the trace spec | `:dao.stream/identity` via `descriptor` puts a second protocol call on the least-trustworthy handle at the least-trustworthy moment, and it breaks the tests: `observe_test`'s reader doubles implement only `IDaoStreamReader` (observe_test.cljc:15–21), so an unguarded `stream/descriptor` throws *inside the trace-construction path* and the nine malformed cases crash instead of asserting. "Descriptor is total by contract" is a promise of *conforming* transports — a handle that just violated the result convention is not owed that trust. | Guard the enrichment (attempt, omit `:dao.stream/identity` on any non-`ok` or throw, with the omission itself being a fact), or drop it from the core's trace and let the composition — which holds the handle — enrich before depositing. Count the test-double change either way.**

**P2 | proposal §6, first bullet | The uncommitted `observe.cljc` docstring paragraph does not "stand and gain one sentence": it is the boundary paragraph from the generalization round and it names `valid-or-transport-error` and the fold-unification plan — this change deletes that function. Bundling a stale paragraph with a semantic correction to a landed commit also mixes two logical changes in one working tree that already holds them. | Land the boundary-paragraph change (rewritten for the post-fold world, including the one-place-a-new-outcome-arrives argument) as its own commit — before or inside this change, never assumed to stand.**

**P3 | proposal §6 and self-objection | Arithmetic and counting: the status set becomes seven, not eight (six today plus `:unrecognized`); six code namespaces change, not five. | Fix the counts — an axiom-adjacent document earns scrutiny of its own numbers.**

**P3 | proposal §1, §3, §5 rhetoric | "Invents a meaning," "laundering," and "no retention policy" overstate a reviewed predecessor that carried distinguishing markers (`:dao.stream/invalid-operation-result`, and `:answer` since `b2bf609`) and a docstring that documents its own bounding discipline. | Retire the accusations in what lands; make the change on its merits. The history should record an encoding improved, not a crime corrected.**

**P3 | proposal §1 taxonomy | The throwing operation is a third case (neither answer nor value); its treatment (propagate; host defects are exceptions per the contract) is correct but unnamed. | One sentence in the docstring and in the invariant's scope note: throws are deliberately outside the invariant.**

## What should land first, if this is accepted

One reviewed change, two commits, no gap between them — the axiom must never be live while shipped code violates it. **First commit (docs):** the reworded invariant and the Host Boundaries sentence in `datom.world.md`, plus the DaoJing plan's Decision 1 row, routed as a design-document change per house style. **Second commit (code):** the core (`:unrecognized` + guarded trace), `observe_test`, `forward`, the VM, `runtime.v2`, then `rpc`/`apply`'s reason rename with the REPL adapter remap — with the stale boundary paragraph rewritten in its own preceding commit. The Wait list is correctly gated on `dao.space` and should stay exactly as proposed.

The user's entitlement, plainly: **this is not enthusiasm dressed in the system's vocabulary — the invariant is a real consequence of Axiom 2, and the code change is a real improvement over the fold. But an axiom accepted on a draft sentence would be worse than no axiom, and the draft sentence is what is currently on the table. Fix the wording, guard the trace, split the commits, and it should land.**
