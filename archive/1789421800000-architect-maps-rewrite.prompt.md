Created-GMT: 2026-09-14 21:41:00 GMT
Created-Local: 2026-09-15 04:41:00 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: ba464ac6-c781-4fae-853e-9304435530b1
# Task: Rewrite Architecture to "Code as Maps"
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-15 04:41:00 | Status: active | Rationale: Fable has the best architectural context on this subsystem.

The owner has issued a definitive architectural ruling: "i want to build a LISP using maps not a LISP using vectors instead of lists".

This means the entire premise of projecting the semantic Map AST into flat `[id tag & slots]` vector rows (tuples) for storage, hashing, and streaming is DEAD. 
The Map AST *is* the code. It is the canonical representation, the stored representation in `dao.jing`, and the streamed payload in `dao.stream`.

Your task:
1. Rename `docs/design/yin.vm.code-as-tuples.md` to `docs/design/yin.vm.code-as-maps.md` (use git mv).
2. Rewrite the document completely to remove all mentions of "rows", "tuples", and "projection boundaries".
3. Define how the Map AST itself is canonically encoded and hashed (e.g., does it rely on the same encoder, or a new map encoder?).
4. Define how `yin.vm` observers receive batches of Map ASTs from `dao.stream` and load them directly.
5. Ensure the document's new title and thesis reflect "Code as Maps".

Do not commit your changes. Just execute the edits and leave them in the working tree.
