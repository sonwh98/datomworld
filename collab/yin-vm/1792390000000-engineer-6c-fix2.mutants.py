#!/usr/bin/env python3
"""Fix round 2 mutants: apply one, run the quick tier, restore, verify."""
import re, shutil, subprocess, sys

L = 'src/cljc/yin/vm/semantic_register/layout.cljc'
T = 'test/yin/vm/semantic_register/layout_test.cljc'
H = 'test/yin/vm/semantic_register/safepoint_harness_test.cljc'

MUTANTS = {
    'N1-closures-file-wide': (T,
        "        (h/scoped\n          (fn []\n            (let [sr (:machine",
        "        (h/file-wide\n          (fn []\n            (let [sr (:machine"),
    'N1-kernel-space-global': (H,
        "(map segment-of (vals @*projected*))",
        "(map segment-of (vals @code-index))"),
    'N2-no-injectivity': (H,
        "  (when-not (= (count names) (count (distinct (vals names))))\n",
        "  (when-not true\n"),
    'N2-no-descriptor-guard': (H,
        "    (when-not (descriptors-equivalent? (mapv :descriptors roots))\n",
        "    (when-not true\n"),
    'N3-top-level-only': (L,
        "(:vec :list :set :map) (some (fn [[i y]] (row-procedure y (conj path i))) (map-indexed vector (:slots n)))",
        "(:vec :list :set :map) nil"),
    'N4-swapped-images': (L,
        "                  (if (= :stack kind)\n                    (dcode/image-hash",
        "                  (if (not= :stack kind)\n                    (dcode/image-hash"),
}

NS = ['-n', 'yin.vm.semantic-register.layout-test',
      '-n', 'yin.vm.semantic-register.safepoint-harness-test',
      '-n', 'yin.vm.store-write-audit-test']

for name in sys.argv[1:] or MUTANTS:
    path, old, new = MUTANTS[name]
    backup = '/tmp/fix2-' + name + '.bak'
    shutil.copy2(path, backup)
    src = open(path).read()
    assert src.count(old) == 1, (name, src.count(old))
    open(path, 'w').write(src.replace(old, new))
    try:
        out = subprocess.run(['clojure', '-M:test', '-e', ':slow'] + NS,
                             capture_output=True, text=True).stdout
    finally:
        shutil.copy2(backup, path)
    same = subprocess.run(['diff', '-q', backup, path]).returncode == 0
    clean = subprocess.run(['git', 'diff', '--quiet', '--', path]).returncode
    ran = re.findall(r'Ran \d+ tests containing \d+ assertions\.\n\d+ failures, \d+ errors\.', out)
    fails = sorted(set(re.findall(r'^(?:FAIL|ERROR) in \((\S+)\)', out, re.M)))
    print(name, '|', ' '.join(ran).replace('\n', ' '), '|', fails, '| restored:', same,
          '| git-diff-quiet:', 'clean' if clean == 0 else 'tracked-changes',
          '| :ambiguous-image in output:', ':ambiguous-image' in out)
