Completed-GMT: 2026-09-27 08:58:25 GMT
Completed-Local: 2026-09-27 15:58:25 Asia/Ho_Chi_Minh

Created [yin.repl.dao.space-index.md](/Users/sto/workspace/datomworld/docs/design/yin.repl.dao.space-index.md). It places an independent index observer on `program-out`, alongside the evaluator. It specifies the canonical AST rows, their datom projection, and session provenance as the transacted set; recommends exposing `q` through the user's own `require`; and limits startup cost to observer setup, with indexing work only when a program is forwarded.

Verified the file is ASCII and every line is at most 80 columns.

Status: COMPLETE