Created-GMT: 2026-11-19 (ruled; filed by the seat — astra's session is read-only)
Coding-Agent: codex (gpt-6-astra, jupiter POOL 2, read-only)
Session-ID: 01a1effe-d75d-7b80-8495-9e6a5e6d836a
Verdict Q1: r18's structured refusal payload AUTHORIZED as-is — the operational-refusal terminal may carry the structured :yang.compile/* answer in :php/fatal; consumers discriminate via :yang.compile/outcome == :yang.compile/rejected (guard code supplied; never stringify). Rest of r18 landable within declared scope; landing record must distinguish the earlier timeout from the subsequent green gate (188/5066/0).
Verdict Q2: the source supports frontier-r1's diagnosis — the fixture throws a PHP-created replacement AFTER shutdown released the global (spec's rethrow description incorrect); the carrier mint/reader is correct as-is. Authorize the staged order: seed_replacement -> capture payload -> probe -> compare escaping payload with the capture. R5/R2 rule 3 preserved; acceptance unchanged (the row x 3 backends + intact assertions); the fix is authorized but acceptance NOT declared achieved.

**Q1 — Authorize r18’s structured refusal payload as-is.**

The contract requires operational refusals to remain operational and preserve their evidence; it does not require every `:php/fatal` value to be text. [§8.10.7](/home/sto/ci/dw-evalb/docs/design/yang.antlr.md:3904) protects that classification. The [byte-domain ruling](/home/sto/ci/dw-evalb/collab/yang-php/1794605712000-architect-bytedomain-ruling.findings.md:51) explicitly requires the structured refusal reason to survive in the run outcome. The class-fatal ruling’s textual payload requirement applies to **guest declaration fatals**, not every operational terminal.

The implementation preserves this distinction in control flow: `php/root-fatal` bypasses guest handlers and shutdown; `php/guest-fatal` follows the separately specified guest-fatal lifecycle. `run-main` copies the operational payload into `:php/fatal` without string conversion. I found no inspected consumer that unconditionally performs string operations on that field.

Consumers must discriminate the compiler refusal before treating a fatal payload as text:

```clojure
(let [fatal (:php/fatal result)]
  (cond
    (and (map? fatal)
         (= :yang.compile/rejected (:yang.compile/outcome fatal)))
    (handle-compiler-refusal fatal)

    (string? fatal)
    (handle-fatal-text fatal)

    :else
    (handle-other-result result)))
```

The discriminator is **`:yang.compile/outcome` inside `:php/fatal`**, with value `:yang.compile/rejected`; retain the entire answer, including reason, diagnostics and correlation. Do not stringify it, invent a sibling field, or manufacture a guest exception.

Two qualifications matter:

- This guard interprets an established runtime-result channel. It does not authenticate arbitrary guest maps or authorize condition-shape routing.
- Text does **not** universally mean guest-fatal: the existing operational budget fatal also carries text. Furthermore, `run-main` does not preserve the internal `:php/operational` marker in its final result.

The dispatch’s §8.1 reference is the host-boundary **List 1 category restriction**, not a terminal-payload schema. This change introduces no category there.

**The rest of r18 is architecturally landable within its declared scope.** The correlated request → `serve` → publication → site/linker admission → caller-task invocation path is consistent with the design. The three expression fixtures plus exact admitted-entry reuse fixture assert the landed class-fatal channels. Preserve the disclosure that this collision matrix covers the **JVM semantic service path**, not every VM, and that the composition is synchronous. This is not certification of asynchronous service behavior or every eval declaration case.

I accept **188/5066/0 GREEN as the seat-reported gate**. The checked-in r18 findings still describe the earlier timeout; the landing record should distinguish that earlier run from the subsequent green gate.

**Q2 — The source supports frontier-r1’s fixture diagnosis. Authorize the fixture repair and runtime seeding; no carrier change is warranted for this unit.**

The decisive sequence is:

1. The PHP caller initializes `$replacement` at unit execution.
2. `compose-exception` completes that execution through `php/run-main` **before** executing the observation ([composition](/home/sto/ci/dw-frontier/test/yang/call/conformance.clj:337)).
3. Shutdown releases PHP globals ([release](/home/sto/ci/dw-frontier/src/cljc/yang/php/antlr/prelude.cljc:4550)).
4. The observation reads `replacement_payload`, then calls `probe`; `finally` executes **`throw $replacement`**, not a rethrow of the incoming Python wrapper ([fixture](/home/sto/ci/dw-frontier/test/yang/call/exception_legs.clj:107)).

Without seeding, the observer reads a released global, and throwing its null value produces PHP’s “Can only throw objects” error. That explains the unequal payload without a provenance defect.

The existing [mint and reader](/home/sto/ci/dw-frontier/src/cljc/yang/php/antlr/prelude.cljc:4784) already store the complete incoming condition in the PHP wrapper cell’s private provenance and recover it through `cell/dsg__provenance`. The public entry forwards that retained condition when the same wrapper escapes. The spec’s description of this leg as an incoming-wrapper rethrow is therefore incorrect.

Authorize the staged order:

`seed_replacement()` → capture `replacement_payload()` → execute `probe()` → compare the escaping payload with that captured object.

This preserves **R5**: no shape-based authentication, forged provenance, or private mint access is added. It preserves **R2 rule 3**: foreign reception and complete-condition retention remain unchanged. Under §8.10.8, the PHP-created replacement supersedes the pending foreign exception and escapes as a fresh thrown condition.

**Acceptance remains unchanged:** `[:python :php :finally-replacement]` must pass on register, semantic and stack, with the existing identity, exactly-once transfer and unreachable-after assertions intact; focused regressions and previously green PHP rows must remain green. The frontier findings contain no completed backend verdict, so this authorizes the fix but does not declare acceptance achieved.

These rulings settle both architectural questions for this unit. Proceed on this authority without reopening the diagnosis dispute; any subsequent execution failure must be reported with its concrete evidence.

Read-only source review; no files changed or new tests run.