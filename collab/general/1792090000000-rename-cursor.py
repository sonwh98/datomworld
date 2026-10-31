import re, sys

# Ordered: specific spellings first, then the general word.
RULES = [
    (r':yin\.k/c-', ':yin.k/cur-'),
    (r'"c-"', '"cur-"'),
    (r'#"c-\(', '#"cur-('),
    (r'\bc-N\b', 'cur-N'),
    (r'\bc-(9|10)\b', r'cur-\1'),
    (r'response-cell', 'response-cursor'),
    (r'cell-body', 'cursor-body'),
    (r'cursor cells', 'cursors'),
    (r'cells', 'cursors'),
    (r'cell', 'cursor'),
]

for path in sys.argv[1:]:
    with open(path, encoding='utf-8') as fh:
        text = fh.read()
    for pat, rep in RULES:
        text = re.sub(pat, rep, text)
    with open(path, 'w', encoding='utf-8') as fh:
        fh.write(text)
