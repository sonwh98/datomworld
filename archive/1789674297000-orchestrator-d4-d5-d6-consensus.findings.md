# Architectural Consensus: D4, D5, D6

**Date:** $(date +"%Y-%m-%d %H:%M:%S %Z")
**Participants:** \`gpt-6-astra\`, \`claude-fable-5.1\`
**Role:** Architects

## Consensus Rulings

### D4: Batch Coordinates
**Resolution:** Producer/composition-minted admission token.
- **Coordinate:** \`[:source medium batch-token j]\`, with \`medium = :dao.stream/identity\` and \`j\` the batch member index.
- **Allocation:** The admitting composition mints a fresh random UUID for each new batch admission and places it under a qualified key in the §8.5 envelope, outside canonical rows and hashed code.
- **Stability:** All observers and rereads receive the same token. Recovery preserves the staged or retained envelope.
- **Retries:** Reuse the token when retrying a staged append that was not accepted. A fresh admission after an accepted append requires a fresh token.

### D5: Macro Expander Implementation
**Resolution:** Datom-native first.
- **Reasoning:** "Macros are stream topology" makes the expander a process upstream of evaluators. Its input representation is a seam, and §9.1 already names the adapter as that seam. Rewriting the macro spec for rows would combine two correctness problems.
- **Action:** Build datom-native first using §9.1's adapter to preserve §8.5's ordered harvest catalogue and occurrence-bound declarations.

### D6: Universal Continuation Format (UCF)
**Resolution:** Commit r3 now as a baseline.
- **Reasoning:** An untracked document that six units cite is hidden state. Commit the file verbatim with a docs-prefixed message, preserving the Proposed/Deferred warning, so U12's amendment is a reviewable diff.
- **Action:** U12 will carry the two §7.6.1 corrections, the §7.3.4 supersession note, and the stale "r2" wording in the warning box.
