I’ll reconcile the existing content-addressing and dependency machinery first, then add a bounded treatment to the VM design without changing the projection or its fingerprint.
Completed-GMT: 2026-09-21 18:01:44 GMT  
Completed-Local: 2026-09-22 01:01:44 +07  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a

## 1. What exists today

Implemented machinery includes:

- `yin.vm.content`: content-addressed semantic rows and canonical instruction
  vectors, with address verification and validated loading.
- `yin.vm.semantic/load-vector`: records the loaded vector’s
  `:yin.code/hash` alias.
- `yin.vm.completion`: computes conservative reachable dependency facts,
  including `:yin.k/requires`, store slices, module requirements, and
  discovery data.
- `yin.vm.module`: an explicit value-based registry that resolves module
  exports.
- Ledger and macro infrastructure for named definitions and expansion.

What does not exist:

- A linker that classifies free names as definitions, primitives, store keys,
  or module exports and rewrites definition references to hashes.
- A hash-call instruction and address-based call loader.
- A complete cycle/SCC identity scheme for mutually recursive definitions.
- A linked de Bruijn code dimension.

## 2. Ruling

Reference-by-hash is a later epic, not part of this VM’s B phases.

The current VM already provides de Bruijn identity for local bindings and
content-addressed code-image caching. Extending it to dependency identity would
require a new linked layer because the existing projection deliberately
preserves free names and its fingerprint cannot change.

The smallest coherent future scope is:

- input: projected fingerprint plus an explicit, content-addressed name
  environment;
- preserve primitives, store keys, and dynamic module effects as named
  operations;
- rewrite only verified definition references;
- add a new linked-code dimension with a `:call-hash`-style instruction;
- resolve immutable manifests at link time and registry-valued dependencies at
  load time;
- compute dependency closure and stable strongly connected component identities
  for recursive definitions;
- load and verify target code through `yin.vm.content`.

This preserves the current fingerprint-as-cache-key benefit. It would add
dependency-level identity and distribution potential, but would not by itself
provide global distribution or Unison runtime compatibility.

## 3. Honest parity statement

- The VM matches Unison-style identity for local binder renaming and
  content-addressed executable artifacts.
- It does not match dependency-wide hash identity because free names remain in
  the projection and runtime resolution remains name-based.
- A future linker could add hash identity for immutable definitions while
  retaining named treatment for primitives, stores, and dynamic modules.
- Unison runtime execution and any claim of runtime parity are UNVERIFIED.

## 4. Design edit

Added `### 7.1 Future reference-by-hash linking` to
`docs/design/yin.vm.debruijn-vm.md`, and added the corresponding owner decision
to section 8. The edit explicitly marks dependency linking as a future epic,
defines its prerequisites, cycle handling, link/load distinction, diagnostics,
and completion criteria.

Sections changed:

- §7, non-goals and new §7.1.
- §8, owner-decision list.

Owner decisions raised:

- Whether to commission the future linker.
- Which definition ledger and module manifest are authoritative.
- Whether linked code needs a new persisted dimension or remains a derived
  layer.
- When cross-host dependency linking and continuation transport become
  required.
