import sys

path = sys.argv[1]
depth = 0
line_no = 1
in_str = False
in_comment = False
prev = ""
starts = []
with open(path) as f:
    while True:
        ch = f.read(1)
        if not ch:
            break
        if ch == "\n":
            line_no += 1
            in_comment = False
            prev = ch
            continue
        if in_comment:
            prev = ch
            continue
        if in_str:
            if ch == '"' and prev != "\\":
                in_str = False
            prev = ch
            continue
        if ch == '"':
            in_str = True
            prev = ch
            continue
        if ch == ";" and prev != "\\":
            in_comment = True
            prev = ch
            continue
        if ch == "(" or ch == "[" or ch == "{":
            if depth == 0:
                starts.append((line_no, ch))
            depth += 1
        elif ch == ")" or ch == "]" or ch == "}":
            depth -= 1
            if depth < 0:
                print(f"UNBALANCED: extra closer at line {line_no}")
                sys.exit(1)
            if depth == 0:
                starts.pop()
        prev = ch
print(f"final depth {depth}")
for s in starts[:5]:
    print("unclosed top-level form starting at line", s[0], s[1])
