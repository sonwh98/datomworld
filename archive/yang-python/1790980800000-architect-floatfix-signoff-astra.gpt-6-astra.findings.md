I’ll read the sign-off brief and implementation log, then inspect the numeric-key and carrier paths before ruling on scope and C3 sequencing.


Completed-GMT: 2026-10-02 22:28:02 GMT
Completed-Local: 2026-10-03 05:28:02 +0700

Read both requested documents and inspected the implementation worktree. No edits or tests run; lane results below remain the engineer’s reported evidence.

**1. Numeric keys: approve the bounded interim behavior, not its use as C3’s normalization foundation.**

For the current Python integer domain `[-2^53, 2^53]`, the scheme preserves finite numeric equality: integral values within the safe range become integers, signed zeros become integer zero, and the two endpoints use float64 content without losing their exact value. `1`, `1.0`, and `True` share one key; `−0.0`, `0.0`, `0`, and `False` share another. Changing the JVM’s internal normalized key from Double to integer is acceptable.

However, [data.cljc:457](/Users/sto/workspace/datomworld-py-floatfix/src/cljc/yin/vm/data.cljc:457) converts non-`number?` carriers through `float-value`, and the fallback converts other large integers too. Thus exact `2^53+1` can collapse onto `2^53`. **This helper must not acquire an arbitrary-integer support claim.** Restrict its admitted integer domain explicitly, and reject out-of-domain exact integer inputs before conversion if exposed through the module boundary. Preserve float/integer kind long enough to distinguish that refusal from an admissible large float.

Keep the interim normalization in the floatfix; do not restore the old double-key path. **C3-S2 must replace it from the original exact integer or float64 value with reduced-rational decimal-string keys—not stringify its result.** Stringification cannot recover discarded bits. Enable large Python integer keys only with that replacement. Test adjacent large integers, equal int/float pairs, tuple keys, and signed zero. Existing NaN-key divergence remains an explicitly deferred limitation, not a cross-host dictionary-parity claim.

The replacement changes the runtime profile/prelude and affected addresses again. Rebuild affected artifacts; do not reinterpret an existing live dictionary’s index under the new normalization.

**2. Execution bridge: accept the explicit Python seam; universal unwrapping is neither required nor desirable.**

My ruling requires carrier-aware admission, faithful representation through canonical transformations, and working execution for the claimed Python profile. It does **not** require every evaluator to erase float kind whenever it evaluates a literal. A generic Yin literal may remain a carrier; arithmetic consumers must explicitly support carriers or use a declared conversion seam. This implementation does not establish generic Yin float-arithmetic compatibility, and documentation must say so.

The additional de Bruijn classification and bit-encoding changes are necessary scope: they preserve an admitted scalar through executable-image construction. Unwrapping specifically to extract IEEE bits—including signed zero—is appropriate ([debruijn_code.cljc:233](/Users/sto/workspace/datomworld-py-floatfix/src/cljc/yin/vm/debruijn_code.cljc:233)). Global unwrapping would instead make an integral float indistinguishable from an integer on JavaScript and jeopardize later addressing.

Require the seam contract to remain explicit: raw arithmetic temporaries must be rewrapped before reaching guest-visible Python float values or addressed state. “No capture” alone does not prove this; retain a regression covering arithmetic-result storage and re-encoding, including integral results and `−0.0`. The narrower link-request-ID predicate need not admit float carriers merely because runtime values do.

I have not read a completed fable sign-off, so agreement with that response is not yet established.

**Converged ruling:**

1. Approve bounded interim numeric-key normalization with an explicit integer-domain guard; C3 replaces it directly from exact typed inputs before enabling bignum keys—never by stringifying rounded intermediate keys. **Potential difference with fable: reject retaining it as C3’s semantic foundation.**
2. Accept the Python-specific conversion seam plus carrier-aware VM and executable-image gates; do not mandate universal unwrapping, and do not claim generic Yin arithmetic compatibility. **Potential difference with fable: universal bridging is not required.**