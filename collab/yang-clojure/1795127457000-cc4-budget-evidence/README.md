cc4 budget closure evidence (ruling 1795017757000, declaration v1
1795016601236). darkstar-cc4-*.log are the per-configuration JVM runner
outputs on darkstar (Darwin arm64, JDK 21.0.2+13-58); jupiter-cc4-*.log
the same on jupiter (48-core Linux, JDK 21.0.13.1). post7/pre7/post8 =
post-port 7-member / pre-port 7-member / post-port 8-member
configurations, suffix -B/-S. The first jupiter pass and the first
darkstar corpus-based S runs used the pre-fix corpus (unconditional
C1-strip): superseded by the redo runs in these files; the final files
are all from the fixed corpus. jupiter-cc4-fidelity is absent; the
fidelity probe ran on darkstar (darkstar-cc4-fidelity.log) and its
member-by-member rows equality holds per pin by construction of the
identical storage bytes across hosts. yang-cc4-corpus.clj and
yang-cc4-budget-runner.clj are the executed harness. The draft
1795017203479 was never modified.