Completed-GMT: 2026-09-24 12:13:57 GMT
Completed-Local: 2026-09-24 19:13:57 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a0d340-f8e7-7e30-9b74-c0a0e6b636fb

# Task: Consensus Verification of yin.vm.linker.md (Revision r2)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Status-Event: 2026-09-24 18:57:31 +0700 | Model: gpt-5.6-sol | Status: completed | Rationale: Initial review findings (8 P1, 6 P2)
- Status-Event: 2026-09-24 19:13:57 +0700 | Model: gpt-6-sol | Status: active | Rationale: Resumed thread 01a0d340-f8e7-7e30-9b74-c0a0e6b636fb for consensus verification

Verdict: REJECTED

Revision r2 resolves several findings, but the request correlation, module installation, derivation, and authority rules still permit incorrect or unverified behavior.

| Item | Status | Evidence and required change |
|---|---|---|
| 1. AST hash | CLOSED | L445-461 hashes the fetched body and prepends its address only for tree validation. |
| 2. Wire request purity | PARTIAL | L633-647 excludes functions and handles, but its example contains both `:yin.link/name` and `:yin.link/identity` while requiring exactly one. `:invalid-request` is also absent from the refusal table at L391. Correct the example and define the refusal. |
| 3. Dependency obligations | PARTIAL | L352-374 adds exact profile and manifest checks. It offers no declaration for a module's own store definitions: a body such as `(def x 1) x` has a free `x` under the stated scanner and becomes `:undeclared-free`. Define and verify module-local store obligations. |
| 4. Pending states and correlation | PARTIAL | L758-824 specifies cursor-before-append, retained requests, and exact response matching. But IDs come from each VM's counter (L764); child tasks have separate VMs (L853-859). Two tasks sharing a response stream can mint the same ID. Mint IDs in the shared scheduler/link-pair scope, or include a unique task origin. |
| 5. Child installation | PARTIAL | L832-888 gives the install an independently stepped child. Exported closures still carry child-local code coordinates: the spec proposes loading into the parent only when a binding is applied (L877-885), while current stack and register closures contain a body PC without an image identity, and semantic closures contain a local segment ID. Specify export relocation or invocation in the owning child VM before publishing bindings. |
| 6. Four-way derivation | PARTIAL | L941-965 verifies that a record *claims* the manifest's tree and image identity. It permits `:fallback :verifying` to turn into composition trust when the profile differs, and does not require fetching the tree for recomputation. A forged, correctly hashed record can therefore claim an unrelated image. A verifying request must fetch the tree and recompute under an exact per-format profile, or refuse. |
| 7. Manifest name | CLOSED | L966-970 compares the declared and requested names after manifest validation. |
| 8. M4 authority | PARTIAL | L997-1026 fixes snapshots and ambiguous-name refusal. The accepted principal is still a self-asserted datom, with authentication explicitly left open. Require authenticated transaction provenance or verified signed assertions before treating `:asserted-by` as authority. |
| 9a. Address decoupling scope | CLOSED | L197-215 now states accurately that strict storage/VM identity decoupling holds only for H and R. |
| 9b. Address-directed matching | CLOSED | L257-264 uses the identity's algorithm for storage-derived formats. |
| 9c. Row bounds | CLOSED | L319-332 validates each row before following children and defines part, depth, and byte bounds. |
| 9d. Driver cadence and local path | CLOSED | L672-700 replaces the raw handle with an explicit driver and requires a traffic-equivalence test. |
| 9e. DHT boundary | CLOSED | L589-597 places the synchronous DHT handle behind the content server and limits its equivalence claim to the linker boundary. |
| 9f. Host modules | PARTIAL | L1047-1060 checks declared profile classes. A profile alone cannot enforce the claim that a host function performs no IO before returning effect data. State the trusted composition boundary and its verification obligation explicitly. |
| 10. ASCII and width | CLOSED | Read-only checks found zero non-ASCII lines and zero lines over 80 columns. |

One additional ordering defect remains: L289-300 runs row-local grammar validation in step 2, while L342-351 says `:contract-mismatch` is raised before any grammar check under the wrong contract. Check the requested contract before step 2's row validator. No files were edited.
