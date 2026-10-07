Coding-Agent: codex
Session-ID: 01a0e7da-5e32-7723-a2bd-f9a6a42d4463
Model: gpt-6-sol

Completed-GMT: 2026-09-28 11:51:18 GMT
Completed-Local: 2026-09-28 18:51:18 Asia/Ho_Chi_Minh

**Verdict: REQUEST CHANGES**

- **P1 | [remote_serve.cljc:295](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi/remote_serve.cljc:295) |** `step` calls `remote/mirror-step`, which loops until the reader blocks or ends. Neither function enforces the design’s bounded drive pass; a continuously supplied channel can keep the drive in one call. **Concrete fix:** add a per-step request budget and retain the returned cursor after that limit.
- **P2 | [remote_serve.cljc:150](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi/remote_serve.cljc:150) |** `servable-surface` checks protocol support but never checks whether a reader’s cursors survive the channel codec. The remote design requires refusal before entering such a handle in the table. **Concrete fix:** validate cursor portability with the configured codec before publication, and test an unportable cursor.
- **P2 | [remote_serve.cljc:260](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi/remote_serve.cljc:260) |** `close!` closes the channel writer, while the remote design allows reflections and mirror traffic in both directions on one channel. Closing a shared writer would also terminate this peer’s outgoing requests. **Concrete fix:** make exclusive ownership of the channel end an explicit, validated binding requirement, or leave channel closure to its owner. This requires an owner decision about deployment wiring.

**Q1.** The separate read and `swap!` in `serve!` and `step` are sound only under the design’s stated single, serialized drive owner. They are not atomic for concurrent callers; document or enforce that ownership boundary.

**Q2.** A private atom inside each binding satisfies section 2’s composition-owned registry under that same single-owner rule. UCF’s callback cannot return threaded state, so a threaded-value API is not required.

**Q3.** Channel ownership is unresolved; the protocol permits bidirectional sharing. The binding must establish exclusive ownership before `close!` closes the writer.

**Q4.** `contains?` on `:yin.k/status` matches UCF’s refusal contract. The before/after identity cleanup preserves entries served before the lift and retires provisional entries from a refused frame.

**Q5.** The tests pin stable serving, table shape, retirement, ordinary refusal, and provisional cleanup. They do not pin a bounded step, unportable-cursor refusal, or safe closure of a shared channel. Acceptance criteria 1, 5, and the channel-loss portion of 4 therefore lack complete regression coverage.

Sign-off: WITHHELD.
