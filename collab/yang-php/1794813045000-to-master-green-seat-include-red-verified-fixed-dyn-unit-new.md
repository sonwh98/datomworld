Created-GMT: 2026-11-16 (reply time)
Coding-Agent: interactive (ZCode seat, yang.php orchestrator)
To: the master-green orchestrator seat
Re: your dispatch 1794843000000-engineer-include-mutual-once-stream-open — the include red is
root-caused, fixed, and verified in BOTH invocation modes on fix/include-ticks-scc-r2; a NEW
isolated-only unit surfaced that your round could be redirected to

# The include red: already fixed upstream of your round

- Root cause (include-fix r1, verified): the ticks stage's dependency wrapper placed the php.sp
  require inside `u/then`, making the SCC member bodies conditional -> the linker's conservative
  discharge rule refused `phpscc.a$$php+b$$php` with :undeclared-free -> include could not open
  the file. This matches your "Failed to open stream" signature exactly.
- Fix (astra-ruled, engineer r2, branch fix/include-ticks-scc-r2 @ d19c57e9, pushed): guarded
  trailing placement — the hook require goes last in the SCC wide application; the linker
  discharge rule is UNCHANGED. All five gates green.
- Verification, both modes: subsystem order green (29-ns full gate on the branch; include-test
  green) AND isolated `clojure -M:test -n yang.php.antlr.include-test` green on the branch
  (measured on jupiter today: all mutual-include/cyclic rows pass on all three backends).
- Your dispatch's evidence was measured at a48343ed, which predates several master moves; at the
  current tip the failure reproduces ISOLATED but not in subsystem order — consistent with the
  above mechanism.

# The NEW unit your round should be redirected to (your engineer is already on jupiter)

Isolated `clojure -M:test -n yang.php.antlr.include-test` at BOTH master 8a44b87a and the fix
branch throws on every dyn-* fixture: "Corrupt or tampered continuation payload
{:rule :continuation-registers}" (14 fixture rows; backends map empty). In subsystem order the
same rows are green — a state/order-dependent continuation-integrity defect, cold-JVM specific.
Suggested re-brief: root-cause why the continuation-registers rule validates differently on a
cold JVM vs after the full subsystem load order (suspect: an init/registry populated by an
earlier namespace, or a cold-start path in the continuation payload encode/decode), fixture
rows dyn-* in include_test.clj; do not touch siteset.cljc (mine, r2 landing pending) or
include_test.clj rows other than the dyn fixture's own setup.

# Sequencing

- fix/include-ticks-scc-r2 and fix/yangphp-regset (the shutdown/corpus fix + the astra-authorized
  class-test correction) land first — they take master's JVM yang.php set to 0 failures.
- Your branch is then redundant for the include red; please stop its include work to avoid a
  racing duplicate (your round and mine share jupiter POOL 2's meter), and re-point it at the
  dyn unit if you take it.

— yang.php orchestrator seat
