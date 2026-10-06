"""Generate int-ops-v2.txt from the repo root with CPython 3.9.6."""
import math
import operator
import struct
import sys

assert sys.version.split()[0] == "3.9.6", sys.version

# Explicit ownership: no order-dependent slice can omit an operator.
integer_ops = ["truediv", "floordiv", "mod", "divmod"]
float_ops = ["truediv", "floordiv", "mod", "divmod"]
ops = {"truediv": operator.truediv, "floordiv": operator.floordiv,
       "mod": operator.mod, "divmod": divmod, "pow": operator.pow}
values = [0, 1, -1, 3, -3, 2**53-1, 2**53, 2**53+1,
          -2**53-1, 2**63, -2**63, 2**64+1, -2**64-1,
          2**100, -2**100, 2**1074, 2**1075, 10**400,
          True, False]
floats = [float(2**53), float(2**63), 0.5, -0.0,
          math.inf, -math.inf, math.nan]


def encode(v):
    if isinstance(v, tuple):
        return "t:" + ",".join(map(encode, v))
    if isinstance(v, bool):
        return str(v)
    if isinstance(v, float):
        return "f:" + struct.pack(">d", v).hex()
    return str(v)


rows = []


def row(op, a, b):
    try:
        expected = encode(ops[op](a, b))
    except Exception as exc:
        expected = "!" + type(exc).__name__
    rows.append("\t".join([op, encode(a), encode(b), expected]))


for a in values:
    for b in values:
        for op in integer_ops:
            row(op, a, b)
    for b in floats:
        for op in float_ops:
            row(op, a, b)
            row(op, b, a)
for a in floats:
    for b in floats:
        for op in float_ops:
            row(op, a, b)
# Exponent grids are bounded independently: never allocate enormous powers.
for a in [0, 1, -1, 2, -2, 3, 2**53+1, -2**64, True, False]:
    for b in [0, 1, 2, 3, 40, 100, -1, -2, -3, True, False]:
        row("pow", a, b)
for a in [0, 1, -1]:
    for b in [2**64, 2**64+1, -2**64]:
        row("pow", a, b)
    if a != -1:
        row("pow", a, -2**64-1)
# The negative-exponent contract intentionally keeps reciprocal-of-fpow;
# these exact dyadic cases agree with CPython and pin that path.
for a in [0.0, -0.0, 0.5, -0.5, 1.0, -1.0, math.inf,
          -math.inf, math.nan]:
    for b in [0, 1, 2, 3, -1, -2, -3]:
        row("pow", a, b)
# This profile's fpow preserves exact exponent parity. CPython rounds
# an exponent on its float path, so (-1.0)**(2**64+1) and
# (-1)**(-2**64-1) differ. Pin those profile rules in portable tests,
# rather than claiming they are CPython parity rows.
for b in [2**64, 2**64+1, -2**64-1]:
    row("pow", 1.0, b)
row("pow", -1.0, 2**64)
for a in [2, -2, 2**53+1]:
    for b in [0.0, 1.0, 2.0, -1.0, -2.0]:
        row("pow", a, b)
row("pow", 10**400, -1)
row("truediv", 10**400, 10**398)
# A ratio where independently rounded operands give a different answer.
row("truediv", 2**53+1, 2**53+3)

with open("test/resources/yang/python/int-ops-v2.txt", "w") as out:
    out.write("int-ops-v2\nCPython 3.9.6\n\n")
    out.write("\n".join(dict.fromkeys(rows)) + "\n")
