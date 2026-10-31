#!/usr/bin/env python3 -I
"""Adversarial mutant driver for derive.cljc (slice 5b review, claude-fable-5-1).

Applies one exact-string mutant at a time to the source, runs the JVM fast
lane for derive-test (foreground), records which deftests FAIL/ERROR, then
restores the source from the verified backup and byte-compares.
"""
import re
import subprocess
import sys

SRC = "src/cljc/yin/vm/semantic_register/derive.cljc"
BAK = "collab/p5b-fable-derive.verified-backup.cljc"
CMD = ["clojure", "-M:test", "-e", ":slow",
       "-n", "yin.vm.semantic-register.derive-test"]

MUTANTS = [
    ("M01 expiry < -> <=",
     "#(< (nth % 2) pc)", "#(<= (nth % 2) pc)"),
    ("M02 highest-free instead of lowest-free",
     "(first free) next-slot", "(last free) next-slot"),
    ("M03 free set unsorted (#{} not sorted-set)",
     "free (sorted-set)", "free #{}"),
    ("M04 points from defs only (no L)",
     "(into (analysis/live a pc) (analysis/defs v pc))", "(analysis/defs v pc)"),
    ("M05 points from L only (no defs(p))",
     "(into (analysis/live a pc) (analysis/defs v pc))", "(analysis/live a pc)"),
    ("M06 start = first def only (no widening over layout L)",
     "[r (apply min (get points r)) (apply max (get points r))]",
     "[r (apply min (filter #(= r (code/rd (nth v %))) (get points r))) (apply max (get points r))]"),
    ("M07 end = last def (not widened to last use)",
     "[r (apply min (get points r)) (apply max (get points r))]",
     "[r (apply min (get points r)) (apply max (filter #(= r (code/rd (nth v %))) (get points r)))]"),
    ("M08 drop structural reservation",
     "(if missing 1 0)", "0"),
    ("M09 structural slot off by one (locals+peak+1)",
     ":structural (+ locals next-slot)", ":structural (+ locals next-slot 1)"),
    ("M10 locals = arity+1",
     "(count (nth (nth v owner) 2))", "(inc (count (nth (nth v owner) 2)))"),
    ("M11 physical register without locals offset",
     "(+ locals (get slots r))", "(get slots r)"),
    ("M12 body :end exclusive",
     ":start start :end (dec end)", ":start start :end end"),
    ("M13 body :start off by one",
     ":start start :end (dec end)", ":start (inc start) :end (dec end)"),
    ("M14 live never filled",
     "(rcode/body-liveness image bi)", "{}"),
    ("M15 live from allocator's own L(p+1) minus rd (wrong analysis)",
     "(rcode/body-liveness image bi)",
     "(let [{:keys [start end]} (nth bodies bi) {:keys [slots locals]} (nth allocations bi)] (into {} (keep (fn [pc] (when (contains? rcode/boundary-opcodes (first (nth rv pc))) [pc (vec (sort (map #(+ locals (get slots %)) (disj (analysis/live a (inc pc)) (code/rd (nth rv pc))))))]))) (range start end)))"),
    ("M16 live from L(p+1) but tail calls forced [] (alternative reading)",
     "(rcode/body-liveness image bi)",
     "(let [{:keys [start end]} (nth bodies bi) {:keys [slots locals]} (nth allocations bi)] (into {} (keep (fn [pc] (when (contains? rcode/boundary-opcodes (first (nth rv pc))) [pc (if (code/tail-call? (nth rv pc)) [] (vec (sort (map #(+ locals (get slots %)) (disj (analysis/live a (inc pc)) (code/rd (nth rv pc)))))))]))) (range start end)))"),
    ("M17 tail-call live non-empty",
     "(assoc-in image [:instructions pc\n                                         (rcode/live-slot-index (first (nth instructions pc)))] live)",
     "(assoc-in image [:instructions pc\n                                         (rcode/live-slot-index (first (nth instructions pc)))] (if (code/tail-call? (nth rv pc)) [0] live))"),
    ("M18 live not sorted (reversed)",
     "(rcode/live-slot-index (first (nth instructions pc)))] live)",
     "(rcode/live-slot-index (first (nth instructions pc)))] (vec (reverse live)))"),
    ("M19 :regs operands reversed (call/ffi-call args)",
     ":regs (mapv physical x)", ":regs (mapv physical (reverse x))"),
    ("M20 :ffi-call args reversed only",
     ":regs (mapv physical x)", ":regs (mapv physical (if (= op :ffi-call) (reverse x) x))"),
    ("M21 :stream-put stream/value swapped",
     ":reg (physical x)", ":reg (physical (if (= op :stream-put) (nth t (- 4 i)) x))"),
    ("M22 :call rd/operator swapped",
     ":reg (physical x)", ":reg (physical (if (and (= op :call) (< i 2)) (nth t (- 2 i)) x))"),
    ("M23 :define rd := rs",
     ":reg (physical x)", ":reg (physical (if (and (= op :define) (= i 0)) (nth t 3) x))"),
    ("M24 :resume value-reg +1",
     ":reg (physical x)", ":reg (if (= op :resume) (inc (physical x)) (physical x))"),
    ("M25 :park/:current-continuation rd +1",
     ":reg (physical x)", ":reg (if (contains? #{:park :current-continuation} op) (inc (physical x)) (physical x))"),
    ("M26 closure arity -1 (resolve)",
     ":closure [:closure rd (count x) y]", ":closure [:closure rd (dec (count x)) y]"),
    ("M27 definition-less id -> slot locals+0 instead of structural",
     "(if (contains? slots r) (+ locals (get slots r)) structural)", "(+ locals (get slots r 0))"),
    ("M28 active filter > (expired entries dropped without freeing)",
     "(filterv #(>= (nth % 2) pc) active)", "(filterv #(> (nth % 2) pc) active)"),
    ("M29 next-slot always advances",
     "(if (seq free) next-slot (inc next-slot))", "(inc next-slot)"),
    ("M30 freed slot not removed from free set",
     "[(disj free i) (if (seq free)", "[free (if (seq free)"),
    ("M31 assignment order at one pc: decreasing id",
     "(sort defined))", "(reverse (sort defined)))"),
    ("M32 expiry sort removed",
     "(sort-by (juxt #(nth % 2) first)\n                               (filter #(< (nth % 2) pc) active))",
     "(filter #(< (nth % 2) pc) active)"),
    ("M33 registers = locals+peak (structural counted) but structural slot = peak-1 (aliases last temp)",
     ":structural (+ locals next-slot)", ":structural (+ locals (max 0 (dec next-slot)))"),
]


def run(selected):
    original = open(BAK, "rb").read()
    assert open(SRC, "rb").read() == original, "source != backup before start"
    rows = []
    for name, old, new in MUTANTS:
        if selected and not any(s in name for s in selected):
            continue
        text = original.decode()
        assert text.count(old) >= 1, f"pattern not found for {name}"
        mutated = text.replace(old, new, 1)
        open(SRC, "w").write(mutated)
        try:
            p = subprocess.run(CMD, capture_output=True, text=True, timeout=900)
            out = p.stdout + p.stderr
            fails = sorted(set(re.findall(r"^(?:FAIL|ERROR) in \(([^)]+)\)", out, re.M)))
            summary = re.search(r"Ran (\d+) tests containing (\d+) assertions\.\n(\d+) failures, (\d+) errors", out)
            summ = summary.groups() if summary else ("?", "?", "?", "?")
            if not summary:
                # compile error etc.
                tail = out.strip().splitlines()[-3:]
                summ = ("compile/other error:", " | ".join(tail), "", "")
        finally:
            open(SRC, "wb").write(original)
            assert open(SRC, "rb").read() == original, "RESTORE FAILED"
        verdict = "KILLED" if (summ[2] not in ("0", "") or summ[3] not in ("0", "")) else "SURVIVED"
        rows.append((name, verdict, summ, fails))
        print(f"{name:75s} {verdict:9s} fail={summ[2]} err={summ[3]} tests={summ[0]} assertions={summ[1]} by={fails}", flush=True)
    return rows


if __name__ == "__main__":
    run(sys.argv[1:])
