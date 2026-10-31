import json, shutil, subprocess, filecmp

SRC = 'src/cljc/yin/vm/ucf/rows.cljc'
FIXED = 'collab/1792090000000-renamed-rows.cljc'
CMD = ['clojure', '-J-Xmx256m', '-M:test', '-n', 'yin.vm.ucf.rows-test']

MUTANTS = {
    # Round 3 'guest', respelled: the default classifier interprets well-formed
    # marker shapes again (drops the guest/explicit marker distinction).
    'guest': ('(check-literal-markers! x)\n  [:lit x])',
              '(cond (and (marker-shaped? x) (map? (:yin.k/row x))) [:row (:yin.k/row x)]\n'
              '        (and (marker-shaped? x) (keyword? (:yin.k/cursor x))) [:cursor (:yin.k/cursor x)]\n'
              '        :else (do (check-literal-markers! x) [:lit x])))'),
    # Round 3 'bytes': remove one label-bytes preflight check.
    'bytes': ('(when (> n remaining)', '(when false'),
    # Round 3 'equality': collapse equality tags (untagged guest literals).
    'equality': (':lit (literal-value (second v))', ':lit (second v)'),
    # Extra, weaker: only the scalar leaf tag is collapsed.
    'equality-leaf-only': ('              :else [:literal x]))', '              :else x))'),
    # Frame validation runs before the canonical-numbering check.
    'numbering-order': ('           (when (and (not= false (:check-numbering budget)) (not= root (:yin.k/root canonical)))\n'
                        '             (bad! :noncanonical-cursors [root]))\n'
                        '           (when (= :task (:kind task))\n'
                        '             (when-let [d (validate-frames task)]\n'
                        '               (throw (ex-info "Frame validation failed" (update d :yin.k/path #(into [root] %))))))\n',
                        '           (when (= :task (:kind task))\n'
                        '             (when-let [d (validate-frames task)]\n'
                        '               (throw (ex-info "Frame validation failed" (update d :yin.k/path #(into [root] %))))))\n'
                        '           (when (and (not= false (:check-numbering budget)) (not= root (:yin.k/root canonical)))\n'
                        '             (bad! :noncanonical-cursors [root]))\n'),
    # Census sorted in reverse numeric order.
    'census-reversed': ('(sort-by #(census-order (first %)) (partition 2 slots))',
                        '(sort-by #(census-order (first %)) #(compare %2 %1) (partition 2 slots))'),
}

shutil.copyfile(SRC, FIXED)
assert filecmp.cmp(SRC, FIXED, shallow=False)
original = open(FIXED, encoding='utf-8').read()
results = {}
for name, (old, new) in MUTANTS.items():
    assert filecmp.cmp(SRC, FIXED, shallow=False), 'source drifted before ' + name
    assert original.count(old) == 1, 'site not unique for ' + name
    log = 'collab/1792090000000-mutant-' + name + '.log'
    try:
        open(SRC, 'w', encoding='utf-8').write(original.replace(old, new))
        try:
            p = subprocess.run(CMD, capture_output=True, text=True, timeout=300)
            out, code = p.stdout + p.stderr, p.returncode
        except subprocess.TimeoutExpired as e:
            out, code = 'TIMEOUT 300s\n' + str(e.stdout or ''), 'timeout'
        open(log, 'w', encoding='utf-8').write(out)
    finally:
        shutil.copyfile(FIXED, SRC)
        assert filecmp.cmp(SRC, FIXED, shallow=False), 'restore failed after ' + name
    fails = [l for l in out.splitlines() if l.startswith(('FAIL in', 'ERROR in'))]
    summary = [l for l in out.splitlines() if l.startswith(('Ran ', '0 failures', ' failures')) or 'failures,' in l]
    results[name] = {'exit': code, 'failing': sorted(set(fails)), 'summary': summary, 'log': log}
    print(name, json.dumps(results[name], indent=1))
json.dump(results, open('collab/1792090000000-rename-mutants.json', 'w'), indent=1)
print('restored-identical', filecmp.cmp(SRC, FIXED, shallow=False))
