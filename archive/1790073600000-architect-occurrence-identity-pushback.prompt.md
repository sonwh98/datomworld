Created-GMT: 2026-09-22 09:53:00 GMT
Created-Local: 2026-09-22 16:53:00 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed: your de Bruijn VM design thread)
# Task: architect-occurrence-identity-pushback — verify your blocking finding against the actual entity-minting code
Role: Architect

Your sign-off on the three documents flagged a BLOCKING finding: "resolved
tuples preserve source entity IDs while replacing each variable's name
with a context-dependent lexical address... If one variable entity is
referenced under two lexical contexts, that entity may be bound at
different depths or be bound in one occurrence and free in another."

Before this is acted on: read `src/cljc/yin/vm.cljc` lines 538-556
(`ast->datoms-with-root`) yourself, specifically the `gen-id`/`pre-eid`
logic and the comment above `seen-eids`. Every AST node -- including
every `:variable` occurrence node -- receives a FRESH, unique entity id
via `gen-id` UNLESS the source node map itself carries a pre-assigned
`:eid` key. That sharing mechanism is explicitly documented ("a shared
lambda-ast (same :eid in definition operand and call-site operator)") as
existing for reusing a whole LAMBDA SUBTREE across a definition and its
reference sites (code deduplication), not for plain variable-occurrence
nodes. Every test fixture and corpus construction in this codebase builds
`:variable` nodes fresh, with no `:eid` (e.g. `(defn- v [s] {:type
:variable, :name s})` in the B2 test corpus and `yin.vm.linearize-test`'s
own construction style) -- so two syntactic occurrences of the same name
under different binders are always distinct entities today. A single
entity being resolved under two different lexical contexts would require
a caller to deliberately assign the same `:eid` to two variable nodes
under different binder scopes, which is not how variables are ever
constructed, and would misuse a mechanism documented for a different
purpose (subtree sharing, not per-occurrence dedup).

If this holds, the blocking finding does not describe an actual defect
introduced by the resolved-tuples design -- it describes a property of
`ast->datoms-with-root` that the already-merged, already-signed-off named
linearizer (`yin.vm.linearize/lower`) and the original B2 implementation
have relied on unconditionally already, before this session's redesign.
Confirm or refute this directly against the code (not against your prior
reasoning), and either withdraw the finding or explain specifically what
scenario in the ACTUAL construction pipeline (not a hypothetical shared
entity) still produces the ambiguity you described. Do not soften your
answer to avoid contradicting your own prior finding if the code says
otherwise.

While you have the thread open, also state plainly: does withdrawing this
finding (if warranted) change your "not sound" verdicts on
`yin.vm.debruijn.register.md` and `yin.vm.debruijn.stack.md` to "sound
with changes" given your should-fix findings (the direct-lowerer
validation seam, the H/R biconditional overclaim, the unverified side
table capture risk) are the only ones that would remain?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
