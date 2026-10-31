Completed-GMT: 2026-09-27 17:29:42 GMT
Completed-Local: 2026-09-28 00:29:42 ICT

Updated [yang.antlr.md](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md): replaced the governing `dao.stream.apply.md` reference with `dao.stream.remote.md` and `dao.jing.content`; clarified mirror/reflection transport, `ws-project` composition, and `dao.jing/accept-bytes!` ingress; and limited `dao.stream.apply` to the VM’s local FFI bridge, with its network wire envelope retired.

The draft had no references to `dao.jing.remote`, `dao.stream.serving`, `dao.stream.rpc.ws`, the WebSocket accept frame, the served-path table, or a REPL copy model. Its encoder envelopes describe compiler artifacts and remain current. Passages marked historical: **0**. The diff passes whitespace checks; all edited lines are ASCII and within 80 columns.

Status: COMPLETE