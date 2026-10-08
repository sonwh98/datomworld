Coding-Agent: glm
Model: glm-5.3

The review is complete. Since the task's deliverable is a findings report (read-only — "Do not edit any file"), here it is in full; the same report is saved to the plan file.

---

# Independent adversarial review — `docs/design/dao.jing.cbor.md`

## Verdict

**Ready for owner sign-off, conditional on two text-only corrections (F1, F2).** Both sit inside the sign-off-relevant text and misstate what the `dao.space` owner would be approving. Nothing architectural must change. F3–F6 are recommended one-liners; F7–F8 informational.

## Findings (most severe first)

**F1 (Medium) — "uniform with today's JVM behavior" is wrong for the `=` builtin; the plan changes JVM query semantics, and the sign-off text hides that.**
§Numeric identity: "One intended consequence, uniform with today's JVM behavior: covered-index membership **and matching** are by numeric value." Half true. Covered-index membership is comparator-based (`dao.space.index/compare-vals` → host `compare`; JVM `(compare 1 1.0)` = 0) — that half is uniform today. But `dao.space.query`'s builtins bind host Clojure vars (`src/cljc/dao/space/query.cljc:750–768` — `'= =, 'min min`, …), and JVM `(= 1 1.0)` is **false** (Clojure `=` is kind-strict). Routing `=` through portable numeric-value equality flips that to true on the JVM — the plan standardizes on today's *JavaScript* behavior and *changes* today's JVM behavior. As the behavioral summary the sign-off owner reads, it understates the blast radius. Also unspecified: which operand `min`/`max` return when mixed kinds compare zero (`(min 1 1.0)` is host-arbitrary today). Fix: a sentence or two stating the JVM flip explicitly and pinning min/max tie behavior.

**F2 (Medium-low) — "the plan's one change outside `dao.jing*`" is contradicted by the plan's own step 5.**
The finding-4 addition and step 3 both claim the `dao.space.index`/`query` routing is the only change outside `dao.jing*`. Step 5 then requires the `dao.data.btree.storage` adapter's "verification must become a byte-hash check" and supersedes `dao.data.btree.md` §5.2's default-off rationale (§5.2 pre-authorizes flipping the default when canonical encoding lands — verified, `docs/design/dao.data.btree.md:683–707`). Whether that's a code change in `dao.data.btree.storage`, a default flip, or doc-only is left ambiguous — but as written the plan names a second cross-namespace change while claiming there is only one. Scope the claim (e.g., "the only change to comparison semantics; the btree verification flip is §5.2's pre-authorized one") or flag btree's owner too.

**F3 (Low) — Finding-1 correction: resolves the contradiction; residual looseness in the Objective's framing.**
The correction works in substance: frame ownership is explicit, replay ingress validation is sanctioned by the Layering section's ingress rule, and "never a divergent per-backend codec" stops the carve-out from becoming a loophole. No other section contradicts it. Two residuals: (a) the Objective's parenthetical classes "the memory/remote/DHT byte-store implementations" as "pluggable, third-party-implementable backends" — those shipped implementations are exactly as first-party as `dao.jing.file`; what is third-party-implementable is the *byte-store contract*, and the real separator is that file is the only backend that re-ingests its own output across process death (hence framing + replay acceptance, hence the codec dependency). The crisp statement already exists in *Memory and files*; the Objective's first/third-party version is the weaker, slightly wrong one. (b) The doc still lists file as an unmarked backend everywhere else (Objective line 16 and the `backend: memory | file | ...` diagram), so the Objective paragraph is the only place drawing the exemption. One-sentence tightening; not a contradiction.

**F4 (Low) — silent supported-domain narrowing vs today's transitional encoder.** Today's `canonical-print` sends scalars through `pr-str`, so characters, `#inst`, `#uuid`, and tagged literals are addressable today. The plan's supported list omits them, "Jing's four names" leaves no slot, and "existing stores … must be rebuilt together" presumes re-encodability. No current producer emits these (same latency as the byte-array item), but the delta should be named — one sentence.

**F5 (Low) — rebuild/rollback precondition unstated.** "No legacy reader" + reject-old-stores means the only reconstruction path is replaying original values from intake streams — and streams can evict (`:dao.stream/gap`), so an evicted stream plus a rejected old store is unrecoverable content. State the rebuild source and precondition — one sentence.

**F6 (Nit) — "the shape `dao.jing.remote` already answers" overstates.** Today's envelope is exactly `{:found? boolean, :value v}` (`src/cljc/dao/jing/remote.cljc:55–60, 84–85`), not `{:found? :bytes}`. The found?/explicit-absence shape is what exists; `:bytes` is the plan's future key.

**F7 (Informational) — sequence window.** Between step 3 (memory/file on CBOR addresses) and step 4 (remote/DHT migrated), the remote wire still speaks the old value protocol, so steps 3–4 can't each be independently green with remote tests live. Note they land together.

**F8 (Design note) — the file frame's CBOR array is the sole reason the Objective carve-out exists.** The outer 4-byte length prefix already frames the record and the digest is fixed 32 bytes; a raw `digest ‖ payload` frame needs no TLV parse, eliminates the only CBOR-knowledge exception in the backend layer, and shrinks the replay-path parser. The chosen CBOR frame is disciplined and workable — recording the simpler alternative for the implementer, not as a defect.

## What was checked and held

- **Code claims** all verified: `max-datagram` 1200; no WebSocket byte cap; `compare-vals` string fallback + all four comparators; host-native query builtins; btree comparator caller-supplied (no change needed); §5.2 default-off print-hash rationale; string-based `sha256`, print-based `content-hash`, EDN framing with 4-byte BE signed prefix and torn-tail recovery; address shape; all five public ops; all four `dao.jing.md` open items real; effect-stream deferral matches; dependents (manifests, btree.storage, `yin.vm` AST/continuation addressing, `psset_fixtures`' 860 hardcoded addresses + dev generator) all covered by "rebuilt together" + step 5.
- **External claims spot-checked**: IANA tag 27 = "Serialised language-independent object with type name and constructor arguments" — the `[name payload]` use matches its registered meaning; tags 2/3/4/30 as claimed; Boring really writes idents as tag-39 slash-joined text, so the rejection rationale is sound; Boring's `with-meta` is tag-27 `[name [meta value]]`.
- **Six invariants**: no violations. The one cross-boundary widening is the already-flagged `dao.space` → `dao.jing.cbor` edge — an existing dependency (`segment-key`) gaining operations, direction-checked, no cycle, no layer collapse. No hidden globals, no callbacks, immutable snapshots, closed dispatch, no assumed graphs.
- **Numeric identity / encoding contract / clean break** re-derived: Base64 budget math checks; old-format files cannot parse as new frames (leading `[` is CBOR major-4/len-27, never a valid two-element byte-string array); interrupted-first-write refusal is conservative and justified; duplicate-key rules (cross-kind and same-kind-different-scale) are consistent with the injectivity acceptance list; NaN/±0/decimal-scale/float32-widening rules agree across contract, section, and test list.

**Bottom line:** a clean independent pass — the two prior corrections hold (with F3's wording caveat), no blocking defects, no invariant violations; fix F1 and F2 before the `dao.space` owner reads it. Per the task, no files were edited.
