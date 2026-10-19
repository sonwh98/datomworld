import subprocess, sys

# Usage: python3 -I collab/1792110000000-run.py <log> <cmd...>
log, cmd = sys.argv[1], sys.argv[2:]
p = subprocess.run(cmd, capture_output=True, text=True, timeout=1200)
out = p.stdout + p.stderr
open(log, 'w', encoding='utf-8').write('$ ' + ' '.join(cmd) + '\n' + out + '\nexit ' + str(p.returncode) + '\n')
print(out[-6000:])
print('exit', p.returncode)
