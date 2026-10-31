s = open('collab/1792090000000-rename-mutants.py').read()
reps = [
    ('1792090000000-renamed-rows', '1792110000000-renamed-rows'),
    ('1792090000000-mutant-', '1792110000000-mutant-'),
    ('1792090000000-rename-mutants.json', '1792110000000-rename-mutants.json'),
    ('(keyword? (:yin.k/cursor x))) [:cursor (:yin.k/cursor x)]',
     '(keyword? (:yin.k/cursor-cell x))) [:cursor-cell (:yin.k/cursor-cell x)]'),
    (':noncanonical-cursors', ':noncanonical-cursor-cells'),
]
for a, b in reps:
    assert a in s, a
    s = s.replace(a, b)
assert '1792090000000' not in s
open('collab/1792110000000-rename-mutants.py', 'w').write(s)
