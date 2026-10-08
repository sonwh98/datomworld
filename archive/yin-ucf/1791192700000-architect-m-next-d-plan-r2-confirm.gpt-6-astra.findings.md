Completed-GMT: 2026-10-05 09:28:12 GMT
Completed-Local: 2026-10-05 16:28:12 +07

**CONFIRMED WITH RESIDUALS (1–4 below).** R2 adequately incorporates most findings. Four concrete corrections remain; they do not require another redesign.

| Original finding | Confirmation against r2 |
|---|---|
| 1. Complete gate coverage | **Confirmed:** §1.2 and D4–D6 specify child stamping, separate observation paths, and refusal of late applies. |
| 2. Held immediates | **Confirmed with residual 1:** §1.4 correctly retains observations through acknowledgment, but cursor creation has no pre-observation position. |
| 3. Portable replay identity/application | **Confirmed with residual 1:** §1.4 defines task paths, source matching, alias ordering, and four states. Cursor-source construction remains incomplete. |
| 4. Correlation versus dedup | **Confirmed:** §1.3 correctly separates request correlation from authority operation-ID dedup and distinguishes projected outcomes. |
| 5. Remote protocol paths | **Confirmed:** §1.5 and D2/D3 provide renewal carriage and authenticated ledger reconstruction. |
| 6. End-run and release behavior | **Confirmed with residual 2:** §1.6 preserves quarantine but overstates the effect of ordinary release. |
| 7. Version-aware validation | **Confirmed with residual 3:** §1.7 separates v0 from v1 inspection. Decoder selection must now be made concrete. |
| 8. Deterministic lift | **Confirmed:** §1.8 and D8/D9 separate preparation from pure encoding, preserve aliases and wait order, and permit envelope-shaped payloads. |
| 9. Abort safety | **Confirmed:** §1.9 requires proof of non-admission and current tenure for holder abort. |
| 10. Durable progress/recovery | **Confirmed:** §1.10 and D14 establish write-ahead intents, retained checkpoint references, uncertain-write suspension, and no execution from historical grants. |
| 11. Scope, exclusivity and slicing | **Confirmed:** §1.11, the serialized slice order, D15/D16, and §3 distinguish memory protocol tests, file-backed acceptance, and E’s evidence. |
| 12. REPL scheduling | **Confirmed with residual 4:** §1.12 fixes hydration starvation, but callers need shutdown/cadence integration. |

**Residual 1 — define cursor-creation sources without observing live state.**

Section 1.4 gives `:cursor` the same `:yin.k/position p` source component as `:next` and `:poll`. But cursor creation has no existing cursor position: [engine/handle-cursor](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:507) obtains it by minting the oldest cursor. Replaying must not mint a live cursor to discover the source it should match.

Replace that source rule with:

> For `:next` and `:poll`, the source contains the existing portable position. For `:cursor`, the source contains the requested cursor origin, such as `:dao.stream/oldest`, rather than the resulting position. The resulting portable position belongs only to the recorded observation. Replay constructs the cursor from that observation without live minting.

This blocks the current D12 cursor-replay implementation contract until corrected.

**Residual 2 — qualify “release cannot make it regrantable.”**

Section 1.6 says release cannot “make it regrantable.” That is correct for a quarantined occurrence, but false for an ordinary failed run: release without accepted completion returns the checkpoint through reclaim so it can be granted again. Input conflict and replay divergence explicitly do not quarantine.

Use:

> Release never clears quarantine or completes an occurrence without accepted completion evidence. A quarantined occurrence remains ungrantable. For other failed runs, release follows the ordinary reclaim/regrant policy; the ending driver does not itself authorize recovery.

Otherwise an engineer could incorrectly suppress legitimate regrant after post-grant failure or divergence.

**Residual 3 — resolve the decoder unknown: one existing decoder does not read both body formats.**

The profiles are deliberately different:

- [dao.stream.cbor](/Users/sto/workspace/datomworld/src/cljc/dao/stream/cbor.cljc:1) uses tag 39 identifiers.
- [Jing’s decoder](/Users/sto/workspace/datomworld/src/cljc/dao/jing/cbor.cljc:1526) explicitly refuses tag 39.
- The current version-0 lower uses the stream codec; it must retain that path.

Thus D7 needs two decoding paths. Preserve the decoder/profile identity alongside the decoded value, then enforce the allowed body version for that profile. A successful v0 decode must not silently become v1 through re-encoding, and failure in v1 structural validation must not trigger reinterpretation as v0.

Replace the ambiguous “Neither decoding is `:yin.k/undecodable`” sentence with:

> If neither supported codec decodes the bytes, return `:yin.k/undecodable`. Retain which codec accepted the bytes and enforce its body-version contract before address and restoration checks.

Scalar overlap between the codecs does not establish body compatibility: handoff bodies contain qualified identifier keys. This is a small D7 correction, not a codec-unification project.

**Residual 4 — the REPL callers cannot remain wholly unchanged.**

The existing `[state server lines]` return shape can stay. Normal callers already thread the returned state correctly. However:

- JVM exits the normal loop when `:running?` becomes false, then drains the endpoint and closes resources ([main.cljc:981](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:981)).
- Node and Dart switch into `stop-tick`, which no longer calls `step-all` ([main.cljc:1124](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:1124)).
- `moved?` currently tracks existing shell/server progress, not custody progress ([main.cljc:849](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:849)).

One last custody step cannot guarantee delivery of a release that encounters backpressure. D15 must either continue control-plane stepping during bounded shutdown draining or durably retain outstanding intents for restart before exit. Program execution remains stopped. Integrate custody progress and required service cadence with the existing tick owner; no second timer is needed.

With these four corrections, **r2 is sufficient to implement**. The other details are implementation and acceptance work, not further architectural blockers. No files changed or suites run.