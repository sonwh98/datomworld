import subprocess

p = subprocess.run(['clojure', '-J-Xmx256m', '-M:test', '-n', 'yin.vm.ucf.rows-test'],
                   capture_output=True, text=True, timeout=600)
out = p.stdout + p.stderr
open('collab/1792090000000-rows-256m.log', 'w', encoding='utf-8').write(out)
print(out)
print('exit', p.returncode)
