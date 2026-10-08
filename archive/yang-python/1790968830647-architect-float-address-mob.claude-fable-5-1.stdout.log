Completed-GMT: 2026-10-02 19:28:29 GMT
Completed-Local: 2026-10-03 02:28:29 +07

# Float-address ruling (fable half, cross-ruling astra's final text)

**Ruling:** the codec conforms and stays untouched. I mandate a third path: a Python float's payload is Jing float64 content (`cbor/float64`) on every host, for literals, prelude constants and runtime values alike. I concur with astra on every point, with three corrections below.

All evidence is static reading; nothing was run on any host, and no files were edited.

## 1. Root cause

The defect is in the producers, not in `dao.jing.cbor`.

- **Codec conforms.** On JS, `host-integer?` classifies any integral, non-negative-zero Number as an integer (`src/cljc/dao/jing/cbor.cljc:456-457`), and `number-wire` sends it to `int-wire` (`cbor.cljc:1006-1011`). The contract requires exactly this (`docs/design/dao.jing.cbor.md:387-391`).
- **Producer 1, source literals.** `parse-number` emits `{:py/float <bare host number>}` (`src/cljc/yang/python/antlr/lower.cljc:279-281`). The map tag never reaches the numeric bytes, so `2.0` is integer 2 on Node and float64 on JVM/Dart.
- **Producer 2, quoted prelude.** Integral float literals sit at `src/cljc/yang/python/antlr/prelude.cljc:89, 101, 865, 887, 891, 930, 936, 949, 967, 968, 1021, 1029, 1176`. The ClojureScript reader has already collapsed `1.0` to `1` before any code runs.
- **Where both become addresses.** They land in row bodies hashed at `src/cljc/yin/vm.cljc:1192-1193` (`jing/segment-key body`).
- **Producer 3, runtime values.** `py/float` builds `{:py/float (* 1.0 x)}` (`prelude.cljc:89`), so `4/2` is `{:py/float 2}` on Node. Any image identity holding it diverges (`src/cljc/yin/vm/semantic.cljc:996`).
- **Same class, loud variant.** A float at or above 2^53 is integral, so on Node it hits the unsafe-integer refusal (`cbor.cljc:1007-1010`).

## 2. Mechanism

**Reject (a).** The kind is gone before the codec sees the number, so no classification rule can recover it. Normalizing JVM/Dart to integers instead would violate "`1` and `1.0` have different addresses" (`dao.jing.cbor.md:281-284`).

**Reject (b) as posed.** Tagging row inputs alone leaves runtime values diverging. It also leaves Node with two representations, because a JVM-minted float row already decodes to the carrier on Node (`cbor.cljc:1436-1439`, `:529`).

**Mandate: one representation, the one the wire already delivers.**

1. **Lowering.** `lower.cljc:279-281` emits `{:py/float (cbor/float64 v)}`: the identity on JVM/Dart, the carrier on JS (`cbor.cljc:494-501`).
2. **Uniform on JS.** Every `:py/float` payload is a carrier, including non-integral values. The bytes would match either way, but the carrier only equals another carrier (`cbor.cljc:351-355`).
3. **Prelude seam.** `py/float` (`prelude.cljc:89`) wraps and `py/num` (`:100`) unwraps, through two new pure functions in the data module table (`src/cljc/yin/vm/data.cljc:455`). Arithmetic in between stays host-native on bare numbers.
4. **Prelude constants.** The quoted prelude carries no integral float literal. `(* 1.0 x)` coercions and constants like `1.0` and `0.0` go through the data functions; `0.5` (`:892`) is fine. Guard with a JVM test that fails on any integral-valued Double in a prelude literal row.
5. **No capture inside the seam.** No yield or safepoint may sit between an unwrap and its rewrap. I did not verify whether the safepoint stage marks sites inside prelude bodies, so this must be pinned by a test, not assumed.
6. **Execution bridge.** Admit the carrier at all three scalar gates: `plain-data?` and `machine-data?` (`vm.cljc:962, 981`) and `scalar?` (`src/cljc/yin/vm/engine.cljc:638`). Audit the kind classifier at `src/cljc/yin/vm/values.cljc:195`. The precedent is the exact-integer carrier (`engine.cljc:634-638`); `yin.vm.debruijn` already sees through the float carrier (`src/cljc/yin/vm/debruijn.cljc:227, 299`).

**Contract amendments.**

- `dao.jing.cbor.md`: no byte, fixture or code change. Add one sentence under *Numeric identity*: carrying float kind on JS is a producer obligation; the codec never infers it.
- `yang.antlr.md` (`:1243-1247`, `:1339-1340`): adopt astra's normative paragraph, with correction 1 below.
- The yin.vm data-domain doc: the float64 carrier is an admitted scalar.

**Migration cost.** One preparatory slice, landed before any float-bearing pin.

- **Addresses.** JVM/Dart Python-literal rows keep their bytes; Node's move to match. Prelude rows change on every host, which changes every bundled unit's address (`yang.antlr.md:1828-1831`). Rebuild from source, no aliasing.
- **Code.** One lowering line, about 13 prelude sites, two data functions, three predicate sites.
- **Audit.** `float-repr` (`src/cljc/yang/python/antlr/render.cljc:28-40, 100`) must unwrap on JS. Dict-key normalization at `prelude.cljc:1176` puts a float in a map key and needs a decision on its kind.
- **Tests.** Node expectations comparing bare numbers move to constructors or `content=`.

## 3. What may proceed meanwhile

- **Slice 1's golden test** stays valid: its tree is hand-built with no numeric literals (`test/yang/safepoint_test.cljc:35-40`).
- **C3-S2** (`yang.antlr.md:2113-2114`): integer-literal rows, bytes, hashes and malformed-encoding refusals proceed, with cross-host pins at the row or subtree level.
- **C2-S5** (`yang.antlr.md:1858-1860`): heap collection, lift refusal, iterator classes and snapshot rendering proceed with behavioural parity and same-host determinism.
- **Not allowed until the preparatory slice lands:**
  - any cross-host pin of a bundled-unit root (A or A′), even for a float-free user program, because the prelude itself carries the divergent literals;
  - any cross-host pin of a snapshot holding a runtime float;
  - any Node-specific golden for such trees;
  - any edit to `cbor.cljc` or its fixtures.
- **Sequencing.** The float slice touches `vm.cljc:962, 981` and `engine.cljc:638`, the scalar gates C3's integer carrier also goes through. If C3-S2 edits them, land the two serially, not concurrently.

## Cross-ruling of astra, per point

| Astra point | Verdict |
|---|---|
| 1. Codec conforms; defect is upstream in producers | Concur. I add runtime values (producer 3) and the 2^53 refusal. |
| 2. Use the existing carrier, no new tag or row shape; codec and fixtures unchanged | Concur. |
| 2. Normative text | Concur with correction 1. |
| 2. Execution bridge | Concur with correction 2. |
| 2. Migration (Node re-minted, no aliasing, prelude change moves bundled addresses) | Concur. |
| 3. Gates; "float-free" covers the bundled prelude | Concur. See correction 3. |

**Correction 1.** Astra's text binds "every floating-point literal entering canonical rows". It must also bind runtime float values in any addressed image (snapshots, continuations). That is the case C2-S5 actually exercises.

**Correction 2.** Astra names only `engine.cljc:632`. The row and machine-payload gates at `vm.cljc:962` and `:981` reject the JS carrier too, and they are what stop a carrier-bearing row from being built at all.

**Correction 3.** The earlier fable text (mis-prefixed log `collab/1790868830647-…stdout.log`, summarized at `docs/orchestrator-log.md:9847-9849`) let C3-S2 proceed "in full" and C2-S5 pin cross-host goldens on float-free programs. That was too loose: astra's stricter reading is right, and section 3 above replaces it.

Status: COMPLETE
