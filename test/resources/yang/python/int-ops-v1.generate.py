"""Generate int-ops-v1.txt from the repository root with CPython 3.9.6."""
import math
import operator
import sys
import struct

assert sys.version.split()[0] == "3.9.6", sys.version

# The S0 list, written independently of the implementation under test.
values = [0, 1, -1, 2**31-1, 2**31, 2**31+1, 2**32,
          2**53-1, 2**53, 2**53+1, -2**53+1, -2**53, -2**53-1,
          2**62, 2**63-1, 2**63, 2**63+1, -2**63+1, -2**63, -2**63-1,
          2**64-1, 2**64, 2**64+1, -2**64+1, -2**64, -2**64-1,
          2**100, 10**30, 2**1074, 2**1075, 2**2000, True, False]
ops = {"add": operator.add, "sub": operator.sub, "mul": operator.mul,
       "neg": operator.neg, "lt": operator.lt, "le": operator.le,
       "gt": operator.gt, "ge": operator.ge, "eq": operator.eq,
       "ne": operator.ne, "truediv": operator.truediv}


def encode(v):
    if isinstance(v, bool):
        return str(v)
    if isinstance(v, float):
        return "f:" + struct.pack(">d", v).hex()
    return str(v)


rows = []


def row(op, a, b=None):
    try:
        result = ops[op](a) if b is None else ops[op](a, b)
        expected = encode(result)
    except Exception as exc:
        expected = "!" + type(exc).__name__
    fields = [op, encode(a), "_" if b is None else encode(b), expected]
    rows.append("\t".join(fields))


for a in values:
    row("neg", a)
    # Neighbours, cancellation, promotion and boolean coercion.
    for b in [0, 1, -1, a, -a, True, False]:
        for op in list(ops)[:10]:
            if op != "neg":
                row(op, a, b)
    for b in [float(2**53), float(2**63), 0.5, -0.0,
              math.inf, -math.inf, math.nan]:
        for op in ops:
            if op != "neg":
                row(op, a, b)
                row(op, b, a)
for a in [float(2**53), float(2**63), 0.5, -0.0,
          math.inf, -math.inf, math.nan]:
    row("neg", a)
row("add", 10**400, 1.5)
# Repeat guards are pinned separately: do not attempt a huge allocation.
with open("test/resources/yang/python/int-ops-v1.txt", "w") as out:
    out.write("int-ops-v1\nCPython 3.9.6\n\n")
    out.write("\n".join(dict.fromkeys(rows)) + "\n")
