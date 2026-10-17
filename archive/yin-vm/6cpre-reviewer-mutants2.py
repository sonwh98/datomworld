import pathlib, subprocess, re, sys

ROOT = pathlib.Path('/Users/sto/workspace/datomworld-srvm-p6cpre')
SRC = ROOT / 'src/cljc/yin/vm/debruijn/stack.cljc'
BACKUP = ROOT / 'collab/6cpre-reviewer-stack.backup.cljc'
SUMMARY = ROOT / 'collab/6cpre-reviewer-mutants2.log'
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
subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--', 'src/'], cwd=ROOT, check=True)
original = BACKUP.read_bytes().decode()

SEAM = '(return-transition restored val)'

mutants = [
 ('N01 seam-only: frame not popped', SEAM,
  '(assoc (return-transition restored val) :continuation (:continuation restored))'),
 ('N02 seam-only: base not truncated', SEAM,
  '(assoc (return-transition restored val) :stack (conj (:stack restored) val))'),
 ('N03 seam-only: value not pushed', SEAM,
  '(update (return-transition restored val) :stack pop)'),
 ('N04 seam-only: empty-K halt delivers nil instead of val', SEAM,
  '(if (seq (:continuation restored)) (return-transition restored val) (return-transition restored nil))'),
 ('N05 seam-only: empty-K halt keeps the entry store-of', SEAM,
  '(if (seq (:continuation restored)) (return-transition restored val) (assoc (return-transition restored val) :store-of (:store-of restored)))'),
 ('N06 seam-only: delivered against base registers, not the entry', SEAM,
  '(return-transition (assoc base :value val :halted? false) val)'),
 ('N07 return-transition keeps vm store-of instead of the frame', ':store-of (:store-of frame))', ':store-of (:store-of vm))'),
 ('N08 seam-only: pc left at entry pc after the return transition', SEAM,
  '(assoc (return-transition restored val) :pc (:pc restored))'),
]

out = []
try:
    for i, (name, a, b) in enumerate(mutants, 1):
        assert original.count(a) == 1, (name, original.count(a))
        SRC.write_text(original.replace(a, b, 1))
        log = ROOT / f'collab/6cpre-reviewer-mutant2-{i:02d}.log'
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
        out.append('   restored: cmp ok, git diff --quiet HEAD -- src/ ok')
finally:
    SRC.write_bytes(BACKUP.read_bytes())
    subprocess.run(['cmp', str(SRC), str(BACKUP)], check=True)
    SUMMARY.write_text('\n'.join(out) + '\n')
print('DONE', flush=True)
