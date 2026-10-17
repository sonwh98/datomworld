Completed-GMT: 2026-10-14 04:02:02 GMT
Completed-Local: 2026-10-14 11:02:02 +07
Coding-Agent: claude
Session-ID: aa937a79-1f03-4d96-8086-95aabdc1158e

Plan mode was active, so the findings could not be written to `collab/`. The full position is in the plan file and below. The brief asked for a mob position only, and the positions below stand on their own without the plan file. Sources read: `datom.world.md`, the I4 scoping design, `yin.vm.linker.dht.md` 4.1-4.2, 7.3-7.5, 9, `yin.vm.linker.md` 4.2-4.3, 7.2, `yang.antlr.md` 8.5.6, plus five code facts checked read-only and cited where they bear on a ruling.

## Q1. Unpinned optional imports: Option A, land strict, ledger the unpinned case

A `nil` pin is a name claim with no address. Section 7.4 says a pinned address "is never a name claim: it says what the publisher linked against." A nil pin says the publisher linked against nothing, so the reader would bind whatever its fold answers with nothing to compare at receive. That is the hole the mob's Q4 named, reopened for a subset of names. Under the owner's ruling that A is the only code identity, a dependency edge without an address is not a dependency edge.

Four further reasons, each sufficient on its own:

- **It contradicts a landed linker rule.** Step 5b of `yin.vm.linker.md` 4.2 states that an obligation with no manifest address discharges nothing, even against a receiver entry that has none. A read of a nil-pinned module's exports would be undischargeable by rule, so 5a/5b would need a new exception exactly for the unsafe case.
- **It is new persisted structure in a content-hashed record.** The manifest validator at `src/cljc/yin/vm/linker.cljc:847` requires every value under requires to be a segment address. Admitting nil is a schema revision. Every manifest moves, and the walker, the binding check, the install pin check, the future pin-conflict rule and the publish footprint all grow a nil branch. Derive, don't persist forbids this when the I4 acceptance line does not need it.
- **Optionality is a Python inference, not a linker fact.** Deciding that an import is optional means reading `try/except` shapes in the frontend, and the shapes are many. A fragile syntactic inference would become a pin shape in a language-blind record. That crosses the boundary in the wrong direction.
- **Strict I4 satisfies the acceptance line.** "Absent names included" is met by absent-at-the-reader: pinned at publication, unresolvable in the reader's fold, `try-require` answers absent, the except branch runs.

Consequences. Publication keeps refusing an unpublished import in `publication-order`, and its text should say the import is unpinnable, naming the unit. Runtime resolution is unchanged from the scoping design, and the pin-conflict rule stays total over addresses. The ledger entry should name the future shape explicitly: a separate declaration for name-only optional requirements, resolved by the reader's fold without pin comparison and admitted by the holder, needing a schema revision and a 5a/5b rule. Never a nil inside the requires map.

## Q2. Surface: Option A, bare VM primitive `try-require`

`require` is a bare primitive at `src/cljc/yin/vm.cljc:410`. `try-require` is the same mechanism with one flag flipped, namely who learns of the refusal. Splitting the two halves across two declaration systems is gratuitous asymmetry. The poll/next precedent is about refusal as data, not about where the word lives. The stream poll export is a host export because the whole stream module is one, and `require` is not.

- **Language-blindness** is equal under both options. No linker datum changes either way.
- **Composition overhead** is not equal. A host module must be registered in every composition that evaluates a module using it, and the Python lowering's free-name declaration resolves a qualified name only through the registry's host-export profile. A bare primitive is declared through the existing primitives profile with zero lowering edits and zero registry setup.
- **Publication** already refuses a host-module-qualified free name in the index-derived path at `src/cljc/yin/vm/linker/publish.cljc:302`. A bare primitive rides the existing primitives declaration by profile address.
- **Effect profiling** is identical in power. The distinct effect kind is what matters, so a callee whose profile omits it cannot observe linking refusals, and both options give that.
- **VM invariants.** The engine already interprets the require effect. One more kind registers through the existing park-entry fallback with no kernel edit. A host module named `module` would also occupy a plausible user module name and invite the dotted-prefix registry defect of ruling 3.

Ruling: bare primitive, effect kind `:module/try-require`, declared under the manifest's primitives by profile address. The doc's `module/try-require` wording is to be read as the effect kind and amended in `yang.antlr.md` 8.5.6 and `yin.vm.linker.md` 7.2 step 7.

## Q3. `ModuleNotFoundError` scope: Option A, absent by name only

CPython raises `ModuleNotFoundError` when no finder locates the module. It raises plain `ImportError` when the module is located but cannot be imported: a loader failure, a circular partial initialization, a hook that declines. An admission refusal is located-but-declined. The fold resolved the name to an address and the holder's policy refused it. That is a loader-stage failure, so `ImportError`.

Transparency decides it. Trust is a configurable policy by the owner's 2026-10-12 direction. Under Option B, a narrow `except ModuleNotFoundError` fallback would silently degrade on an untrusted principal, and the user would never learn that trusting the principal fixes it. A policy decision must not wear the shape of absence. Under Option A the refusal data still rides on the exception; the class only chooses which except clauses run.

Fault isolation favors A too. The common idiom catches `ImportError`, which catches both under either option, so no real optional-import code breaks. The narrow class exists to mean "not there". Widening it merges two causes with two different remedies, publish-or-declare versus trust.

The mapping over the I4 response datum:

- Refused with reason absent carrying a name, the fold found no accepted assertion: `ModuleNotFoundError`, with the `name` attribute set and the refusal carried.
- Refused with reason absent carrying an address, a content miss after resolution: `ImportError`. Located but unreachable is CPython's `ImportError`.
- Ambiguous name, every admission reason, pin mismatch, dependency binding, require cycle, descriptor defect, export missing, foreign image, and the linker's own refusals: `ImportError` carrying the refusal.
- Status lost after abandonment: `ImportError`.
- A registry hit or an ok answer never raises.

Shape: one new builtin row placing `ModuleNotFoundError` under `ImportError` in the prelude table beside the existing row at `src/cljc/yang/python/antlr/prelude.cljc:3241`. The refusal branch matches on the reason keyword and the presence of the name key, plain data, never on text, per the section 9 consumer rule.

## What is not done

The findings file `collab/1791950277753-architect-i4-mob-questions.claude-fable-5-1.findings.md` is not written, because plan mode forbade edits outside the plan file. The complete text is in `/Users/sto/.claude/plans/read-collab-1791950277753-architect-i4-m-majestic-aho.md` and above. No tests were run and no source was edited.
