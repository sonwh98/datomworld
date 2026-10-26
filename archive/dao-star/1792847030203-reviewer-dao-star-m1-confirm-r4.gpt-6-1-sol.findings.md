Completed-GMT: 2026-10-24 13:05:46 GMT
Completed-Local: 2026-10-24 20:05:46 +07

Static review only; no edits or suite reruns.

- **Gap 1 — resolved:** `src/cljc/dao/star/driver.cljc:443` observes subscription holders before processing inputs; `:314` refreshes them before patches; `:145` checks exchange tenure; `:362` checks both lineage and exchange tenure before staged projection. The two independently scheduled tests exercise the previously missing cases.
- **Gap 2 — resolved:** `driver.cljc:277` invokes session-wide `forget!`, clearing subscriptions and marking the lineage gapped (`:199`). Subsequent renewal iterates the updated subscription set (`:430`). The two-cursor test covers sibling cleanup.
- **Gap 3 — partially resolved:** `src/cljc/dao/star/lease.cljc:104–128` preserves rejected grants across unusable readings, but loses associated lapse records. This introduces the defect below.
- **Docstring — resolved:** `src/cljc/dao/star/signals.cljc:5` now qualifies totality by supported host and identifies the CLJD exception.

**HIGH | `src/cljc/dao/star/lease.cljc:112` | Deferred grants can outlive an observed lapse.**

While ungranted, every value takes the first conditional branch. With an unusable reading:

1. An `:accepted` grant is retained in `:pending-facts`.
2. A following `:lapsed` for that lease is discarded: `observe-grant` rejects it, and only accepted facts are retained.
3. On a later usable reading, the retained grant establishes tenure. The lapse is already behind the advanced cursor.

The holder can then report holding, renew, or permit staged projection despite having read its lease’s retirement. The new invalid-reading test covers only a grant followed by recovery, so it misses this interaction.

**Recommended correction:** defer fact-cursor advancement until a usable reading exists, or preserve ordered lifecycle evidence—including lapses—alongside deferred grants. Add a grant → lapse → usable-reading test asserting no tenure, renewal, or projection.

The flagged **foreign-grant residual** does not grant authority to the wrong holder: `observe-grant` still checks attribution and addressing. However, pending storage can grow indefinitely if the holder’s own grant never arrives. Filtering permanently inapplicable grants would avoid that resource risk; distinguish them from applicable grants deferred solely because of an unusable reading.

The projection and gap fixes appear complete for the reviewed paths. The deferred-grant lifecycle defect blocks sign-off.

**NOT SIGNED OFF**