Created-GMT: 2026-09-27 19:05:00 GMT
Created-Local: 2026-09-28 02:05:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Consistency pass — yang.antlr.md vs the post-slice-5 tree

Role: Lead System Architect

docs/design/yang.antlr.md (the ANTLR frontend architecture, drafted
2026-09-24, never gated) is queued for implementation after the
dao.stream.remote epic. Before any gate, its stale references must be
updated to the current tree. The doc predates:
- slice 4: dao.jing.remote DELETED whole, replaced by dao.jing.content
  (the content service over dao.stream.remote reflections) and
  dao.jing/accept-bytes! as the unified ingress check;
- slice 5: dao.stream.serving + dao.stream.rpc.ws deleted; the apply
  wire envelope retired from the network path (ruling A:
  dao.stream.apply.cljc stays as the VM's local FFI bridge only);
  yin.repl.serve/connect reworked onto ws-project + reflections;
- the ws accept frame + served-path table retirement (deferred ws
  debt, executing in slice 5);
- the entire dao.stream.remote.md spec set (mirror/reflection,
  middleware, leases on served entries).

Task:
1. Grep yang.antlr.md for every reference to: dao.stream.apply,
   dao.jing.remote, dao.stream.serving, dao.stream.rpc.ws,
   rpc envelopes, yin.repl.serve's copy model, and any other
   superseded surface. For each: update the text to the current
   architecture (dao.jing.content as the content service;
   dao.stream.remote mirror/reflection as the transport;
   dao.jing/accept-bytes! as ingress; ws-project composition), or mark
   the passage historical with a pointer, per which reads truer.
2. Check the doc's subordinate-doc list still makes sense (it cites
   dao.stream.apply.md as governing -- re-point to
   dao.stream.remote.md + dao.jing.content as appropriate).
3. Do NOT redesign the ANTLR architecture itself: the SPI, the
   grammar-plugin model, the phased roadmap, and the verification
   laws stand. This is a consistency pass only.

Write scope: docs/design/yang.antlr.md only. ASCII, <= 80 columns on
every added/edited line, matching the document's voice.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Summarize: every stale reference found and how you updated it, plus
the count of passages marked historical.

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
