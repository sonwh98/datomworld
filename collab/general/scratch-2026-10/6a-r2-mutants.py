#!/usr/bin/env python3 -I
"""Round-2 mutation harness for slice 6a. Foreground, sequential.
Reuses the reviewer's surviving mutants (needles from 6a-reviewer-mutants.py)
and adds one revert-mutant per round-2 source fix. Each mutant: needle
occurs exactly once, apply, run one named test (literal -n / -v), restore
from collab/6a-r2-backups/, verify cmp == 0 and that `git diff HEAD -- src/`
still equals the saved authorized diff (git diff --quiet is recorded too;
it is 1 by construction because the round-2 source changes are uncommitted)."""
import json, os, re, subprocess, sys, time

ROOT = "/Users/sto/workspace/datomworld-srvm-p6a"
FRAME = "src/cljc/yin/vm/semantic_register/frame.cljc"
ROWS = "src/cljc/yin/vm/ucf/rows.cljc"
BACKUP = {FRAME: "collab/6a-r2-backups/frame.cljc", ROWS: "collab/6a-r2-backups/rows.cljc"}
DIFF = "collab/6a-r2-backups/src.diff"
FT = "yin.vm.semantic-register.frame-test"
RT = "yin.vm.ucf.rows-test"

sys.path.insert(0, os.path.join(ROOT, "collab"))
REV = {}
src = open(os.path.join(ROOT, "collab/6a-reviewer-mutants.py"), encoding="utf-8").read()
ns = {}
exec(compile(src.split("def sh(")[0].replace(
    'PROBE = open(os.path.join(ROOT, "collab/6a-reviewer-probe.cljc"), encoding="utf-8").read()', 'PROBE = ""'),
    "reviewer", "exec"), ns)
for m in ns["MUTANTS"]:
    REV[m[0]] = m

SURVIVORS = [
    ("R06", FT, "rule-boundaries-refuse-their-tamper"),
    ("R07", FT, "rule-boundaries-refuse-their-tamper"),
    ("R10", FT, "rule-boundaries-refuse-their-tamper"),
    ("R12", FT, "rule-boundaries-refuse-their-tamper"),
    ("R17", RT, "canonical-roots-are-pinned"),
    ("R21", RT, "canonical-roots-are-pinned"),
    ("R24", RT, "gates-and-graph-before-hashes"),
    ("R25", RT, "task-root-checks-carried-code-and-frame"),
    ("R26", RT, "unordered-containers-are-order-free"),
    ("R29", RT, "gates-and-graph-before-hashes"),
    ("R30", RT, "task-root-checks-carried-code-and-frame"),
    ("R33", RT, "schema-and-literal-canonicality"),
    ("R35", RT, "schema-and-literal-canonicality"),
    ("R45", RT, "gates-and-graph-before-hashes"),
    ("R46", RT, "schema-and-literal-canonicality"),
]

MUTANTS = []
for mid, tns, var in SURVIVORS:
    _, path, needle, repl, _, _, desc = REV[mid]
    MUTANTS.append((mid, path, needle, repl, tns, var, desc))
# R46's reference-kind assertion lives in gates-and-graph-before-hashes.
MUTANTS = [(m[0], m[1], m[2], m[3], m[4], "gates-and-graph-before-hashes" if m[0] == "R46" else m[5], m[6])
           for m in MUTANTS]

MUTANTS += [
    ("F2a", ROWS, "    (and (= :cells kind) (= :C d)) (and (keyword? x) (some? (re-matches census-key (name x))))\n", "",
     RT, "schema-and-literal-canonicality", "finding 2: census key spelling not validated"),
    ("F2b", ROWS, "(sort-by #(census-order (first %)) (partition 2 slots))", "(sort-by #(str (first %)) (partition 2 slots))",
     RT, "census-is-numeric-from-ten-cells", "finding 2/8: census sorted by key spelling, not by n"),
    ("F3", ROWS, "\n                                  (vector? (nth slots 12)) (every? keyword? (nth slots 12)))", ")",
     RT, "gates-and-graph-before-hashes", "finding 3: gate reads an unvalidated installs slot"),
    ("F6a", ROWS, "      :else (refuse! :yin.k/non-portable :marker []))))", "      :else [:lit x])))",
     RT, "marker-shaped-guest-maps-are-not-portable", "finding 6: malformed marker-shaped map encoded as a literal"),
    ("F6b", ROWS, "    :lit (if (marker-shaped? (second v)) (refuse! :yin.k/non-portable :marker []) (second v))))",
     "    :lit (second v)))", RT, "marker-shaped-guest-maps-are-not-portable", "finding 6: marker-shaped [:lit] decoded"),
    ("F7", FRAME, "       (or (not (contains? e :yin.k/store-of)) (some? (:yin.k/store-of e)))\n", "",
     FT, "rule-boundaries-refuse-their-tamper", "finding 7: present nil store-of accepted"),
]


def sh(args):
    p = subprocess.run(args, cwd=ROOT, capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def src_diff():
    return sh(["git", "diff", "HEAD", "--", "src/"])[1]


def main(only=None):
    saved = open(os.path.join(ROOT, DIFF), encoding="utf-8").read()
    for path, backup in BACKUP.items():
        if sh(["cmp", path, backup])[0] != 0:
            sys.exit(f"backup differs from source: {backup}")
    if src_diff() != saved:
        sys.exit("src/ diff differs from the saved authorized diff before mutation")
    results = []
    for mid, path, needle, repl, tns, var, desc in MUTANTS:
        if only and mid not in only:
            continue
        full = os.path.join(ROOT, path)
        text = open(full, encoding="utf-8").read()
        rec = {"id": mid, "file": path, "description": desc, "test": f"{tns}/{var}"}
        n = text.count(needle)
        if n != 1:
            rec["status"] = f"NEEDLE-COUNT-{n}"
            results.append(rec); print(json.dumps(rec), flush=True); continue
        try:
            with open(full, "w", encoding="utf-8") as f:
                f.write(text.replace(needle, repl))
            argv = ["clojure", "-M:test", "-n", tns, "-v", f"{tns}/{var}"]
            t0 = time.time()
            code, out = sh(argv)
            rec.update(argv=argv, exit=code, seconds=round(time.time() - t0, 1))
            m = re.search(r"Ran (\d+) tests containing (\d+) assertions\.\n(\d+) failures, (\d+) errors", out)
            if m:
                rec["tests"], rec["assertions"], rec["failures"], rec["errors"] = map(int, m.groups())
            else:
                rec["tail"] = out[-1500:]
            rec["fail_lines"] = [l for l in out.splitlines() if l.startswith(("FAIL in", "ERROR in"))][:6]
            rec["status"] = "KILLED" if code != 0 else "SURVIVED"
            with open(os.path.join(ROOT, "collab", f"6a-r2-{mid}.log"), "w") as lf:
                lf.write(" ".join(argv) + "\n" + out)
        finally:
            with open(os.path.join(ROOT, BACKUP[path]), encoding="utf-8") as b:
                orig = b.read()
            with open(full, "w", encoding="utf-8") as f:
                f.write(orig)
            rec["restore_cmp"] = sh(["cmp", path, BACKUP[path]])[0]
            rec["restore_src_diff_equals_saved"] = src_diff() == saved
            rec["git_diff_quiet_src"] = sh(["git", "diff", "--quiet", "HEAD", "--", "src/"])[0]
        results.append(rec)
        print(json.dumps({k: rec.get(k) for k in ("id", "status", "test", "tests", "assertions", "failures", "errors",
                                                   "restore_cmp", "restore_src_diff_equals_saved")}), flush=True)
    tag = "-" + "-".join(sorted(only)) if only else ""
    with open(os.path.join(ROOT, "collab", f"6a-r2-mutants{tag}.json"), "w") as f:
        json.dump(results, f, indent=1)


if __name__ == "__main__":
    main(set(sys.argv[1:]) or None)
