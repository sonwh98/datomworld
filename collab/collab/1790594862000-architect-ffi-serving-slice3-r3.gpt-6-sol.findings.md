Coding-Agent: codex
Session-ID: 01a0e7c5-6b35-7e01-9081-541da3b78e92
Model: gpt-6-sol (pinned)

Completed-GMT: 2026-09-28 16:22:01 GMT  
Completed-Local: 2026-09-28 23:22:01 Asia/Ho_Chi_Minh

## 1. Correlation ruling

Use a **caller-scoped apply ID and an FFI response router**. An exclusive caller tenure cannot be enforced by the current endpoint: a remote descriptor can be attached by another caller, and the mirror protocol has no caller identity. Minting the second VM’s cursor at `:newest` alone would avoid old responses but would not resolve simultaneous callers.

The caller’s host composition must mint a portable, unique token for each VM caller tenure and pass it as `:ffi-caller-id` when constructing a VM with a call pair. A call’s ID is `[caller-token local-park-id]`. Make that **also the parked map key**, so there is one ID to retain and correlate; do not store a second mapping. `engine/park-continuation` needs an explicit optional ID that becomes both its map key and returned `:id`. Each VM FFI call site supplies the composite ID. `apply/correlation-id?` already accepts any non-`nil` ID, and the responder echoes the ID; rpc’s independent safe-integer allocator is unaffected. The original VM request map still travels unchanged. UCF’s existing `:yin.k/call-id` and retained request envelope carry the composite ID without a new AST or envelope field ([apply.cljc:24](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:24), [semantic.cljc:462](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:462), [ucf/remote.cljc:328](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/remote.cljc:328)).

**ID uniqueness alone is insufficient.** The generic wait sweep reads the next call-out value for each waiter and advances their shared cursor before `call-result` checks the ID ([waitset.cljc:202](/Users/sto/workspace/datomworld/src/cljc/dao/stream/waitset.cljc:202), [engine.cljc:1343](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1343), [ffi.cljc:94](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi.cljc:94)). Add a bounded FFI-specific poll in `yin.vm.engine`, beside its existing link-specific poll. For each response cursor cell, read at most the configured budget, advance that cell on every `ok`, and wake **only** the live FFI waiter on that cell whose `:call-id` equals `apply/response-id`. Keep other waiters parked; discard or diagnose responses for no live waiter. A terminal read wakes the affected waiters as loss. Leave ordinary `dao.stream.waitset` behavior unchanged. Retain `ffi/call-result` as the final validation of the matched envelope.

**Required VM files:** `yin.vm.cljc` (construction option and state), `yin.vm.engine.cljc` (park ID and FFI polling), `yin.vm.ffi.cljc` (shared ID construction and loss data), `yin.vm.semantic.cljc`, `yin.vm.ast_walker.cljc`, `yin.vm.debruijn.stack.cljc`, and `yin.vm.debruijn.register.cljc` (the four FFI call sites). The UCF remote facade needs acceptance checks for the composite ID but no format change. These VM edits require the stated **OWNER authorization**. A two-VM test must prove that each receives its own result even when both use one exported pair and their responses arrive in the opposite order.

## 2. Call-out readiness

Put a bounded, state-threaded readiness step in the **caller composition**, before VM construction. It attaches both reflections, asks the call-out reflection for a `:newest` cursor, yields on retry, and returns the actual minted cursor after the remote drive files the answer. Add a trusted `:call-out-cursor` construction option so `yin.vm/empty-state` installs that cursor directly; retain its current mint for locally created pairs. This keeps the VM unaware of remote reflection retry behavior and ensures the cursor exists before this caller sends a request. The current test-only pre-poll demonstrates the needed sequence ([responder_test.cljc:278](/Users/sto/workspace/datomworld/test/yin/vm/ffi/remote_serve/responder_test.cljc:278)); construction currently requires synchronous `mint-oldest` ([yin.vm.cljc:2043](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:2043)).

## 3. Portable loss error

The responder keeps its local `::request-lost` result and closes call-out after a call-in gap ([responder.cljc:180](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi/remote_serve/responder.cljc:180)). It cannot manufacture a correlated apply response for a request already evicted, because its ID is unknown.

When an FFI response wait reads `:dao.stream/end`, the **VM FFI error path** must raise `ex-info` carrying `{:call-id id, :error {:dao.stream.apply/code :dao.stream.apply/ended, :dao.stream.apply/message "FFI response stream ended before this call was answered"}}`. Use the same error shape for a response gap, with a gap-specific message. `:dao.stream.apply/ended` is the ruling’s existing terminal word; the VM reports the loss it actually observed without claiming to know why the responder closed the stream. An end currently wakes with `nil`, which falls into “malformed envelope” ([waitset.cljc:118](/Users/sto/workspace/datomworld/src/cljc/dao/stream/waitset.cljc:118), [ffi.cljc:95](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi.cljc:95)).

## 4. Commit order and owner decisions

**Give the VM correction its own slice and gate.** The holder’s grant-gap fix is confined to `holder.cljc` and can be included with the responder/holder 3c work after its focused gate; that commit must be described as server and holder composition, not completed remote FFI. Then land caller readiness, VM correlation and routing, and the portable loss error together: they form one correctness boundary. Claim remote FFI end-to-end completion only after the two-VM, readiness, gap, and migrated retained-call tests pass on that combined state.

The **OWNER decision** is authorization to edit the named VM files. The owner also supplies deployment choices for token minting, handler and renewal authority, channel adapter, capacity, and lease timing. Those values are composition inputs; they do not require another architecture ruling.
