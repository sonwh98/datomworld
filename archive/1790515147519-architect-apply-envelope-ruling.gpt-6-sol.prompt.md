Created-GMT: 2026-09-27 18:25:00 GMT
Created-Local: 2026-09-28 01:25:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Ruling — the fate of dao.stream.apply.cljc vs the plan's "retire the wire envelope"

Role: Lead System Architect

Slice 5 (REPL service + copy-path retirement) is BLOCKED on a conflict
the plan's wording leaves genuinely ambiguous. The fate list
(dao.stream.remote.implementation-plan.md section 5) says:

  "dao.stream.apply as a wire envelope: **retired**. Its ok-or-error
   wrapper duplicates the outcome map. The :dao.stream.apply/call AST
   node and the VM's FFI bridge keep the name and are unrelated to the
   network."

But the implementer verified: src/cljc/yin/vm/ffi.cljc and
src/cljc/yin/vm/ast_walker.cljc require dao.stream.apply and call its
ENTIRE function surface (apply2/request, response?, response-id,
response-ok, response-error, put-request!, put-response!,
dispatch-request) -- it is the VM's own FFI-call bridge, load-bearing
for core VM evaluation, not just a name. The implementer's report:
collab/1790497441896-vm-engineer-dao-stream-remote-slice5.r4.claude.findings.md

The two readings:
A. "The FFI bridge keeps the name" means the MODULE stays exactly as-is
   (it remains the FFI bridge's envelope, untouched); "retired" applies
   only to its use as the REPL's NETWORK wire format -- satisfied by
   deleting dao.stream.rpc.ws.cljc and dao.stream.serving.cljc and an
   additive translation rework inside dao.stream.rpc.cljc (translate
   reflections' transport-error/channel-gone/end into the existing
   :dao.stream.apply terminal values driver.cljc keys on). The
   implementer's proposed resolution, with the confirmed ws.cljc
   retirement scope.
B. The module is deleted anyway; its minimal envelope shape moves into
   dao.stream.rpc.cljc (which the earlier reconnaissance proposed);
   yin.vm.ffi/ast_walker re-point at the new home.

Read the implementer's findings, dao.stream.apply.cljc, its consumers
(yin/vm/ffi.cljc, ast_walker.cljc, dao/stream/rpc.cljc,
yin/repl/driver.cljc, adapter.cljc), the plan's section 5 rows for
apply and rpc, and docs/design/dao.stream.md's apply references.

Rule A or B, and prescribe the concrete slice-5 work items that follow
(verbatim-applicable for the implementer): which files are
deleted/reworked, what the rpc.cljc translation rework looks like, and
what serve/connect compose. Keep it minimal; no new modules.

Do not edit files. Cite file:line evidence.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>
