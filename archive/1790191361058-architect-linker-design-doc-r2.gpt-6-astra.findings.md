Created-GMT: 2026-09-23 19:23:50 GMT
Completed-Local: 2026-09-24 02:23:50 +0700 ICT
Coding-Agent: codex
Session-ID: 01a0cfa1-8974-7992-b9d8-cd4e4ea77e65

# Findings: Architect Confirmation Review — yin.vm.debruijn.linker.md (Round 2)

Role: Lead System Architect
Model: gpt-6-astra

## Verdict: REQUEST CHANGES

The two Round 1 findings are resolved. Step 2 now uses `jing/segment-matches?`,
which reads the algorithm from the address. Section 11 also separates linker
checks of corrupt local content and client responses from DHT peer filtering,
which can yield `:absent`.

The owner's shared-linker and Jing-storage/DaoStream-transfer rules are clearly
stated and architecturally sound in this document. B6 is bounded here to
`debruijn_linker.cljc` and its test file; the two findings below prevent
sign-off on that boundary:

## P1 | linker design:208 | Free-names scanners placement

Both format records name `image-free-names` functions in existing code
namespaces, but neither function exists in those namespaces, while the file
box permits only the new linker source and test files.

**Recommendation:** Define the scanners in the new linker namespace
(`yin.vm.debruijn-linker/stack-free-names` and
`yin.vm.debruijn-linker/register-free-names`) and reference them there,
preserving "Existing edits: none".

## P2 | linker design:316 / register design:1011 | Reconcile R5 phase box

This document makes B6 the sole milestone and module for both formats, but the
register design's R5 box still requires a separate register linker source and
test file (`debruijn_register_linker.cljc`).

**Recommendation:** Reconcile that phase box so implementers have one
deliverable contract, noting that B6 provides the unified linker.

## Confirmed Passing

- Round 1 P1 resolved: `segment-matches?` reads algorithm from address.
- Round 1 P2 resolved: local/client verification separated from DHT peer
  filtering.
- Owner's shared-linker (stack + register) rule approved.
- Owner's Jing-storage / DaoStream-transfer principle approved.
- Standalone B6 delivery approved.

No files edited.
