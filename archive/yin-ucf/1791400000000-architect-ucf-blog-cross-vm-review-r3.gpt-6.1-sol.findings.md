I’ll check the Part Two recast against the custody and admission rules, then read the full essay for any remaining reliance on curvature or holonomy. This review will remain read-only.
The recast fixes the copying-versus-holonomy distinction, but two custody claims remain too strong: it says exactly one candidate is admitted for an occurrence, although re-grants can admit successive holders, and it calls the epoch the complete compatibility condition. I also found the old “manifold is not flat” sentence still present; the new sheaf language needs an explicit analogy qualifier.
Completed-GMT: 2026-10-09 16:12:24 GMT
Completed-Local: 2026-10-09 23:12:24 Asia/Bangkok

Reviewed `cc5bf3ed`, the three-commit log, the preceding-commit diff, and the essay from disk. No files edited.

The recast correctly distinguishes faithful copying from path-dependent transport. Four essay-accuracy issues remain; none requires changing the architecture.

1. **MEDIUM | public/chp/blog/hygienic-parallel-transport-universal-continuation-format.blog:239 | Custody does not admit exactly one candidate forever.**  
   §7.7.7–§7.7.8 permit successive grants for an uncompleted occurrence after reclaim. Admission can also suspend, so there is no unconditional existence guarantee. A candidate that never obtained a grant is not a “lapsed incarnation.” Fork policy deliberately permits multiple continuations.

   **Correction:** Replace the sentence with: “Under exclusive custody, at most one lease is current for an occurrence at a time. Reclaim may admit a replacement holder; stale holders cannot commit protected effects, and completion records exactly one successor or terminal edge.” Scope the abstract’s “one of those readings” and line 194’s “where there must be one” to exclusive custody.

2. **MEDIUM | public/chp/blog/hygienic-parallel-transport-universal-continuation-format.blog:217 | The epoch is part of the admission condition, not the whole condition.**  
   §7.7.8 requires readable authority, a valid authenticated binding, an open occurrence, the active lease, matching epoch, operation scope, and intent/dedup checks. Matching the epoch alone establishes neither tenure nor permission to commit.

   **Correction:** Say “The epoch is one term of that condition, bound by the authority to the grant; admission checks the full binding, tenure, operation scope and intent.” Keep the preceding atomic-admission paragraph.

3. **MEDIUM | public/chp/blog/hygienic-parallel-transport-universal-continuation-format.blog:162 | A curvature-dependent explanation remains.**  
   “The manifold is not flat: hosts differ…” still treats host heterogeneity as evidence of non-flatness, contrary to the new distinction at line 192. Differences in representations and available capabilities do not establish curvature.

   **Correction:** Replace it with “Hosts differ in representation and available capabilities; those differences are what lift, lower and compatibility checks must account for.”

4. **MEDIUM | public/chp/blog/hygienic-parallel-transport-universal-continuation-format.blog:194 | The sheaf language is not yet explicitly qualified.**  
   The text asserts local sections over neighbourhoods and a global section without defining the base, restriction maps or overlap conditions. Line 158 still calls the correspondence “exact enough to compute with.” Moreover, sheaf gluing assembles compatible local sections; it does not itself select one competing candidate. The move from host/time points to occurrence points at line 204 also needs qualification.

   The general-geometry sentence at line 156 is too broad: a flat connection can have global holonomy on a non-simply-connected base. Curvature measures infinitesimal failure, not every possible form of path dependence.

   **Correction:** Add an explicit qualification: “This is a gluing analogy, not a constructed sheaf: the ledger supplies the admissibility rules for local candidate continuations.” Replace line 158’s exactness claim with an analogy qualifier; describe UCF transport as path-independent **up to the canonical equivalence already stated**, rather than proving geometric flatness. At line 156, distinguish curvature from possible global holonomy. Qualify the Lineage’s “ledger is the gluing” accordingly.

Part Three’s references to Part Two’s geometry and custody remain coherent with these corrections. The retained separation between transport and admission, atomic intent/dedup checks, and honest limits on protected effects passes review.

The retraction sentence at line 192 is acceptable in a published essay and need not be cut. For a more timeless presentation, optionally replace its opening with “This copying should not be mistaken for holonomy.” That is editorial, not blocking.

VERDICT: CHANGES_REQUESTED
