Created-GMT: 2026-10-02 22:40:00 GMT
Created-Local: 2026-10-03 05:40:00 +07 (+0700)
Coding-Agent: claude
Session-ID: 2f42188d-f1e5-4b5b-a212-4d0f2ee514f9 (resumed; your float-address mob session)

# Task: Two sign-off questions on the float-fix implementation

Role: Lead System Architect

The float-fix engineer implemented your converged ruling (with astra) — cross-host goldens are byte-identical on
JVM/Node/Dart, the carrier is inserted at the producer, the bridge covers vm.cljc/engine.cljc scalar gates AND the
de Bruijn executable-scalar gates your correction 2 did not name (it found Node's stack/register VMs refused
carrier-bearing images there, and fixed a -0.0 sign drop in both double-le-rà-hex copies). Lanes green. The
implementation tree: /Users/sto/workspace/datomworld-py-floatfix (branch yang-python-floatfix, uncommitted;
report collab/1790973728000-compiler-engineer-python-floatfix.claude-opus-5-5.stdout.log in the main tree).

TWO QUESTIONS need your ruling before it can land:

1. DICT-KEY NORMALIZATION (the engineer's flagged decision): it added data/numeric-key — a numeric dict/set key
   is the INTEGER when integral within ±(2^53-1), so 1, 1.0, True and -0.0/0 collapse to one key; otherwise the
   key is float64 content. Sign off, or correct? AND the interaction: C3 ruling 6 (C3-S2, in flight in another
   worktree) replaces numeric key normalization with reduced-rational DECIMAL-STRING keys. Does the engineer's
   interim numeric-key survive as the C3-S2 foundation (C3-S2 then stringifies over it), or should the floatfix
   landing drop numeric-key and leave keys untouched until C3-S2 lands? Rule the sequencing.
2. THE BRIDGE SCOPE: the engineer reports "generic yin code is not bridged — a float literal decoded from a row
   on JS evaluates as a carrier; only the Python path converts back through data/float-value". Is that an
   acceptable reading of your correction 2 (the named gates pass and values re-enter execution only through
   Python's seam), or must the bridge be universal (every consumer unwraps), in which case name the additional
   surfaces for this slice?

Read the implementation tree as needed. End with "Ruling:" — decisive, per question. Read-only; do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>
