s = open('collab/1792090000000-rename-equivalence.clj').read()
reps = [
    (';; versus renamed codec over the same fixtures in each spelling.',
     ';; versus the cursor-cell codec (cc-N, :cursor-cells) over the same fixtures in each spelling.'),
    ('(re-matches #"cur-(.*)" (name k))', '(re-matches #"cc-(.*)" (name k))'),
    ('(get {:cursors :cells :cursor-body :cell-body} (:kind x) (:kind x))',
     '(get {:cursor-cells :cells :cursor-cell-body :cell-body} (:kind x) (:kind x))'),
    ('(= x :cursors) :cells', '(= x :cursor-cells) :cells'),
    ('(= x :cursor-body) :cell-body', '(= x :cursor-cell-body) :cell-body'),
    ('cur->c', 'cc->c'),
    ('(= :cursor (first x))) [:cell', '(= :cursor-cell (first x))) [:cell'),
    ('[:cursor l]', '[:cursor-cell l]'), ('[:cursor a]', '[:cursor-cell a]'), ('[:cursor b]', '[:cursor-cell b]'),
    ('(vector :cursor %)', '(vector :cursor-cell %)'),
    ('[:cursor :x]', '[:cursor-cell :x]'),
    ('rt/cursor-body', 'rt/cursor-cell-body'),
    ('(cur-N<->c-N)', '(cc-N<->c-N)'),
]
for a, b in reps:
    assert a in s, a
    s = s.replace(a, b)
assert ':cursor ' not in s and 'cur-' not in s, 'leftover'
open('collab/1792110000000-rename-equivalence.clj', 'w').write(s)
