# Verification: final ABI decomposition (deepseek-v4-pro, independent)

Scope: read `datom.world.abi.md`, `dao.stream.wasm.md`, and `yin.vm.rust-engine.md`
in full; diffed `datom.world.md` and `dao.stream.md`; read the retained `yin.vm.engine.md`
§E (E.3 pointer + E.4/E.5) and the updated `register.md`/`yang.wasm.md` citations;
ran a full link-resolution and retired/renamed-file scan across `docs/design`.

## VERDICT: PASS

Doctrine is consolidated faithfully and verbatim; the new `dao.stream.wasm.md` is
correctly framed as an implementation (not an ABI); the moves dropped nothing; the
rename is clean with section-number repointing that checks out; and mechanically the
only broken links are the pre-existing, unrelated legacy ones. Ready to commit.

---

## A. DOCTRINE FIDELITY — PASS

`datom.world.abi.md` contains, verbatim, everything the task requires:
- **one-ABI** (`:27-33`) and **boundary-drawn-not-found** (`:56-64`);
- **taxonomy quote, verbatim** (`:37-39`): "dao.stream is an abstraction boundary. It
  is not a transport implementation. It can be implemented using a ring-buffer, a
  websocket or UDP";
- **custody-subset consequence** (`:46-52`);
- **floor argument** (`:83-87`: seam immovable / frontier movable);
- **naming doctrine** (`:77-81`: "the system ABI names only dao.stream"; "Internal
  compiler linkage does not create an ecosystem boundary");
- **split rule vs dao.stream.md** (`:5-8`: contract-what vs boundary-why);
- **effects/events** (`:94-107`) and **adapter discipline** (`:109-148`).

`datom.world.md`'s Host Boundaries remnant is two lines plus a link
(`datom.world.md:70-72`), faithful ("A host boundary is the dao.stream abstraction
boundary; implementations do not add contracts") and points correctly at
`datom.world.abi.md`.

## B. NO CATEGORY ERRORS — PASS

`dao.stream.wasm.md` is framed throughout as an **implementation** of the abstraction
(`:1-7` "Subordinate to dao.stream.md … that document is the contract; this one
specifies the wasm import-surface implementation"; `:9-13` provenance), never as "an
ABI". It is **language-neutral** — `:17-20` explicitly sends the Rust host side to
`yin.vm.rust-engine.md` §6.4 and states "the guest engine is not implemented by those
host adapters". Outcome maps match `dao.stream.md` (append ok/full/invalid-value/
closed/refused/transport-error, next ok/blocked/end/gap/cursor-mismatch/invalid-cursor/
refused/transport-error, close `{ok}` — verified against `dao.stream.md:577-603,629-631`).
Jing CBOR only (`:96-105`); the "no TLV or alternate framing" line (`:100`) is a
prohibition, not a revival. No NEED_SPACE/comparator resurrection.

## C. COMPLETENESS OF MOVES — PASS

- **Host Boundaries → abi.md**: the full doctrine (system ABI, taxonomy, custody,
  rulings, naming/floor, effects/events, adapter discipline) is present in abi.md with
  no content dropped; `datom.world.md` retains only the 2-line remnant + link.
- **engine §E.3 → dao.stream.wasm.md**: the adapter table, outcome/buffer discipline,
  and canonical-bytes/authority sections moved verbatim into `dao.stream.wasm.md` §§1-3
  (identical text to the pre-move `engine.md` §E.3 I verified last round). `engine.md`
  §E.3 is now a clean pointer (`:545-551`) that preserves the §E.4/§E.5 numbering.
- **Retained §E content intact**: E.1 state discipline, E.2 arena/GC/two-codec,
  E.4 embedder seam + single-port + traversal + stream/park/custody/FFI rules, E.5
  conformance method — all present and now citing `dao.stream.wasm.md` (`:557`) and
  `yin.vm.rust-engine.md` by section.

## D. RENAME INTEGRITY — PASS

`yin.vm.rust-engine.md` keeps the surviving core: crates (§9, `:1131`), Value ownership
(§4.1 + §11 K-D2), atomicity (§5.3), fault containment (§5.5), unsafe scoping (§9
"forbid unsafe" except three crates), LLVM path (§7), native node (§8), K-phases (§10).
Superseded framing is trimmed with dated notes, not silently (`:705` "the former Level-1
architecture is superseded by the 2026-10-19 ruling"; `:720`; `:1168` "supersedes T5
offload framing"; footer `:1318`).

Citations now use **section numbers** (not the old fixed line numbers). Spot-checked
>10 against the actual headings/content:
- §9 = Crate layout (`:1131`) ✓ — cited by `engine.md:507,577`
- §6.4 = Rust host wasm stream surface (`:765-780`, the new subsection) ✓ — cited by `dao.stream.wasm.md:19`
- §10 = Phases, K0/K1–K5 (`:1159-1210`) ✓ — cited by `engine.md:722,862`, `register.md:1423,1676`
- §11 = Decisions incl. K-D2/K-D2b/K-D7 + Deferred (`:1214-1285`) ✓ — cited by `engine.md:433,700`, `yang.wasm.md:131,479`
- §7.2 equivalence law (`:802`), §7.3 call shape (`:830`), §7.5 artifact/cache (`:994`) ✓
- §7.4 + §12 risk 2 = "two dispatchers" (`:917`, `:1289`) ✓ — matches `engine.md:584`
- §§5.3–5.5 = atomicity/diagnostics/fault-containment (`:622,663,671`) ✓ — matches `engine.md:589`
- §§4.2/4.6 = plain-data shared Images (`:36` self-cites) ✓ — matches `engine.md:439`

**No reference to "rust-kernel" remains anywhere in `docs/design`** (grep returns zero);
the old file is gone from disk.

## E. MECHANICAL — PASS

- **dao.stream.md edit is the pointer only**: `git diff` shows exactly `+1` line
  (`:463` the `dao.stream.wasm.md` pointer), nothing else moved in that landed spec.
- **No src/ or test/ changes**: `git status --short -- src test` is empty.
- **All relative links resolve** across the eight changed/new files (17 unique targets,
  all present; no dead link to a retired/renamed file).
- **Pre-existing broken links**: found **three** markdown-link breaks, all in the
  legacy-UDP/telemetry docs and all unrelated to this step (none reference
  `rust-kernel.md`, the retired wasm docs, or anything this decomposition touched):
  - `daostream-udp-design.md:478` → `./daostream-design.md` (dead; file is `dao.stream.md`)
  - `daostream-udp-design.md:479` → `./daocall-design.md` (dead; file is `dao.stream.apply.md`)
  - `yin.vm.telemetry.implementation-plan.md:10` → `./yin.vm.v1-retirement.implementation-plan.md` (dead)
  These files are not modified by this step (absent from `git status`), confirming
  pre-existing. Note: the author reported "four"; I count three `](...)`-syntax breaks
  plus two additional bare-text references to retired v1-model plan files
  (`yin.vm.v2-consumers.implementation-plan.md`, `dao.stream.v1-retirement.implementation-plan.md`)
  that are not markdown links — the discrepancy is cosmetic and does not affect the verdict.

---

### Non-blocking observations

1. `datom.world.abi.md:27` opens with a stray leading space (" One abstraction
   boundary…"). Cosmetic only.
2. `yin.vm.rust-engine.md` §12 risk 3 uses "ABI" in the internal-layout sense ("fixes
   `Value`'s tag position … as ABI"; "ABI version bump" in the artifact cache key). This
   is internal compiler/runtime layout versioning, explicitly carved out by
   `datom.world.abi.md:77-81` ("Internal compiler linkage does not create an ecosystem
   boundary"), so it is not a category error — worth an eyeball at review to keep the
   word "ABI" from drifting toward a second-interface reading.

Neither observation blocks; the tree is ready to commit.
