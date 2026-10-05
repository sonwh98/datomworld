"""Generator of test/resources/yin/vm/integer-v3.txt.

Provenance, like yang/python/int-contract-v1.generate.py: run from the
repository root with CPython 3.9.6 to rewrite the fixture. Every row is
computed here by CPython's own float(int), int / int, int-float
comparison and int(float), independently of the Clojure code; the
float exports of yin.vm.integer version 3 must reproduce each row on
every host (test/yin/vm/integer_test.cljc).

Rows, one per line, integers in decimal and floats as the 16 hex digits
of their IEEE-754 bits:

  to-float N BITS|float-overflow
  true-div A B BITS|float-overflow
  compare-float N BITS -1|0|1
  from-float BITS N
"""
import math
import random
import struct
import sys

assert sys.version.split()[0] == "3.9.6", sys.version

OUT = "test/resources/yin/vm/integer-v3.txt"


def bits_hex(x):
    return struct.pack(">d", x).hex()


def float_text(f):
    try:
        return bits_hex(f())
    except OverflowError:
        return "float-overflow"


def next_up(x):
    return struct.unpack(">d", struct.pack(">q", struct.unpack(
        ">q", struct.pack(">d", x))[0] + 1))[0]


def next_down(x):
    return struct.unpack(">d", struct.pack(">q", struct.unpack(
        ">q", struct.pack(">d", x))[0] - 1))[0]


TOP = 2 ** 1024 - 2 ** 970

TO_FLOAT = [0, 1, 2, 3, 7, 10 ** 15, 2 ** 31, 2 ** 32 + 1]
for k in [52, 53, 54, 55, 62, 63, 64, 65, 100, 500, 1000, 1022, 1023]:
    TO_FLOAT += [2 ** k - 1, 2 ** k, 2 ** k + 1]
TO_FLOAT += [
    # ties to even, both directions, at the 53-bit edge
    2 ** 53 + 1, 2 ** 53 + 3, 2 ** 53 + 5,
    2 ** 54 + 2, 2 ** 54 + 6, 2 ** 54 + 1, 2 ** 54 + 3,
    2 ** 63 + 2 ** 10, 2 ** 63 + 3 * 2 ** 10, 2 ** 63 + 2 ** 10 + 1,
    2 ** 64 - 2 ** 11, 2 ** 64 - 2 ** 10, 2 ** 64 - 2 ** 10 - 1,
    # the overflow edge: TOP is the tie above the largest finite float,
    # and an odd mantissa rounds up, so TOP itself overflows
    2 ** 1024 - 2 ** 971, 2 ** 1024 - 2 ** 971 + 1,
    TOP - 1, TOP, TOP + 1, 2 ** 1024 - 1, 2 ** 1024, 2 ** 1025,
    2 ** 2000, 10 ** 308, 10 ** 309, 10 ** 30, 10 ** 400,
    12345678901234567890123456789]
TO_FLOAT += [-n for n in TO_FLOAT if n != 0]

rng = random.Random(20261006)
for _ in range(40):
    n = rng.getrandbits(rng.randrange(54, 1030))
    TO_FLOAT.append(n if rng.random() < 0.5 else -n)

TRUE_DIV = [
    (0, 1), (0, -5), (0, 10 ** 400), (1, 1), (-1, 1), (7, 3), (-7, 3),
    (7, -3), (-7, -3), (1, 3), (2, 3), (1, 10), (10 ** 20, 3),
    (10 ** 400, 10 ** 400), (10 ** 400, 3 * 10 ** 399),
    (-(10 ** 400), 3 * 10 ** 399), (10 ** 400 + 1, 10 ** 400),
    (10 ** 400, 10 ** 400 + 1), (2 ** 1000 + 1, 2 ** 1000),
    (10 ** 309, 10), (10 ** 400, 10 ** 91), (10 ** 400, 10 ** 92),
    (2 ** 1024, 1), (2 ** 1024, 2), (2 ** 1025, 2), (TOP, 1),
    (TOP - 1, 1), (2 * TOP, 2), (2 * TOP - 1, 2), (-(2 ** 1024), 1),
    (1, 2 ** 1022), (1, 2 ** 1023), (1, 2 ** 1074), (1, 2 ** 1075),
    (-1, 2 ** 1075), (1, 2 ** 1075 - 1), (3, 2 ** 1075), (5, 2 ** 1075),
    (3, 2 ** 1076), (5, 2 ** 1076), (7, 2 ** 1076), (1, 3 * 2 ** 1073),
    (2 ** 52 + 1, 2 ** 1074), (-3, 2 ** 1075), (1, 10 ** 400),
    (10 ** 100, 10 ** 420), (2 ** 53 + 1, 1), (2 ** 53 + 1, 2),
    (2 ** 54 + 2, 2), (2 ** 54 + 2, 1), (2 ** 53 + 3, 1),
    (2 ** 64 - 1, 2 ** 64), (2 ** 64 + 1, 2 ** 64),
    (2 ** 53 * 3 + 1, 3), (10 ** 16, 7), (-(10 ** 16), 7)]

for _ in range(40):
    a = rng.getrandbits(rng.randrange(1, 1200))
    b = rng.getrandbits(rng.randrange(1, 1200)) + 1
    TRUE_DIV.append((a if rng.random() < 0.5 else -a,
                     b if rng.random() < 0.5 else -b))

FLOATS = [0.0, -0.0, 0.5, -0.5, 1.0, -1.0, 1.5, -1.5, 2.5,
          float(2 ** 53), next_down(float(2 ** 53)),
          next_up(float(2 ** 53)), 2.0 ** 52 - 0.5,
          float(2 ** 53 - 1), -float(2 ** 53),
          float(2 ** 63), next_down(float(2 ** 63)),
          next_up(float(2 ** 63)), -float(2 ** 63),
          next_down(-float(2 ** 63)), float(2 ** 64),
          1e308, -1e308, 5e-324, -5e-324, 2.0 ** -1022,
          float(2 ** 1023), 1.7976931348623157e308, 123456.75,
          math.inf, -math.inf]

INTS = [0, 1, -1, 2, 123456, 123457, 2 ** 52, 2 ** 53 - 1, 2 ** 53,
        2 ** 53 + 1, 2 ** 53 + 2, -(2 ** 53), -(2 ** 53) - 1,
        2 ** 63 - 1, 2 ** 63, 2 ** 63 + 1, 2 ** 63 - 1024,
        2 ** 63 + 2048, -(2 ** 63), -(2 ** 63) - 1, 2 ** 64,
        2 ** 1023, 2 ** 1024, 10 ** 308, 10 ** 400, -(10 ** 400)]

COMPARE = []
for f in FLOATS:
    near = {0, 1, -1}
    if math.isfinite(f):
        t = int(f)
        near |= {t - 1, t, t + 1}
    for n in sorted(near | set(INTS)):
        COMPARE.append((n, f))


def main():
    out = ["integer-v3", "generator CPython " + sys.version.split()[0]]
    for n in TO_FLOAT:
        out.append("to-float %d %s" % (n, float_text(lambda: float(n))))
    for a, b in TRUE_DIV:
        out.append("true-div %d %d %s" % (a, b, float_text(lambda: a / b)))
    for n, f in COMPARE:
        out.append("compare-float %d %s %d"
                   % (n, bits_hex(f), (n > f) - (n < f)))
    for f in FLOATS:
        if math.isfinite(f):
            out.append("from-float %s %d" % (bits_hex(f), int(f)))
    with open(OUT, "w") as fh:
        fh.write("\n".join(out) + "\n")


main()
