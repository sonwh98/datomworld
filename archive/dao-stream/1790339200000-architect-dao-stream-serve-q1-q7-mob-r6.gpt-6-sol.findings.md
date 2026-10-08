Completed-GMT: 2026-09-25 12:34:09 GMT
Completed-Local: 2026-09-25 19:34:09 Asia/Ho_Chi_Minh

**GLM’s three edits**

1. **ACCEPT — “completed source observation.”** It includes every completed source answer, including `blocked`, `gap`, and `end`; “successful” could imply `ok` alone. [Fable design:139](/Users/sto/workspace/datomworld/collab/1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md:139)
2. **ACCEPT — retain “retry later.”** That is the current `blocked` disposition and the IO model’s polling behavior. [dao.stream.md:545](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:545), [dao.stream.md:146](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:146)
3. **ACCEPT — one declared nature, “deferred remote observation.”** The same declaration should govern the proxy’s `next` and `cursor` behavior. [dao.stream.md:446](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:446), [Fable design:125](/Users/sto/workspace/datomworld/collab/1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md:125), [Fable design:137](/Users/sto/workspace/datomworld/collab/1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md:137)

**Closing positions:** I endorse GLM’s restatement of Q1 through Q7. For Q6, the composition-retirement v1 stopgap must remain an explicitly weaker lifetime policy; UCF migration acceptance still needs adequate served-entry lifetime. GLM’s correction on `end` is right: a source `gap` is relayed, while `end` may also arise locally when an attachment is exhausted. [dao.stream.md:603](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:603), [Fable design:149](/Users/sto/workspace/datomworld/collab/1790335900000-architect-dao-stream-serving-spec.claude-fable-5-1.findings.md:149)

**Final draft for the two contract definitions**

> **Reading — `:dao.stream/blocked`:** No observation is available through this handle at this cursor now; the cursor does not advance, and the reader may retry later. A handle declaring **deferred remote observation** may return `blocked` while fetching even if the source already holds a value or has evicted that position. If a source observation completes, its outcome is supplied.

> **Cursors — anchors:** A handle declaring **deferred remote observation** may return a source-minted anchor from its last completed source observation. Its `:newest` may precede the source’s current tail but must never follow it; its `:oldest` may precede the currently retained head, in which case a subsequent `next` reports the source’s `gap`.

CONSENSUS: Q1, Q2, Q3, Q4, Q5, Q6, Q7; OPEN: none
