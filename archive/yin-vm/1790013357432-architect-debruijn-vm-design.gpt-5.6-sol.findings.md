I’m reading the existing semantic VM, code tuple, continuation, portability, macro, and stream designs so the new document reuses their contracts rather than inventing a parallel execution model.
Completed-GMT: 2026-09-21 18:00:27 GMT  
Completed-Local: 2026-09-22 01:00:27 +07  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a

Created [docs/design/yin.vm.debruijn-vm.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.debruijn-vm.md).

The design specifies a sibling `:yin.debruijn.code/*` instruction dimension, deterministic lowering from projected records, explicit frame-indexed environments, closure capture, scope validation, continuation and stream semantics, phased implementation B0-B6, cross-host differential testing, and strict coexistence with the named AST and existing VM.

Owner decisions listed in the design:

- Approve the new instruction namespace and descriptor.
- Approve occurrence duplication for initial DAG lowering.
- Decide code-image persistence versus cache-only storage.
- Decide UCF cross-host continuation scope and timing.
- Decide exposure of node-hash debugging data.
- Confirm structural tail propagation for stream applications.
- Decide whether existing VM protocols are implemented immediately.
- Decide whether source-to-projection lineage is ever persisted.

Underspecified projection issues surfaced:

- Reader scope-checking for `{:bound [depth position]}` is still absent.
- No source-to-projected lineage/debug index exists.
- D5 persistence remains a standalone pipeline adapter, not a Yang caller.
- Tail-position recomputation needs a formally shared rule for all stream applications.
- DaoJing file storage retains known host-specific lossiness/refusal limits.
- Universal continuation transport remains proposed rather than implemented.

No material factual errors were found in the brief. The important qualification is that UCF and remote continuation transport are not currently implemented.
