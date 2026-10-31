#!/usr/bin/env python3 -I
"""Reviewer mutation harness for slice 6a. Foreground only, sequential.
Each mutant: assert the needle occurs exactly once, apply, run one named
test (literal -n / -v), capture summary, restore from the reviewer
backup, verify with cmp and git diff --quiet."""
import json, os, re, subprocess, sys, time

ROOT = "/Users/sto/workspace/datomworld-srvm-p6a"
FRAME = "src/cljc/yin/vm/semantic_register/frame.cljc"
ROWS = "src/cljc/yin/vm/ucf/rows.cljc"
BACKUP = {FRAME: "collab/6a-reviewer-backups/frame.cljc",
          ROWS: "collab/6a-reviewer-backups/rows.cljc"}
FT = "yin.vm.semantic-register.frame-test"
RT = "yin.vm.ucf.rows-test"

PROBE = open(os.path.join(ROOT, "collab/6a-reviewer-probe.cljc"), encoding="utf-8").read()
MUTANTS = [
 # id, file, needle, replacement, test ns, test var, description
 ("P00", ROWS, "        (captures task [])))))\n", "        (captures task [])))))\n" + PROBE, RT, "row-ids-are-jing-default", "placeholder-skip"),
 ("R01", FRAME, "(some #(not (contains? w %)) wanted) (problem (kind \"window-missing\") p)",
  "false (problem (kind \"window-missing\") p)", FT, "nil-valued-register-is-a-member", "A6 window-missing dropped"),
 ("R02", FRAME,
  "      (when-not (vector? k) (problem :k-shape [:yin.k/k]))\n      (some (fn [[f p]]\n              (when-not (and (closed? f [:yin.k/frame-type :yin.k/segment :yin.k/pc\n                                         :yin.k/env :yin.k/window :yin.k/rd] [])\n                             (= :return (:yin.k/frame-type f)))\n                (problem :k-shape p))) returns)\n      (some (fn [[f p]]\n              (when-not (and (map? (:yin.k/window f))\n                             (every? code/reg? (keys (:yin.k/window f))))\n                (problem :window-representation (conj p :yin.k/window)))) envs)\n",
  "      (some (fn [[f p]]\n              (when-not (and (map? (:yin.k/window f))\n                             (every? code/reg? (keys (:yin.k/window f))))\n                (problem :window-representation (conj p :yin.k/window)))) envs)\n      (when-not (vector? k) (problem :k-shape [:yin.k/k]))\n      (some (fn [[f p]]\n              (when-not (and (closed? f [:yin.k/frame-type :yin.k/segment :yin.k/pc\n                                         :yin.k/env :yin.k/window :yin.k/rd] [])\n                             (= :return (:yin.k/frame-type f)))\n                (problem :k-shape p))) returns)\n",
  FT, "first-defect-is-structural", "S2 and S3 reordered (window before K)"),
 ("R03", FRAME, "(when-not (and (every? #(contains? bindings %) names)", "(when-not (and true", FT, "free-env-mismatch", "E2 lexical presence not required"),
 ("R04", FRAME, "      (some vm/reserved-name? (keys e))\n      (problem :reserved-name [:free-env]))))", "      false\n      (problem :reserved-name [:free-env]))))", FT, "free-env-mismatch", "X1 reserved names unchecked"),
 ("R05", FRAME, "(and (contains? (:ffi-call-ids task) (:parked-key task))", "(and true", FT, "tail-bookkeeping-and-delivery", "P4 bookkeeping key membership dropped"),
 ("R06", FRAME, "      (when-not (vector? k) (problem :k-shape [:yin.k/k]))\n", "", FT, "each-rule-refuses-its-tamper", "S2 non-vector K accepted"),
 ("R07", FRAME, "(or (= slot :yin.k/site) (pos? n))", "true", FT, "each-rule-refuses-its-tamper", "C2 pc>0 lower bound dropped"),
 ("R08", FRAME, "                (when-not (= (code/body-index bs (dec pc)) (code/body-index bs pc))\n", "                (when-not true\n", FT, "each-rule-refuses-its-tamper", "C3 pc-body unchecked"),
 ("R09", FRAME, "(contains? w rd) (problem (kind \"window-self\") p)", "false (problem (kind \"window-self\") p)", FT, "worked-example-saved", "A4 window-self dropped"),
 ("R10", FRAME,
  "      (some #(not (contains? wanted %)) ks) (problem (kind \"window-extra\") p)\n      (some #(not (contains? w %)) wanted) (problem (kind \"window-missing\") p))))",
  "      (some #(not (contains? w %)) wanted) (problem (kind \"window-missing\") p)\n      (some #(not (contains? wanted %)) ks) (problem (kind \"window-extra\") p))))",
  FT, "each-rule-refuses-its-tamper", "A5/A6 order swapped"),
 ("R11", FRAME, "                  (when (some vm/reserved-name? (keys (get-in f [:yin.k/env :yin.k/bindings])))\n", "                  (when false\n", FT, "each-rule-refuses-its-tamper", "E1 reserved binding unchecked"),
 ("R12", FRAME, "                            #{})]", "                            #{:next})]", FT, "each-rule-refuses-its-tamper", "P2 :park/:current-continuation admit :next"),
 ("R13", FRAME, "(when-not (= rd (:yin.k/rd d)) (problem :destination [:yin.k/deliver :yin.k/rd]))", "nil", FT, "each-rule-refuses-its-tamper", "A2 destination equality dropped"),
 ("R14", FRAME, "(when-not (and (= :call (first fi)) (not (code/tail-call? fi)))", "(when-not true", FT, "each-rule-refuses-its-tamper", "K1 return-site unchecked"),
 ("R15", ROWS, "body (into [kind] slots) id (jing/segment-key body)]", "body (into [kind] slots) id (jing/segment-key (into [kind] (rest slots)))]", RT, "row-ids-are-jing-default", "row id hashed without its first child slot"),
 ("R16", ROWS, "(when-not (= (set (keys cells)) @reached) (bad! :extra-cell (conj path 9)))", "nil", RT, "schema-and-literal-canonicality", "census extra cell not refused"),
 ("R17", ROWS, "(if (or (nil? best) (neg? (compare-bytes (:key leaf) (:key best)))) leaf best)", "(if (nil? best) leaf best)", RT, "canonical-under-is-renaming-invariant", "search keeps first leaf (input-order tie break)"),
 ("R18", ROWS, "(let [[k v] (first x)] [k v]) (filter (fn [[k v]] (some? v)) m)", "", RT, "bijection", "placeholder-skip"),
 ("R19", ROWS, "  (node kind (mapcat (fn [[k v]] [k (encode v)]) m)))", "  (node kind (mapcat (fn [[k v]] (when (some? v) [k (encode v)])) m)))", RT, "bijection", "window nil-valued key dropped on encode"),
 ("R20", ROWS, "    :lit (second v)))", "    :lit (second v)))\n\n(def ^:private _unused nil)", RT, "bijection", "placeholder-skip"),
 ("R21", ROWS, "[(first members) (keyword \"yin.k\" (str \"c-\" (inc i)))]) cs))", "[(first members) (keyword \"yin.k\" (str \"c-\" (- (count cs) i)))]) cs))", RT, "canonical-under-is-renaming-invariant", "numbering reversed (two cells swapped)"),
 ("R22", ROWS, "     (spend! counter budget :rows (count rs))\n", "", RT, "schema-and-literal-canonicality", "rows budget charge removed"),
 ("R23", ROWS, "body (into [kind] slots) id (jing/segment-key body)]", "body (into [kind] slots) id (jing/segment-key body {:algorithm :sha256})]", RT, "row-ids-are-jing-default", "digest changed to sha256"),
 ("R24", ROWS, "(when-not (and (= #{:yin.k/handoff :yin.k/version :yin.k/root :yin.k/rows} (set (keys wire)))\n                    (vector? rs)", "(when-not (and true\n                    (vector? rs)", RT, "gates-and-graph-before-hashes", "wire extra key accepted"),
 ("R25", ROWS,
  "        (when-let [d (frame/task-defect ctx)] (assoc d :yin.k/status :yin.k/undecodable))\n        (some (fn [[i w]] (judge w :wait ctx [:frames i])) (map-indexed vector waits))\n",
  "        (some (fn [[i w]] (judge w :wait ctx [:frames i])) (map-indexed vector waits))\n        (when-let [d (frame/task-defect ctx)] (assoc d :yin.k/status :yin.k/undecodable))\n",
  RT, "numbering-precedes-frame-defect", "X1 judged after waits"),
 ("R26", ROWS, "    (= kind :set) (sorted-bytes slots)\n    :else slots))", "    :else slots))", RT, "canonical-under-is-renaming-invariant", "set slots not sorted on emit"),
 ("R27", ROWS, "         (when-not (= (vec slots) (ordered-slots kind (vec slots))) (bad! :row-order [id]))\n", "", RT, "window-sorted-and-unique", "row-order check removed"),
 ("R28", ROWS, "(when (and (not= false (:check-numbering budget)) (not= root (:yin.k/root canonical)))", "(when false", RT, "numbering-precedes-frame-defect", "noncanonical-cells comparison removed"),
 ("R29", ROWS, "                 (when (contains? active id) (bad! :cyclic [id]))\n                 (let [row (get index id) slots (when row (subvec row 2))]", "                 (let [row (get index id) slots (when row (subvec row 2))]", RT, "child-scope-and-shared-child-dag", "gate cycle check removed"),
 ("R30", ROWS, "(when (code/well-formed? v) (bad! :code [id 11 address]))", "nil", RT, "task-root-checks-carried-code-and-frame", "carried code well-formedness unchecked"),
 ("R31", ROWS, "  (into {} (map (fn [[k v]] [k (decode-value v)])) (partition 2 (:slots n))))", "  (into {} (keep (fn [[k v]] (let [x (decode-value v)] (when (some? x) [k x])))) (partition 2 (:slots n))))", RT, "bijection", "[:lit nil] decoded as absence (key dropped)"),
 ("R32", ROWS, "               (spend! counter budget :search 1)\n", "", RT, "refinement-search-preserves-unordered-multiplicity", "search bound removed"),
 ("R33", ROWS, "                        :lit (when-not (literal? (second x)) (bad! :literal p))", "                        :lit nil", RT, "schema-and-literal-canonicality", "non-portable literal accepted"),
 ("R34", ROWS, "                         (not (some reference? slots))) (bad! :reference-free-row path))", "                         false) (bad! :reference-free-row path))", RT, "schema-and-literal-canonicality", "reference-free collection row accepted"),
 ("R35", ROWS, "                  (when-not (= (count decoded) (count (distinct decoded))) (bad! :duplicate-key path))))", "                  nil))", RT, "schema-and-literal-canonicality", "decoded-equality duplicate key unchecked"),
 ("R36", ROWS, "                    (when-not (contains? correspondence id) (bad! :missing-stream-correspondence [id]))\n", "", RT, "canonical-under-is-renaming-invariant", "missing stream correspondence guessed"),
 ("R37", FRAME, "          (when (and (= :captured context) (not (and act? (= :current-continuation op))))\n            (problem :captured-site [(if act? :yin.k/pc :yin.k/site)]))", "          nil", FT, "each-rule-refuses-its-tamper", "P3 captured-site unchecked"),
 ("R38", FRAME, "(= (= :wait context) (contains? frame :yin.k/pending))", "true", FT, "each-rule-refuses-its-tamper", "P1 pending presence unchecked"),
 ("R39", FRAME, "              (when-not (code/tail-call? inst) (problem :site [:yin.k/site]))", "              nil", FT, "each-rule-refuses-its-tamper", "T1 tail site unchecked"),
 ("R40", FRAME, "(closed? frame required [:yin.k/pending])", "(map? frame)", FT, "each-rule-refuses-its-tamper", "S1 closed key set unchecked"),
 ("R41", FRAME, "(window-defect f fv frd \"return-\" p)", "nil", FT, "each-rule-refuses-its-tamper", "K3 return window unchecked"),
 ("R42", FRAME, "(when-not (= frd (code/rd fi)) (problem :return-destination (conj p :yin.k/rd)))", "nil", FT, "each-rule-refuses-its-tamper", "K2 return destination unchecked"),
 ("R43", FRAME, "              (when-not (contains? code-map (:yin.k/segment f))\n", "              (when-not true\n", FT, "each-rule-refuses-its-tamper", "C1 segment membership unchecked"),
 ("R44", FRAME, "(every? code/reg? (keys (:yin.k/window f)))", "true", FT, "each-rule-refuses-its-tamper", "S3 window keys not required to be registers"),
 ("R45", ROWS, "                        (= id (jing/segment-key (vec body)))", "                        true", RT, "window-sorted-and-unique", "row hash equality unchecked on decode"),
 ("R46", ROWS, "                               (when (and (or (ref? d) (refs? d))\n                                          (not (contains? (set (rest d)) (second (get index target)))))\n                                 (bad! :reference-kind [id i]))\n", "", RT, "schema-and-literal-canonicality", "structural reference kind unchecked"),
]

def sh(args, cwd=ROOT):
    p = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr

def main(only=None):
    # Refuse to mutate unless every backup exists and is byte-identical to the source now.
    for path, backup in BACKUP.items():
        if not os.path.exists(os.path.join(ROOT, backup)):
            sys.exit(f"missing backup {backup}")
        c, out = sh(["cmp", path, backup])
        if c != 0:
            sys.exit(f"backup differs from source: {backup}\n{out}")
        c, _ = sh(["git", "diff", "--quiet", "HEAD", "--", path])
        if c != 0:
            sys.exit(f"source differs from HEAD before mutation: {path}")
    results = []
    for mid, path, needle, repl, ns, var, desc in MUTANTS:
        if desc == "placeholder-skip":
            continue
        if only and mid not in only:
            continue
        full = os.path.join(ROOT, path)
        with open(full, encoding="utf-8") as f:
            src = f.read()
        n = src.count(needle)
        rec = {"id": mid, "file": path, "description": desc, "test": f"{ns}/{var}"}
        if n != 1:
            rec["status"] = f"NEEDLE-COUNT-{n}"
            results.append(rec); print(json.dumps(rec)); continue
        mutated = src.replace(needle, repl)
        try:
            with open(full, "w", encoding="utf-8") as f:
                f.write(mutated)
            argv = ["clojure", "-M:test", "-n", ns, "-v", f"{ns}/{var}"]
            t0 = time.time()
            code, out = sh(argv)
            rec["argv"] = argv
            rec["exit"] = code
            rec["seconds"] = round(time.time() - t0, 1)
            m = re.search(r"Ran (\d+) tests containing (\d+) assertions\.\n(\d+) failures, (\d+) errors", out)
            if m:
                rec["tests"], rec["assertions"], rec["failures"], rec["errors"] = map(int, m.groups())
            else:
                rec["tail"] = out[-1500:]
            fails = [l for l in out.splitlines() if l.startswith("FAIL in") or l.startswith("ERROR in")]
            rec["fail_lines"] = fails[:6]
            killed = code != 0
            rec["status"] = "KILLED" if killed else "SURVIVED"
            with open(os.path.join(ROOT, "collab", f"6a-reviewer-{mid}.log"), "w") as lf:
                lf.write(" ".join(argv) + "\n" + out)
        finally:
            with open(os.path.join(ROOT, BACKUP[path]), encoding="utf-8") as b:
                orig = b.read()
            with open(full, "w", encoding="utf-8") as f:
                f.write(orig)
            c1, _ = sh(["cmp", path, BACKUP[path]])
            c2, _ = sh(["git", "diff", "--quiet", "HEAD", "--", path])
            rec["restore_cmp"] = c1
            rec["restore_git_diff"] = c2
        results.append(rec)
        print(json.dumps(rec), flush=True)
    with open(os.path.join(ROOT, "collab", "6a-reviewer-mutants.json"), "w") as f:
        json.dump(results, f, indent=1)

if __name__ == "__main__":
    main(set(sys.argv[1:]) or None)
