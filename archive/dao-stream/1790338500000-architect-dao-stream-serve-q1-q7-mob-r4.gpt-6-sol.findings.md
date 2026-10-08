Completed-GMT: 2026-09-25 12:25:09 GMT
Completed-Local: 2026-09-25 19:25:09 Asia/Ho_Chi_Minh

**Q5(i) — DIFFERS.** GLM’s §4 declaration plus one sentence near OD-3(2) is insufficient. It would leave the contract saying `blocked` means nothing exists at the position and `:newest` follows the newest appended value, while permitting answers that mean something else. This is a **serve design conformance gate**, independent of accepting OD-1/2/3. [dao.stream.md:477](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:477), [dao.stream.md:542](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:542), [Fable design:125](/Users/sto/workspace/datomworld/collab/1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md:125), [Fable design:137](/Users/sto/workspace/datomworld/collab/1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md:137)

I would accept **explicit contract amendments to the two affected definitions**, together with the §4 declaration:

> **Reading:** “`blocked` means no observation is available *through this handle* at this cursor now; the cursor does not advance. A handle declaring deferred remote reads may return `blocked` while fetching even if the source already holds a value or has evicted that position. A later successful source observation supplies the source’s outcome.”

> **Cursors:** “A handle declaring deferred remote anchors may return a source-minted anchor from its last completed source observation. Its `:newest` may precede the source’s current tail but must never follow it; its `:oldest` may precede the currently retained head, in which case a subsequent read reports the source’s `gap`.”

These changes use existing outcomes and return immediately, so they respect no waiting and require no unaccepted OD. GLM’s arguments establish that a fresh remote answer cannot be demanded synchronously and that current VM consumers throw on a new outcome. They do **not** establish that the present meanings can be changed by declaration alone. Its gap and no-skip tests are necessary, but do not test what `blocked` asserts or whether an anchor represents current state. Also, `end` cannot be “only ever relayed”: the contract permits local `end` after an attachment is exhausted. [dao.stream.md:153](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:153), [engine.cljc:245](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:245), [Fable design:288](/Users/sto/workspace/datomworld/collab/1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md:288), [dao.stream.md:603](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:603)

**The required proxy meaning:** `blocked` must assert only that this handle has no result available now, while returned anchors must be expressly allowed as earlier source-minted observations with a no-skip guarantee.

CONSENSUS: Q1, Q2, Q3, Q4, Q6, Q7; OPEN: Q5
