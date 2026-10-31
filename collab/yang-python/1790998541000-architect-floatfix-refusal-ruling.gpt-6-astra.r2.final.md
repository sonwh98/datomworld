Completed-GMT: 2026-10-03 05:46:34 GMT
Completed-Local: 2026-10-03 12:46:34 Asia/Ho_Chi_Minh

**Joint-position proposal: SHIP-WITH-CHANGES — remove the VM wrappers, make JavaScript Float64 reject numeric/default coercion while preserving explicit printing, and land that carrier hardening before or together with floatfix.**

**1. The proposal resolves my architectural objection.**

Yes. The refusal belongs to the host carrier’s conversion behavior, without changing the standard primitive registry or consulting a language profile. The carrier remains immutable data with unchanged canonical bytes; no provenance flag, new tag, or evaluator dispatch is needed.

I accept fable’s argument that carrier admission newly exposes silent concatenation. I therefore revise my release recommendation: **do not knowingly ship the newly reachable `"21"` outcome when a narrow carrier-level fix can prevent it.** This protects against accidental coercion; it does not establish portable generic arithmetic. JVM computation versus JavaScript refusal remains a disclosed limitation.

**2. Define precisely what refuses and what remains supported.**

Recommend a throwing `valueOf`, preserving the existing `toString` and printing protocols. Alternatively, `Symbol.toPrimitive` must permit the **string** hint and reject **number/default** hints; an unconditionally throwing implementation is unsuitable. JavaScript distinguishes these conversion paths. [ECMAScript conversion rules](https://tc39.es/ecma262/multipage/abstract-operations.html#sec-ordinarytoprimitive).

The acceptance contract is:

| Surface | Required behavior |
|---|---|
| Native arithmetic and relational comparison requiring numeric/default coercion | Refuse loudly, including `carrier + 1` and `carrier < 3`. |
| `.toString()`, `String(carrier)`, `pr-str`, ClojureScript `str` | Preserve existing rendering; verify actual compiled paths. |
| `"" + carrier` | Refuse: concatenation uses default coercion. Use explicit string conversion instead. |
| ClojureScript equality/hash protocols | Remain unchanged; their implementations access the payload directly. |
| Raw JS coercive equality | May now refuse when it requires conversion; strict identity remains unchanged. |
| Generic sorting | Numeric comparisons may refuse; default JS string sorting is not portable numeric ordering. |
| Jing explicit numeric comparison/content equality | Continue to operate through explicit payload access. |
| Canonical CBOR and executable-image encoding | Preserve bytes, hashes, NaN normalization, and signed zero. |
| JSON | Do not add implicit numeric serialization or `toJSON`; preserve existing declared adapters and refusals. |

The carrier’s existing equality, hash, and printing methods are separate implementations at [cbor.cljc:349](/Users/sto/workspace/datomworld-py-floatfix/src/cljc/dao/jing/cbor.cljc:349). Nevertheless, test them: coercion can occur in surrounding string-building or adapter code.

The proposal does **not** make “every operator touching a carrier” throw. Identity, truthiness, and operations that perform no conversion need not throw. Likewise, explicit text conversion followed by parsing remains possible; this is protection against accidental coercion, not a security boundary.

The [Jing documentation’s existing refusal sentence](/Users/sto/workspace/datomworld-py-floatfix/docs/design/dao.jing.cbor.md:353) specifically governs **query arithmetic**, including other numeric carriers. Keep those query guards. Add a separate Float64 coercion rule; do not imply this change hardens Decimal, Rational, or native BigInt.

All eight affected VM operators need integration coverage, including carrier operand positions and a later variadic operand. Test actual operations that require conversion rather than demanding refusal from identity arities that merely return their argument. Use a stable carrier-coercion error, **not** `:yin.k/non-portable`.

**3. Land it as a narrow Jing change coupled to floatfix acceptance.**

Prefer a separately reviewable Jing commit, immediately preceding floatfix. Combining them is acceptable if the changes remain clearly separated.

This explicitly amends the earlier prohibition on editing `cbor.cljc`: **its carrier behavior may change; its canonical encoding contract and frozen fixtures may not.**

Record:

- In `dao.jing.cbor.md`: numeric/default coercion refusal, preserved printing, unchanged bytes, and the remaining generic-arithmetic limitation.
- In §8.5.5: unchanged standard primitive bindings, carrier hardening, and Python’s explicit conversion seam.
- In UCF: scalar portability does not establish arithmetic portability; do not classify the conversion error as a lift refusal.

Require unchanged canonical fixtures, printing/equality/hash tests, raw-JavaScript coercion tests, and decoded-row execution across all four VMs. Python arithmetic and re-encoding must remain green on all three hosts.

**I no longer recommend releasing floatfix with wrapper removal alone while this fix remains pending.** The pieces can be developed independently, but the release gate should require both.

**4. Remaining disagreement**

None in principle **if fable accepts carrier-level hardening as the substitute for VM wrappers**. I do not require extending this slice to every numeric carrier. Broader carrier hardening can follow separately.

This is my proposed convergence, not a claim that fable has already accepted it. Read-only; no edits or tests executed.