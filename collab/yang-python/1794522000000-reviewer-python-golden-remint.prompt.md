Created-GMT: 2026-11-12 22:20 GMT
Created-Local: 2026-11-13 05:20 +0700
Dispatched-By: master-green orchestrator seat (interactive)
Destination: qwen3.8-max (non-codex reviewer per the independence rule)
Task-ID: 1794522000000-reviewer-python-golden-remint

# REVIEW: branch `python-golden-remint` (ce9c6fdf + 588ce3b5) before it lands

READ-ONLY review — do not edit files, do not run test suites; git and file reading only.
You are the independent reviewer for a codex-implemented golden re-mint round.

## Inputs

- Diff: `git diff origin/master...origin/python-golden-remint` (5 files: corpus edn + 4 pin
  files), commits `ce9c6fdf` (corpus + pins), `588ce3b5` (safepoint pin).
- Findings (with per-command rc/Ran evidence and cross-host agreement records):
  `collab/yang-python/1794521000000-engineer-python-golden-remint.codex-gpt-6.1-terra.findings.md`
- Attribution trail: `collab/exception-track/1794519298000-orchestrator-relay-provenance-adoption-broke-node-dht-import.md`
- Precedents for this exact commit shape: `e6380a2f`, `aeb423fc`, `bb3976f8`, `428bbdd7`.

## Review questions (answer each explicitly)

1. Is every changed literal in the 4 test files a pinned address/count accompanied by an
   honest docstring rider naming the content change that moved it (P9 math builtins
   f82afe44 + provenance adoption 00ca2d1c/8018005d)? Any edit that looks like anything
   OTHER than a mechanical re-pin => REQUEST CHANGES.
2. Corpus: diff of `dht-import-v1.edn` — purely regenerated data (addresses/blobs/indexes),
   no hand-crafted values, structure unchanged? The findings claim two-run SHA-256
   `ee91e25e7d74fe85fd5d61f593ff1dd48e70f218c8605f108e14460dc1269f61` — sanity-check that
   the committed file's git blob hash is consistent with a full rewrite.
3. Scope: nothing touched outside the 5 files; in particular the golden corpus under
   `manifest-golden` and `test/yang/call/conformance.clj` are UNTOUCHED (standing contract).
4. Verification completeness: findings record, for every nightly-failing surface
   (dht-import both hosts incl. slow tier, float-address, linked-prelude incl. runtime-keys
   70→71 and manifest-address, prelude-parity seeded-builtins, linked-safepoint
   pysp-manifest both hosts) => green rc. Flag any surface verified on only one host.
5. The 70→71 count change: does the diff show WHY the new runtime key appears (the provenance
   primitive adding one), i.e. is the rider truthful rather than papering a regression?

## Deliverable

Append a verdict section to the findings file: `REVIEW (qwen3.8-max) — APPROVE` or
`REQUEST CHANGES` with numbered items; also state the single strongest reason this is (or is
not) safe to land. Do not push anything.
