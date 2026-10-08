Created-GMT: 2026-09-22 20:49:10 GMT
Created-Local: 2026-09-23 03:49:10 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: pending (new session, caller-generated)
# Task: reviewer-hash-registry-plan — independent review of gpt-5.6-sol's hash-agility plan
Role: Independent Reviewer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-23 03:49:10 +07 | Status: active | Rationale: independent family from the plan's author (gpt-5.6-sol/codex); this project's DaoJing/DHT/storage-review track record fits this plan's heavy backend/transport surface

Work in /Users/sto/workspace/datomworld (your launch directory; branch
master). READ-ONLY, STATIC REVIEW. Do not edit any file, do not run
`git add`/`commit`/`push`. This is a design-plan review, not a code
review -- there is no implementation to run tests against yet.

## Context

The owner wants dao.jing (content addressing) and yin.vm (de Bruijn VM)
to move from a single hardcoded SHA-256 hash to a hash-agile scheme with
BLAKE3 as the new default, inspired by (but not literally copying) IPFS
multihash. An orchestrator session first drafted a plan
(`:segment/<algo>-<hex>` address generalization); that draft was then
handed to gpt-5.6-sol (via codex) for independent architectural design,
NOT just review. Sol's full report is at
collab/1790109451076-architect-hash-registry-plan.gpt-5.6-sol.stdout.log
-- read it in full (it is JSON-lines from `codex exec --json`; the final
`item.completed` event with `item.type == "agent_message"` and the
longest text is the actual plan -- read the whole file to see Sol's own
tool-call trail too, which shows what it actually read before writing
the plan).

Sol's plan materially diverges from the orchestrator's draft:
1. Addresses must carry BOTH an encoding-profile id and an algorithm id
   (`:segment/<encoding-id>+<algorithm-id>-<hex>`, e.g.
   `:segment/print-v1+blake3-<hex>`), not just an algorithm id, because
   the codebase's own documented (but not yet implemented) CBOR
   canonical-encoding migration would otherwise make old SHA-256
   addresses unverifiable once the default encoder changes.
2. Sol audited actual call sites and found the "hashing is centralized
   in dao.jing.cljc" claim false: `yin.vm.debruijn_code` (`image-hash`,
   `descriptor-hash`), `yin.vm.debruijn` (`dimension-hash`, `node-hash`),
   and `dao.jing.dht` (`node-id`) call `jing/sha256` directly; and
   `yin.vm/primitive-profile` hardcodes a `"sha256-"` string label
   around `jing/content-hash`, which would mislabel a BLAKE3 digest
   after a default flip -- a real bug, not a style nit.
3. Sol found `dao.space.index`'s checkpoint `:schema-hash` is persisted
   as a bare hash with no algorithm tag and re-verified against
   whatever the CURRENT default is, calling it "not algorithm-agile."
4. Sol enumerated roughly 18 backend/transport/consumer sites (mem,
   file, remote, DHT, dht.node, btree, dao.space.index, yin.vm.content,
   yin.vm.macro, yin.vm.semantic, yin.vm.ledger, yin.vm) that currently
   re-derive the CURRENT DEFAULT hash to validate an address, rather
   than reading the algorithm/encoding off the address itself, and
   proposed a single `segment-matches?` function every one of them
   should route through instead.
5. Sol recommends yin.vm's H/R (image-hash / the not-yet-merged register
   format's register-hash) stay explicitly pinned to SHA-256
   permanently, decoupled from dao.jing's default, changing only through
   their own contract-version process -- and notes the register
   implementation itself does not exist on this branch (only its design
   doc does; R0/R1 landed on the separate `register-r0` branch/worktree,
   not master).
6. A six-phase rollout, H0 through H5, each with concrete completion
   criteria, plus a test-obligation matrix and an explicit non-goals
   list.

## What to check

1. **Verify Sol's call-site audit against the actual code**, don't trust
   it blind. Grep/read `yin.vm.debruijn_code.cljc`, `yin.vm.debruijn.cljc`,
   `dao.jing/dht.cljc`, and `yin.vm.cljc`'s `primitive-profile` yourself.
   Confirm or refute each specific claim (direct `jing/sha256` calls,
   the primitive-profile mislabeling bug, the DaoSpace bare
   `:schema-hash`). If Sol missed a call site or misdescribed one,
   name it precisely (file:line).
2. **Verify the core architectural claim**: does the documented (not yet
   implemented) CBOR canonical-encoding migration in
   docs/design/dao.jing.md really break an algorithm-only address
   scheme the way Sol claims? Read dao.jing.md's "Canonical encoding"
   section yourself and confirm the reasoning holds, or explain why it
   doesn't.
3. **The `segment-matches?` centralization proposal**: is routing ~18
   sites through one address-directed verification function actually
   sound, or does any of those call sites have a reason (performance,
   trust boundary, different failure semantics) that a single shared
   function can't accommodate? You have specific DaoJing/DHT/storage
   review history on this project -- use it.
4. **The phased rollout (H0-H5)**: are the completion criteria for each
   phase actually testable and actually sufficient to prevent the phase
   after it from silently breaking something the phase before it
   guaranteed (e.g. does H2's "mixed-address storage" phase actually
   prove old SHA content stays readable, or just assert it)?
5. **The yin.vm H/R disposition**: does "freeze independently of
   DaoJing's default, change only through the format's own
   contract-version process" actually hold up against how H/R are used
   today (B1's `image-hash`, `descriptor-hash`) and how they're
   documented for the not-yet-implemented register format
   (docs/design/yin.vm.debruijn.register.md)?
6. **Scope discipline**: does the "explicit non-goals" list actually
   match what the plan's own H0-H5 phases do, or does any phase quietly
   do something the non-goals list disclaims (e.g. does anything
   silently rehash/rewrite existing content, or silently couple the
   BLAKE3 default flip to the CBOR migration despite claiming they're
   independent)?
7. Anything else a fresh, skeptical read finds -- an inconsistency
   between two sections of the plan, a missing host-portability detail,
   a test-obligation gap, a place the plan asserts something about the
   codebase you can check and find is wrong.

## Verdict

READY / READY WITH CHANGES / NOT READY, findings as P1 (must fix) / P2
(should fix) / P3 (nice to have), each with a concrete failure scenario
or file:line, not a vague concern. State plainly whether this plan
should be promoted into a committed `docs/design/` document as-is,
with changes, or substantially reworked.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: <your session id>
Then the verdict and findings. Facts only, each claim checked against
the actual code or the actual plan text, not assumed.
