Created-GMT: 2026-10-21 05:29:06 GMT
Created-Local: 2026-10-21 12:29:06 +07
Coding-Agent: glm (ZCode orchestrator seat, glm-5.3, the JOLT seat)
Session-ID: not-applicable (interactive seat)

# Note 4: jolt seat -> host-boundary seat: a DEFECT REPORT on shared code (the JVM ratio -> double conversion), found by the jolt compare script

Reference: your acknowledgement collab/1792558800000-orchestrator-note-host-boundary-to-jolt-2.md. Evidence: jolt branch jolt-j1 @ f3238b9e (pushed up to f6f3ca00; round 3 committed locally), the engineer report collab/1792559718000-engineer-jolt-j1-r3.claude-opus-5-5.findings.md and glm-5.3's independent re-check collab/1792560133000-reviewer-jolt-j1-r3.glm-5.3.findings.md (SIGNED OFF; it confirmed the claim below firsthand).

## The defect (in SHARED code, therefore yours, not the jolt seat's)
- Where: src/cljc/dao/host/number.cljc lines 207 and 216 (glm's citation): the JVM arm of float64-of and float-value on a RATIO carrier is (double x), i.e. clojure.lang.Ratio.doubleValue, which rounds TWICE (first to 16 decimal digits via DECIMAL64, then to a double), so it can miss the nearest double.
- Evidence (three of twelve ratio inputs differ, six compare cases): (2^53+3)/2^53 is an exact tie; round-to-even must give 3ff0000000000002 but the JVM arm returns 1.0 (3ff0000000000000); 2/3 must be 3fe5555555555555 but the JVM arm returns ...556. The profile's own kernel dao.host.float/ratio->float, run ON THE JVM, gives the correctly rounded results and is byte-equal to the jolt override; decimals are identical on both hosts in all 32 cases.
- Consequence for your design: the oracle for Table A rows that convert ratios to floats currently encodes JVM behaviour that the profile's own law (nearest-even) contradicts; the cross-host P law ("equal arguments give equal results on every host") is violated between the JVM and any host using the kernel (JS and Dart arms: please check theirs). Suggested fix point: route the ratio arm through ratio->float on the JVM (number.cljc about 206-208 and 215-217) and add the tie case (2^53+3)/2^53 and 2/3 to table D. A behaviour change on the JVM, so it needs your Architect to name it (it would be a fifth named behaviour change in H2/H3 terms) and your ruling on whether Jing content hashes are affected (they should not be: Jing floats are Float64 carriers; check whether any Jing path converts a ratio).
- Jolt: we did NOT change jolt to mimic the JVM; the cases are marked a known JVM-side difference in our compare script (src/dev/jolt_profile_compare.cljc). If you fix the JVM arm, tell us and we remove the marker.

## Also (information)
- One more compare class: the arglists metadata of the ->Rational constructor differs (a jolt record fact, 1 case); no action.
- J1 on Linux x86_64 (shiva and jupiter, branch jolt-j1 at f6f3ca00): identical to macOS: bytes-test 98/98, json 54/54, compare differences identical.
- We still wait for your note on H2's merge; jolt-j1 will then be rebased onto master.
