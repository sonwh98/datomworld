"""Generate int-ops-v3.txt from the repo root with CPython 3.9.6."""
import operator
import sys

assert sys.version.split()[0] == "3.9.6", sys.version

binary_ops = ["bitand", "bitor", "bitxor"]
shift_ops = ["lshift", "rshift"]
unary_ops = ["invert"]
ops = {"bitand": operator.and_, "bitor": operator.or_,
       "bitxor": operator.xor, "invert": operator.invert,
       "lshift": operator.lshift, "rshift": operator.rshift}
values = [0, 1, -1, 3, -6, 2**53-1, 2**53, 2**53+1,
          -2**53-1, 2**63, -2**63, 2**64+1, -2**64-1,
          2**100, -2**100, True, False]
counts = [0, 1, 2, 52, 53, 54, 63, 64, 100, 127, -1, -2**64,
          True, False]
rows = []


def row(op, a, b=None):
    try:
        value = ops[op](a) if b is None else ops[op](a, b)
        expected = str(value)
    except Exception as exc:
        expected = "!" + type(exc).__name__
    rows.append("\t".join([op, str(a), "_" if b is None else str(b),
                           expected]))


for a in values:
    for op in unary_ops:
        row(op, a)
    for b in values:
        for op in binary_ops:
            row(op, a, b)
    for b in counts:
        for op in shift_ops:
            row(op, a, b)
    # Huge right counts allocate nothing, regardless of sign.
    for b in [2**64, 2**100]:
        row("rshift", a, b)
# CPython's huge nonzero left shifts are allocation failures; the profile
# enforces its own bit budget, pinned separately rather than allocating.
for b in [2**64, 2**100]:
    row("lshift", 0, b)

with open("test/resources/yang/python/int-ops-v3.txt", "w") as out:
    out.write("int-ops-v3\nCPython 3.9.6\n\n")
    out.write("\n".join(dict.fromkeys(rows)) + "\n")
