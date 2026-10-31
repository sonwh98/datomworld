Created-GMT: 2026-10-20 14:05:00 GMT
Created-Local: 2026-10-20 21:05:00 +07
Coding-Agent: claude
Model: claude-fable-5-1
Session-ID: 36134bec-9cbd-4852-b78c-4901af66fd02
Task: revise the dao.stream host-boundary design (r2)
Role: Architect
Deliverable: docs/design/dao.stream.host-boundary.md revised in place (r2); no src edits, nothing staged.
Labels: V verified by reading, R read-not-run, P proposed. Nothing executed. Non-Claude sign-off required again.

# Rationale

**The refined rule changes the basis, not most verdicts.** r1 grounded the
primitive profile in "machine-internal"; astra's A1 was right that ABI §4-§5
permit in-instance calls without making purity a criterion. The owner's test 2
supplies the criterion: dao.stream wherever the contact *can be an IO action*.
§2.2 makes that operational with six properties (observation, effect,
nondeterminism, asynchrony, world-dependent failure, another process), each
cited to the doctrine. §2.3 decides the borderline cases: fallible-but-pure
decoders are direct calls (failure is a function of the input); hashing in a
linked library is a direct call that must execute in-instance (wasm: never an
import); entropy, time and scheduling are IO; the Python parser is pure
in-process and IO as another process, and the stage shape serves both. §2.4
states plainly where the owner's literal sentence is met (all world contact)
and not met (the profile and `main` are still per host).

**Entropy was r1's real defect.** Passing `random-bytes`/`schedule` functions
into portable code is the "callable host service" ABI `:143-148` rejects (A2),
and the resolved-call census (A3) shows 24 `random-uuid` sites plus `rand`,
`gensym` and secure generators that a reader-conditional grep cannot see,
including inside the in-memory streams, so "in-memory needs no seam" was wrong
about identity. r2: an entropy stream the driver deposits onto (events in),
identities derived from drawn bytes, `{:identity}` in creation specs, and
`content.async` turned into a stepped owner the driver pumps. A reproducible-
identity test becomes possible for the first time.

**Exactness replaces "about 55".** Table A names all 66 functions by name
and source line; table B the portable replacements; table C the runtime
prerequisites astra named (btree atomics and weak refs with the strong default,
`yin.vm.values` and the Jing carriers' equality/hash/print blocks, the promise
facade). Oracles are named per entry with V/P labels; laws for aliasing
(`slice` copies, `copy!` is memmove), malformed UTF-8 (refuse, never U+FFFD),
Unicode 16.0, and sort stability are pinned.

**Storage seam (A7).** The claim-file algorithm stays portable; the seam
enumerates the lower operations (`create-exclusive!`, `unlink!`, `list-dir`,
`own-pid`, `process-alive?`, `canonical-path`) beside the OS lock, with
short-read, failed-append, failed-sync and failed-rename outcomes specified.

**Disjointness re-checked (V).** Two one-line edits in codec-track files
(`codec.cljc` require, `ws.cljc:122` identity) are deferred past C4 and
recorded. Nothing else overlaps.

# Disposition of astra's findings

+----+---------------+----------------------------------------------------------------------------------------------------------------+
| Id | Disposition   | Where in r2                                                                                                    |
+====+===============+================================================================================================================+
| A1 | accepted      | §2.1-2.2 basis is purity/totality/synchrony grounded in ABI §4-§5; §7 wasm in-instance rule and import-list    |
|    |               | gate (H8); §2.4 literal-sentence statement; HD1 supersedes r1's basis.                                         |
+----+---------------+----------------------------------------------------------------------------------------------------------------+
| A2 | accepted      | §4.1 seam rule distinguishes implementation-private seams from application-facing services; §4.4 entropy       |
|    |               | stream, stepped `content.async`, driver-owned pumping, `dao.space.dht` change; H5 rewritten; HD3.              |
+----+---------------+----------------------------------------------------------------------------------------------------------------+
| A3 | accepted      | §3.2 resolved-call census (9 rows, every site cited); §3.3 exempt syntax by pattern; H0 scoped to the adopted  |
|    |               | closure with two lists and two negative tests; in-memory identity claim corrected.                             |
+----+---------------+----------------------------------------------------------------------------------------------------------------+
| A4 | accepted      | §4.3 tables A (66 named entries), B, C (atomics, weak/soft refs, carrier types, promise facade); every residual|
|    |               | assigned a slice or "none" with reason; sorting/hashing/regex/format classified in §2.3 and §3.2 row 9.        |
+----+---------------+----------------------------------------------------------------------------------------------------------------+
| A5 | accepted      | Oracle files and cases per entry in table A with V/P labels; laws pinned (aliasing, malformed input, Unicode  |
|    |               | 16.0, stability); corpus errata preserved (table B, H4).                                                       |
+----+---------------+----------------------------------------------------------------------------------------------------------------+
| A6 | accepted      | H2 lists all nine consumers incl. the three Python tests; empty-grep gate before deletion; H1 tests the new    |
|    |               | namespaces directly; "engine untouched" withdrawn (§9).                                                        |
+----+---------------+----------------------------------------------------------------------------------------------------------------+
| A7 | accepted,     | §4.2: lower operations enumerated (option 2), algorithm stays portable; outcomes for short reads, partial       |
|    | option 2      | writes, acquisition, durability; H6 acceptance adds scripted-seam tests for each.                               |
+----+---------------+----------------------------------------------------------------------------------------------------------------+
| A8 | accepted      | All slowdown/speedup numbers removed; H4 carries a measurement with no threshold (OQ7); risk and cost           |
|    |               | reassessed in §8.1 and §9 after the inventory; OQ1-OQ6 recorded as adopted in §11.1 with the owner's quotes.   |
+----+---------------+----------------------------------------------------------------------------------------------------------------+

Not verified: no lane was run; the §3.2 census is by `rg` over the closure
with every hit opened, not by a compiler-resolved call graph; the H4
performance question is open until measured.
