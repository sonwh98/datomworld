#!/usr/bin/env python3 -I
"""Round-3 reviewer mutation harness for rows.cljc.

Each mutant: verify source == verified backup, apply the replacement(s) (each
must match exactly once), run ONE named test in the foreground under a 256 MiB
heap, restore from the backup, verify cmp, record. Never touches git.
"""
import subprocess, sys, os, json, time, filecmp, re

WT = "/Users/sto/workspace/datomworld-srvm-p6a"
SRC = os.path.join(WT, "src/cljc/yin/vm/ucf/rows.cljc")
BAK = os.path.join(WT, "collab/6a-r3-reviewer-backups/rows.cljc")
NS = "yin.vm.ucf.rows-test"

ENC_CHECK = "  [d x numbering rows]\n  (when-not (or (ref? d) (refs? d) (= :C d))"
DEC_CHECK = "               [d x] (map vector (descriptors kind slots) slots)]\n         (when-not (or (ref? d) (refs? d) (= :C d))"

MUTANTS = [
    ("X01", "drop guest/explicit distinction: default value-node classifies marker shapes as references (round-2 code)",
     [("(defn- value-node\n  [x]\n  (check-literal-markers! x)\n  [:lit x])",
       "(defn- value-node\n  [x]\n  (let [r (:yin.k/row x)]\n    (cond\n      (not (marker-shaped? x)) [:lit x]\n"
       "      (and (map? r) (= #{:kind :slots} (set (keys r))) (keyword? (:kind r)) (vector? (:slots r))) [:row r]\n"
       "      (keyword? (:yin.k/cell x)) [:cell (:yin.k/cell x)]\n      :else (refuse! :yin.k/non-portable :marker []))))")],
     "well-formed-guest-markers-are-refused"),
    ("X02", "skip marker refusal for :store rows only (encode and decode)",
     [("(let [[x' rs'] (emit-slot d x numbering rs)] [(conj out x') rs'])",
       "(let [[x' rs'] (if (= :store kind) [x rs] (emit-slot d x numbering rs))] [(conj out x') rs'])"),
      (DEC_CHECK, DEC_CHECK[:-1] + " (= :store kind))")],
     "every-literal-context-refuses-markers"),
    ("X03", "skip marker refusal in metadata",
     [("(check-literal-markers! v)))\n  (when (meta x) (check-literal-markers! (meta x))))",
       "(check-literal-markers! v))))")],
     "every-literal-context-refuses-markers"),
    ("X04", "skip marker refusal inside nested collections",
     [("  (when (coll? x)\n    (doseq [v (if (map? x) (mapcat identity x) x)] (check-literal-markers! v)))",
       "  (when false\n    (doseq [v (if (map? x) (mapcat identity x) x)] (check-literal-markers! v)))")],
     "every-literal-context-refuses-markers"),
    ("X05", "skip marker refusal for :optional-D slots (custody) on encode and decode",
     [(ENC_CHECK, ENC_CHECK[:-1] + " (= :optional-D d))"),
      (DEC_CHECK, DEC_CHECK[:-1] + " (= :optional-D d))")],
     "every-literal-context-refuses-markers"),
    ("X06", "collapse equality tags: guest literals untagged",
     [(":lit (literal-value (second v))", ":lit (second v)")],
     "cell-and-identity-shaped-literal-are-distinct"),
    ("X07", "literal vector and literal list tagged differently (breaks the genuine duplicate)",
     [("(sequential? x) [:seq (mapv literal-value x)]",
       "(vector? x) [:seq (mapv literal-value x)]\n              (sequential? x) [:list (mapv literal-value x)]")],
     "schema-and-literal-canonicality"),
    ("X08", "remove preflight from checked-encode",
     [("checked-encode (fn [x] (check-label-bytes! x budget) (cbor/encode x))", "checked-encode (fn [x] (cbor/encode x))")],
     "intermediate-label-bytes-are-bounded"),
    ("X09", "remove preflight from checked-key",
     [("checked-key (fn [x] (check-label-bytes! x budget) (bytes-key x))", "checked-key (fn [x] (bytes-key x))")],
     "intermediate-label-bytes-are-bounded"),
    ("X10", "remove preflight doseq from checked-sort",
     [("                        (doseq [x xs] (check-label-bytes! x budget))\n", "")],
     "intermediate-label-bytes-are-bounded"),
    ("X11", "check-label-bytes! never refuses",
     [("                (when (> n remaining)\n                  (refuse! :yin.k/unsupported :resource-limit [:label-bytes]))",
       "                nil")],
     "intermediate-label-bytes-are-bounded"),
    ("X12", "default :label-bytes 1",
     [(":label-bytes 1048576}", ":label-bytes 1}")],
     "canonical-roots-are-pinned"),
    ("X13", "decode-side marker loop removed only (emit side kept)",
     [(DEC_CHECK, DEC_CHECK.replace("(when-not (or (ref? d) (refs? d) (= :C d))", "(when false"))],
     "every-literal-context-refuses-markers"),
    ("X14", "emit-side marker check removed only (decode side kept)",
     [(ENC_CHECK, ENC_CHECK.replace("(when-not (or (ref? d) (refs? d) (= :C d))", "(when false"))],
     "every-literal-context-refuses-markers"),
    ("X15", "decoded-value no longer refuses marker-shaped :lit",
     [(":lit (if (marker-shaped? (second v)) (refuse! :yin.k/non-portable :marker []) (second v))", ":lit (second v)")],
     "marker-shaped-guest-maps-are-not-portable"),
]

only = set(sys.argv[1:])
results = []
for mid, desc, reps, test in MUTANTS:
    if only and mid not in only:
        continue
    assert filecmp.cmp(SRC, BAK, shallow=False), "source differs from backup before " + mid
    src = open(SRC).read()
    for old, new in reps:
        assert src.count(old) == 1, (mid, "pattern count", src.count(old), old[:80])
        src = src.replace(old, new)
    log_path = os.path.join(WT, "collab", "6a-r3-reviewer-%s.log" % mid)
    cmd = ["clojure", "-J-Xmx256m", "-M:test", "-n", NS, "-v", NS + "/" + test]
    t0 = time.time()
    try:
        with open(SRC, "w") as f:
            f.write(src)
        with open(log_path, "w") as log:
            log.write("# %s: %s\n$ %s\n" % (mid, desc, " ".join(cmd)))
            log.flush()
            try:
                p = subprocess.run(cmd, cwd=WT, stdout=log, stderr=subprocess.STDOUT, timeout=600)
                rc = p.returncode
            except subprocess.TimeoutExpired:
                rc = "TIMEOUT"
    finally:
        with open(BAK, "rb") as b, open(SRC, "wb") as s:
            s.write(b.read())
    restored = filecmp.cmp(SRC, BAK, shallow=False)
    git = subprocess.run(["git", "diff", "--quiet", "HEAD", "--", "src/cljc/yin/vm/ucf/rows.cljc"], cwd=WT).returncode
    text = open(log_path).read()
    m = re.search(r"Ran (\d+) tests containing (\d+) assertions\.\s*(\d+) failures, (\d+) errors", text)
    counts = m.groups() if m else None
    oom = "OutOfMemoryError" in text
    killed = (rc != 0)
    results.append(dict(id=mid, desc=desc, test=test, exit=rc, counts=counts, oom=oom,
                        verdict="KILLED" if killed else "SURVIVED", seconds=round(time.time() - t0, 1),
                        restored_cmp=restored, git_clean=(git == 0)))
    print("%s %-8s exit=%s counts=%s oom=%s %.0fs restored=%s git_clean=%s  %s" % (
        mid, results[-1]["verdict"], rc, counts, oom, time.time() - t0, restored, git == 0, test))
    assert restored and git == 0, "RESTORE FAILED " + mid

with open(os.path.join(WT, "collab", "6a-r3-reviewer-mutants.json"), "a") as f:
    f.write(json.dumps(results, indent=1) + "\n")
