"""Generate float-text-v1.txt from the repo root with CPython 3.9.6.

repr_float rows: f:<16 hex bits> -> repr text.
float_str rows: text as comma-separated hex code points -> f:<bits>, or
!Class<TAB>message for a refused string. Floats are bit patterns, so no
row passes through a host's float reader.
"""
import random
import struct
import sys
from decimal import Decimal, getcontext

assert sys.version.split()[0] == "3.9.6", sys.version

getcontext().prec = 2000
ops = ["repr_float", "float_str"]
rows = []


def bits(x):
    return struct.pack(">d", x).hex()


def from_bits(n):
    return struct.unpack(">d", struct.pack(">Q", n))[0]


def to_bits(x):
    return struct.unpack(">Q", struct.pack(">d", x))[0]


def code_points(s):
    return ",".join("%x" % ord(c) for c in s)


def repr_row(x):
    rows.append("\t".join(["repr_float", "f:" + bits(x), "_", repr(x)]))


def str_row(s):
    try:
        expected = "f:" + bits(float(s))
    except Exception as exc:
        message = str(exc)
        assert message.isascii() and "\t" not in message, message
        expected = "!" + type(exc).__name__ + "\t" + message
    rows.append("\t".join(["float_str", code_points(s), "_", expected]))


MAX_BITS = 0x7fefffffffffffff


def neighbors(n):
    """Bit patterns n - 1, n, n + 1 that are positive and finite."""
    return [m for m in (n - 1, n, n + 1) if 0 < m <= MAX_BITS]


# ------------------------------------------------------------- repr_float
named = [0.1, 0.2, 0.3, 0.1 + 0.2, 1 / 3, 2 / 3, 0.5, 1.5, 2.5, 100.0,
         1e22, 1e23, 1e16, 9999999999999998.0, 1e15, 123456789012345.6,
         1234567890123456.7, 9007199254740993.0, 9007199254740992.0,
         0.0001, 0.00001, 0.00012345, 0.000099999, 1e-05, 1e-07,
         12345678.5, 1.23456785e7, 123456789.123, 3.14159, 2.718281828459045,
         1.7976931348623157e308, 2.2250738585072014e-308,
         2.225073858507201e-308, 5e-324, 1e-323, 1.5e300, 4.35, 0.07,
         1.1, 2.675, 1e-320, 1.2345e-310, 6.02214076e23, 299792458.0,
         1e100, 1e-100, 1e21, 1e-7, 0.000123]
for x in [0.0, -0.0, float("inf"), float("-inf"), float("nan")]:
    repr_row(x)
for x in named:
    repr_row(x)
    repr_row(-x)
for n in [1, 0x000fffffffffffff, 0x0010000000000000, MAX_BITS]:
    repr_row(from_bits(n))
# every power of two and both sides of every binade boundary
for k in range(-1074, 1024):
    for n in neighbors(to_bits(2.0 ** k)):
        repr_row(from_bits(n))
# powers of ten across the range, with their neighbours
for k in range(-323, 309):
    for n in neighbors(to_bits(float("1e%d" % k))):
        repr_row(from_bits(n))
# both notation thresholds, integral and not
for k in range(14, 18):
    for d in [1, 9, 99, 12345]:
        x = float(10 ** k - d)
        repr_row(x)
        repr_row(x + 0.5)
for x in [0.0001, 0.00009999999999999999, 0.001, 0.000999]:
    for n in neighbors(to_bits(x)):
        repr_row(from_bits(n))
rng = random.Random(20261006)
for _ in range(600):
    repr_row(from_bits(rng.randrange(1, MAX_BITS + 1)))
for _ in range(300):
    repr_row(rng.randrange(1, 10 ** rng.randrange(1, 17))
             / 10 ** rng.randrange(0, 25))

# --------------------------------------------------------------- float_str
for x in named:
    str_row(repr(x))
    str_row("%.17e" % x)
    str_row("%.25e" % x)
for _ in range(200):
    x = from_bits(rng.randrange(1, MAX_BITS + 1))
    str_row(repr(x))
    str_row("%.30e" % x)


def exact(x):
    return Decimal(x)


def plain(d):
    """Decimal d as plain digits with an exponent, no rounding."""
    sign, digits, exp = d.as_tuple()
    text = "".join(map(str, digits))
    return ("-" if sign else "") + text + "e" + str(exp)


# halfway cases: the exact midpoint (ties to even), and a hair either side
for lo in [1, 2, 0x000ffffffffffffe, 0x000fffffffffffff, 0x0010000000000000,
           to_bits(1.0), to_bits(1.0) - 1, to_bits(9007199254740992.0),
           to_bits(1e23), to_bits(0.1), MAX_BITS - 1, to_bits(2.0 ** -1000),
           to_bits(1e300)]:
    a, b = exact(from_bits(lo)), exact(from_bits(lo + 1))
    mid = (a + b) / 2
    eps = (b - a) / Decimal(10 ** 12)
    for d in [mid, mid - eps, mid + eps]:
        str_row(plain(d))
    str_row(str(mid))
# half the least subnormal: a tie that rounds to zero; past it, up
half = exact(from_bits(1)) / 2
str_row(str(half))
str_row(plain(half))
# 800 and more significant digits: the midpoint padded with zeros (a tie)
# and with a final 1 (above the tie), where only a sticky digit decides
for lo in [1, 0x000ffffffffffffe, 0x0010000000000000]:
    a, b = exact(from_bits(lo)), exact(from_bits(lo + 1))
    mid = (a + b) / 2
    sign, digits, exp = mid.as_tuple()
    text = "".join(map(str, digits))
    for tail in ["0" * 300, "0" * 300 + "1", "0" * 120]:
        str_row(text + tail + "e" + str(exp - len(tail)))
str_row("1" + "0" * 900 + "e-900")
str_row("0." + "0" * 1000 + "1e1000")
str_row("9" * 850)
str_row("9" * 850 + "e-850")
# the overflow edge: 2^1024 - 2^970 is the tie that rounds to inf
edge = Decimal(2 ** 1024 - 2 ** 970)
str_row(str(int(edge)))
str_row(str(int(edge) - 1))
str_row(plain(edge - Decimal(1) / 10 ** 30))
for s in ["1e400", "-1e400", "1e-400", "-1e-400", "1e308", "1e309", "1e310",
          "1e311", "0.1e311", "100e308", "1.7976931348623157e308",
          "1.7976931348623158e308", "1.7976931348623159e308", "1e-323",
          "1e-324", "1e-325", "1e-326", "1e-327", "2.4703282292062327e-324",
          "2.4703282292062328e-324", "4.9406564584124654e-324",
          "2.2250738585072011e-308", "2.2250738585072012e-308",
          "1e99999999999999999999", "-1e99999999999999999999",
          "1e-99999999999999999999", "0e99999999999999999999",
          "123456789e-99999999999999999999", "0.000001e315",
          "10000000000000000000000000000000e-340", "9007199254740993",
          "9007199254740995", "9007199254740993.000000000000000001",
          "0.1", "0.3", "1e22", "1e23", "8.98846567431158e307",
          # signed zero
          "0", "-0", "+0.0", "-0.0", "0e0", "-0e5", "0e-400", "-0e400",
          "00000.000", "-.0e5", "0.", "-0.e-0",
          # leading zeros and short forms
          "000123", "00.5e-0001", ".5", "5.", "-.5", "+5.", "1E5", "1e+5",
          "1e-5", "1E-05", "123.456e2", "0.000000000000000000001e21",
          # underscores
          "1_000.5", "1_0e1_0", ".5_5", "1_000_000", "0_0.0_0", "1.5_0e-0_1",
          # whitespace
          " 1.5 ", "\t\n1.5\x0b\x0c\r", "\u30001.5\xa0", "\x1c1\x1f",
          "\x851.5\u2028", "\u20002.5\u200a", "\u2029\u202f3\u205f",
          "\u16801\u3000", "\x1d\x1e7", "\x1c", "\x85", "\u3000", " \t ",
          "1\x1c", " 1\x85",
          # inf and nan
          "inf", "-inf", "+INF", "Infinity", "-iNfInItY", "nan", "-nan",
          "+NaN", " nan ", " -Infinity\n",
          # malformed
          "", " ", ".", "1e", "e1", "1e+", "1e-", "+", "-", "1_.0", "1._0",
          "_1", "1_", "1__0", "1e_1", "1e1_", "1_e1", "1e1__0", "_",
          "0x1p3", "0x10", "inf1", "infinit", "infinityy", "nana", "na n",
          "in f", "1.5.5", "1,5", "1 5", "--1", "+-1", "-+1", "1.0j", "1d",
          "1f", "\x00", "1e1.5", ".e1", "+.", "-.e5", " . ", "1\x00",
          "\x0b", "1e+-1", "1ee1", ". 5", "1 .5", "e", "E5", "1e 5",
          "\u200b1", "1\u200b", "'1'", "1.5\n2"]:
    str_row(s)

with open("test/resources/yang/python/float-text-v1.txt", "w") as out:
    assert all(op in ops for op in (r.split("\t")[0] for r in rows))
    out.write("float-text-v1\nCPython 3.9.6\n\n")
    out.write("\n".join(dict.fromkeys(rows)) + "\n")
