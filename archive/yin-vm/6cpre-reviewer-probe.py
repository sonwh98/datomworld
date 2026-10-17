import subprocess, pathlib, sys
ROOT = pathlib.Path('/Users/sto/workspace/datomworld-srvm-p6cpre')
cp = subprocess.run(['clojure', '-Spath', '-A:test'], cwd=ROOT, check=True,
                    stdout=subprocess.PIPE).stdout.decode().strip()
r = subprocess.run(['clojure', '-Scp', cp, '-M', '-i', 'collab/6cpre-reviewer-handoff-repro.clj',
                    '-e', '(println :done)'], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
text = r.stdout.decode(errors='replace')
(ROOT / 'collab/6cpre-reviewer-handoff-repro.log').write_text(text)
print('\n'.join(l for l in text.splitlines() if 'WARNING' not in l and 'Timbre' not in l))
print('exit', r.returncode)
s = subprocess.run(['cljstyle', 'check', 'src/cljc/yin/vm/debruijn/stack.cljc',
                    'test/yin/vm/debruijn/stack_tail_completion_test.cljc'],
                   cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
print('cljstyle exit', s.returncode, s.stdout.decode(errors='replace'))
