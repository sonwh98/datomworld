from pathlib import Path
import subprocess

cases = [
    ('refusal-as-error', Path('src/cljc/yin/vm/engine.cljc'),
     '(if (:try? entry)\n    (ready entry (cond-> refusal',
     '(if false\n    (ready entry (cond-> refusal'),
    ('success-as-symbol', Path('src/cljc/yin/vm/engine.cljc'),
     '(ready entry (if (:try? entry)\n                 {:status :ok, :module module-name}',
     '(ready entry (if false\n                 {:status :ok, :module module-name}'),
    ('cycle-throws', Path('src/cljc/yin/vm/module.cljc'),
     '(if try?\n          {:value (assoc refusal :status :refused)',
     '(if false\n          {:value (assoc refusal :status :refused)')]

for name, path, before, after in cases:
    original = path.read_text()
    assert original.count(before) == 1, name
    try:
        path.write_text(original.replace(before, after))
        with Path(f'collab/ld1-mutant-{name}.log').open('w') as log:
            result = subprocess.run(
                ['clojure', '-M:test', '-n', 'yin.vm.linker-require-test'],
                stdout=log, stderr=subprocess.STDOUT)
        print(f'{name}: exit {result.returncode}', flush=True)
        assert result.returncode != 0, f'Surviving mutant: {name}'
    finally:
        path.write_text(original)
