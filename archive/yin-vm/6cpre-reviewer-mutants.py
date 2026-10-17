import pathlib, subprocess, re, sys

ROOT = pathlib.Path('/Users/sto/workspace/datomworld-srvm-p6cpre')
SRC = ROOT / 'src/cljc/yin/vm/debruijn/stack.cljc'
BACKUP = ROOT / 'collab/6cpre-reviewer-stack.backup.cljc'
SUMMARY = ROOT / 'collab/6cpre-reviewer-mutants.log'
NSS = ['yin.vm.debruijn.stack-tail-completion-test',
       'yin.vm.debruijn.stack-test',
       'yin.vm.debruijn.stack-effects-test',
       'yin.vm.debruijn.stack-parity-test',
       'yin.vm.semantic-ffi-test',
       'yin.vm.continuation-invoke-test']
CMD = ['clojure', '-M:test']
for ns in NSS:
    CMD += ['-n', ns]

subprocess.run(['cmp', str(SRC), str(BACKUP)], check=True)
original = BACKUP.read_bytes().decode()

COND = "(and (= :call (first site)) (true? (nth site 2)))"
RT_STACK = "(conj (subvec (:stack vm) 0 (:stack-base frame)) val)"
HALT = "(assoc vm :halted? true :stack [val] :value val :store-of nil)"

mutants = [
 ('M01 marker omitted', COND, 'false'),
 ('M02 marker on every :call (non-tail too)', COND, '(= :call (first site))'),
 ('M03 marker on every effect park', COND, 'true'),
 ('M04 frame not popped', ':continuation (pop (:continuation vm))', ':continuation (:continuation vm)'),
 ('M05 base not truncated', RT_STACK, '(conj (:stack vm) val)'),
 ('M06 truncated to outermost base', RT_STACK, '(conj (subvec (:stack vm) 0 (:stack-base (first (:continuation vm)))) val)'),
 ('M07 value not pushed', RT_STACK, '(subvec (:stack vm) 0 (:stack-base frame))'),
 ('M08 empty-K halt value from entry stack, not val', HALT, '(assoc vm :halted? true :stack [(peek (:stack vm))] :value (peek (:stack vm)) :store-of nil)'),
 ('M09 empty-K halt keeps store context', HALT, '(assoc vm :halted? true :stack [val] :value val)'),
 ('M10 delivered twice', '(return-transition restored val)', '(update (return-transition restored val) :stack conj val)'),
 ('M11 retained re-park skipped', '(if (:request-sent entry)', '(if false'),
 ('M12 retained re-park drops the marker', '(ffi/response-wait-entry entry (:call-id entry))', '(dissoc (ffi/response-wait-entry entry (:call-id entry)) :resume-mode)'),
 ('M13 response decoding skipped', '(if call-id (ffi/call-result val call-id) val)', 'val'),
 ('M14 tail resumes at pc by push (layout runs)', '(return-transition restored val)', '(assoc restored :stack (conj (:stack restored) val))'),
 ('M15 caller frames not restored', ':frames (:frames frame)', ':frames (:frames vm)'),
 ('M16 site read at advanced pc', '(nth (:segment vm) (:pc vm))', "(nth (:segment vm) pc')"),
 ('M17 parked call not removed', '(update base :parked dissoc call-id)', 'base'),
 ('M18 halted? not cleared on restore', ':value val\n                          :halted? false)]', ':value val)]'),
]

out = []
try:
    for i, (name, a, b) in enumerate(mutants, 1):
        assert original.count(a) == 1, (name, original.count(a))
        SRC.write_text(original.replace(a, b, 1))
        log = ROOT / f'collab/6cpre-reviewer-mutant-{i:02d}.log'
        try:
            r = subprocess.run(CMD, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=900)
            text = r.stdout.decode(errors='replace'); rc = r.returncode
        except subprocess.TimeoutExpired as e:
            text = (e.stdout or b'').decode(errors='replace') + '\n### TIMEOUT 900s ###\n'; rc = 'TIMEOUT'
        log.write_text(text)
        names = sorted(set(re.findall(r'^(?:FAIL|ERROR) in \(([^)]+)\)', text, re.M)))
        ran = re.search(r'^Ran .*$', text, re.M)
        tot = re.search(r'^\d+ failures, \d+ errors\.$', text, re.M)
        status = 'KILLED' if (names or rc != 0) else 'SURVIVED'
        line = f'{name} | exit {rc} | {status} | {ran.group(0) if ran else "no Ran line"} | {tot.group(0) if tot else ""} | {", ".join(names)}'
        print(line, flush=True); out.append(line)
        SRC.write_bytes(BACKUP.read_bytes())
        subprocess.run(['cmp', str(SRC), str(BACKUP)], check=True)
        subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--', 'src/'], cwd=ROOT, check=True)
        out.append(f'   restored: cmp ok, git diff --quiet HEAD -- src/ ok')
finally:
    SRC.write_bytes(BACKUP.read_bytes())
    subprocess.run(['cmp', str(SRC), str(BACKUP)], check=True)
    SUMMARY.write_text('\n'.join(out) + '\n')
print('DONE', flush=True)
