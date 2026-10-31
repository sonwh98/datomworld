import re, sys

# Applied to the PRE-RENAME originals (collab/1792090000000-backup-*.orig),
# where every "cell" denotes the carrier's box (no heap cells in these files).
# Ordered: specific spellings first, then the general word. Prose sites get
# "cursor-cell" here and are hand-fixed to "cursor cell" where a noun is meant.
RULES = [
    (r':yin\.k/c-', ':yin.k/cc-'),
    (r'"c-"', '"cc-"'),
    (r'#"c-\(', '#"cc-('),
    (r'\bc-N\b', 'cc-N'),
    (r'(?<!\w)cell', 'cursor-cell'),
]

src, dst = sys.argv[1], sys.argv[2]
with open(src, encoding='utf-8') as fh:
    text = fh.read()
for pat, rep in RULES:
    text = re.sub(pat, rep, text)
with open(dst, 'w', encoding='utf-8') as fh:
    fh.write(text)
