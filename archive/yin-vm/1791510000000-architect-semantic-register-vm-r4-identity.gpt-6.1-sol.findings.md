I’ll read the revised linker block and check that its walker admission rule uses the existing AST-to-semantic lowering.
Completed-GMT: 2026-10-09 18:25:02 GMT
Completed-Local: 2026-10-10 01:25:02 Asia/Bangkok

The revision closes the walker gap. No remaining merge blockers.

The correct reference today is `yin.vm.linearize`: for canonical AST rows, `lower-rows` produces the semantic instruction vector whose `ucf/code-address` must equal the requested A. Verification must use the declared lowering and semantic contracts. The forthcoming register-shaped design can replace that lowering without changing this admission rule.

The walker-specific bullet correctly qualifies the general tree → A → native-image wording: walker admission verifies **tree → A**, without assuming an inverse. Syntax-content addresses remain valid, and trusted derivation records cannot bypass verification.

Contract-scoped `[A f]` indexing, independent availability of the canonical vector, and the two integrity obligations are consistent. The amendment does not pre-empt register numbering, windows, allocation, stackification, or delivery semantics.

Read-only review; no files edited.

VERDICT: READY_TO_MERGE
